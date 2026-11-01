package com.mlmvpn.scanner.engines.flux.core.parse

import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxNode
import com.mlmvpn.scanner.engines.flux.core.model.Proto
import com.mlmvpn.scanner.engines.flux.core.model.Security
import com.mlmvpn.scanner.engines.flux.core.model.Transport
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * FLUX's own link reader: strict where the app's general [com.mlmvpn.scanner.utils.VpnConfig] is
 * forgiving, because FLUX races public nodes nobody vetted. A link that is malformed, sends its
 * credentials in the clear, or turns certificate checks off never reaches the race.
 *
 * Kept separate from VpnConfig on purpose: VpnConfig parses with android.net.Uri (so it cannot run
 * in the JVM tests), has no Hysteria2, and accepts anything a user pastes -- right for a config the
 * user chose, wrong for one FLUX picked for them.
 *
 * Accepts `vless://`, `trojan://`, `hysteria2://` / `hy2://`. Everything else is rejected with a
 * reason, so a source's health can be measured by how much of it is usable.
 */
object FluxLinkParser {

    sealed class Result {
        data class Ok(val node: FluxNode) : Result()
        data class Rejected(val reason: String) : Result()
    }

    /** Parsed nodes of a subscription body, deduplicated, plus how many lines were refused. */
    data class Batch(val nodes: List<FluxNode>, val rejected: Map<String, Int>, val duplicates: Int)

    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    /** Xray also maps a short string (1-30 bytes) to a UUID; some panels ship those. */
    private val VLESS_ID_RE = Regex("^[A-Za-z0-9_\\-]{1,30}$")
    private val HOST_RE = Regex("^[A-Za-z0-9.\\-_]{1,253}$")
    private val PBK_RE = Regex("^[A-Za-z0-9_\\-]{42,44}$")
    private val SID_RE = Regex("^[0-9a-fA-F]{0,16}$")
    private val FINGERPRINTS = setOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
        "random", "randomized", "randomizednoalpn", "unsafe",
    )
    private val FLOWS = setOf("", "xtls-rprx-vision")

    fun parseBody(body: String, sourceId: String = ""): Batch {
        val text = decodeBodyIfBase64(body)
        val seen = LinkedHashMap<String, FluxNode>()
        val rejected = HashMap<String, Int>()
        var dupes = 0
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .forEach { line ->
                when (val r = parse(line, sourceId)) {
                    is Result.Ok -> if (seen.putIfAbsent(r.node.id, r.node) != null) dupes++
                    is Result.Rejected -> rejected.merge(r.reason, 1, Int::plus)
                }
            }
        return Batch(seen.values.toList(), rejected, dupes)
    }

    /**
     * A body is either links one per line, or the whole list base64-encoded (the usual
     * subscription format). Decoded only when it does not already look like links.
     */
    fun decodeBodyIfBase64(body: String): String {
        val trimmed = body.trim()
        if (trimmed.contains("://")) return trimmed
        return b64(trimmed.replace(Regex("\\s"), "")) ?: trimmed
    }

    fun parse(link: String, sourceId: String = ""): Result {
        val raw = link.trim()
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return Result.Rejected("not-a-link")
        val proto = when (raw.substring(0, schemeEnd).lowercase()) {
            "vless" -> Proto.VLESS
            "trojan" -> Proto.TROJAN
            "hysteria2", "hy2" -> Proto.HY2
            else -> return Result.Rejected("unsupported-scheme")
        }
        val parts = split(raw.substring(schemeEnd + 3)) ?: return Result.Rejected("malformed")
        val q = parts.query
        if (parts.port !in 1..65535) return Result.Rejected("bad-port")
        if (!validHost(parts.host)) return Result.Rejected("bad-host")
        val credential = parts.userInfo
        if (credential.isEmpty()) return Result.Rejected("no-credential")

        // Certificate checks off: anyone on the path could read the traffic, and this core has
        // removed `allowInsecure` altogether (a config carrying it does not start -- one such
        // outbound took a whole probe core down on a device). Refused for every protocol.
        val insecure = q["allowInsecure"] == "1" || q["allowInsecure"] == "true" || q["insecure"] == "1" || q["insecure"] == "true"
        if (insecure) return Result.Rejected("insecure-tls")

        return when (proto) {
            Proto.HY2 -> hy2(parts, credential, insecure, sourceId)
            else -> vlessOrTrojan(proto, parts, credential, sourceId)
        }
    }

    private fun vlessOrTrojan(proto: Proto, p: Parts, credential: String, sourceId: String): Result {
        val q = p.query
        if (proto == Proto.VLESS) {
            if (!UUID_RE.matches(credential) && !VLESS_ID_RE.matches(credential)) return Result.Rejected("bad-uuid")
            val enc = q["encryption"].orEmpty()
            if (enc.isNotEmpty() && enc != "none") return Result.Rejected("unsupported-encryption")
        }
        val transport = when (q["type"].orEmpty().lowercase()) {
            "", "tcp", "raw" -> Transport.TCP
            "ws" -> Transport.WS
            "grpc" -> Transport.GRPC
            "xhttp", "splithttp" -> Transport.XHTTP
            "httpupgrade" -> Transport.HTTPUPGRADE
            else -> return Result.Rejected("unsupported-transport")
        }
        val header = q["headerType"].orEmpty()
        if (header.isNotEmpty() && header != "none") return Result.Rejected("unsupported-header")
        val security = when (q["security"].orEmpty().lowercase()) {
            "tls" -> Security.TLS
            "reality" -> Security.REALITY
            // Trojan is TLS by definition; a trojan link without `security` means TLS.
            "" -> if (proto == Proto.TROJAN) Security.TLS else Security.NONE
            "none" -> Security.NONE
            else -> return Result.Rejected("unsupported-security")
        }
        // A VLESS session without TLS shows the ISP every site the user opens (the inner SNI goes
        // out as written). FLUX does not race those.
        if (security == Security.NONE) return Result.Rejected("plaintext")

        val serverIsName = Family.ofLiteral(p.host) == null
        var host = q["host"].orEmpty().trim()
        var sni = q["sni"].orEmpty().trim().ifEmpty { q["peer"].orEmpty().trim() }
        if (host.isNotEmpty() && !validHost(host)) return Result.Rejected("bad-host-header")
        if (sni.isNotEmpty() && !validHost(sni)) return Result.Rejected("bad-sni")
        if (security == Security.TLS && sni.isEmpty()) {
            sni = when {
                host.isNotEmpty() && Family.ofLiteral(host) == null -> host
                serverIsName -> p.host
                else -> return Result.Rejected("tls-without-name")
            }
        }
        if (transport.cdnFrontable && host.isEmpty()) host = if (sni.isNotEmpty()) sni else if (serverIsName) p.host else ""

        val fp = q["fp"].orEmpty().lowercase().let { if (it in FINGERPRINTS) it else "" }
        val flow = q["flow"].orEmpty()
        if (flow !in FLOWS) return Result.Rejected("unsupported-flow")
        if (flow.isNotEmpty() && (proto != Proto.VLESS || transport != Transport.TCP)) return Result.Rejected("flow-needs-tcp")

        var pbk = ""; var sid = ""; var spx = ""
        if (security == Security.REALITY) {
            pbk = q["pbk"].orEmpty()
            sid = q["sid"].orEmpty()
            spx = q["spx"].orEmpty()
            if (!PBK_RE.matches(pbk)) return Result.Rejected("bad-reality-key")
            if (!SID_RE.matches(sid)) return Result.Rejected("bad-reality-sid")
            if (sni.isEmpty() || Family.ofLiteral(sni) != null) return Result.Rejected("reality-without-sni")
            if (transport != Transport.TCP && transport != Transport.GRPC && transport != Transport.XHTTP) return Result.Rejected("reality-transport")
        }
        val path = when (transport) {
            Transport.WS, Transport.HTTPUPGRADE, Transport.XHTTP -> q["path"].orEmpty().ifEmpty { "/" }
            else -> ""
        }
        val serviceName = if (transport == Transport.GRPC) q["serviceName"].orEmpty() else ""
        val alpn = q["alpn"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val node = FluxNode(
            id = nodeId(proto, p.host, p.port, transport, security, host, path, sni, serviceName, credential),
            proto = proto, server = p.host, port = p.port, credential = credential,
            transport = transport, security = security, sni = sni, host = host, path = path,
            alpn = alpn, fingerprint = fp, flow = flow, publicKey = pbk, shortId = sid, spiderX = spx,
            serviceName = serviceName, xhttpMode = if (transport == Transport.XHTTP) q["mode"].orEmpty() else "",
            label = p.fragment, sourceId = sourceId,
            finalmask = q["fm"]?.takeIf { it.trim().startsWith("{") },
            cipherSuites = q["cs"].orEmpty(),
        )
        return Result.Ok(node)
    }

    private fun hy2(p: Parts, credential: String, insecure: Boolean, sourceId: String): Result {
        val q = p.query
        val sni = q["sni"].orEmpty().trim().ifEmpty { if (Family.ofLiteral(p.host) == null) p.host else "" }
        if (sni.isNotEmpty() && !validHost(sni)) return Result.Rejected("bad-sni")
        // Port hopping ("443,20000-30000") is not carried by this core's single-port outbound.
        if (q["mport"] != null) return Result.Rejected("port-hopping")
        val obfs = q["obfs"].orEmpty()
        if (obfs.isNotEmpty() && obfs != "salamander") return Result.Rejected("unsupported-obfs")
        val obfsPassword = q["obfs-password"].orEmpty()
        if (obfs.isNotEmpty() && obfsPassword.isEmpty()) return Result.Rejected("obfs-without-password")
        val node = FluxNode(
            id = nodeId(Proto.HY2, p.host, p.port, Transport.QUIC, Security.TLS, "", "", sni, obfs + ":" + obfsPassword, credential),
            proto = Proto.HY2, server = p.host, port = p.port, credential = credential,
            transport = Transport.QUIC, security = Security.TLS, sni = sni,
            alpn = listOf("h3"), obfs = obfs, obfsPassword = obfsPassword, label = p.fragment,
            sourceId = sourceId, insecure = insecure,
            certPin = certPin(q["pinSHA256"] ?: q["pinsha256"] ?: q["pinSha256"]),
        )
        return Result.Ok(node)
    }

    /** A certificate pin as the core takes it: 64 lowercase hex digits, or "" when it is not one. */
    fun certPin(raw: String?): String {
        val hex = raw.orEmpty().replace(":", "").replace("-", "").trim().lowercase()
        return if (hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) hex else ""
    }

    /**
     * The node's identity: what makes two links the same server for the same account. The
     * credential enters only as its own hash, so the id can be logged and stored in the clear.
     */
    fun nodeId(proto: Proto, server: String, port: Int, transport: Transport, security: Security, host: String, path: String, sni: String, extra: String, credential: String): String {
        val cred = sha256(credential)
        return sha256(listOf(proto.name, server.lowercase(), port, transport.code, security.name, host.lowercase(), path, sni.lowercase(), extra, cred).joinToString("|")).take(20)
    }

    private fun validHost(h: String): Boolean {
        if (h.isEmpty()) return false
        if (Family.ofLiteral(h) == Family.V6) return h.all { it.isLetterOrDigit() || it == ':' || it == '.' } && h.count { it == ':' } >= 2
        if (Family.ofLiteral(h) == Family.V4) return h.split('.').all { s -> s.toIntOrNull()?.let { it in 0..255 } == true }
        return HOST_RE.matches(h) && !h.startsWith('.') && !h.endsWith('-') && h.contains('.')
    }

    private class Parts(val userInfo: String, val host: String, val port: Int, val query: Map<String, String>, val fragment: String)

    /** `userinfo@host:port/?query#fragment`, with an IPv6 host in brackets. */
    private fun split(rest: String): Parts? {
        val hash = rest.indexOf('#')
        val fragment = if (hash >= 0) dec(rest.substring(hash + 1)) else ""
        val noFrag = if (hash >= 0) rest.substring(0, hash) else rest
        val qm = noFrag.indexOf('?')
        val query = if (qm >= 0) parseQuery(noFrag.substring(qm + 1)) else emptyMap()
        val authority = (if (qm >= 0) noFrag.substring(0, qm) else noFrag).trimEnd('/')
        val at = authority.lastIndexOf('@')
        if (at <= 0) return null
        val userInfo = dec(authority.substring(0, at))
        val hostPort = authority.substring(at + 1)
        val host: String
        val portStr: String
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(1, close)
            portStr = hostPort.substring(close + 1).removePrefix(":")
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon < 0) { host = hostPort; portStr = "443" } else {
                host = hostPort.substring(0, colon); portStr = hostPort.substring(colon + 1)
            }
        }
        val port = portStr.ifEmpty { "443" }.toIntOrNull() ?: return null
        return Parts(userInfo, host.trim().lowercase(), port, query, fragment)
    }

    private fun parseQuery(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        s.split('&').forEach { kv ->
            if (kv.isEmpty()) return@forEach
            val eq = kv.indexOf('=')
            val k = if (eq >= 0) kv.substring(0, eq) else kv
            val v = if (eq >= 0) kv.substring(eq + 1) else ""
            out.putIfAbsent(dec(k), dec(v))
        }
        return out
    }

    /** Percent-decoding that keeps a literal '+' (these links are not form-encoded). */
    private fun dec(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) { s }

    private fun b64(s: String): String? = try {
        val cleaned = s.replace('-', '+').replace('_', '/')
        val padded = cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')
        String(com.mlmvpn.scanner.engines.cfdoctor.WireBase64.decode(padded), Charsets.UTF_8).takeIf { it.contains("://") }
    } catch (_: Exception) { null }

    fun sha256(s: String): String {
        // Hex by table, not String.format per byte: this names every node of every source list,
        // thousands at a time, and the formatter was most of the cost (FLUX freeze, 1.2.41).
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
