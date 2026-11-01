package com.mlmvpn.scanner.engines.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineBrainTest {

    private fun server(n: Int, src: String = "barryfar", at: Long = 0) = OutlineServer(
        id = "id$n", uri = "ss://x@203.0.113.$n:443#s$n", name = "s$n", host = "203.0.113.$n", port = 443,
        method = "aes-128-gcm", sourceId = src, addedAt = at,
    )

    @Test fun deletedServersStayOutOfRefreshes() {
        val now = 10_000L
        var st = OutlineBrain.merge(OutlineState(), listOf(server(1), server(2)), now)
        st = OutlineBrain.remove(st, listOf("id1"), now)
        st = OutlineBrain.merge(st, listOf(server(1), server(3)), now + 1)
        assertEquals(setOf("id2", "id3"), st.servers.map { it.id }.toSet())
        // ...until the tombstone expires.
        st = OutlineBrain.merge(st, listOf(server(1)), now + OutlineBrain.TOMBSTONE_MS + 1)
        assertTrue(st.servers.any { it.id == "id1" })
    }

    @Test fun workingFirstFastestFirstFavouritesOnTop() {
        var st = OutlineBrain.merge(OutlineState(), (1..5).map { server(it) }, 0)
        st = OutlineBrain.record(st, "wifi", listOf(
            OutlineBrain.Result("id1", 300), OutlineBrain.Result("id2", 100), OutlineBrain.Result("id3", null),
        ), 1)
        assertEquals(listOf("id2", "id1"), OutlineBrain.sorted(st, "wifi").take(2).map { it.id })
        assertEquals("id3", OutlineBrain.sorted(st, "wifi").last().id)
        st = st.copy(favorites = setOf("id5"))
        assertEquals("id5", OutlineBrain.sorted(st, "wifi").first().id)
        assertEquals("id2", OutlineBrain.best(st, "wifi")!!.id)
        assertNull("results are per network", OutlineBrain.best(st, "mobile"))
    }

    @Test fun deadIdsSpareFavourites() {
        var st = OutlineBrain.merge(OutlineState(), (1..3).map { server(it) }, 0).copy(favorites = setOf("id2"))
        st = OutlineBrain.record(st, "n", listOf(OutlineBrain.Result("id1", null), OutlineBrain.Result("id2", null)), 1)
        assertEquals(listOf("id1"), OutlineBrain.deadIds(st, "n"))
    }

    @Test fun poolHasNoSizeLimit() {
        val many = (1..5_000).map { server(it % 250 + 1, at = it.toLong()).copy(id = "p$it") }
        val mine = server(9999, OutlineServer.USER_SOURCE).copy(id = "mine")
        val st = OutlineBrain.merge(OutlineState(), many + mine, 0)
        assertEquals(5_001, st.servers.size)
        assertTrue(st.servers.any { it.id == "mine" })
    }

    @Test fun aServerItsAnsweringListStoppedCarryingGoesAfterThreeDays() {
        val day = 24 * 3_600_000L
        var st = OutlineBrain.merge(OutlineState(), listOf(server(1), server(2)), 0)
        // Two days on, the list carries only id1: id2 is not stale yet.
        st = OutlineBrain.merge(st, listOf(server(1)), 2 * day)
        assertTrue(st.servers.any { it.id == "id2" })
        // Four days on, its list answered and still does not carry it: gone. id1 was carried: kept.
        st = OutlineBrain.merge(st, listOf(server(1)), 4 * day)
        assertFalse(st.servers.any { it.id == "id2" })
        assertTrue(st.servers.any { it.id == "id1" })
    }

    @Test fun aListThatDidNotAnswerSaysNothingAndWhatWorkedStays() {
        val day = 24 * 3_600_000L
        var st = OutlineBrain.merge(OutlineState(), listOf(server(1, "a"), server(2, "b"), server(3, "a")), 0)
        st = OutlineBrain.record(st, "n", listOf(OutlineBrain.Result("id3", 250)), 1)
        // Ten days on only list «a» answered, carrying id1: id2's list said nothing, id3 once worked.
        st = OutlineBrain.merge(st, listOf(server(1, "a")), 10 * day)
        assertEquals(setOf("id1", "id2", "id3"), st.servers.map { it.id }.toSet())
    }

    @Test fun deadOnEveryNetworkIsDroppedButNotWhatWorkedOrIsMine() {
        var st = OutlineBrain.merge(OutlineState(), (1..3).map { server(it) } + server(9, OutlineServer.USER_SOURCE).copy(id = "mine"), 0)
        repeat(OutlineBrain.DROP_AFTER_FAILS) { i ->
            st = OutlineBrain.record(st, "n", listOf(OutlineBrain.Result("id1", null), OutlineBrain.Result("mine", null)), i + 1L)
        }
        st = OutlineBrain.record(st, "n", listOf(OutlineBrain.Result("id2", 200)), 9)
        st = OutlineBrain.merge(st, emptyList(), 10)
        assertFalse(st.servers.any { it.id == "id1" })
        assertTrue(st.servers.any { it.id == "id2" })
        assertTrue(st.servers.any { it.id == "mine" })
    }

    @Test fun userKeyKeepsItsNameOnMerge() {
        val mine = server(1, OutlineServer.USER_SOURCE).copy(name = "My key")
        var st = OutlineBrain.merge(OutlineState(), listOf(mine), 0)
        st = OutlineBrain.merge(st, listOf(server(1).copy(name = "public name")), 1)
        assertEquals("My key", st.servers.single().name)
        assertTrue(st.servers.single().user)
    }

    @Test fun quickTestCoversFavouritesAndSkipsRepeatFailures() {
        var st = OutlineBrain.merge(OutlineState(), (1..200).map { server(it % 250 + 1).copy(id = "s$it") }, 0)
        st = st.copy(favorites = setOf("s150"))
        st = OutlineBrain.record(st, "n", listOf(OutlineBrain.Result("s1", null)), 1)
        st = OutlineBrain.record(st, "n", listOf(OutlineBrain.Result("s1", null)), 2)
        val picked = OutlineBrain.pickForTest(st, "n", all = false, limit = 40).map { it.id }
        assertTrue("s150" in picked)
        assertFalse("s1" in picked)
        assertEquals(40, picked.size)
    }

    @Test fun codecRoundTrip() {
        var st = OutlineBrain.merge(OutlineState(), listOf(server(1), server(2).copy(prefix = true, country = "DE")), 5)
        st = OutlineBrain.record(st, "wifi", listOf(OutlineBrain.Result("id1", 120, 5_000_000)), 6)
        st = st.copy(favorites = setOf("id2"), lastConnectedId = "id1", scoutWinners = mapOf("wifi" to "id1"),
            prefs = OutlinePrefs(autoFallback = false, disabledSources = setOf("ebrasha"), warned = true))
        val back = OutlineCodec.decode(OutlineCodec.encode(st))
        assertEquals(st, back)
    }

    // ---------------------------------------------------------------- Shadowmere

    @Test fun shadowmereTakesOverAServerAndKeepsItsTag() {
        val gh = server(1)
        val sm = server(1, OutlineSources.SHADOWMERE).copy(name = "shadowmere · Paris, France", uri = "ss://x@203.0.113.1:443#sm", checkedAt = 500, uptime = 90)
        var st = OutlineBrain.merge(OutlineState(), listOf(gh), 0)
        // Shadowmere starts carrying a server a GitHub list brought first; the list order is the engine's (most trusted first).
        st = OutlineBrain.merge(st, listOf(sm, gh.copy(name = "renamed by github")), 10)
        val s = st.servers.single()
        assertTrue(s.shadowmere)
        assertEquals("shadowmere · Paris, France", s.name)
        assertEquals("ss://x@203.0.113.1:443#sm", s.uri)
        assertEquals(500L, s.checkedAt)
        assertEquals(90, s.uptime)
        // Only GitHub carries it in a later refresh: the tag and the check time stay.
        st = OutlineBrain.merge(st, listOf(gh.copy(name = "again")), 20)
        assertEquals("shadowmere · Paris, France", st.servers.single().name)
        assertEquals(500L, st.servers.single().checkedAt)
        assertEquals(20L, st.servers.single().seenAt)
    }

    @Test fun quickTestCoversEveryKeyShadowmereListsNow() {
        val sm = (1..120).map { server(it, OutlineSources.SHADOWMERE).copy(id = "m$it") }
        val gh = (1..50).map { server(it).copy(id = "g$it") }
        var st = OutlineBrain.merge(OutlineState(), sm + gh, 1_000).copy(lastRefreshAt = 1_000)
        val picked = OutlineBrain.pickForTest(st, "n", all = false, limit = 80).map { it.id }
        assertEquals(120, picked.count { it.startsWith("m") })
        // Not carried for hours (Shadowmere dropped them, or could not be reached): back to the usual 80.
        st = st.copy(lastRefreshAt = 1_000 + 3 * 3_600_000L)
        assertEquals(80, OutlineBrain.pickForTest(st, "n", all = false, limit = 80).size)
    }

    @Test fun codecKeepsShadowmereCheckTimes() {
        val st = OutlineBrain.merge(OutlineState(), listOf(server(1, OutlineSources.SHADOWMERE).copy(checkedAt = 123, uptime = 0), server(2)), 5)
        assertEquals(st, OutlineCodec.decode(OutlineCodec.encode(st)))
    }

    @Test fun shadowmereIsDueEveryHalfHour() {
        val src = OutlineSources.byId(OutlineSources.SHADOWMERE)!!
        val m = 60_000L
        val base = 1_000_000 * m
        assertFalse(OutlineSources.due(src, base + 20 * m, lastOk = base, lastTry = base))
        assertTrue(OutlineSources.due(src, base + 31 * m, lastOk = base, lastTry = base))
        // Unreachable: asked again ten minutes after the last try, not on every look.
        assertFalse(OutlineSources.due(src, base + 5 * m, lastOk = 0, lastTry = base))
        assertTrue(OutlineSources.due(src, base + 11 * m, lastOk = 0, lastTry = base))
        // A GitHub list keeps its own hours.
        val gh = OutlineSources.byId("forcerun")!!
        assertFalse(OutlineSources.due(gh, base + 120 * m, lastOk = base, lastTry = base))
        assertTrue(OutlineSources.due(gh, base + 181 * m, lastOk = base, lastTry = base))
    }
}
