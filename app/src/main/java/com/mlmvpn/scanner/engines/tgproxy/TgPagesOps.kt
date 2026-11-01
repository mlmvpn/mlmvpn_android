package com.mlmvpn.scanner.engines.tgproxy

import com.mlmvpn.scanner.models.CloudAccount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Cloudflare Pages, for the Telegram proxy: a Direct Upload project whose `_worker.js` is the same
 * relay script the Workers path uploads.
 *
 * Pages differs from Workers in ways that matter here:
 *  - the secret and the service id are project environment variables (`secret_text`/`plain_text`),
 *    not script bindings; they are read at the deployment, so they go in before the first one;
 *  - a deployment is built asynchronously: the upload returns at once and the project answers on
 *    `<name>.pages.dev` only when the deploy stage has succeeded -- hence [stage];
 *  - a Pages project cannot define a Durable Object, which the relay does not use;
 *  - an account holds at most 100 projects and 500 builds a month on the free plan.
 */
class TgPagesOps(private val api: TgCfApi) {

    private fun account(a: CloudAccount) = "/accounts/${a.accountId}/pages/projects"

    /** Creates the project; one that already exists (a resumed build) is fine. */
    suspend fun create(a: CloudAccount, name: String, secret: String, serviceId: String) {
        val env = JSONObject()
            .put("PROXY_SECRET", JSONObject().put("type", "secret_text").put("value", secret))
            .put("MLM_TGP_ID", JSONObject().put("type", "plain_text").put("value", serviceId))
        val config = JSONObject().put("env_vars", env).put("compatibility_date", TgWorkersOps.COMPATIBILITY_DATE)
        val body = JSONObject()
            .put("name", name)
            .put("production_branch", BRANCH)
            .put("deployment_configs", JSONObject().put("production", config).put("preview", config))
        try {
            api.send(a, "POST", account(a), body.toString().toRequestBody("application/json".toMediaType()))
        } catch (e: TgApiException) {
            if (e.kind != TgApiException.Kind.CONFLICT) throw e
        }
    }

    /** Uploads `_worker.js` as a production deployment and returns the deployment id. */
    suspend fun deploy(a: CloudAccount, name: String, code: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("manifest", "{}")
            .addFormDataPart("branch", BRANCH)
            .addFormDataPart("_worker.js", "_worker.js", code.toRequestBody("application/javascript".toMediaType()))
            .build()
        val r = api.send(a, "POST", "${account(a)}/$name/deployments", body)
        return r.json.optJSONObject("result")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: throw TgApiException(TgApiException.Kind.OTHER, r.http, null)
    }

    /** What the deployment is doing: (stage name, status) of its latest stage, e.g. ("deploy", "success"). */
    suspend fun stage(a: CloudAccount, name: String, deploymentId: String): Pair<String, String> {
        val result = api.send(a, "GET", "${account(a)}/$name/deployments/$deploymentId").json.optJSONObject("result")
        val latest = result?.optJSONObject("latest_stage")
        // Any failed stage is a failure, whichever one is latest.
        val stages = result?.optJSONArray("stages")
        if (stages != null) {
            for (i in 0 until stages.length()) {
                val s = stages.optJSONObject(i) ?: continue
                if (s.optString("status") == "failure") return s.optString("name") to "failure"
            }
        }
        return (latest?.optString("name") ?: "") to (latest?.optString("status") ?: "")
    }

    /** The project: its `subdomain` (`<name>.pages.dev`) and environment variables. */
    suspend fun project(a: CloudAccount, name: String): JSONObject =
        api.send(a, "GET", "${account(a)}/$name").json.optJSONObject("result") ?: JSONObject()

    fun hostOf(project: JSONObject): String = project.optString("subdomain").trim().lowercase()

    /** The `MLM_TGP_ID` the project carries, or null when it has none (not ours). Throws NOT_FOUND if missing. */
    suspend fun owner(a: CloudAccount, name: String): String? = ownerOf(project(a, name))

    fun ownerOf(project: JSONObject): String? =
        project.optJSONObject("deployment_configs")?.optJSONObject("production")?.optJSONObject("env_vars")
            ?.optJSONObject("MLM_TGP_ID")?.optString("value")?.takeIf { it.isNotBlank() }

    suspend fun delete(a: CloudAccount, name: String) {
        try {
            api.send(a, "DELETE", "${account(a)}/$name")
        } catch (e: TgApiException) {
            if (e.kind != TgApiException.Kind.NOT_FOUND) throw e
        }
    }

    suspend fun names(a: CloudAccount): List<String> {
        val arr = api.send(a, "GET", account(a)).json.optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.filter { it.startsWith(TgLink.PREFIX) }
    }

    companion object {
        const val BRANCH = "main"
    }
}
