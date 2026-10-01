package com.mlmvpn.scanner.engines.cfdoctor

import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.CloudVerifyProbe
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * ST1 through the app's REAL path: CloudManager's own account check, with its own requests and
 * client settings, bound to the physical network.
 *
 * The verdict comes from what that check concluded ([CloudVerifyProbe]), not from each request it
 * made on the way. The check legitimately tries the wrong auth scheme first when it has to guess
 * (a Global Key looks like a token); counting that 403 as "credentials rejected" is how the old
 * Doctor reported working accounts as broken. Individual requests are still recorded -- as `A1`
 * evidence rows -- so the report shows what happened on the wire.
 */
class St1RealPath(private val cloud: CloudManager, private val env: AndroidNetEnv, private val budget: DataBudget) {

    private fun client(route: String, emit: (Evidence) -> Unit, fixedIp: String?): OkHttpClient {
        val builder = cloud.diagnosticClientBuilder().dispatcher(Dispatcher()).connectionPool(ConnectionPool())
            .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        env.picked?.network?.socketFactory?.let { builder.socketFactory(it) }
        builder.dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns(okhttp3.Dns { env.resolve(it) }))
        if (fixedIp != null) builder.dns { host -> if (host == "api.cloudflare.com") listOf(InetAddress.getByName(fixedIp)) else env.resolve(host) }
        builder.addInterceptor { chain ->
            val start = env.now()
            try {
                val response = chain.proceed(chain.request())
                val body = response.body
                val bytes = body?.byteStream()?.let { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (out.size() < 65536) { val n = input.read(buffer, 0, minOf(buffer.size, 65536 - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
                    out.toByteArray()
                } ?: ByteArray(0)
                budget.count(bytes.size)
                val headers = response.headers.names().associateWith { response.header(it).orEmpty() }
                val path = chain.request().url.encodedPath
                emit(Evidence.http("A1", "$route:${endpoint(path)}", response.code, headers, String(bytes), true, env.now() - start))
                response.newBuilder().body(bytes.toResponseBody(body?.contentType())).build().also { body?.close() }
            } catch (e: Exception) {
                val phase = phaseOf(e)
                emit(Evidence.failure("A1", "$route:${endpoint(chain.request().url.encodedPath)}", phase, e, env.now() - start))
                throw e
            }
        }
        return builder.build()
    }

    /**
     * Runs the app's account check once over [route] and records one terminal `ST1` row with the
     * check's own conclusion.
     */
    suspend fun run(account: CloudAccount?, credential: Pair<String, String>?, route: String, fixedIp: String?, emit: (Evidence) -> Unit) {
        if (env.picked == null) { emit(Evidence("ST1", route, "net.unavailable", numbers = mapOf("terminal" to 1L))); return }
        val wire = ArrayList<Evidence>()
        val client = client(route, { wire += it; emit(it) }, fixedIp)
        val started = env.now()
        try {
            val probe = suspendCancellableCoroutine<CloudVerifyProbe?> { cont ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                scope.launch {
                    val p = runCatching {
                        if (account != null) cloud.probeAccountStatus(account.copy(), client, readOnly = true)
                        else if (credential != null) cloud.probeCredential(credential.first, credential.second, client)
                        else null
                    }.getOrNull()
                    if (cont.isActive) cont.resume(p)
                    scope.cancel()
                }
                cont.invokeOnCancellation { client.dispatcher.cancelAll(); scope.cancel() }
            }
            emit(conclude(route, probe, wire, env.now() - started))
        } finally {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        private fun endpoint(path: String) = when {
            path.endsWith("/tokens/verify") -> "verify"
            path.endsWith("/workers/subdomain") -> "subdomain"
            path.endsWith("/accounts") -> "accounts"
            path.endsWith("/user") -> "user"
            else -> "other"
        }

        private fun phaseOf(e: Throwable) = when (e) {
            is java.net.UnknownHostException -> "dns"
            is javax.net.ssl.SSLException -> "tls"
            is java.net.ConnectException -> "tcp"
            is java.net.SocketTimeoutException -> "tcp"
            else -> "http"
        }

        fun conclude(route: String, p: CloudVerifyProbe?, wire: List<Evidence>, ms: Long): Evidence =
            AccountConclusion.of(route, p?.let {
                AccountConclusion.Check(it.tokenVerifyHttp, it.subdomainHttp, it.tokenVerifyErrors + " " + it.subdomainErrors,
                    it.hasSubdomain, it.emailVerified, it.transport, it.scheme)
            }, wire, ms)
    }
}
