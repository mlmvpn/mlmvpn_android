package com.mlmvpn.scanner.engines.amnezia

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
    private const val REFRESH_EVERY_MS = 6L * 3_600_000
    private const val AUTO_TEST_AFTER_MS = 30L * 60_000
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
            }
        }
    }

    // ---------------------------------------------------------------- opening the screen

    /** Called when the screen opens: fetch the lists when due, and test when the list is stale. */
    fun onOpen(context: Context) {
        init(context)
        _net.value = FluxNet.current(app).key
        val s = store.current
        val now = System.currentTimeMillis()
        if (s.servers.isEmpty() || now - s.lastRefreshAt > REFRESH_EVERY_MS) {
            refresh(force = s.lastRefreshAt == 0L, thenTest = true)
        } else if (testJob?.isActive != true && lastTestAge(now) > AUTO_TEST_AFTER_MS) {
            test(AmzTestMode.BEST)
        }
    }

    private fun lastTestAge(now: Long): Long =
        now - (store.current.statsFor(_net.value).values.maxOfOrNull { it.lastTestAt } ?: 0L)

    // ---------------------------------------------------------------- lists

    fun refresh(force: Boolean = true, thenTest: Boolean = false) {
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
                if (thenTest && store.current.servers.isNotEmpty()) test(AmzTestMode.BEST)
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

    fun test(mode: AmzTestMode, country: String? = null, kind: AmzKind? = null) {
        val net = FluxNet.current(app).key.also { _net.value = it }
        val picked = AmzBrain.pickForTest(store.current, net, mode, System.currentTimeMillis(), country = country, kind = kind)
        start(picked, mode, net)
    }

    fun testOne(id: String) = testIds(listOf(id), AmzTestMode.ONE)

    private fun testIds(ids: List<String>, mode: AmzTestMode = AmzTestMode.ONE) {
        val set = ids.toHashSet()
        val net = FluxNet.current(app).key.also { _net.value = it }
        start(store.current.servers.filter { it.id in set }, mode, net)
    }

    fun stopTest() {
        testJob?.cancel()
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
            val done = java.util.concurrent.atomic.AtomicInteger()
            val alive = java.util.concurrent.atomic.AtomicInteger()
            // Results reach the list every few hundred ms, not one store write per server.
            val flusher = launch {
                while (isActive) { delay(350); flush(buffer, net, offline = false) }
            }
            try {
                if (!tester.online()) { _notice.value = Notice.Offline; return@launch }
                tester.run(servers, store.current.prefs.obfuscate, object : AmzTester.Sink {
                    override fun result(id: String, ms: Long?, country: String?) {
                        buffer[id] = AmzBrain.Result(id, ms, country)
                        val d = done.incrementAndGet()
                        val a = if (ms != null) alive.incrementAndGet() else alive.get()
                        _pending.value = _pending.value - id
                        _progress.value = _progress.value.copy(done = d, alive = a)
                    }

                    override fun skip(id: String) {
                        _pending.value = _pending.value - id
                        _progress.value = _progress.value.copy(done = done.incrementAndGet())
                    }
                })
                flusher.cancel()
                // Everything failed: either every server is dead, or the network went away under
                // the test. Only the second is checked -- and then nothing is learned or removed.
                val offline = alive.get() == 0 && servers.size >= 5 && !tester.online()
                if (offline) _notice.value = Notice.Offline
                flush(buffer, net, offline)
            } finally {
                flusher.cancel()
                withContext(kotlinx.coroutines.NonCancellable) { flush(buffer, net, offline = false) }
                _pending.value = emptySet()
                _progress.value = _progress.value.copy(running = false)
            }
        }
    }

    private fun flush(buffer: ConcurrentHashMap<String, AmzBrain.Result>, net: String, offline: Boolean) {
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
    fun connectAsync(context: Context, serverId: String? = null, country: String? = null) {
        init(context)
        wantConnected = true
        connectJob?.cancel()
        connectJob = scope.launch {
            try { connectLoop(serverId, country) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "connect crashed", e)
                if (wantConnected) _connect.value = AmzConnect.Failed(serverId, AmzConnect.Reason.NO_DATA)
            }
        }
    }

    fun disconnectAsync(context: Context) {
        init(context)
        wantConnected = false
        connectJob?.cancel()
        _connect.value = AmzConnect.Idle
        if (MyVpnService.connectedNodeIdFlow.value == NODE_ID) {
            context.applicationContext.startService(Intent(context.applicationContext, MyVpnService::class.java).apply { action = "STOP" })
        }
    }

    private suspend fun connectLoop(serverId: String?, country: String?) {
        val net = FluxNet.current(app).key.also { _net.value = it }
        if (!FluxNet.online(app)) { _connect.value = AmzConnect.Failed(serverId, AmzConnect.Reason.OFFLINE); return }
        val tried = HashSet<String>()
        val first = serverId?.let { id -> store.current.servers.firstOrNull { it.id == id } }
            ?: AmzBrain.best(store.current, net, country)
            ?: store.current.lastConnectedId?.let { id -> store.current.servers.firstOrNull { it.id == id } }
            ?: AmzBrain.promising(store.current, net, country, emptySet(), System.currentTimeMillis())
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
            server = AmzBrain.best(store.current, net, fallbackCountry, tried) ?: AmzBrain.best(store.current, net, null, tried)
                ?: AmzBrain.promising(store.current, net, fallbackCountry, tried, System.currentTimeMillis())
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
        val config = withContext(Dispatchers.IO) { buildConfig(server, here) } ?: return AmzConnect.Reason.BAD_CONFIG
        if (!wantConnected) return null
        _connect.value = AmzConnect.Connecting(server.id, AmzConnect.Step.STARTING, attempt)
        val up = startTunnel(config)
        if (!wantConnected) return null
        if (!up) {
            return if (MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.FAILED && server.kind == AmzKind.HY2) AmzConnect.Reason.VPN_REFUSED
            else AmzConnect.Reason.BAD_CONFIG
        }
        if (server.kind != AmzKind.HY2) {
            _connect.value = AmzConnect.Connecting(server.id, AmzConnect.Step.HANDSHAKE, attempt)
            if (!awaitHandshake()) return AmzConnect.Reason.NO_HANDSHAKE
            if (!wantConnected) return null
        }
        _connect.value = AmzConnect.Connecting(server.id, AmzConnect.Step.VERIFYING, attempt)
        val proxyPort = if (server.kind == AmzKind.HY2) LocalPort.get(app) + LocalPort.PROBE_OFFSET else null
        val rtt = canary(proxyPort, attempts = 3) ?: return AmzConnect.Reason.NO_DATA
        if (!wantConnected) return null
        val since = System.currentTimeMillis()
        _connect.value = AmzConnect.Connected(server.id, server.country, rtt, since)
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

    /** What MyVpnService runs: an AmneziaWG `.conf`, or an Xray config for Hysteria2. */
    private suspend fun buildConfig(server: AmzServer, here: FluxNet.Here): String? = when (server.kind) {
        AmzKind.WG, AmzKind.AWG -> WgConfig.parseConf(server.raw)?.toConf(obfuscate = store.current.prefs.obfuscate, ipv4Only = true)
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

    /** Hands [cfg] to MyVpnService and waits for its answer (not a blind timeout). */
    private suspend fun startTunnel(cfg: String): Boolean {
        val wasUp = MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.CONNECTED
        app.startService(Intent(app, MyVpnService::class.java).apply {
            putExtra("NODE_URI", cfg)
            putExtra("NODE_ID", NODE_ID)
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
            val ms = runCatching {
                val start = System.nanoTime()
                Socket(Proxy.NO_PROXY).use { s ->
                    s.soTimeout = 6_000
                    if (proxyPort != null) {
                        s.connect(InetSocketAddress("127.0.0.1", proxyPort), 2_000)
                        s.getOutputStream().write("GET http://www.gstatic.com/generate_204 HTTP/1.0\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        s.connect(InetSocketAddress("www.gstatic.com", 80), 6_000)
                        s.getOutputStream().write("GET /generate_204 HTTP/1.0\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                    val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                    if (line.contains(" 204") || line.contains(" 200")) (System.nanoTime() - start) / 1_000_000 else null
                }
            }.getOrNull()
            Log.i(TAG, "canary ${i + 1}/$attempts: ${ms?.let { "OK ${it}ms" } ?: "FAIL"}")
            if (ms != null) return@withContext ms
            if (i < attempts - 1) delay(1_200)
        }
        null
    }

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
        if (MyVpnService.connectedNodeIdFlow.value == NODE_ID) {
            app.startService(Intent(app, MyVpnService::class.java).apply { action = "STOP" })
        }
    }

    /** The running Xray tunnel's HTTP inbound, for fetching lists through it; null otherwise. */
    private fun tunnelHttpPort(): Int? =
        if (MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.CONNECTED) LocalPort.get(app) + LocalPort.PROBE_OFFSET else null

    fun isOurs(): Boolean = MyVpnService.connectedNodeIdFlow.value == NODE_ID && MyVpnService.connectionPhaseFlow.value != MyVpnService.Phase.IDLE
}
