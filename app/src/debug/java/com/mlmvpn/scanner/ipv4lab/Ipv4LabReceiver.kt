package com.mlmvpn.scanner.ipv4lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.*
import com.mlmvpn.scanner.engines.amnezia.*
import com.mlmvpn.scanner.engines.cfdoctor.NativeProbe
import com.mlmvpn.scanner.engines.cfnet.*
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.engines.proton.*
import com.mlmvpn.scanner.utils.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only, shell permission required. Uses saved configs internally; logs no credentials. */
class Ipv4LabReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("stop", false)) { job?.cancel(); return }
        if (job?.isActive == true) { log("busy"); return }
        val app = context.applicationContext
        val mode = intent.getStringExtra("mode") ?: "cf"
        val count = intent.getIntExtra("count", 3).coerceIn(1, 12)
        val offset = intent.getIntExtra("offset", 0).coerceAtLeast(0)
        val ipv4 = intent.getBooleanExtra("ipv4", true)
        // via: v4 (the server's IPv4, each method), cf (from inside Cloudflare), v6, or all (the real plan).
        val via = intent.getStringExtra("via") ?: "v4"
        val method = intent.getStringExtra("method")
        job = scope.launch {
            try {
                if (mode == "speed") { speed(intent.getStringExtra("url") ?: "https://speed.cloudflare.com/__down?bytes=10000000"); log("DONE speed"); return@launch }
                // A real Proton connect (the screen's own path) to the server at `host`, or the best.
                if (mode == "connect") {
                    ProtonEngine.init(app)
                    val host = intent.getStringExtra("host")
                    val id = host?.let { h -> ProtonEngine.servers(AmzEngine.store.current.servers, ProtonEngine.ids.value).firstOrNull { it.host == h }?.id }
                    // `route` (e.g. cf:PLAIN, v6:PLAIN) goes first for this server, as its own memory would put it.
                    intent.getStringExtra("route")?.let { route ->
                        if (host == null) return@let
                        val here = FluxNet.current(app)
                        val v6 = ProtonEngine.storedV6(app, host)?.takeIf { FluxNet.hasV6(app, here.network) }
                        val key = NetworkProfile.hashKey("${here.key}|${here.network?.networkHandle}|${v6 != null}|$host")
                        app.getSharedPreferences("proton_routes", Context.MODE_PRIVATE).edit().putString(key, "$route|${System.currentTimeMillis()}").commit()
                    }
                    if (id != null) ProtonEngine.connect(app, id) else ProtonEngine.connect(app)
                    log("connect asked host=$host id=${id != null} route=${intent.getStringExtra("route")}"); return@launch
                }
                if (mode == "disconnect") { ProtonEngine.disconnect(app); log("disconnect asked"); return@launch }
                // Forgets what Proton's ways measured and learned here (what=speed / udp / net / all).
                if (mode == "forget") {
                    val what = intent.getStringExtra("what") ?: "speed"
                    val prefs = app.getSharedPreferences("proton_routes", Context.MODE_PRIVATE)
                    val gone = prefs.all.keys.filter { k -> what == "all" || k.startsWith("$what|") }
                    prefs.edit().apply { gone.forEach { remove(it) } }.commit()
                    log("forgot ${gone.size} ($what)"); return@launch
                }
                // A line without IPv6 (nov6=true), or a filter that silences one way (blackhole=v6 / cf / none).
                if (mode == "simulate") {
                    if (intent.hasExtra("nov6")) ProtonStealth.testNoV6 = intent.getBooleanExtra("nov6", false)
                    intent.getStringExtra("blackhole")?.let { ProtonStealth.testBlackhole = it.takeUnless { b -> b == "none" } }
                    log("simulate nov6=${ProtonStealth.testNoV6} blackhole=${ProtonStealth.testBlackhole}"); return@launch
                }
                if (MyVpnService.isRunning || ProtonAccess.anythingUp()) { log("VPN active: test skipped"); return@launch }
                log("start mode=$mode ipv4Only=$ipv4 via=$via")
                when (mode) {
                    "proton" -> proton(app, count, offset, ipv4, via, method)
                    "carrier" -> carrier(app)
                    // The network's remembered way, as a Proton relay writes it after data passed:
                    // `route` = cf:PLAIN puts Cloudflare first on this network (none = forget).
                    "prefer" -> {
                        val here = FluxNet.current(app)
                        val route = intent.getStringExtra("route")
                        val cf = ProtonCfCarrier.possible(app)
                        val prefs = app.getSharedPreferences("proton_routes", Context.MODE_PRIVATE).edit()
                        for (v6 in listOf(true, false)) {
                            val key = "net|" + com.mlmvpn.scanner.engines.cfnet.NetworkProfile.hashKey("${here.key}|$v6|$cf")
                            if (route == null || route == "none") prefs.remove(key) else prefs.putString(key, "$route|${System.currentTimeMillis()}")
                        }
                        prefs.commit()
                        log("prefer route=$route cf=$cf")
                    }
                    "firewall" -> log("firewall=${CfNetManager.get(app).firewall()?.key}")
                    else -> cf(app, count, intent.getBooleanExtra("diagnose", false))
                }
                log("DONE $mode")
            } catch (e: CancellationException) { log("cancelled") } catch (e: Throwable) { log("ERROR ${e.javaClass.simpleName}: ${e.message?.take(160)}") }
        }
    }

    /**
     * Throughput and latency the way an app sees them: this app's own traffic, which is inside the
     * Proton / Amnezia tunnel when one is up and goes direct otherwise.
     */
    private fun speed(url: String) {
        repeat(3) { i ->
            val t0 = System.nanoTime()
            val c = runCatching { java.net.URL("https://www.gstatic.com/generate_204").openConnection() as java.net.HttpURLConnection }.getOrNull()
            val code = runCatching { c?.connectTimeout = 10_000; c?.readTimeout = 10_000; c?.responseCode }.getOrNull()
            c?.disconnect()
            log("latency #$i code=$code ms=${(System.nanoTime() - t0) / 1_000_000}")
        }
        repeat(2) { i ->
            val t0 = System.nanoTime()
            var n = 0L
            var ttfb = 0L
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = 20_000
                c.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        if (ttfb == 0L) ttfb = (System.nanoTime() - t0) / 1_000_000
                        n += r
                    }
                }
            } catch (e: Exception) { log("speed #$i error ${e.javaClass.simpleName}") } finally { c.disconnect() }
            val ms = ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
            log("speed #$i bytes=$n ttfb=$ttfb ms=$ms mbps=${"%.2f".format(n * 8.0 / 1000.0 / ms)}")
        }
    }

    /** The Cloudflare carrier alone: which config it rides, and whether a request passes through it. */
    private fun carrier(app: Context) {
        val here = FluxNet.current(app)
        log("carrier possible=${ProtonCfCarrier.possible(app)} network=${here.network != null}")
        val t0 = System.currentTimeMillis()
        val lease = ProtonCfCarrier.acquire(app, here)
        log("carrier lease=${lease != null} via=${ProtonCfCarrier.via} in ${System.currentTimeMillis() - t0} ms")
        if (lease != null) {
            // TLS through the carrier: a name the far end resolves, then a Proton server by address.
            for (target in listOf("www.google.com" to true, "151.243.141.161" to false, "185.165.242.20" to false)) {
                val t1 = System.currentTimeMillis()
                val outcome = runCatching {
                    java.net.Socket(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", lease.port))).use { s ->
                        s.soTimeout = 15_000
                        s.connect(if (target.second) java.net.InetSocketAddress.createUnresolved(target.first, 443) else java.net.InetSocketAddress(target.first, 443), 8_000)
                        val tm = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                            override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                            override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                        })
                        val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, tm, null) }
                        val tls = ctx.socketFactory.createSocket(s, if (target.second) target.first else "qwerty.com", 443, true) as javax.net.ssl.SSLSocket
                        tls.startHandshake()
                        "tls=${tls.session.protocol}"
                    }
                }.getOrElse { "${it.javaClass.simpleName}: ${it.message?.take(140)}" }
                log("carrier tls ${if (target.second) target.first else "proton"} -> $outcome in ${System.currentTimeMillis() - t1} ms")
            }
        }
        lease?.close()
    }

    private suspend fun cf(app: Context, count: Int, diagnose: Boolean) {
        val groups = GroupManager(app)
        groups.loadCloudGroups(); groups.loadScannerGroupsPublic()
        val configs = (groups.scannerGroupsFlow.value.flatMap { it.nodes } + groups.cloudGroupsFlow.value.flatMap { it.nodes })
            .filter { node -> VpnConfig.parseUri(node.uri)?.let { CfUnfilter.isCloudflareFronted(it) && !CfUnfilter.isLocalFront(it) } == true }
            .distinctBy { it.uri }.take(count)
        val manager = CfNetManager.get(app)
        XrayJsonGenerator.tlsUnfilter = NetworkSettings.tlsUnfilter(app)
        CombineEngine.ensureCoreEnv(app)
        log("CF nodes=${configs.size} scan=${manager.saved(CfStage.SCAN)?.id} connect=${manager.saved(CfStage.CONNECT)?.id}")
        val native = NativeProbe(app)
        try {
            for ((i, node) in configs.withIndex()) {
                val c = VpnConfig.parseUri(node.uri) ?: continue
                if (diagnose && manager.saved(CfStage.SCAN, c) == null) {
                    val r = manager.diagnose(CfStage.SCAN, c, maxMs = 150_000)
                    log("CF diagnosis winner=${r.winner?.id}")
                }
                val scan = XrayJsonGenerator.generateSpeedtestConfig(c, CfStage.SCAN)
                val health = XrayJsonGenerator.generateSpeedtestConfig(c)
                val live = XrayJsonGenerator.generateConfig(c, 10809, includeTun = false)
                fun endpoint(json: String): String {
                    val settings = JSONObject(json).getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings")
                    val s = (settings.optJSONArray("vnext") ?: settings.getJSONArray("servers")).getJSONObject(0)
                    return "${s.optString("address")}:${s.optInt("port")}" // compare only; never logged
                }
                log("CF row=$i v4=${!c.address.contains(':')} route=${manager.runtimeStrategy(CfStage.CONNECT, c)?.id} endpointPreserved=${endpoint(scan) == endpoint(health) && endpoint(scan) == endpoint(live)}")
                val before = CombineEngine.measureDelay(XrayJsonGenerator.generateSpeedtestConfig(c, null), timeoutMs = 12_000)
                val scanned = CombineEngine.measureDelay(scan, timeoutMs = 12_000)
                val healthy = CombineEngine.measureUri(app, node.uri)
                log("CF row=$i bareMs=$before scanMs=$scanned healthMs=$healthy")
                if (healthy > 0) {
                    val (state, result) = native.withCore(live) { port -> CfDataProbe.validate(port) }
                    log("CF row=$i live=$state transfer=${result?.ok} detail=${result?.detail}")
                }
            }
        } finally { native.release() }
    }

    private suspend fun proton(app: Context, count: Int, offset: Int, ipv4: Boolean, via: String, method: String?) {
        ProtonEngine.init(app)
        if (AmzEngine.progress.value.running) { log("Proton ping already running"); return }
        val all = ProtonEngine.servers(AmzEngine.store.current.servers, ProtonEngine.ids.value)
        val servers = all.drop(offset).take(count)
        val here = FluxNet.current(app)
        val native = NativeProbe(app)
        log("Proton saved=${all.size} testing=${servers.size} hasV6=${FluxNet.hasV6(app, here.network)}")
        val methods = method?.let { m -> ProtonMethod.values().filter { it.name == m } } ?: ProtonRoutePlan.V4_METHODS
        try {
            for ((i, server) in servers.withIndex()) {
                val v6 = if (ipv4 && via != "v6" && via != "u6" && via != "all") null else ProtonEngine.storedV6(app, server.host)?.takeIf { FluxNet.hasV6(app, here.network) }
                val cf = (via == "cf" || via == "all") && ProtonCfCarrier.possible(app)
                val report = { route: String, ok: Boolean -> log("Proton row=${i + offset} route=$route data=$ok") }
                // One way only (u6 / u4 / v6 / cf / v4) or the real plan (all).
                val single = via != "all"
                val plan = when (via) {
                    "cf" -> ProtonRoutePlan(false, cf, only = setOf(ProtonVia.CF), result = report)
                    "v6" -> ProtonRoutePlan(v6 != null, only = setOf(ProtonVia.V6), result = report)
                    "u6" -> ProtonRoutePlan(v6 != null, only = setOf(ProtonVia.U6), result = report)
                    "u4" -> ProtonRoutePlan(false, only = setOf(ProtonVia.U4), result = report)
                    "all" -> ProtonRoutePlan(v6 != null, cf, result = report)
                    else -> ProtonRoutePlan(false, v4Methods = methods, only = setOf(ProtonVia.V4), result = report)
                }
                if (plan.size == 0) { log("Proton row=${i + offset} host=${server.host} no route for via=$via"); continue }
                log("Proton row=${i + offset} host=${server.host} routes=${plan.size}")
                val wgPort = WgConfig.parseConf(server.raw)?.endpointPort ?: 51820
                ProtonStealth(server.host, v6, here.network, plan, wgPort) { ProtonCfCarrier.acquire(app, here) }.use { relay ->
                    do {
                        currentCoroutineContext().ensureActive()
                        val wg = WgConfig.parseConf(server.raw) ?: break
                        val outbound = AmzEngine.relayed(wg, relay).xrayOutbound("proxy", null)
                        val json = JSONObject().put("outbounds", JSONArray().put(outbound)).toString()
                        val (state, data) = native.withCore(json) { port -> CfDataProbe.validate(port) }
                        log("Proton row=${i + offset} route=${relay.routeId ?: "none"} core=$state transfer=${data?.ok} detail=${data?.detail}")
                        if (data?.ok == true) { relay.validated(); break }
                    } while (!single && relay.retryAfterFailure())
                }
            }
        } finally { native.release() }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var job: Job? = null
        private fun log(text: String) { Log.i("Ipv4Lab", text) }
    }
}
