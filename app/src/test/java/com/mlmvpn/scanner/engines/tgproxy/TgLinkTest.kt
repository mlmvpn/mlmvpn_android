package com.mlmvpn.scanner.engines.tgproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class TgLinkTest {

    @Test fun capabilityMatchesTelegramsPublishedVectors() {
        assertEquals(
            "MHLEY5PmW1GWqJkSrlmJpvJUiLhBH_QKy6yKg8a0JPk",
            TgLink.capability("proxy.example.com", "000102030405060708090a0b0c0d0e0f"),
        )
        // With random padding the key includes the leading dd byte.
        assertEquals(
            "IpJrt3e7sKtzPyoXy6w-Zj6GGEvsvclN66JzQEfPYLA",
            TgLink.capability("proxy.example.com", "dd000102030405060708090a0b0c0d0e0f"),
        )
    }

    @Test fun capabilityDependsOnTheHost() {
        val s = "000102030405060708090a0b0c0d0e0f"
        assertNotEquals(TgLink.capability("a.example.com", s), TgLink.capability("b.example.com", s))
    }

    @Test fun newSecretsAreRandomPaddedAndValid() {
        val a = TgLink.newSecret()
        val b = TgLink.newSecret()
        assertTrue(TgLink.isSecret(a))
        assertTrue(a.startsWith("dd") && a.length == 34)
        assertNotEquals(a, b)
        assertTrue(TgLink.isPadded(a))
        assertEquals(16, TgLink.keyBytes(a).size)
    }

    @Test fun secretShapes() {
        assertTrue(TgLink.isSecret("00112233445566778899aabbccddeeff"))
        assertTrue(TgLink.isSecret("dd00112233445566778899aabbccddeeff"))
        assertFalse("fake-TLS is not offered", TgLink.isSecret("ee00112233445566778899aabbccddeeff6578616d706c652e636f6d"))
        assertFalse("upper case", TgLink.isSecret("00112233445566778899AABBCCDDEEFF"))
        assertFalse("short", TgLink.isSecret("0011"))
        assertFalse("long", TgLink.isSecret("00112233445566778899aabbccddeeff00"))
        assertFalse("not hex", TgLink.isSecret("zz112233445566778899aabbccddeeff"))
        assertFalse(TgLink.isPadded("00112233445566778899aabbccddeeff"))
        assertArrayEquals(TgLink.unhex("00112233445566778899aabbccddeeff"), TgLink.keyBytes("dd00112233445566778899aabbccddeeff"))
    }

    @Test fun hostShapes() {
        assertTrue(TgLink.isHost("tgp-ab12cd34.sub.workers.dev"))
        assertTrue(TgLink.isHost("tg.example.com"))
        assertTrue(TgLink.isHost("xn--mnchen-3ya.example"))
        assertFalse("upper case", TgLink.isHost("Tg.Example.com"))
        assertFalse("no dot", TgLink.isHost("localhost"))
        assertFalse("ip", TgLink.isHost("1.2.3.4"))
        assertFalse("port", TgLink.isHost("tg.example.com:443"))
        assertFalse("path", TgLink.isHost("tg.example.com/x"))
        assertFalse("space", TgLink.isHost("tg example.com"))
        assertFalse("leading dash", TgLink.isHost("-tg.example.com"))
        assertFalse("empty label", TgLink.isHost("tg..example.com"))
        assertFalse("empty", TgLink.isHost(""))
    }

    @Test fun linksCarryNoPortAndTheExactSecret() {
        val host = "tgp-ab12.sub.workers.dev"
        val secret = "dd00112233445566778899aabbccddeeff"
        assertEquals("tg://webproxy?server=$host&secret=$secret", TgLink.appLink(host, secret))
        assertEquals("https://t.me/webproxy?server=$host&secret=$secret", TgLink.webLink(host, secret))
        val url = TgLink.bridgeUrl(host, secret)
        assertTrue(url.startsWith("https://$host/?bridge="))
        assertEquals(43, url.substringAfter("bridge=").length)
    }

    @Test fun base64UrlHasNoPaddingOrUnsafeCharacters() {
        assertEquals("", TgLink.b64url(ByteArray(0)))
        assertEquals("AA", TgLink.b64url(byteArrayOf(0)))
        assertEquals("_-8", TgLink.b64url(byteArrayOf(0xFF.toByte(), 0xEF.toByte())))
        assertEquals("cIVhlEBk_HMMv6RHNWLY7Fk", TgLink.b64url(byteArrayOf(0x70) + TgLink.unhex("8561944064fc730cbfa4473562d8ec59")))
        val random = SecureRandom()
        repeat(50) {
            val b = ByteArray(random.nextInt(40))
            random.nextBytes(b)
            assertTrue(TgLink.b64url(b).matches(Regex("^[A-Za-z0-9_-]*$")))
        }
    }

    @Test fun oddHexIsRefused() {
        var thrown = false
        try { TgLink.unhex("abc") } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
        assertNull(null as String?)
    }
}
