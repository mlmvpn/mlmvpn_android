package com.mlmvpn.scanner.engines.game.booster.session

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.util.Log
import com.mlmvpn.core.aether.AetherEngine
import com.mlmvpn.core.aether.AetherScan
import com.mlmvpn.core.aether.AetherStage
import com.mlmvpn.core.aether.AetherTunEngine
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.engines.game.GameBoosterManager
import com.mlmvpn.scanner.engines.game.booster.brain.Brain
import com.mlmvpn.scanner.engines.game.booster.brain.BrainStore
import com.mlmvpn.scanner.engines.game.booster.brain.SessionRecord
import com.mlmvpn.scanner.engines.game.booster.crowd.CrowdClient
import com.mlmvpn.scanner.engines.game.booster.decide.Claims
import com.mlmvpn.scanner.engines.game.booster.decide.PathMeasurement
import com.mlmvpn.scanner.engines.game.booster.decide.ProbeMethod
import com.mlmvpn.scanner.engines.game.booster.decide.RegionAdvisor
import com.mlmvpn.scanner.engines.game.booster.decide.RouteDecider
import com.mlmvpn.scanner.engines.game.booster.decide.RoutePolicy
import com.mlmvpn.scanner.engines.game.booster.decide.RoutePolicy.isWarp
import com.mlmvpn.scanner.engines.game.booster.doctor.GameDoctor
import com.mlmvpn.scanner.engines.game.booster.doctor.Obstacle
import com.mlmvpn.scanner.engines.game.booster.doctor.SanctionProbe
import com.mlmvpn.scanner.engines.game.booster.memory.NetworkKey
import com.mlmvpn.scanner.engines.game.booster.memory.RouteMemory
import com.mlmvpn.scanner.engines.game.booster.memory.RouteMemoryStore
import com.mlmvpn.scanner.engines.game.booster.model.GameProfile
import com.mlmvpn.scanner.engines.game.booster.model.ProbeDepth
import com.mlmvpn.scanner.engines.game.booster.model.RegionCatalog
import com.mlmvpn.scanner.engines.game.booster.model.RouteChoice
import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import com.mlmvpn.scanner.engines.game.booster.paths.SanctionDns
import com.mlmvpn.scanner.engines.game.booster.probe.BEACON_FLOWS_DIRECT
import com.mlmvpn.scanner.engines.game.booster.probe.BEACON_FLOWS_SOCKS
import com.mlmvpn.scanner.engines.game.booster.probe.BEACON_PPS_PER_FLOW
import com.mlmvpn.scanner.engines.game.booster.probe.EchoProtocol
import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import com.mlmvpn.scanner.engines.game.booster.probe.SliceAccumulator
import com.mlmvpn.scanner.engines.game.booster.probe.SocksUdpSession
import com.mlmvpn.scanner.engines.game.booster.probe.TrainResult
import com.mlmvpn.scanner.engines.game.booster.probe.UdpVia
import com.mlmvpn.scanner.engines.game.booster.probe.resolveV4
import com.mlmvpn.scanner.engines.game.booster.probe.tcpTrain
import com.mlmvpn.scanner.engines.game.booster.probe.udpTrainFlows
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.mlmvpn.scanner.engines.game.booster.probe.ProbeBudget
import com.mlmvpn.scanner.engines.game.booster.probe.Anchors
import com.mlmvpn.scanner.utils.GameSessionOwner
import java.net.InetSocketAddress

/**
 * The one-button boost, start to finish:
 *
 *  1. **Check** the phone, and the game's own hosts part by part (the doctor): is sign-in, the
 *     game's services, the store, the downloads open, filtered, or refused to Iran?
 *  2. **Regions**: one short measurement of each of the game's regions, to recommend one inside
 *     the game and to pick the one the rest of the boost measures.
 *  3. **Direct**: the player's own line to that region, in slices.
 *  4. **WARP** beside it, paired slice for slice.
 *  5. **Decide** ([RouteDecider] for the numbers, [RoutePolicy] for everything else) and
 *     **apply**: nothing, a DNS-only session (clean answers and/or an anti-sanction DNS for the
 *     refused parts -- the match stays on the kernel path), or WARP.
 *
 * Every verdict is backed by numbers on the card. When nothing beats the direct line the booster
 * says so and applies only what cannot hurt. The whole run has a hard budget: a silent anchor or a
 * slow resolver shortens it, never stretches it.
 */
class BoostOrchestrator(
    private val ctx: Context,
    private val tuner: DeviceTuner,
    private val memory: RouteMemoryStore,
) {

    data class Plan(
        val profile: GameProfile,
        val regionKey: String,
        val gamePackage: String?,
        val depth: ProbeDepth = ProbeDepth.QUICK,
        /** «امتحان با WARP» from the card: WARP for this run, whatever the measurement says. */
        val forceWarp: Boolean = false,
        /** Started from «چه مشکلی داری؟»: what the player says is wrong. */
        val symptom: com.mlmvpn.scanner.engines.game.booster.model.Symptom? = null,
    ) {
        /** Not installed: measure everything, apply nothing. */
        val measureOnly: Boolean get() = gamePackage == null
    }

    /** What the live monitor needs to keep measuring the route the boost settled on. */
    data class Session(
        val route: RouteKind,
        val method: ProbeMethod,
        val echoTarget: InetSocketAddress?,
        val tcpTarget: InetSocketAddress?,
        val network: Network?,
        val baseline: PathStats?,
        val measureOnly: Boolean = false,
        /** The decision as the learning side records it; null for a measure-only run. */
        val record: SessionRecord? = null,
        val owner: String? = null,
    )

    private val socks = InetSocketAddress("127.0.0.1", AetherEngine.AETHER_SOCKS_PORT)
    private var aetherStartedHere = false
    private val owner = java.util.UUID.randomUUID().toString()
    private val aetherOwner = Any()
    @Volatile private var engineRoute: RouteKind? = null

    suspend fun run(plan: Plan): Session? {
        GameSessionOwner.register(owner)
        val total = if (plan.depth == ProbeDepth.THOROUGH || plan.profile.prefs.blocked ||
            plan.profile.prefs.route.isTunnelPin || plan.profile.prefs.route == RouteChoice.WARP ||
            plan.forceWarp) THOROUGH_BUDGET_MS else QUICK_BUDGET_MS
        return withTimeoutOrNull(total) { runWithin(plan, ProbeBudget(total)) }
            ?: throw IllegalStateException("boost_timeout")
    }

    private suspend fun runWithin(plan: Plan, budget: ProbeBudget): Session? {
        val profile = plan.profile
        val thorough = plan.depth == ProbeDepth.THOROUGH
        val sliceMs = if (thorough) 4000L else 2000L
        GameBoostController.update {
            BoostUiState(
                phase = BoostPhase.MEASURING,
                gameId = profile.id,
                stages = StageId.entries.map { Stage(it) },
                startedAt = System.currentTimeMillis(),
            )
        }
        tuner.holdForMeasurement()
        // A sign-in or update problem is checked from scratch, not from six-hour-old verdicts.
        if (plan.symptom?.recheckAccess == true) {
            GameDoctor.forget()
            SanctionDns.forget()
        }

        // ── 1 + 2. the phone, the game's hosts, the game's regions -- all at once ─────────────
        GameBoostController.stage(StageId.CHECK, StageStatus.RUNNING)
        GameBoostController.stage(StageId.REGION, StageStatus.RUNNING)
        val pre = Preflight.snapshot(ctx)
        val net = NetworkKey.current(ctx)
        val network = GameNetwork.pick(ctx)?.network
        val subKey = memory.memory.subKey(profile.id, plan.regionKey)
        val choice = if (plan.forceWarp) RouteChoice.WARP else profile.prefs.route
        val remembered = if (!thorough && choice == RouteChoice.AUTO) memory.memory.recent(net.key, subKey) else null
        // What other players on this operator learned (from disk; refreshed in the background).
        val crowd = CrowdClient.advice(ctx, net.key)
        CrowdClient.refreshSoon(ctx, net.key)
        val brain = BrainStore(ctx)

        // The crowd can spare a quick boost the WARP measurement where WARP almost never wins for
        // this game on this operator, or where the owner switched it off. Never a pinned choice.
        val crowdSkipsWarp = choice == RouteChoice.AUTO && !thorough && remembered == null &&
            (crowd.warpRarelyWins(profile.id) || !crowd.enabled("warp"))
        // «بازی‌ام فیلتر یا تحریم است» (or «وصل نمی‌شه» / «آپدیت نمی‌شه» from «مشکل داری؟», or a
        // tunnel pinned by name): the direct line is no candidate. Every tunnel that leaves the
        // filter is raced to the game's own region instead (TunnelRace) and the fastest is taken.
        val mustTunnel = profile.prefs.blocked || choice.isTunnelPin || choice == RouteChoice.WARP ||
            plan.symptom == com.mlmvpn.scanner.engines.game.booster.model.Symptom.CANT_CONNECT ||
            plan.symptom == com.mlmvpn.scanner.engines.game.booster.model.Symptom.UPDATE
        val applyReserve = if (mustTunnel) TUNNEL_APPLY_RESERVE_MS else APPLY_RESERVE_MS
        val warpWanted = if (mustTunnel) false else when (choice) {
            RouteChoice.DIRECT -> false
            RouteChoice.WARP -> true
            RouteChoice.WIREGUARD, RouteChoice.AMNEZIA, RouteChoice.FENIX -> false
            RouteChoice.AUTO -> !crowdSkipsWarp && (remembered?.route?.isWarp()
                ?: (thorough || !memory.memory.shouldSkipWarp(net.key, profile.id)))
        }
        val warpNoteIfSkipped = when {
            choice == RouteChoice.DIRECT -> WarpNote.SKIPPED_CHOICE
            remembered != null -> WarpNote.NOT_TRIED
            crowdSkipsWarp -> WarpNote.SKIPPED_CROWD
            else -> WarpNote.SKIPPED_MEMORY
        }
        // Started now so its gateway scan overlaps everything else -- over TCP (MASQUE h2) on a line
        // known to drop foreign UDP, where the QUIC variant could never connect.
        val warpOverTcp = NetworkTraits.udpSilent(ctx, net.key)
        var warpOptions = AetherTunEngine.optionsOf(warpConfig(if (warpOverTcp) "h2" else "h3"))
        if (warpWanted) startAetherIfNeeded(warpOptions)

        val cachedDoctor = GameDoctor.cached(net.key, profile.id)
        val (doctor, sweep, load) = coroutineScope {
            val d = async {
                cachedDoctor ?: withTimeoutOrNull(DOCTOR_MS) { GameDoctor.examine(profile.accessHosts(), network) }
                    ?.also { GameDoctor.remember(net.key, profile.id, it) }
            }
            val s = async { RegionSweep.run(profile.regions, network, sliceMs, budget.cap(SWEEP_MS)) }
            val l = async { Preflight.backgroundLoad() }
            Triple(d.await() ?: GameDoctor.Report.EMPTY, s.await(), l.await())
        }
        GameBoostController.stage(StageId.CHECK, StageStatus.DONE)

        // Which region the rest of the boost is about: the one the player is on if it answered,
        // otherwise the best one that did. The advice is what the card recommends in the game.
        // Where nothing answered from here (Irancell drops the test points), the players on this
        // operator say which region gave them the lowest ping.
        val advice = RegionAdvisor.advise(plan.regionKey, sweep.map { it.sample() })
            ?: crowd.bestRegion(profile.id)?.takeIf { (k, _) -> profile.regions.any { it.key == k } }?.let { (k, p50) ->
                RegionAdvisor.Advice(plan.regionKey, k, currentAnswered = false, gainMs = null,
                    method = ProbeMethod.TCP, bestP50 = p50, fromCrowd = true)
            }
        val onRegion = sweep.firstOrNull { it.gameRegion.key == plan.regionKey }
        val target = onRegion?.takeIf { it.answered }
            ?: advice?.best?.let { b -> sweep.firstOrNull { it.gameRegion.key == b } }
            ?: onRegion
        val anchor = target?.anchor ?: RegionCatalog.byKey(profile.region(plan.regionKey).anchorRegion)
        GameBoostController.stage(StageId.REGION, if (sweep.any { it.answered }) StageStatus.DONE else StageStatus.FAILED,
            target?.let { t -> (t.echo?.takeIf { it.ok } ?: t.tcp)?.stats })

        // The anti-sanction DNS is tried in the background while the line is measured: it needs
        // only the doctor's answer, and it would otherwise add seconds to every sanctioned boost.
        val geoHosts = doctor.geoBlocked()
        val sdnsPrefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Services the owner switched off, or that almost never open this game on this operator,
        // are not tried; the one this phone or the crowd found best is kept when it is as good.
        val sdnsProviders = SanctionDns.PROVIDERS.filter { it.id !in crowd.killedSdns() && it.id !in crowd.uselessSdns(profile.id) }
        val sdnsFavourite = brain.brain.bestSdns(net.key) ?: crowd.bestSdns(profile.id) ?: sdnsPrefs.getString(sdnsKey(net.key), null)
        var sdnsFresh = false

        return coroutineScope {
            val sdnsJob = if (geoHosts.isNotEmpty() && profile.prefs.sanctionDns && crowd.enabled("sdns") && sdnsProviders.isNotEmpty()) {
                async {
                    SanctionDns.cached(net.key, profile.id) ?: withTimeoutOrNull(SDNS_MS) {
                        SanctionDns.pick(
                            hosts = geoHosts.map { it.host },
                            systemIps = geoHosts.associate { it.host to it.systemIps },
                            network = network,
                            currentId = sdnsFavourite,
                            providers = sdnsProviders,
                        )
                    }?.also {
                        sdnsFresh = true
                        SanctionDns.remember(net.key, profile.id, it)
                    }
                }
            } else null

            // On Wi-Fi with mobile data up behind it (a standby many phones keep), the same region
            // is measured over mobile data at the same time: sometimes the better line for the
            // game is the one in the player's pocket.
            val cellJob = if (pre.onWifi && target != null) async { measureCellular(target, sliceMs) } else null

            // ── 3. the direct line, in slices ────────────────────────────────────────────────
            GameBoostController.stage(StageId.DIRECT, StageStatus.RUNNING)
            val echoTarget = target?.echoTarget
            val tcpTarget = target?.tcpTarget
            val tunnelTarget = Anchors.tunnelTarget(anchor)
            val directEcho = SliceAccumulator()
            val directTcp = SliceAccumulator()
            // The sweep's slice of this region is the first direct slice.
            directEcho.add(target?.echo)
            directTcp.add(target?.tcp)
            val moreSlices = when {
                remembered != null -> 0
                thorough -> 5
                warpWanted -> 1 // the WARP stage adds paired direct slices of its own
                else -> 3
            }
            // A beacon that sent back nothing at all in the sweep is not asked again: on a line
            // that drops foreign UDP it would only add silent packets to every slice.
            val sweptEcho = target?.echo
            val echoForSlices = if (sweptEcho != null && !sweptEcho.stats.answered) null else echoTarget
            for (i in 0 until moreSlices) {
                if (!budget.allows(sliceMs + applyReserve)) break
                val (e, t) = slice(echoForSlices, tcpTarget, directFlows(network), network, sliceMs)
                directEcho.add(e); directTcp.add(t)
                GameBoostController.stage(StageId.DIRECT, StageStatus.RUNNING, pick(directEcho, directTcp).stats())
            }
            val echoWorks = echoTarget != null && directEcho.stats().let { it.n > 0 && it.n * 5 >= it.sent }
            val method = if (echoWorks) ProbeMethod.ECHO else ProbeMethod.TCP
            val directAcc = if (method == ProbeMethod.ECHO) directEcho else directTcp
            val udpSilent = echoTarget != null && directEcho.sent > 0 && !directEcho.stats().answered
            if (echoTarget != null && directEcho.sent > 0) NetworkTraits.setUdpSilent(ctx, net.key, udpSilent)
            GameBoostController.stage(StageId.DIRECT,
                if (directAcc.stats().answered) StageStatus.DONE else StageStatus.FAILED, directAcc.stats())

            // ── 4. WARP beside it, paired slice for slice ─────────────────────────────────────
            var warpNote = if (warpWanted) WarpNote.NOT_READY else warpNoteIfSkipped
            var warpM: PathMeasurement? = null
            val pairedDirect = SliceAccumulator()
            if (warpWanted && (echoTarget != null || tcpTarget != null)) {
                GameBoostController.stage(StageId.WARP, StageStatus.RUNNING)
                // A game that cannot sign in directly may still sign in through WARP: give it the
                // time it needs. Otherwise WARP gets what the budget has left.
                val readyBudget = if (doctor.coreNeedsTunnel || choice == RouteChoice.WARP) WARP_READY_BLOCKED_MS
                else minOf(WARP_READY_MS, (budget.left() - applyReserve - 2 * sliceMs).coerceAtLeast(0))
                val ready = readyBudget > 0 && awaitAether(budget.cap(readyBudget), warpOptions)
                if (ready) {
                    val warpAcc = SliceAccumulator()
                    val sessions = if (method == ProbeMethod.ECHO) SocksUdpSession.openMany(socks, BEACON_FLOWS_SOCKS) else emptyList()
                    try {
                        val slices = if (thorough) 5 else 3
                        for (i in 0 until slices) {
                            if (i > 0 && !budget.allows(sliceMs + applyReserve)) break
                            // The two paths are measured at the same time, so both see the same
                            // minute of the same line: a fair comparison, and half the wait.
                            val (w, d) = coroutineScope {
                                val w = async {
                                    if (method == ProbeMethod.ECHO) {
                                        if (sessions.isEmpty()) null
                                        else udpTrainFlows(echoTarget!!, EchoProtocol, sessions.map { UdpVia.Socks(it) },
                                            ppsPerFlow = BEACON_PPS_PER_FLOW, durationMs = sliceMs)
                                    } else tcpTarget?.let { tcpTrain(it, count = 4, gapMs = 250, socks = socks) }
                                }
                                val d = async {
                                    if (method == ProbeMethod.ECHO) {
                                        udpTrainFlows(echoTarget!!, EchoProtocol, directFlows(network),
                                            ppsPerFlow = BEACON_PPS_PER_FLOW, durationMs = sliceMs)
                                    } else tcpTarget?.let { tcpTrain(it, count = 4, gapMs = 250, network = network) }
                                }
                                w.await() to d.await()
                            }
                            warpAcc.add(w ?: TrainResult("", "", PathStats.empty(0), emptyList(), 0, null, "no session"))
                            pairedDirect.add(d)
                            directAcc.add(d)
                            GameBoostController.stage(StageId.WARP, StageStatus.RUNNING, warpAcc.stats())
                        }
                    } finally {
                        sessions.forEach { it.close() }
                    }
                    warpM = PathMeasurement(warpRouteKind(warpOptions), method, warpAcc.stats(), warpAcc.sliceP50)
                    warpNote = WarpNote.MEASURED
                    GameBoostController.stage(StageId.WARP,
                        if (warpM.reachable) StageStatus.DONE else StageStatus.FAILED, warpM.stats)
                } else {
                    warpNote = if (AetherEngine.get(ctx).state.value.stage in FAILED_STAGES) WarpNote.FAILED else WarpNote.NOT_READY
                    GameBoostController.stage(StageId.WARP, StageStatus.SKIPPED)
                }
            } else {
                GameBoostController.stage(StageId.WARP, StageStatus.SKIPPED)
            }

            // ── 4b. a game the line cannot carry: the tunnels, raced to the game's region ─────
            var racePick: TunnelRace.Result? = null
            if (mustTunnel && tunnelTarget != null) {
                GameBoostController.stage(StageId.WARP, StageStatus.RUNNING)
                val order = when (choice) {
                    RouteChoice.WIREGUARD -> listOf(RouteKind.WARP_WG)
                    RouteChoice.AMNEZIA -> listOf(RouteKind.AMNEZIA)
                    RouteChoice.FENIX -> listOf(RouteKind.FENIX)
                    RouteChoice.WARP -> listOf(if (warpOverTcp) RouteKind.WARP_MASQUE_H2 else RouteKind.WARP_MASQUE_H3)
                    // What won for this game on this network goes first; all are measured anyway.
                    else -> TunnelRace.CANDIDATES.sortedByDescending { k ->
                        if (remembered?.route == k) 2.0 else brain.brain.planRate(net.key, profile.id, Brain.planCode(k)) ?: 0.5
                    }
                }
                val results = TunnelRace(ctx, aetherOwner).run(tunnelTarget, order, warpOverTcp, RACE_EACH_MS,
                    totalMs = (budget.left() - applyReserve).coerceAtLeast(0)) { r ->
                    GameBoostController.stage(StageId.WARP, StageStatus.RUNNING, r.stats.takeIf { r.reachable })
                }
                racePick = results.filter { it.reachable }.maxByOrNull { it.score }
                racePick?.aether?.let { warpOptions = it }
                if (racePick != null) {
                    warpM = PathMeasurement(racePick.route, ProbeMethod.TLS_HANDSHAKE, racePick.stats)
                    warpNote = WarpNote.MEASURED
                }
                GameBoostController.stage(StageId.WARP, if (racePick != null) StageStatus.DONE else StageStatus.FAILED, racePick?.stats)
                Log.i(TAG, "tunnel race for ${profile.id}: " + results.joinToString { "${it.route}=${it.stats.p50}" } + " -> ${racePick?.route}")
            }

            // ── 5. decide ─────────────────────────────────────────────────────────────────────
            GameBoostController.stage(StageId.DECIDE, StageStatus.RUNNING)
            val directM = PathMeasurement(RouteKind.DIRECT, method, directAcc.stats(),
                if (warpM != null) pairedDirect.sliceP50 else directAcc.sliceP50)
            val verdict = RouteDecider.decide(directM, listOfNotNull(warpM))
            val sdns = sdnsJob?.await()
            val sanctionPick = sdns?.pick
            // A 403 that an anti-sanction proxy got too is not about the country: those hosts are
            // not reported as sanctioned, and nothing is done for them.
            val notGeo = sdns?.notGeo.orEmpty()
            val found = if (notGeo.isEmpty()) doctor else GameDoctor.Report(
                doctor.hosts.map { h -> if (h.host in notGeo && h.obstacle == Obstacle.GEO_BLOCKED) h.copy(obstacle = Obstacle.UNKNOWN) else h },
                doctor.at,
            )
            val pins = found.pins()
            val fragments = found.fragmentable()
            val planRates = listOf("D", "DD", "W3", "W2", "WG").associateWith { brain.brain.planRate(net.key, profile.id, it) }
            val policy = RoutePolicy.choose(RoutePolicy.Input(
                verdict = verdict,
                choice = choice,
                direct = directM,
                warp = warpM,
                directUdpSilent = udpSilent,
                access = RoutePolicy.Access(
                    coreNeedsTunnel = found.coreNeedsTunnel,
                    coreGeoBlocked = found.coreGeoBlocked,
                    havePins = pins.isNotEmpty(),
                    sanctionFixed = sanctionPick != null,
                    fragmentFixes = fragments.isNotEmpty(),
                    anyProblem = !found.allOk,
                ),
                remembered = remembered?.route,
                warpUp = AetherEngine.get(ctx).state.value.connected,
                planRates = planRates,
            ))
            GameBoostController.stage(StageId.DECIDE, StageStatus.DONE)

            // ── 6. apply ──────────────────────────────────────────────────────────────────────
            GameBoostController.update { it.copy(phase = BoostPhase.APPLYING) }
            GameBoostController.stage(StageId.APPLY, StageStatus.RUNNING)
            // A game the line cannot carry goes to the fastest tunnel that reached it, never direct.
            var route = racePick?.route ?: policy.route
            if (mustTunnel && racePick == null) throw IllegalStateException("no_reachable_tunnel")
            var fellBack = false
            val tuning = if (plan.measureOnly) {
                GameBoostController.stage(StageId.APPLY, StageStatus.SKIPPED)
                DeviceTuner.Applied(false, false)
            } else {
                val applied = withTimeoutOrNull(budget.left()) {
                    apply(route, plan, pins, sanctionPick, fragments, tcpTarget, warpOptions, network, racePick?.amneziaServer)
                } == true
                if (!applied) {
                    stopOwnedEngine()
                    if (mustTunnel) throw IllegalStateException("tunnel_apply_failed")
                    fellBack = route != RouteKind.DIRECT
                    // What the DNS-only session was built from did not hold up from inside it:
                    // ask the line again next time instead of reusing it for six hours.
                    if (route == RouteKind.DIRECT_DNS) {
                        GameDoctor.forget()
                        SanctionDns.forget()
                    }
                    route = RouteKind.DIRECT
                    apply(RouteKind.DIRECT, plan, emptyMap(), null, emptyMap(), tcpTarget, warpOptions, network)
                }
                GameBoostController.stage(StageId.APPLY, StageStatus.DONE)
                tuner.apply(pre.onWifi)
            }
            // A measure-only run applied nothing, so a WARP it measured has no route to serve.
            if (!route.isWarp() || plan.measureOnly) stopAetherIfOurs()
            tuner.releaseWake()
            if (route == RouteKind.DIRECT_DNS && sanctionPick != null) {
                sdnsPrefs.edit().putString(sdnsKey(net.key), sanctionPick.provider.id).apply()
            }

            val tunnelled = route.isWarp() || route == RouteKind.AMNEZIA || route == RouteKind.FENIX
            val chosen = if (tunnelled) warpM?.stats else directM.stats
            val chosenMethod = if (tunnelled) warpM?.method ?: method else method
            val claims = if (tunnelled && warpM != null && warpM.method == directM.method && directM.reachable)
                RouteDecider.claims(directM.stats, warpM.stats) else Claims.NONE
            val sanctionApplied = route == RouteKind.DIRECT_DNS && sanctionPick != null
            val classLines = found.perClass
                .filter { (_, o) -> o != Obstacle.OK && o != Obstacle.UNKNOWN }
                .map { (cls, o) ->
                    val hostsOfClass = found.hosts.filter { it.cls == cls }
                    val by = when {
                        o == Obstacle.DNS_POISONED && route == RouteKind.DIRECT_DNS -> FixedBy.CLEAN_DNS
                        o == Obstacle.GEO_BLOCKED && sanctionApplied && hostsOfClass.any { it.host in sanctionPick!!.fixedHosts } -> FixedBy.SANCTION_DNS
                        o == Obstacle.SNI_BLOCKED && route == RouteKind.DIRECT_DNS && hostsOfClass.any { it.host in fragments } -> FixedBy.FRAGMENT
                        (o == Obstacle.SNI_BLOCKED || o == Obstacle.IP_BLOCKED) && route.isWarp() -> FixedBy.WARP
                        else -> null
                    }
                    ClassLine(cls, o, by != null, by)
                }
            val rows = sweep.map { p ->
                val s = p.sample()
                val p50 = if (advice?.method == ProbeMethod.ECHO) p.echo?.stats?.p50 else (p.tcp?.stats?.p50 ?: p.echo?.stats?.p50)
                RegionRow(p.gameRegion.key, p.gameRegion.labelFa, p.gameRegion.labelEn, if (s.answered) p50 else null, s.answered, p.anchor.status)
            }
            val cellularBetterBy = cellJob?.await()?.let { (cellEcho, cellTcp) ->
                val wifi = directM.stats
                val cell = (if (method == ProbeMethod.ECHO) cellEcho else cellTcp) ?: return@let null
                val wp50 = wifi.p50 ?: return@let null
                val cp50 = cell.p50 ?: return@let null
                if (cell.n * 5 < cell.sent) return@let null
                val gain = wp50 - cp50
                val lossOk = !(wifi.lossIsMeaningful && cell.lossIsMeaningful) || cell.loss <= wifi.loss + 1.0
                gain.takeIf { it >= maxOf(15, wp50 * 15 / 100) && lossOk }
            }
            val planCode = Brain.planCode(route)
            val crowdPlan = crowd.planStats(profile.id, planCode)
            // What the learning side needs when the session ends: the decision, as it was made.
            val record = if (plan.measureOnly) null else SessionRecord(
                gameId = profile.id,
                shareable = profile.builtIn,
                regionKey = target?.gameRegion?.key ?: plan.regionKey,
                plan = planCode,
                kind = found.kind?.let { it.ordinal + 1 } ?: 0,
                warpMeasured = warpM != null,
                warpWon = warpM != null && route.isWarp(),
                chosenP50 = chosen?.p50,
                // Only a round run on this line now says anything new about the services.
                sdnsTrials = if (sdnsFresh) sdns?.trials.orEmpty().associate { t ->
                    t.provider.id to when {
                        t.resolverIp == null -> -1
                        t.fixed.isNotEmpty() -> 1
                        else -> 0
                    }
                } else emptyMap(),
                networkKey = net.key,
                startedAt = System.currentTimeMillis(),
                sampled = profile.builtIn && CrowdClient.sampleThisSession(ctx, crowd),
            )
            if (sdnsFresh) {
                sdns?.trials.orEmpty().filter { it.resolverIp != null }.forEach { t ->
                    brain.brain.recordSdns(net.key, t.provider.id, t.fixed.isNotEmpty())
                }
                brain.save()
            }
            GameBoostController.update {
                it.copy(
                    phase = BoostPhase.ACTIVE,
                    outcome = BoostOutcome(
                        gameId = profile.id,
                        gameName = profile.name,
                        gamePackage = plan.gamePackage,
                        regionKey = plan.regionKey,
                        anchorLabelFa = anchor.labelFa,
                        anchorLabelEn = anchor.labelEn,
                        route = route,
                        verdict = if (racePick != null) VerdictKind.MUST_TUNNEL else policy.verdictKind,
                        method = chosenMethod,
                        direct = directM.stats,
                        warp = warpM?.stats,
                        chosen = chosen,
                        claims = claims,
                        warpNote = warpNote,
                        access = policy.access,
                        wifiPowerSaveOff = tuning.wifiPowerSaveOff,
                        tips = Preflight.ranked(pre.tips + listOfNotNull(Preflight.loadTip(load, pre.onCellular))),
                        fromMemory = remembered != null,
                        applyFellBack = fellBack,
                        echoAvailable = echoTarget != null,
                        kind = found.kind,
                        classLines = classLines,
                        regionAdvice = advice,
                        regions = rows,
                        sanctionProviderFa = sanctionPick?.provider?.nameFa?.takeIf { sanctionApplied || plan.measureOnly },
                        sanctionProviderEn = sanctionPick?.provider?.nameEn?.takeIf { sanctionApplied || plan.measureOnly },
                        sanctionUnfixed = policy.sanctionUnfixed && profile.prefs.sanctionDns,
                        udpSilent = udpSilent,
                        offerWarp = policy.offerWarp && !plan.measureOnly,
                        warpYieldedToSignIn = policy.warpYieldedToSignIn,
                        measureOnly = plan.measureOnly,
                        measuredRegionKey = target?.gameRegion?.key?.takeIf { k -> k != plan.regionKey },
                        noteFa = profile.noteFa,
                        noteEn = profile.noteEn,
                        crowdOkPct = crowdPlan?.let { (it.ok * 100 / (it.ok + it.bad).coerceAtLeast(1)) },
                        crowdVotes = crowdPlan?.let { it.ok + it.bad },
                        escalated = policy.escalated,
                        warpOverTcp = warpOverTcp && warpM != null,
                        cellularBetterByMs = cellularBetterBy,
                        symptom = plan.symptom,
                    ),
                )
            }

            // Only a decision the line itself made is worth remembering: not one the user pinned,
            // not a fallback, not a measure-only run, and not one where nothing answered.
            val measuredDecision = choice == RouteChoice.AUTO && !fellBack && !plan.measureOnly &&
                (directM.reachable || warpM?.reachable == true)
            if (measuredDecision) {
                memory.memory.record(net.key, subKey, RouteMemory.Entry(
                    route = route,
                    decidedAt = System.currentTimeMillis(),
                    directP50 = directM.stats.p50,
                    chosenP50 = chosen?.p50,
                    warpMeasured = warpM != null,
                    warpLost = warpM != null && !route.isWarp(),
                ))
                memory.save()
            }
            Log.i(TAG, "boost ${profile.id}/${plan.regionKey}→${target?.gameRegion?.key}: route=$route method=$method " +
                "direct.p50=${directM.stats.p50} warp.p50=${warpM?.stats?.p50} verdict=${verdict.javaClass.simpleName} " +
                "policy=${policy.verdictKind} access=${policy.access} kind=${found.kind} sdns=${sanctionPick?.provider?.id} " +
                "advice=${advice?.best}(${advice?.gainMs}) udpSilent=$udpSilent warp=$warpNote left=${budget.left()}ms")

            Session(route, chosenMethod, echoTarget,
                if (chosenMethod == ProbeMethod.TLS_HANDSHAKE) tunnelTarget else tcpTarget,
                network, chosen, plan.measureOnly, record, owner)
        }
    }

    /** Clean up anything this run started, e.g. when the user stops it half-way. */
    fun abort() {
        GameSessionOwner.release(owner)
        stopOwnedEngine()
        if (MyVpnService.isRunning && MyVpnService.activeGameSessionId == owner) {
            com.mlmvpn.scanner.utils.SafeStart.service(ctx, Intent(ctx, MyVpnService::class.java).apply {
                action = "STOP"
                putExtra(GameSessionOwner.EXPECTED, owner)
            })
        }
        stopAetherIfOurs()
        tuner.release()
    }

    private fun stopOwnedEngine() {
        when (engineRoute) {
            RouteKind.AMNEZIA -> com.mlmvpn.scanner.engines.amnezia.AmzEngine.disconnectAsync(ctx, owner)
            RouteKind.FENIX -> com.mlmvpn.scanner.engines.fenix.FenixEngine.disconnectAsync(ctx, owner)
            else -> Unit
        }
        engineRoute = null
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private fun directFlows(network: Network?): List<UdpVia> = List(BEACON_FLOWS_DIRECT) { UdpVia.Direct(network) }

    private suspend fun slice(
        echo: InetSocketAddress?, tcp: InetSocketAddress?, flows: List<UdpVia>,
        network: Network?, durationMs: Long,
    ): Pair<TrainResult?, TrainResult?> = coroutineScope {
        val e = async { echo?.let { udpTrainFlows(it, EchoProtocol, flows, ppsPerFlow = BEACON_PPS_PER_FLOW, durationMs = durationMs) } }
        val t = async {
            tcp?.let { tcpTrain(it, count = (durationMs / 500).toInt().coerceAtLeast(2), gapMs = 250, network = network) }
        }
        e.await() to t.await()
    }

    private fun pick(echo: SliceAccumulator, tcp: SliceAccumulator): SliceAccumulator = if (echo.stats().answered) echo else tcp

    /** One slice of [target] over standby mobile data, or null when there is none. */
    private suspend fun measureCellular(target: RegionSweep.Probe, sliceMs: Long): Pair<PathStats?, PathStats?>? {
        val cell = GameNetwork.standbyCellular(ctx) ?: return null
        return coroutineScope {
            val e = async {
                target.echoTarget?.let {
                    udpTrainFlows(it, EchoProtocol, List(6) { UdpVia.Direct(cell) }, ppsPerFlow = BEACON_PPS_PER_FLOW,
                        durationMs = sliceMs).stats
                }
            }
            val t = async { target.tcpTarget?.let { tcpTrain(it, count = 4, gapMs = 250, network = cell).stats } }
            e.await() to t.await()
        }
    }

    private fun warpConfig(transport: String): String = AetherTunEngine.buildGameConfig(
        protocol = GameBoosterManager.gameAetherProtocol(ctx),
        scan = AetherScan.TURBO,
        transport = transport,
    )

    private fun warpRouteKind(o: com.mlmvpn.core.aether.AetherOptions): RouteKind = when {
        o.protocol == com.mlmvpn.core.aether.AetherProtocol.WG -> RouteKind.WARP_WG
        o.transport == "h2" -> RouteKind.WARP_MASQUE_H2
        else -> RouteKind.WARP_MASQUE_H3
    }

    private fun startAetherIfNeeded(options: com.mlmvpn.core.aether.AetherOptions) {
        val eng = AetherEngine.get(ctx)
        val running = eng.runningOptions
        if (running != null) {
            // Running and connected, or serving a tunnel: measure through it, do not own it. An
            // engine of this booster's that never connected with other options (h3 on a line
            // that drops UDP) is replaced by the one this line needs.
            val servingTunnel = MyVpnService.isRunning && MyVpnService.connectedNodeId == NODE_WARP
            if (running == options || servingTunnel || !aetherStartedHere) return
            try { eng.stopOwned(aetherOwner) } catch (_: Exception) {}
            if (eng.runningOptions != null) return
        }
        aetherStartedHere = eng.startOwned(options, aetherOwner) { msg -> Log.w(TAG, "aether start: $msg") }
    }

    private fun stopAetherIfOurs() {
        aetherStartedHere = false
        try { AetherEngine.get(ctx).stopOwned(aetherOwner) } catch (_: Exception) {}
    }

    private suspend fun awaitAether(budgetMs: Long, options: com.mlmvpn.core.aether.AetherOptions): Boolean {
        val eng = AetherEngine.get(ctx)
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < budgetMs) {
            val s = eng.state.value
            if (eng.runningOptions != options) return false
            if (s.connected && eng.isSocksAlive()) return true
            if (s.stage in FAILED_STAGES) return false
            delay(300)
        }
        return false
    }

    /** The physical line's own resolvers, for every name the DNS-only session does not steer. */
    private fun ispResolvers(network: Network?): List<String> {
        if (network == null) return emptyList()
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(network)?.dnsServers.orEmpty()
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { it != MyVpnService.DEFAULT_GAME_DNS_IP }
                .take(2)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Put the game on [route]. False when the route could not be brought up. */
    private suspend fun apply(
        route: RouteKind, plan: Plan, pins: Map<String, List<String>>, sanction: SanctionDns.Pick?,
        fragments: Map<String, String>,
        tcpTarget: InetSocketAddress?, warpOptions: com.mlmvpn.core.aether.AetherOptions, network: Network?,
        amneziaServer: String? = null,
    ): Boolean {
        val pkg = plan.gamePackage
        // A per-game route needs the VPN permission; without it the direct line is the answer.
        val needsVpn = route != RouteKind.DIRECT
        if (needsVpn && (pkg == null || VpnService.prepare(ctx) != null)) return false

        return when (route) {
            RouteKind.DIRECT -> {
                // The game must not go through a tunnel the user left connected, or every number
                // above describes a line the game is not on. The screen asked before starting.
                // Both of the app's VPN services: the five-transport tunnel is a separate one.
                if (MyVpnService.isRunning) com.mlmvpn.scanner.ui.stopVpnSafely(ctx)
                com.mlmvpn.scanner.ui.tunnel.TunnelExclusion.releaseForXray(ctx)
                true
            }
            RouteKind.DIRECT_DNS -> {
                // The proven hosts, plus any catalog suffix one of them belongs to: a game signs in
                // through more names than a list knows, and the provider answers only for names on
                // its own list anyway.
                val steered = sanction?.let { s ->
                    val suffixes = plan.profile.steer
                        .filter { suf -> s.fixedHosts.any { h -> h == suf || h.endsWith(".$suf") } }
                        .map { ".$it" }
                    listOf(s.resolverIp to (s.fixedHosts + suffixes))
                }.orEmpty()
                // Hosts filtered on their name that open when fragmented: pinned to the address that
                // was proven, and that address (only) routed into the tunnel to the fragmenting
                // outbound -- the SPLIT shape. Without them the session is DNS only.
                val fragmentIps = fragments.values.distinct().take(MyVpnService.MAX_SPLIT_ROUTES)
                val allPins = pins + fragments.mapValues { (_, ip) -> listOf(ip) }
                val cfg = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateGameDnsOnlyConfig(
                    mtu = 1400, staticHosts = allPins, providerServers = steered, ispResolvers = ispResolvers(network),
                    fragmentIps = fragmentIps)
                startGameVpn(cfg, NODE_DNS, pkg!!,
                    if (fragmentIps.isEmpty()) MyVpnService.GAME_ROUTE_DNS_ONLY else MyVpnService.GAME_ROUTE_SPLIT,
                    splitRoutes = fragmentIps)
                if (!awaitVpnConnected(10_000)) return false
                // Check from the inside: the game's resolver answers with our pins and the proven
                // proxies, the fragmented hosts open through the tunnel, and everything else still
                // falls through to the physical network.
                val canary = canaryDnsOnly(allPins, sanction, fragments, tcpTarget, network)
                if (!canary) {
                    Log.w(TAG, "DNS-only canary failed -- reverting to the direct line")
                    com.mlmvpn.scanner.ui.stopVpnSafely(ctx)
                }
                canary
            }
            RouteKind.WARP_MASQUE_H3, RouteKind.WARP_MASQUE_H2, RouteKind.WARP_WG -> {
                val cfg = AetherTunEngine.buildConfig(warpOptions)
                startGameVpn(cfg, NODE_WARP, pkg!!, MyVpnService.GAME_ROUTE_FULL)
                awaitVpnConnected(30_000)
            }
            RouteKind.ACCESS_HELPER -> false // phase 3
            // The two tunnels with engines of their own: brought up by those engines (their own
            // server choice, canary and reconnect), for the whole device rather than the game
            // alone, and taken down with the boost.
            RouteKind.AMNEZIA -> {
                engineRoute = route
                com.mlmvpn.scanner.engines.amnezia.AmzEngine.connectAsync(ctx, serverId = amneziaServer, owner = owner).join()
                com.mlmvpn.scanner.engines.amnezia.AmzEngine.connect.value is com.mlmvpn.scanner.engines.amnezia.AmzConnect.Connected &&
                    MyVpnService.activeGameSessionId == owner
            }
            RouteKind.FENIX -> {
                engineRoute = route
                com.mlmvpn.scanner.engines.fenix.FenixEngine.connectAsync(ctx, owner = owner).join()
                com.mlmvpn.scanner.engines.fenix.FenixEngine.conn.value is com.mlmvpn.scanner.engines.fenix.FenixEngine.Conn.Connected &&
                    MyVpnService.activeGameSessionId == owner
            }
        }
    }

    private fun startGameVpn(nodeUri: String, nodeId: String, pkg: String, profile: String, splitRoutes: List<String> = emptyList()) {
        val intent = Intent(ctx, MyVpnService::class.java).apply {
            putExtra("NODE_URI", nodeUri)
            putExtra("NODE_ID", nodeId)
            putExtra(GameSessionOwner.EXTRA, owner)
            putExtra("GAME_MODE", true)
            putExtra("GAME_PACKAGE", pkg)
            putExtra("GAME_ROUTE_PROFILE", profile)
            if (splitRoutes.isNotEmpty()) putExtra("GAME_SPLIT_ROUTES", splitRoutes.joinToString(","))
        }
        com.mlmvpn.scanner.utils.SafeStart.service(ctx, intent)
    }

    private suspend fun awaitVpnConnected(timeoutMs: Long): Boolean {
        // Let the service move out of any previous session's CONNECTED first.
        delay(400)
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                when (MyVpnService.connectionPhaseFlow.value) {
                    MyVpnService.Phase.CONNECTED -> if (MyVpnService.activeGameSessionId == owner) return@withTimeoutOrNull true else delay(250)
                    MyVpnService.Phase.FAILED -> return@withTimeoutOrNull false
                    else -> delay(250)
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
    }

    /**
     * From inside the DNS-only session (this app is in it too): every pinned host resolves to its
     * pin; at least one host the anti-sanction DNS was proven on now serves through the answer the
     * session gives; and an anchor is still reachable, i.e. everything else falls through.
     */
    private suspend fun canaryDnsOnly(
        pins: Map<String, List<String>>, sanction: SanctionDns.Pick?, fragments: Map<String, String>,
        tcpTarget: InetSocketAddress?, network: Network?,
    ): Boolean = coroutineScope {
        delay(500)
        val pinsOk = async {
            pins.all { (host, ips) -> resolveV4(host).any { a -> a.hostAddress?.let { it in ips } == true } }
        }
        val sanctionOk = async {
            if (sanction == null) true
            else sanction.fixedHosts.take(2).any { host ->
                val ip = resolveV4(host).firstOrNull()?.hostAddress ?: return@any false
                SanctionProbe.probe(host, ip, network, connectTimeoutMs = 2000, tlsTimeoutMs = 2500).outcome ==
                    SanctionProbe.Outcome.OPEN
            }
        }
        // Through the tunnel on purpose (no network pinned): that is the path the game's own
        // connection to a fragmented host now takes.
        val fragmentOk = async {
            fragments.isEmpty() || fragments.entries.take(2).any { (host, ip) ->
                SanctionProbe.probe(host, ip, null, connectTimeoutMs = 2500, tlsTimeoutMs = 3000).outcome.let {
                    it == SanctionProbe.Outcome.OPEN || it == SanctionProbe.Outcome.GEO_BLOCKED
                }
            }
        }
        val fallThroughOk = async { tcpTarget?.let { tcpTrain(it, count = 2, gapMs = 200, timeoutMs = 3000).ok } ?: true }
        val ok = listOf(pinsOk.await(), sanctionOk.await(), fragmentOk.await(), fallThroughOk.await())
        Log.i(TAG, "DNS-only canary: pins=${ok[0]} sanction=${ok[1]} fragment=${ok[2]} fallthrough=${ok[3]}")
        ok.all { it }
    }

    companion object {
        private const val TAG = "GameBoost"
        const val NODE_DNS = "game_dns"
        const val NODE_WARP = "game_warp"

        /** One tunnel of the race, measured at most this long (WARP's gateway scan is the slow one). */
        const val RACE_EACH_MS = 60_000L

        private const val PREFS = "game_booster_prefs"

        private const val QUICK_BUDGET_MS = 25_000L
        private const val THOROUGH_BUDGET_MS = 70_000L
        /** Kept free at the end of the measuring for applying the route and its canary. */
        private const val APPLY_RESERVE_MS = 4_000L
        private const val TUNNEL_APPLY_RESERVE_MS = 15_000L
        private const val DOCTOR_MS = 9_000L
        private const val SWEEP_MS = 8_000L
        private const val SDNS_MS = 7_000L
        private const val WARP_READY_MS = 12_000L
        private const val WARP_READY_BLOCKED_MS = 45_000L
        private val FAILED_STAGES = setOf(AetherStage.FAILED, AetherStage.CRASHED)

        private fun sdnsKey(networkKey: String) = "sdns_$networkKey"
    }
}
