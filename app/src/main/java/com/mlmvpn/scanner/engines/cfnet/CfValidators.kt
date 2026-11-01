package com.mlmvpn.scanner.engines.cfnet

import android.content.Context
import com.mlmvpn.core.warp.WarpApiFront
import com.mlmvpn.scanner.data.CfEdge
import com.mlmvpn.scanner.data.CfEdgeHeal
import com.mlmvpn.scanner.data.CfFamily
import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.engines.cfdoctor.AndroidNetEnv
import com.mlmvpn.scanner.engines.cfdoctor.CfBogon
import com.mlmvpn.scanner.engines.cfdoctor.EdgeGroups
import com.mlmvpn.scanner.engines.cfdoctor.NativeProbe
import com.mlmvpn.scanner.engines.cfdoctor.WayClient
import com.mlmvpn.scanner.engines.cfdoctor.WayResult
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/**
 * The real validators -- one per stage. Every verdict is backed by actual I/O and a real criterion:
 * a Cloudflare API answer with `success:true`, a WARP identity that is registered, read back and
 * removed, a test worker that echoes a nonce and is then deleted, a subscription that parses to a
 * usable config, clean IPs that carry data through the core, and a tunnel that moves bytes both
 * ways. Nothing is "ok" because a socket opened or a core started.
 *
 * Failures are classified so the engine can tell the route's fault (no answer: DNS, TCP, TLS,
 * silence) from Cloudflare's verdict (rejected credential, API error, account not set up), which is
 * never held against the route.
 *
 * CONTROL stages use [CfControlClient] (the same wire path the app's own requests take). DATA
 * stages use [NativeProbe], which runs the core in the `:xprobe` process -- one core at a time, so
 * DATA validation is serialised by the manager.
 *
 * Secrets never enter a result's `detail`: only status codes, counts and byte totals do. Response
 * bodies are read to compute success and never stored or logged.
 */
class CfValidators(
    private val context: Context,
    private val env: AndroidNetEnv,
    private val native: NativeProbe,
    private val accounts: List<CloudAccount>,
    private val ownConfigs: List<VpnConfig>,
    private val scanBase: VpnConfig?,
    /** IPv6 edges a look at this line just saw answer ([CfNetManager.LineProbe.v6]): proven on first. */
    private val seedV6: List<String> = emptyList(),
    /** Called with a script name BEFORE a deploy test uploads it, so a cut-short run is retried later. */
    private val onPendingCleanup: (accountId: String, scriptName: String) -> Unit = { _, _ -> },
    private val onCleanupComplete: (accountId: String, scriptName: String) -> Unit = { _, _ -> },
    private val onProvenAddress: (CfStage, String, VpnConfig, String) -> Unit = { _, _, _, _ -> },
) {
    private val API = "api.cloudflare.com"
    private val WARP_API = "api.cloudflareclient.com"
    private val UA = "mlmvpn-doctor"

    suspend fun validate(s: Strategy, stage: CfStage): ValidationResult = when (stage) {
        CfStage.ACCOUNT -> account(s.route!!)
        CfStage.IDENTITY -> identity(s.route!!)
        CfStage.DEPLOY -> deploy(s.route!!)
        CfStage.CONFIG -> config(s.route!!)
        CfStage.SCAN -> scan(s.data!!, s.id)
        CfStage.CONNECT -> connect(s.data!!, s.id)
    }

    // ---- CONTROL plumbing -------------------------------------------------------------------------

    private suspend fun exchange(route: RouteSpec, host: String, path: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0), deadlineMs: Int = 12_000): WayResult =
        withContext(Dispatchers.IO) {
            val client = CfControlClient(context, env)
            try { kotlinx.coroutines.runInterruptible {
                client.exchange(route, WayClient.Req(host, path, method, headers + ("User-Agent" to UA), body, deadlineMs = deadlineMs))
            } } finally { client.close() }
        }

    /** A route that got no HTTP answer: classified from where it stopped, never from a message. */
    private fun netFailure(r: WayResult, deadlineMs: Int = 12_000): ValidationResult {
        val fc = FailureClassifier.ofWirePhase(r.phase, timedOut = r.ms >= deadlineMs - 250)
        return ValidationResult.fail(fc, r.ms, "phase=${r.phase}", FailureClassifier.wirePhaseOf(r.phase))
    }

    /** Cloudflare answered, and not with what was needed. Never a network verdict. */
    private fun answered(r: WayResult, what: String, fallback: FailureClass = FailureClass.API_FAILURE): ValidationResult =
        ValidationResult.fail(FailureClassifier.authOrApi(r.http, r.cfErrors.ifEmpty { FailureClassifier.cfErrorsOf(r.body) }) ?: fallback,
            r.ms, "$what http=${r.http}", WirePhase.DONE)

    // ---- ACCOUNT ----------------------------------------------------------------------------------

    private suspend fun account(route: RouteSpec): ValidationResult {
        val acc = accounts.firstOrNull() ?: return ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "no_account")
        // Unsaved credentials have no proven authScheme yet. Mirror addAccount's fallback so an
        // unusual-length Global API Key is still offered for confirmation after its global-header
        // route works; saved accounts use only the scheme already proven at add time.
        val firstBearer = CloudAuth.useBearer(acc)
        val schemes = if (acc.authScheme == null) listOf(firstBearer, !firstBearer) else listOf(firstBearer)
        var last: ValidationResult? = null
        for (bearer in schemes.distinct()) {
            val candidate = if (acc.authScheme == null) acc.copy(authScheme = if (bearer) "bearer" else "global") else acc
            val path = if (bearer) "/client/v4/user/tokens/verify" else "/client/v4/user"
            val r = exchange(route, API, path, headers = authHeaders(candidate))
            // No answer is the route's failure: the other scheme would travel the same way.
            if (!r.answered) return netFailure(r)
            if (!CfValidation.account(r.http, r.body, bearer)) {
                last = answered(r, "verify", FailureClass.AUTH_FAILURE)
                continue
            }
            // A token can be active while lacking account permission. Prove the same account-list
            // request that CloudManager.addAccount will need before asking the user to save it.
            val accountsReply = exchange(route, API, "/client/v4/accounts", headers = authHeaders(candidate))
            if (!accountsReply.answered) return netFailure(accountsReply)
            if (CfValidation.accounts(accountsReply.http, accountsReply.body)) {
                return ValidationResult.pass(r.ms + accountsReply.ms, "http=${accountsReply.http} accounts=true")
            }
            last = answered(accountsReply, "accounts", FailureClass.AUTH_FAILURE)
        }
        return last ?: ValidationResult.fail(FailureClass.AUTH_FAILURE, detail = "credential_rejected")
    }

    private fun authHeaders(acc: CloudAccount): Map<String, String> =
        if (CloudAuth.useBearer(acc)) mapOf("Authorization" to "Bearer ${acc.token.trim()}")
        else mapOf("X-Auth-Email" to acc.email.trim(), "X-Auth-Key" to acc.token.trim())

    // ---- IDENTITY (a real WARP identity: registered, read back with its own token, removed) --------

    private suspend fun identity(route: RouteSpec): ValidationResult {
        val relay = if (route.carrier == Carrier.WORKER_RELAY) com.mlmvpn.core.warp.WarpIdRelay.base(context)
            ?: return ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "no_identity_relay") else null
        val headers = mapOf("User-Agent" to "WARP for Android", "CF-Client-Version" to "a-6.35-4471")
        val wg = com.mlmvpn.core.warp.WarpCrypto.generateKeyPair()
        val payload = JSONObject().put("key", wg.publicKeyBase64).put("install_id", "").put("fcm_token", "")
            .put("tos", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(java.util.Date()))
            .put("model", "Android").put("key_type", "curve25519").put("tunnel_type", "wireguard").put("locale", "en_US")
        suspend fun call(method: String, path: String, body: ByteArray = ByteArray(0), token: String? = null): WayResult {
            val h = headers + mapOf("Content-Type" to "application/json") + (token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
            return if (route.carrier == Carrier.WARP_FRONT) withContext(Dispatchers.IO) {
                try {
                    val a = kotlinx.coroutines.runInterruptible { WarpApiFront.exchange(method, path, h.toList(), body, if (method == "DELETE") 8_000 else 45_000) }
                    WayResult("front", "ok", http = a.status, body = a.body)
                } catch (e: java.io.IOException) {
                    if (Thread.currentThread().isInterrupted) throw e
                    WayResult("front", "wait")
                }
            } else {
                val url = relay?.toHttpUrl()
                exchange(route, url?.host ?: WARP_API, (url?.encodedPath?.trimEnd('/') ?: "") + path, method, h, body, if (method == "DELETE") 8_000 else 18_000)
            }
        }
        val r = call("POST", "/v0a4471/reg", payload.toString().toByteArray())
        if (!r.answered) return netFailure(r, 18_000)
        if (!CfValidation.identity(r.http, r.body)) return answered(r, "identity_invalid")
        val j = JSONObject(r.body)
        val id = j.getString("id")
        val token = j.getString("token")
        // Validate the newly issued credential against its real registration, then remove the test identity.
        // The runtime registration path uses this same route; probe secrets never enter a store unsealed.
        try {
            CfIdentityCleanup.add(context, id, token, route)
            val verify = call("GET", "/v0a4471/reg/$id", token = token)
            if (!verify.answered) return netFailure(verify, 18_000)
            val verified = verify.http in 200..299 && runCatching { JSONObject(verify.body).optString("id") == id }.getOrDefault(false)
            return if (verified) ValidationResult.pass(r.ms + verify.ms, "identity=parsed_authenticated")
                else answered(verify, "identity_verify")
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val deleted = call("DELETE", "/v0a4471/reg/$id", token = token)
                if (deleted.answered && (deleted.http in 200..299 || deleted.http == 404)) CfIdentityCleanup.remove(context, id)
            }
        }
    }

    // ---- DEPLOY (create + nonce verify + delete) --------------------------------------------------

    private suspend fun deploy(route: RouteSpec): ValidationResult {
        val acc = accounts.firstOrNull { it.accountId.isNotBlank() } ?: return ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "no_account")
        val h = authHeaders(acc)
        // The account's workers.dev name first: without one there is nowhere to serve a test from,
        // and creating one is a change to the account this check has no business making.
        val sub = exchange(route, API, "/client/v4/accounts/${acc.accountId}/workers/subdomain", headers = h)
        if (!sub.answered) return netFailure(sub)
        if (!CfValidation.api(sub.http, sub.body)) return answered(sub, "subdomain")
        val subdomain = runCatching { JSONObject(sub.body).optJSONObject("result")?.optString("subdomain") }.getOrNull()?.takeIf { !it.isNullOrBlank() }
            ?: return ValidationResult.fail(FailureClass.ACCOUNT_SETUP, sub.ms, "no_workers_subdomain", WirePhase.DONE)

        val nonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val name = "mlmvpn-doctor-" + java.util.UUID.randomUUID().toString().take(8)
        val path = "/client/v4/accounts/${acc.accountId}/workers/scripts/$name"
        // Recorded BEFORE the upload: a run killed between upload and delete is cleaned up next time.
        onPendingCleanup(acc.accountId, name)

        // 1. Upload a tiny module worker that returns the nonce.
        val boundary = "mlmvpnDoctor" + System.currentTimeMillis()
        val script = "export default { async fetch() { return new Response(\"$nonce\"); } }"
        val meta = JSONObject().put("main_module", "worker.js").toString()
        val bodyStr = "--$boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"\r\nContent-Type: application/javascript+module\r\n\r\n$script\r\n--$boundary--\r\n"
        val put = exchange(route, API, path, "PUT", h + ("Content-Type" to "multipart/form-data; boundary=$boundary"), bodyStr.toByteArray(), deadlineMs = 30_000)
        if (!put.answered) {
            // Nothing may have been created; the pending record makes the next run delete it if it was.
            return netFailure(put, 30_000)
        }
        if (!CfValidation.api(put.http, put.body)) {
            // Cloudflare refused the upload: nothing exists to delete.
            onCleanupComplete(acc.accountId, name)
            return answered(put, "upload")
        }

        var result = ValidationResult.fail(FailureClass.DATA_TRANSFER_FAILURE, put.ms, "not_served")
        try {
            // 2. Expose it on workers.dev and read it back, checking the nonce EXACTLY.
            val enable = exchange(route, API, "$path/subdomain", "POST", h + ("Content-Type" to "application/json"), """{"enabled":true}""".toByteArray())
            if (!enable.answered) return netFailure(enable)
            if (!CfValidation.api(enable.http, enable.body)) return answered(enable, "enable")
            val wHost = "$name.$subdomain.workers.dev"
            // workers.dev takes a moment to publish a new name: a short backoff, not a fixed wait.
            var lastGet: WayResult? = null
            var anyAnswer = false
            for ((attempt, pause) in listOf(0L, 1_500L, 3_000L, 4_500L).withIndex()) {
                if (pause > 0) kotlinx.coroutines.delay(pause)
                val g = exchange(route, wHost, "/", deadlineMs = 10_000)
                lastGet = g
                if (g.answered) anyAnswer = true
                if (g.answered && CfValidation.nonce(g.http, g.body, nonce)) {
                    result = ValidationResult.pass(put.ms + g.ms, "nonce_ok http=${g.http} tries=${attempt + 1}")
                    break
                }
            }
            if (!result.ok) result = when {
                // workers.dev never answered over this route: the route's fault (the API host and the
                // workers.dev hosts are filtered separately on some lines).
                !anyAnswer -> lastGet?.let { netFailure(it, 10_000) } ?: result
                // It answered, never with the nonce: publication delay or an account-side issue.
                else -> ValidationResult.fail(FailureClass.API_FAILURE, put.ms, "served_without_nonce http=${lastGet?.http}", WirePhase.DONE)
            }
        } finally {
            // 3. Always clean up. If delete fails, the pending record stays so the manager retries.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val del = exchange(route, API, path, "DELETE", h, deadlineMs = 15_000)
                if (del.answered && (del.http == 404 || CfValidation.api(del.http, del.body))) onCleanupComplete(acc.accountId, name)
            }
        }
        return result
    }

    // ---- CONFIG -----------------------------------------------------------------------------------

    private suspend fun config(route: RouteSpec): ValidationResult = withContext(Dispatchers.IO) {
        val acc = accounts.firstOrNull { !it.workerUrl.isNullOrBlank() || !it.edgWorkerUrl.isNullOrBlank() }
            ?: return@withContext ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "no_panel")
        if (hostOf(acc.workerUrl ?: acc.edgWorkerUrl!!) == null) return@withContext ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "no_panel_host")
        val cloud = com.mlmvpn.scanner.data.CloudManager(context)
        val wireClient = CfControlClient(context, env)
        // The fetch helpers fold every failure into "no configs"; the tap keeps what the WIRE saw,
        // so a filtered route is told apart from a panel that answered with nothing.
        val seen = java.util.Collections.synchronizedList(mutableListOf<WayResult>())
        val client = wireClient.client(route) { seen += it }
        try {
            val t = System.currentTimeMillis()
            val result = if (!acc.workerUrl.isNullOrBlank()) cloud.fetchCloudConfigs(acc.copy(), client) else cloud.fetchEdgRemoteConfigs(acc.copy(), client)
            val parsed = result.second.mapNotNull { VpnConfig.parseUri(it) }.count { it.address.isNotBlank() }
            val ms = System.currentTimeMillis() - t
            val lastAnswer = seen.lastOrNull { it.answered }
            when {
                parsed > 0 -> ValidationResult.pass(ms, "configs=$parsed")
                result.second.isNotEmpty() -> ValidationResult.fail(FailureClass.CONFIG_PARSE_FAILURE, ms, "links=${result.second.size} parsed=0", WirePhase.DONE)
                lastAnswer != null -> answered(lastAnswer, "subscription_empty")
                seen.isNotEmpty() -> netFailure(seen.last(), 30_000)
                else -> ValidationResult.fail(FailureClass.UNKNOWN, ms, "no_request")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            seen.lastOrNull { !it.answered }?.let { netFailure(it, 30_000) }
                ?: ValidationResult.fail(FailureClass.UNKNOWN, detail = e.javaClass.simpleName)
        } finally {
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            wireClient.close()
        }
    }

    // ---- SCAN -------------------------------------------------------------------------------------

    private suspend fun scan(spec: ShapeSpec, strategyId: String): ValidationResult {
        if (!isXrayShape(spec.shape)) return ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "engine_validated_separately")
        val base = scanBase ?: ownConfigs.firstOrNull { isCloudflare(it) } ?: return ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "no_base_config")
        if (!applicable(spec, base)) return ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "not_applicable")
        val candidates = when (spec.addressPolicy) {
            AddressPolicy.CLEAN_IP -> CfEdge.good(context, 5)
            AddressPolicy.CF_EDGE -> CfEdge.find(context, CfEdge.DEFAULT_HOST, 5)
            AddressPolicy.IPV6_TWIN -> listOfNotNull(runCatching { CfEdgeHeal.goodV6(context) }.getOrNull())
            else -> scanCandidates(spec.family, 5)
        }
        if (candidates.isEmpty()) return ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "no_candidates")
        var passed = 0
        var bestIp: String? = null
        var bestMs = 0L
        for (ip in candidates) {
            val cfg = CfEdgeHeal.onto(base.copy(), ip).also { if (spec.port > 0) it.port = spec.port }
            val r = native.measure(shaped(cfg, spec), 6_000)
            if (r.ms != null) {
                passed++; if (bestIp == null || r.ms < bestMs) { bestIp = ip; bestMs = r.ms }
                runCatching { if (!ip.contains(':')) CfEdge.noteGood(context, ip, r.ms) }
            }
            if (passed >= 2) break
        }
        val ip = bestIp ?: return ValidationResult.fail(FailureClass.SCAN_FAILURE, detail = "passed=0/${candidates.size}")
        // A real data check on the best address: a scan that "finds" IPs that carry nothing is not a pass.
        val cfg = CfEdgeHeal.onto(base.copy(), ip).also { if (spec.port > 0) it.port = spec.port }
        val (status, data) = native.withCore(shaped(cfg, spec)) { port -> CfDataProbe.validate(port) }
        val dataOk = status == "ok" && data?.ok == true
        if (dataOk) onProvenAddress(CfStage.SCAN, strategyId, base, ip)
        return if (dataOk) ValidationResult.pass(bestMs, "passed=$passed data=ok")
        else ValidationResult.fail(FailureClass.DATA_TRANSFER_FAILURE, bestMs, "passed=$passed data=${data?.detail ?: status}", WirePhase.DONE)
    }

    /**
     * Where to look for a working address: the edges already proven here, then the edge the 2026-10
     * summary names for the family (188.114.98.0, 2a06:98c1:3121::7), then a sample of the ranges the
     * 138 Doctor reports showed answering on most lines (not the whole published list, most of which
     * is dead in Iran and would spend the strategy's time on silence).
     */
    private fun scanCandidates(family: Family, n: Int): List<String> = buildList {
        if (family == Family.V6) {
            addAll(seedV6)
            add(FirewallFamily.EDGE_V6)
            addAll(runCatching { CfFamily.sampleV6(n) }.getOrDefault(emptyList()))
        } else {
            addAll(CfEdge.good(context, 2))
            add(FirewallFamily.EDGE_V4)
            val rnd = java.security.SecureRandom()
            repeat(n) { add(EdgeGroups.random(LIVE_RANGES[rnd.nextInt(LIVE_RANGES.size)], rnd)) }
        }
    }.distinct().take(n)

    // ---- CONNECT ----------------------------------------------------------------------------------

    private suspend fun connect(spec: ShapeSpec, strategyId: String): ValidationResult {
        // MASQUE/WARP/chains are full engines, proven by their own real connection checks, not here.
        if (!isXrayShape(spec.shape)) return ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "engine_validated_separately")
        val cfg0 = ownConfigs.firstOrNull { isCloudflare(it) } ?: return ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "no_config")
        if (!applicable(spec, cfg0)) return ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "not_applicable")
        // The config exactly as written: its own address, its own handshake.
        val asIs = spec.shape == TunnelShape.AS_IS && spec.addressPolicy == AddressPolicy.RESOLVED && spec.family == Family.V4 && spec.port == 0
        val addr = if (asIs) null else addressForPolicy(spec, cfg0)
            ?: return ValidationResult.fail(FailureClass.PREREQ_MISSING, detail = "no_address")
        val cfg = (if (addr != null) CfEdgeHeal.onto(cfg0.copy(), addr) else cfg0.copy()).also { if (spec.port > 0) it.port = spec.port }
        val (status, xfer) = native.withCore(shaped(cfg, spec)) { port -> CfDataProbe.validate(port) }
        if (addr != null && status == "ok" && xfer?.ok == true) onProvenAddress(CfStage.CONNECT, strategyId, cfg0, addr)
        return when {
            status == "config_invalid" -> ValidationResult.fail(FailureClass.UNKNOWN, detail = status)
            status != "ok" -> ValidationResult.fail(FailureClass.TUNNEL_FAILURE, detail = status)
            xfer == null -> ValidationResult.fail(FailureClass.TUNNEL_FAILURE, detail = "no_transfer")
            else -> xfer
        }
    }

    private fun addressForPolicy(spec: ShapeSpec, cfg: VpnConfig): String? = when {
        spec.family == Family.V6 || spec.addressPolicy == AddressPolicy.IPV6_TWIN ->
            seedV6.firstOrNull() ?: runCatching { CfEdgeHeal.goodV6(context) }.getOrNull()
        spec.addressPolicy == AddressPolicy.CLEAN_IP -> CfEdge.good(context, 1).firstOrNull()
        spec.addressPolicy == AddressPolicy.CF_EDGE -> CfEdge.find(context, CfEdge.DEFAULT_HOST, 1).firstOrNull()
        else -> cfg.address.takeIf { it.isNotBlank() }?.let { address ->
            if (address.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) address
            else runCatching { env.resolve(if (address.contains(':')) cfg.sni.ifBlank { cfg.wsHost } else address)
                .firstOrNull { it is java.net.Inet4Address && !CfBogon.isBogon(it.hostAddress.orEmpty()) }?.hostAddress }.getOrNull()
                // The name did not resolve here, or only to a block page's address (10.10.34.x): every
                // Cloudflare edge serves it, so the one proven on this line -- or the summary's -- does.
                ?: CfEdge.good(context, 1).firstOrNull() ?: FirewallFamily.EDGE_V4
        }
    }

    // ---- shared DATA helpers ----------------------------------------------------------------------

    /** generateSpeedtestConfig with no saved strategy, then the spec's shape stamped onto the proxy outbound. */
    private fun shaped(cfg: VpnConfig, spec: ShapeSpec): String {
        val json = JSONObject(XrayJsonGenerator.generateSpeedtestConfig(cfg, strategyStage = null))
        val outs = json.optJSONArray("outbounds") ?: return json.toString()
        val proxy = (0 until outs.length()).map { outs.getJSONObject(it) }.firstOrNull { it.optString("tag") == "proxy" } ?: return json.toString()
        val stream = proxy.optJSONObject("streamSettings") ?: return json.toString()
        CfShape.apply(stream, spec, stream.optString("network"))
        return json.toString()
    }

    private fun hostOf(url: String): String? = url.removePrefix("https://").removePrefix("http://").substringBefore('/').takeIf { it.isNotBlank() }

    private fun isCloudflare(c: VpnConfig): Boolean = com.mlmvpn.scanner.utils.CfUnfilter.isCloudflareFronted(c)

    companion object {
        /** The Cloudflare ranges that answered on most lines in the Doctor reports (2026-10-01..03). */
        private val LIVE_RANGES = listOf("104.16.0.0/14", "104.24.0.0/15", "172.64.0.0/15", "172.67.0.0/16", "188.114.96.0/22", "104.21.0.0/16")

        fun isXrayShape(shape: TunnelShape): Boolean = shape in setOf(
            TunnelShape.AS_IS, TunnelShape.CHROME, TunnelShape.FRAG_FP, TunnelShape.FRAG_CHROME, TunnelShape.ECH,
            TunnelShape.FIREFOX, TunnelShape.SAFARI, TunnelShape.RANDOMIZED, TunnelShape.IOS, TunnelShape.EDGE, TunnelShape.ANDROID,
        ) || shape in ShapeSpec.XHTTP_SHAPES

        /** Whether [spec] can be applied to [c] at all: XHTTP tunings need an XHTTP config, and the stream mode a non-Worker host. */
        fun applicable(spec: ShapeSpec, c: VpnConfig): Boolean {
            if (!spec.needsXhttp) return true
            if (c.network.lowercase() !in ShapeSpec.XHTTP_NETWORKS) return false
            if (spec.shape == TunnelShape.XHTTP_H2_STREAM) {
                val names = listOf(c.sni, c.xhttpHost, c.wsHost, c.address).map { it.lowercase() }
                if (names.any { it.endsWith(".workers.dev") || it.endsWith(".pages.dev") }) return false
            }
            return true
        }
    }
}
