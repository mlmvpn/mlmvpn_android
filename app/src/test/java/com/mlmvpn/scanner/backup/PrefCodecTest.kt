package com.mlmvpn.scanner.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefCodecTest {

    @Test
    fun `every SharedPreferences type comes back as the same type`() {
        val values = mapOf<String, Any?>(
            "s" to "متن", "b" to true, "i" to 7, "l" to 7L, "big" to 1_760_760_000_123L,
            "f" to 1.25f, "set" to setOf("b", "a"), "empty" to "",
        )
        // Through text, as the file carries it.
        val back = PrefCodec.decodeMap(JSONObject(PrefCodec.encodeMap(values).toString()))
        assertEquals(values.keys, back.keys)
        for ((k, v) in values) {
            assertEquals(k, v, back[k])
            assertEquals(k, v!!.javaClass, back.getValue(k).javaClass)
        }
        assertTrue(back["i"] is Int)
        assertTrue(back["l"] is Long)
    }

    @Test
    fun `what it cannot carry is skipped, not an error`() {
        assertNull(PrefCodec.encode(Float.NaN))
        assertNull(PrefCodec.encode(null))
        assertNull(PrefCodec.encode(listOf("x")))
        assertNull(PrefCodec.decode(JSONObject().put("q", 1)))
        assertNull(PrefCodec.decode(JSONObject().put("s", "a").put("b", true)))
        assertNull(PrefCodec.decode(JSONObject().put("i", "not a number")))
        assertEquals(emptyMap<String, Any>(), PrefCodec.decodeMap(null))
    }
}
