package com.mlmvpn.core.tunnel

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mlmvpn.core.warp.Ipv4Exits
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * «پروکسی سرور» of «سایفون»: Psiphon's own upstream proxy, with a ready list and real pings. The
 * desktop's psiphon-upstream.js, ported.
 *
 * What it is (psiphon-tunnel-core config.go + psiphon/upstreamproxy): the official apps' «Proxy
 * settings» (host, port, optional username/password; HTTP or SOCKS5) fill `UpstreamProxyURL` -- an
 * upstream proxy for ALL of Psiphon's outbound connections. So the proxy is BEFORE Psiphon:
 * you → proxy → Psiphon server → internet. It does not choose the exit country (the Psiphon server
 * does; measured on the desktop, an SG proxy came out in the US, a TR one in PL). It is a way IN when
 * Psiphon's own ways are blocked or slow on a line.
 *
 * Of the free proxies that relay HTTPS from a line, about half also carry a Psiphon tunnel (desktop,
 * 2026-10-14). So a ping is shown ONLY after the real test ([PsiphonProbes.upstream]); the quick test
 * -- one HTTPS request through the proxy, straight from this line -- only decides which candidates
 * are worth it. The list is the IPv4 exit's ([Ipv4Exits.fetchList]: the shipped one plus the live
 * ones), grouped by where the proxy is.
 */
object PsiphonUpstream {

    private const val TAG = "PsiphonUpstream"
    private const val FILE = "psiphon/upstream.json"
    private const val KEEP = 6
    private const val TRACE_HOST = "www.cloudflare.com"

    /** The real test's verdict, or a real connection's: a Psiphon tunnel through it, and a request across it. */
    data class Real(
        val ok: Boolean,
        val ms: Long? = null,
        val pingMs: Long? = null,
        val egress: String? = null,
        val at: Long = 0L,
        val error: String = "",
        /** The custom proxy's quick test, kept to tell «does not answer» from «answers, Psiphon will not». */
        val quickOk: Boolean? = null,
    )

    /** The user's own proxy, the official app's form. `type`: "http" or "socks5". */
    data class Custom(
        val type: String,
        val host: String,
        val port: Int,
        val auth: Boolean,
        val user: String,
        val pass: String,
        val real: Real? = null,
    )

    /** One proxy of a country, measured. `kind`: "http" or "socks5". */
    data class PoolEntry(val ip: String, val port: Int, val kind: String, val quickMs: Long?, val real: Real?) {
        val key: String get() = "$ip:$port"
    }

    /** How a country measured: tried, answered the quick test, carried Psiphon; the best ping. */
    data class Ping(val tested: Int, val quickOk: Int, val realOk: Int, val bestPing: Long?, val at: Long)

    /** `mode`: "custom" (the user's proxy) or "country" (the ready list's [country]). */
    data class Choice(val mode: String, val country: String? = null)

    /** Where one country's measurement is: the quick test from this line, then the real tests. */
    data class Step(val stage: String = "quick", val quickDone: Int = 0, val quickTotal: Int = 0, val quickOk: Int = 0)

    /** A proxy under a real test right now: its country, since when (wall clock), and whether its tunnel is up. */
    data class Testing(val country: String, val since: Long, val connected: Boolean = false)

    /** One finished real test, for the progress card. */
    data class Recent(val key: String, val country: String, val ok: Boolean, val pingMs: Long?)

    data class Job(
        val running: Boolean = false,
        /** One country, or "all". */
        val scope: String = "",
        val countries: Int = 0,
        val done: Int = 0,
        /** The countries being measured now. */
        val current: List<String> = emptyList(),
        val startedAt: Long = 0L,
        val steps: Map<String, Step> = emptyMap(),
        /** Proxies under a real test now, by "ip:port". */
        val testing: Map<String, Testing> = emptyMap(),
        val quickTested: Int = 0,
        val quickOk: Int = 0,
        val realDone: Int = 0,
        val realOk: Int = 0,
        /** Countries with at least one proxy that carried Psiphon in this run. */
        val withProxy: Int = 0,
        val recent: List<Recent> = emptyList(),
        val slots: Int = 0,
        /** The countries finished, their time added up, for the estimate. */
        val countryMs: Long = 0L,
    )

    /** «ذخیره و سنجش» of the user's own proxy while it runs: "quick" (from this line) or "real" (a throwaway Psiphon through it). */
    data class CustomTest(val stage: String, val since: Long, val connected: Boolean = false)

    data class State(
        val custom: Custom? = null,
        val choice: Choice? = null,
        val pools: Map<String, List<PoolEntry>> = emptyMap(),
        val pings: Map<String, Ping> = emptyMap(),
        val job: Job = Job(),
        val customTest: CustomTest? = null,
    )

    /** A proxy a connect may go through: [url] is what Psiphon is given, [label] what the user is shown. */
    data class Candidate(val url: String, val label: String, val country: String?, val key: String)

    /** A form or a choice the user must fix; the message is a sentence for them. */
    class UpstreamError(message: String) : Exception(message)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state
    private val lock = Any()
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val s = runCatching { parse(JSONObject(file(ctx).readText())) }.getOrDefault(State())
            _state.value = s.copy(job = _state.value.job)
            loaded = true
        }
    }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    private fun edit(ctx: Context, change: (State) -> State): State {
        init(ctx)
        synchronized(lock) {
            // Through update(): a progress change made meanwhile on another thread is kept, not overwritten.
            var made: State? = null
            _state.update { s -> change(s).also { made = it } }
            val next = made ?: _state.value
            runCatching {
                val f = file(ctx)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(toJson(next).toString())
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }.onFailure { Log.w(TAG, "save failed: ${it.message}") }
            return next
        }
    }

    private fun job(change: (Job) -> Job) = _state.update { it.copy(job = change(it.job)) }

    // ── stored form ─────────────────────────────────────────────────────────────────────────────

    private fun realJson(r: Real) = JSONObject().put("ok", r.ok).put("at", r.at).put("error", r.error).apply {
        r.ms?.let { put("ms", it) }; r.pingMs?.let { put("pingMs", it) }; r.egress?.let { put("egress", it) }
        r.quickOk?.let { put("quickOk", it) }
    }

    private fun realOf(o: JSONObject?): Real? = o?.let {
        Real(it.optBoolean("ok"), it.optLong("ms", -1).takeIf { v -> v >= 0 }, it.optLong("pingMs", -1).takeIf { v -> v >= 0 },
            it.optString("egress").ifBlank { null }, it.optLong("at"), it.optString("error"),
            if (it.has("quickOk")) it.optBoolean("quickOk") else null)
    }

    private fun toJson(s: State): JSONObject = JSONObject().apply {
        s.custom?.let { c ->
            put("custom", JSONObject().put("type", c.type).put("host", c.host).put("port", c.port).put("auth", c.auth)
                .put("user", c.user).put("pass", c.pass).apply { c.real?.let { put("real", realJson(it)) } })
        }
        s.choice?.let { c -> put("choice", JSONObject().put("mode", c.mode).apply { c.country?.let { put("country", it) } }) }
        put("pools", JSONObject().apply {
            s.pools.forEach { (cc, l) ->
                put(cc, JSONArray().apply {
                    l.forEach { p ->
                        put(JSONObject().put("ip", p.ip).put("port", p.port).put("kind", p.kind).apply {
                            p.quickMs?.let { put("quickMs", it) }; p.real?.let { put("real", realJson(it)) }
                        })
                    }
                })
            }
        })
        put("pings", JSONObject().apply {
            s.pings.forEach { (cc, p) ->
                put(cc, JSONObject().put("tested", p.tested).put("quickOk", p.quickOk).put("realOk", p.realOk).put("at", p.at)
                    .apply { p.bestPing?.let { put("bestPing", it) } })
            }
        })
    }

    private fun parse(o: JSONObject): State {
        val custom = o.optJSONObject("custom")?.let { c ->
            Custom(c.optString("type", "http"), c.optString("host"), c.optInt("port"), c.optBoolean("auth"),
                c.optString("user"), c.optString("pass"), realOf(c.optJSONObject("real")))
        }?.takeIf { it.host.isNotBlank() && it.port in 1..65535 }
        val choice = o.optJSONObject("choice")?.let { c ->
            when (c.optString("mode")) {
                "custom" -> Choice("custom")
                "country" -> c.optString("country").uppercase(Locale.US).takeIf { Regex("^[A-Z]{2}$").matches(it) }?.let { Choice("country", it) }
                else -> null
            }
        }
        val pools = HashMap<String, List<PoolEntry>>()
        o.optJSONObject("pools")?.let { p ->
            p.keys().forEach { cc ->
                val a = p.optJSONArray(cc) ?: return@forEach
                pools[cc] = (0 until a.length()).mapNotNull { i ->
                    val x = a.optJSONObject(i) ?: return@mapNotNull null
                    PoolEntry(x.optString("ip"), x.optInt("port"), x.optString("kind", "http"),
                        x.optLong("quickMs", -1).takeIf { it >= 0 }, realOf(x.optJSONObject("real")))
                }
            }
        }
        val pings = HashMap<String, Ping>()
        o.optJSONObject("pings")?.let { p ->
            p.keys().forEach { cc ->
                val x = p.optJSONObject(cc) ?: return@forEach
                pings[cc] = Ping(x.optInt("tested"), x.optInt("quickOk"), x.optInt("realOk"),
                    x.optLong("bestPing", -1).takeIf { it >= 0 }, x.optLong("at"))
            }
        }
        return State(custom, choice?.takeIf { it.mode != "custom" || custom != null }, pools, pings)
    }

    // ── what Psiphon is given ───────────────────────────────────────────────────────────────────

    /** `http://user:pass@host:port` or `socks5://…`, the userinfo percent-encoded, IPv6 in brackets. */
    fun urlOf(type: String, host: String, port: Int, user: String? = null, pass: String? = null): String {
        val scheme = if (type == "socks5") "socks5" else "http"
        val h = if (host.contains(':')) "[$host]" else host
        val auth = if (!user.isNullOrEmpty()) "${enc(user)}:${enc(pass.orEmpty())}@" else ""
        return "$scheme://$auth$h:$port"
    }

    fun urlOf(c: Custom): String = urlOf(c.type, c.host, c.port, c.user.takeIf { c.auth }, c.pass.takeIf { c.auth })
    fun urlOf(p: PoolEntry): String = urlOf(p.kind, p.ip, p.port)

    /** The URL with its password hidden, for a log line. */
    fun masked(url: String): String = url.replace(Regex("//([^:@/]+):[^@/]*@"), "//$1:•••@")

    private fun enc(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if ((c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) append(c.toChar())
            else append('%').append(String.format(Locale.US, "%02X", c))
        }
    }

    // ── the quick test ──────────────────────────────────────────────────────────────────────────

    data class Quick(val ok: Boolean, val ms: Long)

    /**
     * One HTTPS request THROUGH the proxy, straight from this line (no tunnel): CONNECT for an HTTP
     * proxy, SOCKS5 (with RFC 1929 credentials when given) for the other, then TLS to Cloudflare and
     * its trace. `ok` on a 200 -- the proxy relays HTTPS from here. [track]: where the socket is kept
     * while it is open, for a «توقف» to close it (a socket blocked in a connect or a read does not
     * notice its coroutine being cancelled).
     */
    fun quick(
        type: String, host: String, port: Int, user: String? = null, pass: String? = null, timeoutMs: Int = 8_000,
        track: MutableSet<Socket>? = null,
    ): Quick {
        val t0 = SystemClock.elapsedRealtime()
        fun done(ok: Boolean) = Quick(ok, SystemClock.elapsedRealtime() - t0)
        val sock = Socket(Proxy.NO_PROXY)
        track?.add(sock)
        return try {
            sock.use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                if (type == "socks5") {
                    val auth = !user.isNullOrEmpty()
                    out.write(if (auth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)); out.flush()
                    if (inp.read() != 5) return done(false)
                    when (inp.read()) {
                        0 -> Unit
                        2 -> {
                            if (!auth) return done(false)
                            val u = user!!.toByteArray(Charsets.UTF_8).take(255).toByteArray()
                            val p = pass.orEmpty().toByteArray(Charsets.UTF_8).take(255).toByteArray()
                            out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); out.flush()
                            if (inp.read() != 1 || inp.read() != 0) return done(false)
                        }
                        else -> return done(false)
                    }
                    val name = TRACE_HOST.toByteArray(Charsets.US_ASCII)
                    out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf(1, 0xBB.toByte())); out.flush()
                    if (inp.read() != 5 || inp.read() != 0) return done(false)
                    inp.read()
                    val skip = when (inp.read()) { 1 -> 4; 4 -> 16; 3 -> inp.read(); else -> return done(false) }
                    repeat(skip + 2) { if (inp.read() < 0) return done(false) }
                } else {
                    val authHdr = if (!user.isNullOrEmpty()) {
                        "Proxy-Authorization: Basic " + android.util.Base64.encodeToString("$user:${pass.orEmpty()}".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP) + "\r\n"
                    } else ""
                    out.write("CONNECT $TRACE_HOST:443 HTTP/1.1\r\nHost: $TRACE_HOST:443\r\n$authHdr\r\n".toByteArray(Charsets.US_ASCII)); out.flush()
                    if (statusOf(head(inp)) != 200) return done(false)
                }
                (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, TRACE_HOST, 443, true).use { t ->
                    t as SSLSocket
                    t.startHandshake()
                    t.outputStream.write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $TRACE_HOST\r\nUser-Agent: MLMVPN\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    t.outputStream.flush()
                    done(statusOf(head(t.inputStream)) == 200)
                }
            }
        } catch (e: Exception) {
            done(false)
        } finally {
            track?.remove(sock)
        }
    }

    private fun head(inp: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = inp.read()
            if (b < 0) break
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }

    private fun statusOf(head: String): Int? = Regex("""^HTTP/1\.[01] (\d{3})""").find(head)?.groupValues?.get(1)?.toIntOrNull()

    // ── jobs ────────────────────────────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val QUICK = Dispatchers.IO.limitedParallelism(90)

    /**
     * The «سنجش همه» run that owns the card: every write of a run checks it is still the current one.
     * «توقف» moves it on, so the page is free at once and a quick test still timing out, or a core
     * still being stopped, can change nothing.
     */
    @Volatile private var gen = 0

    /** A call outside any run ([my] null: one country before a connect) is never stopped by «توقف». */
    private fun mine(my: Int?) = my == null || my == gen

    private fun job(my: Int, change: (Job) -> Job) {
        if (my != gen) return
        _state.update { if (my == gen) it.copy(job = change(it.job)) else it }
    }

    /** One country's quick tests, counted from many threads; [scanMany]'s ticker shows them. */
    private class QuickCount {
        val done = AtomicInteger()
        val ok = AtomicInteger()
    }

    private val quickCounts = ConcurrentHashMap<String, QuickCount>()

    /** The run's quick-test sockets, closed by «توقف»; a connect's own re-test and the custom test keep theirs. */
    private val runSockets: MutableSet<Socket> = java.util.Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * One country: quick-test candidates until [quickWant] relay HTTPS, then the real Psiphon test on
     * those (and on the ones that carried before) until [realWant] connect. Keeps the best [KEEP].
     * [my]: the «سنجش همه» run whose card it reports to; a stopped run writes nothing for the country.
     */
    suspend fun scanCountry(ctx: Context, country: String, quickLimit: Int = 150, quickWant: Int = 4, realWant: Int = 2, my: Int? = null): Ping {
        val app = ctx.applicationContext
        init(app)
        val cc = country.uppercase(Locale.US)
        val list = withContext(Dispatchers.IO) { Ipv4Exits.fetchList(app, cc) }
            .filter { it.kind == Ipv4Exits.Kind.SOCKS5 || it.kind == Ipv4Exits.Kind.HTTP }
            .take(quickLimit)
        val next = AtomicInteger()
        val tested = AtomicInteger()
        val quickOk = ConcurrentLinkedQueue<PoolEntry>()
        val count = QuickCount()
        if (my != null) {
            quickCounts[cc] = count
            job(my) { it.copy(steps = it.steps + (cc to Step("quick", 0, list.size, 0))) }
        }
        coroutineScope {
            repeat(minOf(30, list.size)) {
                launch(QUICK) {
                    while (isActive && mine(my) && quickOk.size < quickWant) {
                        val i = next.getAndIncrement()
                        if (i >= list.size) break
                        val p = list[i]
                        val kind = if (p.kind == Ipv4Exits.Kind.SOCKS5) "socks5" else "http"
                        val q = quick(kind, p.ip, p.port, track = if (my != null) runSockets else null)
                        if (!mine(my)) break
                        tested.incrementAndGet()
                        count.done.incrementAndGet()
                        if (q.ok) { quickOk += PoolEntry(p.ip, p.port, kind, q.ms, null); count.ok.incrementAndGet() }
                    }
                }
            }
        }
        if (!mine(my)) throw CancellationException("stopped")
        if (my != null) {
            quickCounts.remove(cc)
            job(my) {
                it.copy(steps = it.steps + (cc to Step("real", count.done.get(), list.size, count.ok.get())),
                    quickTested = it.quickTested + count.done.get(), quickOk = it.quickOk + count.ok.get())
            }
        }
        // Earlier real successes too: a proxy that carried a tunnel an hour ago is worth re-testing first.
        val fresh = quickOk.toList()
        val old = _state.value.pools[cc].orEmpty().filter { o -> o.real?.ok == true && fresh.none { it.key == o.key } }
        val cands = (old + fresh)
        val results = ConcurrentHashMap<String, Real>()
        val realOk = AtomicInteger()
        val k = AtomicInteger()
        coroutineScope {
            repeat(minOf(PsiphonProbes.width(app), cands.size)) {
                launch {
                    while (isActive && mine(my) && realOk.get() < realWant) {
                        val i = k.getAndIncrement()
                        if (i >= cands.size) break
                        val p = cands[i]
                        val since = System.currentTimeMillis()
                        if (my != null) job(my) { it.copy(testing = it.testing + (p.key to Testing(cc, since))) }
                        val r = try {
                            PsiphonProbes.upstream(app, urlOf(p), onConnected = {
                                if (my != null) job(my) { j -> j.copy(testing = j.testing + (p.key to Testing(cc, since, true))) }
                            })
                        } finally {
                            if (my != null) job(my) { it.copy(testing = it.testing - p.key) }
                        }
                        if (!mine(my)) break
                        // A test that could not run (its process failed twice) says nothing about the proxy.
                        if (r.harness) continue
                        results[p.key] = Real(r.ok, r.ms, r.pingMs, r.egress, System.currentTimeMillis(),
                            if (r.ok) "" else r.error ?: tr("وصل نشد", "did not connect"))
                        if (r.ok) realOk.incrementAndGet()
                        if (my != null) job(my) {
                            it.copy(realDone = it.realDone + 1, realOk = it.realOk + if (r.ok) 1 else 0,
                                recent = (listOf(Recent(p.key, cc, r.ok, r.pingMs)) + it.recent).take(3))
                        }
                    }
                }
            }
        }
        if (!mine(my)) throw CancellationException("stopped")
        val pool = cands.mapNotNull { p -> results[p.key]?.let { p.copy(real = it) } }
            .sortedWith(compareBy<PoolEntry> { if (it.real?.ok == true) 0 else 1 }.thenBy { it.real?.pingMs ?: Long.MAX_VALUE })
            .take(KEEP)
        val ok = pool.filter { it.real?.ok == true }
        val ping = Ping(tested.get(), fresh.size, ok.size, ok.firstOrNull()?.real?.pingMs, System.currentTimeMillis())
        edit(app) { s -> s.copy(pools = s.pools + (cc to pool), pings = s.pings + (cc to ping)) }
        if (my != null && ok.isNotEmpty()) job(my) { it.copy(withProxy = it.withProxy + 1) }
        Log.i(TAG, "scan $cc: ${tested.get()} quick-tested, ${fresh.size} relay HTTPS, ${ok.size} carried Psiphon")
        return ping
    }

    private var running: kotlinx.coroutines.Job? = null

    /** «سنجش همه» (the first 25 countries of the list) or one country. */
    fun scanMany(ctx: Context, countries: List<String>, parallel: Int = 3, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        if (_state.value.job.running || countries.isEmpty()) return
        val my = ++gen
        quickCounts.clear()
        _state.update {
            it.copy(job = Job(running = true, scope = if (countries.size == 1) countries[0] else "all", countries = countries.size,
                startedAt = System.currentTimeMillis(), slots = PsiphonProbes.width(app)))
        }
        running = scope.launch {
            val next = AtomicInteger()
            // The quick tests' counters to the card, four times a second -- not one update per proxy.
            val ticker = launch {
                while (my == gen) {
                    delay(250)
                    job(my) { j ->
                        j.copy(steps = j.steps.mapValues { (cc, s) ->
                            quickCounts[cc]?.takeIf { s.stage == "quick" }?.let { q -> s.copy(quickDone = q.done.get(), quickOk = q.ok.get()) } ?: s
                        })
                    }
                }
            }
            try {
                coroutineScope {
                    repeat(minOf(parallel, countries.size)) {
                        launch {
                            while (isActive && my == gen) {
                                val i = next.getAndIncrement()
                                if (i >= countries.size) break
                                val cc = countries[i]
                                val t0 = System.currentTimeMillis()
                                job(my) { it.copy(current = it.current + cc) }
                                try {
                                    scanCountry(app, cc,
                                        realWant = if (countries.size == 1) 3 else 1,
                                        quickWant = if (countries.size == 1) 8 else 4,
                                        my = my)
                                    job(my) { it.copy(done = it.done + 1, countryMs = it.countryMs + (System.currentTimeMillis() - t0)) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(TAG, "scan $cc: ${e.message}")
                                    job(my) { it.copy(done = it.done + 1) }
                                } finally {
                                    quickCounts.remove(cc)
                                    job(my) { it.copy(current = it.current - cc, steps = it.steps - cc) }
                                }
                            }
                        }
                    }
                }
            } finally {
                ticker.cancel()
                if (my == gen) {
                    job(my) { it.copy(running = false, current = emptyList(), testing = emptyMap(), steps = emptyMap()) }
                    onDone?.invoke()
                }
            }
        }
    }

    /**
     * «توقف»: the page is free at once. The run is cancelled and its quick tests' sockets closed (a
     * blocked connect or read does not see a cancellation); the cores in flight are stopped in the
     * background within a second or two, and nothing more is written for the countries in progress.
     */
    fun cancel() {
        if (!_state.value.job.running) return
        gen++
        running?.cancel()
        runSockets.toList().forEach { runCatching { it.close() } }
        quickCounts.clear()
        _state.update { it.copy(job = it.job.copy(running = false, current = emptyList(), testing = emptyMap(), steps = emptyMap())) }
    }

    // ── the custom proxy, like the official app's form ──────────────────────────────────────────

    fun setCustom(ctx: Context, type: String, host: String, port: String, auth: Boolean, user: String, pass: String?): Custom {
        val t = if (type == "socks5") "socks5" else "http"
        val h = host.trim().removePrefix("[").removeSuffix("]")
        val p = port.trim().toIntOrNull()
        if (h.isEmpty() || h.any { it.isWhitespace() || it == '/' || it == '@' }) {
            throw UpstreamError(tr("میزبان (سرور) را درست وارد کنید — آی‌پی یا نام دامنه.", "Enter the host (server) correctly -- an IP or a domain name."))
        }
        if (p == null || p !in 1..65535) throw UpstreamError(tr("پورت باید عددی بین ۱ تا ۶۵۵۳۵ باشد.", "The port must be a number from 1 to 65535."))
        if (auth && user.isBlank()) throw UpstreamError(tr("با احراز هویت روشن، نام کاربری لازم است.", "With authentication on, a username is needed."))
        var out: Custom? = null
        edit(ctx) { s ->
            val prev = s.custom
            val c = Custom(t, h, p, auth,
                if (auth) user.trim() else "",
                if (auth) (pass ?: prev?.pass.orEmpty()) else "",
                prev?.real?.takeIf { prev.host == h && prev.port == p && prev.type == t })
            out = c
            s.copy(custom = c)
        }
        return out!!
    }

    /** The custom test's quick-test socket, closed by its «توقف». */
    private val customSockets: MutableSet<Socket> = java.util.Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * «ذخیره و سنجش»: the quick test from this line, then a real Psiphon through it. While it runs,
     * [State.customTest] says which part and since when, for the page's card. A test that could not
     * run at all is an error for the user, not the proxy's verdict.
     */
    suspend fun testCustom(ctx: Context): Real {
        val app = ctx.applicationContext
        init(app)
        val c = _state.value.custom ?: throw UpstreamError(tr("اول پروکسی را وارد کنید.", "Enter the proxy first."))
        _state.update { it.copy(customTest = CustomTest("quick", System.currentTimeMillis())) }
        try {
            val q = withContext(Dispatchers.IO) {
                quick(c.type, c.host, c.port, c.user.takeIf { c.auth }, c.pass.takeIf { c.auth }, 10_000, track = customSockets)
            }
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(customTest = CustomTest("real", System.currentTimeMillis())) }
            val r = PsiphonProbes.upstream(app, urlOf(c), onConnected = {
                _state.update { s -> s.copy(customTest = s.customTest?.copy(connected = true)) }
            })
            if (r.harness) throw UpstreamError(tr("سنجش انجام نشد: ${r.error.orEmpty()} — دوباره بزنید.", "The test could not run: ${r.error.orEmpty()} -- try again."))
            val error = when {
                r.ok -> ""
                !q.ok -> tr("پروکسی از اینترنت شما جواب نداد", "The proxy did not answer from your internet")
                else -> r.error ?: tr("سایفون از این پروکسی وصل نشد", "Psiphon did not connect through this proxy")
            }
            val real = Real(r.ok, r.ms, r.pingMs, r.egress, System.currentTimeMillis(), error, quickOk = q.ok)
            edit(app) { s -> s.copy(custom = s.custom?.copy(real = real)) }
            return real
        } finally {
            _state.update { it.copy(customTest = null) }
        }
    }

    /** The custom test's «توقف»: its quick test's socket closed; the page cancels the coroutine that runs it. */
    fun stopCustom() {
        customSockets.toList().forEach { runCatching { it.close() } }
        _state.update { it.copy(customTest = null) }
    }

    // ── the choice ──────────────────────────────────────────────────────────────────────────────

    /** null turns «پروکسی سرور» off. */
    fun choose(ctx: Context, choice: Choice?) {
        init(ctx)
        when {
            choice == null -> Unit
            choice.mode == "custom" && _state.value.custom == null -> throw UpstreamError(tr("اول پروکسی را وارد کنید.", "Enter the proxy first."))
            choice.mode == "country" && !Regex("^[A-Z]{2}$").matches(choice.country.orEmpty()) -> throw UpstreamError(tr("کشور نامعتبر", "Invalid country"))
        }
        edit(ctx) { it.copy(choice = choice) }
    }

    /** The proxies a connect tries, best first: the custom one, or the country's real-tested ones. */
    fun candidates(ctx: Context): List<Candidate> {
        init(ctx)
        val s = _state.value
        val ch = s.choice ?: return emptyList()
        if (ch.mode == "custom") return s.custom?.let { listOf(Candidate(urlOf(it), "${it.host}:${it.port}", null, "custom")) }.orEmpty()
        val cc = ch.country ?: return emptyList()
        return s.pools[cc].orEmpty().filter { it.real?.ok == true }.map { Candidate(urlOf(it), it.key, cc, it.key) }
    }

    /**
     * The candidates as they are NOW: free proxies die within minutes (desktop: one that carried a
     * Psiphon tunnel at 02:14 refused every connection at 02:24). Anything measured more than three
     * minutes ago gets the REAL test again -- a proxy that still relays HTTPS may no longer carry
     * Psiphon -- and a country with none left is measured again on the spot, so whatever the connect
     * uses was proven seconds ago.
     */
    suspend fun freshCandidates(ctx: Context, onNote: (String) -> Unit, maxAgeMs: Long = 3 * 60_000L): List<Candidate> {
        val app = ctx.applicationContext
        init(app)
        val s = _state.value
        val isCountry = s.choice?.mode == "country"
        val list = candidates(app).take(4)
        if (list.isEmpty() && !isCountry) return emptyList()
        val now = System.currentTimeMillis()
        val stale = list.filter { c -> (realOf(c)?.at ?: 0L).let { now - it > maxAgeMs } }
        if (stale.isNotEmpty()) {
            onNote(tr("سنجش واقعی دوبارهٔ ${fa(stale.size)} پروکسی پیش از اتصال…", "Re-testing ${stale.size} proxies before connecting…"))
            coroutineScope {
                stale.forEach { c ->
                    launch {
                        val r = PsiphonProbes.upstream(app, c.url)
                        // A test that could not run says nothing about the proxy: its last verdict stands.
                        if (!r.harness) noteResult(app, c.key, r.ok, Real(r.ok, r.ms, r.pingMs, r.egress, System.currentTimeMillis(),
                            if (r.ok) "" else r.error ?: tr("دیگر وصل نمی‌شود", "no longer connects")))
                    }
                }
            }
            if (isCountry) {
                val cc = s.choice?.country.orEmpty()
                edit(app) { st ->
                    val ok = st.pools[cc].orEmpty().filter { it.real?.ok == true }
                    val p = st.pings[cc] ?: return@edit st
                    st.copy(pings = st.pings + (cc to p.copy(realOk = ok.size, bestPing = ok.mapNotNull { it.real?.pingMs }.minOrNull(), at = System.currentTimeMillis())))
                }
            }
        }
        var alive = candidates(app)
        if (alive.isNotEmpty() || !isCountry) return alive
        val cc = s.choice?.country.orEmpty()
        onNote(tr("پراکسی‌های سنجیده‌شدهٔ $cc دیگر با سایفون وصل نمی‌شوند — سنجش دوبارهٔ این کشور همین حالا…",
            "The measured proxies of $cc no longer connect with Psiphon -- measuring the country again now…"))
        scanCountry(app, cc, quickWant = 6, realWant = 1)
        alive = candidates(app)
        return alive
    }

    private fun realOf(c: Candidate): Real? {
        val s = _state.value
        return if (c.key == "custom") s.custom?.real else s.pools[c.country].orEmpty().firstOrNull { it.key == c.key }?.real
    }

    /** A real connection went through this one (or failed): the row is kept honest. */
    fun noteResult(ctx: Context, key: String, ok: Boolean, real: Real? = null, error: String = "") {
        edit(ctx) { s ->
            val now = System.currentTimeMillis()
            fun upd(r: Real?): Real = real ?: (r ?: Real(ok)).copy(ok = ok, at = now, error = if (ok) "" else error.ifBlank { r?.error.orEmpty() })
            if (key == "custom") s.copy(custom = s.custom?.let { it.copy(real = upd(it.real)) })
            else s.copy(pools = s.pools.mapValues { (_, l) -> l.map { if (it.key == key) it.copy(real = upd(it.real)) else it } })
        }
    }

    private fun fa(n: Int): String = n.toString().map { c -> if (c in '0'..'9') '۰' + (c - '0') else c }.joinToString("")

    /** For tests only. */
    internal fun resetForTest() { loaded = false; _state.value = State() }
}
