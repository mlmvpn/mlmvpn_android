package com.mlmvpn.core.warp

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.SecureRandom

/**
 * Shared Xray-core runner backed by the (new) libv2ray.aar API.
 *
 * The new aar renamed the entry class from `libv2ray.libv2ray` (lowercase)
 * to `libv2ray.Libv2ray` (capital L). The rest of the surface is unchanged:
 *   Libv2ray.initCoreEnv(path, xudpKey)
 *   Libv2ray.newCoreController(handler) -> CoreController
 *   CoreController.startLoop(configJson, fd)
 *   CoreController.stopLoop()
 */
internal object XrayCore {
    private fun randomXudpKey(): String {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        return Base64.encodeToString(keyBytes, flags)
    }

    fun newController(context: Context): libv2ray.CoreController {
        libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, randomXudpKey())
        val handler = object : libv2ray.CoreCallbackHandler {
            override fun onEmitStatus(status: Long, msg: String): Long = 0
            override fun shutdown(): Long = 0
            override fun startup(): Long = 0
        }
        return libv2ray.Libv2ray.newCoreController(handler)
    }
}

/**
 * Runs a fully-formed Xray JSON config through the core.
 * `fd` is the protected tun file descriptor for VPN mode, or 0 for proxy-only.
 */
class VlessXrayInjector(private val fd: Int) : IVpnEngine {
    private var controller: libv2ray.CoreController? = null

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        return try {
            val c = XrayCore.newController(context)
            c.startLoop(config, fd)
            controller = c
            Log.d("VlessXrayInjector", "Xray core started (fd=$fd)")
            true
        } catch (e: Exception) {
            Log.e("VlessXrayInjector", "Failed to start Xray core", e)
            try { controller?.stopLoop() } catch (_: Exception) {}
            controller = null
            false
        }
    }

    override fun stop() {
        try { controller?.stopLoop() } catch (e: Exception) {
            Log.e("VlessXrayInjector", "Error stopping Xray core", e)
        }
        controller = null
    }
}
