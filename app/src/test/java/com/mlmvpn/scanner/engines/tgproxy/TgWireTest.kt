package com.mlmvpn.scanner.engines.tgproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** Vectors computed independently with Node's crypto (see scripts/test-tgproxy-worker.mjs). */
class TgWireTest {

    private val secret = TgLink.unhex("000102030405060708090a0b0c0d0e0f")

    /** A "random" source that always yields the pattern byte i*7+3, so the header is reproducible. */
    private val fixed = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = ((i * 7 + 3) and 255).toByte()
        }
    }

    @Test fun headerAndKeystreamsMatchTheReferenceVectors() {
        val client = TgObfs2Client(secret, dc = -3, tag = 0xdddddddd.toInt(), random = fixed)
        assertEquals(
            "030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c535a61686f767d84e909a100a993510a",
            TgLink.hex(client.header),
        )
        assertEquals(
            "7a61bdb16bedcc6eb66f9e7a634bd748891ddea91667c6bd173c47de05e25b90507960",
            TgLink.hex(client.seal("hello obfuscated2 world, 33 bytes!!".toByteArray())),
        )
        // What the proxy would send back, encrypted with the download keystream, decrypts here.
        val wire = TgLink.unhex("4c2cc9b087cc70d2af977fd2f1f6d15347e746f03cb7689a6ff17b61642587db")
        assertEquals("answer from the proxy 0123456789", String(client.open(wire)))
    }

    @Test fun keystreamsContinueAcrossCalls() {
        val a = TgObfs2Client(secret, 2, 0xeeeeeeee.toInt(), fixed)
        val b = TgObfs2Client(secret, 2, 0xeeeeeeee.toInt(), fixed)
        val data = ByteArray(1000) { it.toByte() }
        val whole = a.seal(data)
        val pieces = listOf(1, 15, 16, 17, 31, 920)
        var at = 0
        val joined = ArrayList<Byte>()
        for (n in pieces) { joined += b.seal(data.copyOfRange(at, at + n)).toList(); at += n }
        assertArrayEquals(whole, joined.toByteArray())
    }

    @Test fun randomHeadersAreAcceptableToTelegram() {
        repeat(200) {
            val c = TgObfs2Client(secret, 2, 0xeeeeeeee.toInt())
            val h = c.header
            assertEquals(64, h.size)
            assertTrue("first byte", (h[0].toInt() and 0xFF) != 0xEF)
            assertTrue("second word not zero", h.copyOfRange(4, 8).any { it.toInt() != 0 })
        }
    }

    @Test fun framesRoundTrip() {
        val payload = "hello".toByteArray()
        val frame = TgFrames.encode(TgFrames.DATA, 0x010203, payload)
        assertEquals(8 + 5, frame.size)
        assertEquals(2, frame[0].toInt())
        assertEquals(listOf(1, 2, 3), listOf(frame[1].toInt(), frame[2].toInt(), frame[3].toInt()))
        val parsed = TgFrames.parse(frame + TgFrames.encode(TgFrames.CLOSE, 7))!!
        assertEquals(2, parsed.size)
        assertEquals(0x010203, parsed[0].stream)
        assertArrayEquals(payload, parsed[0].payload)
        assertEquals(TgFrames.CLOSE, parsed[1].type)
        assertEquals(7, parsed[1].stream)
        assertEquals(0, parsed[1].payload.size)
    }

    @Test fun malformedBatchesAreRefused() {
        assertNull(TgFrames.parse(ByteArray(0)))
        assertNull(TgFrames.parse(ByteArray(7)))
        val lie = TgFrames.encode(TgFrames.DATA, 1, "abc".toByteArray())
        lie[7] = 99
        assertNull(TgFrames.parse(lie))
        val huge = TgFrames.encode(TgFrames.DATA, 1)
        huge[4] = 0; huge[5] = 0x20; huge[6] = 0; huge[7] = 0
        assertNull(TgFrames.parse(huge))
    }

    @Test fun helloIsTheOneFrameTheServerExpects() {
        val hello = TgFrames.parse(TgFrames.HELLO_FRAME)!!.single()
        assertEquals(TgFrames.HELLO, hello.type)
        assertEquals(0, hello.stream)
        assertArrayEquals(byteArrayOf(1), hello.payload)
        assertNotNull(hello)
    }
}
