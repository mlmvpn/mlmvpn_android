package com.mlmvpn.scanner.data

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.models.CloudSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class CloudManager(private val context: Context) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(com.mlmvpn.scanner.emergency.EmergencyInterceptor(context))
        .build()

    private val prefs = context.getSharedPreferences("cloud_accounts_prefs", Context.MODE_PRIVATE)

    // Temporary lists for memory, later persist to SharedPreferences or Room
    val accounts = mutableListOf<CloudAccount>()
    var settings = CloudSettings()

    private val _accountsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<CloudAccount>>(emptyList())
    val accountsFlow: kotlinx.coroutines.flow.StateFlow<List<CloudAccount>> = _accountsFlow

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            loadAccounts()
            _accountsFlow.value = accounts.toList()
        }
    }

    fun loadAccounts() {
        val jsonStr = prefs.getString("accounts_list", "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<CloudAccount>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CloudAccount(
                        id = obj.getString("id"),
                        token = obj.getString("token"),
                        email = obj.getString("email"),
                        name = obj.getString("name"),
                        accountId = obj.getString("accountId"),
                        status = obj.getString("status"),
                        addedAt = obj.getString("addedAt"),
                        workerUrl = obj.optString("workerUrl", ""),
                        uuid = obj.optString("uuid", ""),
                        trPass = obj.optString("trPass", ""),
                        subPath = obj.optString("subPath", ""),
                        edgWorkerUrl = obj.optString("edgWorkerUrl", ""),
                        edgUuid = obj.optString("edgUuid", ""),
                        edgAdminPass = obj.optString("edgAdminPass", ""),
                        edgKvNamespaceId = obj.optString("edgKvNamespaceId", ""),
                        edgStatus = obj.optString("edgStatus", "idle"),
                        nahanWorkerUrl = obj.optString("nahanWorkerUrl", ""),
                        nahanDbId = obj.optString("nahanDbId", ""),
                        nahanApiRoute = obj.optString("nahanApiRoute", "sync"),
                        nahanMasterKey = obj.optString("nahanMasterKey", ""),
                        nahanStatus = obj.optString("nahanStatus", "idle"),
                        mlmWorkerUrl = obj.optString("mlmWorkerUrl", ""),
                        mlmDbId = obj.optString("mlmDbId", ""),
                        mlmAdminPassword = obj.optString("mlmAdminPassword", ""),
                        mlmStatus = obj.optString("mlmStatus", "idle"),
                        isEmailVerified = obj.optBoolean("isEmailVerified", true),
                        hasSubdomain = obj.optBoolean("hasSubdomain", 
                            obj.optString("workerUrl", "").isNotEmpty() || 
                            obj.optString("edgWorkerUrl", "").isNotEmpty() || 
                            obj.optString("nahanWorkerUrl", "").isNotEmpty() ||
                            obj.optString("mlmWorkerUrl", "").isNotEmpty()
                        )
                    )
                )
            }
            accounts.clear()
            accounts.addAll(list)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveAccounts() {
        val arr = org.json.JSONArray()
        for (account in accounts) {
            val obj = JSONObject().apply {
                put("id", account.id)
                put("token", account.token)
                put("email", account.email)
                put("name", account.name)
                put("accountId", account.accountId)
                put("status", account.status)
                put("addedAt", account.addedAt)
                put("workerUrl", account.workerUrl ?: "")
                put("uuid", account.uuid ?: "")
                put("trPass", account.trPass ?: "")
                put("subPath", account.subPath ?: "")
                put("edgWorkerUrl", account.edgWorkerUrl ?: "")
                put("edgUuid", account.edgUuid ?: "")
                put("edgAdminPass", account.edgAdminPass ?: "")
                put("edgKvNamespaceId", account.edgKvNamespaceId ?: "")
                put("edgStatus", account.edgStatus)
                put("nahanWorkerUrl", account.nahanWorkerUrl ?: "")
                put("nahanDbId", account.nahanDbId ?: "")
                put("nahanApiRoute", account.nahanApiRoute)
                put("nahanMasterKey", account.nahanMasterKey ?: "")
                put("nahanStatus", account.nahanStatus)
                put("mlmWorkerUrl", account.mlmWorkerUrl ?: "")
                put("mlmDbId", account.mlmDbId ?: "")
                put("mlmAdminPassword", account.mlmAdminPassword ?: "")
                put("mlmStatus", account.mlmStatus)
                put("isEmailVerified", account.isEmailVerified)
                put("hasSubdomain", account.hasSubdomain)
            }
            arr.put(obj)
        }
        prefs.edit().putString("accounts_list", arr.toString()).apply()
        _accountsFlow.value = accounts.toList()
    }

    suspend fun addAccount(rawToken: String, rawEmail: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val token = rawToken.replace(Regex("[^a-zA-Z0-9_-]"), "").trim()
        val email = rawEmail.trim()
        
        if (token.isEmpty()) return@withContext Pair(false, "Token is empty")

        val isCfat = token.startsWith("cfat_")
        val requestBuilder = Request.Builder()
        
        if (isCfat || email.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer $token")
        } else {
            requestBuilder.header("X-Auth-Email", email)
            requestBuilder.header("X-Auth-Key", token)
        }

        try {
            // Verify Token
            val verifyUrl = if (isCfat || email.isEmpty()) "https://api.cloudflare.com/client/v4/user/tokens/verify"
                            else "https://api.cloudflare.com/client/v4/user"
            
            val req1 = requestBuilder.url(verifyUrl).get().build()
            client.newCall(req1).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()
                    return@withContext Pair(false, "Invalid API Token: $body")
                }
            }

            // Get Accounts
            val req2 = requestBuilder.url("https://api.cloudflare.com/client/v4/accounts").get().build()
            client.newCall(req2).execute().use { response ->
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to get account details")
                
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                if (json.getBoolean("success")) {
                    val results = json.getJSONArray("result")
                    if (results.length() > 0) {
                        val firstAcc = results.getJSONObject(0)
                        val accName = firstAcc.getString("name")
                        val accId = firstAcc.getString("id")

                        val newAcc = CloudAccount(
                            id = System.currentTimeMillis().toString(),
                            token = token,
                            email = email,
                            name = accName,
                            accountId = accId,
                            status = "active",
                            addedAt = System.currentTimeMillis().toString()
                        )
                        val statusResult = checkAccountStatus(newAcc)
                        newAcc.hasSubdomain = statusResult.first
                        newAcc.isEmailVerified = statusResult.second
                        
                        accounts.add(newAcc)
                        saveAccounts()
                        return@withContext Pair(true, "Account added: $accName")
                    }
                }
            }
            Pair(false, "No Cloudflare accounts found under this token")
        } catch (e: Exception) {
            Pair(false, "Network error: ${e.message}")
        }
    }

    suspend fun getUsage(account: CloudAccount): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // Cloudflare free tier resets daily usage at midnight UTC. We query from the start of the current day in UTC.
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val endStr = sdf.format(cal.time)
            
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val startStr = sdf.format(cal.time)

            val query = """
                {"query":"query GetWorkersAnalytics(${'$'}accountTag: String!, ${'$'}datetimeStart: String!, ${'$'}datetimeEnd: String!) {\n                    viewer {\n                        accounts(filter: {accountTag: ${'$'}accountTag}) {\n                            workersInvocationsAdaptive(limit: 10000, filter: {datetime_geq: ${'$'}datetimeStart, datetime_leq: ${'$'}datetimeEnd}) {\n                                sum { requests }\n                            }\n                        }\n                    }\n                }","variables":{"accountTag":"${account.accountId}","datetimeStart":"$startStr","datetimeEnd":"$endStr"}}
            """.trimIndent()
            
            val requestBuilder = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/graphql")
                .post(query.toRequestBody("application/json".toMediaTypeOrNull()))

            if (account.token.startsWith("cfat_") || account.email.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer ${account.token}")
            } else {
                requestBuilder.header("X-Auth-Email", account.email)
                requestBuilder.header("X-Auth-Key", account.token)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = org.json.JSONObject(body)
                    try {
                        if (json.has("errors") && !json.isNull("errors")) {
                            return@withContext Pair(false, "GraphQL Error: " + json.getJSONArray("errors").getJSONObject(0).getString("message"))
                        }
                        
                        val accountsArray = json.optJSONObject("data")
                            ?.optJSONObject("viewer")
                            ?.optJSONArray("accounts")
                            
                        if (accountsArray == null || accountsArray.length() == 0) return@withContext Pair(true, "0")
                        
                        val adaptiveArray = accountsArray.getJSONObject(0)
                            .optJSONArray("workersInvocationsAdaptive")
                            
                        if (adaptiveArray == null || adaptiveArray.length() == 0) return@withContext Pair(true, "0")
                            
                        val sumObj = adaptiveArray.getJSONObject(0).optJSONObject("sum")
                        val requests = sumObj?.optLong("requests", 0L) ?: 0L
                        return@withContext Pair(true, requests.toString())
                    } catch (e: Exception) {
                        val bodyPreview = if (body.length > 200) body.substring(0, 200) else body
                        return@withContext Pair(false, "Parse Error: ${e.message}\nBody: $bodyPreview")
                    }
                } else {
                    return@withContext Pair(false, "HTTP ${response.code}")
                }
            }
            Pair(false, "Unknown Error")
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    suspend fun getAllWorkers(account: CloudAccount): Pair<Boolean, List<String>> = withContext(Dispatchers.IO) {
        try {
            val isCfat = account.token.startsWith("cfat_") || account.email.isEmpty()
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            val request = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts")
                .headers(authHeaders)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return@withContext Pair(false, listOf(body))
                
                val json = org.json.JSONObject(body)
                if (json.getBoolean("success")) {
                    val resultArr = json.optJSONArray("result")
                    val workers = mutableListOf<String>()
                    if (resultArr != null) {
                        for (i in 0 until resultArr.length()) {
                            val obj = resultArr.getJSONObject(i)
                            workers.add(obj.getString("id"))
                        }
                    }
                    return@withContext Pair(true, workers)
                } else {
                    return@withContext Pair(false, listOf(body))
                }
            }
        } catch (e: Exception) {
            Pair(false, listOf(e.message ?: "Unknown error"))
        }
    }

    suspend fun deleteWorker(account: CloudAccount, workerName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val isCfat = account.token.startsWith("cfat_") || account.email.isEmpty()
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            val request = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to delete: $body")
                Pair(true, "Success")
            }
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    suspend fun deployWorker(account: CloudAccount, onProgress: (Int, String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
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
            onProgress(5, "Checking subdomain...")
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
                onProgress(10, "Creating new subdomain...")
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
                        val body = response.body?.string() ?: ""
                        if (response.isSuccessful) {
                            subdomain = randomSub
                            created = true
                        } else {
                            try {
                                val json = org.json.JSONObject(body)
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

            // 2. Generate Secrets
            val workerUuid = java.util.UUID.randomUUID().toString()
            val trPass = java.util.UUID.randomUUID().toString().replace("-", "")
            val subPath = java.util.UUID.randomUUID().toString().substring(0, 8)

            // 3. Create KV Namespace
            onProgress(20, "Creating KV Namespace...")
            var namespaceId = ""
            var errorBody = ""
            val kvReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces")
                .headers(authHeaders)
                .post("{\"title\":\"mlmvpn\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
                
            client.newCall(kvReq).execute().use { response ->
                val body = response.body?.string() ?: ""
                errorBody = body
                val json = try { JSONObject(body) } catch(e:Exception) { null }
                if (response.isSuccessful && json?.optBoolean("success") == true) {
                    namespaceId = json.getJSONObject("result").getString("id")
                } else if (json?.optJSONArray("errors")?.optJSONObject(0)?.optInt("code") == 10014) {
                    // Already exists, fetch it
                    val listReq = Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces")
                        .headers(authHeaders)
                        .get().build()
                    client.newCall(listReq).execute().use { listRes ->
                        val listJson = try { JSONObject(listRes.body?.string() ?: "") } catch(e:Exception) { null }
                        val results = listJson?.optJSONArray("result")
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val item = results.getJSONObject(i)
                                if (item.getString("title") == "mlmvpn" || item.getString("title").contains("mlmvpn")) {
                                    namespaceId = item.getString("id")
                                    break
                                }
                            }
                        }
                    }
                }
            }
            if (namespaceId.isEmpty()) return@withContext Pair(false, "Failed to create KV namespace: $errorBody")

            // 4. Upload Worker
            onProgress(40, "Uploading Worker...")
            var workerScript = ""
            try {
                context.assets.open("worker.js").bufferedReader().use {
                    workerScript = it.readText()
                }
            } catch (e: Exception) {
                return@withContext Pair(false, "Failed to read worker.js from assets: ${e.message}")
            }

            val metadata = JSONObject().apply {
                put("main_module", "worker.js")
                put("compatibility_date", "2024-03-03")
                val bindings = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "kv_namespace")
                        put("name", "kv")
                        put("namespace_id", namespaceId)
                    })
                    put(JSONObject().apply {
                        put("type", "secret_text")
                        put("name", "UUID")
                        put("text", workerUuid)
                    })
                    put(JSONObject().apply {
                        put("type", "secret_text")
                        put("name", "TR_PASS")
                        put("text", trPass)
                    })
                    put(JSONObject().apply {
                        put("type", "secret_text")
                        put("name", "SUB_PATH")
                        put("text", subPath)
                    })
                }
                put("bindings", bindings)
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", metadata.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addFormDataPart("worker.js", "worker.js", workerScript.toRequestBody("application/javascript+module".toMediaTypeOrNull()))
                .build()

            val workerName = com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName()
            val uploadReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .put(multipartBody)
                .build()

            client.newCall(uploadReq).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to upload worker: $body")
            }

            // 5. Inject initial proxy settings (v4.2.2 format)
            onProgress(60, "Injecting proxy settings...")
            val proxySettingsStr = JSONObject().apply {
                put("remoteDNS", "https://8.8.8.8/dns-query")
                put("localDNS", "8.8.8.8")
                put("antiSanctionDNS", "178.22.122.100")
                put("enableIPv6", true)
                put("allowLANConnection", false)
                put("proxyIPMode", "proxyip")
                put("proxyIPs", org.json.JSONArray().apply { put("bpb.yousef.isegaro.com") })
                put("prefixes", org.json.JSONArray())
                put("cleanIPs", org.json.JSONArray())
                put("VLConfigs", true)
                put("TRConfigs", true)
                put("ports", org.json.JSONArray().apply { put(443) })
                put("fingerprint", "chrome")
                put("bypassIran", false)
                put("blockAds", false)
                put("blockPorn", false)
                put("panelVersion", "4.2.2")
            }.toString()
            val setProxyReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/$namespaceId/values/proxySettings")
                .headers(authHeaders)
                .put(proxySettingsStr.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(setProxyReq).execute().use {}

            // 6. Enable Subdomain
            onProgress(80, "Enabling subdomain routing...")
            val enableReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName/subdomain")
                .headers(authHeaders)
                .post("{\"enabled\":true}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            
            client.newCall(enableReq).execute().use { response ->
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to enable subdomain: ${response.body?.string()}")
            }

            // 7. Setup BPB panel password via KV directly
            onProgress(95, "Setting up panel...")
            val finalUrl = "https://$workerName.$subdomain.workers.dev"
            val setPassReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/$namespaceId/values/pwd")
                .headers(authHeaders)
                .put("Admin123!".toRequestBody("text/plain".toMediaTypeOrNull()))
                .build()
            client.newCall(setPassReq).execute().use {}
            
            // Save to account model
            account.status = "deployed"
            account.workerUrl = finalUrl
            account.uuid = workerUuid
            account.trPass = trPass
            account.subPath = subPath
            
            // Save to shared preferences
            saveAccounts()
            
            onProgress(100, "Done!")

            Pair(true, "Deployment Successful! URL: $finalUrl")

        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    suspend fun fetchCloudConfigs(account: CloudAccount): Pair<Boolean, List<String>> = withContext(Dispatchers.IO) {
        try {
            if (account.workerUrl.isNullOrEmpty() || account.subPath.isNullOrEmpty() || account.trPass.isNullOrEmpty()) {
                return@withContext Pair(false, emptyList())
            }

            // Fetch subscription data using the stored workerUrl, subPath, and trPass
            val subUrl = "${account.workerUrl}/sub/raw/${account.subPath}?app=xray"
            val fetchReq = Request.Builder()
                .url(subUrl)
                .get().build()

            val configs = mutableListOf<String>()
            client.newCall(fetchReq).execute().use { res ->
                if (!res.isSuccessful) return@withContext Pair(false, emptyList())
                val base64Data = res.body?.string() ?: ""
                try {
                    val decoded = String(android.util.Base64.decode(base64Data.trim(), android.util.Base64.DEFAULT))
                    configs.addAll(decoded.split("\n").map { it.trim().replace("💦", "mlmvpn") }.filter { it.isNotEmpty() }.map { com.mlmvpn.scanner.utils.AntiDpi.applySniCamouflage(it) })
                } catch (e: Exception) {
                    return@withContext Pair(false, emptyList())
                }
            }

            Pair(true, configs)
        } catch (e: javax.net.ssl.SSLException) {
            Pair(false, emptyList())
        } catch (e: Exception) {
            Pair(false, emptyList())
        }
    }

    private fun ensureLogin(account: CloudAccount): Pair<Boolean, String> {
        val passwordsToTry = listOf(
            "Admin123!",
            "{\"username\":\"admin\",\"password\":\"admin\"}",
            "admin"
        )
        
        try {
            for (pass in passwordsToTry) {
                val loginUrl = "${account.workerUrl}/login/authenticate"
                val loginReq = Request.Builder()
                    .url(loginUrl)
                    .post(pass.toRequestBody("text/plain".toMediaTypeOrNull()))
                    .build()
                
                val loginRes = client.newCall(loginReq).execute()
                if (loginRes.isSuccessful) {
                    val cookies = loginRes.headers("Set-Cookie")
                    if (cookies.isNotEmpty()) {
                        val sessionCookie = cookies.joinToString("; ") { it.split(";")[0] }
                        loginRes.close()
                        Log.d("CloudManager", "Login successful with password length: ${pass.length}")
                        return Pair(true, sessionCookie)
                    }
                }
                loginRes.close()
            }
        } catch (e: javax.net.ssl.SSLException) {
            return Pair(false, "گواهینامه امنیتی (SSL) این ساب‌دامین هنوز فعال نشده است. لطفاً ۱ تا ۳ دقیقه صبر کنید و دوباره تلاش کنید.")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("SSL") || msg.contains("Handshake") || msg.contains("ssl=")) {
                return Pair(false, "گواهینامه امنیتی (SSL) این ساب‌دامین هنوز فعال نشده است. لطفاً ۱ تا ۳ دقیقه صبر کنید و دوباره تلاش کنید.")
            }
            return Pair(false, "Error during login: ${e.message}")
        }
        
        // If all failed, forcefully reset password via Cloudflare API
        forceResetPasswordViaCloudflareAPI(account)
        
        // Try login one last time with Admin123! Retry for up to 30 seconds to allow KV edge propagation
        for (i in 1..10) {
            Log.d("CloudManager", "ensureLogin retry $i: Attempting login with password: Admin123!")
            val finalLoginReq = Request.Builder()
                .url("${account.workerUrl}/login/authenticate")
                .post("Admin123!".toRequestBody("text/plain".toMediaTypeOrNull()))
                .build()
            val finalLoginRes = client.newCall(finalLoginReq).execute()
            if (finalLoginRes.isSuccessful) {
                val cookies = finalLoginRes.headers("Set-Cookie")
                if (cookies.isNotEmpty()) {
                    val sessionCookie = cookies.joinToString("; ") { it.split(";")[0] }
                    finalLoginRes.close()
                    Log.d("CloudManager", "Login successful after force KV reset.")
                    return Pair(true, sessionCookie)
                }
            }
            val code = finalLoginRes.code
            val errBody = finalLoginRes.body?.string()
            finalLoginRes.close()
            
            if (code == 401) {
                Log.d("CloudManager", "ensureLogin retry $i: 401 Wrong password, waiting for KV propagation...")
                Thread.sleep(3000)
                continue
            } else {
                return Pair(false, "Code: $code, Body: $errBody")
            }
        }
        return Pair(false, "Code: 401, Body: Failed to login after KV reset due to propagation timeout.")
    }

    private fun forceResetPasswordViaCloudflareAPI(account: CloudAccount) {
        try {
            val isCfat = account.token.startsWith("cfat_") || account.email.isEmpty()
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            var namespaceId = ""
            val listReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces")
                .headers(authHeaders)
                .get().build()
            client.newCall(listReq).execute().use { listRes ->
                val listJson = JSONObject(listRes.body?.string() ?: "")
                if (listJson.optBoolean("success")) {
                    val results = listJson.getJSONArray("result")
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val title = item.getString("title")
                        if (title == "mlmvpn" || title.contains("mlmvpn")) {
                            namespaceId = item.getString("id")
                            break
                        }
                    }
                }
            }

            if (namespaceId.isNotEmpty()) {
                val setPassReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/$namespaceId/values/pwd")
                    .headers(authHeaders)
                    .put("Admin123!".toRequestBody("text/plain".toMediaTypeOrNull()))
                    .build()
                client.newCall(setPassReq).execute().use {
                    Log.d("CloudManager", "forceReset KV pwd result: ${it.code} - ${it.body?.string()}")
                }
                Thread.sleep(2000) // Wait for Cloudflare KV edge propagation
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun fetchWorkerSettings(account: CloudAccount): JSONObject? = withContext(Dispatchers.IO) {
        try {
            if (account.workerUrl.isNullOrEmpty()) return@withContext null

            // Step 1: Login using helper
            val loginResult = ensureLogin(account)
            if (!loginResult.first) {
                Log.e("CloudManager", "fetchWorkerSettings ensureLogin failed: ${loginResult.second}")
                return@withContext null
            }
            val sessionCookie = loginResult.second

            // Step 2: Get settings
            val settingsUrl = "${account.workerUrl}/panel/settings"
            val settingsReqBuilder = Request.Builder().url(settingsUrl).get()
            if (sessionCookie.isNotEmpty()) {
                settingsReqBuilder.header("Cookie", sessionCookie)
            }

            client.newCall(settingsReqBuilder.build()).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val json = JSONObject(body)
                    if (json.optBoolean("success")) {
                        val bodyObj = json.optJSONObject("body")
                        return@withContext bodyObj?.optJSONObject("proxySettings")
                    }
                }
            }
            return@withContext null
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun updateWorkerSettings(account: CloudAccount, settingsJson: JSONObject): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (account.workerUrl.isNullOrEmpty()) {
                return@withContext Pair(false, "Worker URL is missing")
            }

            // Step 1: Login using helper
            val loginResult = ensureLogin(account)
            if (!loginResult.first) {
                return@withContext Pair(false, "Login failed: ${loginResult.second}")
            }
            val sessionCookie = loginResult.second

            // Step 2: Put settings to /panel/update-settings
            val settingsUrl = "${account.workerUrl}/panel/update-settings"
            val settingsReqBuilder = Request.Builder()
                .url(settingsUrl)
                .put(settingsJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))

            if (sessionCookie.isNotEmpty()) {
                settingsReqBuilder.header("Cookie", sessionCookie)
            }

            client.newCall(settingsReqBuilder.build()).execute().use { res ->
                if (!res.isSuccessful) {
                    val bodyStr = res.body?.string()
                    return@withContext Pair(false, "Failed to update settings: ${res.code} - $bodyStr")
                }
            }

            Pair(true, "تنظیمات با موفقیت به‌روزرسانی شد")
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    suspend fun deployEdgWorker(account: CloudAccount, onProgress: (Int, String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
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
                    val randomSub = com.mlmvpn.scanner.utils.AntiDpi.generateSafeSubdomain()
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
                                val json = org.json.JSONObject(body)
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

            // 2. Setup Variables
            val edgUuid = java.util.UUID.randomUUID().toString()
            val edgAdminPass = account.edgAdminPass.takeIf { !it.isNullOrEmpty() } ?: java.util.UUID.randomUUID().toString().substring(0, 8)
            val proxyIp = "proxyip.cmliussss.net"

            // 3. Create KV Namespace
            onProgress(40, "Creating KV Namespace...")
            val kvTitle = "edg_${java.util.UUID.randomUUID().toString().substring(0, 8).replace("-", "")}"
            val createKvReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces")
                .headers(authHeaders)
                .post("{\"title\":\"$kvTitle\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            
            var kvId = ""
            try {
                val kvRes = client.newCall(createKvReq).execute()
                val kvBody = kvRes.body?.string() ?: ""
                if (kvRes.isSuccessful) {
                    val kvJson = org.json.JSONObject(kvBody)
                    if (kvJson.optBoolean("success", false)) {
                        kvId = kvJson.getJSONObject("result").getString("id")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (kvId.isEmpty()) {
                return@withContext Pair(false, "Failed to create KV namespace for EDG")
            }

            // Save KV namespace ID to account for EDG Settings
            account.edgKvNamespaceId = kvId
            saveAccounts()

            // 4. Upload Worker
            onProgress(50, "Uploading EDG Worker...")
            var workerScript = ""
            try {
                context.assets.open("edg_worker.js").bufferedReader().use {
                    workerScript = it.readText()
                }
            } catch (e: Exception) {
                return@withContext Pair(false, "Failed to read edg_worker.js from assets: ${e.message}")
            }

            val metadata = JSONObject().apply {
                put("main_module", "worker.js")
                put("compatibility_date", "2024-03-03")
                val bindings = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "plain_text")
                        put("name", "UUID")
                        put("text", edgUuid)
                    })
                    put(JSONObject().apply {
                        put("type", "plain_text")
                        put("name", "PROXYIP")
                        put("text", proxyIp)
                    })
                    put(JSONObject().apply {
                        put("type", "plain_text")
                        put("name", "ADMIN")
                        put("text", edgAdminPass)
                    })
                    put(JSONObject().apply {
                        put("type", "kv_namespace")
                        put("name", "KV")
                        put("namespace_id", kvId)
                    })
                }
                put("bindings", bindings)
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", metadata.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addFormDataPart("worker.js", "worker.js", workerScript.toRequestBody("application/javascript+module".toMediaTypeOrNull()))
                .build()

            val workerName = com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() + "-edg"
            val uploadReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .put(multipartBody)
                .build()

            client.newCall(uploadReq).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to upload EDG worker: $body")
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
            
            account.edgStatus = "deployed"
            account.edgWorkerUrl = finalUrl
            account.edgUuid = edgUuid
            account.edgAdminPass = edgAdminPass
            
            saveAccounts()

            Pair(true, "EDG Deployment Successful! URL: $finalUrl")

        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    suspend fun fetchEdgConfigs(account: CloudAccount): Pair<Boolean, List<String>> = withContext(Dispatchers.IO) {
        try {
            if (account.edgWorkerUrl.isNullOrEmpty() || account.edgUuid.isNullOrEmpty()) {
                return@withContext Pair(false, emptyList())
            }

            val workerHost = account.edgWorkerUrl!!.replace("https://", "").replace("http://", "").trimEnd('/')
            val uuid = account.edgUuid!!

            // Fetch proxy IP from KV if available
            var proxyIpStr = ""
            if (!account.edgKvNamespaceId.isNullOrEmpty() && account.accountId.isNotEmpty()) {
                try {
                    val isCfat = account.token.startsWith("cfat_") || account.email.isEmpty()
                    val authHeaders = okhttp3.Headers.Builder().apply {
                        if (isCfat) add("Authorization", "Bearer ${account.token}")
                        else {
                            add("X-Auth-Email", account.email)
                            add("X-Auth-Key", account.token)
                        }
                        add("Content-Type", "application/json")
                    }.build()
                    val req = okhttp3.Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/${account.edgKvNamespaceId}/values/config.json")
                        .headers(authHeaders)
                        .get()
                        .build()
                    val res = client.newCall(req).execute()
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: ""
                        val json = org.json.JSONObject(body)
                        val fanDai = json.optJSONObject("反代")
                        if (fanDai != null) {
                            val ip = fanDai.optString("PROXYIP", "")
                            if (ip.isNotBlank() && ip != "auto") {
                                proxyIpStr = "proxyip=$ip&"
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore, use default path
                }
            }

            // Encode the path query part. edg_worker parses searchParams.get('proxyip').
            val queryParam = if (proxyIpStr.isNotEmpty()) "?${proxyIpStr}ed=2560" else "?ed=2560"
            val encodedPath = java.net.URLEncoder.encode("/$queryParam", "UTF-8")

            val vlessMain = "vless://$uuid@$workerHost:443?encryption=none&security=tls&sni=$workerHost&type=ws&host=$workerHost&path=$encodedPath#EDG-Auto"
            val vlessCF1 = "vless://$uuid@104.21.5.155:443?encryption=none&security=tls&sni=$workerHost&type=ws&host=$workerHost&path=$encodedPath#EDG-CF1"
            val vlessCF2 = "vless://$uuid@172.67.13.12:443?encryption=none&security=tls&sni=$workerHost&type=ws&host=$workerHost&path=$encodedPath#EDG-CF2"
            
            val configs = listOf(vlessMain, vlessCF1, vlessCF2).map { 
                com.mlmvpn.scanner.utils.AntiDpi.applySniCamouflage(it) 
            }

            Pair(true, configs)
        } catch (e: Exception) {
            Pair(false, emptyList())
        }
    }

    suspend fun checkAccountStatus(account: CloudAccount): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        var hasSubdomain = false
        var isEmailVerified = false
        var subdomainName = ""

        try {
            val authHeaders = Headers.Builder()
                .add("X-Auth-Email", account.email)
                .add("X-Auth-Key", account.token)
                .add("Content-Type", "application/json")
                .build()

            // Check subdomain
            val subReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .get().build()

            client.newCall(subReq).execute().use { response ->
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "")
                    if (json.optBoolean("success")) {
                        subdomainName = json.optJSONObject("result")?.optString("subdomain", "") ?: ""
                        if (subdomainName.isNotEmpty()) {
                            hasSubdomain = true
                        }
                    }
                }
            }

            // If they have a subdomain, their email is definitely verified (Cloudflare requires it).
            // We cannot rely on testing https://$subdomainName.workers.dev/ because workers.dev
            // is heavily filtered/blocked in Iran and will cause false negatives for email verification.
            isEmailVerified = true

        } catch (e: Exception) {
            e.printStackTrace()
        }

        Pair(hasSubdomain, isEmailVerified)
    }

    suspend fun createSubdomainOnly(account: CloudAccount): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val authHeaders = Headers.Builder()
                .add("X-Auth-Email", account.email)
                .add("X-Auth-Key", account.token)
                .add("Content-Type", "application/json")
                .build()

            // First check if a subdomain already exists
            val subReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .get().build()

            var existingSub = ""
            client.newCall(subReq).execute().use { response ->
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "")
                    if (json.optBoolean("success")) {
                        existingSub = json.optJSONObject("result")?.optString("subdomain", "") ?: ""
                    }
                }
            }

            if (existingSub.isNotEmpty()) {
                return@withContext Pair(true, existingSub)
            }

            val randomSub = com.mlmvpn.scanner.utils.AntiDpi.generateSafeSubdomain()
            val createReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .put("{\"subdomain\":\"$randomSub\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            client.newCall(createReq).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    return@withContext Pair(true, randomSub)
                } else {
                    if (body.contains("10007")) {
                        return@withContext Pair(true, "already_exists")
                    }
                    return@withContext Pair(false, "Failed to create subdomain: $body")
                }
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "Error: ${e.message}")
        }
    }
}
