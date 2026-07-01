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

            // 3. We ALREADY have the private key from our local generation
            val privateKey = keyPair.privateKeyBase64
            
            val configObject = json.getJSONObject("config")
            val interfaceObject = configObject.getJSONObject("interface")
            val addressesObject = interfaceObject.getJSONObject("addresses")
            
            val ipv4 = addressesObject.getString("v4")
            val ipv6 = addressesObject.getString("v6")

            prefs.edit().apply {
                putString("warp_private_key", privateKey)
                putString("warp_local_ipv4", ipv4)
                putString("warp_local_ipv6", ipv6)
                putBoolean("is_warp_registered", true)
                apply()
            }

            Log.d("WarpManager", "Account successfully generated: IP $ipv4")
            Result.success(WarpAccountData(privateKey, ipv4, ipv6))

        } catch (e: Exception) {
            Log.e("WarpManager", "Registration failed", e)
            Result.failure(e)
        }
    }

    fun getSavedAccount(): WarpAccountData? {
        val priv = prefs.getString("warp_private_key", null) ?: return null
        val v4 = prefs.getString("warp_local_ipv4", "")!!
        val v6 = prefs.getString("warp_local_ipv6", "")!!
        return WarpAccountData(priv, v4, v6)
    }

    data class WarpAccountData(
        val privateKey: String,
        val ipv4: String,
        val ipv6: String
    )
}
