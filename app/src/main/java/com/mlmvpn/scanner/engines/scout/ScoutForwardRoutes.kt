package com.mlmvpn.scanner.engines.scout

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** A scoped measurement endpoint for engines whose proven data path uses TCP forwards. */
object ScoutForwardRoutes {
    private val sessions = ConcurrentHashMap<Int, Lease>()
    class Lease internal constructor(private val routes: Map<Pair<String, Int>, Int>) : Closeable {
        private val reservation = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = reservation.localPort
        private val sockets = mutableListOf<Socket>()
        private var closed = false
        internal fun open(host: String, destinationPort: Int, timeoutMs: Int): Socket {
            val local = routes[host.lowercase() to destinationPort] ?: throw IOException("No tunnel forward for destination")
            val socket = Socket()
            synchronized(this) { check(!closed); sockets += socket }
            try {
                socket.soTimeout = timeoutMs
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress("127.0.0.1", local), timeoutMs)
                return socket
            } catch (e: Exception) { socket.close(); throw e }
        }
        override fun close() = synchronized(this) {
            closed = true
            sessions.remove(port, this)
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
            reservation.close()
        }
    }
    fun register(routes: Map<Pair<String, Int>, Int>): Lease {
        require(routes.isNotEmpty() && routes.values.all { it in 1..65535 })
        return Lease(routes.mapKeys { it.key.first.lowercase() to it.key.second }).also { sessions[it.port] = it }
    }
    /** Null means a normal SOCKS endpoint; a registered but unsupported destination throws. */
    fun open(port: Int, host: String, destinationPort: Int, timeoutMs: Int): Socket? =
        sessions[port]?.open(host, destinationPort, timeoutMs)
}
