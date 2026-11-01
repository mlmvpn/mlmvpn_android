package com.mlmvpn.scanner.engines.proton

import com.mlmvpn.scanner.engines.amnezia.AmzFixtures
import com.mlmvpn.scanner.engines.amnezia.AmzKind
import com.mlmvpn.scanner.engines.amnezia.AmzParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonEngineTest {

    /** The shape Proton's dashboard writes (its own comment lines included), with made-up keys. */
    private val conf = """
        [Interface]
        # Key for the Android device
        # NetShield = 0
        # Moderate NAT = off
        # NAT-PMP (Port Forwarding) = off
        # VPN Accelerator = on
        PrivateKey = ${AmzFixtures.PRIV}
        Address = 10.2.0.2/32
        DNS = 10.2.0.1

        [Peer]
        # NL-FREE#12
        PublicKey = ${AmzFixtures.PUB}
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 185.159.156.1:51820
    """.trimIndent()

    @Test fun `a Proton dashboard config is read as a plain WireGuard server in its country`() {
        val s = AmzParser.parseText(conf, "user", 1L, fileName = "NL-FREE#12").servers.singleOrNull()
        assertNotNull(s)
        assertEquals(AmzKind.WG, s!!.kind)
        assertEquals("NL", s.country)
        assertEquals("185.159.156.1", s.host)
        assertEquals(51820, s.port)
    }

    @Test fun `only Proton's own hosts are allowed in the page`() {
        assertTrue(ProtonEngine.isProtonHost("account.protonvpn.com"))
        assertTrue(ProtonEngine.isProtonHost("account.proton.me"))
        assertTrue(ProtonEngine.isProtonHost("proton.me"))
        assertFalse(ProtonEngine.isProtonHost("evilproton.me"))
        assertFalse(ProtonEngine.isProtonHost("proton.me.evil.com"))
        assertFalse(ProtonEngine.isProtonHost(null))
    }

    @Test fun `the capture script carries the token and no placeholder`() {
        val js = ProtonEngine.captureJs("abc-123")
        assertTrue(js.contains("MlmProton.conf('abc-123',"))
        assertFalse(js.contains("__MLM_TOKEN__"))
    }
}
