package com.mlmvpn.core.aether

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.mlmvpn.core.warp.AwgHostClient
import com.mlmvpn.core.warp.XrayAetherExitService
import com.mlmvpn.core.warp.XrayAetherRegExitService
import com.mlmvpn.core.warp.XrayProbeService
import com.mlmvpn.scanner.engines.cfnet.CfShape
import org.json.JSONArray
import org.json.JSONObject

/**
 * A local Xray that Aether dials out through (`AETHER_UPSTREAM`), so that its TLS ClientHello leaves
 * the phone cut into TLS records by Xray's tlshello finalmask -- PattNG's "exit-node": a freedom
 * outbound with `tlshello-0-len` ([CfShape.finalMaskTlsHello]), behind a loopback SOCKS port.
 *
 * Aether's own `--fragment` cuts the ClientHello into TCP segments, which a filter that reassembles
 * TCP reads whole (home Wi-Fi, 2026-10-10); a record split is a different shape on the wire. Through
 * the exit go the calls to the WARP API and MASQUE over HTTP/2 (both TCP); HTTP/3 never comes here.
 *
 * The core runs in a process of its own ([XrayAetherExitService] for a session, and
 * [XrayAetherRegExitService] for a registration, which may run beside one), so an Xray panic ends
 * only that process. Every call blocks briefly: never on the main thread.
 */
class AetherXrayExit private constructor(service: Class<*>, tag: String) {
    private val host = AwgHostClient(service, tag)
    @Volatile private var port = 0

    /** Starts the exit and returns its SOCKS port, or null when Xray did not start. */
    @Synchronized
    fun start(context: Context): Int? {
        stop(context)
        val p = freePort() ?: return null
        val ok = runCatching {
            host.call(context.applicationContext, XrayProbeService.MSG_START,
                Bundle().apply { putString(XrayProbeService.KEY_CONFIG, config(p)) }, 20_000)
                ?.getBoolean(XrayProbeService.KEY_OK) == true
        }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "the tlshello exit did not start")
            return null
        }
        port = p
        return p
    }

    /**
     * Stops the exit if it runs -- only the one on [onlyPort] when given, so a late stop of an
     * earlier session cannot end the exit a newer one has started. The host ends its process with
     * the core.
     */
    @Synchronized
    fun stop(context: Context, onlyPort: Int = 0) {
        if (port == 0 || (onlyPort != 0 && onlyPort != port)) return
        port = 0
        runCatching { host.call(context.applicationContext, XrayProbeService.MSG_STOP, null, 5_000) }
    }

    companion object {
        private const val TAG = "AetherXrayExit"

        /** The exit of the Aether engine's session. */
        val SESSION by lazy { AetherXrayExit(XrayAetherExitService::class.java, "AetherExit") }

        /** The exit of a WARP registration ([AetherRegister]). */
        val REGISTER by lazy { AetherXrayExit(XrayAetherRegExitService::class.java, "AetherRegExit") }

        /** What Aether's `AETHER_UPSTREAM` names for an exit on [port]. */
        fun upstream(port: Int) = "socks5://127.0.0.1:$port"

        /** A SOCKS inbound on [port] and a freedom outbound that cuts the ClientHello into records. */
        internal fun config(port: Int): String = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("tag", "aether-in").put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true))))
            .put("outbounds", JSONArray().put(JSONObject()
                .put("tag", "exit-node").put("protocol", "freedom")
                .put("streamSettings", JSONObject().put("finalmask", CfShape.finalMaskTlsHello()))))
            .toString()

        private fun freePort(): Int? = runCatching {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.getOrNull()
    }
}
