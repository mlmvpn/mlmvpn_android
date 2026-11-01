package com.mlmvpn.scanner.engines.proton

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ProtonWireTest {
    private class Writes : ByteArrayOutputStream() {
        val sizes = mutableListOf<Int>()
        override fun write(b: ByteArray, off: Int, len: Int) { sizes += len; super.write(b, off, len) }
    }
    private fun hello(): ByteArray = byteArrayOf(22, 3, 1, 0, 120) + ByteArray(120) { it.toByte() }
    private fun records(bytes: ByteArray): List<ByteArray> {
        val parts = mutableListOf<ByteArray>()
        var p = 0
        while (p < bytes.size) {
            assertTrue(p + 5 <= bytes.size)
            val n = ((bytes[p + 3].toInt() and 255) shl 8) or (bytes[p + 4].toInt() and 255)
            assertTrue(p + 5 + n <= bytes.size)
            parts += bytes.copyOfRange(p + 5, p + 5 + n)
            p += 5 + n
        }
        return parts
    }

    @Test fun `PattNG empty record and ordered records preserve every handshake byte`() {
        val out = Writes()
        ProtonHello.write(out, hello(), ProtonMethod.PATTNG_ZERO)
        val parts = records(out.toByteArray())
        assertEquals(0, parts.first().size)
        assertEquals(104, parts[1].size)
        assertEquals(18, parts.size)
        assertArrayEquals(hello().copyOfRange(5, 125), parts.fold(ByteArray(0)) { a, b -> a + b })
        assertEquals(listOf(114, 1, 1, 1, 1, 1, 1, 1, 1, 1, 87), out.sizes)
    }

    @Test fun `PattNG nonempty variant splits TLS records before splitting TCP`() {
        val out = Writes()
        ProtonHello.write(out, hello(), ProtonMethod.PATTNG_SIX)
        assertEquals(listOf(6, 98) + List(16) { 1 }, records(out.toByteArray()).map { it.size })
        assertArrayEquals(hello().copyOfRange(5, 125), records(out.toByteArray()).fold(ByteArray(0)) { a, b -> a + b })
    }

    @Test fun `PattN record fragmentation preserves trailing TLS records`() {
        val next = byteArrayOf(20, 3, 3, 0, 1, 1)
        val out = Writes()
        ProtonHello.write(out, hello() + next, ProtonMethod.PATTN)
        val records = records(out.toByteArray())
        assertTrue(records.size >= 3)
        assertTrue(records.first().size in 50..100)
        assertArrayEquals(hello().copyOfRange(5, 125), records.dropLast(1).fold(ByteArray(0)) { a, b -> a + b })
        assertArrayEquals(next, out.toByteArray().takeLast(6).toByteArray())
    }

    @Test fun `plain or incomplete record remains byte identical`() {
        for (input in listOf(hello(), byteArrayOf(22, 3, 1, 5, 0, 1))) {
            val out = Writes()
            ProtonHello.write(out, input, if (input.size == 125) ProtonMethod.PLAIN else ProtonMethod.PATTNG_ZERO)
            assertArrayEquals(input, out.toByteArray())
        }
    }

    private fun ids(lanes: List<List<ProtonRoute>>) = lanes.map { lane -> lane.joinToString("+") { it.id } }

    @Test fun `every way is a lane of the race and the IPv4 methods share one lane`() {
        val plan = ProtonRoutePlan(hasV6 = true, cf = true)
        assertEquals(
            listOf("u6:PLAIN", "v6:PLAIN", "cf:PLAIN", "u4:PLAIN", ProtonRoutePlan.V4_METHODS.joinToString("+") { "v4:${it.name}" }),
            ids(plan.lanes()),
        )
        assertEquals(4 + ProtonRoutePlan.V4_METHODS.size, plan.size)
        // No IPv6 here (most lines in Iran) and no Cloudflare config: plain WireGuard and Stealth on IPv4.
        assertEquals(listOf("u4:PLAIN", "v4:PLAIN"), ProtonRoutePlan(hasV6 = false).lanes().map { it.first().id })
        // No IPv6 but a Cloudflare config: Cloudflare races beside the server's own IPv4.
        assertEquals(listOf("cf:PLAIN", "u4:PLAIN", "v4:PLAIN"), ProtonRoutePlan(hasV6 = false, cf = true).lanes().map { it.first().id })
        // A network that dropped plain WireGuard: Stealth only.
        assertEquals(listOf("v6:PLAIN", "v4:PLAIN"), ProtonRoutePlan(hasV6 = true, udp = false, udp6 = false).lanes().map { it.first().id })
        // One way only, for the lab.
        assertEquals(listOf("cf:PLAIN"), ids(ProtonRoutePlan(hasV6 = true, cf = true, only = setOf(ProtonVia.CF)).lanes()))
    }

    @Test fun `the remembered way leads the race and its own lane`() {
        val plan = ProtonRoutePlan(hasV6 = true, cf = true, preferred = { "v4:PATTN" })
        val lanes = plan.lanes()
        assertEquals("v4:PATTN", lanes.first().first().id)
        // The rest of the IPv4 methods follow it in the same lane, none tried twice.
        assertEquals(ProtonRoutePlan.V4_METHODS.size, lanes.first().size)
        assertEquals(ProtonRoutePlan.V4_METHODS.size, lanes.first().map { it.id }.toSet().size)
        assertEquals(ProtonMethod.PATTN, plan.preferredRoute()?.method)
        // A way this plan does not have (no IPv6 here) is no preference at all.
        assertNull(ProtonRoutePlan(hasV6 = false, preferred = { "v6:PLAIN" }).preferredRoute())
        assertEquals("u4:PLAIN", ProtonRoutePlan(hasV6 = false, preferred = { "v6:PLAIN" }).lanes().first().first().id)
    }

    @Test fun `only data validation learns a way and only a stall unlearns it`() {
        val learned = mutableListOf<Pair<String, Boolean>>()
        val plan = ProtonRoutePlan(hasV6 = true, cf = true) { id, ok -> learned += id to ok }
        plan.lanes()
        assertTrue(learned.isEmpty())
        plan.validated(ProtonRoute(ProtonVia.CF))
        assertEquals("cf:PLAIN" to true, learned.last())
        plan.failed(ProtonRoute(ProtonVia.U4))
        assertEquals("u4:PLAIN" to false, learned.last())
    }

    @Test fun `the way the network learned is asked when a race starts`() {
        var learnedOnNetwork: String? = null
        val plan = ProtonRoutePlan(hasV6 = true, cf = true, preferred = { learnedOnNetwork })
        // Learned after the plan was made (a test's first batch), before its first race.
        learnedOnNetwork = "cf:PLAIN"
        assertEquals(ProtonVia.CF, plan.lanes().first().first().via)
    }

    @Test fun `ways that just stalled go last in the race, never out of it`() {
        val plan = ProtonRoutePlan(hasV6 = true, cf = true, udp = false, udp6 = false, v4Methods = emptyList())
        assertEquals(listOf("cf:PLAIN", "v6:PLAIN"), ids(plan.lanes(avoid = setOf("v6:PLAIN"))))
        assertEquals(listOf("v6:PLAIN", "cf:PLAIN"), ids(plan.lanes(avoid = setOf("v6:PLAIN", "cf:PLAIN"))))
        // An IPv4 method that stalled goes to the end of its lane.
        val v4 = ProtonRoutePlan(hasV6 = false, udp = false)
        assertEquals(ProtonRoutePlan.V4_METHODS.size, v4.lanes(avoid = setOf("v4:PLAIN")).single().size)
        assertEquals("v4:PATTNG_SIX", v4.lanes(avoid = setOf("v4:PLAIN")).single().first().id)
        assertEquals("v4:PLAIN", v4.lanes(avoid = setOf("v4:PLAIN")).single().last().id)
    }

    @Test fun `a way is only given up on when another measured working here`() {
        val only = ProtonRoutePlan(hasV6 = false, cf = true, speeds = { mapOf("cf:PLAIN" to 9_200_000L, "u4:PLAIN" to 0L, "v4:PLAIN" to 0L) to 1L })
        assertFalse(only.otherWayWorks(ProtonRoute(ProtonVia.CF)))
        val two = ProtonRoutePlan(hasV6 = true, cf = true, speeds = { mapOf("cf:PLAIN" to 9_200_000L, "v6:PLAIN" to 3_000_000L) to 1L })
        assertTrue(two.otherWayWorks(ProtonRoute(ProtonVia.CF)))
        // A way this plan no longer races (UDP the line drops) is no alternative.
        val udp = ProtonRoutePlan(hasV6 = true, speeds = { mapOf("u6:PLAIN" to 9_000_000L, "v6:PLAIN" to 3_000_000L) to 1L })
        udp.failed(ProtonRoute(ProtonVia.U6))
        assertFalse(udp.otherWayWorks(ProtonRoute(ProtonVia.V6)))
    }

    @Test fun `the way measured fastest here leads, ahead of the one that answered first`() {
        val plan = ProtonRoutePlan(hasV6 = true, cf = true, preferred = { "v6:PLAIN" },
            speeds = { mapOf("v6:PLAIN" to 3_000_000L, "cf:PLAIN" to 18_000_000L) to 1L })
        assertEquals("cf:PLAIN", plan.lanes().first().first().id)
        assertEquals(ProtonVia.CF, plan.preferredRoute()?.via)
        // A measured way this plan lacks (UDP left out here) gives way to the next fastest.
        val noUdp = ProtonRoutePlan(hasV6 = true, udp = false, udp6 = false,
            speeds = { mapOf("u6:PLAIN" to 30_000_000L, "v6:PLAIN" to 3_000_000L) to 1L })
        assertEquals("v6:PLAIN", noUdp.preferredRoute()?.id)
        // Nothing measured: the remembered way.
        assertEquals("v6:PLAIN", ProtonRoutePlan(hasV6 = true, cf = true, preferred = { "v6:PLAIN" }).preferredRoute()?.id)
    }

    @Test fun `a speed comparison is due when nothing was measured lately or a way was left out a while ago`() {
        val hour = 3600_000L
        assertTrue(ProtonRoutePlan(hasV6 = true, cf = true).speedsDue(now = 240 * hour))
        val measuredAll = ProtonRoutePlan(hasV6 = false, cf = true, udp = false, v4Methods = emptyList(),
            speeds = { mapOf("cf:PLAIN" to 1L) to 200 * hour })
        assertFalse(measuredAll.speedsDue(now = 240 * hour))
        val leftOut = ProtonRoutePlan(hasV6 = true, cf = true, udp = false, udp6 = false, v4Methods = emptyList(),
            speeds = { mapOf("cf:PLAIN" to 1L) to 200 * hour })
        assertTrue(leftOut.speedsDue(now = 240 * hour))
        assertFalse(leftOut.speedsDue(now = 201 * hour))
    }

    @Test fun `plain WireGuard that stalls leaves the race on both IP versions at once`() {
        val learned = mutableListOf<Pair<String, Boolean>>()
        val plan = ProtonRoutePlan(hasV6 = true, cf = true) { id, ok -> learned += id to ok }
        plan.failed(ProtonRoute(ProtonVia.U6))
        assertEquals("u6:PLAIN" to false, learned.last())
        assertEquals(listOf("v6:PLAIN", "cf:PLAIN", "v4:PLAIN"), plan.lanes().map { it.first().id })
    }

    @Test fun `the lead is the fastest way still worth trying`() {
        val unstable = mutableListOf<String>()
        val plan = ProtonRoutePlan(hasV6 = true, cf = true, onUnstable = { unstable += it },
            speeds = { mapOf("u6:PLAIN" to 8_600_000L, "v6:PLAIN" to 3_300_000L, "cf:PLAIN" to 0L) to 1L })
        assertEquals("u6:PLAIN", plan.preferredRoute()?.id)
        // Stalled a moment ago: the next fastest leads.
        assertEquals("v6:PLAIN", plan.preferredRoute(avoid = setOf("u6:PLAIN"))?.id)
        assertEquals("v6:PLAIN", plan.lanes(avoid = setOf("u6:PLAIN")).first().first().id)
        assertEquals("u6:PLAIN", plan.lanes(avoid = setOf("u6:PLAIN")).last().first().id)
        // Measured at 0 (failed its sample): never the lead, though it stays in the race.
        val zero = ProtonRoutePlan(hasV6 = false, cf = true, speeds = { mapOf("cf:PLAIN" to 0L) to 1L })
        assertNull(zero.preferredRoute())
        assertTrue(zero.lanes().any { it.first().via == ProtonVia.CF })
        plan.unstable(ProtonRoute(ProtonVia.U6))
        assertEquals(listOf("u6:PLAIN"), unstable)
    }

    @Test fun `fragmenting ends after the first TLS flight`() {
        val out = Writes()
        val shaped = ProtonHello.output(out, ProtonMethod.PATTNG_ZERO)
        shaped.write(hello())
        val first = out.size()
        val data = byteArrayOf(23, 3, 3, 0, 3, 9, 8, 7)
        shaped.write(data)
        assertArrayEquals(data, out.toByteArray().copyOfRange(first, out.size()))
        assertEquals(data.size, out.sizes.last())
    }
}
