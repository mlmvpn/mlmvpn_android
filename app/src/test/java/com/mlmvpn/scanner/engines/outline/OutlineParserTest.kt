package com.mlmvpn.scanner.engines.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key shapes taken from the public lists and Outline's own manager (passwords replaced). */
class OutlineParserTest {

    private val now = 1_000L
    private fun b64(s: String) = OutlineParser.encodeB64Url(s)

    @Test fun outlineManagerKey() {
        val key = "ss://${b64("chacha20-ietf-poly1305:Secret123")}@198.51.100.7:12345/?outline=1#%F0%9F%87%A9%F0%9F%87%AA%20Frankfurt"
        val s = OutlineParser.parse(key, "user", now)!!
        assertEquals("chacha20-ietf-poly1305", s.method)
        assertEquals("198.51.100.7", s.host)
        assertEquals(12345, s.port)
        assertEquals("DE", s.country)
        assertFalse(s.prefix)
        assertTrue(s.uri.startsWith("ss://"))
        assertFalse("canonical link carries no query", s.uri.contains("?"))
    }

    @Test fun prefixIsKeptAsAFlagOnly() {
        val key = "ss://${b64("chacha20-ietf-poly1305:pw")}@203.0.113.9:443/?outline=1&prefix=%16%03%01%00%C2%A8%01%01#tls"
        val s = OutlineParser.parse(key, "user", now)!!
        assertTrue(s.prefix)
        assertFalse(s.uri.contains("prefix"))
    }

    @Test fun legacyWholeBlob() {
        val key = "ss://" + b64("aes-256-gcm:pw@203.0.113.10:8388") + "#US-1"
        val s = OutlineParser.parse(key, "src", now)!!
        assertEquals("aes-256-gcm", s.method)
        assertEquals(8388, s.port)
        assertEquals("US", s.country)
    }

    @Test fun plainUserInfoAnd2022() {
        val s = OutlineParser.parse("ss://2022-blake3-aes-128-gcm:QUJDREVGR0hJSktMTU5PUA==@203.0.113.11:443#x", "src", now)
        assertNotNull(s)
        assertEquals("2022-blake3-aes-128-gcm", s!!.method)
    }

    @Test fun ipv6Host() {
        val s = OutlineParser.parse("ss://${b64("aes-128-gcm:pw")}@[2001:db8::1]:443#v6", "src", now)!!
        assertEquals("2001:db8::1", s.host)
        assertTrue(s.uri.contains("[2001:db8::1]:443"))
    }

    @Test fun refusals() {
        assertNull("plugin", OutlineParser.parse("ss://${b64("aes-128-gcm:pw")}@203.0.113.1:443/?plugin=obfs-local%3Bobfs%3Dhttp#x", "s", now))
        assertNull("rc4", OutlineParser.parse("ss://${b64("rc4-md5:pw")}@203.0.113.1:443#x", "s", now))
        assertNull("private", OutlineParser.parse("ss://${b64("aes-128-gcm:pw")}@192.168.1.5:443#x", "s", now))
        assertNull("block page", OutlineParser.parse("ss://${b64("aes-128-gcm:pw")}@10.10.34.35:443#x", "s", now))
        assertNull("no port", OutlineParser.parse("ss://${b64("aes-128-gcm:pw")}@203.0.113.1#x", "s", now))
        assertNull("not ss", OutlineParser.parse("vmess://abc", "s", now))
    }

    @Test fun sameServerTwoNamesIsOneRow() {
        val a = OutlineParser.parse("ss://${b64("aes-128-gcm:pw")}@203.0.113.1:443#A", "s1", now)!!
        val b = OutlineParser.parse("ss://${b64("aes-128-gcm:pw")}@203.0.113.1:443#B", "s2", now)!!
        assertEquals(a.id, b.id)
        val batch = OutlineParser.parseText(a.uri + "\n" + b.uri + "\nvless://x\n", "s", now)
        assertEquals(1, batch.servers.size)
    }

    @Test fun base64Subscription() {
        val list = listOf(
            "ss://${b64("aes-128-gcm:pw1")}@203.0.113.1:443#a",
            "ss://${b64("chacha20-ietf-poly1305:pw2")}@203.0.113.2:443#b",
            "trojan://x@203.0.113.3:443",
        ).joinToString("\n")
        val batch = OutlineParser.parseText(b64(list), "s", now)
        assertEquals(2, batch.servers.size)
    }

    @Test fun ssconfJsonBody() {
        val body = """{"server":"203.0.113.20","server_port":8443,"password":"pw","method":"chacha20-ietf-poly1305","prefix":"\u0016\u0003\u0001"}"""
        val s = OutlineParser.parseSsconfBody(body, "ssconf://example.com/key#Mine", now, "Mine")!!
        assertEquals(8443, s.port)
        assertTrue(s.prefix)
        assertTrue(s.user)
        assertEquals("ssconf://example.com/key#Mine", s.ssconf)
        assertEquals("Mine", s.name)
    }

    @Test fun ssconfLinkBody() {
        val s = OutlineParser.parseSsconfBody("ss://${b64("aes-256-gcm:pw")}@203.0.113.21:443#srv\n", "ssconf://h/p", now)
        assertNotNull(s)
    }

    @Test fun ssconfUrl() {
        assertEquals("https://s3.example.com/k.json" to "My Key", OutlineParser.ssconfUrl("ssconf://s3.example.com/k.json#My%20Key"))
        assertNull(OutlineParser.ssconfUrl("ss://abc"))
    }

    @Test fun keysInAMessage() {
        val text = "سلام این کلید: ss://${b64("aes-128-gcm:pw")}@203.0.113.1:443#a, و این هم ssconf://h.example/x."
        val keys = OutlineParser.keysIn(text)
        assertEquals(2, keys.size)
        assertTrue(keys[1].startsWith("ssconf://h.example/x"))
    }

    @Test fun base64RoundTrip() {
        val t = "chacha20-ietf-poly1305:p@ss:w0rd/+="
        assertEquals(t, OutlineParser.decodeB64(OutlineParser.encodeB64Url(t)))
    }

    @Test fun countryFromNames() {
        assertEquals("NL", OutlineParser.countryFromName("🇳🇱 Amsterdam"))
        assertEquals("FR", OutlineParser.countryFromName("[FR] Paris 03"))
        assertEquals("GB", OutlineParser.countryFromName("| GB | 120ms"))
        assertNull(OutlineParser.countryFromName("server one"))
    }

    // ---------------------------------------------------------------- Shadowmere (shapes its API gave on 2026-10-25)

    @Test fun shadowmereApiPage() {
        val url = "ss://${b64("chacha20-ietf-poly1305:pw1")}@104.253.216.227:49917"
        val json = """{"count":12,"total_pages":2,"current_page":1,"results":[
            {"id":1,"url":"$url","location":"Kharkiv, 63, Ukraine","location_country_code":"UA","is_active":true,
             "last_checked":"2026-10-25T19:00:15.366268Z","times_checked":4,"times_check_succeeded":3,"port":49917},
            {"id":2,"url":"ss://${b64("rc4-md5:pw")}@203.0.113.5:8388","location":"France","location_country_code":"FR",
             "is_active":true,"last_checked":"2026-10-25T19:00:16Z","times_checked":1,"times_check_succeeded":1}]}"""
        val p = OutlineParser.parseShadowmerePage(json, "shadowmere", now)!!
        assertEquals(2, p.pages)
        assertEquals(12, p.count)
        assertEquals("a cipher the core refuses is skipped", 1, p.batch.skipped)
        val s = p.batch.servers.single()
        assertEquals("shadowmere · Kharkiv, Ukraine", s.name)
        assertEquals("UA", s.country)
        assertEquals(75, s.uptime)
        assertEquals(1_792_954_815_000L, s.checkedAt)
        // The tag is in the key itself: copied and added back, it keeps the name.
        val again = OutlineParser.parse(s.uri, "user", now)!!
        assertEquals(s.id, again.id)
        assertEquals("shadowmere · Kharkiv, Ukraine", again.name)
        assertNull("not JSON", OutlineParser.parseShadowmerePage("<html>Just a moment...</html>", "shadowmere", now))
    }

    @Test fun shadowmereSubscription() {
        val json = """[{"server":"82.110.60.9","server_port":19999,"password":"pw+/=","method":"aes-256-gcm","plugin":"","plugin_opts":null,"remarks":"🇦🇹 Vienna, 9, Austria"},
            {"server":"51.222.136.236","server_port":8882,"password":"x","method":"aes-256-gcm","plugin":"obfs-local","plugin_opts":"obfs=http","remarks":"🇨🇦 QC, Canada"}]"""
        val b = OutlineParser.parseSip008(json, "shadowmere", now)!!
        assertEquals("a plugin key is skipped", 1, b.skipped)
        val s = b.servers.single()
        assertEquals("shadowmere · Vienna, Austria", s.name)
        assertEquals("AT", s.country)
        assertEquals(0L, s.checkedAt)
        assertEquals(s.id, OutlineParser.parse(s.uri, "x", now)!!.id)
    }

    @Test fun shadowmerePlaceNames() {
        assertEquals("Amsterdam, The Netherlands", OutlineParser.placeName("Amsterdam, NH, The Netherlands"))
        assertEquals("St Petersburg, Russia", OutlineParser.placeName("St Petersburg, SPE, Russia"))
        assertEquals("Canada", OutlineParser.placeName("🇨🇦 QC, Canada"))
        assertEquals("France", OutlineParser.placeName("France"))
        assertEquals("", OutlineParser.placeName(""))
    }

    @Test fun theShippedShadowmereCopyReads() {
        val text = java.io.File("src/main/assets/" + OutlineSources.SEED_ASSET).readText()
        val p = OutlineParser.parseShadowmerePage(text, OutlineSources.SHADOWMERE, now)!!
        assertTrue(p.batch.servers.size >= 20)
        assertEquals(0, p.batch.skipped)
        assertTrue(p.batch.servers.all { it.shadowmere && it.name.startsWith("shadowmere · ") && it.checkedAt > 0 && it.country != null })
    }

    @Test fun isoTimes() {
        assertEquals(1_792_954_815_000L, OutlineParser.isoTime("2026-10-25T19:00:15Z"))
        assertEquals(0L, OutlineParser.isoTime("yesterday"))
    }
}
