package com.mlmvpn.core.tunnel.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real lines, as the sources served them on 2026-10-03. */
class BridgeLinesTest {

    private fun ok(line: String) = (BridgeLines.parse(line) as BridgeParse.Ok).bridge
    private fun bad(line: String) = (BridgeLines.parse(line) as BridgeParse.Bad).error

    @Test
    fun `obfs4 with and without the Bridge prefix is the same bridge`() {
        val line = "obfs4 100.8.170.63:50164 FBA329BA4F5FE648456C3A28BBAA3C7EBD118E08 cert=DKA6xS0N6c4I1CFVmWc/qgAEhPBgR/dPQyXhbkjt082Ka2xmq1pwSUpCPt626uuKW2Z1TA iat-mode=0"
        val a = ok(line)
        val b = ok("Bridge  $line")
        assertEquals(BridgeTransport.OBFS4, a.transport)
        assertEquals("100.8.170.63", a.host)
        assertEquals(50164, a.port)
        assertEquals(IpFamily.V4, a.family)
        assertEquals(a.id, b.id)
        assertEquals(line, a.line)
    }

    @Test
    fun `webtunnel is dialled at its URL host, not its placeholder address`() {
        val b = ok("webtunnel [2001:db8:1169:5d59:447d:1feb:3595:b174]:443 9E22636EA817AD4FE43F2EC14DEED5131FED1CB0 url=https://cdn-40.triplebit.dev/aif5ohWa4aWiephu ver=0.0.2")
        assertEquals(BridgeTransport.WEBTUNNEL, b.transport)
        assertEquals(IpFamily.V6, b.family)
        assertTrue(b.addressIsPlaceholder)
        assertEquals("cdn-40.triplebit.dev", b.dialHost)
        assertEquals(443, b.dialPort)
    }

    @Test
    fun `vanilla needs its fingerprint`() {
        val b = ok("103.149.168.242:9443 91637DE9ED5B069722DA7A5796926EE13238694D")
        assertEquals(BridgeTransport.VANILLA, b.transport)
        assertNull(b.transport.wire)
        assertEquals(BridgeLineError.BAD_FINGERPRINT, bad("103.149.168.242:9443"))
    }

    @Test
    fun `snowflake from Moat is dialled at its front`() {
        val b = ok("snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org front=www.phpmyadmin.net,cdn.zk.mk ice=stun:stun.antisip.com:3478")
        assertEquals(BridgeTransport.SNOWFLAKE, b.transport)
        assertEquals("www.phpmyadmin.net", b.dialHost)
        assertEquals("2B280B23E1107BB62ABFC40DDCC8824814F80A72", b.fingerprint)
    }

    @Test
    fun `meek_lite and meek are the same transport`() {
        assertEquals(BridgeTransport.MEEK, ok("meek_lite 192.0.2.20:80 url=https://1603026938.rsc.cdn77.org front=www.phpmyadmin.net utls=HelloRandomizedALPN").transport)
        assertEquals(BridgeTransport.MEEK, ok("meek 192.0.2.20:80 url=https://1603026938.rsc.cdn77.org front=www.phpmyadmin.net").transport)
    }

    @Test
    fun `what the app's Tor cannot use, or what is broken, is refused with a reason`() {
        assertEquals(BridgeLineError.CONJURE, bad("conjure 143.110.214.222:80 url=https://registration.refraction.network.global.prod.fastly.net/api"))
        assertEquals(BridgeLineError.UNSUPPORTED_TRANSPORT, bad("scramblesuit 1.2.3.4:443 ABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD"))
        assertEquals(BridgeLineError.BAD_ADDRESS, bad("obfs4 300.1.1.1:443 FBA329BA4F5FE648456C3A28BBAA3C7EBD118E08 cert=x"))
        assertEquals(BridgeLineError.BAD_PORT, bad("obfs4 1.1.1.1:70000 FBA329BA4F5FE648456C3A28BBAA3C7EBD118E08 cert=DKA6xS0N6c4I1CFVmWc/qgAEhPBgR/dPQyXhbkjt082Ka2xmq1pwSUpCPt626uuKW2Z1TA"))
        assertEquals(BridgeLineError.MISSING_CERT, bad("obfs4 1.1.1.1:443 FBA329BA4F5FE648456C3A28BBAA3C7EBD118E08 iat-mode=0"))
        assertEquals(BridgeLineError.MISSING_URL, bad("webtunnel [2001:db8::1]:443 9E22636EA817AD4FE43F2EC14DEED5131FED1CB0 ver=0.0.2"))
        assertEquals(BridgeLineError.BAD_URL, bad("webtunnel [2001:db8::1]:443 9E22636EA817AD4FE43F2EC14DEED5131FED1CB0 url=http://plain.example/x"))
    }

    @Test
    fun `every line of the bundled first-run list is a usable bridge`() {
        val lines = java.io.File("src/main/assets/tor_bridges_seed.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.size > 200)
        lines.forEach { assertTrue(it, BridgeLines.parse(it) is BridgeParse.Ok) }
    }

    @Test
    fun `a pasted list is read line by line, one bad line does not sink the rest`() {
        val text = """
            # from the bot
            obfs4 100.8.170.63:50164 FBA329BA4F5FE648456C3A28BBAA3C7EBD118E08 cert=DKA6xS0N6c4I1CFVmWc/qgAEhPBgR/dPQyXhbkjt082Ka2xmq1pwSUpCPt626uuKW2Z1TA iat-mode=0
            not a bridge
            103.149.168.242:9443 91637DE9ED5B069722DA7A5796926EE13238694D
        """.trimIndent()
        val r = BridgeLines.parseAll(text)
        assertEquals(3, r.size)
        assertEquals(2, r.count { it is BridgeParse.Ok })
    }
}
