package com.mlmvpn.scanner.engines.windscribe

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.amnezia.AmzCarrier
import com.mlmvpn.scanner.engines.amnezia.WgConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket

/**
 * A real WireGuard session to one Windscribe node without the device VPN, for «تست همه».
 *
 * An Xray core in the test host process (XrayHosts: a WireGuard core that panics Xray ends that
 * process, not the app) holds the node's WireGuard session -- the same keys and the same Xray
 * WireGuard the connect uses -- and, where the line blackholes the node, sends its packets on from
 * inside Cloudflare WARP through the shared carrier's UDP ([WsUdpRelay]), the one WARP session
 * every Windscribe connection rides. The test's requests and download go through its HTTP inbound. Nothing
 * is estimated: no handshake, no request and no byte means no result. Bringing the device VPN up
 * and down per node took 25-40 s; this takes a few.
 */
internal class WsWgProbe private constructor(
    private val core: com.mlmvpn.core.warp.XrayHosts.RemoteCore,
    private val app: Context,
    val httpPort: Int,
) : Closeable {
    /** A round trip to the host: off the main thread, like [start]. */
    override fun close() { runCatching { core.stop(app) } }

    /** Requests through this node's WireGuard (the port answers HTTP and SOCKS5). */
    fun proxy(): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))

    companion object {
        private const val TAG = "Windscribe"

        /**
         * [udpPort]: a [WsUdpRelay] into the shared WARP carrier (the line blackholes the node); null: straight to the node.
         * Blocks while the host starts the core: call it off the main thread.
         */
        fun start(context: Context, conf: String, udpPort: Int?): WsWgProbe? {
            val server = WgConfig.parseConf(conf) ?: return null
            val port = ServerSocket(0).use { it.localPort }
            val outbounds = JSONArray()
            val inbounds = JSONArray().put(JSONObject().put("tag", "probe-in").put("listen", "127.0.0.1").put("port", port)
                .put("protocol", "mixed").put("settings", JSONObject().put("auth", "noauth").put("udp", false)))
            val rules = JSONArray()
            if (udpPort != null) {
                outbounds.put(AmzCarrier.inner(server, udpPort).xrayOutbound("proxy", null))
            } else {
                outbounds.put(server.xrayOutbound("proxy", null))
            }
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("probe-in")).put("outboundTag", "proxy"))
            val json = JSONObject().put("remarks", "mlm-windscribe-probe")
                .put("log", JSONObject().put("loglevel", "warning"))
                .put("inbounds", inbounds).put("outbounds", outbounds)
                .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
                .toString()
            val app = context.applicationContext
            val core = com.mlmvpn.core.warp.XrayHosts.RemoteCore(com.mlmvpn.core.warp.XrayHosts.TEST, 0, null, log = false)
            return try {
                if (core.start(app, com.mlmvpn.core.warp.NoiseDoor.rewrite(json))) WsWgProbe(core, app, port)
                else { Log.i(TAG, "probe core did not start"); null }
            } catch (e: Throwable) {
                Log.i(TAG, "probe core did not start (${e.javaClass.simpleName})"); null
            }
        }
    }
}
