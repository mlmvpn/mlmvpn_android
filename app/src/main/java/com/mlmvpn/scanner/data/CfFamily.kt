package com.mlmvpn.scanner.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Which address family of Cloudflare's edge carries tunnel traffic on THIS network, measured, and
 * a source of IPv6 edge addresses.
 *
 * Measured on the phone, 2026-09-28, after Iran's filtering was tightened: through Cloudflare IPv4
 * a Worker tunnel opened (TLS, then the WebSocket upgrade) and carried no data at all -- not a byte
 * back for plain HTTP or TLS inside it -- while the same config on Cloudflare IPv6 got a ServerHello
 * from Google in 1.1-1.9 s, and 48 of 54 random addresses across 16 Cloudflare v6 prefixes answered.
 * So nothing may assume IPv4: the families are measured and whatever works is used, and when the
 * filter moves back the measurement follows it.
 *
 * The verdict is kept per network ([networkKey]) for [TTL_MS] and shared: the scanner sets it, and
 * the combine, the Worker route, the arena and the engines read it.
 */
object CfFamily {

    private const val TAG = "CfFamily"
    private const val TTL_MS = 20 * 60_000L

    data class Verdict(val v4: Boolean?, val v6: Boolean?, val at: Long, val net: String)

    @Volatile private var last: Verdict? = null

    /**
     * Cloudflare's IPv6 prefixes that served Workers on the phone (2026-09-28), each a /48. Any
     * address inside one reaches the same edge -- the interface id is free -- which is why v6 needs
     * no scan in the v4 sense, only a pick and a check.
     */
    val V6_PREFIXES = listOf(
        "2606:4700:3030", "2606:4700:3031", "2606:4700:3032", "2606:4700:3033",
        "2606:4700:3034", "2606:4700:3035", "2606:4700:3036", "2606:4700:3037",
        "2606:4700:10", "2606:4700:20", "2606:4700:7", "2606:4700:4400",
        "2803:f800:50", "2a06:98c1:3120", "2a06:98c1:3121", "2400:cb00:2049",
    )

    /** [n] random edge addresses, spread across [V6_PREFIXES] (a prefix that is closed is simply skipped by the scan). */
    fun sampleV6(n: Int, prefixes: List<String> = V6_PREFIXES): List<String> {
        val rnd = java.security.SecureRandom()
        return (0 until n).map { i ->
            val p = prefixes[i % prefixes.size]
            p + ":" + (0 until 5).joinToString(":") { Integer.toHexString(rnd.nextInt(0x10000)) }
        }.shuffled()
    }

    fun isV6(ip: String) = ip.contains(':')

    fun networkKey(context: Context): String = com.mlmvpn.scanner.engines.github.GtCleanIp.networkKey(context)

    /** Debug labs only: behave as on a line without IPv6. */
    @Volatile var testNoV6 = false

    /** Does this phone have a global IPv6 address, and does it reach Cloudflare over it? */
    fun hasIpv6Route(): Boolean {
        if (testNoV6) return false
        val global = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
                .any { it is Inet6Address && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isLoopbackAddress &&
                    (it.address[0].toInt() and 0xfe) != 0xfc }
        }.getOrDefault(false)
        if (!global) return false
        // Android refuses a socket on the main thread, and that refusal is not "no IPv6": there the
        // address is all that can be known (the cached form never keeps such an answer).
        if (onMainThread()) return true
        return runCatching { Socket().use { it.connect(InetSocketAddress("2606:4700:4700::1111", 443), 2_500); true } }.getOrDefault(false)
    }

    private fun onMainThread(): Boolean =
        runCatching { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() }.getOrDefault(false)

    @Volatile private var v6RouteAt = 0L
    @Volatile private var v6Route = false

    /**
     * [hasIpv6Route], remembered for a minute: cheap enough for every lookup. Only a real
     * measurement is remembered -- one taken on the main thread used to read "no IPv6" (the socket
     * was refused) and every connect for the next minute then scanned IPv4 alone.
     */
    fun hasIpv6RouteCached(): Boolean {
        if (testNoV6) return false
        val now = System.currentTimeMillis()
        if (now - v6RouteAt <= 60_000L) return v6Route
        if (onMainThread()) return hasIpv6Route()
        v6Route = hasIpv6Route(); v6RouteAt = now
        return v6Route
    }

        /**
     * Which family the WARP-family cores should scan for endpoints: the user's explicit "v6"/"both"
     * stands; otherwise it is chosen here -- IPv4 without an IPv6 route, IPv6 when Cloudflare IPv4
     * is measured dead on this network, and both when nothing is known (the filter of 2026-09-28
     * left IPv4 carrying nothing, and a v4-only scan then had nothing to find).
     */
    fun warpScanFamily(context: Context, stored: String?): String {
        if (stored == "v6" || stored == "both") return stored
        // Both whenever there is an IPv6 route -- never v6 alone on the CDN's verdict: WARP runs on
        // UDP to other Cloudflare ranges, and on the network where CDN IPv4 carried nothing WARP
        // IPv4 still worked (2026-09-28). Scanning both lets the core use whichever answers, today
        // and when IPv4 comes back.
        return if (hasIpv6RouteCached()) "both" else "v4"
    }

    /** [warpScanFamily] for code with no Context: the latest verdict, whatever network it was on, if fresh. */
    fun warpScanFamilyAnyNet(): String {
        return if (hasIpv6RouteCached()) "both" else "v4"
    }

    /** The current verdict for this network, when fresh. */
    fun current(context: Context): Verdict? =
        last?.takeIf { it.net == networkKey(context) && System.currentTimeMillis() - it.at < TTL_MS }

    fun record(context: Context, v4: Boolean?, v6: Boolean?) {
        val v = Verdict(v4, v6, System.currentTimeMillis(), networkKey(context))
        last = v
        Log.i(TAG, "verdict on ${v.net}: IPv4 ${v4 ?: "?"}, IPv6 ${v6 ?: "?"}")
    }

    /** True when IPv6 should be tried first here: IPv4 measured dead and IPv6 measured alive. */
    fun preferV6(context: Context): Boolean = current(context)?.let { it.v4 == false && it.v6 == true } == true

    /**
     * Measure both families with the real test (Xray through [baseConfig]): up to [perFamily]
     * addresses each that already passed the cheap check. Records and returns the verdict.
     */
    suspend fun measure(
        context: Context,
        scanner: CloudflareScanner,
        baseConfig: String,
        port: Int,
        v4Alive: List<String>,
        v6Alive: List<String>,
        perFamily: Int = 2,
    ): Verdict {
        suspend fun anyWorks(ips: List<String>): Boolean? {
            if (ips.isEmpty()) return null
            for (ip in ips.take(perFamily)) if (scanner.realDelayTest(ip, baseConfig, context, port) > 0f) return true
            return false
        }
        // Both at once: a dead family costs its timeouts, and there is no reason to pay them in turn.
        val (v4, v6) = kotlinx.coroutines.coroutineScope {
            val a = async { anyWorks(v4Alive) }
            val b = async { anyWorks(v6Alive) }
            a.await() to b.await()
        }
        record(context, v4, v6)
        return current(context)!!
    }
}
