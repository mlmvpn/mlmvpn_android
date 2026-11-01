package com.mlmvpn.scanner.engines.windscribe

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.engines.proton.ProtonCfCarrier
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A loopback port whose every connection is opened to [host]:[port] from inside Cloudflare.
 *
 * Measured on the user's home line (2026-10-10): every Windscribe address is blackholed -- no TCP
 * connect on any advertised port, no ping -- while the API (WSNet's own failover) answers. The same
 * carrier that brings Proton's Stealth there ([ProtonCfCarrier]: one of the user's Cloudflare configs,
 * a Worker that relays TCP, dialled with the handshake the Doctor proved on this line) carries
 * Windscribe's OpenVPN TCP, or the official Stealth/WStunnel proxy's TLS, to the server. The OpenVPN
 * session stays end to end with Windscribe and still checks the server's certificate.
 *
 * Its sockets go to 127.0.0.1 only; the carrier's own sockets are bound to the real network, so this
 * keeps working once the Windscribe tunnel is the device's VPN.
 */
internal class WsCfRelay private constructor(private val socksPort: Int, private val carrier: Closeable, val via: String, private val host: String, private val port: Int) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val localPort: Int = server.localPort
    @Volatile private var closed = false
    private val open = java.util.Collections.synchronizedSet(HashSet<Socket>())

    init { thread(name = "windscribe-cf-accept", isDaemon = true) { acceptLoop() } }

    private fun acceptLoop() {
        while (!closed) {
            val c = try { server.accept() } catch (_: Exception) { if (closed) return else continue }
            thread(name = "windscribe-cf-conn", isDaemon = true) { serve(c) }
        }
    }

    private fun serve(client: Socket) {
        var target: Socket? = null
        try {
            client.tcpNoDelay = true
            open += client
            target = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))).also { open += it }
            target.tcpNoDelay = true
            target.connect(InetSocketAddress.createUnresolved(host, port), 12_000)
            val t = target
            Log.i(TAG, "connection to $host:$port opened through $via")
            val down = thread(name = "windscribe-cf-down", isDaemon = true) { pump(t.getInputStream(), client.getOutputStream()); runCatching { client.shutdownOutput() } }
            pump(client.getInputStream(), t.getOutputStream())
            runCatching { t.shutdownOutput() }
            down.join()
        } catch (e: Exception) {
            if (!closed) Log.i(TAG, "connection to $host:$port failed (${e.javaClass.simpleName})")
        } finally {
            target?.let { runCatching { it.close() }; open -= it }
            runCatching { client.close() }; open -= client
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n); to.flush()
            }
        } catch (_: Exception) { }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        carrier.close()
    }

    companion object {
        private const val TAG = "Windscribe"

        /**
         * Cloudflare WARP first (no per-connection limit; the same session WireGuard rides), then one of
         * the user's own Cloudflare configs (a Worker cuts a connection after some megabytes, which
         * OpenVPN rides out by reconnecting). Seconds the first time on a network; null when neither
         * carries data here.
         */
        suspend fun open(context: Context, host: String, port: Int): WsCfRelay? {
            WsWarpCarrier.lease(context)?.let { w ->
                return try { WsCfRelay(w.port, w, "warp", host, port) } catch (e: Exception) { w.close(); null }
            }
            if (!ProtonCfCarrier.possible(context)) return null
            val lease = ProtonCfCarrier.acquire(context, FluxNet.current(context)) ?: return null
            return try { WsCfRelay(lease.port, lease, "worker", host, port) } catch (e: Exception) { lease.close(); null }
        }
    }
}
