package com.mlmvpn.core.warp

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * The camouflaged route to Cloudflare's WARP API -- the one the tunnel core gives MASQUE and
 * WireGuard (aether `apifront.rs`), for «وارپ»'s own registration.
 *
 * `api.cloudflareclient.com` is filtered by name. The request goes instead to a random Cloudflare
 * edge address (141.101.113.0/24, the core's own prefix) with no DNS lookup, and the TLS
 * ClientHello leaves in small TCP segments with short pauses, so the SNI never sits in one packet
 * for the filter to read. The certificate and the host name are still checked in full: this hides
 * the name from the ISP, it does not trust anything it would not trust directly.
 *
 * TLS is driven by hand through an [SSLEngine] so every byte of the first flight is ours to cut.
 * An [javax.net.ssl.SSLSocket] layered over a socket may write through the file descriptor and
 * skip the stream, which would quietly send the ClientHello whole.
 */
internal object WarpApiFront {
    private val deadlineTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "warp-api-deadline").apply { isDaemon = true }
    }

    private const val TAG = "WarpApiFront"
    const val HOST = "api.cloudflareclient.com"
    private val EDGE_PREFIX = byteArrayOf(141.toByte(), 101, 113)
    private const val CONNECT_MS = 6_000
    private const val READ_MS = 12_000
    private const val MAX_BODY = 512 * 1024

    /**
     * An overall deadline across ALL edge attempts. Without it, up to six edges each with a 6 s
     * connect and several 12 s reads could keep a single `exchange` running for minutes with no
     * ceiling; the caller (identity acquisition) would then appear to hang. Once this passes, no new
     * edge is started and the collected failure is thrown.
     */
    private const val TOTAL_MS = 45_000L

    data class Answer(val status: Int, val body: String, val route: String)

    private val rng = SecureRandom()

    /** One HTTP/1.1 exchange with the WARP API over the camouflaged route. */
    fun exchange(method: String, path: String, headers: List<Pair<String, String>>, body: ByteArray?, timeoutMs: Long = TOTAL_MS): Answer {
        val request = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(HOST).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1) + (body ?: ByteArray(0))

        // Three edges with the ClientHello cut up, then one sent whole -- the order the core uses.
        // Then the edges proven on this network (CfEdge): any Cloudflare edge serves the WARP API,
        // and the Doctor reports showed whole ranges dead in some regions and alive in others, so a
        // fixed prefix alone can be the one that does not answer here.
        val core = edges(4).mapIndexed { i, edge -> edge to (i < 3) }
        val proven = com.mlmvpn.scanner.data.CfEdge.good(null, 2).map { InetSocketAddress(InetAddress.getByName(it), 443) to true }
        val plans = core.take(3) + proven + core.drop(3)
        var last: Exception? = null
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1, TOTAL_MS)
        for ((edge, fragment) in plans) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            if (System.currentTimeMillis() >= deadline) break
            val route = "${edge.address.hostAddress}${if (fragment) " split" else ""}"
            try {
                val answer = once(edge, fragment, request, route, deadline)
                Log.i(TAG, "$method WARP API -> ${answer.status}")
                return answer
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                Log.w(TAG, "$method WARP API failed: ${e.javaClass.simpleName}")
                last = e
            }
        }
        throw last ?: SSLException("no edge answered")
    }

    private fun edges(n: Int): List<InetSocketAddress> {
        val hosts = LinkedHashSet<Int>()
        while (hosts.size < n) hosts += 1 + rng.nextInt(254)
        return hosts.map { h ->
            InetSocketAddress(InetAddress.getByAddress(EDGE_PREFIX + byteArrayOf(h.toByte())), 443)
        }
    }

    private fun once(edge: InetSocketAddress, fragment: Boolean, request: ByteArray, route: String, deadline: Long): Answer {
        Socket().use { socket ->
            val owner = Thread.currentThread()
            val watchdog = deadlineTimer.scheduleAtFixedRate({
                if (owner.isInterrupted || System.currentTimeMillis() >= deadline) runCatching { socket.close() }
            }, 0, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
            try {
            socket.tcpNoDelay = true
            val remaining = (deadline - System.currentTimeMillis()).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            socket.connect(edge, minOf(CONNECT_MS, remaining))
            socket.soTimeout = minOf(READ_MS, remaining)
            val io = EngineIo(newEngine(), socket.getInputStream(), socket.getOutputStream(), fragment)
            io.handshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, io.engine.session)) {
                throw SSLException("certificate is not for $HOST")
            }
            io.send(request)
            val raw = io.receive()
            return parse(raw, route)
            } finally { watchdog.cancel(false) }
        }
    }

    private fun newEngine(): SSLEngine {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        return ctx.createSSLEngine(HOST, 443).apply {
            useClientMode = true
            val p = sslParameters
            p.serverNames = listOf(SNIHostName(HOST))
            runCatching { p.endpointIdentificationAlgorithm = "HTTPS" }
            sslParameters = p
        }
    }

    /** A blocking SSLEngine pump over a plain socket. */
    private class EngineIo(val engine: SSLEngine, val input: InputStream, val output: OutputStream, var fragment: Boolean) {
        private var netOut = ByteBuffer.allocate(engine.session.packetBufferSize)
        private var netIn = ByteBuffer.allocate(engine.session.packetBufferSize)
        private var appIn = ByteBuffer.allocate(engine.session.applicationBufferSize)
        private val empty = ByteBuffer.allocate(0)

        fun handshake() {
            engine.beginHandshake()
            var hs = engine.handshakeStatus
            while (hs != SSLEngineResult.HandshakeStatus.FINISHED && hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                hs = when (hs) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        val r = engine.wrap(empty, netOut)
                        if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) netOut = grow(netOut) else flush()
                        r.handshakeStatus
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        val r = unwrapOnce()
                        when (r.status) {
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> if (!readMore()) throw EOFException("closed during the handshake")
                            SSLEngineResult.Status.CLOSED -> throw EOFException("closed during the handshake")
                            else -> Unit
                        }
                        r.handshakeStatus
                    }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        while (true) engine.delegatedTask?.run() ?: break
                        engine.handshakeStatus
                    }
                    else -> engine.handshakeStatus
                }
            }
            flush()
        }

        fun send(data: ByteArray) {
            val src = ByteBuffer.wrap(data)
            while (src.hasRemaining()) {
                val r = engine.wrap(src, netOut)
                if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) netOut = grow(netOut) else flush()
            }
        }

        /** Everything the server sends until it closes or the HTTP answer is complete. */
        fun receive(): ByteArray {
            val all = ByteArrayOutputStream()
            while (true) {
                val r = unwrapOnce()
                appIn.flip()
                all.write(appIn.array(), 0, appIn.limit())
                appIn.clear()
                if (all.size() > MAX_BODY) throw SSLException("answer too large")
                if (complete(all.toByteArray())) return all.toByteArray()
                when (r.status) {
                    SSLEngineResult.Status.CLOSED -> return all.toByteArray()
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> if (!readMore()) return all.toByteArray()
                    else -> Unit
                }
            }
        }

        private fun unwrapOnce(): SSLEngineResult {
            while (true) {
                netIn.flip()
                val r = engine.unwrap(netIn, appIn)
                netIn.compact()
                if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) { appIn = grow(appIn); continue }
                return r
            }
        }

        private fun readMore(): Boolean {
            if (!netIn.hasRemaining()) netIn = grow(netIn)
            val n = input.read(netIn.array(), netIn.arrayOffset() + netIn.position(), netIn.remaining())
            if (n < 0) return false
            netIn.position(netIn.position() + n)
            return true
        }

        private fun flush() {
            netOut.flip()
            if (!netOut.hasRemaining()) { netOut.clear(); return }
            val bytes = ByteArray(netOut.remaining()).also { netOut.get(it) }
            netOut.clear()
            if (fragment) {
                // Only the first flight -- the ClientHello -- is cut; the rest is ordinary TLS.
                fragment = false
                writeSplit(bytes)
            } else {
                output.write(bytes)
                output.flush()
            }
        }

        private fun writeSplit(bytes: ByteArray) {
            val rng = SecureRandom()
            var at = 0
            var first = true
            while (at < bytes.size) {
                // A tiny first piece splits the record header itself; then 12–48 bytes each, so
                // the server name is spread over several segments whatever its offset.
                val size = if (first) 1 + rng.nextInt(4) else 12 + rng.nextInt(37)
                val end = minOf(bytes.size, at + size)
                output.write(bytes, at, end - at)
                output.flush()
                at = end
                first = false
                if (at < bytes.size) Thread.sleep(3L + rng.nextInt(8))
            }
        }

        private fun grow(b: ByteBuffer): ByteBuffer =
            ByteBuffer.allocate(b.capacity() * 2).also { b.flip(); it.put(b) }
    }

    private fun headerEnd(raw: ByteArray): Int {
        for (i in 0..raw.size - 4) {
            if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
                raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()) return i + 4
        }
        return -1
    }

    private fun headersOf(raw: ByteArray, end: Int): Map<String, String> =
        String(raw, 0, end, Charsets.ISO_8859_1).split("\r\n").drop(1)
            .mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }
            .toMap()

    private fun complete(raw: ByteArray): Boolean {
        val end = headerEnd(raw).takeIf { it > 0 } ?: return false
        val h = headersOf(raw, end)
        h["content-length"]?.toIntOrNull()?.let { return raw.size - end >= it }
        if (h["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return String(raw, end, raw.size - end, Charsets.ISO_8859_1).endsWith("0\r\n\r\n")
        }
        return false
    }

    private fun parse(raw: ByteArray, route: String): Answer {
        val end = headerEnd(raw).takeIf { it > 0 } ?: throw SSLException("no HTTP answer (${raw.size} bytes)")
        val status = String(raw, 0, end, Charsets.ISO_8859_1).substringBefore("\r\n").split(' ')
            .getOrNull(1)?.toIntOrNull() ?: throw SSLException("bad status line")
        val h = headersOf(raw, end)
        var body = raw.copyOfRange(end, raw.size)
        if (h["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) body = dechunk(body)
        h["content-length"]?.toIntOrNull()?.let { if (body.size > it) body = body.copyOf(it) }
        return Answer(status, String(body, Charsets.UTF_8), route)
    }

    private fun dechunk(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        while (at < b.size) {
            var lineEnd = at
            while (lineEnd + 1 < b.size && !(b[lineEnd] == '\r'.code.toByte() && b[lineEnd + 1] == '\n'.code.toByte())) lineEnd++
            val size = String(b, at, lineEnd - at, Charsets.ISO_8859_1).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            if (start + size > b.size) break
            out.write(b, start, size)
            at = start + size + 2
        }
        return out.toByteArray()
    }
}
