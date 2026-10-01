package com.mlmvpn.scanner.engines.amnezia

import com.mlmvpn.scanner.engines.flux.core.country.CountryHint
import com.mlmvpn.scanner.engines.flux.core.parse.FluxLinkParser
import com.mlmvpn.scanner.quick.GeoLabel
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Turns whatever a source or the user hands over into servers: link lists (plain or base64),
 * a single `.conf`, or a zip of `.conf` files. Three kinds are kept -- Hysteria2, `wireguard://`
 * links and WireGuard `.conf` files; everything else in a mixed list is skipped.
 *
 * Hysteria2 goes through FLUX's strict reader, with two differences that suit a list the user
 * browses rather than one an engine races blind:
 *  - `insecure=1` is dropped rather than refused. The core verifies the certificate, so a server
 *    with a real one works and one with a self-signed one fails its delay test -- the test decides,
 *    and nothing ever connects with checks off.
 *  - port hopping (`mport`) is dropped too; the server's main port still answers.
 */
object AmzParser {

    data class Batch(val servers: List<AmzServer>, val skipped: Int)

    /** Links, base64 links, or a `.conf`: whichever [text] turns out to be. */
    fun parseText(text: String, sourceId: String, now: Long, sourceCountry: String? = null, fileName: String = ""): Batch {
        val trimmed = text.trim().trim('﻿')
        if (trimmed.contains("[Interface]", ignoreCase = true) && trimmed.contains("[Peer]", ignoreCase = true)) {
            val s = fromConf(trimmed, fileName, sourceId, now, sourceCountry)
            return Batch(listOfNotNull(s), if (s == null) 1 else 0)
        }
        val body = FluxLinkParser.decodeBodyIfBase64(trimmed)
        val out = LinkedHashMap<String, AmzServer>()
        var skipped = 0
        for (line in body.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("//")) continue
            val s = fromLink(l, sourceId, now, sourceCountry)
            if (s == null) { if (l.contains("://")) skipped++; continue }
            out.putIfAbsent(s.id, s)
        }
        return Batch(out.values.toList(), skipped)
    }

    /** Every `.conf` (and link list) inside a zip. */
    fun parseZip(bytes: ByteArray, sourceId: String, now: Long, sourceCountry: String? = null): Batch {
        val out = LinkedHashMap<String, AmzServer>()
        var skipped = 0
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var total = 0L
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.substringAfterLast('/')
                    if (!name.endsWith(".conf", true) && !name.endsWith(".txt", true)) continue
                    val data = zip.readBytes()
                    total += data.size
                    // A zip bomb is not a server list.
                    if (data.size > MAX_ENTRY || total > MAX_ZIP_TOTAL) break
                    val b = parseText(String(data, Charsets.UTF_8), sourceId, now, sourceCountry, name.substringBeforeLast('.'))
                    b.servers.forEach { out.putIfAbsent(it.id, it) }
                    skipped += b.skipped
                }
            }
        }
        return Batch(out.values.toList(), skipped)
    }

    fun fromLink(link: String, sourceId: String, now: Long, sourceCountry: String? = null): AmzServer? {
        val scheme = link.substringBefore("://", "").lowercase()
        return when (scheme) {
            "hysteria2", "hy2" -> fromHy2(link, sourceId, now, sourceCountry)
            "wireguard", "wg" -> {
                val (cfg, name) = WgConfig.parseUri(link) ?: return null
                fromWg(cfg, name, sourceId, now, sourceCountry)
            }
            else -> null
        }
    }

    fun fromConf(text: String, fileName: String, sourceId: String, now: Long, sourceCountry: String? = null): AmzServer? {
        val cfg = WgConfig.parseConf(text) ?: return null
        return fromWg(cfg, fileName, sourceId, now, sourceCountry)
    }

    private fun fromWg(cfg: WgConfig, name: String, sourceId: String, now: Long, sourceCountry: String?): AmzServer {
        val id = "w" + sha(listOf(cfg.endpointHost, cfg.endpointPort, cfg.publicKey, cfg.privateKey).joinToString("|")).take(19)
        val label = cleanName(name)
        val (cc, from) = country(label, sourceCountry)
        return AmzServer(
            id = id, kind = if (cfg.hasAwg) AmzKind.AWG else AmzKind.WG, raw = cfg.toConf(),
            name = label.ifEmpty { if (cfg.warp) "WARP" else cfg.endpointHost },
            host = cfg.endpointHost, port = cfg.endpointPort,
            country = cc, countryFrom = from, sourceId = sourceId, addedAt = now,
            warp = cfg.warp, needsAwgCore = cfg.needsAwgCore,
        )
    }

    private fun fromHy2(link: String, sourceId: String, now: Long, sourceCountry: String?): AmzServer? {
        val clean = sanitizeHy2(link)
        val node = (FluxLinkParser.parse(clean, sourceId) as? FluxLinkParser.Result.Ok)?.node ?: return null
        val label = cleanName(node.label)
        val (cc, from) = country(label, sourceCountry)
        return AmzServer(
            id = "h" + node.id.take(19), kind = AmzKind.HY2, raw = clean,
            name = label.ifEmpty { node.server }, host = node.server, port = node.port,
            country = cc, countryFrom = from, sourceId = sourceId, addedAt = now,
        )
    }

    /** Drops `insecure`/`allowInsecure` (the core checks certificates) and port hopping. */
    fun sanitizeHy2(link: String): String {
        val hash = link.indexOf('#')
        val frag = if (hash >= 0) link.substring(hash) else ""
        val noFrag = if (hash >= 0) link.substring(0, hash) else link
        val qm = noFrag.indexOf('?')
        if (qm < 0) return link.trim()
        val kept = noFrag.substring(qm + 1).replace("&amp;", "&").split('&').filter { kv ->
            val k = kv.substringBefore('=').lowercase()
            kv.isNotEmpty() && k != "insecure" && k != "allowinsecure" && k != "mport" && k != "pinsha256"
        }
        val base = noFrag.substring(0, qm)
        return (if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")) + frag
    }

    /**
     * The country a server's name claims, or the list's own country. A list that is ABOUT one
     * country beats a name; a name beats nothing. Either is replaced by a measured answer later.
     */
    fun country(label: String, sourceCountry: String?): Pair<String?, CountryFrom> {
        if (sourceCountry != null) return sourceCountry to CountryFrom.SOURCE
        CountryHint.of(label)?.let { return it to CountryFrom.LABEL }
        CJK.entries.firstOrNull { label.contains(it.key) }?.let { return it.value to CountryFrom.LABEL }
        // City and country words ("Frankfurt", "Tokyo") -- only on the readable part of the name.
        val words = label.replace(Regex("(?i)t\\.me/\\S+|@\\S+|https?://\\S+"), " ")
        GeoLabel.countryFromText(listOf(words))?.takeIf { it.source == "name" && words.length >= 4 }?.let {
            return it.code to CountryFrom.LABEL
        }
        return null to CountryFrom.NONE
    }

    /** Chinese names some collectors use instead of flags. */
    private val CJK = linkedMapOf(
        "美国" to "US", "香港" to "HK", "日本" to "JP", "新加坡" to "SG", "台湾" to "TW", "韩国" to "KR",
        "俄罗斯" to "RU", "德国" to "DE", "英国" to "GB", "法国" to "FR", "荷兰" to "NL", "加拿大" to "CA",
        "澳大利亚" to "AU", "土耳其" to "TR", "印度" to "IN", "越南" to "VN", "泰国" to "TH", "马来西亚" to "MY",
    )

    /** The name as a row shows it: no control characters, no endless channel ads. */
    fun cleanName(name: String): String =
        name.replace(Regex("[\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(64)

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val MAX_ENTRY = 256 * 1024
    private const val MAX_ZIP_TOTAL = 16L * 1024 * 1024
}
