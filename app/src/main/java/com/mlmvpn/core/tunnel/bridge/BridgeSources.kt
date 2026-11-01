package com.mlmvpn.core.tunnel.bridge

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Where bridges come from. Each source is data: adding one is a new entry in [ALL], nothing else.
 *
 * Checked on 2026-10-03 from an Iranian line:
 *  - Tor's own Moat API (`bridges.torproject.org/moat/circumvention/settings`) -- what Tor
 *    Browser's Connection Assist asks. No CAPTCHA, answers from Iran, and for country `ir` it hands
 *    out BridgeDB bridges per transport (two at a time; it rate-limits per address, so it is asked
 *    rarely). BridgeDB's web page and e-mail/Telegram channels need a person (CAPTCHA, a mailbox,
 *    a chat) and are not something an app should automate; Moat is the official machine door.
 *  - Three community collectors that gather from Tor Project and test reachability, each
 *    refreshed several times a day, each with an ETag. Their `_tested` lists are used, not the raw
 *    ones. They are labelled community everywhere and never outrank an official bridge on trust.
 * Raw GitHub answers from Iran; jsDelivr is the mirror for when it does not.
 */
object BridgeSources {

    sealed class Kind {
        /** Plain text, one bridge line per line. */
        data class Text(val urls: List<List<String>>) : Kind()
        /** Tor Project's Moat circumvention API. */
        object Moat : Kind()
    }

    data class Source(
        val id: String,
        val label: String,
        val official: Boolean,
        val kind: Kind,
        /** How often it is worth asking. Moat rate-limits; the collectors run hourly or so. */
        val everyMs: Long,
        val home: String,
    )

    private fun gh(repo: String, branch: String, path: String) = listOf(
        "https://raw.githubusercontent.com/$repo/$branch/$path",
        "https://cdn.jsdelivr.net/gh/$repo@$branch/$path",
    )

    private fun collector(id: String, label: String, repo: String, dir: String) = Source(
        id = id, label = label, official = false,
        kind = Kind.Text(listOf("obfs4_tested.txt", "webtunnel_tested.txt", "vanilla_tested.txt", "webtunnel_ipv6_tested.txt")
            .map { gh(repo, "main", "$dir/$it") }),
        everyMs = 3 * 3600_000L,
        home = "https://github.com/$repo",
    )

    val ALL: List<Source> = listOf(
        Source("tor-moat", "Tor Project", official = true, kind = Kind.Moat, everyMs = 6 * 3600_000L, home = "https://bridges.torproject.org/"),
        collector("onionhop", "OnionHop Collector", "center2055/OnionHop-Bridges-Collector", "bridge"),
        collector("delta", "Delta-Kronecker Collector", "Delta-Kronecker/Tor-Bridges-Collector", "bridge"),
        collector("scriptzteam", "Bridges Collector v2", "scriptzteam/Tor-Bridges-Collector-v2", "bridges"),
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    /** What one fetch of one source produced. [lines] is null when it failed outright. */
    data class Fetch(val lines: List<String>?, val changed: Boolean, val error: String?)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS).build()
    }

    private fun cacheDir(ctx: Context) = File(ctx.filesDir, "tor_bridges/cache").apply { mkdirs() }
    private fun key(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /**
     * Fetches one source. Every file of a source is asked conditionally (If-None-Match /
     * If-Modified-Since): an unchanged list costs a 304 and no download. A file that fails on both
     * its URL and its mirror falls back to its last good copy, so one bad file -- or one source
     * down -- never empties the bridge list.
     */
    fun fetch(ctx: Context, s: Source, countryCode: String): Fetch = when (val k = s.kind) {
        is Kind.Moat -> fetchMoat(ctx, countryCode)
        is Kind.Text -> {
            val out = ArrayList<String>()
            var changed = false
            var anyOk = false
            var lastErr: String? = null
            for (mirrors in k.urls) {
                val r = fetchText(ctx, mirrors)
                if (r.lines != null) { anyOk = true; out += r.lines; if (r.changed) changed = true } else lastErr = r.error
            }
            Fetch(if (anyOk) out else null, changed, lastErr)
        }
    }

    private fun fetchText(ctx: Context, mirrors: List<String>): Fetch {
        val primary = mirrors.first()
        val body = File(cacheDir(ctx), key(primary) + ".txt")
        val meta = File(cacheDir(ctx), key(primary) + ".meta")
        val m = runCatching { JSONObject(meta.readText()) }.getOrDefault(JSONObject())
        var lastErr: String? = null
        for (url in mirrors) {
            try {
                val b = Request.Builder().url(url).header("User-Agent", "mlmvpn-android")
                if (body.exists() && m.optString("url") == url) {
                    m.optString("etag").takeIf { it.isNotBlank() }?.let { b.header("If-None-Match", it) }
                    m.optString("lm").takeIf { it.isNotBlank() }?.let { b.header("If-Modified-Since", it) }
                }
                client.newCall(b.build()).execute().use { r ->
                    if (r.code == 304 && body.exists()) return Fetch(body.readLines(), false, null)
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    val text = r.body?.string().orEmpty()
                    // A list is text of bridge lines; an HTML error page from a captive portal is not.
                    if (text.length > 4_000_000 || text.trimStart().startsWith("<")) throw java.io.IOException("not a bridge list")
                    val old = if (body.exists()) body.readText() else null
                    body.writeText(text)
                    meta.writeText(JSONObject().put("url", url).put("etag", r.header("ETag").orEmpty())
                        .put("lm", r.header("Last-Modified").orEmpty()).toString())
                    return Fetch(text.lines(), old != text, null)
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }
        return if (body.exists()) Fetch(body.readLines(), false, lastErr) else Fetch(null, false, lastErr)
    }

    /**
     * Moat: the country's recommended settings, then the defaults. Each answer is a list of
     * `{bridges: {type, source, bridge_strings}}`; `source: builtin` entries carry no lines (they
     * mean "use your built-in ones") and are skipped.
     */
    private fun fetchMoat(ctx: Context, cc: String): Fetch {
        val cache = File(cacheDir(ctx), "moat.json")
        val json = "application/vnd.api+json".toMediaType()
        val transports = JSONArray().put("obfs4").put("webtunnel").put("snowflake").put("meek_lite").put("vanilla")
        val lines = LinkedHashSet<String>()
        var err: String? = null
        for ((path, body) in listOf(
            "settings" to JSONObject().put("country", cc.lowercase()).put("transports", transports).toString(),
            "defaults" to "{}",
        )) {
            try {
                val req = Request.Builder().url("https://bridges.torproject.org/moat/circumvention/$path")
                    .header("User-Agent", "mlmvpn-android")
                    .post(body.toRequestBody(json)).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    val o = JSONObject(r.body?.string().orEmpty())
                    val settings = o.optJSONArray("settings") ?: JSONArray()
                    for (i in 0 until settings.length()) {
                        val strings = settings.optJSONObject(i)?.optJSONObject("bridges")?.optJSONArray("bridge_strings") ?: continue
                        for (j in 0 until strings.length()) strings.optString(j).takeIf { it.isNotBlank() }?.let { lines += it }
                    }
                }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
        }
        if (lines.isEmpty()) {
            val cached = runCatching { JSONArray(cache.readText()) }.getOrNull()
            return if (cached != null) Fetch((0 until cached.length()).map { cached.getString(it) }, false, err ?: "empty")
            else Fetch(null, false, err ?: "empty")
        }
        // Moat hands out a few bridges per ask: what it gave before is kept beside what it gives now.
        val previous = runCatching { JSONArray(cache.readText()) }.getOrNull()
        val merged = LinkedHashSet<String>(lines)
        if (previous != null) for (i in 0 until previous.length()) merged += previous.getString(i)
        val kept = merged.toList().take(60)
        cache.writeText(JSONArray(kept).toString())
        return Fetch(kept, previous == null || kept.size != previous.length() || lines.any { l -> (0 until previous.length()).none { previous.getString(it) == l } }, null)
    }
}
