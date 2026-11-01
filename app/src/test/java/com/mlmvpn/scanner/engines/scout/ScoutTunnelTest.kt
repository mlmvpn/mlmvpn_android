package com.mlmvpn.scanner.engines.scout

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ScoutTunnelTest {
    @Test fun `forced cleanup can target only the dedicated probe process of this app`() {
        assertTrue(ScoutTunnelPolicy.mayKill(42, 41, 1000, 1000, "app:scouttunnel", "app"))
        assertFalse(ScoutTunnelPolicy.mayKill(41, 41, 1000, 1000, "app:scouttunnel", "app"))
        assertFalse(ScoutTunnelPolicy.mayKill(42, 41, 2000, 1000, "app:scouttunnel", "app"))
        assertFalse(ScoutTunnelPolicy.mayKill(42, 41, 1000, 1000, "app", "app"))
        assertFalse(ScoutTunnelPolicy.mayKill(42, 41, 1000, 1000, "app:tunnel", "app"))
    }
    @Test fun `probe config pins its protocol family and files without inheriting bypass routes`() {
        val root = File("build/scout-owned-test").absoluteFile
        val base = JSONObject().put("config_path", "/live/tunnel.toml").put("ip_scan", "both")
            .put("forced_peer", "old:443").put("route_direct", "0.0.0.0/0").put("route_block", "all")
            .put("obfuscation_profile", "aggressive").put("h2_fragmentation", true)
        val config = ScoutTunnelPolicy.config(base, root, "masque", Fam.V6, "h2", 14001, null)
        assertEquals("masque", config.getString("protocol"))
        assertEquals("v6", config.getString("ip_scan"))
        assertEquals("h2", config.getString("masque_transport"))
        assertEquals("127.0.0.1:14001", config.getString("listen"))
        for (key in listOf("config_path", "wireguard_config_path", "masque_config_path", "endpoint_cache_path"))
            assertEquals(root.canonicalFile, File(config.getString(key)).canonicalFile.parentFile)
        assertFalse(config.has("forced_peer"))
        assertFalse(config.has("route_direct"))
        assertFalse(config.has("route_block"))
        assertEquals("aggressive", config.getString("obfuscation_profile"))
        assertEquals("/live/tunnel.toml", base.getString("config_path"))
        assertTrue(ScoutTunnelPolicy.owned(config, root))
        config.put("config_path", File(root, "../outside.toml").path)
        assertFalse(ScoutTunnelPolicy.owned(config, root))
    }
    @Test fun `country endpoint is explicit and incomplete identities cannot enter the reusable cache`() {
        val config = ScoutTunnelPolicy.config(JSONObject(), File("build/scout-owned-test"), "wireguard", Fam.V6, "h3", 14002, "[2001:db8::1]:2408")
        assertEquals("[2001:db8::1]:2408", config.getString("forced_peer"))
        val identity = "device_id = \"test\"\nwg_private_key = \"key\"\n"
        assertTrue(ScoutTunnelPolicy.identityUsable(identity, false))
        assertFalse(ScoutTunnelPolicy.identityUsable(identity, true))
        assertFalse(ScoutTunnelPolicy.identityUsable("wg_private_key = \"key\"", false))
    }
}
