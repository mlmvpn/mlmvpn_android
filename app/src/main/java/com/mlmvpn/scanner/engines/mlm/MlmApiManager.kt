package com.mlmvpn.scanner.engines.mlm

import android.content.Context
import com.google.gson.Gson
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class MlmApiManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(com.mlmvpn.scanner.emergency.EmergencyInterceptor(context))
        .build()
        
    private val gson = Gson()

    private suspend fun getAdminHash(password: String): String = withContext(Dispatchers.Default) {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(password.toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private suspend fun buildRequest(
        account: CloudAccount, 
        endpoint: String, 
        method: String = "GET", 
        bodyStr: String? = null
    ): Request? {
        val workerUrl = account.mlmWorkerUrl?.trimEnd('/') ?: return null
        val url = "$workerUrl$endpoint"
        val password = account.mlmAdminPassword ?: "admin"
        val hash = getAdminHash(password)
        
        val builder = Request.Builder()
            .url(url)
            .header("Cookie", "panel_session=$hash")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
            
        if (method == "POST" && bodyStr != null) {
            builder.post(bodyStr.toRequestBody("application/json".toMediaTypeOrNull()))
        } else if (method == "PUT" && bodyStr != null) {
            builder.put(bodyStr.toRequestBody("application/json".toMediaTypeOrNull()))
        } else if (method == "DELETE") {
            builder.delete()
        } else {
            builder.get()
        }
        
        return builder.build()
    }

    suspend fun getUsers(account: CloudAccount): List<MlmUser>? = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(account, "/api/users") ?: return@withContext null
            client.newCall(req).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful) {
                    val res = gson.fromJson(body, MlmUsersResponse::class.java)
                    return@withContext res.users ?: emptyList()
                } else {
                    android.util.Log.e("MlmApi", "getUsers Failed! Code: ${response.code}, Body: $body")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MlmApi", "getUsers Exception: ${e.message}", e)
        }
        return@withContext null
    }

    suspend fun createUser(account: CloudAccount, request: MlmCreateUserRequest): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(account, "/api/users", "POST", gson.toJson(request)) ?: return@withContext false
            client.newCall(req).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    suspend fun updateUser(account: CloudAccount, username: String, request: MlmUpdateUserRequest): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(account, "/api/users/$username", "PUT", gson.toJson(request)) ?: return@withContext false
            client.newCall(req).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    suspend fun deleteUser(account: CloudAccount, username: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(account, "/api/users/$username", "DELETE") ?: return@withContext false
            client.newCall(req).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    suspend fun toggleUser(account: CloudAccount, username: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = MlmToggleUserRequest(toggleOnly = true)
            val req = buildRequest(account, "/api/users/$username", "PUT", gson.toJson(request)) ?: return@withContext false
            client.newCall(req).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    suspend fun getProxySettings(account: CloudAccount): MlmProxySettings? = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(account, "/api/proxy-ip") ?: return@withContext null
            client.newCall(req).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful) {
                    return@withContext gson.fromJson(body, MlmProxySettings::class.java)
                } else {
                    android.util.Log.e("MlmApi", "getProxySettings Failed! Code: ${response.code}, Body: $body")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MlmApi", "getProxySettings Exception: ${e.message}", e)
        }
        return@withContext null
    }

    suspend fun updateProxySettings(account: CloudAccount, request: MlmProxySettingsRequest): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(account, "/api/proxy-ip", "POST", gson.toJson(request)) ?: return@withContext false
            client.newCall(req).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    suspend fun getUserConfigs(account: CloudAccount, username: String): List<String> = withContext(Dispatchers.IO) {
        val configs = mutableListOf<String>()
        try {
            val workerUrl = account.mlmWorkerUrl?.trimEnd('/') ?: return@withContext configs
            val url = "$workerUrl/sub/$username?txt=1"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    // If it is base64 encoded, decode it, otherwise it's just plain text
                    val text = try {
                        if (body.contains("://")) body else String(android.util.Base64.decode(body, android.util.Base64.DEFAULT))
                    } catch (e: Exception) {
                        body
                    }
                    text.lines().forEach {
                        val line = it.trim()
                        if (line.isNotEmpty() && line.contains("://")) {
                            configs.add(line)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        configs
    }
}
