package com.mlmvpn.scanner.engines.cloud

import android.util.Log
import okhttp3.Dns
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A self-diagnosing route to `*.workers.dev`.
 *
 * Measured on the phone (2026-09-28): on some networks a Cloudflare IP completes the TLS handshake
 * for a Worker's name and then never answers the HTTP request -- 104.21.4.61 went silent for 8 s
 * while 172.67.154.11, handed out by the same DNS for the same Worker, answered in 1.3 s. DNS picks
 * between them at random and OkHttp never moves on (the connection DID open), so every panel whose
 * configs come from its own Worker -- MLM, Spider, Netra, Gozargah -- timed out at random.
 *
 * This finds the fault and routes around it, instead of waiting it out:
 *  - every address a Worker's name resolves to, plus every address already proven on this network,
 *    is checked with a real request (TLS with the Worker's name, then `HEAD /` -- any status line
 *    counts: 401 and 404 are answers, silence is not);
 *  - the verdict is kept per IP for [TTL_MS] (IP health here is a property of the network, not of
 *    one Worker), and an IP that answered for one Worker is tried for the others;
 *  - the healthy address goes first, and [report] says in words what was found, for the screen.
 */
object WorkerRoute {

    private const val TAG = "WorkerRoute"
    private const val TTL_MS = 10 * 60_000L
    private const val PROBE_MS = 3_500

    private data class Verdict(val ok: Boolean, val at: Long, val stage: String, val ms: Long)

    private val verdicts = ConcurrentHashMap<String, Verdict>()
    private val reports = ConcurrentHashMap<String, Pair<Long, String>>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "worker-route").apply { isDaemon = true } }

    /** Wraps [base] so `*.workers.dev` names get the checked, healthy-first answer. */
    fun dns(base: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (isCloudflareName(hostname)) CfTunnel.capDirect(ordered(hostname, base)) else base.lookup(hostname)
    }

    /**
     * OkHttp's raw-socket factory for clients that talk to Cloudflare: it splits the first TLS
     * ClientHello so the server name is not in one packet for the filter to read. See [CfFront].
     * Pair it with [dns] -- the DNS picks an edge that answers, the split gets the handshake past
     * the name filter -- on every client that adds an account, deploys, or fetches a config.
     */
    fun cfSocketFactory(): javax.net.SocketFactory = CfFront.socketFactory

    /**
     * Names served by any Cloudflare edge, so any edge that answers on this network can carry them.
     * The account API and dashboard joined the Workers here after the Doctor reports (2026-10-03):
     * their DNS answer was dead or bogus on some lines while other edges carried them fine.
     */
    fun isCloudflareName(host: String): Boolean = host.lowercase().let {
        it.endsWith(".workers.dev") || it.endsWith(".pages.dev") ||
            it == "api.cloudflare.com" || it == "dash.cloudflare.com"
    }

    /**
     * HTTP/1.1 only, for clients that talk to Workers. Measured on the phone: over HTTP/2 the
     * request to a healthy IP stalled until the read timeout, while the same request over HTTP/1.1
     * answered in 0.6-0.7 s for all four panels. HTTP/1.1 also cannot coalesce across names.
     */
    val HTTP1: List<okhttp3.Protocol> = listOf(okhttp3.Protocol.HTTP_1_1)

    /** Forget every verdict, for a network change. */
    fun reset() { verdicts.clear() }

    /** What was found for [host] in the last few minutes, in words, or null. */
    fun report(host: String): String? = reports[host.lowercase()]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.second

    /** The most recent report for any Worker, for a caller that does not know which name failed. */
    fun latestReport(): String? = reports.values.filter { System.currentTimeMillis() - it.first < TTL_MS }.maxByOrNull { it.first }?.second

    private fun ordered(host: String, base: Dns): List<InetAddress> {
        // A private or reserved answer is the resolver lying (seen on Rightel and MTN Wi-Fi): dropped.
        val resolved = runCatching { base.lookup(host) }.getOrElse { emptyList() }
            .filterNot { com.mlmvpn.scanner.engines.cfdoctor.CfBogon.isBogon(it.hostAddress?.substringBefore('%').orEmpty()) }
        val now = System.currentTimeMillis()
        // Plus the edges proven on this network for anything Cloudflare: DNS hands out a dead
        // address about half the time, and then these are what carries the request.
        val proven = verdicts.filter { it.value.ok && now - it.value.at < TTL_MS }.keys + com.mlmvpn.scanner.data.CfEdge.good(null, 3)
        // Cloudflare IPv6 too, whether or not the resolver returned it: any v6 edge address serves
        // any Worker name (2026-09-28: IPv4 carried no tunnel data at all while v6 did), so two
        // fresh edge addresses always join the check.
        val v6 = if (com.mlmvpn.scanner.data.CfFamily.hasIpv6RouteCached()) com.mlmvpn.scanner.data.CfFamily.sampleV6(2) else emptyList()
        val candidates = (resolved.mapNotNull { it.hostAddress?.substringBefore('%') } + proven + v6).distinct()
        if (candidates.isEmpty()) {
            // The name did not resolve at all (or only to a bogus address): any working edge serves it.
            val found = com.mlmvpn.scanner.data.CfEdge.find(null, host, 3)
            note(host, if (found.isEmpty()) com.mlmvpn.scanner.store.tr("نام $host ترجمه نشد (DNS).", "$host did not resolve (DNS).")
                else com.mlmvpn.scanner.store.tr("نام $host ترجمه نشد؛ از آی‌پی سالم کلادفلر ${found.first()} رفت.", "$host did not resolve; went through the working Cloudflare edge ${found.first()}."))
            if (found.isEmpty()) return resolved
            return found.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        }

        // Fresh verdicts are reused; the rest are probed together, not one after another.
        val unknown = candidates.filter { ip -> verdicts[ip]?.let { now - it.at >= TTL_MS } ?: true }
        if (unknown.isNotEmpty()) {
            val jobs = unknown.map { ip -> ip to pool.submit<Verdict> { probe(ip, host) } }
            for ((ip, f) in jobs) verdicts[ip] = runCatching { f.get(PROBE_MS + 1_500L, TimeUnit.MILLISECONDS) }
                .getOrElse { Verdict(false, System.currentTimeMillis(), "timeout", PROBE_MS.toLong()) }
        }

        // IPv6 first when it answered: on a filtered IPv4 the small requests pass and the larger
        // ones stall (MLM's config list hung 40 s while its user list answered in 1.5 s).
        var good = candidates.filter { verdicts[it]?.ok == true }
            .sortedWith(compareBy<String>({ if (it.contains(':')) 0 else 1 }, { verdicts[it]!!.ms }))
        // Nothing the resolver or the past offered answers: find edges that do, on this network.
        if (good.isEmpty()) {
            val found = com.mlmvpn.scanner.data.CfEdge.find(null, host, 3)
            found.forEach { verdicts[it] = Verdict(true, System.currentTimeMillis(), "edge", 0) }
            if (found.isNotEmpty()) {
                note(host, com.mlmvpn.scanner.store.tr(
                    "آی‌پی‌های DNS برای $host جواب ندادند؛ از آی‌پی سالم کلادفلر ${found.first()} رفت.",
                    "The DNS addresses for $host did not answer; went through the working Cloudflare edge ${found.first()}."))
                good = found
            }
        }
        val bad = candidates.filter { verdicts[it]?.ok != true }
        val first = resolved.firstOrNull()?.hostAddress
        when {
            good.isEmpty() -> note(host, describe(host, candidates))
            first != null && good.first() != first -> {
                val v = verdicts[first]
                note(host, com.mlmvpn.scanner.store.tr(
                    "آی‌پی $first برای $host ${stageFa(v?.stage)}؛ به ${good.first()} رفت.",
                    "$first for $host ${v?.stage ?: "failed"}; switched to ${good.first()}."))
            }
        }
        // Only the healthy ones when there are any. Returning the silent ones too, even last, let
        // OkHttp coalesce onto an open HTTP/2 connection to a silent IP: every *.workers.dev name of
        // an account shares one certificate, and coalescing only asks that the IP be in this list.
        return (good.ifEmpty { bad }).mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }

    private fun note(host: String, text: String) {
        Log.i(TAG, text)
        reports[host.lowercase()] = System.currentTimeMillis() to text
    }

    private fun stageFa(stage: String?) = when (stage) {
        "tcp" -> "وصل نشد"
        "tls" -> "دست‌دهی TLS نکرد"
        "http" -> "دست‌دهی کرد ولی جواب HTTP نداد"
        else -> "جواب نداد"
    }

    private fun describe(host: String, ips: List<String>): String {
        val parts = ips.joinToString("، ") { ip -> "$ip: " + stageFa(verdicts[ip]?.stage) }
        return com.mlmvpn.scanner.store.tr("هیچ آی‌پی سالمی برای $host نبود ($parts).", "No healthy IP for $host ($parts).")
    }

    /** TCP, then TLS with [host] as SNI, then `HEAD /`: the first stage that fails names the fault. */
    private fun probe(ip: String, host: String): Verdict {
        val t0 = System.currentTimeMillis()
        fun v(ok: Boolean, stage: String) = Verdict(ok, System.currentTimeMillis(), stage, System.currentTimeMillis() - t0)
        val raw = java.net.Socket()
        try {
            try { raw.connect(InetSocketAddress(ip, 443), PROBE_MS) } catch (e: Exception) { return v(false, "tcp") }
            raw.soTimeout = PROBE_MS
            val ssl = try {
                val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
                ctx.init(null, null, null)
                (ctx.socketFactory.createSocket(raw, host, 443, true) as javax.net.ssl.SSLSocket).also { s ->
                    runCatching { s.sslParameters = s.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName(host)) } }
                    s.startHandshake()
                }
            } catch (e: Exception) { return v(false, "tls") }
            return try {
                ssl.outputStream.apply { write("HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: mlmvpn\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                val line = ssl.inputStream.bufferedReader().readLine().orEmpty()
                v(line.startsWith("HTTP/"), "http")
            } catch (e: Exception) { v(false, "http") } finally { runCatching { ssl.close() } }
        } finally {
            runCatching { raw.close() }
        }
    }
}
