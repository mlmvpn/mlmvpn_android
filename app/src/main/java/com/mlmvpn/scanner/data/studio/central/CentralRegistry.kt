package com.mlmvpn.scanner.data.studio.central

import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.engines.cloud.CfTunnel
import com.mlmvpn.scanner.engines.cloud.PanelRegistry
import com.mlmvpn.scanner.engines.cloud.WorkerRoute
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
 * The Studio's record on the account: key [Central.CODE] of the KV namespace `mlmvpn-panels`, the
 * same registry the panels and the central Worker (`GW`) live in, written in the same envelope
 * (`{v, code, script, url, kv, d1, s, by, at}`, docs/PANEL-REGISTRY.md). It is bound to no Worker:
 * only the account's own credential reads it.
 *
 * Stricter than [PanelRegistry] on purpose, because what it guards is people's access: a listing
 * that failed is an ERROR here, never «there is no record» — writing the Studio's list over one
 * that was merely unreadable would erase what another phone put there. Only a credential that may
 * not touch KV at all reads as [RegistryRead.NoAccess].
 */
object CentralRegistry {

    private val http = OkHttpClient.Builder().dns(WorkerRoute.dns()).protocols(WorkerRoute.HTTP1)
        .socketFactory(WorkerRoute.cfSocketFactory()).proxySelector(CfTunnel.selector())
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val TEXT = "text/plain".toMediaType()
    private val JSON = "application/json".toMediaType()

    /** Cloudflare account id → the namespace's id, once found. */
    private val nsCache = ConcurrentHashMap<String, String>()

    private fun api(account: CloudAccount) = "https://api.cloudflare.com/client/v4/accounts/${account.accountId}"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private class NoKvAccess : Exception("no KV access")

    /** The namespace's id, null when the account has none. Throws on anything but a clean answer. */
    private fun namespace(account: CloudAccount): String? {
        nsCache[account.accountId]?.let { return it }
        var page = 1
        while (page <= 20) {
            val req = Request.Builder().url("${api(account)}/storage/kv/namespaces?per_page=100&page=$page")
                .headers(CloudAuth.headers(account)).get().build()
            val arr = http.newCall(req).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw NoKvAccess()
                if (!r.isSuccessful) error("namespaces HTTP ${r.code}")
                val body = JSONObject(r.body?.string().orEmpty())
                if (!body.optBoolean("success", true)) error("namespaces: ${body.optJSONArray("errors")}")
                body.optJSONArray("result") ?: error("namespaces: no result")
            }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("title") == PanelRegistry.NS_TITLE) {
                    val id = o.optString("id").ifBlank { error("namespace without an id") }
                    nsCache[account.accountId] = id
                    return id
                }
            }
            if (arr.length() < 100) return null
            page++
        }
        error("namespaces: too many pages")
    }

    /** The account's record, exactly as stored. */
    fun read(account: CloudAccount): RegistryRead {
        val ns = try {
            namespace(account) ?: return RegistryRead.Missing
        } catch (e: NoKvAccess) {
            return RegistryRead.NoAccess
        }
        val req = Request.Builder().url("${api(account)}/storage/kv/namespaces/$ns/values/${enc(Central.CODE)}")
            .headers(CloudAuth.headers(account)).get().build()
        return http.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> RegistryRead.Missing
                r.code == 401 || r.code == 403 -> RegistryRead.NoAccess
                !r.isSuccessful -> error("registry HTTP ${r.code}")
                else -> {
                    val text = r.body?.string().orEmpty()
                    // A value that is not our envelope is treated as unreadable, not as empty: it is
                    // someone's data, and writing over it is not this code's call to make.
                    val o = runCatching { JSONObject(text) }.getOrNull() ?: error("registry value is not JSON")
                    val s = o.optJSONObject("s") ?: error("registry value has no s")
                    RegistryRead.Found(o.optString("script"), o.optString("url"), s, o.optString("by"), o.optLong("at"))
                }
            }
        }
    }

    /** Write the record (making the namespace when the account has none yet). */
    fun put(account: CloudAccount, script: String, url: String, s: JSONObject) {
        val ns = namespace(account) ?: create(account)
        val body = JSONObject().put("v", 1).put("code", Central.CODE).put("script", script).put("url", url)
            .put("kv", JSONObject.NULL).put("d1", JSONObject.NULL).put("s", s)
            .put("by", "android").put("at", System.currentTimeMillis())
        val headers = CloudAuth.headers(account).newBuilder().set("Content-Type", "text/plain").build()
        val req = Request.Builder().url("${api(account)}/storage/kv/namespaces/$ns/values/${enc(Central.CODE)}")
            .headers(headers).put(body.toString().toRequestBody(TEXT)).build()
        http.newCall(req).execute().use { r -> if (!r.isSuccessful) error("registry write HTTP ${r.code}") }
    }

    /** Forget the record, but only while it still names [script]. */
    fun removeIf(account: CloudAccount, script: String) {
        val cur = read(account) as? RegistryRead.Found ?: return
        if (cur.script != script) return
        val ns = namespace(account) ?: return
        val req = Request.Builder().url("${api(account)}/storage/kv/namespaces/$ns/values/${enc(Central.CODE)}")
            .headers(CloudAuth.headers(account)).delete().build()
        http.newCall(req).execute().use { r -> if (!r.isSuccessful && r.code != 404) error("registry delete HTTP ${r.code}") }
    }

    /** The namespace, made now; the other device may have made it a moment ago, so look once more. */
    private fun create(account: CloudAccount): String {
        val req = Request.Builder().url("${api(account)}/storage/kv/namespaces").headers(CloudAuth.headers(account))
            .post(JSONObject().put("title", PanelRegistry.NS_TITLE).toString().toRequestBody(JSON)).build()
        val made = http.newCall(req).execute().use { r ->
            runCatching { JSONObject(r.body?.string().orEmpty()).optJSONObject("result")?.optString("id") }.getOrNull()?.ifBlank { null }
        }
        if (made != null) { nsCache[account.accountId] = made; return made }
        return namespace(account) ?: error("the registry namespace could not be made")
    }
}
