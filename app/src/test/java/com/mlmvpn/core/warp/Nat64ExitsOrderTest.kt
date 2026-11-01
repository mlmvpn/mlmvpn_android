package com.mlmvpn.core.warp

import org.junit.Assert.assertEquals
import org.junit.Test

class Nat64ExitsOrderTest {

    private val now = 1_800_000_000_000L

    private fun gw(prefix: String, cc: String) = Nat64Exits.Gateway(prefix, cc, "", "test", Nat64Exits.Source.BUILT_IN)
    private fun ok(rtt: Long, mbps: Double?, ago: Long = 60_000) = Nat64Exits.Probe(ok = true, rttMs = rtt, mbps = mbps, at = now - ago)
    private val dead = Nat64Exits.Probe(ok = false, at = now - 60_000)

    private val london = gw("2a00:1098:2c:0:0:5::", "GB")
    private val frankfurt = gw("2001:67c:2960:6464::", "DE")
    private val helsinki = gw("2a01:4f9:c010:3f02:64:0::", "FI")
    private val amsterdam = gw("2a00:1098:2b:0:0:1::", "NL")
    private val amsterdam2 = gw("2a02:898:146:64::", "NL")
    private val amsterdam3 = gw("2a00:1098:2b::", "NL")

    @Test fun `a gateway that carried the download goes before one that only answered the trace`() {
        // The ping on the test phone, 2026-10-20: London answered fastest but moved no download.
        val probes = mapOf(london.key to ok(220, null), frankfurt.key to ok(328, 7.4), helsinki.key to ok(363, 5.4))
        val order = Nat64Exits.order(listOf(london, helsinki, frankfurt), probes, { 0 }, now)
        assertEquals(listOf(frankfurt, helsinki, london), order)
    }

    @Test fun `a failed connect is one step down, not the back of the line`() {
        // Amsterdam answered the ping and then went quiet once: still a better try than a gateway
        // the same ping found dead, and than one nobody measured.
        val probes = mapOf(amsterdam.key to ok(485, 0.8), amsterdam2.key to dead)
        val once = Nat64Exits.order(listOf(amsterdam2, amsterdam3, amsterdam), probes, { if (it == amsterdam) 1 else 0 }, now)
        assertEquals(listOf(amsterdam, amsterdam3, amsterdam2), once)
        // Failed three times it has used up its ping: the unmeasured one goes first.
        val thrice = Nat64Exits.order(listOf(amsterdam2, amsterdam3, amsterdam), probes, { if (it == amsterdam) 3 else 0 }, now)
        assertEquals(listOf(amsterdam3, amsterdam, amsterdam2), thrice)
    }

    @Test fun `an old ping counts as no ping`() {
        val probes = mapOf(london.key to ok(220, 9.0, ago = 7 * 3600_000L), frankfurt.key to ok(328, null))
        assertEquals(2, Nat64Exits.evidence(probes[london.key], now))
        assertEquals(1, Nat64Exits.evidence(probes[frankfurt.key], now))
        assertEquals(listOf(frankfurt, london), Nat64Exits.order(listOf(london, frankfurt), probes, { 0 }, now))
    }

    @Test fun `the country list shows the ping that says most`() {
        val pings = listOf(ok(220, null), ok(400, 3.0), ok(300, 6.0))
        assertEquals(ok(300, 6.0), pings.minWithOrNull(Nat64Exits.BETTER_PROBE))
    }
}
