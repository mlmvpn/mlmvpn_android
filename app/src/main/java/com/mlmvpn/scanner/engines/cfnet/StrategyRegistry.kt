package com.mlmvpn.scanner.engines.cfnet

/**
 * Runtime facts a strategy's prerequisites are checked against. Supplied per run by the Android
 * layer (does an account exist, is a Worker deployed, is there an IPv6 route, …). Pure, so the
 * engine tests can construct one directly.
 */
data class EngineContext(
    val hasAccount: Boolean = false,
    val hasWorker: Boolean = false,
    val hasV6: Boolean = false,
    val hasOwnConfig: Boolean = false,
    val warpSupported: Boolean = true,
    val torAvailable: Boolean = false,
    val psiphonAvailable: Boolean = false,
    val hasProxy: Boolean = false,
    /** The config the DATA stage runs on is an XHTTP one (so the XHTTP tunings can apply). */
    val xhttp: Boolean = false,
) {
    fun satisfies(prereqs: Set<Prereq>): Boolean = prereqs.all {
        when (it) {
            Prereq.ACCOUNT -> hasAccount
            Prereq.WORKER_DEPLOYED -> hasWorker
            Prereq.IPV6_ROUTE -> hasV6
            Prereq.OWN_CONFIG -> hasOwnConfig
            Prereq.WARP_SUPPORTED -> warpSupported
            Prereq.TOR -> torAvailable
            Prereq.PSIPHON -> psiphonAvailable
            Prereq.ACTIVE_PROXY -> hasProxy
            Prereq.XHTTP_CONFIG -> xhttp
        }
    }
}

/**
 * The central list of strategies. New strategies are added in one place ([StrategyCatalog]); nothing
 * else needs to change, which is the whole point of having a registry rather than scattering the
 * knobs through twenty call sites.
 */
class StrategyRegistry {
    private val entries = mutableListOf<Strategy>()

    fun register(s: Strategy): StrategyRegistry {
        require(entries.none { it.id == s.id }) { "duplicate strategy id: ${s.id}" }
        entries += s
        return this
    }

    fun all(): List<Strategy> = entries.toList()

    fun byId(id: String): Strategy? = entries.find { it.id == id }

    /** The strategies that apply to [stage] and whose prerequisites [ctx] satisfies (all, when ctx is null). */
    fun forStage(stage: CfStage, ctx: EngineContext? = null): List<Strategy> =
        entries.filter { stage in it.stages && (ctx == null || ctx.satisfies(it.prereqs)) }

    companion object {
        fun default(): StrategyRegistry = StrategyRegistry().also { StrategyCatalog.populate(it) }
    }
}
