package com.mlmvpn.scanner.engines.outline

import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Reads Outline access keys and Shadowsocks lists. Pure Kotlin (no Android types), so every shape
 * found in the wild is covered by unit tests.
 *
 *  - `ss://BASE64(method:password)@host:port/?outline=1&prefix=...#name` (SIP002, Outline's own)
 *  - `ss://BASE64(method:password@host:port)#name` (the legacy form)
 *  - `ss://method:password@host:port#name` (plain userinfo, rare)
 *  - a whole list, one key per line, or the same list base64-encoded as a subscription
 *  - the body of an `ssconf://` dynamic key: one `ss://` link, or Outline's JSON object
 *
 * Refused: plugins (obfs, v2ray-plugin -- a transport the tunnel does not build), ciphers the core
 * does not speak or that are broken (rc4, plain, none), and addresses that can never be a server
 * (private ranges, Iran's block page).
 */
object OutlineParser {

    /** What Xray's Shadowsocks outbound accepts, lower-case. */
    val CIPHERS = setOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "chacha20-poly1305",
        "xchacha20-ietf-poly1305", "xchacha20-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
    )

    data class Batch(val servers: List<OutlineServer>, val skipped: Int)

    fun parseText(text: String, sourceId: String, now: Long): Batch {
        val body = text.trim().removePrefix("﻿")
        // A subscription: the whole list as one base64 blob.
        val lines = if (!body.contains("://") && body.length > 16) decodeB64(body.replace(Regex("\\s"), ""))?.lines() ?: body.lines() else body.lines()
        var skipped = 0
        val out = LinkedHashMap<String, OutlineServer>()
        for (raw in lines) {
            val line = raw.trim()
            if (!line.startsWith("ss://", ignoreCase = true)) continue
            val s = parse(line, sourceId, now)
            if (s == null) skipped++ else out.putIfAbsent(s.id, s)
        }
        return Batch(out.values.toList(), skipped)
    }

    fun parse(uri: String, sourceId: String, now: Long): OutlineServer? = runCatching {
        val link = uri.trim()
        if (!link.startsWith("ss://", ignoreCase = true)) return null
        val afterScheme = link.substring(5)
        val name = afterScheme.substringAfter('#', "").let { urlDecode(it) }.trim()
        val noFrag = afterScheme.substringBefore('#')
        val query = noFrag.substringAfter('?', "")
        if (query.contains("plugin=", ignoreCase = true)) return null
        val main = noFrag.substringBefore('?').trimEnd('/')

        val credentials: String
        val endpoint: String
        val at = main.lastIndexOf('@')
        if (at < 0) {
            val decoded = decodeB64(main) ?: return null
            val split = decoded.lastIndexOf('@')
            if (split < 0) return null
            credentials = decoded.substring(0, split)
            endpoint = decoded.substring(split + 1)
        } else {
            val userInfo = urlDecode(main.substring(0, at))
            credentials = if (userInfo.contains(':')) userInfo else decodeB64(userInfo) ?: return null
            endpoint = main.substring(at + 1)
        }
        val colon = credentials.indexOf(':')
        if (colon <= 0) return null
        val method = credentials.substring(0, colon).trim().lowercase()
        val password = credentials.substring(colon + 1)
        if (method !in CIPHERS || password.isEmpty()) return null

        val (host, port) = splitHostPort(endpoint) ?: return null
        if (!usableHost(host) || port !in 1..65535) return null

        val prefix = query.split('&').any { it.startsWith("prefix=", ignoreCase = true) && it.length > 7 }
        build(method, password, host, port, name, sourceId, now, prefix)
    }.getOrNull()

    /**
     * The body an `ssconf://` key's address answered with: one `ss://` link, or Outline's JSON
     * (`server`, `server_port`, `password`, `method`, optional `prefix`).
     */
    fun parseSsconfBody(body: String, keyUrl: String, now: Long, name: String? = null): OutlineServer? {
        val text = body.trim()
        val s = if (text.startsWith("{")) runCatching {
            val o = JSONObject(text)
            val method = o.optString("method").lowercase()
            val password = o.optString("password")
            val host = o.optString("server").trim('[', ']')
            val port = o.optInt("server_port", o.optString("server_port").toIntOrNull() ?: 0)
            if (method !in CIPHERS || password.isEmpty() || !usableHost(host) || port !in 1..65535) null
            else build(method, password, host, port, name ?: host, OutlineServer.USER_SOURCE, now, o.optString("prefix").isNotEmpty())
        }.getOrNull()
        else text.lines().map { it.trim() }.firstOrNull { it.startsWith("ss://", ignoreCase = true) }?.let { parse(it, OutlineServer.USER_SOURCE, now) }
        return s?.copy(ssconf = keyUrl, name = name?.takeIf { it.isNotBlank() } ?: s.name)
    }

    // ---------------------------------------------------------------- Shadowmere

    data class Page(val batch: Batch, val pages: Int, val count: Int)

    /**
     * One page of Shadowmere's `/api/proxies/?is_active=true`: `{count, total_pages, results: [{url,
     * location, location_country_code, last_checked, times_checked, times_check_succeeded, ...}]}`.
     * Each key is named `shadowmere · City, Country` -- the tag travels with the key when it is copied
     * or shared -- and keeps when Shadowmere last checked it and how often it passed.
     */
    fun parseShadowmerePage(json: String, sourceId: String, now: Long): Page? = runCatching {
        val o = JSONObject(json)
        val results = o.getJSONArray("results")
        var skipped = 0
        val out = LinkedHashMap<String, OutlineServer>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            if (!r.optBoolean("is_active", true)) continue
            val s = parse(r.optString("url"), sourceId, now)
            if (s == null) { skipped++; continue }
            val cc = r.optString("location_country_code").uppercase().takeIf { it in COUNTRIES }
            val checked = r.optInt("times_checked")
            val passed = r.optInt("times_check_succeeded")
            out.putIfAbsent(s.id, renamed(s, tagged(sourceId, placeName(r.optString("location")), s)).copy(
                country = cc ?: s.country,
                checkedAt = isoTime(r.optString("last_checked")),
                uptime = if (checked > 0) (passed * 100 / checked).coerceIn(0, 100) else null,
            ))
        }
        Page(Batch(out.values.toList(), skipped), o.optInt("total_pages", 1).coerceAtLeast(1), o.optInt("count", out.size))
    }.getOrNull()

    /**
     * Shadowmere's `/api/sub/`: the same keys as one array of `{server, server_port, password, method,
     * plugin, remarks}` (remarks: "🇦🇹 Vienna, 9, Austria"), without check times.
     */
    fun parseSip008(json: String, sourceId: String, now: Long): Batch? = runCatching {
        val a = org.json.JSONArray(json.trim())
        var skipped = 0
        val out = LinkedHashMap<String, OutlineServer>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val method = o.optString("method").lowercase()
            val password = o.optString("password")
            val host = o.optString("server").trim('[', ']')
            val port = o.optInt("server_port")
            val plugin = o.optString("plugin").takeIf { it != "null" }.orEmpty()
            if (plugin.isNotEmpty() || method !in CIPHERS || password.isEmpty() || !usableHost(host) || port !in 1..65535) { skipped++; continue }
            val remarks = o.optString("remarks").takeIf { it != "null" }.orEmpty()
            val s = build(method, password, host, port, remarks, sourceId, now, false)
            out.putIfAbsent(s.id, renamed(s, tagged(sourceId, placeName(remarks), s)))
        }
        Batch(out.values.toList(), skipped)
    }.getOrNull()

    /**
     * "Kharkiv, 63, Ukraine" -> "Kharkiv, Ukraine": the city and the country, without the region
     * codes between them ("NH", "ENG", "63") or a leading flag.
     */
    fun placeName(location: String): String {
        val noFlag = buildString { location.codePoints().forEach { if (it !in 0x1F1E6..0x1F1FF) appendCodePoint(it) } }
        val parts = noFlag.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return parts.firstOrNull().orEmpty()
        fun code(p: String) = p.all { it.isDigit() } || (p.length <= 3 && p.all { it.isUpperCase() || it.isDigit() })
        val city = parts.first().takeIf { !code(it) }
        return listOfNotNull(city, parts.last()).joinToString(", ")
    }

    private fun tagged(tag: String, place: String, s: OutlineServer) = "$tag · " + place.ifBlank { "${s.host}:${s.port}" }

    /** The same key under another name: the link's own name (after `#`) goes with it. */
    fun renamed(s: OutlineServer, name: String): OutlineServer =
        s.copy(name = name, uri = s.uri.substringBefore('#') + "#" + urlEncode(name))

    /** "2026-10-25T19:00:15.276201Z" -> epoch milliseconds; 0 when it is not that shape. */
    fun isoTime(text: String): Long = runCatching {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.parse(text.trim().take(19))!!.time
    }.getOrDefault(0L)

    /** `ssconf://host/path#name` -> the https address it stands for, and the name. */
    fun ssconfUrl(key: String): Pair<String, String?>? {
        val k = key.trim()
        val rest = when {
            k.startsWith("ssconf://", ignoreCase = true) -> k.substring(9)
            else -> return null
        }
        val name = rest.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let { urlDecode(it) }
        val addr = rest.substringBefore('#')
        if (addr.isBlank()) return null
        return "https://$addr" to name
    }

    /** Every `ss://` or `ssconf://` key in a pasted text (a message, a list, a page). */
    fun keysIn(text: String): List<String> =
        Regex("(?i)(ssconf|ss)://[^\\s\"'<>]+").findAll(text).map { it.value.trimEnd(',', '.', ')', ']') }.distinct().toList()

    private fun build(method: String, password: String, host: String, port: Int, name: String, sourceId: String, now: Long, prefix: Boolean): OutlineServer {
        val hostPart = if (host.contains(':')) "[$host]" else host
        val userInfo = encodeB64Url("$method:$password")
        val shown = name.ifBlank { "$host:$port" }
        val uri = "ss://$userInfo@$hostPart:$port#" + urlEncode(shown)
        return OutlineServer(
            id = idOf(method, password, host, port), uri = uri, name = shown, host = host.lowercase(), port = port,
            method = method, country = countryFromName(shown), sourceId = sourceId, addedAt = now, prefix = prefix,
        )
    }

    /** The same server in two lists is one row: identity is what the server checks, not the name. */
    fun idOf(method: String, password: String, host: String, port: Int): String {
        val d = MessageDigest.getInstance("SHA-1").digest("$method|$password|${host.lowercase()}|$port".toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun splitHostPort(endpoint: String): Pair<String, Int>? {
        val e = endpoint.trim()
        if (e.startsWith("[")) {
            val close = e.indexOf(']')
            if (close < 0) return null
            return e.substring(1, close) to (e.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null)
        }
        val sep = e.lastIndexOf(':')
        if (sep <= 0) return null
        return e.substring(0, sep) to (e.substring(sep + 1).toIntOrNull() ?: return null)
    }

    /** Not a server anywhere: private, loopback, unspecified, or Iran's block page. */
    fun usableHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty() || h == "localhost" || h.contains(' ')) return false
        val v4 = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return true }
        if (v4 != null) {
            val (a, b) = v4[0] to v4[1]
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false
            if (a == 169 && b == 254) return false
            if (a == 172 && b in 16..31) return false
            if (a == 192 && b == 168) return false
            if (a == 100 && b in 64..127) return false
        }
        if (h == "::" || h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return false
        return true
    }

    /** A flag (🇩🇪) or a country code at a word boundary (`DE-1`, `[US]`, `| NL |`) in a server's name. */
    fun countryFromName(name: String): String? {
        val cps = name.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            val a = cps[i]; val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                val cc = "${'A' + (a - 0x1F1E6)}${'A' + (b - 0x1F1E6)}"
                if (cc in COUNTRIES) return cc
            }
        }
        val m = Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])").findAll(name).map { it.groupValues[1] }.firstOrNull { it in COUNTRIES }
        return m
    }

    private val COUNTRIES: Set<String> = java.util.Locale.getISOCountries().toHashSet()

    // ---------------------------------------------------------------- base64 without android.util

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decodeB64(raw: String): String? {
        val s = raw.trim().replace('-', '+').replace('_', '/').replace(Regex("\\s"), "").trimEnd('=')
        if (s.isEmpty() || s.any { it !in ALPHABET }) return null
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in s) {
            buf = (buf shl 6) or ALPHABET.indexOf(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xFF)
            }
        }
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.UTF_8)
        // A blob that is not text was not base64 of a key; refuse instead of returning garbage.
        return if (text.contains('�')) null else text
    }

    fun encodeB64Url(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
            if (i + 1 < bytes.size) sb.append(ALPHABET[(n shr 6) and 63])
            if (i + 2 < bytes.size) sb.append(ALPHABET[n and 63])
            i += 3
        }
        return sb.toString().replace('+', '-').replace('/', '_')
    }

    private fun urlDecode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
