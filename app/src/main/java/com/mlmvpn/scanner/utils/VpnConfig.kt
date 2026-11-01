package com.mlmvpn.scanner.utils

import android.net.Uri

data class VpnConfig(
    var protocol: String = "vless",
    var name: String = "",
    var address: String = "",
    var port: Int = 443,
    var uuid: String = "",
    var flow: String = "",
    var network: String = "tcp",
    var wsHost: String = "",
    var wsPath: String = "",
    var xhttpPath: String = "",
    var xhttpHost: String = "",
    var xhttpMode: String = "auto",
    var xhttpExtra: String = "",
    var serviceName: String = "",
    var tls: String = "tls",
    var sni: String = "",
    var alpn: String = "",
    var fingerprint: String = "",
    var publicKey: String = "",
    var shortId: String = "",
    var spiderX: String = "",
    // ── vmess / shadowsocks ────────────────────────────────────────────────────────────────
    // Both protocols carry their credentials differently from vless/trojan, which put
    // everything in the URI's userinfo. vmess ships a base64 JSON blob and shadowsocks a
    // method:password pair, so they need fields of their own rather than being squeezed into
    // `uuid`. Empty for every other protocol.
    var method: String = "",          // ss cipher, e.g. "aes-256-gcm"
    var password: String = "",        // ss password
    var alterId: Int = 0,             // vmess aid — 0 on everything modern
    var vmessSecurity: String = "auto",
    /**
     * The share link exactly as it arrived.
     *
     * vmess and ss links are not reconstructable from these fields: a vmess link is a base64
     * JSON object whose key order and extra keys we do not model, and an ss link has two
     * incompatible spellings in the wild. [toUriString] hands this back verbatim for those,
     * so a config that came in runnable goes back out runnable instead of being re-spelled
     * into something the core rejects.
     */
    var rawUri: String = "",
    /**
     * TLS cipher suites (Go's names, joined by ':'), the `cs` of PattNG's links. They only count
     * with the `unsafe` fingerprint, which [CfUnfilter.shape] then sets.
     */
    var cipherSuites: String = "",
    /** The stream's finalMask as a JSON object, the `fm` of PattNG's links ("" for none). */
    var finalMask: String = "",
    /**
     * The ECH config list for this server -- the `ech` of a link. On this app's links it is the
     * `cloudflare-ech.com+udp://1.1.1.1` form (an outer name plus the resolver that serves the key),
     * which the EDG worker exports and the ECH strategy sets; the core fetches the key over that DoH
     * and encrypts the ClientHello, so the real server name never travels in the clear. "" = no ECH.
     */
    var ech: String = ""
) {
    companion object {
        /** A %XX escape (decodeB64). */
        private val PERCENT = Regex("%([0-9A-Fa-f]{2})")

        fun parseUri(uriString: String): VpnConfig? {
            try {
                val config = VpnConfig()
                val uri = Uri.parse(uriString)
                config.protocol = uri.scheme?.lowercase() ?: return null
                // vmess and ss are not URIs in the same sense -- neither has a parseable
                // authority -- so they get their own readers before the generic path below.
                if (config.protocol == "vmess") return parseVmess(uriString)
                if (config.protocol == "ss") return parseShadowsocks(uriString)
                if (config.protocol != "vless" && config.protocol != "trojan") return null
                config.rawUri = uriString

                val userInfo = uri.userInfo
                if (userInfo != null) config.uuid = userInfo
                
                config.address = uri.host ?: ""
                config.port = if (uri.port > 0) uri.port else 443
                
                config.name = uri.fragment?.let { android.net.Uri.decode(it) } ?: ""

                // The query read once. Uri.getQueryParameter rescans the whole query and builds a
                // fresh ICU decoder on every call -- nineteen per link -- and the node list, the
                // free-config importer and the scanner parse thousands of links: the main thread
                // stood in those decoders for seconds (freeze reports, 1.2.41).
                val q = queryOf(uri.encodedQuery)
                config.network = q.param("type") ?: "tcp"
                config.wsHost = q.param("host") ?: ""
                config.wsPath = q.param("path") ?: ""
                config.xhttpPath = config.wsPath
                config.xhttpHost = config.wsHost
                config.xhttpMode = q.param("mode") ?: "auto"
                config.xhttpExtra = q.param("extra") ?: ""
                config.serviceName = q.param("serviceName") ?: ""
                config.tls = q.param("security") ?: ""
                config.sni = q.param("sni") ?: ""
                config.alpn = q.param("alpn") ?: ""
                config.fingerprint = q.param("fp") ?: ""
                config.cipherSuites = q.param("cs") ?: ""
                config.finalMask = q.param("fm") ?: ""
                config.ech = q.param("ech") ?: ""
                config.flow = q.param("flow") ?: ""
                config.publicKey = q.param("pbk") ?: ""
                config.shortId = q.param("sid") ?: ""
                config.spiderX = q.param("spx") ?: ""

                return config
            } catch (e: Exception) {
                return null
            }
        }

        /** A query's raw pairs, first occurrence of each key kept (as Uri.getQueryParameter answers). */
        private fun queryOf(encodedQuery: String?): Map<String, String> {
            if (encodedQuery.isNullOrEmpty()) return emptyMap()
            val out = HashMap<String, String>()
            for (pair in encodedQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                if (!out.containsKey(key)) out[key] = if (eq < 0) "" else pair.substring(eq + 1)
            }
            return out
        }

        /**
         * A value decoded exactly as Uri.getQueryParameter decodes it ('+' is a space, %XX is UTF-8),
         * and only when there is something to decode.
         */
        private fun Map<String, String>.param(key: String): String? {
            val raw = this[key] ?: return null
            if (raw.indexOf('%') < 0 && raw.indexOf('+') < 0) return raw
            return try {
                java.net.URLDecoder.decode(raw, "UTF-8")
            } catch (e: Exception) {
                Uri.decode(raw.replace('+', ' '))
            }
        }

        /** Anything with a `#remark`, decoded, or "" when there is none. */
        private fun fragmentOf(uriString: String): String {
            val hash = uriString.indexOf('#')
            if (hash == -1) return ""
            return try {
                Uri.decode(uriString.substring(hash + 1))
            } catch (e: Exception) {
                uriString.substring(hash + 1)
            }
        }

        /**
         * Tolerant base64: these links appear both padded and unpadded, URL-safe and not, and some
         * lists escape the padding (`%3D`): an ss link with one could not be read (a «گره» chat test,
         * 28 Oct 2026). Only %XX is undone; a `+` stays a `+`.
         */
        private fun decodeB64(raw: String): String? {
            val unescaped = PERCENT.replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
            val cleaned = unescaped.trim().replace("-", "+").replace("_", "/").replace(Regex("\\s"), "")
            val padded = cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')
            return try {
                String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
            } catch (e: Exception) {
                null
            }
        }

        /**
         * `vmess://<base64 of a JSON object>`.
         *
         * The JSON uses one-or-two-letter keys (`add`, `id`, `net`, `tls`, ...) and every value
         * may arrive as a string or a number depending on which client wrote it, so ports and
         * alterId are read as strings and coerced.
         */
        private fun parseVmess(uriString: String): VpnConfig? {
            return try {
                val body = uriString.substringAfter("vmess://").substringBefore('#')
                val json = org.json.JSONObject(decodeB64(body) ?: return null)
                val config = VpnConfig()
                config.protocol = "vmess"
                config.rawUri = uriString
                config.address = json.optString("add").ifBlank { return null }
                config.port = json.optString("port").toIntOrNull() ?: json.optInt("port", 0)
                if (config.port <= 0) return null
                config.uuid = json.optString("id").ifBlank { return null }
                config.alterId = json.optString("aid").toIntOrNull() ?: json.optInt("aid", 0)
                config.vmessSecurity = json.optString("scy").ifBlank { "auto" }
                config.network = json.optString("net").ifBlank { "tcp" }
                config.wsHost = json.optString("host")
                config.wsPath = json.optString("path")
                config.xhttpHost = config.wsHost
                config.xhttpPath = config.wsPath
                config.serviceName = json.optString("path")   // grpc puts serviceName in `path`
                // `tls` is "tls" or empty; a few writers use "none" explicitly.
                config.tls = json.optString("tls").let { if (it == "none") "" else it }
                config.sni = json.optString("sni").ifBlank { config.wsHost }
                config.alpn = json.optString("alpn")
                config.fingerprint = json.optString("fp")
                config.cipherSuites = json.optString("cs")
                config.finalMask = json.optString("fm")
                config.ech = json.optString("ech")
                config.name = fragmentOf(uriString).ifBlank { json.optString("ps") }
                config
            } catch (e: Exception) {
                null
            }
        }

        /**
         * `ss://` in both spellings found in the wild:
         *   - legacy: the whole `method:password@host:port` is one base64 blob
         *   - SIP002: only `method:password` is base64, the host is plain
         */
        private fun parseShadowsocks(uriString: String): VpnConfig? {
            return try {
                val body = uriString.substringAfter("ss://").substringBefore('#')
                val config = VpnConfig()
                config.protocol = "ss"
                config.rawUri = uriString
                config.name = fragmentOf(uriString)

                val at = body.lastIndexOf('@')
                val credentials: String
                val endpoint: String
                if (at == -1) {
                    // Legacy: everything is in one blob.
                    val decoded = decodeB64(body.substringBefore('?')) ?: return null
                    val split = decoded.lastIndexOf('@')
                    if (split == -1) return null
                    credentials = decoded.substring(0, split)
                    endpoint = decoded.substring(split + 1)
                } else {
                    // SIP002: userinfo may be base64 or, rarely, already plain.
                    val userInfo = body.substring(0, at)
                    credentials = decodeB64(userInfo) ?: userInfo
                    endpoint = body.substring(at + 1).substringBefore('?')
                }

                val colon = credentials.indexOf(':')
                if (colon == -1) return null
                config.method = credentials.substring(0, colon)
                config.password = credentials.substring(colon + 1)

                val portSep = endpoint.lastIndexOf(':')
                if (portSep == -1) return null
                config.address = endpoint.substring(0, portSep).trim('[', ']')
                config.port = endpoint.substring(portSep + 1).toIntOrNull() ?: return null
                if (config.address.isBlank() || config.port <= 0) return null

                // Plugins (obfs, v2ray-plugin) need a transport this app does not build here;
                // accepting them would produce a config the core starts and cannot use.
                if (body.contains("plugin=")) return null

                config.network = "tcp"
                config.tls = ""
                config
            } catch (e: Exception) {
                null
            }
        }
    }
    
    fun toUriString(): String {
        // vmess and ss cannot be rebuilt from these fields without risking a link the core
        // rejects -- see [rawUri]. Hand back what came in.
        if ((protocol == "vmess" || protocol == "ss") && rawUri.isNotEmpty()) return rawUri

        val builder = Uri.Builder()
            .scheme(protocol)
            .encodedAuthority("$uuid@$address:$port")
        
        if (network.isNotEmpty()) builder.appendQueryParameter("type", network)
        if (network == "xhttp") {
            if (xhttpHost.isNotEmpty()) builder.appendQueryParameter("host", xhttpHost)
            if (xhttpPath.isNotEmpty()) builder.appendQueryParameter("path", xhttpPath)
            if (xhttpMode != "auto" && xhttpMode.isNotEmpty()) builder.appendQueryParameter("mode", xhttpMode)
            if (xhttpExtra.isNotEmpty()) builder.appendQueryParameter("extra", xhttpExtra)
        } else if (network == "grpc") {
            if (serviceName.isNotEmpty()) builder.appendQueryParameter("serviceName", serviceName)
        } else {
            if (wsHost.isNotEmpty()) builder.appendQueryParameter("host", wsHost)
            if (wsPath.isNotEmpty()) builder.appendQueryParameter("path", wsPath)
        }
        if (tls.isNotEmpty()) builder.appendQueryParameter("security", tls)
        if (sni.isNotEmpty()) builder.appendQueryParameter("sni", sni)
        if (alpn.isNotEmpty()) builder.appendQueryParameter("alpn", alpn)
        if (fingerprint.isNotEmpty()) builder.appendQueryParameter("fp", fingerprint)
        if (cipherSuites.isNotEmpty()) builder.appendQueryParameter("cs", cipherSuites)
        if (finalMask.isNotEmpty()) builder.appendQueryParameter("fm", finalMask)
        if (ech.isNotEmpty()) builder.appendQueryParameter("ech", ech)
        if (protocol == "vless" && flow.isNotEmpty()) builder.appendQueryParameter("flow", flow)
        if (tls == "reality") {
            if (publicKey.isNotEmpty()) builder.appendQueryParameter("pbk", publicKey)
            if (shortId.isNotEmpty()) builder.appendQueryParameter("sid", shortId)
            if (spiderX.isNotEmpty()) builder.appendQueryParameter("spx", spiderX)
        }
        if (protocol == "vless") builder.appendQueryParameter("encryption", "none")
        
        builder.fragment(name)
        
        return builder.build().toString().replace("%40", "@")
    }
}
