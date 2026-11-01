package com.mlmvpn.core.tunnel.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Whether a bridge can be reached from THIS line, and how far away it is -- measured the way each
 * transport is actually dialled, never with ICMP (most bridges drop it, and a ping that answers
 * says nothing about the port Tor needs).
 *
 *  - obfs4 / vanilla: a TCP connect to the bridge's address and port. The round trip is the time
 *    to the SYN-ACK. This is reachability, not a full handshake: a carrier that lets the
 *    connection open and then kills the obfs4 stream is only caught by Tor itself -- which is why
 *    the bridges Tor really bootstrapped through are remembered and ranked first (BridgeManager).
 *  - webtunnel: the URL's host is resolved and a TLS handshake made with its name and its
 *    certificate checked, because that is exactly what the transport does first. A host that
 *    resolves to the block page, or whose TLS is cut, fails here as it would in Tor.
 *  - meek / snowflake: a TLS handshake with the FRONT (the CDN name the censor sees). Their
 *    bridge address is a placeholder Tor never dials; whether the front answers is what decides.
 */
internal object BridgeTester {

    enum class Outcome { OK, FAILED, NEEDS_IPV6 }

    data class Result(val outcome: Outcome, val rttMs: Long?)

    private const val TIMEOUT_MS = 5_000

    /** Many at once, a few at a time; one dead bridge never holds up the rest. */
    suspend fun testAll(
        bridges: List<TorBridge>,
        ipv6: Boolean,
        parallel: Int = 12,
        onEach: (TorBridge, Result) -> Unit,
    ) = coroutineScope {
        val gate = Semaphore(parallel)
        bridges.map { b ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val r = runCatching { test(b, ipv6) }.getOrDefault(Result(Outcome.FAILED, null))
                    onEach(b, r)
                }
            }
        }.awaitAll()
    }

    suspend fun test(b: TorBridge, ipv6: Boolean): Result = withContext(Dispatchers.IO) {
        when (b.transport) {
            BridgeTransport.OBFS4, BridgeTransport.VANILLA -> {
                if (b.family == IpFamily.V6 && !ipv6) return@withContext Result(Outcome.NEEDS_IPV6, null)
                val addr = runCatching { InetAddress.getByName(b.host) }.getOrNull() ?: return@withContext Result(Outcome.FAILED, null)
                tcp(addr, b.port)?.let { Result(Outcome.OK, it) } ?: Result(Outcome.FAILED, null)
            }
            BridgeTransport.WEBTUNNEL, BridgeTransport.MEEK, BridgeTransport.SNOWFLAKE -> {
                val all = runCatching { InetAddress.getAllByName(b.dialHost).toList() }.getOrDefault(emptyList())
                    .filter { !(it.isSiteLocalAddress || it.isLoopbackAddress || it.isAnyLocalAddress) }
                val usable = all.filter { it is Inet4Address || (ipv6 && it is Inet6Address) }
                if (usable.isEmpty()) {
                    return@withContext Result(if (all.isNotEmpty() && all.all { it is Inet6Address }) Outcome.NEEDS_IPV6 else Outcome.FAILED, null)
                }
                val verifyName = b.transport == BridgeTransport.WEBTUNNEL
                for (a in usable.take(2)) {
                    tls(a, b.dialPort, b.dialHost, verifyName)?.let { return@withContext Result(Outcome.OK, it) }
                }
                Result(Outcome.FAILED, null)
            }
        }
    }

    /** Milliseconds to an open TCP connection, or null. */
    private fun tcp(addr: InetAddress, port: Int): Long? = runCatching {
        val t0 = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(addr, port), TIMEOUT_MS) }
        (System.nanoTime() - t0) / 1_000_000
    }.getOrNull()

    /** TCP round trip of a connection whose TLS handshake (as [sni]) then completed; or null. */
    private fun tls(addr: InetAddress, port: Int, sni: String, verifyName: Boolean): Long? = runCatching {
        Socket().use { raw ->
            val t0 = System.nanoTime()
            raw.connect(InetSocketAddress(addr, port), TIMEOUT_MS)
            val rtt = (System.nanoTime() - t0) / 1_000_000
            raw.soTimeout = TIMEOUT_MS
            val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, sni, port, false) as SSLSocket
            s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
            s.use { tlsSock ->
                tlsSock.startHandshake()
                if (verifyName && !HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, tlsSock.session)) return@runCatching null
            }
            rtt
        }
    }.getOrNull()
}
