package com.mlmvpn.scanner.widget

import android.content.Context
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.EngineLadder
import com.mlmvpn.scanner.data.SessionTraffic
import com.mlmvpn.scanner.ui.home.ActiveEngines
import com.mlmvpn.scanner.ui.tunnel.TunnelController
import com.mlmvpn.scanner.ui.tunnel.TunnelStage
import com.mlmvpn.scanner.utils.ScreenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * What every widget shows, in one place: which method is carrying the phone, how far it has got,
 * since when, where it comes out, and the bytes moving through it.
 *
 * The app has no single "the VPN" -- the five transports have their own service and controller,
 * the Xray family shares MyVpnService, OpenVPN has its own, and FLUX, FENIX, Outline, Amnezia,
 * Proton and Windscribe each run a connect of their own before any service hears about it. The
 * home screen already answers "which tile is lit" from those sources ([ActiveEngines]); this adds
 * the two things a widget needs on top: the connect still in progress, and the session's numbers.
 *
 * Lives in the main process, attached once from the app class. Everything it watches is a flow
 * that only moves when an engine changes state, so idle it costs nothing; the one-second traffic
 * sampler runs only while a session is up AND something is showing it.
 */
@OptIn(FlowPreview::class)
object WidgetLive {

    enum class Phase { IDLE, CONNECTING, CONNECTED }

    data class Conn(
        /** The home tile id of the method, or null when nothing is up (or a ladder has not picked one yet). */
        val method: String? = null,
        val phase: Phase = Phase.IDLE,
        /** Wall-clock start of the session; 0 unless [phase] is CONNECTED. */
        val since: Long = 0L,
        /** ISO-2 exit country, once known. */
        val country: String? = null,
        /**
         * CONNECTING only because a widget asked for [method] a moment ago; no engine has reported
         * anything yet. Drawn as connecting, but it is not evidence that an engine is working.
         */
        val pending: Boolean = false,
    ) {
        val up: Boolean get() = phase == Phase.CONNECTED
        val busy: Boolean get() = phase != Phase.IDLE
    }

    data class Traffic(
        /** This session's totals. */
        val rx: Long = 0L,
        val tx: Long = 0L,
        /** Bytes per second over the last sample. */
        val rxRate: Long = 0L,
        val txRate: Long = 0L,
        /**
         * The rates, oldest first: the live chart. Up to [HISTORY] + 1 of them -- one more than
         * the chart spans, so the oldest can slide out past the left edge while the newest slides
         * in at the right.
         */
        val down: LongArray = LongArray(0),
        val up: LongArray = LongArray(0),
        /** Which sample this is. Every tick is a new one, even when nothing moved. */
        val seq: Long = 0L,
        /** When [down] and [up] last took a new rate, on the frame clock's time base ([System.nanoTime]); 0 before the first. */
        val atNanos: Long = 0L,
    ) {
        override fun equals(other: Any?): Boolean = other is Traffic && seq == other.seq && rx == other.rx && tx == other.tx &&
            rxRate == other.rxRate && txRate == other.txRate && atNanos == other.atNanos &&
            down.contentEquals(other.down) && up.contentEquals(other.up)
        override fun hashCode(): Int = ((rx * 31 + tx) * 31 + seq).hashCode()
    }

    /** Samples across the chart: one a second, so a minute. */
    const val HISTORY = 60

    /** The sampler's beat. */
    const val TICK_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var attached = false
    private lateinit var app: Context

    private val _conn = MutableStateFlow(Conn())
    val conn: StateFlow<Conn> = _conn

    private val _traffic = MutableStateFlow(Traffic())
    /** Collect it only while showing it: collecting is what keeps the sampler running. */
    val traffic: StateFlow<Traffic> = _traffic

    /** Set by the home-screen widgets when one of them shows live numbers; see WidgetUpdater. */
    val widgetsWantTraffic = MutableStateFlow(false)

    /** Who wants the exit country found for sessions whose engine does not report one. */
    private val countryWanted = MutableStateFlow<Set<String>>(emptySet())

    /** The method a widget tap just asked for, until an engine reports its own state. */
    @Volatile private var requested: Pair<String, Long>? = null
    private const val REQUEST_MS = 90_000L

    /** Session start, for engines that do not keep one: the moment this saw the method come up. */
    private var startedKey: String? = null
    private var startedAt: Long = 0L

    /** The exit country this found itself, for [lookupKey]'s session. */
    @Volatile private var lookedUp: String? = null
    @Volatile private var lookupKey: String? = null
    private var lookupJob: Job? = null

    fun attach(context: Context) {
        if (attached) return
        synchronized(this) {
            if (attached) return
            attached = true
        }
        app = context.applicationContext
        ScreenState.watch(app)
        scope.launch {
            changes().debounce(120).collect { refresh() }
        }
        // The sampler: on only while a session is up and someone is looking at its numbers.
        scope.launch {
            combine(_conn, _traffic.subscriptionCount, widgetsWantTraffic, ScreenState.on) { c, subscribers, widgets, screen ->
                if (c.up && (subscribers > 0 || (widgets && screen))) c.method + "@" + c.since else null
            }.distinctUntilChanged().collectLatest { session ->
                if (session == null) {
                    if (!_conn.value.up) _traffic.value = Traffic()
                    return@collectLatest
                }
                sample()
            }
        }
        refresh()
    }

    /** Every engine state that can change what a widget shows, as one stream of "look again". */
    private fun changes(): Flow<Unit> {
        val sources = mutableListOf<Flow<*>>(
            TunnelController.state,
            MyVpnService.isRunningFlow,
            MyVpnService.connectedNodeIdFlow,
            MyVpnService.connectionPhaseFlow,
            MyVpnService.sniSessionFlow,
            com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connection,
            com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.connection,
            com.mlmvpn.scanner.engines.amnezia.AmzEngine.connect,
            com.mlmvpn.scanner.engines.proton.ProtonEngine.tried,
            com.mlmvpn.scanner.engines.flux.FluxEngine.ui,
            com.mlmvpn.scanner.engines.outline.OutlineEngine.connect,
            com.mlmvpn.scanner.engines.fenix.FenixEngine.conn,
            EngineLadder.running,
            countryWanted,
        )
        return merge(*sources.map { f -> f.map { } }.toTypedArray())
    }

    /** A widget asked for [method]: show it as connecting until an engine says otherwise. */
    fun noteRequested(method: String) {
        requested = method to System.currentTimeMillis()
        if (attached) scope.launch { refresh() }
    }

    fun clearRequested() {
        requested = null
        if (attached) scope.launch { refresh() }
    }

    /**
     * [who] shows the exit country (the in-app widget, a placed widget): look it up for sessions
     * whose engine does not report one. Off again with [on] false.
     */
    fun wantCountry(who: String, on: Boolean) {
        val now = countryWanted.value
        countryWanted.value = if (on) now + who else now - who
    }

    /** The state right now, worked out on the spot -- for a widget drawn before [attach] has run. */
    fun now(context: Context): Conn {
        if (!attached) attach(context)
        return _conn.value
    }

    @Synchronized
    private fun refresh() {
        val next = runCatching { compute() }.getOrElse { Conn() }
        if (next != _conn.value) _conn.value = next
        if (next.up && next.country == null && countryWanted.value.isNotEmpty()) startLookup(next)
        if (!next.up) {
            lookupJob?.cancel()
            lookupJob = null
        }
    }

    private fun compute(): Conn {
        val context = app
        val tunnel = TunnelController.state.value
        val live = ActiveEngines.idsNow(context)
        val req = requested?.takeIf { System.currentTimeMillis() - it.second < REQUEST_MS }?.first
        if (requested != null && req == null) requested = null

        // Lit: prefer the method a widget asked for when it is among the lit ones (an SNI session can
        // light beside a tunnel); otherwise the first lit one.
        val litMethod = when {
            live.isEmpty() -> null
            req != null && req in live -> req
            else -> live.first()
        }
        if (litMethod != null) {
            // MASQUE that did not come up in its budget hands the session to WireGuard; the tile the
            // user asked for owns it, exactly as its screen does (TunnelUiState.handedOverFrom).
            val owner = tunnel.handedOverFrom?.id?.takeIf { tunnel.active?.id == litMethod } ?: litMethod
            // The home grid lights an Xray-family tile from the moment its service starts; a widget
            // says "connected" only once the tunnel really is.
            if (!isUp(litMethod, tunnel)) return Conn(owner, Phase.CONNECTING)
            if (req == owner) requested = null
            return Conn(owner, Phase.CONNECTED, sinceOf(litMethod, tunnel), countryOf(litMethod, tunnel))
        }
        startedKey = null

        val connecting = connectingMethod(context, tunnel)
        if (connecting != null) return Conn(connecting.ifEmpty { null }, Phase.CONNECTING)
        if (req != null) return Conn(req, Phase.CONNECTING, pending = true)
        return Conn()
    }

    /**
     * Whether a lit method is really carrying traffic. The transports and OpenVPN light only once
     * connected; the Xray family lights as soon as MyVpnService starts, so for those the service's
     * phase -- or, for the engines that verify their own tunnel, the engine's state -- decides.
     */
    private fun isUp(method: String, tunnel: com.mlmvpn.scanner.ui.tunnel.TunnelUiState): Boolean {
        if (com.mlmvpn.scanner.ui.tunnel.Transport.byId(method) != null) return tunnel.stage == TunnelStage.RUNNING
        if (method == "openvpn" || method == "windscribe") return true
        val service = MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.CONNECTED
        return runCatching {
            when (method) {
                "flux" -> com.mlmvpn.scanner.engines.flux.FluxEngine.let {
                    if (it.isInitialized()) it.ui.value is com.mlmvpn.scanner.engines.flux.core.model.FluxUiState.Connected else service
                }
                "fenix" -> com.mlmvpn.scanner.engines.fenix.FenixEngine.let {
                    if (it.isInitialized()) it.conn.value is com.mlmvpn.scanner.engines.fenix.FenixEngine.Conn.Connected else service
                }
                "outline" -> com.mlmvpn.scanner.engines.outline.OutlineEngine.let {
                    if (it.isInitialized()) it.connect.value is com.mlmvpn.scanner.engines.outline.OutlineConnect.Connected else service
                }
                "amnezia", "proton" -> com.mlmvpn.scanner.engines.amnezia.AmzEngine.let {
                    if (it.isInitialized()) it.connect.value is com.mlmvpn.scanner.engines.amnezia.AmzConnect.Connected else service
                }
                else -> service
            }
        }.getOrDefault(service)
    }

    /** The method an engine is connecting right now; "" for a ladder that has not settled on one. */
    private fun connectingMethod(context: Context, tunnel: com.mlmvpn.scanner.ui.tunnel.TunnelUiState): String? {
        if (tunnel.stage == TunnelStage.STARTING) tunnel.active?.id?.let { return it }
        if (MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.CONNECTING) {
            ActiveEngines.homeOfNode(context, MyVpnService.connectedNodeId)?.let { return it }
        }
        val ovpn = com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connection.value
        if (ovpn.phase == com.mlmvpn.scanner.openvpn.ConnectionPhase.CONNECTING ||
            ovpn.phase == com.mlmvpn.scanner.openvpn.ConnectionPhase.RECONNECTING
        ) {
            return if (ovpn.profileId?.startsWith(com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.PROFILE_PREFIX) == true) "windscribe" else "openvpn"
        }
        val ws = com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.connection.value
        if (ws.phase == com.mlmvpn.scanner.engines.windscribe.WsPhase.CONNECTING && !ws.testing) return "windscribe"
        val amz = com.mlmvpn.scanner.engines.amnezia.AmzEngine
        if (amz.isInitialized() && amz.connect.value is com.mlmvpn.scanner.engines.amnezia.AmzConnect.Connecting) {
            return ActiveEngines.homeOfNode(context, amz.NODE_ID) ?: "amnezia"
        }
        val flux = com.mlmvpn.scanner.engines.flux.FluxEngine
        if (flux.isInitialized() && flux.ui.value is com.mlmvpn.scanner.engines.flux.core.model.FluxUiState.Searching) return "flux"
        val outline = com.mlmvpn.scanner.engines.outline.OutlineEngine
        if (outline.isInitialized() && outline.connect.value is com.mlmvpn.scanner.engines.outline.OutlineConnect.Connecting) return "outline"
        val fenix = com.mlmvpn.scanner.engines.fenix.FenixEngine
        if (fenix.isInitialized() && fenix.conn.value is com.mlmvpn.scanner.engines.fenix.FenixEngine.Conn.Connecting) return "fenix"
        if (EngineLadder.running.value) return ""
        return null
    }

    private fun sinceOf(method: String, tunnel: com.mlmvpn.scanner.ui.tunnel.TunnelUiState): Long {
        // An engine that keeps its own start time is the authority.
        val own: Long? = when {
            tunnel.active?.id == method && tunnel.connectedAt > 0 -> tunnel.connectedAt
            method == "openvpn" || method == "windscribe" ->
                com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connection.value.connectedAt?.takeIf { it > 0 }
            else -> null
        } ?: when (val c = runCatching { com.mlmvpn.scanner.engines.amnezia.AmzEngine.connect.value }.getOrNull()) {
            is com.mlmvpn.scanner.engines.amnezia.AmzConnect.Connected ->
                if (method == "amnezia" || method == "proton" || method == "windscribe") c.since.takeIf { it > 0 } else null
            else -> null
        } ?: when (val c = runCatching { com.mlmvpn.scanner.engines.outline.OutlineEngine.connect.value }.getOrNull()) {
            is com.mlmvpn.scanner.engines.outline.OutlineConnect.Connected -> if (method == "outline") c.since.takeIf { it > 0 } else null
            else -> null
        }
        if (own != null) {
            startedKey = method
            startedAt = own
            return own
        }
        if (startedKey != method) {
            startedKey = method
            startedAt = System.currentTimeMillis()
        }
        return startedAt
    }

    private fun countryOf(method: String, tunnel: com.mlmvpn.scanner.ui.tunnel.TunnelUiState): String? {
        val code: String? = when {
            tunnel.active?.id == method -> tunnel.country
            method == "openvpn" -> com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connection.value.exitCountry
            method == "fenix" -> (runCatching { com.mlmvpn.scanner.engines.fenix.FenixEngine.conn.value }.getOrNull()
                as? com.mlmvpn.scanner.engines.fenix.FenixEngine.Conn.Connected)?.let { it.exitCountry ?: it.country }
            method == "flux" -> (runCatching { com.mlmvpn.scanner.engines.flux.FluxEngine.ui.value }.getOrNull()
                as? com.mlmvpn.scanner.engines.flux.core.model.FluxUiState.Connected)?.countryCode
            method == "outline" -> (runCatching { com.mlmvpn.scanner.engines.outline.OutlineEngine.connect.value }.getOrNull()
                as? com.mlmvpn.scanner.engines.outline.OutlineConnect.Connected)?.country
            method == "amnezia" || method == "proton" || method == "windscribe" ->
                (runCatching { com.mlmvpn.scanner.engines.amnezia.AmzEngine.connect.value }.getOrNull()
                    as? com.mlmvpn.scanner.engines.amnezia.AmzConnect.Connected)?.country
                    ?: if (method == "windscribe") com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connection.value.exitCountry else null
            else -> null
        }
        val clean = code?.trim()?.uppercase()?.takeIf { com.mlmvpn.core.tunnel.IpFormatter.isRealCountry(it) }
        if (clean != null) return clean
        return lookedUp?.takeIf { lookupKey == "$method@${startedAtFor(method)}" }
    }

    private fun startedAtFor(method: String): Long = if (startedKey == method) startedAt else 0L

    /**
     * Finds the exit country of an Xray-family session by asking a geo service through the app's
     * own local proxy -- the only path from this process into such a tunnel (the app keeps itself
     * out of its own tun). The same source the connection tab's per-config flags use, so the two
     * agree. Tried a few times: a core that just came up may not carry anything for a moment.
     */
    private fun startLookup(c: Conn) {
        val method = c.method ?: return
        // Only the Xray family has the local proxy to look through.
        if (!MyVpnService.isRunning) return
        val key = "$method@${c.since}"
        if (lookupKey == key && (lookupJob?.isActive == true || lookedUp != null)) return
        lookupKey = key
        lookedUp = null
        lookupJob?.cancel()
        lookupJob = scope.launch {
            val waits = longArrayOf(2_000, 4_000, 8_000, 16_000)
            for (w in waits) {
                delay(w)
                if (!isActive || lookupKey != key) return@launch
                val code = withContext(Dispatchers.IO) { geoThroughProxy(com.mlmvpn.scanner.utils.LocalPort.get(app)) }
                if (code != null) {
                    lookedUp = code
                    refresh()
                    return@launch
                }
            }
        }
    }

    private val COUNTRY_CODE = Regex("\"country_code\"\\s*:\\s*\"([A-Za-z]{2})\"")

    private fun geoThroughProxy(port: Int): String? {
        if (port <= 0) return null
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
        return runCatching {
            client.newCall(Request.Builder().url("https://api.ip.sb/geoip").header("User-Agent", "Mozilla/5.0").build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val body = r.body?.string().orEmpty()
                    COUNTRY_CODE.find(body)?.groupValues?.get(1)?.uppercase()
                        ?.takeIf { com.mlmvpn.core.tunnel.IpFormatter.isRealCountry(it) }
                }
        }.getOrNull()
    }

    /**
     * One sample a second while it is wanted. Session totals come from the services' own counter
     * ([SessionTraffic]); OpenVPN keeps its own. The engines report on clocks of their own, so the
     * rate goes through [LiveRate] rather than being "this reading minus the last".
     *
     * The beat is fixed-rate -- the next tick is a second after the last one was due, not a second
     * after it finished -- because the chart glides one step per second and a beat that drifts
     * late makes it stop and start.
     */
    private suspend fun sample() {
        val rxMeter = LiveRate()
        val txMeter = LiveRate()
        val last = _traffic.value
        var down = last.down
        var up = last.up
        var atNanos = last.atNanos
        if (last.rx == 0L && last.tx == 0L) { down = LongArray(0); up = LongArray(0); atNanos = 0L }
        var seq = last.seq
        var first = true
        // An engine that does not say when it counted (OpenVPN): a change is dated to the tick that saw it.
        var undated = Reading(-1L, -1L, null)
        var undatedAt = 0L
        // The Xray family's service samples every two seconds unless told someone is watching.
        SessionTraffic.live.value = true
        try {
            var due = android.os.SystemClock.uptimeMillis()
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val reading = totalsFor(_conn.value.method)
                val rx = reading.rx
                val tx = reading.tx
                val countedAt = reading.atMs ?: run {
                    if (rx != undated.rx || tx != undated.tx) {
                        undated = reading
                        undatedAt = android.os.SystemClock.uptimeMillis()
                    }
                    undatedAt
                }
                val rxRate = rxMeter.next(rx, countedAt)
                val txRate = txMeter.next(tx, countedAt)
                // The first reading has nothing to compare with: it starts the meters, not the chart.
                if (!first) {
                    down = push(down, rxRate)
                    up = push(up, txRate)
                    atNanos = System.nanoTime()
                }
                first = false
                _traffic.value = Traffic(rx, tx, rxRate, txRate, down, up, ++seq, atNanos)
                due += TICK_MS
                // A whole beat or more behind (a long pause): skip the beats that were missed.
                val behind = android.os.SystemClock.uptimeMillis() - due
                if (behind >= TICK_MS) due += behind / TICK_MS * TICK_MS
                delay((due - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L))
            }
        } finally {
            SessionTraffic.live.value = false
        }
    }

    /** Received and sent so far, and when the engine counted them; a null time when it does not say. */
    private data class Reading(val rx: Long, val tx: Long, val atMs: Long?)

    private fun totalsFor(method: String?): Reading {
        if (method == "openvpn" || (method == "windscribe" && com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connection.value.active)) {
            val c = com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connection.value
            if (c.received > 0 || c.sent > 0) return Reading(c.received, c.sent, null)
        }
        val c = SessionTraffic.count ?: return SessionTraffic.totals.value.let { Reading(it.rx, it.tx, null) }
        return Reading(c.rx, c.tx, c.atMs)
    }

    private fun push(arr: LongArray, v: Long): LongArray {
        val keep = HISTORY + 1
        val out = if (arr.size < keep) arr.copyOf(arr.size + 1) else arr.copyOfRange(arr.size - keep + 1, arr.size).copyOf(keep)
        out[out.size - 1] = v.coerceAtLeast(0L)
        return out
    }
}
