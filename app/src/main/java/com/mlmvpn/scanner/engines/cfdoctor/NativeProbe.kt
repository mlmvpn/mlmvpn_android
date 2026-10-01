package com.mlmvpn.scanner.engines.cfdoctor

import android.content.Context
import android.os.Bundle
import com.mlmvpn.core.warp.AwgHostClient
import com.mlmvpn.core.warp.XrayProbeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket

/**
 * One Xray config, measured the way the app would use it: the core in the `:xprobe` process
 * (a Go panic there costs the Doctor one answer, never the app), a real request through its
 * SOCKS port from here.
 *
 * Replaces the in-process `measureOutboundDelay`, which could neither be stopped when it wedged
 * nor survive a panic, and which the old Doctor therefore gave up on after its first timeout.
 */
class NativeProbe(private val context: Context) {

    data class Result(val ms: Long?, val code: String)

    /**
     * [config] is an Xray JSON with an outbound tagged `proxy` (generateSpeedtestConfig's shape).
     * The core is given exactly one SOCKS inbound on a free loopback port.
     */
    suspend fun measure(config: String, timeoutMs: Int = 12_000): Result = withContext(Dispatchers.IO) {
        val port = ServerSocket(0).use { it.localPort }
        val json = runCatching { withSocks(config, port) }.getOrElse { return@withContext Result(null, "config_invalid") }
        val deaths = AwgHostClient.xray.deaths
        val started = AwgHostClient.xray.call(context, XrayProbeService.MSG_START,
            Bundle().apply { putString(XrayProbeService.KEY_CONFIG, json) }, 20_000)?.getBoolean(XrayProbeService.KEY_OK) == true
        if (!started) {
            return@withContext Result(null, if (AwgHostClient.xray.deaths != deaths) "core_crashed" else "core_refused")
        }
        try {
            // The first request carries the server's own handshake; the second is the delay.
            val first = get(port, timeoutMs) ?: return@withContext Result(null, if (AwgHostClient.xray.deaths != deaths) "core_crashed" else "no_response")
            Result(get(port, timeoutMs) ?: first, "ok")
        } finally {
            AwgHostClient.xray.call(context, XrayProbeService.MSG_STOP, null, 5_000)
        }
    }

    fun release() = AwgHostClient.xray.release(context)

    private fun withSocks(config: String, port: Int): String {
        val j = JSONObject(config)
        j.put("inbounds", JSONArray().put(JSONObject().put("tag", "socks").put("listen", "127.0.0.1").put("port", port)
            .put("protocol", "socks").put("settings", JSONObject().put("auth", "noauth").put("udp", false))))
        j.put("log", JSONObject().put("loglevel", "none"))
        j.remove("routing")
        return j.toString()
    }

    private fun get(port: Int, timeoutMs: Int): Long? = runCatching {
        val start = System.nanoTime()
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { s ->
            s.soTimeout = timeoutMs
            s.connect(InetSocketAddress.createUnresolved("www.gstatic.com", 80), timeoutMs)
            s.getOutputStream().write("GET /generate_204 HTTP/1.0\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
            val line = s.getInputStream().bufferedReader().readLine().orEmpty()
            if (line.contains(" 204") || line.contains(" 200")) (System.nanoTime() - start) / 1_000_000 else null
        }
    }.getOrNull()
}
