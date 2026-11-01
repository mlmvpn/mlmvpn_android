package com.mlmvpn.scanner.engines.scout

import org.junit.Assert.*
import org.junit.Test

class ScoutScoreTest {
    @Test fun `stable 42 Mbps ranks above unstable 50 Mbps`() {
        val ranked = ScoutScore.score(listOf(
            ScoutScore.Input("fast", latencyMs = 100, mbps = 50.0, stability = 0.7, connectMs = 500, famFit = 1.0),
            ScoutScore.Input("stable", latencyMs = 100, mbps = 42.0, stability = 1.0, connectMs = 500, famFit = 1.0)))
        assertEquals("stable", ranked.first().id)
        assertEquals(96.0, ranked.first().total, 1.0)
        assertEquals(81.0, ranked.last().total, 1.0)
    }
    @Test fun `best tunnel excludes weak unfilter and handshake only methods`() {
        val methods = listOf("weak", "unfilter", "handshake", "tunnel").map { id ->
            val status = if (id == "weak") ScoutStatus.WEAK else ScoutStatus.WORKS
            MethodResult(id, status, variants = listOf(VariantResult("v", "", status = status, numbers = Numbers(latencyMs = if (id == "tunnel") 300 else 10))))
        }
        val s = ScoutSummary.build(methods, { id -> ScoutSummary.Info(
            if (id == "unfilter") MethodKind.UNFILTER else MethodKind.TUNNEL,
            if (id == "handshake") Evidence.HANDSHAKE else Evidence.DATA) }, true, false)
        assertEquals("tunnel", s.best)
        assertEquals("unfilter", s.unfilterBest)
        assertNull(s.fastest)
    }
}
