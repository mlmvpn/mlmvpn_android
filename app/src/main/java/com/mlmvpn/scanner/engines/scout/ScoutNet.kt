package com.mlmvpn.scanner.engines.scout

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The shared yardstick's hands: real requests, either through a method's local SOCKS port (the
 * name resolved at the far end, as an app's traffic would be) or straight over the phone's own
 * line. Every method is measured with exactly these, which is what makes their numbers comparable.
 *
 * Targets are deliberately NOT on Cloudflare: a Worker-based method cannot open a Cloudflare
 * address at all, and measuring it against one would fail it for the wrong reason.
 *
 * Everything here blocks. Callers run it inside `runInterruptible(Dispatchers.IO)`; [guarded]
 * closes the socket when that thread is interrupted, so a stop never waits out a timeout.
 */
internal object ScoutNet {

    /** Google's 204: small, everywhere, not on Cloudflare. "Data went through" means this answered. */
    const val VERIFY_HOST = "www.gstatic.com"
    /** Filtered in Iran by name: a method that promises to get past the filter must open it. */
    const val FILTERED_HOST = "www.youtube.com"
    const val PATH_204 = "/generate_204"
    /** The same CDN used by manual Geph probes; range requests keep every sample small. */
    const val SPEED_URL = "https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb"

    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "scout-io").apply { isDaemon = true } }

    fun <T> guarded(s: Socket, block: () -> T): T {
        val owner = Thread.currentThread()
        val task = watchdog.scheduleAtFixedRate({ if (owner.isInterrupted) runCatching { s.close() } }, 50, 50, TimeUnit.MILLISECONDS)
        try {
            return block()
        } finally {
            task.cancel(false)
        }
    }

    fun resolve(network: Network?, host: String): List<InetAddress> =
        (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList()

    /** A connected TCP socket: through [socksPort] when given, else over [network] (or the default). */
    fun open(socksPort: Int?, network: Network?, host: String, port: Int, timeoutMs: Int, address: InetAddress? = null): Socket {
        if (socksPort != null) ScoutForwardRoutes.open(socksPort, host, port, timeoutMs)?.let { return it }
        val s = when {
            socksPort != null -> Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            network != null -> network.socketFactory.createSocket()
            else -> Socket()
        }
        try {
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val target = when {
                socksPort != null -> InetSocketAddress.createUnresolved(host, port)
                else -> InetSocketAddress(address ?: resolve(network, host).first(), port)
            }
            s.connect(target, timeoutMs)
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    /** TLS over [raw] with [sni], the certificate checked for [host] -- a block page cannot pass. */
    fun tls(raw: Socket, host: String, timeoutMs: Int, sni: String = host, verifyName: Boolean = true): SSLSocket {
        val f = SSLSocketFactory.getDefault() as SSLSocketFactory
        val t = f.createSocket(raw, host, 443, true) as SSLSocket
        t.sslParameters = t.sslParameters.apply {
            serverNames = listOf(SNIHostName(sni))
            if (verifyName) endpointIdentificationAlgorithm = "HTTPS"
        }
        t.soTimeout = timeoutMs
        t.startHandshake()
        return t
    }

    class Resp(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    /** One HTTP/1.1 request on an open socket. Keeps the connection usable when [keepAlive]. */
    fun request(s: Socket, host: String, path: String, keepAlive: Boolean, maxBody: Int = 16 * 1024): Resp {
        val out = s.getOutputStream()
        out.write(("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0 (Linux; Android) MLMScout\r\n" +
            "Accept: */*\r\nConnection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n").toByteArray())
        out.flush()
        return readResponse(s.getInputStream(), maxBody)
    }

    fun readResponse(input: InputStream, maxBody: Int): Resp {
        val status = readLine(input) ?: throw java.io.EOFException("no status line")
        val code = Regex("^HTTP/1\\.[01] (\\d{3})").find(status)?.groupValues?.get(1)?.toInt()
            ?: throw java.io.IOException("not http")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (code == 204 || code == 304 || code in 100..199) return Resp(code, headers, ByteArray(0))
        val body = ByteArrayOutputStream()
        val len = headers["content-length"]?.toLongOrNull()
        when {
            headers["transfer-encoding"]?.contains("chunked", true) == true -> {
                while (body.size() < maxBody) {
                    val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
                    if (size == 0) { readLine(input); break }
                    copy(input, body, size, maxBody)
                    readLine(input)
                }
            }
            len != null -> copy(input, body, len.toInt().coerceAtMost(maxBody), maxBody)
            else -> copy(input, body, Int.MAX_VALUE, maxBody)
        }
        return Resp(code, headers, body.toByteArray())
    }

    private fun copy(input: InputStream, out: ByteArrayOutputStream, n: Int, max: Int) {
        val buf = ByteArray(8192)
        var left = n
        while (left > 0 && out.size() < max) {
            val r = input.read(buf, 0, minOf(buf.size, left, max - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
            left -= r
        }
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            if (b.size() < 8192) b.write(c)
        }
    }

    /** A 204 from [host] over HTTPS, timed from the first byte sent; the server's Date header with it. */
    data class Hit(val ms: Long, val date: String?)

    fun https204(socksPort: Int?, network: Network?, host: String, timeoutMs: Int, address: InetAddress? = null): Hit? = runCatching {
        val t0 = System.nanoTime()
        open(socksPort, network, host, 443, timeoutMs, address).use { raw ->
            guarded(raw) {
                tls(raw, host, timeoutMs).use { t ->
                    val r = request(t, host, PATH_204, keepAlive = false, maxBody = 0)
                    if (r.code == 204) Hit((System.nanoTime() - t0) / 1_000_000, r.headers["date"]) else null
                }
            }
        }
    }.getOrNull()

    /** Plain HTTP; for the line's own check of whether something rewrites unencrypted traffic. */
    fun http(network: Network?, host: String, path: String, timeoutMs: Int): Resp? = runCatching {
        open(null, network, host, 80, timeoutMs).use { s -> guarded(s) { request(s, host, path, keepAlive = false, maxBody = 4096) } }
    }.getOrNull()

    /**
     * [n] small HTTPS requests on ONE open connection through the method, each timed on its own.
     * The first request (which pays for the handshakes) is not counted. A connection the far end
     * closes is reopened and the train goes on; an empty list means nothing answered at all.
     */
    fun latencyTrain(socksPort: Int, n: Int, timeoutMs: Int): List<Long> {
        val out = ArrayList<Long>(n)
        var conn: SSLSocket? = null
        var raw: Socket? = null
        fun connect(): SSLSocket {
            raw = open(socksPort, null, VERIFY_HOST, 443, timeoutMs)
            return tls(raw!!, VERIFY_HOST, timeoutMs).also { t -> request(t, VERIFY_HOST, PATH_204, keepAlive = true, maxBody = 0) }
        }
        var failures = 0
        try {
            conn = connect()
            while (out.size < n && failures < 3) {
                val c = conn ?: break
                val t0 = System.nanoTime()
                val ok = runCatching { guarded(raw!!) { request(c, VERIFY_HOST, PATH_204, keepAlive = true, maxBody = 0).code == 204 } }.getOrDefault(false)
                if (ok) {
                    out += (System.nanoTime() - t0) / 1_000_000
                } else {
                    failures++
                    runCatching { conn?.close() }
                    conn = runCatching { connect() }.getOrNull()
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { conn?.close() }
            runCatching { raw?.close() }
        }
        return out
    }

    /** Where a method comes out, asked through it; two letters or null. Three sources, none on Cloudflare first. */
    fun exitCountry(socksPort: Int, timeoutMs: Int): String? {
        fun cc(s: String?) = s?.trim()?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2}")) }
        runCatching {
            open(socksPort, null, "ipinfo.io", 443, timeoutMs).use { raw ->
                guarded(raw) {
                    tls(raw, "ipinfo.io", timeoutMs).use { t ->
                        val b = String(request(t, "ipinfo.io", "/json", keepAlive = false, maxBody = 2048).body)
                        cc(Regex("\"country\"\\s*:\\s*\"([A-Za-z]{2})\"").find(b)?.groupValues?.get(1))
                    }
                }
            }
        }.getOrNull()?.let { return it }
        return runCatching {
            open(socksPort, null, "www.cloudflare.com", 443, timeoutMs).use { raw ->
                guarded(raw) {
                    tls(raw, "www.cloudflare.com", timeoutMs).use { t ->
                        val b = String(request(t, "www.cloudflare.com", "/cdn-cgi/trace", keepAlive = false, maxBody = 2048).body)
                        cc(Regex("(?m)^loc=([A-Za-z]{2})").find(b)?.groupValues?.get(1))
                    }
                }
            }
        }.getOrNull()
    }

    data class Speed(val bytes: Int, val ms: Long) {
        val mbps: Double get() = if (ms <= 0) 0.0 else bytes * 8.0 / 1_000_000.0 / (ms / 1000.0)
    }

    /** A listener or a WireGuard handshake is insufficient: prove WARP at the HTTPS exit. */
    fun warpVerified(socksPort: Int, timeoutMs: Int): Boolean = runCatching {
        val host = "www.cloudflare.com"
        open(socksPort, null, host, 443, timeoutMs).use { raw ->
            guarded(raw) {
                tls(raw, host, timeoutMs).use { socket ->
                    val response = request(socket, host, "/cdn-cgi/trace", keepAlive = false, maxBody = 4096)
                    ScoutWarpPolicy.verified(response.code, String(response.body, Charsets.UTF_8))
                }
            }
        }
    }.getOrDefault(false)

    /**
     * A short throughput sample: [maxBytes] of [url], timed from the first byte of the body to the
     * last (the handshakes are the connect time's business, not the speed's). Null when nothing came.
     */
    fun download(socksPort: Int?, network: Network?, url: String, maxBytes: Int, timeoutMs: Int): Speed? = runCatching {
        val u = java.net.URL(url)
        val host = u.host
        open(socksPort, network, host, 443, timeoutMs).use { raw ->
            guarded(raw) {
                tls(raw, host, timeoutMs).use { t ->
                    val out = t.getOutputStream()
                    out.write("GET ${u.file} HTTP/1.1\r\nHost: $host\r\nUser-Agent: MLMScout\r\nRange: bytes=0-${maxBytes - 1}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    val input = t.getInputStream()
                    val status = readLine(input) ?: return@use null
                    if (!status.matches(Regex("^HTTP/1\\.[01] 20[06] .*"))) return@use null
                    while (readLine(input)?.isNotEmpty() == true) Unit
                    val buf = ByteArray(16 * 1024)
                    var got = 0
                    var first = 0L
                    while (got < maxBytes) {
                        val r = input.read(buf, 0, minOf(buf.size, maxBytes - got))
                        if (r < 0) break
                        if (got == 0) first = System.nanoTime()
                        got += r
                    }
                    if (got < 64 * 1024) null else Speed(got, (System.nanoTime() - first) / 1_000_000)
                }
            }
        }
    }.getOrNull()

    /** Time to a TCP handshake over [network], or null. */
    fun tcp(network: Network?, address: InetAddress, port: Int, timeoutMs: Int): Long? = runCatching {
        val t0 = System.nanoTime()
        open(null, network, address.hostAddress ?: "", port, timeoutMs, address).use { (System.nanoTime() - t0) / 1_000_000 }
    }.getOrNull()

    /** Time to a finished TLS handshake with [sni] at [address] over [network], or null. */
    fun tlsTime(network: Network?, address: InetAddress, sni: String, timeoutMs: Int, verifyName: Boolean = true): Long? = runCatching {
        val t0 = System.nanoTime()
        open(null, network, sni, 443, timeoutMs, address).use { raw ->
            guarded(raw) { tls(raw, sni, timeoutMs, sni, verifyName).use { (System.nanoTime() - t0) / 1_000_000 } }
        }
    }.getOrNull()

    fun median(v: List<Long>): Long? = v.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    fun p95(v: List<Long>): Long? = v.sorted().let { if (it.isEmpty()) null else it[((it.size - 1) * 95) / 100] }
}
