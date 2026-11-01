package com.mlmvpn.scanner.engines.gateway

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The central Worker's data and rules — pure Kotlin (org.json and the JDK only), so all of it runs
 * as a plain JVM test. It is the Android half of the Windows app's `edge-gateway/manager.js`; the
 * two must agree on every byte of what they write to the account (docs/PANEL-REGISTRY.md, key `GW`).
 *
 * One Cloudflare account has ONE relay Worker (`cloudflare-worker/edge-gateway/`, shipped here under
 * assets/edge_gateway/). BPB is its own row; every other panel is one more id on the same Worker.
 */

/** A failure the user should read, in both languages. The screen picks one with `tr`. */
class GatewayException(val fa: String, val en: String, cause: Throwable? = null) : Exception(fa, cause)

object Gateway {
    /** The panel registry's key for the account's central Worker. */
    const val CODE = "GW"
    const val MAIN_MODULE = "worker.js"
    const val COMPAT_DATE = "2024-09-23"
    const val SCRIPT_SUFFIX = "-gw"
    const val MAX_FALLBACKS = 3
    /** The Proxy IP the BPB installer already uses: it reaches Cloudflare-hosted sites a relay cannot dial. */
    const val DEFAULT_FALLBACK = "bpb.yousef.isegaro.com"
    /** WebSocket early data (0-RTT): the first bytes ride in the upgrade request. The Worker reads it. */
    const val EARLY_DATA = 2560
    /** How long the Worker's resolved addresses are trusted before they are looked up again. */
    const val RESOLVE_TTL_MS = 6 * 3600 * 1000L
    /** How long a read of the shared record stands before the links ask the account again. */
    const val PULL_TTL_MS = 60 * 1000L
    /** Ports Cloudflare serves TLS on; 443 is the one that is always right. */
    val TLS_PORTS = listOf(443, 8443, 2053, 2083, 2087, 2096)

    /**
     * The panels this ONE Worker stands in for, in the order the Cloud screen lists them. BPB is the
     * Worker's own row; every other panel is an id of its own on the same Worker (it accepts up to 16).
     */
    val PANEL_TITLE: Map<String, String> = linkedMapOf(
        "BPB" to "BPB", "EDG" to "Edge", "ZEU" to "Zeus", "SPD" to "Spider", "NTR" to "Netra",
        "GZG" to "Gozargah", "NVA" to "Nova", "NHN" to "Nahan", "MLM" to "MLM",
    )
    val PANEL_CODES: List<String> = PANEL_TITLE.keys.filter { it != "BPB" }

    val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
    val WS_PATH_RE = Regex("^/[A-Za-z0-9._~\\-/]*$")
    val WORKER_HOST_RE = Regex("^[a-z0-9-]+\\.[a-z0-9-]+\\.workers\\.dev$")

    /**
     * The Worker's `UUID` variable: BPB's id first, then every connected panel's, then the people of
     * «کانفیگ استدیو» ([studio], already in their order) — comma separated, each once.
     */
    fun idsText(uuid: String, panels: Map<String, PanelId>, studio: List<String> = emptyList()): String =
        (listOf(uuid) + panels.values.map { it.uuid } + studio).filter { it.isNotBlank() }.distinct().joinToString(",")

    /** What the Worker's own code allowed before it said otherwise: `config.js` → `maxUuids: 16`. */
    const val DEFAULT_MAX_IDS = 16

    /**
     * How many ids the Worker accepts, read from the code it is deployed with (`config.js`:
     * `maxUuids`) rather than repeated here — so the one number lives in one place, on both apps, and
     * putting it back is one edit. The Worker silently keeps only the first that many it reads.
     */
    fun maxIdsOf(files: List<Pair<String, String>>): Int {
        val config = files.firstOrNull { it.first == "config.js" }?.second ?: return DEFAULT_MAX_IDS
        val n = Regex("maxUuids\\s*:\\s*(\\d{1,4})").find(config)?.groupValues?.get(1)?.toIntOrNull() ?: return DEFAULT_MAX_IDS
        return n.coerceIn(1, 1000)
    }

    /**
     * A short digest of the Studio ids one upload carried, written beside the record (`s.studio`).
     * The ids themselves are a secret the Worker never gives back; this is how a phone tells whether
     * the Worker still holds the Studio's people — another device that uploads without them (the
     * Windows app, an older phone) writes the record without it. Empty when there were none.
     */
    fun idsMarker(ids: List<String>): String {
        if (ids.isEmpty()) return ""
        val h = MessageDigest.getInstance("SHA-256").digest(ids.joinToString(",").toByteArray(Charsets.UTF_8))
        return h.joinToString("") { "%02x".format(it) }.take(16)
    }

    val MARK_RE = Regex("^[0-9a-f]{16}$")

    /**
     * A digest of exactly what gets uploaded: the module names and contents, sorted by name, with LF
     * line ends whatever the checkout did. The Windows app computes the same digest over the same
     * files, so «the deployed code is this build's code» reads the same on both.
     */
    fun codeHash(files: List<Pair<String, String>>): String {
        val h = MessageDigest.getInstance("SHA-256")
        for ((name, content) in files.sortedBy { it.first }) {
            h.update((name + "\u0000" + content.replace("\r\n", "\n") + "\u0000").toByteArray(Charsets.UTF_8))
        }
        return h.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    // ── addresses ────────────────────────────────────────────────────────────────────────

    private val IPV4 = Regex("^(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    /** A name Xray can dial: letters, digits, dots and dashes in dot-separated labels (an IP is not one). */
    private val DIAL_NAME = Regex("^(?=.{1,253}$)([A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?\\.)+[A-Za-z]{2,63}$")

    fun isIPv4(s: String) = IPV4.matches(s)

    fun isIPv6(s: String): Boolean {
        if (s.isEmpty() || !s.contains(':')) return false
        if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return false
        val dbl = s.indexOf("::")
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) return false
        val head = if (dbl >= 0) s.substring(0, dbl) else s
        val tail = if (dbl >= 0) s.substring(dbl + 2) else ""
        val groups = (if (head.isEmpty()) emptyList() else head.split(':')) + (if (tail.isEmpty()) emptyList() else tail.split(':'))
        var count = 0
        for ((i, g) in groups.withIndex()) {
            if (g.contains('.')) {
                if (i != groups.lastIndex || !isIPv4(g)) return false     // an embedded IPv4 may only close the address
                count += 2
            } else {
                if (g.isEmpty() || g.length > 4) return false
                count += 1
            }
        }
        return if (dbl >= 0) count <= 7 else count == 8
    }

    data class Classified(val address: String, val kind: String)

    /** "a", "[v6]" or "v6" → its address and kind (`ipv4` | `ipv6` | `domain`); null when it is none of them. */
    fun classifyAddress(raw: String?): Classified? {
        val t = (raw ?: "").trim().removePrefix("[").removeSuffix("]")
        if (t.isEmpty()) return null
        if (isIPv4(t)) return Classified(t, "ipv4")
        if (isIPv6(t)) return Classified(t, "ipv6")
        if (DIAL_NAME.matches(t)) return Classified(t.lowercase(), "domain")
        return null
    }

    /**
     * The «clean IPs / domains» the user typed — one per line, comma or space — as a list of valid,
     * distinct addresses (at most [max]). Throws on an entry that is none of IPv4, IPv6 or a domain.
     */
    fun parseAddressList(text: String?, max: Int = 30): List<String> {
        val out = LinkedHashSet<String>()
        for (part in (text ?: "").split(Regex("[\\s,;]+"))) {
            if (part.isEmpty()) continue
            val c = classifyAddress(part) ?: throw GatewayException("«$part» نه آی‌پی است و نه دامنه.", "\"$part\" is neither an IP address nor a domain.")
            out.add(c.address)
            if (out.size > max) throw GatewayException("حداکثر $max آی‌پی یا دامنه.", "At most $max IPs or domains.")
        }
        return out.toList()
    }

    /** "a.example, b.example:8443" → "a.example,b.example:8443"; throws on a malformed entry. */
    fun normalizeFallback(text: String?): String {
        fun bad(raw: String) = GatewayException(
            "نشانی پشتیبان درست نیست: «$raw» — به شکل host یا host:port بنویسید.",
            "The backup address is not valid: \"$raw\" — write it as host or host:port.",
        )
        val bracketed = Regex("^\\[([0-9a-fA-F:.]+)](?::(\\d{1,5}))?$")
        val plain = Regex("^([^:]+)(?::(\\d{1,5}))?$")
        val out = mutableListOf<String>()
        for (raw in (text ?: "").split(Regex("[,\\s]+"))) {
            if (raw.isEmpty()) continue
            val host: String
            var port: Int? = null
            val b = bracketed.find(raw)
            if (b != null) {
                host = b.groupValues[1]
                b.groupValues[2].takeIf { it.isNotEmpty() }?.let { port = it.toInt() }
            } else if (raw.count { it == ':' } > 1) {
                if (!Regex("^[0-9a-fA-F:.]+$").matches(raw)) throw bad(raw)
                host = raw
            } else {
                val m = plain.find(raw) ?: throw bad(raw)
                host = m.groupValues[1]
                m.groupValues[2].takeIf { it.isNotEmpty() }?.let { port = it.toInt() }
            }
            val p = port
            if (p != null && (p < 1 || p > 65535)) throw bad(raw)
            if (host.length > 253 || !Regex("^[A-Za-z0-9._:-]+$").matches(host)) throw bad(raw)
            val shown = if (host.contains(':')) "[$host]" else host
            out.add(if (p != null) "$shown:$p" else shown)
        }
        if (out.size > MAX_FALLBACKS) throw GatewayException("حداکثر $MAX_FALLBACKS نشانی پشتیبان.", "At most $MAX_FALLBACKS backup addresses.")
        return out.joinToString(",")
    }
}

// ── settings ─────────────────────────────────────────────────────────────────────────────

/**
 * What BPB's own settings default to (cleanIPs ["www.speedtest.net"], ports [443], IPv6 off, and the
 * Worker's resolved addresses always listed). They only shape the LINKS the app builds — nothing here
 * is sent to the Worker, which is why changing them needs no redeploy.
 */
data class GatewaySettings(
    val cleanIPs: List<String> = listOf("www.speedtest.net"),
    val ports: List<Int> = listOf(443),
    val ipv6: Boolean = false,
    val withResolved: Boolean = true,
    val methods: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("cleanIPs", JSONArray(cleanIPs)).put("ports", JSONArray(ports))
        .put("ipv6", ipv6).put("withResolved", withResolved).put("methods", methods)

    companion object {
        val DEFAULT = GatewaySettings()

        /** Settings as another device wrote them: anything that does not validate falls back to the defaults. */
        fun fromJson(o: JSONObject?): GatewaySettings = try {
            normalize(patchOf(o), DEFAULT)
        } catch (e: GatewayException) {
            DEFAULT
        }

        fun patchOf(o: JSONObject?): SettingsPatch {
            if (o == null) return SettingsPatch()
            return SettingsPatch(
                cleanIPs = o.optJSONArray("cleanIPs")?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } },
                ports = o.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.optInt(it) } },
                ipv6 = if (o.has("ipv6")) o.optBoolean("ipv6") else null,
                withResolved = if (o.has("withResolved")) o.optBoolean("withResolved") else null,
                methods = if (o.has("methods")) o.optBoolean("methods") else null,
            )
        }

        /** The user's settings, validated, over [base]. Throws a [GatewayException] before anything is written. */
        fun normalize(patch: SettingsPatch, base: GatewaySettings = DEFAULT): GatewaySettings {
            var clean = base.cleanIPs
            var ports = base.ports
            patch.cleanIPs?.let { clean = Gateway.parseAddressList(it) }
            patch.ports?.let { raw ->
                val ok = raw.distinct().filter { it in Gateway.TLS_PORTS }
                if (ok.isEmpty()) throw GatewayException("دست‌کم یکی از درگاه‌های TLS را انتخاب کنید.", "Choose at least one TLS port.")
                ports = ok
            }
            return GatewaySettings(clean, ports, patch.ipv6 ?: base.ipv6, patch.withResolved ?: base.withResolved, patch.methods ?: base.methods)
        }
    }
}

/** A change to the settings; `null` leaves a field as it is. [cleanIPs] is the text the user typed. */
data class SettingsPatch(
    val cleanIPs: String? = null,
    val ports: List<Int>? = null,
    val ipv6: Boolean? = null,
    val withResolved: Boolean? = null,
    val methods: Boolean? = null,
)

// ── the record ───────────────────────────────────────────────────────────────────────────

/** One panel's id on the Worker, and what this device has taken with it (counts are local, never shared). */
data class PanelId(val uuid: String, val at: Long = 0L, val fetchedAt: Long = 0L, val configCount: Int = 0)

/** The addresses the Worker's name resolves to, and when they were looked up. */
data class Resolved(val at: Long, val v4: List<String>, val v6: List<String>)

/**
 * This device's copy of the account's central Worker. The id ([uuid]) and path ([wsPath]) cannot be
 * read back from the Worker; they live here and in the account's registry (key `GW`).
 */
data class GatewayRecord(
    val accountId: String,
    val script: String,
    val host: String,
    val uuid: String,
    val wsPath: String,
    val fallback: String,
    val codeHash: String,
    /** When this side last changed the Worker or its record: «whichever side changed last wins». */
    val at: Long,
    val panels: Map<String, PanelId> = emptyMap(),
    val settings: GatewaySettings = GatewaySettings.DEFAULT,
    val fetchedAt: Long = 0L,
    val configCount: Int = 0,
    val resolved: Resolved? = null,
    val sharedAt: Long = 0L,
    val sharedBy: String = "",
    /** The last write to the account failed: this side holds a change the account has not heard of. */
    val unshared: Boolean = false,
    /** Another central Worker this device made before it found the account's own; left on Cloudflare. */
    val replaced: String? = null,
    /** [Gateway.idsMarker] of the Studio ids the last upload carried, as the account's record says. */
    val studioMark: String = "",
) {
    val url: String get() = "https://$host"

    /** The id of [panel] on the Worker: BPB's own, or the one given when that panel was connected. */
    fun idOf(panel: String): String? = if (panel == "BPB") uuid else panels[panel]?.uuid

    fun toJson(): JSONObject {
        val p = JSONObject()
        for ((code, id) in panels) p.put(code, JSONObject().put("uuid", id.uuid).put("at", id.at).put("fetchedAt", id.fetchedAt).put("configCount", id.configCount))
        val o = JSONObject().put("accountId", accountId).put("script", script).put("host", host).put("uuid", uuid).put("wsPath", wsPath)
            .put("fallback", fallback).put("codeHash", codeHash).put("at", at).put("panels", p).put("settings", settings.toJson())
            .put("fetchedAt", fetchedAt).put("configCount", configCount).put("sharedAt", sharedAt).put("sharedBy", sharedBy)
            .put("unshared", unshared)
        resolved?.let { o.put("resolved", JSONObject().put("at", it.at).put("v4", JSONArray(it.v4)).put("v6", JSONArray(it.v6))) }
        replaced?.let { o.put("replaced", it) }
        if (studioMark.isNotEmpty()) o.put("studio", studioMark)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): GatewayRecord? {
            val host = o.optString("host")
            val uuid = o.optString("uuid")
            val wsPath = o.optString("wsPath")
            if (!Gateway.WORKER_HOST_RE.matches(host.lowercase()) || !Gateway.UUID_RE.matches(uuid) || !Gateway.WS_PATH_RE.matches(wsPath)) return null
            val panels = LinkedHashMap<String, PanelId>()
            o.optJSONObject("panels")?.let { p ->
                for (code in p.keys()) {
                    val e = p.optJSONObject(code) ?: continue
                    if (code in Gateway.PANEL_CODES && Gateway.UUID_RE.matches(e.optString("uuid"))) {
                        panels[code] = PanelId(e.optString("uuid"), e.optLong("at"), e.optLong("fetchedAt"), e.optInt("configCount"))
                    }
                }
            }
            val r = o.optJSONObject("resolved")?.let { x ->
                Resolved(x.optLong("at"), x.optJSONArray("v4")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                    x.optJSONArray("v6")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())
            }
            return GatewayRecord(
                accountId = o.optString("accountId"), script = o.optString("script"), host = host.lowercase(), uuid = uuid, wsPath = wsPath,
                fallback = o.optString("fallback"), codeHash = o.optString("codeHash"), at = o.optLong("at"), panels = panels,
                settings = GatewaySettings.fromJson(o.optJSONObject("settings")), fetchedAt = o.optLong("fetchedAt"),
                configCount = o.optInt("configCount"), resolved = r, sharedAt = o.optLong("sharedAt"), sharedBy = o.optString("sharedBy"),
                unshared = o.optBoolean("unshared"), replaced = o.optString("replaced").takeIf { it.isNotBlank() },
                studioMark = o.optString("studio").takeIf { Gateway.MARK_RE.matches(it) }.orEmpty(),
            ).takeIf { it.script.isNotBlank() && it.host.substringBefore('.') == it.script.lowercase() }
        }
    }
}

// ── the shared record (registry key GW) ──────────────────────────────────────────────────

/** What the account's registry says: the fields of a record both apps agree on, validated. */
data class SharedGateway(
    val script: String,
    val host: String,
    val uuid: String,
    val wsPath: String,
    val fallback: String,
    val panels: Map<String, PanelId>,
    val settings: GatewaySettings,
    val codeHash: String,
    val at: Long,
    val by: String,
    /** `s.studio`: [Gateway.idsMarker] of the Studio ids the last upload carried. Only phones write it. */
    val studioMark: String = "",
) {
    val url: String get() = "https://$host"
}

/** What a pull decided about this device's copy. */
sealed interface PullPlan {
    /** This device has none: the account's Worker is used. */
    data object Adopt : PullPlan
    /** This device made another Worker of its own: the account's wins, [own] is left on Cloudflare. */
    data class Replace(val own: String) : PullPlan
    /** This side changed last and the account never heard of it: publish it. */
    data object Push : PullPlan
    data object Same : PullPlan
    /** The account's record is newer: take it in. */
    data object Update : PullPlan
}

object GatewayShared {

    /** The `s` object written under key `GW` — the same JSON the Windows app writes. */
    fun toS(rec: GatewayRecord): JSONObject {
        val panels = JSONObject()
        for ((code, id) in rec.panels) panels.put(code, JSONObject().put("uuid", id.uuid).put("at", id.at))
        val s = JSONObject().put("uuid", rec.uuid).put("wsPath", rec.wsPath).put("fallback", rec.fallback)
            .put("panels", panels).put("settings", rec.settings.toJson()).put("codeHash", rec.codeHash)
        // Only when there is one: a record with no Studio people reads exactly as the Windows app writes it.
        if (rec.studioMark.isNotEmpty()) s.put("studio", rec.studioMark)
        return s
    }

    /**
     * The registry value → its fields, or null when it is not a record this build can trust: the id,
     * path and address are validated exactly as the link builder needs them; a panel code this build
     * does not know (a newer app may have added one) is dropped; settings or a fallback that do not
     * validate fall back to the defaults rather than refusing the whole record.
     */
    fun parse(script: String, url: String, s: JSONObject?, by: String, at: Long): SharedGateway? = try {
        if (script.isBlank() || s == null) null else {
            val uuid = s.optString("uuid")
            val wsPath = s.optString("wsPath")
            val host = java.net.URI(url).host?.lowercase().orEmpty()
            if (!Gateway.UUID_RE.matches(uuid) || !Gateway.WS_PATH_RE.matches(wsPath) ||
                !Gateway.WORKER_HOST_RE.matches(host) || host.substringBefore('.') != script.lowercase()
            ) null else {
                val panels = LinkedHashMap<String, PanelId>()
                s.optJSONObject("panels")?.let { p ->
                    for (code in p.keys()) {
                        val e = p.optJSONObject(code) ?: continue
                        if (code in Gateway.PANEL_CODES && Gateway.UUID_RE.matches(e.optString("uuid"))) panels[code] = PanelId(e.optString("uuid"), e.optLong("at"))
                    }
                }
                val fallback = try { Gateway.normalizeFallback(s.optString("fallback")) } catch (e: GatewayException) { "" }
                SharedGateway(script, host, uuid, wsPath, fallback, panels, GatewaySettings.fromJson(s.optJSONObject("settings")),
                    s.optString("codeHash"), at, by, s.optString("studio").takeIf { Gateway.MARK_RE.matches(it) }.orEmpty())
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun key(script: String, uuid: String, wsPath: String, fallback: String, codeHash: String, panels: Map<String, PanelId>, settings: GatewaySettings): String =
        listOf(script, uuid, wsPath, fallback, codeHash, panels.entries.map { it.key to it.value.uuid }.sortedBy { it.first }, settings.toJson().toString()).toString()

    /** The parts of two records that make the links and the Worker differ — what «changed» means. */
    fun keyOf(r: GatewayRecord) = key(r.script, r.uuid, r.wsPath, r.fallback, r.codeHash, r.panels, r.settings)
    fun keyOf(s: SharedGateway) = key(s.script, s.uuid, s.wsPath, s.fallback, s.codeHash, s.panels, s.settings)

    /**
     * What to do about the account's record, given this device's copy:
     *  - none here → adopt it; another Worker here → the account's wins (one central Worker per account);
     *  - the same Worker → whichever side changed last wins (`at`); this side newer means it was never
     *    published, so it is published now. A tie only goes that way when this side KNOWS it has an
     *    unpublished change.
     */
    fun plan(local: GatewayRecord?, shared: SharedGateway): PullPlan {
        if (local == null) return PullPlan.Adopt
        if (local.script != shared.script) return PullPlan.Replace(local.script)
        val differs = keyOf(local) != keyOf(shared)
        if (!differs) return PullPlan.Same
        val mineNewer = local.at > shared.at || (local.unshared && local.at >= shared.at)
        return if (mineNewer) PullPlan.Push else PullPlan.Update
    }

    /** This device's copy after taking [shared] in. Counters that are only this device's survive when the ids did. */
    fun adopt(accountId: String, local: GatewayRecord?, shared: SharedGateway, replaced: String?, now: Long): GatewayRecord {
        val keep = local != null && local.script == shared.script && local.uuid == shared.uuid && local.wsPath == shared.wsPath
        val panels = LinkedHashMap<String, PanelId>()
        for ((code, p) in shared.panels) {
            val mine = if (keep) local?.panels?.get(code)?.takeIf { it.uuid == p.uuid } else null
            panels[code] = p.copy(fetchedAt = mine?.fetchedAt ?: 0L, configCount = mine?.configCount ?: 0)
        }
        return GatewayRecord(
            accountId = accountId, script = shared.script, host = shared.host, uuid = shared.uuid, wsPath = shared.wsPath,
            fallback = shared.fallback, codeHash = shared.codeHash, at = shared.at.takeIf { it > 0 } ?: now, panels = panels,
            settings = shared.settings,
            fetchedAt = if (keep) local?.fetchedAt ?: 0L else 0L, configCount = if (keep) local?.configCount ?: 0 else 0,
            resolved = if (keep) local?.resolved else null,
            sharedAt = now, sharedBy = shared.by, unshared = false, replaced = replaced,
            studioMark = shared.studioMark,
        )
    }
}
