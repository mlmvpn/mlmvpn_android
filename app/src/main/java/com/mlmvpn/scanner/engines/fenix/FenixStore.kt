package com.mlmvpn.scanner.engines.fenix

import android.content.Context
import com.mlmvpn.scanner.data.SecureStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything FENIX keeps. The accounts -- their tokens, passes and allowances -- are one list
 * sealed with the Keystore ([SecureStore]); the server list, the chosen country and the pings are
 * plain.
 */
internal class FenixStore(context: Context) {

    private val p = context.applicationContext.getSharedPreferences("fenix", Context.MODE_PRIVATE)

    data class Pass(
        val token: String,
        /** Epoch seconds. */
        val issuedAt: Long,
        val expiresAt: Long,
        val quotaLimit: Long? = null,
        val quotaRemaining: Long? = null,
        /** Epoch seconds of the monthly reset, when Guardian says. */
        val quotaReset: Long? = null,
    )

    /** The monthly allowance as Guardian last reported it. [at] is in ms. */
    data class Quota(val limit: Long?, val remaining: Long?, val resetAt: Long?, val at: Long)

    /**
     * One Firefox account.
     *
     * [pass] is the pass the tunnel uses; a quota check fetches a pass of its own and keeps only
     * the numbers in [quota], so the running tunnel's pass (and the renewal timed on its expiry)
     * is never swapped underneath it.
     */
    data class Acct(
        val id: String,
        val email: String,
        val access: String,
        val refresh: String?,
        /** Epoch seconds. */
        val expiresAt: Long,
        val pass: Pass? = null,
        val quota: Quota? = null,
        /** Sign-in no longer valid (revoked, password changed): shown, skipped, removable. */
        val signedOut: Boolean = false,
        /** When it was last used for a connection (ms): the rotation's order. */
        val lastUsedAt: Long = 0L,
        val addedAt: Long = System.currentTimeMillis(),
    )

    // ── sealed ──────────────────────────────────────────────────────────────────────────────────

    private fun putSecret(key: String, plain: String?) {
        if (plain == null) { p.edit().remove(key).apply(); return }
        // A broken Keystore (some devices) keeps working unsealed rather than losing the sign-in.
        p.edit().putString(key, SecureStore.seal(plain) ?: plain).apply()
    }

    private fun secret(key: String): String? {
        val raw = p.getString(key, null) ?: return null
        return if (SecureStore.isSealed(raw)) SecureStore.open(raw) else raw
    }

    // ── accounts ────────────────────────────────────────────────────────────────────────────────

    /** In memory: MAE asks for a pass on every config it compiles, and a Keystore open is not free. */
    @Volatile private var cache: List<Acct>? = null

    @Synchronized
    fun accounts(): List<Acct> {
        cache?.let { return it }
        val list = secret("accounts")?.let { decode(it) } ?: migrate()
        cache = list
        return list
    }

    fun account(id: String?): Acct? = id?.let { i -> accounts().firstOrNull { it.id == i } }

    @Synchronized
    fun update(id: String, change: (Acct) -> Acct) {
        save(accounts().map { if (it.id == id) change(it) else it })
    }

    /** Adds the account, or refreshes it when the same email is already there. Returns its id. */
    @Synchronized
    fun upsert(email: String, access: String, refresh: String?, expiresAt: Long): String {
        val cur = accounts()
        val same = cur.firstOrNull { it.email.equals(email, ignoreCase = true) && email.isNotBlank() }
        return if (same != null) {
            update(same.id) { it.copy(access = access, refresh = refresh ?: it.refresh, expiresAt = expiresAt, signedOut = false) }
            same.id
        } else {
            val id = java.util.UUID.randomUUID().toString().take(8)
            save(cur + Acct(id, email, access, refresh, expiresAt))
            id
        }
    }

    @Synchronized
    fun remove(id: String) {
        save(accounts().filterNot { it.id == id })
        if (activeId == id) activeId = null
    }

    @Synchronized
    private fun save(list: List<Acct>) {
        cache = list
        putSecret("accounts", encode(list))
    }

    /** The account the running tunnel (and MAE) uses. */
    var activeId: String?
        get() = p.getString("active", null)
        set(v) = p.edit().putString("active", v).apply()

    /** Before several accounts: the one account was kept as `auth` + `pass` + `q_*`. */
    private fun migrate(): List<Acct> {
        val auth = secret("auth")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val access = auth.optString("access")
        if (access.isBlank()) return emptyList()
        val pass = secret("pass")?.let { runCatching { passOf(JSONObject(it)) }.getOrNull() }
        val quota = if (p.contains("q_at")) Quota(
            p.getLong("q_limit", -1).takeIf { it >= 0 }, p.getLong("q_left", -1).takeIf { it >= 0 },
            p.getLong("q_reset", -1).takeIf { it > 0 }, p.getLong("q_at", 0),
        ) else null
        val a = Acct(java.util.UUID.randomUUID().toString().take(8), auth.optString("email"), access,
            auth.optString("refresh").ifBlank { null }, auth.optLong("exp"), pass, quota)
        putSecret("accounts", encode(listOf(a)))
        putSecret("auth", null); putSecret("pass", null)
        p.edit().remove("q_limit").remove("q_left").remove("q_reset").remove("q_at").putString("active", a.id).apply()
        return listOf(a)
    }

    private fun encode(list: List<Acct>): String = JSONArray().apply {
        list.forEach { a ->
            put(JSONObject().put("id", a.id).put("email", a.email).put("access", a.access).put("refresh", a.refresh.orEmpty())
                .put("exp", a.expiresAt).put("out", a.signedOut).put("used", a.lastUsedAt).put("added", a.addedAt)
                .apply {
                    a.pass?.let { x -> put("pass", JSONObject().put("t", x.token).put("iat", x.issuedAt).put("exp", x.expiresAt)) }
                    a.quota?.let { q -> put("quota", JSONObject().put("l", q.limit ?: -1).put("r", q.remaining ?: -1).put("z", q.resetAt ?: -1).put("at", q.at)) }
                })
        }
    }.toString()

    private fun decode(json: String): List<Acct> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Acct(
                id = o.getString("id"), email = o.optString("email"), access = o.optString("access"),
                refresh = o.optString("refresh").ifBlank { null }, expiresAt = o.optLong("exp"),
                pass = o.optJSONObject("pass")?.let { passOf(it) },
                quota = o.optJSONObject("quota")?.let { q ->
                    Quota(q.optLong("l", -1).takeIf { it >= 0 }, q.optLong("r", -1).takeIf { it >= 0 }, q.optLong("z", -1).takeIf { it > 0 }, q.optLong("at"))
                },
                signedOut = o.optBoolean("out"), lastUsedAt = o.optLong("used"), addedAt = o.optLong("added"),
            )
        }
    }.getOrDefault(emptyList())

    private fun passOf(o: JSONObject): Pass? =
        Pass(o.optString("t"), o.optLong("iat"), o.optLong("exp")).takeIf { it.token.isNotBlank() }

    // ── plain ───────────────────────────────────────────────────────────────────────────────────

    /** The chosen country code, [FenixServers.FASTEST] or [FenixServers.RECOMMENDED]. */
    var country: String
        get() = p.getString("country", FenixServers.FASTEST).orEmpty()
        set(v) = p.edit().putString("country", v).apply()

    /** Whether MAE may use FENIX as one of its foreign exits. On by default once signed in. */
    var forMae: Boolean
        get() = p.getBoolean("for_mae", true)
        set(v) = p.edit().putBoolean("for_mae", v).apply()

    fun serversJson(): String? = p.getString("servers", null)
    val serversAt: Long get() = p.getLong("servers_at", 0L)
    fun saveServers(json: String) = p.edit().putString("servers", json).putLong("servers_at", System.currentTimeMillis()).apply()

    /** Handshake times per country, measured from the user's own line (ms; -1 = no answer). */
    fun pings(): Map<String, Long> = runCatching {
        val o = JSONObject(p.getString("pings", "{}")!!)
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    val pingsAt: Long get() = p.getLong("pings_at", 0L)

    fun savePings(m: Map<String, Long>) =
        p.edit().putString("pings", JSONObject(m as Map<*, *>).toString()).putLong("pings_at", System.currentTimeMillis()).apply()

    /** The exit country Cloudflare saw through the tunnel last time, per chosen country. */
    fun exitSeen(): Map<String, String> = runCatching {
        val o = JSONObject(p.getString("exits", "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun saveExitSeen(country: String, seen: String) {
        val o = runCatching { JSONObject(p.getString("exits", "{}")!!) }.getOrDefault(JSONObject())
        p.edit().putString("exits", o.put(country, seen).toString()).apply()
    }

    /** HTTP/2 by default; falls back to HTTP/1.1 per connection if a network breaks HTTP/2. */
    var http1: Boolean
        get() = p.getBoolean("http1", false)
        set(v) = p.edit().putBoolean("http1", v).apply()
}
