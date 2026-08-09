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
import org.json.JSONArray
import kotlinx.coroutines.flow.collectLatest
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.models.VpnNode
import kotlinx.coroutines.launch

data class SubGenAccountData(
    val workerName: String,
    val workerUrl: String,
    val namespaceId: String
)

class SubGenManager(private val context: Context) {
    companion object {
        private var autoSyncJob: kotlinx.coroutines.Job? = null
        /**
         * Last config payload actually pushed per slug, so auto-sync can skip a re-upload that
         * would change nothing. The flow it listens on re-emits on every node mutation, and
         * most of those are metadata-only (a ping result, a speed-test figure, a country flag)
         * which leave the URI list byte-identical -- without this guard each one still cost a
         * full Cloudflare API round trip, all day, for every link on every account.
         * Process-scoped on purpose: the worst a restart can cause is one redundant upload.
         */
        private val lastUploadedConfigs = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    private val prefs = context.getSharedPreferences("subgen_prefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun saveAccountData(cloudAccountId: String, workerName: String, workerUrl: String, namespaceId: String) {
        val json = JSONObject().apply {
            put("workerName", workerName)
            put("workerUrl", workerUrl)
            put("namespaceId", namespaceId)
        }
        prefs.edit().putString("acc_$cloudAccountId", json.toString()).apply()
    }

    fun getAccountData(cloudAccountId: String): SubGenAccountData? {
        val str = prefs.getString("acc_$cloudAccountId", null) ?: return null
        return try {
            val json = JSONObject(str)
            SubGenAccountData(
                workerName = json.getString("workerName"),
                workerUrl = json.getString("workerUrl"),
                namespaceId = json.getString("namespaceId")
            )
        } catch (e: Exception) {
            null
        }
    }
    
    fun removeAccountData(cloudAccountId: String) {
        prefs.edit().remove("acc_$cloudAccountId").apply()
    }

    // --- SubLinks Local Storage ---
    
    fun getSubLinks(): List<SubLinkConfig> {
        val jsonStr = prefs.getString("sub_links", "[]") ?: "[]"
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<SubLinkConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SubLinkConfig(
                        slug = obj.getString("slug"),
                        name = obj.getString("name"),
                        mappedGroupName = if (obj.has("mappedGroupName") && !obj.isNull("mappedGroupName")) obj.getString("mappedGroupName") else null,
                        expiryTimestamp = obj.optLong("expiryTimestamp", 0),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        hits = obj.optInt("hits", 0),
                        accountId = obj.optString("accountId", "")
                    )
                )
            }
            list.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSubLinks(links: List<SubLinkConfig>) {
        val arr = JSONArray()
        links.forEach { link ->
            val obj = JSONObject().apply {
                put("slug", link.slug)
                put("name", link.name)
                put("mappedGroupName", link.mappedGroupName)
                put("expiryTimestamp", link.expiryTimestamp)
                put("createdAt", link.createdAt)
                put("hits", link.hits)
                put("accountId", link.accountId)
            }
            arr.put(obj)
        }
        prefs.edit().putString("sub_links", arr.toString()).apply()
    }

    fun addSubLink(link: SubLinkConfig) {
        val list = getSubLinks().toMutableList()
        list.removeAll { it.slug == link.slug }
        list.add(link)
        saveSubLinks(list)
    }

    fun removeSubLink(slug: String) {
        val list = getSubLinks().toMutableList()
        list.removeAll { it.slug == slug }
        saveSubLinks(list)
    }

    // --- Cloudflare API Calls ---

    private fun getAuthHeaders(account: CloudAccount): Headers {
        val isCfat = account.token.startsWith("cfat_") || account.email.isEmpty()
        return Headers.Builder().apply {
            if (isCfat) add("Authorization", "Bearer ${account.token}")
            else {
                add("X-Auth-Email", account.email)
                add("X-Auth-Key", account.token)
            }
        }.build()
    }

    suspend fun checkWorkerExistsOnline(account: CloudAccount): SubGenAccountData? = withContext(Dispatchers.IO) {
        try {
            // First check locally
            val localData = getAccountData(account.id)
            if (localData != null) return@withContext localData

            // If not found locally, query Cloudflare to see if a subgen worker exists
            val headers = getAuthHeaders(account)
            val req = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts?per_page=100")
                .headers(headers)
                .get().build()
            
            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                val json = JSONObject(body)
                if (!json.optBoolean("success")) {
                    val errors = json.optJSONArray("errors")?.toString() ?: body
                    throw Exception("Cloudflare API Error (Fetching scripts): $errors")
                }
                
                val arr = json.optJSONArray("result") ?: return@use
                for (i in 0 until arr.length()) {
                    val scriptObj = arr.getJSONObject(i)
                    val id = scriptObj.optString("id")
                    val idLower = id.lowercase()
                    // Loosen logic to detect any worker containing 'sub' or 'worker' just in case they used a custom name
                    if (idLower.contains("sub-generator") || idLower.contains("subgen") || idLower.contains("sub-worker") || idLower.contains("sub_worker") || idLower.contains("sub")) {
                        // We found it! Now we need namespaceId.
                        // Let's get the KV namespaces to find the related KV
                        val kvReq = Request.Builder()
                            .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces?per_page=100")
                            .headers(headers)
                            .get().build()
                        var namespaceId = ""
                        client.newCall(kvReq).execute().use { kvRes ->
                            val kvBody = kvRes.body?.string() ?: ""
                            android.util.Log.d("SubGenManager", "KV List response: $kvBody")
                            val kvJson = try { JSONObject(kvBody) } catch(e:Exception){ null }
                            val kvArr = kvJson?.optJSONArray("result")
                            if (kvArr != null) {
                                for (j in 0 until kvArr.length()) {
                                    val kvObj = kvArr.getJSONObject(j)
                                    val titleLower = kvObj.optString("title").lowercase()
                                    // Loosen KV check to match any KV containing 'sub'
                                    if (titleLower.contains("subgen_kv") || titleLower.contains("sub")) {
                                        namespaceId = kvObj.optString("id")
                                        break
                                    }
                                }
                            }
                        }
                        if (namespaceId.isNotEmpty()) {
                            // Find subdomain to build workerUrl
                            val subReq = Request.Builder()
                                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                                .headers(headers)
                                .get().build()
                            var subdomain = ""
                            client.newCall(subReq).execute().use { subRes ->
                                val subJson = try { JSONObject(subRes.body?.string() ?: "") } catch(e:Exception){ null }
                                subdomain = subJson?.optJSONObject("result")?.optString("subdomain") ?: ""
                            }
                            val workerUrl = "https://$id.$subdomain.workers.dev"
                            val data = SubGenAccountData(id, workerUrl, namespaceId)
                            saveAccountData(account.id, id, workerUrl, namespaceId)
                            return@withContext data
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun uploadConfigs(account: CloudAccount, subData: SubGenAccountData, slug: String, configsBase64: String, expiryTimestamp: Long): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val headers = getAuthHeaders(account)
            val jsonPayload = JSONObject().apply {
                put("configs", configsBase64)
                put("expiry", expiryTimestamp)
            }.toString()

            val req = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/${subData.namespaceId}/values/sub_$slug")
                .headers(headers)
                .put(jsonPayload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            client.newCall(req).execute().use { res ->
                val bodyStr = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    return@withContext Pair(true, "")
                } else {
                    return@withContext Pair(false, "API Error: ${res.code} - $bodyStr")
                }
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown Exception")
        }
    }

    suspend fun deleteSubLinkFromKV(account: CloudAccount, subData: SubGenAccountData, slug: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val headers = getAuthHeaders(account)
            
            // Delete configs
            val req1 = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/${subData.namespaceId}/values/sub_$slug")
                .headers(headers)
                .delete()
                .build()
            client.newCall(req1).execute().use {}

            // Delete stats
            val req2 = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/${subData.namespaceId}/values/stat_$slug")
                .headers(headers)
                .delete()
                .build()
            client.newCall(req2).execute().use {}

            removeSubLink(slug)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun syncSubLinksOnline(account: CloudAccount, subData: SubGenAccountData) = withContext(Dispatchers.IO) {
        try {
            val headers = getAuthHeaders(account)
            val req = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/${subData.namespaceId}/keys")
                .headers(headers)
                .get()
                .build()

            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val json = org.json.JSONObject(body)
                    if (json.optBoolean("success")) {
                        val arr = json.optJSONArray("result") ?: return@use
                        val onlineSlugs = mutableSetOf<String>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val keyName = obj.optString("name")
                            if (keyName.startsWith("sub_")) {
                                onlineSlugs.add(keyName.removePrefix("sub_"))
                            }
                        }
                        
                        val localLinks = getSubLinks().toMutableList()
                        val localSlugs = localLinks.map { it.slug }.toSet()
                        
                        var changed = false
                        for (slug in onlineSlugs) {
                            if (!localSlugs.contains(slug)) {
                                localLinks.add(SubLinkConfig(
                                    slug = slug,
                                    name = slug,
                                    mappedGroupName = null,
                                    expiryTimestamp = 0,
                                    createdAt = System.currentTimeMillis(),
                                    hits = 0,
                                    accountId = account.id
                                ))
                                changed = true
                            }
                        }
                        
                        if (changed) {
                            saveSubLinks(localLinks)
                        }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    suspend fun fetchStats(account: CloudAccount, subData: SubGenAccountData, slug: String): Int = withContext(Dispatchers.IO) {
        try {
            val headers = getAuthHeaders(account)
            val req = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/${subData.namespaceId}/values/stat_$slug")
                .headers(headers)
                .get()
                .build()

            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: "0"
                    val hits = body.trim().toIntOrNull() ?: 0
                    
                    // Update cache
                    val links = getSubLinks().toMutableList()
                    val index = links.indexOfFirst { it.slug == slug }
                    if (index != -1) {
                        val updated = links[index].copy(hits = hits)
                        links[index] = updated
                        saveSubLinks(links)
                    }
                    return@withContext hits
                }
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Resolves a sub-link's `mappedGroupName` (a composite `"manual:<engine>:<groupTitle>"` /
     * `"cloud:<groupId>:<engine>"` / `"scanner:<groupId>:<engine>"` string, always set this way
     * by SubLinkScreen -- see its "Update" button logic) to the actual node list it refers to.
     * Must stay in sync with that screen's own copy of this parsing; kept here as the one shared
     * implementation so the two can no longer drift.
     */
    private fun resolveGroupNodes(
        mappedGroupName: String,
        liveNodes: List<VpnNode>,
        groupManager: GroupManager
    ): List<VpnNode> {
        val parts = mappedGroupName.split(":")
        return if (parts.size >= 2) {
            when (parts[0]) {
                "manual" -> {
                    val engine = parts[1]
                    val groupTitle = parts.getOrNull(2)?.let { if (it == "null") null else it }
                    liveNodes.filter { it.engineType == engine && (groupTitle == null || it.groupTitle == groupTitle) }
                }
                "cloud" -> groupManager.cloudGroups.find { it.id == parts[1] }?.nodes?.filter { it.engineType == parts.getOrNull(2) } ?: emptyList()
                "scanner" -> groupManager.scannerGroups.find { it.id == parts[1] }?.nodes?.filter { it.engineType == parts.getOrNull(2) } ?: emptyList()
                else -> emptyList()
            }
        } else {
            liveNodes.filter { it.engineType == mappedGroupName }
        }
    }

    fun startAutoSync() {
        autoSyncJob?.cancel()
        val cloudManager = CloudManager(context)
        val nodeManager = NodeManager(context)
        val groupManager = GroupManager(context)
        autoSyncJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            nodeManager.nodesFlow.collectLatest { nodes ->
                kotlinx.coroutines.delay(5000) // Wait 5 seconds after modifications

                val accounts = cloudManager.accounts
                if (accounts.isEmpty()) return@collectLatest

                val allLinks = getSubLinks()
                for (account in accounts) {
                    val accountData = getAccountData(account.id) ?: continue
                    val links = allLinks.filter { it.accountId == account.id || it.accountId.isEmpty() }

                    for (link in links) {
                        val mapped = link.mappedGroupName
                        if (mapped != null) {
                            val groupNodes = resolveGroupNodes(mapped, nodes, groupManager)
                            val configsStr = groupNodes.joinToString("\n") { it.uri }
                            if (lastUploadedConfigs[link.slug] == configsStr) continue
                            val ok = uploadConfigs(account, accountData, link.slug, configsStr, link.expiryTimestamp).first
                            if (ok) lastUploadedConfigs[link.slug] = configsStr
                        }
                    }
                }
            }
        }
    }
}
