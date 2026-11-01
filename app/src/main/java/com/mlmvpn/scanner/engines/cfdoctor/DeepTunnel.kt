package com.mlmvpn.scanner.engines.cfdoctor

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One VLESS/Trojan over (TLS) WebSocket/HTTPUpgrade connection, end to end, on a chosen address
 * and with chosen TLS tricks -- the same thing the app's real scanner and connection do, without
 * the core, so it can say exactly which step failed and run fifty at once.
 *
 * Stages: tcp, tls, ws(<status>) / ws, proxy (the server refused the VLESS header), egress (the
 * worker took it but nothing came back from the internet), ok. With [Opt.downloadBytes] it then
 * pulls that much through the tunnel and reports the throughput.
 */
class DeepTunnel(private val env: NetEnv) {

    data class Opt(
        val ip: String, val port: Int, val tls: Boolean = true, val sni: String, val host: String,
        val path: String, val recipe: SplitRecipe = SplitRecipe(), val protocol: String? = null,
        val deadlineMs: Int = 8_000, val downloadBytes: Int = 0,
    )

    data class Res(val stage: String, val ms: Long, val tcpMs: Long = 0, val tlsMs: Long = 0, val downBytes: Long = 0, val downMs: Long = 0) {
        val ok: Boolean get() = stage == "ok"
        val kbps: Long get() = if (downMs > 0) downBytes * 8 / downMs else 0
    }

    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "doctor-tunnel-deadline").apply { isDaemon = true } }

    fun test(c: TunnelConfig, o: Opt): Res {
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        var stage = "tcp"; var tcpMs = 0L; var tlsMs = 0L
        val socket = try { env.socket() } catch (_: Exception) { return Res("socket", ms()) }
        val timer = watchdog.schedule({ runCatching { socket.close() } }, (o.deadlineMs + if (o.downloadBytes > 0) 20_000 else 0).toLong(), TimeUnit.MILLISECONDS)
        try {
            socket.soTimeout = o.deadlineMs; socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(InetAddress.getByName(o.ip), o.port), minOf(o.deadlineMs, 3_000))
            tcpMs = ms()
            var input: InputStream = socket.getInputStream(); var output: OutputStream = socket.getOutputStream()
            if (o.tls) {
                stage = "tls"
                val t = System.nanoTime()
                val tls = TlsChannel(input, output, o.sni.ifBlank { o.host }, o.port, o.sni, o.recipe, o.protocol, env.tlsContext())
                tlsMs = (System.nanoTime() - t) / 1_000_000
                input = tls.input; output = tls.output
            }
            stage = "ws"
            val key = WireBase64.encode(ByteArray(16).also { SecureRandom().nextBytes(it) })
            HttpWire.request(output, o.host, o.path.ifBlank { "/" }, headers = mapOf("Upgrade" to "websocket", "Sec-WebSocket-Version" to "13", "Sec-WebSocket-Key" to key, "User-Agent" to "Mozilla/5.0"))
            val head = HttpWire.head(input)
            if (head.status != 101) return Res("ws(${head.status})", ms(), tcpMs, tlsMs)
            if (c.transport == "ws") {
                val ws = WebSocketStream(input, output); input = ws.input; output = ws.output
            }
            stage = "proxy"
            // Not on Cloudflare (a Worker cannot reach Cloudflare addresses); cachefly answered 43 bytes through workers.
            val target = if (o.downloadBytes > 0) DOWNLOAD_HOST to DOWNLOAD_PATH else "connectivitycheck.gstatic.com" to "/generate_204"
            output.write(TunnelStageClient.proxyHeader(c, target.first, 80))
            HttpWire.request(output, target.first, target.second)
            if (c.protocol == "vless") {
                val h = HttpWire.readExact(input, 2)
                if (h[0] != 0.toByte()) return Res("proxy", ms(), tcpMs, tlsMs)
                HttpWire.readExact(input, h[1].toInt() and 255)
            }
            stage = "egress"
            val r = HttpWire.head(input)
            if (o.downloadBytes <= 0) return Res(if (r.status == 204) "ok" else "egress(${r.status})", ms(), tcpMs, tlsMs)
            if (r.status != 200) return Res("egress(${r.status})", ms(), tcpMs, tlsMs)
            socket.soTimeout = 10_000
            val t = System.nanoTime(); var got = 0L; val buf = ByteArray(16_384)
            while (got < o.downloadBytes) { val n = input.read(buf); if (n < 0) break; got += n }
            val dms = ((System.nanoTime() - t) / 1_000_000).coerceAtLeast(1)
            return Res(if (got >= o.downloadBytes) "ok" else "stall", ms(), tcpMs, tlsMs, got, dms)
        } catch (e: Exception) {
            return Res(stage, ms(), tcpMs, tlsMs)
        } finally {
            timer.cancel(false); runCatching { socket.close() }
        }
    }

    fun shutdown() { watchdog.shutdownNow() }

    companion object {
        const val DOWNLOAD_HOST = "speedtest.tele2.net"
        const val DOWNLOAD_PATH = "/10MB.zip"
    }
}
