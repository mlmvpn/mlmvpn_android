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
        /** Not tested after all (its test core died under it): nothing is learned about it. */
        fun skip(id: String)
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

    suspend fun run(servers: List<AmzServer>, obfuscate: Boolean, sink: Sink) = coroutineScope {
        val here = FluxNet.current(app)
        val batchSize = if (here.wifi) 96 else 64
        val timeout = if (here.cellular) 6_000 else 5_000
        val (awgOnly, xray) = servers.partition { it.needsAwgCore }
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
            for (batch in xray.groupBy { it.kind == AmzKind.HY2 }.values.flatMap { it.chunked(batchSize) }) {
                ensureActive()
                runBatch(batch, here, obfuscate, timeout, sink, retries = 2)
            }
            awgJob?.join()
        } finally {
            if (awgOnly.isNotEmpty()) AwgHostClient.probe.release(app)
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { stopRemote(); AwgHostClient.xray.release(app) }
        }
    }

    private class Entry(val server: AmzServer, val outbound: JSONObject, val noise: JSONObject?)

    private suspend fun runBatch(batch: List<AmzServer>, here: FluxNet.Here, obfuscate: Boolean, timeout: Int, sink: Sink, retries: Int) {
        val built = coroutineScope {
            // Hysteria2 dials literal addresses only: names are resolved here, on the bare network,
            // with DoH where the network's own resolver lies.
            val gate = Semaphore(16)
            batch.map { s -> async(Dispatchers.IO) { gate.withPermit { s to runCatching { entry(s, here, obfuscate) }.getOrNull() } } }.awaitAll()
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
    private suspend fun testEntries(entries: List<Entry>, timeout: Int, sink: Sink, retries: Int) {
        val deathsBefore = AwgHostClient.xray.deaths
        val ports = withContext(Dispatchers.IO) { entries.map { freePort() } }
        var started = startRemote(config(entries, ports))
        var used = entries
        var usedPorts = ports
        if (!started && AwgHostClient.xray.deaths == deathsBefore) {
            // One outbound this core cannot build takes the whole config down with it. Find the
            // ones that do build (a config error fails in milliseconds) and test those.
            val ok = ArrayList<Entry>()
            for (e in entries) {
                if (startRemote(config(listOf(e), listOf(freePort())))) ok += e else sink.result(e.server.id, null, null)
            }
            used = ok
            usedPorts = withContext(Dispatchers.IO) { ok.map { freePort() } }
            started = used.isNotEmpty() && startRemote(config(used, usedPorts))
        }
        if (!started) {
            if (AwgHostClient.xray.deaths != deathsBefore) used.forEach { sink.skip(it.server.id) }
            else used.forEach { sink.result(it.server.id, null, null) }
            return
        }
        val results = coroutineScope {
            val probeGate = Semaphore(48)
            used.zip(usedPorts).map { (e, port) ->
                async(Dispatchers.IO) {
                    probeGate.withPermit { e to measure(port, timeout, wantCountry = e.server.countryFrom.rank < CountryFrom.PROBE.rank && !e.server.warp) }
                }
            }.awaitAll()
        }
        withContext(Dispatchers.IO) { stopRemote() }
        if (AwgHostClient.xray.deaths == deathsBefore) {
            results.forEach { (e, r) -> sink.result(e.server.id, r.first, r.second) }
            return
        }
        // The core died under this batch. What worked, worked; the rest is tried again.
        Log.w(TAG, "test core died under a batch of ${used.size}; retrying in halves")
        results.filter { it.second.first != null }.forEach { (e, r) -> sink.result(e.server.id, r.first, r.second) }
        val again = results.filter { it.second.first == null }.map { it.first }
        if (retries <= 0 || again.isEmpty()) { again.forEach { sink.skip(it.server.id) }; return }
        again.chunked(maxOf(1, (again.size + 1) / 2)).forEach { testEntries(it, timeout, sink, retries - 1) }
    }

    private suspend fun startRemote(cfg: String): Boolean = withContext(Dispatchers.IO) {
        AwgHostClient.xray.call(app, XrayProbeService.MSG_START, Bundle().apply { putString(XrayProbeService.KEY_CONFIG, cfg) }, 20_000)
            ?.getBoolean(XrayProbeService.KEY_OK) == true
    }

    private fun stopRemote() {
        AwgHostClient.xray.call(app, XrayProbeService.MSG_STOP, null, 5_000)
    }

    private suspend fun entry(s: AmzServer, here: FluxNet.Here, obfuscate: Boolean): Entry? = when (s.kind) {
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
        AmzKind.WG, AmzKind.AWG -> WgConfig.parseConf(s.raw)?.let { cfg ->
            val noise = cfg.noiseOutbound("n", obfuscate)
            Entry(s, cfg.xrayOutbound("x", if (noise != null) "n" else null), noise)
        }
    }

    /** One SOCKS inbound per server, wired by tag to that server's outbound (and its noise). */
    private fun config(entries: List<Entry>, ports: List<Int>): String {
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
        }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        return JSONObject()
            .put("remarks", "mlm-amnezia-probe")
            .put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            .toString()
    }

    /** The delay through the SOCKS port at [port] (or null), and the exit's country when asked. */
    private suspend fun measure(port: Int, timeout: Int, wantCountry: Boolean): Pair<Long?, String?> = coroutineScope {
        val first = get(port, "www.gstatic.com", "/generate_204", timeout) ?: return@coroutineScope null to null
        val cc = if (wantCountry) async(Dispatchers.IO) { trace(port, timeout) } else null
        val second = get(port, "www.gstatic.com", "/generate_204", timeout)
        (second ?: first) to cc?.await()
    }

    private fun open(port: Int, host: String, timeoutMs: Int): Socket {
        val s = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        s.soTimeout = timeoutMs
        // Unresolved: the name is resolved at the exit, never by this phone's resolver.
        s.connect(InetSocketAddress.createUnresolved(host, 80), timeoutMs)
        return s
    }

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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    companion object { private const val TAG = "AmzTester" }
}
