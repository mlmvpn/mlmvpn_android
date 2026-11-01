package com.mlmvpn.scanner.engines.cfnet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The orchestration: for one stage on the current network it runs the fast path (re-check the saved
 * winner), then ranked fallback discovery, proves each candidate with a REAL validator, records
 * every outcome, and returns the winner. It is pure -- the validator is injected -- so the whole
 * Detect → Test → Validate → Select → Persist → Reuse → Monitor → Recover loop is unit-tested with a
 * fake validator, and the Android layer only has to supply real ones ([CfValidators]).
 *
 * Self-healing is the fast path failing: the saved winner is re-checked first, and if it no longer
 * works it is recorded as a failure (which drops its confidence and pushes it below untried
 * alternatives) and ranked fallback finds a replacement, which is then saved as the new winner.
 *
 * What is recorded is evidence ABOUT A STRATEGY only. An authoritative answer from Cloudflare (a
 * rejected credential, a rate limit) proves the route carried the request, so it is not held
 * against the route; an attempt cut short by the stage deadline, or cancelled because a sibling
 * already won, is not evidence of anything and is not recorded either.
 */
class StrategyEngine(
    val registry: StrategyRegistry,
    val store: StrategyStore,
    /** Proves one strategy for one stage with real I/O. Must classify its own failure; never throws for a normal failure. */
    private val validate: suspend (Strategy, CfStage, EngineContext) -> ValidationResult,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Reports progress for the UI: (stage, strategy being tried, result-or-null-while-running). Called from worker threads. */
    private val onProgress: (CfStage, Strategy, ValidationResult?) -> Unit = { _, _, _ -> },
) {

    data class StageBudget(val maxAttempts: Int = 48, val deadlineMs: Long = 150_000, val parallel: Int = 3) {
        init { require(maxAttempts > 0 && deadlineMs > 0 && parallel in 1..3) }
    }

    data class StageOutcome(
        val stage: CfStage,
        val winner: Strategy?,
        val result: ValidationResult?,
        /** Every strategy tried, in the order tried, with its outcome -- for the report and the UI. */
        val tried: List<Pair<Strategy, ValidationResult>>,
        /** Set when the stage could not run at all (no candidates after prereq filtering, a missing account). */
        val skippedReason: String? = null,
        /** A verdict no other strategy could change (a rejected credential): why discovery stopped early. */
        val verdict: FailureClass? = null,
        /** True when the saved winner answered on the fast path (no discovery was needed). */
        val fromFastPath: Boolean = false,
    ) {
        val ok: Boolean get() = winner != null
        /** The most informative failure across everything tried (for the report when nothing worked). */
        val dominantFailure: FailureClass?
            get() = verdict ?: tried.mapNotNull { it.second.failure }.filter { it != FailureClass.CANCELLED }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    /**
     * Find and prove the best strategy for [stage]; records outcomes and returns the winner (or why not).
     * [family]: this network's firewall family when known -- its strategies are tried first; it only
     * orders the search, every strategy is still proven before it wins.
     * [prefer]: strategy ids tried before the ranked rest, in this order, right after the fast path --
     * what a quick look at the line just saw pass. Only an order too: each is still proven here.
     */
    suspend fun best(stage: CfStage, profile: NetworkProfile, ctx: EngineContext, budget: StageBudget = StageBudget(),
        family: FirewallFamily? = null, prefer: List<String> = emptyList()): StageOutcome = coroutineScope {
        val candidates = registry.forStage(stage, ctx)
        if (candidates.isEmpty()) return@coroutineScope StageOutcome(stage, null, null, emptyList(), skippedReason = "no_candidates")

        val tried = LinkedHashMap<String, Pair<Strategy, ValidationResult>>()
        val start = now()
        fun remaining() = budget.deadlineMs - (now() - start)

        suspend fun attempt(s: Strategy): ValidationResult {
            onProgress(stage, s, null)
            val allowance = minOf(s.timeoutMs, remaining().coerceAtLeast(1))
            val truncated = allowance < s.timeoutMs
            val r = try {
                withTimeoutOrNull(allowance) { validate(s, stage, ctx) }
                    ?: ValidationResult.fail(FailureClass.TIMEOUT, allowance, if (truncated) "stage_deadline" else "strategy_timeout")
            } catch (e: CancellationException) {
                // A real cancellation (Stop, a sibling already won) propagates; a timeout thrown from
                // INSIDE the validator (its own withTimeout) is just this strategy timing out.
                currentCoroutineContext().ensureActive()
                ValidationResult.fail(FailureClass.TIMEOUT, detail = "inner_timeout")
            } catch (e: Exception) {
                ValidationResult.fail(FailureClass.UNKNOWN, detail = e.javaClass.simpleName)
            }
            // A validator that swallowed the interruption and returned a "failure" was cancelled, not
            // beaten: nothing it says is evidence.
            currentCoroutineContext().ensureActive()
            val evidence = r.ok || (r.failure!!.countsAgainstStrategy && !(truncated && r.failure == FailureClass.TIMEOUT))
            if (evidence) store.record(profile, stage, s.id, r.ok, r.latencyMs, r.failure, now())
            onProgress(stage, s, r)
            return r
        }

        // 1. Fast path: re-check the saved winner first (once more on a network-level failure:
        //    a known winner is worth one retry before a whole discovery is paid for).
        store.fastPath(profile, stage, candidates, now())?.let { fp ->
            var r = attempt(fp)
            if (!r.ok && fp.retries > 0 && r.failure?.isNetwork == true && remaining() > fp.timeoutMs / 2) r = attempt(fp)
            tried[fp.id] = fp to r
            if (r.ok) return@coroutineScope StageOutcome(stage, fp, r, tried.values.toList(), fromFastPath = true)
            if (r.failure?.endsStage == true) return@coroutineScope StageOutcome(stage, null, null, tried.values.toList(), verdict = r.failure)
        }

        // 2. Ranked fallback discovery, in bounded-parallel batches. The first success in a batch
        //    cancels its siblings (their cleanup still runs: they are children of this scope).
        val ranked = store.rank(profile, stage, candidates, now(), if (stage.layer == Layer.DATA) family else null).filter { it.id !in tried }
            .let { all ->
                val first = prefer.distinct().mapNotNull { id -> all.firstOrNull { it.id == id } }
                first + all.filter { it !in first }
            }
        var i = 0
        while (i < ranked.size && tried.size < budget.maxAttempts && remaining() > 0) {
            val count = minOf(budget.parallel, budget.maxAttempts - tried.size, ranked.size - i)
            val batch = ranked.subList(i, i + count)
            val results = Channel<Pair<Strategy, ValidationResult>>(Channel.UNLIMITED)
            val jobs = batch.map { s -> launch(Dispatchers.Default) { results.send(s to attempt(s)) } }
            var winner: Pair<Strategy, ValidationResult>? = null
            var verdict: FailureClass? = null
            for (n in batch.indices) {
                val (s, r) = results.receive()
                tried[s.id] = s to r
                if (r.ok) { winner = s to r; break }
                if (r.failure?.endsStage == true) { verdict = r.failure; break }
            }
            if (winner != null || verdict != null) {
                jobs.forEach { it.cancel() }
                // Siblings that finished before the cancel keep their real outcome; the rest were cut short.
                while (true) { val done = results.tryReceive().getOrNull() ?: break; tried[done.first.id] = done }
                batch.filter { it.id !in tried }.forEach { tried[it.id] = it to ValidationResult.fail(FailureClass.CANCELLED, detail = "sibling_finished") }
                return@coroutineScope if (winner != null) StageOutcome(stage, winner.first, winner.second, tried.values.toList())
                    else StageOutcome(stage, null, null, tried.values.toList(), verdict = verdict)
            }
            i += count
        }

        StageOutcome(stage, null, null, tried.values.toList())
    }

    /**
     * Report a runtime failure of a strategy that had been chosen (e.g. a live connection dropped),
     * so its confidence falls and the next [best] call heals to a replacement. Pure bookkeeping.
     */
    fun reportRuntimeFailure(stage: CfStage, strategyId: String, profile: NetworkProfile, failure: FailureClass) {
        if (failure.countsAgainstStrategy) store.record(profile, stage, strategyId, ok = false, latencyMs = 0, failure = failure, now = now())
    }

    /** Report a runtime success (a live connection that carried data), to reinforce the winner. */
    fun reportRuntimeSuccess(stage: CfStage, strategyId: String, profile: NetworkProfile, latencyMs: Long) {
        store.record(profile, stage, strategyId, ok = true, latencyMs = latencyMs, failure = null, now = now())
    }
}
