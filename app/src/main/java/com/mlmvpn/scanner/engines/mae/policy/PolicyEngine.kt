package com.mlmvpn.scanner.engines.mae.policy

import com.mlmvpn.scanner.engines.mae.model.Diagnosis
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ForeignEgressStatus
import com.mlmvpn.scanner.engines.mae.model.Health
import com.mlmvpn.scanner.engines.mae.model.ObservedRequirements
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.engines.mae.model.ServicePolicy
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.RouteProvider
import com.mlmvpn.scanner.engines.mae.store.MaeState

/**
 * Glue between what is remembered ([MaeState]) and the scorer: builds each service's candidates
 * for the current network and turns the scorer's answer into a stored [ServicePolicy].
 * Pure; the engine calls it after every discovery and on every network change.
 */
object PolicyEngine {
    /** How long a proof of acceptance outlives an exit that merely failed to answer. */
    const val PROOF_GRACE_MS = 24 * 3600_000L

    /** A policy is re-verified after this long, stretched for policies that keep proving right. */
    fun ttlMs(confidence: Double): Long = (2 * 3600_000L * (1 + 4 * confidence.coerceIn(0.0, 1.0))).toLong()

    fun isStale(p: ServicePolicy?, now: Long): Boolean =
        p == null || now - p.decidedAt > ttlMs(p.confidence)

    /**
     * An exit that is one fixed server abroad: foreign, the same address for a whole session, and not
     * a Cloudflare colo (the Worker, the old US helper, the user's panel configs). What an app that
     * opens only from a real place abroad -- Gemini, Google Flow -- is given (see [decide]).
     */
    fun fixedExitAbroad(p: RouteProvider): Boolean =
        p.kind == RouteKind.FOREIGN && p.caps.stableExit &&
            p.id != com.mlmvpn.scanner.engines.mae.route.WorkerRoute.ID &&
            p.id != com.mlmvpn.scanner.engines.mae.route.UsExitRoute.ID &&
            !p.id.startsWith(com.mlmvpn.scanner.engines.mae.route.UserConfigRoute.CLOUD_PREFIX)

    fun requirements(state: MaeState, service: ServiceDef, net: String, now: Long = System.currentTimeMillis()): ObservedRequirements {
        val key = MaeState.sk(service.id, net)
        val d = state.diagnoses[key] ?: Diagnosis()
        val r = ObservedRequirements.fromDiagnosis(d, service.hints)
        // What the user saw outranks what a probe saw: the probe reads a web page, the user the app.
        return if (state.userEvidence[key]?.geo(now) == true && !r.wantsForeign)
            r.copy(needsForeignGeo = com.mlmvpn.scanner.engines.mae.model.AxisValue(
                com.mlmvpn.scanner.engines.mae.model.Tri.YES, 0.9, listOf("user: the app refused the country")))
        else r
    }

    fun candidates(
        state: MaeState,
        service: ServiceDef,
        net: String,
        providers: List<RouteProvider>,
        health: (String) -> Health,
    ): List<Candidate> = providers.map { p ->
        val m = state.metrics[MaeState.rk(p.id, service.id, net)] ?: com.mlmvpn.scanner.engines.mae.model.RouteMetrics()
        // Families this service was actually served on through this route, on this network.
        val accepted = FamilyPolicy.values().filter { f ->
            state.proofs["${p.id}:$f|${service.id}|$net"]?.serviceAccepted == true
        }.toSet()
        Candidate(
            routeId = p.id,
            kind = p.kind,
            metrics = m,
            health = health(p.id),
            cost = p.cost,
            quotaLimited = p.caps.quotaLimited,
            usable = if (m.lastTestedAt == 0L) null else m.lastOkAt == m.lastTestedAt,
            acceptedFamilies = accepted,
        )
    }

    fun decide(
        state: MaeState,
        service: ServiceDef,
        net: String,
        providers: List<RouteProvider>,
        health: (String) -> Health,
        now: Long,
        plan: RepairPlan? = null,
        /** The network's default path, for a fail-open app with nothing working (see [RouteScorer.decide]). */
        fallbackRoute: String? = null,
    ): Pair<Decision, ServicePolicy?> {
        val key = MaeState.sk(service.id, net)
        val current = state.policies[key]
        var req = requirements(state, service, net, now)
        if (plan?.needsForeign == true) {
            // The user saw the service refuse the country: that is evidence, not a guess.
            req = req.copy(needsForeignGeo = com.mlmvpn.scanner.engines.mae.model.AxisValue(
                com.mlmvpn.scanner.engines.mae.model.Tri.YES, 0.9, listOf("user: country refusal")))
        }
        val byId = providers.associateBy { it.id }
        var cands = candidates(state, service, net, providers, health)
        // Each filter narrows the field; if one would leave nothing, it is skipped rather than
        // leave the app with no route at all.
        fun narrow(keep: (Candidate) -> Boolean) { cands.filter(keep).takeIf { it.isNotEmpty() }?.let { cands = it } }

        // A fail-closed app MAE has not checked on this network yet: the foreign exit it proved on
        // another network, if that is available here, rather than an Iranian address or nothing
        // while the check runs.
        if (service.hints.failMode == com.mlmvpn.scanner.engines.mae.model.FailMode.CLOSED && plan == null &&
            current == null && cands.none { it.usable != null }) {
            val elsewhere = state.policies.values.filter { it.serviceId == service.id && it.netKey != net }
                .sortedByDescending { it.decidedAt }
                .firstOrNull { p -> byId[p.routeId]?.kind == RouteKind.FOREIGN && health(p.routeId) != Health.UNAVAILABLE }
            if (elsewhere != null) return Decision.Use(elsewhere.routeId, elsewhere.family, 0.0, "proven on another network; being checked here") to null
        }

        // Routes the user said this app did not open on, this week: not chosen again while
        // anything else is there -- a routine re-check must not walk back into a reported failure.
        state.userEvidence[key]?.failedRoutes(now)?.takeIf { it.isNotEmpty() }?.let { bad -> narrow { it.routeId !in bad } }

        // An app that opens only from a real place abroad (Gemini, Google Flow) is given nothing but
        // an exit that is one fixed server there -- FENIX, Outline, the user's own configs, FLUX. A
        // Cloudflare exit leaves from the colo nearest the phone, which Google places in Iran or
        // Russia, and an exit that moves between servers (Lantern) both pass a page check and are then
        // refused at the first prompt (error 1060; the user, 2026-10-18). Strict on purpose: "no exit
        // found" is the truth when there is none, a route that only looks open is not.
        if (service.hints.usExit) cands = cands.filter { c -> byId[c.routeId]?.let(::fixedExitAbroad) == true }
        if (plan != null) {
            narrow { it.routeId !in plan.excludedRoutes }
            if (plan.requireUdp) narrow { byId[it.routeId]?.caps?.udp == true }
            if (plan.stableExit) {
                // The Worker's exit changes from one connection to the next (BG/RO/AZ measured):
                // exactly what a login or a captcha holds against you.
                narrow { it.routeId != com.mlmvpn.scanner.engines.mae.route.WorkerRoute.ID }
                cands = cands.map { c ->
                    val single = c.acceptedFamilies.filter { it != FamilyPolicy.BOTH }
                    if (single.isNotEmpty()) c.copy(acceptedFamilies = setOf(if (FamilyPolicy.V4_ONLY in single) FamilyPolicy.V4_ONLY else single.first())) else c
                }
            }
        }
        val decision = RouteScorer.decide(
            req = req,
            failMode = service.hints.failMode,
            heavy = service.hints.heavy || plan?.preferThroughput == true,
            candidates = cands,
            // A repair must be free to leave the current route: no hysteresis toward it.
            current = if (plan != null) null else current?.routeId,
            pinned = current?.takeIf { it.pinned && plan == null }?.routeId,
            fallbackRoute = fallbackRoute,
        )
        val policy = when (decision) {
            is Decision.Use -> ServicePolicy(
                serviceId = service.id, netKey = net, routeId = decision.routeId, family = decision.family,
                confidence = if (current?.routeId == decision.routeId) current.confidence else 0.5,
                // Only the route the user pinned is "pinned": when it is gone the decision is Auto's,
                // and the manage page must not show another route as the user's choice.
                why = decision.why, decidedAt = now, pinned = current?.pinned == true && current.routeId == decision.routeId,
            )
            is Decision.Block -> null
        }
        return decision to policy
    }

    /**
     * Records what the user reported about [serviceId] on [net]: a route it did not open on
     * ([failedRoute], `route:FAMILY`), a country refusal ([geo]), or that it opened on
     * [openedRoute] (which clears that route and -- on a local route -- the country claim).
     */
    fun withEvidence(
        state: MaeState, serviceId: String, net: String, now: Long,
        failedRoute: String? = null, geo: Boolean = false,
        openedRoute: String? = null, openedLocally: Boolean = false,
    ): MaeState {
        val key = MaeState.sk(serviceId, net)
        val old = state.userEvidence[key] ?: com.mlmvpn.scanner.engines.mae.store.UserEvidence()
        var failed = old.failed.filterValues { now - it < com.mlmvpn.scanner.engines.mae.store.UserEvidence.TTL_MS }
        failedRoute?.let { failed = failed + (it to now) }
        openedRoute?.let { r -> failed = failed.filterKeys { it.substringBefore(':') != r.substringBefore(':') } }
        val next = old.copy(
            failed = failed,
            geoAt = when { geo -> now; openedLocally -> 0L; else -> old.geoAt },
        )
        return state.copy(userEvidence = if (next.isEmpty(now)) state.userEvidence - key else state.userEvidence + (key to next))
    }

    /**
     * Folds one discovery into the state and re-decides the service's route. Returns the new state
     * and the decision. [incident] records the repair attempt in the incident log.
     */
    fun mergeDiscovery(
        state: MaeState,
        service: ServiceDef,
        net: String,
        result: DiscoveryResult,
        providers: (MaeState) -> List<RouteProvider>,
        health: (String) -> Health,
        now: Long,
        incident: Boolean = false,
        plan: RepairPlan? = null,
        fallbackRoute: String? = null,
    ): Pair<MaeState, Decision> {
        val key = MaeState.sk(service.id, net)
        var proofs = state.proofs
        val kept = HashSet<String>()
        result.proofs.forEach { p ->
            val k = "${p.routeId}|${service.id}|$net"
            // An exit that did not answer at all says nothing about the service: a proof of
            // acceptance from the last day survives it (Irancell, 2026-09-30: every exit missed
            // its echo once right after an install, and Gemini fell back to an Iranian route).
            val old = proofs[k]
            val transient = !p.serviceAccepted && p.reason == Discovery.NO_ECHO
            if (transient && old != null && old.serviceAccepted && now - old.at < PROOF_GRACE_MS) kept += p.routeId.substringBefore(':')
            else proofs = proofs + (k to p)
        }
        // ...and neither does it count as the route failing, for a route whose proof was kept.
        var metrics = state.metrics
        result.metrics.forEach { (rid, m) -> if (rid !in kept) metrics = metrics + (MaeState.rk(rid, service.id, net) to m) }
        val keptEgress = (state.egress[key] as? ForeignEgressStatus.Proven)?.takeIf { old ->
            val none = result.egress as? ForeignEgressStatus.NoneFound
            none != null && none.rejected.values.all { it == Discovery.NO_ECHO } &&
                proofs.any { (k, p) -> k.startsWith("${old.routeId}:") && k.endsWith("|${service.id}|$net") && p.serviceAccepted }
        }
        var next = state.copy(
            diagnoses = state.diagnoses + (key to result.diagnosis),
            metrics = metrics, proofs = proofs,
            egress = state.egress + (key to (keptEgress ?: result.egress)),
        )
        val (decision, policy) = decide(next, service, net, providers(next), health, now, plan, fallbackRoute)
        next = if (policy != null) next.copy(policies = next.policies + (key to policy)) else next.copy(policies = next.policies - key)
        if (incident) {
            val outcome = when (decision) { is Decision.Use -> "route ${decision.routeId}"; is Decision.Block -> "no route" }
            next = next.copy(incidents = (next.incidents + com.mlmvpn.scanner.engines.mae.store.Incident(service.id, net, now, outcome))
                .takeLast(com.mlmvpn.scanner.engines.mae.store.MaeStateCodec.MAX_INCIDENTS))
        }
        return next to decision
    }

    /**
     * The user's verdict on the current route. "Opened" is a bounded boost (never a lock);
     * "didn't open" is a failure on the route's record, a confidence cut and an unpin -- the
     * engine then re-discovers the service at top priority.
     */
    fun recordFeedback(state: MaeState, serviceId: String, net: String, opened: Boolean, now: Long): MaeState {
        val key = MaeState.sk(serviceId, net)
        val p = state.policies[key] ?: return state
        val mk = MaeState.rk(p.routeId, serviceId, net)
        val m = (state.metrics[mk] ?: com.mlmvpn.scanner.engines.mae.model.RouteMetrics()).record(opened, null, now)
        val policy = if (opened) p.copy(confidence = (p.confidence + 0.1).coerceAtMost(0.95))
        else p.copy(confidence = (p.confidence - 0.3).coerceAtLeast(0.0), pinned = false)
        return state.copy(policies = state.policies + (key to policy), metrics = state.metrics + (mk to m))
    }

    /** UDP of a service leaves only by its own route: when that route is TCP-only and abroad, UDP is blocked. */
    fun blockUdp(decision: Decision, providers: List<RouteProvider>, req: ObservedRequirements): Boolean {
        if (decision !is Decision.Use) return true
        val p = providers.firstOrNull { it.id == decision.routeId } ?: return false
        return p.kind == RouteKind.FOREIGN && !p.caps.udp || (req.wantsForeign && !p.caps.udp)
    }
}
