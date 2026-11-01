package com.mlmvpn.scanner.data.studio.central

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * A person's subscription, written once on the phone in the three shapes apps read, with
 * [PLACEHOLDER] where their device's id goes -- the sub Worker (assets/studio_sub) puts it in.
 *
 *  - `x`: a JSON array of whole Xray configs (v2rayNG, v2rayN, Happ, v2RayTun, Streisand …);
 *  - `c`: a Clash (mihomo) profile (Hiddify, FlClash, Clash Verge …);
 *  - `l`: plain share links, for an app that reads nothing else.
 *
 * A location («ساب چندلوکیشنی») is the person's own way through the central Worker and then a
 * public proxy in that country: the proxy is dialled THROUGH the Worker (Xray `dialerProxy`, mihomo
 * `dialer-proxy`), so the Worker -- which only relays to an address -- is not changed in any way,
 * and the sites see the proxy's country. With several proxies the app keeps to one that answers
 * (Xray's observatory and balancer, mihomo's fallback group). A share link cannot say "through
 * that, then this", so locations are not in `l`.
 *
 * Pure (org.json and the JDK only), so every shape is checked by plain JVM tests and, in
 * scripts/, by the Xray core itself.
 */
object CentralSubRender {

    const val PLACEHOLDER = "{{ID}}"

    /** An id the link builder accepts, written in place of [PLACEHOLDER] and swapped back after. */
    const val DUMMY_ID = "00000000-0000-4000-8000-00000000c0de"

    const val PROBE_URL = "https://www.gstatic.com/generate_204"

    /** One of the central Worker's links, as GatewayLinks writes them (vless, WebSocket, TLS). */
    data class Link(
        val address: String, val port: Int, val host: String, val sni: String, val path: String,
        val fp: String, val alpn: List<String>, val ech: String?, val method: String?, val name: String,
    ) {
        /** Written in a form other apps read: not the F&F method, whose presets are this app's own words. */
        val portable: Boolean get() = method == null || method == "ech" || method == "v6"
    }

    data class Templates(val x: String, val c: String, val l: String)

    /** `vless://id@address:port?query#name` → its parts; null for anything that is not that shape. */
    fun parse(link: String): Link? = runCatching {
        if (!link.startsWith("vless://")) return null
        val hash = link.indexOf('#')
        val name = if (hash < 0) "" else URLDecoder.decode(link.substring(hash + 1), "UTF-8")
        val body = link.substring("vless://".length, if (hash < 0) link.length else hash)
        val at = body.indexOf('@')
        val q = body.indexOf('?')
        if (at < 0 || q < at) return null
        val hostPort = body.substring(at + 1, q)
        val (address, port) = if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            hostPort.substring(1, end) to hostPort.substring(end + 2).toInt()
        } else {
            hostPort.substringBeforeLast(':') to hostPort.substringAfterLast(':').toInt()
        }
        val params = body.substring(q + 1).split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        if (params["type"] != "ws" || params["security"] != "tls") return null
        val host = params["host"].orEmpty()
        Link(
            address = address, port = port, host = host, sni = params["sni"]?.ifBlank { null } ?: host,
            path = params["path"] ?: "/", fp = params["fp"] ?: "chrome",
            alpn = params["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            ech = params["ech"]?.ifBlank { null }, method = params["mlm_method"]?.ifBlank { null }, name = name,
        )
    }.getOrNull()

    /** The flag of a two-letter country, from its regional indicator letters. */
    fun flag(cc: String): String = if (cc.length != 2) "" else cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    // ------------------------------------------------------------------ Xray

    private fun workerOutbound(l: Link, tag: String): JSONObject {
        val tls = JSONObject().put("serverName", l.sni).put("fingerprint", l.fp).put("allowInsecure", false)
        if (l.alpn.isNotEmpty()) tls.put("alpn", JSONArray(l.alpn))
        l.ech?.let { tls.put("echConfigList", it) }
        return JSONObject().put("tag", tag).put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", l.address).put("port", l.port)
                .put("users", JSONArray().put(JSONObject().put("id", PLACEHOLDER).put("encryption", "none"))))))
            .put("streamSettings", JSONObject().put("network", "ws").put("security", "tls")
                .put("tlsSettings", tls)
                .put("wsSettings", JSONObject().put("path", l.path).put("host", l.host)))
    }

    private fun proxyOutbound(p: ExitProxy, tag: String, via: String): JSONObject =
        JSONObject().put("tag", tag).put("protocol", if (p.kind == ExitProxy.HTTP) "http" else "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", p.ip).put("port", p.port))))
            .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", via)))

    private fun xrayShell(remarks: String, outbounds: JSONArray, routing: JSONObject, observatory: JSONObject? = null): JSONObject {
        val o = JSONObject().put("remarks", remarks)
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
            .put("inbounds", JSONArray()
                .put(JSONObject().put("tag", "socks").put("listen", "127.0.0.1").put("port", 10808).put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
                    .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls")).put("routeOnly", true)))
                .put(JSONObject().put("tag", "http").put("listen", "127.0.0.1").put("port", 10809).put("protocol", "http")))
            .put("outbounds", outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
            .put("routing", routing)
        observatory?.let { o.put("observatory", it) }
        return o
    }

    /** One link, as a whole config of its own. */
    fun xrayDirect(l: Link): JSONObject =
        xrayShell(l.name, JSONArray().put(workerOutbound(l, "proxy")), JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))

    /**
     * One location: [via] carries everything; TCP leaves through the proxy (or, with several, the one
     * the observatory last found answering). DNS goes over the Worker, which answers it as DNS over
     * HTTPS -- public proxies carry no UDP -- and any other UDP is refused, so apps fall back to TCP.
     */
    fun xrayLocation(loc: CentralLocation, via: Link, remarks: String): JSONObject {
        val outs = JSONArray()
        val px = loc.proxies.mapIndexed { i, p -> proxyOutbound(p, "px-$i", "cw") }
        px.forEach { outs.put(it) }
        outs.put(workerOutbound(via, "cw"))
        val rules = JSONArray()
            .put(JSONObject().put("type", "field").put("network", "udp").put("port", "53").put("outboundTag", "cw"))
            .put(JSONObject().put("type", "field").put("network", "udp").put("outboundTag", "block"))
        val routing = JSONObject().put("domainStrategy", "AsIs")
        var observatory: JSONObject? = null
        if (px.size > 1) {
            routing.put("balancers", JSONArray().put(JSONObject().put("tag", "loc").put("selector", JSONArray().put("px-"))
                .put("strategy", JSONObject().put("type", "leastPing")).put("fallbackTag", "px-0")))
            rules.put(JSONObject().put("type", "field").put("network", "tcp").put("balancerTag", "loc"))
            observatory = JSONObject().put("subjectSelector", JSONArray().put("px-")).put("probeUrl", PROBE_URL)
                .put("probeInterval", "1m").put("enableConcurrency", true)
        } else {
            rules.put(JSONObject().put("type", "field").put("network", "tcp").put("outboundTag", "px-0"))
        }
        routing.put("rules", rules)
        return xrayShell(remarks, outs, routing, observatory)
    }

    // ------------------------------------------------------------------ Clash (mihomo)

    private fun yq(s: String): String = JSONObject.quote(s)

    private fun clashWorker(l: Link, name: String): String = buildString {
        append("  - name: ").append(yq(name)).append('\n')
        append("    type: vless\n")
        append("    server: ").append(yq(l.address)).append('\n')
        append("    port: ").append(l.port).append('\n')
        append("    uuid: ").append(PLACEHOLDER).append('\n')
        append("    network: ws\n    tls: true\n    udp: true\n")
        append("    servername: ").append(yq(l.sni)).append('\n')
        append("    client-fingerprint: ").append(yq(l.fp)).append('\n')
        if (l.alpn.isNotEmpty()) append("    alpn: [").append(l.alpn.joinToString(", ") { yq(it) }).append("]\n")
        append("    ws-opts:\n      path: ").append(yq(l.path)).append("\n      headers:\n        Host: ").append(yq(l.host)).append('\n')
    }

    private fun clashProxy(p: ExitProxy, name: String, via: String): String =
        "  - {name: ${yq(name)}, type: ${if (p.kind == ExitProxy.HTTP) "http" else "socks5"}, server: ${yq(p.ip)}, port: ${p.port}, dialer-proxy: ${yq(via)}}\n"

    // ------------------------------------------------------------------ all three

    /**
     * [links]: the person's own links, written for [DUMMY_ID] (EdgeGateway.studioLinks); the first
     * plain one is the way every location goes through. [countryName]: a country's name in the
     * operator's language.
     */
    fun render(links: List<String>, locations: List<CentralLocation>, countryName: (String) -> String, fixedLabel: String = "IP ثابت"): Templates {
        val parsed = links.mapNotNull { parse(it) }.map { it.copy(name = it.name.ifBlank { it.address }) }
        val portable = parsed.filter { it.portable }
        val via = portable.firstOrNull { it.method == null } ?: portable.firstOrNull()
        fun locName(l: CentralLocation) = (flag(l.cc) + " " + countryName(l.cc)).trim() + if (l.fixed) " · $fixedLabel" else ""

        // x: the locations first -- they are why the subscription exists -- then the plain ways in.
        val x = JSONArray()
        if (via != null) for (l in locations) x.put(xrayLocation(l, via, locName(l)))
        for (l in portable) x.put(xrayDirect(l))

        // c: the Worker's ways in, a proxy chain per location, one fallback group each, one picker.
        val c = StringBuilder("mixed-port: 7890\nallow-lan: false\nmode: rule\nlog-level: warning\nipv6: true\nproxies:\n")
        val plain = portable.filter { it.ech == null }.distinctBy { it.name }
        for (l in plain) c.append(clashWorker(l, l.name))
        val groups = StringBuilder()
        val picks = mutableListOf<String>()
        if (via != null && plain.isNotEmpty()) {
            val viaName = plain.first().name
            for (loc in locations) {
                val name = locName(loc)
                val members = loc.proxies.mapIndexed { i, p ->
                    val n = "$name ${i + 1}"
                    c.append(clashProxy(p, n, viaName))
                    n
                }
                groups.append("  - name: ").append(yq(name)).append('\n')
                groups.append("    type: fallback\n    url: ").append(yq(PROBE_URL)).append("\n    interval: 120\n")
                groups.append("    proxies: [").append(members.joinToString(", ") { yq(it) }).append("]\n")
                picks += name
            }
        }
        picks += plain.map { it.name }
        c.append("proxy-groups:\n").append(groups)
        c.append("  - name: \"PROXY\"\n    type: select\n    proxies: [").append(picks.joinToString(", ") { yq(it) }).append("]\n")
        c.append("rules:\n  - MATCH,PROXY\n")

        val l = links.filter { link -> parse(link)?.let { it.method == null || it.method == "v6" || it.method == "ech" } ?: true }
            .joinToString("\n")
        return Templates(
            x = x.toString().replace(DUMMY_ID, PLACEHOLDER),
            c = c.toString().replace(DUMMY_ID, PLACEHOLDER),
            l = l.replace(DUMMY_ID, PLACEHOLDER),
        )
    }
}
