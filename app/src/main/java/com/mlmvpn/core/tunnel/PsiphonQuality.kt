package com.mlmvpn.core.tunnel

import android.os.SystemClock
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.math.roundToLong

/**
 * What the Psiphon server that just answered is worth, asked before anyone's traffic is routed
 * through it -- the desktop's psiphon-manager.js `measureTunnel` and its slow-server search.
 *
 * SEVERAL CONNECTIONS AT ONCE, because a single stream through a domain-fronted meek tunnel
 * measures about the same whatever the server is worth (0.5–0.9 Mbit/s on the desktop's line,
 * whether the server was a good one or the one that later delivered 0.10). Six -- what a browser
 * opens -- separate them: a good US server gave 3.13 Mbit/s and a bad FR one 0.4, same line, same
 * hour (2026-09-19).
 *
 * The floor is CALIBRATED, not picked: with exactly these parameters (six streams, 768 KB in all, so
 * connection setup is a real part of the figure) a healthy server measured 1.62, the stuck one
 * 0.10–0.46. 0.9 sits clear of both groups -- below the healthy band, not at its edge, so an
 * acceptable server is not turned into three redials.
 */
object PsiphonQuality {

    const val STREAMS = 6
    const val BYTES = 768 * 1024
    const val MIN_GOOD_MBIT = 0.9
    /** How many servers one rung may draw before it settles for what it has. */
    const val MAX_SERVER_ATTEMPTS = 3

    private const val HOST = "speed.cloudflare.com"
    private const val PORT = 80

    /** [mbit]: everything that arrived over the whole batch's time; [okStreams]: how many brought bytes at all. */
    data class Result(val mbit: Double, val okStreams: Int, val bytes: Long, val ms: Long)

    /**
     * [STREAMS] downloads at once through the SOCKS port, [BYTES] between them. Plain HTTP on port 80:
     * the endpoint answers the same byte count there, the payload is incompressible either way, and
     * nothing here needs to trust a certificate to count bytes.
     */
    fun measure(socksPort: Int, streams: Int = STREAMS, bytes: Int = BYTES, timeoutMs: Long = 12_000): Result {
        val each = maxOf(65_536, bytes / streams)
        val t0 = SystemClock.elapsedRealtime()
        val deadline = t0 + timeoutMs
        val got = LongArray(streams)
        val threads = (0 until streams).map { i ->
            Thread({ got[i] = one(socksPort, each, deadline) }, "psiphon-quality-$i").apply { isDaemon = true; start() }
        }
        threads.forEach { it.join(timeoutMs + 3_000) }
        val ms = SystemClock.elapsedRealtime() - t0
        val total = got.sum()
        val secs = maxOf(0.001, ms / 1000.0)
        return Result(((total * 8 / 1e6) / secs * 100).roundToLong() / 100.0, got.count { it > 0 }, total, ms)
    }

    /** One stream: SOCKS5 to the speed endpoint, then its body counted until [bytes] or the deadline. */
    private fun one(socksPort: Int, bytes: Int, deadline: Long): Long {
        var got = 0L
        runCatching {
            Socket(Proxy.NO_PROXY).use { s ->
                s.connect(InetSocketAddress("127.0.0.1", socksPort), 3_000)
                fun left() = (deadline - SystemClock.elapsedRealtime()).toInt()
                s.soTimeout = minOf(9_000, maxOf(1, left()))
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write(byteArrayOf(5, 1, 0)); out.flush()
                if (inp.read() != 5 || inp.read() != 0) return 0L
                val name = HOST.toByteArray(Charsets.US_ASCII)
                out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((PORT shr 8).toByte(), PORT.toByte()))
                out.flush()
                if (inp.read() != 5 || inp.read() != 0) return 0L
                inp.read()
                val skip = when (inp.read()) { 1 -> 4; 4 -> 16; 3 -> inp.read(); else -> return 0L }
                repeat(skip + 2) { if (inp.read() < 0) return 0L }
                out.write("GET /__down?bytes=$bytes HTTP/1.1\r\nHost: $HOST\r\nUser-Agent: mlmvpn-tunnel-check\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                if (!skipHead(inp)) return 0L
                val buf = ByteArray(16 * 1024)
                while (got < bytes) {
                    val l = left()
                    if (l <= 0) break
                    s.soTimeout = l
                    val n = inp.read(buf)
                    if (n < 0) break
                    got += n
                }
            }
        }
        return got
    }

    private fun skipHead(inp: InputStream): Boolean {
        var state = 0
        var seen = 0
        while (seen < 16 * 1024) {
            val b = inp.read()
            if (b < 0) return false
            seen++
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && state == 1 -> 2
                b == '\n'.code && state == 3 -> return true
                else -> 0
            }
        }
        return false
    }
}
