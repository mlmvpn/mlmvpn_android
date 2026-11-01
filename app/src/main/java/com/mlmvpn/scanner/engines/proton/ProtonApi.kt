package com.mlmvpn.scanner.engines.proton

import android.util.Base64
import android.webkit.CookieManager
import com.mlmvpn.core.warp.TweetNaclFast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The free servers of the signed-in account, taken by the app itself once the user has signed in.
 *
 * Going through Proton's dashboard by hand (WireGuard configuration, Android, a free server, Create,
 * Download -- for every server) was too many steps nobody saw: users signed in, pressed «تمام» and
 * got nothing. So the app does what that dashboard does, the same calls in the same order
 * (WebClients, packages/components/containers/vpn/WireGuardConfigurationSection):
 *
 *  1. `vpn/v2` for the plan's highest tier, `vpn/v2/clientconfig` for which features exist;
 *  2. `vpn/v1/logicals` for the servers, kept as the dashboard keeps them: tier within the plan,
 *     no Secure Core / Tor (Features & 3), at least one server with a WireGuard key, best Score first;
 *  3. an Ed25519 key made on the phone, registered with `vpn/v1/certificate` as a persistent
 *     config; the WireGuard private key is that Ed25519 key's X25519 twin, which is what Proton's
 *     servers derive from the registered key.
 *
 * Unlike the dashboard (a key per file), ONE key serves every server: measured on the user's line,
 * the key registered for one server carried data on two others. So the account gets one entry in
 * its dashboard, and every free server it has (116 on 2026-10-06, in 10 countries) is taken, each
 * with its IPv6 address -- the way past the filter there (see [ProtonStealth]).
 *
 * The session is the page's own: the cookies the sign-in left in the WebView (the AUTH-<UID> one
 * names the session), so the password still never reaches the app. The calls take the same way out
 * as the page did (the engine's listener, or the line).
 */
object ProtonApi {

    private const val BASE = "https://account.protonvpn.com"
    /** The session cookie (AUTH-<UID>) is scoped to /api/: asked for the site root it is not there. */
    private const val COOKIE_URL = "$BASE/api/"
    /** The dashboard's own client id; its version is read from the page (it moves with every release). */
    private const val CLIENT_ID = "web-vpn-settings"
    const val FALLBACK_VERSION = "5.0.421.0"
    /** What the configs are listed as in the account's dashboard. */
    const val DEVICE_NAME = "MLM VPN"
    /** A ceiling, not a target: every server the plan has is taken (116 free ones when measured). */
    const val MAX_SERVERS = 300
    /** A key this close to its expiry is replaced, and every config made again with the new one. */
    private const val RENEW_BEFORE_MS = 3L * 24 * 3600_000
    private const val NET_TRIES = 3
    private const val NET_RETRY_MS = 2_000L

    /** A server the account may use, and the one physical machine of it the config points at. */
    data class Pick(val name: String, val country: String?, val ip: String, val publicKey: String, val label: String, val ipv6: String? = null)

    /** A config ready for the Amnezia store. */
    data class Conf(val name: String, val country: String?, val text: String)

    /** The account's WireGuard key: X25519, base64; [expiresAt] in seconds (0: not said). */
    data class AccountKey(val privateKey: String, val expiresAt: Long)

    sealed class Outcome {
        /**
         * [key]: set when a key was registered just now -- every config was then made with it, and
         * the configs of an older key are to go. [v6]: the IPv6 address of every server listed, by IPv4.
         */
        data class Got(val confs: List<Conf>, val key: AccountKey?, val v6: Map<String, String>) : Outcome()
        /** No session in the page: the user has not signed in (or signed out). */
        object NotSignedIn : Outcome()
        /** The session was refused: it ran out, the user has to sign in again. */
        object Expired : Outcome()
        /** Every server the plan has is in the list already. */
        data class NothingNew(val v6: Map<String, String>) : Outcome()
        data class Failed(val detail: String) : Outcome()
    }

    /** The sessions the page holds, newest cookie first is not guaranteed, so each is tried. */
    fun sessions(): List<String> {
        val all = runCatching { CookieManager.getInstance().getCookie(COOKIE_URL) }.getOrNull() ?: return emptyList()
        return all.split(';').map { it.trim().substringBefore('=') }
            .filter { it.startsWith("AUTH-") && it.length > 5 }
            .map { it.removePrefix("AUTH-") }
            .distinct()
    }

    fun signedIn(): Boolean = sessions().isNotEmpty()

    /**
     * Forgets every Proton sign-in the WebView holds -- Proton's cookies and site storage, no other
     * site's (FENIX's sign-in page lives in the same WebView). Android has no "remove the cookies of
     * one site", so each of Proton's cookies is set again already expired, on every path and domain
     * Proton uses; if a session cookie still survives, everything is cleared rather than leave the
     * user signed in.
     */
    fun forgetSession() {
        val cm = CookieManager.getInstance()
        for (site in SITES) {
            for (path in COOKIE_PATHS) {
                val url = "https://$site$path"
                val names = runCatching { cm.getCookie(url) }.getOrNull().orEmpty()
                    .split(';').map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() }
                val parent = site.split('.').takeLast(2).joinToString(".")
                for (name in names) for (p in COOKIE_PATHS) {
                    cm.setCookie(url, "$name=; Max-Age=0; Path=$p; Secure")
                    cm.setCookie(url, "$name=; Max-Age=0; Path=$p; Domain=$parent; Secure")
                }
            }
        }
        cm.flush()
        runCatching { val ws = android.webkit.WebStorage.getInstance(); SITES.forEach { ws.deleteOrigin("https://$it") } }
        if (signedIn()) { cm.removeAllCookies(null); cm.flush(); runCatching { android.webkit.WebStorage.getInstance().deleteAllData() } }
    }

    /** Where Proton's sign-in leaves cookies and storage (the page is fenced to these sites). */
    private val SITES = listOf(
        "account.protonvpn.com", "account-api.protonvpn.com", "protonvpn.com",
        "account.proton.me", "account-api.proton.me", "proton.me", "verify.proton.me", "verify-api.proton.me",
    )
    private val COOKIE_PATHS = listOf("/", "/api/", "/api/auth/refresh")

    /**
     * Takes the servers the list does not have yet ([have]: the entry addresses of the Proton servers
     * already kept), with the account's [key] -- or, when there is none yet or it is about to expire,
     * with a key registered now, and then every server again. [version] is the dashboard's own
     * (`SENTRY_RELEASE.id` on the page).
     */
    suspend fun takeFreeServers(
        proxy: Proxy, version: String, userAgent: String, have: Set<String>, key: AccountKey?,
        max: Int = MAX_SERVERS, pageUid: String? = null,
    ): Outcome =
        withContext(Dispatchers.IO) {
            // Every sign-in leaves its own AUTH cookie (six on one phone after a few tries), and an
            // older one is refused with "insufficient scope": the page's own session goes first.
            val uids = sessions().let { all -> if (pageUid != null && pageUid in all) listOf(pageUid) + (all - pageUid) else all }
            if (uids.isEmpty()) return@withContext Outcome.NotSignedIn
            val cookies = runCatching { CookieManager.getInstance().getCookie(COOKIE_URL) }.getOrNull().orEmpty()
            val http = OkHttpClient.Builder().proxy(proxy)
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
                .build()
            try {
                // The session that answers. Any other AUTH cookie is an older sign-in.
                var found: Pair<Api, JSONObject>? = null
                for (uid in uids) {
                    val a = Api(http, uid, cookies, version.ifBlank { FALLBACK_VERSION }, userAgent)
                    try { found = a to a.get("vpn/v2"); break } catch (e: ApiError) { if (!e.auth && !e.scope) throw e }
                }
                val (api, vpn) = found ?: return@withContext Outcome.Expired.also { log("no session answered (${uids.size} cookie(s))") }
                val maxTier = vpn.optJSONObject("VPN")?.optInt("MaxTier", 0) ?: 0
                val flags = runCatching { api.get("vpn/v2/clientconfig").optJSONObject("FeatureFlags") }.getOrNull()
                val logicals = api.get("vpn/v1/logicals?WithIpV6=1").optJSONArray("LogicalServers") ?: JSONArray()
                val v6 = v6Map(logicals)
                val usable = key?.takeIf { it.expiresAt == 0L || it.expiresAt * 1000 - System.currentTimeMillis() > RENEW_BEFORE_MS }
                val picks = pick(logicals, maxTier, if (usable != null) have else emptySet(), max)
                log("tier $maxTier, ${logicals.length()} servers listed (${v6.size} with IPv6), ${picks.size} to take, " +
                    "${have.size} kept, key ${if (usable != null) "kept" else "new"}")
                if (picks.isEmpty()) return@withContext Outcome.NothingNew(v6)

                val k = usable ?: run {
                    val keys = ProtonKeys.generate()
                    val r = api.post("vpn/v1/certificate", JSONObject()
                        .put("ClientPublicKey", b64(keys.ed25519Public))
                        .put("Mode", "persistent")
                        .put("DeviceName", DEVICE_NAME)
                        .put("Features", features(flags, picks.first())))
                    AccountKey(b64(keys.x25519Private), r.optLong("ExpirationTime", 0L)).also {
                        log("key registered, expires ${if (it.expiresAt > 0) java.util.Date(it.expiresAt * 1000) else "?"}")
                    }
                }
                Outcome.Got(picks.map { p -> Conf(p.name, p.country, conf(k.privateKey, p)) }, if (usable == null) k else null, v6)
            } catch (e: ApiError) {
                log("refused, HTTP ${e.status} code ${e.code}: ${e.message}")
                if (e.auth) Outcome.Expired else Outcome.Failed(e.message ?: "HTTP ${e.status}")
            } catch (e: Exception) {
                log("failed: ${e.javaClass.simpleName}: ${e.message}")
                Outcome.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            } finally {
                http.connectionPool.evictAll()
            }
        }

    // ── what the dashboard does, as pure functions ──────────────────────────────────────────────

    /**
     * The servers to take: the dashboard's filter, best Score first, then one per country before a
     * second of any country (so the list starts with every country). A server whose machines are
     * already in the list is skipped. Of a server's machines, one with an IPv6 address is preferred.
     */
    fun pick(logicals: JSONArray, maxTier: Int, have: Set<String>, max: Int, random: Random = Random.Default): List<Pick> {
        val usable = (0 until logicals.length()).mapNotNull { logicals.optJSONObject(it) }
            .filter { it.optInt("Tier", Int.MAX_VALUE) <= maxTier && (it.optInt("Features") and 3) == 0 && it.optInt("Status", 1) != 0 }
            .mapNotNull { l ->
                val servers = l.optJSONArray("Servers") ?: return@mapNotNull null
                val withKey = (0 until servers.length()).mapNotNull { servers.optJSONObject(it) }
                    .filter { str(it, "X25519PublicKey").isNotEmpty() && str(it, "EntryIP").isNotEmpty() }
                if (withKey.isEmpty() || withKey.any { str(it, "EntryIP") in have }) null
                else l to withKey
            }
            .sortedBy { it.first.optDouble("Score", Double.MAX_VALUE) }
        val byCountry = LinkedHashMap<String, MutableList<Pair<JSONObject, List<JSONObject>>>>()
        for (u in usable) byCountry.getOrPut(str(u.first, "ExitCountry")) { ArrayList() } += u
        val out = ArrayList<Pick>()
        var round = 0
        while (out.size < max) {
            var any = false
            for (list in byCountry.values) {
                val (l, servers) = list.getOrNull(round) ?: continue
                // The dashboard takes one of the logical's machines at random; an online one if it can,
                // and here one that also has IPv6.
                val online = servers.filter { truthy(it, "Status", default = true) }.ifEmpty { servers }
                val best = online.filter { str(it, "EntryIPv6").isNotEmpty() }.ifEmpty { online }
                val s = best[random.nextInt(best.size)]
                out += Pick(
                    name = str(l, "Name"), country = str(l, "ExitCountry").ifEmpty { null },
                    ip = str(s, "EntryIP"), publicKey = str(s, "X25519PublicKey"), label = str(s, "Label"),
                    ipv6 = str(s, "EntryIPv6").ifEmpty { null },
                )
                any = true
                if (out.size >= max) break
            }
            if (!any) break
            round++
        }
        return out
    }

    /** Every machine's IPv6 address, by its IPv4 one: the configs keep IPv4, the relay tries IPv6 first. */
    fun v6Map(logicals: JSONArray): Map<String, String> {
        val out = HashMap<String, String>()
        for (i in 0 until logicals.length()) {
            val servers = logicals.optJSONObject(i)?.optJSONArray("Servers") ?: continue
            for (j in 0 until servers.length()) {
                val s = servers.optJSONObject(j) ?: continue
                val v4 = str(s, "EntryIP")
                val v6 = str(s, "EntryIPv6")
                if (v4.isNotEmpty() && v6.isNotEmpty()) out[v4] = v6
            }
        }
        return out
    }

    /** The features the dashboard registers for a free-tier config (tier-1 ones are left out, as there). */
    fun features(flags: JSONObject?, p: Pick): JSONObject = JSONObject().apply {
        if (p.label.isNotEmpty()) put("Bouncing", p.label)
        if (truthy(flags, "PortForwarding")) put("PortForwarding", false)
        if (truthy(flags, "VpnAccelerator")) put("SplitTCP", true)
        put("peerName", p.name)
        put("peerIp", p.ip)
        put("peerPublicKey", p.publicKey)
        put("platform", "Android")
    }

    /** The dashboard's config template, without the IPv6 lines (the IPv6 feature is not asked for). */
    fun conf(privateKey: String, p: Pick): String = """
        [Interface]
        # Key for $DEVICE_NAME
        PrivateKey = $privateKey
        Address = 10.2.0.2/32
        DNS = 10.2.0.1

        [Peer]
        # ${p.name}
        PublicKey = ${p.publicKey}
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = ${p.ip}:51820
        PersistentKeepalive = 25
    """.trimIndent()

    // ── the wire ────────────────────────────────────────────────────────────────────────────────

    private class ApiError(val status: Int, val code: Int, message: String) : Exception(message) {
        /** The session is gone (signed out, ran out): 401, or Proton's own "invalid session" codes. */
        val auth: Boolean get() = status == 401 || code == 10013 || code == 401
        /** A session that is valid but not allowed this (an older sign-in): the next one is tried. */
        val scope: Boolean get() = code == 9106
    }

    private class Api(val http: OkHttpClient, val uid: String, val cookies: String, val version: String, val userAgent: String) {
        fun get(path: String): JSONObject = send(request(path).get().build())
        fun post(path: String, body: JSONObject): JSONObject =
            send(request(path).post(body.toString().toRequestBody("application/json;charset=utf-8".toMediaType())).build())

        private fun request(path: String) = Request.Builder().url("$BASE/api/$path")
            .header("x-pm-uid", uid)
            .header("x-pm-appversion", "$CLIENT_ID@$version")
            .header("Accept", "application/vnd.protonmail.v1+json")
            .header("Cookie", cookies)
            .header("User-Agent", userAgent)

        /**
         * A request that is cut on the way (the engine's route dropped for a moment: a TLS handshake
         * closed through FLUX, measured) is sent again, twice, a little later.
         */
        private fun send(req: Request): JSONObject {
            var last: java.io.IOException? = null
            repeat(NET_TRIES) { i ->
                if (i > 0) Thread.sleep(NET_RETRY_MS * i)
                try { return once(req) } catch (e: java.io.IOException) {
                    last = e
                    log("${req.url.encodedPath}: ${e.javaClass.simpleName} (try ${i + 1}/$NET_TRIES)")
                }
            }
            throw last ?: java.io.IOException("no answer")
        }

        private fun once(req: Request): JSONObject = http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrNull()
            val code = o?.optInt("Code") ?: 0
            if (!r.isSuccessful || o == null || (code != 1000 && code != 1001)) {
                throw ApiError(r.code, code, o?.let { str(it, "Error") }?.ifEmpty { null } ?: "HTTP ${r.code}")
            }
            o
        }
    }

    /** A value as text; JSON null and absence are both "". */
    private fun str(o: JSONObject, key: String): String {
        val v = o.opt(key)
        return if (v == null || v == JSONObject.NULL) "" else v.toString().trim()
    }

    /** JavaScript's truthiness, which is what the dashboard tests flags with (they come as 1/0). */
    private fun truthy(o: JSONObject?, key: String, default: Boolean = false): Boolean = when (val v = o?.opt(key)) {
        null, JSONObject.NULL -> default
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty() && v != "0" && v != "false"
        else -> true
    }

    /** Steps only: no cookie, key or session id is ever written. */
    private fun log(line: String) { runCatching { android.util.Log.i("ProtonApi", line) } }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/**
 * The key pair a Proton WireGuard config is made of. Proton registers an Ed25519 public key and its
 * servers turn it into the X25519 key WireGuard sees; the matching WireGuard private key is the
 * Ed25519 secret's clamped SHA-512 half -- the dashboard's `getX25519PrivateKey`, byte for byte.
 */
internal object ProtonKeys {
    class Keys(val ed25519Public: ByteArray, val x25519Private: ByteArray)

    private val random = SecureRandom()

    fun generate(): Keys = fromSeed(ByteArray(32).also(random::nextBytes))

    fun fromSeed(seed: ByteArray): Keys {
        require(seed.size == 32)
        val pub = TweetNaclFast.Signature.keyPair_fromSeed(seed).publicKey
        val h = MessageDigest.getInstance("SHA-512").digest(seed).copyOf(32)
        h[0] = (h[0].toInt() and 0xf8).toByte()
        h[31] = ((h[31].toInt() and 0x7f) or 0x40).toByte()
        return Keys(pub, h)
    }
}
