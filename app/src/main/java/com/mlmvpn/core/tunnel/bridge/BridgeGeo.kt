package com.mlmvpn.core.tunnel.bridge

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * A bridge's country, from address ranges on the phone -- never one lookup per bridge on someone
 * else's server (a bridge address is half a secret, and BridgeDB's ones are handed out sparingly).
 *
 * Two tables, cheapest first:
 *  1. Tor's own GeoIP file, already in the APK for the exit-country picker (assets/geoip). It is
 *     filtered to the picker's countries, yet it placed 833 of 935 community IPv4 bridges on
 *     2026-10-03 (DE, US, NL, FR, CA, GB, FI ...), because bridges live where relays live.
 *  2. For what that misses -- IPv6, the remaining countries, webtunnel hosts -- the open
 *     `ip-location-db` country tables (geo-whois-asn-country, PDDL), downloaded once over jsDelivr
 *     (2.3 MB + 4.5 MB), kept, and refreshed monthly. Only fetched when something is unplaced.
 *
 * A webtunnel/meek/snowflake bridge is placed by the address its HOST resolves to. A host that
 * does not resolve, or resolves to Iran's block page, stays unknown -- nothing is guessed.
 */
internal object BridgeGeo {

    private class Table4(val start: LongArray, val end: LongArray, val cc: Array<String>)
    private class Table6(val startHi: LongArray, val startLo: LongArray, val endHi: LongArray, val endLo: LongArray, val cc: Array<String>)

    private const val FULL4 = "https://cdn.jsdelivr.net/npm/@ip-location-db/geo-whois-asn-country/geo-whois-asn-country-ipv4-num.csv"
    private const val FULL6 = "https://cdn.jsdelivr.net/npm/@ip-location-db/geo-whois-asn-country/geo-whois-asn-country-ipv6-num.csv"
    private const val FULL_MAX_AGE_MS = 30L * 24 * 3600_000L

    private fun dir(ctx: Context) = File(ctx.filesDir, "tor_bridges/geo").apply { mkdirs() }

    /**
     * Countries for [ips] (addresses as text). Missing entries mean "unknown". Loads the tables
     * once for the batch and lets them go afterwards: a few megabytes for a second, not for the
     * life of the app.
     */
    fun countries(ctx: Context, ips: Collection<String>): Map<String, String> {
        if (ips.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        val v4 = ips.filter { !it.contains(':') }
        val v6 = ips.filter { it.contains(':') }

        if (v4.isNotEmpty()) {
            loadTor4(ctx)?.let { t -> v4.forEach { ip -> find4(t, ip)?.let { out[ip] = it } } }
        }
        val missing4 = v4.filter { it !in out }
        if (missing4.isNotEmpty() || v6.isNotEmpty()) {
            if (missing4.isNotEmpty()) full(ctx, "full4.csv", FULL4)?.let { f -> load4(f)?.let { t -> missing4.forEach { ip -> find4(t, ip)?.let { out[ip] = it } } } }
            if (v6.isNotEmpty()) full(ctx, "full6.csv", FULL6)?.let { f -> load6(f)?.let { t -> v6.forEach { ip -> find6(t, ip)?.let { out[ip] = it } } } }
        }
        return out
    }

    /** A bridge's own address, or the address its dial host resolves to; null when there is none. */
    fun addressOf(b: TorBridge): String? {
        if (!b.addressIsPlaceholder && b.family != IpFamily.NAME && b.transport != BridgeTransport.WEBTUNNEL) return b.host
        val host = b.dialHost
        val addr = runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } ?: InetAddress.getByName(host) }.getOrNull()
            ?: return null
        val text = addr.hostAddress?.substringBefore('%') ?: return null
        // Iran's DNS answers blocked names with its block page (10.10.34.x), and a private or
        // local answer is never where a bridge is.
        if (addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) return null
        return text
    }

    // ── tables ─────────────────────────────────────────────────────────────────────────────────

    private fun loadTor4(ctx: Context): Table4? = runCatching {
        ctx.assets.open("geoip").bufferedReader().use { r -> parse4(r.lineSequence()) }
    }.getOrNull()

    private fun load4(f: File): Table4? = runCatching { f.bufferedReader().use { r -> parse4(r.lineSequence()) } }.getOrNull()

    private fun parse4(lines: Sequence<String>): Table4 {
        val s = ArrayList<Long>(300_000); val e = ArrayList<Long>(300_000); val c = ArrayList<String>(300_000)
        for (l in lines) {
            if (l.isEmpty() || l[0] == '#') continue
            val a = l.indexOf(','); val b = l.indexOf(',', a + 1)
            if (a < 0 || b < 0) continue
            val cc = l.substring(b + 1).trim().uppercase()
            if (cc.length != 2) continue
            val start = l.substring(0, a).toLongOrNull() ?: continue
            val end = l.substring(a + 1, b).toLongOrNull() ?: continue
            s += start; e += end; c += cc
        }
        val order = s.indices.sortedBy { s[it] }
        return Table4(LongArray(order.size) { s[order[it]] }, LongArray(order.size) { e[order[it]] }, Array(order.size) { c[order[it]] })
    }

    private fun find4(t: Table4, ip: String): String? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var n = 0L
        for (p in parts) n = (n shl 8) or ((p.toIntOrNull() ?: return null).toLong() and 0xff)
        var lo = 0; var hi = t.start.size - 1; var idx = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (t.start[mid] <= n) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
        return if (idx >= 0 && n <= t.end[idx]) t.cc[idx] else null
    }

    private fun load6(f: File): Table6? = runCatching {
        val sh = ArrayList<Long>(); val sl = ArrayList<Long>(); val eh = ArrayList<Long>(); val el = ArrayList<Long>(); val c = ArrayList<String>()
        f.bufferedReader().use { r ->
            for (l in r.lineSequence()) {
                if (l.isEmpty() || l[0] == '#') continue
                val p = l.split(',')
                if (p.size < 3 || p[2].trim().length != 2) continue
                val a = p[0].toBigIntegerOrNull() ?: continue
                val b = p[1].toBigIntegerOrNull() ?: continue
                sh += a.shiftRight(64).toLong(); sl += a.toLong()
                eh += b.shiftRight(64).toLong(); el += b.toLong()
                c += p[2].trim().uppercase()
            }
        }
        val order = sh.indices.sortedWith { x, y -> cmp(sh[x], sl[x], sh[y], sl[y]) }
        Table6(
            LongArray(order.size) { sh[order[it]] }, LongArray(order.size) { sl[order[it]] },
            LongArray(order.size) { eh[order[it]] }, LongArray(order.size) { el[order[it]] },
            Array(order.size) { c[order[it]] },
        )
    }.getOrNull()

    private fun cmp(ah: Long, al: Long, bh: Long, bl: Long): Int {
        val h = java.lang.Long.compareUnsigned(ah, bh)
        return if (h != 0) h else java.lang.Long.compareUnsigned(al, bl)
    }

    private fun find6(t: Table6, ip: String): String? {
        val bytes = runCatching { (InetAddress.getByName(ip) as? Inet6Address)?.address }.getOrNull() ?: return null
        val n = BigInteger(1, bytes)
        val h = n.shiftRight(64).toLong(); val l = n.toLong()
        var lo = 0; var hi = t.startHi.size - 1; var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cmp(t.startHi[mid], t.startLo[mid], h, l) <= 0) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        return if (idx >= 0 && cmp(h, l, t.endHi[idx], t.endLo[idx]) <= 0) t.cc[idx] else null
    }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    }

    /** The full table on disk: kept for a month, downloaded when absent or stale; null if unreachable. */
    private fun full(ctx: Context, name: String, url: String): File? {
        val f = File(dir(ctx), name)
        if (f.length() > 100_000 && System.currentTimeMillis() - f.lastModified() < FULL_MAX_AGE_MS) return f
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                val tmp = File(f.path + ".tmp")
                r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (tmp.length() < 100_000) { tmp.delete(); throw java.io.IOException("too small") }
                tmp.renameTo(f)
            }
            f
        }.getOrElse { if (f.length() > 100_000) f else null }
    }
}
