package com.mlmvpn.scanner.engines.nova

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.cloud.CfWorkers
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.StoreFiles
import com.mlmvpn.scanner.store.StoreNet
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * «پنل نوا» (Nova Proxy, github.com/IRNova/Nova-Proxy) on the user's own Cloudflare account.
 *
 * Licensed PolyForm Noncommercial, and shipped by its developer as one minified, obfuscated
 * `worker.js`, so it is never bundled: the app fetches it from the developer's repository at install
 * time and checks it against the SHA-256 the same repository publishes in `version.json`
 * (`worker_sha256`) -- what runs is exactly what the developer released.
 *
 * Bindings (its wrangler.jsonc): a D1 as `DB`, a KV as `KV`, `nodejs_compat`. Its API, read from its
 * own panel script (2026-09-28): a fresh deploy has no admin password and `/install` is public --
 * whoever sets it first owns the panel -- so the app sets a random one IMMEDIATELY with
 * `POST /install/set {password}`; then `POST /login` (form `password=`) gives the session cookie,
 * and `GET /admin/sub-content` returns the panel's own subscription (its own `/sub?token=`).
 */
object NovaPanel {

    private const val TAG = "NovaPanel"
    const val REPO = "IRNova/Nova-Proxy"
    const val BRANCH = "main"
    const val PATH = "worker.js"
    const val ASSET = "nova_worker.js"
    private const val PREFS = "nova_panel"
    private const val COMPAT_DATE = "2026-07-30"

    data class Install(val script: String, val url: String, val password: String, val d1Id: String, val kvId: String)

    fun looksLikeNova(code: String) = code.contains("IRNova") && code.contains("/install/set") && code.contains("admin/sub-content")

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** This panel's key in the account's shared registry (engines/cloud/PanelRegistry). */
    const val CODE = "NVA"

    private fun fromRecord(g: com.mlmvpn.scanner.engines.cloud.PanelRegistry.Record) =
        Install(g.script, g.url, g.s.optString("password"), g.d1.orEmpty(), g.kv.orEmpty())

    private fun publish(account: CloudAccount, i: Install) = com.mlmvpn.scanner.engines.cloud.PanelRegistry.publish(
        account, CODE, i.script, i.url, i.kvId.ifBlank { null }, i.d1Id.ifBlank { null }, JSONObject().put("password", i.password))

    /** The account's shared Nova → this phone; this phone's → the account when it has none. */
    fun sync(context: Context, account: CloudAccount) {
        val reg = com.mlmvpn.scanner.engines.cloud.PanelRegistry
        val g = reg.live(account, CODE)
        if (g != null && g.s.optString("password").isNotBlank()) { save(context, account.accountId, fromRecord(g)); return }
        val mine = install(context, account.accountId) ?: return
        if (reg.scriptExists(account, mine.script)) publish(account, mine)
    }

    fun install(context: Context, accountId: String): Install? = runCatching {
        val o = JSONObject(prefs(context).getString("i_$accountId", null) ?: return null)
        Install(o.getString("script"), o.getString("url"), o.getString("pw"), o.optString("d1"), o.optString("kv"))
    }.getOrNull()

    private fun save(context: Context, accountId: String, i: Install) {
        prefs(context).edit().putString("i_$accountId", JSONObject().put("script", i.script).put("url", i.url)
            .put("pw", i.password).put("d1", i.d1Id).put("kv", i.kvId).toString()).apply()
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * The developer's `worker.js`, pinned to the commit read, and verified: the same commit's
     * `version.json` names its SHA-256. A mismatch is refused rather than deployed.
     */
    fun code(context: Context): String =
        runCatching { StoreFiles.readText(context, ASSET) }.getOrNull() ?: run {
            val commit = StoreNet.latestCommit(context, REPO, BRANCH, PATH)
            val code = StoreNet.getText(context, "https://raw.githubusercontent.com/$REPO/${commit.sha}/$PATH", 8 * 1024 * 1024)
            val manifest = runCatching {
                JSONObject(StoreNet.getText(context, "https://raw.githubusercontent.com/$REPO/${commit.sha}/version.json", 64 * 1024))
            }.getOrNull()
            val want = manifest?.optString("worker_sha256").orEmpty()
            if (want.isNotBlank() && !want.equals(sha256(code), ignoreCase = true)) {
                error(tr("کد دریافتی نوا با امضای خود سازنده (SHA-256) نمی‌خواند؛ نصب نشد.",
                    "Nova's code does not match the developer's own SHA-256; not installed."))
            }
            code
        }

    private fun random(n: Int): String {
        val abc = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (0 until n).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    private fun client(context: Context): OkHttpClient =
        com.mlmvpn.scanner.update.UpdateNet.client(context, 15, 30, directFirst = true).newBuilder().followRedirects(false).build()
    private val JSON = "application/json".toMediaType()

    /** `POST /login`, form `password=`; the session cookie(s) as one Cookie header value. */
    private fun login(context: Context, url: String, password: String): String {
        val req = Request.Builder().url("$url/login").post(FormBody.Builder().add("password", password).build()).build()
        client(context).newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val ok = runCatching { JSONObject(text).optBoolean("success") }.getOrDefault(false)
            val cookies = r.headers("Set-Cookie").map { it.substringBefore(';') }.filter { it.contains('=') }
            if (!ok || cookies.isEmpty()) error(tr("ورود به پنل نوا نشد", "Could not sign in to Nova") + " (HTTP ${r.code})")
            return cookies.joinToString("; ")
        }
    }

    /** Put Nova on [account] (or the developer's newest code on the one there), then claim it. */
    suspend fun deploy(context: Context, account: CloudAccount, onStep: (String) -> Unit): Result<Install> = withContext(Dispatchers.IO) {
        runCatching {
            onStep(tr("بررسی زیردامنه…", "Checking the subdomain…"))
            val sub = CfWorkers.subdomain(account)
            // The account's own Nova first (Windows' or this phone's): no second Worker, D1 or KV.
            com.mlmvpn.scanner.engines.cloud.PanelRegistry.live(account, CODE)?.takeIf { it.s.optString("password").isNotBlank() }?.let { g ->
                val shared = fromRecord(g)
                if (install(context, account.accountId) != shared) {
                    save(context, account.accountId, shared)
                    onStep(tr("همان نوای مشترک این حساب به کار می‌رود — ورکر یا دیتابیس تازه ساخته نمی‌شود.", "Using this account's shared Nova — no new Worker or database."))
                }
            }
            val prev = install(context, account.accountId)
            val script = prev?.script ?: (com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() + "-nva")
            val url = prev?.url ?: "https://$script.$sub.workers.dev"

            onStep(tr("دریافت کد از گیت‌هاب سازنده و بررسی امضا…", "Fetching the code from the developer's GitHub and checking it…"))
            val code = code(context)
            if (!looksLikeNova(code)) error(tr("کد دریافتی پنل نوا نیست.", "The code received is not Nova."))

            val d1 = prev?.d1Id?.ifBlank { null } ?: run {
                onStep(tr("ساخت دیتابیس D1…", "Creating the D1 database…"))
                CfWorkers.d1(account, "$script-db")
            }
            val kv = prev?.kvId?.ifBlank { null } ?: run {
                onStep(tr("ساخت فضای KV…", "Creating the KV namespace…"))
                CfWorkers.kv(account, "$script-kv")
            }
            onStep(tr("بارگذاری ورکر…", "Uploading the Worker…"))
            CfWorkers.upload(account, script, code,
                bindings = listOf(CfWorkers.d1Binding("DB", d1), CfWorkers.kvBinding("KV", kv)),
                flags = listOf("nodejs_compat"), compatibilityDate = COMPAT_DATE)
            onStep(tr("فعال کردن آدرس…", "Enabling the address…"))
            CfWorkers.enableWorkersDev(account, script)

            if (prev != null) return@runCatching prev.copy(d1Id = d1, kvId = kv).also { save(context, account.accountId, it); publish(account, it) }

            // First run: /install is public until a password is set, so it is set at once.
            onStep(tr("امن کردن پنل…", "Securing the panel…"))
            val password = random(24)
            var claimed = false
            var lastError = ""
            for (attempt in 0 until 8) {     // a new workers.dev name answers after a few seconds
                runCatching {
                    val req = Request.Builder().url("$url/install/set")
                        .post(JSONObject().put("password", password).toString().toRequestBody(JSON)).build()
                    client(context).newCall(req).execute().use { r ->
                        val o = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
                        if (r.isSuccessful && o?.optBoolean("success") == true) claimed = true
                        else lastError = o?.optString("error").orEmpty().ifBlank { "HTTP ${r.code}" }
                    }
                }.onFailure { lastError = it.message.orEmpty() }
                if (claimed || lastError == "already_configured") break
                delay(3000)
            }
            if (!claimed) error(tr("پنل نوا پس از نصب رمز نگرفت", "Nova did not take a password after the install") + " ($lastError)")
            val inst = Install(script, url, password, d1, kv)
            save(context, account.accountId, inst)
            publish(account, inst)
            inst
        }.onFailure { Log.w(TAG, "deploy", it) }
    }

    /** The panel's own subscription, as links. */
    suspend fun configs(context: Context, inst: Install): List<String> = withContext(Dispatchers.IO) {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                return@withContext configsOnce(context, inst)
            } catch (e: Exception) {
                last = e
                if (attempt < 3) delay(3000)
            }
        }
        throw last ?: IllegalStateException("no configs")
    }

    private fun md5hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Nova's subscription token for a host and UUID: md5 of characters 7..27 of md5(host + uuid) (its `He`). */
    fun subToken(host: String, uuid: String): String = md5hex(md5hex(host + uuid).substring(7, 27)).lowercase()

    private fun links(text: String): List<String> =
        text.split("\n").map { it.trim() }.filter { (it.startsWith("vless://") || it.startsWith("trojan://")) && !it.contains("@127.0.0.1:") }

    /**
     * The panel's subscription, read by the app itself: `GET /admin/config.json` gives the host and
     * UUID, from which the token is computed exactly as the panel does, and `/sub?token=` is fetched
     * directly. Nova's own `/admin/sub-content` asks the Worker to fetch itself, which Cloudflare
     * refuses on workers.dev (error 1042, measured 2026-09-28). The config's own `LINK` is the fallback.
     */
    private fun configsOnce(context: Context, inst: Install): List<String> {
        val cookie = login(context, inst.url, inst.password)
        val cfg = client(context).newCall(Request.Builder().url("${inst.url}/admin/config.json").header("Cookie", cookie).get().build())
            .execute().use { r ->
                if (!r.isSuccessful) error("config.json HTTP ${r.code}")
                JSONObject(r.body?.string().orEmpty())
            }
        val host = cfg.optString("HOST").ifBlank { inst.url.removePrefix("https://").substringBefore('/') }
        val uuid = cfg.optString("UUID")
        if (uuid.isNotBlank()) {
            val sub = runCatching {
                client(context).newCall(Request.Builder().url("${inst.url}/sub?token=${subToken(host, uuid)}&b64").get().build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val body = r.body?.string().orEmpty().trim()
                    val text = if (body.contains("://")) body
                        else runCatching { String(android.util.Base64.decode(body, android.util.Base64.DEFAULT)) }.getOrDefault(body)
                    links(text)
                }
            }.onFailure { Log.w(TAG, "sub", it) }.getOrNull().orEmpty()
            Log.i(TAG, "subscription: ${sub.size} links")
            if (sub.isNotEmpty()) return sub
        }
        return links(cfg.optString("LINK")).ifEmpty { error(tr("اشتراک نوا خالی بود.", "Nova's subscription was empty.")) }
    }

    suspend fun remove(context: Context, account: CloudAccount): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val inst = install(context, account.accountId) ?: return@runCatching
            CfWorkers.remove(account, inst.script, kvId = inst.kvId, d1Id = inst.d1Id)
            prefs(context).edit().remove("i_${account.accountId}").apply()
            com.mlmvpn.scanner.engines.cloud.PanelRegistry.removeIf(account, CODE, inst.script)
        }
    }
}
