package com.mlmvpn.scanner.engines.windscribe

import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import kotlin.concurrent.thread

/**
 * A loopback UDP port whose datagrams reach [host]:[port] through a SOCKS5 server's UDP ASSOCIATE --
 * the shared WARP carrier's ([WsWarpCarrier]), so OpenVPN UDP and WireGuard ride the one WARP session
 * the TCP connections use. A second WARP core with the same key would take that session away (one
 * session per key at Cloudflare): measured 2026-10-10, every test after one ran timed out.
 *
 * One local peer (the OpenVPN core or a probe's WireGuard): answers go to whoever sent last.
 */
internal class WsUdpRelay(socksPort: Int, private val host: String, private val port: Int) : Closeable {
    private val control = Socket()
    private val local = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    private val upstream = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    private val relayAt: InetSocketAddress
    val localPort: Int = local.localPort
    @Volatile private var peer: SocketAddress? = null
    @Volatile private var closed = false
    private val header: ByteArray

    init {
        try {
            control.connect(InetSocketAddress("127.0.0.1", socksPort), 5_000)
            control.soTimeout = 8_000
            val out = control.getOutputStream()
            val input = DataInputStream(control.getInputStream())
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
            // UDP ASSOCIATE from wherever we will send: 0.0.0.0:0.
            out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
            input.readUnsignedByte()
            val addr = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) })
                4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) })
                3 -> InetAddress.getByName(String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }))
                else -> error("address type")
            }
            val bndPort = input.readUnsignedShort()
            relayAt = InetSocketAddress(if (addr.isAnyLocalAddress) InetAddress.getByName("127.0.0.1") else addr, bndPort)
            control.soTimeout = 0
            val target = InetAddress.getByName(host)
            val raw = target.address
            header = byteArrayOf(0, 0, 0, if (raw.size == 4) 1 else 4) + raw + byteArrayOf((port shr 8).toByte(), port.toByte())
        } catch (e: Exception) {
            close(); throw e
        }
        thread(name = "windscribe-udp-up", isDaemon = true) { up() }
        thread(name = "windscribe-udp-down", isDaemon = true) { down() }
        // The association lives as long as its TCP connection: when the carrier drops it, so do we.
        thread(name = "windscribe-udp-ctl", isDaemon = true) { runCatching { control.getInputStream().read() }; if (!closed) { Log.i(TAG, "UDP association ended"); close() } }
    }

    private fun up() {
        val buf = ByteArray(65_535)
        while (!closed) {
            val p = DatagramPacket(buf, buf.size)
            try { local.receive(p) } catch (_: Exception) { break }
            peer = p.socketAddress
            val framed = ByteArray(header.size + p.length)
            System.arraycopy(header, 0, framed, 0, header.size)
            System.arraycopy(p.data, p.offset, framed, header.size, p.length)
            runCatching { upstream.send(DatagramPacket(framed, framed.size, relayAt)) }
        }
    }

    private fun down() {
        val buf = ByteArray(65_535)
        while (!closed) {
            val p = DatagramPacket(buf, buf.size)
            try { upstream.receive(p) } catch (_: Exception) { break }
            val to = peer ?: continue
            if (p.length < 4) continue
            val skip = when (buf[3].toInt()) { 1 -> 10; 4 -> 22; 3 -> 7 + (buf[4].toInt() and 0xff); else -> continue }
            if (p.length <= skip) continue
            runCatching { local.send(DatagramPacket(buf, skip, p.length - skip, to)) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { local.close() }; runCatching { upstream.close() }; runCatching { control.close() }
    }

    private companion object { const val TAG = "Windscribe" }
}
