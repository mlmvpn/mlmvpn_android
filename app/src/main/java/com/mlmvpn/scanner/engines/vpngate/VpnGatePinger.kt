package com.mlmvpn.scanner.engines.vpngate

import com.mlmvpn.scanner.MyVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real latency measurement against the endpoint a server would actually be dialled on.
 *
 * The number VPN Gate publishes in the CSV is measured from *their* probes in Japan and says
 * nothing about the delay from the user's phone — which is the only figure worth showing.
 * So this opens a real TCP handshake to the `remote <host> <port>` of the server's own .ovpn
 * profile, exactly the socket the OpenVPN core will open, and reports the round trip.
 *
 * Sockets are `protect()`ed, so when a tunnel is already up the probe still leaves through
 * the physical interface and measures the real path instead of looping through the VPN.
 */
object VpnGatePinger {

    const val FAILED = -1

    private const val TIMEOUT_MS = 3000
    private const val ATTEMPTS = 2

    /** Max servers probed at once. High enough to be quick, low enough not to trip rate limits. */
    private const val PARALLELISM = 12

    private val REMOTE_RE = Regex("""^\s*remote\s+(\S+)\s+(\d+)""", RegexOption.MULTILINE)

    /** The endpoint the OpenVPN core will dial, straight from the profile. */
    fun remoteOf(ovpn: String): Pair<String, Int>? =
        REMOTE_RE.find(ovpn)?.let { m ->
            val port = m.groupValues[2].toIntOrNull() ?: return null
            m.groupValues[1] to port
        }

    /** Best of [ATTEMPTS] round trips in ms, or [FAILED]. */
    suspend fun ping(host: String, port: Int): Int = withContext(Dispatchers.IO) {
        var best = FAILED
        repeat(ATTEMPTS) {
            // Between attempts, not just between servers: each singleConnect can block for
            // the full TIMEOUT_MS, so without this a cancelled sweep still pays for the
            // second attempt on every host already in flight.
            ensureActive()
            val rtt = singleConnect(host, port)
            if (rtt != FAILED && (best == FAILED || rtt < best)) best = rtt
        }
        best
    }

    suspend fun ping(server: VpnGateServer, ovpn: String): Int {
        val (host, port) = remoteOf(ovpn) ?: (server.ip to 443)
        return ping(host, port)
    }

    /**
     * Probes [servers] in bounded parallel, publishing each result through [onResult] as soon
     * as it lands so the list fills in progressively instead of freezing until the last row.
     *
     * Worker pool, not `chunked(N).forEach { awaitAll }` — see the note on
     * [SoftEtherProbe.probeAll]. The chunked form makes every group of 12 a barrier, so one
     * dead host (2 attempts × 3s) stalls the eleven behind it, and a large selection looks
     * frozen. Cancellable throughout, so the UI's ✕ stops it promptly instead of leaving it
     * to grind out FAILED for the rest of the list.
     */
    suspend fun pingAll(
        servers: List<VpnGateServer>,
        ovpnOf: (VpnGateServer) -> String,
        onResult: (VpnGateServer, Int) -> Unit,
    ) = coroutineScope {
        val cursor = java.util.concurrent.atomic.AtomicInteger(0)
        List(minOf(PARALLELISM, servers.size)) {
            launch(Dispatchers.IO) {
                while (isActive) {
                    val i = cursor.getAndIncrement()
                    if (i >= servers.size) break
                    val server = servers[i]
                    val rtt = try {
                        ping(server, ovpnOf(server))
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        FAILED
                    }
                    try {
                        onResult(server, rtt)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        // One bad callback must not cancel the scope and kill every worker.
                    }
                }
            }
        }.joinAll()
    }

    private fun singleConnect(host: String, port: Int): Int {
        var socket: Socket? = null
        return try {
            val s = Socket()
            socket = s
            // Keep the probe off the tunnel — otherwise, once connected, every server would
            // appear to have the latency of the currently active one.
            MyVpnService.instance?.protect(s)
            s.soTimeout = TIMEOUT_MS
            val start = System.currentTimeMillis()
            s.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            (System.currentTimeMillis() - start).toInt()
        } catch (e: Exception) {
            FAILED
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
