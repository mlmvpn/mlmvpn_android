package com.mlmvpn.scanner.wslab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.engines.windscribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Debug-only Windscribe lab (shell permission). Logs no session, password or key.
 *   am broadcast -n com.mlmvpn.scanner/.wslab.WsLabReceiver --es mode info|refresh|probe|connect|disconnect
 *     probe: --ei count 4   connect: --es proto WIREGUARD|OPENVPN_TCP|... --es dc <dcId>
 */
class WsLabReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val mode = intent.getStringExtra("mode") ?: "info"
        WindscribeEngine.init(app)
        scope.launch {
            withTimeoutOrNull(5_000) { while (WindscribeEngine.data.value.accounts.isEmpty()) delay(100) }
            try {
                when (mode) {
                    "info" -> info()
                    "refresh" -> { WindscribeEngine.refresh(); delay(300); WindscribeEngine.busy.first { !it }; log("refresh error=${WindscribeEngine.error.value}"); info() }
                    "probe" -> probe(app, intent.getIntExtra("count", 4))
                    "connect" -> {
                        val proto = intent.getStringExtra("proto")?.let { p -> WsProtocol.values().firstOrNull { it.name == p } }
                        val dc = intent.getStringExtra("dc")
                        val server = dc?.let { d -> WindscribeEngine.data.value.servers.filter { it.dcId == d }.minByOrNull { it.load ?: 100 } }
                        WindscribeEngine.selectProtocol(proto); WindscribeEngine.selectServer(server?.id)
                        delay(300); WindscribeEngine.busy.first { !it }
                        WindscribeEngine.connect(app, searchAgain = intent.getBooleanExtra("again", false))
                        log("connect asked proto=$proto dc=$dc server=${server?.hostname}")
                    }
                    "disconnect" -> WindscribeEngine.disconnect()
                    // Breaks the shared WARP carrier under a running tunnel: the watchdog must repair it.
                    "breakwarp" -> com.mlmvpn.scanner.engines.windscribe.WsWarpCarrier.closeShared()
                    "testall" -> WindscribeEngine.connect(app, benchmark = true, all = true, allOf = intent.getStringExtra("dc"))
                    "results" -> WindscribeEngine.results.value.forEach { r ->
                        val srv = WindscribeEngine.data.value.servers.firstOrNull { it.id == r.method.serverId }
                        log("result ${srv?.city} ${r.method.protocol} cf=${r.method.cf} ok=${r.success} ping=${r.latencyMs} ${r.successfulSamples}/${r.samples} bps=${r.downloadBps} connect=${r.connectMs}")
                    }
                    // Exit and a 5 MB sample on the app's own route (inside an OpenVPN tunnel; outside an Xray one).
                    "ip" -> {
                        val c = java.net.URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 10_000; c.readTimeout = 10_000
                        log("trace " + c.inputStream.bufferedReader().readLines().filter { it.startsWith("ip=") || it.startsWith("loc=") }.joinToString(" "))
                        val t0 = System.nanoTime()
                        val d = java.net.URL("https://speed.cloudflare.com/__down?bytes=5000000").openConnection() as java.net.HttpURLConnection
                        d.connectTimeout = 10_000; d.readTimeout = 15_000
                        val n = d.inputStream.use { inp -> val b = ByteArray(65536); var t = 0L; while (true) { val r = inp.read(b); if (r < 0) break; t += r }; t }
                        val sec = (System.nanoTime() - t0) / 1e9
                        log("speed ${"%.1f".format(n * 8 / sec / 1e6)} Mbps ($n bytes in ${"%.1f".format(sec)} s)")
                    }
                }
                log("DONE $mode")
            } catch (e: Throwable) { log("FAILED $mode ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private fun info() {
        val d = WindscribeEngine.data.value
        val a = d.active
        log("accounts=${d.accounts.size} active=${a?.username} premium=${a?.premium} status=${a?.status} valid=${a?.sessionValid} remaining=${a?.remaining} storageError=${d.storageError}")
        log("ports=" + d.ports.entries.joinToString { "${it.key}=${it.value}" })
        log("protocols=${WindscribeEngine.supportedProtocols.value} selected=${d.selectedServer} protocol=${d.protocol}")
        val dcs = d.servers.groupBy { it.dcId }
        log("servers=${d.servers.size} datacenters=${dcs.size}")
        dcs.values.forEach { nodes ->
            val s = nodes.minBy { it.load ?: 100 }
            log("dc=${s.dcId} ${s.country} ${s.city} ${s.nick} host=${s.hostname} ip=${s.ip} ip2=${s.ip2} ip3=${s.ip3} wg=${s.publicKey.isNotBlank()} x509=${s.x509} load=${s.load} nodes=${nodes.size}")
        }
    }

    /** From the bare network: TCP connect per endpoint/port, and a TLS hello on the TLS-carried ones. */
    private suspend fun probe(app: Context, count: Int) = coroutineScope {
        val d = WindscribeEngine.data.value
        val net = FluxNet.current(app).network ?: run { log("no network"); return@coroutineScope }
        val servers = d.servers.groupBy { it.dcId }.values.map { n -> n.minBy { it.load ?: 100 } }
            .sortedBy { it.load ?: 100 }.take(count)
        val jobs = servers.flatMap { s ->
            listOf(WsProtocol.OPENVPN_TCP to s.ip2, WsProtocol.STEALTH to s.ip3, WsProtocol.WSTUNNEL to s.ip).flatMap { (p, host) ->
                d.ports[p].orEmpty().map { port ->
                    async(Dispatchers.IO) {
                        val t0 = System.nanoTime()
                        val r = runCatching {
                            net.socketFactory.createSocket().use { sock ->
                                sock.connect(InetSocketAddress(host, port), 6_000)
                                val tcp = (System.nanoTime() - t0) / 1_000_000
                                if (p == WsProtocol.OPENVPN_TCP) "tcp ${tcp}ms" else {
                                    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustAll), null) }
                                    val tls = ctx.socketFactory.createSocket(sock, s.hostname, port, false) as SSLSocket
                                    tls.soTimeout = 6_000
                                    tls.startHandshake()
                                    "tcp ${tcp}ms tls ${(System.nanoTime() - t0) / 1_000_000}ms ${tls.session.protocol} cn=${(tls.session.peerCertificates.firstOrNull() as? X509Certificate)?.subjectX500Principal?.name}"
                                }
                            }
                        }.getOrElse { "FAIL ${it.javaClass.simpleName} ${it.message?.take(60)}" }
                        log("probe ${s.city} $p $host:$port -> $r")
                    }
                }
            }
        }
        jobs.awaitAll()
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private fun log(s: String) = Log.i("WsLab", s)
    }
}
