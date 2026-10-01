package com.mlmvpn.scanner.engines.amnezia

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shapes taken from the real lists (keys replaced with made-up ones). */
object AmzFixtures {
    fun key(seed: Int): String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + seed).toByte() })
    val PRIV = key(1)
    val PUB = key(2)
    val PSK = key(3)
    const val WARP = WgConfig.WARP_PEER_KEY

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** v2ray-config's bundle line: WARP, reserved, MahsaNG noise, a flag in the name. */
    fun wgUri(host: String = "162.159.192.17", port: Int = 4500, name: String = "[1] 🇺🇸 | US | 174ms") =
        "wireguard://${enc(PRIV)}@$host:$port?address=${enc("172.16.0.2/32")}&reserved=181%2C54%2C101&publickey=${enc(WARP)}" +
            "&mtu=1280&keepalive=5&wnoise=random&wnoisecount=5&wnoisedelay=2-5&wpayloadsize=5-10#${enc(name).replace("+", "%20")}"

    /** FastNodes' sub/wireguard/*.conf. */
    fun conf(endpoint: String = "162.159.192.0:859", extra: String = "") = """
        [Interface]
        PrivateKey = $PRIV
        Address = 172.16.0.2/32,2606:4700:110:8354:f105:aa3e:23c:3103/128
        DNS = 1.1.1.1
        $extra

        [Peer]
        PublicKey = $WARP
        Endpoint = $endpoint
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent()

    /** iZbushka's AmneziaWG.zip entries: addresses without a prefix, a tab after DNS, I-packets. */
    fun awgConf(i1: String = "<b 0x494e56495445>") = """
        [Interface]
        PrivateKey = $PRIV
        Address = 172.16.0.2, 2606:4700:110:89ab:8f6e:4b2c:37ab:616c
        DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111
        MTU = 1280
        S1 = 0
        S2 = 0
        Jc = 4
        Jmin = 40
        Jmax = 70
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4
        I1 = $i1
        # Protocol masking
        Id = lenta.ru
        Ip = quic

        [Peer]
        PublicKey = $WARP
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = nl.tribukvy.ltd:891
    """.trimIndent()

    fun hy2(host: String = "130.49.161.70", name: String = "HK   HYSTERIA2-TCP-NTLS") =
        "hysteria2://p7Q7secret@$host:443?sni=hy2.aspidnet.xyz&insecure=1&obfs=salamander&obfs-password=pw#${enc(name).replace("+", "%20")}"
}

class AmzParserTest {

    private val now = 1_700_000_000_000L

    @Test fun `wireguard link with warp reserved and mahsang noise`() {
        val (cfg, name) = WgConfig.parseUri(AmzFixtures.wgUri())!!
        assertEquals("162.159.192.17", cfg.endpointHost)
        assertEquals(4500, cfg.endpointPort)
        assertEquals(listOf(181, 54, 101), cfg.reserved)
        assertTrue(cfg.warp)
        // wnoisecount=5, wpayloadsize=5-10 become AmneziaWG junk of the same shape.
        assertEquals(5, cfg.jc)
        assertEquals(5, cfg.jmin)
        assertEquals(10, cfg.jmax)
        assertTrue(cfg.hasAwg)
        assertFalse(cfg.needsAwgCore)
        assertTrue(name.contains("US"))
    }

    @Test fun `server from a wireguard link is filed under the flag in its name`() {
        val s = AmzParser.fromLink(AmzFixtures.wgUri(), "mv-wg", now)!!
        assertEquals("US", s.country)
        assertEquals(CountryFrom.LABEL, s.countryFrom)
        assertTrue(s.warp)
        assertEquals(AmzKind.AWG, s.kind)
    }

    @Test fun `conf file parsed and written back with only known keys`() {
        val cfg = WgConfig.parseConf(AmzFixtures.awgConf())!!
        assertEquals("nl.tribukvy.ltd", cfg.endpointHost)
        assertEquals(891, cfg.endpointPort)
        assertEquals(4, cfg.jc)
        val out = cfg.toConf()
        assertFalse("WireSock keys must not reach the AmneziaWG parser", out.contains("Id ="))
        assertFalse(out.contains("Ip ="))
        assertTrue(out.contains("Address = 172.16.0.2/32, 2606:4700:110:89ab:8f6e:4b2c:37ab:616c/128"))
        assertTrue(out.contains("I1 = <b 0x494e56495445>"))
        assertTrue(out.contains("DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111\n"))
    }

    @Test fun `hex-only I packets go through xray, others need the amneziawg core`() {
        assertFalse(WgConfig.parseConf(AmzFixtures.awgConf())!!.needsAwgCore)
        assertTrue(WgConfig.parseConf(AmzFixtures.awgConf("<b 0xab><r 16><t>"))!!.needsAwgCore)
        assertEquals("494e56495445", WgConfig.hexPacket("<b 0x494e56495445>"))
        assertNull(WgConfig.hexPacket("<r 16>"))
    }

    @Test fun `custom magic headers need the amneziawg core`() {
        val cfg = WgConfig.parseConf(AmzFixtures.conf(extra = "Jc = 3\nJmin = 10\nJmax = 50\nS1 = 15\nS2 = 20\nH1 = 1234\nH2 = 5678\nH3 = 9\nH4 = 10"))!!
        assertTrue(cfg.needsAwgCore)
        val s = AmzParser.fromConf(cfg.toConf(), "my-server", AmzServer.USER_SOURCE, now)!!
        assertTrue(s.needsAwgCore)
        assertEquals(AmzKind.AWG, s.kind)
    }

    @Test fun `plain conf gets compatible junk only when obfuscation is on`() {
        val cfg = WgConfig.parseConf(AmzFixtures.conf())!!
        assertFalse(cfg.hasAwg)
        assertFalse(cfg.toConf(obfuscate = false).contains("Jc"))
        val obf = cfg.toConf(obfuscate = true)
        assertTrue(obf.contains("Jc = 4"))
        assertTrue(obf.contains("H1 = 1"))
        assertTrue(obf.contains("S1 = 0"))
    }

    @Test fun `ipv4 only drops the v6 address and v6 dns`() {
        val out = WgConfig.parseConf(AmzFixtures.awgConf())!!.toConf(ipv4Only = true)
        assertTrue(out.contains("Address = 172.16.0.2/32\n"))
        assertFalse(out.contains("2606:4700:4700::1111"))
    }

    @Test fun `conf with no endpoint or a bad key is refused`() {
        assertNull(WgConfig.parseConf(AmzFixtures.conf().replace(Regex("Endpoint = .*"), "")))
        assertNull(WgConfig.parseConf(AmzFixtures.conf().replace(AmzFixtures.PRIV, "short")))
    }

    @Test fun `xray outbound carries the noise through a dialer proxy`() {
        val cfg = WgConfig.parseConf(AmzFixtures.conf())!!
        val noise = cfg.noiseOutbound("n", obfuscate = true)!!
        assertEquals("freedom", noise.getString("protocol"))
        assertEquals(4, noise.getJSONObject("settings").getJSONArray("noises").length())
        val out = cfg.xrayOutbound("x", "n")
        assertEquals("wireguard", out.getString("protocol"))
        assertEquals("n", out.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        assertNull(cfg.noiseOutbound("n", obfuscate = false))
    }

    @Test fun `awg I packet becomes a hex noise before the junk`() {
        val noise = WgConfig.parseConf(AmzFixtures.awgConf())!!.noiseOutbound("n", obfuscate = false)!!
        val first = noise.getJSONObject("settings").getJSONArray("noises").getJSONObject(0)
        assertEquals("hex", first.getString("type"))
        assertEquals("494e56495445", first.getString("packet"))
    }

    @Test fun `hysteria2 keeps certificate checks and drops port hopping`() {
        val clean = AmzParser.sanitizeHy2(AmzFixtures.hy2().replace("?sni", "?mport=20000-30000&sni"))
        assertFalse(clean.contains("insecure"))
        assertFalse(clean.contains("mport"))
        assertTrue(clean.contains("obfs=salamander"))
        val s = AmzParser.fromLink(AmzFixtures.hy2(), "a94-hy2", now)!!
        assertEquals(AmzKind.HY2, s.kind)
        assertEquals("HK", s.country)
    }

    @Test fun `mixed list keeps only the three kinds and dedupes`() {
        val body = listOf(
            AmzFixtures.hy2(), AmzFixtures.hy2(),
            "vless://00000000-0000-0000-0000-000000000000@1.2.3.4:443?security=tls&sni=a.com#x",
            "hysteria://1.162.128.195:18490?auth=dongtaiwang.com",
            AmzFixtures.wgUri(),
        ).joinToString("\n")
        val b = AmzParser.parseText(body, "src", now)
        assertEquals(2, b.servers.size)
        val b64 = AmzParser.parseText(Base64.getEncoder().encodeToString(body.toByteArray()), "src", now)
        assertEquals(2, b64.servers.size)
    }

    @Test fun `a list about one country files everything there`() {
        val s = AmzParser.parseText(AmzFixtures.hy2(name = "no country here"), "x", now, sourceCountry = "DE").servers.single()
        assertEquals("DE", s.country)
        assertEquals(CountryFrom.SOURCE, s.countryFrom)
    }

    @Test fun `chinese and city names give a country, channel ads do not`() {
        assertEquals("US", AmzParser.country("美国 V2CROSS.COM", null).first)
        assertEquals("DE", AmzParser.country("Frankfurt node 3", null).first)
        assertNull(AmzParser.country("t.me/SOSkeyNET", null).first)
    }

    @Test fun `zip of conf files`() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("WARPm1_01.conf")); z.write(AmzFixtures.awgConf().toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("WARPm1_02.conf")); z.write(AmzFixtures.conf("1.2.3.4:500").toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("readme.md")); z.write("x".toByteArray()); z.closeEntry()
        }
        val b = AmzParser.parseZip(bos.toByteArray(), "dk-awg", now)
        assertEquals(2, b.servers.size)
        assertNotNull(b.servers.firstOrNull { it.kind == AmzKind.AWG })
    }

    @Test fun `codec round trip`() {
        val servers = AmzParser.parseText(listOf(AmzFixtures.hy2(), AmzFixtures.wgUri()).joinToString("\n"), "src", now).servers
        val s = AmzState(
            servers = servers,
            stats = mapOf("net1" to mapOf(servers[0].id to AmzStat(ok = 2, ewmaMs = 120.5, lastMs = 110, lastOkAt = now, lastTestAt = now))),
            netSeen = mapOf("net1" to now), sources = mapOf("src" to AmzSourceStat(10, 3)),
            tombstones = mapOf("gone" to now), prefs = AmzPrefs(obfuscate = false), lastRefreshAt = now, lastConnectedId = servers[1].id,
        )
        val back = AmzCodec.decode(AmzCodec.encode(s))
        assertEquals(s, back)
        assertTrue(JSONObject(AmzCodec.encode(s)).has("servers"))
    }
}
