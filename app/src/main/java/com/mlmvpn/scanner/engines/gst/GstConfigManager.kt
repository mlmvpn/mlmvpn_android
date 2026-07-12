package com.mlmvpn.scanner.engines.gst

import android.content.Context
import androidx.preference.PreferenceManager
import com.mlmvpn.scanner.ui.tlsPing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GstRelay(
    val id: String = java.util.UUID.randomUUID().toString(),
    val deploymentId: String,
    val authKey: String
)

object GstConfigManager {
    private const val PREFS_KEY_RELAYS = "gst_relays"
    private const val PREFS_KEY_SNI_LIST = "gst_sni_list"
    private const val PREFS_KEY_CLEAN_IP_LIST = "gst_clean_ip_list"
    private const val PREFS_KEY_RELAY_URLS = "gst_relay_urls"

    val DEFAULT_SNI_LIST = listOf(
        "www.google.com",
        "googleapis.com",
        "mtalk.google.com",
        "mail.google.com",
        "www.youtube.com",
        "youtubei.googleapis.com",
        "googlevideo.com",
        "ytimg.com",
        "play.google.com",
        "drive.google.com",
        "translate.google.com",
        "maps.google.com"
    )

    val ITEM_DESCRIPTIONS = mapOf(
        "youtubei.googleapis.com" to "بهترین برای اپ یوتیوب",
        "googlevideo.com" to "عالی برای لود سریع ویدیو",
        "www.youtube.com" to "وب‌سایت یوتیوب",
        "mtalk.google.com" to "پایداری بالا",
        "142.250.186.110" to "سرور اصلی گوگل اج",
        "142.251.37.110" to "سرور اصلی گوگل اج",
        "104.16.24.34" to "آی‌پی کلودفلر",
        "104.17.45.12" to "آی‌پی کلودفلر"
    )

    // The canonical Google frontend IP from the upstream mhrv-rs reference config
    // (therealaleph/MasterHttpRelayVPN-RUST). Used as the default `google_ip` for
    // domain-fronting � it reliably fronts script.google.com AND the direct Google
    // tunnel (YouTube/googlevideo/googleapis). Do NOT default to Cloudflare IPs here:
    // Google services are not served from Cloudflare, so a CF google_ip breaks video.
    const val DEFAULT_GOOGLE_IP = "216.239.38.120"

    val DEFAULT_IP_LIST = listOf(
        "216.239.38.120", // Google frontend (reference default)
        "216.239.32.120", // Google frontend
        "216.239.34.120", // Google frontend
        "216.239.36.120", // Google frontend
        "142.250.186.110", // Google edge
        "142.251.37.110",  // Google edge
        "172.217.18.238",  // Google edge
        "142.250.181.142", // Google edge
        "142.251.32.14"    // Google edge
    )

    fun getRelays(context: Context): List<GstRelay> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val relaysJson = prefs.getString(PREFS_KEY_RELAYS, "[]") ?: "[]"
        val list = mutableListOf<GstRelay>()
        try {
            val array = org.json.JSONArray(relaysJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    GstRelay(
                        id = obj.getString("id"),
                        deploymentId = obj.getString("deploymentId"),
                        authKey = obj.getString("authKey")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveRelays(context: Context, relays: List<GstRelay>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val array = org.json.JSONArray()
        relays.forEach {
            val obj = org.json.JSONObject()
            obj.put("id", it.id)
            obj.put("deploymentId", it.deploymentId)
            obj.put("authKey", it.authKey)
            array.put(obj)
        }
        prefs.edit().putString(PREFS_KEY_RELAYS, array.toString()).apply()
    }

    fun getSelectedSniList(context: Context): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val json = prefs.getString(PREFS_KEY_SNI_LIST, null)
        if (json == null) return listOf("www.google.com", "googleapis.com") // default fallback
        
        val list = mutableListOf<String>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) list.add(array.getString(i))
        } catch (e: Exception) { e.printStackTrace() }
        
        return list
    }

    fun saveSelectedSniList(context: Context, snis: List<String>) {
        val array = org.json.JSONArray()
        snis.forEach { array.put(it) }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREFS_KEY_SNI_LIST, array.toString()).apply()
    }

    /**
     * Optional Cloudflare Worker relay URLs used to accelerate/stabilize the Apps
     * Script tunnel. Empty = GST runs on Google alone (the default). When non-empty,
     * [GstEngine] wires them in as `relay_url`/`parallel_relay` alongside the scripts.
     */
    fun getRelayUrls(context: Context): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val json = prefs.getString(PREFS_KEY_RELAY_URLS, null) ?: return emptyList()
        val list = mutableListOf<String>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) {
                val url = array.getString(i).trim()
                if (url.isNotEmpty()) list.add(url)
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    /**
     * A stable, per-install random key used ONLY between the app and its Cloudflare
     * deploy-proxy worker (gst_relay_worker). Kept separate from the GST relay/tunnel
     * auth key so redeploying relays never desyncs it from the worker's AUTH_KEY binding
     * (which previously caused the worker to reject requests with its decoy page / 502).
     */
    fun getOrCreateRelayProxyKey(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        var k = prefs.getString("gst_relay_proxy_key", null)
        if (k.isNullOrEmpty()) {
            k = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("gst_relay_proxy_key", k).apply()
        }
        return k
    }

    fun saveRelayUrls(context: Context, urls: List<String>) {
        val array = org.json.JSONArray()
        urls.filter { it.isNotBlank() }.forEach { array.put(it.trim()) }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREFS_KEY_RELAY_URLS, array.toString()).apply()
    }

    fun getSelectedCleanIpList(context: Context): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val json = prefs.getString(PREFS_KEY_CLEAN_IP_LIST, null)
        if (json == null) return emptyList()
        
        val list = mutableListOf<String>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) list.add(array.getString(i))
        } catch (e: Exception) { e.printStackTrace() }
        
        return list
    }

    fun saveSelectedCleanIpList(context: Context, ips: List<String>) {
        val array = org.json.JSONArray()
        ips.forEach { array.put(it) }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREFS_KEY_CLEAN_IP_LIST, array.toString()).apply()
    }
}
