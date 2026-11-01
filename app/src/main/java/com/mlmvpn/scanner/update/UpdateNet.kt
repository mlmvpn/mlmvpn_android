package com.mlmvpn.scanner.update

import android.content.Context
import com.mlmvpn.core.tunnel.TunnelStatus
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.utils.LocalPort
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

// =================================================================================================
// Reaching GitHub from a network that does not want the app to.
//
// The update screen reported, verbatim:
//
//     Unable to resolve host "github.com": No address associated with hostname
//
// -- while the version check beside it had worked and was showing the release name and its size.
// Both facts matter. The check talks to `api.github.com` and the download to `github.com`, and on
// an Iranian line those two names are not treated the same: one resolved and the other was
// answered with nothing at all. So the app knew an update existed and could not fetch a byte of
// it, which is the worst of the three possible states.
//
// Two things are wrong and both are fixed here.
//
// **The name is resolved by the network's own resolver.** That resolver is the thing doing the
// blocking, so asking it is asking the wrong party. Every request from this file resolves over
// DNS-over-HTTPS instead, to a resolver addressed BY IP so that resolving the resolver is not
// itself a DNS lookup.
//
// **The download does not use the tunnel, even when one is up.** It cannot by accident: the VPN
// services exclude this app from their own tunnel -- they must, or the tunnel's traffic would
// re-enter the interface it is feeding -- so an ordinary socket from here leaves on the carrier's
// link however healthy the tunnel is. The only way in is the local proxy the engine publishes,
// and that is what this points at whenever there is one.
// =================================================================================================

object UpdateNet {

    /**
     * Resolvers tried in order, addressed by IP.
     *
     * By IP is the whole point: a DoH resolver named by hostname would need a DNS lookup to reach,
     * which is the lookup that does not work. Both of these serve a certificate valid for the
     * address itself, so TLS verifies without a name.
     *
     * Cloudflare first because its JSON endpoint is the one the rest of the app already defaults
     * to; Google second because the two are rarely blocked in the same way at the same time.
     */
    private val DOH_ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve",
    )

    /**
     * A plain client used ONLY to ask the DoH resolvers.
     *
     * It must not carry the custom [Dns] below or resolving a name would require resolving a name.
     * It needs none: every endpoint above is already an address.
     */
    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /** Resolved addresses, so a download does not re-ask for every connection it opens. */
    private val cache = HashMap<String, List<InetAddress>>()

    /**
     * Is this already an address rather than a name?
     *
     * Tested by SHAPE, never by handing it to `InetAddress.getByName` and seeing whether it
     * throws. That call does a real system lookup for anything that is not a literal -- which is
     * the exact lookup this class exists to avoid, and it would have quietly answered every
     * request with the poisoned result before DoH was ever consulted.
     */
    private val LITERAL = Regex("""^(\d{1,3}\.){3}\d{1,3}$|^[0-9a-fA-F:]+:[0-9a-fA-F:.]*$""")

    private val doh = Dns { hostname ->
        // An address given as an address needs nothing done to it.
        if (LITERAL.matches(hostname)) {
            runCatching { return@Dns listOf(InetAddress.getByName(hostname)) }
        }

        synchronized(cache) { cache[hostname] }?.let { return@Dns it }

        val resolved = resolveOverHttps(hostname)
        if (resolved.isNotEmpty()) {
            synchronized(cache) { cache[hostname] = resolved }
            return@Dns resolved
        }

        // The system resolver last rather than not at all: on a network that is NOT interfering it
        // is correct and instant, and on one that is, we have already tried the alternative.
        Dns.SYSTEM.lookup(hostname)
    }

    /**
     * Every address both resolvers know, in order, deduplicated.
     *
     * Merged rather than "first one that answers", because the answers differ and not every
     * answer works: measured on the line this was reported from, one resolver's address connected
     * in 2.8 seconds and the other's took 15 -- right at the download's connect timeout. OkHttp
     * walks the list until one connects, so handing it both gives it somewhere to fail over to.
     * The cost is one extra small request per hostname, once, since the result is cached.
     */
    private fun resolveOverHttps(hostname: String): List<InetAddress> {
        val all = mutableListOf<InetAddress>()
        for (endpoint in DOH_ENDPOINTS) {
            val out = runCatching {
                val url = "$endpoint?name=$hostname&type=A"
                val request = Request.Builder()
                    .url(url)
                    .header("accept", "application/dns-json")
                    .build()
                bootstrap.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val json = JSONObject(response.body?.string().orEmpty())
                    val answers = json.optJSONArray("Answer") ?: return@use emptyList()
                    (0 until answers.length()).mapNotNull { i ->
                        val entry = answers.optJSONObject(i) ?: return@mapNotNull null
                        // Type 1 is A. A CNAME (5) in the chain is normal and carries no address;
                        // taking its data as one would hand OkHttp a hostname where it wants bytes.
                        if (entry.optInt("type") != 1) return@mapNotNull null
                        runCatching { InetAddress.getByName(entry.optString("data")) }.getOrNull()
                    }
                }
            }.getOrDefault(emptyList())
            all += out
        }
        return all.distinct()
    }

    /**
     * The engine's local proxy, or null when nothing is running.
     *
     * Same question [com.mlmvpn.scanner.lan.LanShare] answers for sharing, asked here for the same
     * reason: it is the only door into the tunnel from inside this process.
     */
    fun tunnelProxy(context: Context): Proxy? {
        val port = when {
            MyVpnService.isRunning -> MyVpnService.runtimeControlSocksPort.takeIf { it > 0 }
                ?: MyVpnService.runtimeSocksPort.takeIf { it > 0 } ?: LocalPort.get(context)
            else -> TunnelStatus.activeSocksPort
        }
        if (port <= 0) return null
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
    }

    /**
     * A client for one update request, built fresh so it sees the tunnel as it is right now.
     *
     * Not a cached singleton on purpose: a user whose first attempt failed will turn a tunnel on
     * and press the button again, and a client built once at class-load would still be going
     * around it.
     */
    fun client(
        context: Context,
        connectSeconds: Long,
        readSeconds: Long,
        /**
         * A panel's Worker (deploy, configs) goes DIRECT and falls back to the tunnel only when it cannot
         * connect (engines/cloud/CfTunnel.kt) — instead of through the tunnel whenever one is on, which is
         * right for GitHub and the update download and wrong for Cloudflare, where direct works for most.
         */
        directFirst: Boolean = false,
        /**
         * Bound the whole call (below). False for a file download: there the ceiling cut every file
         * that took longer than connect + read + 20 s -- 95 s for the app's own update, so its APK
         * needed 6 Mbit/s and more to finish -- which is what users reported as "it reaches the end
         * (or the middle), drops, and starts again from zero". A download keeps the read timeout,
         * which still catches a connection that stopped sending.
         */
        wholeCallTimeout: Boolean = true,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectSeconds, TimeUnit.SECONDS)
        .readTimeout(readSeconds, TimeUnit.SECONDS)
        .writeTimeout(readSeconds, TimeUnit.SECONDS)
        // An overall ceiling on the whole call, so a half-open SOCKS proxy or a stalled read cannot
        // hang a registration or an update check indefinitely (the per-step timeouts alone did not
        // bound the total). Comfortably above connect + read so a legitimately slow tunnel still works.
        .apply { if (wholeCallTimeout) callTimeout(connectSeconds + readSeconds + 20, TimeUnit.SECONDS) }
        // Worker names get the checked, healthy-first answer (com.mlmvpn.scanner.engines.cloud.WorkerRoute); everything else as before.
        .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns(doh))
        .protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory())
        .apply {
            if (directFirst) proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector())
            else tunnelProxy(context)?.let { proxy(it) }
        }
        .build()

    /** Drops resolved addresses, for a retry after the network changed. */
    fun forgetResolved() {
        synchronized(cache) { cache.clear() }
    }
}
