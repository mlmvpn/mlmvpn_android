package com.mlmvpn.scanner.engines.amnezia

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmzCarrierTest {

    private val carrier = AmzCarrier.Carrier(
        WgConfig.parseConf(AmzFixtures.conf("[2606:4700:d0::a29f:c001]:2408"))!!, own = false, net = "n", at = 0L,
    )

    @Test fun `what worked here is tried first, IPv6 edges only where the line has IPv6`() {
        val withV6 = AmzCarrier.candidates(true, "8.6.112.4:894", listOf("[2606:4700:d0::1]:500", "8.6.112.9:1180"))
        assertEquals("8.6.112.4:894", withV6.first())
        assertTrue(withV6.any { it.startsWith("[2606:4700:d0::a29f:c001]") })
        assertTrue("[2606:4700:d0::1]:500" in withV6)

        val noV6 = AmzCarrier.candidates(false, null, listOf("[2606:4700:d0::1]:500", "8.6.112.9:1180"))
        assertFalse(noV6.any { it.startsWith("[") })
        assertTrue("8.6.112.9:1180" in noV6)
        assertEquals(noV6.size, noV6.toSet().size)
    }

    @Test fun `an endpoint swap keeps the keys and brackets an IPv6 address`() {
        val c = AmzCarrier.withEndpoint(carrier.cfg, "[2606:4700:d0::a29f:c001]:1701")
        assertEquals("2606:4700:d0::a29f:c001", c.endpointHost)
        assertEquals(1701, c.endpointPort)
        assertEquals("[2606:4700:d0::a29f:c001]:1701", c.endpoint())
        assertEquals(carrier.cfg.privateKey, c.privateKey)
    }

    @Test fun `the tunnel hands the server's packets to the carrier through a loopback door`() {
        val server = WgConfig.parseConf(AmzFixtures.conf("185.165.170.3:65142"))!!.copy(publicKey = AmzFixtures.PUB)
        val j = JSONObject(AmzCarrier.tunnelConfig(server, carrier, 10808, 40123))
        assertTrue(AmzCarrier.isCarrierConfig(j.getString("remarks")))

        val outs = j.getJSONArray("outbounds")
        val proxy = outs.getJSONObject(0)
        assertEquals("proxy", proxy.getString("tag"))
        val peer = proxy.getJSONObject("settings").getJSONArray("peers").getJSONObject(0)
        assertEquals("127.0.0.1:40123", peer.getString("endpoint"))
        assertTrue(proxy.getJSONObject("settings").getInt("mtu") <= AmzCarrier.INNER_MTU)

        val carrierOut = (0 until outs.length()).map { outs.getJSONObject(it) }.first { it.getString("tag") == AmzCarrier.TAG }
        assertEquals(AmzCarrier.NOISE_TAG, carrierOut.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))

        val door = (0 until j.getJSONArray("inbounds").length()).map { j.getJSONArray("inbounds").getJSONObject(it) }
            .first { it.getString("protocol") == "dokodemo-door" }
        assertEquals(40123, door.getInt("port"))
        assertEquals("185.165.170.3", door.getJSONObject("settings").getString("address"))
        assertEquals(65142, door.getJSONObject("settings").getInt("port"))
        assertEquals("udp", door.getJSONObject("settings").getString("network"))

        // The door's rule comes before the catch-all, or the server's packets would loop into itself.
        val rules = j.getJSONObject("routing").getJSONArray("rules")
        val doorRule = (0 until rules.length()).first { rules.getJSONObject(it).optJSONArray("inboundTag")?.getString(0) == door.getString("tag") }
        val catchAll = (0 until rules.length()).first { rules.getJSONObject(it).optString("network") == "tcp,udp" }
        assertEquals(AmzCarrier.TAG, rules.getJSONObject(doorRule).getString("outboundTag"))
        assertTrue(doorRule < catchAll)
        assertEquals(10808 + 10000, (0 until j.getJSONArray("inbounds").length()).map { j.getJSONArray("inbounds").getJSONObject(it) }
            .first { it.getString("protocol") == "http" }.getInt("port"))
    }
}
