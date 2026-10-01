package com.mlmvpn.scanner.engines.cfdoctor

import org.junit.Assert.*
import org.junit.Test

class DoctorCoreTest {
    @Test fun privateEvidenceCannotEchoResponseOrExceptionSecrets() {
        val secret = "private-uuid-token@example.net/private-sub-path"
        val sample = Evidence.http("A1", "real:subdomain", 403, mapOf("server" to secret, "cf-ray" to "$secret-THR"),
            "{\"errors\":[{\"code\":9109,\"message\":\"$secret\"}]}", privateEndpoint = true)
        val st1 = AccountConclusion.of("real.acc1", AccountConclusion.Check(200, 403, "9109: $secret", false, null, "", "bearer"), listOf(sample), 10)
        val report = CfDoctorReport.render("1", listOf(sample, st1), emptySet(), emptyList(), true)
        assertFalse(report.contains(secret))
        assertFalse(report.contains("private-sub-path"))
        assertTrue(report.contains("9109"))
        assertEquals("cf.auth", StageVerdict.account(listOf(st1), true).layer)
    }

    private fun check(tok: Int, sub: Int, errors: String = "", hasSub: Boolean = true, verified: Boolean? = null, transport: String = "") =
        AccountConclusion.Check(tok, sub, errors, hasSub, verified, transport, "bearer")

    @Test fun transportFailuresNeverBecomeBadCredentials() {
        val dead = listOf(Evidence("A1", "real:verify", "tls_timeout"))
        val real = AccountConclusion.of("real.acc1", check(-1, -1, transport = "SSLException"), dead, 10)
        assertEquals("net.tls", real.code)
        assertEquals(Verdict.FAIL, StageVerdict.account(listOf(real), true).verdict)
        // The real route dies on the network, the DoH address works: the account is fine.
        val doh = AccountConclusion.of("doh.acc1", check(200, 200), emptyList(), 10)
        val r = StageVerdict.account(listOf(real, doh), true)
        assertEquals(Verdict.DEGRADED, r.verdict)
        assertEquals("net.tls", r.layer)
        assertEquals("cf.ratelimit", AccountConclusion.of("real.acc1", check(429, -1), emptyList(), 1).code)
        assertEquals(Verdict.SKIPPED, StageVerdict.account(emptyList(), false).verdict)
    }

    @Test fun aWrongFirstGuessOfTheAuthSchemeIsNotARejection() {
        // CloudManager tries Bearer on a Global Key first; the 403 it gets is on the wire (A1),
        // but the check itself succeeded -- that is what ST1 must say.
        val wire = listOf(Evidence("A1", "real:verify", "http_status", 403, errors = listOf(10000)), Evidence("A1", "real:user", "ok", 200))
        val st1 = AccountConclusion.of("real.acc1", check(-1, 200), wire, 10)
        assertEquals("ok", st1.code)
        assertEquals(Verdict.PASS, StageVerdict.account(wire + st1, true).verdict)
    }

    @Test fun accountSetupProblemsAreNotNetworkProblems() {
        assertEquals("cf.account", AccountConclusion.of("real", check(200, 200, hasSub = false), emptyList(), 1).code)
        assertEquals("cf.account", AccountConclusion.of("real", check(200, 200, verified = false), emptyList(), 1).code)
        assertEquals("cf.auth", AccountConclusion.of("real", check(401, -1, "10000: x"), emptyList(), 1).code)
        assertEquals("net.http", AccountConclusion.of("real", null, emptyList(), 1).code)
    }

    @Test fun publicApiDoesNotProveAuthentication() {
        assertEquals(Verdict.SKIPPED, StageVerdict.account(listOf(Evidence("X1", "system", code = "ok", http = 200)), false).verdict)
        // Wire rows alone never make a verdict either: only the check's conclusion does.
        assertEquals(Verdict.SKIPPED, StageVerdict.account(listOf(Evidence("A1", "real:verify", "ok", 200)), true).verdict)
    }

    @Test fun partialSubscriptionAndBadEncodingRemainDistinct() {
        assertEquals("sub.status(404)", PanelChain.inspect(404, "", { true }).layer)
        assertEquals("sub.empty", PanelChain.inspect(200, "", { true }).layer)
        assertEquals("sub.format", PanelChain.inspect(200, "%%%broken", { true }).layer)
        val body = java.util.Base64.getEncoder().encodeToString("vless://good\ntrojan://bad".toByteArray())
        assertEquals("parse.partial", PanelChain.inspect(200, body, { it.endsWith("good") }).layer)
        assertEquals("parse.zero", PanelChain.inspect(200, body, { false }).layer)
    }

    @Test fun triageDoesNotGuessInternationalOutageFromMissingControls() {
        assertFalse(TriageClassifier.classify(emptyList()).contains("NO_INTERNATIONAL"))
        val rows = listOf(Evidence("T6", "domestic", code = "ok"), Evidence("T6", "foreign", code = "tcp_timeout"))
        assertFalse(TriageClassifier.classify(rows).contains("NO_INTERNATIONAL"))
        assertTrue(TriageClassifier.classify(rows + rows[0] + rows[1] + rows[1]).contains("NO_INTERNATIONAL"))
        assertFalse(TriageClassifier.classify(rows + Evidence("X1", "system", code = "ok", http = 200)).contains("NO_INTERNATIONAL"))
    }

    @Test fun bogonsIncludeCarrierNatAndIpv6LocalSpace() {
        listOf("10.10.34.1", "127.0.0.1", "100.64.0.1", "169.254.1.2", "::1", "fc00::1", "fe80::1", "2001:4188:2:600::1").forEach { assertTrue(it, CfBogon.isBogon(it)) }
        assertFalse(CfBogon.isBogon("104.16.0.1"))
        assertFalse(CfBogon.isBogon("2606:4700::1111"))
    }

    @Test fun signedPlansStillCannotChooseArbitraryDestinationsOrUnboundedWork() {
        assertThrows(IllegalArgumentException::class.java) { DoctorPlan.parse("""{"version":"1","ips":["127.0.0.1"]}""") }
        assertThrows(IllegalArgumentException::class.java) { DoctorPlan.parse("""{"version":"1","hosts":["evil.example"]}""") }
        val p = DoctorPlan.parse("""{"version":"1","maxAttempts":99999,"maxMs":9999999}""")
        assertTrue(p.maxAttempts <= 900)
        assertTrue(p.maxMs <= 480000)
        assertTrue(TierPlanner.methods(setOf("DNS_POISONED"), false, p).contains("D3"))
        assertFalse(TierPlanner.methods(setOf("DNS_POISONED"), false, p).contains("X3"))
    }

    @Test fun dnsRejectsWrongTransactionAndCompressionLoops() {
        val query = DnsWire.query("api.cloudflare.com", 1, 123)
        assertEquals(123, ((query[0].toInt() and 255) shl 8) or (query[1].toInt() and 255))
        assertThrows(IllegalArgumentException::class.java) { DnsWire.parse(query, 124) }
        val loop = byteArrayOf(0,123,0x81.toByte(),0x80.toByte(),0,1,0,0,0,0,0,0,0xc0.toByte(),12,0,1,0,1)
        assertThrows(IllegalArgumentException::class.java) { DnsWire.parse(loop, 123) }
    }

    @Test fun reportKeepsEveryAttemptAndRemainsBounded() {
        val rows = (1..900).map { Evidence("T1", "system", code = "tcp_timeout", elapsedMs = it.toLong()) }
        val text = CfDoctorReport.render("1", rows, setOf("IP_BLOCK_ALL"), listOf("tier1:ip_block"), false)
        assertTrue(text.toByteArray().size <= 150 * 1024)
        assertEquals(900, text.lineSequence().count { it.startsWith("{\"id\"") })
        assertTrue(text.contains("complete=false"))
    }
    @Test fun verboseReportsUseCompactRowsWithoutDroppingAttempts() {
        val rows=(1..900).map { Evidence("ST3","config3:C9.cloudflare","proxy_or_egress",elapsedMs=20000,atMs=480000,
            errors=listOf(10000,9109,6003),numbers=mapOf("terminal" to 1,"dns_ms" to 3000,"tcp_ms" to 4000,"tls_ms" to 6000,"http_ms" to 5000,"tls" to 1,"bytes" to 12000)) }
        val text=CfDoctorReport.render("1.0.0",rows,emptySet(),emptyList(),false)
        assertTrue(text.toByteArray().size<=150*1024)
        assertEquals(900,text.lineSequence().count { it.startsWith("{\"id\"") })
    }
    @Test fun authFailuresDoNotDisappearBehindSuccessfulTokenVerification() {
        // A live token without Workers permission: verify says 200, the subdomain call 403/9109.
        val st1 = AccountConclusion.of("real.acc1", check(200, 403, "9109: no permission", hasSub = false), emptyList(), 1)
        assertEquals("cf.auth", StageVerdict.account(listOf(st1), true).layer)
        assertNotEquals(Verdict.PASS, StageVerdict.account(listOf(st1), true).verdict)
    }
}
