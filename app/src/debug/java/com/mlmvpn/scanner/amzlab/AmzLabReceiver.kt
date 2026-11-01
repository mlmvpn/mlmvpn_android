package com.mlmvpn.scanner.amzlab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.mlmvpn.core.warp.AwgHostClient
import com.mlmvpn.core.warp.XrayProbeService
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.engines.amnezia.AmzCarrier
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.engines.amnezia.AmzKind
import com.mlmvpn.scanner.engines.amnezia.AmzTester
import com.mlmvpn.scanner.engines.amnezia.AmzServer
import com.mlmvpn.scanner.engines.amnezia.WgConfig
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.outbound.FluxOutbounds
import com.mlmvpn.scanner.engines.flux.core.parse.FluxLinkParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket

/**
 * Debug-only lab for «Amnezia through Cloudflare»: do the public WireGuard and Hysteria2 servers
 * that answer nothing directly on this line answer when their UDP rides inside Cloudflare WARP?
 *
 *   adb shell am broadcast -n com.mlmvpn.scanner/.amzlab.AmzLabReceiver --es mode outer
 *   adb shell am broadcast -n com.mlmvpn.scanner/.amzlab.AmzLabReceiver --es mode chain --es kind wg --ei count 40
 *     extras: ep=<host:port> (the WARP endpoint to use instead of the seed's own), seed=<shuffle seed>
 *
 * Logs go to tag AmzLab: server kind, host, delay, exit country. No keys.
 */
class AmzLabReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("stop", false)) { job?.cancel(); return }
        if (job?.isActive == true) { log("busy"); return }
        val app = context.applicationContext
        val mode = intent.getStringExtra("mode") ?: "chain"
        val count = intent.getIntExtra("count", 40).coerceIn(1, 300)
        val kind = intent.getStringExtra("kind") ?: "wg"
        val ep = intent.getStringExtra("ep")
        val seed = intent.getIntExtra("seed", 1)
        val speed = intent.getIntExtra("speed", 3)
        val how = intent.getStringExtra("how") ?: "hairpin"
        // Only these servers ("host" or "host:port", comma separated).
        val hosts = intent.getStringExtra("hosts")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        LOG_LEVEL = intent.getStringExtra("log") ?: "none"
        job = scope.launch {
            try {
                if (MyVpnService.isRunning && mode != "disconnect") { log("VPN active: lab skipped"); return@launch }
                AmzEngine.init(app)
                when (mode) {
                    "outer" -> { val o = outers(app, count, ep); log("outer best=${o?.first?.host ?: "none"}") }
                    "chain" -> chain(app, count, kind, ep, seed, speed, how, hosts)
                    // A real connect (the screen's own path) to the first server matching `hosts`.
                    "connect" -> {
                        val s = AmzEngine.store.current.servers.firstOrNull { s -> hosts != null && ("${s.host}:${s.port}" in hosts || s.host in hosts) }
                        log("connect ${s?.host}:${s?.port} via=${s?.let { AmzEngine.store.current.statsFor(FluxNet.current(app).key)[it.id]?.via }}")
                        if (s != null) AmzEngine.connectAsync(app, serverId = s.id).join()
                        log("connect state ${AmzEngine.connect.value}")
                    }
                    "disconnect" -> { AmzEngine.disconnectAsync(app); log("disconnect asked") }
                    // The lists fetched again (the pool rebuilt under its caps), then nothing else.
                    "refresh" -> {
                        AmzEngine.refresh(force = true)
                        kotlinx.coroutines.delay(1_000)
                        while (AmzEngine.refreshing.value) kotlinx.coroutines.delay(500)
                        val sv = AmzEngine.store.current.servers
                        log("refresh: pool ${sv.size}, plain WireGuard ${sv.count { it.kind != AmzKind.HY2 && !it.warp && !it.user }}, hy2 ${sv.count { it.kind == AmzKind.HY2 }}, warp ${sv.count { it.warp }}")
                    }
                    // The app's own path: AmzTester straight into its Cloudflare pass (AmzCarrier picks the way in).
                    "prod" -> {
                        val st = AmzEngine.store.current
                        val servers = st.servers.filter { s -> !s.warp && s.kind != AmzKind.HY2 && (hosts == null || "${s.host}:${s.port}" in hosts || s.host in hosts) }
                            .shuffled(java.util.Random(seed.toLong())).take(count)
                        if (intent.getBooleanExtra("forget", false)) AmzCarrier.forget(FluxNet.current(app).key)
                        log("prod: ${servers.size} servers straight to the Cloudflare pass")
                        var ok = 0
                        AmzTester(app).run(servers, true, object : AmzTester.Sink {
                            override fun result(id: String, ms: Long?, country: String?) = result(id, ms, country, null)
                            override fun result(id: String, ms: Long?, country: String?, via: String?) {
                                if (ms != null) ok++
                                val s = servers.firstOrNull { it.id == id }
                                log("prod row ${s?.host}:${s?.port} ms=${ms ?: "-"} via=${via ?: "-"} cc=${country ?: "?"}")
                            }
                            override fun skip(id: String) { log("prod skip $id") }
                        }, cfFirst = servers.map { it.id }.toSet(), warpPool = st.servers.filter { it.warp })
                        log("prod SUMMARY ok=$ok of ${servers.size}")
                    }
                    else -> log("unknown mode $mode")
                }
                log("DONE $mode")
            } catch (e: CancellationException) {
                log("cancelled")
            } catch (e: Throwable) {
                log("ERROR ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            } finally {
                withContext(NonCancellable) { stopCore(app) }
            }
        }
    }

    /**
     * The outer WARP leg -- the [WgConfig] of a WARP seed, its endpoint replaced by [ep] when given.
     * WARP seeds tested on their own (junk in front, as AmzTester does): the fastest that carries
     * data is the outer leg. Seeds that answered on this network before go first.
     */
    private suspend fun outers(app: Context, count: Int, ep: String?): Pair<AmzServer, WgConfig>? {
        val st = AmzEngine.store.current
        val stats = st.statsFor(FluxNet.current(app).key)
        val warps = st.servers.filter { it.warp }
            .sortedWith(compareByDescending<AmzServer> { stats[it.id]?.alive == true }.thenBy { stats[it.id]?.ewmaMs?.takeIf { v -> v > 0 } ?: 9e9 })
            .take(count.coerceAtMost(24))
        val cfgs = warps.mapNotNull { s -> WgConfig.parseConf(s.raw)?.let { s to withEndpoint(it, ep) } }
        log("outer candidates=${cfgs.size} ep=${ep ?: "own"}")
        val probes = cfgs.mapIndexed { i, (_, c) -> Probe(c.xrayOutbound("o-$i", "on-$i"), listOfNotNull(c.noiseOutbound("on-$i", true))) }
        val r = batch(app, probes, emptyList(), timeout = 6_000, wantCountry = true)
        cfgs.forEachIndexed { i, (s, _) -> log("outer ${s.host}:${s.port} ${r[i].first?.let { "OK ${it}ms" } ?: "FAIL"} loc=${r[i].second}") }
        val best = cfgs.indices.filter { r[it].first != null }.minByOrNull { r[it].first!! } ?: return null
        return cfgs[best]
    }

    private fun withEndpoint(c: WgConfig, ep: String?): WgConfig {
        if (ep.isNullOrBlank()) return c
        val host = ep.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = ep.substringAfterLast(':').toIntOrNull() ?: return c
        return c.copy(endpointHost = host, endpointPort = port)
    }

    /**
     * The same sample of public servers, first as the app reaches them today (straight out, junk in
     * front of WireGuard), then inside WARP. Then a short speed sample through a few that worked.
     */
    private suspend fun chain(app: Context, count: Int, kind: String, ep: String?, seed: Int, speedCount: Int, how: String, hosts: Set<String>? = null) {
        val outer = outers(app, 24, ep) ?: run { log("chain: no WARP outer carries data here"); return }
        log("chain: outer ${outer.first.host}:${outer.first.port} ep=${ep ?: "own"}")
        val here = FluxNet.current(app)
        val pool = AmzEngine.store.current.servers.filter { s ->
            !s.warp && (!s.user || hosts != null) && !s.needsAwgCore && when (kind) {
                "wg" -> s.kind != AmzKind.HY2
                "hy2" -> s.kind == AmzKind.HY2
                else -> true
            } && (hosts == null || "${s.host}:${s.port}" in hosts || s.host in hosts)
        }.shuffled(java.util.Random(seed.toLong())).take(count)
        log("chain: sample=${pool.size} kind=$kind of ${AmzEngine.store.current.servers.size}")

        // Each server once as it is reached today, once through WARP. Through WARP in one of two
        // shapes: "dialer" (the server's outbound dials through WARP's with sockopt.dialerProxy) or
        // "hairpin" (it dials a loopback dokodemo-door of the same core, which hands its UDP to WARP).
        suspend fun probe(s: AmzServer, tag: String, viaWarp: Boolean): Probe? = when (s.kind) {
            AmzKind.WG, AmzKind.AWG -> WgConfig.parseConf(s.raw)?.let { c ->
                when {
                    !viaWarp -> c.noiseOutbound("$tag-n", true).let { n -> Probe(c.xrayOutbound(tag, if (n != null) "$tag-n" else null), listOfNotNull(n)) }
                    how == "dialer" -> Probe(c.copy(mtu = INNER_MTU).xrayOutbound(tag, WARP_TAG))
                    else -> {
                        val hp = withContext(Dispatchers.IO) { freePort() }
                        Probe(c.copy(mtu = INNER_MTU, endpointHost = "127.0.0.1", endpointPort = hp).xrayOutbound(tag, null),
                            ins = listOf(hairpin("$tag-dk", hp, c.endpointHost, c.endpointPort)),
                            rules = listOf(rule("$tag-dk", WARP_TAG)))
                    }
                }
            }
            AmzKind.HY2 -> {
                val node = (FluxLinkParser.parse(s.raw, s.sourceId) as? FluxLinkParser.Result.Ok)?.node
                val ip = node?.let { n -> Family.ofLiteral(n.server)?.let { n.server } ?: FluxNet.resolve(app, here.network, n.server).let { it[Family.V4] ?: it[Family.V6] } }
                if (node == null || ip == null) null else {
                    val o = FluxOutbounds.outbound(FluxCandidate(node = node, family = Family.ofLiteral(ip) ?: Family.V4, dialAddress = ip), tag)
                    when {
                        !viaWarp -> Probe(o)
                        how == "dialer" -> {
                            o.optJSONObject("streamSettings")?.let { ss -> (ss.optJSONObject("sockopt") ?: JSONObject().also { ss.put("sockopt", it) }).put("dialerProxy", WARP_TAG) }
                            Probe(o)
                        }
                        else -> {
                            val hp = withContext(Dispatchers.IO) { freePort() }
                            o.getJSONObject("settings").put("address", "127.0.0.1").put("port", hp)
                            // The certificate is checked against the server's own name, not 127.0.0.1.
                            if (Family.ofLiteral(node.server) == null) o.optJSONObject("streamSettings")?.optJSONObject("tlsSettings")?.let { t ->
                                if (!t.has("serverName")) t.put("serverName", node.server)
                            }
                            o.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.remove("domainStrategy")
                            Probe(o, ins = listOf(hairpin("$tag-dk", hp, ip, node.port)), rules = listOf(rule("$tag-dk", WARP_TAG)))
                        }
                    }
                }
            }
        }
        val warpShared = listOfNotNull(outer.second.xrayOutbound(WARP_TAG, "$WARP_TAG-n"), outer.second.noiseOutbound("$WARP_TAG-n", true))

        val built = pool.mapIndexed { i, s -> Triple(s, runCatching { probe(s, "d-$i", false) }.getOrNull(), runCatching { probe(s, "w-$i", true) }.getOrNull()) }
            .filter { it.second != null && it.third != null }
        log("chain: how=$how built=${built.size}")
        val direct = batch(app, built.map { it.second!! }, emptyList(), timeout = 6_000, wantCountry = false)
        val viaWarp = batch(app, built.map { it.third!! }, warpShared, timeout = 9_000, wantCountry = true)
        var dOk = 0; var wOk = 0
        built.forEachIndexed { i, (s, _, _) ->
            if (direct[i].first != null) dOk++
            if (viaWarp[i].first != null) wOk++
            log("row ${s.kind.name.lowercase()} ${s.host}:${s.port} cc=${s.country ?: "??"} direct=${direct[i].first ?: "-"} warp=${viaWarp[i].first ?: "-"} loc=${viaWarp[i].second ?: "?"}")
        }
        log("SUMMARY kind=$kind tested=${built.size} direct_ok=$dOk warp_ok=$wOk")

        // Speed: the outer alone, then a few servers that worked inside it. One core at a time.
        if (speedCount > 0) {
            log("speed outer-only: ${speed(app, Probe(outer.second.xrayOutbound("s", "s-n"), listOfNotNull(outer.second.noiseOutbound("s-n", true))), emptyList())}")
            built.indices.filter { viaWarp[it].first != null }.sortedBy { viaWarp[it].first!! }.take(speedCount).forEach { i ->
                val s = built[i].first
                val inner = probe(s, "s", true) ?: return@forEach
                log("speed ${s.kind.name.lowercase()} ${s.host} loc=${viaWarp[i].second}: ${speed(app, inner, warpShared)}")
            }
        }
    }

    /** One server as the probe core reaches it: its outbound, and what that needs beside it. */
    private class Probe(
        val out: JSONObject,
        val outs: List<JSONObject> = emptyList(),
        val ins: List<JSONObject> = emptyList(),
        val rules: List<JSONObject> = emptyList(),
    )

    /** A loopback UDP door of the core itself that sends what it gets on to [host]:[port]. */
    private fun hairpin(tag: String, port: Int, host: String, toPort: Int) = JSONObject().put("tag", tag)
        .put("listen", "127.0.0.1").put("port", port).put("protocol", "dokodemo-door")
        .put("settings", JSONObject().put("address", host).put("port", toPort).put("network", "udp"))

    private fun rule(inTag: String, outTag: String) =
        JSONObject().put("type", "field").put("inboundTag", JSONArray().put(inTag)).put("outboundTag", outTag)

    // ------------------------------------------------------------------ the probe core

    /** One core in `:xprobe` for [probes] (one SOCKS inbound each, wired by order), [shared] beside them. */
    private suspend fun batch(app: Context, probes: List<Probe>, shared: List<JSONObject>, timeout: Int, wantCountry: Boolean): List<Pair<Long?, String?>> {
        if (probes.isEmpty()) return emptyList()
        val ports = withContext(Dispatchers.IO) { probes.map { freePort() } }
        if (!startCore(app, config(probes, shared, ports))) {
            log("batch: core refused the config (${probes.size} servers)")
            return probes.map { null to null }
        }
        return try {
            coroutineScope {
                val gate = Semaphore(24)
                ports.map { port -> async(Dispatchers.IO) { gate.withPermit { measure(port, timeout, wantCountry) } } }.awaitAll()
            }
        } finally {
            withContext(NonCancellable) { stopCore(app) }
        }
    }

    private fun config(probes: List<Probe>, shared: List<JSONObject>, ports: List<Int>): String {
        val inbounds = JSONArray(); val outbounds = JSONArray(); val rules = JSONArray()
        probes.forEachIndexed { i, p ->
            val inTag = "in-$i"
            inbounds.put(JSONObject().put("tag", inTag).put("port", ports[i]).put("listen", "127.0.0.1").put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", false)))
            p.ins.forEach { inbounds.put(it) }
            outbounds.put(p.out)
            p.outs.forEach { outbounds.put(it) }
            rules.put(rule(inTag, p.out.getString("tag")))
            p.rules.forEach { rules.put(it) }
        }
        shared.forEach { outbounds.put(it) }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        return JSONObject().put("log", JSONObject().put("loglevel", LOG_LEVEL)).put("inbounds", inbounds).put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules)).toString()
    }

    private suspend fun startCore(app: Context, cfg: String): Boolean = withContext(Dispatchers.IO) {
        AwgHostClient.xray.call(app, XrayProbeService.MSG_START, Bundle().apply { putString(XrayProbeService.KEY_CONFIG, cfg) }, 25_000)
            ?.getBoolean(XrayProbeService.KEY_OK) == true
    }

    private suspend fun stopCore(app: Context) = withContext(Dispatchers.IO) {
        runCatching { AwgHostClient.xray.call(app, XrayProbeService.MSG_STOP, null, 8_000) }
    }

    /** First request (carries the handshakes), second request (the delay), and the exit's country. */
    private suspend fun measure(port: Int, timeout: Int, wantCountry: Boolean): Pair<Long?, String?> = coroutineScope {
        val first = get(port, "www.gstatic.com", "/generate_204", timeout) ?: return@coroutineScope null to null
        val cc = if (wantCountry) async(Dispatchers.IO) { trace(port, timeout) } else null
        val second = get(port, "www.gstatic.com", "/generate_204", timeout)
        (second ?: first) to cc?.await()
    }

    /** One core, one SOCKS port: TTFB and Mbit/s of a 2 MB download from Cloudflare's speed host. */
    private suspend fun speed(app: Context, p: Probe, shared: List<JSONObject>): String {
        val port = withContext(Dispatchers.IO) { freePort() }
        if (!startCore(app, config(listOf(p), shared, listOf(port)))) return "core refused"
        return try {
            withContext(Dispatchers.IO) {
                // Warm the tunnel: the first request carries every handshake.
                get(port, "www.gstatic.com", "/generate_204", 9_000) ?: return@withContext "no data"
                runCatching {
                    val raw = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                    raw.soTimeout = 15_000
                    raw.connect(InetSocketAddress.createUnresolved("speed.cloudflare.com", 443), 9_000)
                    val tls = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                        .createSocket(raw, "speed.cloudflare.com", 443, true) as javax.net.ssl.SSLSocket
                    tls.use { s ->
                        val t0 = System.nanoTime()
                        s.outputStream.write("GET /__down?bytes=2000000 HTTP/1.1\r\nHost: speed.cloudflare.com\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
                        val input = s.inputStream
                        val buf = ByteArray(64 * 1024)
                        var total = 0L; var first = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            if (first == 0L) first = System.nanoTime()
                            total += n
                            if (System.nanoTime() - t0 > 20_000_000_000L) break
                        }
                        val secs = (System.nanoTime() - first) / 1e9
                        "ttfb=${(first - t0) / 1_000_000}ms bytes=$total mbps=${"%.2f".format(total * 8 / 1e6 / secs.coerceAtLeast(0.001))}"
                    }
                }.getOrElse { "error ${it.javaClass.simpleName}" }
            }
        } finally {
            withContext(NonCancellable) { stopCore(app) }
        }
    }

    private fun get(port: Int, host: String, path: String, timeoutMs: Int): Long? = runCatching {
        val start = System.nanoTime()
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { s ->
            s.soTimeout = timeoutMs
            s.connect(InetSocketAddress.createUnresolved(host, 80), timeoutMs)
            s.getOutputStream().write("GET $path HTTP/1.0\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
            val line = readLine(s.getInputStream()).orEmpty()
            if (line.contains(" 204") || line.contains(" 200")) (System.nanoTime() - start) / 1_000_000 else null
        }
    }.getOrNull()

    private fun trace(port: Int, timeoutMs: Int): String? = runCatching {
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { s ->
            s.soTimeout = timeoutMs
            s.connect(InetSocketAddress.createUnresolved("www.cloudflare.com", 80), timeoutMs)
            s.getOutputStream().write("GET /cdn-cgi/trace HTTP/1.0\r\nHost: www.cloudflare.com\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
            String(s.getInputStream().readBytes(), Charsets.UTF_8).lineSequence().firstOrNull { it.startsWith("loc=") }?.substringAfter('=')?.trim()
        }
    }.getOrNull()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 256) {
            val b = input.read(); if (b < 0) break
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val WARP_TAG = "warp"
        /** Inner WireGuard inside WARP's 1280: 1200 + 32 (WireGuard) + 28 (IPv4/UDP) still fits. */
        private const val INNER_MTU = 1200
        /** The probe core's own log level for this run (`--es log warning` shows its errors in logcat). */
        @Volatile private var LOG_LEVEL = "none"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var job: Job? = null
        private fun log(text: String) { Log.i("AmzLab", text) }
    }
}
