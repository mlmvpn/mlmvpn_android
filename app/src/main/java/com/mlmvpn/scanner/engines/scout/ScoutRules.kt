package com.mlmvpn.scanner.engines.scout

/**
 * The rules that turn measurements into words. Pure, so each one is unit-tested
 * (app/src/test/.../engines/scout) and can be explained exactly.
 */
object ScoutRules {
    fun speedCandidates(methods: Collection<MethodResult>): List<Pair<String, VariantResult>> = methods.flatMap { method ->
        method.variants.filter { it.status.healthy && it.numbers.mbps == null && !it.numbers.speedAttempted }
            .map { method.id to it }
    }

    fun pendingVariants(variants: List<Variant>, done: Set<String>, quick: Boolean): List<Variant> =
        variants.filter { it.id !in done && (!quick || it.quick) }

    /** Best first: what a method or an IP family "is" when its variants disagree. */
    private val ORDER = listOf(
        ScoutStatus.WORKS, ScoutStatus.WEAK, ScoutStatus.CHECKING, ScoutStatus.WAITING,
        ScoutStatus.CANCELLED, ScoutStatus.INCONCLUSIVE, ScoutStatus.SERVICE_DOWN, ScoutStatus.FAILED,
        ScoutStatus.ON_DEMAND, ScoutStatus.NEEDS_SETUP, ScoutStatus.NOT_INSTALLED, ScoutStatus.NOT_APPLICABLE,
        ScoutStatus.UPCOMING,
    )

    fun rank(s: ScoutStatus): Int = ORDER.indexOf(s)

    /**
     * A method's status from its variants.
     *
     * One working variant is enough for "works" (Tor with obfs4 works even when Meek does not).
     * With nothing healthy yet and variants still to run, it is WAITING -- a first variant that
     * failed is not a verdict on the method. Otherwise the most telling of the rest: a run that
     * was cut short says "cancelled" rather than "failed", and an incomplete test says
     * "inconclusive" rather than guessing; a panel that failed outweighs one that is not set up.
     */
    fun aggregate(variants: List<VariantResult>, pending: Int): ScoutStatus {
        val s = variants.map { it.status }
        if (ScoutStatus.WORKS in s) return ScoutStatus.WORKS
        if (ScoutStatus.WEAK in s) return ScoutStatus.WEAK
        if (pending > 0 || s.isEmpty()) return ScoutStatus.WAITING
        return s.minByOrNull { rank(it) } ?: ScoutStatus.WAITING
    }

    /** Healthy, fully measured exits first; compare throughput before delay. */
    fun representative(variants: List<VariantResult>): VariantResult? {
        if (variants.isEmpty()) return null
        return variants.sortedWith(
            compareBy<VariantResult> { rank(it.status) }
                .thenBy { if (it.full) 0 else 1 }
                .thenByDescending { it.numbers.mbps ?: -1.0 }
                .thenBy { it.numbers.latencyMs ?: Long.MAX_VALUE }
                .thenByDescending { it.at }
        ).first()
    }

    // ---- UDP ---------------------------------------------------------------------------------

    /**
     * One independent UDP probe: one server and port, how many times it was asked, and how many
     * VALID answers came back (a STUN reply with our transaction id, a DNS answer that can only
     * have come from the resolver asked, a QUIC version negotiation echoing our ids). A reply from
     * the operator's own resolver standing in for 8.8.8.8 is not valid and is not counted.
     */
    data class UdpProbe(val target: String, val port: Int, val attempts: Int, val valid: Int)

    enum class UdpWhy { ANSWERED, TCP_DOWN, ALL_SILENT, NOT_ENOUGH }

    data class UdpVerdict(val udp: Tri, val why: UdpWhy, val answeredBy: List<String> = emptyList())

    /**
     * The plan's strict rule (3-8). OPEN on one valid answer from abroad. CLOSED only when TCP to the
     * same servers works (so the line is up), at least three independent probes -- two servers or
     * more, two ports or more -- each asked at least twice, all stayed silent. Anything else: UNKNOWN.
     */
    fun judgeUdp(probes: List<UdpProbe>, tcpWorks: Boolean): UdpVerdict {
        val answered = probes.filter { it.valid > 0 }
        if (answered.isNotEmpty()) return UdpVerdict(Tri.YES, UdpWhy.ANSWERED, answered.map { it.target }.distinct())
        if (!tcpWorks) return UdpVerdict(Tri.UNKNOWN, UdpWhy.TCP_DOWN)
        val solid = probes.filter { it.attempts >= 2 }
        val independent = solid.map { it.target to it.port }.distinct()
        val hosts = solid.map { it.target }.distinct().size
        val ports = solid.map { it.port }.distinct().size
        return if (independent.size >= 3 && hosts >= 2 && ports >= 2) UdpVerdict(Tri.NO, UdpWhy.ALL_SILENT)
        else UdpVerdict(Tri.UNKNOWN, UdpWhy.NOT_ENOUGH)
    }

    // ---- what one measurement means ----------------------------------------------------------

    /** Why a method that carried data is still only "weak". Null: it is healthy. */
    enum class Weak { NO_FILTERED, IRAN_EXIT, UNSTABLE, SLOW_RESPONSE, SLOW_SPEED }

    const val GATE_STABILITY = 0.80
    const val GATE_LATENCY_MS = 3_000L
    const val GATE_MBPS = 0.3

    /**
     * The gates of 3-10 and 3-5, in order of what the user feels most. [kind] decides which apply:
     * every method that promises to get past the filter must open a filtered site, and a tunnel
     * that promises a foreign address must not come out in Iran.
     */
    fun weakness(kind: MethodKind, n: Numbers, requireForeignExit: Boolean = true): Weak? = when {
        kind != MethodKind.ANTISANCTION && n.filtered == Tri.NO -> Weak.NO_FILTERED
        // An anti-sanction exit that comes out in Iran un-sanctions nothing, exactly like a tunnel.
        requireForeignExit && kind != MethodKind.UNFILTER && n.exitCountry.equals("IR", ignoreCase = true) -> Weak.IRAN_EXIT
        (n.stabilityRate ?: 1.0) < GATE_STABILITY -> Weak.UNSTABLE
        (n.latencyMs ?: 0) > GATE_LATENCY_MS -> Weak.SLOW_RESPONSE
        n.mbps != null && n.mbps < GATE_MBPS -> Weak.SLOW_SPEED
        else -> null
    }
}
