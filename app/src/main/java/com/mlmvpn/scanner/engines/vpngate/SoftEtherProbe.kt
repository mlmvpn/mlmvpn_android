package com.mlmvpn.scanner.engines.vpngate

import com.mlmvpn.scanner.MyVpnService
import kittoku.mvc.client.WATERMARK
import kittoku.mvc.unit.property.PropertyPack
import kittoku.mvc.unit.property.SEP_ERROR
import kittoku.mvc.unit.property.SEP_RANDOM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A real handshake test, as opposed to the latency probe.
 *
 * [VpnGatePinger] only opens a TCP connection, which on a lot of mobile carriers succeeds
 * against servers that then refuse to talk: the port answers, the number looks healthy, and the
 * connect fails anyway. This carries the exchange far enough to prove the server is a working
 * SoftEther listener that accepts *us*:
 *
 *   1. TCP connect
 *   2. TLS handshake — also catches interception, since an intercepting middlebox terminates
 *      TLS itself and the SoftEther layer behind it never answers
 *   3. `POST /vpnsvc/connect.cgi` carrying SoftEther's watermark, exactly as the real client
 *      opens a session
 *   4. HTTP 200 whose body parses as a SoftEther property pack with a server random and no
 *      error code
 *
 * A server that clears step 4 has agreed to start a session. The elapsed time is the honest
 * cost of reaching that point, which is the number worth showing.
 *
 * Deliberately separate from the ping path — nothing here touches [VpnGatePinger].
 */
object SoftEtherProbe {

    private const val TAG = "SoftEtherProbe"

    /** Why a server failed, in the order the checks run. */
    enum class Failure { UNREACHABLE, TLS_BLOCKED, NOT_SOFTETHER, REFUSED, TIMEOUT }

    sealed class Result {
        /** Handshake completed; [ms] is the time to a usable session offer. */
        data class Ok(val ms: Int) : Result()
        data class Failed(val reason: Failure) : Result()
    }

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 8_000
    /**
     * Concurrent probes. Raised from 8 once [probeAll] stopped wasting slots on the chunk
     * barrier: a whole-continent sweep is hundreds of servers and the wall-clock cost is set
     * almost entirely by the dead ones sitting out their timeouts. These are network-bound
     * TLS handshakes, and Dispatchers.IO carries 64 threads by default, so 16 is comfortable.
     */
    private const val PARALLELISM = 16

    /** Guard against a hostile or broken server streaming forever. */
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    /**
     * Hard ceiling on one server, enforced by closing its socket from a watchdog.
     *
     * The per-operation timeouts do not add up to a bound. `soTimeout` covers reads;
     * `connect` has its own. Nothing covers `startHandshake()` against a peer that completes
     * the TCP connect and then goes silent mid-handshake, and Java sockets have no write
     * timeout at all — a peer that stops draining leaves `write()` blocked forever. A worker
     * parked in either one never returns to the queue, so with enough of them the sweep stops
     * partway through and simply abandons the rest of the list.
     *
     * A coroutine timeout cannot fix this: blocking socket I/O has no suspension point to
     * cancel at. Closing the socket underneath does — the blocked call throws immediately.
     */
    private const val HARD_TIMEOUT_MS = 20_000L

    /**
     * SoftEther relays are authenticated by the hub credentials and the watermark exchange,
     * not by PKI, and the official client does not require a trusted chain either. Volunteer
     * relays therefore run self-signed certificates — and those are exactly the servers a
     * blocked line can still reach, since the operator's own address range is the part that
     * gets filtered. Validating against the system store threw all of them away as
     * "CertPathValidatorException: Trust anchor not found" before a single byte of SoftEther
     * was exchanged.
     */
    private val trustAllFactory: SSLSocketFactory by lazy {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
        )
        javax.net.ssl.SSLContext.getInstance("TLS")
            .apply { init(null, trustAll, java.security.SecureRandom()) }
            .socketFactory
    }

    private fun sniFor(host: String): String =
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) "www.opengw.net"
        else host

    suspend fun probe(host: String, port: Int): Result = coroutineScope {
        val socketRef = java.util.concurrent.atomic.AtomicReference<SSLSocket?>(null)
        val watchdog = launch {
            kotlinx.coroutines.delay(HARD_TIMEOUT_MS)
            android.util.Log.w(TAG, "hard timeout on $host — closing socket")
            try { socketRef.get()?.close() } catch (_: Exception) {}
        }
        try {
            // SoftEther listens on 443, 992 and 5555 out of the box, but the directory only
            // publishes one of them. When a carrier filters the published port the others are
            // often still open, so a single-port verdict understates how many relays are
            // usable. Alternates are only tried when the listed port gave nothing at all —
            // never after a real answer, which would just slow the sweep down.
            val first = probeInner(host, port, socketRef)
            if (first is Result.Ok) return@coroutineScope first
            val reason = (first as Result.Failed).reason
            if (reason != Failure.UNREACHABLE && reason != Failure.TIMEOUT &&
                reason != Failure.TLS_BLOCKED
            ) return@coroutineScope first

            for (alt in ALT_PORTS) {
                if (alt == port) continue
                if (!isActive) break
                val r = probeInner(host, alt, socketRef)
                if (r is Result.Ok) {
                    android.util.Log.i(TAG, "port fallback worked: $host:$alt (listed $port)")
                    return@coroutineScope r
                }
            }
            first
        } finally {
            watchdog.cancel()
        }
    }

    /** Ports a stock SoftEther server accepts SSL-VPN on, besides whatever the list gives. */
    private val ALT_PORTS = intArrayOf(443, 992, 5555)

    private suspend fun probeInner(
        host: String,
        port: Int,
        socketRef: java.util.concurrent.atomic.AtomicReference<SSLSocket?>,
    ): Result = withContext(Dispatchers.IO) {
        var socket: SSLSocket? = null
        try {
            val started = System.currentTimeMillis()

            val s = (trustAllFactory.createSocket() as SSLSocket).apply {
                // Keep the probe off any tunnel that is already up, so it measures the real
                // path rather than the active session's.
                MyVpnService.instance?.protect(this)
                // Published before connect(), so the watchdog can reach it even if the
                // connect itself is what hangs.
                socketRef.set(this)
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
            }
            socket = s

            // EXPERIMENT: send an SNI. Connecting by IP produces a ClientHello with no
            // server_name at all, and on Irancell every handshake dies as a read timeout —
            // the hello leaves and nothing comes back, which is what SNI-less TLS to a
            // non-whitelisted address looks like. SoftEther does no virtual hosting, so the
            // name is ignored by the server.
            try {
                val params = s.sslParameters
                params.serverNames = listOf(javax.net.ssl.SNIHostName(sniFor(host)))
                s.sslParameters = params
            } catch (e: Exception) {
                android.util.Log.d(TAG, "SNI rejected: ${e.message}")
            }

            try {
                s.startHandshake()
            } catch (e: Exception) {
                // The exception class and message are the only thing that separates "the
                // carrier reset the handshake" from "the certificate was rejected" from "the
                // server simply isn't a TLS listener" — all three land here and used to be
                // reported identically as TLS_BLOCKED.
                android.util.Log.d(
                    TAG,
                    "TLS failed $host:$port — ${e.javaClass.simpleName}: ${e.message}"
                )
                return@withContext Result.Failed(Failure.TLS_BLOCKED)
            }

            val body = ByteBuffer.allocate(WATERMARK.size).put(WATERMARK).array()
            val request = buildString {
                append("POST /vpnsvc/connect.cgi HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Content-Type: image/jpeg\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: Keep-Alive\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)

            s.outputStream.apply {
                write(request)
                write(body)
                flush()
            }

            val response = readHttpResponse(s.inputStream)
                ?: return@withContext Result.Failed(Failure.NOT_SOFTETHER)

            if (!response.first.startsWith("HTTP/1.1 200")) {
                return@withContext Result.Failed(Failure.REFUSED)
            }

            // Only a genuine SoftEther listener answers the watermark with a property pack
            // carrying a server random.
            val pack = try {
                PropertyPack().also { it.read(ByteBuffer.wrap(response.second)) }
            } catch (e: Exception) {
                return@withContext Result.Failed(Failure.NOT_SOFTETHER)
            }
            if (pack.intProperties[SEP_ERROR] != null) return@withContext Result.Failed(Failure.REFUSED)
            if (pack.bytesProperties[SEP_RANDOM] == null) return@withContext Result.Failed(Failure.NOT_SOFTETHER)

            Result.Ok((System.currentTimeMillis() - started).toInt())
        } catch (e: java.net.SocketTimeoutException) {
            Result.Failed(Failure.TIMEOUT)
        } catch (c: CancellationException) {
            // CancellationException is an Exception, so the catch below used to swallow it and
            // report the server as UNREACHABLE. That both slandered a healthy server and left
            // the caller's worker loop running after the screen was gone. Cancellation is not
            // a probe result.
            throw c
        } catch (e: Exception) {
            Result.Failed(Failure.UNREACHABLE)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /** @return start line and body, or null if the response is not parseable HTTP. */
    private fun readHttpResponse(input: InputStream): Pair<String, ByteArray>? {
        val header = StringBuilder()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            val c = b.toChar()
            header.append(c)
            matched = when {
                (matched == 0 || matched == 2) && c == '\r' -> matched + 1
                (matched == 1 || matched == 3) && c == '\n' -> matched + 1
                else -> 0
            }
            if (header.length > 8192) return null
        }

        val lines = header.toString().split("\r\n")
        val startLine = lines.firstOrNull() ?: return null
        val length = lines
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull()
            ?: return startLine to ByteArray(0)

        if (length !in 0..MAX_RESPONSE_BYTES) return null

        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return startLine to body
    }

    /**
     * Probes a list in bounded parallel, reporting each result as it lands so the list fills
     * in progressively.
     *
     * [PARALLELISM] long-lived workers pull from a shared cursor. The obvious alternative —
     * `servers.chunked(PARALLELISM).forEach { it.map { async {...} }.forEach { it.await() } }`
     * — is what this used to do, and it makes every group of 8 a barrier: the slowest server
     * in a chunk gates the next chunk, so seven results in 300ms are followed by a 14-second
     * stall on one dead host (CONNECT_TIMEOUT + READ_TIMEOUT). Selecting a whole continent is
     * mostly dead hosts, so nearly every chunk paid full price and the sweep looked like it
     * had stopped after the first handful. With a worker pool a slow server occupies exactly
     * one of the slots it deserves.
     */
    suspend fun probeAll(
        servers: List<VpnGateServer>,
        onResult: (VpnGateServer, Result) -> Unit,
    ) = coroutineScope {
        val cursor = java.util.concurrent.atomic.AtomicInteger(0)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        android.util.Log.i(TAG, "probeAll: starting ${servers.size} servers on $PARALLELISM workers")
        List(minOf(PARALLELISM, servers.size)) { workerId ->
            launch(Dispatchers.IO) {
                while (true) {
                    // Checked here rather than in the `while` condition so an inactive worker
                    // is reported instead of silently vanishing — a sweep that ends early
                    // with servers left unclaimed is exactly the bug this logging is for.
                    if (!isActive) {
                        android.util.Log.w(TAG, "worker $workerId stopping: no longer active")
                        break
                    }
                    val i = cursor.getAndIncrement()
                    if (i >= servers.size) break
                    val server = servers[i]
                    // Always the SoftEther listener, never the profile's OpenVPN `remote` —
                    // they are different services on different ports.
                    val result = probe(server.ip, SOFTETHER_PORT)
                    // onResult belongs OUTSIDE probe's own catch, so guard it here. It writes
                    // to a StateFlow the UI collects; letting anything it throws escape would
                    // cancel this coroutineScope and with it every other worker — one bad
                    // result silently killing the whole sweep.
                    try {
                        onResult(server, result)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        android.util.Log.w(TAG, "onResult threw for ${server.hostName}", t)
                    }
                    completed.incrementAndGet()
                }
            }
        }.joinAll()
        android.util.Log.i(TAG, "probeAll: finished ${completed.get()}/${servers.size}")
    }

    /** SoftEther's SSL-VPN listener. VPN Gate always publishes 443 for it. */
    private const val SOFTETHER_PORT = 443
}
