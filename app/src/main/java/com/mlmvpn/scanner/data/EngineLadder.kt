package com.mlmvpn.scanner.data

import android.content.Context
import android.content.Intent
import com.mlmvpn.core.tunnel.CoreConfig
import com.mlmvpn.core.tunnel.TunnelStatus
import com.mlmvpn.core.tunnel.TunnelVpnService
import com.mlmvpn.scanner.ui.tunnel.Transport
import com.mlmvpn.scanner.ui.tunnel.TunnelExclusion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Try transports in order until one is actually carrying traffic.
 *
 * For the case where there is nothing to remember: a fresh install, tapped from the Quick Settings
 * tile. The tile cannot ask a question and has no screen to show a list on, so its only honest
 * options are to open the app -- which is what it used to do, and which reads as a button that
 * does nothing -- or to go and find a working tunnel by itself. This is the second one.
 *
 * **Psiphon first, deliberately.** It brings its own server list and its own obfuscation, so it is
 * the one transport that needs nothing configured and nothing scanned to have a chance on a
 * network the app has never seen. The rest follow in descending order of how much they depend on
 * finding a reachable gateway first.
 *
 * A rung is judged by [TunnelStatus], not by whether the start command was accepted: every one of
 * these "starts" successfully and then discovers a few seconds later that it cannot reach anything.
 * That is precisely the failure the ladder exists to walk past, so the only thing worth waiting for
 * is the tunnel reporting itself up.
 *
 * Lives in a process-wide object rather than in the TileService, which the system unbinds seconds
 * after the tap -- a ladder that took thirty seconds would be killed a third of the way down it.
 */
object EngineLadder {

    /**
     * How long one rung gets before it is written off.
     *
     * Long enough for Psiphon to walk its own internal ladder of fronted and direct rungs, short
     * enough that the whole descent stays inside a minute or so on a network where nothing works.
     */
    private const val RUNG_TIMEOUT_MS = 22_000L

    /** Poll interval while waiting for a rung to come up. */
    private const val POLL_MS = 500L

    /** After a stop, before the next start. See TunnelExclusion for why the wait matters. */
    private const val SETTLE_MS = 600L

    private val order = listOf(
        Transport.PSIPHON,
        Transport.MASQUE,
        Transport.WIREGUARD,
        Transport.GOOL,
        Transport.TOR,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _running = MutableStateFlow(false)

    /** True while the ladder is working through the rungs. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _current = MutableStateFlow<Transport?>(null)

    /** Which rung is being tried, for a screen or a tile that wants to say so. */
    val current: StateFlow<Transport?> = _current.asStateFlow()

    /** Idempotent: a second tap while it is already walking does nothing. */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        _running.value = true
        job = scope.launch {
            try {
                for (transport in order) {
                    if (!transport.supportedHere) continue
                    _current.value = transport
                    if (tryRung(app, transport)) {
                        // LastEngine is written by TunnelController on the CONNECTED broadcast, so
                        // the next tap goes straight here rather than walking the ladder again.
                        return@launch
                    }
                }
                // Nothing came up. Leave the device clean rather than with a half-started service.
                stopTunnel(app)
            } finally {
                _running.value = false
                _current.value = null
            }
        }
    }

    fun cancel(context: Context) {
        job?.cancel()
        job = null
        _running.value = false
        _current.value = null
        stopTunnel(context.applicationContext)
    }

    private suspend fun tryRung(app: Context, transport: Transport): Boolean {
        // Only one VpnService may hold the device tun, and the second establish() takes it from
        // the first rather than failing.
        TunnelExclusion.releaseForTunnelStack(app)
        withContext(Dispatchers.IO) {
            val config = CoreConfig.json(app, transport.value)
            app.startForegroundService(
                Intent(app, TunnelVpnService::class.java)
                    .setAction(TunnelVpnService.ACTION_CONNECT)
                    .putExtra(TunnelVpnService.EXTRA_CONFIG, config)
            )
        }

        var waited = 0L
        while (waited < RUNG_TIMEOUT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
            if (TunnelStatus.isActive()) return true
        }

        // Timed out. Take it down before the next rung, and wait for it to actually let go --
        // starting the next one into a tun the previous still holds is the race TunnelExclusion
        // exists to prevent, and here it would make every remaining rung fail for the wrong reason.
        stopTunnel(app)
        delay(SETTLE_MS)
        return false
    }

    private fun stopTunnel(app: Context) {
        runCatching {
            com.mlmvpn.scanner.utils.SafeStart.service(app, 
                Intent(app, TunnelVpnService::class.java)
                    .setAction(TunnelVpnService.ACTION_DISCONNECT)
            )
        }
    }
}
