package com.mlmvpn.scanner.engines.amnezia

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.mlmvpn.core.warp.AwgHostClient
import com.mlmvpn.core.warp.AwgProbeService
import com.mlmvpn.core.warp.XrayProbeService
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.outbound.FluxOutbounds
import com.mlmvpn.scanner.engines.flux.core.parse.FluxLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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
 * The delay test, the way v2rayNG's "real delay" works: a real HTTP request THROUGH each server,
 * timed. Not a ping and not a TCP connect -- a server that answers a ping but carries nothing
 * fails here, which is the point.
 *
 * Fast because it is batched: one Xray core holds a few dozen servers, each behind its own local
 * SOCKS port (explicit server -> port map), and all of them are asked at once. Per server:
 *  1. a first request, which also carries the server's own handshake -- it must succeed;
 *  2. a second request on a fresh connection: its time is the delay shown (what a user feels);
 *  3. alongside it, Cloudflare's trace through the same server, for where it really exits.
 *
 * Plain WireGuard is tested WITH the junk packets it will be connected with (Xray's freedom
 * `noises` in front of the WireGuard dial), so a filter that drops bare WireGuard does not make a
 * server look dead that the real connection would carry. Servers only AmneziaWG can talk to go to
 * the `:awgprobe` process instead, one at a time, side by side with the batches.
 */
class AmzTester(private val app: Context) {

    interface Sink {
        fun result(id: String, ms: Long?, country: String?)
        /** A result with the way it was reached ([AmzStat.VIA_CF] inside Cloudflare); the plain one by default. */
        fun result(id: String, ms: Long?, country: String?, via: String?) = result(id, ms, country)
        /** Not tested after all (its test core died under it): nothing is learned about it. */
        fun skip(id: String)
        /** The way relayed servers are tried in the round now running, for the screen; null when none is. */
        fun via(via: String?) {}
        /** A self-signed server's link, pinned to the certificate it shows now ([AmzPins]): to be kept. */
        fun pinned(id: String, raw: String) {}
    }

    /** False when the network itself does not answer: nothing should be learned from a run then. */
    suspend fun online(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket(Proxy.NO_PROXY).use { s ->
                s.soTimeout = 4_000
                s.connect(InetSocketAddress("connectivitycheck.gstatic.com", 80), 4_000)
                s.getOutputStream().write("GET /generate_204 HTTP/1.0\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
                readLine(s.getInputStream()).orEmpty().let { it.contains(" 204") || it.contains(" 200") }
            }
        }.getOrDefault(false)
    }

    /**
     * @param cfFirst servers that last worked here only from inside Cloudflare: tested that way
     *   straight away, without first failing over the line again.
     * @param warpPool the pool's Cloudflare WARP accounts, from which [AmzCarrier] finds a way into
     *   Cloudflare on this line when it has no identity of its own.
     */
    suspend fun run(servers: List<AmzServer>, obfuscate: Boolean, sink: Sink, cfFirst: Set<String> = emptySet(), warpPool: List<AmzServer> = emptyList()) = coroutineScope {
        val here = FluxNet.current(app)
        // 96 configs in one core was more than a 4 GB phone kept: the test process was killed for
        // memory twice in a row on the user's Galaxy A50 (2026-10-11), and every kill costs a retry.
        val batchSize = if (here.wifi) 64 else 48
        val timeout = if (here.cellular) 6_000 else 5_000
        val (awgOnly, unpinned) = servers.partition { it.needsAwgCore }
        // Self-signed certificates are fetched batch by batch below, not all up front: up front, a
        // list of 3,000 sat at «۰ از ۳۰۰۰» for minutes before the first result (user report 2026-10-11).
        val xray = unpinned
        // Servers with a relay of their own (Proton's Stealth) are tested through it.
        val relays = HashMap<String, AmzEngine.RelayHandle>()
        withContext(Dispatchers.IO) {
            xray.filter { it.kind != AmzKind.HY2 }.forEach { s -> runCatching { AmzEngine.relay?.open(s) }.getOrNull()?.let { relays[s.id] = it } }
        }
        // A plain WireGuard server that does not answer over the line is tried once more from inside
        // Cloudflare before it is called dead: a line that drops UDP to the server itself often lets
        // WARP's through (measured 2026-10-07: 8 of 40 directly, 21 of 40 through WARP). Its failure
        // over the line is held back until then, so the list never shows it dead in between.
        val cfEligible = { s: AmzServer -> s.kind != AmzKind.HY2 && !s.warp && !s.needsAwgCore && s.id !in relays }
        val viaCf = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        xray.filter { it.id in cfFirst && cfEligible(it) }.forEach { viaCf += it.id }
        val xrayById = xray.associateBy { it.id }
        val lineSink = object : Sink {
            override fun result(id: String, ms: Long?, country: String?) {
                val s = xrayById[id]
                if (ms == null && s != null && cfEligible(s)) viaCf += id else sink.result(id, ms, country)
            }
            override fun skip(id: String) = sink.skip(id)
            override fun via(via: String?) = sink.via(via)
        }
        // The AmneziaWG-only servers run in their own process while the batches run here.
        val awgJob = if (awgOnly.isEmpty()) null else launch(Dispatchers.IO) {
            for (s in awgOnly) {
                ensureActive()
                val reply = AwgHostClient.probe.call(app, AwgProbeService.MSG_TEST, Bundle().apply {
                    putString(AwgProbeService.KEY_CONFIG, s.raw)
                    putInt(AwgProbeService.KEY_TIMEOUT, timeout)
                }, timeout * 4L + 8_000)
                val ms = reply?.getLong(AwgProbeService.KEY_MS, -1L)?.takeIf { it >= 0 }
                sink.result(s.id, ms, reply?.getString(AwgProbeService.KEY_COUNTRY))
            }
        }
        try {
            // Hysteria2 and WireGuard in separate batches: if one kind drives the core into a
            // panic, the other kind's results are not lost with it.
            for (batch in xray.filter { it.id !in relays && it.id !in viaCf }.groupBy { it.kind == AmzKind.HY2 }.values.flatMap { it.chunked(batchSize) }) {
                ensureActive()
                runBatch(pinSelfSigned(batch, here, sink), here, obfuscate, timeout, lineSink, retries = 2, relays)
            }
            // Through a relay the first request also opens its TLS connection, on a line where that
            // took up to 6 s with dozens at once: data came back after the plain 5 s had given up.
            for (batch in xray.filter { it.id in relays }.chunked(RELAY_BATCH)) {
                ensureActive()
                var pending = batch
                var rounds = 0
                // Each server's relay races all its ways at once (IPv6, Cloudflare, IPv4, UDP -- the
                // way that last worked on this network with a head start), one more time at most: a
                // server none of them reaches is dead here, and walking every TLS variant for each of
                // 111 servers one by one turned a ping into half an hour.
                while (pending.isNotEmpty() && rounds++ < RELAY_ROUNDS) {
                    ensureActive()
                    sink.via(pending.mapNotNull { relays[it.id]?.via }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
                    val again = mutableSetOf<String>()
                    val round = object : Sink {
                        override fun result(id: String, ms: Long?, country: String?) {
                            val handle = relays[id]
                            if (ms == null && handle?.retryAfterFailure() == true) again += id
                            else {
                                if (ms != null) handle?.validated()
                                sink.result(id, ms, country)
                            }
                        }
                        override fun skip(id: String) = sink.skip(id)
                    }
                    // New probe core => new WireGuard session for each method. Retrying only TLS
                    // would reuse a session whose handshake the server/filter already discarded.
                    runBatch(pending, here, obfuscate, maxOf(timeout * 2, RELAY_TIMEOUT_MS), round, retries = 1, relays)
                    pending = pending.filter { it.id in again }
                }
                // Out of rounds: those servers are reported dead here.
                pending.forEach { sink.result(it.id, null, null) }
                batch.forEach { relays.remove(it.id)?.close() }
            }
            if (viaCf.isNotEmpty()) cloudflarePass(xray.filter { it.id in viaCf }, here, timeout, sink, warpPool)
            sink.via(null)
            awgJob?.join()
        } finally {
            relays.values.forEach { runCatching { it.close() } }
            if (awgOnly.isNotEmpty()) AwgHostClient.probe.release(app)
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { stopRemote(); AwgHostClient.xray.release(app) }
        }
    }

    /**
     * Each self-signed Hysteria2 server is asked for its certificate first, and tested pinned to it
     * (the core accepts that one and nothing else). One that shows none is still tested as it is:
     * the delay test stays the judge.
     */
    private suspend fun pinSelfSigned(servers: List<AmzServer>, here: FluxNet.Here, sink: Sink): List<AmzServer> {
        if (servers.none { it.kind == AmzKind.HY2 && it.selfSigned }) return servers
        val timeout = if (here.cellular) PIN_TIMEOUT_MS + 1_000 else PIN_TIMEOUT_MS
        val gate = Semaphore(PIN_PARALLEL)
        val shown = java.util.concurrent.atomic.AtomicInteger()
        val out = coroutineScope {
            servers.map { s ->
                async(Dispatchers.IO) {
                    if (s.kind != AmzKind.HY2 || !s.selfSigned) s
                    else gate.withPermit {
                        val p = runCatching { AmzPins.pin(app, here, s, timeout) }.getOrNull()
                        if (p == null) s else {
                            shown.incrementAndGet()
                            if (p.raw != s.raw) sink.pinned(s.id, p.raw)
                            p
                        }
                    }
                }
            }.awaitAll()
        }
        Log.i(TAG, "self-signed: $shown of ${servers.count { it.kind == AmzKind.HY2 && it.selfSigned }} showed a certificate")
        return out
    }

    /**
     * The servers that did not answer over the line, once more from inside Cloudflare ([AmzCarrier]):
     * each one's own WireGuard dials a loopback door of the same core, which hands its UDP to the
     * carrier. Their results are final either way.
     */
    private suspend fun cloudflarePass(servers: List<AmzServer>, here: FluxNet.Here, timeout: Int, sink: Sink, warpPool: List<AmzServer>) {
        sink.via(AmzStat.VIA_CF)
        val carrier = runCatching { AmzCarrier.acquire(app, here, this, warpPool) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "carrier: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (carrier == null) {
            Log.i(TAG, "cloudflare pass: no way into Cloudflare on this line; ${servers.size} stay dead")
            servers.forEach { sink.result(it.id, null, null) }
            return
        }
        // Results are held until the pass is judged: one through a carrier that died says nothing.
        class Got(val id: String, val ms: Long?, val country: String?, val skipped: Boolean = false)
        suspend fun pass(c: AmzCarrier.Carrier): List<Got> {
            val got = java.util.Collections.synchronizedList(ArrayList<Got>())
            val collect = object : Sink {
                override fun result(id: String, ms: Long?, country: String?) { got += Got(id, ms, country) }
                override fun skip(id: String) { got += Got(id, null, null, skipped = true) }
            }
            val shared = AmzCarrier.outbounds(c)
            for (batch in servers.chunked(CF_BATCH)) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val entries = withContext(Dispatchers.IO) {
                    batch.mapNotNull { s ->
                        val cfg = WgConfig.parseConf(s.raw)
                        if (cfg == null) { collect.result(s.id, null, null); null }
                        else {
                            val door = freePort()
                            Entry(s, AmzCarrier.inner(cfg, door).xrayOutbound("x", null), null, door = Door(door, cfg.endpointHost, cfg.endpointPort))
                        }
                    }
                }
                if (entries.isNotEmpty()) testEntries(entries, maxOf(timeout * 2, CF_TIMEOUT_MS), collect, retries = 1, shared = shared)
            }
            return got.toList()
        }
        var used = carrier
        var got = pass(used)
        // Nothing at all through a carrier that carried a moment ago: it is the carrier that died
        // (measured: a pass of 18 found none, the same servers 5 of 7 minutes later). A fresh one, once.
        if (got.none { it.ms != null } && servers.size >= 4) {
            AmzCarrier.forget(here.key)
            val again = runCatching { AmzCarrier.acquire(app, here, this, warpPool) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
            if (again != null) {
                Log.i(TAG, "cloudflare pass via ${used.label} found nothing; again via ${again.label}")
                used = again
                got = pass(again)
            }
        }
        val ok = got.count { it.ms != null }
        got.forEach { g -> if (g.skipped) sink.skip(g.id) else sink.result(g.id, g.ms, g.country, if (g.ms != null) AmzStat.VIA_CF else null) }
        Log.i(TAG, "cloudflare pass via ${used.label}: $ok of ${servers.size} answered")
        if (ok == 0) AmzCarrier.forget(here.key)
    }

    /** A loopback UDP door of the probe core that hands what it gets to [host]:[hostPort] through the carrier. */
    private class Door(val port: Int, val host: String, val hostPort: Int)

    private class Entry(val server: AmzServer, val outbound: JSONObject, val noise: JSONObject?, val door: Door? = null)

    private suspend fun runBatch(
        batch: List<AmzServer>, here: FluxNet.Here, obfuscate: Boolean, timeout: Int, sink: Sink, retries: Int,
        relays: Map<String, AmzEngine.RelayHandle> = emptyMap(),
    ) {
        val built = coroutineScope {
            // Hysteria2 dials literal addresses only: names are resolved here, on the bare network,
            // with DoH where the network's own resolver lies.
            val gate = Semaphore(16)
            batch.map { s -> async(Dispatchers.IO) { gate.withPermit { s to runCatching { entry(s, here, obfuscate, relays[s.id]) }.getOrNull() } } }.awaitAll()
        }
        built.filter { it.second == null }.forEach { (s, _) -> sink.result(s.id, null, null) }
        val entries = built.mapNotNull { it.second }
        if (entries.isNotEmpty()) testEntries(entries, timeout, sink, retries)
    }

    /**
     * One test core (in the :xprobe process) for [entries]. Results are held until the batch is
     * over: if the core died meanwhile, every failure in it may be the crash and not the server,
     * so the batch is tried again in halves, and what still cannot be tested is skipped.
     */
    private suspend fun testEntries(entries: List<Entry>, timeout: Int, sink: Sink, retries: Int, shared: List<JSONObject> = emptyList()) {
        val deathsBefore = AwgHostClient.xray.deaths
        val ports = withContext(Dispatchers.IO) { entries.map { freePort() } }
        var started = startRemote(config(entries, ports, shared))
        var used = entries
        var usedPorts = ports
        if (!started && AwgHostClient.xray.deaths == deathsBefore) {
            // One outbound this core cannot build takes the whole config down with it. Find the
            // ones that do build (a config error fails in milliseconds) and test those.
            Log.w(TAG, "the core refused a batch of ${entries.size}; trying them one by one")
            val ok = ArrayList<Entry>()
            for (e in entries) {
                if (startRemote(config(listOf(e), listOf(freePort()), shared))) ok += e else sink.result(e.server.id, null, null)
            }
            used = ok
            usedPorts = withContext(Dispatchers.IO) { ok.map { freePort() } }
            started = used.isNotEmpty() && startRemote(config(used, usedPorts, shared))
        }
        if (!started) {
            if (AwgHostClient.xray.deaths != deathsBefore) used.forEach { sink.skip(it.server.id) }
            else used.forEach { sink.result(it.server.id, null, null) }
            return
        }
        // From here: the start itself may have replaced a test process that had ended (AwgHostClient).
        val deathsAtStart = AwgHostClient.xray.deaths
        val results = coroutineScope {
            val probeGate = Semaphore(com.mlmvpn.scanner.utils.ThreadBudget.parallel(48))
            used.zip(usedPorts).map { (e, port) ->
                async(Dispatchers.IO) {
                    probeGate.withPermit { e to measure(port, timeout, wantCountry = e.server.countryFrom.rank < CountryFrom.PROBE.rank && !e.server.warp) }
                }
            }.awaitAll()
        }
        // Judged before the stop: a stop may end the test process by design (XrayProbeService), and
        // what was measured before it stands.
        val diedMeasuring = AwgHostClient.xray.deaths != deathsAtStart
        withContext(Dispatchers.IO) { stopRemote() }
        if (!diedMeasuring) {
            results.forEach { (e, r) -> sink.result(e.server.id, r.first, r.second) }
            return
        }
        // The core died under this batch. What worked, worked; the rest is tried again.
        Log.w(TAG, "test core died under a batch of ${used.size}; retrying in halves")
        results.filter { it.second.first != null }.forEach { (e, r) -> sink.result(e.server.id, r.first, r.second) }
        val again = results.filter { it.second.first == null }.map { it.first }
        if (retries <= 0 || again.isEmpty()) { again.forEach { sink.skip(it.server.id) }; return }
        again.chunked(maxOf(1, (again.size + 1) / 2)).forEach { testEntries(it, timeout, sink, retries - 1, shared) }
    }

    /**
     * The given WARP accounts, each already pointed at the endpoint it is to try, measured side by
     * side in one core (each its own key, so none takes another's session): their delays in order,
     * null where nothing came back. For [AmzCarrier]'s search for a way into Cloudflare.
     */
    internal suspend fun probeWarp(pairs: List<Pair<AmzServer, WgConfig>>, timeout: Int): List<Long?> {
        if (pairs.isEmpty()) return emptyList()
        val got = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val entries = pairs.map { (s, c) -> c.noiseOutbound("n", true).let { n -> Entry(s, c.xrayOutbound("x", if (n != null) "n" else null), n) } }
        testEntries(entries, timeout, object : Sink {
            override fun result(id: String, ms: Long?, country: String?) { if (ms != null) got[id] = ms }
            override fun skip(id: String) {}
        }, retries = 1)
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { stopRemote() }
        return pairs.map { got[it.first.id] }
    }

    private suspend fun startRemote(cfg: String): Boolean = withContext(Dispatchers.IO) {
        AwgHostClient.xray.call(app, XrayProbeService.MSG_START, Bundle().apply { putString(XrayProbeService.KEY_CONFIG, cfg) }, 20_000)
            ?.getBoolean(XrayProbeService.KEY_OK) == true
    }

    private fun stopRemote() {
        AwgHostClient.xray.call(app, XrayProbeService.MSG_STOP, null, 5_000)
    }

    private suspend fun entry(s: AmzServer, here: FluxNet.Here, obfuscate: Boolean, relay: AmzEngine.RelayHandle? = null): Entry? = when (s.kind) {
        AmzKind.HY2 -> {
            val node = (FluxLinkParser.parse(s.raw, s.sourceId) as? FluxLinkParser.Result.Ok)?.node
            if (node == null) null else {
                val literal = Family.ofLiteral(node.server)?.let { node.server }
                    ?: FluxNet.resolve(app, here.network, node.server).let { it[Family.V4] ?: it[Family.V6] }
                literal?.let { ip ->
                    val c = FluxCandidate(node = node, family = Family.ofLiteral(ip) ?: Family.V4, dialAddress = ip)
                    Entry(s, FluxOutbounds.outbound(c, "x"), null)
                }
            }
        }
        AmzKind.WG, AmzKind.AWG -> WgConfig.parseConf(s.raw)?.let { parsed ->
            val cfg = AmzEngine.relayed(parsed, relay)
            val noise = if (relay != null) null else cfg.noiseOutbound("n", obfuscate)
            Entry(s, cfg.xrayOutbound("x", if (noise != null) "n" else null), noise)
        }
    }

    /**
     * [s] as a one-server probe config: a SOCKS inbound wired to its outbound. The game booster
     * runs it in the probe process to measure the game's own region THROUGH this server before
     * deciding where the game goes (the port is replaced by whoever runs it).
     */
    suspend fun probeConfig(s: AmzServer, obfuscate: Boolean): String? {
        val here = FluxNet.current(app)
        return entry(s, here, obfuscate)?.let { config(listOf(it), listOf(0)) }
    }

    /**
     * One SOCKS inbound per server, wired by tag to that server's outbound (and its noise); a server
     * reached through the carrier also gets its loopback door, wired to [shared]'s carrier.
     */
    private fun config(entries: List<Entry>, ports: List<Int>, shared: List<JSONObject> = emptyList()): String {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        entries.forEachIndexed { i, e ->
            val inTag = "ain-$i"; val outTag = "aout-$i"
            inbounds.put(JSONObject().put("tag", inTag).put("port", ports[i]).put("listen", "127.0.0.1").put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", false)))
            val out = JSONObject(e.outbound.toString()).put("tag", outTag)
            if (e.noise != null) {
                val noiseTag = "anoise-$i"
                out.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.put("dialerProxy", noiseTag)
                outbounds.put(JSONObject(e.noise.toString()).put("tag", noiseTag))
            }
            outbounds.put(out)
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put(inTag)).put("outboundTag", outTag))
            e.door?.let { d ->
                val doorTag = "adoor-$i"
                inbounds.put(AmzCarrier.door(doorTag, d.port, d.host, d.hostPort))
                rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put(doorTag)).put("outboundTag", AmzCarrier.TAG))
            }
        }
        shared.forEach { outbounds.put(it) }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        return JSONObject()
            .put("remarks", "mlm-amnezia-probe")
            .put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            .toString()
    }

    /**
     * The delay through the SOCKS port at [port] (or null), and the exit's country when asked. The
     * first request is the proof, over HTTPS ([AmzProof]): a server that answers only a small
     * plain-HTTP request showed a delay and then carried nothing (Ehsan, 31 Oct 2026). The delay
     * shown is still the plain request's, as before.
     */
    private suspend fun measure(port: Int, timeout: Int, wantCountry: Boolean): Pair<Long?, String?> = coroutineScope {
        val first = proof(port, timeout) ?: return@coroutineScope null to null
        val cc = if (wantCountry) async(Dispatchers.IO) { trace(port, timeout) } else null
        val second = get(port, "www.gstatic.com", "/generate_204", timeout)
        (second ?: first) to cc?.await()
    }

    private fun open(port: Int, host: String, timeoutMs: Int, remotePort: Int = 80): Socket {
        val s = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        s.soTimeout = timeoutMs
        // Unresolved: the name is resolved at the exit, never by this phone's resolver.
        s.connect(InetSocketAddress.createUnresolved(host, remotePort), timeoutMs)
        return s
    }

    /** HTTPS through the SOCKS port at [port], its time, or null when it did not carry ([AmzProof]). */
    private fun proof(port: Int, timeoutMs: Int): Long? = runCatching {
        val start = System.nanoTime()
        if (AmzProof.https204(open(port, AmzProof.HOST, timeoutMs, 443), timeoutMs)) (System.nanoTime() - start) / 1_000_000 else null
    }.getOrNull()

    private fun get(port: Int, host: String, path: String, timeoutMs: Int): Long? = runCatching {
        val start = System.nanoTime()
        open(port, host, timeoutMs).use { s ->
            s.getOutputStream().write("GET $path HTTP/1.0\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
            val line = readLine(s.getInputStream()).orEmpty()
            if (line.contains(" 204") || line.contains(" 200")) (System.nanoTime() - start) / 1_000_000 else null
        }
    }.getOrNull()

    private fun trace(port: Int, timeoutMs: Int): String? = runCatching {
        open(port, "www.cloudflare.com", timeoutMs).use { s ->
            s.getOutputStream().write("GET /cdn-cgi/trace HTTP/1.0\r\nHost: www.cloudflare.com\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
            val text = String(s.getInputStream().readNBytesCompat(8 * 1024), Charsets.UTF_8)
            text.lineSequence().firstOrNull { it.startsWith("loc=") }?.substringAfter('=')?.trim()?.uppercase()
                ?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } && it != "XX" && it != "T1" }
        }
    }.onFailure { Log.d(TAG, "trace via :$port failed: ${it.javaClass.simpleName}") }.getOrNull()

    private fun InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(2048)
        while (out.size() < max) {
            val n = runCatching { read(buf) }.getOrDefault(-1)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 256) {
            val b = input.read(); if (b < 0) break
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    /**
     * A free loopback port. Asking the kernel for "any" can still fail when hundreds of test
     * cores have just closed theirs (EADDRINUSE, crash #582): a few tries, then a random high port.
     */
    private fun freePort(): Int {
        repeat(5) { runCatching { return ServerSocket(0).use { it.localPort } }; Thread.sleep(50) }
        return 20_000 + java.util.Random().nextInt(30_000)
    }

    companion object {
        private const val TAG = "AmzTester"
        /** Relayed servers (Proton's Stealth) are tested this many at once, with this long a wait. */
        private const val RELAY_BATCH = 16
        private const val RELAY_TIMEOUT_MS = 12_000
        /** How many rounds a test gives a relayed server (its relay tries all its ways in each) before it calls the server dead. */
        private const val RELAY_ROUNDS = 3
        /** Servers per core inside one Cloudflare session, and their wait: two handshakes deep. */
        private const val CF_BATCH = 48
        private const val CF_TIMEOUT_MS = 9_000
        /** Self-signed servers asked for their certificate at once, and how long each may take. */
        private const val PIN_PARALLEL = 48
        private const val PIN_TIMEOUT_MS = 3_000
    }
}
