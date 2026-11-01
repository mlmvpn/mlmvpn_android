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
    companion object {
        private val coreMutex = kotlinx.coroutines.sync.Mutex()
        private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "xprobe-cancel").apply { isDaemon = true }
        }
    }

    data class Result(val ms: Long?, val code: String)

    /**
     * [config] is an Xray JSON with an outbound tagged `proxy` (generateSpeedtestConfig's shape).
     * The core is given exactly one SOCKS inbound on a free loopback port.
     */
    suspend fun measure(config: String, timeoutMs: Int = 12_000): Result {
        coreMutex.lock()
        try { return measureLocked(config, timeoutMs) } finally { coreMutex.unlock() }
    }

    private suspend fun measureLocked(config: String, timeoutMs: Int): Result = withContext(Dispatchers.IO) {
        val port = ServerSocket(0).use { it.localPort }
        val json = runCatching { withSocks(config, port) }.getOrElse { return@withContext Result(null, "config_invalid") }
        val deaths = AwgHostClient.xray.deaths
        try {
            val started = kotlinx.coroutines.runInterruptible {
                AwgHostClient.xray.call(context, XrayProbeService.MSG_START,
                    Bundle().apply { putString(XrayProbeService.KEY_CONFIG, json) }, 20_000)?.getBoolean(XrayProbeService.KEY_OK) == true
            }
            if (!started) return@withContext Result(null, if (AwgHostClient.xray.deaths != deaths) "core_crashed" else "core_refused")
            // The core says "started" before its SOCKS inbound listens. Asking at once failed in a
            // few milliseconds and was reported as the CONFIG not answering: ~900 of the v2
            // reports' "native.no_response" rows were under 2 s (2026-10-03). Wait for the port.
            if (!kotlinx.coroutines.runInterruptible { listening(port, 4_000) }) return@withContext Result(null, if (AwgHostClient.xray.deaths != deaths) "core_crashed" else "core_not_listening")
            // The first request carries the server's own handshake; the second is the delay.
            val first = kotlinx.coroutines.runInterruptible { get(port, timeoutMs) } ?: return@withContext Result(null, if (AwgHostClient.xray.deaths != deaths) "core_crashed" else "no_response")
            Result(kotlinx.coroutines.runInterruptible { get(port, timeoutMs) } ?: first, "ok")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                AwgHostClient.xray.call(context, XrayProbeService.MSG_STOP, null, 5_000)
            }
        }
    }

    fun release() {
        if (coreMutex.tryLock()) try { AwgHostClient.xray.release(context) } finally { coreMutex.unlock() }
    }

    /**
     * Starts [config] and runs [block] against its local SOCKS port while it is up (the data
     * transfer test). Null with the reason when the core would not come up.
     *
     * [keepRouting]: a whole config that IS its own routing (the serverless «کانفیگ ایران» files:
     * no server, only rules that pick fragment / direct / block per destination) is run with its
     * rules and its inbound's sniffing intact; only the listening port changes.
     */
    suspend fun <T> withCore(config: String, keepRouting: Boolean = false, block: suspend (Int) -> T): Pair<String, T?> {
        coreMutex.lock()
        try { return withCoreLocked(config, keepRouting, block) } finally { coreMutex.unlock() }
    }

    private suspend fun <T> withCoreLocked(config: String, keepRouting: Boolean, block: suspend (Int) -> T): Pair<String, T?> = withContext(Dispatchers.IO) {
        val port = ServerSocket(0).use { it.localPort }
        val json = runCatching { withSocks(config, port, keepRouting) }.getOrElse { return@withContext "config_invalid" to null }
        val deaths = AwgHostClient.xray.deaths
        try {
            val started = kotlinx.coroutines.runInterruptible {
                AwgHostClient.xray.call(context, XrayProbeService.MSG_START,
                    Bundle().apply { putString(XrayProbeService.KEY_CONFIG, json) }, 20_000)?.getBoolean(XrayProbeService.KEY_OK) == true
            }
            if (!started) return@withContext (if (AwgHostClient.xray.deaths != deaths) "core_crashed" else "core_refused") to null
            if (!kotlinx.coroutines.runInterruptible { listening(port, 4_000) }) return@withContext "core_not_listening" to null
            "ok" to block(port)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                AwgHostClient.xray.call(context, XrayProbeService.MSG_STOP, null, 5_000)
            }
        }
    }

    private fun listening(port: Int, withinMs: Long): Boolean {
        val until = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < until) {
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun withSocks(config: String, port: Int, keepRouting: Boolean = false): String {
        val j = JSONObject(config)
        val own = j.optJSONArray("inbounds")?.optJSONObject(0)
        j.put("inbounds", JSONArray().put(JSONObject().put("tag", if (keepRouting) own?.optString("tag")?.ifBlank { null } ?: "socks" else "socks")
            .put("listen", "127.0.0.1").put("port", port)
            .put("protocol", "socks").put("settings", JSONObject().put("auth", "noauth").put("udp", false))
            .apply { if (keepRouting) own?.optJSONObject("sniffing")?.let { put("sniffing", it) } }))
        j.put("log", JSONObject().put("loglevel", "none"))
        if (!keepRouting) j.remove("routing")
        return j.toString()
    }

    private fun get(port: Int, timeoutMs: Int): Long? = runCatching {
        val start = System.nanoTime()
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { s ->
            val owner = Thread.currentThread()
            val cancel = watchdog.scheduleAtFixedRate({ if (owner.isInterrupted) runCatching { s.close() } }, 50, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
            try {
            s.soTimeout = timeoutMs
            s.connect(InetSocketAddress.createUnresolved("www.gstatic.com", 80), timeoutMs)
            s.getOutputStream().write("GET /generate_204 HTTP/1.0\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
            val line = s.getInputStream().bufferedReader().readLine().orEmpty()
            if (line.matches(Regex("HTTP/1\\.[01] 204(?: .*)?"))) (System.nanoTime() - start) / 1_000_000 else null
            } finally { cancel.cancel(false) }
        }
    }.getOrNull()
}
