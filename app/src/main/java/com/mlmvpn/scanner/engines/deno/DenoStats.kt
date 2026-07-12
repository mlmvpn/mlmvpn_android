package com.mlmvpn.scanner.engines.deno

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Usage for Deno accounts, read from Deno's official analytics API.
 *
 * The old approach (polling each server's in-memory /stats counter) was
 * inaccurate: Deno load-balances across isolates, so a poll usually hit a
 * different isolate than the one that carried the traffic (10MB shown as 40KB).
 * The analytics API aggregates across all isolates server-side, so totals are
 * correct � at the cost of a few minutes' delay.
 *
 * Deno limits handled: an analytics query must span STRICTLY < 7 days, so we
 * fetch the last 30 days in 6-day chunks and derive day/week/month from each
 * bucket's `time`. Fields: network_egress_bytes = download, ingress = upload.
 */
class DenoStats(private val context: Context) {

    companion object {
        private const val TAG = "DenoDeploy"
        private const val API = "https://api.deno.com/v2"
    }

    private val manager = DenoManager(context)

    data class Usage(
        val requests: Long = 0,
        val downloadBytes: Long = 0, // server egress -> user download
        val uploadBytes: Long = 0    // server ingress -> user upload
    ) {
        val totalBytes get() = downloadBytes + uploadBytes
        operator fun plus(o: Usage) =
            Usage(requests + o.requests, downloadBytes + o.downloadBytes, uploadBytes + o.uploadBytes)
    }

    data class AccountUsage(
        val daily: Usage = Usage(),
        val weekly: Usage = Usage(),
        val monthly: Usage = Usage(),
        val total: Usage = Usage(),   // last 30 days (analytics horizon)
        val appCount: Int = 0,
        val ok: Boolean = true
    )

    /**
     * Aggregate usage across every app in the account's org via the analytics
     * API. `deployments` is used only to locate the account token; the app list
     * itself comes from the API (so it covers servers made outside the app).
     * Builds a fresh HTTP client so it routes through the current VPN tunnel.
     */
    suspend fun pollAccount(
        accountId: String,
        deployments: List<DenoManager.DenoDeployment>
    ): AccountUsage = withContext(Dispatchers.IO) {
        val token = manager.getAccounts().firstOrNull { it.id == accountId }?.token
            ?: deployments.firstOrNull { it.token.isNotEmpty() }?.token
            ?: return@withContext AccountUsage(ok = false)

        val client = DenoHttp.buildClient(context)
        try {
            val slugs = listAppSlugs(client, token)
            if (slugs.isEmpty()) return@withContext AccountUsage(appCount = 0, ok = true)

            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000
            val dayAgo = now - day
            val weekAgo = now - 7 * day

            var daily = Usage(); var weekly = Usage(); var monthly = Usage()
            for (slug in slugs) {
                var start = now - 30 * day
                while (start < now) {
                    val end = minOf(start + 6 * day, now)
                    for (b in fetchBuckets(client, token, slug, start, end)) {
                        val u = Usage(b.requests, b.egress, b.ingress)
                        monthly += u
                        if (b.timeMs >= weekAgo) weekly += u
                        if (b.timeMs >= dayAgo) daily += u
                    }
                    start = end
                }
            }
            AccountUsage(daily, weekly, monthly, monthly, slugs.size, ok = true)
        } catch (e: Exception) {
            Log.e(TAG, "pollAccount error: ${e.message}")
            AccountUsage(ok = false)
        }
    }

    private fun listAppSlugs(client: okhttp3.OkHttpClient, token: String): List<String> {
        val req = Request.Builder().url("$API/apps?limit=100")
            .headers(DenoHttp.authHeaders(token)).get().build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string() ?: return emptyList()
            if (!res.isSuccessful) { Log.w(TAG, "list apps HTTP ${res.code}"); return emptyList() }
            val arr = org.json.JSONArray(body)
            return (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("slug").ifEmpty { null } }
        }
    }

    private data class Bucket(val timeMs: Long, val requests: Long, val egress: Long, val ingress: Long)

    private fun fetchBuckets(
        client: okhttp3.OkHttpClient, token: String, slug: String, sinceMs: Long, untilMs: Long
    ): List<Bucket> {
        val url = "$API/apps/$slug/analytics?since=${iso(sinceMs)}&until=${iso(untilMs)}"
        val req = Request.Builder().url(url).headers(DenoHttp.authHeaders(token)).get().build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string() ?: return emptyList()
            if (!res.isSuccessful) return emptyList()
            return parseBuckets(body)
        }
    }

    private fun parseBuckets(body: String): List<Bucket> {
      return try {
        val o = JSONObject(body)
        val fields = o.optJSONArray("fields") ?: return emptyList()
        val values = o.optJSONArray("values") ?: return emptyList()
        var t = -1; var r = -1; var eg = -1; var ig = -1
        for (i in 0 until fields.length()) when (fields.getJSONObject(i).optString("name")) {
            "time" -> t = i
            "request_count" -> r = i
            "network_egress_bytes" -> eg = i
            "network_ingress_bytes" -> ig = i
        }
        val out = ArrayList<Bucket>(values.length())
        for (i in 0 until values.length()) {
            val row = values.getJSONArray(i)
            out.add(Bucket(
                timeMs = if (t >= 0) parseIso(row.optString(t)) else 0,
                requests = if (r >= 0) row.optLong(r, 0) else 0,
                egress = if (eg >= 0) row.optLong(eg, 0) else 0,
                ingress = if (ig >= 0) row.optLong(ig, 0) else 0
            ))
        }
        out
      } catch (e: Exception) { emptyList() }
    }

    private fun iso(ms: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }

    private fun parseIso(s: String): Long = try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.parse(s)?.time ?: 0
    } catch (e: Exception) { 0 }
}
