package com.mlmvpn.scanner.engines.scout

import org.junit.Assert.*
import org.junit.Test

class ScoutWarpTest {
    @Test fun `only a successful trace with an exact warp value proves WARP`() {
        assertTrue(ScoutWarpPolicy.verified(200, "ip=192.0.2.1\nwarp=on\nloc=NL\n"))
        assertTrue(ScoutWarpPolicy.verified(200, "warp=plus\r\n"))
        for (body in listOf("warp=off", "warp=only", "<html>warp=on</html>", "", "warp=on\nwarp=off")) {
            assertFalse(body, ScoutWarpPolicy.verified(200, body))
        }
        assertFalse(ScoutWarpPolicy.verified(302, "warp=on"))
        assertFalse(ScoutWarpPolicy.verified(500, "warp=on"))
    }

    @Test fun `probe does not inherit a previous tunnel or bootstrap proxy`() {
        val parent = mapOf("PATH" to "bin", "AETHER_PEER" to "stale", "AETHER_IP" to "v6",
            "HTTPS_PROXY" to "http://old", "all_proxy" to "socks5://old", "no_proxy" to "*")
        assertEquals(mapOf("PATH" to "bin"), ScoutWarpPolicy.cleanParent(parent))
    }

    @Test fun `explicit probe family overrides automatic family selection`() {
        for (fam in listOf(Fam.V4, Fam.V6)) {
            val env = ScoutWarpPolicy.pinFamily(mapOf("AETHER_IP" to "both", "AETHER_SOCKS" to "127.0.0.1:1234"), fam)
            assertEquals(if (fam == Fam.V4) "v4" else "v6", env["AETHER_IP"])
            assertEquals("127.0.0.1:1234", env["AETHER_SOCKS"])
        }
    }
}
