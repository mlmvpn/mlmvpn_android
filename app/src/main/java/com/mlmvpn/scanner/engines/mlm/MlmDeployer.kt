package com.mlmvpn.scanner.engines.mlm

import android.content.Context
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.utils.AntiDpi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MlmDeployer(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(com.mlmvpn.scanner.emergency.EmergencyInterceptor(context))
        .build()

    suspend fun deployMlm(account: CloudAccount, onProgress: (Int, String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val isCfat = account.token.startsWith("cfat_") || account.email.isEmpty()
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            // 1. Check Subdomain
            onProgress(10, "Checking subdomain...")
            var subdomain = ""
            val subReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .get().build()

            client.newCall(subReq).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    if (json.optBoolean("success")) {
                        subdomain = json.optJSONObject("result")?.optString("subdomain", "") ?: ""
                    }
                }
            }

            if (subdomain.isEmpty()) {
                onProgress(20, "Creating new subdomain...")
                var created = false
                var attempts = 0
                while (!created && attempts < 3) {
                    val randomSub = AntiDpi.generateSafeSubdomain()
                    val createReq = Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                        .headers(authHeaders)
                        .put("{\"subdomain\":\"$randomSub\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    client.newCall(createReq).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        if (response.isSuccessful) {
                            subdomain = randomSub
                            created = true
                        } else {
                            try {
                                val json = JSONObject(body)
                                val errors = json.optJSONArray("errors")
                                if (errors != null && errors.length() > 0) {
                                    val errorCode = errors.getJSONObject(0).optInt("code")
                                    if (errorCode == 10007) {
                                        return@withContext Pair(false, "ERR_ACCOUNT_HAS_SUBDOMAIN")
                                    }
                                }
                            } catch (e: Exception) { }
                        }
                    }
                    attempts++
                }
                if (!created) {
                    return@withContext Pair(false, "Failed to create a workers subdomain.")
                }
            }

            // 2. Reuse the existing D1 Database if this account was already deployed before
            // (a redeploy/update used to always create a brand-new D1 database and never
            // cleaned up the old one on failure, so repeated retries silently ate into the
            // account's D1 quota until every future deploy failed with no D1 left to create --
            // matching reports of "have to delete the previous version to make it work again").
            onProgress(40, "Setting up D1 Database...")
            var databaseId = account.mlmDbId?.takeIf { it.isNotEmpty() } ?: ""

            if (databaseId.isEmpty()) {
                // Look for a previously-created mlm_db_* database on this account before
                // provisioning a new one, in case an earlier failed/interrupted deploy already
                // created one that never got saved to account.mlmDbId.
                val listReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/d1/database")
                    .headers(authHeaders)
                    .get().build()
                client.newCall(listReq).execute().use { listRes ->
                    val listJson = JSONObject(listRes.body?.string() ?: "")
                    if (listJson.optBoolean("success")) {
                        val results = listJson.getJSONArray("result")
                        for (i in 0 until results.length()) {
                            val entry = results.getJSONObject(i)
                            if (entry.optString("name").startsWith("mlm_db_")) {
                                databaseId = entry.getString("uuid")
                                break
                            }
                        }
                    }
                }
            }

            if (databaseId.isEmpty()) {
                val dbName = "mlm_db_" + UUID.randomUUID().toString().substring(0, 6)
                val d1Req = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/d1/database")
                    .headers(authHeaders)
                    .post("{\"name\":\"$dbName\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                client.newCall(d1Req).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    try {
                        val json = JSONObject(body)
                        if (response.isSuccessful && json.optBoolean("success")) {
                            databaseId = json.getJSONObject("result").getString("uuid")
                        }
                    } catch (e: Exception) { }
                }
            }

            if (databaseId.isEmpty()) {
                return@withContext Pair(false, "Failed to create D1 Database. If you've retried this deploy several times, your Cloudflare account may have hit its D1 database limit -- delete unused databases at dash.cloudflare.com and try again.")
            }

            // 3. Upload Worker
            onProgress(60, "Uploading MLM Worker...")
            var workerScript = ""
            try {
                context.assets.open("mlm_worker.js").bufferedReader().use {
                    workerScript = it.readText()
                }
            } catch (e: Exception) {
                return@withContext Pair(false, "Failed to read mlm_worker.js from assets: ${e.message}")
            }

            val metadata = JSONObject().apply {
                put("main_module", "worker.js")
                // Fixed past date, matching every other deployer in this app (Nahan/SubGen/EDG/DNS/GST).
                // This used to be computed from the device's local date/timezone -- Cloudflare rejects a
                // compatibility_date in the future (UTC), and for timezones ahead of UTC (e.g. Iran,
                // UTC+3:30) the local calendar date rolls over before UTC's does, so between ~00:00 and
                // 03:30 local time the computed date was tomorrow in UTC and every upload failed.
                put("compatibility_date", "2024-03-03")
                val bindings = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "d1")
                        put("name", "DB")
                        put("id", databaseId)
                    })
                    val adminPass = if (!account.mlmAdminPassword.isNullOrEmpty()) account.mlmAdminPassword!! else "admin"
                    put(JSONObject().apply {
                        put("type", "plain_text")
                        put("name", "ADMIN_PASSWORD")
                        put("text", adminPass)
                    })
                    put(JSONObject().apply {
                        put("type", "plain_text")
                        put("name", "DEBUG")
                        put("text", "1")
                    })
                }
                put("bindings", bindings)
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", metadata.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addFormDataPart("worker.js", "worker.js", workerScript.toRequestBody("application/javascript+module".toMediaTypeOrNull()))
                .build()

            val workerName = AntiDpi.generateSafeWorkerName() + "-mlm"
            val uploadReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .put(multipartBody)
                .build()

            client.newCall(uploadReq).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to upload MLM worker: $body")
            }

            // 4. Enable Subdomain
            onProgress(80, "Enabling subdomain routing...")
            val enableReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName/subdomain")
                .headers(authHeaders)
                .post("{\"enabled\":true}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            client.newCall(enableReq).execute().use { response ->
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to enable subdomain: ${response.body?.string()}")
            }

            // 5. Finalize
            onProgress(100, "Done!")
            val finalUrl = "https://$workerName.$subdomain.workers.dev"

            account.mlmStatus = "deployed"
            account.mlmWorkerUrl = finalUrl
            account.mlmDbId = databaseId
            if (account.mlmAdminPassword.isNullOrEmpty()) account.mlmAdminPassword = "admin"

            Pair(true, "Deployment Successful! URL: $finalUrl")

        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }
}
