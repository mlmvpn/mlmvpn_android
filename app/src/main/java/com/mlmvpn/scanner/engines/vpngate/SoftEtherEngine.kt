package com.mlmvpn.scanner.engines.vpngate

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import com.mlmvpn.core.warp.IVpnEngine
import kittoku.mvc.preference.MvcPreference
import kittoku.mvc.service.SoftEtherVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Connects over SoftEther's own SSL-VPN protocol instead of OpenVPN.
 *
 * Why not OpenVPN: the bundled openvpn3 core takes the tunnel all the way up — TLS 1.3,
 * PUSH_REPLY, ASSIGN_IP, "TUN established" — and then refuses the data channel with
 * "crypto_alg: AES-128-CBC: bad cipher for data channel use". With IV_NCP=2 it accepts only
 * AEAD ciphers there, while SoftEther's OpenVPN emulation offers CBC exclusively. The one
 * switch that disables that check, `ncp-disable`, is a removed option in this core and makes
 * it reject the whole profile.
 *
 * SoftEther's native protocol has no such limitation, and it is what these servers are built
 * around: HTTP over TLS with a browser User-Agent, which is also why it keeps working on
 * networks where the OpenVPN listener does not.
 *
 * The client is kittoku's Minimum VPN Client (Apache-2.0, pure Kotlin, no native code),
 * vendored under `kittoku.mvc`. It brings its own VpnService, so this engine drives it the
 * same way AmneziaWG is driven: MyVpnService skips its own TUN and hands over.
 */
class SoftEtherEngine : IVpnEngine {

    companion object {
        const val URI_SCHEME = "softether://"

        private const val TAG = "SoftEther"

        /** Every VPN Gate relay exposes the same virtual hub and accepts these credentials. */
        private const val HUB = "VPNGATE"
        private const val USERNAME = "vpn"
        private const val PASSWORD = "vpn"

        /** SoftEther's SSL-VPN listener. 443 is the one VPN Gate always publishes. */
        private const val SSL_PORT = 443

        /**
         * The client negotiates in three stages — SoftEther, DHCP, ARP — each with its own
         * 30 s budget. Anything under 90 s can cut the attempt off before the stage that is
         * actually stuck gets to report why, which is exactly what a 45 s limit did.
         */
        private const val CONNECT_TIMEOUT_MS = 100_000L

        /**
         * `host:port` packed into the node URI, so MyVpnService stays a dumb dispatcher.
         */
        fun uriFor(host: String, port: Int = SSL_PORT) = "$URI_SCHEME$host:$port"

        /**
         * Whether the live session negotiated a UDP channel. The preference only records what
         * the user asked for; the server has to agree and the network has to let UDP through,
         * so on a filtered line this stays false with the switch on.
         */
        fun isUdpAccelerationActive(): Boolean = SoftEtherVpnService.isUdpAccelerationActive
    }

    private var appCtx: Context? = null
    @Volatile private var stopped = false

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        val ctx = context.applicationContext
        appCtx = ctx

        val target = config.removePrefix(URI_SCHEME)
        val host = target.substringBefore(':')
        val port = target.substringAfter(':', "").toIntOrNull() ?: SSL_PORT
        if (host.isBlank()) {
            Log.e(TAG, "no host in '$config'")
            return false
        }

        val udpAcceleration = VpnGateStore.isUdpAccelerationEnabled(ctx)

        return try {
            Log.d(TAG, "connecting to $host:$port hub=$HUB udpAccel=$udpAcceleration")

            // The vendored client reads its configuration from the default SharedPreferences,
            // so writing the keys is the whole of the wiring. commit(), not apply(): the
            // service reads them on the very next line.
            PreferenceManager.getDefaultSharedPreferences(ctx).edit().apply {
                putString(MvcPreference.HOME_HOSTNAME.name, host)
                putString(MvcPreference.SSL_PORT.name, port.toString())
                putString(MvcPreference.HOME_HUB.name, HUB)
                putString(MvcPreference.HOME_USERNAME.name, USERNAME)
                putString(MvcPreference.HOME_PASSWORD.name, PASSWORD)
                putString(MvcPreference.SSL_VERSION.name, "DEFAULT")
                putBoolean(MvcPreference.SSL_DO_SELECT_SUITES.name, false)
                // UDP acceleration is the user's call, and off by default.
                //
                // It is SoftEther's answer to TCP-in-TCP and is markedly faster where UDP gets
                // through. On the network this was developed against it negotiates (UDP_STATUS
                // reaches OPEN) and then carries nothing at all — 100% loss pinging 8.8.8.8,
                // DNS stops resolving — so it cannot be the default. It also puts the data
                // plane on a second, non-TLS channel, which is more conspicuous.
                putBoolean(MvcPreference.UDP_ENABLE_ACCELERATION.name, udpAcceleration)
                if (udpAcceleration) putString(MvcPreference.ETHERNET_MTU.name, "1500")
                putBoolean(MvcPreference.LOG_DO_SAVE_LOG.name, false)
            }.commit()

            withContext(Dispatchers.Main) {
                ctx.startService(
                    Intent(ctx, SoftEtherVpnService::class.java)
                        .setAction(kittoku.mvc.service.ACTION_VPN_CONNECT)
                )
            }

            // The client has no completion callback to await, so settle for observing that it
            // stayed up: it tears its own service down on any negotiation failure.
            var waited = 0L
            while (waited < CONNECT_TIMEOUT_MS) {
                delay(1000)
                waited += 1000
                if (stopped) return false
                if (SoftEtherVpnService.isConnected) {
                    Log.d(TAG, "connected to $host:$port")
                    return true
                }
                if (SoftEtherVpnService.lastError != null) {
                    Log.e(TAG, "negotiation failed: ${SoftEtherVpnService.lastError}")
                    return false
                }
            }
            Log.e(TAG, "timed out connecting to $host:$port")
            false
        } catch (e: Exception) {
            Log.e(TAG, "failed to start SoftEther", e)
            false
        }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        val ctx = appCtx ?: return
        try {
            ctx.startService(
                Intent(ctx, SoftEtherVpnService::class.java)
                    .setAction(kittoku.mvc.service.ACTION_VPN_DISCONNECT)
            )
            Log.d(TAG, "disconnect requested")
        } catch (e: Exception) {
            Log.e(TAG, "error stopping SoftEther", e)
        } finally {
            appCtx = null
        }
    }
}
