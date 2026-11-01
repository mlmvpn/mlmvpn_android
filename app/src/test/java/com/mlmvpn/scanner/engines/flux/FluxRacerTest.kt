package com.mlmvpn.scanner.engines.flux

import com.mlmvpn.scanner.engines.flux.FluxFixtures.cand
import com.mlmvpn.scanner.engines.flux.FluxFixtures.reality
import com.mlmvpn.scanner.engines.flux.core.model.FailReason
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.FluxEgressIdentity
import com.mlmvpn.scanner.engines.flux.core.race.FluxProbe
import com.mlmvpn.scanner.engines.flux.core.race.FluxRacer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxRacerTest {

    /** Scripted network: per candidate, how long each stage takes and whether it works. */
    private class Script(
        val reach: Map<String, Pair<Long, Boolean>>,
        val real: Map<String, Triple<Long, Boolean, String?>>,
    ) : FluxProbe {
        var prepared: List<String> = emptyList()
        override suspend fun reach(c: FluxCandidate): FluxProbe.Reach {
            val (ms, ok) = reach.getValue(c.id); delay(ms)
            return FluxProbe.Reach(ok, ms, if (ok) null else FailReason.TCP_TIMEOUT)
        }
        override suspend fun prepare(cs: List<FluxCandidate>): Boolean { prepared = cs.map { it.id }; return true }
        override suspend fun real(c: FluxCandidate): FluxProbe.Real {
            val (ms, ok, cc) = real.getValue(c.id); delay(ms)
            return FluxProbe.Real(ok, if (ok) ms else null, egress = cc?.let { FluxEgressIdentity(countryCode = it, verifiedAt = 1, confidence = 0.6) })
        }
    }

    private val a = cand(reality(ip = "1.1.1.10"))
    private val b = cand(reality(ip = "1.1.1.20"))
    private val c = cand(reality(ip = "1.1.1.30"))
    private val cfg = FluxRacer.Config(graceMs = 250, reachDeadlineMs = 2_000, realDeadlineMs = 3_000)
    private val byRtt: (FluxCandidate, FluxProbe.Real) -> Double = { _, r -> 1.0 / (r.rttMs ?: 10_000) }
    private val any: (FluxCandidate, FluxProbe.Real) -> Boolean = { _, _ -> true }

    @Test fun `fast failure does not win, slow success does`() = runBlocking {
        val p = Script(mapOf(a.id to (10L to false), b.id to (50L to true)), mapOf(b.id to Triple(400L, true, null)))
        val out = FluxRacer(p, cfg).race(listOf(a, b), any, byRtt)
        assertEquals(b.id, out.winner?.id)
        assertEquals(listOf(b.id), p.prepared)
    }

    @Test fun `fast success wins over a much slower one`() = runBlocking {
        val p = Script(mapOf(a.id to (10L to true), b.id to (10L to true)),
            mapOf(a.id to Triple(50L, true, null), b.id to Triple(1_500L, true, null)))
        val t0 = System.currentTimeMillis()
        val out = FluxRacer(p, cfg).race(listOf(a, b), any, byRtt)
        assertEquals(a.id, out.winner?.id)
        // Decided after the grace window, not after the slow one.
        assertTrue(System.currentTimeMillis() - t0 < 1_000)
    }

    @Test fun `better route arriving 100ms later still wins within the grace window`() = runBlocking {
        val p = Script(mapOf(a.id to (5L to true), b.id to (5L to true)),
            mapOf(a.id to Triple(100L, true, null), b.id to Triple(200L, true, null)))
        val scoreBFirst: (FluxCandidate, FluxProbe.Real) -> Double = { cc, _ -> if (cc.id == b.id) 2.0 else 1.0 }
        val out = FluxRacer(p, cfg).race(listOf(a, b), any, scoreBFirst)
        assertEquals(b.id, out.winner?.id)
        assertEquals(2, out.ranked.size)
    }

    @Test fun `a route later than the grace window is not waited for`() = runBlocking {
        val p = Script(mapOf(a.id to (5L to true), b.id to (5L to true)),
            mapOf(a.id to Triple(100L, true, null), b.id to Triple(1_200L, true, null)))
        val scoreBFirst: (FluxCandidate, FluxProbe.Real) -> Double = { cc, _ -> if (cc.id == b.id) 2.0 else 1.0 }
        val out = FluxRacer(p, cfg).race(listOf(a, b), any, scoreBFirst)
        assertEquals(a.id, out.winner?.id)
    }

    @Test fun `wrong country is refused, not chosen`() = runBlocking {
        val p = Script(mapOf(a.id to (5L to true), b.id to (5L to true)),
            mapOf(a.id to Triple(50L, true, "DE"), b.id to Triple(150L, true, "US")))
        val wantUs: (FluxCandidate, FluxProbe.Real) -> Boolean = { _, r -> r.egress?.countryCode == "US" }
        val out = FluxRacer(p, cfg).race(listOf(a, b), wantUs, byRtt)
        assertEquals(b.id, out.winner?.id)
        assertEquals(a.id, out.refused.single().first.id)
    }

    @Test fun `nothing works gives no winner`() = runBlocking {
        val p = Script(mapOf(a.id to (5L to false), b.id to (5L to false), c.id to (5L to false)), emptyMap())
        val out = FluxRacer(p, cfg).race(listOf(a, b, c), any, byRtt)
        assertNull(out.winner)
        assertEquals(3, out.reach.size)
    }

    /** FLUX's own race: a long grace so standbys are found, ended as soon as there are enough. */
    @Test fun `standbys slower than the old grace are kept, and enough of them ends the race`() = runBlocking {
        val d = cand(reality(ip = "1.1.1.40"))
        val p = Script(listOf(a, b, c, d).associate { it.id to (5L to true) }, mapOf(
            a.id to Triple(100L, true, null), b.id to Triple(700L, true, null),
            c.id to Triple(900L, true, null), d.id to Triple(2_500L, true, null),
        ))
        val t0 = System.currentTimeMillis()
        val out = FluxRacer(p, cfg.copy(graceMs = 1_500, enough = 3)).race(listOf(a, b, c, d), any, byRtt)
        assertEquals(listOf(a.id, b.id, c.id), out.ranked.map { it.first.id })
        // Ended by the third answer (~900 ms), not by the grace or the slowest one.
        assertTrue(System.currentTimeMillis() - t0 < 1_500)
    }

    @Test fun `stage two only gets the survivors, capped at the batch`() = runBlocking {
        val many = (1..20).map { cand(reality(ip = "2.2.2.$it")) }
        val p = Script(many.associate { it.id to (5L to true) }, many.associate { it.id to Triple(20L, true, null) })
        FluxRacer(p, cfg.copy(realBatch = 6)).race(many, any, byRtt)
        assertEquals(6, p.prepared.size)
    }
}
