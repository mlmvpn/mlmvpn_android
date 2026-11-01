package com.mlmvpn.scanner.tilelab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mlmvpn.core.tunnel.CoreConfig
import com.mlmvpn.scanner.data.CfFamily
import com.mlmvpn.scanner.ui.tunnel.Transport
import com.mlmvpn.scanner.ui.tunnel.TunnelController
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

/**
 * Debug only (shell permission): drives the MASQUE / WireGuard / WARP-in-WARP tiles exactly as the
 * home screen does, so a connect can be timed from adb. Logs under tag TileLab; no credentials.
 *
 *   adb shell am broadcast -n com.mlmvpn.scanner/.tilelab.TileLabReceiver --es mode tile --es proto wireguard
 *   modes: tile (proto masque|wireguard|gool) · off · config (proto) · state · nov6 (--ez on true|false)
 *   Amnezia: quicpool (--ei n, --ez bind, --ez test: self-signed Hysteria2 certificates, then the
 *   real test) · amz (--es host: connect; the app must be in front) · amzoff
 */
class TileLabReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        TunnelController.attach(app)
        when (intent.getStringExtra("mode")) {
            "tile" -> {
                val proto = intent.getStringExtra("proto") ?: "masque"
                val t = Transport.values().firstOrNull { it.value == proto } ?: return log("no transport $proto")
                TunnelController.connect(app, t)
                log("connect $proto asked")
            }
            "off" -> { TunnelController.disconnect(app); log("disconnect asked") }
            // An Xray freedom outbound with PattNG's tlshello-0-len finalmask behind a loopback SOCKS
            // port, kept up for `secs` (default 240): `aether --upstream socks5://127.0.0.1:<port>`
            // from adb then dials through it. The port is logged and written to cache/xfrag.port.
            "xfrag" -> {
                xfrag?.cancel()
                val secs = intent.getIntExtra("secs", 240).coerceIn(10, 900)
                xfrag = labScope.launch {
                    val cfg = org.json.JSONObject().put("outbounds", org.json.JSONArray().put(
                        org.json.JSONObject().put("tag", "proxy").put("protocol", "freedom")
                            .put("settings", org.json.JSONObject().put("domainStrategy", "AsIs"))
                            .put("streamSettings", org.json.JSONObject().put("finalmask", com.mlmvpn.scanner.engines.cfnet.CfShape.finalMaskTlsHello())),
                    ))
                    val (code, _) = com.mlmvpn.scanner.engines.cfdoctor.NativeProbe(app).withCore(cfg.toString()) { port ->
                        log("xfrag socks on 127.0.0.1:$port for ${secs}s")
                        runCatching { java.io.File(app.cacheDir, "xfrag.port").writeText(port.toString()) }
                        kotlinx.coroutines.delay(secs * 1000L)
                    }
                    log("xfrag ended: $code")
                }
            }
            "xfrag-stop" -> { xfrag?.cancel(); log("xfrag stop asked") }
            // A WebSocket upgrade over TLS to each `hosts` name (comma separated), from this app's own
            // line: 101 is what a GitHub-tunnel relay must answer (runner/relays.mjs › wsProbe).
            "wsprobe" -> {
                val hosts = intent.getStringExtra("hosts").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                labScope.launch {
                    for (h in hosts) {
                        val t0 = System.currentTimeMillis()
                        val r = runCatching {
                            val f = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                            (f.createSocket() as javax.net.ssl.SSLSocket).use { s ->
                                s.connect(java.net.InetSocketAddress(h, 443), 10_000)
                                s.soTimeout = 10_000
                                s.sslParameters = s.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName(h)) }
                                s.startHandshake()
                                s.outputStream.write(("GET /x HTTP/1.1\r\nHost: $h\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
                                    "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nbypass-tunnel-reminder: 1\r\n\r\n").toByteArray())
                                s.inputStream.bufferedReader().readLine().orEmpty()
                            }
                        }.getOrElse { it.javaClass.simpleName + ": " + it.message }
                        log("wsprobe $h -> $r in ${System.currentTimeMillis() - t0} ms")
                    }
                }
            }
            // Off the main thread, as the screen builds it: the IPv6 check opens a socket.
            "config" -> { val proto = intent.getStringExtra("proto"); Thread { log(redact(CoreConfig.json(app, proto))) }.start() }
            "state" -> log("state=${TunnelController.state.value}")
            // Pretend this line has no IPv6 (an IPv4-only line), for the family choice.
            "nov6" -> { CfFamily.testNoV6 = intent.getBooleanExtra("on", true); log("nov6=${CfFamily.testNoV6}") }
            // A tile setting by key (scan mode, transport...), or removed with --es value none.
            "pref" -> {
                val key = intent.getStringExtra("key") ?: return log("no key")
                val value = intent.getStringExtra("value")
                app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().apply {
                    if (value == null || value == "none") remove(key) else putString(key, value)
                }.commit()
                log("pref $key=${value ?: "none"}")
            }
            // The exit country of the WARP transports ("" = direct).
            "exit" -> { com.mlmvpn.core.warp.Nat64Exits.setSelection(app, intent.getStringExtra("value").orEmpty().let { if (it == "direct") "" else it }); log("exit=${com.mlmvpn.core.warp.Nat64Exits.selection(app)}") }
            // Why the family check answers as it does, step by step, off the main thread.
            "v6check" -> Thread {
                val addrs = runCatching {
                    java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                        .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<java.net.Inet6Address>().map { nic.name to it } }
                }.getOrElse { log("interfaces threw ${it.javaClass.simpleName}"); emptyList() }
                val global = addrs.filter { (_, a) -> !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isLoopbackAddress && (a.address[0].toInt() and 0xfe) != 0xfc }
                log("v6 addresses: ${addrs.size} (global ${global.size} on ${global.map { it.first }.distinct()})")
                val t0 = System.currentTimeMillis()
                val conn = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("2606:4700:4700::1111", 443), 2_500); "ok" } }.getOrElse { it.javaClass.simpleName + ": " + it.message }
                log("connect [2606:4700:4700::1111]:443 -> $conn in ${System.currentTimeMillis() - t0} ms; hasIpv6Route=${CfFamily.hasIpv6Route()} cached=${CfFamily.hasIpv6RouteCached()}")
            }.start()
            // Amnezia's self-signed Hysteria2 servers asked for their certificate (n of them, the
            // socket bound to the bare network or not): how many show one on this line.
            "quicpool" -> {
                val n = intent.getIntExtra("n", 100)
                val bind = intent.getBooleanExtra("bind", true)
                labScope.launch {
                    com.mlmvpn.scanner.engines.amnezia.AmzEngine.init(app)
                    val st = com.mlmvpn.scanner.engines.amnezia.AmzEngine.store.current
                    val pick = st.servers.filter { it.selfSigned }.shuffled().take(n)
                    val here = com.mlmvpn.scanner.engines.flux.FluxNet.current(app)
                    val gate = kotlinx.coroutines.sync.Semaphore(48)
                    val t0 = System.currentTimeMillis()
                    val got = kotlinx.coroutines.coroutineScope {
                        pick.map { s ->
                            async {
                                gate.withPermit {
                                    val h = if (bind) here else here.copy(network = null)
                                    com.mlmvpn.scanner.engines.amnezia.AmzPins.pin(app, h, s, 3_000)
                                }
                            }
                        }.awaitAll()
                    }
                    log("quicpool: ${got.count { it != null }} of ${pick.size} showed a certificate (bind=$bind, network=${here.network != null}) in ${System.currentTimeMillis() - t0} ms")
                    got.filterNotNull().take(5).forEach { log("quicpool: ${it.host}:${it.port} ${it.raw.substringAfter("pinSHA256=").take(16)}") }
                    // Then the real delay test on those, through the core, pinned (--ez test true).
                    if (intent.getBooleanExtra("test", false)) {
                        val ids = got.filterNotNull().map { it.id }
                        if (ids.isNotEmpty()) {
                            com.mlmvpn.scanner.engines.amnezia.AmzEngine.init(app)
                            com.mlmvpn.scanner.engines.amnezia.AmzEngine.testServers(ids)?.join()
                            val net = com.mlmvpn.scanner.engines.flux.FluxNet.current(app).key
                            val after = com.mlmvpn.scanner.engines.amnezia.AmzEngine.store.current
                            val stats = after.statsFor(net)
                            ids.forEach { id ->
                                val sv = after.servers.firstOrNull { it.id == id }
                                log("quictest: ${sv?.host}:${sv?.port} pinned=${sv?.raw?.contains("pinSHA256") == true} ms=${stats[id]?.lastMs}")
                            }
                        }
                    }
                }
            }
            // Connects Amnezia to the server at --es host (its first match), or disconnects (amzoff).
            "amz" -> {
                com.mlmvpn.scanner.engines.amnezia.AmzEngine.init(app)
                val host = intent.getStringExtra("host").orEmpty()
                val s = com.mlmvpn.scanner.engines.amnezia.AmzEngine.store.current.servers.firstOrNull { it.host == host }
                    ?: return log("amz: no server $host")
                com.mlmvpn.scanner.engines.amnezia.AmzEngine.connectAsync(app, serverId = s.id)
                log("amz: connect ${s.kind} $host:${s.port} pinned=${s.raw.contains("pinSHA256")}")
            }
            "amzoff" -> { com.mlmvpn.scanner.engines.amnezia.AmzEngine.disconnectAsync(app); log("amz: disconnect asked") }
            else -> log("unknown mode")
        }
    }

    private fun redact(json: String): String = runCatching {
        val o = org.json.JSONObject(json)
        listOf("team", "access_client_id", "access_client_secret", "access_token", "access_email").forEach { if (o.has(it)) o.put(it, "***") }
        o.toString()
    }.getOrDefault("(unreadable)")

    private fun log(s: String) { Log.i("TileLab", s) }

    private companion object {
        val labScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        @Volatile var xfrag: kotlinx.coroutines.Job? = null
    }
}
