package com.mlmvpn.scanner.data

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.cfdoctor.CfBogon
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Which Cloudflare edge addresses actually work on THIS network -- one answer for the whole app.
 *
 * What the Doctor reports showed (138 runs from MCI, MTN, Rightel and home Wi-Fi across Iran,
 * 2026-10-01..03, and the same pattern from a PC here):
 *  - Cloudflare is not "blocked" or "open" as a whole. Reachability is per address range, and the
 *    ranges differ: 104.16-19.x, 104.24-25.x, 172.64-65.x and 172.67.x answered in ~95% of runs;
 *    104.20/22/23/26.x, 172.66/68-71.x, 141.101.x, 103.21.x and most of the rest almost never;
 *    188.114.96-99.x, 104.21.x and 104.27.x answered in most places and were dead in others --
 *    which is exactly "it works in one city and not in the next".
 *  - Through an address that answers, everything Cloudflare serves works: the account API,
 *    workers.dev panels, the WARP API, users' Workers. Any edge serves any Cloudflare name; only
 *    the SNI travels to say which.
 *  - DNS is the other half. The phone's resolver hands out an address of its choice for a name (a
 *    dead one about half the time), and on some lines a bogus private one.
 *
 * So nothing that talks to Cloudflare should go where DNS or a stored address happens to point.
 * This keeps, per network, the edges proven to answer (TCP, then TLS with a real Cloudflare name,
 * then any HTTP status line) and hands them out best first. Unknown addresses are checked in
 * parallel in about three seconds, and the result is remembered for half an hour (and the good
 * ones across restarts), so the cost is paid once per network, not per request.
 */
object CfEdge {

    private const val TAG = "CfEdge"
    private const val TTL_MS = 30 * 60_000L
    /** A working edge is trusted this long (and kept across restarts this long); a failure only [TTL_MS]. */
    private const val KEEP_MS = 2 * 3600_000L
    private const val PROBE_MS = 3_000
    const val DEFAULT_HOST = "www.cloudflare.com"

    /** Answered in ~95% of the reports, on every carrier. Sampled most. */
    private val WIDE = listOf("104.16.0.0/14", "104.24.0.0/15", "172.64.0.0/15", "172.67.0.0/16")

    /** Answered in most places and not in others: worth a check, never assumed. */
    private val REGIONAL = listOf("188.114.96.0/22", "104.21.0.0/16", "104.27.0.0/16")

    /** Single addresses that answered in nearly every report: the first candidates on a new network. */
    private val SEEDS = listOf(
        "104.16.113.28", "104.16.179.102", "104.17.120.41", "104.17.190.119",
        "104.18.127.54", "104.18.201.136", "104.19.134.67", "104.19.212.153",
        "104.24.169.132", "104.25.176.145", "172.64.82.98", "172.65.91.105",
        "172.67.109.119", "104.21.234.187", "188.114.97.101", "188.114.98.150",
    )

    private data class Verdict(val ok: Boolean, val ms: Long, val at: Long)

    /** "network|ip" -> verdict. */
    private val verdicts = ConcurrentHashMap<String, Verdict>()
    private val loadedNets = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "cf-edge").apply { isDaemon = true } }
    @Volatile private var app: Context? = null

    /** From Application.onCreate, so callers without a Context (OkHttp's Dns) still know the network. */
    fun init(context: Context) { app = context.applicationContext }

    private fun net(context: Context?): String =
        (context ?: app)?.let { runCatching { CfFamily.networkKey(it) }.getOrNull() } ?: "unknown"

    private fun fresh(v: Verdict?, now: Long) = v != null && now - v.at < (if (v.ok) KEEP_MS else TTL_MS)

    /** The edges proven on this network, best first, without touching the network. */
    fun good(context: Context? = null, max: Int = 4): List<String> {
        val n = net(context)
        load(context, n)
        val now = System.currentTimeMillis()
        return verdicts.entries.asSequence()
            .filter { it.key.startsWith("$n|") && it.value.ok && fresh(it.value, now) }
            .sortedBy { it.value.ms }
            // The network key has a '|' of its own ("wifi|<gateway>"): the address follows the LAST one.
            .map { it.key.substringAfterLast('|') }
            .take(max).toList()
    }

    /** True when [ip] was checked on this network lately and did not answer. */
    fun isBad(context: Context?, ip: String): Boolean {
        val v = verdicts["${net(context)}|$ip"] ?: return false
        return !v.ok && fresh(v, System.currentTimeMillis())
    }

    /** True when [ip] was checked on this network lately and answered. */
    fun isGood(context: Context?, ip: String): Boolean {
        val v = verdicts["${net(context)}|$ip"] ?: return false
        return v.ok && fresh(v, System.currentTimeMillis())
    }

    /**
     * Whether [ip] answers for [host] here: a fresh verdict, or a check now (at most ~3 s).
     * Blocking; call it off the main thread.
     */
    fun check(context: Context?, ip: String, host: String = DEFAULT_HOST): Boolean {
        val n = net(context)
        verdicts["$n|$ip"]?.takeIf { fresh(it, System.currentTimeMillis()) }?.let { return it.ok }
        val v = probe(ip, host)
        verdicts["$n|$ip"] = v
        if (v.ok) save(context, n)
        return v.ok
    }

    /**
     * At least [want] working edges for [host] if there are any to find: the proven ones, else a
     * parallel check of the seeds and a sample of the ranges that usually answer. Blocking.
     */
    fun find(context: Context?, host: String = DEFAULT_HOST, want: Int = 3): List<String> {
        val have = good(context, want)
        if (have.size >= want) return have
        val n = net(context)
        val now = System.currentTimeMillis()
        val candidates = (SEEDS.shuffled().take(10) + sample(WIDE, 8) + sample(REGIONAL, 4))
            .distinct()
            .filter { ip -> !fresh(verdicts["$n|$ip"], now) }
        if (candidates.isNotEmpty()) {
            val jobs = candidates.map { ip -> ip to pool.submit<Verdict> { probe(ip, host) } }
            for ((ip, f) in jobs) {
                verdicts["$n|$ip"] = runCatching { f.get(PROBE_MS * 3L, TimeUnit.MILLISECONDS) }
                    .getOrElse { Verdict(false, PROBE_MS.toLong(), System.currentTimeMillis()) }
            }
            save(context, n)
        }
        return good(context, want.coerceAtLeast(4)).also {
            Log.i(TAG, "on $n: ${it.size} working edges (${it.joinToString()}) of ${candidates.size} checked")
        }
    }

    /** An edge some other part of the app just proved here (the scanner's real test): remembered as working. */
    fun noteGood(context: Context?, ip: String, ms: Long) {
        if (ip.contains(':') || CfBogon.isBogon(ip)) return
        val n = net(context)
        verdicts["$n|$ip"] = Verdict(true, ms.coerceAtLeast(1), System.currentTimeMillis())
        save(context, n)
    }

    /** Forget this network's failures (a network change makes them meaningless); good ones stay as hints. */
    fun forgetFailures() { verdicts.entries.removeIf { !it.value.ok } }

    // ---- the check --------------------------------------------------------------------------------

    /** TCP, then TLS with [host] as SNI, then `HEAD /`: any status line is an answer, silence is not. */
    private fun probe(ip: String, host: String): Verdict {
        val t0 = System.currentTimeMillis()
        fun v(ok: Boolean) = Verdict(ok, System.currentTimeMillis() - t0, System.currentTimeMillis())
        if (CfBogon.isBogon(ip)) return v(false)
        val raw = java.net.Socket()
        try {
            try { raw.connect(InetSocketAddress(ip, 443), PROBE_MS) } catch (_: Exception) { return v(false) }
            raw.soTimeout = PROBE_MS
            val ssl = try {
                val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, null, null) }
                (ctx.socketFactory.createSocket(raw, host, 443, true) as javax.net.ssl.SSLSocket).also { s ->
                    runCatching { s.sslParameters = s.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName(host)) } }
                    s.startHandshake()
                }
            } catch (_: Exception) { return v(false) }
            return try {
                ssl.outputStream.apply { write("HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: mlmvpn\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                v(ssl.inputStream.bufferedReader().readLine().orEmpty().startsWith("HTTP/"))
            } catch (_: Exception) { v(false) } finally { runCatching { ssl.close() } }
        } finally {
            runCatching { raw.close() }
        }
    }

    private fun sample(ranges: List<String>, n: Int): List<String> {
        val rnd = java.security.SecureRandom()
        return List(n) {
            val (base, bits) = ranges[rnd.nextInt(ranges.size)].split('/')
            val b = base.split('.').map { it.toLong() }
            val start = (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
            val size = 1L shl (32 - bits.toInt())
            val ip = start + 1 + Math.floorMod(rnd.nextLong(), size - 2)
            "${(ip shr 24) and 255}.${(ip shr 16) and 255}.${(ip shr 8) and 255}.${ip and 255}"
        }
    }

    // ---- across restarts: the good ones only --------------------------------------------------------

    private fun prefs(context: Context?) = (context ?: app)?.getSharedPreferences("cf_edge", Context.MODE_PRIVATE)

    private fun load(context: Context?, n: String) {
        if (!loadedNets.add(n)) return
        val raw = prefs(context)?.getString("net:$n", null) ?: return
        val now = System.currentTimeMillis()
        raw.split(',').forEach { item ->
            // "ip:ms:at", read from the end so nothing in the address can shift the fields.
            val at = item.substringAfterLast(':').toLongOrNull() ?: return@forEach
            val rest = item.substringBeforeLast(':')
            val ms = rest.substringAfterLast(':').toLongOrNull() ?: 999
            val ip = rest.substringBeforeLast(':')
            if (ip.isBlank() || CfBogon.isBogon(ip)) return@forEach
            if (now - at < KEEP_MS) verdicts.putIfAbsent("$n|$ip", Verdict(true, ms, at))
        }
    }

    private fun save(context: Context?, n: String) {
        val now = System.currentTimeMillis()
        val items = verdicts.entries
            .filter { it.key.startsWith("$n|") && it.value.ok && now - it.value.at < KEEP_MS }
            .sortedBy { it.value.ms }.take(12)
            .joinToString(",") { "${it.key.substringAfterLast('|')}:${it.value.ms}:${it.value.at}" }
        runCatching { prefs(context)?.edit()?.putString("net:$n", items)?.apply() }
    }
}
