package com.mlmvpn.scanner.ui.tunnel

import android.content.Context
import android.content.Intent
import com.mlmvpn.core.tunnel.TunnelStatus
import com.mlmvpn.core.tunnel.TunnelVpnService
import com.mlmvpn.scanner.MyVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * Keeps the app's two VPN services off each other's toes.
 *
 * The app now has two, and the split is deliberate. [MyVpnService] is the Xray family --
 * VLESS/VMess/Trojan/Shadowsocks nodes, GST, SoftEther, AmneziaWG, Aether -- and
 * [TunnelVpnService] is the five-transport Cloudflare/Psiphon/Tor stack with its own netstack,
 * its own kill switch and its own reconnect ladder. Merging them would put two very different
 * lifecycles behind one switch for no gain.
 *
 * What they cannot share is the device. Android grants the VPN interface to ONE service at a
 * time, and the second `Builder.establish()` does not error -- it takes the tun away from the
 * first, which is left believing it still has one. The result is a tunnel whose core is running
 * and whose packets go nowhere, and it reads to the user as "the app connected and the internet
 * stopped working". So whichever side is about to start says so here first, and the other is
 * stopped and waited for.
 *
 * The wait is the part that matters. Stopping is asynchronous on both sides: `startService` with
 * a STOP action returns immediately, and the service tears its interface down on its own thread
 * afterwards. Establishing the new tun inside that window is exactly the race above.
 */
object TunnelExclusion {

    /**
     * True when either service currently holds the device tun.
     *
     * Used by a screen that wants to warn before taking over rather than doing it silently.
     */
    fun isAnythingRunning(): Boolean = MyVpnService.isRunning || TunnelStatus.isActive() ||
        com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connection.value.active

    /** Which side, in words, for a confirmation dialog. Null when nothing is up. */
    fun runningEngineName(): String? = when {
        com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connection.value.active -> "OpenVPN"
        TunnelStatus.isActive() -> S(R.string.current_tunnel)
        MyVpnService.isRunning -> S(R.string.current_config)
        else -> null
    }

    /**
     * Stop the Xray-family service, if it is up, and wait for it to let go.
     *
     * Called on the way into a transport connect. A no-op when nothing is running, which is the
     * common case and costs a single volatile read.
     */
    suspend fun releaseForTunnelStack(context: Context) {
        com.mlmvpn.scanner.openvpn.OpenVpnRuntime.release(context)
        if (!MyVpnService.isRunning) return
        val app = context.applicationContext
        com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).apply { action = "STOP" })
        awaitStopped { !MyVpnService.isRunning }
    }

    /**
     * Stop the five-transport service, if it is up, and wait for it to let go.
     *
     * The mirror of [releaseForTunnelStack], for the Xray side. Call it before starting any
     * node from the connection tab, Quick Connect, the game booster or the tile -- anywhere
     * that reaches [MyVpnService] directly.
     */
    suspend fun releaseForXray(context: Context) {
        com.mlmvpn.scanner.openvpn.OpenVpnRuntime.release(context)
        if (!TunnelStatus.isActive()) return
        val app = context.applicationContext
        com.mlmvpn.scanner.utils.SafeStart.service(app, 
            Intent(app, TunnelVpnService::class.java)
                .apply { action = TunnelVpnService.ACTION_DISCONNECT }
        )
        awaitStopped { !TunnelStatus.isActive() }
    }

    /**
     * Poll until the predicate holds, or give up.
     *
     * Bounded on purpose: a service that will not stop must not leave the user pressing a
     * button that does nothing. Past the deadline the connect proceeds anyway -- one tunnel
     * fighting another is a worse outcome than a connect that never starts, but only just, and
     * the failure is at least visible.
     */
    private suspend fun awaitStopped(done: () -> Boolean) {
        withTimeoutOrNull(STOP_TIMEOUT_MS) {
            while (!done()) delay(100)
        }
        // A short settle after the flag clears. The flag is set at the top of the stop handler,
        // before the ParcelFileDescriptor is actually closed; establishing inside that gap is
        // the same race with a smaller window.
        delay(SETTLE_MS)
    }

    /** OpenVPN validates its profile and credentials before requesting this handoff. */
    suspend fun releaseForOpenVpn(context: Context) {
        val app = context.applicationContext
        if (MyVpnService.isRunning) com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).setAction("STOP"))
        if (TunnelStatus.isActive()) com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_DISCONNECT))
        kotlinx.coroutines.withTimeout(10_000) {
            while (MyVpnService.isRunning || TunnelStatus.isActive()) delay(100)
        }
        delay(SETTLE_MS)
    }

    private const val STOP_TIMEOUT_MS = 6_000L
    private const val SETTLE_MS = 350L
}
