package com.mlmvpn.scanner.engines.tgproxy

import com.mlmvpn.scanner.models.CloudAccount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloudflare Workers, for the Telegram proxy: upload the module with its bindings, switch on the
 * workers.dev address, read back who owns it, delete it. Every call goes through [TgCfApi].
 *
 * The three bindings are the secret (`PROXY_SECRET`, never readable again from Cloudflare), the
 * service id (`MLM_TGP_ID`, plain text, so ownership can be checked before a change or a delete),
 * and optionally `MAX_DIALS`.
 */
class TgWorkersOps(private val api: TgCfApi) {

    private fun account(a: CloudAccount) = "/accounts/${a.accountId}"

    /** The account's `<sub>.workers.dev` name, or null when it has none yet. */
    suspend fun subdomain(a: CloudAccount): String? {
        val r = api.send(a, "GET", "${account(a)}/workers/subdomain")
        return r.json.optJSONObject("result")?.optString("subdomain")?.takeIf { it.isNotBlank() }
    }

    suspend fun upload(a: CloudAccount, name: String, code: String, secret: String, serviceId: String) {
        val bindings = JSONArray()
            .put(JSONObject().put("type", "secret_text").put("name", "PROXY_SECRET").put("text", secret))
            .put(JSONObject().put("type", "plain_text").put("name", "MLM_TGP_ID").put("text", serviceId))
        val meta = JSONObject()
            .put("main_module", "worker.js")
            .put("compatibility_date", COMPATIBILITY_DATE)
            .put("bindings", bindings)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", "metadata.json", meta.toString().toRequestBody("application/json".toMediaType()))
            .addFormDataPart("worker.js", "worker.js", code.toRequestBody("application/javascript+module".toMediaType()))
            .build()
        api.send(a, "PUT", "${account(a)}/workers/scripts/$name", body, retries = 2)
    }

    /** Switches on `<name>.<sub>.workers.dev`. Safe to repeat. */
    suspend fun enable(a: CloudAccount, name: String) {
        api.send(a, "POST", "${account(a)}/workers/scripts/$name/subdomain", """{"enabled":true}""".toRequestBody("application/json".toMediaType()), retries = 2)
    }

    /**
     * The `MLM_TGP_ID` the script carries: who made it. Throws NOT_FOUND when the script does not
     * exist; returns null when it exists without the marker (not ours).
     */
    suspend fun owner(a: CloudAccount, name: String): String? {
        val r = api.send(a, "GET", "${account(a)}/workers/scripts/$name/settings")
        val bindings = r.json.optJSONObject("result")?.optJSONArray("bindings") ?: return null
        for (i in 0 until bindings.length()) {
            val b = bindings.optJSONObject(i) ?: continue
            if (b.optString("name") == "MLM_TGP_ID") return b.optString("text").takeIf { it.isNotBlank() }
        }
        return null
    }

    /** Deletes the script; a script that is already gone counts as deleted. */
    suspend fun delete(a: CloudAccount, name: String) {
        try {
            api.send(a, "DELETE", "${account(a)}/workers/scripts/$name?force=true")
        } catch (e: TgApiException) {
            if (e.kind != TgApiException.Kind.NOT_FOUND) throw e
        }
    }

    /** The names of this account's scripts that this feature made. */
    suspend fun names(a: CloudAccount): List<String> {
        val arr = api.send(a, "GET", "${account(a)}/workers/scripts").json.optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }.filter { it.startsWith(TgLink.PREFIX) }
    }

    companion object {
        /** Fixed and in the past: the phone's own date can be ahead of Cloudflare's. */
        const val COMPATIBILITY_DATE = "2024-09-23"
    }
}
