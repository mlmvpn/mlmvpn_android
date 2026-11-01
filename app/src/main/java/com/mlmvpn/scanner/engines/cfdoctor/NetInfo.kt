package com.mlmvpn.scanner.engines.cfdoctor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Who carries this line and where: the provider (and its AS number), province and city.
 *
 * Reachability in Iran differs by provider and by region, and "MCI / MTN / Wi-Fi" said neither --
 * a Wi-Fi report could be any of a dozen fixed-line providers. The user's own address is never
 * kept: only the provider, AS number, region and city are written.
 */
object NetInfo {

    fun lookup(env: AndroidNetEnv): Map<String, String> {
        val b = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        env.picked?.network?.let { n -> b.socketFactory(n.socketFactory); b.dns { h -> n.getAllByName(h).toList() } }
        val client = b.build()
        try {
            for ((url, parse) in listOf<Pair<String, (JSONObject) -> Map<String, String>>>(
                "https://ipinfo.io/json" to { j ->
                    val org = j.optString("org")
                    mapOf("asn" to org.substringBefore(' ').takeIf { it.startsWith("AS") }.orEmpty(), "isp" to org.substringAfter(' ', org),
                        "region" to j.optString("region"), "city" to j.optString("city"), "country" to j.optString("country"))
                },
                "http://ip-api.com/json/?fields=status,countryCode,regionName,city,isp,as" to { j ->
                    mapOf("asn" to j.optString("as").substringBefore(' '), "isp" to j.optString("isp"),
                        "region" to j.optString("regionName"), "city" to j.optString("city"), "country" to j.optString("countryCode"))
                },
            )) {
                val m = runCatching {
                    client.newCall(Request.Builder().url(url).header("User-Agent", "curl/8").build()).execute().use { r ->
                        if (!r.isSuccessful) null else parse(JSONObject(r.body?.string().orEmpty()))
                    }
                }.getOrNull()
                if (m != null && (m["asn"].orEmpty().isNotBlank() || m["isp"].orEmpty().isNotBlank()))
                    return m.mapValues { clean(it.value) }.filterValues { it.isNotBlank() } + ("netinfo_source" to url.substringAfter("//").substringBefore('/'))
            }
            return mapOf("netinfo_source" to "unavailable")
        } finally {
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    private fun clean(s: String) = s.take(60).replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").trim()
}
