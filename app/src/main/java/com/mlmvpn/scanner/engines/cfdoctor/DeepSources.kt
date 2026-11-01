package com.mlmvpn.scanner.engines.cfdoctor

import com.mlmvpn.scanner.utils.VpnConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Configs to test with when the user has none of their own on Cloudflare -- typically exactly the
 * user whose account or deploy is what fails. Without them the scan and the connection stages would
 * have nothing to measure. Taken from the public list FLUX already uses (patterniha's Free-Configs:
 * Cloudflare-fronted, tested from Iran by its publisher), over GitHub and its jsDelivr mirror.
 * Marked "public" in the report, so their results are never read as the user's own.
 */
object PublicConfigs {
    private val URLS = listOf(
        "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt",
        "https://cdn.jsdelivr.net/gh/patterniha/Free-Configs@main/configs.txt",
    )

    fun fetch(env: AndroidNetEnv, out: DeepReport, want: Int = 2): List<VpnConfig> {
        val b = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        env.picked?.network?.let { n -> b.socketFactory(n.socketFactory); b.dns { h -> n.getAllByName(h).toList() } }
        val client = b.build()
        try {
            for (url in URLS) {
                val text = runCatching {
                    client.newCall(Request.Builder().url(url).header("User-Agent", "mlmvpn-doctor").build()).execute().use { r ->
                        if (r.isSuccessful) r.body?.string().orEmpty() else throw java.io.IOException("http_${r.code}")
                    }
                }
                val host = url.substringAfter("//").substringBefore('/')
                if (text.isFailure) { out.line("SOURCE", "CONFIGS", "public", "from" to host, "result" to (text.exceptionOrNull()?.message ?: "error")); continue }
                val body = text.getOrThrow().trim()
                val lines = if (body.contains("://")) body.lines() else runCatching { String(android.util.Base64.decode(body, android.util.Base64.DEFAULT)).lines() }.getOrDefault(emptyList())
                val cf = lines.mapNotNull { VpnConfig.parseUri(it.trim()) }
                    .filter { ConfigProbes.isCloudflare(it) && ConfigProbes.rawConfig(it) != null }
                out.line("SOURCE", "CONFIGS", "public", "from" to host, "result" to "ok", "cloudflare_configs" to cf.size)
                if (cf.isNotEmpty()) return cf.shuffled().take(want)
            }
            return emptyList()
        } finally {
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
