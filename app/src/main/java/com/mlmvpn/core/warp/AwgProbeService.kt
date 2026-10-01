package com.mlmvpn.core.warp

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import org.amnezia.awg.backend.ProxyGoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import org.amnezia.awg.config.proxy.Socks5Proxy
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket

/**
 * Tests a server that only the AmneziaWG core can talk to (its own S1/S2/H1..H4, or signature
 * packets Xray cannot build), in a process of its own (`:awgprobe`).
 *
 * The library's proxy mode brings the tunnel up as a local SOCKS5 listener -- no VpnService, no
 * TUN -- so a real request can go through the server while the device's own VPN is untouched.
 * Its own process for the same reason the tunnel has one (one Go runtime per process), and apart
 * from the tunnel's because the proxy backend keeps process-wide state that the tunnel's teardown
 * would clear. One server at a time: the backend holds a single tunnel.
 */
class AwgProbeService : Service() {

    companion object {
        const val MSG_TEST = 1
        const val KEY_CONFIG = "config"
        const val KEY_TIMEOUT = "timeout"
        /** The delay in ms, or -1. */
        const val KEY_MS = "ms"
        const val KEY_COUNTRY = "cc"
        private const val TAG = "AwgProbe"
    }

    private val actions = object : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>) {}
        override fun runPostUp(scripts: MutableCollection<String>) {}
        override fun runPreDown(scripts: MutableCollection<String>) {}
        override fun runPostDown(scripts: MutableCollection<String>) {}
    }

    private val worker = HandlerThread("awg-probe").apply { start() }
    private val messenger = Messenger(Handler(worker.looper) { msg ->
        if (msg.what == MSG_TEST) {
            val out = Bundle()
            val cfg = msg.data?.getString(KEY_CONFIG)
            val timeout = msg.data?.getInt(KEY_TIMEOUT, 6_000) ?: 6_000
            val (ms, cc) = if (cfg == null) -1L to null else test(cfg, timeout)
            out.putLong(KEY_MS, ms)
            cc?.let { out.putString(KEY_COUNTRY, it) }
            runCatching { msg.replyTo?.send(Message.obtain(null, AwgHostService.MSG_REPLY).apply { data = out }) }
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun test(config: String, timeoutMs: Int): Pair<Long, String?> {
        val port = ServerSocket(0).use { it.localPort }
        val backend = ProxyGoBackend(applicationContext, actions)
        val tunnel = object : Tunnel {
            override fun getName() = "awgprobe"
            override fun onStateChange(state: Tunnel.State) {}
            override fun isIpv4ResolutionPreferred(): Boolean = true
        }
        return try {
            val parsed = Config.parse(ByteArrayInputStream(config.toByteArray()))
            val withProxy = Config.Builder()
                .setInterface(parsed.`interface`)
                .addPeers(parsed.peers)
                .setDnsSettings(parsed.dnsSettings)
                .addProxy(Socks5Proxy("127.0.0.1:$port", null, null))
                .build()
            backend.setState(tunnel, Tunnel.State.UP, withProxy)
            // The first request carries the handshake; the second is the delay a user feels.
            val first = get(port, "www.gstatic.com", "/generate_204", timeoutMs)
            if (first == null) return -1L to null
            val ms = get(port, "www.gstatic.com", "/generate_204", timeoutMs) ?: first
            val cc = trace(port, timeoutMs)
            ms to cc
        } catch (e: Exception) {
            Log.w(TAG, "test failed: ${e.javaClass.simpleName}")
            -1L to null
        } finally {
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        }
    }

    private fun open(port: Int, host: String, timeoutMs: Int): Socket {
        val s = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        s.soTimeout = timeoutMs
        s.connect(InetSocketAddress.createUnresolved(host, 80), timeoutMs)
        return s
    }

    private fun get(port: Int, host: String, path: String, timeoutMs: Int): Long? = runCatching {
        val start = System.nanoTime()
        open(port, host, timeoutMs).use { s ->
            s.getOutputStream().write("GET $path HTTP/1.0\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            val line = s.getInputStream().bufferedReader().readLine().orEmpty()
            if (line.contains(" 204") || line.contains(" 200")) (System.nanoTime() - start) / 1_000_000 else null
        }
    }.getOrNull()

    /** The exit's country from Cloudflare's trace (`loc=`), asked through the tunnel (resolved at the exit). */
    private fun trace(port: Int, timeoutMs: Int): String? = runCatching {
        open(port, "www.cloudflare.com", timeoutMs).use { s ->
            s.getOutputStream().write("GET /cdn-cgi/trace HTTP/1.0\r\nHost: www.cloudflare.com\r\nConnection: close\r\n\r\n".toByteArray())
            s.getInputStream().bufferedReader().readText().lineSequence()
                .firstOrNull { it.startsWith("loc=") }?.substringAfter('=')?.trim()?.uppercase()
                ?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } && it != "XX" && it != "T1" }
        }
    }.getOrNull()
}
