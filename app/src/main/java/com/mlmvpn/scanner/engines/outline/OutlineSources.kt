package com.mlmvpn.scanner.engines.outline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Where the free Outline (Shadowsocks) servers come from: public GitHub lists that their owners
 * rebuild every few minutes to every few hours. Checked on 2026-10-10 -- every one had been rebuilt
 * that same day:
 *
 *  - OpenRay and F0rc3Run publish only servers their own pipeline has just seen carry traffic,
 *    so they are tested first ([prior]).
 *  - barry-far, ebrasha, Epodonios and mahdibland are large hourly-or-better collections.
 *
 * Each is fetched from raw.githubusercontent.com, then from jsDelivr's copy when GitHub is
 * filtered, then both again through the running tunnel's local proxy.
 *
 * Shadowmere (shadowmere.xyz, added 2026-10-25) is a site, not a GitHub file: it re-checks every key
 * it knows every 20-40 minutes (rounds seen at 19:00 and 19:40 UTC, each written within two minutes)
 * and its API hands out only the ones that passed, with where each one is and when it was checked --
 * so it is trusted first. The site is filtered in Iran: it is asked only through one of the app's
 * tunnels, and the APK carries the keys it listed when the app was built ([OutlineSources.SEED_ASSET]).
 */
data class OutlineSource(
    val id: String,
    val label: String,
    val repo: String = "",
    val branch: String = "",
    val path: String = "",
    val prior: Double,
    val everyHours: Int = 1,
    val everyMinutes: Int = everyHours * 60,
    val maxBytes: Int = 3 * 1024 * 1024,
    /**
     * A site with an API of its own instead of a GitHub file ([OutlineSourceRepo] has its reader),
     * filtered in Iran: asked only through one of the app's tunnels.
     */
    val site: String? = null,
) {
    val rawUrl: String get() = "https://raw.githubusercontent.com/$repo/$branch/$path"
    val mirrorUrl: String get() = "https://cdn.jsdelivr.net/gh/$repo@$branch/$path"
    /** Where it comes from, as the sources page shows it. */
    val where: String get() = site ?: repo
}

object OutlineSources {

    /** Also the tag its keys carry, in their names and on their rows. */
    const val SHADOWMERE = "shadowmere"

    val BUILT_IN: List<OutlineSource> = listOf(
        OutlineSource(SHADOWMERE, "Shadowmere", prior = 0.9, everyMinutes = 30, site = "shadowmere.xyz"),
        OutlineSource("openray", "OpenRay", "sakha1370/OpenRay", "main", "output/kind/ss.txt", 0.85),
        OutlineSource("forcerun", "F0rc3Run", "F0rc3Run/F0rc3Run", "main", "splitted-by-protocol/shadowsocks.txt", 0.8, everyHours = 3),
        OutlineSource("barryfar", "barry-far", "barry-far/V2ray-Config", "main", "Splitted-By-Protocol/ss.txt", 0.6),
        OutlineSource("epodonios", "Epodonios", "Epodonios/v2ray-configs", "main", "Splitted-By-Protocol/ss.txt", 0.55),
        OutlineSource("ebrasha", "EbraSha", "ebrasha/free-v2ray-public-list", "main", "ss_configs.txt", 0.5),
        OutlineSource("mahdibland", "ShadowsocksAggregator", "mahdibland/ShadowsocksAggregator", "master", "sub/splitted/ss.txt", 0.45, everyHours = 6),
    )

    fun byId(id: String): OutlineSource? = BUILT_IN.firstOrNull { it.id == id }

    fun prior(id: String): Double = when (id) {
        OutlineServer.USER_SOURCE -> 1.0
        else -> byId(id)?.prior ?: 0.3
    }

    fun label(id: String): String = byId(id)?.label ?: id

    /** Shadowmere's keys as it listed them when the app was built (scripts/update-shadowmere-seed.js). */
    const val SEED_ASSET = "outline/shadowmere_seed.json"

    private const val RETRY_MS = 10 * 60_000L

    /**
     * Due again [OutlineSource.everyMinutes] after it last answered ([lastOk]). One that does not
     * answer is asked again [RETRY_MS] after it was last asked ([lastTry]), not every time the page
     * looks.
     */
    fun due(src: OutlineSource, now: Long, lastOk: Long, lastTry: Long): Boolean =
        now - lastTry >= RETRY_MS && now - lastOk >= src.everyMinutes * 60_000L
}

/** Fetches the lists, remembering per source when it last answered and its ETag. */
class OutlineSourceRepo(private val app: Context) {

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Fetched(val source: OutlineSource, val batch: OutlineParser.Batch)

    fun lastOk(id: String): Long = prefs.getLong("ok_$id", 0)
    fun lastCount(id: String): Int = prefs.getInt("n_$id", 0)
    private fun lastTry(id: String): Long = prefs.getLong("try_$id", 0)

    fun due(src: OutlineSource, now: Long): Boolean = OutlineSources.due(src, now, lastOk(src.id), lastTry(src.id))

    /** [tunnelUp]: one of the app's tunnels is up, the only way to the filtered sites. */
    fun anyDue(disabled: Set<String>, now: Long, tunnelUp: Boolean): Boolean =
        OutlineSources.BUILT_IN.any { it.id !in disabled && (it.site == null || tunnelUp) && due(it, now) }

    /**
     * The lists that are due ([force]: all of them), only [only] when given. A site
     * ([OutlineSource.site]) is asked only when [tunnelUp]: without a tunnel it is skipped, not tried
     * and failed.
     */
    suspend fun refresh(
        force: Boolean, disabled: Set<String>, proxyPort: Int?, now: Long, tunnelUp: Boolean, only: Set<String>? = null,
    ): List<Fetched> = coroutineScope {
        if (force) prefs.edit().apply { prefs.all.keys.filter { it.startsWith("etag_") }.forEach { remove(it) } }.apply()
        val gate = Semaphore(3)
        OutlineSources.BUILT_IN
            .filter { it.id !in disabled && (only == null || it.id in only) && (it.site == null || tunnelUp) }
            .filter { force || due(it, now) }
            .onEach { prefs.edit().putLong("try_${it.id}", now).apply() }
            .map { src ->
                async(Dispatchers.IO) {
                    if (src.site != null) runCatching { fetchShadowmere(src, now) }.getOrNull()
                    else gate.withPermit { runCatching { fetchOne(src, proxyPort, now) }.getOrNull() }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    /**
     * Shadowmere's API: every key that passed this hour's check, ten to a page, each with where it
     * is, when it was checked and how often it passed. The pages come together; when the API does
     * not answer, or a page of it does not, the subscription -- the same keys in one request, without
     * the check times -- fills in.
     *
     * Only through the tunnel that is up: the update client ([com.mlmvpn.scanner.update.UpdateNet])
     * goes through the running engine's local proxy, whichever tile it is, with the name resolved at
     * the far end -- the app is outside its own VPN, so a plain socket would meet the filter.
     *
     * The site sits behind Cloudflare's bot check, which turns away curl's and Java's default
     * User-Agent (403 challenge, measured 2026-10-25); ours and okhttp's pass.
     */
    private suspend fun fetchShadowmere(src: OutlineSource, now: Long): Fetched? = coroutineScope {
        val api = "https://${src.site}/api"
        val client = com.mlmvpn.scanner.update.UpdateNet.client(app, connectSeconds = 10, readSeconds = 25)
        fun page(n: Int) = getText(client, "$api/proxies/?is_active=true&page=$n")?.let { OutlineParser.parseShadowmerePage(it, src.id, now) }
        val first = page(1)
        val gate = Semaphore(4)
        val rest = (2..(first?.pages ?: 1).coerceAtMost(SHADOWMERE_MAX_PAGES))
            .map { n -> async(Dispatchers.IO) { gate.withPermit { page(n) } } }
            .awaitAll()
        val pages = listOfNotNull(first) + rest.filterNotNull()
        val out = LinkedHashMap<String, OutlineServer>()
        pages.forEach { p -> p.batch.servers.forEach { out.putIfAbsent(it.id, it) } }
        var skipped = pages.sumOf { it.batch.skipped }
        // A page lost, or keys that moved between pages while a round was being written.
        if (first == null || rest.any { it == null } || out.size < first.count) {
            getText(client, "$api/sub/")?.let { OutlineParser.parseSip008(it, src.id, now) }?.let { sub ->
                sub.servers.forEach { out.putIfAbsent(it.id, it) }
                if (pages.isEmpty()) skipped = sub.skipped
            }
        }
        Log.i(TAG, "source ${src.id}: ${out.size} servers from ${pages.size} API pages, $skipped skipped")
        if (out.isEmpty()) return@coroutineScope null
        prefs.edit().putLong("ok_${src.id}", now).putInt("n_${src.id}", out.size).apply()
        Fetched(src, OutlineParser.Batch(out.values.toList(), skipped))
    }

    private fun getText(client: OkHttpClient, url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    /**
     * Shadowmere's keys from the APK ([OutlineSources.SEED_ASSET]), when they are newer than anything
     * this phone has had: on a fresh install, and after an update that carries a newer copy. Null
     * otherwise -- a copy already taken in, or a fetch through a tunnel since.
     */
    fun shadowmereSeed(now: Long): List<OutlineServer>? = runCatching {
        val text = app.assets.open(OutlineSources.SEED_ASSET).bufferedReader().use { it.readText() }
        val servers = OutlineParser.parseShadowmerePage(text, OutlineSources.SHADOWMERE, now)?.batch?.servers.orEmpty()
        val built = servers.maxOfOrNull { it.checkedAt } ?: return@runCatching null
        if (built <= prefs.getLong(SEED_KEY, 0) || built <= lastOk(OutlineSources.SHADOWMERE)) return@runCatching null
        prefs.edit().putLong(SEED_KEY, built).apply()
        servers
    }.onFailure { Log.w(TAG, "shadowmere seed not read: ${it.javaClass.simpleName}") }.getOrNull()

    private fun fetchOne(src: OutlineSource, proxyPort: Int?, now: Long): Fetched? {
        val body = get(src, proxyPort) ?: return null
        if (body === NOT_MODIFIED) { prefs.edit().putLong("ok_${src.id}", now).apply(); return null }
        val batch = OutlineParser.parseText(String(body, Charsets.UTF_8), src.id, now)
        Log.i(TAG, "source ${src.id}: ${batch.servers.size} servers, ${batch.skipped} skipped")
        if (batch.servers.isEmpty()) return null
        prefs.edit().putLong("ok_${src.id}", now).putInt("n_${src.id}", batch.servers.size).apply()
        return Fetched(src, batch)
    }

    private fun get(src: OutlineSource, proxyPort: Int?): ByteArray? {
        val etagKey = "etag_${src.id}"
        val etag = prefs.getString(etagKey, null)
        val attempts = listOfNotNull(
            src.rawUrl to null, src.mirrorUrl to null,
            proxyPort?.let { src.rawUrl to httpProxy(it) }, proxyPort?.let { src.mirrorUrl to httpProxy(it) },
        )
        for ((u, proxy) in attempts) {
            val r = runCatching { request(u, proxy, src.maxBytes, etag.takeIf { u == src.rawUrl }) }.getOrNull() ?: continue
            if (r.notModified) return NOT_MODIFIED
            r.etag?.takeIf { u == src.rawUrl }?.let { prefs.edit().putString(etagKey, it).apply() }
            return r.body
        }
        return null
    }

    private class Response(val body: ByteArray?, val etag: String?, val notModified: Boolean)

    private fun request(url: String, proxy: Proxy?, maxBytes: Int, etag: String?): Response? {
        val c = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        etag?.let { c.setRequestProperty("If-None-Match", it) }
        try {
            return when (c.responseCode) {
                304 -> Response(null, etag, true)
                200 -> {
                    val out = java.io.ByteArrayOutputStream()
                    c.inputStream.use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > maxBytes) break
                        }
                    }
                    Response(out.toByteArray(), c.getHeaderField("ETag"), false)
                }
                else -> null
            }
        } finally { c.disconnect() }
    }

    /** The body behind an `ssconf://` key, on the line or through the tunnel. */
    fun fetchSsconf(url: String, proxyPort: Int?): String? {
        val attempts: List<Proxy?> = listOf<Proxy?>(null) + listOfNotNull(proxyPort?.let { httpProxy(it) })
        for (p in attempts) {
            val b = runCatching { request(url, p, 64 * 1024, null)?.body }.getOrNull() ?: continue
            return String(b, Charsets.UTF_8)
        }
        return null
    }

    private fun httpProxy(port: Int) = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))

    companion object {
        private const val TAG = "OutlineSources"
        private const val PREFS = "outline_sources"
        private val NOT_MODIFIED = ByteArray(0)
        private const val USER_AGENT = "MLMVPN-Outline"
        private const val SEED_KEY = "seed_shadowmere"
        /** Ten keys a page: a thousand keys is far beyond any round seen (183 on 2026-10-25). */
        private const val SHADOWMERE_MAX_PAGES = 100
    }
}
