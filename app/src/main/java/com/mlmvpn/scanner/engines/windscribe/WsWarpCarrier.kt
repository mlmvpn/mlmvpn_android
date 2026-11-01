package com.mlmvpn.scanner.engines.windscribe

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.amnezia.AmzCarrier
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.openvpn.OpenVpnBypass
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket

/**
 * A local SOCKS port whose connections leave from inside Cloudflare WARP.
 *
 * The same WARP session that carries Windscribe's WireGuard ([AmzCarrier], found on this line by the
 * Amnezia engine) carries the OpenVPN family's one TCP connection here: an Xray core in this process,
 * SOCKS in, WARP's WireGuard out. Measured on the user's home line (2026-10-10), where every
 * Windscribe address is blackholed: WARP reaches them.
 *
 * The core's UDP to the WARP edge must not enter the OpenVPN tunnel it carries: its edge address is
 * put in [OpenVpnBypass], which the OpenVPN service leaves out of the tunnel's routes.
 */
internal class WsWarpCarrier private constructor(val port: Int, private val edge: String) : Closeable {
    @Volatile private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        // Off the caller's thread: the stop is a call to the carrier's process.
        Thread({ runCatching { host.call(appContext ?: return@Thread, com.mlmvpn.core.warp.XrayProbeService.MSG_STOP, null, 5_000) } }, "ws-carrier-stop").start()
        OpenVpnBypass.remove(edge)
        Log.i(TAG, "WARP carrier stopped")
    }

    /** A use of the shared carrier ([lease]): its SOCKS port while open. */
    class Lease internal constructor(val port: Int, private val done: () -> Unit) : Closeable {
        @Volatile private var closed = false
        override fun close() { if (!closed) { closed = true; done() } }
    }

    companion object {
        private const val TAG = "Windscribe"
        private const val IDLE_STOP_MS = 60_000L

        private val lock = kotlinx.coroutines.sync.Mutex()
        /** The carrier's own process (`:xcarrier`): an Xray panic there does not take the app down. */
        private val host by lazy { com.mlmvpn.core.warp.AwgHostClient(com.mlmvpn.core.warp.XrayCarrierService::class.java, "XrayCarrierClient") }
        @Volatile private var appContext: Context? = null
        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        private var shared: WsWarpCarrier? = null
        private var sharedNet: String? = null
        private var users = 0
        private var idleJob: kotlinx.coroutines.Job? = null

        /**
         * The one WARP carrier every TCP connection on this network shares: started once, kept while
         * in use and a minute after (a reconnect, the next test), checked with one request before it
         * is handed out again. A carrier started and stopped per connection let WARP see the same key
         * come and go every few seconds, and the next start sometimes carried nothing.
         */
        suspend fun lease(context: Context): Lease? = lock.withLock {
            val net = com.mlmvpn.scanner.engines.flux.FluxNet.current(context).key
            shared?.let { s ->
                if (sharedNet == net && !s.closed && probe(s.port, tries = 1) != null) {
                    users++; idleJob?.cancel()
                    return@withLock Lease(s.port) { release() }
                }
                s.close(); shared = null
            }
            // The first start on a network sometimes carries nothing yet (measured 2026-10-10): once more.
            val s = open(context) ?: open(context) ?: return@withLock null
            shared = s; sharedNet = net; users = 1; idleJob?.cancel()
            Lease(s.port) { release() }
        }

        private fun release() {
            scope.launch {
                lock.withLock {
                    users = (users - 1).coerceAtLeast(0)
                    if (users > 0) return@withLock
                    idleJob?.cancel()
                    idleJob = scope.launch {
                        kotlinx.coroutines.delay(IDLE_STOP_MS)
                        lock.withLock { if (users == 0) { shared?.close(); shared = null } }
                    }
                }
            }
        }

        /**
         * Before another WARP core of this app takes the same key (the Amnezia engine's tunnel): this
         * one's session would be taken from it, or take it back.
         */
        suspend fun closeShared() = lock.withLock {
            idleJob?.cancel()
            shared?.close(); shared = null; users = 0
        }

        /** Blocking; seconds the first time on a network. Null when WARP carries nothing here. */
        private suspend fun open(context: Context): WsWarpCarrier? {
            val app = context.applicationContext
            appContext = app
            val carrier = AmzEngine.warpCarrier(app) ?: run { Log.i(TAG, "no WARP carrier on this network"); return null }
            val port = ServerSocket(0).use { it.localPort }
            val edge = carrier.cfg.endpointHost
            OpenVpnBypass.add(edge)
            val started = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                host.call(app, com.mlmvpn.core.warp.XrayProbeService.MSG_START,
                    android.os.Bundle().apply { putString(com.mlmvpn.core.warp.XrayProbeService.KEY_CONFIG, config(carrier, port)) }, 20_000)
                    ?.getBoolean(com.mlmvpn.core.warp.XrayProbeService.KEY_OK) == true
            }
            if (!started) {
                OpenVpnBypass.remove(edge)
                Log.i(TAG, "WARP carrier core did not start"); return null
            }
            val ms = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { probe(port) }
            if (ms == null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { host.call(app, com.mlmvpn.core.warp.XrayProbeService.MSG_STOP, null, 5_000) }
                }
                OpenVpnBypass.remove(edge)
                Log.i(TAG, "WARP carrier ${carrier.label} carried nothing")
                return null
            }
            Log.i(TAG, "WARP carrier up via ${carrier.label} in $ms ms")
            return WsWarpCarrier(port, edge)
        }

        /** SOCKS in (TCP, and UDP for OpenVPN UDP and WireGuard through [WsUdpRelay]), WARP out. */
        private fun config(carrier: AmzCarrier.Carrier, socksPort: Int): String {
            val outbounds = JSONArray()
            AmzCarrier.outbounds(carrier).forEach { outbounds.put(it) }
            val inbounds = JSONArray().put(JSONObject().put("tag", "ws-in").put("listen", "127.0.0.1").put("port", socksPort)
                .put("protocol", "socks").put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1")))
            val tags = JSONArray().put("ws-in")
            return JSONObject()
                .put("remarks", "mlm-windscribe-warp")
                .put("log", JSONObject().put("loglevel", "warning"))
                .put("inbounds", inbounds)
                .put("outbounds", outbounds)
                .put("routing", JSONObject().put("rules", JSONArray().put(JSONObject().put("type", "field")
                    .put("inboundTag", tags).put("outboundTag", AmzCarrier.TAG))))
                .toString()
        }

        private fun probe(port: Int, tries: Int = 3): Long? {
            // The first WireGuard handshake to the edge may take a few seconds after the core starts.
            repeat(tries) {
                val got = runCatching {
                    val start = System.nanoTime()
                    Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { s ->
                        s.soTimeout = 10_000
                        s.connect(InetSocketAddress.createUnresolved("www.gstatic.com", 80), 10_000)
                        s.getOutputStream().write("GET /generate_204 HTTP/1.0\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
                        val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                        if (line.contains(" 204") || line.contains(" 200")) (System.nanoTime() - start) / 1_000_000 else null
                    }
                }.getOrNull()
                if (got != null) return got
            }
            return null
        }
    }
}
