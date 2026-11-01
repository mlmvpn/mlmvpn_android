package com.mlmvpn.scanner.engines.cfdoctor

import android.content.Context
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every way, for one request: first each way of finding an address (resolvers, edge ranges, the
 * app's own edge picker, IPv6), then each way of speaking TLS on the most telling address of those
 * -- one where TCP connects but TLS is cut, when there is one, because that is where a TLS trick
 * can make the difference.
 */
class WayMatrix(private val client: WayClient, private val v6: Boolean) {

    fun addressWays(): List<Way> =
        listOf(Way(AddrSource.SYSTEM), Way(AddrSource.APP_EDGE), Way(AddrSource.DOH_GOOGLE), Way(AddrSource.DOH_ADGUARD), Way(AddrSource.UDP_GOOGLE)) +
            EdgeGroups.GROUPS.keys.map { Way(AddrSource.EDGE, group = it) } +
            (if (v6) listOf(Way(AddrSource.V6)) else emptyList())

    data class Outcome(val results: List<WayResult>, val anchor: String?, val anchorWhy: String)

    suspend fun run(req: (Way) -> WayClient.Req, tlsVariants: Boolean = true, parallel: Int = 6): Outcome = coroutineScope {
        val gate = Semaphore(parallel)
        val ways = addressWays()
        val first = ways.map { w -> async(Dispatchers.IO) { gate.withPermit { client.exchange(w, req(w)) } } }.awaitAll()
        // The most telling address for the TLS ways.
        val tlsCut = first.firstOrNull { it.phase.startsWith("tls") && it.ip.isNotEmpty() }
        val sys = first.firstOrNull { it.way == "sys" && it.ip.isNotEmpty() && it.phase !in setOf("tcp", "socket") }
        val answered = first.firstOrNull { it.answered && it.ip.isNotEmpty() && !it.ip.contains(':') }
        val (anchor, why) = when {
            tlsCut != null -> tlsCut.ip to "tls_cut_on_${tlsCut.way}"
            sys != null -> sys.ip to "system_address"
            answered != null -> answered.ip to "answered_on_${answered.way}"
            else -> null to "no_tcp_anywhere"
        }
        val second = if (!tlsVariants || anchor == null) emptyList() else {
            val base = first.first { it.ip == anchor }.way.substringBefore('+')
            TlsVariant.entries.filter { it != TlsVariant.PLAIN }.map { v ->
                async(Dispatchers.IO) { gate.withPermit { client.exchange(Way(AddrSource.SYSTEM, v), req(Way(AddrSource.SYSTEM, v)), fixedIp = anchor).copy(way = "$base+${v.key}") } }
            }.awaitAll()
        }
        Outcome(first + second, anchor, why)
    }
}

/** Meaning of a Cloudflare API answer, for the report and the verdict. */
object CfApiAnswer {
    fun of(r: WayResult): String {
        if (!r.answered) return "net.${r.phase}"
        val ok = runCatching { org.json.JSONObject(r.body).optBoolean("success") }.getOrDefault(false)
        val c = r.cfErrors
        return when {
            r.http in 200..299 && ok -> "ok"
            r.http == 429 || 1015 in c || 971 in c -> "cf.ratelimit"
            1000 in c -> "cf.token_invalid"
            9103 in c || 9106 in c || 9107 in c -> "cf.key_or_email_wrong"
            6003 in c || 6111 in c -> "cf.header_malformed"
            10000 in c -> "cf.no_permission"
            9109 in c -> "cf.token_expired_or_ip_restricted"
            r.http == 401 || r.http == 403 -> if (r.body.contains("\"success\"")) "cf.auth" else "cf.blocked_${r.http}"
            r.http in 500..599 -> "cf.server_${r.http}"
            else -> "http.${r.http}"
        }
    }
}

/**
 * ST1 and the deploy path, with the user's real account(s).
 *  1. The app's own check, unchanged (what the user actually experiences).
 *  2. The same identity check over every way.
 *  3. On a way that worked: the permissions every app feature needs, one request each.
 *  4. The deploy path: an upload the size of a real worker, which Cloudflare reads in full and
 *     then refuses (no metadata part), so nothing is created on the account. If it were ever
 *     accepted, the script is deleted at once.
 */
class AccountStage(
    private val app: Context, private val cloud: CloudManager, private val env: AndroidNetEnv,
    private val client: WayClient, private val matrix: WayMatrix, private val out: DeepReport,
    private val emit: (Evidence) -> Unit,
) {
    private val API = "api.cloudflare.com"

    /** Whether any account's credential was accepted on some way: the later stages say so in their lines. */
    var credentialOk = false; private set

    suspend fun run(accounts: List<CloudAccount>, credential: Pair<String, String>?) {
        val targets: List<Pair<String, CloudAccount?>> = accounts.mapIndexed { i, a -> "acc${i + 1}" to a } +
            (if (!credential?.first.isNullOrBlank()) listOf("new" to null) else emptyList())
        if (targets.isEmpty()) { out.verdict("ACCOUNT", "-", "no_account"); return }
        // One account's trouble never stops the next one.
        for ((tag, a) in targets.take(4)) runCatching { one(tag, a, credential) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; out.verdict("ACCOUNT", tag, "doctor_error:${it.javaClass.simpleName}") }
    }

    private fun headers(a: CloudAccount?, credential: Pair<String, String>?): Map<String, String> {
        if (a != null) return cloud.authHeadersFor(a).toMultimap().mapValues { it.value.first() }
        val (token, email) = credential!!
        return if (email.isBlank()) mapOf("Authorization" to "Bearer ${token.trim()}")
        else mapOf("X-Auth-Email" to email.trim(), "X-Auth-Key" to token.trim())
    }

    private suspend fun one(tag: String, a: CloudAccount?, credential: Pair<String, String>?) {
        val h = headers(a, credential)
        val bearer = h.containsKey("Authorization")
        out.section("ACCOUNT", tag, "scheme" to if (bearer) "token" else "global_key", "has_account_id" to (a?.accountId?.isNotBlank() == true))

        // 1. The app's own path.
        val st1 = St1RealPath(cloud, env, DataBudget(8L * 1024 * 1024))
        val appRows = ArrayList<Evidence>()
        withTimeoutOrNull(45_000) { st1.run(a?.copy(), credential, "real.$tag", null) { appRows += it; emit(it) } }
            ?: emit(Evidence("ST1", "real.$tag", "net.timeout", numbers = mapOf("terminal" to 1L)))
        val appVerdict = appRows.lastOrNull { it.id == "ST1" }
        out.line("APP", "ACCOUNT", tag, "result" to (appVerdict?.code ?: "timeout"), "http" to appVerdict?.http, "cf" to appVerdict?.errors?.joinToString("/"))
        appRows.filter { it.id == "A1" }.forEach { out.line("APPREQ", "ACCOUNT", tag, "req" to it.route.substringAfterLast(':'), "code" to it.code, "http" to it.http, "cf" to it.errors.joinToString("/"), "ms" to it.elapsedMs) }

        // 2. The identity check over every way.
        val path = if (bearer) "/client/v4/user/tokens/verify" else "/client/v4/user"
        val m = matrix.run({ WayClient.Req(API, path, headers = h + ("User-Agent" to UA), deadlineMs = 12_000) })
        m.results.forEach { out.way("ACCOUNT", tag, it, CfApiAnswer.of(it)) }
        out.line("ANCHOR", "ACCOUNT", tag, "ip" to m.anchor, "why" to m.anchorWhy)
        val good = m.results.filter { CfApiAnswer.of(it) == "ok" }
        val answered = m.results.filter { it.answered }
        val appNetwork = appVerdict == null || appVerdict.code.startsWith("net.")
        val verdict = when {
            good.isNotEmpty() && appVerdict?.ok == true -> "ok"
            // The app's check reached Cloudflare and Cloudflare objected: that, not the route, is the finding.
            good.isNotEmpty() && !appNetwork -> "identity_ok_app_check_says_${appVerdict!!.code}"
            good.isNotEmpty() -> "app_route_fails_other_ways_work"
            answered.isNotEmpty() -> CfApiAnswer.of(answered.groupBy { CfApiAnswer.of(it) }.maxByOrNull { it.value.size }!!.value.first())
            else -> "unreachable:" + m.results.groupBy { it.phase }.mapValues { it.value.size }.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}${it.value}" }
        }
        out.verdict("ACCOUNT", tag, verdict, "ways_ok" to good.joinToString(",") { it.way }.ifEmpty { "-" })

        // 3. Permissions, on a way that worked.
        val via = good.firstOrNull { it.way == "sys" } ?: good.firstOrNull()
        if (via != null) credentialOk = true
        val accountId = a?.accountId?.takeIf { it.isNotBlank() }
        if (via != null && accountId != null) {
            val w = Way(AddrSource.SYSTEM)
            for ((name, p) in listOf(
                "workers_subdomain" to "/client/v4/accounts/$accountId/workers/subdomain",
                "workers_scripts" to "/client/v4/accounts/$accountId/workers/scripts",
                "kv" to "/client/v4/accounts/$accountId/storage/kv/namespaces?per_page=5",
                "d1" to "/client/v4/accounts/$accountId/d1/database?per_page=5",
                "account" to "/client/v4/accounts/$accountId",
            )) {
                val r = withContext(Dispatchers.IO) { client.exchange(w, WayClient.Req(API, p, headers = h + ("User-Agent" to UA), deadlineMs = 12_000), fixedIp = via.ip) }
                out.line("PERM", "ACCOUNT", tag, "what" to name, "result" to CfApiAnswer.of(r), "http" to r.http, "cf" to r.cfErrors.joinToString("/"), "ms" to r.ms)
            }
            upload(tag, accountId, h, m.results, pathOnly = false)
        } else {
            // The account did not work (or has no id yet), but the deploy PATH can still be measured:
            // the same worker-sized upload, which Cloudflare refuses for the credential instead of the
            // missing metadata. Whether the body gets through is what decides deploys on this line.
            out.line("PERM", "ACCOUNT", tag, "what" to "all", "result" to if (via == null) "skipped_credential_not_accepted" else "skipped_no_account_id")
            upload(tag, accountId ?: "0".repeat(32), h, m.results, pathOnly = true)
        }
    }

    /** The deploy path: a worker-sized body over several ways, refused by Cloudflare after it is read. */
    private suspend fun upload(tag: String, accountId: String, h: Map<String, String>, seen: List<WayResult>, pathOnly: Boolean) {
        val size = runCatching { com.mlmvpn.scanner.store.StoreFiles.open(app, "worker.js").use { it.readBytes().size } }.getOrDefault(400_000)
            .coerceIn(200_000, 1_200_000)
        val boundary = "mlmvpnDoctor" + System.currentTimeMillis()
        val filler = ByteArray(size) { ('a' + (it % 26)).code.toByte() }
        val body = ("--$boundary\r\nContent-Disposition: form-data; name=\"doctor_probe\"; filename=\"probe.txt\"\r\nContent-Type: text/plain\r\n\r\n").toByteArray() +
            filler + "\r\n--$boundary--\r\n".toByteArray()
        val name = "mlmvpn-doctor-upload-probe"
        val path = "/client/v4/accounts/$accountId/workers/scripts/$name"
        // The app's way, the edge picker, and up to three ways that answered the identity check.
        // The app's way, the edge picker, then up to three ways that got an answer (or at least through TLS).
        val ways = (listOf("sys", "cfedge") + seen.filter { it.answered }.map { it.way } +
            seen.filter { it.phase in setOf("send", "wait", "body") }.map { it.way }).distinct().take(5)
        out.section("UPLOAD", tag, "bytes" to body.size, "mode" to if (pathOnly) "path_only_credential_not_usable" else "full")
        for (wid in ways) {
            val way = seen.firstOrNull { it.way == wid } ?: continue
            if (way.ip.isEmpty()) { out.line("WAYSKIP", "UPLOAD", tag, "way" to wid, "why" to way.phase); continue }
            val r = withContext(Dispatchers.IO) {
                client.exchange(Way(AddrSource.SYSTEM, tlsOf(wid)), WayClient.Req("api.cloudflare.com", path, "PUT",
                    h + mapOf("User-Agent" to UA, "Content-Type" to "multipart/form-data; boundary=$boundary"), body,
                    deadlineMs = 90_000, trackUpload = true), fixedIp = way.ip).copy(way = wid)
            }
            val outcome = when {
                r.answered && r.http in 200..299 -> "accepted_unexpectedly"
                r.answered && r.sent >= body.size && pathOnly -> "path_ok_full_body_then_${CfApiAnswer.of(r)}"
                r.answered && r.sent >= body.size -> "ok_full_body_then_refused"
                r.answered -> "answered_before_full_body"
                r.phase == "send" -> "stalled_while_sending"
                r.phase == "wait" -> "sent_all_no_answer"
                else -> "net.${r.phase}"
            }
            out.way("UPLOAD", tag, r, outcome, "of" to body.size)
            if (r.answered && r.http in 200..299) {
                // Never leave anything behind on a user's account.
                val d = withContext(Dispatchers.IO) { client.exchange(Way(AddrSource.SYSTEM), WayClient.Req("api.cloudflare.com", path, "DELETE", h + ("User-Agent" to UA)), fixedIp = way.ip) }
                out.line("CLEANUP", "UPLOAD", tag, "http" to d.http)
            }
        }
    }

    private fun tlsOf(wayId: String): TlsVariant = TlsVariant.entries.firstOrNull { wayId.endsWith("+${it.key}") } ?: TlsVariant.PLAIN

    companion object { const val UA = "mlmvpn-doctor" }
}

/**
 * ST2: every panel on every account. The app's own fetch first (what the user gets), then the
 * panel's host over every way, and for BPB the real subscription over every way (with how many
 * configs each way brought back).
 */
class PanelStage(
    private val app: Context, private val client: WayClient, private val matrix: WayMatrix,
    private val out: DeepReport, private val emit: (Evidence) -> Unit,
) {
    data class Panel(val id: String, val host: String, val subPath: String?)

    suspend fun run(accounts: List<CloudAccount>, configs: MutableList<VpnConfig>) {
        var any = false
        accounts.take(4).forEachIndexed { i, a ->
            val tag = "acc${i + 1}"
            val panels = panelsOf(a)
            out.section("PANELS", tag, "installed" to panels.joinToString(",") { it.id }.ifEmpty { "none" })
            if (panels.isEmpty()) emit(Evidence("ST2", "account", "panel.none", numbers = mapOf("terminal" to 1L)))
            for (p in panels) {
                any = true
                // One panel's trouble never stops the next one.
                runCatching { one("${p.id}.$tag", a, p, full = i == 0, configs) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; out.verdict("PANEL", "${p.id}.$tag", "doctor_error:${it.javaClass.simpleName}") }
            }
        }
        if (!any) reference()
    }

    /**
     * No panel to test (no account, nothing deployed, or deploying is exactly what fails): the way
     * to Workers is measured anyway, on a made-up workers.dev name. Cloudflare answers any such name
     * (404), so an answer means a panel deployed here would be reachable, and a failure says where.
     */
    private suspend fun reference() {
        val host = "mlmvpn-doctor-" + java.util.UUID.randomUUID().toString().take(8) + ".workers.dev"
        out.section("PANEL", "reference", "why" to "no_panel_to_test", "host_kind" to "workers.dev")
        val m = matrix.run({ WayClient.Req(host, "/", "HEAD", mapOf("User-Agent" to "v2rayNG/1.9.0"), deadlineMs = 12_000) })
        m.results.forEach { out.way("PANEL", "reference", it, if (it.answered) "answered_${it.http}" else "net.${it.phase}") }
        val ok = m.results.filter { it.answered }
        out.verdict("PANEL", "reference", if (ok.isNotEmpty()) "workers_reachable" else "workers_unreachable", "ways_ok" to ok.joinToString(",") { it.way }.ifEmpty { "-" })
    }

    private fun host(url: String?) = url?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/')?.takeIf { it.isNotBlank() }

    private fun panelsOf(a: CloudAccount): List<Panel> = buildList {
        host(a.workerUrl)?.let { add(Panel("BPB", it, if (!a.subPath.isNullOrBlank()) "/${a.subPath}/sub/raw?app=xray" else null)) }
        host(a.edgWorkerUrl)?.let { add(Panel("EDG", it, null)) }
        host(a.nahanWorkerUrl)?.let { add(Panel("NHN", it, null)) }
        host(a.mlmWorkerUrl)?.let { add(Panel("MLM", it, null)) }
        runCatching { com.mlmvpn.scanner.engines.spider.SpiderPanel.install(app, a.accountId) }.getOrNull()?.let { host(it.url)?.let { h -> add(Panel("SPD", h, null)) } }
        runCatching { com.mlmvpn.scanner.engines.netra.NetraPanel.install(app, a.accountId) }.getOrNull()?.let { host(it.url)?.let { h -> add(Panel("NTR", h, null)) } }
        runCatching { com.mlmvpn.scanner.engines.gozargah.GozargahPanel.install(app, a.accountId) }.getOrNull()?.let { host(it.url)?.let { h -> add(Panel("GZG", h, null)) } }
        runCatching { com.mlmvpn.scanner.engines.nova.NovaPanel.install(app, a.accountId) }.getOrNull()?.let { host(it.url)?.let { h -> add(Panel("NVA", h, null)) } }
    }

    private suspend fun one(tag: String, a: CloudAccount, p: Panel, full: Boolean, configs: MutableList<VpnConfig>) {
        out.section("PANEL", tag, "host_kind" to if (p.host.endsWith(".workers.dev")) "workers.dev" else if (p.host.endsWith(".pages.dev")) "pages.dev" else "custom_domain")

        // 1. The app's own fetch.
        val t0 = System.currentTimeMillis()
        val appResult = runCatching {
            detached(35_000) { appFetch(a, p) } ?: throw java.util.concurrent.TimeoutException()
        }
        val links = appResult.getOrNull().orEmpty()
        val parsed = links.mapNotNull { VpnConfig.parseUri(it) }
        configs += parsed
        val appCode = when {
            appResult.isFailure -> "error:" + (appResult.exceptionOrNull()?.javaClass?.simpleName ?: "?")
            links.isEmpty() -> "empty"
            parsed.isEmpty() -> "parse.zero"
            else -> "ok"
        }
        out.line("APP", "PANEL", tag, "result" to appCode, "links" to links.size, "parsed" to parsed.size, "ms" to (System.currentTimeMillis() - t0))
        emit(Evidence("ST2", tag, if (appCode == "ok") "ok" else if (appCode == "empty") "sub.unavailable" else appCode.substringBefore(':'),
            numbers = mapOf("terminal" to 1L, "count" to parsed.size.toLong())))

        // 2. The host (or BPB's real subscription) over every way.
        val path = p.subPath ?: "/"
        val m = matrix.run({ WayClient.Req(p.host, path, if (p.subPath == null) "HEAD" else "GET", mapOf("User-Agent" to "v2rayNG/1.9.0"),
            maxBody = 262_144, deadlineMs = 14_000) }, tlsVariants = full)
        m.results.forEach { r ->
            val outcome = when {
                !r.answered -> "net.${r.phase}"
                p.subPath != null && r.http in 200..299 -> subCount(r.body).let { if (it > 0) "ok_configs_$it" else "answered_no_configs" }
                else -> "answered_${r.http}"
            }
            out.way("PANEL", tag, r, outcome)
        }
        val ok = m.results.filter { it.answered && (p.subPath == null || subCount(it.body) > 0) }
        out.verdict("PANEL", tag, when {
            appCode == "ok" -> "ok"
            ok.isNotEmpty() -> "app_route_fails_other_ways_work"
            m.results.any { it.answered } -> "host_answers_but_no_configs"
            else -> "unreachable"
        }, "ways_ok" to ok.joinToString(",") { it.way }.ifEmpty { "-" })
    }

    private fun subCount(body: String): Int = runCatching {
        val t = body.trim()
        val text = if (t.contains("://")) t else String(android.util.Base64.decode(t, android.util.Base64.DEFAULT))
        text.lines().count { it.contains("://") }
    }.getOrDefault(0)

    /**
     * The app's real "get configs" for the panel -- the path «میدان کانفیگ» uses. For the panels
     * whose configs belong to a user, that path makes the app's own service user when it is
     * missing, exactly as the Arena does; nothing else on the panel is touched.
     */
    private suspend fun appFetch(a: CloudAccount, p: Panel): List<String> {
        val ctx = app
        return when (p.id) {
            "BPB" -> CloudManager(ctx).fetchCloudConfigs(a.copy()).second
            "EDG" -> CloudManager(ctx).fetchEdgConfigs(a.copy()).second
            else -> com.mlmvpn.scanner.engines.arena.ArenaPanels.byId(p.id)?.candidates(ctx, a.copy()).orEmpty()
        }
    }
}

/** Background work the Doctor may walk away from: blocking clients cannot be interrupted, so the stage waits only [ms] for them. */
private val DETACHED = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

internal suspend fun <T> detached(ms: Long, block: suspend () -> T): T? {
    val d = DETACHED.async { block() }
    return withTimeoutOrNull(ms) { d.await() }.also { if (it == null) d.cancel() }
}
