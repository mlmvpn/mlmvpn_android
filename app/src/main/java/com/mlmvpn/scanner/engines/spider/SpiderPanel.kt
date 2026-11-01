package com.mlmvpn.scanner.engines.spider

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.StoreFiles
import com.mlmvpn.scanner.store.StoreNet
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * «پنل اسپایدر» on the user's own Cloudflare account, with this app as its panel.
 *
 * SpiderPanel (github.com/amirh00sain/SpiderPanel) is a Python panel for a VPS; only its Worker
 * runs on Cloudflare. That Worker is self-contained -- VLESS over WebSocket at `/ws/{uuid}`, users
 * in its own KV, a concurrent-IP limit, a quota and an expiry per user, and an outbound that races
 * a pool of SOCKS5/HTTP proxies, scored by the visitor's country and Cloudflare data centre and
 * kept sticky per user -- and it is driven over a Bearer-token admin API. So the app plays the
 * panel: it deploys the developer's own file unmodified (the three constants the panel injects
 * filled in), keeps the token, and talks to `/panel/config`, `/panel/status` and `/api/users`.
 *
 * What is NOT brought over is what the app already has (scanner, subscriptions, device limits in
 * Config Studio) or cannot run on Cloudflare (the Telegram bots, MTProxy, Reality, the VPS
 * tunnels). The panel's default proxy source (NiREvil's ProxyIP-Daily) is left out too: measured
 * 2026-09-27, its addresses are Cloudflare relays -- a TLS request through them reaches Cloudflare
 * -- and none of twelve answered the HTTP CONNECT the Worker sends them, so the race would always
 * fail back to direct. The exits here are the user's own proxies.
 *
 * The code has no licence, so it is not bundled in the app: it comes from the developer's GitHub
 * at deploy time, or from the store's pinned copy once the store has fetched one.
 */
object SpiderPanel {

    private const val TAG = "SpiderPanel"
    const val REPO = "amirh00sain/SpiderPanel"
    const val BRANCH = "main"
    const val PATH = "worker/worker.js"
    /** The store's asset name. Never shipped: [code] falls back to the developer's GitHub. */
    const val ASSET = "spider_worker.js"
    private const val PREFS = "spider_panel"

    /** The three values the panel writes into the file, as `const NAME = <JSON string>;`. */
    val INJECTED = listOf("PANEL_TOKEN", "PANEL_DOMAIN", "WORKER_DOMAIN")

    private val http = OkHttpClient.Builder().dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns()).protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector()).connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()

    // ── what is stored per Cloudflare account ──────────────────────────────────────────────────

    data class Install(val script: String, val url: String, val token: String, val kvId: String)

    data class Exit(val proxy: String, val country: String) {
        fun toJson() = JSONObject().put("proxy", proxy).put("country", country)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun install(context: Context, accountId: String): Install? {
        val o = runCatching { JSONObject(prefs(context).getString("i_$accountId", null) ?: return null) }.getOrNull() ?: return null
        return Install(o.optString("script"), o.optString("url"), o.optString("token"), o.optString("kv"))
            .takeIf { it.url.startsWith("https://") && it.token.isNotBlank() }
    }

    private fun saveInstall(context: Context, accountId: String, i: Install) {
        prefs(context).edit().putString("i_$accountId", JSONObject()
            .put("script", i.script).put("url", i.url).put("token", i.token).put("kv", i.kvId).toString()).apply()
    }

    /** This panel's key in the account's shared registry (engines/cloud/PanelRegistry). */
    const val CODE = "SPD"

    private fun fromRecord(g: com.mlmvpn.scanner.engines.cloud.PanelRegistry.Record) = Install(g.script, g.url, g.s.optString("token"), g.kv.orEmpty())

    private fun publish(account: CloudAccount, i: Install) = com.mlmvpn.scanner.engines.cloud.PanelRegistry.publish(
        account, CODE, i.script, i.url, i.kvId.ifBlank { null }, null, JSONObject().put("token", i.token))

    /** The account's shared Spider → this phone; this phone's → the account when it has none. */
    fun sync(context: Context, account: CloudAccount) {
        val reg = com.mlmvpn.scanner.engines.cloud.PanelRegistry
        val g = reg.live(account, CODE)
        if (g != null && g.s.optString("token").isNotBlank()) { saveInstall(context, account.accountId, fromRecord(g)); return }
        val mine = install(context, account.accountId) ?: return
        if (reg.scriptExists(account, mine.script)) publish(account, mine)
    }

    fun forget(context: Context, accountId: String) {
        prefs(context).edit().remove("i_$accountId").remove("x_$accountId").apply()
    }

    fun exits(context: Context, accountId: String): List<Exit> = runCatching {
        val arr = JSONArray(prefs(context).getString("x_$accountId", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Exit(it.optString("proxy"), it.optString("country")) }
    }.getOrDefault(emptyList())

    fun saveExits(context: Context, accountId: String, list: List<Exit>) {
        prefs(context).edit().putString("x_$accountId", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    // ── the code ───────────────────────────────────────────────────────────────────────────────

    /** The store's copy when it has one, else the file on the developer's main branch right now. */
    fun code(context: Context): String =
        runCatching { StoreFiles.readText(context, ASSET) }.getOrNull()
            ?: run {
                val commit = StoreNet.latestCommit(context, REPO, BRANCH, PATH)
                StoreNet.getText(context, "https://raw.githubusercontent.com/$REPO/${commit.sha}/$PATH", 4 * 1024 * 1024)
            }

    private fun constRe(name: String) = Regex("""const\s+$name\s*=\s*([^;\n]+);""")

    /**
     * The template with every injected value put back as its placeholder: same file, same text.
     *
     * Every occurrence, not only the `const` lines: the file's header comment names the three
     * placeholders too, and both this app and SpiderPanel's own deployer (a plain str.replace)
     * fill those as well. Restoring only the constants left the comment carrying the token, and
     * the store then saw every installed copy as out of date (found on the phone 2026-09-27).
     */
    fun normalize(code: String): String {
        val values = injectedValues(code) ?: return code
        return values.entries.fold(code) { t, (n, v) -> t.replace(v, "__${n}__") }
    }

    /** Fill the placeholders of [template] with [values] (raw JS literals), everywhere, as the panel does. */
    fun inject(template: String, values: Map<String, String>): String {
        var out = template
        for (n in INJECTED) {
            val v = values[n] ?: throw IllegalStateException("missing $n")
            if (!out.contains("__${n}__")) throw IllegalStateException(
                tr("کد تازهٔ اسپایدر دیگر جای «$n» را ندارد — نصب نشد.", "Spider's new code no longer has a place for $n — not installed."))
            out = out.replace("__${n}__", v)
        }
        return out
    }

    /** The values a deployed copy carries, as raw JS literals; null when any is missing. */
    fun injectedValues(deployed: String): Map<String, String>? {
        val m = INJECTED.associateWith { n -> constRe(n).find(deployed)?.groupValues?.get(1)?.trim() }
        return if (m.values.any { it == null || it.startsWith("__") }) null else m.mapValues { it.value!! }
    }

    fun looksLikeSpider(code: String) = code.contains("SpiderPanel") && code.contains("SPIDER_KV") && code.contains("/panel/config")

    // ── deploy ─────────────────────────────────────────────────────────────────────────────────

    /** Put the Worker on [account], or update its code in place when it is already there. */
    suspend fun deploy(context: Context, account: CloudAccount, onStep: (String) -> Unit): Result<Install> = withContext(Dispatchers.IO) {
        runCatching {
            val api = "https://api.cloudflare.com/client/v4/accounts/${account.accountId}"
            val headers = CloudAuth.headers(account)

            onStep(tr("بررسی زیردامنه…", "Checking the subdomain…"))
            val sub = http.newCall(Request.Builder().url("$api/workers/subdomain").headers(headers).get().build()).execute().use { r ->
                runCatching { JSONObject(r.body?.string().orEmpty()).optJSONObject("result")?.optString("subdomain") }.getOrNull().orEmpty()
            }
            if (sub.isBlank()) error(tr("این حساب هنوز زیردامنهٔ workers.dev ندارد.", "This account has no workers.dev subdomain yet."))

            // The account's own Spider first (Windows' or this phone's): no second Worker or KV.
            com.mlmvpn.scanner.engines.cloud.PanelRegistry.live(account, CODE)?.takeIf { it.s.optString("token").isNotBlank() }?.let { g ->
                val shared = fromRecord(g)
                if (install(context, account.accountId) != shared) {
                    saveInstall(context, account.accountId, shared)
                    onStep(tr("همان اسپایدر مشترک این حساب به کار می‌رود — ورکر یا KV تازه ساخته نمی‌شود.", "Using this account's shared Spider — no new Worker or KV."))
                }
            }
            val prev = install(context, account.accountId)
            val script = prev?.script ?: (com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() + "-spd")
            val host = "$script.$sub.workers.dev"
            val token = prev?.token ?: ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

            onStep(tr("دریافت کد از گیت‌هاب سازنده…", "Fetching the code from the developer's GitHub…"))
            val template = code(context)
            if (!looksLikeSpider(template)) error(tr("کد دریافتی ورکر اسپایدر نیست.", "The code received is not Spider's Worker."))
            val body = inject(template, mapOf(
                "PANEL_TOKEN" to JSONObject.quote(token),
                // The panel is this app; the field only shows up in the Worker's /health.
                "PANEL_DOMAIN" to JSONObject.quote("mlmvpn-app"),
                "WORKER_DOMAIN" to JSONObject.quote(host),
            ))

            val kv = prev?.kvId?.takeIf { it.isNotBlank() } ?: run {
                onStep(tr("ساخت فضای KV…", "Creating the KV namespace…"))
                createKv(api, headers, "$script-db")
            }

            onStep(tr("بارگذاری ورکر…", "Uploading the Worker…"))
            val meta = JSONObject().apply {
                put("main_module", "worker.js")
                put("compatibility_date", "2024-09-23")
                put("bindings", JSONArray().put(JSONObject().put("type", "kv_namespace").put("name", "SPIDER_KV").put("namespace_id", kv)))
            }
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", meta.toString().toRequestBody("application/json".toMediaType()))
                .addFormDataPart("worker.js", "worker.js", body.toRequestBody("application/javascript+module".toMediaType()))
                .build()
            http.newCall(Request.Builder().url("$api/workers/scripts/$script").headers(headers).put(multipart).build()).execute().use { r ->
                if (!r.isSuccessful) error(tr("بارگذاری ورکر رد شد", "Uploading the Worker failed") + " (HTTP ${r.code}): " + cfMessage(r.body?.string()))
            }

            onStep(tr("فعال کردن آدرس…", "Enabling the address…"))
            http.newCall(Request.Builder().url("$api/workers/scripts/$script/subdomain").headers(headers)
                .post("{\"enabled\":true}".toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (!r.isSuccessful) error(tr("فعال کردن آدرس ورکر نشد", "Could not enable the Worker address") + " (HTTP ${r.code})")
            }

            val inst = Install(script, "https://$host", token, kv)
            saveInstall(context, account.accountId, inst)
            publish(account, inst)
            inst
        }.onFailure { Log.w(TAG, "deploy", it) }
    }

    private fun createKv(api: String, headers: okhttp3.Headers, title: String): String {
        val req = Request.Builder().url("$api/storage/kv/namespaces").headers(headers)
            .post(JSONObject().put("title", title).toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val id = runCatching { JSONObject(text).optJSONObject("result")?.optString("id") }.getOrNull()
            if (r.isSuccessful && !id.isNullOrBlank()) return id
            // A namespace of that title already exists from an earlier, interrupted deploy.
            if (text.contains("already exists")) {
                http.newCall(Request.Builder().url("$api/storage/kv/namespaces?per_page=100").headers(headers).get().build()).execute().use { l ->
                    val arr = runCatching { JSONObject(l.body?.string().orEmpty()).optJSONArray("result") }.getOrNull()
                    for (i in 0 until (arr?.length() ?: 0)) {
                        val o = arr!!.getJSONObject(i)
                        if (o.optString("title") == title) return o.getString("id")
                    }
                }
            }
            error(tr("ساخت KV نشد", "Could not create the KV namespace") + " (HTTP ${r.code}): " + cfMessage(text))
        }
    }

    private fun cfMessage(body: String?): String = runCatching {
        JSONObject(body.orEmpty()).optJSONArray("errors")?.optJSONObject(0)?.optString("message")
    }.getOrNull()?.ifBlank { null } ?: body.orEmpty().take(160)

    /** Delete the Worker and its KV from the account, then forget the install. */
    suspend fun remove(context: Context, account: CloudAccount): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val inst = install(context, account.accountId) ?: return@runCatching
            val api = "https://api.cloudflare.com/client/v4/accounts/${account.accountId}"
            val headers = CloudAuth.headers(account)
            http.newCall(Request.Builder().url("$api/workers/scripts/${inst.script}?force=true").headers(headers).delete().build()).execute().use { r ->
                if (!r.isSuccessful && r.code != 404) error("HTTP ${r.code}: " + cfMessage(r.body?.string()))
            }
            if (inst.kvId.isNotBlank()) runCatching {
                http.newCall(Request.Builder().url("$api/storage/kv/namespaces/${inst.kvId}").headers(headers).delete().build()).execute().close()
            }
            forget(context, account.accountId)
        }
    }

    // ── the Worker's admin API ─────────────────────────────────────────────────────────────────

    data class User(
        val uuid: String, val remark: String, val limitBytes: Long, val usedBytes: Long,
        val expire: Long, val ipLimit: Int, val proxyIps: List<String>,
    ) {
        fun toJson() = JSONObject().put("uuid", uuid).put("remark", remark).put("limit_bytes", limitBytes)
            .put("used_bytes", usedBytes).put("expire", expire).put("concurrent_connections", ipLimit)
            .put("proxy_ips", JSONArray(proxyIps))

        val expired get() = expire > 0 && System.currentTimeMillis() / 1000 >= expire
        val overQuota get() = limitBytes > 0 && usedBytes >= limitBytes

        companion object {
            fun from(o: JSONObject) = User(
                uuid = o.optString("uuid"), remark = o.optString("remark", "user"),
                limitBytes = o.optLong("limit_bytes"), usedBytes = o.optLong("used_bytes"),
                expire = o.optLong("expire"), ipLimit = o.optInt("concurrent_connections"),
                proxyIps = o.optJSONArray("proxy_ips")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            )
        }
    }

    private fun call(context: Context, inst: Install, method: String, path: String, body: JSONObject? = null): JSONObject {
        val b = Request.Builder().url(inst.url + path).header("Authorization", "Bearer ${inst.token}")
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            else -> b.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        }
        com.mlmvpn.scanner.update.UpdateNet.client(context, 15, 30, directFirst = true).newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrNull()
            if (!r.isSuccessful) error(o?.optString("error")?.ifBlank { null } ?: "HTTP ${r.code}")
            return o ?: JSONObject()
        }
    }

    /**
     * Writes the list has not caught up with yet. The Worker lists users with KV `list()`, which
     * is eventually consistent: a user just written can be missing from it for up to a minute, so
     * a user created on the phone did not appear until the page was left and reopened. Each write
     * this app makes is remembered for [PENDING_MS] and laid over what the Worker returns.
     */
    private val pendingPut = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, User>>()
    private val pendingDel = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val PENDING_MS = 90_000L

    private fun key(inst: Install, uuid: String) = inst.url + "|" + uuid

    private fun overlay(inst: Install, live: List<User>): List<User> {
        val now = System.currentTimeMillis()
        pendingPut.entries.removeIf { now - it.value.first > PENDING_MS }
        pendingDel.entries.removeIf { now - it.value > PENDING_MS }
        val put = pendingPut.filterKeys { it.startsWith(inst.url + "|") }.values.associate { it.second.uuid to it.second }
        val gone = pendingDel.keys.filter { it.startsWith(inst.url + "|") }.map { it.substringAfter('|') }.toSet()
        // A record we just wrote wins over the listed one (a reset to zero usage included);
        // after PENDING_MS the Worker's own copy, with the usage it counted, is back.
        val merged = live.filter { it.uuid !in gone }.map { u -> put[u.uuid] ?: u }
        val missing = put.values.filter { p -> p.uuid !in gone && merged.none { it.uuid == p.uuid } }
        return (merged + missing).sortedBy { it.remark.lowercase() }
    }

    suspend fun users(context: Context, inst: Install): List<User> = withContext(Dispatchers.IO) {
        val arr = call(context, inst, "GET", "/api/users").optJSONArray("users") ?: JSONArray()
        overlay(inst, (0 until arr.length()).map { User.from(arr.getJSONObject(it)) })
    }

    suspend fun addUser(context: Context, inst: Install, remark: String, gb: Double, days: Int, ipLimit: Int): User = withContext(Dispatchers.IO) {
        val u = User(
            uuid = UUID.randomUUID().toString(), remark = remark.ifBlank { "user" },
            limitBytes = if (gb > 0) (gb * 1024 * 1024 * 1024).toLong() else 0L, usedBytes = 0,
            expire = if (days > 0) System.currentTimeMillis() / 1000 + days * 86_400L else 0L,
            ipLimit = ipLimit.coerceAtLeast(0), proxyIps = emptyList(),
        )
        val made = User.from(call(context, inst, "POST", "/api/users", u.toJson()).optJSONObject("user") ?: u.toJson())
        pendingDel.remove(key(inst, made.uuid))
        pendingPut[key(inst, made.uuid)] = System.currentTimeMillis() to made
        made
    }

    /** POST /api/users replaces the record: the way to change one user without touching others. */
    suspend fun updateUser(context: Context, inst: Install, u: User): User = withContext(Dispatchers.IO) {
        val saved = User.from(call(context, inst, "POST", "/api/users", u.toJson()).optJSONObject("user") ?: u.toJson())
        pendingPut[key(inst, saved.uuid)] = System.currentTimeMillis() to saved
        saved
    }

    suspend fun deleteUser(context: Context, inst: Install, uuid: String) = withContext(Dispatchers.IO) {
        call(context, inst, "DELETE", "/api/user/$uuid")
        pendingPut.remove(key(inst, uuid))
        pendingDel[key(inst, uuid)] = System.currentTimeMillis()
        Unit
    }

    /**
     * Push the exit pool. `/panel/config` REPLACES the user set with the one it is given (deleting
     * the rest), so the live users -- with the usage the Worker has counted -- are read first and
     * sent back unchanged.
     */
    suspend fun pushExits(context: Context, inst: Install, exits: List<Exit>) = withContext(Dispatchers.IO) {
        val live = users(context, inst)
        val proxies = JSONObject()
        exits.groupBy { it.country.uppercase().ifBlank { "XX" } }.forEach { (cc, list) ->
            proxies.put(cc.lowercase(), JSONObject().put("country", cc).put("country_code", cc)
                .put("proxies", JSONArray(list.map { it.proxy })))
        }
        call(context, inst, "POST", "/panel/config", JSONObject()
            .put("users", JSONArray(live.map { it.toJson() }))
            .put("settings", JSONObject().put("proxies", proxies)))
        Unit
    }

    data class Route(val key: String, val latencyMs: Int, val jitterMs: Int, val failures: Int, val successes: Int, val cooling: Boolean)
    data class Status(val users: Int, val online: Int, val traffic: Long, val healthy: Int, val tracked: Int, val routes: List<Route>, val edge: String)

    /** `/panel/health-check` runs the Worker's own probe of every exit, then reports. */
    suspend fun status(context: Context, inst: Install, probe: Boolean): Status = withContext(Dispatchers.IO) {
        val o = if (probe) call(context, inst, "POST", "/panel/health-check") else call(context, inst, "GET", "/panel/status")
        val r = o.optJSONObject("routing") ?: JSONObject()
        val now = System.currentTimeMillis()
        val routes = r.optJSONArray("routes")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Route(it.optString("key"), it.optInt("latency_ms"), it.optInt("jitter_ms"), it.optInt("failures"),
                    it.optInt("successes"), it.optLong("cooldown_until") > now)
            }
        }.orEmpty()
        val edge = r.optJSONObject("edge")?.let { listOf(it.optString("colo"), it.optString("country")).filter { s -> s.isNotBlank() }.joinToString(" · ") }.orEmpty()
        Status(o.optInt("users"), o.optInt("online"), o.optLong("traffic"), r.optInt("healthy"), r.optInt("tracked"), routes, edge)
    }

    /** The user's VLESS link, the shape the Worker serves: TLS WebSocket at /ws/{uuid}. */
    fun link(inst: Install, u: User, address: String? = null): String {
        val host = inst.url.removePrefix("https://").trimEnd('/')
        val path = java.net.URLEncoder.encode("/ws/${u.uuid}", "UTF-8")
        val name = java.net.URLEncoder.encode("Spider-" + u.remark, "UTF-8").replace("+", "%20")
        return "vless://${u.uuid}@${address ?: host}:443?encryption=none&security=tls&sni=$host&fp=chrome&type=ws&host=$host&path=$path#$name"
    }
}
