package com.mlmvpn.scanner.engines.fenix

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FenixConfigTest {

    private val listJson = """
        {"data":[
          {"code":"DE","name":"Germany","cities":[{"code":"MUC","name":"Munich","servers":[{"hostname":"muc139.m1.fastly-masque.net","port":2499}]}]},
          {"code":"US","name":"CatchAll Anycast","cities":[{"code":"X","name":"X","servers":[{"hostname":"p.m1.fastly-masque.net","port":2499}]}]},
          {"code":"ZA","name":"South Africa","cities":[{"code":"JNB","name":"Johannesburg","servers":[{"hostname":"jnb70.m1.fastly-masque.net","port":2499,"quarantined":true}]}]},
          {"country":{"code":"US","name":"United States","cities":[{"code":"US","name":"US","servers":[{"hostname":"us.m1.fastly-masque.net","port":2499}]}]}}
        ]}
    """.trimIndent()

    @Test
    fun `server list keeps real countries and drops anycast and quarantined servers`() {
        val list = FenixServers.parse(listJson)
        assertEquals(listOf("DE", "US"), list.map { it.country })
        assertEquals("us.m1.fastly-masque.net", list.first { it.country == "US" }.host)
    }

    @Test
    fun `tunnel config sends nothing around the proxy`() {
        val de = FenixServers.parse(listJson).first()
        val cfg = JSONObject(FenixConfig.tunnel(de, listOf("23.235.42.8", "2a04:4e41:8001::8"), "TOKEN", 10808, null, http1 = false, mobile = false))
        assertTrue(FenixConfig.isFenix(cfg.getString("remarks")))

        val outbounds = cfg.getJSONArray("outbounds")
        val fenix0 = outbounds.getJSONObject(0)
        assertEquals("http", fenix0.getString("protocol"))
        assertEquals("Bearer TOKEN", fenix0.getJSONObject("settings").getJSONObject("headers").getString("Proxy-Authorization"))
        val tls = fenix0.getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertEquals("muc139.m1.fastly-masque.net", tls.getString("serverName"))
        assertEquals("h2", tls.getJSONArray("alpn").getString(0))
        // Dialled by address: the tunnel never needs a resolver to come up.
        assertEquals("23.235.42.8", fenix0.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("address"))

        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        val direct = (0 until rules.length()).map { rules.getJSONObject(it) }.filter { it.optString("outboundTag") == "direct" }
        // Only LAN ranges go direct.
        assertEquals(1, direct.size)
        assertTrue(direct.single().getJSONArray("ip").toString().contains("192.168.0.0/16"))
        // UDP never reaches "direct": dropped or refused inside the tunnel.
        val udp = (0 until rules.length()).map { rules.getJSONObject(it) }.filter { it.optString("network") == "udp" }
        assertTrue(udp.isNotEmpty())
        assertFalse(udp.any { it.optString("outboundTag") == "direct" })
        // No IPv6 answers: the TUN has no IPv6, so nothing can leave around it.
        assertEquals("UseIPv4", cfg.getJSONObject("dns").getString("queryStrategy"))
        // The second path is the standby behind the balancer.
        assertEquals(2, cfg.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0).getJSONArray("selector").length())
    }

    @Test
    fun `http1 fallback asks for HTTP 1 1 only`() {
        val de = FenixServers.parse(listJson).first()
        val o = FenixConfig.outbound("t", de, "23.235.42.8", "X", http1 = true)
        assertEquals("http/1.1", o.getJSONObject("streamSettings").getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
    }
}
