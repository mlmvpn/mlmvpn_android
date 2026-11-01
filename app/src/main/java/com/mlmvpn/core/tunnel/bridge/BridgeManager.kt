package com.mlmvpn.core.tunnel.bridge

import android.content.Context
import android.util.Log
import com.mlmvpn.core.tunnel.TorBridges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tor's bridges: where they come from, how they are doing, and which ones Tor is given.
 *
 * It sits UNDER TorManager rather than beside it. TorManager still owns the process, the torrc
 * and the connection ladder; it only asks here for the bridge lines of the transport it is about
 * to try ([linesFor]) and reports back which bridges really carried a bootstrap ([noteTorUsed]).
 * Everything else -- fetching, merging, testing, placing, pruning -- happens in the background and
 * never on a connect's critical path: a connect uses what is already here.
 *
 * Trust, kept explicit:
 *  - official (Tor Project's Moat) and community sources stay distinguishable on every bridge;
 *  - nothing is used just because a list said so: a bridge is ranked by what was measured from this
 *    line, and a bridge Tor actually bootstrapped through outranks everything;
 *  - a bridge no source lists any more, or one that keeps failing, is dropped, not kept forever.
 */
object BridgeManager {

    private const val TAG = "TorBridges"
    const val CHOICE_PREF = "tor_bridge_choice"
    /** The one bridge the user picked by hand, by id; absent = the automatic choice. */
    const val PINNED_PREF = "tor_bridge_pinned"
    /** The list shipped in the APK, so the first run already has bridges. Not a fetched source. */
    const val SEED_SOURCE = "seed"
    private const val SEED_ASSET = "tor_bridges_seed.txt"

    /** Where Tor's bridges come from. */
    enum class Choice(val key: String) {
        /** The best tested bridges from every source, with the built-in ones as a backstop. */
        AUTO("auto"),
        /** Only the user's own. */
        MINE("mine"),
        /** Only the lines built into the app (the behaviour before any of this existed). */
        BUILTIN("builtin");

        companion object { fun from(k: String?) = entries.firstOrNull { it.key == k } ?: AUTO }
    }

    enum class Status { UNTESTED, OK, FAILED, WAS_OK, NEEDS_IPV6 }

    data class Entry(
        val bridge: TorBridge,
        val sources: Set<String>,
        val official: Boolean,
        val custom: Boolean,
        val firstSeen: Long,
        /** Last time any source still listed it (custom ones: when added). */
        val lastListed: Long,
        val testedAt: Long = 0L,
        val ok: Boolean? = null,
        val rttMs: Long? = null,
        val failStreak: Int = 0,
        val lastOkAt: Long = 0L,
        val needsV6: Boolean = false,
        /** ISO code, or null for unknown. [geoDone] says whether it was looked for. */
        val country: String? = null,
        val geoDone: Boolean = false,
        /** Last time Tor bootstrapped with this bridge among its bridges and fetched its descriptor. */
        val torOkAt: Long = 0L,
    ) {
        val status: Status
            get() = when {
                needsV6 -> Status.NEEDS_IPV6
                ok == null -> Status.UNTESTED
                ok == true -> Status.OK
                lastOkAt > 0 -> Status.WAS_OK
                else -> Status.FAILED
            }
    }

    data class SourceState(
        val id: String,
        val fetchedAt: Long = 0L,
        val changedAt: Long = 0L,
        val ok: Boolean = false,
        val error: String? = null,
        val count: Int = 0,
    )

    data class State(
        val entries: List<Entry> = emptyList(),
        val sources: Map<String, SourceState> = emptyMap(),
        val refreshing: Boolean = false,
        val testing: Boolean = false,
        val testDone: Int = 0,
        val testTotal: Int = 0,
        val lastRefreshAt: Long = 0L,
        /** Bridges the user deleted, by id → when. A source listing one again does not bring it back for [HIDE_MS]. */
        val removed: Map<String, Long> = emptyMap(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private lateinit var app: Context
    @Volatile private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private fun file(ctx: Context) = File(ctx.filesDir, "tor_bridges/state.json").also { it.parentFile?.mkdirs() }

    /** Whether the stored bridges have been read in; until then a screen shows what it has. */
    val ready: Boolean get() = loaded

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        app = ctx.applicationContext
        _state.value = load() ?: seed()
        loaded = true
    }

    /** First run (or an unreadable state file): the bridges that answered from Iran when the APK was built. */
    private fun seed(): State {
        val now = System.currentTimeMillis()
        val lines = runCatching { app.assets.open(SEED_ASSET).bufferedReader().readLines() }.getOrDefault(emptyList())
        val entries = lines.mapNotNull { (BridgeLines.parse(it) as? BridgeParse.Ok)?.bridge }
            .distinctBy { it.id }
            .map { Entry(it, setOf(SEED_SOURCE), official = false, custom = false, firstSeen = now, lastListed = now) }
        return State(entries = entries)
    }

    // ── the choice ─────────────────────────────────────────────────────────────────────────────

    fun choice(ctx: Context): Choice =
        Choice.from(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(CHOICE_PREF, Choice.AUTO.key))

    fun setChoice(ctx: Context, c: Choice) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(CHOICE_PREF, c.key).apply()

    // ── one bridge picked by hand ──────────────────────────────────────────────────────────────

    /** The bridge the user picked, if it is still here. */
    fun pinned(ctx: Context): Entry? {
        init(ctx)
        val id = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(PINNED_PREF, null) ?: return null
        return _state.value.entries.firstOrNull { it.bridge.id == id }
    }

    /** Pins [id] (Tor then connects through that bridge only), or with null goes back to automatic. */
    fun setPinned(ctx: Context, id: String?) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().apply {
            if (id == null) remove(PINNED_PREF) else putString(PINNED_PREF, id)
        }.apply()

    // ── what TorManager is given ───────────────────────────────────────────────────────────────

    /**
     * The bridge lines for [transport] for the next attempt, best first. Never touches the network.
     *
     * [Choice.AUTO]: the best of what is here, ranked by evidence -- a bootstrap through it, then a
     * reachable test with a low round trip, then untested fresh ones; official before community on
     * a tie. The built-in lines are added behind them for meek and snowflake (whose rendezvous the
     * collectors do not list) and used alone when nothing else is known for the transport.
     * [Choice.MINE]: the user's own, untouched. [Choice.BUILTIN]: the app's own lists.
     */
    fun linesFor(ctx: Context, transport: BridgeTransport, max: Int = defaultMax(transport)): List<String> {
        init(ctx)
        // A bridge picked by hand is exactly that: only it, whatever the choice below.
        pinned(ctx)?.let { if (it.bridge.transport == transport) return listOf(it.bridge.line) }
        val builtin = builtinFor(transport)
        return when (choice(ctx)) {
            Choice.BUILTIN -> builtin
            Choice.MINE -> _state.value.entries.filter { it.custom && it.bridge.transport == transport }.map { it.bridge.line }
            Choice.AUTO -> {
                val ranked = _state.value.entries
                    .filter { it.bridge.transport == transport && it.status != Status.NEEDS_IPV6 }
                    .filter { it.status != Status.FAILED || it.custom }
                    .sortedByDescending { score(it) }
                    .map { it.bridge.line }
                    .take(max)
                when {
                    ranked.isEmpty() -> builtin
                    transport == BridgeTransport.MEEK || transport == BridgeTransport.SNOWFLAKE -> (ranked + builtin).distinct().take(max + builtin.size)
                    else -> ranked
                }
            }
        }
    }

    /** [Choice.AUTO]'s lines, whatever the tile is set to: MLM Scout tests «پل‌ها: خودکار» as such. */
    fun autoLinesFor(ctx: Context, transport: BridgeTransport, max: Int = defaultMax(transport)): List<String> {
        init(ctx)
        val builtin = builtinFor(transport)
        val ranked = _state.value.entries
            .filter { it.bridge.transport == transport && it.status != Status.NEEDS_IPV6 }
            .filter { it.status != Status.FAILED || it.custom }
            .sortedByDescending { score(it) }
            .map { it.bridge.line }
            .take(max)
        return if (ranked.isEmpty()) builtin else ranked
    }

    private fun defaultMax(t: BridgeTransport) = when (t) {
        // Tor dials every configured bridge; a handful of good ones beats dozens of maybes.
        BridgeTransport.OBFS4, BridgeTransport.VANILLA -> 8
        BridgeTransport.WEBTUNNEL -> 6
        BridgeTransport.SNOWFLAKE, BridgeTransport.MEEK -> 2
    }

    private fun builtinFor(t: BridgeTransport): List<String> = when (t) {
        BridgeTransport.OBFS4 -> TorBridges.OBFS4
        BridgeTransport.MEEK -> TorBridges.MEEK
        BridgeTransport.SNOWFLAKE -> TorBridges.SNOWFLAKE
        else -> emptyList()
    }

    private fun score(e: Entry): Double {
        val now = System.currentTimeMillis()
        var s = 0.0
        if (now - e.torOkAt < 7 * DAY) s += 1000
        when (e.status) {
            Status.OK -> s += 500 - ((e.rttMs ?: 1500).coerceAtMost(2500) / 5.0)
            Status.UNTESTED -> s += 120
            Status.WAS_OK -> s += 60
            else -> Unit
        }
        if (e.official) s += 80
        if (e.custom) s += 40
        s += e.sources.size * 10
        if (now - e.lastListed < DAY) s += 30
        s -= e.failStreak * 150
        return s
    }

    /**
     * The bridge this Tor session came in through: the hand-picked one, or else the first whose
     * descriptor arrived during the bootstrap. Null between sessions or for a built-in line that
     * is not in the list. The connected screen shows its country beside the exit's.
     */
    private val _sessionBridge = MutableStateFlow<String?>(null)
    val sessionBridge: StateFlow<String?> = _sessionBridge

    /** A new attempt starts: forget the last session's bridge, or name the pinned one up front. */
    fun noteSessionStart(ctx: Context, transport: BridgeTransport?) {
        _sessionBridge.value = runCatching { pinned(ctx)?.takeIf { it.bridge.transport == transport }?.bridge?.id }.getOrNull()
    }

    /**
     * Tor fetched a bridge's descriptor during a bootstrap: that bridge really works from here.
     * Called by TorManager from the line `new bridge descriptor '...' (fresh): $FP~...`.
     */
    fun noteTorUsed(fingerprint: String) {
        if (!loaded) return
        val fp = fingerprint.uppercase()
        val now = System.currentTimeMillis()
        var hit = false
        _state.value = _state.value.copy(entries = _state.value.entries.map {
            if (it.bridge.fingerprint == fp) { hit = true; it.copy(torOkAt = now, ok = true, lastOkAt = now, failStreak = 0) } else it
        })
        if (hit) {
            if (_sessionBridge.value == null) _sessionBridge.value = _state.value.entries.firstOrNull { it.bridge.fingerprint == fp }?.bridge?.id
            scope.launch { lock.withLock { save() } }
        }
    }

    // ── the user's own ─────────────────────────────────────────────────────────────────────────

    data class AddResult(val added: Int, val duplicates: Int, val bad: List<BridgeParse.Bad>)

    fun addCustom(ctx: Context, text: String): AddResult {
        init(ctx)
        val parsed = BridgeLines.parseAll(text)
        val now = System.currentTimeMillis()
        val current = _state.value.entries.associateBy { it.bridge.id }.toMutableMap()
        var added = 0; var dup = 0
        for (p in parsed.filterIsInstance<BridgeParse.Ok>()) {
            val old = current[p.bridge.id]
            if (old != null) {
                dup++
                if (!old.custom) current[p.bridge.id] = old.copy(custom = true)
                continue
            }
            current[p.bridge.id] = Entry(p.bridge, emptySet(), official = false, custom = true, firstSeen = now, lastListed = now)
            added++
        }
        _state.value = _state.value.copy(entries = current.values.toList())
        scope.launch {
            lock.withLock { save() }
            if (added > 0) { geolocate(); testAll(onlyCustom = true) }
        }
        return AddResult(added, dup, parsed.filterIsInstance<BridgeParse.Bad>())
    }

    /** Deletes bridges; a source listing them again does not bring them back for a month. */
    fun remove(ctx: Context, ids: Set<String>): Int {
        init(ctx)
        val now = System.currentTimeMillis()
        val gone = _state.value.entries.filter { it.bridge.id in ids }
        if (gone.isEmpty()) return 0
        _state.value = _state.value.copy(
            entries = _state.value.entries.filterNot { it.bridge.id in ids },
            removed = _state.value.removed + gone.filter { it.sources.isNotEmpty() }.associate { it.bridge.id to now },
        )
        val pin = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(PINNED_PREF, null)
        if (pin != null && pin in ids) setPinned(ctx, null)
        scope.launch { lock.withLock { save() } }
        return gone.size
    }

    /** The bridges that did not answer their last test. */
    fun deadIds(): Set<String> = _state.value.entries
        .filter { it.status == Status.FAILED || it.status == Status.WAS_OK }
        .map { it.bridge.id }.toSet()

    // ── refresh ────────────────────────────────────────────────────────────────────────────────

    /** Fetches every source that is due (all of them with [force]), then places and tests what is new. */
    fun refresh(ctx: Context, force: Boolean = false) {
        init(ctx)
        if (_state.value.refreshing) return
        scope.launch {
            _state.value = _state.value.copy(refreshing = true)
            try {
                val changed = lock.withLock { fetchDue(force) }
                geolocate()
                if (changed || force) testAll(onlyDue = true)
            } catch (e: Exception) {
                Log.w(TAG, "refresh: ${e.javaClass.simpleName}")
            } finally {
                _state.value = _state.value.copy(refreshing = false)
            }
        }
    }

    /** Cheap to call often (screen opened, Tor connecting): only due sources are asked. */
    fun refreshIfDue(ctx: Context) {
        init(ctx)
        val now = System.currentTimeMillis()
        val due = BridgeSources.ALL.any { s -> now - (_state.value.sources[s.id]?.fetchedAt ?: 0L) >= s.everyMs }
        if (due) refresh(ctx)
    }

    /** Scout owns measurement traffic: fetch due lists without launching background bridge tests. */
    suspend fun refreshForProbe(ctx: Context) {
        init(ctx)
        if (choice(ctx) == Choice.AUTO) lock.withLock { fetchDue(force = false) }
    }

    private suspend fun fetchDue(force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val cc = countryCode()
        val todo = BridgeSources.ALL.filter { s -> force || now - (_state.value.sources[s.id]?.fetchedAt ?: 0L) >= s.everyMs }
        if (todo.isEmpty()) return false
        // Each source on its own: one that fails costs only its own fresh lines.
        val results = kotlinx.coroutines.coroutineScope {
            todo.map { s -> async(Dispatchers.IO) { s to runCatching { BridgeSources.fetch(app, s, cc) }.getOrElse { BridgeSources.Fetch(null, false, it.message) } } }.awaitAll()
        }
        val byId = _state.value.entries.associateBy { it.bridge.id }.toMutableMap()
        val removed = _state.value.removed.filterValues { now - it < HIDE_MS }
        val sources = _state.value.sources.toMutableMap()
        var changed = false
        for ((s, f) in results) {
            val prev = sources[s.id] ?: SourceState(s.id)
            if (f.lines == null) {
                sources[s.id] = prev.copy(fetchedAt = now, ok = false, error = f.error)
                continue
            }
            var count = 0
            for (raw in f.lines) {
                val p = BridgeLines.parse(raw) as? BridgeParse.Ok ?: continue
                count++
                if (p.bridge.id in removed) continue
                val old = byId[p.bridge.id]
                byId[p.bridge.id] = if (old == null) {
                    changed = true
                    Entry(p.bridge, setOf(s.id), s.official, custom = false, firstSeen = now, lastListed = now)
                } else {
                    old.copy(sources = old.sources + s.id, official = old.official || s.official, lastListed = now)
                }
            }
            sources[s.id] = prev.copy(fetchedAt = now, ok = true, error = f.error, count = count, changedAt = if (f.changed) now else prev.changedAt)
            if (f.changed) changed = true
        }
        _state.value = _state.value.copy(entries = prune(byId.values.toList(), now), sources = sources, lastRefreshAt = now, removed = removed)
        save()
        return changed
    }

    /**
     * The list grows with every refresh; what goes on its own is only what is plainly dead: failing
     * again and again with no success in three days, or gone from every source for two weeks
     * without answering lately. The user's own, and any bridge Tor or a test found working in the
     * last week, always stay. [MAX_ENTRIES] is only a guard against a runaway source.
     */
    private fun prune(list: List<Entry>, now: Long): List<Entry> {
        val kept = list.filter { e ->
            e.custom || now - e.torOkAt < 14 * DAY || now - e.lastOkAt < 7 * DAY || (
                now - e.lastListed < 14 * DAY &&
                    !(e.failStreak >= 4 && now - e.lastOkAt > 3 * DAY))
        }
        val (mine, listed) = kept.partition { it.custom }
        return mine + listed.sortedByDescending { score(it) }.take(MAX_ENTRIES)
    }

    private fun countryCode(): String = runCatching {
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        tm.networkCountryIso?.takeIf { it.length == 2 } ?: tm.simCountryIso?.takeIf { it.length == 2 }
    }.getOrNull() ?: "ir"

    // ── places ─────────────────────────────────────────────────────────────────────────────────

    private suspend fun geolocate() {
        val todo = _state.value.entries.filter { !it.geoDone }
        if (todo.isEmpty()) return
        // Hostnames (webtunnel) are resolved a few at a time; addresses cost nothing.
        val gate = Semaphore(12)
        val addr = kotlinx.coroutines.coroutineScope {
            todo.map { e -> async(Dispatchers.IO) { gate.withPermit { e.bridge.id to BridgeGeo.addressOf(e.bridge) } } }.awaitAll()
        }.toMap()
        val cc = BridgeGeo.countries(app, addr.values.filterNotNull().toSet())
        lock.withLock {
            _state.value = _state.value.copy(entries = _state.value.entries.map { e ->
                if (e.geoDone || e.bridge.id !in addr) e else e.copy(country = addr[e.bridge.id]?.let { cc[it] }, geoDone = true)
            })
            save()
        }
    }

    // ── tests ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Tests in the background. [onlyDue]: what was never tested or not for six hours (at most
     * [AUTO_TEST_MAX] of them, best first). [transport] / [onlyCustom] narrow it. The screen sees
     * progress as it goes; results are written once at the end.
     */
    fun testAll(ctx: Context, transport: BridgeTransport? = null) {
        init(ctx)
        scope.launch { testAll(transport = transport) }
    }

    fun testOne(ctx: Context, id: String) {
        init(ctx)
        scope.launch {
            val e = _state.value.entries.firstOrNull { it.bridge.id == id } ?: return@launch
            val r = BridgeTester.test(e.bridge, com.mlmvpn.scanner.data.CfFamily.hasIpv6Route())
            applyResult(e.bridge.id, r)
            lock.withLock { save() }
        }
    }

    private suspend fun testAll(transport: BridgeTransport? = null, onlyDue: Boolean = false, onlyCustom: Boolean = false) {
        if (_state.value.testing) return
        val now = System.currentTimeMillis()
        var list = _state.value.entries
            .filter { transport == null || it.bridge.transport == transport }
            .filter { !onlyCustom || it.custom }
        if (onlyDue) list = list.filter { now - it.testedAt > 6 * 3600_000L }.sortedByDescending { score(it) }.take(AUTO_TEST_MAX)
        if (list.isEmpty()) return
        _state.value = _state.value.copy(testing = true, testDone = 0, testTotal = list.size)
        try {
            val v6 = com.mlmvpn.scanner.data.CfFamily.hasIpv6Route()
            BridgeTester.testAll(list.map { it.bridge }, v6) { b, r ->
                applyResult(b.id, r)
                _state.value = _state.value.copy(testDone = _state.value.testDone + 1)
            }
        } finally {
            _state.value = _state.value.copy(testing = false)
            lock.withLock { save() }
        }
    }

    @Synchronized
    private fun applyResult(id: String, r: BridgeTester.Result) {
        val now = System.currentTimeMillis()
        _state.value = _state.value.copy(entries = _state.value.entries.map { e ->
            if (e.bridge.id != id) e else when (r.outcome) {
                BridgeTester.Outcome.OK -> e.copy(testedAt = now, ok = true, rttMs = r.rttMs, failStreak = 0, lastOkAt = now, needsV6 = false)
                BridgeTester.Outcome.FAILED -> e.copy(testedAt = now, ok = false, rttMs = null, failStreak = e.failStreak + 1, needsV6 = false)
                BridgeTester.Outcome.NEEDS_IPV6 -> e.copy(testedAt = now, needsV6 = true)
            }
        })
    }

    // ── storage ────────────────────────────────────────────────────────────────────────────────

    private fun save() {
        val s = _state.value
        val o = JSONObject().put("v", 1).put("refreshed", s.lastRefreshAt)
        o.put("bridges", JSONArray().apply {
            s.entries.forEach { e ->
                put(JSONObject()
                    .put("line", e.bridge.line).put("src", JSONArray(e.sources.toList())).put("off", e.official).put("mine", e.custom)
                    .put("first", e.firstSeen).put("listed", e.lastListed).put("tAt", e.testedAt)
                    .put("ok", e.ok ?: JSONObject.NULL).put("rtt", e.rttMs ?: -1).put("fail", e.failStreak).put("okAt", e.lastOkAt)
                    .put("v6", e.needsV6).put("cc", e.country ?: JSONObject.NULL).put("geo", e.geoDone).put("tor", e.torOkAt))
            }
        })
        o.put("sources", JSONObject().apply {
            s.sources.values.forEach { x ->
                put(x.id, JSONObject().put("at", x.fetchedAt).put("chg", x.changedAt).put("ok", x.ok)
                    .put("err", x.error ?: JSONObject.NULL).put("n", x.count))
            }
        })
        o.put("removed", JSONObject().apply { s.removed.forEach { (k, v) -> put(k, v) } })
        val f = file(app)
        val tmp = File(f.path + ".tmp")
        runCatching { tmp.writeText(o.toString()); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) } }
            .onFailure { Log.w(TAG, "save: ${it.javaClass.simpleName}") }
    }

    private fun load(): State? = runCatching {
        val o = JSONObject(file(app).readText())
        val arr = o.optJSONArray("bridges") ?: JSONArray()
        val entries = (0 until arr.length()).mapNotNull { i ->
            val x = arr.getJSONObject(i)
            val b = (BridgeLines.parse(x.getString("line")) as? BridgeParse.Ok)?.bridge ?: return@mapNotNull null
            val src = x.optJSONArray("src")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            Entry(
                bridge = b, sources = src, official = x.optBoolean("off"), custom = x.optBoolean("mine"),
                firstSeen = x.optLong("first"), lastListed = x.optLong("listed"), testedAt = x.optLong("tAt"),
                ok = if (x.isNull("ok")) null else x.optBoolean("ok"), rttMs = x.optLong("rtt", -1).takeIf { it >= 0 },
                failStreak = x.optInt("fail"), lastOkAt = x.optLong("okAt"), needsV6 = x.optBoolean("v6"),
                country = if (x.isNull("cc")) null else x.optString("cc").takeIf { it.length == 2 },
                geoDone = x.optBoolean("geo"), torOkAt = x.optLong("tor"),
            )
        }
        val so = o.optJSONObject("sources") ?: JSONObject()
        val sources = so.keys().asSequence().associateWith { k ->
            val x = so.getJSONObject(k)
            SourceState(k, x.optLong("at"), x.optLong("chg"), x.optBoolean("ok"), if (x.isNull("err")) null else x.optString("err"), x.optInt("n"))
        }
        val ro = o.optJSONObject("removed") ?: JSONObject()
        val removed = ro.keys().asSequence().associateWith { ro.optLong(it) }
        State(entries = entries, sources = sources, lastRefreshAt = o.optLong("refreshed"), removed = removed)
    }.getOrNull()

    private const val DAY = 24 * 3600_000L
    private const val MAX_ENTRIES = 5000
    private const val AUTO_TEST_MAX = 1000
    private const val HIDE_MS = 30 * DAY
}
