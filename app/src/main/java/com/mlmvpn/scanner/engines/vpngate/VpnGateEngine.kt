package com.mlmvpn.scanner.engines.vpngate

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mlmvpn.core.warp.IVpnEngine
import com.tim.basevpn.state.ConnectionState
import com.tim.openvpn.configuration.OpenVPNConfig
import com.tim.openvpn.connection.OpenVPNConnection
import com.tim.openvpn.service.OpenVPNService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Drives an OpenVPN (openvpn3) tunnel for a VPN Gate server.
 *
 * Shaped exactly like [com.mlmvpn.core.warp.AmneziaWgInjector]: the core brings up its own
 * VpnService — here `com.tim.openvpn.service.OpenVPNService`, which additionally lives in its
 * own `:openvpn` process — so MyVpnService deliberately does NOT establish a TUN on this path
 * and the `fd` it would have produced is unused. A second `establish()` in the same process
 * would revoke the interface the core just brought up.
 *
 * The `config` string handed to [start] is the `vpngate://<host>` sentinel, not a profile:
 * the .ovpn text itself is written to [PENDING_OVPN] by [VpnGateController], which keeps the
 * Intent extras small (profiles run ~10 KB) and off the Binder transaction.
 */
class VpnGateEngine : IVpnEngine {

    companion object {
        const val URI_SCHEME = "vpngate://"

        /** Relative to `filesDir`. Written by the controller, read here. */
        const val PENDING_OVPN = "vpngate/pending.ovpn"

        private const val TAG = "VpnGateEngine"
        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val NOTIFICATION_CLASS = "com.tim.notification.DefaultVpnServiceNotification"

        // The library's own Intent contract, read straight out of the AAR. OpenVPNService is an
        // IntentActionVpnService: onStartCommand runs initDependencies(intent) — which is the
        // ONLY place the config is parsed and the binder created — and then switches on the
        // action. Nothing else starts a tunnel.
        private const val EXTRA_CONFIG = "CONFIGURATION_KEY"
        private const val EXTRA_ACTION = "ACTION_KEY"
        private const val EXTRA_NOTIFICATION_CLASS = "NOTIFICATION_IMPL_CLASS_KEY"
        private const val ACTION_START = "ACTION_START_KEY"
        private const val ACTION_STOP = "ACTION_STOP_KEY"

        fun pendingFile(context: Context): File = File(context.filesDir, PENDING_OVPN)
    }

    private var appCtx: Context? = null
    private var connection: OpenVPNConnection? = null
    private val connected = CompletableDeferred<Boolean>()

    /** Set once CONNECTED is seen, so a later DISCONNECTED is a drop rather than a failure. */
    @Volatile private var reachedConnected = false

    /** Guards against stop() racing an unsolicited drop into a double teardown. */
    @Volatile private var stopped = false

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        val ctx = context.applicationContext
        appCtx = ctx

        val host = config.removePrefix(URI_SCHEME).ifBlank { "vpngate" }

        val ovpn = try {
            val f = pendingFile(ctx)
            if (!f.exists()) {
                Log.e(TAG, "no pending profile at ${f.absolutePath}")
                return false
            }
            f.readText()
        } catch (e: Exception) {
            Log.e(TAG, "failed to read pending profile", e)
            return false
        }

        if (ovpn.isBlank()) {
            Log.e(TAG, "pending profile is empty")
            return false
        }

        return try {
            // NOTE: never log `ovpn` — it carries the inline client key.
            Log.d(TAG, "start(): host=$host profile=${ovpn.length} chars")

            // Deliberately NOT OpenVPNConfigParser.parse(). That parser looks up <ca>, <cert>,
            // <key> AND <tls-crypt> unconditionally, and a missing block makes its indexOf
            // return -1 which it feeds straight into subList:
            //     IndexOutOfBoundsException: fromIndex = -1  (OpenVPNConfigParser.linesByKey)
            // VPN Gate profiles are SoftEther-generated and never carry <tls-crypt>, so every
            // single one of them crashes it.
            //
            // OpenVPNService uses `configuration ?: buildConfig()`, so handing it the profile
            // text directly bypasses the parser entirely and lets openvpn3 read the file — the
            // same core OpenVPN Connect uses, which VPN Gate officially supports.
            // The raw (hardened) profile, NOT the field-based config: buildConfig() emits a
            // fixed template and would silently drop the tls-cert-profile / tls-version-min
            // directives that make VPN Gate's SHA-1 client certificate acceptable at all.
            val hardened = OvpnProfileBuilder.harden(ovpn)
            val parsed = OpenVPNConfig(name = host, configuration = hardened)
            OvpnProfileBuilder.remoteOf(hardened)?.let { (h, p) ->
                Log.d(TAG, "config: $h:$p tls-cert-profile=insecure")
            }

            // Order matters, and it is the opposite of what OpenVPNConnection.start() does.
            //
            // VpnConnection.start() is only `vpnService?.startVPN()`, and `vpnService` stays
            // null until the service binds. But BindableVpnService.onBind() returns the binder
            // field, which is created by initDependencies() — called exclusively from
            // onStartCommand(). So a bind that precedes a start hands back null, the callback
            // never fires and the connect silently hangs until our timeout. That is exactly
            // what the logs showed: start() logged, then 60 s of nothing.
            //
            // So: start the service with the config first, then bind purely to receive state.
            withContext(Dispatchers.Main) {
                // Tear down any session still retrying from a previous attempt, otherwise the
                // old one keeps its own connect-retry loop alive alongside the new one.
                ctx.startService(
                    Intent(ctx, OpenVPNService::class.java).apply { putExtra(EXTRA_ACTION, ACTION_STOP) }
                )
            }
            delay(200)
            withContext(Dispatchers.Main) {
                ctx.startService(
                    Intent(ctx, OpenVPNService::class.java).apply {
                        putExtra(EXTRA_CONFIG, parsed)
                        putExtra(EXTRA_ACTION, ACTION_START)
                        putExtra(EXTRA_NOTIFICATION_CLASS, NOTIFICATION_CLASS)
                    }
                )
            }
            delay(400)
            withContext(Dispatchers.Main) {
                val conn = OpenVPNConnection(ctx) { state -> onState(state) }
                connection = conn
                // `false` on purpose: with `true` the listener replays an initial DISCONNECTED
                // the moment it binds — before the service has reported anything real. We used
                // to read that as a failed connect and killed a core that was still dialling,
                // roughly 400 ms in, every single time.
                conn.bindService(false)
            }

            val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } ?: false
            if (!ok) {
                Log.e(TAG, "connect failed or timed out for $host")
                teardownQuietly()
            } else {
                Log.d(TAG, "connected to $host")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "failed to start OpenVPN", e)
            teardownQuietly()
            false
        }
    }

    override fun stop() {
        if (stopped) {
            Log.w(TAG, "stop() called twice — ignoring")
            return
        }
        stopped = true
        try {
            // Same reasoning as start(): drive the service through its Intent contract rather
            // than through the connection, which is only a state channel here.
            appCtx?.startService(
                Intent(appCtx, OpenVPNService::class.java).apply {
                    putExtra(EXTRA_ACTION, ACTION_STOP)
                }
            )
            connection?.stop()
            Log.d(TAG, "stop(): disconnect requested")
        } catch (e: Exception) {
            Log.e(TAG, "error stopping OpenVPN", e)
        } finally {
            try { connection?.clear() } catch (_: Exception) {}
            connection = null
            appCtx = null
            if (!connected.isCompleted) connected.complete(false)
        }
    }

    private fun onState(state: ConnectionState) {
        Log.d(TAG, "state -> $state")
        when (state) {
            ConnectionState.CONNECTED -> {
                reachedConnected = true
                if (!connected.isCompleted) connected.complete(true)
            }

            ConnectionState.PERMISSION_NOT_GRANTED -> {
                // The one terminal failure worth reporting early — retrying cannot fix it.
                if (!connected.isCompleted) connected.complete(false)
            }

            ConnectionState.DISCONNECTED -> {
                if (reachedConnected && !stopped) {
                    // Dropped after a good connect. Route it through MyVpnService's normal
                    // STOP path so isRunning / connectedNodeId / the phase flow and the
                    // quick-settings tile all settle back to IDLE together.
                    reportDropToService()
                }
                // Before the first CONNECTED, a DISCONNECTED is NOT a verdict: openvpn3 runs
                // its own connect-retry loop and reports one between attempts. Treating it as
                // failure aborted the dial after a single try. Success is CONNECTED; failure
                // is the timeout.
            }

            else -> Unit
        }
    }

    private fun reportDropToService() {
        val ctx = appCtx ?: return
        try {
            Log.w(TAG, "tunnel dropped after connect — telling MyVpnService to stop")
            ctx.startService(
                Intent(ctx, com.mlmvpn.scanner.MyVpnService::class.java).apply { action = "STOP" }
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to report drop", e)
        }
    }

    /**
     * Best-effort teardown after a failed/partial start, so the stop() MyVpnService always
     * issues on the last-assigned engine is a harmless no-op. Same discipline as
     * AmneziaWgInjector.teardownQuietly — a stale engine's teardown is what bites you on the
     * NEXT connect, whatever protocol that one happens to be.
     */
    private fun teardownQuietly() {
        try { connection?.stop() } catch (_: Exception) {}
        try { connection?.clear() } catch (_: Exception) {}
        connection = null
        stopped = true
    }
}
