package com.mlmvpn.scanner.engines.freeconfig

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Fetches free public VLESS/VMess/Trojan/Shadowsocks configs, tests them for a real proxied
 * connection, and returns only the ones that actually work. Modeled on how apps like Kingo VPN
 * source their lists: aggregate a handful of hourly/frequently updated public subscription repos
 * on GitHub. Nothing here is MLM VPN's own infrastructure -- these are the same public
 * raw.githubusercontent.com lists Kingo itself pulls from, fetched directly instead of relying on
 * Kingo's own merge/rename step.
 */
object FreeConfigEngine {

    private const val TAG = "FreeConfigEngine"

    const val GROUP_NAME = "کانفیگ های رایگان"

    /** (url, isBase64Encoded) */
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/hello-world-1989/cn-news/main/end-gfw-together" to true,
        "https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/refs/heads/main/Config/vless.txt" to false,
        "https://raw.githubusercontent.com/4n0nymou3/multi-proxy-config-fetcher/refs/heads/main/configs/proxy_configs_tested.txt" to false,
        "https://raw.githubusercontent.com/kingowow/Kingo-vpn/refs/heads/main/server/KingoVpn.txt" to false
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val URI_PREFIXES = listOf("vless://", "vmess://", "trojan://", "ss://")

    /**
     * Downloads every source and returns the deduplicated list of raw candidate config URIs
     * (untested). This is what backs step 1's "you can currently fetch N configs" count.
     */
    suspend fun fetchCandidates(): List<String> = withContext(Dispatchers.IO) {
        val jobs = SOURCES.map { (url, isBase64) ->
            async {
                try {
                    val req = Request.Builder().url(url).get().build()
                    client.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) return@async emptyList<String>()
                        val raw = res.body?.string() ?: return@async emptyList<String>()
                        val text = if (isBase64) {
                            try {
                                String(android.util.Base64.decode(raw.trim(), android.util.Base64.DEFAULT))
                            } catch (e: Exception) {
                                raw
                            }
                        } else raw
                        text.lines()
                            .map { it.trim() }
                            .filter { line -> URI_PREFIXES.any { prefix -> line.startsWith(prefix, ignoreCase = true) } }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "source failed: $url (${e.message})")
                    emptyList()
                }
            }
        }
        val all = jobs.awaitAll().flatten()
        // Dedupe by host:port+id rather than the full string, since the same server often shows
        // up across multiple source lists with only its remark text differing.
        all.distinctBy { dedupeKey(it) }
    }

    private fun dedupeKey(uri: String): String {
        val config = VpnConfig.parseUri(uri) ?: return uri
        return "${config.protocol}|${config.address}|${config.port}|${config.uuid}"
    }

    /**
     * Same identity key as [dedupeKey], exposed for comparing a fetched result against the
     * user's already-saved nodes -- our own remark/name is always randomized per fetch
     * ("mlmvpnNNNN"), so name-based comparison would never catch a re-fetch of the same server;
     * this compares the actual connection identity (protocol+address+port+uuid) instead.
     */
    fun serverKey(uri: String): String = dedupeKey(uri)

    /**
     * Splits [results] into (new, alreadyOwned) by comparing each against [existingUris] (e.g.
     * every node already in NodeManager) via [serverKey], so a re-fetch of a server the user
     * already saved isn't imported as a second copy under a new random name.
     */
    fun splitAlreadyOwned(results: List<VpnNode>, existingUris: List<String>): Pair<List<VpnNode>, List<VpnNode>> {
        val existingKeys = existingUris.map { serverKey(it) }.toHashSet()
        return results.partition { serverKey(it.uri) !in existingKeys }
    }

    data class TestProgress(val tested: Int, val candidates: Int, val working: Int, val target: Int)

    /** Same reachability check the app's own "Real Delay" test uses (NodesTab.kt): actually spin
     * up Xray with the config and measure a real proxied HTTPS round trip, instead of just
     * probing whether *something* answers TLS on that host:port. A dead/hijacked node can still
     * pass a bare TLS handshake, which is why a plain TCP+TLS probe wasn't accurate enough. */
    private const val DELAY_TEST_URL = "https://clients3.google.com/generate_204"

    private suspend fun ensureXrayEnv(context: Context) = withContext(Dispatchers.IO) {
        try {
            for (filename in listOf("geosite.dat", "geoip.dat")) {
                val destFile = java.io.File(context.filesDir, filename)
                if (!destFile.exists() || destFile.length() < 1000) {
                    context.assets.open(filename).use { input ->
                        java.io.FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "asset copy failed: ${e.message}")
        }
        try {
            val keyBytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(keyBytes)
            val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            val xudpBaseKey = android.util.Base64.encodeToString(keyBytes, flags)
            libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey)
        } catch (e: Exception) {
            Log.w(TAG, "initCoreEnv failed: ${e.message}")
        }
    }

    /**
     * Cheap first-pass filter: just try to open a raw TCP socket to the config's address:port.
     * Most entries in a public aggregated list are already dead (server offline, port closed) --
     * catching those here costs ~1-2s each at high concurrency instead of burning a full Xray
     * core spin-up + handshake + the old 10s timeout on something that was never going to work.
     */
    private suspend fun quickTcpCheck(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), 1800)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Two-stage funnel to find [targetCount] working configs fast:
     *  1. Cheap TCP reachability pre-check on a large batch, high concurrency -- throws out the
     *     (usually large) fraction of candidates that are simply offline, in ~2s per batch.
     *  2. Only survivors go through the expensive real Xray-proxied test (same one the app's own
     *     "Real Delay" test uses), at higher concurrency than before since these are scattered
     *     public IPs, not one shared origin.
     * Stops as soon as [targetCount] genuinely working configs are found or candidates run out.
     */
    suspend fun collectWorking(
        context: Context,
        candidates: List<String>,
        targetCount: Int,
        shouldStop: () -> Boolean = { false },
        onProgress: (TestProgress) -> Unit
    ): List<VpnNode> = coroutineScope {
        ensureXrayEnv(context)

        val shuffled = candidates.shuffled()
        val working = mutableListOf<VpnNode>()
        var tested = 0
        val preCheckBatchSize = 25
        val realTestChunkSize = 12
        val preCheckSemaphore = Semaphore(30)
        val realTestSemaphore = Semaphore(realTestChunkSize)

        var index = 0
        // shouldStop() is the user's "این تعداد کافیه" button -- a clean early exit that keeps
        // whatever was already found, as opposed to cancelling the coroutine (which would throw
        // and lose the partial `working` list instead of returning it). Checked between every
        // small chunk (not just between the old, much bigger 50-wide batches) so pressing it
        // actually takes effect within a second or two instead of waiting out a whole in-flight
        // batch of up to a dozen 6s Xray tests.
        while (index < shuffled.size && working.size < targetCount && !shouldStop()) {
            coroutineContext.ensureActive()
            val batch = shuffled.subList(index, minOf(index + preCheckBatchSize, shuffled.size))
            index += preCheckBatchSize

            // Stage 1: fast reachability filter.
            val survivors = batch.map { uri ->
                async(Dispatchers.IO) {
                    preCheckSemaphore.acquire()
                    try {
                        val cfg = VpnConfig.parseUri(uri) ?: return@async null
                        if (quickTcpCheck(cfg.address, cfg.port)) uri to cfg else null
                    } finally {
                        preCheckSemaphore.release()
                    }
                }
            }.awaitAll().filterNotNull()

            // Candidates that failed the cheap check are done being "tested" -- they never reach
            // the expensive stage, so count them immediately rather than waiting for stage 2.
            tested += (batch.size - survivors.size)
            onProgress(TestProgress(tested, candidates.size, working.size, targetCount))

            if (shouldStop()) break

            // Stage 2: real proxied connection test, only on survivors -- in small chunks (sized
            // to the concurrency limit, so nothing is queued past what's already running) with a
            // stop-check between each chunk, instead of one big awaitAll() over every survivor
            // that a stop request would have to wait out completely.
            for (chunk in survivors.chunked(realTestChunkSize)) {
                if (shouldStop() || working.size >= targetCount) break
                coroutineContext.ensureActive()

                val results = chunk.map { (uri, cfg) ->
                    async(Dispatchers.IO) {
                        realTestSemaphore.acquire()
                        try {
                            val jsonConfig = XrayJsonGenerator.generateSpeedtestConfig(cfg)
                            val delayMs = withTimeoutOrNull(6_000L) {
                                libv2ray.Libv2ray.measureOutboundDelay(jsonConfig, DELAY_TEST_URL)
                            } ?: 0L
                            if (delayMs > 0) uri to cfg else null
                        } catch (e: Exception) {
                            null
                        } finally {
                            realTestSemaphore.release()
                        }
                    }
                }.awaitAll()

                for (r in results) {
                    tested++
                    if (r != null && working.size < targetCount) {
                        working.add(buildBadgedNode(r.first, r.second))
                    }
                }
                onProgress(TestProgress(tested, candidates.size, working.size, targetCount))
            }
        }

        working
    }

    /**
     * Renames the config to "mlmvpnNNNN" (random 3-4 digit code) both as the node's display name
     * AND baked into the URI's own remark (#fragment) so the branding survives copy/share -- the
     * exported URI string is all a share carries, there's no separate metadata channel.
     */
    private fun buildBadgedNode(uri: String, config: VpnConfig): VpnNode {
        val code = Random.nextInt(100, 10000)
        val badgeName = "mlmvpn$code"
        val encodedRemark = URLEncoder.encode(badgeName, "UTF-8").replace("+", "%20")
        val rebrandedUri = if (uri.contains("#")) {
            uri.substringBeforeLast("#") + "#" + encodedRemark
        } else {
            "$uri#$encodedRemark"
        }
        // Matches the type-naming convention createNodeFromUri (AddNodeModal.kt) uses everywhere
        // else in the app -- "ss" specifically is stored as "shadowsocks", not the raw URI scheme.
        val type = if (config.protocol == "ss") "shadowsocks" else config.protocol
        return VpnNode(
            id = UUID.randomUUID().toString(),
            name = badgeName,
            uri = rebrandedUri,
            type = type,
            engineType = "Manual",
            groupTitle = GROUP_NAME
        )
    }
}
