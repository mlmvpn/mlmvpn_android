package com.mlmvpn.core.warp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * «کشور خروجی با پراکسی رایگان»: the IPv4 way to an exit country for «وارپ», «ماسک», «وایرگارد» and
 * «وارپ در وارپ» -- the desktop's exit-proxies.js (2026-10-13/14), on the phone.
 *
 * NAT64 ([Nat64Exits]) moves where WARP itself comes out, but only on a line with IPv6. This is the
 * other way, a hop AFTER the engine:
 *
 *     apps → TUN → SocksFront ──exit: a free proxy in the chosen country──► dialled THROUGH WARP
 *
 * WARP carries the connection out of Iran; the free proxy, reached from Cloudflare's network, is where
 * it finally leaves, so the sites see the proxy's country. What it cannot do, and the page says so:
 * UDP (free proxies carry TCP only; QUIC falls back to TCP at once, DNS still resolves through WARP),
 * privacy (the proxy's owner sees which sites are visited), and staying up (free proxies die within
 * hours -- the pool is measured, the best few are kept, a dead one is swapped for the next).
 *
 * Every number is measured THROUGH WARP ([WarpVia]): a proxy that answers the user's line can refuse
 * Cloudflare's addresses (desktop, 2026-10-13). A country is a fact only once Cloudflare's trace
 * through the proxy names it; the list's own country is a guess.
 */
object Ipv4Exits {
    private const val TAG = "Ipv4Exits"
    private const val FILE = "exit_proxy.json"
    /** The shipped list: an index, and one file per country (scripts/gen-exit-proxy-seed.js). */
    private const val SEED_INDEX = "exit_proxies/index.json"
    private const val SEED_COUNTRY = "exit_proxies/c/%s.json"
    const val POOL_KEEP = 8
    private const val LIST_TTL_MS = 20 * 60_000L
    /** A proxy a connection found dead is skipped this long, unless a measurement sees it answer. */
    private const val DEAD_MS = 30 * 60_000L
    const val TRACE_HOST = "speed.cloudflare.com"

    /**
     * What a proxy speaks. SOCKS4 too, unlike the desktop (its Xray outbound cannot): the phone's own
     * front speaks it (SOCKS4a for names), and the shipped list alone has thousands of them.
     */
    enum class Kind(val code: String) {
        SOCKS5("socks5"), HTTP("http"), SOCKS4("socks4");

        companion object {
            fun of(code: String?): Kind = values().firstOrNull { it.code == code } ?: HTTP
        }
    }

    /** One listed proxy, not yet measured. */
    data class Candidate(
        val ip: String, val port: Int, val kind: Kind,
        val city: String = "", val speedMs: Long? = null, val source: String = SRC_SEED,
    )

    /** A measured proxy that carried traffic out of [loc]. */
    data class PoolEntry(
        val ip: String, val port: Int, val kind: Kind, val city: String, val loc: String,
        val rttMs: Long?, val at: Long, val confirmedAt: Long = 0, val movedFrom: String? = null,
    ) {
        val key: String get() = "$ip:$port"
    }

    /** What the last look at one country found. */
    data class Ping(
        val ok: Boolean, val rttMs: Long?, val found: Int, val tested: Int, val listed: Int,
        val at: Long, val viaWarp: Boolean, val confirmedAt: Long = 0,
    )

    data class State(
        val enabled: Boolean = false,
        /** The privacy warning has been read and accepted once. */
        val ack: Boolean = false,
        val country: String = "",
        val pools: Map<String, List<PoolEntry>> = emptyMap(),
        val pings: Map<String, Ping> = emptyMap(),
        val dead: Map<String, Long> = emptyMap(),
        val scannedAt: Long = 0,
    ) {
        fun poolOf(cc: String = country): List<PoolEntry> = pools[cc].orEmpty()
    }

    data class ProbeResult(val ok: Boolean, val rttMs: Long?, val loc: String? = null, val ip: String? = null, val error: String? = null)

    class ExitError(val code: String, message: String) : Exception(message)

    private const val SRC_SEED = "seed"
    private const val SRC_PROXIFLY = "proxifly"
    private const val SRC_FPW = "freeproxy.world"

    private val _state = MutableStateFlow<State?>(null)

    /** For the page; [load] fills it the first time. */
    fun stateFlow(ctx: Context): StateFlow<State?> {
        if (_state.value == null) _state.value = load(ctx)
        return _state.asStateFlow()
    }

    // ── the store ──────────────────────────────────────────────────────────────────────────────

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)
    private val ioLock = Any()

    fun load(ctx: Context): State = synchronized(ioLock) {
        _state.value?.let { return it }
        val s = runCatching { parse(JSONObject(file(ctx).readText())) }.getOrDefault(State())
        _state.value = s
        s
    }

    private fun save(ctx: Context, s: State): State = synchronized(ioLock) {
        _state.value = s
        runCatching {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(toJson(s).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "save: ${it.message}") }
        s
    }

    private fun update(ctx: Context, change: (State) -> State): State = synchronized(ioLock) { save(ctx, change(load(ctx))) }

    private fun parse(o: JSONObject): State {
        fun entry(x: JSONObject) = PoolEntry(
            x.getString("ip"), x.getInt("port"), Kind.of(x.optString("kind")),
            x.optString("city"), x.optString("loc"), x.optLong("rtt", -1).takeIf { it >= 0 }, x.optLong("at"),
            x.optLong("confirmedAt"), x.optString("movedFrom").ifBlank { null },
        )
        val pools = HashMap<String, List<PoolEntry>>()
        o.optJSONObject("pools")?.let { p -> p.keys().forEach { cc -> val a = p.getJSONArray(cc); pools[cc] = (0 until a.length()).map { entry(a.getJSONObject(it)) } } }
        val pings = HashMap<String, Ping>()
        o.optJSONObject("pings")?.let { p ->
            p.keys().forEach { cc ->
                val x = p.getJSONObject(cc)
                pings[cc] = Ping(x.optBoolean("ok"), x.optLong("rtt", -1).takeIf { it >= 0 }, x.optInt("found"), x.optInt("tested"),
                    x.optInt("listed"), x.optLong("at"), x.optBoolean("viaWarp"), x.optLong("confirmedAt"))
            }
        }
        val dead = HashMap<String, Long>()
        o.optJSONObject("dead")?.let { d -> d.keys().forEach { k -> dead[k] = d.getLong(k) } }
        return State(o.optBoolean("enabled"), o.optBoolean("ack"), o.optString("country"), pools, pings, dead, o.optLong("scannedAt"))
    }

    private fun toJson(s: State): JSONObject {
        val now = System.currentTimeMillis()
        fun entry(e: PoolEntry) = JSONObject().put("ip", e.ip).put("port", e.port).put("kind", e.kind.code)
            .put("city", e.city).put("loc", e.loc).put("rtt", e.rttMs ?: -1).put("at", e.at).put("confirmedAt", e.confirmedAt).put("movedFrom", e.movedFrom ?: "")
        return JSONObject()
            .put("enabled", s.enabled).put("ack", s.ack).put("country", s.country).put("scannedAt", s.scannedAt)
            .put("pools", JSONObject().apply { s.pools.forEach { (cc, l) -> put(cc, JSONArray().apply { l.forEach { put(entry(it)) } }) } })
            .put("pings", JSONObject().apply {
                s.pings.forEach { (cc, p) ->
                    put(cc, JSONObject().put("ok", p.ok).put("rtt", p.rttMs ?: -1).put("found", p.found).put("tested", p.tested)
                        .put("listed", p.listed).put("at", p.at).put("viaWarp", p.viaWarp).put("confirmedAt", p.confirmedAt))
                }
            })
            // A dead mark older than its use is forgotten rather than kept for ever.
            .put("dead", JSONObject().apply { s.dead.forEach { (k, t) -> if (now - t < 24 * 3600_000L) put(k, t) } })
    }

    // ── the offline list ───────────────────────────────────────────────────────────────────────

    /** The countries the IPv4 list offers before anything is fetched: the usual ones first. */
    private val DEFAULT_COUNTRIES = listOf(
        "DE", "NL", "US", "GB", "FR", "FI", "SE", "CA", "JP", "SG", "HK", "TR", "AE", "PL", "IT", "ES",
        "CH", "AT", "RU", "IN", "KR", "BR", "AU", "RO", "CZ",
    )

    /** The shipped list's index: total and countries, and per country how many it has. */
    private class SeedIndex(val total: Int, val counts: Map<String, Int>)

    @Volatile private var seedIndex: SeedIndex? = null

    private fun index(ctx: Context): SeedIndex {
        seedIndex?.let { return it }
        val idx = runCatching {
            val o = JSONObject(ctx.applicationContext.assets.open(SEED_INDEX).bufferedReader().use { it.readText() })
            val c = o.getJSONObject("countries")
            val counts = HashMap<String, Int>()
            c.keys().forEach { cc -> val x = c.getJSONObject(cc); counts[cc] = x.optInt("s5") + x.optInt("h") + x.optInt("s4") }
            SeedIndex(o.optInt("total", counts.values.sum()), counts)
        }.getOrElse { Log.w(TAG, "seed index: ${it.message}"); SeedIndex(0, emptyMap()) }
        seedIndex = idx
        return idx
    }

    /** The shipped list's size, for the page: proxies and countries (the same count the desktop states). */
    fun seedInfo(ctx: Context): Pair<Int, Int> = index(ctx).let { it.total to it.counts.size }

    /**
     * One country's shipped proxies, read from its own file: a scan looks at one country, and parsing
     * all ~48k at once would hold them in memory on a low-end phone for nothing. The last few are kept.
     */
    private val seedCountryCache = object : LinkedHashMap<String, List<Candidate>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Candidate>>?) = size > 6
    }

    private fun seedOf(ctx: Context, cc: String): List<Candidate> {
        synchronized(seedCountryCache) { seedCountryCache[cc]?.let { return it } }
        if (cc !in index(ctx).counts) return emptyList()
        val list = runCatching {
            val rows = JSONArray(ctx.applicationContext.assets.open(SEED_COUNTRY.format(cc)).bufferedReader().use { it.readText() })
            (0 until rows.length()).mapNotNull { i ->
                val r = rows.optJSONArray(i) ?: return@mapNotNull null
                val kind = when (r.optString(2)) { "s" -> Kind.SOCKS5; "4" -> Kind.SOCKS4; else -> Kind.HTTP }
                Candidate(r.getString(0), r.getInt(1), kind, r.optString(4), if (r.isNull(3)) null else r.optLong(3), SRC_SEED)
            }
        }.getOrElse { Log.w(TAG, "seed $cc: ${it.message}"); emptyList() }
        synchronized(seedCountryCache) { seedCountryCache[cc] = list }
        return list
    }

    /** Every country of the IPv4 list: the usual ones, then each the shipped copy has three or more for. */
    fun countryList(ctx: Context): List<String> {
        val counts = index(ctx).counts
        return DEFAULT_COUNTRIES + counts.keys.filter { it !in DEFAULT_COUNTRIES && (counts[it] ?: 0) >= 3 }
            .sortedByDescending { counts[it] ?: 0 }
    }

    // ── the live lists ─────────────────────────────────────────────────────────────────────────

    private val listCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Candidate>>>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 11)").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    /**
     * One country's proxies: proxifly's per-country list first (its entries carry traffic far more
     * often -- desktop, DE 2026-10-13: 1 of 155 freeproxy.world rows worked, 11 of proxifly's),
     * then freeproxy.world's pages, then the shipped copy -- which is what is left when both sites
     * are filtered. Cached for 20 minutes; never throws.
     */
    fun fetchList(ctx: Context, country: String): List<Candidate> {
        val cc = country.uppercase(Locale.US)
        if (!Regex("^[A-Z]{2}$").matches(cc)) return emptyList()
        listCache[cc]?.let { (at, l) -> if (System.currentTimeMillis() - at < LIST_TTL_MS) return l }
        val seen = HashSet<String>()
        val out = ArrayList<Candidate>()
        fun add(c: Candidate) { if (seen.add("${c.ip}:${c.port}")) out += c }
        for (url in listOf(
            "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/countries/$cc/data.txt",
            "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/countries/$cc/data.txt",
        )) {
            val text = get(url) ?: continue
            parseProxifly(text).forEach(::add)
            break
        }
        for (type in listOf("socks5", "http")) {
            for (page in 1..2) {
                val html = get("https://www.freeproxy.world/?type=$type&anonymity=&country=$cc&speed=&port=&page=$page") ?: break
                val rows = parseFreeproxyWorld(html).filter { it.second.isEmpty() || it.second == cc }.map { it.first }
                rows.forEach(::add)
                if (rows.size < 10) break
            }
        }
        seedOf(ctx, cc).forEach(::add)
        if (out.isNotEmpty()) listCache[cc] = System.currentTimeMillis() to out
        return out
    }

    internal fun parseProxifly(text: String): List<Candidate> = text.lineSequence().mapNotNull { line ->
        val m = Regex("""^(socks5|socks4|http|https)://(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})\s*$""", RegexOption.IGNORE_CASE).find(line.trim()) ?: return@mapNotNull null
        val port = m.groupValues[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
        val kind = when (m.groupValues[1].lowercase(Locale.US)) { "socks5" -> Kind.SOCKS5; "socks4" -> Kind.SOCKS4; else -> Kind.HTTP }
        Candidate(m.groupValues[2], port, kind, source = SRC_PROXIFLY)
    }.toList()

    private fun strip(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    /** freeproxy.world's table → (candidate, its listed country). */
    internal fun parseFreeproxyWorld(html: String): List<Pair<Candidate, String>> {
        val out = ArrayList<Pair<Candidate, String>>()
        for (row in html.split(Regex("<tr\\b", RegexOption.IGNORE_CASE)).drop(1)) {
            val cells = row.split(Regex("<td\\b", RegexOption.IGNORE_CASE)).drop(1).map { it.replaceFirst(Regex("^[^>]*>"), "") }
            if (cells.size < 6) continue
            val ip = strip(cells[0])
            val port = strip(cells[1]).toIntOrNull() ?: continue
            if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(ip) || port !in 1..65535) continue
            val cc = Regex("country=([A-Za-z]{2})").find(cells[2])?.groupValues?.get(1)?.uppercase(Locale.US).orEmpty()
            val types = Regex("type=(socks5|socks4|https|http)", RegexOption.IGNORE_CASE).findAll(row).map { it.groupValues[1].lowercase(Locale.US) }.toSet()
            val kind = when {
                "socks5" in types -> Kind.SOCKS5
                "http" in types || "https" in types -> Kind.HTTP
                "socks4" in types -> Kind.SOCKS4
                else -> continue
            }
            val speed = Regex("(\\d+)\\s*ms").find(strip(cells[4]))?.groupValues?.get(1)?.toLongOrNull()
            out += Candidate(ip, port, kind, strip(cells[3]).take(40), speed, SRC_FPW) to cc
        }
        return out
    }

    /**
     * The live lists before the shipped one, and within each SOCKS5 (they carried far more often on the
     * desktop), then HTTP, then SOCKS4 (no IPv6 targets); a list's own speed decides the rest.
     */
    private fun ordered(list: List<Candidate>): List<Candidate> {
        fun kindRank(c: Candidate) = when (c.kind) { Kind.SOCKS5 -> 0; Kind.HTTP -> 1; Kind.SOCKS4 -> 2 }
        fun sourceRank(c: Candidate) = when (c.source) { SRC_PROXIFLY -> 0; SRC_FPW -> 1; else -> 2 }
        return list.sortedWith(compareBy<Candidate>({ sourceRank(it) }, { kindRank(it) }, { it.speedMs ?: 99_999 }))
    }

    // ── measuring one ──────────────────────────────────────────────────────────────────────────

    /** A TCP socket to [host]:[port] -- through the local SOCKS5 [via] (WARP) when it is not 0. */
    private fun dial(host: String, port: Int, via: Int, timeoutMs: Int): Socket {
        val s = if (via > 0) Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", via))) else Socket(Proxy.NO_PROXY)
        s.soTimeout = timeoutMs
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        s.tcpNoDelay = true
        return s
    }

    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 8192 && !sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readAll(input: InputStream, max: Int = 64 * 1024): String {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buf.size() < max) {
            val n = runCatching { input.read(chunk) }.getOrDefault(-1)
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toString("ISO-8859-1")
    }

    private fun field(body: String, k: String) = Regex("(?m)^$k=(.*)$").find(body)?.groupValues?.get(1)?.trim()

    /** A SOCKS5 CONNECT to [host]:[port] on an open stream to the proxy, without authentication. */
    private fun socks5(s: Socket, host: String, port: Int): Boolean {
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        out.write(byteArrayOf(5, 1, 0)); out.flush()
        if (inp.read() != 5 || inp.read() != 0) return false
        val name = host.toByteArray(Charsets.US_ASCII)
        out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte()))
        out.flush()
        if (inp.read() != 5) return false
        val rep = inp.read()
        inp.read()
        val skip = when (inp.read()) { 1 -> 4; 4 -> 16; 3 -> inp.read(); else -> return false }
        repeat(skip + 2) { if (inp.read() < 0) return false }
        return rep == 0
    }

    /** A SOCKS4a CONNECT to [host]:[port] (the proxy resolves the name): true on its 0x5A reply. */
    private fun socks4a(s: Socket, host: String, port: Int): Boolean {
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        out.write(byteArrayOf(4, 1, ((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte(), 0, 0, 0, 1, 0) +
            host.toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
        out.flush()
        val reply = ByteArray(8)
        var got = 0
        while (got < 8) { val n = inp.read(reply, got, 8 - got); if (n < 0) return false; got += n }
        return reply[1].toInt() and 0xFF == 0x5A
    }

    /**
     * One proxy, measured the way it will be used: `ok` only when Cloudflare's trace came back
     * through it AND names [want]. An HTTP proxy is asked what a browser asks it -- CONNECT :443
     * (most refuse CONNECT :80: 83 of 155 German ones on 2026-10-13) -- then the country from a plain
     * proxied GET. [rttOnly]: the CONNECT time alone, for a proxy the live chain already proved.
     */
    fun probe(p: Candidate, via: Int, want: String?, timeoutMs: Int = 9_000, rttOnly: Boolean = false): ProbeResult {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        var sock: Socket? = null
        return try {
            val body: String
            val rtt: Long
            if (p.kind == Kind.HTTP) {
                dial(p.ip, p.port, via, timeoutMs).use { s ->
                    s.getOutputStream().write("CONNECT $TRACE_HOST:443 HTTP/1.1\r\nHost: $TRACE_HOST:443\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    s.getOutputStream().flush()
                    val code = Regex("""^HTTP/1\.[01] (\d{3})""").find(readHead(s.getInputStream()))?.groupValues?.get(1)
                    if (code != "200") return ProbeResult(false, ms(), error = "CONNECT ${code ?: "بی‌پاسخ"}")
                }
                rtt = ms()
                if (rttOnly) return ProbeResult(true, rtt)
                sock = dial(p.ip, p.port, via, timeoutMs)
                sock.getOutputStream().write("GET http://$TRACE_HOST/cdn-cgi/trace HTTP/1.1\r\nHost: $TRACE_HOST\r\nUser-Agent: MLMVPN\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                sock.getOutputStream().flush()
                body = readAll(sock.getInputStream())
            } else {
                sock = dial(p.ip, p.port, via, timeoutMs)
                val opened = if (p.kind == Kind.SOCKS4) socks4a(sock, TRACE_HOST, 80) else socks5(sock, TRACE_HOST, 80)
                if (!opened) return ProbeResult(false, ms(), error = "SOCKS بسته")
                if (rttOnly) return ProbeResult(true, ms())
                sock.getOutputStream().write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $TRACE_HOST\r\nUser-Agent: MLMVPN\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                sock.getOutputStream().flush()
                body = readAll(sock.getInputStream())
                rtt = ms()
            }
            val loc = field(body, "loc")?.uppercase(Locale.US).orEmpty()
            val ip = field(body, "ip")
            when {
                !body.startsWith("HTTP/1.") || !body.substringBefore('\n').contains(" 200") || loc.isEmpty() -> ProbeResult(false, rtt, error = "پاسخ trace نیامد")
                want != null && loc != want -> ProbeResult(false, rtt, loc, ip, "خروجی $loc است، نه $want")
                else -> ProbeResult(true, rtt, loc, ip)
            }
        } catch (e: Exception) {
            ProbeResult(false, ms(), error = (e.message ?: e.javaClass.simpleName).take(80))
        } finally {
            runCatching { sock?.close() }
        }
    }

    /** Cloudflare's trace over HTTPS through a local SOCKS port: where that port's traffic really leaves. */
    data class Trace(val ok: Boolean, val loc: String? = null, val ip: String? = null, val colo: String? = null, val warp: String? = null, val rttMs: Long = 0)

    fun httpsTrace(socksPort: Int, timeoutMs: Long = 12_000): Trace {
        val t0 = System.nanoTime()
        return runCatching {
            OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs + 3_000, TimeUnit.MILLISECONDS)
                .build()
                .newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").header("User-Agent", "MLMVPN").build())
                .execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    val loc = field(body, "loc")?.uppercase(Locale.US)
                    if (!r.isSuccessful || loc.isNullOrBlank()) Trace(false, rttMs = (System.nanoTime() - t0) / 1_000_000)
                    else Trace(true, loc, field(body, "ip"), field(body, "colo"), field(body, "warp"), (System.nanoTime() - t0) / 1_000_000)
                }
        }.getOrElse { Trace(false, rttMs = (System.nanoTime() - t0) / 1_000_000) }
    }

    // ── measuring a country ────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    private val PROBES = Dispatchers.IO.limitedParallelism(96)

    data class ScanResult(val country: String, val tested: Int, val listed: Int, val ok: Int)

    /**
     * Measures [country]'s proxies through [via] -- at most [limit], [parallel] at a time, stopping
     * once [enough] carried traffic out of that country -- and keeps the best [POOL_KEEP], fastest
     * first. A look that finds nothing does not erase what an earlier one measured: free proxies
     * flap, and an empty pool is a country the user can no longer pick.
     */
    suspend fun scan(
        ctx: Context, country: String, via: Int, limit: Int = 300, parallel: Int = 32, enough: Int = POOL_KEEP,
        onProgress: (done: Int, total: Int, ok: Int) -> Unit = { _, _, _ -> }, isCancelled: () -> Boolean = { false },
    ): ScanResult = withContext(Dispatchers.IO) {
        val cc = country.uppercase(Locale.US)
        val list = ordered(fetchList(ctx, cc)).take(limit)
        val results = ConcurrentLinkedQueue<Pair<Candidate, ProbeResult>>()
        val next = AtomicInteger()
        val done = AtomicInteger()
        val ok = AtomicInteger()
        coroutineScope {
            repeat(minOf(parallel, list.size)) {
                launch(PROBES) {
                    while (ok.get() < enough && !isCancelled()) {
                        val i = next.getAndIncrement()
                        if (i >= list.size) break
                        val r = probe(list[i], via, cc)
                        results += list[i] to r
                        if (r.ok) ok.incrementAndGet()
                        onProgress(done.incrementAndGet(), list.size, ok.get())
                    }
                }
            }
        }
        val now = System.currentTimeMillis()
        val good = results.filter { it.second.ok }.sortedBy { it.second.rttMs ?: Long.MAX_VALUE }.take(POOL_KEEP)
            .map { (c, r) -> PoolEntry(c.ip, c.port, c.kind, c.city, r.loc ?: cc, r.rttMs, now) }
        // Measured, through the same path, leaving from somewhere else: a real exit for THAT country.
        val elsewhere = results.filter { (_, r) -> !r.ok && r.loc != null && Regex("^[A-Z]{2}$").matches(r.loc) && r.loc != cc && r.loc != "IR" && r.loc != "T1" }
        update(ctx) { s ->
            val pools = s.pools.toMutableMap()
            val pings = s.pings.toMutableMap()
            val keep = good.ifEmpty { s.poolOf(cc) }
            pools[cc] = keep
            for ((c, r) in elsewhere) {
                val to = r.loc!!
                val toList = pools[to].orEmpty()
                if (toList.any { it.ip == c.ip && it.port == c.port } || toList.size >= POOL_KEEP * 2) continue
                val moved = (toList + PoolEntry(c.ip, c.port, c.kind, c.city, to, r.rttMs, now, movedFrom = cc)).sortedBy { it.rttMs ?: Long.MAX_VALUE }
                pools[to] = moved
                val pg = pings[to]
                pings[to] = Ping(true, moved.first().rttMs, moved.size, pg?.tested ?: 0, pg?.listed ?: 0, pg?.at ?: now, pg?.viaWarp ?: (via > 0), pg?.confirmedAt ?: 0)
            }
            pings[cc] = Ping(keep.isNotEmpty(), keep.firstOrNull()?.rttMs, keep.size, done.get(), list.size, now, via > 0, pings[cc]?.confirmedAt ?: 0)
            // A proxy that answered THIS measurement is alive, whatever an earlier connect concluded.
            val dead = s.dead - good.map { it.key }.toSet()
            s.copy(pools = pools, pings = pings, dead = dead,
                country = s.country.ifBlank { cc }, scannedAt = if (s.country.isBlank() || s.country == cc) now else s.scannedAt)
        }
        ScanResult(cc, done.get(), list.size, good.size)
    }

    /**
     * «سنجش همهٔ کشورها»: each country a SHORT look -- its first candidates, stopping at the first two
     * that carry -- enough to rank countries fastest to slowest and to name the dead ones. A country
     * picked later gets its full scan only if this one found too few.
     */
    suspend fun pingAll(
        ctx: Context, via: Int, countries: List<String> = countryList(ctx), perCountry: Int = 80, parallelCountries: Int = 4,
        onCountry: (country: String, started: Boolean, done: Int, total: Int) -> Unit = { _, _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) = coroutineScope {
        val next = AtomicInteger()
        val done = AtomicInteger()
        repeat(minOf(parallelCountries, countries.size)) {
            launch(Dispatchers.IO) {
                while (!isCancelled()) {
                    val i = next.getAndIncrement()
                    if (i >= countries.size) break
                    val cc = countries[i]
                    onCountry(cc, true, done.get(), countries.size)
                    runCatching { scan(ctx, cc, via, limit = perCountry, parallel = 16, enough = 2, isCancelled = isCancelled) }
                    onCountry(cc, false, done.incrementAndGet(), countries.size)
                }
            }
        }
    }

    // ── the choice ─────────────────────────────────────────────────────────────────────────────

    /**
     * Turns the free-proxy exit on or off (for [country] when given). On needs the privacy warning
     * accepted and, unless [allowEmpty], a measured pool. On turns the NAT64 exit off: one thing
     * decides the country at a time.
     */
    fun setEnabled(ctx: Context, on: Boolean, ack: Boolean = false, country: String? = null, allowEmpty: Boolean = false): State {
        val s0 = load(ctx)
        val cc = country?.uppercase(Locale.US)
        if (cc != null && !Regex("^[A-Z]{2}$").matches(cc)) throw ExitError("COUNTRY", "country")
        val s = (if (cc != null) s0.copy(country = cc) else s0).let { it.copy(ack = it.ack || ack) }
        if (on && !s.ack) throw ExitError("ACK", "ack")
        // The warning, once accepted, is remembered even when the country still has to be measured:
        // otherwise it came back the moment the measurement turned the exit on (2026-10-17).
        if (ack && !s0.ack) save(ctx, s0.copy(ack = true))
        if (on && !allowEmpty && s.poolOf().isEmpty()) throw ExitError("EMPTY", "empty")
        val saved = save(ctx, s.copy(enabled = on))
        if (on) Nat64Exits.setSelection(ctx, Nat64Exits.DIRECT)
        return saved
    }

    fun disable(ctx: Context) {
        if (load(ctx).enabled) update(ctx) { it.copy(enabled = false) }
    }

    fun isOn(ctx: Context): Boolean = load(ctx).let { it.enabled && it.country.isNotBlank() }

    /** The proxy the next connect uses: the best of the country's pool not found dead lately. */
    fun chosen(ctx: Context, s: State = load(ctx), now: Long = System.currentTimeMillis()): PoolEntry? {
        if (!s.enabled) return null
        return s.poolOf().firstOrNull { (s.dead[it.key] ?: 0L) <= now - DEAD_MS }
    }

    /** A real connection came out where this proxy's country says: ✓, with the live connection's number. */
    fun confirm(ctx: Context, p: PoolEntry, loc: String, rttMs: Long? = null) {
        update(ctx) { s ->
            val list = s.pools[loc] ?: return@update s
            val now = System.currentTimeMillis()
            val updated = list.map { if (it.ip == p.ip && it.port == p.port) it.copy(confirmedAt = now, rttMs = rttMs ?: it.rttMs) else it }
            val ping = s.pings[loc]?.let { it.copy(confirmedAt = now, rttMs = updated.mapNotNull { e -> e.rttMs }.minOrNull() ?: it.rttMs) }
            s.copy(pools = s.pools + (loc to updated), pings = if (ping != null) s.pings + (loc to ping) else s.pings)
        }
    }

    /** Filed under [from], really leaving from [to]: moved there (Iran is no exit anyone picks: dropped). */
    fun reclassify(ctx: Context, p: PoolEntry, from: String, to: String) {
        update(ctx) { s ->
            val fromList = s.pools[from].orEmpty().filterNot { it.ip == p.ip && it.port == p.port }
            var pools = s.pools + (from to fromList)
            var pings = s.pings
            if (Regex("^[A-Z]{2}$").matches(to) && to != "IR" && to != "T1") {
                val toList = pools[to].orEmpty()
                if (toList.none { it.ip == p.ip && it.port == p.port }) {
                    val moved = toList + p.copy(loc = to, movedFrom = from, at = System.currentTimeMillis())
                    pools = pools + (to to moved)
                    val pg = pings[to]
                    pings = pings + (to to Ping(true, moved.mapNotNull { it.rttMs }.minOrNull(), moved.size, pg?.tested ?: 0, pg?.listed ?: 0, System.currentTimeMillis(), pg?.viaWarp ?: true))
                }
            }
            pings[from]?.let { pings = pings + (from to it.copy(found = fromList.size, ok = fromList.isNotEmpty())) }
            s.copy(pools = pools, pings = pings)
        }
    }

    fun reportDead(ctx: Context, p: PoolEntry) {
        update(ctx) { it.copy(dead = it.dead + (p.key to System.currentTimeMillis())) }
    }

    /** Clears the measurements (the page's «پاک کردن نتیجه‌ها»); the choice and the warning stay. */
    fun forgetMeasurements(ctx: Context) {
        update(ctx) { it.copy(pools = emptyMap(), pings = emptyMap(), dead = emptyMap(), enabled = false) }
    }

    /** The hop SocksFront takes for [p]. */
    fun hopOf(p: PoolEntry) = com.mlmvpn.core.tunnel.SocksFront.ExitHop(
        p.ip, p.port,
        when (p.kind) {
            Kind.SOCKS5 -> com.mlmvpn.core.tunnel.SocksFront.HOP_SOCKS5
            Kind.SOCKS4 -> com.mlmvpn.core.tunnel.SocksFront.HOP_SOCKS4
            Kind.HTTP -> com.mlmvpn.core.tunnel.SocksFront.HOP_HTTP
        },
    )
}
