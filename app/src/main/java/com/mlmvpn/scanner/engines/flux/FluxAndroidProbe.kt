package com.mlmvpn.scanner.engines.flux

import android.content.Context
import android.net.Network
import android.util.Log
import com.mlmvpn.core.warp.VlessXrayInjector
import com.mlmvpn.scanner.engines.flux.core.compile.FluxConfigCompiler
import com.mlmvpn.scanner.engines.flux.core.country.EgressVerifier
import com.mlmvpn.scanner.engines.flux.core.model.FailReason
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.Proto
import com.mlmvpn.scanner.engines.flux.core.model.Security
import com.mlmvpn.scanner.engines.flux.core.race.FluxProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The race's hands on a real phone.
 *
 * Stage 1 dials the candidate's address on the underlying network and, for TLS and REALITY, goes
 * through the handshake with the candidate's own SNI -- which is where Iranian filtering usually
 * strikes (a reset right after the ClientHello). No trust is extended to anything here: a
 * certificate error still proves the server answered, which is all stage 1 asks.
 *
 * Stage 2 runs ONE probe core holding every surviving candidate, each behind its own SOCKS port
 * (explicit candidate -> port map), and sends a real request through each: a 204 from Google for
 * the round trip, and the exit's own address and country from two echo services.
 */
class FluxAndroidProbe(
    private val context: Context,
    private val network: Network?,
    private val mobile: Boolean,
    /** Ask the exit where it is (costs two small requests per candidate). */
    private val wantEgress: Boolean = true,
) : FluxProbe {

    private val lock = Mutex()
    private var core: VlessXrayInjector? = null
    private var ports: Map<String, Int> = emptyMap()

    private val connectMs = if (mobile) 2_000 else 1_500
    private val tlsMs = if (mobile) 2_500 else 2_000

    override suspend fun reach(c: FluxCandidate): FluxProbe.Reach {
        val r = reachImpl(c)
        FluxLog.i("reach ${if (r.ok) "OK  " else "FAIL"} ${r.ms?.let { "${it}ms" } ?: "-"} ${r.reason?.name ?: ""} | $c -> ${c.dialAddress}:${c.node.port} sni=${c.node.sni.ifEmpty { "-" }}")
        return r
    }

    private suspend fun reachImpl(c: FluxCandidate): FluxProbe.Reach = withContext(Dispatchers.IO) {
        // QUIC cannot be checked with a TCP socket and a UDP poke proves little; stage 2 decides.
        if (c.node.proto == Proto.HY2) return@withContext FluxProbe.Reach(true, null)
        val start = System.nanoTime()
        val raw = (network?.socketFactory?.createSocket() ?: Socket())
        try {
            raw.connect(InetSocketAddress(InetAddress.getByName(c.dialAddress), c.node.port), connectMs)
        } catch (e: SocketTimeoutException) {
            runCatching { raw.close() }; return@withContext FluxProbe.Reach(false, reason = FailReason.TCP_TIMEOUT)
        } catch (e: Exception) {
            runCatching { raw.close() }; return@withContext FluxProbe.Reach(false, reason = FailReason.TCP_REFUSED)
        }
        // A shaped handshake (ECH, a cut hello) exists because the plain one is reset here: judging
        // it by a plain handshake would drop exactly the candidates that can get through. TCP is
        // all stage 1 asks of them; stage 2 runs their real handshake.
        if (c.node.security == Security.NONE || c.node.sni.isEmpty() || c.fragment != com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile.OFF) {
            runCatching { raw.close() }
            return@withContext FluxProbe.Reach(true, (System.nanoTime() - start) / 1_000_000)
        }
        // The handshake with the node's own name, as the real tunnel will send it.
        val ssl = try {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, c.node.sni, c.node.port, true) as SSLSocket
        } catch (e: Exception) { runCatching { raw.close() }; return@withContext FluxProbe.Reach(false, reason = FailReason.TLS_ERROR) }
        try {
            ssl.soTimeout = tlsMs
            runCatching {
                val params = ssl.sslParameters
                params.serverNames = listOf(javax.net.ssl.SNIHostName(c.node.sni))
                ssl.sslParameters = params
            }
            ssl.startHandshake()
            FluxProbe.Reach(true, (System.nanoTime() - start) / 1_000_000)
        } catch (e: SSLHandshakeException) {
            // The server answered with a certificate this phone does not trust (REALITY's borrowed
            // one, a self-signed origin): the path is open, which is the question here.
            if (e.message.orEmpty().contains("reset", true)) FluxProbe.Reach(false, reason = FailReason.RESET_AFTER_SNI)
            else FluxProbe.Reach(true, (System.nanoTime() - start) / 1_000_000)
        } catch (e: SocketTimeoutException) {
            FluxProbe.Reach(false, reason = FailReason.TLS_TIMEOUT)
        } catch (e: SocketException) {
            FluxProbe.Reach(false, reason = FailReason.RESET_AFTER_SNI)
        } catch (e: SSLException) {
            FluxProbe.Reach(false, reason = if (e.message.orEmpty().contains("reset", true) || e.message.orEmpty().contains("closed", true)) FailReason.RESET_AFTER_SNI else FailReason.TLS_ERROR)
        } catch (e: Exception) {
            FluxProbe.Reach(false, reason = FailReason.OTHER)
        } finally {
            runCatching { ssl.close() }
        }
    }

    override suspend fun prepare(cs: List<FluxCandidate>): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            stopCore()
            if (startCore(cs)) { FluxLog.i("probe: core up with ${cs.size} candidates, ports ${ports.values.joinToString(",")}"); return@withContext true }
            // One outbound this core cannot build takes the whole config down with it. Find the
            // ones that do build (a config error fails in milliseconds) and race those.
            val good = cs.filter { c ->
                val trial = VlessXrayInjector(0)
                val ok = runCatching { trial.start(context, FluxConfigCompiler.probe(mapOf(c to freePort())), 0) }.getOrDefault(false)
                runCatching { trial.stop() }
                if (!ok) FluxLog.w("probe: candidate does not build on this core, dropped: $c")
                ok
            }
            FluxLog.i("probe: batch core refused; ${good.size}/${cs.size} candidates build on their own")
            if (good.isEmpty()) { FluxLog.w("probe core did not start (${cs.size} candidates)"); return@withContext false }
            startCore(good)
        }
    }

    /** One core for [cs], each candidate on its own port. False when the core refuses the config. */
    private suspend fun startCore(cs: List<FluxCandidate>): Boolean {
        val map = LinkedHashMap<FluxCandidate, Int>()
        cs.forEach { map[it] = freePort() }
        val c = VlessXrayInjector(0)
        val ok = runCatching { c.start(context, FluxConfigCompiler.probe(map), 0) }.getOrDefault(false)
        if (!ok) { runCatching { c.stop() }; return false }
        core = c
        ports = map.entries.associate { (cand, port) -> cand.id to port }
        return true
    }

    override suspend fun real(c: FluxCandidate): FluxProbe.Real {
        val r = realImpl(c)
        val e = r.egress
        FluxLog.i("real  ${if (r.ok) "OK  " else "FAIL"} ${r.rttMs?.let { "${it}ms" } ?: "-"} ${r.reason?.name ?: ""} exit=${e?.countryCode ?: "?"}" +
            "${e?.let { " (conf ${it.confidence}, ${it.ipv4 ?: it.ipv6 ?: "no ip"}, ${it.asn ?: ""})" } ?: ""} port=${ports[c.id]} | $c")
        return r
    }

    private suspend fun realImpl(c: FluxCandidate): FluxProbe.Real = coroutineScope {
        val port = ports[c.id] ?: return@coroutineScope FluxProbe.Real(false, reason = FailReason.OTHER)
        val timeout = if (mobile) 6_000 else 5_000
        val probe = async(Dispatchers.IO) { timedGet(port, "www.gstatic.com", 80, "/generate_204", tls = false, timeoutMs = timeout) }
        val trace = if (wantEgress) async(Dispatchers.IO) { echo(port, EgressVerifier.TRACE, timeout) } else null
        val api = if (wantEgress) async(Dispatchers.IO) { echo(port, EgressVerifier.IP_API, timeout) } else null
        val (code, ms) = probe.await() ?: return@coroutineScope FluxProbe.Real(false, reason = FailReason.HTTP_TIMEOUT)
        if (code != 204 && code != 200) {
            FluxLog.i("real: unexpected HTTP $code via :$port")
            return@coroutineScope FluxProbe.Real(false, reason = FailReason.HTTP_FAILED)
        }
        val obs = listOfNotNull(trace?.await(), api?.await())
        val egress = if (wantEgress) EgressVerifier.combine(obs, System.currentTimeMillis()) else null
        FluxProbe.Real(true, ms, egress = egress?.takeIf { obs.isNotEmpty() })
    }

    /** The exit as one echo service sees it, through the candidate. */
    suspend fun echo(port: Int, source: EgressVerifier.Source, timeoutMs: Int): EgressVerifier.Observation? = withContext(Dispatchers.IO) {
        val url = java.net.URL(source.url)
        val tls = url.protocol == "https"
        val body = body(port, url.host, if (url.port > 0) url.port else if (tls) 443 else 80, url.file, tls, timeoutMs) ?: return@withContext null
        EgressVerifier.parse(source.id, body)
    }

    /** Stops the probe core. Called when the race is over and nothing more will be asked. */
    fun close() {
        stopCore()
    }

    private fun stopCore() {
        core?.let { runCatching { it.stop() } }
        core = null; ports = emptyMap()
    }

    /** One GET through the SOCKS port; the status code and the time to the status line. */
    private fun timedGet(port: Int, host: String, remotePort: Int, path: String, tls: Boolean, timeoutMs: Int): Pair<Int, Long>? = runCatching<Pair<Int, Long>?> {
        val start = System.nanoTime()
        open(port, host, remotePort, tls, timeoutMs).use { s ->
            s.getOutputStream().write(request(host, path))
            val status = readLine(s.getInputStream()) ?: return@runCatching null
            val ms = (System.nanoTime() - start) / 1_000_000
            status.split(' ').getOrNull(1)?.toIntOrNull()?.let { it to ms }
        }
    }.onFailure { FluxLog.i("real: request via :$port to $host failed: ${it.javaClass.simpleName} ${it.message?.take(120) ?: ""}") }.getOrNull()

    private fun body(port: Int, host: String, remotePort: Int, path: String, tls: Boolean, timeoutMs: Int): String? = runCatching {
        open(port, host, remotePort, tls, timeoutMs).use { s ->
            s.getOutputStream().write(request(host, path))
            val text = readUpTo(s.getInputStream(), 8 * 1024)
            if (!text.startsWith("HTTP/1.1 200") && !text.startsWith("HTTP/1.0 200")) null else text.substringAfter("\r\n\r\n")
        }
    }.getOrNull()

    private fun open(port: Int, host: String, remotePort: Int, tls: Boolean, timeoutMs: Int): Socket {
        val s = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        s.soTimeout = timeoutMs
        // Unresolved: the name is resolved at the exit, never by this phone's resolver.
        s.connect(InetSocketAddress.createUnresolved(host, remotePort), timeoutMs)
        if (!tls) return s
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, remotePort, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
            ssl.close(); error("certificate does not match $host")
        }
        return ssl
    }

    /** HTTP/1.0 on purpose: no chunked body to undo, and the server closes when done. */
    private fun request(host: String, path: String) =
        "GET $path HTTP/1.0\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 256) {
            val b = input.read(); if (b < 0) break
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun readUpTo(input: InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(2048)
        while (out.size() < max) {
            val n = runCatching { input.read(buf) }.getOrDefault(-1)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    companion object { private const val TAG = "FluxProbe" }
}
