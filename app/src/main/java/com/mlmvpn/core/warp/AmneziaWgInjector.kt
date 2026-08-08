package com.mlmvpn.core.warp

import android.content.Context
import android.util.Log
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream

class AmneziaWgInjector(private val fd: Int) : IVpnEngine {
    private var backend: GoBackend? = null
    // Application context captured in start(), needed to stop the library's own VpnService
    // in stop(). See stopBackendService() for why that is our job and not the library's.
    private var appContext: Context? = null
    // Must be the SAME instance for UP and DOWN — GoBackend matches the active tunnel by
    // object identity. Building a new one in stop() would leave the tunnel up.
    private var tunnel: Tunnel? = null

    // No-op handler; we don't run any pre/post up/down scripts.
    private val actionHandler = object : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>) {}
        override fun runPostUp(scripts: MutableCollection<String>) {}
        override fun runPreDown(scripts: MutableCollection<String>) {}
        override fun runPostDown(scripts: MutableCollection<String>) {}
    }

    private fun buildTunnel() = object : Tunnel {
        override fun getName() = "amneziawg0"
        override fun onStateChange(state: Tunnel.State) {}
        override fun isIpv4ResolutionPreferred(): Boolean = true
    }

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        try {
            // SECURITY: never log the raw config -- it contains the PrivateKey and the server
            // Endpoint IP. Leaking the endpoint to logcat lets the address get discovered and
            // filtered. Log only a harmless length.
            Log.d("AmneziaWgInjector", "start(): config length=${config.length}")
            appContext = context.applicationContext
            val localBackend = GoBackend(context, actionHandler)
            backend = localBackend

            val parsedConfig = Config.parse(ByteArrayInputStream(config.toByteArray()))
            Log.d("AmneziaWgInjector", "Parsed config OK. Peers=${parsedConfig.peers.size}")

            val tunnel = buildTunnel()
            this.tunnel = tunnel
            localBackend.setState(tunnel, Tunnel.State.UP, parsedConfig)

            val state = localBackend.getState(tunnel)
            Log.d("AmneziaWgInjector", "setState UP done. backend state=$state, version=${localBackend.version}")
            if (state == Tunnel.State.UP) {
                return true
            }

            // The Go backend didn't reach UP (e.g. bad handshake/endpoint) but may hold
            // partially-initialized native state. Never leave `tunnel`/`backend` set on a
            // non-UP result -- MyVpnService always calls stop() on whatever currentEngine
            // was last assigned (including a failed one, on the very next connect attempt,
            // possibly before this failure's stopSelf() has torn the service down). Calling
            // setState(DOWN) on a backend that never cleanly reached UP is what was crashing
            // native inside libam-go.so on the subsequent stop() -- regardless of which engine
            // the user tried to connect next, since the crash happened in the teardown of the
            // STALE AmneziaWG instance, not in whatever came after it.
            teardownQuietly(localBackend, tunnel)
            return false
        } catch (e: Exception) {
            Log.e("AmneziaWgInjector", "Failed to start AmneziaWG", e)
            teardownQuietly(backend, tunnel)
            return false
        }
    }

    override fun stop() {
        com.mlmvpn.scanner.CrashReporter.note("AmneziaWgInjector.stop() begin")
        try {
            val t = tunnel
            val b = backend
            if (t != null && b != null) {
                b.setState(t, Tunnel.State.DOWN, null)
                val state = b.getState(t)
                Log.d("AmneziaWgInjector", "setState DOWN done. backend state=$state")
            } else {
                Log.w("AmneziaWgInjector", "stop() called but no live tunnel/backend -- nothing to tear down")
            }
        } catch (e: Exception) {
            Log.e("AmneziaWgInjector", "Error stopping AmneziaWG", e)
        } finally {
            stopBackendService()
            tunnel = null
            backend = null
            appContext = null
            com.mlmvpn.scanner.CrashReporter.note("AmneziaWgInjector.stop() end")
        }
    }

    /**
     * Stops `AbstractBackend$VpnService` -- the VpnService the amneziawg library brings up itself.
     *
     * The library starts it (AbstractBackend.startVpnService -> context.startService) but never
     * stops it: there is no stopSelf() anywhere in amneziawg-android 2.1.13. So setState(DOWN)
     * closes the tun fd and clears the Go handle, yet the service keeps running as an orphan whose
     * `owner` still points at the GoBackend we just tore down, while MyVpnService has already
     * destroyed itself. Stopping it here is what actually completes the teardown.
     *
     * Safe to call unconditionally AFTER setState(DOWN): the service's onDestroy calls
     * handleDestroy(owner), which is a no-op once currentTunnel is null (which DOWN just made it),
     * and it resets the library's static `vpnService` future so the NEXT connect goes through
     * startService() again instead of reusing a stale instance.
     */
    private fun stopBackendService() {
        val ctx = appContext ?: return
        try {
            ctx.stopService(
                android.content.Intent(ctx, org.amnezia.awg.backend.AbstractBackend.VpnService::class.java)
            )
            Log.d("AmneziaWgInjector", "AbstractBackend\$VpnService stop requested")
        } catch (e: Exception) {
            Log.w("AmneziaWgInjector", "Could not stop AbstractBackend\$VpnService: ${e.message}")
        }
    }

    /** Best-effort DOWN + clear state after a failed/partial start(), so a later stop() is a no-op. */
    private fun teardownQuietly(b: GoBackend?, t: Tunnel?) {
        if (b != null && t != null) {
            try { b.setState(t, Tunnel.State.DOWN, null) } catch (e: Exception) {
                Log.w("AmneziaWgInjector", "teardownQuietly: setState(DOWN) threw: ${e.message}")
            }
        }
        stopBackendService()
        tunnel = null
        backend = null
        appContext = null
    }
}
