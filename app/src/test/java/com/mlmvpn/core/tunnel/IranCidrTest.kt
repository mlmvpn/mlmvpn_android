package com.mlmvpn.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IranCidrTest {

    private fun v4(s: String) = IranCidr.parseV4(s)!!

    @Test fun parsing() {
        assertEquals("2.144.0.0/14", v4("2.144.0.0/14").toString())
        assertEquals("host bits are masked", "5.0.0.0/8", v4("5.6.7.8/8").toString())
        assertNull(IranCidr.parseV4("2001:db8::/32"))
        assertNull(IranCidr.parseV4("300.1.1.1/8"))
        val (a, b) = IranCidr.parse("2.144.0.0/14\n# note\n2a01:5ec0::/29\n\nnonsense\n")
        assertEquals(1, a.size); assertEquals(1, b.size)
    }

    @Test fun collapseMergesBuddiesAndDropsNested() {
        val c = IranCidr.collapse(listOf(v4("10.0.1.0/24"), v4("10.0.0.0/24"), v4("10.0.0.128/25")))
        assertEquals(listOf("10.0.0.0/23"), c.map { it.toString() })
    }

    @Test fun complementCoversEverythingButTheHoles() {
        val holes = listOf(v4("2.144.0.0/14"), v4("5.0.0.0/8"), v4("100.0.0.0/30"))
        val comp = IranCidr.complementV4(holes, maxPrefix = 18)
        val total = comp.sumOf { it.size }
        // The /30 is smaller than /18, so it stays inside the tunnel.
        assertEquals((1L shl 32) - (1L shl 18) - (1L shl 24), total)
        assertFalse(IranCidr.contains(comp, IranCidr.ipv4Value("2.145.1.1")!!))
        assertFalse(IranCidr.contains(comp, IranCidr.ipv4Value("5.200.0.1")!!))
        assertTrue(IranCidr.contains(comp, IranCidr.ipv4Value("100.0.0.1")!!))
        assertTrue(IranCidr.contains(comp, IranCidr.ipv4Value("8.8.8.8")!!))
        // No overlaps: sorted and disjoint.
        comp.zipWithNext().forEach { (a, b) -> assertTrue(a.end < b.start) }
    }

    @Test fun keptAddressesStayInside() {
        val comp = IranCidr.complementV4(listOf(v4("178.22.0.0/16")), 18, keep = listOf(IranCidr.ipv4Value("178.22.122.100")!!))
        assertTrue(IranCidr.contains(comp, IranCidr.ipv4Value("178.22.122.100")!!))
        assertEquals(1L shl 32, comp.sumOf { it.size })
    }

    @Test fun blocksAreExact() {
        val b = IranCidr.blocks(IranCidr.ipv4Value("10.0.0.1")!!, IranCidr.ipv4Value("10.0.0.6")!!)
        assertEquals(listOf("10.0.0.1/32", "10.0.0.2/31", "10.0.0.4/31", "10.0.0.6/32"), b.map { it.toString() })
    }

    @Test fun emptyHolesIsTheWholeSpace() {
        assertEquals(listOf("0.0.0.0/0"), IranCidr.complementV4(emptyList(), 18).map { it.toString() })
    }
}
