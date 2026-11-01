package com.mlmvpn.scanner.data.studio.central

import com.mlmvpn.scanner.data.studio.domain.ExpiryMode
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.ResetPolicy
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.SubscriptionPolicy
import com.mlmvpn.scanner.data.studio.domain.UserStatus
import com.mlmvpn.scanner.engines.gateway.Gateway
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * «کانفیگ استدیو» on the account's central Worker — the data and its rules, pure Kotlin (org.json
 * and the JDK only), so all of it runs as a plain JVM test.
 *
 * The user's request (2026-10-18): «میخوام سیستم کانفیگ استدیو وورکرش مثل وورکرهای دیگه بشه که همه
 * چی روی گوشی کاربره و متصل بشه و وورکر مرکزی», with the rule «وورکر مرکزی رو تا جای ممکن به هیچ
 * عنوان تغییر نده». So the Worker stays the relay it is (`assets/edge_gateway`): it knows a list of
 * ids and nothing else. Everything a person IS — their name, their end date, whether they are on —
 * lives here, on the phone, and the only thing that reaches the Worker is the list of ids that may
 * pass right now.
 *
 * One Cloudflare account carries one [CentralDoc]. A copy is written to the account's own registry
 * (key [Central.CODE], docs/PANEL-REGISTRY.md) so a second phone sees the same people, and so a
 * device that uploads the central Worker for another reason (a panel connected, an update) keeps
 * their ids in the upload instead of dropping them.
 *
 * Volume, devices and IP limits are NOT here, and that is the design rather than a gap: they need a
 * counter on the Worker, and the Worker must not change.
 */
object Central {
    /** The registry key of the Studio's people on one account (the Windows app ignores unknown keys). */
    const val CODE = "CS"

    /** What turns an account id into the installation id of its central-Worker shard. */
    const val SUFFIX = "#gw"

    /** The shape of the shared record ([CentralDoc.toShared]). A newer one is read as far as it is understood. */
    const val SHARED_VERSION = 1

    /** The shape of this phone's own copy ([CentralDoc.toLocal]). */
    const val LOCAL_VERSION = 1

    const val DAY_MS = 86_400_000L

    /** Limits on what a person's record may carry, so a hostile registry value cannot grow without end. */
    const val MAX_NAME = 64
    const val MAX_NOTE = 1000
    const val MAX_TAGS = 16
    const val MAX_TAG = 32
    const val MAX_USERS = 5000
    const val MAX_PLANS = 200
    const val MAX_CONTACT = 300
    const val MAX_AUDIT = 200

    /** Under a device limit, apps that send no device id share one place (the operator's default) … */
    const val NOHWID_ALLOW = "allow"
    /** … or are refused the subscription. */
    const val NOHWID_BLOCK = "block"
    /** Device places one person may have: each is an id on a Worker that takes [maxIds] in all. */
    const val MAX_DEVICES = 10
    const val MAX_LOCATIONS = 12
    /** Proxies one location may switch between. */
    const val MAX_PROXIES = 8
    const val MAX_BANNED = 50
    /** How the sub Worker names a device: "h:" and 24 hex of its app's id, or "~" for an app that sends none. */
    val DEVICE_KEY_RE = Regex("^(h:[0-9a-f]{24}|~)$")

    /** A subscription's secret path: 22+ characters of base64url, never guessable. */
    val TOKEN_RE = Regex("^[A-Za-z0-9_-]{22,64}$")

    private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** 144 random bits as 24 base64url characters (by hand: java.util.Base64 is API 26, the app runs on 24). */
    fun newToken(): String {
        val b = ByteArray(18).also { java.security.SecureRandom().nextBytes(it) }
        val sb = StringBuilder(24)
        for (i in b.indices step 3) {
            val n = ((b[i].toInt() and 0xFF) shl 16) or ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i + 2].toInt() and 0xFF)
            sb.append(B64URL[(n shr 18) and 63]).append(B64URL[(n shr 12) and 63]).append(B64URL[(n shr 6) and 63]).append(B64URL[n and 63])
        }
        return sb.toString()
    }

    fun installationIdOf(accountId: String): String = accountId + SUFFIX
    fun isCentral(installationId: String?): Boolean = installationId?.endsWith(SUFFIX) == true
    fun accountIdOf(installationId: String): String = installationId.removeSuffix(SUFFIX)

    /**
     * How many people one central Worker can carry beside its own id and the panels connected to it.
     * [maxIds] is the Worker's own cap, read from the code it is deployed with ([Gateway.maxIdsOf]).
     */
    fun capacity(maxIds: Int, panelCount: Int): Int = (maxIds - 1 - panelCount).coerceAtLeast(0)

    /** A person's local id: "c" and 15 hex — never the id on the Worker, which is a secret. */
    fun newUserId(): String = "c" + java.util.UUID.randomUUID().toString().replace("-", "").take(15)

    /** The engine's own spelling of a creation time (SQLite CURRENT_TIMESTAMP, UTC), so lists sort as one. */
    fun sqlTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    internal fun cleanName(s: String?): String = (s ?: "").trim().replace(Regex("\\s+"), " ").take(MAX_NAME)

    internal fun cleanTags(tags: List<String>?): List<String> =
        tags.orEmpty().map { it.trim().take(MAX_TAG) }.filter { it.isNotEmpty() }.distinct().take(MAX_TAGS)

    internal fun isId(s: String?): Boolean = s != null && Gateway.UUID_RE.matches(s)
}

/**
 * One person.
 *
 * [gw] is the id the Worker knows them by — their whole access, so it never leaves this record and
 * the registry. [expiresAt] 0 is "no end". A deletion keeps the row as a tombstone ([deletedAt] > 0)
 * so a second phone learns that the person is gone rather than bringing them back.
 */
data class CentralUser(
    val id: String,
    val name: String,
    val gw: String,
    val enabled: Boolean = true,
    val note: String? = null,
    val tags: List<String> = emptyList(),
    val planId: String? = null,
    val expiresAt: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val deletedAt: Long = 0L,
    /** When the link was last renewed («لینک تازه»): the tie-break when two copies changed in the same millisecond. */
    val rotatedAt: Long = 0L,
    /** Where this person was moved from: the old installation and its user id, "installation|user". */
    val movedFrom: String? = null,
    /** The secret path of their subscription on the account's sub Worker; null until one is made. */
    val token: String? = null,
    /**
     * How many devices may use this person's access («محدودیت دستگاه»); 0 = no limit, and every
     * device shares [gw]. Under a limit each device place has an id of its own on the central
     * Worker ([slots]) -- the Worker itself turns away a device that has none -- and [gw] is not on it.
     */
    val deviceLimit: Int = 0,
    /** Under a limit, apps that send no device id: [Central.NOHWID_ALLOW] (they share one place) or [Central.NOHWID_BLOCK]. */
    val noHwid: String = Central.NOHWID_ALLOW,
    /** Under a limit: one central-Worker id per device place, [deviceLimit] of them, place 0 first. */
    val slots: List<String> = emptyList(),
    /** Exit countries through public proxies, after the central Worker («ساب چندلوکیشنی»). */
    val locations: List<CentralLocation> = emptyList(),
    /**
     * Devices the operator took off («حذف دستگاه»), by the key the sub Worker knows them by (a hash
     * of their app's device id, or "~" for apps that send none): refused rather than let back into
     * the place they left.
     */
    val banned: List<String> = emptyList(),
) {
    val isDeleted: Boolean get() = deletedAt > 0

    /** May pass the Worker at [now]: not deleted, switched on, and inside their term. */
    fun isLive(now: Long): Boolean = !isDeleted && enabled && (expiresAt <= 0 || expiresAt > now)

    fun isExpired(now: Long): Boolean = expiresAt in 1..now

    /** The ids this person takes on the central Worker while they may pass: a place each. */
    fun ids(): List<String> = if (deviceLimit > 0) slots else listOf(gw)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("gw", gw).put("on", enabled)
        .put("note", note ?: JSONObject.NULL).put("tags", JSONArray(tags)).put("plan", planId ?: JSONObject.NULL)
        .put("exp", expiresAt).put("created", createdAt).put("updated", updatedAt).put("deleted", deletedAt)
        .put("rotated", rotatedAt).put("from", movedFrom ?: JSONObject.NULL)
        .put("token", token ?: JSONObject.NULL).put("devices", deviceLimit).put("nohwid", noHwid)
        .put("slots", JSONArray(slots)).put("locs", JSONArray(locations.map { it.toJson() })).put("ban", JSONArray(banned))

    /**
     * What a tombstone keeps: enough to win a merge and to say who it was, nothing a person wrote --
     * and the subscription's token, so whichever phone sees the deletion first takes it down.
     */
    fun asTombstone(at: Long): CentralUser = CentralUser(
        id = id, name = name, gw = gw, enabled = false, createdAt = createdAt, updatedAt = at, deletedAt = at,
        rotatedAt = rotatedAt, movedFrom = movedFrom, token = token,
    )

    /** The domain shape every Config Studio screen already draws. */
    fun toStudioUser(installationId: String, now: Long): StudioUser = StudioUser(
        id = id,
        username = name,
        credential = gw,
        status = when {
            isDeleted -> UserStatus.DELETED
            !enabled -> UserStatus.DISABLED
            isExpired(now) -> UserStatus.EXPIRED
            else -> UserStatus.ACTIVE
        },
        note = note?.takeIf { it.isNotBlank() },
        tags = tags,
        planId = planId,
        policy = SubscriptionPolicy(
            expiryMode = ExpiryMode.ABSOLUTE,
            expiresAt = expiresAt.takeIf { it > 0 },
        ),
        createdAt = Central.sqlTime(createdAt),
        updatedAt = updatedAt,
        deletedAt = deletedAt.takeIf { it > 0 },
        installationId = installationId,
    )

    companion object {
        /** A record as another device wrote it: anything that does not validate is dropped, never guessed. */
        fun fromJson(o: JSONObject?): CentralUser? {
            if (o == null) return null
            val id = o.optString("id").trim()
            val gw = o.optString("gw").trim().lowercase()
            if (id.isEmpty() || id.length > 40 || !Central.isId(gw)) return null
            val name = Central.cleanName(o.optString("name"))
            if (name.isEmpty()) return null
            val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            // A limit is only as good as its ids: a record whose places do not add up has none.
            val limit = o.optInt("devices").coerceIn(0, Central.MAX_DEVICES)
            val slots = o.optJSONArray("slots")?.let { a -> (0 until a.length()).map { a.optString(it).trim().lowercase() } }
                ?.takeIf { s -> s.size == limit && s.all { Central.isId(it) } && s.toSet().size == s.size }
            return CentralUser(
                id = id, name = name, gw = gw, enabled = o.optBoolean("on", true),
                note = o.optNullableString("note")?.take(Central.MAX_NOTE),
                tags = Central.cleanTags(tags),
                planId = o.optNullableString("plan")?.take(40),
                expiresAt = o.optLong("exp").coerceAtLeast(0),
                createdAt = o.optLong("created").coerceAtLeast(0),
                updatedAt = o.optLong("updated").coerceAtLeast(0),
                deletedAt = o.optLong("deleted").coerceAtLeast(0),
                rotatedAt = o.optLong("rotated").coerceAtLeast(0),
                movedFrom = o.optNullableString("from")?.take(120),
                token = o.optNullableString("token")?.takeIf { Central.TOKEN_RE.matches(it) },
                deviceLimit = if (slots != null) limit else 0,
                noHwid = if (o.optString("nohwid") == Central.NOHWID_BLOCK) Central.NOHWID_BLOCK else Central.NOHWID_ALLOW,
                slots = slots.orEmpty(),
                locations = o.optJSONArray("locs")?.let { a ->
                    (0 until minOf(a.length(), Central.MAX_LOCATIONS)).mapNotNull { CentralLocation.fromJson(a.optJSONObject(it)) }
                        .distinctBy { it.cc }
                }.orEmpty(),
                banned = o.optJSONArray("ban")?.let { a ->
                    (0 until minOf(a.length(), Central.MAX_BANNED)).map { a.optString(it) }.filter { Central.DEVICE_KEY_RE.matches(it) }.distinct()
                }.orEmpty(),
            )
        }

        /**
         * Which of two copies of the same person is the one to keep. The later change wins; on a tie
         * a deletion wins (a person both deleted and edited in the same instant stays deleted), then
         * the newer link, then the larger id — so two phones always pick the same one.
         */
        fun newer(a: CentralUser, b: CentralUser): CentralUser = when {
            a.updatedAt != b.updatedAt -> if (a.updatedAt > b.updatedAt) a else b
            a.isDeleted != b.isDeleted -> if (a.isDeleted) a else b
            a.rotatedAt != b.rotatedAt -> if (a.rotatedAt > b.rotatedAt) a else b
            else -> if (a.gw >= b.gw) a else b
        }
    }
}

/**
 * One public proxy a location leaves through, as it was last measured from THIS account's central
 * Worker ([checkedAt] 0 = never). [kind] is what both client families can speak to it: socks5 or http.
 */
data class ExitProxy(
    val ip: String,
    val port: Int,
    val kind: String,
    /** Where Cloudflare's trace through it came out, at the last check. */
    val loc: String = "",
    val rttMs: Long? = null,
    val checkedAt: Long = 0L,
    /** The last check failed. */
    val dead: Boolean = false,
) {
    val key: String get() = "$ip:$port"

    fun toJson(): JSONObject = JSONObject().put("ip", ip).put("port", port).put("kind", kind).put("loc", loc)
        .put("rtt", rttMs ?: JSONObject.NULL).put("at", checkedAt).put("dead", dead)

    companion object {
        const val SOCKS5 = "socks5"
        const val HTTP = "http"
        private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

        fun valid(ip: String, port: Int, kind: String): Boolean = IPV4.matches(ip) && port in 1..65535 && (kind == SOCKS5 || kind == HTTP)

        fun fromJson(o: JSONObject?): ExitProxy? {
            if (o == null) return null
            val ip = o.optString("ip").trim()
            val port = o.optInt("port")
            val kind = o.optString("kind")
            if (!valid(ip, port, kind)) return null
            return ExitProxy(ip, port, kind, o.optString("loc").take(2).uppercase(Locale.US),
                if (o.isNull("rtt") || !o.has("rtt")) null else o.optLong("rtt"), o.optLong("at").coerceAtLeast(0), o.optBoolean("dead"))
        }
    }
}

/**
 * One exit country of a person's subscription: their own way through the central Worker, and from
 * there out through one of [proxies] -- the sites they open see that country. With several, the app
 * switches to one that answers when another dies; [fixed] keeps exactly one, for a constant exit IP.
 */
data class CentralLocation(val cc: String, val proxies: List<ExitProxy>, val fixed: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("cc", cc).put("fixed", fixed).put("px", JSONArray(proxies.map { it.toJson() }))

    companion object {
        private val CC = Regex("^[A-Z]{2}$")

        fun fromJson(o: JSONObject?): CentralLocation? {
            if (o == null) return null
            val cc = o.optString("cc").uppercase(Locale.US)
            if (!CC.matches(cc)) return null
            val px = o.optJSONArray("px")?.let { a -> (0 until minOf(a.length(), Central.MAX_PROXIES)).mapNotNull { ExitProxy.fromJson(a.optJSONObject(it)) } }
                .orEmpty().distinctBy { it.key }
            if (px.isEmpty()) return null
            val fixed = o.optBoolean("fixed")
            return CentralLocation(cc, if (fixed) px.take(1) else px, fixed)
        }
    }
}

/** What this phone last wrote of one person's subscription: from which version of them, under which token. */
data class SubPushed(val at: Long, val token: String?) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("t", token ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject?): SubPushed? = o?.let { SubPushed(it.optLong("at"), it.optNullableString("t")?.takeIf { t -> Central.TOKEN_RE.matches(t) }) }
    }
}

/**
 * The account's subscription Worker: where people's subscription links point, the KV it reads, and
 * [code], a digest of the code it was last uploaded with -- a newer app uploads its own over it.
 */
data class SubWorker(val script: String, val host: String, val kv: String, val code: String = "") {
    fun toJson(): JSONObject = JSONObject().put("script", script).put("host", host).put("kv", kv).put("code", code)

    /** The address a person's app is given. */
    fun urlOf(token: String): String = "https://$host/$token"

    companion object {
        private val NAME = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
        private val HOST = Regex("^[a-z0-9-]{1,63}\\.[a-z0-9-]{1,63}\\.workers\\.dev$")
        private val KV = Regex("^[0-9a-f]{32}$")

        fun fromJson(o: JSONObject?): SubWorker? {
            if (o == null) return null
            val s = SubWorker(o.optString("script"), o.optString("host").lowercase(Locale.US), o.optString("kv"), o.optString("code").take(32))
            return s.takeIf { NAME.matches(it.script) && HOST.matches(it.host) && KV.matches(it.kv) }
        }
    }
}

/** A «بسته», exactly as the fleet carries it — only its time reaches a central-Worker person. */
internal object PlanJson {
    fun toJson(p: Plan): JSONObject = JSONObject().put("id", p.id).put("name", p.name)
        .put("quota", p.quotaBytes ?: 0L).put("days", p.durationDays ?: 0)
        .put("mode", if (p.expiryMode == ExpiryMode.ON_FIRST_CONNECT) "on_first_connect" else "absolute")
        .put("daily", p.dailyQuotaBytes ?: 0L).put("reset", p.reset.name.lowercase())
        .put("devices", p.deviceLimit ?: 0).put("conns", p.connLimit ?: 0).put("ips", p.ipLimit ?: 0)
        .put("template", p.templateId ?: JSONObject.NULL).put("def", p.isDefault).put("arch", p.archived)
        .put("created", p.createdAt).put("updated", p.updatedAt)

    fun fromJson(o: JSONObject?): Plan? {
        if (o == null) return null
        val id = o.optString("id").trim()
        if (id.isEmpty() || id.length > 40) return null
        fun pos(key: String): Long? = o.optLong(key).takeIf { it > 0 }
        fun posInt(key: String): Int? = o.optInt(key).takeIf { it > 0 }
        return Plan(
            id = id,
            name = o.optString("name").trim().take(80),
            quotaBytes = pos("quota"),
            durationDays = posInt("days"),
            expiryMode = ExpiryMode.from(o.optString("mode")),
            dailyQuotaBytes = pos("daily"),
            reset = ResetPolicy.from(o.optString("reset")),
            deviceLimit = posInt("devices"),
            connLimit = posInt("conns"),
            ipLimit = posInt("ips"),
            templateId = o.optNullableString("template")?.take(40),
            isDefault = o.optBoolean("def"),
            archived = o.optBoolean("arch"),
            createdAt = o.optLong("created").coerceAtLeast(0),
            updatedAt = o.optLong("updated").coerceAtLeast(0),
        )
    }

    fun newer(a: Plan, b: Plan): Plan = when {
        a.updatedAt != b.updatedAt -> if (a.updatedAt > b.updatedAt) a else b
        a.archived != b.archived -> if (a.archived) a else b
        else -> if (a.name >= b.name) a else b
    }
}

/** One thing this phone did, kept on this phone only (the «سابقه» page reads it). */
data class CentralAudit(val ts: Long, val action: String, val target: String?, val summary: String?) {
    fun toJson(): JSONObject = JSONObject().put("ts", ts).put("action", action)
        .put("target", target ?: JSONObject.NULL).put("summary", summary ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject?): CentralAudit? {
            if (o == null) return null
            val action = o.optString("action").take(40)
            if (action.isEmpty()) return null
            return CentralAudit(o.optLong("ts"), action, o.optNullableString("target")?.take(80), o.optNullableString("summary")?.take(200))
        }
    }
}

/**
 * The people and «بسته»‌ها of ONE Cloudflare account, plus what this phone knows about the account's
 * central Worker: which ids it last uploaded ([appliedIds]). Whether the Worker still holds them is
 * read from the account's Worker record ([com.mlmvpn.scanner.engines.gateway.Gateway.idsMarker]).
 */
data class CentralDoc(
    /** The app's own account record id (`CloudAccount.id`). */
    val accountId: String,
    /** Cloudflare's id for the same account: one account carries one central Worker, so one record. */
    val cloudAccountId: String = "",
    val users: List<CentralUser> = emptyList(),
    val plans: List<Plan> = emptyList(),
    /** The operator's own words for the status message («راه تماس»). */
    val contact: String? = null,
    /** The central Worker these people's links point at. Empty until it is known. */
    val script: String = "",
    val host: String = "",
    /** The ids this phone last put on the Worker, in order, and when. */
    val appliedIds: List<String> = emptyList(),
    val appliedAt: Long = 0L,
    /** The account's copy of this record was last written (or read) at. */
    val sharedAt: Long = 0L,
    /** The last write to the account failed: this phone holds a change the account has not heard of. */
    val unshared: Boolean = false,
    /** Things this phone did, newest first. Never shared. */
    val audit: List<CentralAudit> = emptyList(),
    /** The account's subscription Worker, once one is installed. Shared: one per account, for every phone. */
    val sub: SubWorker? = null,
    /**
     * Person id → what this phone last wrote of their subscription to the sub Worker's KV: the
     * [CentralUser.updatedAt] it was written from and the token it was written under. Never shared:
     * each phone knows what it wrote; an older entry is a write still owed (and a changed token, an
     * old address still to take down).
     */
    val subPushed: Map<String, SubPushed> = emptyMap(),
    /** A digest of the Worker's ways in the subscriptions were last written from; a new one rewrites them all. */
    val subLinks: String = "",
) {
    val installationId: String get() = Central.installationIdOf(accountId)

    fun user(id: String): CentralUser? = users.firstOrNull { it.id == id }

    /** Everyone who is not a tombstone, oldest first. */
    val people: List<CentralUser> get() = users.filter { !it.isDeleted }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    /**
     * The ids that may pass the Worker at [now], in a stable order (oldest person first; a person's
     * device places in order), each once. This is what is uploaded — nothing else about a person
     * ever reaches Cloudflare's runtime.
     */
    fun liveIds(now: Long): List<String> = people.filter { it.isLive(now) }.flatMap { it.ids() }.distinct()

    /** How many places the people who may pass right now take on the Worker. */
    fun liveCount(now: Long): Int = liveIds(now).size

    /** The shared record's `s`: ids with their end, for a device that knows nothing else, then the rest. */
    fun toShared(now: Long): JSONObject {
        val ids = JSONArray()
        for (u in people.filter { it.isLive(now) }) {
            for (id in u.ids()) ids.put(JSONObject().put("id", id).put("until", u.expiresAt.coerceAtLeast(0)))
        }
        return JSONObject().put("v", Central.SHARED_VERSION).put("ids", ids)
            .put("users", JSONArray(users.map { it.toJson() }))
            .put("plans", JSONArray(plans.map { PlanJson.toJson(it) }))
            .put("contact", contact ?: JSONObject.NULL)
            .put("sub", sub?.toJson() ?: JSONObject.NULL)
    }

    fun toLocal(): JSONObject = toShared(0L).put("lv", Central.LOCAL_VERSION).put("accountId", accountId)
        .put("cloudAccountId", cloudAccountId)
        .put("script", script).put("host", host).put("applied", JSONArray(appliedIds)).put("appliedAt", appliedAt)
        .put("sharedAt", sharedAt).put("unshared", unshared)
        .put("audit", JSONArray(audit.map { it.toJson() }))
        .put("subPushed", JSONObject().apply { for ((k, v) in subPushed) put(k, v.toJson()) })
        .put("subLinks", subLinks)

    /** [entry] on top, the oldest falling off past [Central.MAX_AUDIT]. */
    fun withAudit(entry: CentralAudit): CentralDoc = copy(audit = (listOf(entry) + audit).take(Central.MAX_AUDIT))

    companion object {
        /** The users and plans of a shared `s` (or of this phone's own copy), validated. */
        internal fun parseUsers(s: JSONObject?): List<CentralUser> {
            val a = s?.optJSONArray("users") ?: return emptyList()
            val byId = LinkedHashMap<String, CentralUser>()
            for (i in 0 until minOf(a.length(), Central.MAX_USERS)) {
                val u = CentralUser.fromJson(a.optJSONObject(i)) ?: continue
                byId[u.id] = byId[u.id]?.let { CentralUser.newer(it, u) } ?: u
            }
            return byId.values.toList()
        }

        internal fun parsePlans(s: JSONObject?): List<Plan> {
            val a = s?.optJSONArray("plans") ?: return emptyList()
            val byId = LinkedHashMap<String, Plan>()
            for (i in 0 until minOf(a.length(), Central.MAX_PLANS)) {
                val p = PlanJson.fromJson(a.optJSONObject(i)) ?: continue
                byId[p.id] = byId[p.id]?.let { PlanJson.newer(it, p) } ?: p
            }
            return byId.values.toList()
        }

        fun fromLocal(o: JSONObject?): CentralDoc? {
            if (o == null) return null
            val accountId = o.optString("accountId").trim()
            if (accountId.isEmpty()) return null
            val applied = o.optJSONArray("applied")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { Central.isId(it) } }.orEmpty()
            val audit = o.optJSONArray("audit")?.let { a -> (0 until a.length()).mapNotNull { CentralAudit.fromJson(a.optJSONObject(it)) } }.orEmpty()
            return CentralDoc(
                accountId = accountId,
                cloudAccountId = o.optString("cloudAccountId"),
                users = parseUsers(o),
                plans = parsePlans(o),
                contact = o.optNullableString("contact")?.take(Central.MAX_CONTACT),
                script = o.optString("script"),
                host = o.optString("host"),
                appliedIds = applied,
                appliedAt = o.optLong("appliedAt"),
                sharedAt = o.optLong("sharedAt"),
                unshared = o.optBoolean("unshared"),
                audit = audit.take(Central.MAX_AUDIT),
                sub = SubWorker.fromJson(o.optJSONObject("sub")),
                subPushed = o.optJSONObject("subPushed")?.let { p ->
                    p.keys().asSequence().mapNotNull { k -> SubPushed.fromJson(p.optJSONObject(k))?.let { k to it } }.toMap()
                }.orEmpty(),
                subLinks = o.optString("subLinks").take(64),
            )
        }

        /**
         * This phone's copy with the account's shared copy taken in, person by person — the later
         * change wins ([CentralUser.newer]), nobody is lost on either side, and a tombstone stays a
         * tombstone. Plans the same way. The contact line follows whichever side changed last
         * ([remoteAt] against this copy's [sharedAt]).
         */
        fun merge(local: CentralDoc, remote: JSONObject?, remoteAt: Long): CentralDoc {
            if (remote == null) return local
            val users = LinkedHashMap<String, CentralUser>()
            for (u in local.users) users[u.id] = u
            for (u in parseUsers(remote)) users[u.id] = users[u.id]?.let { CentralUser.newer(it, u) } ?: u
            val plans = LinkedHashMap<String, Plan>()
            for (p in local.plans) plans[p.id] = p
            for (p in parsePlans(remote)) plans[p.id] = plans[p.id]?.let { PlanJson.newer(it, p) } ?: p
            val remoteContact = remote.optNullableString("contact")?.take(Central.MAX_CONTACT)
            val contact = if (remoteAt > local.sharedAt && !local.unshared) remoteContact else local.contact ?: remoteContact
            // One sub Worker per account: the one this phone knows, else the one another phone made.
            val sub = local.sub ?: SubWorker.fromJson(remote.optJSONObject("sub"))
            return local.copy(users = users.values.toList(), plans = plans.values.toList(), contact = contact, sub = sub)
        }

        /**
         * The ids a device that knows only the shared record must keep on the Worker at [now]: every
         * `s.ids` entry that is a real id and whose end has not passed. This is all the Windows app (or
         * an older phone) needs to read; it is also what a phone with no copy of its own falls back to.
         */
        fun sharedLiveIds(s: JSONObject?, now: Long): List<String> {
            val a = s?.optJSONArray("ids") ?: return emptyList()
            val out = LinkedHashSet<String>()
            for (i in 0 until minOf(a.length(), Central.MAX_USERS)) {
                val e = a.optJSONObject(i) ?: continue
                val id = e.optString("id").trim().lowercase()
                val until = e.optLong("until")
                if (Central.isId(id) && (until <= 0 || until > now)) out.add(id)
            }
            return out.toList()
        }
    }
}

/** `optString` returns "" for a JSON null and for a missing key; both mean "none" here. */
internal fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
