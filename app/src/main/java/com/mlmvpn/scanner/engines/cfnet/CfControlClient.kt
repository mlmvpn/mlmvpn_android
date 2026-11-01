package com.mlmvpn.scanner.engines.cfnet

import android.content.Context
import com.mlmvpn.scanner.data.CfEdge
import com.mlmvpn.scanner.data.CfFamily
import com.mlmvpn.scanner.engines.cfdoctor.*
import com.mlmvpn.scanner.engines.cloud.WorkerRoute
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A CONTROL exchange that did not get an HTTP answer, with the phase it stopped at (dns, tcp, tls,
 * send, wait, body, edge.none, proxy.*). Thrown into OkHttp so the app's own requests fail exactly
 * as they did before, and caught by name where the phase is needed for classification -- never
 * parsed out of a message.
 */
class CfWireException(val phase: String) : IOException("cfnet_$phase")

/**
 * The same wire path for diagnosis and for the app's own requests: one address source × one TLS
 * shape, over the Doctor's WayClient (SSLEngine, so the ClientHello can be cut, re-recorded or
 * re-cased), certificate and hostname verified. What the Doctor proved is therefore literally
 * what CloudManager then runs.
 */
class CfControlClient(private val context: Context, private val env: AndroidNetEnv = AndroidNetEnv(context)) {
    private val ways = WayClient(env, book(context, env))
    private val proxyCancellation = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "cfnet-proxy-cancel").apply { isDaemon = true }
    }

    fun exchange(route: RouteSpec, req: WayClient.Req, cancelled: () -> Boolean = { false }): WayResult {
        if (cancelled() || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("cfnet_cancelled")
        if (route.address == AddressSource.CURRENT_TUNNEL) return proxyExchange(req, cancelled)
        val tls = when (route.shape) {
            WireShape.NONE -> TlsVariant.PLAIN
            WireShape.SPLIT -> TlsVariant.F6
            WireShape.F3 -> TlsVariant.F3
            WireShape.F4 -> TlsVariant.F4
            WireShape.F8 -> TlsVariant.F8
            WireShape.F12 -> TlsVariant.F12
            WireShape.F14 -> TlsVariant.F14
            WireShape.TLS12 -> TlsVariant.TLS12
            WireShape.TLS13 -> TlsVariant.TLS13
            WireShape.SNI_MIXED -> TlsVariant.SNI_MIXED
        }
        // A fixed Cloudflare range: WayClient finds a live address in it (cached per network).
        if (route.address.range.isNotEmpty()) return ways.exchange(Way(AddrSource.EDGE, tls, route.address.range), req, null, cancelled)
        val fixed = when (route.address) {
            AddressSource.WORKER_ROUTE -> WorkerRoute.dns().lookup(req.host).firstOrNull { !it.hostAddress.orEmpty().contains(':') }?.hostAddress
            AddressSource.CF_EDGE -> CfEdge.find(context, req.host, 1).firstOrNull()
            AddressSource.SCANNER_CLEAN -> CfEdge.good(context, 1).firstOrNull()
            AddressSource.IPV6_EDGE -> CfFamily.sampleV6(1).firstOrNull()
            else -> null
        }
        val source = when (route.address) {
            AddressSource.DOH_GOOGLE -> AddrSource.DOH_GOOGLE
            AddressSource.DOH_ADGUARD -> AddrSource.DOH_ADGUARD
            AddressSource.UDP_GOOGLE -> AddrSource.UDP_GOOGLE
            else -> AddrSource.SYSTEM
        }
        if (route.address !in RESOLVER_SOURCES && fixed == null)
            return WayResult(route.address.key, "edge.none")
        return ways.exchange(Way(source, tls), req, fixed, cancelled)
    }

    private fun proxyExchange(req: WayClient.Req, cancelled: () -> Boolean): WayResult {
        val proxy = com.mlmvpn.scanner.update.UpdateNet.tunnelProxy(context) ?: return WayResult("proxy", "proxy.missing")
        val client = OkHttpClient.Builder().proxy(proxy).followRedirects(false)
            .connectTimeout(6, TimeUnit.SECONDS).callTimeout(req.deadlineMs.toLong(), TimeUnit.MILLISECONDS).build()
        val url = "https://${req.host}:${req.port}${req.path}".toHttpUrl()
        val request = Request.Builder().url(url).apply {
            req.headers.forEach { (key, value) -> header(key, value) }
            method(req.method, if (req.method in setOf("GET", "HEAD", "DELETE") && req.body.isEmpty()) null
                else req.body.toRequestBody(req.headers["Content-Type"]?.toMediaTypeOrNull()))
        }.build()
        val call = client.newCall(request)
        val owner = Thread.currentThread()
        val timer = proxyCancellation.scheduleAtFixedRate({ if (cancelled() || owner.isInterrupted) call.cancel() }, 50, 50, TimeUnit.MILLISECONDS)
        val start = System.nanoTime()
        try {
            return call.execute().use { r ->
                val text = r.body?.source()?.let { source ->
                    source.request(req.maxBody.toLong() + 1)
                    if (source.buffer.size > req.maxBody) throw IOException("cfnet_body_limit")
                    source.buffer.readUtf8()
                }.orEmpty()
                WayResult("proxy", "ok", http = r.code, ms = (System.nanoTime() - start) / 1_000_000,
                    body = text, headers = r.headers.toMultimap().mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(", ") })
            }
        } catch (_: IOException) { return WayResult("proxy", "proxy.failure", ms = (System.nanoTime() - start) / 1_000_000) }
        finally { timer.cancel(false); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    /**
     * An OkHttp client whose every call goes over [route]. [tap] sees each wire outcome, so a caller
     * that only gets "no configs" back from a higher layer can still tell a network failure (no HTTP
     * answer at all) from an empty or broken answer.
     */
    fun client(route: RouteSpec, tap: (WayResult) -> Unit = {}): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS).followRedirects(false)
        .addInterceptor { chain -> response(chain.request(), route, tap) { chain.call().isCanceled() } }.build()

    fun response(request: Request, route: RouteSpec, tap: (WayResult) -> Unit = {}, cancelled: () -> Boolean = { false }): Response {
        if (request.url.scheme != "https") throw IOException("cfnet_https_required")
        val buf = okio.Buffer()
        request.body?.let { body ->
            if (body.contentLength() > MAX_BODY || body.isDuplex() || body.isOneShot()) throw IOException("cfnet_body_unsupported")
            body.writeTo(buf)
            if (buf.size > MAX_BODY) throw IOException("cfnet_body_limit")
        }
        val headers = request.headers.toMultimap().mapValues { it.value.joinToString(", ") }.toMutableMap()
        request.body?.contentType()?.let { headers["Content-Type"] = it.toString() }
        headers.remove("Accept-Encoding")
        headers["Accept-Encoding"] = "identity"
        // A worker upload is a few hundred KB: on a slow line 30 s was not always enough to send it,
        // so the deadline grows with what has to go out (a second per 32 KB), capped at two minutes.
        val deadline = (30_000 + buf.size / 32).coerceAtMost(120_000L).toInt()
        val r = exchange(route, WayClient.Req(request.url.host,
            request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""),
            request.method, headers, buf.readByteArray(), request.url.port, maxBody = MAX_BODY, deadlineMs = deadline), cancelled)
        tap(r)
        if (!r.answered) throw CfWireException(r.phase)
        val h = Headers.Builder()
        r.headers.filterKeys { it !in setOf("transfer-encoding", "content-length", "content-encoding") }
            .forEach { (k, v) -> h.add(k, v) }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(r.http).message("Cloudflare")
            .headers(h.build()).body(r.body.toResponseBody(r.headers["content-type"]?.toMediaTypeOrNull())).build()
    }

    fun close() { ways.shutdown(); proxyCancellation.shutdownNow() }

    companion object {
        private const val MAX_BODY = 4_194_304
        private val RESOLVER_SOURCES = setOf(AddressSource.SYSTEM, AddressSource.DOH_GOOGLE, AddressSource.DOH_ADGUARD, AddressSource.UDP_GOOGLE)

        /**
         * One edge book per network, shared by every exchange for ten minutes: a range route then
         * pays for finding a live address in its range once, not on every request.
         */
        private data class Shared(val net: String, val at: Long, val book: EdgeBook)
        @Volatile private var shared: Shared? = null

        private fun book(context: Context, env: AndroidNetEnv): EdgeBook {
            val net = runCatching { CfFamily.networkKey(context) }.getOrDefault("unknown")
            val now = System.currentTimeMillis()
            shared?.takeIf { it.net == net && now - it.at < 10 * 60_000 }?.let { return it.book }
            return EdgeBook(env).also { shared = Shared(net, now, it) }
        }

        /** The phase of a CONTROL failure for classification, from a [CfWireException] anywhere in the chain. */
        fun phaseOf(e: Throwable): String? = generateSequence(e) { it.cause }.take(6).filterIsInstance<CfWireException>().firstOrNull()?.phase
    }
}
