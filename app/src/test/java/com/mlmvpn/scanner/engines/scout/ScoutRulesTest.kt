package com.mlmvpn.scanner.engines.scout

import org.junit.Assert.*
import org.junit.Test

class ScoutRulesTest {
    @Test fun `failed speed samples stay unknown after saving and do not masquerade as zero speed`() {
        val restored = Numbers.from(Numbers(speedAttempted = true).toJson())
        assertNull(restored.mbps)
        assertTrue(restored.speedAttempted)
        val exit = VariantResult("country", "Country", status = ScoutStatus.WORKS, numbers = restored)
        assertTrue(ScoutRules.speedCandidates(listOf(MethodResult("geph", variants = listOf(exit)))).isEmpty())
    }
    @org.junit.Test fun `every connected country gets a speed test even after another country was measured`() {
        val countries = (1..12).map { VariantResult("c$it", "Country $it", status = ScoutStatus.WORKS,
            numbers = if (it == 1) Numbers(mbps = 2.0) else Numbers()) }
        val result = ScoutRules.speedCandidates(listOf(MethodResult("warp", variants = countries)))
        org.junit.Assert.assertEquals(11, result.size)
        org.junit.Assert.assertFalse(result.any { it.second.id == "c1" })
    }
    @org.junit.Test fun `direct WARP can work from Iran while foreign exit requirements remain explicit`() {
        val numbers = Numbers(exitCountry = "IR", filtered = Tri.YES)
        org.junit.Assert.assertNull(ScoutRules.weakness(MethodKind.TUNNEL, numbers, requireForeignExit = false))
        org.junit.Assert.assertEquals(ScoutRules.Weak.IRAN_EXIT, ScoutRules.weakness(MethodKind.TUNNEL, numbers))
    }
    @org.junit.Test fun `fastest healthy country wins over the lowest latency country`() {
        val lowPing = VariantResult("a", "A", status = ScoutStatus.WORKS, numbers = Numbers(latencyMs = 20, mbps = 1.0))
        val fast = VariantResult("b", "B", status = ScoutStatus.WORKS, numbers = Numbers(latencyMs = 80, mbps = 12.0))
        org.junit.Assert.assertEquals("b", ScoutRules.representative(listOf(lowPing, fast))?.id)
    }
    @org.junit.Test fun `automatic setup is deferred to full pass and completed variants are not repeated`() {
        val cold = Variant("geph", "Geph", quick = false)
        org.junit.Assert.assertTrue(ScoutRules.pendingVariants(listOf(cold), emptySet(), true).isEmpty())
        org.junit.Assert.assertEquals(listOf(cold), ScoutRules.pendingVariants(listOf(cold), emptySet(), false))
        org.junit.Assert.assertTrue(ScoutRules.pendingVariants(listOf(cold), setOf("geph"), false).isEmpty())
    }
    private fun result(s: ScoutStatus) = VariantResult("v", "Variant", status = s)
    @Test fun `a failed first variant is not a failed method while work remains`() {
        assertEquals(ScoutStatus.WAITING, ScoutRules.aggregate(listOf(result(ScoutStatus.FAILED)), 1))
        assertEquals(ScoutStatus.WORKS, ScoutRules.aggregate(listOf(result(ScoutStatus.FAILED), result(ScoutStatus.WORKS)), 1))
    }
    @Test fun `UDP silence is not proof when TCP is also down`() {
        val probes = listOf(ScoutRules.UdpProbe("a", 53, 2, 0), ScoutRules.UdpProbe("a", 443, 2, 0), ScoutRules.UdpProbe("b", 3478, 2, 0))
        assertEquals(Tri.UNKNOWN, ScoutRules.judgeUdp(probes, false).udp)
        assertEquals(Tri.NO, ScoutRules.judgeUdp(probes, true).udp)
        assertEquals(Tri.UNKNOWN, ScoutRules.judgeUdp(probes.take(2), true).udp)
        assertEquals(Tri.UNKNOWN, ScoutRules.judgeUdp(List(3) { probes.first() }, true).udp)
        assertEquals(Tri.YES, ScoutRules.judgeUdp(listOf(probes.first().copy(valid = 1)), false).udp)
    }
    @Test fun `quality thresholds reject fast but unreliable connections`() {
        assertEquals(ScoutRules.Weak.UNSTABLE, ScoutRules.weakness(MethodKind.TUNNEL, Numbers(stabilityOk = 7, stabilityTotal = 10, mbps = 50.0)))
        assertNull(ScoutRules.weakness(MethodKind.TUNNEL, Numbers(stabilityOk = 8, stabilityTotal = 10, mbps = 0.3, latencyMs = 3000)))
        assertEquals(ScoutRules.Weak.IRAN_EXIT, ScoutRules.weakness(MethodKind.TUNNEL, Numbers(exitCountry = "ir")))
    }
    @Test fun `missing metrics survive JSON without becoming zero`() {
        val loaded = Numbers.from(Numbers().toJson())
        assertNull(loaded.mbps)
        assertNull(loaded.latencyMs)
        assertNull(loaded.stabilityRate)
    }
}
