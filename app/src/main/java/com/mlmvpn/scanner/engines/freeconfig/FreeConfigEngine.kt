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
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

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

    // Deliberately NOT localised: this is the folder's identity on disk, not a label. Groups are
    // matched by name, so a value that changes with the language would orphan every folder a
    // user already has and quietly build a second one beside it.
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

    // ── the @Raydikalx aggregator's own categories ──────────────────────────────────────
    //
    // The aggregator merges ~19 public sources every 15 minutes and publishes them already
    // sorted by how each config did in its own testing. Its labels are worth surfacing
    // verbatim rather than flattening everything into one list: someone who picks "verified"
    // starts from configs that passed a real proxied request minutes ago, so their own test
    // finds working ones far sooner than it would from the raw set.
    //
    // The same pools back the desktop app's free-config panel, so a category means the same
    // thing on both.

    private const val INDEX_URL = "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/index.json"
    private const val INDEX_MIRROR = "https://cdn.jsdelivr.net/gh/0xRadikal/Free-v2ray-Configs@main/index.json"

    /** The pool the user picked, or where its entries came from. */
    data class Pool(
        val id: String,
        val title: String,
        /** One line on why this category exists -- shown under its title. */
        val why: String,
        /** How many unique configs it holds right now, straight from the aggregator's index. */
        val count: Int,
        val url: String = "",
        val mirror: String = "",
    )

    data class Catalog(
        val pools: List<Pool>,
        /** When the aggregator last rebuilt, ISO-8601, or null. */
        val updatedAt: String?,
        val intervalMinutes: Int?,
        val healthySources: Int?,
        val totalSources: Int?,
    )

    /** Our own aggregate of the four lists this app has always used. */
    const val POOL_LEGACY = "legacy"

    private data class PoolSpec(val id: String, val title: String, val why: String, val path: String)

    // Best-first: the order they are offered in.
    private val POOL_SPECS = listOf(
        PoolSpec("verified", S(R.string.verified), S(R.string.a_real_request_passed_through_them_in), "verified/configs.txt"),
        PoolSpec("fast", S(R.string.fast), S(R.string.verified_with_a_median_latency_under_the), "fast/configs.txt"),
        PoolSpec("secure", S(R.string.secure), S(R.string.verified_with_forward_secrecy_and_without_disabling), "secure/configs.txt"),
        PoolSpec("all", S(R.string.all_4), S(R.string.the_whole_set_with_no_pre_testing), "all/configs.txt"),
    )

    private var catalogCache: Catalog? = null
    private var catalogAt = 0L
    private const val CATALOG_TTL_MS = 5 * 60 * 1000L

    /** Fetch text, primary first then the jsDelivr mirror -- raw.githubusercontent is filtered. */
    private suspend fun fetchText(url: String, mirror: String?): String = withContext(Dispatchers.IO) {
        var lastErr: String? = null
        for (target in listOfNotNull(url, mirror)) {
            try {
                client.newCall(Request.Builder().url(target).get().build()).execute().use { res ->
                    if (!res.isSuccessful) {
                        lastErr = "HTTP ${res.code}"
                        return@use
                    }
                    return@withContext res.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                lastErr = e.message
            }
        }
        throw IllegalStateException(lastErr ?: S(R.string.not_fetched))
    }

    /**
     * What is on offer right now: category sizes, freshness, and how many sources are healthy.
     *
     * Read from the aggregator's own `index.json` rather than hard-coded, so the counts the
     * user sees are the counts they will actually get. A category the index reports as empty
     * is dropped rather than offered and then found to be nothing.
     *
     * The legacy aggregate is always appended, because it is this app's own set of sources and
     * does not depend on the aggregator being reachable at all.
     */
    suspend fun fetchCatalog(force: Boolean = false): Catalog = withContext(Dispatchers.IO) {
        val cached = catalogCache
        if (!force && cached != null && System.currentTimeMillis() - catalogAt < CATALOG_TTL_MS) {
            return@withContext cached
        }

        val legacy = Pool(
            id = POOL_LEGACY,
            title = S(R.string.extra_sources),
            why = S(R.string.the_four_public_lists_this_app_has),
            count = -1,   // unknown until fetched; the UI shows it as "?" rather than "0"
        )

        val catalog = try {
            val idx = org.json.JSONObject(fetchText(INDEX_URL, INDEX_MIRROR))
            val base = idx.optString("raw_base").ifBlank { "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main" }
            val mirrorBase = idx.optString("cdn_base").ifBlank { "https://cdn.jsdelivr.net/gh/0xRadikal/Free-v2ray-Configs@main" }

            // Counts live in two objects: the cascade ones (verified/fast/secure) and the plain
            // categories (all/heavy/light). Merge both so one lookup covers every pool.
            val counts = HashMap<String, Int>()
            for (key in listOf("cascade_categories", "categories")) {
                val obj = idx.optJSONObject(key) ?: continue
                for (name in obj.keys()) {
                    obj.optJSONObject(name)?.optInt("unique")?.takeIf { it > 0 }?.let { counts[name] = it }
                }
            }

            val pools = POOL_SPECS.mapNotNull { spec ->
                val count = counts[spec.id] ?: return@mapNotNull null
                Pool(
                    id = spec.id, title = spec.title, why = spec.why, count = count,
                    url = "$base/${spec.path}", mirror = "$mirrorBase/${spec.path}",
                )
            }

            val sources = idx.optJSONObject("sources")
            Catalog(
                pools = pools + legacy,
                updatedAt = idx.optString("updated_at").ifBlank { null },
                intervalMinutes = idx.optInt("update_interval_minutes").takeIf { it > 0 },
                healthySources = sources?.optInt("healthy"),
                totalSources = sources?.optInt("total_count"),
            )
        } catch (e: Exception) {
            // The aggregator being unreachable must not take the whole feature down -- the
            // app's own sources still work, so offer those alone rather than an error screen.
            Log.w(TAG, "catalog failed: ${e.message}")
            Catalog(listOf(legacy), null, null, null, null)
        }

        catalogCache = catalog
        catalogAt = System.currentTimeMillis()
        catalog
    }

    /**
     * Every candidate URI in one pool, deduplicated by real server identity.
     *
     * [POOL_LEGACY] falls through to this app's own four sources; anything else is one of the
     * aggregator's category files.
     */
    suspend fun fetchCandidates(pool: Pool): List<String> {
        if (pool.id == POOL_LEGACY) return fetchCandidates()
        val text = fetchText(pool.url, pool.mirror)
        // Off the caller's thread: the wizard asks from the main thread, and the dedupe parses
        // every link of a pool -- thousands -- which froze the screen (freeze reports, 1.2.41).
        return withContext(Dispatchers.Default) {
            text.lines()
                .map { it.trim() }
                .filter { line -> URI_PREFIXES.any { prefix -> line.startsWith(prefix, ignoreCase = true) } }
                .distinctBy { dedupeKey(it) }
        }
    }

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
        // Shadowsocks carries no uuid, so its identity is method+password; without them two
        // different ss accounts on one host:port would collapse into a single entry.
        val secret = if (config.protocol == "ss") "${config.method}:${config.password}" else config.uuid
        return "${config.protocol}|${config.address}|${config.port}|$secret"
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

    /** Also used by the quick-connect scanner, which runs the same real-delay primitive. */
    @Volatile
    private var xrayEnvReady = false
    private val xrayEnvLock = Any()

    suspend fun ensureXrayEnv(context: Context) = withContext(Dispatchers.IO) {
        // Once per process, and never again.
        //
        // This used to run on every call, and the quick-connect scanner calls it once per
        // config. `initCoreEnv` sets up the core's shared global state and mints a new
        // xudpBaseKey each time, so re-running it while a dozen measurement cores are mid-flight
        // was re-initialising the ground under them -- servers that were perfectly alive came
        // back as failures, in numbers large enough to look like the internet was down. The
        // asset copy is also pure I/O that only matters the first time.
        if (xrayEnvReady) return@withContext
        synchronized(xrayEnvLock) {
            if (xrayEnvReady) return@withContext
            xrayEnvReady = true
        }
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
        // The core itself never runs in the main process (XrayHosts, `:xmeasure`): nothing to set up here.
        if (com.mlmvpn.core.warp.XrayHosts.inMainProcess(context)) return@withContext
        try {
            val keyBytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(keyBytes)
            val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            val xudpBaseKey = android.util.Base64.encodeToString(keyBytes, flags)
            libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey)
        } catch (e: Throwable) {
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
    ): List<VpnNode> = withContext(Dispatchers.Default) { coroutineScope {
        // Off the caller's thread (the wizard's is the main one): only the progress goes back to it.
        val caller = Dispatchers.Main.immediate
        suspend fun report(p: TestProgress) = withContext(caller) { onProgress(p) }
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
            report(TestProgress(tested, candidates.size, working.size, targetCount))

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
                            // In `:xmeasure` (CombineEngine.measureDelay): a free config that panics
                            // Xray ends that process, not the app, and the timeout really fires.
                            val delayMs = com.mlmvpn.scanner.data.CombineEngine.measureDelay(jsonConfig, DELAY_TEST_URL, 6_000L)
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
                report(TestProgress(tested, candidates.size, working.size, targetCount))
            }
        }

        working
    } }

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
