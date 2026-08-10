package com.mlmvpn.scanner.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves a config's server domain to its IPs *before* the Xray config is generated, so the
 * result can be pinned into the config's own `dns.hosts` and the outbound can be told to dial
 * those IPs directly.
 *
 * Why this exists: every Cloudflare-panel config (BPB / EDG / Nahan) points at a
 * `*.workers.dev` domain, which resolves to a large rotating set of Cloudflare edge IPs. Some
 * of those IPs are throttled or dead from Iranian networks at any given moment. Left to itself,
 * xray-core resolves the domain at dial time and tries the addresses the resolver happened to
 * hand it, in order -- so a single unlucky IP means a long stall or an outright connect failure,
 * which is the "config tests fine but won't connect" / "real delay is 1000-3000ms" symptom.
 *
 * Pinning the answer here has two effects: the resolution cost disappears from the connect path
 * entirely (it's already in `dns.hosts` before the core starts), and because several IPs are
 * pinned at once, `happyEyeballs` can race them and keep whichever answers first instead of
 * being stuck with the first one.
 *
 * Resolution order: DoH over HTTPS to a resolver's own IP first (no DNS bootstrap needed, and
 * not affected by an ISP resolver that lies or is slow), then the system resolver as a fallback.
 * A failure at every step is not an error -- the caller simply gets an empty list and the config
 * is generated exactly as before, letting the core resolve normally.
 */
object DomainPreResolver {

    /** Keep the pinned set small: happyEyeballs races these, it isn't a candidate pool to sift. */
    private const val MAX_IPS = 6

    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    /** Queried by IP, so this never depends on resolving the resolver's own hostname first. */
    private val DOH_ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve"
    )

    private data class Entry(val ips: List<String>, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Resolve [host] to A records. Returns an empty list for an IP literal, a blank host, or a
     * total resolution failure -- all of which mean "generate the config the old way".
     */
    suspend fun resolve(host: String): List<String> = withContext(Dispatchers.IO) {
        val h = host.trim().trim('[', ']')
        if (h.isEmpty() || isIpLiteral(h)) return@withContext emptyList()

        cache[h]?.let { hit ->
            if (System.currentTimeMillis() - hit.at < CACHE_TTL_MS) return@withContext hit.ips
        }

        val ips = (queryDoh(h) ?: querySystem(h) ?: emptyList()).take(MAX_IPS)
        if (ips.isNotEmpty()) cache[h] = Entry(ips, System.currentTimeMillis())
        ips
    }

    /**
     * Convenience wrapper: the map shape [XrayJsonGenerator.generateConfig] expects for
     * `pinnedHostIps`. Only the address the outbound actually dials is resolved -- the SNI and
     * WebSocket Host headers are never connected to, so pinning them would be dead weight.
     */
    suspend fun pinnedHostsFor(config: VpnConfig): Map<String, List<String>> {
        val addr = config.address
        if (addr.isBlank()) return emptyMap()
        val ips = resolve(addr)
        return if (ips.isEmpty()) emptyMap() else mapOf(addr to ips)
    }

    /**
     * Both endpoints speak the same JSON DNS API (Cloudflare needs the `accept` header to pick
     * it over wire-format; Google's `/resolve` path returns JSON unconditionally), so one
     * parser covers both.
     */
    private fun queryDoh(host: String): List<String>? {
        for (endpoint in DOH_ENDPOINTS) {
            try {
                val req = Request.Builder()
                    .url("$endpoint?name=$host&type=A")
                    .header("accept", "application/dns-json")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val answers = org.json.JSONObject(body).optJSONArray("Answer") ?: return@use
                    val ips = mutableListOf<String>()
                    for (i in 0 until answers.length()) {
                        val a = answers.optJSONObject(i) ?: continue
                        // type 1 == A. CNAME records (type 5) appear in the same array and their
                        // "data" is a hostname, not an address -- taking those would pin garbage.
                        if (a.optInt("type") != 1) continue
                        val ip = a.optString("data").takeIf { it.isNotBlank() } ?: continue
                        if (isIpLiteral(ip)) ips.add(ip)
                    }
                    if (ips.isNotEmpty()) return ips
                }
            } catch (_: Exception) {
                // Try the next resolver; a DoH endpoint being unreachable is expected here.
            }
        }
        return null
    }

    private fun querySystem(host: String): List<String>? = try {
        InetAddress.getAllByName(host)
            .filterIsInstance<java.net.Inet4Address>()
            .mapNotNull { it.hostAddress }
            .takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun isIpLiteral(s: String): Boolean {
        if (s.contains(':')) return true // IPv6 literal
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } && (p.toIntOrNull() ?: 256) <= 255 }
    }
}
