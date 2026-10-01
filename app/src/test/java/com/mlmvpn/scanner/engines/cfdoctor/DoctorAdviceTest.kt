package com.mlmvpn.scanner.engines.cfdoctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the person reads. A wrong sentence here sends them after the wrong fix. */
class DoctorAdviceTest {

    private fun terminal(id: String, code: String, extra: Map<String, Long> = emptyMap()) =
        Evidence(id, "r", code, numbers = mapOf("terminal" to 1L) + extra)

    @Test fun noInternetIsSaidPlainlyAndNothingElseIsBlamed() {
        val a = DoctorAdvice.of(emptyList(), setOf("NO_CONNECTIVITY"), true, false)
        assertEquals(DoctorAdvice.Overall.NO_INTERNET, a.overall)
    }

    @Test fun cleanRunIsAllGoodOnlyWhenComplete() {
        val rows = listOf(terminal("ST3", "ok"), Evidence("X1", "system", "ok", 200))
        assertEquals(DoctorAdvice.Overall.ALL_GOOD, DoctorAdvice.of(rows, setOf("OK"), true, false).overall)
        assertEquals(DoctorAdvice.Overall.PARTIAL, DoctorAdvice.of(rows, setOf("OK"), false, false).overall)
    }

    @Test fun workerQuotaIsNotCalledABrokenConfig() {
        val a = DoctorAdvice.of(listOf(terminal("ST3", "ws(500)")), emptySet(), true, false)
        assertTrue(DoctorAdvice.Finding.CONFIG_QUOTA in a.findings)
        assertFalse(DoctorAdvice.Finding.CONFIG_PROXY in a.findings)
        val wrongPath = DoctorAdvice.of(listOf(terminal("ST3", "ws(404)")), emptySet(), true, false)
        assertTrue(DoctorAdvice.Finding.CONFIG_WS in wrongPath.findings)
    }

    @Test fun deadEgressIsNotADeadConfig() {
        val a = DoctorAdvice.of(listOf(terminal("ST3", "egress", mapOf("proxy_ack" to 1L))), emptySet(), true, false)
        assertEquals(listOf(DoctorAdvice.Finding.CONFIG_EGRESS), a.findings)
    }

    @Test fun scannerFilteredNameSaysCleanIpCannotHelp() {
        val a = DoctorAdvice.of(listOf(terminal("ST4", "scan.tls")), setOf("SNI_BLOCK"), true, false)
        assertTrue(DoctorAdvice.Finding.SCAN_TLS in a.findings)
        // A filtered name on its own is an observation about the line, not a problem.
        assertTrue(DoctorAdvice.Finding.SNI_BLOCK in a.notes)
        assertFalse(DoctorAdvice.Finding.SNI_BLOCK in a.findings)
    }

    /**
     * The phone runs of 2026-10-01: account, panel and the first config all worked, some
     * Cloudflare ranges and UDP were silent. That line is fine for this user -- v1 called it
     * broken.
     */
    @Test fun workingServicesWithPartialLineBlocksIsAllGood() {
        val rows = listOf(
            terminal("ST1", "ok").copy(route = "real.acc1"),
            terminal("ST2", "ok").copy(route = "bpb"),
            terminal("ST3", "ok").copy(route = "config1:vless-ws-tls-443-cf"),
            terminal("ST4", "ok").copy(route = "vless-ws-tls-443-cf"),
        )
        val a = DoctorAdvice.of(rows, setOf("IP_BLOCK_PARTIAL", "UDP_SUSPECT"), true, true)
        assertEquals(DoctorAdvice.Overall.ALL_GOOD, a.overall)
        assertTrue(a.findings.isEmpty())
        assertEquals(2, a.notes.size)
    }

    @Test fun aConfigThatConnectsOnItsRetryIsAWorkingConfig() {
        val rows = listOf(
            terminal("ST3", "tls").copy(route = "config1:x"),
            terminal("ST3", "ok").copy(route = "config1:x"),
        )
        assertEquals(Verdict.PASS, StageVerdict.chain(rows, "ST3").verdict)
    }

    @Test fun oneDeadConfigBesideAWorkingOneIsNotABrokenLine() {
        val rows = listOf(terminal("ST3", "ok").copy(route = "config1:x"), terminal("ST3", "ws").copy(route = "config2:x"))
        val a = DoctorAdvice.of(rows, emptySet(), true, false)
        assertEquals(Verdict.DEGRADED, a.stages[2].verdict)
        assertEquals(listOf(DoctorAdvice.Finding.CONFIG_SOME_FAILED), a.findings)
    }

    @Test fun aStageCutByItsDeadlineSaysSoInsteadOfNothingToCheck() {
        val r = StageVerdict.chain(listOf(terminal("ST4", "unfinished").copy(route = "stage")), "ST4")
        assertEquals(Verdict.SKIPPED, r.verdict)
        assertEquals("unfinished", r.layer)
    }

    @Test fun oneRejectedAccountBesideAWorkingOne() {
        val rows = listOf(
            terminal("ST1", "ok").copy(route = "real.acc1"),
            terminal("ST1", "cf.auth").copy(route = "real.acc2"),
        )
        val a = DoctorAdvice.of(rows, emptySet(), true, true)
        assertEquals(Verdict.DEGRADED, a.stages[0].verdict)
        assertEquals(listOf(DoctorAdvice.Finding.ACCOUNT_SOME_FAILED), a.findings)
    }

    @Test fun cloudflare400WithItsOwnCodeIsTheCredential() {
        val st1 = AccountConclusion.of("real.acc2", AccountConclusion.Check(400, -1, "6003: Invalid request headers", false, null, "", "bearer"), emptyList(), 1)
        assertEquals("cf.auth", st1.code)
    }

    @Test fun fragmentCountsAsWorkingOnlyWhereThePlainHandshakeFailed() {
        val rows = listOf(Evidence("F3", "ip", "ok", 200))
        assertFalse(DoctorAdvice.Works.FRAGMENT in DoctorAdvice.of(rows, emptySet(), true, false).works)
        assertTrue(DoctorAdvice.Works.FRAGMENT in DoctorAdvice.of(rows, setOf("SNI_BLOCK"), true, false).works)
    }

    @Test fun configVariantsAreOnlyAdviceWhenTheConfigFailed() {
        val variant = Evidence("C3", "config1:C3.F12", "ok")
        assertTrue(DoctorAdvice.Works.CONFIG_FRAGMENT in DoctorAdvice.of(listOf(terminal("ST3", "tls"), variant), emptySet(), true, false).works)
        assertFalse(DoctorAdvice.Works.CONFIG_FRAGMENT in DoctorAdvice.of(listOf(terminal("ST3", "ok"), variant), emptySet(), true, false).works)
    }

    @Test fun skippedStagesProduceNoFinding() {
        val a = DoctorAdvice.of(emptyList(), setOf("OK"), true, false)
        assertTrue(a.findings.isEmpty())
        assertEquals(Verdict.SKIPPED, a.stages[0].verdict)
        assertEquals("scan.config.none", a.stages[3].layer)
    }

    @Test fun accountRouteOnlyFindingWhenAnotherRouteWorks() {
        val real = terminal("ST1", "net.tls").copy(route = "real.acc1")
        val doh = terminal("ST1", "ok").copy(route = "doh.acc1")
        val a = DoctorAdvice.of(listOf(real, doh), emptySet(), true, true)
        assertEquals(listOf(DoctorAdvice.Finding.ACCOUNT_ROUTE_ONLY), a.findings)
    }
}
