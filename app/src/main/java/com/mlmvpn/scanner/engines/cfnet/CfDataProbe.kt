package com.mlmvpn.scanner.engines.cfnet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The real-success check for a tunnel: small, bounded application requests through the tunnel's
 * own SOCKS inbound (never the default route), each with its answer validated:
 *  1. plain HTTP `generate_204` → exactly 204 (a captive/block page answers 200);
 *  2. the same over HTTPS → 204 with a completed TLS handshake inside the tunnel;
 *  3. a download of exactly 65 536 bytes (the length is the check, so a block page cannot pass);
 *  4. a 16 384-byte upload whose body the far end ECHOES back -- an acknowledgement, not just a
 *     socket write that went into a buffer.
 * All four must pass. A download that works with an upload that does not is a FAILED tunnel.
 *
 * Each check has more than one independent endpoint, tried in order, so one third-party service
 * being down (httpbin.org has bad days) cannot make a working tunnel look broken. Workers cannot
 * reach Cloudflare-hosted sites without a proxy IP, so non-Cloudflare endpoints come first.
 */
object CfDataProbe {
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "cfnet-transfer-deadline").apply { isDaemon = true }
    }

    const val DOWN_BYTES = 65_536
    const val UP_BYTES = 16_384
    private const val TOTAL_MS = 45_000L

    private val HTTP_204 = listOf("http://www.gstatic.com/generate_204", "http://connectivitycheck.gstatic.com/generate_204")
    private val HTTPS_204 = listOf("https://www.gstatic.com/generate_204", "https://www.google.com/generate_204")
    private val DOWNLOAD = listOf(
        "https://httpbin.org/bytes/$DOWN_BYTES",
        "https://nghttp2.org/httpbin/bytes/$DOWN_BYTES",
        "https://speed.cloudflare.com/__down?bytes=$DOWN_BYTES",
    )
    private val ECHO = listOf("https://httpbin.org/post", "https://postman-echo.com/post", "https://nghttp2.org/httpbin/post")

    /**
     * Runs the four checks through `127.0.0.1:[port]` within one 45 s ceiling. Never throws for a
     * network failure or a timeout -- those are a FAILED result; only a real cancellation propagates.
     */
    suspend fun validate(port: Int): ValidationResult =
        withTimeoutOrNull(TOTAL_MS) { runInterruptible(Dispatchers.IO) { run(port) } }
            ?: ValidationResult.fail(FailureClass.TIMEOUT, TOTAL_MS, "transfer_deadline")

    private fun run(port: Int): ValidationResult {
        val start = System.nanoTime()
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .followRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS).build()
        val owner = Thread.currentThread()
        val cancellation = watchdog.scheduleAtFixedRate({
            if (owner.isInterrupted) client.dispatcher.cancelAll()
        }, 100, 100, TimeUnit.MILLISECONDS)
        fun ms() = (System.nanoTime() - start) / 1_000_000
        try {
            fun <T> first(urls: List<String>, check: (String) -> T?): T? {
                for (url in urls) {
                    if (owner.isInterrupted) throw java.io.InterruptedIOException("cfnet_cancelled")
                    runCatching { check(url) }.getOrNull()?.let { return it }
                }
                return null
            }
            fun get(url: String) = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute()

            val http204 = first(HTTP_204) { url -> get(url).use { it.code.takeIf { c -> c == 204 } } } ?: 0
            if (http204 != 204) return ValidationResult.fail(FailureClass.DATA_TRANSFER_FAILURE, ms(), "http204=no", WirePhase.RESPONSE)
            val httpsOk = first(HTTPS_204) { url -> get(url).use { r -> (r.code == 204 && r.handshake != null).takeIf { it } } } == true
            if (!httpsOk) return ValidationResult.fail(FailureClass.DATA_TRANSFER_FAILURE, ms(), "https204=no", WirePhase.RESPONSE)

            val down = first(DOWNLOAD) { url ->
                get(url).use { r ->
                    if (r.code != 200) return@use null
                    val announced = r.body?.contentLength() ?: -1
                    if (announced >= 0 && announced != DOWN_BYTES.toLong()) return@use null
                    val stream = r.body?.byteStream() ?: return@use null
                    val buf = ByteArray(8192)
                    var n = 0L
                    while (n <= DOWN_BYTES) {
                        val read = stream.read(buf, 0, minOf(buf.size.toLong(), DOWN_BYTES + 1 - n).toInt())
                        if (read < 0) break
                        n += read
                    }
                    n.takeIf { it == DOWN_BYTES.toLong() }
                }
            } ?: 0L
            if (down != DOWN_BYTES.toLong()) return ValidationResult.fail(FailureClass.DATA_TRANSFER_FAILURE, ms(), "down=$down", WirePhase.RESPONSE)

            val payload = "cfnet-transfer-check:" + "x".repeat(UP_BYTES - 21)
            val up = first(ECHO) { url ->
                client.newCall(Request.Builder().url(url).post(payload.toRequestBody("text/plain".toMediaType())).build()).execute().use { r ->
                    val body = r.body?.source()?.let { source ->
                        source.request(65_537)
                        if (source.buffer.size > 65_536) "" else source.buffer.readUtf8()
                    }
                    val echoed = r.code == 200 && runCatching { JSONObject(body.orEmpty()).optString("data") == payload }.getOrDefault(false)
                    if (echoed) payload.toByteArray().size.toLong() else null
                }
            } ?: 0L

            val ms = ms()
            return if (CfValidation.transfer(http204, down, httpsOk, up)) ValidationResult.pass(ms, "http=204 https=204 down=$down up=$up")
            else ValidationResult.fail(FailureClass.DATA_TRANSFER_FAILURE, ms, "down=$down up=$up", WirePhase.RESPONSE)
        } catch (e: java.io.InterruptedIOException) {
            if (owner.isInterrupted) throw e
            return ValidationResult.fail(FailureClass.TIMEOUT, ms())
        } finally {
            cancellation.cancel(false)
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
