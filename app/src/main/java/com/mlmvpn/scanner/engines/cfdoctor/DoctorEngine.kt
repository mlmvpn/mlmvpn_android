package com.mlmvpn.scanner.engines.cfdoctor

import android.content.Context
import android.util.AtomicFile
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.data.ScannerManager
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.StoreFiles
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cloudflare Doctor: one run that finds where this line breaks the way to Cloudflare, for the four
 * things the app needs from it --
 *  ST1 the Cloudflare account (Cloud section), ST2 the panels' configs, ST3 the configs connecting,
 *  ST4 the clean-IP scanner --
 * plus the general network checks that explain them (DNS, addresses, names, fragmenting, ...).
 *
 * App-scoped, not screen-scoped: a run takes minutes, and the user leaving for Telegram (to send
 * the report of the PREVIOUS run, typically) must not cancel it. Only Stop does.
 *
 * The low-level probes (raw TLS with fragmenting, DNS wire, the VLESS/Trojan stage client) are
 * kept from the first version; this file is the orchestration around them.
 */
object DoctorEngine {

    // In run order: the scan comes before connecting, which uses the addresses it found.
    enum class Step { IDLE, TRIAGE, ACCOUNT, PANEL, SCANNER, CONFIG, METHODS, VARIANTS, DONE }

    data class State(
        val running: Boolean = false,
        val step: Step = Step.IDLE,
        /** 0..1, by milestones rather than elapsed time (a fast line should not sit at 20%). */
        val fraction: Float = 0f,
        val progress: DoctorProgress = DoctorProgress(),
        val advice: DoctorAdvice.Advice? = null,
        val hasCredential: Boolean = false,
        val finishedAt: Long = 0,
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var app: Context? = null

    /** The last report's text, for sharing and saving. Recovered from disk after a restart. */
    @Volatile var report: String = ""
        private set

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        report = runCatching { latest(context).openRead().bufferedReader().use { it.readText() } }.getOrDefault("")
    }

    /** When the run behind [report] started, and on what line: the file is named after the RUN, not the moment of sharing. */
    @Volatile private var reportTag: String = ""

    fun fileName(context: Context): String =
        "mlmvpn-doctor-${reportTag.ifEmpty { java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + "-" + AndroidNetEnv.operator(context) }}.txt"

    fun start(context: Context, full: Boolean, label: String, credential: Pair<String, String>?, account: CloudAccount? = null, complaint: Set<String> = emptySet()) {
        init(context)
        if (job?.isActive == true) return
        // The previous report goes now. Sharing after a new run must never send the old one --
        // which is exactly what happened when a run failed before writing anything (2026-10-01:
        // two "different" reports, MCI and Irancell, were the same Wi-Fi run).
        report = ""
        runCatching { latest(context).delete() }
        val net = runCatching { AndroidNetEnv(context.applicationContext).environment(context)["net"] }.getOrNull() ?: "unknown"
        reportTag = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + "-" +
            (if (net == "wifi") "WIFI" else AndroidNetEnv.operator(context))
        _state.value = State(running = true, step = Step.TRIAGE, fraction = 0.02f, hasCredential = false)
        job = scope.launch {
            try {
                run(context.applicationContext, full, label, credential, account, complaint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(failed = true)
                // Even a run that broke leaves a report saying where, so the next file is not empty.
                val line = "VERDICT internal_error:${e.javaClass.simpleName}@${_state.value.step}\n"
                report = if (report.isBlank()) "HEADER MLMVPN DOCTOR v3 complete=false\n$line" else "$report\n$line"
            } finally {
                val p = _state.value.progress
                _state.value = _state.value.copy(
                    running = false, step = Step.DONE, fraction = 1f, finishedAt = System.currentTimeMillis(),
                    advice = DoctorAdvice.of(p.rows, p.flags, p.complete, _state.value.hasCredential),
                )
            }
        }
    }

    fun stop() { job?.cancel() }

    private fun step(s: Step, fraction: Float) {
        _state.value = _state.value.copy(step = s, fraction = maxOf(_state.value.fraction, fraction))
    }

    private suspend fun run(app: Context, full: Boolean, label: String, credential: Pair<String, String>?, selected: CloudAccount?, complaint: Set<String>) {
        val env = AndroidNetEnv(app)
        val plan = runCatching { StoreFiles.open(app, "cf_doctor_plan.json").bufferedReader().use { DoctorPlan.parse(it.readText()) } }
            .getOrElse { app.assets.open("cf_doctor_plan.json").bufferedReader().use { DoctorPlan.parse(it.readText()) } }
        val budget = DataBudget(plan.maxBytes)
        val net = NetProbes(env, budget)
        val cloud = CloudManager(app)
        val accounts = selected?.let { listOf(it.copy()) } ?: cloud.accountsFlow.value.map { it.copy() }
        val hasCredential = accounts.isNotEmpty() || !credential?.first.isNullOrBlank()
        _state.value = _state.value.copy(hasCredential = hasCredential)
        val environment = env.environment(app).toMutableMap()
        if (label.isNotBlank()) environment["network_label"] = safeLabel(label)
        environment["complaint"] = complaint.sorted().joinToString(",").ifEmpty { "none" }
        val v6 = runCatching { com.mlmvpn.scanner.data.CfFamily.hasIpv6Route() }.getOrDefault(false)
        environment["v6route"] = if (v6) "y" else "n"
        // Provider and region (never the address): the regional differences need them.
        environment.putAll(kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { NetInfo.lookup(env) }.getOrDefault(emptyMap()) })
        val deep = DeepReport()

        val probes = object : NetworkDoctorProbe(net) {
            override suspend fun probe(spec: ProbeSpec, timeout: Int): Evidence =
                if (spec.kind == "h2") AndroidHttpProbe.h2(spec, env, budget, timeout) else super.probe(spec, timeout)
        }
        var configs = emptyList<VpnConfig>()

        val runner = DoctorRunner(plan, probes, budget,
            stages = { r ->
                // Doctor v3: every stage with the user's real account, panels and configs, each over
                // every way, so the report says which ways work for THIS user and why the others do not.
                val book = EdgeBook(env)
                val ways = WayClient(env, book)
                val matrix = WayMatrix(ways, v6)
                // Each stage on its own: a time limit or an error in one is written down and the
                // next one runs anyway. And none depends on the one before having worked -- each has
                // its own fallback (see below), so a user whose account fails still gets the deploy
                // path, the panels' way, the scan and the connection measured.
                suspend fun guard(stage: String, ms: Long, block: suspend () -> Unit) {
                    try {
                        withTimeoutOrNull(ms) { block() } ?: deep.verdict(stage, "stage", "stage_time_limit")
                    } catch (e: CancellationException) {
                        if (job?.isCancelled == true) throw e
                        deep.verdict(stage, "stage", "stage_cancelled_inside")
                    } catch (e: Exception) {
                        deep.verdict(stage, "stage", "doctor_error:${e.javaClass.simpleName}")
                    }
                }
                try {
                    // ---- ST1: the account, and the deploy path (measured even when the account fails).
                    step(Step.ACCOUNT, 0.12f)
                    guard("ACCOUNT", 210_000) { AccountStage(app, cloud, env, ways, matrix, deep, r::record).run(accounts, credential) }
                    if (!hasCredential) r.decision("skip:ST1:no_credential")

                    // ---- ST2: every panel (their addresses are stored on the phone, not asked of the
                    // account API); with none, a reference Worker name over every way.
                    step(Step.PANEL, 0.3f)
                    val fromPanels = mutableListOf<VpnConfig>()
                    guard("PANEL", 240_000) { PanelStage(app, ways, matrix, deep, r::record).run(accounts, fromPanels) }

                    // ---- which configs the scan and the connection use: the user's own first; with no
                    // Cloudflare config of their own, public ones (marked so), so both stages still measure.
                    val saved = runCatching { NodeManager(app).nodesFlow.value.mapNotNull { VpnConfig.parseUri(it.uri) } }.getOrDefault(emptyList())
                    val scannerConfig = runCatching { VpnConfig.parseUri(ScannerManager.globalBaseConfig.value) }.getOrNull()
                    val own = ConfigProbes.pick(fromPanels + saved + listOfNotNull(scannerConfig))
                    val publicCfgs = if (own.none { ConfigProbes.isCloudflare(it) && ConfigProbes.rawConfig(it) != null })
                        runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { PublicConfigs.fetch(env, deep) } }.getOrDefault(emptyList()) else emptyList()
                    configs = own + publicCfgs
                    val sources = own.map { "own" } + publicCfgs.map { "public" }
                    deep.line("CONFIGS", "ALL", "picked", "own" to own.size, "own_cloudflare" to own.count { ConfigProbes.isCloudflare(it) },
                        "from_panels" to fromPanels.size, "saved" to saved.size, "scanner_base" to (scannerConfig != null), "public" to publicCfgs.size)

                    // ---- ST4: the scanner, across every range.
                    step(Step.SCANNER, 0.48f)
                    val scanBase = scannerConfig ?: configs.firstOrNull { ConfigProbes.isCloudflare(it) && ConfigProbes.rawConfig(it) != null }
                        ?: configs.firstOrNull { ConfigProbes.isCloudflare(it) }
                    val baseWhy = when {
                        scannerConfig != null -> "scanner_base_config"
                        scanBase == null -> "none"
                        publicCfgs.any { it === scanBase } -> "public_config"
                        else -> "first_cloudflare_config"
                    }
                    val scan = ScanStage(app, env, deep, r::record)
                    guard("SCAN", 480_000) { scan.run(scanBase, baseWhy, v6) }

                    // ---- ST3: connecting, and data both ways.
                    step(Step.CONFIG, 0.66f)
                    guard("CONNECT", 540_000) { ConnectStage(app, env, deep, r::record).run(configs, scan.best.map { it.ip }, v6, sources) }

                    // ---- what the edge groups and the app's edge picker found on this line.
                    runCatching {
                        deep.line("EDGES", "NET", "groups",
                            "alive" to book.knownEdges().entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "-" },
                            "dead" to book.deadGroups().sorted().joinToString(",").ifEmpty { "-" },
                            "cfedge" to com.mlmvpn.scanner.data.CfEdge.good(app, 4).joinToString(",").ifEmpty { "-" })
                    }
                    runCatching { tunnelCheck(app, r, budget) }
                } finally {
                    ways.shutdown()
                }
                step(Step.METHODS, 0.9f)
            },
            // The old per-config variations are part of ST3 now (ConnectStage).
            adaptiveStages = { _, _ -> },
            persist = { p ->
                val text = CfDoctorReport.render(plan.version, p.rows, p.flags, p.decisions, p.complete, environment, hasCredential, deep.text())
                report = text
                runCatching {
                    val f = latest(app)
                    val out = f.startWrite()
                    try { out.write(text.toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
                }
                val fraction = when {
                    p.tier >= 2 -> 0.9f
                    p.tier == 1 -> maxOf(0.15f, _state.value.fraction)
                    else -> 0.05f + 0.1f * (p.rows.size / 20f).coerceAtMost(1f)
                }
                _state.value = _state.value.copy(progress = p, fraction = maxOf(_state.value.fraction, fraction))
            })
        runner.run(full)
    }

    /** ST2 for one account: each deployed panel, its host, its subscription, and what parses. */
    private suspend fun panel(app: Context, a: CloudAccount, cloud: CloudManager, env: AndroidNetEnv, net: NetProbes, budget: DataBudget, r: DoctorRunner, configs: MutableList<VpnConfig>) {
        val panels = listOf("bpb" to a.workerUrl, "edg" to a.edgWorkerUrl, "nahan" to a.nahanWorkerUrl, "mlm" to a.mlmWorkerUrl).filter { !it.second.isNullOrBlank() }
        if (panels.isEmpty()) { r.record(Evidence("ST2", "account", "panel.none", numbers = mapOf("terminal" to 1L))); return }
        for ((type, url) in panels) {
            val uri = runCatching { URI(url) }.getOrNull()
            val host = uri?.host
            if (host.isNullOrBlank() || uri.scheme != "https") { r.record(Evidence("ST2", type, "host.invalid", numbers = mapOf("terminal" to 1L))); continue }
            // The worker's own host, publicly: DNS, TCP, TLS and a HEAD.
            val hostRow = net.run(ProbeSpec("W1", host, method = "HEAD", privateEndpoint = true, route = type), 8_000)
            r.record(hostRow)
            if (hostRow.http == 0) { r.record(Evidence("ST2", type, "host.${hostRow.code.substringBefore('_')}", numbers = mapOf("terminal" to 1L))); continue }
            if (type !in setOf("bpb", "edg")) {
                // The panel answers; its subscription is not instrumented, so reachable is all we say.
                r.record(Evidence("ST2", type, "ok", hostRow.http, numbers = mapOf("terminal" to 1L, "limited" to 1L)))
                r.decision("limited:W2:$type:subscription_not_instrumented")
                continue
            }
            var sawSubscription = false
            val builder = cloud.diagnosticClientBuilder().dispatcher(Dispatcher()).connectionPool(ConnectionPool())
                .callTimeout(15_000, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .dns { h -> env.resolve(h).filter { !CfBogon.isBogon(it.hostAddress.orEmpty()) }.ifEmpty { throw java.net.UnknownHostException() } }
            env.picked?.network?.socketFactory?.let { builder.socketFactory(it) }
            builder.addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val bytes = readBody(response, budget, 262_144)
                val text = String(bytes, Charsets.UTF_8)
                if (type == "bpb") {
                    sawSubscription = true
                    val result = PanelChain.inspect(response.code, text) { VpnConfig.parseUri(it)?.let { c -> c.address.isNotBlank() } == true }
                    val ev = Evidence.http("ST2", type, response.code, response.headers.names().associateWith { response.header(it).orEmpty() }, text, true)
                    r.record(ev.copy(code = result.layer, numbers = ev.numbers + mapOf("terminal" to 1L)))
                } else r.record(Evidence("W2", "edg_kv", if (response.isSuccessful) "ok" else "kv_unavailable", response.code))
                response.newBuilder().body(bytes.toResponseBody(response.body?.contentType())).build()
            }
            val client = builder.build()
            try {
                val result = managed(client) { if (type == "bpb") cloud.fetchCloudConfigs(a, client) else cloud.fetchEdgConfigs(a, client) }
                configs += result.second.mapNotNull(VpnConfig::parseUri)
                if (type == "edg") r.record(Evidence("ST2", "edg_local", if (result.first && result.second.isNotEmpty()) "ok" else "parse.zero",
                    numbers = mapOf("terminal" to 1L, "count" to result.second.size.toLong())))
                else if (!sawSubscription) r.record(Evidence("ST2", type, "sub.unavailable", numbers = mapOf("terminal" to 1L)))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                r.record(Evidence("ST2", type, "sub.unavailable", numbers = mapOf("terminal" to 1L)))
            } finally {
                client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            }
        }
    }

    private suspend fun tunnelCheck(app: Context, r: DoctorRunner, budget: DataBudget) {
        val status = runCatching { com.mlmvpn.scanner.lan.LanShare.status(app) }.getOrNull()
        if (status?.vpnUp != true) { r.decision("skip:R1:no_tunnel"); return }
        val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", status.upstreamPort)))
            .callTimeout(8_000, TimeUnit.MILLISECONDS).followRedirects(false).build()
        try {
            val (code, rest) = managed(client) {
                client.newCall(Request.Builder().url("https://api.cloudflare.com/client/v4/ips").build()).execute().use { resp ->
                    resp.code to (resp.headers.names().associateWith { resp.header(it).orEmpty() } to String(readBody(resp, budget, 8_192)))
                }
            }
            r.record(Evidence.http("R1", "socks", code, rest.first, rest.second, false))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            r.record(Evidence.failure("R1", "socks", "http", e))
        } finally {
            client.connectionPool.evictAll(); client.dispatcher.cancelAll(); client.dispatcher.executorService.shutdown()
        }
    }

    /** A blocking OkHttp call that Stop can actually interrupt. */
    private suspend fun <T> managed(client: OkHttpClient, block: suspend () -> T): T = suspendCancellableCoroutine { cont ->
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        s.launch {
            try { val v = block(); if (cont.isActive) cont.resume(v) } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) } finally { s.coroutineContext[Job]?.cancel() }
        }
        cont.invokeOnCancellation { client.dispatcher.cancelAll(); s.coroutineContext[Job]?.cancel() }
    }

    private fun readBody(response: Response, budget: DataBudget, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        response.body?.byteStream()?.use { input ->
            val buffer = ByteArray(4096)
            while (out.size() < limit) { val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size())); if (n < 0) break; budget.count(n); out.write(buffer, 0, n) }
        }
        return out.toByteArray()
    }

    private fun latest(context: Context) = AtomicFile(File(File(context.cacheDir, "doctor").apply { mkdirs() }, "latest.txt"))

    /** The one free-text field: no addresses, hosts, ids or line breaks survive it. */
    fun safeLabel(raw: String): String = com.mlmvpn.scanner.utils.SecretRedactor.redact(raw.take(80))
        .replace(Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b|\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|\\b[\\w-]+(?:\\.[\\w-]+)+\\b"), "redacted")
        .replace(Regex("[\\r\\n\\t]"), " ")
}
