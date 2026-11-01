package com.mlmvpn.scanner.engines.cfnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The two Iranian firewall families (2026-10): MCI-type lines pass Cloudflare with ECH on IPv4 (or
 * Chrome on IPv6), Irancell-type lines only with F&F. What a network proved decides which family's
 * handshakes are tried first on it.
 */
class FirewallFamilyTest {
    private val registry = StrategyRegistry.default()
    private val profile = NetworkProfile("line", hasV6 = true)
    private val now = 5_000_000L
    private fun store() = StrategyStore(Files.createTempDirectory("firewall").resolve("strategies.json").toFile())
    private fun s(id: String) = registry.byId(id)!!

    @Test fun `each headline handshake belongs to its family`() {
        assertEquals(FirewallFamily.ECH, FirewallFamily.of(s("dat_ech_v4")))
        assertEquals(FirewallFamily.ECH, FirewallFamily.of(s("dat_clean_ech")))
        assertEquals(FirewallFamily.ECH, FirewallFamily.of(s("dat_chrome_v6")))      // MCI-type's IPv6 way
        assertEquals(FirewallFamily.ECH, FirewallFamily.of(s("dat_fragchrome_v6")))  // ... with F&F's mask
        assertEquals(FirewallFamily.FF, FirewallFamily.of(s("dat_fragfp_v4")))
        assertEquals(FirewallFamily.FF, FirewallFamily.of(s("dat_port2053_fragfp")))
        assertEquals(FirewallFamily.OPEN, FirewallFamily.of(s("dat_asis_v4")))
        assertEquals(FirewallFamily.OPEN, FirewallFamily.of(s("dat_chrome_v4")))
        assertNull(FirewallFamily.of(s("ctl_sys_plain")))                             // CONTROL routes say nothing
    }

    @Test fun `the family a network proved is its most recent data success`() {
        val store = store()
        assertNull(store.family(profile, registry::byId))
        store.record(profile, CfStage.SCAN, "dat_fragfp_v4", false, 0, FailureClass.TLS_FAILURE, now)
        assertNull(store.family(profile, registry::byId))                              // a failure proves nothing
        store.record(profile, CfStage.SCAN, "dat_ech_v4", true, 120, null, now + 1)
        assertEquals(FirewallFamily.ECH, store.family(profile, registry::byId))
        // CONTROL evidence never decides it.
        store.record(profile, CfStage.ACCOUNT, "ctl_wroute_plain", true, 90, null, now + 2)
        assertEquals(FirewallFamily.ECH, store.family(profile, registry::byId))
        // Another network's evidence stays there.
        assertNull(store.family(profile.copy(key = "other"), registry::byId))
    }

    @Test fun `the network's family is searched first, in its own order`() {
        val store = store()
        val candidates = registry.forStage(CfStage.CONNECT, EngineContext(hasV6 = false))
        val cold = store.rank(profile, CfStage.CONNECT, candidates, now)
        assertEquals(FirewallFamily.FF, FirewallFamily.of(cold.first()))               // the priors favour F&F
        val mci = store.rank(profile, CfStage.CONNECT, candidates, now, FirewallFamily.ECH)
        assertEquals(FirewallFamily.ECH, FirewallFamily.of(mci.first()))
        val echCount = candidates.count { FirewallFamily.of(it) == FirewallFamily.ECH }
        assertTrue(mci.take(echCount).all { FirewallFamily.of(it) == FirewallFamily.ECH })
        val irancell = store.rank(profile, CfStage.CONNECT, candidates, now, FirewallFamily.FF)
        assertEquals("dat_fragfp_v4", irancell.first().id)
    }

    @Test fun `the summary's three ways come in the family's own order, each a real strategy`() {
        assertEquals(listOf("dat_ech_v4", "dat_chrome_v6", "dat_fragfp_v4"), FirewallFamily.headline(FirewallFamily.ECH))
        assertEquals(listOf("dat_fragfp_v4", "dat_ech_v4", "dat_chrome_v6"), FirewallFamily.headline(FirewallFamily.FF))
        assertEquals(FirewallFamily.headline(FirewallFamily.ECH), FirewallFamily.headline(null))
        // Every id names a strategy that a SCAN and a CONNECT can run.
        for (id in FirewallFamily.headline(null)) {
            assertTrue(id, CfStage.SCAN in s(id).stages && CfStage.CONNECT in s(id).stages)
        }
        // The summary's example edges are literal addresses of their own family.
        assertEquals(Family.V4, CfShape.literalFamily(FirewallFamily.EDGE_V4))
        assertEquals(Family.V6, CfShape.literalFamily(FirewallFamily.EDGE_V6))
    }

    @Test fun `a scan proved on one stage leads the other stage's search`() = kotlinx.coroutines.runBlocking {
        val store = store()
        store.record(profile, CfStage.SCAN, "dat_ech_v4", true, 120, null, now)
        val tried = mutableListOf<String>()
        val engine = StrategyEngine(registry, store, { st, _, _ -> tried += st.id; ValidationResult.fail(FailureClass.TLS_FAILURE) }, now = { now + 10 })
        engine.best(CfStage.CONNECT, profile, EngineContext(hasV6 = false), StrategyEngine.StageBudget(maxAttempts = 3, parallel = 1),
            family = store.family(profile, registry::byId))
        assertEquals(FirewallFamily.ECH, FirewallFamily.of(registry.byId(tried.first())!!))
    }
}
