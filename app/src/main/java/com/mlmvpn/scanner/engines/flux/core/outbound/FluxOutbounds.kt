package com.mlmvpn.scanner.engines.flux.core.outbound

import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile
import com.mlmvpn.scanner.engines.flux.core.model.Proto
import com.mlmvpn.scanner.engines.flux.core.model.Security
import com.mlmvpn.scanner.engines.flux.core.model.Transport
import com.mlmvpn.scanner.engines.cfnet.CfShape
import org.json.JSONArray
import org.json.JSONObject

/**
 * A candidate as one Xray outbound. The same object goes into the probe core and into the tunnel,
 * so what was measured is exactly what carries the traffic.
 *
 * Rules carried over from the app's own config builder, where each was learned the hard way
 * ([com.mlmvpn.scanner.data.studio.config.ConfigBuilder]): REALITY has its own settings object, not
 * TLS's; `h2` is never offered on WebSocket (Cloudflare negotiates it and the dial dies); XHTTP
 * behind a CDN only works as `packet-up`.
 */
object FluxOutbounds {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Cloudflare's ECH key (base64), fetched on the real line by the engine before a race. Written
     * into [FragmentProfile.ECH] outbounds as is, so the core never has to ask a resolver for it
     * (UDP to 1.1.1.1 is dropped on some lines). Null: the core fetches it itself.
     */
    @Volatile var echKey: String? = null

    fun outbound(c: FluxCandidate, tag: String): JSONObject {
        val n = c.node
        val out = JSONObject().put("tag", tag)
        when (n.proto) {
            Proto.VLESS -> out.put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", c.dialAddress).put("port", n.port).put("users", JSONArray().put(
                    JSONObject().put("id", n.credential).put("encryption", "none").apply { if (n.flow.isNotEmpty()) put("flow", n.flow) }
                ))
            )))
            Proto.TROJAN -> out.put("protocol", "trojan").put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", c.dialAddress).put("port", n.port).put("password", n.credential)
            )))
            Proto.HY2 -> out.put("protocol", "hysteria").put("settings",
                JSONObject().put("version", 2).put("address", c.dialAddress).put("port", n.port))
        }
        out.put("streamSettings", stream(c))
        if (c.mux && muxable(c)) {
            out.put("mux", JSONObject().put("enabled", true).put("concurrency", 8)
                .put("xudpConcurrency", 16).put("xudpProxyUDP443", "reject"))
        }
        return out
    }

    /**
     * Mux only where it can work: not with XTLS Vision (which needs the raw connection), not on
     * XHTTP (which multiplexes itself) and not on Hysteria2 (QUIC streams already are).
     */
    fun muxable(c: FluxCandidate): Boolean =
        c.node.proto != Proto.HY2 && c.node.flow.isEmpty() && c.node.transport != Transport.XHTTP

    /** Fragmenting splits the TLS ClientHello; meaningless on QUIC. */
    fun fragmentable(c: FluxCandidate): Boolean = c.node.proto != Proto.HY2

    /**
     * The handshakes worth racing for [c] besides its plain one, most promising first. A
     * Cloudflare dial gets ECH (the name hidden) and PattNG's cut hello; any other TLS dial gets
     * the cut hellos only. REALITY borrows another site's certificate, so ECH cannot apply there.
     */
    fun shapes(c: FluxCandidate, cloudflare: Boolean): List<FragmentProfile> {
        if (!fragmentable(c)) return emptyList()
        return when {
            c.node.security == Security.TLS && cloudflare -> listOf(FragmentProfile.ECH, FragmentProfile.PATTNG, FragmentProfile.BALANCED)
            c.node.security == Security.TLS -> listOf(FragmentProfile.PATTNG, FragmentProfile.BALANCED)
            c.node.security == Security.REALITY -> listOf(FragmentProfile.BALANCED)
            else -> emptyList()
        }
    }

    private fun stream(c: FluxCandidate): JSONObject {
        val n = c.node
        val s = JSONObject()
        s.put("network", when (n.transport) {
            Transport.TCP -> "tcp"
            Transport.WS -> "ws"
            Transport.GRPC -> "grpc"
            Transport.XHTTP -> "xhttp"
            Transport.HTTPUPGRADE -> "httpupgrade"
            Transport.QUIC -> "hysteria"
        })
        when (n.transport) {
            Transport.WS -> s.put("wsSettings", JSONObject().put("path", n.path.ifEmpty { "/" }).apply {
                if (n.host.isNotEmpty()) put("host", n.host)
            }.put("headers", JSONObject().put("User-Agent", BROWSER_UA)))
            Transport.HTTPUPGRADE -> s.put("httpupgradeSettings", JSONObject().put("path", n.path.ifEmpty { "/" }).apply {
                if (n.host.isNotEmpty()) put("host", n.host)
            })
            Transport.XHTTP -> s.put("xhttpSettings", JSONObject().put("host", n.host).put("path", n.path.ifEmpty { "/" })
                .put("mode", n.xhttpMode.takeIf { it.isNotBlank() && it != "auto" } ?: "packet-up"))
            Transport.GRPC -> s.put("grpcSettings", JSONObject().put("serviceName", n.serviceName).put("multiMode", false))
            Transport.QUIC -> s.put("hysteriaSettings", JSONObject().put("version", 2).put("auth", n.credential))
            Transport.TCP -> Unit
        }
        when (n.security) {
            Security.REALITY -> s.put("security", "reality").put("realitySettings", JSONObject()
                .put("serverName", n.sni)
                .put("fingerprint", n.fingerprint.ifEmpty { "chrome" })
                .put("publicKey", n.publicKey)
                .put("shortId", n.shortId)
                .put("spiderX", n.spiderX.ifEmpty { "/" })
                .put("show", false))
            Security.TLS -> s.put("security", "tls").put("tlsSettings", JSONObject().apply {
                if (n.sni.isNotEmpty()) put("serverName", n.sni)
                // uTLS fingerprints are TCP-only; QUIC brings its own handshake.
                if (n.proto != Proto.HY2) put("fingerprint", n.fingerprint.ifEmpty { "chrome" })
                val alpn = when {
                    n.proto == Proto.HY2 -> listOf("h3")
                    n.transport == Transport.WS || n.transport == Transport.HTTPUPGRADE -> n.alpn.filter { it != "h2" && it != "h3" }.ifEmpty { listOf("http/1.1") }
                    else -> n.alpn.filter { it != "h3" }
                }
                if (alpn.isNotEmpty()) put("alpn", JSONArray().also { a -> alpn.forEach { a.put(it) } })
                if (n.cipherSuites.isNotEmpty()) put("cipherSuites", n.cipherSuites)
                // A self-signed server: exactly its own certificate, by hash (allowInsecure is gone).
                if (n.certPin.isNotEmpty()) put("pinnedPeerCertSha256", n.certPin)
            }.also { tls ->
                // The Doctor's two Cloudflare handshakes, exactly as it proves them.
                when (c.fragment) {
                    FragmentProfile.ECH -> CfShape.applyEch(tls, echKey ?: CfShape.ECH_DEFAULT)
                    FragmentProfile.PATTNG -> CfShape.applyFragFp(s, tls, s.optString("network"))
                    else -> Unit
                }
            })
            Security.NONE -> Unit
        }
        // Dial by the family this candidate was raced on. The address is a literal in every config
        // FLUX starts, so this never sends the server's own name to a resolver; it only stops
        // a both-families literal from being tried on the wrong family.
        s.put("sockopt", JSONObject().put("domainStrategy", if (c.family == Family.V4) "ForceIPv4" else "ForceIPv6"))
        finalmask(c)?.let { s.put("finalmask", it) }
        return s
    }

    private fun finalmask(c: FluxCandidate): JSONObject? {
        val n = c.node
        if (n.proto == Proto.HY2) {
            if (n.obfs != "salamander") return null
            return JSONObject().put("udp", JSONArray().put(JSONObject().put("type", "salamander")
                .put("settings", JSONObject().put("password", n.obfsPassword))))
        }
        val tcp = when (c.fragment) {
            // ECH sends one whole hello; PattNG's cut is written with its TLS settings above.
            FragmentProfile.OFF, FragmentProfile.ECH, FragmentProfile.PATTNG -> return null
            // The publisher's own tested fragment is this node's balanced profile when it has one.
            FragmentProfile.BALANCED -> n.finalmask?.let { runCatching { JSONObject(it).optJSONArray("tcp") }.getOrNull() } ?: balanced()
            FragmentProfile.CONSERVATIVE -> JSONArray().put(fragment("tlshello", listOf("0", "104", "1"), listOf("0"), "0"))
            FragmentProfile.AGGRESSIVE -> JSONArray()
                .put(fragment("tlshello", listOf("6", "98", "1"), listOf("1"), "0"))
                .put(fragment("1-1", listOf("114", "1"), listOf("2"), "11"))
        }
        return JSONObject().put("tcp", tcp)
    }

    /** The split Serverless and MAE's fragment route already use: known to pass on MCI and Irancell. */
    private fun balanced() = JSONArray()
        .put(fragment("tlshello", listOf("0", "104", "1"), listOf("0"), "0"))
        .put(fragment("1-1", listOf("114", "1"), listOf("1"), "11"))

    private fun fragment(packets: String, lengths: List<String>, delays: List<String>, maxSplit: String) =
        JSONObject().put("type", "fragment").put("settings", JSONObject()
            .put("packets", packets)
            .put("lengths", JSONArray().also { a -> lengths.forEach { a.put(it) } })
            .put("delays", JSONArray().also { a -> delays.forEach { a.put(it) } })
            .put("maxSplit", maxSplit))
}
