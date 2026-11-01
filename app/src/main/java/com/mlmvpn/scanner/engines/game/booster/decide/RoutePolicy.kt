package com.mlmvpn.scanner.engines.game.booster.decide

import com.mlmvpn.scanner.engines.game.booster.model.RouteChoice
import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import com.mlmvpn.scanner.engines.game.booster.session.AccessState
import com.mlmvpn.scanner.engines.game.booster.session.VerdictKind

/**
 * From "what was measured" and "what the doctor found" to "where the game goes" -- pure, so every
 * rule below is a unit test.
 *
 * The rules, in the order they apply:
 *  1. The measurement's verdict ([RouteDecider]) picks between the direct line and WARP -- except
 *     that a direct line which merely did not answer the test points is NOT a reason to tunnel.
 *     Test points (GameLift beacons, anchors) are not the game: on a line that whitelists game
 *     servers and drops other foreign UDP -- Irancell -- they are silent while the game works.
 *     Tunnelling on that silence moves a working game into a slower path. So WARP is only forced
 *     when the doctor proves the game's own sign-in cannot be reached directly, or the user asked.
 *  2. What the user pinned wins, where it works at all.
 *  3. Signing in comes first: a sign-in the direct line cannot reach but WARP can, takes WARP; a
 *     sign-in refused on country is fixed by an anti-sanction DNS, and that beats a WARP whose
 *     only merit was a few milliseconds -- WARP leaves from an address the publisher still reads
 *     as Iran, so on WARP the player could not sign in at all.
 */
object RoutePolicy {

    data class Access(
        /** Every checked sign-in / service host is filtered by name or address: no DNS fixes it. */
        val coreNeedsTunnel: Boolean = false,
        /** A sign-in / service host refuses Iran. */
        val coreGeoBlocked: Boolean = false,
        /** Clean answers exist for poisoned hosts. */
        val havePins: Boolean = false,
        /** An anti-sanction DNS was proven to fix at least one refused host. */
        val sanctionFixed: Boolean = false,
        /** Some host filtered on its TLS name opens when the ClientHello is sent in pieces. */
        val fragmentFixes: Boolean = false,
        /** Nothing was checked, or nothing is in the way. */
        val anyProblem: Boolean = false,
    )

    data class Input(
        val verdict: Verdict,
        val choice: RouteChoice,
        val direct: PathMeasurement,
        val warp: PathMeasurement?,
        /** The target region's echo point exists and not one UDP packet came back directly. */
        val directUdpSilent: Boolean,
        val access: Access,
        /** A route remembered for this network, used when WARP was not re-measured this time. */
        val remembered: RouteKind? = null,
        val warpUp: Boolean = false,
        /**
         * How each plan ([com.mlmvpn.scanner.engines.game.booster.brain.Brain.planCode]) has done
         * for this game on this network, from this phone's own sessions and answers; null or absent
         * where there is not enough evidence.
         */
        val planRates: Map<String, Double?> = emptyMap(),
    )

    data class Output(
        val route: RouteKind,
        val verdictKind: VerdictKind,
        val access: AccessState,
        /** Offer «امتحان با WARP» on the card: the line could not be judged, or WARP alone answered. */
        val offerWarp: Boolean,
        /** A refused sign-in that no anti-sanction DNS opened on this line. */
        val sanctionUnfixed: Boolean,
        /** WARP was chosen by measurement but the sign-in fix needed the direct line, so it lost. */
        val warpYieldedToSignIn: Boolean = false,
        /** The route the numbers pointed to kept failing here, so the other one was taken. */
        val escalated: Boolean = false,
    )

    /** A plan that worked for fewer than this share of sessions here is avoided when there is another. */
    const val AVOID_BELOW = 0.35

    fun choose(i: Input): Output {
        val warpReachable = i.warp?.reachable == true
        var route: RouteKind = when (val v = i.verdict) {
            is Verdict.Switch -> v.to.route
            is Verdict.MustTunnel ->
                if (i.choice == RouteChoice.WARP || i.access.coreNeedsTunnel) v.to.route else RouteKind.DIRECT
            is Verdict.StayDirect, Verdict.NothingWorks -> RouteKind.DIRECT
        }
        when (i.choice) {
            RouteChoice.DIRECT -> route = RouteKind.DIRECT
            RouteChoice.WARP -> if (warpReachable) route = i.warp!!.route
            RouteChoice.AUTO -> Unit
            // A tunnel pinned by name is decided by the tunnel race before the policy runs.
            RouteChoice.WIREGUARD, RouteChoice.AMNEZIA, RouteChoice.FENIX -> Unit
        }
        if (i.choice == RouteChoice.AUTO && i.remembered?.isWarp() == true && i.warp == null && i.warpUp) {
            route = i.remembered
        }

        var access = when {
            !i.access.anyProblem -> AccessState.OK
            i.access.coreNeedsTunnel -> AccessState.NEEDS_TUNNEL
            else -> AccessState.OK
        }
        // Sign-in first.
        if (i.access.coreNeedsTunnel && !route.isWarp() && i.choice != RouteChoice.DIRECT) {
            if (warpReachable) route = i.warp!!.route else access = AccessState.BLOCKED
        }
        var yielded = false
        if (route.isWarp() && i.access.coreGeoBlocked && i.access.sanctionFixed &&
            i.choice == RouteChoice.AUTO && !i.access.coreNeedsTunnel
        ) {
            route = RouteKind.DIRECT
            yielded = true
        }
        val dnsFixes = i.access.havePins || i.access.sanctionFixed || i.access.fragmentFixes
        val directCode = if (dnsFixes) "DD" else "D"

        // What this phone learned overrides a close call: a plan that kept failing here is left
        // for the other one -- but never into WARP when signing in needs the direct line, and never
        // out of WARP when only WARP reaches the game.
        var escalated = false
        if (i.choice == RouteChoice.AUTO && !yielded) {
            val code = if (route.isWarp()) com.mlmvpn.scanner.engines.game.booster.brain.Brain.planCode(route) else directCode
            val now = i.planRates[code]
            if (now != null && now < AVOID_BELOW) {
                if (!route.isWarp() && warpReachable && !i.access.coreGeoBlocked) {
                    val other = i.planRates[com.mlmvpn.scanner.engines.game.booster.brain.Brain.planCode(i.warp!!.route)]
                    if (other == null || other > now) { route = i.warp.route; escalated = true }
                } else if (route.isWarp() && i.direct.reachable && !i.access.coreNeedsTunnel) {
                    val other = i.planRates[directCode]
                    if (other == null || other > now) { route = RouteKind.DIRECT; escalated = true }
                }
            }
        }
        if (route == RouteKind.DIRECT && dnsFixes) {
            route = RouteKind.DIRECT_DNS
        }
        access = when {
            route == RouteKind.DIRECT_DNS -> AccessState.FIXED_BY_DNS
            route.isWarp() && access == AccessState.NEEDS_TUNNEL -> AccessState.OK // WARP reaches it
            access == AccessState.NEEDS_TUNNEL -> AccessState.BLOCKED
            else -> access
        }

        val verdictKind = when {
            escalated -> VerdictKind.LEARNED
            route.isWarp() && i.verdict is Verdict.MustTunnel -> VerdictKind.MUST_TUNNEL
            route.isWarp() -> VerdictKind.SWITCH
            access == AccessState.BLOCKED && !i.direct.reachable && !warpReachable -> VerdictKind.NOTHING_WORKS
            !i.direct.reachable -> VerdictKind.UNMEASURED
            else -> VerdictKind.STAY_DIRECT
        }
        val offerWarp = !route.isWarp() && i.choice != RouteChoice.DIRECT &&
            (i.directUdpSilent || !i.direct.reachable || (i.verdict is Verdict.MustTunnel))
        val sanctionUnfixed = i.access.coreGeoBlocked && !i.access.sanctionFixed
        return Output(route, verdictKind, access, offerWarp, sanctionUnfixed, yielded, escalated)
    }

    fun RouteKind.isWarp(): Boolean =
        this == RouteKind.WARP_MASQUE_H3 || this == RouteKind.WARP_MASQUE_H2 || this == RouteKind.WARP_WG
}
