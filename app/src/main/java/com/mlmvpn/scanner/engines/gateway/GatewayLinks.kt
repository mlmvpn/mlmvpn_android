package com.mlmvpn.scanner.engines.gateway

import com.mlmvpn.scanner.engines.cfnet.CfShape

/**
 * The configs that point at the central Worker — built here, with no request to the Worker. The
 * Android half of the Windows app's `edge-gateway/links.js`: the same shape, the same names, the
 * same query-parameter order, so a link made on either device reads the same on the other. Pure
 * (JDK + [CfShape] only).
 */
object GatewayLinks {

    /** A name the Worker can have: dot-separated labels ending in a TLD. */
    private val HOST_RE = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$")

    private val KIND_NAME = mapOf("domain" to "Domain", "ipv4" to "IPv4", "ipv6" to "IPv6", "clean" to "Clean")

    /** The developer's example edge: any address of 188.114.96.0/20 reaches the same one. */
    const val EDGE_V4 = "188.114.98.0"
    /** The developer's example IPv6 edge. */
    const val EDGE_V6 = "2a06:98c1:3121::7"

    /** JavaScript's `encodeURIComponent`: everything but A-Z a-z 0-9 - _ . ! ~ * ' ( ) is %XX (UTF-8, upper-case hex). */
    fun encodeURIComponent(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.!~*'()")) sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }

    /** The WHATWG `application/x-www-form-urlencoded` encoder `URLSearchParams.toString()` uses. */
    private fun formEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            when {
                c < 0x80 && (ch.isLetterOrDigit() || ch in "*-._") -> sb.append(ch)
                c == 0x20 -> sb.append('+')
                else -> sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
        return sb.toString()
    }

    /**
     * One vless:// link, the shape every CF-fronted VLESS-over-WebSocket link has.
     * [host] is the Worker's workers.dev name — SNI and Host header; [address] is where to dial (the
     * host itself unless a clean IP or an edge is chosen).
     */
    fun vlessLink(
        uuid: String, host: String, wsPath: String, name: String = "gateway", port: Int = 443,
        address: String? = null, early: Int = Gateway.EARLY_DATA, fp: String = "chrome",
    ): String {
        if (!Gateway.UUID_RE.matches(uuid)) throw GatewayException("شناسهٔ درگاه معتبر نیست.", "The gateway id is not valid.")
        if (!HOST_RE.matches(host)) throw GatewayException("نشانی ورکر معتبر نیست.", "The Worker address is not valid.")
        if (!Gateway.WS_PATH_RE.matches(wsPath)) throw GatewayException("مسیر تونل معتبر نیست.", "The tunnel path is not valid.")
        if (port < 1 || port > 65535) throw GatewayException("درگاه معتبر نیست.", "The port is not valid.")
        val dial = address?.takeIf { it.isNotEmpty() } ?: host
        val target = if (dial.contains(':') && !dial.startsWith("[")) "[$dial]" else dial
        val q = listOf(
            "encryption" to "none", "security" to "tls", "type" to "ws", "host" to host, "sni" to host, "fp" to fp,
            // WebSocket speaks only HTTP/1.1: an h2 ALPN here would make Cloudflare pick h2 and kill the dial.
            "alpn" to "http/1.1",
            "path" to if (early > 0) "$wsPath?ed=$early" else wsPath,
        ).joinToString("&") { (k, v) -> formEncode(k) + "=" + formEncode(v) }
        return "vless://$uuid@$target:$port?$q#${encodeURIComponent(name)}"
    }

    data class Addr(val address: String, val kind: String)

    /**
     * The links for one install, the way BPB's own subscription lists them: the Worker's name, then
     * (when asked) the addresses that name resolves to, then the clean IPs/domains — every address on
     * every chosen port. The host stays the SNI and the Host header on all of them, so an address
     * that is a resolved IP or a fronting domain still reaches this Worker.
     */
    fun buildConfigs(
        host: String, uuid: String, wsPath: String, ports: List<Int> = listOf(443),
        addresses: List<Addr>? = null, label: String = "BPB",
    ): List<String> {
        if (host.isBlank() || uuid.isBlank() || wsPath.isBlank()) throw GatewayException("درگاه روی این حساب نصب نیست.", "The gateway is not installed on this account.")
        val list = ports.distinct().filter { it in Gateway.TLS_PORTS }.ifEmpty { listOf(443) }
        val short = host.substringBefore('.')
        val addrs = addresses?.takeIf { it.isNotEmpty() } ?: listOf(Addr(host, "domain"))
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        for (a in addrs) {
            val same = a.address.equals(host, ignoreCase = true)
            for (port in list) {
                if (!seen.add(a.address + "|" + port)) continue
                val kind = KIND_NAME[a.kind] ?: KIND_NAME.getValue("domain")
                val tail = (if (same) "" else "-" + a.address) + (if (list.size > 1) "-$port" else "")
                out.add(vlessLink(uuid, host, wsPath, "$label-$short-${if (same) "Domain" else kind}$tail", port, if (same) null else a.address))
            }
        }
        return out
    }

    /** The same link under another name (the part after «#»). */
    fun renameLink(uri: String, name: String): String {
        val i = uri.indexOf('#')
        return (if (i < 0) uri else uri.substring(0, i)) + "#" + encodeURIComponent(name)
    }

    /** [uri] dialling [edge] instead (the link's own SNI and Host stay): `vless://id@edge:port?…`. */
    fun withAddress(uri: String, edge: String): String {
        val at = uri.indexOf('@')
        val q = uri.indexOf('?')
        if (at < 0 || q < at) return uri
        val port = uri.substring(at + 1, q).substringAfterLast(':')
        val target = if (edge.contains(':') && !edge.startsWith("[")) "[$edge]" else edge
        return uri.substring(0, at + 1) + "$target:$port" + uri.substring(q)
    }

    /**
     * [uri] with these query parameters set (appended at the end, in order) and these removed; every
     * other parameter keeps its place — the way the Windows app's cf-methods `withParams` does it.
     */
    fun withParams(uri: String, set: List<Pair<String, String>>, remove: Set<String>): String {
        val hash = uri.indexOf('#')
        val head = if (hash < 0) uri else uri.substring(0, hash)
        val tail = if (hash < 0) "" else uri.substring(hash)
        val q = head.indexOf('?')
        val base = if (q < 0) head else head.substring(0, q)
        val names = set.map { it.first }.toSet()
        val pairs = mutableListOf<String>()
        if (q >= 0) {
            for (kv in head.substring(q + 1).split('&')) {
                if (kv.isEmpty()) continue
                val k = kv.substringBefore('=')
                val name = runCatching { java.net.URLDecoder.decode(k, "UTF-8") }.getOrDefault(k)
                if (name.lowercase() in remove || name in names) continue
                pairs.add(kv)
            }
        }
        for ((k, v) in set) pairs.add(encodeURIComponent(k) + "=" + encodeURIComponent(v))
        return base + (if (pairs.isNotEmpty()) "?" + pairs.joinToString("&") else "") + tail
    }

    data class MethodLink(val method: String, val edge: String, val uri: String)

    /**
     * ECH, F&F and — when the user says this network has IPv6 — IPv6, each on the developer's example
     * edge: `fp`, `cs`, `fm`, `ech` and the right ALPN written into the link exactly as the Windows
     * app's cf-methods writes them (the same preset names; this app's parser knows them too), and
     * `mlm_method` so a link says which method it is. One edge per method keeps the list readable.
     *
     * @param base the plain `:443` link of the Worker's own name
     */
    fun methodVariants(base: String, ipv6: Boolean, label: String, short: String): List<MethodLink> {
        val kinds = listOf(Triple("ech", "ECH", EDGE_V4), Triple("ff", "FF", EDGE_V4)) + (if (ipv6) listOf(Triple("v6", "IPv6", EDGE_V6)) else emptyList())
        val drop = setOf("ech", "echcl", "echconfiglist", "fm", "cs")
        return kinds.map { (method, kind, edge) ->
            val set = mutableListOf("mlm_method" to method, "alpn" to "http/1.1")
            when (method) {
                "v6" -> set.add("fp" to "chrome")
                "ech" -> { set.add("fp" to "chrome"); set.add("ech" to CfShape.ECH_DEFAULT) }
                else -> { set.add("fp" to CfShape.FINGERPRINT_UNSAFE); set.add("cs" to CfShape.CS_PRESET); set.add("fm" to CfShape.FM_PRESET) }
            }
            val uri = renameLink(withParams(withAddress(base, edge), set, drop), "$label-$short-$kind-$edge")
            MethodLink(method, edge, uri)
        }
    }
}
