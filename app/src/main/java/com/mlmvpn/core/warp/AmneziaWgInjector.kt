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
            backend = GoBackend(context, actionHandler)

            val parsedConfig = Config.parse(ByteArrayInputStream(config.toByteArray()))
            Log.d("AmneziaWgInjector", "Parsed config OK. Peers=${parsedConfig.peers.size}")

            val tunnel = buildTunnel()
            this.tunnel = tunnel
            backend?.setState(tunnel, Tunnel.State.UP, parsedConfig)

            val state = backend?.getState(tunnel)
            Log.d("AmneziaWgInjector", "setState UP done. backend state=$state, version=${backend?.version}")
            return state == Tunnel.State.UP
        } catch (e: Exception) {
            Log.e("AmneziaWgInjector", "Failed to start AmneziaWG", e)
            return false
        }
    }

    override fun stop() {
        try {
            val t = tunnel
            if (t != null) {
                backend?.setState(t, Tunnel.State.DOWN, null)
                val state = backend?.getState(t)
                Log.d("AmneziaWgInjector", "setState DOWN done. backend state=$state")
            } else {
                Log.w("AmneziaWgInjector", "stop() called but no tunnel reference")
            }
        } catch (e: Exception) {
            Log.e("AmneziaWgInjector", "Error stopping AmneziaWG", e)
        } finally {
            tunnel = null
            backend = null
        }
    }
}
