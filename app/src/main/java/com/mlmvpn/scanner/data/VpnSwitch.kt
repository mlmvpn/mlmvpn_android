package com.mlmvpn.scanner.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.mlmvpn.core.tunnel.TunnelStatus
import com.mlmvpn.core.tunnel.TunnelVpnService
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.utils.SafeStart

/**
 * One on/off for every tunnel the app can raise, for the Quick Settings tile.
 *
 * The tile knew two of the app's VPN services, MyVpnService and TunnelVpnService. OpenVPN (its own
 * service, also Windscribe's OpenVPN family) read as "off", so a tap tried to connect something
 * else instead of disconnecting it (user report 2026-10-11). And stopping a service is not enough
 * for an engine that watches its tunnel -- Windscribe's watchdog, FLUX's standbys, the Amnezia
 * engine's connect loop -- which brought it straight back: each is told through its own disconnect.
 */
object VpnSwitch {

    /** A tunnel of this app is up (or an engine is on its way to one), whichever service holds it. */
    fun isUp(context: Context): Boolean =
        MyVpnService.isRunning || TunnelStatus.isActive() ||
            com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connection.value.active || ownVpnUp(context)

    /** An engine is connecting or connected, even before its service reports in. */
    fun engineBusy(): Boolean = runCatching {
        com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.connection.value.phase.let {
            it == com.mlmvpn.scanner.engines.windscribe.WsPhase.CONNECTING || it == com.mlmvpn.scanner.engines.windscribe.WsPhase.CONNECTED
        } ||
            (com.mlmvpn.scanner.engines.amnezia.AmzEngine.isInitialized() && com.mlmvpn.scanner.engines.amnezia.AmzEngine.connect.value.let {
                it is com.mlmvpn.scanner.engines.amnezia.AmzConnect.Connecting || it is com.mlmvpn.scanner.engines.amnezia.AmzConnect.Connected
            })
    }.getOrDefault(false)

    /** Stops whatever is up, through each engine's own disconnect first, then the services themselves. */
    fun stopAll(context: Context) {
        val app = context.applicationContext
        runCatching {
            val ws = com.mlmvpn.scanner.engines.windscribe.WindscribeEngine
            if (ws.connection.value.phase != com.mlmvpn.scanner.engines.windscribe.WsPhase.DISCONNECTED) ws.disconnect()
        }
        runCatching {
            val amz = com.mlmvpn.scanner.engines.amnezia.AmzEngine
            if (amz.isInitialized() && amz.connect.value !is com.mlmvpn.scanner.engines.amnezia.AmzConnect.Idle) amz.disconnectAsync(app)
        }
        runCatching {
            val flux = com.mlmvpn.scanner.engines.flux.FluxEngine
            if (flux.ui.value !is com.mlmvpn.scanner.engines.flux.core.model.FluxUiState.Idle) flux.disconnectAsync(app)
        }
        runCatching {
            val outline = com.mlmvpn.scanner.engines.outline.OutlineEngine
            if (outline.connect.value !is com.mlmvpn.scanner.engines.outline.OutlineConnect.Idle) outline.disconnectAsync(app)
        }
        runCatching {
            val fenix = com.mlmvpn.scanner.engines.fenix.FenixEngine
            if (fenix.isInitialized() && fenix.conn.value !is com.mlmvpn.scanner.engines.fenix.FenixEngine.Conn.Idle) fenix.disconnectAsync(app)
        }
        runCatching { com.mlmvpn.scanner.openvpn.OpenVpnRuntime.disconnect(app) }
        if (TunnelStatus.isActive()) {
            SafeStart.service(app, Intent(app, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_DISCONNECT))
        }
        if (MyVpnService.isRunning || ownVpnUp(app)) {
            SafeStart.service(app, Intent(app, MyVpnService::class.java).apply { action = "STOP" })
            MyVpnService.isRunning = false
            MyVpnService.connectedNodeId = null
        }
    }

    /** The system has a VPN network up that this app owns (the AmneziaWG backend, SoftEther: their own services). */
    private fun ownVpnUp(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                Build.VERSION.SDK_INT >= 29 && caps.ownerUid == android.os.Process.myUid()
        }
    }.getOrDefault(false)
}
