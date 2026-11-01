package com.mlmvpn.scanner.openvpn

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.utils.LocalPort
import com.mlmvpn.scanner.utils.NetworkSettings
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The app's Local Port while an OpenVPN tunnel is up: a SOCKS5 listener (CONNECT) whose connections
 * leave through that tunnel, because this app is inside it.
 *
 * Every Xray engine already offers the Local Port, and LAN sharing is built on it: another device
 * on the hotspot or Wi-Fi reaches the tunnel through it (directly, or through [com.mlmvpn.scanner.lan.LanRelay]).
 * OpenVPN has no proxy of its own, so a phone sharing its connection fell silent the moment the
 * tunnel was OpenVPN (the OpenVPN tile, Windscribe's OpenVPN family). The same rules as the Xray
 * inbound apply: bound to the network only while sharing is active, the share password asked when
 * one is set (user [NetworkSettings.LAN_USER]), blocked devices refused.
 */
object OpenVpnSocks {
    private const val TAG = "OpenVpnSocks"

    @Volatile private var server: ServerSocket? = null
    /** The port listening now, 0 when none. */
    @Volatile var port: Int = 0
        private set

    @Synchronized fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val share = com.mlmvpn.scanner.lan.LanShare.sharingActive(app)
        val p = LocalPort.get(app)
        val s = try {
            ServerSocket(p, 64, InetAddress.getByName(if (share) "0.0.0.0" else "127.0.0.1"))
        } catch (e: Exception) { Log.w(TAG, "could not bind $p: ${e.message}"); return }
        server = s; port = p
        Log.i(TAG, "listening on ${if (share) "0.0.0.0" else "127.0.0.1"}:$p")
        thread(name = "ovpn-socks", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(name = "ovpn-socks-conn", isDaemon = true) { serve(app, c) }
            }
        }
        // The counted LAN door (and its HTTP front end) follows the engine that is up now.
        if (share) runCatching { com.mlmvpn.scanner.lan.LanRelay.sync(app, com.mlmvpn.scanner.lan.LanShare.status(app)) }
    }

    @Synchronized fun stop() {
        val s = server ?: return
        server = null; port = 0
        runCatching { s.close() }
        Log.i(TAG, "stopped")
    }

    private fun serve(context: Context, client: Socket) {
        var target: Socket? = null
        try {
            val peer = client.inetAddress
            if (!peer.isLoopbackAddress && peer.hostAddress in NetworkSettings.lanBlocked(context)) return
            client.tcpNoDelay = true
            client.soTimeout = 20_000
            val input = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()
            if (input.readUnsignedByte() != 5) return
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            val password = NetworkSettings.lanPassword(context)?.takeIf { com.mlmvpn.scanner.lan.LanShare.sharingActive(context) }
            if (password != null) {
                if (methods.none { it.toInt() == 2 }) { out.write(byteArrayOf(5, 0xFF.toByte())); return }
                out.write(byteArrayOf(5, 2)); out.flush()
                if (input.readUnsignedByte() != 1) return
                val user = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                val pass = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                val ok = user == NetworkSettings.LAN_USER && pass == password
                out.write(byteArrayOf(1, if (ok) 0 else 1)); out.flush()
                if (!ok) return
            } else {
                out.write(byteArrayOf(5, 0)); out.flush()
            }
            val ver = input.readUnsignedByte(); val cmd = input.readUnsignedByte(); input.readUnsignedByte()
            val host = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
                4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
                3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
                else -> null
            }
            val dport = input.readUnsignedShort()
            if (ver != 5 || cmd != 1 || host == null) { reply(out, 7); return }
            val t = try { Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, dport), 15_000) } }
                catch (_: Exception) { reply(out, 5); return }
            target = t
            reply(out, 0)
            client.soTimeout = 0
            val down = thread(name = "ovpn-socks-down", isDaemon = true) { pump(t.getInputStream(), out); runCatching { client.shutdownOutput() } }
            pump(input, t.getOutputStream())
            runCatching { t.shutdownOutput() }
            down.join()
        } catch (_: Exception) {
        } finally {
            runCatching { target?.close() }
            runCatching { client.close() }
        }
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
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
}
