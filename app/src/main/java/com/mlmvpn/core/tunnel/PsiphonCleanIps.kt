package com.mlmvpn.core.tunnel

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * «آی‌پی تمیز» of «سایفون»: clean CDN edge addresses for the fronted rung (E), found and proved on
 * the user's own line. The desktop's psiphon-cleanip.js, ported -- the same providers, the same
 * candidates, the same tests, the same choice of what Psiphon is given.
 *
 * What it rests on (measured on the desktop 2026-10-14):
 *  - Psiphon's fronted protocols dial a CDN edge -- Akamai (provider BACEC04E64813F2B) for 10 of the
 *    11 fronted entries shipped, Fastly (089D05B86ACE8D3B) for one -- at whatever address DNS gives
 *    for a generated name like a1014.na.akamai.net, with a random SNI. The official core takes
 *    `DNSResolverPreresolvedIPAddressCIDRs` ({providerID: [CIDR…]}) at probability 1.0, and then
 *    dials OUR addresses instead.
 *  - A clean IPv6 Akamai edge connected in 2.5 s where DNS took 8. The IPv4 edges answered TLS and
 *    carried nothing -- so both families are candidates and the REAL test decides, not TLS.
 *
 * Candidates: the names Psiphon itself dials, resolved on this phone (each address brings its
 * neighbours -- the /24, or the last 256 of an IPv6), random addresses from the providers' IPv4
 * ranges, and the user's own. Then a TLS handshake with a random SNI whose certificate must be the
 * provider's; then «سنجش واقعی»: a throwaway Psiphon in a process of its own given ONLY that address
 * ([PsiphonProbes]) -- connected or not, how long, and a real HTTPS request through it.
 *
 * «سریع» (the default) puts a data pre-check ([edgeCheck]) in front of the real test: one request
 * through the edge to Psiphon's own meek server. Measured on the phone 2026-10-18 over all 398
 * addresses of a scan: every IPv6 edge answered it with the meek server's 404 in 0.5–2 s and every
 * one of them then connected; every IPv4 edge completed TLS and sent back NOTHING in 30 s, eight
 * tries apiece, and none of them carried a Psiphon tunnel. An address that answers nothing is
 * settled by that alone; everything else still gets the real test, the answering ones first.
 */
object PsiphonCleanIps {

    private const val TAG = "PsiphonClean"
    private const val FILE = "psiphon/clean-ips.json"
    private const val MAX_KEEP = 400

    class Provider(
        val key: String,
        /** Psiphon's own id for the CDN, the key of `DNSResolverPreresolvedIPAddressCIDRs`. */
        val id: String,
        private val fa: String,
        private val en: String,
        val certRe: Regex,
        val names: (Int) -> List<String>,
        val ranges: List<String>,
    ) {
        val label: String get() = tr(fa, en)
    }

    private val rnd = SecureRandom()

    val PROVIDERS: Map<String, Provider> = linkedMapOf(
        "akamai" to Provider(
            "akamai", "BACEC04E64813F2B", "اکامای", "Akamai", Regex("akamai", RegexOption.IGNORE_CASE),
            names = { n ->
                // Psiphon's own fronting names: a([1-9]|1[0-9])([0-9]{2})\.(dsc)?(b|d|g|g2|na|r|w7)\.akamai\.net
                val l = listOf("b", "d", "g", "g2", "na", "r", "w7")
                val out = LinkedHashSet<String>()
                while (out.size < n) out += "a${100 + rnd.nextInt(1900)}.${if (rnd.nextBoolean()) "dsc" else ""}${l[rnd.nextInt(l.size)]}.akamai.net"
                out.toList()
            },
            ranges = listOf("2.16.0.0/13", "23.0.0.0/12", "23.32.0.0/11", "23.192.0.0/11", "95.100.0.0/15", "184.24.0.0/13", "104.64.0.0/10"),
        ),
        "fastly" to Provider(
            "fastly", "089D05B86ACE8D3B", "فستلی", "Fastly", Regex("fastly", RegexOption.IGNORE_CASE),
            names = { listOf("j.sni.global.fastly.net", "k.sni.global.fastly.net", "l.sni.global.fastly.net", "m.sni.global.fastly.net") },
            ranges = listOf("151.101.0.0/16", "146.75.0.0/16", "199.232.0.0/16"),
        ),
    )

    // ── the list ────────────────────────────────────────────────────────────────────────────────

    /**
     * The real test's verdict: a tunnel came up (in [ms]) and a whole HTTPS request crossed it (in
     * [pingMs]). [byEdge]: settled by the data pre-check alone -- nothing came back through the edge.
     */
    data class Real(val ok: Boolean, val ms: Long?, val pingMs: Long?, val error: String, val at: Long, val byEdge: Boolean = false)

    data class Entry(
        val ip: String,
        val provider: String,
        /** "user" or "scan". */
        val source: String,
        val addedAt: Long,
        /** Answered TLS with the provider's certificate. */
        val ok: Boolean = false,
        val tlsMs: Long? = null,
        val testedAt: Long = 0L,
        val fails: Int = 0,
        val real: Real? = null,
        /** A connection of the user's came up through this address. */
        val confirmedAt: Long = 0L,
        val connectMs: Long? = null,
        /** Picked by hand («انتخاب»): when any are, they are all rung E gets. */
        val pinned: Boolean = false,
    ) {
        val v6: Boolean get() = ip.contains(':')
    }

    /** An address under a real test right now: since when (wall clock), and whether its tunnel is already up. */
    data class Testing(val since: Long, val connected: Boolean = false)

    /** One finished test, for the progress card. */
    data class Recent(val ip: String, val ok: Boolean, val pingMs: Long?, val byEdge: Boolean)

    /** What a scan or a real test is doing, for the page. */
    data class Job(
        val running: Boolean = false,
        /** harvest | tls | real | done | cancelled */
        val stage: String = "",
        val startedAt: Long = 0L,
        /** When the run finished or was stopped (wall clock), for the page's summary. */
        val endedAt: Long = 0L,
        /** The scan's TLS stage. */
        val done: Int = 0,
        val total: Int = 0,
        val ok: Int = 0,
        /** «سنجش واقعی»: with the data pre-check («سریع»), or every address on a throwaway Psiphon. */
        val fast: Boolean = true,
        /** Addresses in this real-test run. */
        val ips: Int = 0,
        val edgeDone: Int = 0,
        /** Pre-check: Psiphon's meek server answered through the edge. */
        val edgeOk: Int = 0,
        /** Pre-check: nothing came back -- settled without a Psiphon. */
        val edgeSilent: Int = 0,
        /** Real tests finished with a verdict. */
        val real: Int = 0,
        val realOk: Int = 0,
        /** Real tests queued so far; it grows while the pre-check runs. */
        val realTotal: Int = 0,
        /** Tests that could not run (their process failed twice): nothing recorded for them. */
        val broken: Int = 0,
        /** How many real tests run at once. */
        val slots: Int = 0,
        val budgetMs: Int = PsiphonProbes.CLEAN_MS,
        val current: Map<String, Testing> = emptyMap(),
        val recent: List<Recent> = emptyList(),
        /** The finished real tests' time, added up, for the estimate. */
        val realMs: Long = 0L,
        val error: String = "",
        val note: String = "",
    ) {
        /** Addresses that have their answer: a real test's, or the pre-check's «nothing came back». */
        val settled: Int get() = real + edgeSilent + broken
    }

    data class State(val list: List<Entry> = emptyList(), val job: Job = Job())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    private val lock = Any()
    @Volatile private var loaded = false

    /** Read the list in once; the page calls it off the main thread. */
    fun init(ctx: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val list = runCatching { parse(JSONObject(file(ctx).readText())) }.getOrDefault(emptyList())
            _state.update { it.copy(list = sorted(list)) }
            loaded = true
        }
    }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    private fun save(ctx: Context) {
        val list = _state.value.list
        runCatching {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject().put("list", JSONArray().apply { list.forEach { put(toJson(it)) } }).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "save failed: ${it.message}") }
    }

    /** Change the list and keep it: the one way every write goes, so two jobs cannot lose each other's results. */
    private fun edit(ctx: Context, change: (MutableList<Entry>) -> Unit) {
        init(ctx)
        synchronized(lock) {
            val list = _state.value.list.toMutableList()
            change(list)
            _state.update { it.copy(list = sorted(list)) }
            save(ctx)
        }
    }

    private fun toJson(e: Entry) = JSONObject().apply {
        put("ip", e.ip); put("provider", e.provider); put("source", e.source); put("addedAt", e.addedAt)
        if (e.ok) put("ok", true)
        e.tlsMs?.let { put("tlsMs", it) }
        if (e.testedAt > 0) put("testedAt", e.testedAt)
        if (e.fails > 0) put("fails", e.fails)
        e.real?.let { r ->
            put("real", JSONObject().put("ok", r.ok).put("error", r.error).put("at", r.at).apply {
                r.ms?.let { put("ms", it) }; r.pingMs?.let { put("pingMs", it) }
                if (r.byEdge) put("edge", true)
            })
        }
        if (e.confirmedAt > 0) put("confirmedAt", e.confirmedAt)
        e.connectMs?.let { put("connectMs", it) }
        if (e.pinned) put("pinned", true)
    }

    private fun parse(o: JSONObject): List<Entry> {
        val a = o.optJSONArray("list") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val x = a.optJSONObject(i) ?: return@mapNotNull null
            val ip = x.optString("ip").takeIf { isIp(it) } ?: return@mapNotNull null
            Entry(
                ip = ip, provider = x.optString("provider", "akamai").takeIf { it in PROVIDERS } ?: "akamai",
                source = x.optString("source", "scan"), addedAt = x.optLong("addedAt"),
                ok = x.optBoolean("ok"), tlsMs = x.optLong("tlsMs", -1).takeIf { it >= 0 },
                testedAt = x.optLong("testedAt"), fails = x.optInt("fails"),
                real = x.optJSONObject("real")?.let { r ->
                    Real(r.optBoolean("ok"), r.optLong("ms", -1).takeIf { it >= 0 }, r.optLong("pingMs", -1).takeIf { it >= 0 },
                        r.optString("error"), r.optLong("at"), r.optBoolean("edge"))
                },
                confirmedAt = x.optLong("confirmedAt"), connectMs = x.optLong("connectMs", -1).takeIf { it >= 0 },
                pinned = x.optBoolean("pinned"),
            )
        // One row per address: the page's list is keyed by it, and a key twice closes the app.
        }.distinctBy { it.ip }
    }

    /**
     * Working first, and among those the FASTEST first -- the ping the row shows -- so a run can be
     * stopped at any moment and the top row is the one to pick. Then the ones still to be tested,
     * then the failed ones.
     */
    internal fun rank(e: Entry): Int = when {
        e.real?.ok == true -> 0
        e.real == null && e.confirmedAt > 0 -> 0
        e.real == null && e.ok -> 1
        e.real == null && e.testedAt == 0L -> 2
        e.real != null -> 3
        else -> 4
    }

    internal fun sorted(list: List<Entry>): List<Entry> = list.sortedWith(
        compareBy<Entry> { rank(it) }
            .thenBy { it.real?.takeIf { r -> r.ok }?.pingMs ?: Long.MAX_VALUE }
            .thenBy { it.real?.ms ?: it.connectMs ?: Long.MAX_VALUE }
            .thenBy { it.tlsMs ?: Long.MAX_VALUE },
    )

    data class AddResult(val added: Int, val bad: List<String>)

    /** The user's own addresses: IPs (v4/v6) and small IPv4 CIDRs (/22 to /32), one per line or space. */
    fun addUser(ctx: Context, text: String, provider: String = "akamai"): AddResult {
        val prov = provider.takeIf { it in PROVIDERS } ?: "akamai"
        var added = 0
        val bad = ArrayList<String>()
        val now = System.currentTimeMillis()
        edit(ctx) { list ->
            val have = list.mapTo(HashSet()) { it.ip }
            fun upsert(ip: String) {
                if (ip in have) {
                    val i = list.indexOfFirst { it.ip == ip }
                    if (i >= 0) list[i] = list[i].copy(source = "user")
                } else {
                    have += ip; list += Entry(ip, prov, "user", now); added++
                }
            }
            for (tok in text.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }) {
                // "[v6]" and "[v6]:443" lose the brackets and the port; "v4:443" its port. A bare IPv6
                // keeps everything: "2a02::443" is an address whose last group is 0x443.
                val t = when {
                    tok.startsWith("[") -> tok.substringAfter('[').substringBefore(']')
                    tok.endsWith(":443") && isV4(tok.removeSuffix(":443")) -> tok.removeSuffix(":443")
                    else -> tok
                }
                val m = Regex("""^(\d+\.\d+\.\d+\.\d+)/(\d{1,2})$""").find(t)
                if (m != null && isV4(m.groupValues[1])) {
                    val bits = m.groupValues[2].toInt()
                    if (bits in 22..32) { expandV4(m.groupValues[1], bits).forEach { upsert(it) }; continue }
                }
                val ip = canonical(t)
                if (ip != null && !isBogon(ip)) { upsert(ip); continue }
                bad += tok.take(40)
            }
        }
        return AddResult(added, bad)
    }

    fun remove(ctx: Context, ips: Collection<String>): Int {
        var n = 0
        edit(ctx) { list -> val before = list.size; list.removeAll { it.ip in ips }; n = before - list.size }
        return n
    }

    /** «حذف خراب‌ها»: failed the real test (or the pre-check), or TLS twice. */
    fun removeDead(ctx: Context): Int {
        var n = 0
        edit(ctx) { list ->
            val before = list.size
            list.removeAll { (it.real?.ok == false && it.confirmedAt == 0L) || (it.fails >= 2 && !it.ok) }
            n = before - list.size
        }
        return n
    }

    /** «انتخاب» on a row: pinned addresses are all rung E gets. */
    fun setPinned(ctx: Context, ip: String, on: Boolean) = edit(ctx) { list ->
        val i = list.indexOfFirst { it.ip == ip }
        if (i >= 0) list[i] = list[i].copy(pinned = on)
    }

    fun clearPinned(ctx: Context) = edit(ctx) { list -> list.replaceAll { it.copy(pinned = false) } }

    /** The core named the address that carried the tunnel (ConnectedServer · meekResolvedIPAddress). */
    fun noteUsed(ctx: Context, ip: String, ms: Long?) {
        val clean = canonical(ip.replace("\\", "").trim()) ?: return
        edit(ctx) { list ->
            val i = list.indexOfFirst { it.ip == clean }
            if (i >= 0) list[i] = list[i].copy(confirmedAt = System.currentTimeMillis(), connectMs = ms ?: list[i].connectMs)
        }
    }

    // ── what Psiphon is given ───────────────────────────────────────────────────────────────────

    /**
     * Rung E's `DNSResolverPreresolvedIPAddressCIDRs`: per provider id, the best [max] addresses that
     * are not known bad, proven ones first (the fastest of those first); the pinned ones alone when
     * any are. Empty when there is nothing -- and then rung E is not in the ladder at all.
     */
    fun configCidrs(ctx: Context, max: Int = 8): Map<String, List<String>> {
        init(ctx)
        val all = _state.value.list
        val out = LinkedHashMap<String, List<String>>()
        fun cidr(e: Entry) = e.ip + if (e.v6) "/128" else "/32"
        val pinned = all.filter { it.pinned }
        if (pinned.isNotEmpty()) {
            for ((key, p) in PROVIDERS) pinned.filter { it.provider == key }.takeIf { it.isNotEmpty() }?.let { out[p.id] = it.map(::cidr) }
            return out
        }
        // A connection of the user's that came up through an address outweighs one throwaway test
        // that did not; the list's order still puts the ones that pass today first.
        val proven = all.filter { it.confirmedAt > 0 || it.real?.ok == true }
        // Two proven addresses stand on their own: TLS-only answers are what failed on the desktop's
        // line (IPv4 edges answer TLS and carry nothing), so they only fill in when little is proven.
        // Nothing that failed a test fills in -- the user's own addresses included.
        val good = if (proven.size >= 2) proven
            else all.filter { it.confirmedAt > 0 || it.real?.ok == true || (it.real == null && (it.ok || (it.source == "user" && it.testedAt == 0L))) }
        for ((key, p) in PROVIDERS) good.filter { it.provider == key }.take(max).takeIf { it.isNotEmpty() }?.let { out[p.id] = it.map(::cidr) }
        return out
    }

    fun hasUsable(ctx: Context): Boolean = configCidrs(ctx).isNotEmpty()

    fun usableCount(ctx: Context): Int = configCidrs(ctx).values.sumOf { it.size }

    /** Which provider a clean address of the core's belongs to, for the real test of one. */
    fun providerOf(ip: String): Provider = PROVIDERS[_state.value.list.firstOrNull { it.ip == ip }?.provider] ?: PROVIDERS.getValue("akamai")

    // ── candidates ──────────────────────────────────────────────────────────────────────────────

    private val V4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    internal fun isV4(s: String): Boolean = V4.find(s)?.groupValues?.drop(1)?.all { (it.toIntOrNull() ?: 999) <= 255 } == true

    private fun isIp(s: String) = isV4(s) || (s.contains(':') && canonical(s) != null)

    /** An IP written the one way the list compares it: IPv4 as is, IPv6 compressed (RFC 5952). Null when it is no IP. */
    internal fun canonical(s: String): String? {
        val t = s.trim()
        if (isV4(t)) return t.split('.').joinToString(".") { it.toInt().toString() }
        if (!t.contains(':') || !Regex("^[0-9A-Fa-f:.]+$").matches(t)) return null
        val groups = ipv6Groups(t) ?: return null
        return compactV6(groups)
    }

    /** The eight 16-bit groups of an IPv6 literal, or null. No DNS: a literal only. */
    internal fun ipv6Groups(s: String): IntArray? {
        if (s.count { it == ':' } < 2 || s.contains(":::")) return null
        val parts = s.split("::")
        if (parts.size > 2) return null
        fun groups(x: String): List<Int>? = if (x.isEmpty()) emptyList() else x.split(':').map { g ->
            if (g.isEmpty() || g.length > 4) return null
            g.toIntOrNull(16) ?: return null
        }
        val left = groups(parts[0]) ?: return null
        val right = if (parts.size == 2) groups(parts[1]) ?: return null else emptyList()
        val fill = 8 - left.size - right.size
        if (parts.size == 1 && fill != 0) return null
        if (parts.size == 2 && fill < 1) return null
        return (left + List(if (parts.size == 2) fill else 0) { 0 } + right).toIntArray()
    }

    internal fun compactV6(g: IntArray): String {
        // The longest run of two or more zero groups becomes "::" (the first, on a tie).
        var bestAt = -1; var bestLen = 0; var i = 0
        while (i < 8) {
            if (g[i] == 0) {
                var j = i
                while (j < 8 && g[j] == 0) j++
                if (j - i > bestLen && j - i >= 2) { bestAt = i; bestLen = j - i }
                i = j
            } else i++
        }
        if (bestAt < 0) return g.joinToString(":") { Integer.toHexString(it) }
        val head = (0 until bestAt).joinToString(":") { Integer.toHexString(g[it]) }
        val tail = (bestAt + bestLen until 8).joinToString(":") { Integer.toHexString(g[it]) }
        return "$head::$tail"
    }

    internal fun isBogon(ip: String): Boolean = if (isV4(ip)) {
        Regex("""^(0|10|127)\.|^169\.254\.|^172\.(1[6-9]|2\d|3[01])\.|^192\.168\.|^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.""").containsMatchIn(ip)
    } else {
        Regex("^(::|fe80:|fc|fd)", RegexOption.IGNORE_CASE).containsMatchIn(ip) || ip == "::1"
    }

    private fun v4ToInt(ip: String): Long = ip.split('.').fold(0L) { a, b -> a * 256 + b.toLong() }
    private fun intToV4(n: Long): String = "${(n shr 24) and 255}.${(n shr 16) and 255}.${(n shr 8) and 255}.${n and 255}"

    /** Every host address of [ip]'s /[bits] (no .0, no .255). */
    internal fun expandV4(ip: String, bits: Int): List<String> {
        val size = 1L shl (32 - bits)
        val base = (v4ToInt(ip) / size) * size
        return (0 until size).mapNotNull { k -> (base + k).takeIf { (it and 255L) != 0L && (it and 255L) != 255L }?.let(::intToV4) }
    }

    /** The 256 neighbours in the same edge cluster: the last group's low byte. */
    internal fun expandV6(ip: String): List<String> {
        val g = ipv6Groups(ip) ?: return emptyList()
        val hi = g[7] and 0xff00
        return (0 until 256).map { k -> compactV6(g.copyOf().also { it[7] = hi + k }) }
    }

    private fun sampleRanges(p: Provider, n: Int): List<String> {
        val out = LinkedHashSet<String>()
        var k = 0
        while (k++ < n * 3 && out.size < n) {
            val (base, bits) = p.ranges[rnd.nextInt(p.ranges.size)].split('/')
            val size = 1L shl (32 - bits.toInt())
            val v = v4ToInt(base) + (rnd.nextLong() and Long.MAX_VALUE) % size
            if ((v and 255L) != 0L && (v and 255L) != 255L) out += intToV4(v)
        }
        return out.toList()
    }

    /** The edges this phone's DNS maps Psiphon's own fronting names to. */
    private suspend fun harvest(p: Provider, count: Int, my: Int): List<String> = coroutineScope {
        val names = p.names(count)
        val found = Collections.synchronizedSet(LinkedHashSet<String>())
        val next = AtomicInteger()
        (0 until 16).map {
            launch(Dispatchers.IO) {
                while (isActive && mine(my)) {
                    val i = next.getAndIncrement()
                    if (i >= names.size) break
                    runCatching { InetAddress.getAllByName(names[i]) }.getOrNull()?.forEach { a ->
                        canonical(a.hostAddress.orEmpty().substringBefore('%'))?.takeIf { !isBogon(it) }?.let { found += it }
                    }
                }
            }
        }.forEach { it.join() }
        found.toList()
    }

    // ── testing ─────────────────────────────────────────────────────────────────────────────────

    private fun randomSni(): String {
        val a = "abcdefghijklmnopqrstuvwxyz"
        val n = 4 + rnd.nextInt(2)
        return (0 until n).map { a[rnd.nextInt(26)] }.joinToString("") + listOf(".com", ".org", ".net")[rnd.nextInt(3)]
    }

    /** Any certificate: what is checked is whose it is, not whether this phone trusts it. */
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    private val tlsFactory by lazy { SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), rnd) }.socketFactory }

    /**
     * The sockets of the tests in flight: «توقف» closes them, since a socket blocked in a connect or a
     * read does not notice its coroutine being cancelled (a stop waited for every one of them).
     */
    private val live: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    private fun closeLive() { live.toList().forEach { runCatching { it.close() } } }

    private fun isProviders(s: SSLSocket, p: Provider): Boolean {
        val cert = s.session.peerCertificates.firstOrNull() as? X509Certificate
        val names = (cert?.subjectX500Principal?.name.orEmpty()) + " " +
            runCatching { cert?.subjectAlternativeNames?.joinToString(" ") { it.getOrNull(1)?.toString().orEmpty() } }.getOrNull().orEmpty()
        return p.certRe.containsMatchIn(names)
    }

    data class Tls(val ok: Boolean, val ms: Long?, val error: String = "")

    /** TLS straight to ip:443 with a random SNI; ok only when the certificate is the provider's. */
    fun tlsTest(ip: String, p: Provider, timeoutMs: Int = 4_000): Tls {
        val t0 = System.nanoTime()
        val raw = Socket(Proxy.NO_PROXY)
        live += raw
        return try {
            raw.use {
                raw.connect(InetSocketAddress(InetAddress.getByName(ip), 443), timeoutMs)
                raw.soTimeout = timeoutMs
                val sni = randomSni()
                (tlsFactory.createSocket(raw, sni, 443, true) as SSLSocket).use { s ->
                    s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
                    s.startHandshake()
                    if (isProviders(s, p)) Tls(true, (System.nanoTime() - t0) / 1_000_000)
                    else Tls(false, null, tr("گواهی دیگری", "another certificate"))
                }
            }
        } catch (e: Exception) {
            Tls(false, null, e.javaClass.simpleName)
        } finally {
            live -= raw
        }
    }

    /** What came back through an edge, asked the way Psiphon's fronted meek asks. */
    enum class EdgeVerdict {
        /** Psiphon's meek server answered (Go's «404 page not found» to a cookieless request): data crosses. */
        ORIGIN,
        /** Something else answered, or part of an answer came: inconclusive -- the real test decides. */
        OTHER,
        /** TLS completed and then nothing came back at all. */
        SILENT,
        /** No TLS with the provider's certificate. */
        NOTLS,
    }

    data class Edge(val verdict: EdgeVerdict, val ms: Long? = null)

    /** How long the pre-check waits for an answer; an answering edge takes 0.5–2 s (phone, 2026-10-18). */
    const val EDGE_MS = 20_000

    /**
     * The data pre-check: TLS with a random SNI to ip:443 (the provider's certificate required), then
     * one request whose Host is one of Psiphon's own meek fronts for this provider ([hosts]), on two
     * connections at once with two fronts. Any meek answer settles it at once; [EdgeVerdict.SILENT]
     * only when every connection that completed TLS got nothing back in [budgetMs].
     */
    internal suspend fun edgeCheck(ip: String, p: Provider, hosts: List<String>, budgetMs: Int = EDGE_MS): Edge = coroutineScope {
        if (hosts.isEmpty()) return@coroutineScope Edge(EdgeVerdict.OTHER)
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        val picks = hosts.shuffled(rnd).let { if (it.size >= 2) it.take(2) else listOf(it[0], it[0]) }
        val mine = Collections.synchronizedList(ArrayList<Socket>())
        val results = Channel<Edge>(picks.size)
        val tries = picks.map { h -> launch(EDGE_IO) { results.send(edgeTry(ip, p, h, deadline, mine)) } }
        var best: Edge? = null
        for (k in picks.indices) {
            val r = results.receive()
            if (r.verdict == EdgeVerdict.ORIGIN) {
                // The other connection is not needed: closed, so its read gives up now.
                synchronized(mine) { mine.forEach { runCatching { it.close() } } }
                tries.forEach { it.cancel() }
                return@coroutineScope r
            }
            best = combine(best, r)
        }
        best ?: Edge(EdgeVerdict.NOTLS)
    }

    /** OTHER over SILENT over NOTLS: any answer at all means data crossed. */
    internal fun combine(a: Edge?, b: Edge): Edge {
        if (a == null) return b
        fun w(e: Edge) = when (e.verdict) { EdgeVerdict.ORIGIN -> 3; EdgeVerdict.OTHER -> 2; EdgeVerdict.SILENT -> 1; EdgeVerdict.NOTLS -> 0 }
        return if (w(b) > w(a)) b else a
    }

    /** What a whole answer says: the meek server's own 404, or anything else. */
    internal fun edgeAnswer(head: String): EdgeVerdict {
        val status = head.substringBefore("\r\n")
        val body = head.substringAfter("\r\n\r\n", "")
        return if (status.startsWith("HTTP/1.") && status.contains(" 404") && body.startsWith("404 page not found")) EdgeVerdict.ORIGIN
        else EdgeVerdict.OTHER
    }

    private fun edgeTry(ip: String, p: Provider, host: String, deadline: Long, mine: MutableList<Socket>): Edge {
        fun left() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L).toInt()
        val raw = Socket(Proxy.NO_PROXY)
        live += raw
        mine += raw
        var tls = false
        var got = 0
        return try {
            raw.connect(InetSocketAddress(InetAddress.getByName(ip), 443), minOf(5_000, left()))
            raw.soTimeout = left()
            val sni = randomSni()
            val s = tlsFactory.createSocket(raw, sni, 443, true) as SSLSocket
            s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
            s.startHandshake()
            if (!isProviders(s, p)) return Edge(EdgeVerdict.NOTLS)
            tls = true
            val sent = SystemClock.elapsedRealtime()
            s.outputStream.write("GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            s.outputStream.flush()
            val buf = ByteArray(2048)
            val sb = StringBuilder()
            var first = -1L
            while (sb.length < 4096) {
                s.soTimeout = left()
                val n = s.inputStream.read(buf)
                if (n < 0) break
                if (first < 0) first = SystemClock.elapsedRealtime() - sent
                got += n
                sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                if (sb.contains("\r\n\r\n") && edgeAnswer(sb.toString()) == EdgeVerdict.ORIGIN) break
            }
            when {
                got == 0 -> Edge(EdgeVerdict.SILENT)
                else -> Edge(edgeAnswer(sb.toString()), first)
            }
        } catch (e: Exception) {
            when {
                !tls -> Edge(EdgeVerdict.NOTLS)
                got == 0 -> Edge(EdgeVerdict.SILENT)
                else -> Edge(EdgeVerdict.OTHER)
            }
        } finally {
            runCatching { raw.close() }
            live -= raw
            mine -= raw
        }
    }

    // ── jobs ────────────────────────────────────────────────────────────────────────────────────

    data class ScanOptions(
        val providers: List<String> = listOf("akamai"),
        val families: List<String> = listOf("v6", "v4"),
        val sources: List<String> = listOf("names"),
        val maxIps: Int = 1500,
        val realTop: Int = 6,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: kotlinx.coroutines.Job? = null

    /**
     * The run that owns the page: every write of a run checks it is still the current one. «توقف»
     * moves it on, so the page is free at once and whatever the stopped run still has in flight
     * (a socket timing out, a core being stopped) can no longer touch the list or the card.
     */
    @Volatile private var gen = 0

    private fun mine(my: Int) = my == gen

    private fun job(my: Int, change: (Job) -> Job) {
        if (!mine(my)) return
        _state.update { if (mine(my)) it.copy(job = change(it.job)) else it }
    }

    /** The counters a run bumps from many threads at once; [tick] copies them to the page four times a second. */
    private class Counters {
        val tlsDone = AtomicInteger()
        val tlsOk = AtomicInteger()
        val edgeDone = AtomicInteger()
        val edgeOk = AtomicInteger()
        val edgeSilent = AtomicInteger()
        val queued = AtomicInteger()
    }

    /** Addresses the pre-check settled, written to the list in batches. */
    private val silentQueue = ConcurrentLinkedQueue<String>()
    @Volatile private var appCtx: Context? = null

    private fun publish(my: Int, c: Counters) = job(my) {
        it.copy(done = c.tlsDone.get(), ok = c.tlsOk.get(), edgeDone = c.edgeDone.get(), edgeOk = c.edgeOk.get(),
            edgeSilent = c.edgeSilent.get(), realTotal = c.queued.get())
    }

    private fun silentText(): String = tr("پیش‌سنجش: در ${fa(EDGE_MS / 1000)} ثانیه هیچ داده‌ای از این لبه برنگشت",
        "Pre-check: no data came back through this edge in ${EDGE_MS / 1000} s")

    private fun flushSilent(app: Context) {
        val ips = generateSequence { silentQueue.poll() }.toList()
        if (ips.isEmpty()) return
        val now = System.currentTimeMillis()
        val text = silentText()
        val set = ips.toHashSet()
        edit(app) { l ->
            for (k in l.indices) if (l[k].ip in set) l[k] = l[k].copy(real = Real(false, null, null, text, now, byEdge = true))
        }
    }

    private suspend fun tick(my: Int, c: Counters, app: Context) {
        var n = 0
        while (mine(my)) {
            delay(250)
            publish(my, c)
            if (++n % 2 == 0) flushSilent(app)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val TLS = Dispatchers.IO.limitedParallelism(48)

    /** Addresses pre-checked at once (two connections each), as many as the scan's TLS stage. */
    private const val EDGE_PARALLEL = 48

    /** Two connections per address in the pre-check. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val EDGE_IO = Dispatchers.IO.limitedParallelism(2 * EDGE_PARALLEL + 4)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val EDGE = Dispatchers.IO.limitedParallelism(EDGE_PARALLEL)

    private const val PREF_FAST = "psiphon_clean_fast"

    /** «سریع» (the data pre-check first) or «کامل» (every address on a throwaway Psiphon). */
    fun fastMode(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF_FAST, true)

    fun setFastMode(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(PREF_FAST, on).apply()
    }

    /** «اسکن»: candidates → TLS → the best [ScanOptions.realTop] get the real test (half IPv6, half IPv4). */
    fun scan(ctx: Context, o: ScanOptions) {
        val app = ctx.applicationContext
        if (_state.value.job.running) return
        val my = ++gen
        appCtx = app
        val c = Counters()
        _state.update { it.copy(job = Job(running = true, stage = "harvest", startedAt = System.currentTimeMillis())) }
        running = scope.launch {
            val ticker = launch { tick(my, c, app) }
            try {
                init(app)
                val seen = _state.value.list.mapTo(HashSet()) { it.ip }
                val cand = ArrayList<Pair<String, String>>()
                for (key in o.providers.filter { it in PROVIDERS }) {
                    val p = PROVIDERS.getValue(key)
                    val seeds = if ("names" in o.sources) harvest(p, if (key == "akamai") 220 else 4, my) else emptyList()
                    val pool = ArrayList<String>()
                    for (ip in seeds) {
                        if (ip.contains(':') && "v6" in o.families) pool += expandV6(ip)
                        if (!ip.contains(':') && "v4" in o.families) pool += expandV4(ip, 24)
                    }
                    if ("ranges" in o.sources && "v4" in o.families) pool += sampleRanges(p, (o.maxIps + 2) / 3)
                    // The found edges themselves first, then their neighbours, shuffled so one cluster
                    // does not take the whole budget.
                    val order = seeds.filter { if (it.contains(':')) "v6" in o.families else "v4" in o.families } + pool.shuffled(rnd)
                    for (ip in order) if (ip !in seen && !isBogon(ip)) { seen += ip; cand += ip to key }
                }
                if (!mine(my)) return@launch
                val list = cand.take(o.maxIps)
                job(my) { it.copy(stage = "tls", total = list.size, done = 0, ok = 0,
                    note = if (list.isEmpty()) tr("نامی حل نشد — اینترنت یا DNS این گوشی را بررسی کنید، یا «رنج‌ها» را هم انتخاب کنید.",
                        "No name resolved -- check this phone's internet or DNS, or choose «ranges» too.") else "") }
                val found = ConcurrentLinkedQueue<Triple<String, String, Long>>()
                val next = AtomicInteger()
                coroutineScope {
                    repeat(minOf(48, list.size)) {
                        launch(TLS) {
                            while (isActive && mine(my)) {
                                val i = next.getAndIncrement()
                                if (i >= list.size) break
                                val (ip, key) = list[i]
                                val r = tlsTest(ip, PROVIDERS.getValue(key))
                                if (!mine(my)) break
                                if (r.ok) { found += Triple(ip, key, r.ms ?: 0L); c.tlsOk.incrementAndGet() }
                                c.tlsDone.incrementAndGet()
                            }
                        }
                    }
                }
                if (!mine(my)) return@launch
                publish(my, c)
                val now = System.currentTimeMillis()
                edit(app) { l ->
                    for ((ip, key, ms) in found) {
                        val i = l.indexOfFirst { it.ip == ip }
                        val e = if (i >= 0) l[i] else Entry(ip, key, "scan", now)
                        val upd = e.copy(ok = true, tlsMs = ms, testedAt = now, fails = 0)
                        if (i >= 0) l[i] = upd else l += upd
                    }
                    // Bounded: what proved itself, then the fastest TLS answers.
                    val keep = sorted(l).take(MAX_KEEP)
                    l.clear(); l.addAll(keep)
                }
                if (o.realTop > 0 && mine(my) && found.isNotEmpty()) {
                    // The fastest of each family: on a line like the desktop's, IPv4 edges answer TLS and carry nothing.
                    val fresh = sorted(_state.value.list.filter { e -> found.any { it.first == e.ip } })
                    val pick = fresh.filter { it.v6 }.take((o.realTop + 1) / 2) + fresh.filter { !it.v6 }.take(o.realTop / 2)
                    realTestNow(app, my, c, pick.map { it.ip }, fastMode(app))
                }
                Log.i(TAG, "scan: ${list.size} candidates, ${found.size} answered TLS, real ${_state.value.job.realOk}/${_state.value.job.real}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                job(my) { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                ticker.cancel()
                finish(my, c, app)
            }
        }
    }

    /**
     * «سنجش واقعی» of [ips] (best first). [fast]: the data pre-check first, as the page's setting says;
     * a single address the user asked about gets the whole real test, whatever the setting.
     */
    fun realTest(ctx: Context, ips: List<String>, fast: Boolean = ips.size > 1 && fastMode(ctx)) {
        val app = ctx.applicationContext
        if (_state.value.job.running || ips.isEmpty()) return
        val my = ++gen
        appCtx = app
        val c = Counters()
        _state.update { it.copy(job = Job(running = true, stage = "real", startedAt = System.currentTimeMillis(), fast = fast)) }
        running = scope.launch {
            val ticker = launch { tick(my, c, app) }
            try { realTestNow(app, my, c, ips, fast) }
            finally {
                ticker.cancel()
                finish(my, c, app)
            }
        }
    }

    private fun finish(my: Int, c: Counters, app: Context) {
        if (!mine(my)) return
        flushSilent(app)
        publish(my, c)
        job(my) { it.copy(running = false, stage = "done", current = emptyMap(), endedAt = System.currentTimeMillis()) }
        val j = _state.value.job
        if (j.ips > 0) Log.i(TAG, "real test: ${j.ips} addresses in ${(j.endedAt - j.startedAt) / 1000} s on ${j.slots} slots, fast=${j.fast}; " +
            "pre-check ${j.edgeOk} answered, ${j.edgeSilent} silent of ${j.edgeDone}; real ${j.realOk} of ${j.real} connected, ${j.broken} could not run")
    }

    /** Every address on the list, best first: «سنجش واقعی همه». */
    fun allIps(): List<String> = _state.value.list.map { it.ip }

    /**
     * Addresses waiting for their real test: those whose edge answered first, the quickest answer
     * first; then the ones the pre-check could not tell about. Several workers take from it while
     * the pre-check is still filling it.
     */
    private class Work {
        private val lock = Any()
        private var order = 0L
        private val q = PriorityQueue<Triple<Long, Long, String>>(compareBy<Triple<Long, Long, String>> { it.first }.thenBy { it.second })
        private var closed = false
        private val wake = Channel<Unit>(Channel.CONFLATED)

        fun add(ip: String, key: Long) {
            synchronized(lock) { q.add(Triple(key, order++, ip)) }
            wake.trySend(Unit)
        }

        fun close() {
            synchronized(lock) { closed = true }
            wake.trySend(Unit)
        }

        /** The next address, or null once the pre-check is done and nothing is left. */
        suspend fun take(): String? {
            while (true) {
                var item: String? = null
                var over = false
                var more = false
                synchronized(lock) {
                    item = q.poll()?.third
                    more = q.isNotEmpty()
                    over = item == null && closed
                }
                // One wake-up may stand for several additions: passed on to the next worker waiting.
                if (item != null) { if (more) wake.trySend(Unit); return item }
                if (over) { wake.trySend(Unit); return null }
                wake.receive()
            }
        }
    }

    private suspend fun realTestNow(app: Context, my: Int, c: Counters, ips: List<String>, fast: Boolean) = coroutineScope {
        val width = PsiphonProbes.width(app)
        c.queued.set(0)
        job(my) { it.copy(stage = "real", fast = fast, ips = ips.size, edgeDone = 0, edgeOk = 0, edgeSilent = 0, real = 0, realOk = 0,
            realTotal = 0, broken = 0, slots = width, budgetMs = PsiphonProbes.CLEAN_MS, current = emptyMap(), recent = emptyList(), realMs = 0L) }
        listOf(c.edgeDone, c.edgeOk, c.edgeSilent).forEach { it.set(0) }
        val work = Work()
        if (fast) {
            launch {
                try {
                    // Read once, before the workers: each would otherwise wait for the first one's reading.
                    val hosts = PROVIDERS.mapValues { (_, p) -> PsiphonProbes.fronted(app, p.id).hosts }
                    val next = AtomicInteger()
                    coroutineScope {
                        repeat(minOf(EDGE_PARALLEL, ips.size)) {
                            launch(EDGE) {
                                while (isActive && mine(my)) {
                                    val i = next.getAndIncrement()
                                    if (i >= ips.size) break
                                    val ip = ips[i]
                                    val p = providerOf(ip)
                                    val e = edgeCheck(ip, p, hosts[p.key].orEmpty())
                                    if (!mine(my)) break
                                    when (e.verdict) {
                                        EdgeVerdict.SILENT -> {
                                            silentQueue += ip
                                            c.edgeSilent.incrementAndGet()
                                            job(my) { j -> j.copy(recent = (listOf(Recent(ip, false, null, true)) + j.recent).take(3)) }
                                        }
                                        EdgeVerdict.ORIGIN -> { c.edgeOk.incrementAndGet(); c.queued.incrementAndGet(); work.add(ip, e.ms ?: 0L) }
                                        EdgeVerdict.OTHER -> { c.queued.incrementAndGet(); work.add(ip, 100_000L + (e.ms ?: 0L)) }
                                        EdgeVerdict.NOTLS -> { c.queued.incrementAndGet(); work.add(ip, 200_000L) }
                                    }
                                    c.edgeDone.incrementAndGet()
                                }
                            }
                        }
                    }
                } finally {
                    work.close()
                }
            }
        } else {
            ips.forEachIndexed { i, ip -> work.add(ip, i.toLong()) }
            c.queued.set(ips.size)
            work.close()
        }
        repeat(minOf(width, ips.size)) {
            launch {
                while (mine(my)) {
                    val ip = work.take() ?: break
                    if (!mine(my)) break
                    testOne(app, my, ip)
                }
            }
        }
    }

    private suspend fun testOne(app: Context, my: Int, ip: String) {
        val p = providerOf(ip)
        val since = System.currentTimeMillis()
        job(my) { it.copy(current = it.current + (ip to Testing(since))) }
        val r = try {
            PsiphonProbes.cleanIp(app, ip, p.id, onConnected = { job(my) { j -> j.copy(current = j.current + (ip to Testing(since, true))) } })
        } finally {
            job(my) { it.copy(current = it.current - ip) }
        }
        if (!mine(my)) return
        val took = System.currentTimeMillis() - since
        if (r.harness) {
            // The test broke twice, not the address: nothing is written about it.
            Log.w(TAG, "real test of $ip could not run: ${r.error}")
            job(my) { it.copy(broken = it.broken + 1) }
            return
        }
        edit(app) { l ->
            val k = l.indexOfFirst { it.ip == ip }
            if (k >= 0) l[k] = l[k].copy(real = Real(r.ok, r.ms, r.pingMs,
                if (r.ok) "" else r.error ?: tr("با سایفون وصل نشد", "Psiphon did not connect"), System.currentTimeMillis()))
        }
        job(my) {
            it.copy(real = it.real + 1, realOk = it.realOk + if (r.ok) 1 else 0, realMs = it.realMs + took,
                recent = (listOf(Recent(ip, r.ok, r.pingMs, false)) + it.recent).take(3))
        }
    }

    /**
     * «توقف»: the page is free at once. The run is cancelled, its sockets closed (a blocked connect or
     * read does not see a cancellation), and what it still has in flight can write nothing more; the
     * cores in flight are stopped in the background within a second or two.
     */
    fun cancel() {
        if (!_state.value.job.running) return
        // What the pre-check already settled is a measurement like any other: written, off this thread.
        appCtx?.let { app -> scope.launch { runCatching { flushSilent(app) } } }
        gen++
        running?.cancel()
        closeLive()
        _state.update { it.copy(job = it.job.copy(running = false, stage = "cancelled", current = emptyMap(), endedAt = System.currentTimeMillis())) }
    }

    /** Persian digits, for the Persian half of a sentence. */
    private fun fa(n: Int): String = n.toString().map { c -> if (c in '0'..'9') '۰' + (c - '0') else c }.joinToString("")

    /** For tests only: forget what was read. */
    internal fun resetForTest() { loaded = false; _state.value = State() }
}
