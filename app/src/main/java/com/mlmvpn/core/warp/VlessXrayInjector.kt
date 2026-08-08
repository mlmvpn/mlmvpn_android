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
            // Surface xray-core status/log lines into the in-app log panel (GstLog) so a
            // user in Iran connecting a default config can actually see WHY it failed —
            // previously every status line was silently dropped (`return 0`).
            override fun onEmitStatus(status: Long, msg: String): Long {
                if (msg.isNotBlank()) {
                    com.mlmvpn.scanner.engines.gst.GstLog.i("XrayCore", "status=$status $msg")
                }
                return 0
            }
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

    /** Copy geosite.dat / geoip.dat from assets into filesDir if missing (xray reads them from there). */
    private fun ensureGeoData(context: Context) {
        for (filename in listOf("geosite.dat", "geoip.dat")) {
            try {
                val destFile = java.io.File(context.filesDir, filename)
                if (destFile.exists() && destFile.length() >= 1000) continue
                context.assets.open(filename).use { input ->
                    java.io.FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
                com.mlmvpn.scanner.engines.gst.GstLog.i("VlessXrayInjector", "copied $filename into filesDir")
            } catch (e: Exception) {
                com.mlmvpn.scanner.engines.gst.GstLog.w("VlessXrayInjector", "ensureGeoData $filename failed: ${e.message}")
            }
        }
    }

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        return try {
            // Ensure geosite.dat / geoip.dat exist in filesDir before starting the core. Configs
            // that use geosite:/geoip: routing rules (e.g. the Iran default configs with
            // geosite:category-ir) otherwise fail with "failed to open geosite.dat: no such file".
            // Previously this copy only ran from certain NodesTab test actions, so a direct connect
            // after a fresh install/data-clear left the files missing and the config wouldn't start.
            ensureGeoData(context)
            val remarks = try { org.json.JSONObject(config).optString("remarks") } catch (_: Exception) { "" }
            com.mlmvpn.scanner.engines.gst.GstLog.i(
                "VlessXrayInjector",
                "starting xray core (fd=$fd, ${config.length} chars${if (remarks.isNotBlank()) ", remarks=$remarks" else ""})"
            )
            val c = XrayCore.newController(context)
            c.startLoop(config, fd)
            controller = c
            com.mlmvpn.scanner.engines.gst.GstLog.i("VlessXrayInjector", "xray core started OK (fd=$fd)")
            true
        } catch (e: Exception) {
            com.mlmvpn.scanner.engines.gst.GstLog.e("VlessXrayInjector", "failed to start xray core: ${e.message}")
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
