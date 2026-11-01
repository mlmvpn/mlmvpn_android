package com.mlmvpn.scanner.engines.game.booster.decide

import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import com.mlmvpn.scanner.engines.game.booster.probe.ProbeStats
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** How a path's numbers were obtained. Paths are only ever compared by the same method. */
enum class ProbeMethod {
    /** UDP echo from an AWS GameLift beacon in the game's region -- the real thing. */
    ECHO,
    /** TCP handshakes to region anchors -- used where UDP does not get through. */
    TCP,
    /** Full authenticated TLS setup through a tunnel; never compared with echo or SYN timing. */
    TLS_HANDSHAKE,
}

/** One route, measured. [sliceP50] holds the p50 of each interleaved slice, null if it got nothing. */
data class PathMeasurement(
    val route: RouteKind,
    val method: ProbeMethod,
    val stats: PathStats,
    val sliceP50: List<Int?> = emptyList(),
    val targetLabel: String = "",
) {
    val reachable: Boolean get() = stats.answered
    val score: Int get() = ProbeStats.score(stats)
}

/** What the result card is allowed to say. Each flag clears a bar that a coin flip would not. */
data class Claims(
    val pingDeltaMs: Int?,
    val lowerPing: Boolean,
    val jitterDeltaMs: Double?,
    val steadier: Boolean,
    val lossDelta: Double?,
    val lessLoss: Boolean,
) {
    val any: Boolean get() = lowerPing || steadier || lessLoss

    companion object {
        val NONE = Claims(null, false, null, false, null, false)
    }
}

sealed class Verdict {
    /** Stay on the direct line: nothing measured beat it by a margin worth another layer. */
    data class StayDirect(val direct: PathMeasurement, val bestOther: PathMeasurement?) : Verdict()

    /** A tunnel measurably beats the direct line. */
    data class Switch(val to: PathMeasurement, val direct: PathMeasurement, val claims: Claims) : Verdict()

    /**
     * The direct line reached nothing, so the fastest route that works is the answer. No margin
     * is required against a control that does not exist -- for a filtered or sanctioned game in
     * Iran this is the normal case, not a failure.
     */
    data class MustTunnel(val to: PathMeasurement) : Verdict()

    /** Neither the direct line nor any tunnel answered. Something bigger is wrong. */
    object NothingWorks : Verdict()
}

/**
 * The same rules the desktop booster's tournament uses (`game/tournament.js`), so both apps give
 * the same answer for the same line:
 *
 *  - a tunnel must beat direct by at least [SCORE_MARGIN] points of score -- a small win is not
 *    worth an extra layer that can itself fail mid-match;
 *  - it may not buy "stability" with a higher typical ping without saying so ([P50_SLACK_MS]);
 *  - it must win most of the interleaved slices ([SLICE_WIN_SHARE]), so one lucky slice on a
 *    congested line cannot decide a whole session.
 */
object RouteDecider {
    const val SCORE_MARGIN = 8
    const val P50_SLACK_MS = 5
    const val SLICE_WIN_SHARE = 0.75

    fun decide(direct: PathMeasurement, others: List<PathMeasurement>): Verdict {
        val comparable = others.filter { it.method == direct.method || !direct.reachable }
        val best = comparable.filter { it.reachable }.maxByOrNull { it.score }

        if (!direct.reachable) {
            return if (best != null) Verdict.MustTunnel(best) else Verdict.NothingWorks
        }
        if (best == null) return Verdict.StayDirect(direct, null)

        val dp50 = direct.stats.p50 ?: Int.MAX_VALUE
        val bp50 = best.stats.p50 ?: Int.MAX_VALUE
        val beatsByScore = best.score - direct.score >= SCORE_MARGIN
        val noWorsePing = bp50 <= dp50 + P50_SLACK_MS
        val consistent = sliceWins(best, direct).let { (wins, total) ->
            total == 0 || wins >= ceil(SLICE_WIN_SHARE * total).toInt()
        }
        return if (beatsByScore && noWorsePing && consistent) {
            Verdict.Switch(best, direct, claims(direct.stats, best.stats))
        } else {
            Verdict.StayDirect(direct, best)
        }
    }

    /** (slices the candidate won, slices compared). A slice nobody answered in is not compared. */
    fun sliceWins(candidate: PathMeasurement, control: PathMeasurement): Pair<Int, Int> {
        val n = min(candidate.sliceP50.size, control.sliceP50.size)
        var wins = 0
        var total = 0
        for (i in 0 until n) {
            val c = candidate.sliceP50[i]
            val d = control.sliceP50[i]
            if (c == null && d == null) continue
            total++
            if (c != null && (d == null || c < d)) wins++
        }
        return wins to total
    }

    /**
     * What "better" may be said, comparing [before] (the direct line) with [after] (the chosen
     * route). Each bar is set so that ordinary run-to-run noise cannot clear it.
     */
    fun claims(before: PathStats, after: PathStats): Claims {
        val bp = before.p50
        val ap = after.p50
        val pingDelta = if (bp != null && ap != null) bp - ap else null
        val lowerPing = bp != null && pingDelta != null && pingDelta >= max(5.0, bp * 0.05)

        val bj = before.jitter
        val aj = after.jitter
        val jitterDelta = if (bj != null && aj != null) bj - aj else null
        val steadier = bj != null && jitterDelta != null &&
            jitterDelta >= max(2.0, bj * 0.25) &&
            (after.spread ?: Int.MAX_VALUE) < (before.spread ?: Int.MAX_VALUE)

        val lossMeaningful = before.lossIsMeaningful && after.lossIsMeaningful
        val lossDelta = if (lossMeaningful) before.loss - after.loss else null
        val lessLoss = lossDelta != null && lossDelta >= 1.0

        return Claims(pingDelta, lowerPing, jitterDelta, steadier, lossDelta, lessLoss)
    }
}
