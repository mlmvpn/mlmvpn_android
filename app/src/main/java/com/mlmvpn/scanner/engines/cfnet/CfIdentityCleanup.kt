package com.mlmvpn.scanner.engines.cfnet

import android.content.Context
import com.mlmvpn.scanner.data.SecureStore
import com.mlmvpn.scanner.engines.cfdoctor.WayClient
import com.mlmvpn.core.warp.WarpApiFront
import com.mlmvpn.core.warp.WarpIdRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Only unremoved diagnostic identities. Credentials are sealed before being persisted. */
object CfIdentityCleanup {
    private val lock = Any()
    private fun prefs(context: Context) = context.getSharedPreferences("cfnet_identity_cleanup", Context.MODE_PRIVATE)
    private fun read(context: Context): JSONArray {
        val sealed = prefs(context).getString("sealed", null) ?: return JSONArray()
        return JSONArray(SecureStore.open(sealed) ?: error("secure_cleanup_unavailable"))
    }
    private fun write(context: Context, items: JSONArray) {
        val sealed = SecureStore.seal(items.toString()) ?: error("secure_cleanup_unavailable")
        check(prefs(context).edit().putString("sealed", sealed).commit())
    }
    fun add(context: Context, id: String, token: String, route: RouteSpec) = synchronized(lock) {
        val items = read(context)
        check(items.length() < 16) { "identity_cleanup_pending" }
        items.put(JSONObject().put("id", id).put("token", token).put("carrier", route.carrier.name)
            .put("address", route.address.name).put("shape", route.shape.name))
        write(context, items)
    }
    fun remove(context: Context, id: String) = synchronized(lock) {
        val old = read(context)
        val next = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).optString("id") != id) next.put(old.getJSONObject(i))
        write(context, next)
    }
    suspend fun retry(context: Context) = withTimeoutOrNull(25_000) {
        runInterruptible(Dispatchers.IO) {
            val items = synchronized(lock) { runCatching { read(context) }.getOrDefault(JSONArray()) }
            for (i in 0 until minOf(items.length(), 2)) {
                if (Thread.currentThread().isInterrupted) break
                runCatching {
                    val item = items.getJSONObject(i)
                    val id = item.getString("id")
                    val route = RouteSpec(AddressSource.valueOf(item.getString("address")), WireShape.valueOf(item.getString("shape")), Carrier.valueOf(item.getString("carrier")))
                    val path = "/v0a4471/reg/$id"
                    val headers = mapOf("Authorization" to "Bearer ${item.getString("token")}", "User-Agent" to "WARP for Android", "CF-Client-Version" to "a-6.35-4471")
                    val status = if (route.carrier == Carrier.WARP_FRONT) WarpApiFront.exchange("DELETE", path, headers.toList(), ByteArray(0)).status else {
                        val base = if (route.carrier == Carrier.WORKER_RELAY) WarpIdRelay.base(context)?.toHttpUrl() else "https://api.cloudflareclient.com".toHttpUrl()
                        if (base == null) return@runCatching
                        val wire = CfControlClient(context)
                        try { wire.exchange(route, WayClient.Req(base.host, base.encodedPath.trimEnd('/') + path, "DELETE", headers, deadlineMs = 12_000)).http }
                        finally { wire.close() }
                    }
                    if (status in 200..299 || status == 404) remove(context, id)
                }
            }
        }
    }
}
