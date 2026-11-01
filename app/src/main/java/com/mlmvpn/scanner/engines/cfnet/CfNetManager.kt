package com.mlmvpn.scanner.engines.cfnet

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.data.*
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.engines.cfdoctor.*
import com.mlmvpn.scanner.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException

/**
 * The application-wide coordinator of the strategy engine: it owns the registry and the learning
 * store, builds the real validators for a stage, runs diagnoses (one per stage at a time, and the
 * DATA stages one at a time overall, because `:xprobe` runs one core), and is the single place the
 * runtime asks "which proven strategy applies here?".
 *
 * No credential, URL, address or response body enters the learning store or the log line.
 */
class CfNetManager private constructor(private val app: Context) {
    val registry = StrategyRegistry.default()
    val store = StrategyStore(File(app.filesDir, "cfnet/strategies.json"))
    private val stageLocks = CfStage.entries.associateWith { Mutex() }
    private val dataLane = Mutex()

    /** What a diagnosis is doing right now, for any screen that wants to show it. */
    data class Live(val stage: CfStage? = null, val strategyId: String? = null, val tried: Int = 0)
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live

    /** The last outcome per stage in this process, for the Doctor and the developer view. */
    private val _outcomes = MutableStateFlow<Map<CfStage, StrategyEngine.StageOutcome>>(emptyMap())
    val outcomes: StateFlow<Map<CfStage, StrategyEngine.StageOutcome>> = _outcomes

    @Volatile private var cachedProfile: Pair<Long, NetworkProfile>? = null

    /** The current network's profile. Cached for a few seconds: generateConfig asks per scanned address. */
    fun profile(): NetworkProfile {
        val now = System.currentTimeMillis()
        cachedProfile?.takeIf { now - it.first < 3_000 }?.let { return it.second }
        return CfNetEnv.profile(app).also { cachedProfile = now to it }
    }

    fun enabled() = NetworkSettings.tlsUnfilter(app)

    /**
     * This network's firewall family ([FirewallFamily]): the one a DATA strategy proved by carrying
     * data here, else what the quick probe ([classify]) saw on it in the last few days; null when
     * neither has looked yet. For every part of the app that has to pick a Cloudflare handshake.
     */
    fun firewall(): FirewallFamily? {
        val p = profile()
        return store.family(p, registry::byId) ?: hint(p)
    }

    private fun hintPrefs() = app.getSharedPreferences("cfnet_family", Context.MODE_PRIVATE)
    private fun hintKey(p: NetworkProfile) = p.key + "|" + p.signature()

    private fun hint(p: NetworkProfile): FirewallFamily? = hintPrefs().getString(hintKey(p), null)?.split('|')
        ?.takeIf { it.size == 2 && System.currentTimeMillis() - (it[1].toLongOrNull() ?: 0) in 0..HINT_MS }
        ?.let { FirewallFamily.of(it[0]) }

    /**
     * What a few seconds' look at the line saw: the DATA strategies whose handshake passed there,
     * fastest first ([order]), the firewall family they point at, and the IPv6 edges that answered
     * (any address of a prefix is the same edge, so the one that answered is the one to prove on).
     */
    data class LineProbe(val family: FirewallFamily?, val order: List<String>, val v6: List<String> = emptyList())

    private data class ProbeWay(val id: String, val family: FirewallFamily, val spec: ShapeSpec, val edges: List<String>)

    /**
     * [classify] on the config a DATA diagnosis would run on -- [config] if it is a Cloudflare one, the
     * scanner's base config, else a saved one -- for a caller that orders several stages by it (the
     * Cloud tab's «سنجش روش‌ها»). Null when there is no Cloudflare config to look with.
     */
    suspend fun probeLine(config: VpnConfig? = null): LineProbe? = withContext(Dispatchers.IO) {
        CfNetEnv.refresh(app)
        cachedProfile = null
        val base = config?.takeIf(CfUnfilter::isCloudflareFronted)
            ?: VpnConfig.parseUri(ScannerManager.globalBaseConfig.value)?.takeIf(CfUnfilter::isCloudflareFronted)
            ?: candidateConfigs(null).firstOrNull()
            ?: return@withContext null
        classify(base, profile())
    }

    /**
     * Which firewall family this line is, and which of the 2026-10 summary's ways get through it,
     * before anything was proven on it: [base] dialled with each headline handshake on a few live
     * Cloudflare edges, all at once (in-process measurements, one round of a few seconds) -- ECH, F&F
     * and as written on IPv4 (the summary's 188.114.98.0 among the edges), and Chrome on IPv6 (its
     * 2a06:98c1:3121::7) where the line has IPv6. A line that passes one family's handshakes and not
     * the other's is that family; both or neither tells nothing. Kept as a hint and an order only --
     * nothing is recorded as proof, and the strategies are still proven one by one.
     */
    private suspend fun classify(base: VpnConfig, p: NetworkProfile): LineProbe? = withContext(Dispatchers.IO) {
        if (!CfUnfilter.isCloudflareFronted(base) || CfUnfilter.isLocalFront(base)) return@withContext null
        val rnd = java.security.SecureRandom()
        val v4 = (runCatching { CfEdge.good(app, 1) }.getOrDefault(emptyList()) + FirewallFamily.EDGE_V4 +
            EdgeGroups.random("104.16.0.0/14", rnd) + EdgeGroups.random("172.64.0.0/15", rnd)).distinct().take(4)
        val v6 = if (!p.hasV6) emptyList() else
            (listOf(FirewallFamily.EDGE_V6) + runCatching { CfFamily.sampleV6(1) }.getOrDefault(emptyList())).distinct()
        // As written only when the link carries no handshake of its own: its own ECH would read as "open".
        val own = base.ech.isNotBlank() || base.cipherSuites.isNotBlank() || base.finalMask.isNotBlank()
        val ways = listOfNotNull(
            ProbeWay("dat_ech_v4", FirewallFamily.ECH, ShapeSpec(TunnelShape.ECH), v4),
            ProbeWay("dat_fragfp_v4", FirewallFamily.FF, ShapeSpec(TunnelShape.FRAG_FP), v4),
            if (own) null else ProbeWay("dat_chrome_v4", FirewallFamily.OPEN, ShapeSpec(TunnelShape.CHROME), v4),
            // Chrome on Cloudflare's IPv6 is the «همراه اول»-type's second way.
            if (v6.isEmpty()) null else ProbeWay("dat_chrome_v6", FirewallFamily.ECH, ShapeSpec(TunnelShape.CHROME, Family.V6), v6),
        )
        runCatching { CfEchKey.fetch(app) }
        CombineEngine.ensureCoreEnv(app)
        val hits = kotlinx.coroutines.coroutineScope {
            ways.flatMap { w -> w.edges.map { ip -> async {
                Triple(w, ip, CombineEngine.measureDelay(shapedJson(CfEdgeHeal.onto(base.copy(), ip), w.spec), timeoutMs = CLASSIFY_MS))
            } } }.awaitAll()
        }.filter { it.third > 0 }
        // An IPv4 edge that carried a handshake here is a working edge for every step after this one.
        hits.filter { !it.second.contains(':') }.forEach { runCatching { CfEdge.noteGood(app, it.second, it.third) } }
        val passed = hits.map { it.first.family }.toSet()
        val family = when {
            FirewallFamily.OPEN in passed -> FirewallFamily.OPEN
            FirewallFamily.ECH in passed && FirewallFamily.FF !in passed -> FirewallFamily.ECH
            FirewallFamily.FF in passed && FirewallFamily.ECH !in passed -> FirewallFamily.FF
            else -> null
        }
        val order = hits.groupBy { it.first.id }.mapValues { (_, h) -> h.minOf { it.third } }.entries.sortedBy { it.value }.map { it.key }
        Log.i(TAG, "firewall probe: passed=${order.joinToString()} -> ${family?.key ?: "unknown"} net=${p.carrier}")
        if (family != null) hintPrefs().edit().putString(hintKey(p), "${family.key}|${System.currentTimeMillis()}").apply()
        LineProbe(family, order, hits.filter { it.second.contains(':') }.sortedBy { it.third }.map { it.second })
    }

    /** [cfg]'s measurement config with [spec]'s handshake stamped on its proxy outbound (no learned strategy). */
    private fun shapedJson(cfg: VpnConfig, spec: ShapeSpec): String {
        val json = org.json.JSONObject(XrayJsonGenerator.generateSpeedtestConfig(cfg, strategyStage = null))
        val outs = json.optJSONArray("outbounds") ?: return json.toString()
        (0 until outs.length()).mapNotNull(outs::optJSONObject).firstOrNull { it.optString("tag") == "proxy" }
            ?.optJSONObject("streamSettings")?.let { CfShape.apply(it, spec, it.optString("network")) }
        return json.toString()
    }

    /**
     * The proven winner for [stage] on this network (its last outcome a success, confident), or
     * null. With [config], only a strategy that can apply to it: an XHTTP tuning is never handed to
     * a WebSocket config, so nothing is ever recorded against a strategy that did not run.
     */
    fun saved(stage: CfStage, config: VpnConfig? = null, family: Family? = null): Strategy? = if (!enabled()) null else
        store.fastPath(profile(), stage, registry.forStage(stage).filter { applies(it, config) && (family == null || it.data?.family == family) })

    private fun applies(s: Strategy, config: VpnConfig?): Boolean {
        val d = s.data ?: return true
        return if (config != null) CfValidators.applicable(d, config) else !d.needsXhttp
    }

    /**
     * A config that dials one literal address -- a clean IP the scanner proved, and every config a
     * combine builds on it -- AND carries its handshake in its own link (`ech`, `cs`, `fm`): what the
     * combine writes from the scan that found the address. It is dialled exactly as written: no
     * learned strategy reshapes it and nothing moves its address or port, so the health test and the
     * connect use the handshake that found it. A config that dials a name is not affected.
     */
    fun dialledAsWritten(config: VpnConfig): Boolean = CfShape.literalFamily(config.address) != null &&
        (config.ech.isNotBlank() || config.cipherSuites.isNotBlank() || config.finalMask.isNotBlank())

    /**
     * Which proven strategy a live dial of [config] uses for [stage], and whose evidence chose it.
     *
     * A literal address has one family, and only a strategy of that family is a candidate: a shape
     * proven on Cloudflare's IPv6 edges says nothing about an IPv4 clean IP (stamped on one, it is
     * the very handshake the filter cuts -- what made every combined config fail its health test on
     * a line where IPv6 works plain and IPv4 only with a shape). A name keeps both families.
     */
    private fun runtimePath(stage: CfStage, config: VpnConfig?): StrategyStore.RuntimePath? {
        if (!enabled()) return null
        if (stage == CfStage.CONNECT && config != null && dialledAsWritten(config)) return null
        val literal = config?.let { CfShape.literalFamily(it.address) }
        val p = profile()
        val candidates = registry.forStage(stage).filter { s ->
            applies(s, config) && s.data?.family.let { f ->
                when {
                    f == null -> true
                    literal != null -> f == literal
                    else -> f != Family.V6 || p.hasV6
                }
            }
        }
        return store.runtimePath(p, stage, candidates, literal = literal != null)
    }

    fun runtimeStrategy(stage: CfStage, config: VpnConfig? = null): Strategy? = runtimePath(stage, config)?.strategy

    /** The strategy [apply] stamps on [config]'s stream for [stage]; null when it leaves it as written. */
    fun appliedStrategy(stage: CfStage?, config: VpnConfig): Strategy? =
        if (stage != null && CfUnfilter.isCloudflareFronted(config) && !CfUnfilter.isLocalFront(config)) runtimeStrategy(stage, config) else null

    /** For a log line: how [config] is dialled for [stage] -- `written`, a strategy id, or `none`. No address. */
    fun describe(config: VpnConfig, stage: CfStage): String = when {
        !CfUnfilter.isCloudflareFronted(config) -> "not-cf"
        stage == CfStage.CONNECT && dialledAsWritten(config) -> "written"
        else -> appliedStrategy(stage, config)?.id ?: "none"
    }

    /** A whole Xray outbound (a raw JSON config) as a [VpnConfig], for the same rules: its transport, names and own handshake. */
    fun outboundConfig(outbound: org.json.JSONObject): VpnConfig? {
        val stream = outbound.optJSONObject("streamSettings") ?: return null
        val servers = outbound.optJSONObject("settings")?.let { it.optJSONArray("vnext") ?: it.optJSONArray("servers") }
        val tls = stream.optJSONObject("tlsSettings")
        return VpnConfig(
            address = servers?.optJSONObject(0)?.optString("address").orEmpty(),
            network = stream.optString("network", "tcp"),
            tls = stream.optString("security"),
            sni = tls?.optString("serverName").orEmpty(),
            xhttpHost = (stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings"))?.optString("host").orEmpty(),
            wsHost = stream.optJSONObject("wsSettings")?.optJSONObject("headers")?.optString("Host").orEmpty(),
            ech = tls?.optString("echConfigList").orEmpty(),
            cipherSuites = tls?.optString("cipherSuites").orEmpty(),
            finalMask = stream.optJSONObject("finalmask")?.toString().orEmpty(),
        )
    }

    /** [saved] for a whole Xray outbound (a raw JSON config), judged by its own transport and names. */
    fun savedForOutbound(stage: CfStage, outbound: org.json.JSONObject): Strategy? {
        val config = outboundConfig(outbound) ?: return saved(stage)
        return runtimeStrategy(stage, config)
    }

    /**
     * What a combine writes into the copy of [nodeUri] it builds on [ip] ([CfShape.linkParams]): the
     * handshake of [strategyId] -- the SCAN strategy that verified that very address, as the scanner
     * recorded it -- or, for an address with no record of its own (an archive), this network's SCAN
     * winner for the address's family. Empty when the engine is off, nothing is proven, the config
     * is not Cloudflare TLS or rides the local SNI front, or the shape cannot apply to its transport.
     */
    fun handshakeParams(nodeUri: String, ip: String, strategyId: String?): Map<String, String?> {
        if (!enabled()) return emptyMap()
        val config = VpnConfig.parseUri(nodeUri) ?: return emptyMap()
        if (CfUnfilter.isLocalFront(config)) return emptyMap()
        val onIp = CfEdgeHeal.onto(config, ip)
        if (!CfUnfilter.isCloudflareFronted(onIp)) return emptyMap()
        val strategy = strategyId?.let(registry::byId) ?: runtimeStrategy(CfStage.SCAN, onIp) ?: return emptyMap()
        val spec = strategy.data ?: return emptyMap()
        if (!CfValidators.isXrayShape(spec.shape) || !CfValidators.applicable(spec, onIp)) return emptyMap()
        return CfShape.linkParams(spec, onIp.network.lowercase())
    }

    /**
     * A strategy proven for [stage] that the caller may use NOW: the saved winner when it succeeded
     * within [freshMs] (no I/O), else a bounded diagnosis. Null when optimization is off or nothing
     * works -- the caller then runs exactly as it would without the engine.
     */
    suspend fun ensure(stage: CfStage, config: VpnConfig? = null, accountOverride: CloudAccount? = null,
        freshMs: Long = 20 * 60_000L, budget: StrategyEngine.StageBudget? = null): Strategy? {
        if (!enabled()) return null
        saved(stage, config)?.let { s -> if (store.provenWithin(profile(), stage, s.id, freshMs)) return s.also { readyEch(it) } }
        return diagnose(stage, config, accountOverride, budget).winner
    }

    /** An ECH strategy dials with Cloudflare's key itself ([CfEchKey]): fetched now unless it is cached. */
    private suspend fun readyEch(s: Strategy?) {
        if (s?.data?.shape == TunnelShape.ECH) withContext(Dispatchers.IO) { runCatching { CfEchKey.fetch(app) } }
    }

    /**
     * Find and prove the best strategy for [stage]. Missing prerequisites are reported as such (never
     * as a pass); [budget] defaults to a stage-appropriate one.
     */
    suspend fun diagnose(stage: CfStage, config: VpnConfig? = null, accountOverride: CloudAccount? = null,
        budget: StrategyEngine.StageBudget? = null,
        /** A ceiling on the stage's own deadline (the first-run setup keeps every stage short). */
        maxMs: Long? = null,
        /** A look at the line the caller already took ([probeLine]): it orders a DATA search and is not taken again. */
        probe: LineProbe? = null,
        /** Strategy ids to prove first, before the probe's own order (what another stage just proved here). */
        prefer: List<String> = emptyList(),
        progress: (CfStage, Strategy, ValidationResult?) -> Unit = { _, _, _ -> }
    ): StrategyEngine.StageOutcome = stageLocks.getValue(stage).withLock {
        if (stage.layer == Layer.DATA) dataLane.withLock { run(stage, config, accountOverride, budget, maxMs, probe, prefer, progress) }
        else run(stage, config, accountOverride, budget, maxMs, probe, prefer, progress)
    }

    private suspend fun run(stage: CfStage, config: VpnConfig?, accountOverride: CloudAccount?,
        budget: StrategyEngine.StageBudget?, maxMs: Long?, probe: LineProbe?, prefer: List<String>,
        progress: (CfStage, Strategy, ValidationResult?) -> Unit,
    ): StrategyEngine.StageOutcome = withContext(Dispatchers.IO) {
        CfNetEnv.refresh(app)
        cachedProfile = null
        val p = profile()
        val cloud = CloudManager(app)
        cloud.awaitLoaded()
        CfIdentityCleanup.retry(app)
        val accounts = accountOverride?.let { listOf(it.copy()) } ?: cloud.accountsFlow.value.map { it.copy() }
        retryCleanup(accounts)
        val configs = candidateConfigs(config)
        val base = config ?: VpnConfig.parseUri(ScannerManager.globalBaseConfig.value)?.takeIf(CfUnfilter::isCloudflareFronted) ?: configs.firstOrNull()
        // The config a DATA stage runs on decides whether the XHTTP tunings are candidates at all.
        val dataTarget = if (stage == CfStage.SCAN) base else configs.firstOrNull()
        val ctx = EngineContext(hasAccount = accounts.isNotEmpty(), hasWorker = com.mlmvpn.core.warp.WarpIdRelay.isReady(app),
            hasV6 = p.hasV6, hasOwnConfig = configs.isNotEmpty(), warpSupported = false,
            hasProxy = com.mlmvpn.scanner.update.UpdateNet.tunnelProxy(app) != null,
            xhttp = dataTarget?.network?.lowercase() in ShapeSpec.XHTTP_NETWORKS)
        val missing = when (stage) {
            CfStage.ACCOUNT, CfStage.DEPLOY -> "no_account".takeIf { accounts.isEmpty() }
            CfStage.CONFIG -> when {
                accounts.isEmpty() -> "no_account"
                accounts.none { !it.workerUrl.isNullOrBlank() || !it.edgWorkerUrl.isNullOrBlank() } -> "no_panel"
                else -> null
            }
            CfStage.SCAN -> "no_config".takeIf { base == null }
            CfStage.CONNECT -> "no_config".takeIf { configs.isEmpty() }
            CfStage.IDENTITY -> null
        }
        if (missing != null) {
            return@withContext StrategyEngine.StageOutcome(stage, null, null, emptyList(), "prerequisite:$missing")
                .also { remember(it, p) }
        }
        // Which firewall this line is (MCI-type: ECH; Irancell-type: F&F) and which ways got through it:
        // the caller's look, else -- while nothing is proven here -- a few seconds' probe, so the search
        // starts with what passes instead of walking the other family's handshakes first. Only for
        // DATA stages, and only an order: each strategy is still proven before it wins.
        val look = if (stage.layer != Layer.DATA) null else probe ?: if (firewall() == null) dataTarget?.let { classify(it, p) } else null
        val family = if (stage.layer != Layer.DATA) null else look?.family ?: firewall()
        // The ECH strategies dial with Cloudflare's key itself, fetched on the real line.
        if (stage.layer == Layer.DATA) runCatching { CfEchKey.fetch(app) }
        val env = AndroidNetEnv(app)
        val native = NativeProbe(app)
        val validators = CfValidators(app, env, native, accounts, configs, base, seedV6 = look?.v6.orEmpty(),
            onPendingCleanup = { account, script -> pending(account, script) },
            onCleanupComplete = { account, script -> clearPending(account, script) },
            onProvenAddress = { st, id, original, ip ->
                app.getSharedPreferences("cfnet_addresses", Context.MODE_PRIVATE).edit()
                    .putString(addressKey(p, st, id, original), ip).commit()
            })
        val effective = budget ?: when (stage) {
            CfStage.SCAN, CfStage.CONNECT -> StrategyEngine.StageBudget(parallel = 1, deadlineMs = 180_000)
            // WARP registration is rate limited per source address: one at a time.
            CfStage.IDENTITY -> StrategyEngine.StageBudget(parallel = 1, deadlineMs = 150_000)
            // Each deploy attempt creates (and deletes) a worker: two at a time is plenty.
            CfStage.DEPLOY -> StrategyEngine.StageBudget(parallel = 2, deadlineMs = 150_000)
            CfStage.ACCOUNT, CfStage.CONFIG -> StrategyEngine.StageBudget(parallel = 3, deadlineMs = 120_000)
        }.let { b -> if (maxMs != null && maxMs < b.deadlineMs) b.copy(deadlineMs = maxMs) else b }
        var count = 0
        _live.value = Live(stage, null, 0)
        try {
            StrategyEngine(registry, store, { s, st, _ -> validators.validate(s, st) }, onProgress = { st, s, r ->
                if (r != null) count++
                _live.value = Live(st, s.id, count)
                progress(st, s, r)
            }).best(stage, p, ctx, effective, family, prefer = prefer + look?.order.orEmpty()).also { remember(it, p) }
        } finally {
            _live.value = Live()
            native.release()
            withContext(kotlinx.coroutines.NonCancellable) { retryCleanup(accounts) }
        }
    }

    /** The configs a DATA diagnosis can use: the one given, the connected one, then the saved ones. */
    private fun candidateConfigs(config: VpnConfig?): List<VpnConfig> {
        val nodes = NodeManager(app).nodesFlow.value
        val connected = com.mlmvpn.scanner.MyVpnService.connectedNodeIdFlow.value?.let { id -> nodes.firstOrNull { it.id == id } }
        val groups = GroupManager(app).cloudGroupsFlow.value.flatMap { it.nodes }
        return (listOfNotNull(config) + (listOfNotNull(connected) + nodes + groups).mapNotNull { VpnConfig.parseUri(it.uri) })
            .filter(CfUnfilter::isCloudflareFronted)
    }

    private fun remember(out: StrategyEngine.StageOutcome, p: NetworkProfile) {
        // Atomic: two stages can finish at the same moment (CONTROL stages run side by side).
        _outcomes.update { it + (out.stage to out) }
        // One safe line per diagnosis: ids, classes and numbers only.
        Log.i(TAG, "${out.stage.key}: " + when {
            out.ok -> "winner=${out.winner?.id} ms=${out.result?.latencyMs} fast=${out.fromFastPath} tried=${out.tried.size}"
            out.skippedReason != null -> "skipped=${out.skippedReason}"
            else -> "none failure=${out.dominantFailure} tried=${out.tried.size}"
        } + " net=${p.carrier} v6=${p.hasV6} dns=${p.dnsSane}")
    }

    private fun addressKey(p: NetworkProfile, stage: CfStage, id: String, config: VpnConfig): String =
        "${p.key}|${p.signature()}|${stage.key}|$id|${NetworkProfile.hashKey(config.address + "|" + config.sni + "|" + config.wsHost + "|" + config.xhttpHost + "|" + config.uuid)}"

    /** Retry only test workers recorded by this app, with the original account's credential. */
    private suspend fun retryCleanup(accounts: List<CloudAccount>) = kotlinx.coroutines.withTimeoutOrNull(30_000) {
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) { retryCleanupBlocking(accounts) }
    }
    private fun retryCleanupBlocking(accounts: List<CloudAccount>) {
        val prefs = app.getSharedPreferences("cfnet_cleanup", Context.MODE_PRIVATE)
        val entries = prefs.getStringSet("pending", emptySet()).orEmpty().toList().take(8)
        if (entries.isEmpty()) return
        val wire = CfControlClient(app)
        try {
            for (entry in entries) {
                if (Thread.currentThread().isInterrupted) break
                val parts = entry.split('|', limit = 2)
                if (parts.size != 2 || !parts[1].matches(Regex("mlmvpn-doctor-[a-f0-9]{8}"))) { clearPending(parts[0], parts.getOrElse(1) { "" }); continue }
                val acc = accounts.firstOrNull { it.accountId == parts[0] } ?: continue
                val request = okhttp3.Request.Builder().url("https://api.cloudflare.com/client/v4/accounts/${acc.accountId}/workers/scripts/${parts[1]}")
                    .headers(CloudAuth.headers(acc)).delete().build()
                val route = saved(CfStage.DEPLOY)?.route ?: RouteSpec(AddressSource.WORKER_ROUTE, WireShape.SPLIT)
                runCatching { wire.response(request, route).use { r ->
                    if (r.code == 404 || CfValidation.api(r.code, r.body?.string().orEmpty())) clearPending(acc.accountId, parts[1])
                } }
            }
        } finally { wire.close() }
    }

    private fun pending(account: String, script: String) {
        // IDs of our temporary workers only, never tokens; retained until DELETE is acknowledged.
        val prefs = app.getSharedPreferences("cfnet_cleanup", Context.MODE_PRIVATE)
        synchronized(prefs) {
            val entries = prefs.getStringSet("pending", emptySet()).orEmpty().toMutableSet()
            entries += "$account|$script"
            prefs.edit().putStringSet("pending", entries).commit()
        }
    }

    private fun clearPending(account: String, script: String) {
        val prefs = app.getSharedPreferences("cfnet_cleanup", Context.MODE_PRIVATE)
        synchronized(prefs) {
            val entries = prefs.getStringSet("pending", emptySet()).orEmpty().toMutableSet()
            entries.remove("$account|$script")
            prefs.edit().putStringSet("pending", entries).commit()
        }
    }

    /**
     * A runtime outcome of a strategy (a live tunnel's data check, a real registration). Successes
     * reinforce it; failures that are the route's fault lower it so the next run heals; Cloudflare's
     * own verdicts (auth, rate limit) are not held against the route.
     */
    fun report(stage: CfStage, strategy: Strategy?, p: NetworkProfile, r: ValidationResult) {
        if (strategy == null) return
        if (r.ok || r.failure?.countsAgainstStrategy == true) store.record(p, stage, strategy.id, r.ok, r.latencyMs, r.failure)
        Log.i(TAG, "${stage.key} runtime: ${strategy.id} ${if (r.ok) "ok ms=${r.latencyMs}" else "failed ${r.failure}"}")
    }

    /** The baseline strategy: the config exactly as written. What a run with no proven winner uses. */
    fun asIs(): Strategy = registry.byId("dat_asis_v4")!!

    /** Registration and subsequent authenticated calls use the exact diagnosed IDENTITY carrier. */
    fun identityExchange(method: String, path: String, headers: Map<String, String>, body: ByteArray): com.mlmvpn.scanner.engines.cfdoctor.WayResult? {
        val selected = saved(CfStage.IDENTITY) ?: return null
        val route = selected.route ?: return null
        val p = profile()
        try {
            val result = if (route.carrier == Carrier.WARP_FRONT) {
                val r = com.mlmvpn.core.warp.WarpApiFront.exchange(method, path, headers.toList(), body)
                com.mlmvpn.scanner.engines.cfdoctor.WayResult("identity", "ok", http = r.status, body = r.body)
            } else {
                val base = if (route.carrier == Carrier.WORKER_RELAY) com.mlmvpn.core.warp.WarpIdRelay.base(app)?.toHttpUrl()
                    ?: throw IOException("cfnet_relay_missing") else "https://api.cloudflareclient.com".toHttpUrl()
                val wire = CfControlClient(app)
                try { wire.exchange(route, WayClient.Req(base.host, base.encodedPath.trimEnd('/') + path, method, headers, body, deadlineMs = 25_000)) }
                finally { wire.close() }
            }
            if (!result.answered) {
                report(CfStage.IDENTITY, selected, p, ValidationResult.fail(FailureClassifier.ofWirePhase(result.phase, result.ms >= 24_750)))
                throw CfWireException(result.phase)
            }
            if (result.http !in 200..299) {
                report(CfStage.IDENTITY, selected, p, ValidationResult.fail(FailureClassifier.authOrApi(result.http, FailureClassifier.cfErrorsOf(result.body)) ?: FailureClass.API_FAILURE))
            }
            return result
        } catch (e: CfWireException) {
            throw e
        } catch (e: IOException) {
            if (Thread.currentThread().isInterrupted) throw e
            report(CfStage.IDENTITY, selected, p, ValidationResult.fail(FailureClass.TCP_RESET))
            throw e
        }
    }

    /** Apply only a strategy actually proven on this network and this stage. */
    fun configure(config: VpnConfig, stage: CfStage?): VpnConfig {
        if (stage == null || !CfUnfilter.isCloudflareFronted(config) || CfUnfilter.isLocalFront(config)) return config
        // A literal address IS the config: a clean IP the scan proved, on the port it answered on.
        // Neither is ever swapped for a diagnosis's sample address or a strategy's port.
        if (CfShape.literalFamily(config.address) != null) return config.copy()
        val path = runtimePath(stage, config) ?: return config
        val chosen = path.strategy
        val spec = chosen.data ?: return config
        // A scan proves a handshake on sampled IPs. Carry that handshake forward, without
        // replacing the combined address with the diagnosis's single sample address.
        if (path.source == CfStage.SCAN) return config.copy()
        val ip = app.getSharedPreferences("cfnet_addresses", Context.MODE_PRIVATE)
            .getString(addressKey(profile(), stage, chosen.id, config), null)
        val result = if (ip != null) CfEdgeHeal.onto(config.copy(), ip) else config.copy()
        if (spec.port > 0) result.port = spec.port
        return result
    }

    fun apply(stream: org.json.JSONObject, config: VpnConfig, stage: CfStage?) {
        appliedStrategy(stage, config)?.data?.let { CfShape.apply(stream, it, stream.optString("network")) }
    }

    /** The port a proven SCAN strategy dials, to be probed in addition to the user's own; 0 = none. */
    fun scanPort(base: VpnConfig? = null, family: Family? = null): Int = saved(CfStage.SCAN, base, family)?.data?.port ?: 0

    /**
     * Whether the cheap pre-filter of a scan (a plain-TLS request to the config's own name) would
     * misjudge addresses: when the proven SCAN shape changes the ClientHello, a plain handshake can be
     * cut by the very filter the shape gets through, so the pre-filter must stay at TCP.
     */
    fun scanNeedsShapedPrefilter(base: VpnConfig?, family: Family? = null): Boolean {
        if (base != null && (base.ech.isNotBlank() || base.finalMask.isNotBlank() || base.cipherSuites.isNotBlank())) return true
        val shape = saved(CfStage.SCAN, base, family)?.data?.shape ?: return false
        return shape != TunnelShape.AS_IS
    }

    /** One application request, saved route first, then bounded route fallback on transport errors. */
    fun controlInterceptor(): Interceptor = Interceptor { chain ->
        if (!enabled()) return@Interceptor chain.proceed(chain.request())
        val request = chain.request()
        val host = request.url.host
        val ownPanel = CloudManager(app).accountsFlow.value.any { acc ->
            listOf(acc.workerUrl, acc.edgWorkerUrl).any { url -> url?.let { runCatching { it.toHttpUrl().host == host }.getOrDefault(false) } == true }
        }
        if (request.url.scheme != "https" || (host != "api.cloudflare.com" && !host.endsWith(".workers.dev") && !host.endsWith(".pages.dev") && !ownPanel))
            return@Interceptor chain.proceed(request)
        val stage = request.tag(CfStage::class.java) ?: when {
            request.url.host != "api.cloudflare.com" -> CfStage.CONFIG
            request.method !in setOf("GET", "HEAD") -> CfStage.DEPLOY
            else -> CfStage.ACCOUNT
        }
        val p = profile()
        val candidates = registry.forStage(stage, EngineContext(hasV6 = p.hasV6, hasProxy = com.mlmvpn.scanner.update.UpdateNet.tunnelProxy(app) != null))
        val routes = (listOfNotNull(saved(stage)) + store.rank(p, stage, candidates)).distinctBy { it.id }.take(3)
        val wire = CfControlClient(app)
        try {
            var last: IOException? = null
            for (s in routes) {
                if (chain.call().isCanceled() || Thread.currentThread().isInterrupted) throw IOException("cfnet_cancelled")
                val r = try {
                    wire.response(request, s.route!!) { chain.call().isCanceled() }
                } catch (e: CfWireException) {
                    // No HTTP answer over this route: its fault. Try the next one.
                    last = e
                    if (!chain.call().isCanceled()) report(stage, s, p, ValidationResult.fail(FailureClassifier.ofWirePhase(e.phase)))
                    continue
                }
                // Cloudflare answered. The response always goes back to the app untouched; what it
                // proves is recorded only where it proves something about the stage.
                if (stage == CfStage.ACCOUNT && request.url.encodedPath in setOf("/client/v4/user", "/client/v4/user/tokens/verify")) {
                    val body = r.peekBody(65_536).string()
                    report(stage, s, p, if (CfValidation.account(r.code, body, request.url.encodedPath.endsWith("/tokens/verify"))) ValidationResult.pass(0, "auth=verified")
                        else ValidationResult.fail(FailureClassifier.authOrApi(r.code, FailureClassifier.cfErrorsOf(body)) ?: FailureClass.API_FAILURE))
                }
                if (stage == CfStage.CONFIG && host != "api.cloudflare.com" && request.method == "GET" && r.isSuccessful && request.url.pathSegments.contains("sub")) {
                    val body = r.peekBody(4_194_304).string()
                    val decoded = runCatching { String(android.util.Base64.decode(body.trim(), android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(body)
                    val parsed = decoded.lineSequence().mapNotNull { VpnConfig.parseUri(it.trim()) }.any { it.address.isNotBlank() }
                    report(stage, s, p, if (parsed) ValidationResult.pass(0, "subscription=parsed") else ValidationResult.fail(FailureClass.CONFIG_PARSE_FAILURE))
                }
                if (r.code == 401 || r.code == 403) {
                    // A bare 401/403 (no Cloudflare error JSON) is this route being refused: a WAF page
                    // for the exit address, a captive portal. The next route may well get through.
                    val body = r.peekBody(65_536).string()
                    if (FailureClassifier.cfErrorsOf(body).isEmpty() && !body.trimStart().startsWith("{") && s != routes.last()) {
                        report(stage, s, p, ValidationResult.fail(FailureClass.HTTP_BLOCKED))
                        r.close(); continue
                    }
                }
                return@Interceptor r
            }
            throw last ?: IOException("cfnet_no_route")
        } finally { wire.close() }
    }

    companion object {
        private const val TAG = "CfNet"
        /** How long the firewall probe's answer stands for a network before it is looked at again. */
        private const val HINT_MS = 3 * 24 * 3600_000L
        private const val CLASSIFY_MS = 7_000L
        @Volatile private var instance: CfNetManager? = null
        @Volatile private var context: Context? = null
        fun bind(app: Context) { context = app.applicationContext }
        fun current(): CfNetManager? = context?.let(::get)
        fun get(app: Context): CfNetManager = instance ?: synchronized(this) {
            instance ?: CfNetManager(app.applicationContext).also { instance = it; context = app.applicationContext }
        }
    }
}
