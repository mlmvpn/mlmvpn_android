package com.mlmvpn.core.tunnel.bridge

import java.security.MessageDigest

/**
 * The transports a bridge can use, as far as THIS app's Tor can carry them.
 *
 * The app's pluggable-transport binary is lyrebird 0.8.1 (libobfs4proxy.so): obfs4, meek_lite,
 * snowflake and webtunnel, nothing else -- checked in the shipped binary on 2026-10-03. Vanilla
 * needs no transport at all. Conjure is NOT here on purpose: lyrebird has no conjure client, so a
 * conjure bridge would be a line Tor cannot use, and offering it would be offering a failure.
 */
enum class BridgeTransport(
    /** The name on a bridge line and in `ClientTransportPlugin`; null for vanilla. */
    val wire: String?,
    /** For the UI. */
    val label: String,
) {
    OBFS4("obfs4", "obfs4"),
    WEBTUNNEL("webtunnel", "WebTunnel"),
    SNOWFLAKE("snowflake", "Snowflake"),
    MEEK("meek_lite", "Meek"),
    VANILLA(null, "Vanilla");

    companion object {
        fun fromWire(name: String): BridgeTransport? = when (name.lowercase()) {
            "obfs4" -> OBFS4
            "webtunnel" -> WEBTUNNEL
            "snowflake" -> SNOWFLAKE
            // Tor Browser's lines say meek_lite; older guides say meek.
            "meek_lite", "meek" -> MEEK
            else -> null
        }
    }
}

enum class IpFamily { V4, V6, NAME }

/** Where a bridge came from. Official (Tor Project) is kept apart from the community. */
data class BridgeOrigin(val sourceId: String, val official: Boolean)

/**
 * One bridge, parsed. [line] is the canonical bridge line Tor is given (`Bridge <line>`); [id] is
 * stable for the same bridge however a source happened to write it.
 */
data class TorBridge(
    val id: String,
    val line: String,
    val transport: BridgeTransport,
    /** As written on the line. For webtunnel it is usually a placeholder (2001:db8::/32). */
    val host: String,
    val port: Int,
    val family: IpFamily,
    val fingerprint: String?,
    /** The transport's own arguments (cert=, url=, front=, ice=, ...). */
    val params: Map<String, String>,
    /** The host actually dialled: the address, or the URL's host for webtunnel/meek/snowflake. */
    val dialHost: String,
    val dialPort: Int,
) {
    /** Placeholder addresses (192.0.2.x, 2001:db8::) that Tor never dials: the URL is what counts. */
    val addressIsPlaceholder: Boolean
        get() = host.startsWith("192.0.2.") || host.lowercase().startsWith("2001:db8:")
}

/** Why a pasted line was refused, in words the screen can show. */
enum class BridgeLineError {
    EMPTY, UNSUPPORTED_TRANSPORT, CONJURE, BAD_ADDRESS, BAD_PORT, BAD_FINGERPRINT,
    MISSING_CERT, MISSING_URL, BAD_URL,
}

sealed class BridgeParse {
    data class Ok(val bridge: TorBridge) : BridgeParse()
    data class Bad(val error: BridgeLineError, val input: String) : BridgeParse()
}

/**
 * Reads bridge lines in every form they are handed around in: with or without the `Bridge `
 * prefix, vanilla (`ip:port fingerprint`), each transport's own arguments, IPv6 in brackets, and
 * the odd stray whitespace or comment of a pasted list. Nothing is trusted: an address must be an
 * address, a port a port, a fingerprint 40 hex digits, and each transport must carry what it needs.
 */
object BridgeLines {

    private val FP = Regex("^[0-9A-Fa-f]{40}$")
    private val V4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /** Every line of a pasted text, parsed on its own: one bad line never sinks the rest. */
    fun parseAll(text: String): List<BridgeParse> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .map { parse(it) }
            .toList()

    fun parse(raw: String): BridgeParse {
        var s = raw.trim().replace('\t', ' ')
        if (s.startsWith("Bridge ", ignoreCase = true)) s = s.substring(7).trim()
        val tokens = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return BridgeParse.Bad(BridgeLineError.EMPTY, raw)

        // Vanilla starts with the address; everything else with a transport name.
        val first = tokens[0]
        val looksLikeAddress = first.startsWith("[") || first.first().isDigit()
        val transport = if (looksLikeAddress) BridgeTransport.VANILLA else {
            if (first.equals("conjure", ignoreCase = true)) return BridgeParse.Bad(BridgeLineError.CONJURE, raw)
            BridgeTransport.fromWire(first) ?: return BridgeParse.Bad(BridgeLineError.UNSUPPORTED_TRANSPORT, raw)
        }
        val rest = if (transport == BridgeTransport.VANILLA) tokens else tokens.drop(1)
        val addr = rest.firstOrNull() ?: return BridgeParse.Bad(BridgeLineError.BAD_ADDRESS, raw)
        val (host, port, family) = splitAddress(addr) ?: return BridgeParse.Bad(BridgeLineError.BAD_ADDRESS, raw)
        if (port !in 1..65535) return BridgeParse.Bad(BridgeLineError.BAD_PORT, raw)

        var fingerprint: String? = null
        val params = LinkedHashMap<String, String>()
        for (t in rest.drop(1)) {
            val eq = t.indexOf('=')
            if (eq > 0) {
                params[t.substring(0, eq).lowercase()] = t.substring(eq + 1)
            } else if (fingerprint == null && FP.matches(t)) {
                fingerprint = t.uppercase()
            } else {
                return BridgeParse.Bad(BridgeLineError.BAD_FINGERPRINT, raw)
            }
        }
        // Some lines repeat the fingerprint as an argument (snowflake does).
        params["fingerprint"]?.let { if (fingerprint == null && FP.matches(it)) fingerprint = it.uppercase() }

        var dialHost = host
        var dialPort = port
        when (transport) {
            BridgeTransport.OBFS4 -> {
                if (fingerprint == null) return BridgeParse.Bad(BridgeLineError.BAD_FINGERPRINT, raw)
                val cert = params["cert"]
                if (cert.isNullOrBlank() || cert.length < 60) return BridgeParse.Bad(BridgeLineError.MISSING_CERT, raw)
                if ("iat-mode" !in params) params["iat-mode"] = "0"
            }
            BridgeTransport.VANILLA -> {
                // Tor accepts a vanilla bridge without a fingerprint, but then cannot tell an
                // impostor from the bridge: refused here.
                if (fingerprint == null) return BridgeParse.Bad(BridgeLineError.BAD_FINGERPRINT, raw)
            }
            BridgeTransport.WEBTUNNEL, BridgeTransport.MEEK, BridgeTransport.SNOWFLAKE -> {
                val url = params["url"]
                if (url.isNullOrBlank()) return BridgeParse.Bad(BridgeLineError.MISSING_URL, raw)
                val u = runCatching { java.net.URI(url) }.getOrNull()
                if (u == null || u.scheme?.lowercase() != "https" || u.host.isNullOrBlank()) {
                    return BridgeParse.Bad(BridgeLineError.BAD_URL, raw)
                }
                // What the phone really dials: the URL's host for webtunnel; the front for the two
                // domain-fronted ones (the first of a comma list), which is what a censor sees.
                dialHost = when (transport) {
                    BridgeTransport.WEBTUNNEL -> u.host
                    else -> params["front"]?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: u.host
                }
                dialPort = if (u.port > 0) u.port else 443
                if (transport == BridgeTransport.WEBTUNNEL && fingerprint == null) {
                    return BridgeParse.Bad(BridgeLineError.BAD_FINGERPRINT, raw)
                }
            }
        }

        val canonical = buildString {
            transport.wire?.let { append(it).append(' ') }
            append(if (family == IpFamily.V6) "[$host]:$port" else "$host:$port")
            fingerprint?.let { append(' ').append(it) }
            params.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
        }
        val id = idOf(transport, host, port, fingerprint, params)
        return BridgeParse.Ok(TorBridge(id, canonical, transport, host, port, family, fingerprint, params, dialHost, dialPort))
    }

    /**
     * The same bridge, however written: transport and fingerprint, plus the URL where several
     * webtunnel/meek bridges share placeholder addresses, else the endpoint.
     */
    private fun idOf(t: BridgeTransport, host: String, port: Int, fp: String?, params: Map<String, String>): String {
        val key = when (t) {
            BridgeTransport.WEBTUNNEL, BridgeTransport.MEEK, BridgeTransport.SNOWFLAKE ->
                "${t.name}|${fp ?: ""}|${params["url"]?.lowercase()?.trimEnd('/')}|${params["front"] ?: ""}"
            else -> "${t.name}|${fp ?: ""}|${host.lowercase()}:$port"
        }
        val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun splitAddress(a: String): Triple<String, Int, IpFamily>? {
        if (a.startsWith("[")) {
            val close = a.indexOf(']')
            if (close < 0 || close + 1 >= a.length || a[close + 1] != ':') return null
            val host = a.substring(1, close)
            if (!host.contains(':') || host.any { !(it.isLetterOrDigit() || it == ':' || it == '.') }) return null
            val port = a.substring(close + 2).toIntOrNull() ?: return null
            return Triple(host, port, IpFamily.V6)
        }
        val colon = a.lastIndexOf(':')
        if (colon <= 0) return null
        val host = a.substring(0, colon)
        val port = a.substring(colon + 1).toIntOrNull() ?: return null
        val m = V4.matchEntire(host)
        if (m != null) {
            if (m.groupValues.drop(1).any { (it.toIntOrNull() ?: 999) > 255 }) return null
            return Triple(host, port, IpFamily.V4)
        }
        // A hostname in the address field is legal for Tor, rare in practice.
        if (host.matches(Regex("^[A-Za-z0-9.-]+$")) && host.contains('.')) return Triple(host, port, IpFamily.NAME)
        return null
    }
}
