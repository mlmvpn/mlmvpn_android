package com.mlmvpn.scanner.engines.game

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client-side driver for the user's Dedicated DNS worker (dns_worker.js).
 *
 * Distinct from [DnsRaceTester] (whose job is "find ANY public/Iranian DNS that answers"):
 * this class talks only to the user's own private worker and its job is "pick the ECS region
 * that yields the lowest REAL ping to the game server for this specific game". It reuses the
 * same conventions as [DnsRaceTester] -- parallel racing, JSON `Answer` parsing -- but against
 * a single known-good workers.dev endpoint, so there's no DNS-of-the-DNS bootstrap problem.
 *
 * The region table mirrors REGIONS in dns_worker.js -- keep the two in sync. The worker maps a
 * short region code to the real ECS subnet server-side, so the client only ever sends the code.
 */
object DedicatedDnsResolver {

    private const val TAG = "DedicatedDnsResolver"
    private const val QUERY_TIMEOUT_MS = 2500L

    data class DnsRegion(
        val code: String,
        val nameFa: String,
        val nameEn: String
    )

    /** UAE first = default fallback per product spec. */
    val ALL_REGIONS: List<DnsRegion> = listOf(
        DnsRegion("AE", "امارات", "UAE"),
        DnsRegion("TR", "ترکیه", "Turkey"),
        DnsRegion("DE", "آلمان", "Germany"),
        DnsRegion("SG", "سنگاپور", "Singapore"),
        DnsRegion("IN", "هند", "India"),
        DnsRegion("US", "آمریکا", "US")
    )

    const val DEFAULT_REGION = "AE"

    fun regionByCode(code: String?): DnsRegion =
        ALL_REGIONS.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: ALL_REGIONS.first()

    fun cacheKeyFor(gameId: String): String = "dns_best_region_$gameId"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /**
     * One GET to the worker's /resolve endpoint. Returns the list of resolved A-record IPs for
     * [hostname] as seen from the spoofed [region], or empty on any failure. Parsing mirrors
     * [DnsRaceTester.resolveViaDoh] (same JSON `Answer` schema the worker deliberately emits).
     */
    suspend fun resolveViaWorker(workerUrl: String, hostname: String, region: String): List<String> =
        withContext(Dispatchers.IO) {
            val base = workerUrl.trimEnd('/')
            val url = "$base/resolve?domain=$hostname&region=$region"
            try {
                val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
                val body = resp.body?.string()
                resp.close()
                if (body.isNullOrEmpty()) return@withContext emptyList()

                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer") ?: return@withContext emptyList()
                val ips = mutableListOf<String>()
                for (i in 0 until answers.length()) {
                    val ans = answers.getJSONObject(i)
                    if (ans.optInt("type") == 1) ips.add(ans.getString("data"))
                }
                ips
            } catch (e: Exception) {
                Log.d(TAG, "resolveViaWorker($hostname, $region) failed: ${e.message}")
                emptyList()
            }
        }

    /**
     * Races every candidate region against the worker in parallel and returns the single best
     * (lowest real ping) region, or null if none produced a pingable IP (caller falls back to AE).
     * Thin wrapper over [testAllRegions] so the "pick the winner" and "show me all regions" paths
     * share one measurement implementation.
     */
    suspend fun raceRegions(
        workerUrl: String,
        testEndpoints: List<String>,
        port: Int,
        candidateRegions: List<DnsRegion> = ALL_REGIONS
    ): RegionRaceResult? =
        testAllRegions(workerUrl, testEndpoints, port, candidateRegions).minByOrNull { it.pingMs }

    /**
     * Measures EVERY candidate region against the worker in parallel and returns a per-region
     * result list (sorted best-ping first) so the UI can show the user exactly which region wins
     * for the selected game on their current internet.
     *
     * Accuracy: for each region we resolve the game's real hostnames (ECS region-steered), then
     * ping each returned IP individually with several samples and take that region's BEST IP as its
     * representative -- because in-game the client settles on one low-latency endpoint, not the
     * average of every anycast IP. This is a real TCP-RTT to the actual game server IP:port on the
     * user's own connection, so it tracks the in-game ping closely.
     *
     * Only regions that produced at least one pingable IP are returned.
     */
    suspend fun testAllRegions(
        workerUrl: String,
        testEndpoints: List<String>,
        port: Int,
        candidateRegions: List<DnsRegion> = ALL_REGIONS,
        samplesPerIp: Int = 5
    ): List<RegionRaceResult> = coroutineScope {
        candidateRegions.map { region ->
            async(Dispatchers.IO) {
                val ips = mutableSetOf<String>()
                for (endpoint in testEndpoints) {
                    val resolved = withTimeoutOrNull(QUERY_TIMEOUT_MS) {
                        resolveViaWorker(workerUrl, endpoint, region.code)
                    } ?: emptyList()
                    ips.addAll(resolved)
                }
                if (ips.isEmpty()) {
                    Log.d(TAG, "region ${region.code}: worker resolved no IPs")
                    return@async null
                }

                // Ping each IP separately; the region's score is its best (lowest-latency) IP.
                var bestPing = Long.MAX_VALUE
                var bestJitter = 0L
                for (ip in ips) {
                    val (avg, jitter) = GamePingTester.averagePing(listOf(ip), port, samples = samplesPerIp, proxyPort = null)
                    if (avg in 1 until bestPing) {
                        bestPing = avg
                        bestJitter = jitter
                    }
                }
                if (bestPing == Long.MAX_VALUE) {
                    Log.d(TAG, "region ${region.code}: resolved ${ips.size} IPs but none pingable")
                    null
                } else {
                    Log.d(TAG, "region ${region.code}: best ping=${bestPing}ms (jitter=${bestJitter}ms) over ${ips.size} IPs")
                    RegionRaceResult(region, ips.toList(), bestPing, bestJitter)
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.pingMs }
    }

    data class RegionRaceResult(
        val region: DnsRegion,
        val resolvedIps: List<String>,
        val pingMs: Long,
        val jitterMs: Long
    )
}
