package com.mlmvpn.scanner.engines.nahan

import android.content.Context
import android.util.Base64
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

private class NahanNonRetryableException(message: String) : Exception(message)

class NahanDeployer(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(com.mlmvpn.scanner.emergency.EmergencyInterceptor(context))
        .build()

    suspend fun deployNahan(account: CloudAccount, onProgress: (Int, String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
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
                    return@withContext Pair(false, "Failed to create a workers subdomain. Please create one manually in Cloudflare dashboard.")
                }
            }

            // 2. Reuse the existing D1 Database if this account was already deployed before,
            // instead of always provisioning a new one (see MlmDeployer.kt for why: repeated
            // retries used to silently eat into the account's D1 quota).
            onProgress(40, "Setting up D1 Database...")
            var databaseId = account.nahanDbId?.takeIf { it.isNotEmpty() } ?: ""

            if (databaseId.isEmpty()) {
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
                            if (entry.optString("name").startsWith("nhn_db_")) {
                                databaseId = entry.getString("uuid")
                                break
                            }
                        }
                    }
                }
            }

            if (databaseId.isEmpty()) {
                val dbName = "nhn_db_" + UUID.randomUUID().toString().substring(0, 6)
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
            onProgress(60, "Uploading Nahan Worker...")
            var workerScript = ""
            try {
                context.assets.open("nahan_worker.js").bufferedReader().use {
                    workerScript = it.readText()
                }
            } catch (e: Exception) {
                return@withContext Pair(false, "Failed to read nahan_worker.js from assets: ${e.message}")
            }

            val metadata = JSONObject().apply {
                put("main_module", "worker.js")
                put("compatibility_date", "2024-03-03")
                val bindings = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "d1")
                        put("name", "IOT_DB")
                        put("id", databaseId)
                    })
                }
                put("bindings", bindings)
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", metadata.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addFormDataPart("worker.js", "worker.js", workerScript.toRequestBody("application/javascript+module".toMediaTypeOrNull()))
                .build()

            val workerName = AntiDpi.generateSafeWorkerName() + "-nhn"
            val uploadReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .put(multipartBody)
                .build()

            client.newCall(uploadReq).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to upload NHN worker: $body")
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

            account.nahanStatus = "deployed"
            account.nahanWorkerUrl = finalUrl
            account.nahanDbId = databaseId
            account.nahanMasterKey = "admin" // Default master key
            account.nahanApiRoute = "sync"

            // Note: Caller is responsible for saving the account logic.
            Pair(true, "Deployment Successful! URL: $finalUrl")

        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    private fun getAuthKey(account: CloudAccount): String {
        return if (account.nahanMasterKey.isNullOrEmpty()) "admin" else account.nahanMasterKey!!
    }

    suspend fun fetchSettings(account: CloudAccount): NahanConfig? = withContext(Dispatchers.IO) {
        if (account.nahanWorkerUrl.isNullOrEmpty()) return@withContext null
        try {
            val masterKey = getAuthKey(account)
            val baseUrl = account.nahanWorkerUrl!!.trimEnd('/')
            val apiRoute = (account.nahanApiRoute ?: "sync").trim('/')
            val authUrl = "$baseUrl/$apiRoute/api/auth"
            
            val jsonPayload = JSONObject().apply {
                put("key", masterKey)
            }
            
            val req = Request.Builder()
                .url(authUrl)
                .post(jsonPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    if (json.optBoolean("success")) {
                        val configObj = json.optJSONObject("config")
                        if (configObj != null) {
                            return@withContext NahanConfig.fromJson(configObj)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun syncSettings(account: CloudAccount, config: NahanConfig): Boolean = withContext(Dispatchers.IO) {
        if (account.nahanWorkerUrl.isNullOrEmpty()) return@withContext false
        try {
            val masterKey = getAuthKey(account)
            val baseUrl = account.nahanWorkerUrl!!.trimEnd('/')
            val apiRoute = (account.nahanApiRoute ?: "sync").trim('/')
            val syncUrl = "$baseUrl/$apiRoute/api/sync"
            
            val jsonPayload = JSONObject().apply {
                put("key", masterKey)
                put("config", config.toJson())
            }
            
            val req = Request.Builder()
                .url(syncUrl)
                .post(jsonPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    return@withContext json.optBoolean("success")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    suspend fun fetchNahanNodes(account: CloudAccount): List<String> = withContext(Dispatchers.IO) {
        if (account.nahanWorkerUrl.isNullOrEmpty()) return@withContext emptyList()
        val configs = mutableListOf<String>()
        try {
            // First try default (no sub) � works when no multi-user
            val subUrl = "${account.nahanWorkerUrl}/${account.nahanApiRoute ?: "sync"}?flag=a"
            
            val req = Request.Builder()
                .url(subUrl)
                .get()
                .build()

            val response = client.newCall(req).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val base64Data = resp.body?.string() ?: ""
                    try {
                        val decoded = String(Base64.decode(base64Data.trim(), Base64.DEFAULT))
                        configs.addAll(decoded.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.map { AntiDpi.applySniCamouflage(it) })
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else if (resp.code == 403) {
                    // Multi-user mode: fetch settings to get user list, then fetch each
                    val settings = fetchSettings(account)
                    if (settings != null && settings.users.isNotEmpty()) {
                        for (user in settings.users) {
                            if (user.isPaused) continue
                            try {
                                val userNodes = fetchUserNodes(account, user.name)
                                configs.addAll(userNodes)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
                Unit
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext configs
    }

    suspend fun fetchUserNodes(account: CloudAccount, userName: String): List<String> = withContext(Dispatchers.IO) {
        if (account.nahanWorkerUrl.isNullOrEmpty()) return@withContext emptyList()
        val configs = mutableListOf<String>()
        var retries = 3
        var finalException: Exception? = null
        
        while (retries > 0) {
            try {
                // Worker uses ?sub={name} to identify user subscriptions
                val encodedName = java.net.URLEncoder.encode(userName, "UTF-8")
                val baseUrl = account.nahanWorkerUrl!!.trimEnd('/')
                val apiRoute = (account.nahanApiRoute ?: "sync").trim('/')
                val subUrl = "$baseUrl/$apiRoute?sub=$encodedName&flag=a"
                
                val req = Request.Builder()
                    .url(subUrl)
                    .get()
                    .build()

                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val base64Data = response.body?.string() ?: ""
                        try {
                            val decoded = String(Base64.decode(base64Data.trim(), Base64.DEFAULT))
                            val lines = decoded.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                            val validNodes = lines.filter { it.startsWith("vless://") || it.startsWith("trojan://") }
                            if (validNodes.isNotEmpty()) {
                                configs.addAll(validNodes.map { AntiDpi.applySniCamouflage(it) })
                                return@withContext configs // Success, exit early
                            } else {
                                finalException = Exception("خروجی نامعتبر است.")
                                retries--
                                if (retries > 0) kotlinx.coroutines.delay(1500)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            finalException = Exception("خطا در رمزگشایی کانفیگ (خروجی نامعتبر است)")
                            retries--
                            if (retries > 0) kotlinx.coroutines.delay(1500)
                        }
                    } else if (response.code == 403) {
                        throw NahanNonRetryableException("خطای دسترسی (کد 403).") // Definitive error, do not retry
                    } else {
                        finalException = Exception("خطا در دریافت اطلاعات (کد ${response.code})")
                        retries--
                        if (retries > 0) kotlinx.coroutines.delay(1500)
                    }
                }
            } catch (e: NahanNonRetryableException) {
                finalException = e
                retries = 0
            } catch (e: Exception) {
                e.printStackTrace()
                finalException = e
                retries--
                if (retries > 0) kotlinx.coroutines.delay(1500)
            }
        }
        throw finalException ?: Exception("خطای ناشناخته در دریافت نودها")
    }
}
