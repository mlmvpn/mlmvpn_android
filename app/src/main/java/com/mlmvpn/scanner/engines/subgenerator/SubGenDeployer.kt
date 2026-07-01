package com.mlmvpn.scanner.engines.subgenerator

import android.content.Context
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SubGenDeployer(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun deploySubWorker(account: CloudAccount, onProgress: (Int, String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
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
            onProgress(10, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_checking_sub))
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
                onProgress(20, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_creating_sub))
                var created = false
                var attempts = 0
                while (!created && attempts < 3) {
                    val randomSub = com.mlmvpn.scanner.utils.AntiDpi.generateSafeSubdomain()
                    val createReq = Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                        .headers(authHeaders)
                        .put("{\"subdomain\":\"$randomSub\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    client.newCall(createReq).execute().use { response ->
                        if (response.isSuccessful) {
                            subdomain = randomSub
                            created = true
                        } else {
                            try {
                                val body = response.body?.string() ?: ""
                                val json = org.json.JSONObject(body)
                                val errors = json.optJSONArray("errors")
                                if (errors != null && errors.length() > 0) {
                                    if (errors.getJSONObject(0).optInt("code") == 10007) {
                                        return@withContext Pair(false, "ERR_ACCOUNT_HAS_SUBDOMAIN")
                                    }
                                }
                            } catch (e: Exception) { }
                        }
                    }
                    attempts++
                }
                if (!created) {
                    return@withContext Pair(false, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_sub_error))
                }
            }

            // 2. Create/Get KV Namespace
            onProgress(40, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_kv))
            var namespaceId = ""
            var kvErrorDetails = ""
            val kvReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces")
                .headers(authHeaders)
                .post("{\"title\":\"subgen_kv\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
                
            client.newCall(kvReq).execute().use { response ->
                val body = response.body?.string() ?: ""
                kvErrorDetails = body
                val json = try { JSONObject(body) } catch(e:Exception) { null }
                if (response.isSuccessful && json?.optBoolean("success") == true) {
                    namespaceId = json.getJSONObject("result").getString("id")
                } else {
                    // Already exists or other error, let's try to find it in the list
                    val listReq = Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces?per_page=100")
                        .headers(authHeaders)
                        .get().build()
                    client.newCall(listReq).execute().use { listRes ->
                        val listBody = listRes.body?.string() ?: ""
                        if (!listRes.isSuccessful) {
                            kvErrorDetails += "\nList Error: $listBody"
                        }
                        val listJson = try { JSONObject(listBody) } catch(e:Exception){ null }
                        val results = listJson?.optJSONArray("result")
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val item = results.getJSONObject(i)
                                val title = item.optString("title", "")
                                if (title.contains("subgen_kv", ignoreCase = true)) {
                                    namespaceId = item.getString("id")
                                    break
                                }
                            }
                        }
                    }
                }
            }
            if (namespaceId.isEmpty()) {
                android.util.Log.e("SubGenDeployer", "KV Error Details: $kvErrorDetails")
                if (kvErrorDetails.contains("10000") || kvErrorDetails.contains("Authentication error")) {
                    return@withContext Pair(false, "خطای دسترسی کلادفلر: توکن شما دسترسی Workers KV Storage ندارد. لطفاً در تنظیمات توکن دسترسی KV را اضافه کنید.")
                }
                return@withContext Pair(false, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_kv_error) + "\n" + kvErrorDetails)
            }

            // 3. Upload Worker
            onProgress(60, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_upload))
            var workerScript = ""
            try {
                context.assets.open("sub_worker.js").bufferedReader().use {
                    workerScript = it.readText()
                }
            } catch (e: Exception) {
                return@withContext Pair(false, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_script_error, e.message))
            }

            val metadata = JSONObject().apply {
                put("main_module", "sub_worker.js")
                put("compatibility_date", "2024-03-03")
                val bindings = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "kv_namespace")
                        put("name", "kv")
                        put("namespace_id", namespaceId)
                    })
                }
                put("bindings", bindings)
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", metadata.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addFormDataPart("sub_worker.js", "sub_worker.js", workerScript.toRequestBody("application/javascript+module".toMediaTypeOrNull()))
                .build()

            val workerName = "sub-generator-" + java.util.UUID.randomUUID().toString().substring(0, 6)
            val uploadReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .put(multipartBody)
                .build()

            client.newCall(uploadReq).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) return@withContext Pair(false, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_upload_error, body))
            }

            // 4. Enable Subdomain Route
            onProgress(80, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_enabling))
            val enableReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName/subdomain")
                .headers(authHeaders)
                .post("{\"enabled\":true}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            
            client.newCall(enableReq).execute().use { response ->
                if (!response.isSuccessful) return@withContext Pair(false, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_enable_error, response.body?.string()))
            }

            onProgress(100, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_finish))
            val finalUrl = "https://$workerName.$subdomain.workers.dev"

            // Save to manager
            val manager = SubGenManager(context)
            manager.saveAccountData(account.id, workerName, finalUrl, namespaceId)

            Pair(true, context.getString(com.mlmvpn.scanner.R.string.subgen_deploy_success, finalUrl))
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }
}
