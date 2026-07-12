package com.mlmvpn.scanner.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VpnSubscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L
)

class SubscriptionManager private constructor(val context: Context) {
    companion object {
        @Volatile
        private var instance: SubscriptionManager? = null

        operator fun invoke(context: Context): SubscriptionManager {
            return instance ?: synchronized(this) {
                instance ?: SubscriptionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences("subscription_manager_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val subscriptions = mutableListOf<VpnSubscription>()
    
    private val _subscriptionsFlow = MutableStateFlow<List<VpnSubscription>>(emptyList())
    val subscriptionsFlow: StateFlow<List<VpnSubscription>> = _subscriptionsFlow

    init {
        loadSubscriptions()
    }

    private fun loadSubscriptions() {
        val jsonStr = prefs.getString("subscriptions", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<VpnSubscription>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    VpnSubscription(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        lastUpdated = obj.optLong("lastUpdated", 0L)
                    )
                )
            }
            subscriptions.clear()
            subscriptions.addAll(list)
            _subscriptionsFlow.value = subscriptions.toList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveSubscriptions() {
        val arr = JSONArray()
        for (sub in subscriptions) {
            val obj = JSONObject()
            obj.put("id", sub.id)
            obj.put("name", sub.name)
            obj.put("url", sub.url)
            obj.put("lastUpdated", sub.lastUpdated)
            arr.put(obj)
        }
        prefs.edit().putString("subscriptions", arr.toString()).apply()
        _subscriptionsFlow.value = subscriptions.toList()
    }

    fun addSubscription(name: String, url: String): VpnSubscription {
        val sub = VpnSubscription(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            url = url,
            lastUpdated = 0L
        )
        subscriptions.add(sub)
        saveSubscriptions()
        return sub
    }
    
    fun removeSubscription(id: String) {
        subscriptions.removeAll { it.id == id }
        saveSubscriptions()
    }

    suspend fun updateSubscription(sub: VpnSubscription, nodeManager: NodeManager): Pair<Boolean, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        return@withContext try {
            val request = Request.Builder()
                .url(sub.url)
                .header("User-Agent", "v2rayNG/1.8.31")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Pair(false, "Server returned ${response.code}")
            }
            
            val bodyStr = response.body?.string() ?: return@withContext Pair(false, "Empty response")
            
            // Try decoding base64 if it's base64
            var decodedStr = bodyStr
            var decodeFailed = false
            try {
                if (!bodyStr.contains("://")) {
                    val decodedBytes = Base64.decode(bodyStr.trim(), Base64.DEFAULT)
                    decodedStr = String(decodedBytes)
                }
            } catch (e: Exception) {
                decodeFailed = true
                android.util.Log.w("SubscriptionManager", "Base64 decode failed for subscription ${sub.id}: ${e.message}")
            }

            val lines = decodedStr.split("\n", "\r").map { it.trim() }.filter { it.isNotEmpty() }
            val validLines = lines.filter { it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("trojan://") }

            if (validLines.isEmpty()) {
                return@withContext Pair(false, if (decodeFailed) "Failed to decode subscription content" else "No valid configs found in subscription")
            }
            
            // Remove old nodes for this sub
            nodeManager.removeNodesByGroup(sub.id)
            
            // Add new nodes
            val addedCount = nodeManager.addConfigs(validLines, sub.id)
            
            // Update timestamp
            val updatedSub = sub.copy(lastUpdated = System.currentTimeMillis())
            val index = subscriptions.indexOfFirst { it.id == sub.id }
            if (index != -1) {
                subscriptions[index] = updatedSub
                saveSubscriptions()
            }
            
            Pair(true, "Added $addedCount configs")
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, e.message ?: "Unknown error")
        }
    }
    
    suspend fun updateAllSubscriptions(nodeManager: NodeManager): Pair<Int, Int> {
        var successCount = 0
        var failCount = 0
        for (sub in subscriptions) {
            val res = updateSubscription(sub, nodeManager)
            if (res.first) successCount++ else failCount++
        }
        return Pair(successCount, failCount)
    }
}




