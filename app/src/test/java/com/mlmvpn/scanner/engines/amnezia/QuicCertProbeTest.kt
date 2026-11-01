package com.mlmvpn.scanner.engines.amnezia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuicCertProbeTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun `blake2b-256 matches the reference`() {
        assertEquals("bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319", hex(QuicCertProbe.Blake2b.hash256("abc".toByteArray())))
        // More than one block.
        assertEquals("5aa7fbbf37986bb2a5d547c0d3c4d4326a24d786e7d57bf93fc784176e38b33d", hex(QuicCertProbe.Blake2b.hash256(ByteArray(300) { 'x'.code.toByte() })))
    }

    @Test fun `initial keys match RFC 9001 appendix A`() {
        val (key, iv, hp) = QuicCertProbe.clientInitialKeys(byteArrayOf(0x83.toByte(), 0x94.toByte(), 0xc8.toByte(), 0xf0.toByte(), 0x3e, 0x51, 0x57, 0x08))
        assertEquals("1f369613dd76d5467730efcbe3b1a22d", hex(key))
        assertEquals("fa044b2f42a3fd3b46fb255c", hex(iv))
        assertEquals("9f50449e04a0e810283a1e9933adedd2", hex(hp))
    }

    @Test fun `self-signed links are kept, flagged and pinned`() {
        val link = "hysteria2://pw@1.2.3.4:443?sni=www.bing.com&insecure=1&obfs=salamander&obfs-password=pw#x"
        val s = AmzParser.fromLink(link, "src", 0L)!!
        assertTrue(s.selfSigned)
        assertFalse(s.raw.contains("insecure"))
        val pin = "ab".repeat(32)
        val pinned = AmzParser.withPin(s.raw, pin)
        assertTrue(pinned.endsWith("pinSHA256=$pin#x"))
        // A second pin replaces the first.
        val again = AmzParser.withPin(pinned, "cd".repeat(32))
        assertFalse(again.contains(pin))
        val node = (com.mlmvpn.scanner.engines.flux.core.parse.FluxLinkParser.parse(again) as com.mlmvpn.scanner.engines.flux.core.parse.FluxLinkParser.Result.Ok).node
        assertEquals("cd".repeat(32), node.certPin)
        // Re-reading the same server keeps its id: a pin never makes it a new server.
        assertEquals(s.id, AmzParser.fromLink(again, "src", 0L)!!.id)
    }

    @Test fun `a link's own pin is kept and the server is not flagged`() {
        val pin = "AB:".repeat(31) + "AB"
        val s = AmzParser.fromLink("hysteria2://pw@1.2.3.4:443?sni=a.com&insecure=1&pinSHA256=${pin}#x", "src", 0L)!!
        assertFalse(s.selfSigned)
        assertTrue(s.raw.contains("pinSHA256=" + "ab".repeat(32)))
    }

    @Test fun `old removals of hysteria2 are lifted once`() {
        val old = """{"v":1,"servers":[],"tomb":{"habc":1,"wdef":2}}"""
        val st = AmzCodec.decode(old)
        assertEquals(setOf("wdef"), st.tombstones.keys)
        val again = AmzCodec.decode(AmzCodec.encode(st.copy(tombstones = mapOf("habc" to 1L))))
        assertEquals(setOf("habc"), again.tombstones.keys)
    }

    @Test fun `the flag survives the codec`() {
        val s = AmzParser.fromLink("hysteria2://pw@1.2.3.4:443?sni=a.com&insecure=1#x", "src", 0L)!!
        val back = AmzCodec.decode(AmzCodec.encode(AmzState(servers = listOf(s)))).servers.single()
        assertTrue(back.selfSigned)
    }
}
