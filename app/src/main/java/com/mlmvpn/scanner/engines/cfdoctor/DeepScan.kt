package com.mlmvpn.scanner.engines.cfdoctor

import android.content.Context
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/** What a config is, without anything that identifies it. */
internal fun shapeOf(c: VpnConfig) = ConfigProbes.shape(c)

/** The address of a config, as much as the report may say: the edge range, or what its name resolves to. */
internal fun addressKind(c: VpnConfig, env: NetEnv): String {
    val a = c.address
    if (a.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) return "ip:" + (if (CfBogon.isCloudflare(a)) a else EdgeBook.mask16(a))
    if (a.contains(':')) return "ip6:" + EdgeBook.mask16(a)
    val ips = runCatching { env.resolve(a).mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
    val kind = if (a.endsWith(".workers.dev")) "workers.dev" else if (a.endsWith(".pages.dev")) "pages.dev" else "domain"
    return "$kind->" + when {
        ips.isEmpty() -> "unresolved"
        ips.any { CfBogon.isBogon(it) } -> "bogus(" + ips.joinToString("/") { EdgeBook.mask16(it) } + ")"
        else -> ips.take(3).joinToString("/") { if (CfBogon.isCloudflare(it)) it else EdgeBook.mask16(it) }
    }
}

/**
 * ST4, the way the real scanner works: 100 addresses from every Cloudflare range (IPv6 too when
 * the line has it), plus the addresses the user's own scanner saved, each taken through the user's
 * base config end to end. Then the app's own core on the best of them, to confirm.
 */
class ScanStage(private val app: Context, private val env: NetEnv, private val out: DeepReport, private val emit: (Evidence) -> Unit) {

    /**
     * [res] the plain pass; [frag] a second pass with the ClientHello cut up, for addresses that
     * got through TLS and then went silent -- on some lines the filter judges the session by its
     * TLS fingerprint, which this client cannot dress up like a browser, while the app's core can
     * (2026-10-03: IPv4 failed "proxy" here and the core connected through the same address).
     */
    data class Hit(val ip: String, val range: String, val res: DeepTunnel.Res, val frag: DeepTunnel.Res? = null) {
        val ok: Boolean get() = res.ok || frag?.ok == true
        val ms: Long get() = if (res.ok) res.ms else frag?.ms ?: res.ms
    }

    var best: List<Hit> = emptyList(); private set

    suspend fun run(base: VpnConfig?, baseWhy: String, v6: Boolean) {
        val raw = base?.let { ConfigProbes.rawConfig(it) }
        out.section("SCAN", "base", "config" to (base?.let { shapeOf(it) } ?: "none"), "from" to baseWhy,
            "mode" to if (raw != null) "full_tunnel" else if (base != null) "tls_only" else "edge_only")
        val ranges = com.mlmvpn.scanner.data.CloudflareScanner.DEFAULT_RANGES + "172.64.0.0/13"
        val sample = LinkedHashMap<String, String>() // ip -> range
        // 100 per range; 40 on a phone that can only hold a few connections at once (ThreadBudget),
        // or the scan would not finish in its time.
        val parallel = com.mlmvpn.scanner.utils.ThreadBudget.parallel(48)
        val perRange = if (parallel >= 40) 100 else 40
        val rnd = java.security.SecureRandom()
        for (r in ranges) {
            val size = 1L shl (32 - r.substringAfter('/').toInt())
            val want = minOf(perRange.toLong(), size - 2).toInt()
            var guard = 0
            val chosen = HashSet<String>()
            while (chosen.size < want && guard++ < want * 5) chosen += EdgeGroups.random(r, rnd)
            chosen.forEach { sample[it] = r }
        }
        if (v6) com.mlmvpn.scanner.data.CfFamily.sampleV6(64).forEach { sample[it] = "v6:" + it.split(':').take(3).joinToString(":") }
        // What the user's own scanner kept: does it still work here, now?
        val archive = runCatching {
            val gm = com.mlmvpn.scanner.data.GroupManager(app); gm.loadIpArchives()
            gm.ipArchives.find { it.id == com.mlmvpn.scanner.data.GroupManager.UNIFIED_ARCHIVE }?.ips.orEmpty()
        }.getOrDefault(emptyList()).map { it.trim().removePrefix("[").substringBefore(']').let { s -> if (s.count { ch -> ch == ':' } == 1) s.substringBefore(':') else s } }
            .filter { CfBogon.isCloudflare(it) }.distinct().take(100)
        archive.forEach { sample.putIfAbsent(it, "archive") }
        out.line("SAMPLE", "SCAN", "all", "ips" to sample.size, "per_range" to perRange, "parallel" to parallel, "archive" to archive.size, "v6" to v6)

        val tunnel = DeepTunnel(env)
        val gate = Semaphore(parallel)
        val firstPass = coroutineScope {
            sample.entries.map { (ip, range) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val res = when {
                            raw != null -> tunnel.test(raw, DeepTunnel.Opt(ip, base!!.port, sni = raw.sni, host = raw.host, path = raw.path, deadlineMs = 6_000))
                            else -> tlsOnly(ip, base)
                        }
                        Hit(ip, if (ip in archive) "archive" else range, res)
                    }
                }
            }.awaitAll()
        }
        // Second pass, fragmented, for what went silent after TLS.
        fun silentAfterTls(r: DeepTunnel.Res) = r.tlsMs > 0 && (r.stage == "ws" || r.stage == "proxy" || r.stage.startsWith("egress") || r.stage == "stall")
        val hits = if (raw == null) firstPass else coroutineScope {
            firstPass.map { h ->
                async(Dispatchers.IO) {
                    if (!silentAfterTls(h.res)) h else gate.withPermit {
                        h.copy(frag = tunnel.test(raw, DeepTunnel.Opt(h.ip, base!!.port, sni = raw.sni, host = raw.host, path = raw.path,
                            recipe = TlsVariant.F12.recipe, deadlineMs = 6_000)))
                    }
                }
            }.awaitAll()
        }
        tunnel.shutdown()
        if (raw == null) runCatching { tlsClient.shutdown() }

        // Per range: how far its addresses got.
        fun pastWs(st: String) = st == "ok" || st.startsWith("egress") || st == "proxy" || st == "stall"
        val coreByRange = HashMap<String, Int>()
        hits.groupBy { it.range }.forEach { (range, list) ->
            val ok = list.filter { it.ok }
            out.line("RANGE", "SCAN", range, "n" to list.size,
                "tcp" to list.count { it.res.tcpMs > 0 },
                "tls" to list.count { it.res.tlsMs > 0 },
                "ws" to list.count { pastWs(it.res.stage) },
                "ok" to ok.size, "ok_plain" to list.count { it.res.ok }, "ok_only_fragmented" to list.count { !it.res.ok && it.frag?.ok == true },
                "med_ms" to ok.map { it.ms }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] },
                "fail" to list.filterNot { it.ok }.groupBy { it.res.stage }.mapValues { it.value.size }.entries.joinToString(",") { "${it.key}${it.value}" })
        }
        // Every address that worked, with its time, and every failure by stage (the raw material for "which IP works where").
        hits.filter { it.ok }.sortedBy { it.ms }.chunked(40).forEach { chunk ->
            out.raw("IPS_OK " + chunk.joinToString(" ") { "${it.ip}:${it.ms}" + if (!it.res.ok) ":frag" else "" })
        }
        hits.filterNot { it.ok }.groupBy { it.res.stage }.forEach { (stage, list) ->
            list.chunked(60).forEach { chunk -> out.raw("IPS_FAIL ${DeepReport.tok(stage)} " + chunk.joinToString(" ") { it.ip }) }
        }

        // The app's own core -- what the real scanner uses -- on up to two addresses per range that
        // got through TLS (working ones first), and four IPv6 ones: the per-range answer that counts.
        var coreOk = 0
        val coreHits = ArrayList<Hit>()
        if (base != null) {
            val picks = hits.filter { !it.range.startsWith("v6:") && it.res.tlsMs > 0 }.groupBy { it.range }
                .flatMap { (_, l) -> l.sortedWith(compareBy<Hit>({ if (it.ok) 0 else 1 }, { it.ms })).take(2) } +
                hits.filter { it.range.startsWith("v6:") && it.res.tlsMs > 0 }.sortedWith(compareBy<Hit>({ if (it.ok) 0 else 1 }, { it.ms })).take(4)
            val native = NativeProbe(app)
            for (h in picks) {
                currentCoroutineContext().ensureActive()
                val c = base.copy().also { if (it.sni.isBlank()) it.sni = it.wsHost.ifBlank { it.address }; if (it.wsHost.isBlank()) it.wsHost = it.address; it.address = h.ip }
                val r = runCatching { native.measure(XrayJsonGenerator.generateSpeedtestConfig(c, strategyStage = null), 7_000) }.getOrNull()
                if (r?.ms != null) { coreOk++; coreHits += h; coreByRange[h.range] = (coreByRange[h.range] ?: 0) + 1 }
                out.line("CORE", "SCAN", h.ip, "range" to h.range, "raw" to h.res.stage, "frag" to h.frag?.stage, "core" to (if (r?.ms != null) "ok" else r?.code ?: "error"), "ms" to r?.ms)
            }
            native.release()
            coreByRange.forEach { (range, n) -> out.line("RANGE_CORE", "SCAN", range, "core_ok" to n) }
        }
        // The connection stage uses these: proven by the core first, then the raw passes, one per range.
        best = (coreHits.sortedBy { it.ms } + hits.filter { it.ok }.sortedBy { it.ms }).distinctBy { it.range }.take(6)
        val okCount = hits.count { it.ok }
        val verdict = when {
            base == null -> "no_base_config"
            coreOk > 0 -> "ok"
            okCount > 0 -> "raw_ok_core_fails"
            hits.none { it.res.tcpMs > 0 } -> "no_tcp_any_range"
            hits.none { it.res.tlsMs > 0 } -> "tls_blocked_all"
            hits.none { pastWs(it.res.stage) } -> "ws_refused_all"
            else -> "egress_fails_all"
        }
        out.verdict("SCAN", "all", verdict, "ok" to okCount, "of" to hits.size, "core_ok" to coreOk)
        emit(Evidence("ST4", "scan", if (verdict == "ok") "ok" else "scan.$verdict", numbers = mapOf("terminal" to 1L, "sample" to hits.size.toLong(), "ok" to okCount.toLong())))
    }

    /** One client for the whole scan: each has its own deadline thread. */
    private val tlsClient by lazy { WayClient(env, EdgeBook(env)) }

    private fun tlsOnly(ip: String, base: VpnConfig?): DeepTunnel.Res {
        val host = base?.let { it.sni.ifBlank { it.wsHost.ifBlank { it.address } } }?.takeIf { it.isNotBlank() && !it.first().isDigit() } ?: "www.cloudflare.com"
        val r = tlsClient.exchange(Way(AddrSource.SYSTEM), WayClient.Req(host, "/", "HEAD", port = base?.port ?: 443, deadlineMs = 6_000), fixedIp = ip)
        return DeepTunnel.Res(if (r.answered) "ok" else r.phase, r.ms, r.tcpMs, r.tlsMs)
    }
}

/**
 * ST3 and the data: each of the user's configs, on its own address and on the best addresses the
 * scan found, raw (every TLS trick, other ports, plain HTTP as PattN does) and through the app's
 * own core (fingerprints, ECH, cipher suites, fragment). Then real data both ways on what worked.
 */
class ConnectStage(private val app: Context, private val env: NetEnv, private val out: DeepReport, private val emit: (Evidence) -> Unit) {

    suspend fun run(configs: List<VpnConfig>, scanBest: List<String>, v6: Boolean, sources: List<String> = emptyList()) {
        if (configs.isEmpty()) {
            out.verdict("CONNECT", "-", "no_config")
            emit(Evidence("ST3", "config", "config.none", numbers = mapOf("terminal" to 1L)))
            return
        }
        val tunnel = DeepTunnel(env)
        val native = NativeProbe(app)
        try {
            // Every variant through the core on the first config; the rest get their addresses (time: one core at a time).
            configs.forEachIndexed { i, c ->
                runCatching { one("config${i + 1}", c, scanBest, v6, tunnel, native, deep = i == 0, source = sources.getOrElse(i) { "own" }) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; out.verdict("CONNECT", "config${i + 1}", "doctor_error:${it.javaClass.simpleName}") }
            }
        } finally {
            tunnel.shutdown(); native.release()
        }
    }

    private suspend fun one(tag: String, c: VpnConfig, scanBest: List<String>, v6: Boolean, tunnel: DeepTunnel, native: NativeProbe, deep: Boolean, source: String) {
        val cf = ConfigProbes.isCloudflare(c)
        out.section("CONNECT", tag, "source" to source, "shape" to shapeOf(c), "address" to withContext(Dispatchers.IO) { addressKind(c, env) }, "cf" to cf)
        val raw = ConfigProbes.rawConfig(c)

        // Addresses: its own, and for a Cloudflare config the scan's best, the app's edge, IPv6.
        val addrs = LinkedHashMap<String, String>() // label -> address ("" = the config's own, resolved by the phone)
        addrs["own"] = ""
        if (cf) {
            scanBest.take(2).forEachIndexed { k, ip -> addrs["scan${k + 1}"] = ip }
            withContext(Dispatchers.IO) { com.mlmvpn.scanner.data.CfEdge.find(app, c.sni.ifBlank { c.wsHost.ifBlank { com.mlmvpn.scanner.data.CfEdge.DEFAULT_HOST } }, 1) }
                .firstOrNull()?.let { if (it !in addrs.values) addrs["cfedge"] = it }
            if (v6) addrs["v6"] = com.mlmvpn.scanner.data.CfFamily.sampleV6(1).first()
        }
        fun ipOf(label: String): String? = addrs[label]?.ifEmpty {
            runCatching { env.resolve(c.address).firstOrNull { !CfBogon.isBogon(it.hostAddress.orEmpty()) }?.hostAddress }.getOrNull()
        }

        var anyOk = false
        val working = ArrayList<Triple<String, String, String>>() // address label, ip, way that worked (raw)

        // ---- raw: every address plain, then the tricks on the own address and the best scanned one.
        if (raw != null) {
            val gate = Semaphore(8)
            data class T(val label: String, val ip: String?, val way: String, val opt: (String) -> DeepTunnel.Opt?)
            val base: (String) -> DeepTunnel.Opt = { ip -> DeepTunnel.Opt(ip, c.port, sni = raw.sni, host = raw.host, path = raw.path) }
            val tasks = ArrayList<T>()
            for (label in addrs.keys) tasks += T(label, ipOf(label), "tls") { ip -> base(ip) }
            val trickOn = listOfNotNull("own", addrs.keys.firstOrNull { it.startsWith("scan") })
            for (label in trickOn) {
                val ip = ipOf(label)
                for (v in listOf(TlsVariant.TLS12, TlsVariant.TLS13, TlsVariant.SNI_MIXED, TlsVariant.F1, TlsVariant.F3, TlsVariant.F4, TlsVariant.F6, TlsVariant.F8, TlsVariant.F12, TlsVariant.F14)) {
                    tasks += T(label, ip, v.key) { x -> base(x).copy(recipe = v.recipe, protocol = v.protocol,
                        sni = if (v == TlsVariant.SNI_MIXED) raw.sni.mapIndexed { n, ch -> if (n % 2 == 0) ch.uppercaseChar() else ch.lowercaseChar() }.joinToString("") else raw.sni) }
                }
                for (p in listOf(2053, 2083, 2087, 2096, 8443).filter { it != c.port }) tasks += T(label, ip, "port$p") { x -> base(x).copy(port = p) }
                // PattN's way: the same worker over plain HTTP (no TLS at all) on Cloudflare's HTTP ports.
                for (p in listOf(80, 8080, 2052)) tasks += T(label, ip, "plain$p") { x -> base(x).copy(port = p, tls = false) }
            }
            val results = coroutineScope {
                tasks.map { t ->
                    async(Dispatchers.IO) { gate.withPermit { t to (t.ip?.let { ip -> t.opt(ip)?.let { tunnel.test(raw, it) } } ?: DeepTunnel.Res("dns", 0)) } }
                }.awaitAll()
            }
            for ((t, r) in results) {
                out.line("RAW", "CONNECT", tag, "addr" to t.label, "ip" to t.ip?.let { if (CfBogon.isCloudflare(it)) it else EdgeBook.mask16(it) }, "way" to t.way,
                    "result" to r.stage, "ms" to r.ms, "tcp_ms" to r.tcpMs.takeIf { it > 0 }, "tls_ms" to r.tlsMs.takeIf { it > 0 })
                if (r.ok) { anyOk = true; working += Triple(t.label, t.ip.orEmpty(), t.way) }
            }
            // Data through the raw tunnel, on the first two that worked: the worker's own throughput, no core involved.
            for ((label, ip, way) in working.filter { it.third == "tls" || it.third.startsWith("plain") }.distinctBy { it.first }.take(2)) {
                val opt = base(ip).let { if (way.startsWith("plain")) it.copy(port = way.removePrefix("plain").toInt(), tls = false) else it }
                var r = withContext(Dispatchers.IO) { tunnel.test(raw, opt.copy(downloadBytes = 2_000_000, deadlineMs = 10_000)) }
                out.line("XFER", "CONNECT", tag, "via" to "raw:$label:$way", "result" to r.stage, "down_bytes" to r.downBytes, "down_kbps" to r.kbps, "ms" to r.downMs)
                if (!r.ok && opt.tls) {
                    r = withContext(Dispatchers.IO) { tunnel.test(raw, opt.copy(recipe = TlsVariant.F12.recipe, downloadBytes = 2_000_000, deadlineMs = 10_000)) }
                    out.line("XFER", "CONNECT", tag, "via" to "raw:$label:$way+frag75d15", "result" to r.stage, "down_bytes" to r.downBytes, "down_kbps" to r.kbps, "ms" to r.downMs)
                }
            }
        } else out.line("RAW", "CONNECT", tag, "result" to "not_applicable_${c.protocol}_${c.network}_${c.tls}")

        // ---- the app's own core.
        data class N(val label: String, val ip: String?, val variant: String)
        val nTasks = ArrayList<N>()
        for (label in addrs.keys) nTasks += N(label, addrs[label], "baseline")
        if (deep) {
            val target = working.firstOrNull { it.third == "tls" }?.first ?: "own"
            for (v in listOf("C2.chrome", "C2.firefox", "C2.ios", "C2.edge", "C2.qq", "C2.random", "C2.randomized", "C8", "C9.udp", "C9.google", "C3.F12"))
                nTasks += N(target, addrs[target], v)
        }
        val nativeOk = ArrayList<N>()
        for (t in nTasks) {
            currentCoroutineContext().ensureActive()
            val cfg = c.copy().also { if (!t.ip.isNullOrEmpty()) { if (it.sni.isBlank()) it.sni = it.wsHost.ifBlank { it.address }; if (it.wsHost.isBlank()) it.wsHost = it.address; it.address = t.ip } }
            val json = runCatching { XrayJsonGenerator.generateSpeedtestConfig(cfg, strategyStage = null).let { if (t.variant == "baseline") it else ConfigVariants.apply(it, t.variant) } }.getOrNull()
            val r = if (json == null) NativeProbe.Result(null, "config_invalid") else runCatching { native.measure(json, 9_000) }.getOrElse { NativeProbe.Result(null, "error") }
            out.line("CORE", "CONNECT", tag, "addr" to t.label, "variant" to t.variant, "result" to (if (r.ms != null) "ok" else r.code), "ms" to r.ms)
            if (r.ms != null) { anyOk = true; nativeOk += t }
        }

        // ---- real data through the core: download and upload, on up to two that worked.
        for (t in nativeOk.distinctBy { it.label }.take(2)) {
            val cfg = c.copy().also { if (!t.ip.isNullOrEmpty()) { if (it.sni.isBlank()) it.sni = it.wsHost.ifBlank { it.address }; if (it.wsHost.isBlank()) it.wsHost = it.address; it.address = t.ip } }
            val json = runCatching { XrayJsonGenerator.generateSpeedtestConfig(cfg, strategyStage = null).let { if (t.variant == "baseline") it else ConfigVariants.apply(it, t.variant) } }.getOrNull() ?: continue
            val (code, x) = native.withCore(json) { port -> transfer(port) }
            out.line("XFER", "CONNECT", tag, "via" to "core:${t.label}:${t.variant}", "result" to code,
                "down_kbps" to x?.downKbps, "down_bytes" to x?.downBytes, "up_kbps" to x?.upKbps, "up_bytes" to x?.upBytes, "note" to x?.note)
        }

        out.verdict("CONNECT", tag, if (anyOk) "ok" else "fails_every_way",
            "raw_ok" to working.joinToString(",") { "${it.first}:${it.third}" }.ifEmpty { "-" },
            "core_ok" to nativeOk.joinToString(",") { "${it.label}:${it.variant}" }.ifEmpty { "-" })
        emit(Evidence("ST3", "$tag:${shapeOf(c)}", if (anyOk) "ok" else "fails_every_way", numbers = mapOf("terminal" to 1L)))
    }

    data class Xfer(val downBytes: Long, val downKbps: Long, val upBytes: Long, val upKbps: Long, val note: String)

    /** 3 MB down and 1 MB up through the core's SOCKS port, to hosts that are not on Cloudflare (a Worker cannot reach those). */
    private fun transfer(port: Int): Xfer {
        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
        val http = okhttp3.OkHttpClient.Builder().proxy(proxy).connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(40, java.util.concurrent.TimeUnit.SECONDS).build()
        var note = ""
        var down = 0L; var dms = 1L
        runCatching {
            val t = System.nanoTime()
            http.newCall(okhttp3.Request.Builder().url("http://${DeepTunnel.DOWNLOAD_HOST}${DeepTunnel.DOWNLOAD_PATH}").build()).execute().use { r ->
                val inp = r.body?.byteStream() ?: return@use
                val buf = ByteArray(16_384)
                while (down < 3_000_000) { val n = inp.read(buf); if (n < 0) break; down += n }
            }
            dms = ((System.nanoTime() - t) / 1_000_000).coerceAtLeast(1)
            if (down < 100_000) note += "down:short_${down}B;"
        }.onFailure { note += "down:${it.javaClass.simpleName};" }
        var up = 0L; var ums = 0L
        runCatching {
            val payload = ByteArray(1_000_000) { (it % 251).toByte() }
            val t = System.nanoTime()
            val body = object : okhttp3.RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                override fun contentLength() = payload.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { var p = 0; while (p < payload.size) { val n = minOf(16_384, payload.size - p); sink.write(payload, p, n); sink.flush(); p += n; up = p.toLong() } }
            }
            http.newCall(okhttp3.Request.Builder().url("http://speedtest.tele2.net/upload.php").post(body).build()).execute().use { it.body?.bytes() }
            ums = ((System.nanoTime() - t) / 1_000_000).coerceAtLeast(1)
        }.onFailure { note += "up:${it.javaClass.simpleName};" }
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        // A speed only for a transfer that finished: a timeout used to divide by 1 ms.
        return Xfer(down, if (down > 0) down * 8 / dms else 0, up, if (ums > 0) up * 8 / ums else 0, note.ifEmpty { "" })
    }
}
