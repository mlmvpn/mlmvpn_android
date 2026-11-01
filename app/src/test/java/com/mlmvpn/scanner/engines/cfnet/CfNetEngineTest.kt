package com.mlmvpn.scanner.engines.cfnet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CfNetEngineTest {

    @Test fun oneRuntimeFailureRevokesEvenALongTrustedWinner() {
        val st = store()
        repeat(100) { st.record(prof, CfStage.CONNECT, "dat_chrome_v4", true, 20, null, 1000L + it) }
        st.record(prof, CfStage.CONNECT, "dat_chrome_v4", false, 0, FailureClass.DATA_TRANSFER_FAILURE, 1100)
        assertNull(st.fastPath(prof, CfStage.CONNECT, StrategyRegistry.default().forStage(CfStage.CONNECT), 1101))
    }

    @Test fun attemptBudgetIsNotExceededByLastParallelBatch() = runBlocking {
        val eng = StrategyEngine(StrategyRegistry.default(), store(), validatorFor(emptySet()))
        val out = eng.best(CfStage.ACCOUNT, prof, ctx, StrategyEngine.StageBudget(maxAttempts = 4, parallel = 3))
        assertEquals(4, out.tried.size)
    }

    @Test fun stageDeadlineCancelsSlowValidation() = runBlocking {
        val eng = StrategyEngine(StrategyRegistry.default(), store(), { _, _, _ ->
            kotlinx.coroutines.delay(2000)
            ValidationResult.pass(2000)
        })
        val start = System.nanoTime()
        val out = eng.best(CfStage.ACCOUNT, prof, ctx, StrategyEngine.StageBudget(deadlineMs = 80, parallel = 1))
        assertFalse(out.ok)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1000)
    }

    @Test fun aRejectedCredentialStopsDiscoveryAndIsNotHeldAgainstTheRoute() = runBlocking {
        val st = store()
        var calls = 0
        val eng = StrategyEngine(StrategyRegistry.default(), st, { _, _, _ ->
            calls++
            ValidationResult.fail(FailureClass.AUTH_FAILURE, detail = "http=403", phase = WirePhase.DONE)
        })
        val out = eng.best(CfStage.ACCOUNT, prof, ctx, StrategyEngine.StageBudget(parallel = 1))
        assertFalse(out.ok)
        assertEquals(FailureClass.AUTH_FAILURE, out.verdict)
        assertEquals(FailureClass.AUTH_FAILURE, out.dominantFailure)
        assertEquals("one authoritative answer is enough", 1, calls)
        // The route carried the request, so it keeps its standing.
        assertEquals(0, st.stat(prof, CfStage.ACCOUNT, out.tried.single().first.id).fail)
    }

    @Test fun firstSuccessCancelsSlowSiblings() = runBlocking {
        val reg = StrategyRegistry.default()
        val first = StrategyEngine(reg, store(), { _, _, _ -> ValidationResult.fail(FailureClass.TLS_FAILURE) })
        // Learn the ranked order, then make the top candidate fast and everything else slow.
        val order = first.store.rank(prof, CfStage.ACCOUNT, reg.forStage(CfStage.ACCOUNT, ctx))
        val fast = order.first().id
        val st = store()
        val eng = StrategyEngine(reg, st, { s, _, _ ->
            if (s.id == fast) ValidationResult.pass(5) else { kotlinx.coroutines.delay(10_000); ValidationResult.fail(FailureClass.TLS_FAILURE) }
        })
        val t0 = System.nanoTime()
        val out = eng.best(CfStage.ACCOUNT, prof, ctx, StrategyEngine.StageBudget(parallel = 3))
        assertEquals(fast, out.winner?.id)
        assertTrue("siblings must be cancelled, not awaited", (System.nanoTime() - t0) / 1_000_000 < 3_000)
        // Cancelled siblings are not failures of theirs.
        out.tried.filter { it.first.id != fast }.forEach { (s, r) ->
            assertEquals(FailureClass.CANCELLED, r.failure)
            assertEquals(0, st.stat(prof, CfStage.ACCOUNT, s.id).fail)
        }
    }

    @Test fun anAttemptCutShortByTheStageDeadlineIsNotRecorded() = runBlocking {
        val st = store()
        val eng = StrategyEngine(StrategyRegistry.default(), st, { _, _, _ ->
            kotlinx.coroutines.delay(5_000); ValidationResult.pass(5_000)
        })
        val out = eng.best(CfStage.ACCOUNT, prof, ctx, StrategyEngine.StageBudget(deadlineMs = 100, parallel = 1))
        assertFalse(out.ok)
        out.tried.forEach { (s, _) -> assertEquals(0, st.stat(prof, CfStage.ACCOUNT, s.id).fail) }
    }

    @Test fun aTimeoutInsideTheValidatorIsAStrategyTimeoutNotACrash() = runBlocking {
        val st = store()
        val eng = StrategyEngine(StrategyRegistry.default(), st, { s, _, _ ->
            if (s.id == "dat_ech_v4") ValidationResult.pass(10)
            else kotlinx.coroutines.withTimeout(10) { kotlinx.coroutines.delay(1_000); ValidationResult.pass(1) }
        })
        val out = eng.best(CfStage.CONNECT, prof, ctx, StrategyEngine.StageBudget(parallel = 1))
        assertEquals("dat_ech_v4", out.winner?.id)
        assertTrue(out.tried.any { it.second.failure == FailureClass.TIMEOUT })
    }

    @Test fun aKnownWinnerGetsOneRetryOnANetworkGlitch() = runBlocking {
        val st = store()
        repeat(4) { st.record(prof, CfStage.CONNECT, "dat_ech_v4", true, 30, null, 1000) }
        var n = 0
        val eng = StrategyEngine(StrategyRegistry.default(), st, { s, _, _ ->
            if (s.id == "dat_ech_v4" && n++ == 0) ValidationResult.fail(FailureClass.TCP_RESET) else if (s.id == "dat_ech_v4") ValidationResult.pass(30)
            else ValidationResult.fail(FailureClass.TLS_FAILURE)
        }, now = { 1500 })
        val out = eng.best(CfStage.CONNECT, prof, ctx)
        assertEquals("dat_ech_v4", out.winner?.id)
        assertTrue(out.fromFastPath)
    }

    @Test fun aProfileChangeDoesNotReuseOldWinner() {
        val st = store()
        val reg = StrategyRegistry.default()
        repeat(10) { st.record(prof, CfStage.CONNECT, "dat_chrome_v4", true, 20, null, 1000) }
        assertNull(st.fastPath(prof.copy(dnsSane = false), CfStage.CONNECT, reg.all(), 1001))
        st.record(prof.copy(dnsSane = false), CfStage.ACCOUNT, "ctl_sys_plain", true, 20, null, 1001)
        assertNull(st.fastPath(prof.copy(dnsSane = false), CfStage.CONNECT, reg.all(), 1002))
    }

    @Test fun engineVersionChangeRequiresRevalidation() {
        val file = File(Files.createTempDirectory("cfnet-version").toFile(), "s.json")
        StrategyStore(file, 1).record(prof, CfStage.CONNECT, "dat_chrome_v4", true, 20, null, 1000)
        assertNull(StrategyStore(file, 2).fastPath(prof, CfStage.CONNECT, StrategyRegistry.default().all(), 1001))
    }

    private val prof = NetworkProfile(key = NetworkProfile.hashKey("net"), hasV4 = true, hasV6 = true)
    private val ctx = EngineContext(hasAccount = true, hasWorker = true, hasV6 = true, warpSupported = true, torAvailable = true, psiphonAvailable = true)

    private fun store() = StrategyStore(File(Files.createTempDirectory("cfnet").toFile(), "s.json"))

    /** A validator that only lets the strategies whose ids are in [wins] pass. */
    private fun validatorFor(wins: Set<String>): suspend (Strategy, CfStage, EngineContext) -> ValidationResult =
        { s, _, _ -> if (s.id in wins) ValidationResult.pass(42, "ok") else ValidationResult.fail(FailureClass.TLS_FAILURE, phase = WirePhase.SECURE_CONNECT) }

    @Test fun discoveryFindsTheWorkingStrategyAndSavesIt() = runBlocking {
        val st = store()
        val reg = StrategyRegistry.default()
        val eng = StrategyEngine(reg, st, validatorFor(setOf("dat_ech_v4")))
        val out = eng.best(CfStage.CONNECT, prof, ctx)
        assertTrue(out.ok)
        assertEquals("dat_ech_v4", out.winner?.id)
        // It is now the proven fast-path winner.
        assertEquals("dat_ech_v4", st.fastPath(prof, CfStage.CONNECT, reg.forStage(CfStage.CONNECT, ctx))?.id)
    }

    @Test fun fastPathReusesTheSavedWinnerWithoutTryingEverything() = runBlocking {
        val st = store()
        val reg = StrategyRegistry.default()
        // Seed a proven winner.
        repeat(4) { st.record(prof, CfStage.SCAN, "dat_fragchrome_v4", ok = true, latencyMs = 30, failure = null, now = 1000) }
        var calls = 0
        val eng = StrategyEngine(reg, st, { s, _, _ -> calls++; if (s.id == "dat_fragchrome_v4") ValidationResult.pass(30) else ValidationResult.fail(FailureClass.TCP_TIMEOUT) }, now = { 1500 })
        val out = eng.best(CfStage.SCAN, prof, ctx)
        assertEquals("dat_fragchrome_v4", out.winner?.id)
        assertEquals("only the fast-path winner should be validated", 1, calls)
        assertEquals(1, out.tried.size)
    }

    @Test fun selfHealsWhenTheSavedWinnerStopsWorking() = runBlocking {
        val st = store()
        val reg = StrategyRegistry.default()
        repeat(4) { st.record(prof, CfStage.CONNECT, "dat_fragfp_v4", ok = true, latencyMs = 40, failure = null, now = 1000) }
        // fragfp now fails; ech works.
        val eng = StrategyEngine(reg, st, validatorFor(setOf("dat_ech_v4")), now = { 2000 })
        val out = eng.best(CfStage.CONNECT, prof, ctx)
        assertEquals("dat_ech_v4", out.winner?.id)
        // The old winner was tried first and recorded as a failure (its confidence drops).
        assertTrue(out.tried.any { it.first.id == "dat_fragfp_v4" && !it.second.ok })
        assertTrue(st.confidence(prof, CfStage.CONNECT, "dat_fragfp_v4", now = 2000) <
            st.confidence(prof, CfStage.CONNECT, "dat_ech_v4", now = 2000))
    }

    @Test fun reportsNoWinnerWithADominantFailureWhenNothingWorks() = runBlocking {
        val st = store()
        val reg = StrategyRegistry.default()
        val eng = StrategyEngine(reg, st, validatorFor(emptySet()))
        val out = eng.best(CfStage.ACCOUNT, prof, ctx, StrategyEngine.StageBudget(maxAttempts = 20))
        assertFalse(out.ok)
        assertNull(out.winner)
        assertEquals(FailureClass.TLS_FAILURE, out.dominantFailure)
        assertTrue(out.tried.isNotEmpty())
    }

    @Test fun skipsAStageWithNoCandidatesAfterPrereqFiltering() = runBlocking {
        val reg = StrategyRegistry().register(Strategy(
            id = "dat_only_chain", name = "x", stages = setOf(CfStage.CONNECT), layer = Layer.DATA,
            prior = 0.3, cost = 7, timeoutMs = 1000, retries = 0, prereqs = setOf(Prereq.TOR),
            data = ShapeSpec(TunnelShape.CHAIN_TOR),
        ))
        val eng = StrategyEngine(reg, store(), validatorFor(emptySet()))
        val out = eng.best(CfStage.CONNECT, prof, EngineContext(torAvailable = false))
        assertFalse(out.ok)
        assertEquals("no_candidates", out.skippedReason)
        assertTrue(out.tried.isEmpty())
    }

    @Test fun preferredStrategiesAreProvenFirstInTheGivenOrder() = runBlocking {
        val order = mutableListOf<String>()
        // Nothing passes, so every attempt is seen: the preferred ones must lead, in the order given,
        // however low their priors (chrome_v6 ranks near the bottom on its own).
        val eng = StrategyEngine(StrategyRegistry.default(), store(), { s, _, _ ->
            order += s.id
            ValidationResult.fail(FailureClass.TLS_FAILURE)
        })
        val prefer = listOf("dat_chrome_v6", "dat_ech_v4", "dat_fragfp_v4")
        eng.best(CfStage.SCAN, prof, ctx, StrategyEngine.StageBudget(maxAttempts = 5, parallel = 1), prefer = prefer)
        assertEquals(prefer, order.take(3))
    }

    @Test fun aPreferredIdThatIsNotACandidateIsSkipped() = runBlocking {
        val order = mutableListOf<String>()
        val eng = StrategyEngine(StrategyRegistry.default(), store(), { s, _, _ ->
            order += s.id
            ValidationResult.fail(FailureClass.TLS_FAILURE)
        })
        // No IPv6 here: the IPv6 way is not a candidate, so the next preferred one leads.
        val noV6 = EngineContext(hasAccount = true, hasWorker = true, hasV6 = false)
        eng.best(CfStage.SCAN, prof, noV6, StrategyEngine.StageBudget(maxAttempts = 2, parallel = 1),
            prefer = listOf("dat_chrome_v6", "dat_ech_v4", "no_such_strategy"))
        assertEquals("dat_ech_v4", order.first())
        assertFalse("dat_chrome_v6" in order)
    }

    @Test fun runtimeFailureLowersConfidenceForHealing() {
        val st = store()
        val reg = StrategyRegistry.default()
        repeat(5) { st.record(prof, CfStage.CONNECT, "dat_chrome_v4", ok = true, latencyMs = 20, failure = null, now = 1000) }
        val before = st.confidence(prof, CfStage.CONNECT, "dat_chrome_v4", now = 1000)
        val eng = StrategyEngine(reg, st, validatorFor(emptySet()), now = { 1000 })
        eng.reportRuntimeFailure(CfStage.CONNECT, "dat_chrome_v4", prof, FailureClass.DATA_TRANSFER_FAILURE)
        val after = st.confidence(prof, CfStage.CONNECT, "dat_chrome_v4", now = 1000)
        assertTrue(after < before)
        assertEquals(FailureClass.DATA_TRANSFER_FAILURE, st.stat(prof, CfStage.CONNECT, "dat_chrome_v4").lastFailure)
    }
}
