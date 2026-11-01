package com.mlmvpn.scanner.engines.flux.core.race

import com.mlmvpn.scanner.engines.flux.core.model.FailReason
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.FluxEgressIdentity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the racer needs from the outside world. The Android implementation dials real sockets and
 * runs a probe core; the tests script it.
 */
interface FluxProbe {
    /** Stage 1, cheap: is the dial address reachable at all (TCP, and the TLS handshake where it applies)? */
    suspend fun reach(c: FluxCandidate): Reach

    /**
     * Called once, with exactly the candidates stage 2 will use, before any of them is asked. The
     * Android side starts one probe core here with an explicit candidate -> local port map.
     */
    suspend fun prepare(cs: List<FluxCandidate>): Boolean

    /** Stage 2: a real request through the candidate (proxy handshake + HTTP), and its exit. */
    suspend fun real(c: FluxCandidate): Real

    data class Reach(val ok: Boolean, val ms: Long? = null, val reason: FailReason? = null)
    data class Real(
        val ok: Boolean,
        val rttMs: Long? = null,
        val handshakeMs: Long? = null,
        val egress: FluxEgressIdentity? = null,
        val reason: FailReason? = null,
    )
}

/**
 * The race: stage 1 thins the field cheaply, stage 2 measures the survivors for real, in parallel.
 *
 * Unlike a first-answer-wins race, the first success is only a provisional winner: a short grace
 * window lets a better candidate that is a few hundred milliseconds behind still take it. Waiting
 * any longer than that would cost the user more than a slightly better route gains them -- the
 * rest is the scorer's and the background checks' job after the tunnel is up.
 */
class FluxRacer(private val probe: FluxProbe, private val cfg: Config = Config()) {

    data class Config(
        /** Stage-1 sockets in flight at once. */
        val reachConcurrency: Int = 12,
        /** Stage 1 stops once this many survivors are in hand. */
        val realBatch: Int = 10,
        /** Stage 1 stops at this deadline with whatever survived. */
        val reachDeadlineMs: Long = 2_500,
        /** Stage 2 gives up on everything at this deadline. */
        val realDeadlineMs: Long = 7_000,
        /** How long after the first success a better one may still win. */
        val graceMs: Long = 250,
        /** Stage 2 stops as soon as this many are accepted (the grace is a ceiling, not a wait). */
        val enough: Int = Int.MAX_VALUE,
    )

    data class Outcome(
        /** Accepted successes, best first. Empty: nothing worked. */
        val ranked: List<Pair<FluxCandidate, FluxProbe.Real>>,
        val reach: Map<String, FluxProbe.Reach>,
        val real: Map<String, FluxProbe.Real>,
        /** Real successes refused by [accept] (wrong country): useful to the country picker. */
        val refused: List<Pair<FluxCandidate, FluxProbe.Real>>,
    ) {
        val winner: FluxCandidate? get() = ranked.firstOrNull()?.first
    }

    suspend fun race(
        candidates: List<FluxCandidate>,
        accept: (FluxCandidate, FluxProbe.Real) -> Boolean,
        score: (FluxCandidate, FluxProbe.Real) -> Double,
    ): Outcome {
        val reach = LinkedHashMap<String, FluxProbe.Reach>()
        val survivors = stage1(candidates, reach)
        if (survivors.isEmpty() || !probe.prepare(survivors)) {
            return Outcome(emptyList(), reach, emptyMap(), emptyList())
        }
        val scope = detached()
        val real = LinkedHashMap<String, FluxProbe.Real>()
        val accepted = ArrayList<Pair<FluxCandidate, FluxProbe.Real>>()
        val refused = ArrayList<Pair<FluxCandidate, FluxProbe.Real>>()
        val results = Channel<Pair<FluxCandidate, FluxProbe.Real>>(Channel.UNLIMITED)
        survivors.forEach { c -> scope.launch { results.send(c to safeReal(c)) } }
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        var firstOkAt: Long? = null
        var received = 0
        while (received < survivors.size) {
            val budget = firstOkAt?.let { it + cfg.graceMs - elapsed() } ?: (cfg.realDeadlineMs - elapsed())
            if (budget <= 0) break
            val next = withTimeoutOrNull(budget) { results.receive() } ?: break
            received++
            val (c, r) = next
            real[c.id] = r
            if (!r.ok) continue
            if (accept(c, r)) {
                accepted += next
                if (firstOkAt == null) firstOkAt = elapsed()
                if (accepted.size >= cfg.enough) break
            } else refused += next
        }
        scope.cancel()
        results.close()
        return Outcome(accepted.sortedByDescending { score(it.first, it.second) }, reach, real, refused)
    }

    /**
     * Probes run outside the caller's scope and are cancelled, not joined, when the race is
     * decided: a socket stuck in a blocking connect must not hold the winner back until its own
     * timeout. Each probe still ends by that timeout; nothing it reports afterwards is read.
     */
    private suspend fun detached(): CoroutineScope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })

    /** Stage 1: bounded concurrency; returns survivors in the order they answered (fastest first). */
    private suspend fun stage1(candidates: List<FluxCandidate>, into: MutableMap<String, FluxProbe.Reach>): List<FluxCandidate> {
        val scope = detached()
        val gate = Semaphore(cfg.reachConcurrency)
        val results = Channel<Pair<FluxCandidate, FluxProbe.Reach>>(Channel.UNLIMITED)
        candidates.forEach { c ->
            scope.launch { gate.withPermit { results.send(c to safeReach(c)) } }
        }
        val survivors = ArrayList<FluxCandidate>()
        val start = System.nanoTime()
        var received = 0
        while (received < candidates.size && survivors.size < cfg.realBatch) {
            val left = cfg.reachDeadlineMs - (System.nanoTime() - start) / 1_000_000
            if (left <= 0) break
            val (c, r) = withTimeoutOrNull(left) { results.receive() } ?: break
            received++
            into[c.id] = r
            if (r.ok) survivors += c
        }
        scope.cancel()
        results.close()
        return survivors
    }

    private suspend fun safeReach(c: FluxCandidate): FluxProbe.Reach =
        try { probe.reach(c) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { FluxProbe.Reach(false, reason = FailReason.OTHER) }

    private suspend fun safeReal(c: FluxCandidate): FluxProbe.Real =
        try { probe.real(c) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { FluxProbe.Real(false, reason = FailReason.OTHER) }
}
