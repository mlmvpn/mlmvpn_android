package com.mlmvpn.core.warp

import android.content.Context
import android.util.Log

/**
 * Runs a WARP Xray config through the core.
 *
 * NOTE: this is a thin wrapper over the shared Xray core runner. It expects the
 * `config` string it is handed to already be a valid Xray JSON config (i.e. built
 * by the caller / XrayJsonGenerator). If a raw non-JSON WARP descriptor is passed,
 * core start will fail and this returns false — that path may need WARP->Xray
 * config building wired up.
 */
class WarpXrayInjector(private val fd: Int) : IVpnEngine {
    private var controller: libv2ray.CoreController? = null

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        return try {
            val c = XrayCore.newController(context)
            c.startLoop(config, fd)
            controller = c
            Log.d("WarpXrayInjector", "WARP Xray core started (fd=$fd)")
            true
        } catch (e: Exception) {
            Log.e("WarpXrayInjector", "Failed to start WARP Xray core", e)
            try { controller?.stopLoop() } catch (_: Exception) {}
            controller = null
            false
        }
    }

    override fun stop() {
        try { controller?.stopLoop() } catch (e: Exception) {
            Log.e("WarpXrayInjector", "Error stopping WARP Xray core", e)
        }
        controller = null
    }
}
