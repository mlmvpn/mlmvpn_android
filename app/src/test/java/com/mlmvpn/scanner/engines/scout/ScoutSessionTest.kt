package com.mlmvpn.scanner.engines.scout

import org.junit.Assert.*
import org.junit.Test

class ScoutSessionTest {
    @Test fun `session measurement pins one outbound without background probes or direct fallback`() {
        val outbound = org.json.JSONObject().put("tag", "original").put("protocol", "vless")
        val config = org.json.JSONObject(ScoutSessionPolicy.probeConfig(outbound))
        assertFalse(config.has("observatory"))
        assertFalse(config.has("routing"))
        assertEquals(1, config.getJSONArray("outbounds").length())
        assertEquals("proxy", config.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
        assertEquals("original", outbound.getString("tag"))
    }
    @Test fun `expired or unfinished GitHub sessions cannot be tested as live connections`() {
        for (status in listOf("READY", "ACTIVE", "EXPIRING_SOON")) {
            assertTrue(ScoutSessionPolicy.usable(status, 2000, 1000))
            assertFalse(ScoutSessionPolicy.usable(status, 1000, 1000))
            assertTrue(ScoutSessionPolicy.usable(status, 0, 1000))
        }
        for (status in listOf("SETTING_UP", "FAILED", "ENDING", "ENDED", ""))
            assertFalse(ScoutSessionPolicy.usable(status, 2000, 1000))
    }
    @Test fun `new relay handoffs must be ready and cannot roll back revision`() {
        assertTrue(ScoutSessionPolicy.acceptHandoff(3, 3, "ready"))
        assertTrue(ScoutSessionPolicy.acceptHandoff(3, 4, "ready"))
        assertFalse(ScoutSessionPolicy.acceptHandoff(3, 2, "ready"))
        assertFalse(ScoutSessionPolicy.acceptHandoff(3, 4, "failed"))
    }
}
