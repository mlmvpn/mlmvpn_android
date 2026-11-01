package com.mlmvpn.scanner.engines.gst

import android.content.Context
import com.mlmvpn.scanner.MyVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Picks the Google front (IP + cover SNI) the tunnel will actually dial.
 *
 * The relay test talks to script.google.com over the normal route, but the core never does:
 * it opens TLS to `google_ip` with `front_domain` as the SNI and asks for script.google.com
 * inside. So a relay could test healthy while the tunnel moved nothing, because the chosen IP
 * timed out on this network or the cover name had no matching certificate (googlevideo.com and
 * ytimg.com fail verification on Google's frontends, and the core runs with verify_ssl = true).
 * Picking the IP at random from the user's ticks made that a coin toss on every connect.
 *
 * Here every candidate pair does the core's real first step: TCP, TLS with the cover SNI and a
 * verified certificate, then an HTTP request with Host: script.google.com. Any HTTP status line
 * back from Google means the path carries relay traffic. The fastest such pair wins.
 */
object GstFront {
    private const val TAG = "GstFront"
    private const val TIMEOUT_MS = 4000

    data class Front(val ip: String, val sni: String, val ms: Int)

    /** Cover names that verify on Google's frontends. Bare googlevideo.com / ytimg.com do not. */
    fun usableSni(sni: String): Boolean = sni.isNotBlank() && sni !in BROKEN_SNI

    private val BROKEN_SNI = setOf("googlevideo.com", "ytimg.com")

    /**
     * The ticked cover names and IPs, cleaned the way the engine will use them. Anything not in
     * the offered lists (Cloudflare IPs ticked in older builds) is dropped: Google is not served
     * from those addresses.
     */
    fun candidates(context: Context): Pair<List<String>, List<String>> {
        val snis = GstConfigManager.getSelectedSniList(context).filter(::usableSni)
            .ifEmpty { listOf("www.google.com") }
        val ips = GstConfigManager.getSelectedCleanIpList(context)
            .filter { it in GstConfigManager.DEFAULT_IP_LIST }
            .ifEmpty { listOf(GstConfigManager.DEFAULT_GOOGLE_IP) }
        return snis to ips
    }

    /**
     * The fastest working pair, or null when none answered. Tries the ticked pairs first; when
     * all of them fail it widens to every offered IP with www.google.com before giving up.
     */
    suspend fun pick(context: Context): Front? {
        val (snis, ips) = candidates(context)
        val first = race(ips.flatMap { ip -> snis.map { ip to it } })
        if (first != null) return first
        GstLog.w(TAG, "No ticked front answered; trying every Google IP with www.google.com")
        val wider = GstConfigManager.DEFAULT_IP_LIST.filter { it !in ips }.map { it to "www.google.com" }
        return race(wider)
    }

    private suspend fun race(pairs: List<Pair<String, String>>): Front? = coroutineScope {
        if (pairs.isEmpty()) return@coroutineScope null
        val results = pairs.take(24).map { (ip, sni) ->
            async(Dispatchers.IO) { probe(ip, sni)?.let { Front(ip, sni, it) } }
        }.awaitAll()
        results.forEachIndexed { i, r ->
            val (ip, sni) = pairs[i]
            GstLog.d(TAG, "front $ip / $sni → ${r?.let { "${it.ms} ms" } ?: "failed"}")
        }
        results.filterNotNull().minByOrNull { it.ms }
    }

    /** Milliseconds for TCP + verified TLS + one HTTP status line, or null. */
    suspend fun probe(ip: String, sni: String): Int? = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val raw = Socket()
            MyVpnService.instance?.protect(raw)
            raw.soTimeout = TIMEOUT_MS
            raw.connect(InetSocketAddress(ip, 443), TIMEOUT_MS)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(raw, sni, 443, true) as SSLSocket).use { ssl ->
                ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, ssl.session)) {
                    return@withContext null
                }
                val out = ssl.outputStream
                out.write(
                    ("GET /macros/s/probe/exec HTTP/1.1\r\nHost: script.google.com\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                out.flush()
                val line = ssl.inputStream.bufferedReader().readLine() ?: return@withContext null
                if (!line.startsWith("HTTP/1.")) return@withContext null
            }
            (System.currentTimeMillis() - start).toInt()
        } catch (e: Exception) {
            null
        }
    }
}
