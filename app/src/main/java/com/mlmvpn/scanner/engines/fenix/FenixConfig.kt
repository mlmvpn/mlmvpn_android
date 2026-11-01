package com.mlmvpn.scanner.engines.fenix

import org.json.JSONArray
import org.json.JSONObject

/**
 * FENIX's Xray configs.
 *
 * The data plane is Xray's own HTTP proxy client: TLS to the Fastly server, HTTP/2 negotiated by
 * ALPN, and every connection a CONNECT stream on ONE shared HTTP/2 session (Xray keeps it per
 * server) -- one handshake for the whole phone instead of one per connection, which is what makes
 * new connections fast. The proxy pass rides on each CONNECT as `Proxy-Authorization: Bearer`.
 * Checked from an Iranian line on 2026-10-03 with Xray 26.7.28: the server answers over HTTP/2 and
 * refuses a made-up pass, as it should.
 *
 * Leak rules, each deliberate (the same as FLUX's):
 *  - every lookup is answered by FakeDNS inside the tunnel, and the real resolver behind it is a
 *    DoH server reached THROUGH the tunnel: no plaintext DNS leaves the phone;
 *  - the server is dialled by literal address, so the tunnel never needs a resolver to come up;
 *  - nothing is routed direct except LAN ranges;
 *  - the proxy carries TCP only: QUIC is refused at once so apps move to TCP, and any other UDP is
 *    dropped rather than sent outside;
 *  - no IPv6 anywhere in the tunnel (no AAAA, and MyVpnService gives the TUN no IPv6 address, so
 *    Android blocks the family instead of letting it leave around the tunnel).
 */
internal object FenixConfig {

    /** Read by MyVpnService: FENIX owns its routing (no Google fix) and its TUN is IPv4-only. */
    const val REMARKS = "mlm-fenix-v4"
    const val TAG_PREFIX = "fenix-"
    private const val BALANCER = "fenix"
    private const val DNS_TAG = "fenix-dns"

    fun isFenix(remarks: String?) = remarks == REMARKS

    private val LAN = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "100.64.0.0/10",
        "fe80::/10", "fd00::/8", "::1/128",
    )
    /** Where Iran's filter answers a hijacked connection (the block page): fail at once. */
    private val BLOCK_PAGE = listOf("10.10.34.0/24", "2001:4188:2:600::/64")

    /**
     * One FENIX outbound.
     *
     * @param address what is dialled: a literal address of [server] (preferred) or its name.
     * @param http1 HTTP/1.1 instead of HTTP/2: a new TLS connection per stream. Only the fallback for
     *   a network that breaks HTTP/2; slower to open connections, same speed once open.
     */
    fun outbound(tag: String, server: FenixServers.Server, address: String, token: String, http1: Boolean = false): JSONObject =
        JSONObject()
            .put("tag", tag)
            .put("protocol", "http")
            .put("settings", JSONObject()
                .put("servers", JSONArray().put(JSONObject().put("address", address).put("port", server.port)))
                .put("headers", JSONObject().put("Proxy-Authorization", "Bearer $token")))
            .put("streamSettings", JSONObject()
                .put("network", "tcp")
                .put("security", "tls")
                .put("tlsSettings", JSONObject()
                    // The name is verified even when a literal address is dialled.
                    .put("serverName", server.host)
                    .put("alpn", JSONArray().put(if (http1) "http/1.1" else "h2"))
                    .put("fingerprint", "chrome")
                    // Older Androids lack the roots these servers chain to (FenixRoots).
                    .put("certificates", JSONArray(FenixRoots.xrayCertificates().map { pem ->
                        JSONObject().put("usage", "verify").put("certificate", JSONArray(pem))
                    })))
                .put("sockopt", JSONObject()
                    // A dead path is noticed by the observatory within a minute; keepalives keep a
                    // carrier's NAT from dropping the one long-lived connection in between.
                    .put("tcpKeepAliveIdle", 30)
                    .put("tcpKeepAliveInterval", 15)))

    /**
     * The tunnel. [paths] are the same server reached two ways (its IPv4 and IPv6 address, or two
     * IPv4 addresses): the first is used whenever it works, and Xray's observatory moves new
     * connections to the second within a minute of the first failing -- no reconnect, no restart.
     */
    fun tunnel(server: FenixServers.Server, paths: List<String>, token: String, localPort: Int, quicSink: JSONObject?, http1: Boolean, mobile: Boolean): String {
        require(paths.isNotEmpty()) { "no address" }
        val used = paths.distinct().take(2)
        val balanced = used.size > 1
        fun toRoutes(r: JSONObject) = if (balanced) r.put("balancerTag", BALANCER) else r.put("outboundTag", "${TAG_PREFIX}0")

        val json = JSONObject()
        json.put("remarks", REMARKS)
        json.put("log", JSONObject().put("loglevel", "warning"))
        json.put("inbounds", JSONArray()
            .put(JSONObject().put("tag", "socks").put("port", localPort).put("listen", "127.0.0.1").put("protocol", "mixed")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
                .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic").put("fakedns"))))
            // localPort+10000: the app-wide convention for a check through the tunnel.
            .put(JSONObject().put("tag", "http").put("port", localPort + 10000).put("listen", "127.0.0.1").put("protocol", "http")))

        val outbounds = JSONArray()
        used.forEachIndexed { i, a -> outbounds.put(outbound("$TAG_PREFIX$i", server, a, token, http1)) }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        outbounds.put(JSONObject().put("tag", "blocked").put("protocol", "blackhole"))
        val quicTag = if (quicSink != null) { outbounds.put(quicSink); quicSink.optString("tag") } else "blocked"
        json.put("outbounds", outbounds)

        json.put("dns", JSONObject()
            .put("tag", DNS_TAG)
            .put("servers", JSONArray()
                .put("fakedns")
                .put(JSONObject().put("address", "https://1.1.1.1/dns-query"))
                .put(JSONObject().put("address", "https://8.8.8.8/dns-query")))
            .put("queryStrategy", "UseIPv4"))
        json.put("fakedns", JSONArray().put(JSONObject().put("ipPool", "198.18.0.0/15").put("poolSize", 65535)))

        val rules = JSONArray()
        rules.put(rule().put("port", "53").put("outboundTag", "dns-out"))
        rules.put(toRoutes(rule().put("inboundTag", JSONArray().put(DNS_TAG))))
        rules.put(rule().put("ip", JSONArray(BLOCK_PAGE)).put("outboundTag", "blocked"))
        rules.put(rule().put("ip", JSONArray(LAN)).put("outboundTag", "direct"))
        // The proxy carries TCP only. QUIC gets an immediate refusal (apps retry over TCP at once);
        // every other UDP flow is dropped inside the tunnel -- never sent around it.
        rules.put(rule().put("network", "udp").put("protocol", JSONArray().put("quic")).put("outboundTag", quicTag))
        rules.put(rule().put("network", "udp").put("port", "443").put("outboundTag", quicTag))
        rules.put(rule().put("network", "udp").put("outboundTag", "blocked"))
        rules.put(toRoutes(rule().put("network", "tcp")))

        if (!balanced) {
            json.put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            return json.toString()
        }
        json.put("routing", JSONObject()
            .put("domainStrategy", "AsIs")
            .put("rules", rules)
            .put("balancers", JSONArray().put(JSONObject()
                .put("tag", BALANCER)
                .put("selector", JSONArray(used.indices.map { "$TAG_PREFIX$it" }))
                .put("fallbackTag", "${TAG_PREFIX}0")
                .put("strategy", JSONObject().put("type", "leastLoad").put("settings", JSONObject()
                    .put("expected", 1)
                    .put("maxRTT", "3s")
                    .put("tolerance", 0.5)
                    .put("baselines", JSONArray().put("1s"))
                    // The first path is preferred whenever it is alive.
                    .put("costs", JSONArray(used.indices.map { i ->
                        JSONObject().put("regexp", false).put("match", "$TAG_PREFIX$i").put("value", 1.0 + i * 9.0)
                    })))))))
        json.put("burstObservatory", JSONObject()
            .put("subjectSelector", JSONArray(used.indices.map { "$TAG_PREFIX$it" }))
            .put("pingConfig", JSONObject()
                .put("destination", "https://www.gstatic.com/generate_204")
                .put("interval", if (mobile) "2m" else "1m")
                .put("sampling", 2)
                .put("timeout", "5s")))
        return json.toString()
    }

    private fun rule() = JSONObject().put("type", "field")
}
