package com.mlmvpn.scanner.engines.amnezia

import java.io.InputStream
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The proof that a server carries real traffic: an HTTPS request through it, Google's certificate
 * checked, answered 204 (Ehsan, 31 Oct 2026: servers showed a delay, and even «connected», yet
 * nothing went through them). The plain-HTTP check before it passed on servers that only let a
 * small request to port 80 through, and on any page that answered 200 in place of the real one; a
 * TLS handshake also sends full-size packets, which a path with a smaller MTU drops.
 */
internal object AmzProof {
    const val HOST = "www.gstatic.com"
    private const val PATH = "/generate_204"

    /**
     * Over [raw], already connected to [HOST]:443 (directly, or through a SOCKS port). Closes it.
     * True only for a 204 from a server whose certificate is [HOST]'s.
     */
    fun https204(raw: Socket, timeoutMs: Int): Boolean {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket(raw, HOST, 443, true) as SSLSocket).use { ssl ->
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, ssl.session)) return false
            ssl.outputStream.write("GET $PATH HTTP/1.1\r\nHost: $HOST\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
            ssl.outputStream.flush()
            val line = readLine(ssl.inputStream) ?: return false
            return line.startsWith("HTTP/") && line.contains(" 204")
        }
    }

    /**
     * [raw] connected to an HTTP proxy, asked for a tunnel to [HOST]:443 (CONNECT), then
     * [https204] through it. Closes it.
     */
    fun https204ViaHttpProxy(raw: Socket, timeoutMs: Int): Boolean {
        raw.soTimeout = timeoutMs
        raw.getOutputStream().write("CONNECT $HOST:443 HTTP/1.1\r\nHost: $HOST:443\r\n\r\n".toByteArray())
        val input = raw.getInputStream()
        val status = readLine(input)
        if (status == null || !status.contains(" 200")) { raw.close(); return false }
        // the proxy's headers, up to the blank line
        while (true) {
            val h = readLine(input) ?: run { raw.close(); return false }
            if (h.isEmpty()) break
        }
        return https204(raw, timeoutMs)
    }

    /** One line, without its CR LF; null at the end of the stream. Reads byte by byte: no read-ahead. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 1024) {
            val b = input.read()
            if (b < 0) return sb.toString().takeIf { it.isNotEmpty() }
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}
