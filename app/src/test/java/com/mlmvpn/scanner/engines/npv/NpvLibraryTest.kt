package com.mlmvpn.scanner.engines.npv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpvLibraryTest {

    @Test
    fun aDamagedEntryIsSkippedNotTheWholeLibrary() {
        val text = """
            [
              {"id": "a", "name": "Mine", "kind": "LINK", "uri": "vless://u@h:443", "source": "mine", "addedAt": 5},
              {"name": "no id", "kind": "LINK", "uri": "vless://u@h:443"},
              {"id": "b", "kind": "SOMETHING"},
              {"id": "c", "kind": "LINK"},
              7,
              {"id": "d", "kind": "NATIVE", "name": "f.npvt"}
            ]
        """.trimIndent()
        val e = NpvLibrary.parse(text)
        assertEquals(listOf("a", "d"), e.map { it.id })
        assertEquals("Mine", e[0].name)
        assertEquals(5L, e[0].addedAt)
    }

    @Test
    fun aNativeFileNeverCarriesALinkToRun() {
        // Even if one was written by mistake: a native NPV Tunnel file is not run in this app.
        val e = NpvLibrary.parse("""[{"id": "n", "kind": "NATIVE", "uri": "vless://u@h:443"}]""")
        assertEquals(NpvLibrary.Kind.NATIVE, e.single().kind)
        assertNull(e.single().uri)
    }

    @Test
    fun missingFieldsTakeTheirDefaults() {
        val e = NpvLibrary.parse("""[{"id": "x", "kind": "LINK", "uri": "ss://k@h:8388"}]""").single()
        assertEquals("x", e.name)
        assertEquals("mine", e.source)
        assertEquals(0L, e.addedAt)
        assertTrue(e.kind == NpvLibrary.Kind.LINK && e.uri == "ss://k@h:8388")
    }
}
