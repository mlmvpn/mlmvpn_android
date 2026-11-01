package com.mlmvpn.scanner.engines.amnezia

import kotlinx.coroutines.ensureActive

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mlmvpn.core.warp.AwgHostClient
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.engines.flux.core.compile.FluxConfigCompiler
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.parse.FluxLinkParser
import com.mlmvpn.scanner.utils.LocalPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * The «آمنزیا» section: free WireGuard (connected through AmneziaWG, with junk packets that hide
 * the handshake) and Hysteria2 servers, plus the user's own `.conf` files.
 *
 *  - Lists: refreshed from public GitHub lists every few hours ([AmzSourceRepo]), merged by
 *    identity, dead ones kept out for a week.
 *  - Delay: a real request through each server ([AmzTester]); results learned per network
 *    ([AmzBrain]), so the most promising servers are tested -- and listed -- first.
 *  - Country: from the list or the name at first, then from Cloudflare's trace through the server
 *    during a test, then from the running tunnel after connecting. A server moves to the country
 *    it really exits in, whatever its name said.
 *  - Connect: "connected" only after a real request went through the tunnel. Until then the
 *    button shows what is happening and can be cancelled at any moment.
 */
object AmzEngine {

    private const val TAG = "Amnezia"
    const val NODE_ID = "amnezia"
    /** How many times one connect tries a relay on a server before moving to the next server (a relay may stop sooner). */
    private const val RELAY_WAYS = 3
    /** A relay's speed comparison: how long after connecting, and what each sample downloads (2 MB, at most 10 s). */
    private const val TUNE_AFTER_MS = 5_000L
    private const val TUNE_URL = "https://speed.cloudflare.com/__down?bytes=2000000"
    private const val TUNE_MAX_NS = 10_000_000_000L
    private const val REFRESH_EVERY_MS = 6L * 3_600_000
    /** What the tunnel must have brought in during the connect check: a TLS server's certificates. */
    private const val PROOF_MIN_RX = 1_500L
    private const val SEED_ASSET = "amnezia_seed.json"
    private const val UI_PREFS = "amnezia_ui"
    private const val UNDO_MS = 6_000L

    private lateinit var app: Context
    lateinit var store: AmzStore
        private set
    private lateinit var repo: AmzSourceRepo
    private lateinit var tester: AmzTester
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var initialized = false

    private val _connect = MutableStateFlow<AmzConnect>(AmzConnect.Idle)
    val connect: StateFlow<AmzConnect> = _connect

    private val _progress = MutableStateFlow(AmzTestProgress())
    val progress: StateFlow<AmzTestProgress> = _progress

    /** Ids in the running test that have not answered yet (their rows show a spinner). */
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** The network the list's delays belong to. */
    private val _net = MutableStateFlow("unknown")
    val net: StateFlow<String> = _net

    /** One-off messages for the screen (a count of imported servers, a refresh that found nothing). */
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    sealed class Notice {
        data class Imported(val count: Int) : Notice()
        object ImportFailed : Notice()
        /** A WireGuard file with an [Interface] and no [Peer]: half a config, from whoever shared it. */
        object ImportNoPeer : Notice()
        /** A WireGuard file that names exactly what is missing or broken in it. */
        data class ImportWg(val problem: WgConfig.Companion.Problem) : Notice()
        data class Refreshed(val added: Int) : Notice()
        object RefreshFailed : Notice()
        object Offline : Notice()
        data class Removed(val count: Int) : Notice()
    }

    fun consumeNotice() { _notice.value = null }

    /** What the big button connects to: the fastest server, the fastest of a group, or one server. */
    sealed class Target {
        object Auto : Target()
        data class Group(val key: String) : Target()
        data class Server(val id: String) : Target()
    }

    private val _target = MutableStateFlow<Target>(Target.Auto)
    val target: StateFlow<Target> = _target

    fun setTarget(t: Target) {
        _target.value = t
        app.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().putString("target", when (t) {
            Target.Auto -> ""
            is Target.Group -> "g:" + t.key
            is Target.Server -> "s:" + t.id
        }).apply()
    }

    private fun loadTarget(): Target {
        val v = app.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getString("target", "").orEmpty()
        return when {
            v.startsWith("g:") -> Target.Group(v.removePrefix("g:"))
            v.startsWith("s:") -> Target.Server(v.removePrefix("s:"))
            else -> Target.Auto
        }
    }

    /** Connects to whatever [target] says. */
    fun connectTarget(context: Context) {
        init(context)
        when (val t = _target.value) {
            Target.Auto -> connectAsync(context)
            is Target.Group -> connectAsync(context, country = t.key)
            is Target.Server -> if (store.current.servers.any { it.id == t.id }) connectAsync(context, serverId = t.id) else connectAsync(context)
        }
    }

    @Volatile private var testJob: Job? = null
    @Volatile private var connectJob: Job? = null
    @Volatile private var wantConnected = false

    /**
     * A way to some servers other than WireGuard over UDP: given a server, a local UDP port that
     * carries its WireGuard packets (Proton's Stealth, WireGuard inside TLS on 443), or null for
     * plain UDP. Asked for every connect and every test.
     */
    fun interface Relay { fun open(server: AmzServer): RelayHandle? }

    interface RelayHandle : java.io.Closeable {
        /** The local UDP port WireGuard is pointed at instead of the server. */
        val port: Int
        /** Called after application data passed, never after just a transport handshake. */
        fun validated() {}
        /** The relay now carries the tunnel itself (not a test): it may keep what a long session needs. */
        fun carry() {}
        /** Prepare another bounded transport attempt, with a fresh WireGuard session. */
        fun retryAfterFailure(): Boolean = false
        /** Which way the relay is using now, for the screen (Proton: "u6", "v6", "cf", ...); null when it has only one. */
        val via: String? get() = null
        /** Told whenever the relay moves the tunnel onto another way, so the screen can follow. */
        fun watchVia(listener: (String) -> Unit) {}
        /**
         * Under the tunnel: the relay may compare the real speed of its ways, [measure] being one
         * short timed download through the tunnel (bits/s, or null; it stops early once the
         * relay's `stillValid` says the sample no longer counts), and keep the fastest. Blocks.
         */
        fun tune(measure: (stillValid: () -> Boolean) -> Long?) {}
    }

    @Volatile var relay: Relay? = null
    /** The relay under the running tunnel; closed when that tunnel ends or is replaced. */
    @Volatile private var activeRelay: RelayHandle? = null

    /**
     * Off the caller's thread: the relay's close waits for its lock, which one of its workers can
     * hold while it dials, and a disconnect from the screen waited on it on the main thread -- an
     * ANR (1.2.41). The relay is taken out of use at once; it finishes closing on its own.
     */
    private fun closeRelay() {
        val relay = activeRelay ?: return
        activeRelay = null
        runCatching { Thread({ runCatching { relay.close() } }, "amz-relay-close").apply { isDaemon = true }.start() }
            .onFailure { runCatching { relay.close() } }
    }

    /** The SOCKS port this engine opened for Local Network sharing; it closes only its own. */
    @Volatile private var lanPortOpen = false

    /**
     * Local Network sharing over an AmneziaWG tunnel: the app's own SOCKS port (the one OpenVPN's
     * tunnels share through, [com.mlmvpn.scanner.openvpn.OpenVpnSocks]) while connected with sharing
     * on. Nothing here runs Xray in the app's process.
     */
    private fun lanPort(on: Boolean) {
        if (on) {
            if (lanPortOpen || !runCatching { com.mlmvpn.scanner.lan.LanShare.sharingActive(app) }.getOrDefault(false)) return
            com.mlmvpn.scanner.openvpn.OpenVpnSocks.start(app)
            lanPortOpen = com.mlmvpn.scanner.openvpn.OpenVpnSocks.port > 0
        } else if (lanPortOpen) {
            lanPortOpen = false
            com.mlmvpn.scanner.openvpn.OpenVpnSocks.stop()
        }
    }

    /** [server] as the tunnel or a test reaches it: through its relay when it has one. */
    internal fun relayed(cfg: WgConfig, handle: RelayHandle?): WgConfig =
        if (handle == null) cfg else cfg.copy(endpointHost = "127.0.0.1", endpointPort = handle.port)

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        store = AmzStore(app.filesDir)
        repo = AmzSourceRepo(app)
        tester = AmzTester(app)
        _net.value = FluxNet.current(app).key
        // A fresh install starts with servers that were working when the app was built, so the
        // section is usable before (or without) the first download.
        if (store.current.servers.isEmpty()) {
            runCatching {
                val seed = AmzCodec.decode(app.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }).servers
                store.update { AmzBrain.merge(it, seed, System.currentTimeMillis(), _net.value) }
            }.onFailure { Log.w(TAG, "seed not loaded: ${it.javaClass.simpleName}") }
        }
        _target.value = loadTarget()
        // The Cloudflare way is new: failures that were only ever over the line no longer count.
        val ui = app.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        if (!ui.getBoolean("cf_way_v1", false)) {
            store.update { AmzBrain.forgetLineOnlyFailures(it) }
            ui.edit().putBoolean("cf_way_v1", true).apply()
        }
        observeSession()
        initialized = true
    }

    fun isInitialized() = initialized

    /**
     * Another engine took the VPN, or it went down from outside (the tile, the system): stop
     * claiming a connection that is no longer there.
     */
    private fun observeSession() {
        scope.launch {
            combine(MyVpnService.connectionPhaseFlow, MyVpnService.connectedNodeIdFlow) { phase, id ->
                phase != MyVpnService.Phase.IDLE && id == NODE_ID
            }.collect { ours ->
                if (!ours && _connect.value is AmzConnect.Connected) {
                    _connect.value = AmzConnect.Idle
                    wantConnected = false
                }
                if (!ours) lanPort(false)
                if (!ours && connectJob?.isActive != true) closeRelay()
            }
        }
    }

    // ---------------------------------------------------------------- opening the screen

    /** Called when the screen opens: fetch the lists when due, and test when the list is stale. */
    /**
     * The page opened: the lists are fetched when due, but nothing is tested unless the user asks.
     * Opening it used to start a test of the best 200 by itself, as «Outline» did (user, 2026-10-17:
     * «بدون اجازه تست میگیره، شاید کاربر نخواد»).
     */
    fun onOpen(context: Context) {
        init(context)
        _net.value = FluxNet.current(app).key
        val s = store.current
        val now = System.currentTimeMillis()
        if (s.servers.isEmpty() || now - s.lastRefreshAt > REFRESH_EVERY_MS) refresh(force = s.lastRefreshAt == 0L)
    }

    // ---------------------------------------------------------------- lists

    fun refresh(force: Boolean = true) {
        if (_refreshing.value) return
        _refreshing.value = true
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val fetched = withContext(Dispatchers.IO) { repo.refresh(force, tunnelHttpPort(), now) }
                val before = store.current.servers.size
                store.update { s ->
                    val merged = fetched.fold(s) { acc, f -> AmzBrain.merge(acc, f.batch.servers, now, _net.value) }
                    merged.copy(lastRefreshAt = if (fetched.isNotEmpty() || s.servers.isNotEmpty()) now else s.lastRefreshAt)
                }
                val added = store.current.servers.size - before
                Log.i(TAG, "refresh: ${fetched.size} sources answered, pool ${store.current.servers.size} (+$added)")
                _notice.value = if (fetched.isEmpty() && store.current.servers.isEmpty()) Notice.RefreshFailed else Notice.Refreshed(added.coerceAtLeast(0))
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed", e)
                _notice.value = Notice.RefreshFailed
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** A file the user picked or opened with the app. */
    fun importFile(context: Context, bytes: ByteArray, name: String) {
        init(context)
        scope.launch {
            val now = System.currentTimeMillis()
            val batch = repo.parseUserFile(bytes, name, now)
            addUser(batch.servers, now, AmzSourceRepo.decodeText(bytes))
        }
    }

    /** Pasted text: links, or a whole `.conf`. */
    fun importText(context: Context, text: String) {
        init(context)
        scope.launch {
            val now = System.currentTimeMillis()
            addUser(AmzParser.parseText(text, AmzServer.USER_SOURCE, now).servers, now, text)
        }
    }

    /**
     * One `.conf` that came from outside the file picker (Proton's own page, in the app): the
     * server's id, or null when it is not a usable WireGuard config -- the notice says why.
     * [name] is what the config's file was called there; the country is read from it unless the
     * source says it ([country], e.g. Proton's own server list).
     */
    fun importConf(context: Context, text: String, name: String, country: String? = null): String? {
        init(context)
        val now = System.currentTimeMillis()
        val server = AmzParser.parseText(text, AmzServer.USER_SOURCE, now, sourceCountry = country, fileName = name).servers.firstOrNull()
        addUser(listOfNotNull(server), now, text)
        return server?.id
    }

    /**
     * A whole set of configs at once (every server of a Proton account): one merge and one test of
     * them all -- one [importConf] each would start a test per server, each cancelling the last.
     * (text, name, country) per config; the ids of those that are usable.
     */
    fun importConfs(context: Context, confs: List<Triple<String, String, String?>>): List<String> {
        init(context)
        val now = System.currentTimeMillis()
        val servers = confs.mapNotNull { (text, name, cc) ->
            AmzParser.parseText(text, AmzServer.USER_SOURCE, now, sourceCountry = cc, fileName = name).servers.firstOrNull()
        }
        if (servers.isNotEmpty()) addUser(servers, now)
        return servers.map { it.id }
    }

    private fun addUser(servers: List<AmzServer>, now: Long, source: String = "") {
        if (servers.isEmpty()) {
            val problem = if (source.contains("[Interface]", ignoreCase = true)) WgConfig.diagnose(source) else null
            _notice.value = when (problem) {
                null -> Notice.ImportFailed
                WgConfig.Companion.Problem.NO_PEER -> Notice.ImportNoPeer
                else -> Notice.ImportWg(problem)
            }
            return
        }
        store.update { AmzBrain.merge(it, servers.map { s -> s.copy(sourceId = AmzServer.USER_SOURCE) }, now, _net.value) }
        _notice.value = Notice.Imported(servers.size)
        // The user's own servers are tested at once: they want to know if what they added works.
        testIds(servers.map { it.id })
    }

    /** The last deletion, kept so it can be undone: the servers and their results on every network. */
    private data class Removed(val servers: List<AmzServer>, val stats: Map<String, Map<String, AmzStat>>)
    @Volatile private var lastRemoved: Removed? = null
    private val _undoCount = MutableStateFlow(0)
    /** How many servers the last deletion removed while it can still be undone; 0 when it cannot. */
    val undoCount: StateFlow<Int> = _undoCount
    private var undoTimer: Job? = null

    fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        val cur = store.current
        val gone = cur.servers.filter { it.id in set }
        if (gone.isEmpty()) return
        lastRemoved = Removed(gone, cur.stats.mapValues { (_, m) -> m.filterKeys { it in set } })
        store.update { AmzBrain.remove(it, ids, System.currentTimeMillis()) }
        _undoCount.value = gone.size
        undoTimer?.cancel()
        undoTimer = scope.launch { delay(UNDO_MS); _undoCount.value = 0; lastRemoved = null }
    }

    /** Brings the last deletion back, results and all. */
    fun undoDelete() {
        val r = lastRemoved ?: return
        lastRemoved = null
        undoTimer?.cancel()
        _undoCount.value = 0
        val ids = r.servers.mapTo(HashSet()) { it.id }
        store.update { s ->
            val present = s.servers.mapTo(HashSet()) { it.id }
            s.copy(
                servers = s.servers + r.servers.filter { it.id !in present },
                stats = (s.stats.keys + r.stats.keys).associateWith { net -> (s.stats[net] ?: emptyMap()) + (r.stats[net] ?: emptyMap()) },
                tombstones = s.tombstones - ids,
            )
        }
    }

    fun removeDead() {
        val ids = AmzBrain.deadIds(store.current, _net.value)
        delete(ids)
        _notice.value = Notice.Removed(ids.size)
    }

    fun setPrefs(change: (AmzPrefs) -> AmzPrefs) { store.update { it.copy(prefs = change(it.prefs)) } }

    // ---------------------------------------------------------------- delay tests

    /**
     * [auto]: started by the app, not by a tap (the screen opened, the lists came in). Such a test
     * leaves out the servers another section owns -- Proton's, Windscribe's -- which are pinged only
     * when the user asks there: the progress is shared, and Proton's page showed its servers being
     * pinged after a disconnect nobody had asked to ping after (user report 2026-10-11).
     */
    fun test(mode: AmzTestMode, country: String? = null, kind: AmzKind? = null, auto: Boolean = false) {
        val net = FluxNet.current(app).key.also { _net.value = it }
        val picked = AmzBrain.pickForTest(store.current, net, mode, System.currentTimeMillis(), country = country, kind = kind)
        val owned = if (auto) providerIds() else emptySet()
        start(if (owned.isEmpty()) picked else picked.filter { it.id !in owned }, mode, net)
    }

    /** Servers that belong to Proton's or Windscribe's section. */
    private fun providerIds(): Set<String> = runCatching {
        com.mlmvpn.scanner.engines.proton.ProtonEngine.storedIds(app) +
            com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.wireGuardIds(app).values
    }.getOrDefault(emptySet())

    fun testOne(id: String) = testIds(listOf(id), AmzTestMode.ONE)

    /**
     * Delay-tests exactly these servers (a page's «پینگ همه»). The job is returned so a caller can
     * wait for the answers; null when there was nothing to test.
     */
    fun testServers(ids: Collection<String>): Job? {
        testIds(ids.toList(), AmzTestMode.ALL)
        return testJob
    }

    private fun testIds(ids: List<String>, mode: AmzTestMode = AmzTestMode.ONE) {
        val set = ids.toHashSet()
        val net = FluxNet.current(app).key.also { _net.value = it }
        start(store.current.servers.filter { it.id in set }, mode, net)
    }

    /**
     * Stops at once on screen: the job itself winds down at its next check, which a blocking call
     * into the test process can hold off for 20 s -- «توقف» looked dead (user report 2026-10-11).
     */
    fun stopTest() {
        testJob?.cancel()
        _pending.value = emptySet()
        _progress.value = _progress.value.copy(running = false)
    }

    private fun start(servers: List<AmzServer>, mode: AmzTestMode, net: String) {
        if (servers.isEmpty()) return
        testJob?.cancel()
        val previous = testJob
        testJob = scope.launch {
            previous?.join()
            _progress.value = AmzTestProgress(running = true, mode = mode, total = servers.size)
            _pending.value = servers.mapTo(HashSet()) { it.id }
            val buffer = ConcurrentHashMap<String, AmzBrain.Result>()
            val pins = ConcurrentHashMap<String, String>()
            val done = java.util.concurrent.atomic.AtomicInteger()
            val alive = java.util.concurrent.atomic.AtomicInteger()
            // Results reach the list every few hundred ms, not one store write per server.
            val flusher = launch {
                while (isActive) { delay(350); flush(buffer, net, offline = false, pins) }
            }
            try {
                if (!tester.online()) { _notice.value = Notice.Offline; return@launch }
                val st = store.current
                val cfFirst = st.statsFor(net).filterValues { it.viaCloudflare }.keys
                tester.run(servers, st.prefs.obfuscate, object : AmzTester.Sink {
                    override fun result(id: String, ms: Long?, country: String?) = result(id, ms, country, null)

                    override fun result(id: String, ms: Long?, country: String?, via: String?) {
                        buffer[id] = AmzBrain.Result(id, ms, country, via)
                        val d = done.incrementAndGet()
                        val a = if (ms != null) alive.incrementAndGet() else alive.get()
                        _pending.value = _pending.value - id
                        _progress.value = _progress.value.copy(done = d, alive = a)
                    }

                    override fun skip(id: String) {
                        _pending.value = _pending.value - id
                        _progress.value = _progress.value.copy(done = done.incrementAndGet())
                    }

                    override fun via(via: String?) {
                        _progress.value = _progress.value.copy(via = via)
                    }

                    override fun pinned(id: String, raw: String) {
                        pins[id] = raw
                    }
                }, cfFirst = cfFirst, warpPool = st.servers.filter { it.warp })
                flusher.cancel()
                // Everything failed: either every server is dead, or the network went away under
                // the test. Only the second is checked -- and then nothing is learned or removed.
                val offline = alive.get() == 0 && servers.size >= 5 && !tester.online()
                if (offline) _notice.value = Notice.Offline
                flush(buffer, net, offline, pins)
            } finally {
                flusher.cancel()
                withContext(kotlinx.coroutines.NonCancellable) { flush(buffer, net, offline = false, pins) }
                _pending.value = emptySet()
                _progress.value = _progress.value.copy(running = false)
            }
        }
    }

    private fun flush(buffer: ConcurrentHashMap<String, AmzBrain.Result>, net: String, offline: Boolean, pins: ConcurrentHashMap<String, String>? = null) {
        // New certificate pins go in first, so a server is never shown alive with its old link.
        val pinned = pins?.let { p -> p.keys.toList().mapNotNull { id -> p.remove(id)?.let { id to it } }.toMap() }.orEmpty()
        if (pinned.isNotEmpty()) store.update { AmzBrain.pin(it, pinned) }
        if (buffer.isEmpty()) return
        val batch = buffer.keys.toList().mapNotNull { buffer.remove(it) }
        if (batch.isEmpty()) return
        store.update { AmzBrain.record(it, net, batch, System.currentTimeMillis(), offline) }
    }

    // ---------------------------------------------------------------- connecting

    /**
     * Connects to [serverId], or to the fastest working server ([country] when given). With
     * fallback on, a server that does not carry traffic is replaced by the next best of the same
     * country, up to three tries.
     */
    private val connectionOwner = com.mlmvpn.scanner.utils.ConnectionOwner()
    @Volatile private var gameSessionId: String? = null

    /**
     * [only]: connect and fall back among these servers alone. Proton's page uses it, so a Proton
     * config that fails never moves the user onto somebody else's server.
     */
    fun connectAsync(context: Context, serverId: String? = null, country: String? = null, owner: String? = null, only: Set<String>? = null): Job = connectionOwner.replace(owner) {
        init(context)
        val previous = connectJob
        previous?.cancel()
        scope.launch {
            previous?.join()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            wantConnected = true
            gameSessionId = owner
            try { connectLoop(serverId, country, only) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "connect crashed", e)
                if (wantConnected) _connect.value = AmzConnect.Failed(serverId, AmzConnect.Reason.NO_DATA)
            }
        }.also { connectJob = it }
    }

    fun disconnectAsync(context: Context, owner: String? = null) = connectionOwner.stop(owner) {
        init(context)
        wantConnected = false
        connectJob?.cancel()
        _connect.value = AmzConnect.Idle
        lanPort(false)
        closeRelay()
        if (MyVpnService.connectedNodeIdFlow.value == NODE_ID) {
            com.mlmvpn.scanner.utils.SafeStart.service(context.applicationContext, Intent(context.applicationContext, MyVpnService::class.java).apply {
                action = "STOP"
                putExtra(com.mlmvpn.scanner.utils.GameSessionOwner.EXPECTED, owner)
            })
        }
    }

    private suspend fun connectLoop(serverId: String?, country: String?, only: Set<String>? = null) {
        val net = FluxNet.current(app).key.also { _net.value = it }
        if (!FluxNet.online(app)) { _connect.value = AmzConnect.Failed(serverId, AmzConnect.Reason.OFFLINE); return }
        val tried = HashSet<String>()
        fun pool(): AmzState = store.current.let { st -> if (only == null) st else st.copy(servers = st.servers.filter { it.id in only }) }
        val first = serverId?.let { id -> pool().servers.firstOrNull { it.id == id } }
            ?: AmzBrain.best(pool(), net, country)
            ?: store.current.lastConnectedId?.let { id -> pool().servers.firstOrNull { it.id == id } }
            ?: AmzBrain.promising(pool(), net, country, emptySet(), System.currentTimeMillis())
        if (first == null) { _connect.value = AmzConnect.Failed(null, AmzConnect.Reason.NO_DATA); return }
        val fallbackCountry = country ?: AmzBrain.group(first)
        var server: AmzServer? = first
        var attempt = 1
        val maxAttempts = if (store.current.prefs.autoFallback) 3 else 1
        while (server != null && wantConnected) {
            tried += server.id
            val outcome = connectOne(server, attempt)
            if (!wantConnected) return
            if (outcome == null) {
                store.update { it.copy(lastConnectedId = server!!.id) }
                return
            }
            store.update { AmzBrain.record(it, net, listOf(AmzBrain.Result(server!!.id, null)), System.currentTimeMillis()) }
            if (outcome == AmzConnect.Reason.VPN_REFUSED || attempt >= maxAttempts) {
                _connect.value = AmzConnect.Failed(server.id, outcome)
                stopService()
                return
            }
            attempt++
            server = AmzBrain.best(pool(), net, fallbackCountry, tried) ?: AmzBrain.best(pool(), net, null, tried)
                ?: AmzBrain.promising(pool(), net, fallbackCountry, tried, System.currentTimeMillis())
            if (server == null) {
                _connect.value = AmzConnect.Failed(first.id, outcome)
                stopService()
            }
        }
    }

    /** Null when connected and verified; otherwise why not. */
    private suspend fun connectOne(server: AmzServer, attempt: Int): AmzConnect.Reason? {
        _connect.value = AmzConnect.Connecting(server.id, AmzConnect.Step.PREPARING, attempt)
        withContext(Dispatchers.IO) { com.mlmvpn.scanner.ui.tunnel.TunnelExclusion.releaseForXray(app) }
        val here = FluxNet.current(app)
        closeRelay()
        val handle = if (server.kind == AmzKind.HY2) null else withContext(Dispatchers.IO) { runCatching { relay?.open(server) }.getOrNull() }
        activeRelay = handle
        // A plain WireGuard server the line does not let through is reached from inside Cloudflare
        // instead (AmzCarrier). Where that is how it last worked here, it goes there first; otherwise
        // it is the second try, after the line itself carried nothing.
        val cfOk = handle == null && server.kind != AmzKind.HY2 && !server.warp && !server.needsAwgCore
        val cfFirst = cfOk && store.current.statsFor(_net.value)[server.id]?.viaCloudflare == true
        val unreachable = setOf(AmzConnect.Reason.NO_HANDSHAKE, AmzConnect.Reason.NO_DATA)
        if (cfFirst) {
            val outcome = connectCf(server, attempt, here)
            if (!wantConnected || outcome == null || outcome !in unreachable) return outcome
        }
        // A relay tries all its ways at once (Proton: IPv6, Cloudflare, IPv4, UDP); after a try that
        // brought nothing it may ask for one more -- each try restarts the tunnel and waits out a
        // handshake, and the next server is the better bet after that.
        var ways = 0
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val outcome = connectVia(server, attempt, here, handle)
            if (!wantConnected || outcome == null) return outcome
            if (outcome !in unreachable || ++ways >= RELAY_WAYS || handle?.retryAfterFailure() != true) {
                if (cfOk && !cfFirst && outcome in unreachable) {
                    val viaCf = connectCf(server, attempt, here)
                    if (!wantConnected || viaCf == null) return viaCf
                }
                return outcome
            }
        }
    }

    /**
     * A way into Cloudflare WARP on the current network, for another engine to carry its own traffic
     * (Windscribe's OpenVPN family). Found and cached as for [connectCf]; null when WARP carries nothing here.
     * Runs probe cores in `:xprobe` the first time on a network.
     */
    suspend fun warpCarrier(context: Context): AmzCarrier.Carrier? {
        init(context)
        val here = FluxNet.current(app)
        return runCatching { AmzCarrier.acquire(app, here, tester, store.current.servers.filter { it.warp }) }.getOrNull()
    }

    /**
     * [server] from inside Cloudflare: an Xray tunnel whose WireGuard to the server rides the carrier
     * (AmzCarrier). Null when connected and verified, else why not.
     */
    private suspend fun connectCf(server: AmzServer, attempt: Int, here: FluxNet.Here): AmzConnect.Reason? {
        val wg = WgConfig.parseConf(server.raw) ?: return AmzConnect.Reason.BAD_CONFIG
        _connect.value = AmzConnect.Connecting(server.id, AmzConnect.Step.PREPARING, attempt)
        // Looking for the carrier runs probe cores, whose sockets a tunnel of ours still up would carry.
        stopService()
        withTimeoutOrNull(5_000) { MyVpnService.connectionPhaseFlow.first { it == MyVpnService.Phase.IDLE || MyVpnService.connectedNodeIdFlow.value != NODE_ID } }
        val carrier = AmzCarrier.acquire(app, here, tester, store.current.servers.filter { it.warp }) ?: return AmzConnect.Reason.NO_DATA
        if (!wantConnected) return null
        val door = withContext(Dispatchers.IO) { java.net.ServerSocket(0).use { it.localPort } }
        val config = AmzCarrier.tunnelConfig(wg, carrier, LocalPort.get(app), door)
        _connect.value = AmzConnect.Connecting(server.id, AmzConnect.Step.STARTING, attempt)
        val up = startTunnel(config)
        if (!wantConnected) return null
        if (!up) return AmzConnect.Reason.BAD_CONFIG
        _connect.value = AmzConnect.Connecting(server.id, AmzConnect.Step.VERIFYING, attempt)
        // This tunnel is Xray's, which leaves the app outside it: the check goes through its HTTP inbound.
        val proxyPort = LocalPort.get(app) + LocalPort.PROBE_OFFSET
        val rtt = canary(proxyPort, attempts = 3) ?: run {
            AmzCarrier.forget(here.key)
            return AmzConnect.Reason.NO_DATA
        }
        if (!wantConnected) return null
        val since = System.currentTimeMillis()
        _connect.value = AmzConnect.Connected(server.id, server.country, rtt, since, via = AmzStat.VIA_CF)
        store.update { AmzBrain.record(it, _net.value, listOf(AmzBrain.Result(server.id, rtt, via = AmzStat.VIA_CF)), since) }
        Log.i(TAG, "connected via Cloudflare: $server in $rtt ms (${carrier.label})")
        scope.launch {
            val cc = exitCountry(proxyPort)
            if (cc != null) {
                store.update { AmzBrain.exitCountry(it, server.id, cc) }
                val st = _connect.value
                if (st is AmzConnect.Connected && st.serverId == server.id) _connect.value = st.copy(country = cc)
            }
        }
        return null
    }

    private suspend fun connectVia(server: AmzServer, attempt: Int, here: FluxNet.Here, handle: RelayHandle?): AmzConnect.Reason? {
        // Local Network sharing used to move a WireGuard server onto Xray here, in the app's own
        // process, for its SOCKS inbound. Stopping that core between attempts panics Xray now and then
        // (WireGuard dialling through another outbound), and a Go panic closes the app: a tester with
        // sharing on lost the app on every Proton and Windscribe WireGuard connect (2026-10-11,
        // SIGABRT in libgojni.so). The AmneziaWG core stays, in its own process, and the share gets
        // the app's own SOCKS port instead ([lanPort]): this app is inside the tunnel, so what that
        // port carries leaves through it.
        val xrayWg = false
        val config = withContext(Dispatchers.IO) { buildConfig(pinnedNow(server, here), here, handle, xrayWg) } ?: return AmzConnect.Reason.BAD_CONFIG
        if (!wantConnected) return null
        _connect.value = AmzConnect.Connecting(server.id, AmzConnect.Step.STARTING, attempt)
        val up = startTunnel(config)
        if (!wantConnected) return null
        if (!up) {
            return if (MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.FAILED && server.kind == AmzKind.HY2) AmzConnect.Reason.VPN_REFUSED
            else AmzConnect.Reason.BAD_CONFIG
        }
        if (server.kind != AmzKind.HY2 && !xrayWg) {
            _connect.value = AmzConnect.Connecting(server.id, AmzConnect.Step.HANDSHAKE, attempt)
            if (!awaitHandshake()) return AmzConnect.Reason.NO_HANDSHAKE
            if (!wantConnected) return null
        }
        _connect.value = AmzConnect.Connecting(server.id, AmzConnect.Step.VERIFYING, attempt)
        val proxyPort = if (server.kind == AmzKind.HY2 || xrayWg) LocalPort.get(app) + LocalPort.PROBE_OFFSET else null
        val rtt = canary(proxyPort, attempts = 3) ?: return AmzConnect.Reason.NO_DATA
        if (!wantConnected) return null
        val since = System.currentTimeMillis()
        handle?.validated()
        handle?.carry()
        _connect.value = AmzConnect.Connected(server.id, server.country, rtt, since, via = handle?.via)
        if (server.kind != AmzKind.HY2) lanPort(true)
        // The relay may move the tunnel to another way later on (the one in use stalled): the screen follows.
        handle?.watchVia { way ->
            val st = _connect.value
            if (st is AmzConnect.Connected && st.serverId == server.id && st.via != way) _connect.value = st.copy(via = way)
        }
        // A relay with several ways (Proton) may now compare their real speed, in the background:
        // the way that answered first is not always the fastest.
        if (handle != null) scope.launch(Dispatchers.IO) {
            delay(TUNE_AFTER_MS)
            if (activeRelay === handle && (_connect.value as? AmzConnect.Connected)?.serverId == server.id) handle.tune { valid -> tunnelSpeed(valid, proxyPort) }
        }
        store.update { AmzBrain.record(it, _net.value, listOf(AmzBrain.Result(server.id, rtt)), since) }
        Log.i(TAG, "connected: ${server} in ${rtt} ms")
        // Where it really exits: asked through the tunnel, and the server moves there.
        scope.launch {
            val cc = exitCountry(proxyPort)
            if (cc != null) {
                store.update { AmzBrain.exitCountry(it, server.id, cc) }
                val st = _connect.value
                if (st is AmzConnect.Connected && st.serverId == server.id) _connect.value = st.copy(country = cc)
            }
        }
        return null
    }

    /**
     * A self-signed Hysteria2 server pinned to the certificate it shows right now (kept for next
     * time); the pin it already has when it shows none -- then the tunnel's own check decides.
     */
    private suspend fun pinnedNow(server: AmzServer, here: FluxNet.Here): AmzServer {
        if (server.kind != AmzKind.HY2 || !server.selfSigned) return server
        val p = runCatching { AmzPins.pin(app, here, server, if (here.cellular) 4_000 else 3_000) }.getOrNull() ?: return server
        if (p.raw != server.raw) store.update { AmzBrain.pin(it, mapOf(server.id to p.raw)) }
        return p
    }

    /** What MyVpnService runs: an AmneziaWG `.conf`, or an Xray config for Hysteria2. */
    private suspend fun buildConfig(server: AmzServer, here: FluxNet.Here, handle: RelayHandle? = null, xrayWg: Boolean = false): String? = when (server.kind) {
        // Junk packets are for a filter watching UDP; inside a relay they would only be carried along.
        AmzKind.WG, AmzKind.AWG -> WgConfig.parseConf(server.raw)?.let { relayed(it, handle) }?.let { wg ->
            val obfuscate = handle == null && store.current.prefs.obfuscate
            if (xrayWg) AmzCarrier.directTunnelConfig(wg, LocalPort.get(app), obfuscate) else wg.toConf(obfuscate = obfuscate, ipv4Only = true)
        }
        AmzKind.HY2 -> {
            val node = (FluxLinkParser.parse(server.raw, server.sourceId) as? FluxLinkParser.Result.Ok)?.node
            if (node == null) null else {
                val ip = Family.ofLiteral(node.server)?.let { node.server }
                    ?: FluxNet.resolve(app, here.network, node.server).let { it[Family.V4] ?: it[Family.V6] }
                ip?.let {
                    val c = FluxCandidate(node = node, family = Family.ofLiteral(it) ?: Family.V4, dialAddress = it)
                    FluxConfigCompiler.tunnel(listOf(c), IpMode.V4, LocalPort.get(app), null, here.cellular, safe = true)
                }
            }
        }
    }

    /** Whose MTU field applies: this engine also carries Proton's servers and Windscribe's WireGuard. */
    private fun mtuProfile(): com.mlmvpn.scanner.utils.NetworkSettings.Method {
        val id = (_connect.value as? AmzConnect.Connecting)?.serverId
        val server = id?.let { i -> store.current.servers.firstOrNull { it.id == i } }
        return when {
            id != null && id in com.mlmvpn.scanner.engines.proton.ProtonEngine.ids.value -> com.mlmvpn.scanner.utils.NetworkSettings.Method.PROTON
            server?.name?.startsWith("Windscribe") == true -> com.mlmvpn.scanner.utils.NetworkSettings.Method.WINDSCRIBE
            else -> com.mlmvpn.scanner.utils.NetworkSettings.Method.AMNEZIA
        }
    }

    /** Hands [cfg] to MyVpnService and waits for its answer (not a blind timeout). */
    private suspend fun startTunnel(cfg: String): Boolean {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val wasUp = MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.CONNECTED
        com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).apply {
            putExtra("NODE_URI", cfg)
            putExtra("MTU_PROFILE", mtuProfile().id)
            putExtra("NODE_ID", NODE_ID)
            putExtra(com.mlmvpn.scanner.utils.GameSessionOwner.EXTRA, gameSessionId)
            putExtra("PROXY_MODE", false)
            putExtra("LOCAL_PORT", LocalPort.getString(app))
        })
        if (wasUp) withTimeoutOrNull(5_000) { MyVpnService.connectionPhaseFlow.first { it != MyVpnService.Phase.CONNECTED } }
        var sawConnecting = false
        val phase = withTimeoutOrNull(30_000) {
            MyVpnService.connectionPhaseFlow.first { p ->
                if (p == MyVpnService.Phase.CONNECTING) sawConnecting = true
                (p == MyVpnService.Phase.CONNECTED && MyVpnService.connectedNodeIdFlow.value == NODE_ID) ||
                    (sawConnecting && (p == MyVpnService.Phase.IDLE || p == MyVpnService.Phase.FAILED))
            }
        }
        return phase == MyVpnService.Phase.CONNECTED
    }

    /** A WireGuard handshake is the server answering. Up to ~12 s (two of WireGuard's retries). */
    private suspend fun awaitHandshake(): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline && wantConnected) {
            val s = AwgHostClient.stats(app)
            if (s != null && s.handshakeAt > 0) return@withContext true
            delay(400)
        }
        false
    }

    /**
     * One real request through the tunnel: for Hysteria2 through its local HTTP inbound (this app
     * is outside its own Xray VPN), for WireGuard straight out (the AmneziaWG VPN carries this app
     * too). The time to the status line, or null.
     */
    private suspend fun canary(proxyPort: Int?, attempts: Int): Long? = withContext(Dispatchers.IO) {
        repeat(attempts) { i ->
            if (!wantConnected) return@withContext null
            // The tunnel's own count around the request: www.gstatic.com answers from Iran without
            // any tunnel too, so a request that went round it must not pass for one it carried.
            val rx0 = if (proxyPort == null) runCatching { AwgHostClient.stats(app)?.rx }.getOrNull() else null
            val ms = runCatching {
                val start = System.nanoTime()
                // HTTPS, its certificate checked, answered 204 (AmzProof): a plain-HTTP request
                // passed on servers that then carried nothing (Ehsan, 31 Oct 2026)
                val ok = Socket(Proxy.NO_PROXY).use { s ->
                    s.soTimeout = 6_000
                    if (proxyPort != null) {
                        s.connect(InetSocketAddress("127.0.0.1", proxyPort), 2_000)
                        AmzProof.https204ViaHttpProxy(s, 6_000)
                    } else {
                        s.connect(InetSocketAddress(AmzProof.HOST, 443), 6_000)
                        AmzProof.https204(s, 6_000)
                    }
                }
                if (ok) (System.nanoTime() - start) / 1_000_000 else null
            }.getOrNull()
            val rx1 = if (rx0 != null && ms != null) runCatching { AwgHostClient.stats(app)?.rx }.getOrNull() else null
            val around = rx0 != null && rx1 != null && rx1 - rx0 < PROOF_MIN_RX
            Log.i(TAG, "canary ${i + 1}/$attempts: ${if (ms != null && !around) "OK ${ms}ms" else if (around) "FAIL (answered, but not through the tunnel)" else "FAIL"}")
            if (ms != null && !around) return@withContext ms
            if (i < attempts - 1) delay(1_200)
        }
        null
    }

    /**
     * One short download through the tunnel (this app is inside the AmneziaWG VPN): bits per second
     * from the first byte to the last -- the connection setup left out -- or null. A sample for
     * comparing ways under the same tunnel a moment apart, not a speed test.
     */
    private fun tunnelSpeed(valid: () -> Boolean, proxyPort: Int? = null): Long? = runCatching {
        // Through Xray's HTTP inbound when Xray carries the tunnel: this app is outside that VPN.
        val c = (proxyPort?.let { java.net.URL(TUNE_URL).openConnection(Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", it))) }
            ?: java.net.URL(TUNE_URL).openConnection()) as java.net.HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.useCaches = false
        try {
            c.inputStream.use { input ->
                val buf = ByteArray(32 * 1024)
                var start = 0L
                var total = 0L
                val deadline = System.nanoTime() + TUNE_MAX_NS
                while (valid()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val t = System.nanoTime()
                    if (start == 0L) start = t else total += n
                    if (t > deadline) break
                }
                val secs = (System.nanoTime() - start) / 1e9
                if (!valid() || start == 0L || total < 200_000 || secs < 0.05) null else (total * 8 / secs).toLong()
            }
        } finally {
            c.disconnect()
        }
    }.getOrNull().also { Log.i(TAG, "tunnel speed sample: ${it?.let { b -> "${b / 1000} kbit/s" } ?: "failed"}") }

    private suspend fun exitCountry(proxyPort: Int?): String? = withContext(Dispatchers.IO) {
        delay(1_500)
        runCatching {
            Socket(Proxy.NO_PROXY).use { s ->
                s.soTimeout = 8_000
                val req = if (proxyPort != null) {
                    s.connect(InetSocketAddress("127.0.0.1", proxyPort), 2_000)
                    "GET http://www.cloudflare.com/cdn-cgi/trace HTTP/1.0\r\nHost: www.cloudflare.com\r\nConnection: close\r\n\r\n"
                } else {
                    s.connect(InetSocketAddress("www.cloudflare.com", 80), 8_000)
                    "GET /cdn-cgi/trace HTTP/1.0\r\nHost: www.cloudflare.com\r\nConnection: close\r\n\r\n"
                }
                s.getOutputStream().write(req.toByteArray())
                s.getInputStream().bufferedReader().readText().lineSequence()
                    .firstOrNull { it.startsWith("loc=") }?.substringAfter('=')?.trim()?.uppercase()
                    ?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } && it != "XX" && it != "T1" }
            }
        }.getOrNull()
    }

    private fun stopService() {
        closeRelay()
        if (MyVpnService.connectedNodeIdFlow.value == NODE_ID) {
            com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).apply { action = "STOP" })
        }
    }

    /** The running Xray tunnel's HTTP inbound, for fetching lists through it; null otherwise. */
    private fun tunnelHttpPort(): Int? =
        if (MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.CONNECTED) LocalPort.get(app) + LocalPort.PROBE_OFFSET else null

    /**
     * For the game booster: the server this network does best on, as a measurable probe config.
     * Null when there is no server (the section was never opened) or it cannot be expressed. The
     * engine's own state is not touched.
     */
    suspend fun probeForGame(context: Context): Pair<String, String>? {
        init(context)
        val net = FluxNet.current(app).key
        // The probe config dials the server straight over the line; one that works here only from
        // inside Cloudflare is not what it would measure.
        val cfOnly = store.current.statsFor(net).filterValues { it.viaCloudflare }.keys
        val s = AmzBrain.best(store.current, net, exclude = cfOnly)
            ?: store.current.lastConnectedId?.takeIf { it !in cfOnly }?.let { id -> store.current.servers.firstOrNull { it.id == id } }
            ?: AmzBrain.promising(store.current, net, null, cfOnly, System.currentTimeMillis())
            ?: return null
        val cfg = tester.probeConfig(s, store.current.prefs.obfuscate) ?: return null
        return s.id to cfg
    }

    fun isOurs(): Boolean = MyVpnService.connectedNodeIdFlow.value == NODE_ID && MyVpnService.connectionPhaseFlow.value != MyVpnService.Phase.IDLE
}
