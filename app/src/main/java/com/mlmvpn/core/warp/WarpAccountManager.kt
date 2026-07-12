package com.mlmvpn.core.warp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray

class WarpAccountManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("warp_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient()

    suspend fun registerNewAccount(): Result<WarpAccountData> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.cloudflareclient.com/v0a884/reg"
            
            // 1. Generate local Curve25519 key pair
            val keyPair = WarpCrypto.generateKeyPair()
            
            // 2. Construct JSON body with public key
            val jsonBody = JSONObject().apply {
                put("key", keyPair.publicKeyBase64)
                put("install_id", "")
                put("fcm_token", "")
                put("tos", "2024-01-01T00:00:00.000Z")
                put("model", "Android")
                put("locale", "en_US")
            }
            
            val body = jsonBody.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("User-Agent", "1.1.1.1/2402101704.1 (Android 14; x86_64)")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Cloudflare API Error: ${response.code}"))
            }

            val responseBody = response.body?.string() ?: throw Exception("Empty response body")
            val json = JSONObject(responseBody)
            
            // Log the full response for debugging (truncate to 2000 chars max)
            Log.d("WarpManager", "CF API FULL Response: ${responseBody.take(2000)}")
            Log.d("WarpManager", "CF API Response keys: ${json.keys().asSequence().toList()}")

            // 3. We ALREADY have the private key from our local generation
            val privateKey = keyPair.privateKeyBase64
            
            val configObject = json.getJSONObject("config")
            val interfaceObject = configObject.getJSONObject("interface")
            val addressesObject = interfaceObject.getJSONObject("addresses")
            
            val ipv4 = addressesObject.getString("v4")
            val ipv6 = addressesObject.getString("v6")
            
            // client_id can be at root level OR inside config, check both
            var reserved = listOf(0, 0, 0)
            val clientIdB64 = when {
                json.has("client_id") -> json.getString("client_id")
                configObject.has("client_id") -> configObject.getString("client_id")
                else -> null
            }
            
            if (clientIdB64 != null) {
                try {
                    Log.d("WarpManager", "client_id found: $clientIdB64")
                    val decoded = android.util.Base64.decode(clientIdB64, android.util.Base64.DEFAULT)
                    if (decoded.size >= 3) {
                        reserved = listOf(
                            decoded[0].toInt() and 0xFF,
                            decoded[1].toInt() and 0xFF,
                            decoded[2].toInt() and 0xFF
                        )
                    }
                    Log.d("WarpManager", "Reserved bytes: $reserved")
                } catch (e: Exception) {
                    Log.e("WarpManager", "Failed to parse client_id", e)
                }
            } else {
                Log.w("WarpManager", "No client_id found in API response! Config keys: ${configObject.keys().asSequence().toList()}")
            }

            prefs.edit().apply {
                putString("warp_private_key", privateKey)
                putString("warp_local_ipv4", ipv4)
                putString("warp_local_ipv6", ipv6)
                putString("warp_reserved", JSONArray(reserved).toString())
                putBoolean("is_warp_registered", true)
                apply()
            }

            Log.d("WarpManager", "Account successfully generated: IP $ipv4, Reserved $reserved")
            Result.success(WarpAccountData(privateKey, ipv4, ipv6, reserved))

        } catch (e: Exception) {
            Log.e("WarpManager", "Registration failed", e)
            Result.failure(e)
        }
    }

    suspend fun registerMultipleAccounts(count: Int, onProgress: (Int) -> Unit): Result<List<WarpAccountData>> = withContext(Dispatchers.IO) {
        try {
            val accounts = mutableListOf<WarpAccountData>()
            for (i in 1..count) {
                val result = registerNewAccount()
                if (result.isSuccess) {
                    accounts.add(result.getOrNull()!!)
                    onProgress(i)
                } else {
                    // Stop on failure and return what we have if any
                    if (accounts.isNotEmpty()) break
                    return@withContext Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
                }
            }
            
            // Save to JSON array
            val array = JSONArray()
            accounts.forEach { acc ->
                array.put(JSONObject().apply {
                    put("privateKey", acc.privateKey)
                    put("ipv4", acc.ipv4)
                    put("ipv6", acc.ipv6)
                    put("reserved", JSONArray(acc.reserved))
                })
            }
            prefs.edit().putString("warp_accounts_json", array.toString()).apply()
            
            Result.success(accounts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getSavedAccount(): WarpAccountData? {
        val list = getSavedAccounts()
        return list.firstOrNull()
    }

    fun getSavedAccounts(): List<WarpAccountData> {
        val jsonStr = prefs.getString("warp_accounts_json", null)
        if (jsonStr != null) {
            val list = mutableListOf<WarpAccountData>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val reservedArray = if (obj.has("reserved")) obj.getJSONArray("reserved") else JSONArray(listOf(0,0,0))
                    val reserved = listOf(reservedArray.getInt(0), reservedArray.getInt(1), reservedArray.getInt(2))
                    list.add(WarpAccountData(obj.getString("privateKey"), obj.getString("ipv4"), obj.getString("ipv6"), reserved))
                }
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {}
        }
        
        // Fallback to legacy single account
        val priv = prefs.getString("warp_private_key", null) ?: return emptyList()
        val v4 = prefs.getString("warp_local_ipv4", "")!!
        val v6 = prefs.getString("warp_local_ipv6", "")!!
        val reservedStr = prefs.getString("warp_reserved", "[0,0,0]")
        val reserved = try {
            val arr = JSONArray(reservedStr)
            listOf(arr.getInt(0), arr.getInt(1), arr.getInt(2))
        } catch(e: Exception) { listOf(0,0,0) }
        return listOf(WarpAccountData(priv, v4, v6, reserved))
    }

    data class WarpAccountData(
        val privateKey: String,
        val ipv4: String,
        val ipv6: String,
        val reserved: List<Int> = listOf(0, 0, 0)
    )
}
