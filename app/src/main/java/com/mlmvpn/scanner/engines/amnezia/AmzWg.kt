package com.mlmvpn.scanner.engines.amnezia

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/**
 * One WireGuard config, read from a `.conf` file or a `wireguard://` link, with any AmneziaWG
 * settings it carries.
 *
 * Written out again in a canonical form that holds ONLY the keys the AmneziaWG library knows:
 * its parser throws on anything else (`Table`, `SaveConfig`, WireSock's `Id`/`Ip`/`Ib`), so a
 * config copied from another app would otherwise refuse to start for a reason the user never sees.
 */
data class WgConfig(
    val privateKey: String,
    val addresses: List<String>,
    val dns: List<String> = emptyList(),
    val mtu: Int? = null,
    val jc: Int? = null,
    val jmin: Int? = null,
    val jmax: Int? = null,
    val s1: Int? = null,
    val s2: Int? = null,
    /** H1..H4 as written (a number, or a range in AmneziaWG 2). */
    val h: List<String?> = listOf(null, null, null, null),
    /** I1..I5 signature packets, as written. */
    val i: List<String?> = listOf(null, null, null, null, null),
    val publicKey: String,
    val presharedKey: String? = null,
    val endpointHost: String,
    val endpointPort: Int,
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val keepalive: Int? = null,
    /** Cloudflare's client id bytes. Xray only; AmneziaWG has no such field and WARP works without it. */
    val reserved: List<Int>? = null,
) {
    /** Any AmneziaWG setting at all. */
    val hasAwg: Boolean
        get() = (jc ?: 0) > 0 || (s1 ?: 0) != 0 || (s2 ?: 0) != 0 || h.any { it != null && !defaultH(it) } || i.any { !it.isNullOrBlank() }

    /**
     * Settings a plain WireGuard server would not understand (S1/S2, H1..H4 other than 1..4), or
     * signature packets Xray cannot send as they are: only the AmneziaWG core itself can test and
     * carry these.
     */
    val needsAwgCore: Boolean
        get() = (s1 ?: 0) != 0 || (s2 ?: 0) != 0 ||
            h.withIndex().any { (n, v) -> v != null && v.trim() != (n + 1).toString() } ||
            i.any { !it.isNullOrBlank() && hexPacket(it) == null }

    val warp: Boolean get() = publicKey == WARP_PEER_KEY

    private fun defaultH(v: String) = v.trim() in setOf("1", "2", "3", "4")

    /**
     * The config the AmneziaWG library gets.
     *
     * @param obfuscate junk packets in front of a plain config. A plain WireGuard server drops
     *   them unread (they are not WireGuard messages), so this is safe against any server, and it
     *   is what makes the handshake stop looking like WireGuard to a filter. A config with
     *   AmneziaWG settings of its own keeps them untouched.
     * @param ipv4Only IPv6 inside the tunnel blackholes on many Iranian networks; without an IPv6
     *   address Android blocks the family instead, and apps fall back to IPv4 at once.
     */
    fun toConf(obfuscate: Boolean = false, ipv4Only: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = ").append(privateKey).append('\n')
        val addrs = addresses.map { withPrefix(it) }.let { a ->
            if (ipv4Only && a.any { !it.contains(':') }) a.filter { !it.contains(':') } else a
        }
        sb.append("Address = ").append(addrs.joinToString(", ")).append('\n')
        val dnsList = dns.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }.let { d ->
            if (ipv4Only && d.any { !it.contains(':') }) d.filter { !it.contains(':') } else d
        }
        sb.append("DNS = ").append(dnsList.joinToString(", ")).append('\n')
        sb.append("MTU = ").append(mtu ?: DEFAULT_MTU).append('\n')
        if (hasAwg) {
            jc?.let { sb.append("Jc = ").append(it).append('\n') }
            jmin?.let { sb.append("Jmin = ").append(it).append('\n') }
            jmax?.let { sb.append("Jmax = ").append(it).append('\n') }
            s1?.let { sb.append("S1 = ").append(it).append('\n') }
            s2?.let { sb.append("S2 = ").append(it).append('\n') }
            h.forEachIndexed { n, v -> if (v != null) sb.append("H").append(n + 1).append(" = ").append(v).append('\n') }
            i.forEachIndexed { n, v -> if (!v.isNullOrBlank()) sb.append("I").append(n + 1).append(" = ").append(v).append('\n') }
        } else if (obfuscate) {
            sb.append("Jc = ").append(DEFAULT_JC).append('\n')
            sb.append("Jmin = ").append(DEFAULT_JMIN).append('\n')
            sb.append("Jmax = ").append(DEFAULT_JMAX).append('\n')
            sb.append("S1 = 0\nS2 = 0\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4\n")
        }
        sb.append("\n[Peer]\n")
        sb.append("PublicKey = ").append(publicKey).append('\n')
        presharedKey?.let { sb.append("PresharedKey = ").append(it).append('\n') }
        sb.append("AllowedIPs = ").append(allowedIps.ifEmpty { listOf("0.0.0.0/0", "::/0") }.joinToString(", ")).append('\n')
        sb.append("Endpoint = ").append(endpoint()).append('\n')
        sb.append("PersistentKeepalive = ").append(keepalive ?: 25).append('\n')
        return sb.toString()
    }

    fun endpoint(): String = if (endpointHost.contains(':')) "[$endpointHost]:$endpointPort" else "$endpointHost:$endpointPort"

    /**
     * The same server as an Xray `wireguard` outbound, for the delay test. When [noiseTag] is set
     * the outbound dials through that freedom outbound, which sends the junk packets first --
     * so what is tested is what will be connected, not a bare WireGuard the filter may drop.
     */
    fun xrayOutbound(tag: String, noiseTag: String?): JSONObject {
        val peer = JSONObject()
            .put("publicKey", publicKey)
            .put("endpoint", endpoint())
            .put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0"))
        presharedKey?.let { peer.put("preSharedKey", it) }
        keepalive?.let { peer.put("keepAlive", it) }
        val settings = JSONObject()
            .put("secretKey", privateKey)
            .put("address", JSONArray().also { a -> addresses.forEach { a.put(withPrefix(it)) } })
            .put("mtu", mtu ?: DEFAULT_MTU)
            .put("peers", JSONArray().put(peer))
            .put("noKernelTun", true)
            .put("domainStrategy", "ForceIPv4")
        reserved?.takeIf { it.size == 3 }?.let { r -> settings.put("reserved", JSONArray().also { a -> r.forEach { a.put(it) } }) }
        val out = JSONObject().put("tag", tag).put("protocol", "wireguard").put("settings", settings)
        if (noiseTag != null) out.put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", noiseTag)))
        return out
    }

    /**
     * The freedom outbound that carries the junk: the config's own I-packets (hex only) and Jc
     * junk, or the defaults when [obfuscate] asks for them on a plain config. Null when there is
     * nothing to send.
     */
    fun noiseOutbound(tag: String, obfuscate: Boolean): JSONObject? {
        val noises = JSONArray()
        i.forEach { spec -> spec?.let { hexPacket(it) }?.let { hex -> noises.put(JSONObject().put("type", "hex").put("packet", hex).put("delay", "0")) } }
        val count = if (hasAwg) (jc ?: 0) else if (obfuscate) DEFAULT_JC else 0
        val lo = if (hasAwg) (jmin ?: DEFAULT_JMIN) else DEFAULT_JMIN
        val hi = if (hasAwg) (jmax ?: DEFAULT_JMAX) else DEFAULT_JMAX
        repeat(count.coerceIn(0, 10)) {
            noises.put(JSONObject().put("type", "rand").put("packet", "${lo.coerceAtLeast(1)}-${hi.coerceAtLeast(lo.coerceAtLeast(1))}").put("delay", "1-3"))
        }
        if (noises.length() == 0) return null
        return JSONObject().put("tag", tag).put("protocol", "freedom").put("settings", JSONObject()
            .put("domainStrategy", "UseIPv4")
            .put("noises", noises))
    }

    companion object {
        const val WARP_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        const val DEFAULT_MTU = 1280
        const val DEFAULT_JC = 4
        const val DEFAULT_JMIN = 40
        const val DEFAULT_JMAX = 70

        private fun withPrefix(a: String): String {
            val t = a.trim()
            if (t.contains('/')) return t
            return if (t.contains(':')) "$t/128" else "$t/32"
        }

        /**
         * An AmneziaWG I-packet made only of `<b 0x…>` parts, as one hex string Xray can send.
         * Anything with random or time parts (`<r 16>`, `<t>`, `<c>`) is null: only the
         * AmneziaWG core can build those.
         */
        fun hexPacket(spec: String): String? {
            val parts = Regex("<([^>]*)>").findAll(spec).map { it.groupValues[1].trim() }.toList()
            if (parts.isEmpty()) return null
            val out = StringBuilder()
            for (p in parts) {
                if (!p.startsWith("b ")) return null
                val hex = p.removePrefix("b ").trim().removePrefix("0x").removePrefix("0X")
                if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
                out.append(hex.lowercase())
            }
            return out.toString().takeIf { it.isNotEmpty() && it.length <= 2 * 1200 }
        }

        /** A 32-byte key in base64, as WireGuard writes it. */
        fun validKey(k: String?): Boolean = k != null && runCatching { Base64.getDecoder().decode(k.trim()).size == 32 }.getOrDefault(false)

        /** Why a `.conf` cannot be used, for a message that names it; null when it can. */
        enum class Problem { NO_PEER, NO_ENDPOINT, BAD_PRIVATE_KEY, BAD_PUBLIC_KEY, NO_ADDRESS }

        fun diagnose(text: String): Problem? {
            if (parseConf(text) != null) return null
            val body = text.lines().map { it.substringBefore('#').trim() }
            fun value(section: String, key: String): String? {
                var current = ""
                for (l in body) {
                    if (l.startsWith("[") && l.endsWith("]")) { current = l.trim('[', ']').trim().lowercase(); continue }
                    val eq = l.indexOf('=')
                    if (current == section && eq > 0 && l.substring(0, eq).trim().equals(key, true)) return l.substring(eq + 1).trim()
                }
                return null
            }
            return when {
                body.none { it.equals("[peer]", true) || it.replace(" ", "").equals("[peer]", true) } -> Problem.NO_PEER
                value("peer", "endpoint").isNullOrBlank() -> Problem.NO_ENDPOINT
                !validKey(value("interface", "privatekey")) -> Problem.BAD_PRIVATE_KEY
                !validKey(value("peer", "publickey")) -> Problem.BAD_PUBLIC_KEY
                value("interface", "address").isNullOrBlank() -> Problem.NO_ADDRESS
                else -> null
            }
        }

        /** Reads a `.conf` file. Null when it is not a usable WireGuard config. */
        fun parseConf(text: String): WgConfig? {
            var section = ""
            val iface = HashMap<String, String>()
            val addresses = ArrayList<String>()
            val dns = ArrayList<String>()
            val peer = HashMap<String, String>()
            val allowed = ArrayList<String>()
            var peers = 0
            for (rawLine in text.lineSequence()) {
                val line = rawLine.substringBefore('#').trim().trim('﻿')
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length - 1).trim().lowercase()
                    if (section == "peer") peers++
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim().lowercase()
                val value = line.substring(eq + 1).trim()
                when (section) {
                    "interface" -> when (key) {
                        "address" -> addresses += splitList(value)
                        "dns" -> dns += splitList(value)
                        else -> iface[key] = value
                    }
                    // Only the first peer: a config with several is a mesh, not a VPN server.
                    "peer" -> if (peers == 1) when (key) {
                        "allowedips" -> allowed += splitList(value)
                        else -> peer[key] = value
                    }
                }
            }
            val (host, port) = splitEndpoint(peer["endpoint"] ?: return null) ?: return null
            val priv = iface["privatekey"]?.trim()
            val pub = peer["publickey"]?.trim()
            if (!validKey(priv) || !validKey(pub)) return null
            if (addresses.isEmpty()) return null
            return WgConfig(
                privateKey = priv!!, addresses = addresses, dns = dns.filter { it.isNotBlank() && !it.any { c -> c.isLetter() && c !in 'a'..'f' && c !in 'A'..'F' } },
                mtu = iface["mtu"]?.toIntOrNull()?.takeIf { it in 576..1500 },
                jc = iface["jc"]?.toIntOrNull(), jmin = iface["jmin"]?.toIntOrNull(), jmax = iface["jmax"]?.toIntOrNull(),
                s1 = iface["s1"]?.toIntOrNull(), s2 = iface["s2"]?.toIntOrNull(),
                h = (1..4).map { iface["h$it"]?.takeIf { v -> v.isNotBlank() } },
                i = (1..5).map { iface["i$it"]?.takeIf { v -> v.isNotBlank() } },
                publicKey = pub!!, presharedKey = peer["presharedkey"]?.trim()?.takeIf { validKey(it) },
                endpointHost = host, endpointPort = port,
                allowedIps = allowed.ifEmpty { listOf("0.0.0.0/0", "::/0") },
                keepalive = peer["persistentkeepalive"]?.toIntOrNull(),
            ).sane()
        }

        /**
         * Reads a `wireguard://privatekey@host:port?publickey=…&address=…#name` link (the v2rayNG /
         * Hiddify / MahsaNG form). MahsaNG's `wnoise*` settings become AmneziaWG junk of the same
         * size and count. Returns the config and the link's name.
         */
        fun parseUri(link: String): Pair<WgConfig, String>? {
            val raw = link.trim()
            val scheme = raw.substringBefore("://", "").lowercase()
            if (scheme != "wireguard" && scheme != "wg") return null
            val rest = raw.substringAfter("://")
            val hash = rest.indexOf('#')
            val name = if (hash >= 0) dec(rest.substring(hash + 1)) else ""
            val noFrag = if (hash >= 0) rest.substring(0, hash) else rest
            val qm = noFrag.indexOf('?')
            val q = HashMap<String, String>()
            if (qm >= 0) noFrag.substring(qm + 1).split('&').forEach { kv ->
                val e = kv.indexOf('=')
                if (e > 0) q.putIfAbsent(dec(kv.substring(0, e)).lowercase(), dec(kv.substring(e + 1)))
            }
            val authority = (if (qm >= 0) noFrag.substring(0, qm) else noFrag).trimEnd('/')
            val at = authority.lastIndexOf('@')
            if (at <= 0) return null
            val priv = dec(authority.substring(0, at)).trim()
            val (host, port) = splitEndpoint(authority.substring(at + 1)) ?: return null
            val pub = (q["publickey"] ?: q["public_key"] ?: q["peer_public_key"] ?: q["pk"])?.trim()
            if (!validKey(priv) || !validKey(pub)) return null
            val addresses = splitList(q["address"] ?: q["ip"] ?: return null)
            if (addresses.isEmpty()) return null
            val reserved = q["reserved"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 && it.all { b -> b in 0..255 } }
            // MahsaNG noise: wnoisecount packets of wpayloadsize bytes before the handshake.
            val noiseCount = q["wnoisecount"]?.substringBefore('-')?.trim()?.toIntOrNull()?.coerceIn(1, 10)
            val payload = q["wpayloadsize"]?.split('-')?.mapNotNull { it.trim().toIntOrNull() }
            val hasNoise = q.containsKey("wnoise") && noiseCount != null
            val cfg = WgConfig(
                privateKey = priv, addresses = addresses,
                dns = splitList(q["dns"].orEmpty()),
                mtu = q["mtu"]?.toIntOrNull()?.takeIf { it in 576..1500 },
                jc = if (hasNoise) noiseCount else null,
                jmin = if (hasNoise) payload?.firstOrNull()?.coerceIn(1, 1000) ?: DEFAULT_JMIN else null,
                jmax = if (hasNoise) payload?.lastOrNull()?.coerceIn(1, 1000) ?: DEFAULT_JMAX else null,
                publicKey = pub!!,
                presharedKey = (q["presharedkey"] ?: q["psk"])?.trim()?.takeIf { validKey(it) },
                endpointHost = host, endpointPort = port,
                keepalive = q["keepalive"]?.toIntOrNull(),
                reserved = reserved,
            ).sane()
            return cfg to name
        }

        private fun splitList(v: String): List<String> = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /** `host:port` or `[v6]:port`; null when there is no usable port. */
        fun splitEndpoint(ep: String): Pair<String, Int>? {
            val t = ep.trim()
            if (t.startsWith("[")) {
                val close = t.indexOf(']')
                if (close < 0) return null
                val port = t.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
                return (t.substring(1, close) to port).takeIf { port in 1..65535 }
            }
            val colon = t.lastIndexOf(':')
            if (colon <= 0) return null
            val host = t.substring(0, colon).trim().lowercase()
            val port = t.substring(colon + 1).trim().toIntOrNull() ?: return null
            if (port !in 1..65535 || host.isEmpty() || host.any { it.isWhitespace() }) return null
            return host to port
        }

        private fun dec(s: String): String = try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) { s }
    }

    /** Junk sizes the AmneziaWG parser accepts: min below max, both inside one packet. */
    private fun sane(): WgConfig {
        if (jc == null) return this
        val lo = (jmin ?: DEFAULT_JMIN).coerceIn(1, 1200)
        val hi = (jmax ?: DEFAULT_JMAX).coerceIn(1, 1280)
        return copy(jc = jc.coerceIn(0, 128), jmin = minOf(lo, hi - 1).coerceAtLeast(0), jmax = maxOf(hi, lo + 1))
    }
}
