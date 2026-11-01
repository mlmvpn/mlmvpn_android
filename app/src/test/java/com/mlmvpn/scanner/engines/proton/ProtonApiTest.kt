package com.mlmvpn.scanner.engines.proton

import com.mlmvpn.core.warp.TweetNaclFast
import com.mlmvpn.scanner.engines.amnezia.AmzFixtures
import com.mlmvpn.scanner.engines.amnezia.AmzKind
import com.mlmvpn.scanner.engines.amnezia.AmzParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import kotlin.random.Random

class ProtonApiTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** RFC 8032, 7.1, TEST 1: the Ed25519 public key Proton registers is the standard one. */
    @Test fun `the registered key is RFC 8032 Ed25519`() {
        val k = ProtonKeys.fromSeed(hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
        assertArrayEquals(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), k.ed25519Public)
    }

    /**
     * What makes the config work: Proton's servers turn the registered Ed25519 key into an X25519 one
     * (the Edwards-to-Montgomery map, u = (1 + y) / (1 - y)), and WireGuard on the phone derives its
     * public key from the private key in the config. The two must be the same key.
     */
    @Test fun `the WireGuard key is the X25519 twin of the registered key`() {
        val p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        repeat(20) { i ->
            val k = ProtonKeys.fromSeed(ByteArray(32) { (it * 7 + i * 13 + 1).toByte() })
            val yBytes = k.ed25519Public.reversedArray().also { it[0] = (it[0].toInt() and 0x7f).toByte() }
            val y = BigInteger(1, yBytes)
            val u = BigInteger.ONE.add(y).multiply(BigInteger.ONE.subtract(y).mod(p).modInverse(p)).mod(p)
            val montgomery = u.toByteArray().reversedArray().copyOf(32)
            val wgPublic = TweetNaclFast.ScalarMult.scalseMult_base(k.x25519Private)
            assertArrayEquals("seed $i", montgomery, wgPublic)
        }
    }

    @Test fun `the private key is clamped like the dashboard's`() {
        val x = ProtonKeys.fromSeed(ByteArray(32) { 0x55 }).x25519Private
        assertEquals(0, x[0].toInt() and 7)
        assertEquals(0x40, x[31].toInt() and 0xc0)
    }

    private fun server(ip: String, key: String? = "K$ip", label: String = "", status: Int = 1) = JSONObject()
        .put("EntryIP", ip).put("X25519PublicKey", key ?: JSONObject.NULL).put("Label", label).put("Status", status)

    private fun logical(name: String, cc: String, tier: Int, score: Double, vararg servers: JSONObject, features: Int = 0, status: Int = 1) =
        JSONObject().put("Name", name).put("ExitCountry", cc).put("Tier", tier).put("Score", score)
            .put("Features", features).put("Status", status).put("Servers", JSONArray(servers.toList()))

    private val logicals = JSONArray(listOf(
        logical("NL-FREE#2", "NL", 0, 2.0, server("1.0.0.2")),
        logical("NL-FREE#1", "NL", 0, 1.0, server("1.0.0.1")),
        logical("JP-FREE#1", "JP", 0, 3.0, server("2.0.0.1")),
        logical("US-FREE#1", "US", 0, 1.5, server("3.0.0.1")),
        logical("CH#1", "CH", 2, 0.1, server("4.0.0.1")),                       // a paid tier
        logical("SE-SC#1", "SE", 0, 0.2, server("5.0.0.1"), features = 1),     // Secure Core
        logical("PL-FREE#1", "PL", 0, 0.3, server("6.0.0.1", key = null)),      // no WireGuard key
        logical("RO-FREE#1", "RO", 0, 0.4, server("7.0.0.1"), status = 0),      // in maintenance
    ))

    @Test fun `free servers are picked as the dashboard filters them, one per country first`() {
        val picks = ProtonApi.pick(logicals, maxTier = 0, have = emptySet(), max = 4, random = Random(1))
        assertEquals(listOf("NL-FREE#1", "US-FREE#1", "JP-FREE#1", "NL-FREE#2"), picks.map { it.name })
        assertEquals("NL", picks[0].country)
        assertEquals("1.0.0.1", picks[0].ip)
        assertEquals("K1.0.0.1", picks[0].publicKey)
    }

    @Test fun `servers already kept are not taken again`() {
        val picks = ProtonApi.pick(logicals, maxTier = 0, have = setOf("1.0.0.1", "3.0.0.1"), max = 6)
        assertEquals(listOf("NL-FREE#2", "JP-FREE#1"), picks.map { it.name })
        assertTrue(ProtonApi.pick(logicals, 0, setOf("1.0.0.1", "1.0.0.2", "2.0.0.1", "3.0.0.1"), 6).isEmpty())
    }

    @Test fun `of a server's machines the one with IPv6 is taken, and every IPv6 is mapped`() {
        val withV6 = server("8.0.0.1").put("EntryIPv6", "2a0d:5600:19:25::10")
        val list = JSONArray(listOf(logical("CA-FREE#18", "CA", 0, 1.0, server("8.0.0.2"), withV6, server("8.0.0.3"))))
        repeat(10) { seed ->
            val p = ProtonApi.pick(list, maxTier = 0, have = emptySet(), max = 5, random = Random(seed)).single()
            assertEquals("8.0.0.1", p.ip)
            assertEquals("2a0d:5600:19:25::10", p.ipv6)
        }
        assertEquals(mapOf("8.0.0.1" to "2a0d:5600:19:25::10"), ProtonApi.v6Map(list))
    }

    @Test fun `every server is taken by default, all countries first`() {
        val picks = ProtonApi.pick(logicals, maxTier = 0, have = emptySet(), max = ProtonApi.MAX_SERVERS)
        assertEquals(listOf("NL-FREE#1", "US-FREE#1", "JP-FREE#1", "NL-FREE#2"), picks.map { it.name })
    }

    @Test fun `a paid plan also gets its own tier`() {
        val picks = ProtonApi.pick(logicals, maxTier = 2, have = emptySet(), max = 10)
        assertEquals("CH#1", picks.first().name)
        assertFalse(picks.any { it.name == "SE-SC#1" })
    }

    @Test fun `features follow the account's flags the way JavaScript reads them`() {
        val p = ProtonApi.Pick("NL-FREE#1", "NL", "1.0.0.1", "PK", "")
        val on = ProtonApi.features(JSONObject().put("VpnAccelerator", 1).put("PortForwarding", 1), p)
        assertEquals(true, on.get("SplitTCP"))
        assertEquals(false, on.get("PortForwarding"))
        assertEquals("Android", on.getString("platform"))
        assertEquals("1.0.0.1", on.getString("peerIp"))
        assertFalse(on.has("Bouncing"))
        val off = ProtonApi.features(JSONObject().put("VpnAccelerator", 0), p.copy(label = "3"))
        assertFalse(off.has("SplitTCP"))
        assertFalse(off.has("PortForwarding"))
        assertEquals("3", off.getString("Bouncing"))
    }

    @Test fun `the config is a WireGuard server the store reads, in Proton's country`() {
        val p = ProtonApi.Pick("NO-FREE#4", "NO", "185.159.156.9", AmzFixtures.PUB, "")
        val text = ProtonApi.conf(AmzFixtures.PRIV, p)
        assertTrue(text.contains("Endpoint = 185.159.156.9:51820"))
        val s = AmzParser.parseText(text, "user", 1L, sourceCountry = p.country, fileName = p.name).servers.single()
        assertEquals(AmzKind.WG, s.kind)
        assertEquals("NO", s.country)
        assertEquals("185.159.156.9", s.host)
        assertEquals(51820, s.port)
    }
}
