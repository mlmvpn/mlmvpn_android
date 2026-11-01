package com.mlmvpn.scanner.engines.cloud

import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.tr
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The Cloudflare calls every third-party panel deploy needs, in one place: the account's
 * workers.dev subdomain, a KV namespace or D1 database (found again when an interrupted deploy
 * already made it), a module upload with its bindings, the workers.dev route, and removal.
 *
 * Spider, Netra and Gozargah deploy through this. Blocking calls; callers run them on IO.
 */
object CfWorkers {

    private val http = OkHttpClient.Builder().dns(WorkerRoute.dns()).protocols(WorkerRoute.HTTP1).socketFactory(WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector()).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    private fun api(account: CloudAccount) = "https://api.cloudflare.com/client/v4/accounts/${account.accountId}"

    fun cfMessage(body: String?): String = runCatching {
        JSONObject(body.orEmpty()).optJSONArray("errors")?.optJSONObject(0)?.optString("message")
    }.getOrNull()?.ifBlank { null } ?: body.orEmpty().take(160)

    /** The account's `<sub>.workers.dev` name; throws when the account has none yet. */
    fun subdomain(account: CloudAccount): String {
        val sub = http.newCall(Request.Builder().url("${api(account)}/workers/subdomain").headers(CloudAuth.headers(account)).get().build())
            .execute().use { r -> runCatching { JSONObject(r.body?.string().orEmpty()).optJSONObject("result")?.optString("subdomain") }.getOrNull().orEmpty() }
        if (sub.isBlank()) error(tr("این حساب هنوز زیردامنهٔ workers.dev ندارد.", "This account has no workers.dev subdomain yet."))
        return sub
    }

    /**
     * The account's `<sub>.workers.dev` name, made with a neutral one when the account has none yet
     * (what the BPB and Edge deploys do). A refusal because the account already has one — the other
     * device made it a moment ago — reads it instead.
     */
    fun ensureSubdomain(account: CloudAccount): String {
        runCatching { return subdomain(account) }
        val headers = CloudAuth.headers(account)
        repeat(3) {
            val name = com.mlmvpn.scanner.utils.AntiDpi.generateSafeSubdomain()
            http.newCall(Request.Builder().url("${api(account)}/workers/subdomain").headers(headers)
                .put(JSONObject().put("subdomain", name).toString().toRequestBody(JSON)).build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) return name
                if (text.contains("10007")) return subdomain(account)
            }
        }
        error(tr("ساخت زیردامنهٔ workers.dev نشد. یکی را در داشبورد کلادفلر بسازید.", "Could not create a workers.dev subdomain. Create one in the Cloudflare dashboard."))
    }

    /** A KV namespace titled [title]: made, or found when a namespace of that title exists. */
    fun kv(account: CloudAccount, title: String): String = createOrFind(
        account, "storage/kv/namespaces", JSONObject().put("title", title), "title", title, "id",
        tr("ساخت KV نشد", "Could not create the KV namespace"),
    )

    /** A D1 database named [name]: made, or found when one of that name exists. */
    fun d1(account: CloudAccount, name: String): String = createOrFind(
        account, "d1/database", JSONObject().put("name", name), "name", name, "uuid",
        tr("ساخت دیتابیس D1 نشد", "Could not create the D1 database"),
    )

    private fun createOrFind(account: CloudAccount, path: String, body: JSONObject, key: String, value: String, idField: String, failure: String): String {
        val headers = CloudAuth.headers(account)
        http.newCall(Request.Builder().url("${api(account)}/$path").headers(headers).post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val id = runCatching { JSONObject(text).optJSONObject("result")?.optString(idField) }.getOrNull()
            if (r.isSuccessful && !id.isNullOrBlank()) return id
            // Made by an earlier, interrupted deploy: find it by name rather than fail.
            http.newCall(Request.Builder().url("${api(account)}/$path?per_page=100").headers(headers).get().build()).execute().use { l ->
                val arr = runCatching { JSONObject(l.body?.string().orEmpty()).optJSONArray("result") }.getOrNull()
                for (i in 0 until (arr?.length() ?: 0)) {
                    val o = arr!!.getJSONObject(i)
                    if (o.optString(key) == value) return o.optString(idField)
                }
            }
            error("$failure (HTTP ${r.code}): " + cfMessage(text))
        }
    }

    fun kvBinding(name: String, id: String) = JSONObject().put("type", "kv_namespace").put("name", name).put("namespace_id", id)
    fun d1Binding(name: String, id: String) = JSONObject().put("type", "d1").put("name", name).put("id", id)
    fun secret(name: String, value: String) = JSONObject().put("type", "secret_text").put("name", name).put("text", value)

    /** Upload [code] as the ES module `worker.js` of [script], with [bindings] and [flags]. */
    fun upload(account: CloudAccount, script: String, code: String, bindings: List<JSONObject>, flags: List<String> = emptyList(), compatibilityDate: String = "2024-09-23") {
        val meta = JSONObject().apply {
            put("main_module", "worker.js")
            put("compatibility_date", compatibilityDate)
            if (flags.isNotEmpty()) put("compatibility_flags", JSONArray(flags))
            put("bindings", JSONArray(bindings))
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", "metadata.json", meta.toString().toRequestBody(JSON))
            .addFormDataPart("worker.js", "worker.js", code.toRequestBody("application/javascript+module".toMediaType()))
            .build()
        http.newCall(Request.Builder().url("${api(account)}/workers/scripts/$script").headers(CloudAuth.headers(account)).put(body).build()).execute().use { r ->
            if (!r.isSuccessful) error(tr("بارگذاری ورکر رد شد", "Uploading the Worker failed") + " (HTTP ${r.code}): " + cfMessage(r.body?.string()))
        }
    }

    /**
     * Upload several ES modules as one Worker: each part is named by its module path, as `wrangler`
     * does, and [mainModule] is the one the Worker starts from. The central Worker (assets/edge_gateway/)
     * is seven modules.
     */
    fun uploadModules(
        account: CloudAccount, script: String, mainModule: String, files: List<Pair<String, String>>,
        bindings: List<JSONObject>, compatibilityDate: String = "2024-09-23",
    ) {
        val meta = JSONObject().put("main_module", mainModule).put("compatibility_date", compatibilityDate).put("bindings", JSONArray(bindings))
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", "metadata.json", meta.toString().toRequestBody(JSON)).apply {
                for ((name, content) in files) addFormDataPart(name, name, content.toRequestBody("application/javascript+module".toMediaType()))
            }.build()
        http.newCall(Request.Builder().url("${api(account)}/workers/scripts/$script").headers(CloudAuth.headers(account)).put(body).build()).execute().use { r ->
            if (!r.isSuccessful) error(tr("بارگذاری ورکر رد شد", "Uploading the Worker failed") + " (HTTP ${r.code}): " + cfMessage(r.body?.string()))
        }
    }

    /** Turn on `<script>.<sub>.workers.dev`. */
    fun enableWorkersDev(account: CloudAccount, script: String) {
        http.newCall(Request.Builder().url("${api(account)}/workers/scripts/$script/subdomain").headers(CloudAuth.headers(account))
            .post("{\"enabled\":true}".toRequestBody(JSON)).build()).execute().use { r ->
            if (!r.isSuccessful) error(tr("فعال کردن آدرس ورکر نشد", "Could not enable the Worker address") + " (HTTP ${r.code})")
        }
    }

    /** Delete the script (404 counts as gone) and, best effort, its KV namespace / D1 database. */
    fun remove(account: CloudAccount, script: String, kvId: String? = null, d1Id: String? = null) {
        val headers = CloudAuth.headers(account)
        http.newCall(Request.Builder().url("${api(account)}/workers/scripts/$script?force=true").headers(headers).delete().build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 404) error("HTTP ${r.code}: " + cfMessage(r.body?.string()))
        }
        kvId?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { http.newCall(Request.Builder().url("${api(account)}/storage/kv/namespaces/$id").headers(headers).delete().build()).execute().close() }
        }
        d1Id?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { http.newCall(Request.Builder().url("${api(account)}/d1/database/$id").headers(headers).delete().build()).execute().close() }
        }
    }
}
