package com.mlmvpn.scanner.engines.flux

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.util.Log
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.engines.flux.core.compile.FluxConfigCompiler
import com.mlmvpn.scanner.engines.flux.core.country.EgressVerifier
import com.mlmvpn.scanner.engines.flux.core.memory.FluxMemory
import com.mlmvpn.scanner.engines.flux.core.memory.FluxPrefs
import com.mlmvpn.scanner.engines.flux.core.memory.FluxState
import com.mlmvpn.scanner.engines.flux.core.model.FailReason
import com.mlmvpn.scanner.engines.flux.core.model.FailureKind
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.FluxNode
import com.mlmvpn.scanner.engines.flux.core.model.FluxUiState
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.model.NetVerdict
import com.mlmvpn.scanner.engines.flux.core.plan.FluxLearner
import com.mlmvpn.scanner.engines.flux.core.plan.FluxPlanner
import com.mlmvpn.scanner.engines.flux.core.race.FluxProbe
import com.mlmvpn.scanner.engines.flux.core.race.FluxRacer
import com.mlmvpn.scanner.engines.flux.core.score.FluxScorer
import com.mlmvpn.scanner.utils.LocalPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * MLM FLUX Engine: the control plane. The data plane is the app's existing Xray core, run by
 * [MyVpnService] like every other engine -- FLUX only decides what it carries.
 *
 * The user gives three things: a country (or Automatic), an IP mode, and Connect. FLUX:
 *  1. on a network it knows, connects at once to the route that worked there (warm start), with
 *     two standbys behind the same balancer;
 *  2. otherwise measures the network (is Cloudflare reachable? is there IPv6?), races the nodes
 *     that can work on it, and connects to the best -- not merely the first -- answer;
 *  3. says "connected" only after a real request through the running tunnel succeeded;
 *  4. keeps watching: a failed check moves to a standby, a new network gets its own route.
 *
 * Independent of MAE: nothing here needs MAE running. MAE reads FLUX's proven routes through
 * [FluxRoute], from the store only, so the two never probe or start tunnels for each other.
 */
object FluxEngine {

    private const val TAG = "FLUX"
    const val NODE_ID = "flux"

    private lateinit var app: Context
    lateinit var store: FluxStore
        private set
    private lateinit var sources: FluxSourceRepo
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _ui = MutableStateFlow<FluxUiState>(FluxUiState.Idle)
    val ui: StateFlow<FluxUiState> = _ui

    private val _diag = MutableStateFlow(FluxDiagnostics())
    val diagnostics: StateFlow<FluxDiagnostics> = _diag

    @Volatile private var wantConnected = false
    @Volatile private var connectJob: Job? = null
    @Volatile private var watchJob: Job? = null
    /** Searches a failover has run without finding a route; back to 0 on every good connect. */
    @Volatile private var failoverRetries = 0
    /** The routes in the running tunnel, primary first, and the network they were chosen for. */
    @Volatile private var inTunnel: List<FluxCandidate> = emptyList()
    @Volatile private var tunnelNet: String = ""
    @Volatile private var resolvedCache: Pair<String, Map<String, Map<Family, String>>>? = null
    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        store = FluxStore(app.filesDir)
        sources = FluxSourceRepo(app)
        store.update { FluxMemory.prune(it, System.currentTimeMillis()) }
        // Verdicts measured with the first IPv6 edge sampler (random addresses across whole /32s)
        // could say "Cloudflare v6 cut" where it works: measured again once.
        val fp = app.getSharedPreferences("flux_sources", Context.MODE_PRIVATE)
        if (fp.getInt("verdict_gen", 0) < 2) {
            store.update { s -> s.copy(nets = s.nets.mapValues { (_, n) -> n.copy(verdict = n.verdict.copy(at = 0L)) }) }
            fp.edit().putInt("verdict_gen", 2).apply()
        }
        watchNetwork()
        observeSession()
        initialized = true
    }

    /**
     * Another engine took the VPN, or it went down from outside (the system, the tile): FLUX stops
     * claiming a connection it no longer has.
     */
    private fun observeSession() {
        scope.launch {
            kotlinx.coroutines.flow.combine(MyVpnService.connectionPhaseFlow, MyVpnService.connectedNodeIdFlow) { phase, id ->
                phase != MyVpnService.Phase.IDLE && id == NODE_ID
            }.collect { ours ->
                if (!ours && _ui.value is FluxUiState.Connected) {
                    _ui.value = FluxUiState.Idle
                    wantConnected = false
                    watchJob?.cancel()
                }
            }
        }
    }

    fun isInitialized() = initialized

    // ---------------------------------------------------------------- what the screen asks

    fun prefs(): FluxPrefs = store.current.prefs

    fun setCountry(cc: String?) { store.update { it.copy(prefs = it.prefs.copy(country = cc?.uppercase())) } }

    fun setIpMode(mode: IpMode) { store.update { it.copy(prefs = it.prefs.copy(ipMode = mode)) } }

    /**
     * A country in the picker. [verified]: an exit there was measured. [availableHere]: one
     * worked on this network in the last day. Neither: only node names claim it -- choosing it
     * races those nodes first and accepts only an exit measured there.
     */
    data class CountryOption(val code: String, val verified: Boolean, val availableHere: Boolean)

    fun countries(): List<CountryOption> {
        val now = System.currentTimeMillis()
        val s = store.current
        val here = FluxMemory.countriesHere(s, FluxNet.current(app).key, now).toSet()
        val proven = FluxMemory.provenCountries(s, now).toSet()
        val hinted = nodes().mapNotNull { it.countryHint }.groupingBy { it }.eachCount().filter { it.value >= 2 }.keys
        return (proven + hinted).map { CountryOption(it, it in proven, it in here) }
            .sortedWith(compareByDescending<CountryOption> { it.availableHere }.thenByDescending { it.verified }.thenBy { it.code })
    }

    /**
     * The user changed the country or the IP version. While connected, FLUX moves to a route for
     * the new choice (its known route here if there is one, else a race); otherwise nothing yet.
     */
    fun applyChoice(context: Context) {
        init(context)
        if (!wantConnected || !isOurs()) return
        FluxLog.i("choice changed while connected (${prefs().country ?: "auto"}/${prefs().ipMode}); re-planning")
        connectJob?.cancel(); watchJob?.cancel()
        connectJob = scope.launch { runCatching { connect() }.onFailure { if (it !is kotlinx.coroutines.CancellationException) FluxLog.w("re-plan failed", it) } }
    }

    fun isOurs(): Boolean = MyVpnService.connectedNodeIdFlow.value == NODE_ID && MyVpnService.connectionPhaseFlow.value != MyVpnService.Phase.IDLE

    fun userSubscriptions(): List<String> = sources.userSubscriptions()

    fun setUserSubscriptions(urls: List<String>) {
        sources.setUserSubscriptions(urls)
        scope.launch(Dispatchers.IO) { runCatching { sources.refreshDue(onWifi = true, tunnelHttpPort = tunnelHttpPort(), force = true) } }
    }

    // ---------------------------------------------------------------- connect / disconnect

    /** After the VPN permission is granted. Idempotent while a connect is already running. */
    fun connectAsync(context: Context) {
        init(context)
        wantConnected = true
        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            try { connect() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                FluxLog.w("connect crashed", e)
                if (wantConnected) _ui.value = FluxUiState.Failed(FailureKind.NOTHING_WORKS)
            }
        }
    }

    fun disconnectAsync(context: Context) {
        init(context)
        wantConnected = false
        connectJob?.cancel(); watchJob?.cancel()
        inTunnel = emptyList()
        _ui.value = FluxUiState.Idle
        com.mlmvpn.scanner.utils.SafeStart.service(context.applicationContext, Intent(context.applicationContext, MyVpnService::class.java).apply { action = "STOP" })
    }

    /**
     * A config for THIS network without racing, for MyVpnService's reconnect after a network drop
     * and for the Quick Settings tile: the known route here, or null when there is none (the
     * caller then connects through [connectAsync], or keeps what it had).
     */
    fun freshConfig(context: Context): String? {
        init(context)
        val here = FluxNet.current(app)
        val p = prefs()
        val warm = FluxPlanner.warm(input(here, p.country, p.ipMode, nodes(), verdictFor(here.key), resolvedFor(here.key)))
        if (warm.isEmpty()) return null
        inTunnel = warm; tunnelNet = here.key
        return compile(warm, p.ipMode, here.cellular)
    }

    /**
     * [failover]: the route in use stopped answering while connected. The tunnel and the
     * "connected" state are kept while a replacement is raced (make before break): the tunnel is
     * replaced only by a route that has answered, not if the old one answers again meanwhile,
     * and a search that finds nothing does not take the VPN down at once.
     */
    private suspend fun connect(failover: Boolean = false) {
        val before = _ui.value as? FluxUiState.Connected
        if (!failover) _ui.value = FluxUiState.Searching
        val now0 = System.currentTimeMillis()
        val here = FluxNet.current(app)
        val p = prefs()
        FluxLog.i("==== connect: net=${here.key} wifi=${here.wifi} cellular=${here.cellular} mode=${p.ipMode} country=${p.country ?: "auto"}")
        if (!FluxNet.online(app)) { fail(FluxUiState.Failed(FailureKind.OFFLINE), "no underlying network"); return }
        store.update { FluxMemory.markSeen(it, here.key, now0) }

        withContext(Dispatchers.IO) { runCatching { sources.refreshOwn() } }
        var nodes = nodes()
        if (nodes.isEmpty()) {
            // A fresh install: nothing cached yet. The one time a connect waits for the lists.
            FluxLog.i("no cached nodes: fetching the sources now")
            withContext(Dispatchers.IO) { sources.refreshDue(here.wifi, null, force = true) }
            nodes = nodes()
            if (nodes.isEmpty()) { fail(FluxUiState.Failed(FailureKind.NO_NODES), "every source failed and nothing is cached"); return }
        }
        FluxLog.i("nodes: ${nodes.size} " + nodes.groupingBy { "${it.proto.name.lowercase()}/${it.transport.code}/${it.security.name.lowercase()}" }.eachCount()
            + " by source " + nodes.groupingBy { it.sourceId }.eachCount())
        if (nodes.isNotEmpty()) {
            scope.launch(Dispatchers.IO) { runCatching { sources.refreshDue(here.wifi, tunnelHttpPort()) } }
        }

        // ---- warm: a known route on this network, no race.
        val storedVerdict = verdictFor(here.key)
        val warm = FluxPlanner.warm(input(here, p.country, p.ipMode, nodes, storedVerdict, resolvedFor(here.key)))
        if (warm.any { it.fragment == com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile.ECH }) {
            com.mlmvpn.scanner.engines.flux.core.outbound.FluxOutbounds.echKey = withContext(Dispatchers.IO) { FluxNet.echKey(here.network, here.key) }
        }
        if (failover) FluxLog.i("failover: racing for a replacement while the current tunnel stays up")
        else if (warm.isNotEmpty()) {
            FluxLog.i("warm start: ${warm.size} known route(s) on this network: ${warm.joinToString(" | ")}")
            if (startAndVerify(warm, p.ipMode, here)) { afterConnected(here, p); return }
            FluxLog.i("warm route failed; benching it and racing")
            sitOut(here.key, warm.first().id)
            // The standbys were in the same tunnel and it still failed: the race decides.
        }
        else FluxLog.i("no known route on this network for ${FluxState.policyKey(p.country, p.ipMode)}: cold start")
        if (!wantConnected) return

        // ---- cold: measure the network and resolve server names (side by side), then race.
        val (verdict, resolved) = coroutineScope {
            val vJob = async {
                if (storedVerdict.stale(now0)) FluxNet.measureVerdict(app, here, now0).also { v ->
                    store.update { s -> FluxMemory.setVerdict(s, here.key, mergeVerdict(s.nets[here.key]?.verdict, v)) }
                } else storedVerdict
            }
            val rJob = async { resolve(here, nodes) }
            // The ECH key, for the ECH candidates this race will hold.
            val eJob = async(Dispatchers.IO) { FluxNet.echKey(here.network, here.key) }
            com.mlmvpn.scanner.engines.flux.core.outbound.FluxOutbounds.echKey = eJob.await()
            vJob.await() to rJob.await()
        }
        val v = verdictFor(here.key).takeIf { !it.stale(now0) } ?: verdict
        FluxLog.i("verdict: cloudflare v4=${v.cfV4} v6=${v.cfV6} ipv6=${v.v6} udp=${v.udp} (cut=${v.cloudflareCut}, measured ${(System.currentTimeMillis() - v.at) / 1000}s ago); resolved ${resolved.size} server names in ${System.currentTimeMillis() - now0} ms")
        if (FluxPlanner.families(p.ipMode, v).isEmpty()) { fail(FluxUiState.Failed(FailureKind.FAMILY_UNAVAILABLE), "mode ${p.ipMode} has no usable family here"); return }

        // Up to three rounds, each on candidates not raced yet in this connect: public nodes come
        // and go, and a round that found nothing (or a winner that failed in the tunnel) should
        // move on to fresh ones rather than measure the same dead ones again.
        val tried = HashSet<String>()
        for (round in 1..ROUNDS) {
            if (!wantConnected) return
            val inp = input(here, p.country, p.ipMode, nodes, v, resolved).copy(budget = if (here.wifi) 40 else 28)
            val all = FluxPlanner.candidates(inp.copy(budget = Int.MAX_VALUE))
            val cands = all.filterNot { it.id in tried }.take(inp.budget)
            FluxLog.i("round $round: ${all.size} candidates possible, ${cands.size} new to race: " +
                cands.groupingBy { "${it.node.proto.name.lowercase()}${if (it.edge != null) "+edge" else ""}/v${it.family.code}${if (it.fragment.code != "0") "+frag" else ""}" }.eachCount())
            if (cands.isEmpty()) break
            tried += cands.map { it.id }
            val routes = race(here, p, cands) ?: continue
            if (!wantConnected) return
            if (failover && _ui.value is FluxUiState.Connected) {
                // The route in use may have come back while the race ran: then nothing is replaced.
                canary(attempts = 1, timeoutMs = HEALTH_TIMEOUT_MS)?.let {
                    FluxLog.i("failover: the current route answers again (${it} ms); keeping it")
                    rearmWatch(); return
                }
                // From here the tunnel is replaced: "connected" would be untrue for the moment it takes.
                _ui.value = FluxUiState.Searching
            }
            if (startAndVerify(routes, p.ipMode, here)) { afterConnected(here, p); return }
            FluxLog.i("round $round: winner did not carry traffic in the tunnel; benching it")
            sitOut(here.key, routes.first().id)
        }
        if (!wantConnected) return
        if (failover) {
            // Nothing better found. If what is up still answers, it stays; if not, the search runs
            // again with the tunnel kept, a couple of times, before the VPN is given up on.
            val rtt = canary(attempts = 2, timeoutMs = HEALTH_TIMEOUT_MS)
            if (rtt != null) {
                FluxLog.i("failover: nothing better after ${tried.size} candidates, but the tunnel answers (${rtt} ms); keeping it")
                if (_ui.value !is FluxUiState.Connected) {
                    _ui.value = before?.copy(latencyMs = rtt) ?: FluxUiState.Connected(null, inTunnel.firstOrNull()?.family ?: Family.V4, rtt)
                }
                rearmWatch(); return
            }
            if (failoverRetries < FAILOVER_RETRIES) {
                failoverRetries++
                FluxLog.w("failover: no route after ${tried.size} candidates; searching again in ${FAILOVER_PAUSE_MS / 1000} s ($failoverRetries/$FAILOVER_RETRIES)")
                delay(FAILOVER_PAUSE_MS)
                if (wantConnected && isOurs()) connect(failover = true)
                return
            }
        }
        fail(failureFor(p, here), "no route after ${tried.size} candidates")
        stopService()
    }

    /** Goes back to watching the tunnel that is up (after a failover that kept it). */
    private fun rearmWatch() {
        watchJob?.cancel()
        watchJob = scope.launch { watch() }
    }

    /** Races [cands]; returns primary + standbys, or null when nothing passed. */
    private suspend fun race(here: FluxNet.Here, p: FluxPrefs, cands: List<FluxCandidate>): List<FluxCandidate>? {
        val probe = FluxAndroidProbe(app, here.network, mobile = here.cellular)
        val conc = FluxNet.concurrency(app, here)
        // Standbys, not only a winner: the tunnel's balancer moves to a standby on its own when the
        // primary dies, but a race that stopped 250 ms after the first answer almost never had one
        // on a slow line -- so every dead primary meant a new search and a tunnel restart.
        val racer = FluxRacer(probe, FluxRacer.Config(reachConcurrency = conc, realBatch = minOf(10, conc), graceMs = STANDBY_GRACE_MS, enough = STANDBYS))
        val t0 = System.currentTimeMillis()
        val out = try {
            racer.race(cands,
                accept = { _, r -> EgressVerifier.accepts(r.egress, p.country, System.currentTimeMillis()) },
                score = { c, r -> FluxScorer.score(c, FluxMemory.metrics(store.current, here.key, c.id), r.rttMs, System.currentTimeMillis()) })
        } finally { withContext(Dispatchers.IO) { probe.close() } }
        val now = System.currentTimeMillis()
        store.update { FluxLearner.afterRace(it, here.key, now, p.country, p.ipMode, cands, out) }
        _diag.value = _diag.value.copy(
            net = here.key, verdict = store.current.nets[here.key]?.verdict, lastRaceMs = now - t0,
            lastRace = cands.take(60).map { c ->
                val r = out.real[c.id]; val rc = out.reach[c.id]
                FluxDiagnostics.Row(c.toString(), c.id, rc?.ok, rc?.ms, r?.ok, r?.rttMs, (r?.reason ?: rc?.reason)?.code,
                    store.current.egress[c.egressKey]?.countryCode,
                    out.ranked.firstOrNull { it.first.id == c.id }?.let { FluxScorer.score(c, FluxMemory.metrics(store.current, here.key, c.id), it.second.rttMs, now) })
            },
        )
        FluxLog.i("race: ${cands.size} candidates, ${out.reach.count { it.value.ok }} reachable, ${out.real.count { it.value.ok }} answered, ${out.ranked.size} accepted, ${out.refused.size} wrong country, ${now - t0} ms")
        out.reach.values.filter { !it.ok }.groupingBy { it.reason?.name ?: "?" }.eachCount().takeIf { it.isNotEmpty() }?.let { FluxLog.i("race: stage-1 failures by reason $it") }
        out.real.values.filter { !it.ok }.groupingBy { it.reason?.name ?: "?" }.eachCount().takeIf { it.isNotEmpty() }?.let { FluxLog.i("race: stage-2 failures by reason $it") }
        out.ranked.forEachIndexed { i, (c, r) -> FluxLog.i("race: #${i + 1} ${r.rttMs}ms exit=${r.egress?.countryCode ?: "?"} | $c") }
        if (out.ranked.isEmpty()) return null
        val ids = FluxLearner.pickRoutes(out.ranked.map { it.first })
        val byId = out.ranked.associate { it.first.id to it.first }
        return ids.mapNotNull { byId[it] }
    }

    // ---------------------------------------------------------------- the tunnel

    private suspend fun startAndVerify(routes: List<FluxCandidate>, mode: IpMode, here: FluxNet.Here): Boolean {
        if (!wantConnected) return false
        withContext(Dispatchers.IO) { com.mlmvpn.scanner.ui.tunnel.TunnelExclusion.releaseForXray(app) }
        if (!wantConnected) return false
        // The full config (primary + standbys behind a balancer) first; if this core refuses it,
        // the primary alone -- a route that raced fine is not thrown away over the balancer.
        var used = routes
        var up = launch(compile(routes, mode, here.cellular, safe = false))
        if (!up && wantConnected && routes.size > 1 && MyVpnService.connectionPhaseFlow.value != MyVpnService.Phase.FAILED) {
            FluxLog.w("tunnel config did not start; retrying with the primary route alone")
            used = routes.take(1)
            up = launch(compile(used, mode, here.cellular, safe = true))
        }
        if (!up) {
            FluxLog.w("tunnel did not come up (service phase ${MyVpnService.connectionPhaseFlow.value})")
            if (MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.FAILED) _ui.value = FluxUiState.Failed(FailureKind.VPN_REFUSED)
            return false
        }
        FluxLog.i("tunnel core up with ${used.size} route(s); checking a real request through it")
        inTunnel = used; tunnelNet = here.key
        // ...and FLUX says "connected" only once a real request went through it.
        val rtt = canary(attempts = 3)
        if (rtt == null) { FluxLog.w("tunnel up but no request went through it (3 tries)"); return false }
        FluxLog.i("CONNECTED: ${rtt} ms through the tunnel | primary ${used.first()} -> ${used.first().dialAddress}")
        val primary = used.first()
        store.update { FluxLearner.liveSuccess(it, here.key, primary.id, System.currentTimeMillis(), rtt) }
        val country = store.current.egress[primary.egressKey]?.countryCode
        _ui.value = FluxUiState.Connected(country, primary.family, rtt)
        _diag.value = _diag.value.copy(inTunnel = used.map { it.toString() }, why = why(primary, here.key, rtt))
        return true
    }

    /**
     * Hands [cfg] to MyVpnService and waits for its answer: true once the core is up, false as
     * soon as the service gives up (the core refused the config, the VPN was refused) -- not
     * after a blind timeout.
     */
    private suspend fun launch(cfg: String): Boolean {
        FluxLog.i("tunnel: starting config (${cfg.length} chars, ${JSONObject(cfg).optString("remarks")})")
        val wasUp = MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.CONNECTED
        com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).apply {
            putExtra("NODE_URI", cfg)
            putExtra("MTU_PROFILE", com.mlmvpn.scanner.utils.NetworkSettings.Method.FLUX.id)
            putExtra("NODE_ID", NODE_ID)
            putExtra("PROXY_MODE", false)
            putExtra("LOCAL_PORT", LocalPort.getString(app))
        })
        // Replacing a running tunnel: wait for the old one to go down first, or the check after
        // this would be answered by the tunnel that is being replaced.
        if (wasUp) withTimeoutOrNull(5_000) { MyVpnService.connectionPhaseFlow.first { it != MyVpnService.Phase.CONNECTED } }
        var sawConnecting = false
        val phase = withTimeoutOrNull(15_000) {
            MyVpnService.connectionPhaseFlow.first { p ->
                if (p == MyVpnService.Phase.CONNECTING) sawConnecting = true
                (p == MyVpnService.Phase.CONNECTED && MyVpnService.connectedNodeIdFlow.value == NODE_ID) ||
                    (sawConnecting && (p == MyVpnService.Phase.IDLE || p == MyVpnService.Phase.FAILED))
            }
        }
        FluxLog.i("tunnel: service answered ${phase ?: "nothing within 15 s"}")
        return phase == MyVpnService.Phase.CONNECTED
    }

    private fun compile(routes: List<FluxCandidate>, mode: IpMode, mobile: Boolean, safe: Boolean = false): String {
        val primaryUdp = routes.first().node.carriesUdp
        val sink = if (!primaryUdp) com.mlmvpn.scanner.utils.XrayJsonGenerator.quicRefusalOutbound() else null
        val cfg = FluxConfigCompiler.tunnel(routes, mode, LocalPort.get(app), sink, mobile, safe)
        return if (sink == null) cfg else JSONObject(cfg).also { com.mlmvpn.scanner.utils.XrayJsonGenerator.addQuicRefusalPolicyTo(it) }.toString()
    }

    private fun stopService() {
        if (MyVpnService.connectedNodeIdFlow.value == NODE_ID) {
            com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).apply { action = "STOP" })
        }
    }

    private fun tunnelHttpPort(): Int? = if (isOurs()) LocalPort.get(app) + 10000 else null

    /**
     * One real request through the running tunnel's own HTTP inbound -- the same path an app's
     * traffic takes (the balancer, the primary or a standby). The time to the status line, or null.
     */
    private suspend fun canary(attempts: Int, timeoutMs: Int = 6_000): Long? = withContext(Dispatchers.IO) {
        val port = LocalPort.get(app) + 10000
        repeat(attempts) { i ->
            val ms = runCatching {
                val start = System.nanoTime()
                Socket(Proxy.NO_PROXY).use { s ->
                    s.soTimeout = timeoutMs
                    s.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                    s.getOutputStream().write("GET http://www.gstatic.com/generate_204 HTTP/1.0\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
                    val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                    if (line.contains(" 204") || line.contains(" 200")) (System.nanoTime() - start) / 1_000_000 else null
                }
            }.getOrNull()
            FluxLog.i("canary ${i + 1}/$attempts via 127.0.0.1:$port: ${ms?.let { "OK ${it}ms" } ?: "FAIL"}")
            if (ms != null) return@withContext ms
            if (i < attempts - 1) delay(1_000)
        }
        null
    }

    // ---------------------------------------------------------------- after connecting

    private fun afterConnected(here: FluxNet.Here, p: FluxPrefs) {
        failoverRetries = 0
        watchJob?.cancel()
        watchJob = scope.launch {
            launch { background(here, p) }
            watch()
        }
    }

    /**
     * Health checks while connected: every minute at first, stretching to five while the route
     * stays healthy, and only with the screen on (a phone in a pocket is not watched). Two failed
     * checks in a row move to a new route without waiting for the user.
     */
    private suspend fun watch() {
        var interval = 60_000L
        var failures = 0
        while (wantConnected) {
            delay(interval)
            if (!isOurs()) return
            if (!screenOn()) continue
            // Two tries with a longer wait: one slow answer on a busy line is not a dead route (a
            // check failed and the next request went through in 267 ms, on a phone).
            val rtt = canary(attempts = 2, timeoutMs = HEALTH_TIMEOUT_MS)
            FluxLog.i("health: ${rtt?.let { "OK ${it}ms" } ?: "FAIL"} (next check in ${if (rtt != null) minOf(interval * 2, 5 * 60_000L) / 1000 else 20}s)")
            val primary = inTunnel.firstOrNull() ?: return
            if (rtt != null) {
                failures = 0
                interval = minOf(interval * 2, 5 * 60_000L)
                store.update { FluxLearner.liveSuccess(it, tunnelNet, primary.id, System.currentTimeMillis(), rtt) }
                val st = _ui.value
                if (st is FluxUiState.Connected) _ui.value = st.copy(latencyMs = rtt)
                continue
            }
            failures++
            interval = 20_000L
            store.update { FluxLearner.liveFailure(it, tunnelNet, primary.id, System.currentTimeMillis(), FailReason.HTTP_TIMEOUT) }
            if (failures >= 2 && FluxNet.online(app)) {
                FluxLog.w("health: route stopped working (2 checks); finding another")
                _diag.value = _diag.value.copy(failovers = _diag.value.failovers + 1)
                // The failed primary sits out, so the plan picks a standby or races anew.
                sitOut(tunnelNet, primary.id)
                connectJob?.cancel()
                connectJob = scope.launch { runCatching { connect(failover = true) } }
                return
            }
        }
    }

    /**
     * What a connect leaves for later, so it never delays the connect: a second opinion on the
     * exit's country, a small throughput sample (budgeted on mobile data), a fresh network verdict.
     */
    private suspend fun background(here: FluxNet.Here, p: FluxPrefs) {
        delay(4_000)
        val primary = inTunnel.firstOrNull() ?: return
        val port = LocalPort.get(app) + 10000
        // Country: three sources through the tunnel; two agreeing make the proof.
        val obs = coroutineScope {
            listOf(EgressVerifier.TRACE, EgressVerifier.IP_API, EgressVerifier.IPWHO).map { src ->
                async(Dispatchers.IO) { httpGet(port, src.url, 8 * 1024)?.let { EgressVerifier.parse(src.id, it) } }
            }.awaitAll().filterNotNull()
        }
        FluxLog.i("background: exit seen by ${obs.size}/3 echo services: " + obs.joinToString { "${it.source}=${it.countryCode}" })
        if (obs.isNotEmpty()) {
            var id = EgressVerifier.combine(obs, System.currentTimeMillis())
            if (id.countryCode == null && obs.size >= 2) {
                // The sources disagree (a device saw ip-api=DE, ipwho=US): a fourth breaks the tie.
                val tie = withContext(Dispatchers.IO) { httpGet(port, EgressVerifier.IFCONFIG.url, 8 * 1024)?.let { EgressVerifier.parse(EgressVerifier.IFCONFIG.id, it) } }
                FluxLog.i("background: sources disagree; ifconfig says ${tie?.countryCode ?: "nothing"}")
                if (tie != null) id = EgressVerifier.combine(obs + tie, System.currentTimeMillis())
            }
            store.update { FluxMemory.recordEgress(it, primary.egressKey, id) }
            val st = _ui.value
            if (st is FluxUiState.Connected && id.countryCode != null) _ui.value = st.copy(countryCode = id.countryCode)
            // Chosen a country, but the exit turns out to be elsewhere: the route is wrong for it.
            if (p.country != null && id.countryCode != null && !id.countryCode.equals(p.country, true) && id.confidence >= 1.0) {
                FluxLog.w("exit is ${id.countryCode}, not ${p.country}; re-planning")
                store.update { FluxMemory.setBest(it, here.key, p.country, p.ipMode, FluxMemory.best(it, here.key, p.country, p.ipMode).drop(1)) }
                connectJob?.cancel(); connectJob = scope.launch { runCatching { connect() } }
                return
            }
        }
        // Throughput: Wi-Fi 1 MB, mobile 256 KB, and on mobile only within today's budget.
        val bytes = if (here.wifi) 1_000_000 else 256_000
        val now = System.currentTimeMillis()
        if (here.wifi || FluxMemory.spentToday(store.current, now) + bytes <= MOBILE_BUDGET_BYTES) {
            val kbps = throughput(port, bytes)
            if (here.cellular) store.update { FluxMemory.spend(it, now, bytes.toLong()) }
            FluxLog.i("background: throughput ${kbps?.let { "%.1f Mbps".format(it / 1000) } ?: "not measured"} ($bytes bytes)")
            if (kbps != null) store.update { FluxMemory.recordThroughput(it, here.key, primary.id, kbps) }
        }
        // Countries: on Wi-Fi, now and then, measure where untried nodes exit, so the country list
        // holds verified countries and a later country choice connects without a long search.
        if (here.wifi) discoverCountries(here)
        // The verdict, if it is getting old, measured now while nobody waits on it.
        if (verdictFor(here.key).stale(System.currentTimeMillis())) {
            val v = FluxNet.measureVerdict(app, here, System.currentTimeMillis())
            store.update { s -> FluxMemory.setVerdict(s, here.key, mergeVerdict(s.nets[here.key]?.verdict, v)) }
        }
    }

    /**
     * A small race over nodes whose exit is not known yet, spread across the countries their
     * names claim -- for the exits only (it never changes the routes in use). Wi-Fi only, at most
     * once in [DISCOVERY_EVERY_MS] per network, at most [DISCOVERY_MAX] candidates.
     */
    private suspend fun discoverCountries(here: FluxNet.Here) {
        val prefs = app.getSharedPreferences("flux_sources", Context.MODE_PRIVATE)
        val key = "disc_${here.key}"
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(key, 0L) < DISCOVERY_EVERY_MS) return
        prefs.edit().putLong(key, now).apply()
        val p = prefs()
        val s = store.current
        val pool = FluxPlanner.candidates(input(here, null, IpMode.BOTH, nodes(), verdictFor(here.key), resolvedFor(here.key)).copy(budget = Int.MAX_VALUE))
            .filter { s.egress[it.egressKey]?.valid(now) != true && it.fragment.code == "0" }
            .distinctBy { it.node.id }
        // Round-robin over the claimed countries, so one big country does not take every slot.
        val byHint = pool.groupBy { it.node.countryHint ?: "?" }.values.map { it.toMutableList() }
        val picked = ArrayList<FluxCandidate>()
        while (picked.size < DISCOVERY_MAX && byHint.any { it.isNotEmpty() }) byHint.forEach { l -> if (l.isNotEmpty() && picked.size < DISCOVERY_MAX) picked += l.removeAt(0) }
        if (picked.isEmpty()) return
        FluxLog.i("discovery: measuring the exits of ${picked.size} untried nodes (claims: ${picked.groupingBy { it.node.countryHint ?: "?" }.eachCount()})")
        val probe = FluxAndroidProbe(app, here.network, mobile = false)
        val out = try {
            FluxRacer(probe, FluxRacer.Config(reachConcurrency = 8, realBatch = DISCOVERY_MAX, graceMs = 4_000))
                .race(picked, accept = { _, _ -> true }, score = { _, r -> -(r.rttMs ?: 9_999).toDouble() })
        } finally { withContext(Dispatchers.IO) { probe.close() } }
        store.update { FluxLearner.afterRace(it, here.key, System.currentTimeMillis(), null, p.ipMode, picked, out, setBest = false) }
        FluxLog.i("discovery: ${out.ranked.size} answered; exits ${out.ranked.mapNotNull { it.second.egress?.countryCode }.groupingBy { it }.eachCount()}")
    }

    private suspend fun throughput(port: Int, bytes: Int): Double? = withContext(Dispatchers.IO) {
        for (url in listOf("http://speed.cloudflare.com/__down?bytes=$bytes", "http://cachefly.cachefly.net/1mb.test")) {
            val r = runCatching {
                val u = java.net.URL(url)
                val start = System.nanoTime()
                Socket(Proxy.NO_PROXY).use { s ->
                    s.soTimeout = 15_000
                    s.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                    s.getOutputStream().write("GET $url HTTP/1.0\r\nHost: ${u.host}\r\nConnection: close\r\n\r\n".toByteArray())
                    val input = s.getInputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (total < bytes) { val n = input.read(buf); if (n < 0) break; total += n }
                    val secs = (System.nanoTime() - start) / 1e9
                    if (total < bytes / 2 || secs <= 0) null else total * 8 / 1000.0 / secs
                }
            }.getOrNull()
            if (r != null) return@withContext r
        }
        null
    }

    private fun httpGet(proxyPort: Int, url: String, max: Int): String? = runCatching {
        val u = java.net.URL(url)
        val c = u.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))) as java.net.HttpURLConnection
        c.connectTimeout = 6_000; c.readTimeout = 8_000
        try {
            if (c.responseCode != 200) null else c.inputStream.use { String(it.readBytes().take(max).toByteArray()) }
        } finally { c.disconnect() }
    }.getOrNull()

    // ---------------------------------------------------------------- network changes

    private fun watchNetwork() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
                override fun onAvailable(network: Network) = changed()
            })
        }
    }

    @Volatile private var netCheck: Job? = null

    /**
     * The user moved networks (Wi-Fi to mobile data, another Wi-Fi). MyVpnService already rebuilds
     * the tunnel on a drop, asking [freshConfig] for this network's own route; here FLUX only
     * checks, a few seconds later, that what runs now really works -- and races if it does not.
     */
    private fun changed() {
        if (!wantConnected || !isOurs()) return
        netCheck?.cancel()
        netCheck = scope.launch {
            delay(6_000)
            val here = FluxNet.current(app)
            if (here.key == tunnelNet) return@launch
            FluxLog.i("network changed: $tunnelNet -> ${here.key}; checking the tunnel")
            val rtt = canary(attempts = 2)
            if (rtt != null) {
                // The routes carried over: remember them as good here too.
                val p = prefs()
                store.update { s ->
                    var x = FluxMemory.markSeen(s, here.key, System.currentTimeMillis())
                    inTunnel.firstOrNull()?.let { x = FluxLearner.liveSuccess(x, here.key, it.id, System.currentTimeMillis(), rtt) }
                    if (FluxMemory.best(x, here.key, p.country, p.ipMode).isEmpty()) x = FluxMemory.setBest(x, here.key, p.country, p.ipMode, inTunnel.map { it.id })
                    x
                }
                tunnelNet = here.key
            } else if (connectJob?.isActive != true) {
                connectJob = scope.launch { runCatching { connect() } }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun nodes(): List<FluxNode> = sources.nodes()

    private fun verdictFor(net: String): NetVerdict = store.current.nets[net]?.verdict ?: NetVerdict()

    /** A fresh measurement wins, but what the race learned (UDP) survives it. */
    private fun mergeVerdict(old: NetVerdict?, new: NetVerdict): NetVerdict = new.copy(udp = old?.udp ?: new.udp)

    private fun input(here: FluxNet.Here, country: String?, mode: IpMode, nodes: List<FluxNode>, v: NetVerdict, resolved: Map<String, Map<Family, String>>) =
        FluxPlanner.Input(nodes, store.current, here.key, System.currentTimeMillis(), country, mode, v, resolved)

    private val resolveGate = Semaphore(16)

    private fun resolvedFor(net: String): Map<String, Map<Family, String>> =
        resolvedCache?.takeIf { it.first == net }?.second.orEmpty()

    /** Servers given by name, resolved on this network (bounded: 16 at once, 3 s overall). */
    private suspend fun resolve(here: FluxNet.Here, nodes: List<FluxNode>): Map<String, Map<Family, String>> {
        resolvedCache?.takeIf { it.first == here.key }?.let { return it.second }
        val named = nodes.filter { Family.ofLiteral(it.server) == null }
        if (named.isEmpty()) return emptyMap()
        val out = java.util.concurrent.ConcurrentHashMap<String, Map<Family, String>>()
        // One gate for every call, and what is still waiting for it is cancelled at the timeout. A
        // lookup blocks its thread until the resolver gives up; with a gate per call, repeated
        // attempts on a dead network piled up blocked threads until the phone could not start
        // another (out-of-memory and coroutine crashes on Android 11, 1.2.39). Lookups already
        // running are left to finish on their own, so the timeout still returns on time.
        val jobs = named.map { n -> scope.async { resolveGate.withPermit { FluxNet.resolve(app, here.network, n.server).takeIf { it.isNotEmpty() }?.let { out[n.id] = it } } } }
        withTimeoutOrNull(4_500) { jobs.awaitAll() }
        jobs.forEach { it.cancel() }
        return out.toMap().also { resolvedCache = here.key to it }
    }

    private fun failureFor(p: FluxPrefs, here: FluxNet.Here): FluxUiState.Failed {
        val now = System.currentTimeMillis()
        if (p.country != null) {
            val elsewhere = FluxMemory.countriesHere(store.current, here.key, now).filter { it != p.country }
            if (elsewhere.isNotEmpty() || FluxMemory.provenCountries(store.current, now).isNotEmpty())
                return FluxUiState.Failed(FailureKind.NO_ROUTE_FOR_COUNTRY, elsewhere.take(3).joinToString(" · ").ifEmpty { null })
        }
        // The network has IPv6, but no server answered over it: say that, not "your IPv6 is broken".
        if (p.ipMode == IpMode.V6) return FluxUiState.Failed(
            if (verdictFor(here.key).v6 == com.mlmvpn.scanner.engines.flux.core.model.Tri.NO) FailureKind.FAMILY_UNAVAILABLE else FailureKind.NO_ROUTE_FOR_FAMILY)
        return FluxUiState.Failed(FailureKind.NOTHING_WORKS)
    }

    /** "Why this route", in a few plain facts, for the diagnostics page. */
    private fun why(c: FluxCandidate, net: String, rtt: Long): List<String> {
        val m = FluxMemory.metrics(store.current, net, c.id)
        val e = store.current.egress[c.egressKey]
        return listOfNotNull(
            e?.countryCode?.let { "+ exit $it${if (e.confidence >= 1.0) " (2 sources)" else ""}" },
            "+ IPv${c.family.code}",
            "+ $rtt ms through the tunnel",
            m?.kbps?.let { "+ ${"%.1f".format(it / 1000)} Mbps measured" },
            m?.let { "+ ${(it.successEwma * 100).toInt()}% success here" },
            if (c.edge != null) "+ Cloudflare edge ${c.edge}" else null,
            if (c.fragment.code != "0") "+ fragment ${c.fragment.name.lowercase()}" else null,
        )
    }

    private fun screenOn(): Boolean = runCatching { (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(true)

    private fun fail(state: FluxUiState.Failed, why: String) {
        FluxLog.w("==== FAILED ${state.reason}${state.suggestion?.let { " (try: $it)" } ?: ""}: $why")
        _ui.value = state
    }

    /** Every round holds every method, so a fourth costs ~8 s and reaches untried servers of each. */
    private const val ROUNDS = 4
    /** Racing on after the first answer, for standbys; ends sooner once [STANDBYS] have answered. */
    private const val STANDBY_GRACE_MS = 1_500L
    private const val STANDBYS = 3
    private const val HEALTH_TIMEOUT_MS = 10_000
    private const val FAILOVER_RETRIES = 2
    private const val FAILOVER_PAUSE_MS = 30_000L
    private const val DISCOVERY_EVERY_MS = 6 * 3600_000L
    private const val DISCOVERY_MAX = 24

    /** FLUX's own probes on mobile data: at most this much a day. */
    private const val MOBILE_BUDGET_BYTES = 3_000_000L

    /**
     * A route that failed with the tunnel up (not a probe's opinion, the real thing): its breaker
     * opens at once, so the next plan takes a standby or races without it.
     */
    private fun sitOut(net: String, cid: String) = store.update { s ->
        var x = s
        val now = System.currentTimeMillis()
        val streak = FluxMemory.metrics(x, net, cid)?.consecutiveFailures ?: 0
        repeat(maxOf(1, FluxMemory.BREAKER_THRESHOLD - streak)) { x = FluxLearner.liveFailure(x, net, cid, now, FailReason.HTTP_TIMEOUT) }
        x
    }

    /** Read by MAE: FLUX's proven routes on [net], best first, at most [max]. No probing. */
    /** What MLM Scout runs for FLUX on one IP family: a one-route probe config and where it comes out. */
    data class ScoutRoute(val config: String, val country: String?)

    /**
     * MLM Scout's question, "does FLUX work on this line over [family]?": this network's known
     * route for the family, else a race -- the same planner, racer and learning a connect uses
     * (so what Scout proves here is remembered for the next connect), but no tunnel and nothing
     * written to the user's choices. The config is FLUX's probe shape: the caller gives it a SOCKS
     * inbound of its own. Null when no route was found.
     */
    suspend fun scoutRoute(context: Context, family: Family): ScoutRoute? {
        init(context)
        val here = FluxNet.current(app)
        if (!FluxNet.online(app)) return null
        val mode = if (family == Family.V6) IpMode.V6 else IpMode.V4
        var nodes = nodes()
        if (nodes.isEmpty()) {
            withContext(Dispatchers.IO) { sources.refreshDue(here.wifi, null, force = true) }
            nodes = nodes()
            if (nodes.isEmpty()) return null
        }
        val now = System.currentTimeMillis()
        val stored = verdictFor(here.key)
        val primary = FluxPlanner.warm(input(here, null, mode, nodes, stored, resolvedFor(here.key))).firstOrNull()
            ?: run {
                val v = if (stored.stale(now)) FluxNet.measureVerdict(app, here, now).also { v ->
                    store.update { s -> FluxMemory.setVerdict(s, here.key, mergeVerdict(s.nets[here.key]?.verdict, v)) }
                } else stored
                if (FluxPlanner.families(mode, v).isEmpty()) return null
                val resolved = resolve(here, nodes)
                val inp = input(here, null, mode, nodes, v, resolved).copy(budget = if (here.wifi) 40 else 28)
                val cands = FluxPlanner.candidates(inp)
                if (cands.isEmpty()) return null
                race(here, prefs().copy(country = null, ipMode = mode), cands)?.firstOrNull()
            }
            ?: return null
        FluxLog.i("scout: route for ${family.name}: $primary")
        return ScoutRoute(FluxConfigCompiler.probe(mapOf(primary to 10808)), store.current.egress[primary.egressKey]?.countryCode)
    }

    fun provenRoutes(net: String, max: Int): List<FluxCandidate> {
        if (!initialized) return emptyList()
        val s: FluxState = store.current
        val nodes = nodes()
        val inp = FluxPlanner.Input(nodes, s, net, System.currentTimeMillis(), null, IpMode.BOTH, s.nets[net]?.verdict ?: NetVerdict(), resolvedFor(net))
        val warm = FluxPlanner.warm(inp)
        if (warm.size >= max) return warm.take(max)
        // Beyond the warm routes: anything that worked here in the last day, best first.
        val byId = nodes.associateBy { it.id }
        val prefix = "$net|"
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        val more = s.metrics.filter { (k, m) -> k.startsWith(prefix) && m.lastOkAt > dayAgo && !m.coolingDown(System.currentTimeMillis()) }
            .entries.sortedByDescending { it.value.successEwma }
            .mapNotNull { FluxPlanner.rebuild(it.key.removePrefix(prefix), byId, inp) }
        return (warm + more).distinctBy { it.id }.take(max)
    }
}

/** What the hidden diagnostics page shows. Never a credential: candidates print redacted. */
data class FluxDiagnostics(
    val net: String = "",
    val verdict: NetVerdict? = null,
    val inTunnel: List<String> = emptyList(),
    val why: List<String> = emptyList(),
    val lastRace: List<Row> = emptyList(),
    val lastRaceMs: Long = 0,
    val failovers: Int = 0,
) {
    data class Row(
        val label: String, val id: String, val reachOk: Boolean?, val reachMs: Long?, val realOk: Boolean?, val rttMs: Long?,
        val reason: String?, val country: String?, val score: Double?,
    )
}
