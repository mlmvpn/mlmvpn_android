package com.mlmvpn.scanner.engines.outline

import android.content.Context
import com.mlmvpn.scanner.engines.amnezia.AmzCarrier
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shadowsocks from inside Cloudflare WARP: the trick that brought Windscribe, Proton and Amnezia
 * back on lines that blackhole their servers, done for Outline.
 *
 * On the user's line most public Shadowsocks servers answer nothing: their addresses are dropped, or
 * the filter spots Shadowsocks' random-looking first packet. Dialled from inside WARP the line sees
 * only WireGuard to Cloudflare, and the server sees a connection from Cloudflare. ECH, PattNG and
 * the TLS fragments do not apply here -- Shadowsocks carries no TLS hello to hide or split -- so
 * WARP is the way round for it.
 *
 *  - a test runs in the `:xprobe` process ([OutlineProbe]), every server of a batch dialling through
 *    one WARP WireGuard outbound in that core (one key, one session);
 *  - a connect hands MyVpnService one Xray config whose Shadowsocks outbound dials through WARP's
 *    WireGuard in the same core, as the Amnezia engine's Cloudflare tunnel does.
 */
internal object OutlineCf {
    /** Read by MyVpnService through [AmzCarrier.isCarrierConfig]: an engine's own config, IPv4-only TUN. */
    const val REMARKS = "mlm-outline-cf"

    /** The server's Shadowsocks outbound as the app's generator builds it, tagged "proxy". */
    fun outbound(uri: String): JSONObject? = runCatching {
        val parsed = VpnConfig.parseUri(uri) ?: return null
        val json = JSONObject(XrayJsonGenerator.generateSpeedtestConfig(parsed))
        val outs = json.optJSONArray("outbounds") ?: return null
        (0 until outs.length()).mapNotNull { outs.optJSONObject(it) }.firstOrNull { it.optString("tag") == "proxy" }
    }.getOrNull()

    /**
     * The other way into WARP: a loopback SOCKS port of the app's own Aether engine
     * ([com.mlmvpn.core.warp.WarpLeg]) -- taken when the in-core carrier finds nothing on the line
     * (2026-10-17: "0 of 16 endpoints carry data", and every server that needed WARP was lost).
     */
    const val LEG_TAG = "warp-leg"

    fun socksLeg(port: Int): JSONObject = JSONObject().put("tag", LEG_TAG).put("protocol", "socks")
        .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", port))))

    fun dialThrough(outbound: JSONObject, tag: String): JSONObject {
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject().also { outbound.put("streamSettings", it) }
        val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
        sockopt.put("dialerProxy", tag)
        return outbound
    }

    /**
     * [uri] through WARP as a config for a test core (MLM Scout's yardstick): the Shadowsocks outbound
     * first, so it is the default, dialling through the carrier's WireGuard behind it.
     */
    fun probeConfig(uri: String, carrier: AmzCarrier.Carrier): String? {
        val proxy = outbound(uri)?.let { dialThrough(it, AmzCarrier.TAG) } ?: return null
        val outbounds = JSONArray().put(proxy)
        AmzCarrier.outbounds(carrier).forEach { outbounds.put(it) }
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(JSONObject().put("tag", "socks").put("listen", "127.0.0.1").put("port", 10808).put("protocol", "socks")))
            .put("outbounds", outbounds)
            .toString()
    }

    /**
     * The whole tunnel for MyVpnService: everything into the Shadowsocks server, whose connection
     * leaves through WARP's WireGuard in the same core. Same shape as [AmzCarrier.tunnelConfig]:
     * SOCKS/HTTP on the app's Local Port, the status probe beside it, DNS over DoH through the
     * tunnel, IPv4 only. Null when [uri] does not make an outbound.
     */
    fun tunnelConfig(uri: String, carrier: AmzCarrier.Carrier, localPort: Int): String? =
        tunnel(uri, AmzCarrier.TAG, AmzCarrier.outbounds(carrier), localPort)

    /** [tunnelConfig] through the Aether leg on [legPort] instead of the in-core carrier. */
    fun tunnelConfigVia(uri: String, legPort: Int, localPort: Int): String? =
        tunnel(uri, LEG_TAG, listOf(socksLeg(legPort)), localPort)

    private fun tunnel(uri: String, through: String, carrierOutbounds: List<JSONObject>, localPort: Int): String? {
        val proxy = outbound(uri)?.let { dialThrough(it, through) } ?: return null
        val dnsTag = "outline-dns"
        fun rule() = JSONObject().put("type", "field")
        val outbounds = JSONArray().put(proxy)
        carrierOutbounds.forEach { outbounds.put(it) }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        outbounds.put(JSONObject().put("tag", "blocked").put("protocol", "blackhole"))
        return JSONObject()
            .put("remarks", REMARKS)
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray()
                .put(JSONObject().put("tag", "socks").put("port", localPort).put("listen", "127.0.0.1").put("protocol", "mixed")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", true)))
                .put(JSONObject().put("tag", "http").put("port", localPort + com.mlmvpn.scanner.utils.LocalPort.PROBE_OFFSET)
                    .put("listen", "127.0.0.1").put("protocol", "http")))
            .put("outbounds", outbounds)
            .put("dns", JSONObject().put("tag", dnsTag)
                .put("servers", JSONArray()
                    .put(JSONObject().put("address", "https://8.8.8.8/dns-query"))
                    .put(JSONObject().put("address", "https://1.1.1.1/dns-query")))
                .put("queryStrategy", "UseIPv4"))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()
                .put(rule().put("port", "53").put("outboundTag", "dns-out"))
                .put(rule().put("inboundTag", JSONArray().put(dnsTag)).put("outboundTag", "proxy"))
                .put(rule().put("ip", JSONArray().put("10.10.34.0/24")).put("outboundTag", "blocked"))
                .put(rule().put("ip", JSONArray(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "100.64.0.0/10"))).put("outboundTag", "direct"))
                .put(rule().put("network", "tcp,udp").put("outboundTag", "proxy"))))
            .toString()
    }
}
