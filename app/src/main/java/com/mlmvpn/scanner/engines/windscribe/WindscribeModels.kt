package com.mlmvpn.scanner.engines.windscribe

import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

enum class WsProtocol(val code: String) { WIREGUARD("wg"), OPENVPN_UDP("udp"), OPENVPN_TCP("tcp"), STEALTH("stunnel"), WSTUNNEL("wstunnel") }
enum class WsError { NETWORK, AUTH, SESSION_EXPIRED, TWO_FA, CAPTCHA, ACCOUNT_UNAVAILABLE, QUOTA, STORAGE, PROTOCOL, CONFIG, TIMEOUT, CORE,
    /** The test found working combinations, but none of the best came up again right after it. */
    TESTED_NOT_CONNECTED }
class WsException(val error: WsError) : Exception(error.name)
data class WsAccount(val id: String = "", val username: String = "", val email: String? = null, val verified: Boolean = false,
    val premium: Boolean = false, val status: Int = 0, val quota: Long? = null, val used: Long? = null,
    val lastReset: String? = null, val checkedAt: Long = 0, val sessionValid: Boolean = true) {
    val remaining: Long? get() = if (quota != null && used != null) (quota - used).coerceAtLeast(0) else null
}
data class WsServer(val id: String, val dcId: String, val locationId: String, val country: String, val city: String,
    val nick: String, val hostname: String, val ip: String, val ip2: String, val ip3: String, val publicKey: String,
    val x509: String, val load: Int?, val weight: Int)
/** [cf]: the TCP connection to the server is opened from inside Cloudflare (one of the user's own configs). */
data class WsMethod(val serverId: String, val protocol: WsProtocol, val port: Int, val padding: Boolean = false, val cf: Boolean = false) {
    val key get() = "$serverId:${protocol.code}:$port:$padding:$cf"
}
data class WsMeasurement(val method: WsMethod, val connectMs: Long, val latencyMs: Long?, val downloadBps: Long?,
    val successfulSamples: Int, val samples: Int, val checkedAt: Long) {
    val success get() = latencyMs != null && successfulSamples > 0
}
internal object WsContract {
    /** The server's address for [method]: Stealth and WireGuard use ip3, WStunnel ip (official VPNParameters), OpenVPN ip2. */
    fun endpoint(server: WsServer, protocol: WsProtocol): String = when (protocol) {
        WsProtocol.WIREGUARD, WsProtocol.STEALTH -> server.ip3
        WsProtocol.WSTUNNEL -> server.ip
        else -> server.ip2
    }
    /** [relayPort]: the proxy dials a loopback relay that carries its TLS to the server from inside Cloudflare. */
    fun proxy(server: WsServer, method: WsMethod, relayPort: Int? = null): com.mlmvpn.scanner.openvpn.WsProxy {
        require(method.protocol in setOf(WsProtocol.STEALTH, WsProtocol.WSTUNNEL))
        return com.mlmvpn.scanner.openvpn.WsProxy(
            if (relayPort != null) "127.0.0.1" else endpoint(server, method.protocol),
            relayPort ?: method.port, method.protocol == WsProtocol.WSTUNNEL, method.padding)
    }
    /** wg-quick text for one node, as the official client builds it (no Amnezia values: the engine adds its own). */
    fun wireGuard(privateKey: String, address: String, init: JSONObject, server: WsServer, port: Int): String {
        require(server.ip3.isNotBlank() && server.publicKey.isNotBlank())
        return listOf(
            "[Interface]", "PrivateKey = $privateKey", "Address = $address", "DNS = 10.255.255.1", "MTU = 1280", "",
            "[Peer]", "PublicKey = ${server.publicKey}", "PresharedKey = ${init.getString("PresharedKey")}",
            "AllowedIPs = 0.0.0.0/0", "Endpoint = ${server.ip3}:$port", "PersistentKeepalive = 25",
        ).joinToString("\n", postfix = "\n")
    }
    fun account(data: JSONObject, now: Long): WsAccount = WsAccount(
        id = data.optString("user_id"), username = data.optString("username"), email = data.nullableString("email"),
        verified = data.optInt("email_status", -1) == 1, premium = data.optInt("is_premium") == 1,
        status = data.optInt("status"), quota = data.nullableString("traffic_max")?.toLongOrNull()?.takeIf { it >= 0 },
        used = data.nullableString("traffic_used")?.toLongOrNull()?.takeIf { it >= 0 },
        lastReset = data.nullableString("last_reset"), checkedAt = now, sessionValid = data.optBoolean("sessionValid", true),
    )
    fun servers(locations: String, inventory: String, premium: Boolean, alc: Set<String>): List<WsServer> {
        val centers = mutableMapOf<String, Pair<JSONObject, JSONObject>>()
        JSONObject(locations).getJSONArray("locations").objects().forEach { location ->
            val allowed = premium || location.optInt("premium_only") == 0 || location.optString("id") in alc
            if (allowed) location.getJSONArray("datacenters").objects().forEach { dc ->
                if (dc.optInt("status") == 1 && (premium || dc.optInt("premium") == 0 || location.optString("id") in alc))
                    centers[dc.optString("id")] = location to dc
            }
        }
        return JSONObject(inventory).getJSONArray("servers").objects().mapNotNull { node ->
            val (location, dc) = centers[node.optString("dc_id")] ?: return@mapNotNull null
            if (node.optInt("fd") == 1 || node.optString("host").isBlank()) return@mapNotNull null
            WsServer(node.optString("id"), dc.optString("id"), location.optString("id"), location.getString("country_code"),
                dc.getString("city"), dc.optString("nick"), node.getString("host"), node.optString("ip"), node.optString("ip2"),
                node.optString("ip3"), dc.optString("wg_pubkey"), dc.optString("ovpn_x509"),
                node.nullableString("net_load")?.toIntOrNull(), node.optInt("weight"))
        }.distinctBy { it.id }
    }
    /** Matches official WireguardUtil: SHA256(decoded public key), first 32 bits, masked into API CIDR. */
    fun address(publicKey: String, cidr: String): String {
        val parts = cidr.split('/')
        require(parts.size == 2)
        val prefix = parts[1].toInt()
        require(prefix in 1..32)
        val octets = parts[0].split('.').map { it.toInt().also { b -> require(b in 0..255) } }
        require(octets.size == 4)
        val network = octets.fold(0L) { a, b -> (a shl 8) or b.toLong() }
        val bytes = decode64(publicKey).also { require(it.size == 32) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(4).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 255) }
        val hostMask = (1L shl (32 - prefix)) - 1
        val ip = (network and hostMask.inv()) or (hash and hostMask)
        return listOf(24, 16, 8, 0).joinToString(".") { ((ip shr it) and 255).toString() } + "/32"
    }
    fun data(raw: String): JSONObject {
        val envelope = try { JSONObject(raw) } catch (_: Exception) { throw WsException(WsError.CONFIG) }
        if (envelope.has("errorCode") && envelope.optInt("errorCode") != 0) throw WsException(when (envelope.optInt("errorCode")) {
            701 -> WsError.SESSION_EXPIRED
            1340, 1341 -> WsError.TWO_FA
            708 -> WsError.CAPTCHA
            1700 -> WsError.ACCOUNT_UNAVAILABLE
            else -> WsError.AUTH
        })
        return envelope.optJSONObject("data") ?: throw WsException(WsError.CONFIG)
    }
    fun ports(data: JSONObject): Map<WsProtocol, List<Int>> = data.getJSONArray("portmap").objects().mapNotNull { p ->
        val protocol = WsProtocol.values().firstOrNull { it.code == p.optString("protocol") } ?: return@mapNotNull null
        val ports = p.optJSONArray("ports") ?: return@mapNotNull null
        protocol to (0 until ports.length()).mapNotNull { ports.optString(it).toIntOrNull()?.takeIf { port -> port in 1..65535 } }.distinct()
    }.toMap()
    fun openVpn(template: String, server: WsServer, method: WsMethod, username: String, password: String, relayPort: Int? = null): String {
        val endpoint = when { relayPort != null -> "127.0.0.1"; method.protocol == WsProtocol.STEALTH -> server.ip3; method.protocol == WsProtocol.WSTUNNEL -> server.ip; else -> server.ip2 }
        val port = relayPort ?: method.port
        require(method.protocol != WsProtocol.WIREGUARD && endpoint.isNotBlank() && server.x509.isNotBlank())
        require(listOf(username, password, endpoint, server.x509).all { s -> s.isNotBlank() && s.none { it == '\n' || it == '\r' || it == '\u0000' } })
        require(server.x509.matches(Regex("[a-zA-Z0-9._-]+")))
        val proto = if (method.protocol != WsProtocol.OPENVPN_UDP) "tcp-client" else "udp"
        // Retain certificate/key blocks byte-for-byte; replace only top-level transport/identity/auth directives.
        var block: String? = null
        val kept = template.lineSequence().filter { line ->
            val trimmed = line.trim()
            if (block != null) { if (trimmed == "</$block>") block = null; true }
            else if (trimmed.startsWith('<') && !trimmed.startsWith("</")) { block = trimmed.removeSurrounding("<", ">"); true }
            else trimmed.substringBefore(' ').lowercase() !in setOf("remote", "proto", "verify-x509-name", "auth-user-pass", "explicit-exit-notify")
        }.joinToString("\n")
        return "$kept\nproto $proto\nremote $endpoint $port $proto\nremote-cert-tls server\nverify-x509-name ${server.x509} name\n<auth-user-pass>\n$username\n$password\n</auth-user-pass>\nauth-nocache\n"
    }
    // Android 7 lacks java.util.Base64. This strict decoder also serves official base64 OVPN resources.
    fun decode64(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }
        require(clean.length % 4 == 0 && clean.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = java.io.ByteArrayOutputStream()
        var bits = 0; var count = 0
        for (c in clean.takeWhile { it != '=' }) {
            bits = (bits shl 6) or alphabet.indexOf(c); count += 6
            if (count >= 8) { count -= 8; out.write((bits shr count) and 255) }
        }
        return out.toByteArray()
    }
    fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
internal object WsPlanner {
    private val TCP_FAMILY = listOf(WsProtocol.OPENVPN_TCP, WsProtocol.STEALTH, WsProtocol.WSTUNNEL)

    /**
     * Nearest to Iran first: Turkey and the Gulf, then Europe, then the rest (America, Asia). Through
     * Cloudflare the path goes Iran -> a European edge -> the server, so a European node is hundreds of
     * milliseconds closer than an American one (measured 2026-10-10: Dallas 2.1 s, Paris 0.2 s).
     */
    private val NEAR = listOf("TR", "AE", "BG", "RO", "GR", "AT", "HU", "DE", "CH", "NL", "FR", "GB", "BE", "IT", "PL", "CZ", "NO", "SE", "DK", "FI", "ES")
    fun region(country: String): Int = NEAR.indexOf(country.uppercase()).let { if (it < 0) NEAR.size else it / 4 }
    /** Nearer regions first; within one, the least loaded. */
    // Written out with primitive compares: the compareBy/thenBy chains here crashed in the release
    // build with "Integer cannot be cast to Long" (crash #962, R8-merged selectors).
    val ORDER: Comparator<WsServer> = Comparator { a, b ->
        region(a.country).compareTo(region(b.country)).takeIf { it != 0 }
            ?: (a.load ?: 100).compareTo(b.load ?: 100).takeIf { it != 0 }
            ?: b.weight.compareTo(a.weight)
    }

    fun available(server: WsServer, p: WsProtocol): Boolean = when (p) {
        WsProtocol.WIREGUARD -> server.ip3.isNotBlank() && server.publicKey.isNotBlank()
        WsProtocol.STEALTH -> server.ip3.isNotBlank() && server.x509.isNotBlank()
        WsProtocol.WSTUNNEL -> server.ip.isNotBlank() && server.x509.isNotBlank()
        else -> server.ip2.isNotBlank() && server.x509.isNotBlank()
    }

    /** 443 when advertised (it looks like any HTTPS), else the first advertised port. */
    fun mainPort(ports: List<Int>): Int? = if (443 in ports) 443 else ports.firstOrNull()

    /**
     * The order one connect tries, best bet first, every entry an advertised protocol/port/node:
     *  1. WireGuard once -- the Amnezia engine behind it falls back across the nodes and its own ways
     *     (straight, then inside Cloudflare WARP) by itself;
     *  2. the TCP family from inside Cloudflare ([cf]: the user has a Cloudflare config to ride), which
     *     is what works where the line blackholes Windscribe's addresses;
     *  3. straight to the server only where [reachable] says the line reaches that address at all,
     *     plain and with the opening bytes split / TLS padded; OpenVPN UDP last.
     */
    fun plan(servers: List<WsServer>, ports: Map<WsProtocol, List<Int>>, protocol: WsProtocol?, preferred: WsMethod?,
             cf: Boolean = false, reachable: (WsServer, WsProtocol) -> Boolean = { _, _ -> true }): List<WsMethod> {
        val sorted = servers.sortedWith(ORDER)
        fun wanted(p: WsProtocol) = protocol == null || protocol == p
        val plan = buildList {
            if (wanted(WsProtocol.WIREGUARD)) sorted.firstOrNull { available(it, WsProtocol.WIREGUARD) }?.let { s ->
                mainPort(ports[WsProtocol.WIREGUARD].orEmpty())?.let { add(WsMethod(s.id, WsProtocol.WIREGUARD, it)) }
            }
            // OpenVPN UDP rides WARP's UDP door; the TCP family WARP or a Worker.
            if (cf) for ((i, s) in sorted.withIndex()) for (p in TCP_FAMILY + WsProtocol.OPENVPN_UDP) {
                // Inside Cloudflare the line sees none of it: one protocol per extra node is enough.
                if (!wanted(p) || !available(s, p) || (i > 0 && protocol == null && p != WsProtocol.OPENVPN_TCP)) continue
                mainPort(ports[p].orEmpty())?.let { add(WsMethod(s.id, p, it, cf = true)) }
            }
            for (s in sorted) for (p in TCP_FAMILY + WsProtocol.OPENVPN_UDP) {
                if (!wanted(p) || !available(s, p) || !reachable(s, p)) continue
                val list = ports[p].orEmpty()
                for (port in listOfNotNull(mainPort(list)) + list.filter { it != mainPort(list) }.take(1)) {
                    add(WsMethod(s.id, p, port))
                    // OpenVPN TCP: opening bytes split by the relay; Stealth/WStunnel: TLS hello padded.
                    if (p != WsProtocol.OPENVPN_UDP) add(WsMethod(s.id, p, port, padding = true))
                }
            }
        }.distinct()
        // The method that last worked on this network goes first even when this order would not have
        // made it (a row of the test table, an OpenVPN UDP through Cloudflare on a later node).
        val usable = preferred != null && (protocol == null || protocol == preferred.protocol) &&
            preferred.port in ports[preferred.protocol].orEmpty() && servers.any { it.id == preferred.serverId && available(it, preferred.protocol) }
        return if (usable) listOf(preferred!!) + (plan - preferred) else plan
    }
    /**
     * «تست همه»: each datacenter (its least-loaded node, nearest first) with each protocol once, the
     * way a connect reaches it on a filtered line -- WireGuard through the Amnezia engine, the rest
     * from inside Cloudflare. Server by server, so the table fills in rows the user can already use.
     */
    fun matrix(servers: List<WsServer>, ports: Map<WsProtocol, List<Int>>): List<WsMethod> =
        servers.sortedWith(ORDER).flatMap { s ->
            listOf(WsProtocol.WIREGUARD, WsProtocol.OPENVPN_TCP, WsProtocol.STEALTH, WsProtocol.WSTUNNEL, WsProtocol.OPENVPN_UDP).mapNotNull { p ->
                val port = mainPort(ports[p].orEmpty()) ?: return@mapNotNull null
                if (!available(s, p)) null else WsMethod(s.id, p, port, cf = p != WsProtocol.WIREGUARD)
            }
        }

    /** A method's score: steadiness first, then speed, then delay. */
    private fun ratio(m: WsMeasurement): Double = m.successfulSamples.toDouble() / m.samples.coerceAtLeast(1)
    /** More samples through, then faster download, then lower latency. */
    val SCORE: Comparator<WsMeasurement> = Comparator { a, b ->
        ratio(a).compareTo(ratio(b)).takeIf { it != 0 }
            ?: (a.downloadBps ?: 0L).compareTo(b.downloadBps ?: 0L).takeIf { it != 0 }
            ?: (b.latencyMs ?: Long.MAX_VALUE).compareTo(a.latencyMs ?: Long.MAX_VALUE)
    }

    fun best(results: List<WsMeasurement>): WsMeasurement? = results.filter { it.success }.maxWithOrNull(
        Comparator<WsMeasurement> { a, b -> SCORE.compare(a, b).takeIf { it != 0 } ?: b.connectMs.compareTo(a.connectMs) })
}
internal object WsSearchPolicy {
    const val CONNECT_BUDGET_MS = 180_000L
    const val SEARCH_BUDGET_MS = 300_000L
    const val BENCHMARK_BUDGET_MS = 420_000L
    /** Every server with every protocol, one after another: long, stoppable, results kept as they come. */
    const val TEST_ALL_BUDGET_MS = 90 * 60_000L
    fun mayUseCache(error: WsError, valid: Boolean) = error == WsError.NETWORK && valid
}
