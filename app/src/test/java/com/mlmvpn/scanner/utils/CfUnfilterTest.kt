package com.mlmvpn.scanner.utils

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «رفع فیلتر کانفیگ‌ها (کلادفلر)»: PattNG's fragment + fingerprint handshake, as [XrayJsonGenerator]
 * writes it.
 *
 * What these pin down is the thing the earlier version of the switch got wrong: it removed the
 * fingerprint, and Xray reads "" as Chrome. The only value that switches uTLS off, so that the
 * cipher list counts, is the literal `unsafe` -- and it has to travel with the list.
 */
class CfUnfilterTest {

    @After
    fun tearDown() {
        XrayJsonGenerator.tlsUnfilter = false
    }

    private fun worker(
        network: String = "ws",
        address: String = "172.67.154.11",
        tls: String = "tls",
        alpn: String = "",
        cipherSuites: String = "",
        finalMask: String = "",
    ) = VpnConfig(
        protocol = "vless",
        name = "BPB",
        address = address,
        port = 443,
        uuid = "11111111-2222-3333-4444-555555555555",
        network = network,
        wsHost = "edge.example.workers.dev",
        wsPath = "/vl/abc?ed=2560",
        xhttpHost = "edge.example.workers.dev",
        xhttpPath = "/vl/abc",
        tls = tls,
        sni = "edge.example.workers.dev",
        alpn = alpn,
        fingerprint = "chrome",
        cipherSuites = cipherSuites,
        finalMask = finalMask,
    )

    private fun stream(config: VpnConfig): JSONObject {
        val json = JSONObject(XrayJsonGenerator.generateConfig(config, localPort = 10808))
        val outs = json.getJSONArray("outbounds")
        val proxy = (0 until outs.length()).map { outs.getJSONObject(it) }.first { it.optString("tag") == "proxy" }
        return proxy.getJSONObject("streamSettings")
    }

    @Test
    fun `a link that names the F&F presets works like one that spells them out`() {
        // What the Windows app's central-Worker links and the developer's client write: cs=semi-python, fm=tlshello-0-len.
        XrayJsonGenerator.tlsUnfilter = true
        val named = stream(worker(cipherSuites = "semi-python", finalMask = "tlshello-0-len"))
        val spelled = stream(worker(cipherSuites = CfUnfilter.CIPHER_SUITES, finalMask = CfUnfilter.finalMask().toString()))
        assertEquals(spelled.getJSONObject("tlsSettings").toString(), named.getJSONObject("tlsSettings").toString())
        assertEquals(spelled.getJSONObject("finalmask").toString(), named.getJSONObject("finalmask").toString())
        assertEquals("unsafe", named.getJSONObject("tlsSettings").getString("fingerprint"))
        assertEquals(CfUnfilter.CIPHER_SUITES, named.getJSONObject("tlsSettings").getString("cipherSuites"))
    }

    @Test
    fun `explicit fragment config gets unsafe and the two fragment masks`() {
        XrayJsonGenerator.tlsUnfilter = true
        val s = stream(worker(cipherSuites = CfUnfilter.CIPHER_SUITES, finalMask = CfUnfilter.finalMask().toString()))
        val tls = s.getJSONObject("tlsSettings")
        assertEquals("unsafe", tls.getString("fingerprint"))
        assertEquals(CfUnfilter.CIPHER_SUITES, tls.getString("cipherSuites"))
        assertFalse("no version ceiling: Go keeps its TLS 1.3 suites, as in PattNG", tls.has("maxVersion"))

        val tcp = s.getJSONObject("finalmask").getJSONArray("tcp")
        assertEquals(2, tcp.length())
        val first = tcp.getJSONObject(0)
        assertEquals("fragment", first.getString("type"))
        assertEquals("tlshello", first.getJSONObject("settings").getString("packets"))
        assertEquals(listOf("0", "104", "1"), first.getJSONObject("settings").getJSONArray("lengths").let { a -> (0 until a.length()).map { a.getString(it) } })
        val second = tcp.getJSONObject(1).getJSONObject("settings")
        assertEquals("1-1", second.getString("packets"))
        assertEquals("11", second.getString("maxSplit"))
    }

    @Test
    fun `optimization without a validated strategy does not force fragmentation`() {
        XrayJsonGenerator.tlsUnfilter = true
        val s = stream(worker())
        assertEquals("chrome", s.getJSONObject("tlsSettings").getString("fingerprint"))
        assertFalse(s.has("finalmask"))
    }

    @Test
    fun `switch off - the handshake is exactly what it was`() {
        XrayJsonGenerator.tlsUnfilter = false
        val s = stream(worker())
        val tls = s.getJSONObject("tlsSettings")
        assertEquals("chrome", tls.getString("fingerprint"))
        assertFalse(tls.has("cipherSuites"))
        assertFalse(s.has("finalmask"))
    }

    @Test
    fun `switch on - a server that is not behind Cloudflare keeps its handshake`() {
        XrayJsonGenerator.tlsUnfilter = true
        val s = stream(worker(network = "tcp", address = "203.0.113.7").copy(sni = "vpn.example.org", wsHost = "", xhttpHost = ""))
        val tls = s.getJSONObject("tlsSettings")
        assertEquals("chrome", tls.getString("fingerprint"))
        assertFalse(s.has("finalmask"))
    }

    @Test
    fun `switch on - Reality is another handshake and is left alone`() {
        XrayJsonGenerator.tlsUnfilter = true
        val s = stream(worker(network = "tcp", tls = "reality").copy(publicKey = "k", shortId = "ab"))
        assertFalse(s.has("tlsSettings"))
        assertFalse(s.has("finalmask"))
    }

    @Test
    fun `a link's own cs and fm count with the switch off, and cs brings the unsafe fingerprint`() {
        XrayJsonGenerator.tlsUnfilter = false
        val suites = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
        val s = stream(worker(cipherSuites = suites, finalMask = """{"tcp": []}"""))
        val tls = s.getJSONObject("tlsSettings")
        assertEquals("cipherSuites only count with unsafe, so it comes with the list", "unsafe", tls.getString("fingerprint"))
        assertEquals(suites, tls.getString("cipherSuites"))
        assertEquals(0, s.getJSONObject("finalmask").getJSONArray("tcp").length())
    }

    @Test
    fun `a link's own fm wins over the switch's masks`() {
        XrayJsonGenerator.tlsUnfilter = true
        val s = stream(worker(finalMask = """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello", "lengths": ["5"], "delays": ["0"]}}]}"""))
        assertEquals(1, s.getJSONObject("finalmask").getJSONArray("tcp").length())
    }

    @Test
    fun `a text that is not a JSON object is no finalMask`() {
        assertEquals(null, CfUnfilter.parseFinalMask(""))
        assertEquals(null, CfUnfilter.parseFinalMask("{not json"))
        assertEquals(null, CfUnfilter.parseFinalMask("[]"))
        assertTrue(CfUnfilter.parseFinalMask("""{"tcp": []}""") != null)
    }

    @Test
    fun `WebSocket offers http 1_1 only and xhttp offers h2, since unsafe offers the ALPN as written`() {
        XrayJsonGenerator.tlsUnfilter = true
        val ws = stream(worker(network = "ws", alpn = "h2,http/1.1", cipherSuites = CfUnfilter.CIPHER_SUITES)).getJSONObject("tlsSettings").getJSONArray("alpn")
        assertEquals(1, ws.length())
        assertEquals("http/1.1", ws.getString(0))

        val xhttp = stream(worker(network = "xhttp", cipherSuites = CfUnfilter.CIPHER_SUITES)).getJSONObject("tlsSettings").getJSONArray("alpn")
        assertEquals("h2", xhttp.getString(0))
    }

    @Test
    fun `Cloudflare is recognised by name, by address in its ranges, and by a Worker transport`() {
        assertTrue(CfUnfilter.isCloudflareFronted(worker(network = "tcp", address = "203.0.113.7")))   // the workers.dev name
        assertTrue(CfUnfilter.isCloudflareFronted(worker(network = "tcp", address = "104.16.1.1").copy(sni = "x.example.org", wsHost = "", xhttpHost = "")))
        assertTrue(CfUnfilter.isCloudflareFronted(worker(network = "tcp", address = "2606:4700:3030::1").copy(sni = "x.example.org", wsHost = "", xhttpHost = "")))
        assertFalse(CfUnfilter.isCloudflareFronted(worker(network = "ws", address = "vpn.example.org").copy(sni = "vpn.example.org", wsHost = "vpn.example.org", xhttpHost = "")))
        assertFalse(CfUnfilter.isCloudflareFronted(worker(network = "tcp", address = "203.0.113.7").copy(sni = "vpn.example.org", wsHost = "", xhttpHost = "")))
        assertFalse(CfUnfilter.isCloudflareFronted(worker(tls = "reality")))
    }
}
