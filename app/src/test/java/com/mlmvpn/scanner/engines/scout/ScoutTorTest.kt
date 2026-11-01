package com.mlmvpn.scanner.engines.scout

import org.junit.Assert.*
import org.junit.Test

class ScoutTorTest {
    @Test fun `direct Tor uses a private listener and the requested upstream only`() {
        val config = ScoutTorPolicy.config("/data/private tor", 19222, emptyList(), null, null, "127.0.0.1:20870")
        assertTrue(config.contains("SocksPort 127.0.0.1:19222\n"))
        assertTrue(config.contains("DataDirectory \"/data/private tor\""))
        assertTrue(config.contains("Socks5Proxy 127.0.0.1:20870"))
        assertFalse(config.contains("UseBridges 1"))
        assertFalse(config.contains("0.0.0.0"))
    }

    @Test fun `bridge transport gets its proxy separately from Tor`() {
        val config = ScoutTorPolicy.config("/data/tor", 19222, listOf("meek_lite 192.0.2.1:443 url=https://example.org/"),
            "meek_lite", "127.0.0.1:32123", "127.0.0.1:20870")
        assertTrue(config.contains("UseBridges 1"))
        assertTrue(config.contains("ClientTransportPlugin meek_lite socks5 127.0.0.1:32123"))
        assertFalse(config.contains("Socks5Proxy"))
    }

    @Test fun `only requested loopback transport announcements are usable`() {
        assertEquals("127.0.0.1:32123", ScoutTorPolicy.listener("CMETHOD obfs4 socks5 127.0.0.1:32123", "obfs4"))
        for (line in listOf("CMETHOD meek_lite socks5 127.0.0.1:32123", "CMETHOD obfs4 socks5 192.0.2.1:80",
            "CMETHOD obfs4 socks4 127.0.0.1:80", "CMETHOD obfs4 socks5 127.0.0.1:99999", "CMETHODS DONE")) {
            assertNull(ScoutTorPolicy.listener(line, "obfs4"))
        }
    }

    @Test(expected = IllegalArgumentException::class) fun `bridge lines cannot inject torrc directives`() {
        ScoutTorPolicy.config("/data/tor", 19222, listOf("192.0.2.1:443\nSocksPort 0.0.0.0:80"), null, null, null)
    }
}
