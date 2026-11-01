package com.mlmvpn.scanner.engines.scout

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.ui.home.ActiveEngines
import com.mlmvpn.scanner.ui.tunnel.TunnelExclusion
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The VPN side of a run (plan 3-2). Scout never makes a TUN: every method comes up as a local proxy
 * beside a line that is the user's own. So before a run the app's own connection is paused (with
 * the user's consent, on screen), and another app's VPN is a reason not to start at all -- every
 * number would be about that VPN, not about the user's internet.
 */
object ScoutVpn {

    /** One of this app's tunnels holds the phone right now. */
    fun ourVpnUp(): Boolean = TunnelExclusion.isAnythingRunning()

    /** Any VPN on the phone. With ours down, that is another app's. */
    fun anyVpn(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    fun foreignVpn(context: Context): Boolean = !ourVpnUp() && anyVpn(context)

    /** Which of the app's icons is connected now, and its name, for the pause question. */
    fun current(context: Context): PausedVpn? {
        if (!ourVpnUp()) return null
        val home = runCatching { ActiveEngines.idsNow(context).firstOrNull { it != "emergency_3" } ?: ActiveEngines.idsNow(context).firstOrNull() }.getOrNull()
        val label = home?.let { ScoutRegistry.homeApp(it) }?.let { S(it.labelRes) }
            ?: TunnelExclusion.runningEngineName() ?: "VPN"
        return PausedVpn(home, label)
    }

    /**
     * Pauses the app's own connection and waits until the phone is on its bare line again. Engines
     * that keep their own "connected" intent (FLUX, MAE, Amnezia/Proton, Outline, FENIX, GitHub) are told
     * through their own disconnect, so none of them reconnects in the middle of the run.
     */
    suspend fun pause(context: Context): PausedVpn? {
        val app = context.applicationContext
        val was = current(app) ?: return null
        val node = MyVpnService.connectedNodeIdFlow.value
        runCatching {
            when {
                node == com.mlmvpn.scanner.engines.flux.FluxEngine.NODE_ID -> com.mlmvpn.scanner.engines.flux.FluxEngine.disconnectAsync(app)
                node == com.mlmvpn.scanner.engines.mae.MaeEngine.NODE_ID -> com.mlmvpn.scanner.engines.mae.MaeEngine.stopTunnelAsync(app)
                node == com.mlmvpn.scanner.engines.fenix.FenixEngine.NODE_ID -> com.mlmvpn.scanner.engines.fenix.FenixEngine.disconnectAsync(app)
                node == com.mlmvpn.scanner.engines.amnezia.AmzEngine.NODE_ID -> com.mlmvpn.scanner.engines.amnezia.AmzEngine.disconnectAsync(app)
                node == com.mlmvpn.scanner.engines.outline.OutlineEngine.NODE_ID -> com.mlmvpn.scanner.engines.outline.OutlineEngine.disconnectAsync(app)
                node?.startsWith(com.mlmvpn.scanner.engines.github.GtEngine.NODE_PREFIX) == true -> com.mlmvpn.scanner.engines.github.GtEngine.disconnect()
                else -> Unit
            }
        }
        // Windscribe keeps its own "keep it up" intent (a watchdog that repairs drops): ended by its own disconnect.
        runCatching {
            val ws = com.mlmvpn.scanner.engines.windscribe.WindscribeEngine
            if (ws.connection.value.phase != com.mlmvpn.scanner.engines.windscribe.WsPhase.DISCONNECTED) ws.disconnect()
        }
        runCatching { com.mlmvpn.scanner.data.EngineLadder.cancel(app) }
        TunnelExclusion.releaseForTunnelStack(app)
        TunnelExclusion.releaseForXray(app)
        withTimeoutOrNull(10_000) { while (ourVpnUp() || anyVpn(app)) delay(200) }
        delay(800)
        return was
    }
}
