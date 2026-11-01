package com.mlmvpn.scanner.engines.arena

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.data.CombineEngine
import com.mlmvpn.scanner.engines.cloud.WorkerRoute
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.quick.QuickScanner
import com.mlmvpn.scanner.store.StoreManager
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The race director: preparation, the rounds, the live state the screens draw, and the session
 * that goes into history.
 *
 * Fairness, as far as a phone allows: every round goes round-robin (A, B, C, A, B, C …) so a dip
 * in the network lands on everybody instead of on whoever happened to be measured during it;
 * the throughput round is strictly one at a time; at most three Xray instances ever run at once
 * (qualifying), the ceiling [CombineEngine.measureHealthy] found above which numbers inflate.
 */
object ArenaEngine {

    private const val TAG = "Arena"
    private const val REACH_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val MAX_CANDIDATES = 3
    private const val LATENCY_SAMPLES = 3
    private const val STABILITY_SAMPLES = 6
    private const val STABILITY_GAP_MS = 3_000L

    enum class Action { NONE, INSTALL, UPDATE }
    data class Prep(val panel: ArenaPanel, val action: Action)

    enum class Phase { IDLE, PREPARING, CONFIGS, CLEAN_IP, QUALIFY, LATENCY, REACH, SPEED, STABILITY, DONE }
    enum class Lane { WAITING, PIT, READY, RACING, OUT, FINISHED }

    data class LaneState(
        val panelId: String,
        val name: String,
        val lane: Lane = Lane.WAITING,
        val note: String? = null,
        val entry: Entry,
    )

    data class State(
        val phase: Phase = Phase.IDLE,
        val mode: ArenaMode = ArenaMode.QUICK,
        val lanes: List<LaneState> = emptyList(),
        /** 0..1 within the current phase. */
        val progress: Float = 0f,
        val session: ArenaSession? = null,
        val error: String? = null,
        /** The one clean IP every config races on (ArenaCleanIp), once chosen. */
        val cleanIp: ArenaCleanIp.Pick? = null,
        /** What the clean-IP step is doing, or why it found none. */
        val cleanNote: String? = null,
    ) {
        val running get() = phase != Phase.IDLE && phase != Phase.DONE
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // ── preparation plan ───────────────────────────────────────────────────────────────────

    /**
     * What preparation would do on [account], for the confirmation page: install what is missing,
     * update what the Store says is behind on this account, leave the rest alone.
     */
    fun plan(context: Context, account: CloudAccount, panels: List<ArenaPanel> = ArenaPanels.ALL): List<Prep> {
        val rows = StoreManager.rows.value
        return panels.map { p ->
            when {
                !p.installed(context, account) -> Prep(p, Action.INSTALL)
                p.storeId != null && rows.firstOrNull { it.item.id == p.storeId }?.deployments
                    ?.any { it.accountId == account.accountId && it.behind } == true -> Prep(p, Action.UPDATE)
                else -> Prep(p, Action.NONE)
            }
        }
    }

    // ── the race ───────────────────────────────────────────────────────────────────────────

    fun start(context: Context, account: CloudAccount, mode: ArenaMode, preps: List<Prep>) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            val started = System.currentTimeMillis()
            pending.clear()
            _state.value = State(
                phase = Phase.PREPARING, mode = mode,
                lanes = preps.map { LaneState(it.panel.id, it.panel.name, entry = Entry(it.panel.id, it.panel.name)) },
            )
            try {
                prepare(app, account, preps)
                gatherConfigs(app, account, preps.map { it.panel })
                fairEntry(app)
                qualify(app)
                // The clean IP carried nobody: rather than an empty race, everyone requalifies on
                // their own addresses -- still the same rule for all -- and the screen says so.
                if (_state.value.cleanIp != null && racing().isEmpty() && ownAddresses()) qualify(app)
                roundLatency(app)
                roundReach(app)
                if (mode == ArenaMode.FULL) {
                    roundSpeed(app)
                    roundStability(app)
                }
                val session = ArenaSession(
                    id = "arena-$started", startedAt = started, finishedAt = System.currentTimeMillis(), mode = mode,
                    network = ArenaStore.network(app),
                    entries = _state.value.lanes.map { it.entry },
                    cleanIp = _state.value.cleanIp?.ip,
                )
                ArenaStore.add(app, session)
                _state.update { s -> s.copy(phase = Phase.DONE, progress = 1f, session = session,
                    lanes = s.lanes.map { if (it.lane == Lane.RACING) it.copy(lane = Lane.FINISHED) else it }) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "race", e)
                _state.update { it.copy(phase = Phase.DONE, error = e.message) }
            }
        }
    }

    fun cancel() { job?.cancel(); _state.value = State() }

    fun reset() { if (job?.isActive != true) _state.value = State() }

    private fun lane(id: String, f: (LaneState) -> LaneState) =
        _state.update { s -> s.copy(lanes = s.lanes.map { if (it.panelId == id) f(it) else it }) }

    private fun out(id: String, fail: ArenaFail, detail: String?) =
        lane(id) { it.copy(lane = Lane.OUT, note = detail ?: tr(fail.fa, fail.en), entry = it.entry.copy(fail = fail, failDetail = detail)) }

    private fun racing() = _state.value.lanes.filter { it.lane == Lane.RACING || it.lane == Lane.READY }

    /** Install and update, one panel at a time; a failure takes that panel out, not the race. */
    private suspend fun prepare(context: Context, account: CloudAccount, preps: List<Prep>) {
        preps.forEachIndexed { i, p ->
            _state.update { it.copy(progress = i.toFloat() / preps.size) }
            when (p.action) {
                Action.NONE -> lane(p.panel.id) { it.copy(lane = Lane.READY) }
                Action.INSTALL -> {
                    lane(p.panel.id) { it.copy(lane = Lane.PIT, note = tr("نصب…", "Installing…")) }
                    p.panel.install(context, account) { s -> lane(p.panel.id) { it.copy(note = s) } }
                        .onSuccess { lane(p.panel.id) { it.copy(lane = Lane.READY, note = tr("نصب شد", "Installed")) } }
                        .onFailure { out(p.panel.id, ArenaFail.NOT_INSTALLED, it.message) }
                }
                Action.UPDATE -> {
                    lane(p.panel.id) { it.copy(lane = Lane.PIT, note = tr("به‌روزرسانی…", "Updating…")) }
                    val id = p.panel.storeId!!
                    StoreManager.install(context, id)
                    // The Store's own job: wait for it, and race on whatever version is live after.
                    withTimeoutOrNull(180_000) {
                        delay(500)
                        while (StoreManager.jobs.value[id]?.running == true) delay(500)
                    }
                    val err = StoreManager.jobs.value[id]?.error
                    lane(p.panel.id) { it.copy(lane = Lane.READY, note = err?.let { e -> tr("به‌روز نشد؛ با نسخهٔ فعلی: ", "Not updated; racing as is: ") + e } ?: tr("به‌روز شد", "Updated")) }
                }
            }
        }
    }

    /** Each panel's configs, through the Cloud tab's own fetchers. */
    private suspend fun gatherConfigs(context: Context, account: CloudAccount, panels: List<ArenaPanel>) {
        _state.update { it.copy(phase = Phase.CONFIGS, progress = 0f) }
        val ready = panels.filter { p -> _state.value.lanes.first { it.panelId == p.id }.lane == Lane.READY }
        ready.forEachIndexed { i, p ->
            _state.update { it.copy(progress = i.toFloat() / ready.size) }
            lane(p.id) { it.copy(note = tr("گرفتن کانفیگ…", "Getting configs…")) }
            var list = runCatching { p.candidates(context, account) }
                .onFailure { Log.w(TAG, "configs ${p.id}", it) }
            // Self-healing: a failed fetch is tried once more. By then the Worker route has probed
            // every address for the name (engines/cloud/WorkerRoute.kt) and puts a healthy one
            // first, which is what turns "timed out at random" into "switched to 172.67.x.x".
            if (list.getOrNull().isNullOrEmpty()) {
                lane(p.id) { it.copy(note = WorkerRoute.latestReport() ?: tr("دوباره…", "Retrying…")) }
                list = runCatching { p.candidates(context, account) }
                    .onFailure { Log.w(TAG, "configs ${p.id} (retry)", it) }
            }
            val configs = list.getOrNull().orEmpty()
            if (configs.isEmpty()) out(p.id, ArenaFail.NO_CONFIG,
                listOfNotNull(WorkerRoute.latestReport(), list.exceptionOrNull()?.message).joinToString(" — ").ifBlank { null })
            else {
                pending[p.id] = configs
                lane(p.id) { it.copy(note = tr("${configs.size} کانفیگ", "${configs.size} configs"), entry = it.entry.copy(candidates = configs.size)) }
            }
        }
    }

    private val pending = HashMap<String, List<String>>()
    /** Each panel's configs as its panel gave them, before the clean IP went on. */
    private val originals = HashMap<String, List<String>>()

    private fun ownAddresses(): Boolean {
        val retry = _state.value.lanes.filter { it.lane == Lane.OUT && originals[it.panelId] != null &&
            it.entry.fail in setOf(ArenaFail.UNREACHABLE, ArenaFail.TLS_REFUSED, ArenaFail.NO_RESPONSE) }
        if (retry.isEmpty()) return false
        Log.i(TAG, "clean ip ${_state.value.cleanIp?.ip} carried nobody; requalifying on own addresses")
        for (l in retry) {
            pending[l.panelId] = pick(originals.getValue(l.panelId))
            lane(l.panelId) { it.copy(lane = Lane.READY, note = null, entry = it.entry.copy(fail = null, failDetail = null)) }
        }
        _state.update { it.copy(cleanIp = null, cleanNote = tr("هیچ کانفیگی با آی‌پی تمیز جواب نداد؛ همه با نشانی خودشان مسابقه می‌دهند.",
            "No config answered on the clean IP; everyone races on their own address.")) }
        return true
    }

    /**
     * «آی‌پی تمیز یکسان» -- a fixed step of every race (the user's rule, 2026-09-28): one clean IP,
     * measured now for stability and speed (ArenaCleanIp), put on every competitor's config so the
     * race compares panels, not the addresses each one hands out. Then each panel's three candidates
     * are picked from its configs as they will actually race. No IP that passes every handshake means
     * each panel races on its own addresses, and the screen says so.
     */
    private suspend fun fairEntry(context: Context) {
        _state.update { it.copy(phase = Phase.CLEAN_IP, progress = 0f, cleanNote = tr("پیدا کردن آی‌پی تمیز…", "Finding a clean IP…")) }
        // The config the IP is measured through must itself work, or every IP looks dead: each
        // panel's first pick is tried on its own address first.
        _state.update { it.copy(cleanNote = tr("انتخاب کانفیگ آزمایشی…", "Choosing a test config…")) }
        // BPB's config is the base when it works (the user's choice); otherwise the quickest that does.
        val sample = coroutineScope {
            pending.entries.filter { it.value.isNotEmpty() }.map { (id, list) -> id to pick(list).first() }
                .map { (id, u) -> async { Triple(id, u, CombineEngine.measureUri(context, u, 8_000)) } }.awaitAll()
                .filter { it.third > 0 }.sortedWith(compareBy({ if (it.first == "BPB") 0 else 1 }, { it.third }))
                .firstOrNull()?.second
        }
        val chosen = sample?.let { s ->
            runCatching {
                ArenaCleanIp.choose(context, s, pending.values.flatten().mapNotNull { VpnConfig.parseUri(it)?.address }.distinct(), { c -> speed(context, c) }) { step -> _state.update { it.copy(cleanNote = step) } }
            }.onFailure { Log.w(TAG, "clean ip", it) }.getOrNull()
        }
        originals.clear()
        originals.putAll(pending)
        for (k in pending.keys.toList()) {
            val list = pending[k].orEmpty()
            pending[k] = pick(if (chosen != null) list.map { ArenaCleanIp.onto(it, chosen.ip) } else list)
        }
        if (chosen != null) {
            ArenaCleanIp.remember(context, chosen.ip)
            Log.i(TAG, "clean ip ${chosen.ip}: tls ${chosen.tlsMedianMs}±${chosen.spreadMs} ms, ${chosen.mbps} Mbit/s, of ${chosen.tried}")
            _state.update { it.copy(cleanIp = chosen, cleanNote = null, progress = 1f) }
        } else {
            _state.update { it.copy(cleanNote = tr("آی‌پی تمیز پایداری پیدا نشد؛ هر پنل با نشانی خودش مسابقه می‌دهد.",
                "No clean IP held steady; each panel races on its own address."), progress = 1f) }
        }
    }

    /** Up to three candidates, the plain TLS-on-443 ones first: the panel's most typical config. */
    private fun pick(configs: List<String>): List<String> {
        val parsed = configs.mapNotNull { u -> VpnConfig.parseUri(u)?.let { u to it } }
        val ranked = parsed.sortedBy { (_, c) -> (if (c.port == 443) 0 else 1) + (if (c.tls == "tls") 0 else 2) }
        return ranked.map { it.first }.distinct().take(MAX_CANDIDATES).ifEmpty { configs.take(MAX_CANDIDATES) }
    }

    /** Parse → TCP → TLS with the config's own name → one real request through Xray. */
    private suspend fun qualifyOne(context: Context, uri: String): Pair<Long?, ArenaFail?> {
        val c = VpnConfig.parseUri(uri) ?: return null to ArenaFail.INVALID
        if (c.address.isBlank()) return null to ArenaFail.INVALID
        if (QuickScanner.tcpOpen(c.address, c.port, 3_000) < 0) return null to ArenaFail.UNREACHABLE
        if (!QuickScanner.tlsAnswers(c)) return null to ArenaFail.TLS_REFUSED
        val ms = CombineEngine.measureUri(context, uri, 10_000)
        return if (ms > 0) ms to null else null to ArenaFail.NO_RESPONSE
    }

    private suspend fun qualify(context: Context) = coroutineScope {
        _state.update { it.copy(phase = Phase.QUALIFY, progress = 0f) }
        val gate = Semaphore(3)
        val lanes = _state.value.lanes.filter { it.lane == Lane.READY && pending[it.panelId] != null }
        var done = 0
        lanes.map { l ->
            async {
                lane(l.panelId) { it.copy(note = tr("تعیین صلاحیت…", "Qualifying…")) }
                val results = pending.getValue(l.panelId).map { u -> async { gate.withPermit { u to qualifyOne(context, u) } } }.awaitAll()
                val best = results.filter { it.second.first != null }.minByOrNull { it.second.first!! }
                if (best != null) {
                    lane(l.panelId) { it.copy(lane = Lane.RACING, note = null, entry = it.entry.copy(uri = best.first, qualifyMs = best.second.first)) }
                } else {
                    // The furthest any candidate got says the most: a TLS refusal beats "unreachable".
                    val fail = results.mapNotNull { it.second.second }.maxByOrNull { it.ordinal } ?: ArenaFail.NO_RESPONSE
                    out(l.panelId, fail, null)
                }
                synchronized(this@ArenaEngine) { done++ }
                _state.update { it.copy(progress = done.toFloat() / lanes.size) }
            }
        }.awaitAll()
    }

    /** [samples] rounds of one measurement per racer, round-robin. */
    private suspend fun roundRobin(phase: Phase, samples: Int, gapMs: Long = 0, measure: suspend (Entry) -> Unit) {
        _state.update { it.copy(phase = phase, progress = 0f) }
        val ids = racing().map { it.panelId }
        if (ids.isEmpty()) return
        val total = samples * ids.size
        var n = 0
        repeat(samples) { k ->
            for (id in ids) {
                val e = _state.value.lanes.first { it.panelId == id }.entry
                measure(e)
                n++
                _state.update { it.copy(progress = n.toFloat() / total) }
            }
            if (gapMs > 0 && k < samples - 1) delay(gapMs)
        }
    }

    private suspend fun roundLatency(context: Context) = roundRobin(Phase.LATENCY, LATENCY_SAMPLES) { e ->
        val ms = CombineEngine.measureUri(context, e.uri!!, 10_000).takeIf { it > 0 }
        lane(e.panelId) { it.copy(entry = it.entry.copy(latency = it.entry.latency + Sample(System.currentTimeMillis(), ms))) }
    }

    /** A Cloudflare-hosted page through the config: a Worker without a relay cannot open these. */
    private suspend fun roundReach(context: Context) = roundRobin(Phase.REACH, 1) { e ->
        val c = VpnConfig.parseUri(e.uri!!)
        val ms = c?.let {
            val json = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(it)
            CombineEngine.measureDelay(json, REACH_URL, 10_000).takeIf { v -> v > 0 }
        }
        lane(e.panelId) { it.copy(entry = it.entry.copy(reachTried = true, reachMs = ms)) }
    }

    /** The same 1 MB file for everybody (OVH, not on Cloudflare), strictly one after another. */
    private suspend fun roundSpeed(context: Context) = roundRobin(Phase.SPEED, 1) { e ->
        val mbps = VpnConfig.parseUri(e.uri!!)?.let { speed(context, it) }
        lane(e.panelId) { it.copy(entry = it.entry.copy(mbps = mbps)) }
    }

    /**
     * Megabits per second over one fixed 1 MB download through a temporary Xray -- the method of
     * `realSpeedTest` (ui/NodesTab.kt), kept exact: that one prints MB/s to one decimal, which put
     * every panel on the phone at 3.2 or 4.0 Mbit/s and turned the round into ties.
     */
    private suspend fun speed(context: Context, config: VpnConfig): Double? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val port = (20000..30000).random()
        // A test core, in the test host process (XrayHosts): a panel that panics Xray ends that
        // process, not the app.
        var core: com.mlmvpn.core.warp.VlessXrayInjector? = null
        try {
            val json = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateConfig(config, port, includeTun = false)
            val c = com.mlmvpn.core.warp.VlessXrayInjector(0, quiet = true)
            if (!c.start(context, json, port)) return@withContext null
            core = c
            delay(500)
            val client = okhttp3.OkHttpClient.Builder()
                .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port + 10000)))
                .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
            val t0 = System.nanoTime()
            client.newCall(okhttp3.Request.Builder().url(SPEED_URL).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val bytes = r.body?.bytes()?.size ?: 0
                val sec = (System.nanoTime() - t0) / 1e9
                if (bytes > 0 && sec > 0) bytes * 8 / 1e6 / sec else null
            }
        } catch (e: Exception) {
            Log.i(TAG, "speed: ${e.message}")
            null
        } finally {
            runCatching { core?.stop() }
        }
    }

    private const val SPEED_URL = "https://proof.ovh.net/files/1Mb.dat"

    private suspend fun roundStability(context: Context) = roundRobin(Phase.STABILITY, STABILITY_SAMPLES, STABILITY_GAP_MS) { e ->
        val ms = CombineEngine.measureUri(context, e.uri!!, 8_000).takeIf { it > 0 }
        lane(e.panelId) { it.copy(entry = it.entry.copy(stability = it.entry.stability + Sample(System.currentTimeMillis(), ms))) }
    }
}
