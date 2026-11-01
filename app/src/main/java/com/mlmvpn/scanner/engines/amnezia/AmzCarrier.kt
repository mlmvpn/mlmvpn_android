package com.mlmvpn.scanner.engines.amnezia

import android.content.Context
import android.util.Base64
import android.util.Log
import com.mlmvpn.scanner.engines.cfdoctor.NativeProbe
import com.mlmvpn.scanner.engines.flux.FluxNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * «از راه کلادفلر» for the free WireGuard servers: Cloudflare WARP carries their UDP.
 *
 * On many Iranian lines UDP to a foreign server is dropped while UDP to Cloudflare's WARP edge gets
 * through. Measured on the user's line (2026-10-07), the same 40 public WireGuard servers: 8 answered
 * straight over the line, 21 from inside WARP -- each from its own country (US, AU, NZ, CH, IS, SG,
 * PA, CY, HK...), at 10.7 Mbit/s through a Swiss one against 13.5 for WARP alone. WireGuard stays end
 * to end with the server; WARP only carries its packets.
 *
 * The shape that carries data: one Xray core holds the WireGuard session to Cloudflare (the
 * carrier) and, per server, a loopback dokodemo-door ("hairpin"). The server's own WireGuard
 * outbound dials 127.0.0.1:door, and the door hands each datagram to the carrier, which sends it on
 * from inside WARP. Dialling the server's outbound through the carrier directly (`sockopt.dialerProxy`)
 * was tried first and carried nothing at all -- 0 of the same 40.
 *
 * The carrier needs a WARP identity and an endpoint the line lets through:
 *  - the endpoint is searched per network with the public WARP accounts already in the pool, one
 *    account per endpoint so no test takes another's session, and remembered for days;
 *  - the identity is the app's own when it has one: registered once THROUGH a working public
 *    session, since from inside Cloudflare its API is never filtered. A public key is shared with
 *    everyone using the same list, and Cloudflare keeps one session per key, so two users on one
 *    account would take the session from each other. Until an own identity exists, a public account
 *    that just worked is used, picked at random among the best so users spread over them.
 */
object AmzCarrier {

    private const val TAG_LOG = "AmzCarrier"

    /** The carrier's outbound, and the junk in front of its handshake. */
    const val TAG = "amz-cf"
    const val NOISE_TAG = "amz-cf-n"

    /** Read by MyVpnService: an engine's own config (no app-side rewrites), on an IPv4-only TUN. */
    const val REMARKS = "mlm-amnezia-cf"

    /**
     * The server's WireGuard inside WARP's 1280: 1200 + 32 (WireGuard) + 28 (IPv4 + UDP) still fits
     * in one carrier packet, so nothing is fragmented inside the carrier.
     */
    const val INNER_MTU = 1200

    private const val PREFS = "amnezia_carrier"
    private const val PROBE_TIMEOUT_MS = 6_000
    /** A carrier found on a network is reused this long before it is looked for again. */
    private const val CACHE_MS = 20 * 60_000L
    /** After a search found nothing, how long before another is made on the same network. */
    private const val FAILED_RETRY_MS = 5 * 60_000L
    /** A remembered endpoint is tried first for this long. */
    private const val ENDPOINT_MEMORY_MS = 3L * 24 * 3_600_000
    /** After the own identity could not be registered, how long before it is tried again. */
    private const val OWN_RETRY_MS = 6 * 3_600_000L
    private const val MAX_CANDIDATES = 16

    /** WARP's anycast edges. IPv6 first where the line has it: on the user's line only it carried. */
    private val V6_EDGES = listOf("2606:4700:d0::a29f:c001")
    private val V4_EDGES = listOf("162.159.192.1", "162.159.193.10", "162.159.195.1", "188.114.97.1", "188.114.96.1")
    private val PORTS = listOf(2408, 500, 1701, 4500)

    /** A way into Cloudflare: [cfg] is the identity with its endpoint; [own] when the identity is the app's. */
    data class Carrier(val cfg: WgConfig, val own: Boolean, val net: String, val at: Long) {
        /** For the log: which endpoint and whose key, never the key. */
        val label: String get() = "${cfg.endpoint()} ${if (own) "own" else "public"}"
    }

    private val lock = Mutex()
    @Volatile private var cached: Carrier? = null
    @Volatile private var failedNet: String? = null
    @Volatile private var failedAt = 0L

    // ---------------------------------------------------------------- the config pieces

    /** The carrier as Xray outbounds: its WireGuard to Cloudflare, dialling through the junk. */
    fun outbounds(c: Carrier): List<JSONObject> {
        val noise = c.cfg.noiseOutbound(NOISE_TAG, true)
        return listOfNotNull(c.cfg.xrayOutbound(TAG, if (noise != null) NOISE_TAG else null), noise)
    }

    /** [server] as reached through a door at [doorPort]: same keys, loopback endpoint, smaller MTU. */
    fun inner(server: WgConfig, doorPort: Int): WgConfig =
        server.copy(mtu = minOf(server.mtu ?: INNER_MTU, INNER_MTU), endpointHost = "127.0.0.1", endpointPort = doorPort)

    /** A loopback UDP door of the core that sends everything it gets on to [host]:[hostPort] (routed to [TAG]). */
    fun door(tag: String, port: Int, host: String, hostPort: Int): JSONObject = JSONObject().put("tag", tag)
        .put("listen", "127.0.0.1").put("port", port).put("protocol", "dokodemo-door")
        .put("settings", JSONObject().put("address", host).put("port", hostPort).put("network", "udp"))

    /**
     * The whole tunnel for MyVpnService: everything into [server]'s WireGuard, whose packets go out
     * through a door to the carrier. DNS is asked over DoH through the tunnel; no IPv6 anywhere (the
     * remarks keep the TUN IPv4-only). [localPort] is the app's SOCKS port; the HTTP inbound beside
     * it is the app-wide status probe, through which the engine checks the tunnel carries data.
     */
    fun tunnelConfig(server: WgConfig, carrier: Carrier, localPort: Int, doorPort: Int): String {
        val doorTag = "amz-door"
        val dnsTag = "amz-dns"
        fun rule() = JSONObject().put("type", "field")
        val outbounds = JSONArray()
        outbounds.put(inner(server, doorPort).xrayOutbound("proxy", null))
        outbounds(carrier).forEach { outbounds.put(it) }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        outbounds.put(JSONObject().put("tag", "blocked").put("protocol", "blackhole"))
        return JSONObject()
            .put("remarks", REMARKS)
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray()
                .put(JSONObject().put("tag", "socks").put("port", localPort).put("listen", "127.0.0.1").put("protocol", "mixed")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", true)))
                .put(JSONObject().put("tag", "http").put("port", localPort + com.mlmvpn.scanner.utils.LocalPort.PROBE_OFFSET)
                    .put("listen", "127.0.0.1").put("protocol", "http"))
                .put(door(doorTag, doorPort, server.endpointHost, server.endpointPort)))
            .put("outbounds", outbounds)
            .put("dns", JSONObject().put("tag", dnsTag)
                .put("servers", JSONArray()
                    .put(JSONObject().put("address", "https://8.8.8.8/dns-query"))
                    .put(JSONObject().put("address", "https://1.1.1.1/dns-query")))
                .put("queryStrategy", "UseIPv4"))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()
                // The door first: the server's packets ride the carrier, never the tunnel itself.
                .put(rule().put("inboundTag", JSONArray().put(doorTag)).put("outboundTag", TAG))
                .put(rule().put("port", "53").put("outboundTag", "dns-out"))
                .put(rule().put("inboundTag", JSONArray().put(dnsTag)).put("outboundTag", "proxy"))
                .put(rule().put("ip", JSONArray(BLOCK_PAGE)).put("outboundTag", "blocked"))
                .put(rule().put("ip", JSONArray(LAN)).put("outboundTag", "direct"))
                .put(rule().put("network", "tcp,udp").put("outboundTag", "proxy"))))
            .toString()
    }

    /**
     * [server] dialled straight by Xray rather than by the AmneziaWG core: the same WireGuard with
     * the same junk packets in front, plus the local SOCKS/HTTP listener that Local Network sharing
     * hands to other devices -- the AmneziaWG core has no listener at all, so with sharing on its
     * tunnel was up and the share behind it dialled nothing. Same remarks as [tunnelConfig], so
     * MyVpnService treats both alike (own routes and DNS, IPv4 only).
     */
    fun directTunnelConfig(server: WgConfig, localPort: Int, obfuscate: Boolean): String {
        val dnsTag = "amz-dns"
        fun rule() = JSONObject().put("type", "field")
        val noise = server.noiseOutbound("amz-noise", obfuscate)
        val outbounds = JSONArray()
        outbounds.put(server.xrayOutbound("proxy", noise?.optString("tag")))
        noise?.let { outbounds.put(it) }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        outbounds.put(JSONObject().put("tag", "blocked").put("protocol", "blackhole"))
        return JSONObject()
            .put("remarks", REMARKS)
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray()
                .put(JSONObject().put("tag", "socks").put("port", localPort).put("listen", "127.0.0.1").put("protocol", "mixed")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", true)))
                .put(JSONObject().put("tag", "http").put("port", localPort + com.mlmvpn.scanner.utils.LocalPort.PROBE_OFFSET)
                    .put("listen", "127.0.0.1").put("protocol", "http")))
            .put("outbounds", outbounds)
            .put("dns", JSONObject().put("tag", dnsTag)
                .put("servers", JSONArray()
                    .put(JSONObject().put("address", "https://8.8.8.8/dns-query"))
                    .put(JSONObject().put("address", "https://1.1.1.1/dns-query")))
                .put("queryStrategy", "UseIPv4"))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()
                .put(rule().put("port", "53").put("outboundTag", "dns-out"))
                .put(rule().put("inboundTag", JSONArray().put(dnsTag)).put("outboundTag", "proxy"))
                .put(rule().put("ip", JSONArray(BLOCK_PAGE)).put("outboundTag", "blocked"))
                .put(rule().put("ip", JSONArray(LAN)).put("outboundTag", "direct"))
                .put(rule().put("network", "tcp,udp").put("outboundTag", "proxy"))))
            .toString()
    }

    /** Also Outline's Shadowsocks through WARP ([com.mlmvpn.scanner.engines.outline.OutlineCf]): same shape, same treatment. */
    fun isCarrierConfig(remarks: String?): Boolean =
        remarks == REMARKS || remarks == com.mlmvpn.scanner.engines.outline.OutlineCf.REMARKS

    private val LAN = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "100.64.0.0/10")
    /** Iran's filtering answers hijacked connections from here (the block page): fail fast. */
    private val BLOCK_PAGE = listOf("10.10.34.0/24")

    // ---------------------------------------------------------------- finding a carrier

    /** Drops what was found on [net]: the next test or connect searches again. */
    fun forget(net: String) {
        if (cached?.net == net) cached = null
    }

    /**
     * A carrier for [here], or null when nothing gets into Cloudflare on this line. Seconds the first
     * time on a network (one probe core); then reused. Runs probe cores in the `:xprobe` process,
     * so the caller must not have one of its own running.
     */
    suspend fun acquire(context: Context, here: FluxNet.Here, tester: AmzTester, warpPool: List<AmzServer>): Carrier? = lock.withLock {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        cached?.takeIf { it.net == here.key && now - it.at < CACHE_MS }?.let { return@withLock it }
        if (failedNet == here.key && now - failedAt < FAILED_RETRY_MS) return@withLock null

        // 1. Which endpoints carry WARP on this line: one public account per endpoint, side by side.
        val hasV6 = FluxNet.hasV6(app, here.network)
        val accounts = warpPool.filter { it.warp && !it.needsAwgCore }.mapNotNull { s -> WgConfig.parseConf(s.raw)?.let { s to it } }.shuffled()
        val endpoints = candidates(hasV6, remembered(app, here.key), accounts.map { it.second.endpoint() }).take(minOf(MAX_CANDIDATES, accounts.size))
        if (endpoints.isEmpty()) { Log.i(TAG_LOG, "no WARP account to search with"); return@withLock fail(here.key, now) }
        val pairs = endpoints.mapIndexed { i, ep -> accounts[i].first to withEndpoint(accounts[i].second, ep) }
        val delays = tester.probeWarp(pairs, PROBE_TIMEOUT_MS)
        val working = pairs.indices.filter { delays[it] != null }.sortedBy { delays[it]!! }
        Log.i(TAG_LOG, "search on ${if (hasV6) "v4+v6" else "v4"}: ${working.size} of ${pairs.size} endpoints carry data" +
            (working.firstOrNull()?.let { " (best ${pairs[it].second.endpoint()} ${delays[it]} ms)" } ?: ""))
        if (working.isEmpty()) return@withLock fail(here.key, now)
        val bestEp = pairs[working.first()].second.endpoint()
        remember(app, here.key, bestEp)

        // 2. Whose key: the app's own when it works here, else a public one that just did.
        val own = ownIdentity(app) ?: registerOwn(app, pairs[working.first()].second)
        if (own != null) {
            for (i in working.take(2)) {
                val cfg = withEndpoint(own, pairs[i].second.endpoint())
                val ok = tester.probeWarp(listOf(ownServer(cfg) to cfg), PROBE_TIMEOUT_MS).firstOrNull() != null
                if (ok) return@withLock Carrier(cfg, own = true, net = here.key, at = now).also { cached = it; Log.i(TAG_LOG, "carrier ${it.label}") }
            }
            Log.i(TAG_LOG, "own identity carried nothing here; a public account instead")
        }
        val best = delays[working.first()]!!
        // Random among the accounts nearly as fast as the best, so users spread over the list.
        val pick = working.filter { delays[it]!! <= best * 3 / 2 + 100 }.take(3).random()
        Carrier(pairs[pick].second, own = false, net = here.key, at = now).also { cached = it; Log.i(TAG_LOG, "carrier ${it.label}") }
    }

    private fun fail(net: String, now: Long): Carrier? { failedNet = net; failedAt = now; return null }

    /** Endpoints in the order worth trying: what worked here, WARP's own edges, then the accounts' clean addresses. */
    internal fun candidates(hasV6: Boolean, remembered: String?, accountEndpoints: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        remembered?.let { out += it }
        if (hasV6) V6_EDGES.forEach { h -> PORTS.forEach { p -> out += "[$h]:$p" } }
        V4_EDGES.forEachIndexed { i, h -> out += "$h:${PORTS[i % PORTS.size]}" }
        accountEndpoints.filter { !it.startsWith("[") || hasV6 }.forEach { out += it }
        return out.toList()
    }

    internal fun withEndpoint(c: WgConfig, ep: String): WgConfig {
        val host = ep.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = ep.substringAfterLast(':').toIntOrNull() ?: return c
        return c.copy(endpointHost = host, endpointPort = port, keepalive = c.keepalive ?: 25)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun remembered(context: Context, net: String): String? = prefs(context).getString("ep|$net", null)?.split('|')
        ?.takeIf { it.size == 2 && System.currentTimeMillis() - (it[1].toLongOrNull() ?: 0L) in 0..ENDPOINT_MEMORY_MS }?.first()

    private fun remember(context: Context, net: String, ep: String) {
        prefs(context).edit().putString("ep|$net", "$ep|${System.currentTimeMillis()}").apply()
    }

    // ---------------------------------------------------------------- the app's own identity

    /** A pool-shaped stand-in for the own identity, so the tester can measure it like an account. */
    private fun ownServer(cfg: WgConfig) = AmzServer(
        id = "carrier-own", kind = AmzKind.WG, raw = cfg.toConf(), name = "carrier", host = cfg.endpointHost, port = cfg.endpointPort,
        country = null, countryFrom = CountryFrom.NONE, sourceId = "carrier", addedAt = 0L, warp = true,
    )

    /** The registered identity (its key opened from the Keystore), without an endpoint of its own. */
    private fun ownIdentity(context: Context): WgConfig? {
        val raw = prefs(context).getString("own", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val key = com.mlmvpn.scanner.data.SecureStore.open(o.getString("k")) ?: return null
            WgConfig(
                privateKey = key,
                addresses = listOfNotNull(o.optString("v4").ifBlank { null }?.let { "$it/32" }, o.optString("v6").ifBlank { null }?.let { "$it/128" }),
                mtu = 1280,
                publicKey = o.getString("p"),
                endpointHost = "162.159.192.1", endpointPort = 2408,
                keepalive = 25,
                reserved = o.optString("r").split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 3 },
            )
        }.getOrNull()
    }

    /**
     * Registers the app's own WARP identity through [via] -- a public account's session that just
     * carried data: the API is asked from inside Cloudflare, where no filter sits. Null (and not
     * retried for hours) when that fails.
     */
    private suspend fun registerOwn(context: Context, via: WgConfig): WgConfig? {
        val p = prefs(context)
        if (System.currentTimeMillis() - p.getLong("own_fail", 0L) in 0..OWN_RETRY_MS) return null
        val json = JSONObject().put("outbounds", JSONArray().also { a -> outbounds(Carrier(via, false, "", 0L)).forEach { a.put(it) } }).toString()
        val made = runCatching {
            NativeProbe(context).withCore(json) { port ->
                runInterruptible(Dispatchers.IO) {
                    com.mlmvpn.core.warp.WarpIdRelay.registerThrough(context, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                }
            }.second
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG_LOG, "own identity: ${it.javaClass.simpleName}: ${it.message?.take(80)}") }
            .getOrNull()
        if (made == null) {
            p.edit().putLong("own_fail", System.currentTimeMillis()).apply()
            return null
        }
        val t = com.mlmvpn.scanner.engines.mae.egress.MaeWarp.parseToml(made.toml)
        val key = t["wg_private_key"].orEmpty()
        val peer = t["wg_peer_public_key"].orEmpty()
        if (key.isBlank() || peer.isBlank()) { p.edit().putLong("own_fail", System.currentTimeMillis()).apply(); return null }
        val reserved = t["client_id"]?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { Base64.decode(id, Base64.DEFAULT).take(3).joinToString(",") { (it.toInt() and 0xFF).toString() } }.getOrNull()
        }.orEmpty()
        val sealed = withContext(Dispatchers.IO) { com.mlmvpn.scanner.data.SecureStore.seal(key) } ?: return null
        p.edit().putString("own", JSONObject().put("k", sealed).put("p", peer).put("v4", t["ipv4"].orEmpty())
            .put("v6", t["ipv6"].orEmpty()).put("r", reserved).toString()).remove("own_fail").apply()
        Log.i(TAG_LOG, "own WARP identity registered from inside Cloudflare")
        return ownIdentity(context)
    }
}
