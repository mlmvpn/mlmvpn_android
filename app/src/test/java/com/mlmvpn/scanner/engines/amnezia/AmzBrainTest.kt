package com.mlmvpn.scanner.engines.amnezia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmzBrainTest {

    private val now = 1_700_000_000_000L
    private val net = "wifi:home"

    private fun server(i: Int, cc: String? = null, src: String = "src", user: Boolean = false, warp: Boolean = false) = AmzServer(
        id = "s$i", kind = AmzKind.HY2, raw = "hysteria2://x@1.2.3.$i:443", name = "n$i", host = "1.2.3.$i", port = 443,
        country = cc, countryFrom = if (cc != null) CountryFrom.LABEL else CountryFrom.NONE,
        sourceId = if (user) AmzServer.USER_SOURCE else src, addedAt = now, warp = warp,
    )

    private fun state(vararg s: AmzServer) = AmzBrain.merge(AmzState(), s.toList(), now, net)

    @Test fun `three failures in a row remove a public server, never a user's`() {
        var s = state(server(1), server(2, user = true))
        repeat(3) { s = AmzBrain.record(s, net, listOf(AmzBrain.Result("s1", null), AmzBrain.Result("s2", null)), now + it) }
        assertFalse(s.servers.any { it.id == "s1" })
        assertTrue(s.servers.any { it.id == "s2" })
        assertTrue("s1" in s.tombstones)
    }

    @Test fun `a success resets the streak`() {
        var s = state(server(1))
        s = AmzBrain.record(s, net, listOf(AmzBrain.Result("s1", null)), now)
        s = AmzBrain.record(s, net, listOf(AmzBrain.Result("s1", null)), now)
        s = AmzBrain.record(s, net, listOf(AmzBrain.Result("s1", 200)), now)
        s = AmzBrain.record(s, net, listOf(AmzBrain.Result("s1", null)), now)
        assertTrue(s.servers.any { it.id == "s1" })
        assertEquals(1, s.statsFor(net)["s1"]!!.streak)
    }

    @Test fun `offline run learns nothing and removes nothing`() {
        var s = state(server(1))
        repeat(5) { s = AmzBrain.record(s, net, listOf(AmzBrain.Result("s1", null)), now, offline = true) }
        assertTrue(s.servers.any { it.id == "s1" })
        assertTrue(s.statsFor(net).isEmpty())
    }

    @Test fun `auto removal can be turned off`() {
        var s = state(server(1)).copy(prefs = AmzPrefs(autoRemoveDead = false))
        repeat(4) { s = AmzBrain.record(s, net, listOf(AmzBrain.Result("s1", null)), now) }
        assertTrue(s.servers.any { it.id == "s1" })
        assertEquals(listOf("s1"), AmzBrain.deadIds(s, net))
    }

    @Test fun `a removed server does not come back on the next refresh`() {
        var s = state(server(1))
        s = AmzBrain.remove(s, listOf("s1"), now)
        s = AmzBrain.merge(s, listOf(server(1)), now + 1000, net)
        assertTrue(s.servers.isEmpty())
        // ...but it does after a week.
        s = AmzBrain.merge(s, listOf(server(1)), now + 8L * 24 * 3_600_000, net)
        assertEquals(1, s.servers.size)
    }

    @Test fun `results are per network`() {
        var s = state(server(1))
        s = AmzBrain.record(s, "mci", listOf(AmzBrain.Result("s1", 150)), now)
        assertTrue(s.statsFor("mci")["s1"]!!.alive)
        assertTrue(s.statsFor("irancell")["s1"] == null)
    }

    @Test fun `country moves to the measured one, and a weaker guess cannot move it back`() {
        var s = state(server(1, cc = "US"))
        s = AmzBrain.record(s, net, listOf(AmzBrain.Result("s1", 150, "DE")), now)
        assertEquals("DE", s.servers.single().country)
        assertEquals(CountryFrom.PROBE, s.servers.single().countryFrom)
        s = AmzBrain.merge(s, listOf(server(1, cc = "US")), now, net)
        assertEquals("DE", s.servers.single().country)
        s = AmzBrain.exitCountry(s, "s1", "NL")
        assertEquals("NL", s.servers.single().country)
        assertEquals(CountryFrom.EXIT, s.servers.single().countryFrom)
    }

    @Test fun `warp keeps its own group whatever the trace says`() {
        var s = state(server(1, warp = true))
        s = AmzBrain.record(s, net, listOf(AmzBrain.Result("s1", 300, "IR")), now)
        s = AmzBrain.exitCountry(s, "s1", "IR")
        assertEquals(AmzBrain.WARP, AmzBrain.group(s.servers.single()))
        assertEquals("s1", AmzBrain.best(s, net, AmzBrain.WARP)!!.id)
    }

    @Test fun `with nothing tested, connect still has a promising server`() {
        val s = state(server(1), server(2, user = true))
        assertEquals("s2", AmzBrain.promising(s, net, null, emptySet(), now)!!.id)
        assertEquals("s1", AmzBrain.promising(s, net, null, setOf("s2"), now)!!.id)
    }

    @Test fun `an unknown server gets its country once measured`() {
        var s = state(server(1))
        assertEquals(null, s.servers.single().country)
        s = AmzBrain.exitCountry(s, "s1", "FI")
        assertEquals("FI", s.servers.single().country)
    }

    @Test fun `rows sort working fastest first, then untested, then dead`() {
        var s = state(server(1), server(2), server(3), server(4))
        s = AmzBrain.record(s, net, listOf(
            AmzBrain.Result("s1", 900), AmzBrain.Result("s2", 120), AmzBrain.Result("s3", null),
        ), now)
        val rows = AmzBrain.sortRows(s.servers.map { AmzBrain.Row(it, s.statsFor(net)[it.id]) }, s, net, now)
        assertEquals(listOf("s2", "s1", "s4", "s3"), rows.map { it.server.id })
    }

    @Test fun `best test puts known winners first and explores good lists`() {
        val servers = (1..300).map { server(it, src = if (it % 2 == 0) "good" else "bad") }
        var s = AmzBrain.merge(AmzState(), servers, now, net)
        // The "good" list has worked; the "bad" one has not.
        s = AmzBrain.record(s, net, (2..40 step 2).map { AmzBrain.Result("s$it", 100L + it) } + (1..39 step 2).map { AmzBrain.Result("s$it", null) }, now)
        val picked = AmzBrain.pickForTest(s, net, AmzTestMode.BEST, now)
        assertEquals(AmzBrain.BEST_COUNT, picked.size)
        assertEquals("s2", picked.first().id)
        val untested = picked.filter { s.statsFor(net)[it.id] == null }
        assertTrue(untested.count { it.sourceId == "good" } > untested.count { it.sourceId == "bad" })
    }

    @Test fun `country test covers only that country, unknown included`() {
        val s = state(server(1, "DE"), server(2, "DE"), server(3, "US"), server(4))
        assertEquals(setOf("s1", "s2"), AmzBrain.pickForTest(s, net, AmzTestMode.COUNTRY, now, country = "DE").map { it.id }.toSet())
        assertEquals(listOf("s4"), AmzBrain.pickForTest(s, net, AmzTestMode.COUNTRY, now, country = AmzBrain.UNKNOWN).map { it.id })
    }

    @Test fun `pool is capped and warp accounts do not drown the rest`() {
        val warps = (1..AmzBrain.MAX_WARP + 50).map { server(it, warp = true) }
        val s = AmzBrain.merge(AmzState(), warps + server(9999, user = true), now, net)
        assertEquals(AmzBrain.MAX_WARP, s.servers.count { it.warp })
        assertTrue(s.servers.any { it.user })
    }

    @Test fun `best connect is the fastest working server of the country`() {
        var s = state(server(1, "DE"), server(2, "DE"), server(3, "US"))
        s = AmzBrain.record(s, net, listOf(AmzBrain.Result("s1", 300), AmzBrain.Result("s2", 100), AmzBrain.Result("s3", 50)), now)
        assertEquals("s3", AmzBrain.best(s, net)!!.id)
        assertEquals("s2", AmzBrain.best(s, net, "DE")!!.id)
        assertEquals("s1", AmzBrain.best(s, net, "DE", exclude = setOf("s2"))!!.id)
    }

    @Test fun `country order puts working countries first and unknown last`() {
        var s = state(server(1, "DE"), server(2, "US"), server(3), server(4, "FR"))
        s = AmzBrain.record(s, net, listOf(AmzBrain.Result("s2", 80), AmzBrain.Result("s1", 300)), now)
        assertEquals(listOf("US", "DE", "FR", AmzBrain.UNKNOWN), AmzBrain.countryOrder(s, net).map { it.first })
    }
}
