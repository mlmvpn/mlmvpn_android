package com.mlmvpn.scanner.engines.cloud

import android.util.Log
import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.models.CloudAccount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One install per panel per Cloudflare account, shared by the phone and the Windows app.
 *
 * The user's rule (2026-09-30): «تک‌تک پنل‌های روی گوشی و ویندوز باید با هم هماهنگ بشن؛ برای
 * هرکدام ورکر یا KV یا D1 جدا ساخته نشه و اطلاعات هردو مشترک باشه. هر پنل فقط یک KV یا D1
 * بسازه؛ اگر قدیمی داره از همون استفاده کنه.» Each app kept its install records on the device, so
 * the other device knew nothing, made its own Worker and its own storage — and one account ran out
 * of its ten free D1 databases.
 *
 * The registry lives ON THE ACCOUNT: a KV namespace titled `mlmvpn-panels`, one key per panel
 * code, value = the install record — the SAME JSON the Windows app writes (panel-registry.js,
 * docs/PANEL-REGISTRY.md):
 *
 *     { "v": 1, "code": "NTR", "script": "…", "url": "https://….workers.dev",
 *       "kv": "<id>" | null, "d1": "<id>" | null, "s": { …credentials… },
 *       "by": "android" | "windows", "at": <ms> }
 *
 * `s` per code: NTR {uuid,trPass,subPath} · GZG {panelPath,subPath,password} · NVA {password} ·
 * SPD {token} · NHN {masterKey,apiRoute} · MLM {password} · BPB {uuid,trPass,subPath} · EDG {uuid}
 * · ZEU {password?}.
 *
 * It is never bound to any Worker, so no panel code can read it; only the account's own API
 * credential can — the one both apps already hold. Every panel's deploy reads it first (the
 * account's install wins over making another) and writes it after.
 */
object PanelRegistry {

    private const val TAG = "PanelRegistry"
    const val NS_TITLE = "mlmvpn-panels"

    data class Record(
        val code: String, val script: String, val url: String,
        val kv: String?, val d1: String?, val s: JSONObject, val by: String, val at: Long,
    )

    private val http = OkHttpClient.Builder().dns(WorkerRoute.dns()).protocols(WorkerRoute.HTTP1).socketFactory(WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector())
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()
    private val TEXT = "text/plain".toMediaType()
    private val nsCache = ConcurrentHashMap<String, String>()

    private fun api(account: CloudAccount) = "https://api.cloudflare.com/client/v4/accounts/${account.accountId}"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The registry namespace's id; made only when [create] and it is not there yet. */
    fun namespace(account: CloudAccount, create: Boolean = false): String? {
        nsCache[account.accountId]?.let { return it }
        val headers = CloudAuth.headers(account)
        var found: String? = null
        var page = 1
        while (page <= 20 && found == null) {
            val arr = http.newCall(Request.Builder().url("${api(account)}/storage/kv/namespaces?per_page=100&page=$page").headers(headers).get().build())
                .execute().use { r -> runCatching { JSONObject(r.body?.string().orEmpty()).optJSONArray("result") }.getOrNull() } ?: break
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("title") == NS_TITLE) { found = o.optString("id"); break }
            }
            if (arr.length() < 100) break
            page++
        }
        if (found == null && create) {
            found = http.newCall(Request.Builder().url("${api(account)}/storage/kv/namespaces").headers(headers)
                .post(JSONObject().put("title", NS_TITLE).toString().toRequestBody(JSON)).build()).execute().use { r ->
                runCatching { JSONObject(r.body?.string().orEmpty()).optJSONObject("result")?.optString("id") }.getOrNull()?.ifBlank { null }
            } ?: return namespace(account, create = false)   // made by the other device a moment ago
        }
        found?.let { nsCache[account.accountId] = it }
        return found
    }

    /** The account's install of panel [code], or null when the registry has none. */
    fun get(account: CloudAccount, code: String): Record? {
        val ns = namespace(account) ?: return null
        http.newCall(Request.Builder().url("${api(account)}/storage/kv/namespaces/$ns/values/${enc(code)}").headers(CloudAuth.headers(account)).get().build())
            .execute().use { r ->
                if (r.code == 404) return null
                if (!r.isSuccessful) error("registry HTTP ${r.code}")
                val o = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull() ?: return null
                val script = o.optString("script").ifBlank { return null }
                return Record(
                    code = code, script = script, url = o.optString("url"),
                    kv = o.optString("kv").takeIf { it.isNotBlank() && it != "null" },
                    d1 = o.optString("d1").takeIf { it.isNotBlank() && it != "null" },
                    s = o.optJSONObject("s") ?: JSONObject(), by = o.optString("by"), at = o.optLong("at"),
                )
            }
    }

    /** Write the account's install of [code] — this phone's, from now on the one both apps use. */
    fun put(account: CloudAccount, code: String, script: String, url: String, kv: String?, d1: String?, s: JSONObject) {
        val ns = namespace(account, create = true) ?: error("registry namespace")
        val body = JSONObject().put("v", 1).put("code", code).put("script", script).put("url", url)
            .put("kv", kv ?: JSONObject.NULL).put("d1", d1 ?: JSONObject.NULL).put("s", s)
            .put("by", "android").put("at", System.currentTimeMillis())
        val headers = CloudAuth.headers(account).newBuilder().set("Content-Type", "text/plain").build()
        http.newCall(Request.Builder().url("${api(account)}/storage/kv/namespaces/$ns/values/${enc(code)}").headers(headers)
            .put(body.toString().toRequestBody(TEXT)).build()).execute().use { r ->
            if (!r.isSuccessful) error("registry write HTTP ${r.code}")
        }
    }

    /** Forget [code] (its Worker was removed), only when the record still points at [script]. */
    fun removeIf(account: CloudAccount, code: String, script: String) {
        runCatching {
            val cur = get(account, code) ?: return
            if (cur.script != script) return
            val ns = namespace(account) ?: return
            http.newCall(Request.Builder().url("${api(account)}/storage/kv/namespaces/$ns/values/${enc(code)}").headers(CloudAuth.headers(account)).delete().build()).execute().close()
        }.onFailure { Log.w(TAG, "removeIf $code", it) }
    }

    /** Whether a Worker named [script] is on the account. */
    fun scriptExists(account: CloudAccount, script: String): Boolean = runCatching {
        http.newCall(Request.Builder().url("${api(account)}/workers/scripts/${enc(script)}/settings").headers(CloudAuth.headers(account)).get().build())
            .execute().use { it.code == 200 }
    }.getOrDefault(false)

    /**
     * The account's install of [code] when there is one and its Worker is still there — what every
     * deploy asks first. Failures read as «none» (the deploy then goes on as before), never as a
     * reason to stop.
     */
    fun live(account: CloudAccount, code: String): Record? = runCatching {
        get(account, code)?.takeIf { scriptExists(account, it.script) }
    }.onFailure { Log.w(TAG, "live $code", it) }.getOrNull()

    /** [put], never throwing: the install works whether or not the account could be told. */
    fun publish(account: CloudAccount, code: String, script: String, url: String, kv: String?, d1: String?, s: JSONObject) {
        runCatching { put(account, code, script, url, kv, d1, s) }.onFailure { Log.w(TAG, "publish $code", it) }
    }

    /** `https://abc-ntr.sub.workers.dev` → `abc-ntr`. */
    fun scriptOf(url: String?): String? = url?.substringAfter("://")?.substringBefore('.')?.takeIf { it.isNotBlank() }
}
