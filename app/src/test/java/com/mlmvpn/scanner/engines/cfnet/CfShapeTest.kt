package com.mlmvpn.scanner.engines.cfnet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfShapeTest {

    /** A stream as ConfigBuilder makes it: a TLS block with chrome by default, plus a network type. */
    private fun stream(network: String = "ws") = JSONObject()
        .put("network", network)
        .put("security", "tls")
        .put("tlsSettings", JSONObject().put("serverName", "edge.example.workers.dev").put("fingerprint", "chrome"))

    @Test fun echMatchesTheScreenshot() {
        val s = stream()
        assertTrue(CfShape.apply(s, ShapeSpec(TunnelShape.ECH), "ws"))
        val tls = s.getJSONObject("tlsSettings")
        assertEquals("chrome", tls.getString("fingerprint"))                 // screenshot: fingerprint = chrome
        assertEquals("cloudflare-ech.com+udp://1.1.1.1", tls.getString("echConfigList"))  // screenshot value
        assertFalse("screenshot: finalMask removed", s.has("finalmask"))     // screenshot: finalMask empty
        assertFalse(tls.has("cipherSuites"))
    }

    @Test fun echDialsWithTheFetchedKeyWhenThereIsOne() {
        val key = "AEX+DQBBpQAgACB/2z6yYyi0kVcB5vN0bBp1hMnQeX5Nq2tKoD3Ut1JHMwAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA="
        CfShape.echKeyProvider = { key }
        try {
            val s = stream()
            CfShape.apply(s, ShapeSpec(TunnelShape.ECH), "ws")
            assertEquals(key, s.getJSONObject("tlsSettings").getString("echConfigList"))
            // A link's short form becomes the key; a key or another name stays as written.
            assertEquals(key, CfShape.upgradeEch("cloudflare-ech.com+udp://1.1.1.1"))
            assertEquals(key, CfShape.upgradeEch("Cloudflare-ECH.com+https://8.8.8.8/dns-query"))
            assertEquals("other.example+udp://1.1.1.1", CfShape.upgradeEch("other.example+udp://1.1.1.1"))
            // Links keep the short form: the key rotates and a stored link must not go stale.
            assertEquals(CfShape.ECH_DEFAULT, CfShape.linkParams(ShapeSpec(TunnelShape.ECH), "ws")["ech"])
        } finally {
            CfShape.echKeyProvider = { null }
        }
        // No key fetched (or a provider that fails): the short form, as before.
        assertEquals(CfShape.ECH_DEFAULT, CfShape.echValue())
        CfShape.echKeyProvider = { error("no network") }
        try { assertEquals(CfShape.ECH_DEFAULT, CfShape.echValue()) } finally { CfShape.echKeyProvider = { null } }
    }

    @Test fun fragFpMatchesPattNgHandshake() {
        val s = stream("ws")
        CfShape.apply(s, ShapeSpec(TunnelShape.FRAG_FP), "ws")
        val tls = s.getJSONObject("tlsSettings")
        assertEquals("unsafe", tls.getString("fingerprint"))
        assertEquals(CfShape.CIPHER_SUITES, tls.getString("cipherSuites"))
        assertEquals("http/1.1", tls.getJSONArray("alpn").getString(0))      // ws → http/1.1 under unsafe
        val tcp = s.getJSONObject("finalmask").getJSONArray("tcp")
        assertEquals(2, tcp.length())
        assertEquals("tlshello", tcp.getJSONObject(0).getJSONObject("settings").getString("packets"))
        assertEquals("11", tcp.getJSONObject(1).getJSONObject("settings").getString("maxSplit"))
    }

    @Test fun fragChromeKeepsUtlsButStillFragments() {
        val s = stream("ws")
        CfShape.apply(s, ShapeSpec(TunnelShape.FRAG_CHROME), "ws")
        val tls = s.getJSONObject("tlsSettings")
        assertEquals("chrome", tls.getString("fingerprint"))
        assertFalse(tls.has("cipherSuites"))
        assertTrue(s.has("finalmask"))
    }

    @Test fun chromeIsPlainAndClearsPriorShaping() {
        val s = stream("ws").put("finalmask", CfShape.finalMaskTlsHello())
        s.getJSONObject("tlsSettings").put("echConfigList", "x").put("cipherSuites", "y").put("fingerprint", "unsafe")
        CfShape.apply(s, ShapeSpec(TunnelShape.CHROME), "ws")
        val tls = s.getJSONObject("tlsSettings")
        assertEquals("chrome", tls.getString("fingerprint"))
        assertFalse(s.has("finalmask"))
        assertFalse(tls.has("echConfigList"))
        assertFalse(tls.has("cipherSuites"))
    }

    @Test fun fingerprintShapesSetTheProfile() {
        for ((shape, fp) in listOf(TunnelShape.FIREFOX to "firefox", TunnelShape.SAFARI to "safari", TunnelShape.RANDOMIZED to "randomized")) {
            val s = stream("xhttp")
            CfShape.apply(s, ShapeSpec(shape), "xhttp")
            assertEquals(fp, s.getJSONObject("tlsSettings").getString("fingerprint"))
        }
    }

    @Test fun engineOnlyShapesAreNotXrayTls() {
        for (shape in listOf(TunnelShape.MASQUE_H2, TunnelShape.WARP, TunnelShape.CHAIN_TOR, TunnelShape.CHAIN_PSIPHON)) {
            val s = stream("ws")
            assertFalse("$shape is not an Xray TLS shape", CfShape.apply(s, ShapeSpec(shape), "ws"))
        }
    }

    @Test fun xhttpUnderFragFpOffersH2() {
        val s = stream("xhttp")
        CfShape.apply(s, ShapeSpec(TunnelShape.FRAG_FP), "xhttp")
        assertEquals("h2", s.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
    }

    // What a combine writes into a config so it carries the handshake its scan proved.
    @Test fun linkParamsCarryEachHandshake() {
        val ech = CfShape.linkParams(ShapeSpec(TunnelShape.ECH), "ws")
        assertEquals(CfShape.ECH_DEFAULT, ech["ech"])
        assertEquals("chrome", ech["fp"])
        assertTrue(ech.containsKey("cs") && ech["cs"] == null && ech.containsKey("fm") && ech["fm"] == null)

        val frag = CfShape.linkParams(ShapeSpec(TunnelShape.FRAG_FP), "ws")
        assertEquals(CfShape.CIPHER_SUITES, frag["cs"])
        assertEquals(CfShape.finalMaskTlsHello().toString(), frag["fm"])
        assertTrue(frag.containsKey("ech") && frag["ech"] == null)

        val fragChrome = CfShape.linkParams(ShapeSpec(TunnelShape.FRAG_CHROME), "ws")
        assertEquals("chrome", fragChrome["fp"])
        assertTrue(fragChrome["fm"] != null && fragChrome["cs"] == null)

        assertEquals("safari", CfShape.linkParams(ShapeSpec(TunnelShape.SAFARI), "ws")["fp"])
        assertTrue(CfShape.linkParams(ShapeSpec(TunnelShape.AS_IS), "ws").isEmpty())
        assertTrue(CfShape.linkParams(ShapeSpec(TunnelShape.WARP), "ws").isEmpty())
        // An XHTTP tuning means nothing on a WebSocket config, so nothing is written.
        assertTrue(CfShape.linkParams(ShapeSpec(TunnelShape.XHTTP_H2), "ws").isEmpty())
        val xh = CfShape.linkParams(ShapeSpec(TunnelShape.XHTTP_H11), "xhttp")
        assertEquals("http/1.1", xh["alpn"]); assertEquals("packet-up", xh["mode"])
    }

    // A link copied from the Windows app (or the developer's client) names the F&F values instead of spelling them out.
    @Test fun presetNamesInALinkExpandToTheFullValues() {
        assertEquals(CfShape.CIPHER_SUITES, CfShape.resolveCipherSuites("semi-python"))
        assertEquals(CfShape.CIPHER_SUITES, CfShape.resolveCipherSuites(" Semi-Python "))
        assertEquals("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", CfShape.resolveCipherSuites("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"))
        assertEquals("", CfShape.resolveCipherSuites(null))

        assertEquals(CfShape.finalMaskTlsHello().toString(), CfShape.resolveFinalMask("tlshello-0-len")!!.toString())
        assertEquals(CfShape.finalMaskTlsHello().toString(), CfShape.resolveFinalMask("TLSHELLO-0-LEN")!!.toString())
        val v1 = CfShape.resolveFinalMask("tlshello-5-94-1")!!.getJSONArray("tcp")
        assertEquals("5", v1.getJSONObject(0).getJSONObject("settings").getJSONArray("lengths").getString(0))
        assertEquals("355", v1.getJSONObject(1).getJSONObject("settings").getString("maxSplit"))
        // a JSON object still works; anything else is no mask rather than a failure
        assertEquals("tlshello", CfShape.resolveFinalMask("{\"tcp\":[{\"type\":\"fragment\",\"settings\":{\"packets\":\"tlshello\"}}]}")!!.getJSONArray("tcp").getJSONObject(0).getJSONObject("settings").getString("packets"))
        assertEquals(null, CfShape.resolveFinalMask("not-a-preset"))
        assertEquals(null, CfShape.resolveFinalMask("{broken"))
        assertEquals(null, CfShape.resolveFinalMask(""))
        assertEquals(null, CfShape.resolveFinalMask(null))
    }

    @Test fun literalFamilyTellsAddressesFromNames() {
        assertEquals(Family.V4, CfShape.literalFamily("104.16.12.9"))
        assertEquals(Family.V6, CfShape.literalFamily("[2606:4700::6810:1]"))
        assertEquals(Family.V6, CfShape.literalFamily("2a06:98c1:3120::3"))
        assertEquals(null, CfShape.literalFamily("edge.example.workers.dev"))
        assertEquals(null, CfShape.literalFamily("1.2.3"))
        assertEquals(null, CfShape.literalFamily("256.1.1.1"))
        assertEquals(null, CfShape.literalFamily(""))
    }
}
