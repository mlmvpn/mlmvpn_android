package com.mlmvpn.scanner.engines.scout

import kotlin.math.exp
import kotlin.math.ln

/**
 * The ranking, pure so it can be tested and explained (plan 3-10).
 *
 * Every part is 0..1 **relative to the best of the same run and the same kind**, as a ratio (the
 * Arena's lesson: "half as fast scores half"). The parts are combined as a WEIGHTED GEOMETRIC mean,
 * not a sum: one large weakness is not bought back by one large strength. Stability is squared on
 * the way in, because a connection that drops a third of its requests is far worse to use than a
 * third slower: a 50 Mbit/s method holding 70 % scores about 81 against 96 for a 42 Mbit/s one
 * holding 100 % (ScoutScoreTest pins it).
 *
 * A part that was not measured is left out and the remaining weights are scaled back up, so a
 * method is never punished for a round that did not run (a speed sample on mobile data that went
 * to the top three only).
 */
object ScoutScore {

    const val LATENCY = "latency"
    const val SPEED = "speed"
    const val STABILITY = "stability"
    const val CONNECT = "connect"
    const val FAMILY = "family"

    val WEIGHTS: Map<String, Double> = linkedMapOf(LATENCY to 0.30, SPEED to 0.25, STABILITY to 0.30, CONNECT to 0.10, FAMILY to 0.05)

    /** Working on this network in earlier runs too: at most this much on top, never past 100. */
    const val TRUST_MAX = 0.03

    data class Input(
        val id: String,
        val latencyMs: Long? = null,
        val mbps: Double? = null,
        /** Share of the stability attempts that succeeded, 0..1. */
        val stability: Double? = null,
        val connectMs: Long? = null,
        /** 1.0 when it works on every IP family the line has; less when only on one of two. */
        val famFit: Double? = null,
        val historyOk: Int = 0,
        val historyRuns: Int = 0,
    )

    data class Result(val id: String, val total: Double, val parts: Map<String, Double>)

    private fun clamp(v: Double) = v.coerceIn(0.01, 1.0)

    fun score(inputs: List<Input>): List<Result> {
        if (inputs.isEmpty()) return emptyList()
        val bestLat = inputs.mapNotNull { it.latencyMs?.takeIf { v -> v > 0 } }.minOrNull()
        val bestSpeed = inputs.mapNotNull { it.mbps?.takeIf { v -> v > 0 } }.maxOrNull()
        val bestStab = inputs.mapNotNull { it.stability?.takeIf { v -> v > 0 } }.maxOrNull()
        val bestConn = inputs.mapNotNull { it.connectMs?.takeIf { v -> v > 0 } }.minOrNull()
        return inputs.map { i ->
            val parts = LinkedHashMap<String, Double>()
            if (bestLat != null && i.latencyMs != null) parts[LATENCY] = clamp(bestLat.toDouble() / i.latencyMs.coerceAtLeast(1))
            if (bestSpeed != null && i.mbps != null) parts[SPEED] = clamp(i.mbps / bestSpeed)
            if (bestStab != null && i.stability != null) parts[STABILITY] = clamp((i.stability / bestStab).let { it * it })
            if (bestConn != null && i.connectMs != null) parts[CONNECT] = clamp(bestConn.toDouble() / i.connectMs.coerceAtLeast(1))
            if (i.famFit != null) parts[FAMILY] = clamp(i.famFit)
            val wSum = parts.keys.sumOf { WEIGHTS.getValue(it) }
            val gm = if (wSum <= 0) 0.0 else exp(parts.entries.sumOf { (k, v) -> WEIGHTS.getValue(k) * ln(v) } / wSum)
            val trust = if (i.historyRuns > 0) TRUST_MAX * i.historyOk.toDouble() / i.historyRuns else 0.0
            Result(i.id, 100.0 * (gm * (1 + trust)).coerceAtMost(1.0), parts)
        }.sortedWith(compareByDescending<Result> { it.total }.thenBy { r -> inputs.first { it.id == r.id }.latencyMs ?: Long.MAX_VALUE })
    }
}

/** The finished picture of one run, the way the summary screen tells it. */
data class ScoutSummaryData(
    /** «بهترین انتخاب برای اینترنت شما»: a full tunnel with real data, never a weak one. */
    val best: String?,
    val fastest: String?,
    val lowestLatency: String?,
    val mostStable: String?,
    /** Healthy methods other than the ones named above, best first. */
    val otherHealthy: List<String>,
    /** The fastest way to open filtered sites WITHOUT changing the address. */
    val unfilterBest: String?,
    val antisanctionBest: String?,
    val failed: List<String>,
    val setup: List<String>,
    val upcoming: List<String>,
    /** Cancelled, inconclusive, service down, not for this line. */
    val other: List<String>,
    val scores: Map<String, Double>,
)

object ScoutSummary {

    data class Info(val kind: MethodKind, val evidence: Evidence)

    private fun famFit(m: MethodResult, lineV4: Boolean, lineV6: Boolean): Double? {
        if (!(lineV4 && lineV6)) return if (m.variants.any { it.fam != Fam.AUTO }) 1.0 else null
        val v4 = m.famStatus(Fam.V4)?.healthy == true
        val v6 = m.famStatus(Fam.V6)?.healthy == true
        if (m.variants.none { it.fam != Fam.AUTO }) return null
        return when {
            v4 && v6 -> 1.0
            v4 || v6 -> 0.85
            else -> null
        }
    }

    fun input(m: MethodResult, lineV4: Boolean, lineV6: Boolean, history: Pair<Int, Int>?): ScoutScore.Input {
        val n = m.best?.numbers ?: Numbers()
        return ScoutScore.Input(
            id = m.id, latencyMs = n.latencyMs, mbps = n.mbps, stability = n.stabilityRate, connectMs = n.connectMs,
            famFit = famFit(m, lineV4, lineV6), historyOk = history?.first ?: 0, historyRuns = history?.second ?: 0,
        )
    }

    fun build(
        methods: Collection<MethodResult>,
        info: (String) -> Info?,
        lineV4: Boolean,
        lineV6: Boolean,
        history: Map<String, Pair<Int, Int>> = emptyMap(),
    ): ScoutSummaryData {
        fun kindOf(m: MethodResult) = info(m.id)?.kind
        val data = methods.filter { info(it.id)?.evidence == Evidence.DATA }
        val scores = HashMap<String, Double>()
        fun rank(list: List<MethodResult>): List<String> {
            val r = ScoutScore.score(list.map { input(it, lineV4, lineV6, history[it.id]) })
            r.forEach { scores[it.id] = it.total }
            return r.map { it.id }
        }

        val tunnels = data.filter { kindOf(it) == MethodKind.TUNNEL && it.status.healthy }
        val tunnelOrder = rank(tunnels)
        val worksIds = tunnels.filter { it.status == ScoutStatus.WORKS }.map { it.id }.toSet()
        val best = tunnelOrder.firstOrNull { it in worksIds }

        fun <T : Comparable<T>> pick(by: (Numbers) -> T?, max: Boolean): String? {
            val measured = tunnels.mapNotNull { m -> m.best?.numbers?.let(by)?.let { m.id to it } }
            if (measured.size < 2) return null
            return (if (max) measured.maxByOrNull { it.second } else measured.minByOrNull { it.second })?.first
        }
        val fastest = pick({ it.mbps }, max = true)
        val lowest = pick({ it.latencyMs }, max = false)
        val stable = tunnels.mapNotNull { m -> m.best?.numbers?.takeIf { it.stabilityRate != null }?.let { m to it } }
            .takeIf { it.size >= 2 }
            ?.sortedWith(compareByDescending<Pair<MethodResult, Numbers>> { it.second.stabilityRate }.thenBy { it.second.jitterMs ?: Long.MAX_VALUE })
            ?.first()?.first?.id

        val unfilter = data.filter { kindOf(it) == MethodKind.UNFILTER && it.status == ScoutStatus.WORKS }
        val unfilterBest = rank(unfilter).firstOrNull()
        val sanction = data.filter { kindOf(it) == MethodKind.ANTISANCTION && it.status == ScoutStatus.WORKS }
        val antisanctionBest = rank(sanction).firstOrNull()
        // The rest of the healthy ones, including handshake-only methods, scored inside their kind.
        rank(methods.filter { it.status.healthy && it.id !in scores.keys })

        val named = setOfNotNull(best, unfilterBest, antisanctionBest)
        val otherHealthy = methods.filter { it.status.healthy && it.id !in named }
            .sortedWith(compareBy<MethodResult> { ScoutRules.rank(it.status) }.thenByDescending { scores[it.id] ?: 0.0 })
            .map { it.id }

        return ScoutSummaryData(
            best = best, fastest = fastest, lowestLatency = lowest, mostStable = stable,
            otherHealthy = otherHealthy, unfilterBest = unfilterBest, antisanctionBest = antisanctionBest,
            failed = methods.filter { it.status == ScoutStatus.FAILED }.map { it.id },
            setup = methods.filter { it.status == ScoutStatus.NEEDS_SETUP || it.status == ScoutStatus.NOT_INSTALLED || it.status == ScoutStatus.ON_DEMAND }.map { it.id },
            upcoming = methods.filter { it.status == ScoutStatus.UPCOMING }.map { it.id },
            other = methods.filter {
                it.status == ScoutStatus.CANCELLED || it.status == ScoutStatus.INCONCLUSIVE ||
                    it.status == ScoutStatus.SERVICE_DOWN || it.status == ScoutStatus.NOT_APPLICABLE
            }.map { it.id },
            scores = scores,
        )
    }
}
