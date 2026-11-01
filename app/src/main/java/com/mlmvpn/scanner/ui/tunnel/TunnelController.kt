package com.mlmvpn.scanner.ui.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.mlmvpn.core.tunnel.CoreConfig
import com.mlmvpn.core.tunnel.Tun2SocksManager
import com.mlmvpn.core.tunnel.TunnelEngine
import com.mlmvpn.core.tunnel.TunnelStatus
import com.mlmvpn.core.tunnel.TunnelVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TunnelStage { IDLE, STARTING, RUNNING, STOPPED, FAILED }

data class TunnelUiState(
    val stage: TunnelStage = TunnelStage.IDLE,
    /**
     * The transport that currently owns the tunnel, not the one being looked at.
     *
     * Each transport has a screen of its own now, so "which did the user pick" is answered by
     * which screen they are on. This answers the different question of what is running, which
     * is what lets the MASQUE screen say "WireGuard is connected" and offer to take over
     * rather than starting a second tunnel that cannot exist.
     */
    val active: Transport? = null,
    val message: String? = null,
    val exitIp: String? = null,
    /** The exit country as a two-letter code, or blank until one is known. */
    val country: String = "",
    /** The same country as whatever name the source produced; a fallback while the code is absent. */
    val countryName: String = "",
    val connectedAt: Long = 0L,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val speedTx: Long = 0L,
    val speedRx: Long = 0L,
    /** 0..100 while connecting, or -1 when this status carries no measurable progress. */
    val progress: Int = -1,
    /** False when the core was not bundled for this device's ABI. */
    val engineAvailable: Boolean = TunnelEngine.isAvailable,
    /**
     * The WARP tile the user tapped when the service handed the connect to another member (MASQUE did
     * not come up in its budget, so WireGuard carries -- the same exit country). Its screen owns the
     * session and says what happened, instead of offering to "take over" from a stand-in it chose.
     */
    val handedOverFrom: Transport? = null,
) {
    val isBusy: Boolean get() = stage == TunnelStage.STARTING || stage == TunnelStage.RUNNING
}

/**
 * The one object the five transport screens drive the tunnel through.
 *
 * A process-wide singleton rather than a ViewModel, matching how the rest of this app holds
 * engine state (`AetherEngine`, `UaeTrialEngine`, `MyVpnService`'s flows): the tunnel outlives
 * every screen and is started by the Quick Settings tile and by the boot receiver as well, so
 * state scoped to a composition would be wrong the moment the user leaves the screen.
 *
 * [attach] is idempotent and is called from the app class, so the receiver is registered before
 * any screen exists and a tunnel started from the tile is already reflected when one opens.
 */
object TunnelController {

    private val _state = MutableStateFlow(TunnelUiState())
    val state: StateFlow<TunnelUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var attached = false

    /**
     * One receiver for both kinds of update: the service broadcasts status and traffic on the
     * same action and tells them apart by which extras are present, not by a second action.
     */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val update = intent ?: return
            if (update.hasExtra(TunnelVpnService.EXTRA_STATUS)) applyStatus(context, update)
            if (update.hasExtra(TunnelVpnService.EXTRA_TRAFFIC_TX)) applyTraffic(update)
        }
    }

    fun attach(context: Context) {
        if (attached) return
        attached = true
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter(TunnelVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Start from the truth rather than from IDLE: the service may already be running from
        // a previous session, from the tile, or from the boot receiver.
        if (TunnelStatus.isActive()) {
            _state.update { it.copy(stage = TunnelStage.RUNNING) }
        }
    }

    private fun applyStatus(context: Context?, intent: Intent) {
        val status = intent.getStringExtra(TunnelVpnService.EXTRA_STATUS) ?: return
        val running = intent.getStringExtra(TunnelVpnService.EXTRA_PROTOCOL)
            ?.let { name -> Transport.entries.firstOrNull { it.name == name.uppercase() } }
        val stopped = status == TunnelVpnService.STATUS_DISCONNECTED ||
            status == TunnelVpnService.STATUS_FAILED

        // Only a transport that actually connected is worth reopening on. A failed attempt is
        // evidence against it, not for it.
        if (status == TunnelVpnService.STATUS_CONNECTED && running != null && context != null) {
            Transport.rememberWorking(context, running)
            // And which engine the app as a whole was last on, across both VPN services. The
            // Quick Settings tile has no screen to read state from, so this is the only way one
            // button can bring back what the user was actually using.
            com.mlmvpn.scanner.data.LastEngine.recordTunnel(context, running.id)
        }
        // Work out where this tunnel comes out. Started on the CONNECTED edge only, so the
        // status repost that the lookup's own answer triggers does not start a second one.
        if (context != null) {
            val wasConnected = _state.value.stage == TunnelStage.RUNNING
            if (status == TunnelVpnService.STATUS_CONNECTED && !wasConnected) {
                TunnelExitLocator.start(context)
            } else if (stopped) {
                TunnelExitLocator.stop()
            }
        }

        _state.update { prev ->
            prev.copy(
                // A failure keeps its owner: the screen of the transport that failed is the only
                // place that shows why, and the way around it (IdentityBlockedCard). Clearing it
                // here made that screen read «آماده» the moment «وارپ» failed. Every other reader
                // of `active` also checks for RUNNING or busy, so a failed owner holds nothing.
                active = if (status == TunnelVpnService.STATUS_DISCONNECTED) null else running ?: prev.active,
                handedOverFrom = if (status == TunnelVpnService.STATUS_DISCONNECTED) null
                    else intent.getStringExtra(TunnelVpnService.EXTRA_LADDER_FROM)?.takeIf { it.isNotBlank() }
                        ?.let { name -> Transport.entries.firstOrNull { it.name == name.uppercase() } }
                        ?.takeIf { it != running },
                connectedAt = if (stopped) 0L else intent.getLongExtra(
                    TunnelVpnService.EXTRA_CONNECTED_AT, prev.connectedAt
                ),
                // The code is what the screen renders: only a code becomes a flag, and only a
                // code becomes a country name in the language the user set. The human name is
                // kept as the fallback for the window before the lookup lands, when Psiphon has
                // named its region and nothing has resolved a code yet.
                country = if (stopped) "" else intent
                    .getStringExtra(TunnelVpnService.EXTRA_COUNTRY_CODE)
                    ?.takeIf { it.isNotBlank() } ?: prev.country,
                countryName = if (stopped) "" else intent
                    .getStringExtra(TunnelVpnService.EXTRA_COUNTRY)
                    ?.takeIf { it.isNotBlank() } ?: prev.countryName,
                exitIp = if (stopped) null
                    else intent.getStringExtra(TunnelVpnService.EXTRA_EXIT_IP) ?: prev.exitIp,
                progress = intent.getIntExtra(TunnelVpnService.EXTRA_PROGRESS, -1),
                stage = when (status) {
                    TunnelVpnService.STATUS_CONNECTING,
                    TunnelVpnService.STATUS_STARTING,
                    TunnelVpnService.STATUS_SCANNING -> TunnelStage.STARTING
                    TunnelVpnService.STATUS_CONNECTED -> TunnelStage.RUNNING
                    TunnelVpnService.STATUS_FAILED -> TunnelStage.FAILED
                    // Only report "stopped" if something was running; a fresh screen has never
                    // connected at all and IDLE is the honest word for that.
                    else -> if (prev.stage == TunnelStage.IDLE) TunnelStage.IDLE
                            else TunnelStage.STOPPED
                },
                // A detail describes an attempt in progress, so it must not outlive one that
                // succeeded. Carrying it forward is why a connected Psiphon session sat under
                // the words "Psiphon starting…" for as long as it was up: the CONNECTED
                // broadcast has no detail of its own and `?: prev.message` kept the last line
                // the ladder had printed.
                //
                // FAILED is deliberately NOT cleared the same way. There the previous detail is
                // usually the only statement of what went wrong, and the failure broadcast does
                // not always repeat it.
                message = intent.getStringExtra(TunnelVpnService.EXTRA_DETAIL)
                    ?: prev.message.takeIf {
                        status != TunnelVpnService.STATUS_CONNECTED &&
                            status != TunnelVpnService.STATUS_DISCONNECTED
                    },
                txBytes = if (stopped) 0L else prev.txBytes,
                rxBytes = if (stopped) 0L else prev.rxBytes,
                speedTx = if (stopped) 0L else prev.speedTx,
                speedRx = if (stopped) 0L else prev.speedRx,
            )
        }
    }

    private fun applyTraffic(intent: Intent) {
        _state.update {
            it.copy(
                txBytes = intent.getLongExtra(TunnelVpnService.EXTRA_TRAFFIC_TX, it.txBytes),
                rxBytes = intent.getLongExtra(TunnelVpnService.EXTRA_TRAFFIC_RX, it.rxBytes),
                speedTx = intent.getLongExtra(TunnelVpnService.EXTRA_TRAFFIC_SPEED_TX, it.speedTx),
                speedRx = intent.getLongExtra(TunnelVpnService.EXTRA_TRAFFIC_SPEED_RX, it.speedRx),
            )
        }
    }

    /**
     * Start [transport], after the VPN consent dialog has been approved.
     *
     * If another transport already owns the tunnel this swaps it rather than refusing. Refusing
     * would be the simpler rule and it makes the user do the work twice -- disconnect, find the
     * other icon, connect -- for a tap they have already made.
     */
    fun connect(context: Context, transport: Transport) {
        val app = context.applicationContext
        _state.update {
            it.copy(stage = TunnelStage.STARTING, message = null, active = transport, progress = -1)
        }
        scope.launch {
            // Nothing else may hold the device tun. See TunnelExclusion for why this is not a
            // no-op even when the two services look unrelated.
            TunnelExclusion.releaseForTunnelStack(app)

            if (TunnelStatus.isActive() || Tun2SocksManager.isNativeAlive) {
                com.mlmvpn.scanner.utils.SafeStart.service(app, 
                    Intent(app, TunnelVpnService::class.java)
                        .apply { action = TunnelVpnService.ACTION_DISCONNECT }
                )
                waitForTeardown()
            }
            val config = withContext(Dispatchers.IO) { CoreConfig.json(app, transport.value) }
            com.mlmvpn.scanner.utils.SafeStart.service(app, 
                Intent(app, TunnelVpnService::class.java).apply {
                    action = TunnelVpnService.ACTION_CONNECT
                    putExtra(TunnelVpnService.EXTRA_CONFIG, config)
                }
            )
        }
    }

    fun disconnect(context: Context) {
        val app = context.applicationContext
        // Off the main thread: startService is a call into the system server, and while that was
        // busy the disconnect button froze the app for seconds (1.2.42 #2109, #2391).
        Thread({
            com.mlmvpn.scanner.utils.SafeStart.service(app,
                Intent(app, TunnelVpnService::class.java)
                    .apply { action = TunnelVpnService.ACTION_DISCONNECT }
            )
        }, "tunnel-disconnect").start()
    }

    /** Called when the user declines the VPN consent dialog, so the button does not stay busy. */
    fun onPermissionDenied() {
        _state.update { it.copy(stage = TunnelStage.IDLE, active = null) }
    }

    /**
     * Wait for the previous tunnel to actually unwind.
     *
     * Polled rather than slept: a MASQUE tunnel unwinds in well under a second and Tor takes
     * far longer, so any fixed delay is either too slow for one or too fast for the other.
     *
     * Both conditions are checked and neither implies the other. `isActive()` goes false as
     * soon as a stop is *requested*, which is right for the UI and much too early here -- the
     * lwIP stack inside libtun2socks.so keeps touching its globals until run() returns, and
     * starting a second one before that aborts the process on an assertion rather than failing
     * politely.
     */
    private suspend fun waitForTeardown() = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + STOP_GRACE_MS
        while (System.currentTimeMillis() < deadline &&
            (TunnelStatus.isActive() || Tun2SocksManager.isNativeAlive)
        ) {
            delay(120)
        }
    }

    /**
     * Whether this device has a network at all.
     *
     * Checked before a connect rather than after: with no link every transport spends its full
     * scan budget failing and then reports "no gateway answered", which reads as censorship
     * when the real answer is that the Wi-Fi is off.
     */
    fun hasNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        // Some Android 11 builds throw SecurityException ("Package android does not belong to
        // ...") from getNetworkCapabilities. Not knowing is not "offline": let the connect try.
        val caps = try {
            manager.getNetworkCapabilities(manager.activeNetwork)
        } catch (_: SecurityException) {
            return true
        } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** How long to let the old tunnel unwind before starting the new one. */
    private const val STOP_GRACE_MS = 6_000L
}
