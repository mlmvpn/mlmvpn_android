package com.mlmvpn.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** «کاندوئیت», «آی‌پی تمیز» and «پروکسی سرور»: the pure parts, the desktop's behaviour kept. */
class PsiphonExtrasTest {

    private val id = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { (it * 7 + 3).toByte() })

    private fun token(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

    // ── pairing ──

    @Test fun pairingLinkGivesTheCompartmentIdAndName() {
        val p = PsiphonPairing.parse("psiphon://pair/" + token("""{"v":"1","data":{"id":"$id","name":"Home"}}"""))
        assertEquals(id, p.id)
        assertEquals("Home", p.name)
    }

    @Test fun pairingAcceptsTheHttpsFormABareTokenAndABareId() {
        val t = token("""{"v":"1","data":{"id":"$id","name":"x"}}""")
        assertEquals(id, PsiphonPairing.parse("https://conduit.psiphon.ca/pair/$t").id)
        assertEquals(id, PsiphonPairing.parse("  $t \n").id)
        assertEquals(id, PsiphonPairing.parse(id).id)
        assertEquals(43, id.length)
    }

    @Test fun pairingRefusesWhatIsNotOne() {
        for (bad in listOf("", "hello", "psiphon://pair/!!!", "psiphon://pair/" + token("""{"v":"2","data":{"id":"$id"}}"""),
            "psiphon://pair/" + token("""{"v":"1","data":{"id":"short"}}"""))) {
            try { PsiphonPairing.parse(bad); fail("accepted: $bad") } catch (_: PsiphonPairing.PairingError) { }
        }
    }

    @Test fun base64DecodesBothAlphabetsPaddedOrNot() {
        val bytes = ByteArray(40) { (it * 31 + 5).toByte() }
        assertTrue(bytes.contentEquals(PsiphonPairing.decode(Base64.getEncoder().encodeToString(bytes))))
        assertTrue(bytes.contentEquals(PsiphonPairing.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))))
        assertNull(PsiphonPairing.decode("a"))
    }

    // ── clean IPs ──

    @Test fun ipv6IsWrittenOneWay() {
        assertEquals("2a02:26f0:3500:4::b818:4db5", PsiphonCleanIps.canonical("2A02:26F0:3500:0004:0000:0000:B818:4DB5"))
        assertEquals("2001:db8::1", PsiphonCleanIps.canonical("2001:db8:0:0:0:0:0:1"))
        assertEquals("::1", PsiphonCleanIps.canonical("::1"))
        // The longest run of zeros is the one compressed.
        assertEquals("2001:0:0:1::1", PsiphonCleanIps.canonical("2001:0:0:1:0:0:0:1"))
        // One zero group is never "::".
        assertEquals("2001:db8:0:1:1:1:1:1", PsiphonCleanIps.canonical("2001:db8::1:1:1:1:1"))
        assertEquals("1.2.3.4", PsiphonCleanIps.canonical("1.2.3.4"))
        assertNull(PsiphonCleanIps.canonical("a1234.na.akamai.net"))
        assertNull(PsiphonCleanIps.canonical("1.2.3.400"))
        assertNull(PsiphonCleanIps.canonical("2001:db8::1::2"))
    }

    @Test fun neighboursOfAnEdge() {
        val v4 = PsiphonCleanIps.expandV4("2.16.10.69", 24)
        assertEquals(254, v4.size)
        assertEquals("2.16.10.1", v4.first())
        assertEquals("2.16.10.254", v4.last())
        val v6 = PsiphonCleanIps.expandV6("2a02:26f0:3500:4::b818:4db5")
        assertEquals(256, v6.size)
        assertEquals("2a02:26f0:3500:4::b818:4d00", v6.first())
        assertTrue("2a02:26f0:3500:4::b818:4db5" in v6)
    }

    @Test fun privateAndCarrierAddressesAreNotEdges() {
        for (ip in listOf("10.1.2.3", "127.0.0.1", "192.168.1.1", "172.16.0.1", "100.64.0.1", "169.254.1.1", "fd00::1", "fe80::1", "::1")) {
            assertTrue(ip, PsiphonCleanIps.isBogon(ip))
        }
        for (ip in listOf("2.16.10.69", "23.32.1.1", "2a02:26f0::1")) assertFalse(ip, PsiphonCleanIps.isBogon(ip))
    }

    @Test fun workingFastestFirstThenUntestedThenFailed() {
        fun e(ip: String, ok: Boolean = false, tls: Long? = null, real: PsiphonCleanIps.Real? = null, confirmed: Long = 0, source: String = "scan", tested: Long = 1) =
            PsiphonCleanIps.Entry(ip, "akamai", source, 0, ok = ok, tlsMs = tls, testedAt = tested, real = real, confirmedAt = confirmed)
        val list = listOf(
            e("1.1.1.1", ok = true, tls = 50),
            e("2.2.2.2", real = PsiphonCleanIps.Real(false, null, null, "x", 1)),
            // Slower to connect, faster to answer: the ping the row shows decides.
            e("3.3.3.3", real = PsiphonCleanIps.Real(true, 2500, 300, "", 1)),
            e("4.4.4.4", confirmed = 5),
            e("5.5.5.5", real = PsiphonCleanIps.Real(true, 9000, 200, "", 1)),
            e("6.6.6.6", real = PsiphonCleanIps.Real(false, null, null, "silent", 1, byEdge = true)),
            e("7.7.7.7", source = "user", tested = 0),
            // A connection came up through it once, but today's test failed: with the failed ones.
            e("8.8.8.8", confirmed = 5, real = PsiphonCleanIps.Real(false, null, null, "x", 2)),
        )
        assertEquals(listOf("5.5.5.5", "3.3.3.3", "4.4.4.4", "1.1.1.1", "7.7.7.7", "2.2.2.2", "6.6.6.6", "8.8.8.8"),
            PsiphonCleanIps.sorted(list).map { it.ip })
    }

    @Test fun meekServersAnswerIsTheOrigin() {
        val go404 = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nX-Content-Type-Options: nosniff\r\nDate: x\r\nContent-Length: 19\r\n\r\n404 page not found\n"
        assertEquals(PsiphonCleanIps.EdgeVerdict.ORIGIN, PsiphonCleanIps.edgeAnswer(go404))
        val akamai = "HTTP/1.1 400 Bad Request\r\nServer: AkamaiGHost\r\n\r\n<HTML><HEAD><TITLE>Invalid URL</TITLE></HEAD><BODY>Reference&#32;&#35;9</BODY></HTML>"
        assertEquals(PsiphonCleanIps.EdgeVerdict.OTHER, PsiphonCleanIps.edgeAnswer(akamai))
        // A 404 of the edge's own, not the meek server's.
        assertEquals(PsiphonCleanIps.EdgeVerdict.OTHER, PsiphonCleanIps.edgeAnswer("HTTP/1.1 404 Not Found\r\nServer: AkamaiGHost\r\n\r\n<HTML>Not Found</HTML>"))
    }

    @Test fun anyAnswerOutweighsSilenceAndSilenceOutweighsNoTls() {
        val o = PsiphonCleanIps.Edge(PsiphonCleanIps.EdgeVerdict.OTHER, 900)
        val s = PsiphonCleanIps.Edge(PsiphonCleanIps.EdgeVerdict.SILENT)
        val n = PsiphonCleanIps.Edge(PsiphonCleanIps.EdgeVerdict.NOTLS)
        assertEquals(o, PsiphonCleanIps.combine(s, o))
        assertEquals(o, PsiphonCleanIps.combine(o, s))
        assertEquals(s, PsiphonCleanIps.combine(n, s))
        assertEquals(s, PsiphonCleanIps.combine(s, n))
        assertEquals(n, PsiphonCleanIps.combine(null, n))
    }

    // ── the real test's server entries ──

    private fun entry(json: String) = ("1.2.3.4 80 secret cert " + json).toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    @Test fun onlyTheProvidersFrontedEntriesAreGiven() {
        val akamaiMany = entry("""{"frontingProviderID":"AK","capabilities":["SSH","FRONTED-MEEK"],"meekFrontingHost":"old.psiphon3.com","meekFrontingHosts":["www.a.com","www.b.com"]}""")
        val akamaiOne = entry("""{"frontingProviderID":"AK","capabilities":["FRONTED-MEEK","FRONTED-MEEK-QUIC"],"meekFrontingHost":"one.psiphon3.com"}""")
        val fastly = entry("""{"frontingProviderID":"FA","capabilities":["FRONTED-MEEK"],"meekFrontingHosts":["www.f.com"]}""")
        val unfronted = entry("""{"frontingProviderID":"AK","capabilities":["OSSH","UNFRONTED-MEEK"]}""")
        val text = listOf(akamaiMany, "", "zz-not-hex", fastly, akamaiOne, unfronted).joinToString("\n")
        val f = PsiphonProbes.parseFronted(text, "AK")
        assertEquals(2, f.count)
        assertEquals(akamaiMany + "\n" + akamaiOne, f.entries)
        // The Host headers Psiphon sends: an entry's list when it has one, else its single host.
        assertEquals(listOf("www.a.com", "www.b.com", "one.psiphon3.com"), f.hosts)
        assertEquals(1, PsiphonProbes.parseFronted(text, "FA").count)
        assertEquals(0, PsiphonProbes.parseFronted(text, "XX").count)
    }

    @Test fun slotsFollowTheMemoryTheKernelSees() {
        val gib = 1024L * 1024 * 1024
        assertEquals(1, PsiphonProbes.slotsFor(1_900L * 1024 * 1024))
        assertEquals(2, PsiphonProbes.slotsFor((2.8 * gib).toLong()))
        // A "4 GB" phone (the A50: 3,690,784 kB).
        assertEquals(3, PsiphonProbes.slotsFor(3_690_784L * 1024))
        assertEquals(4, PsiphonProbes.slotsFor((5.5 * gib).toLong()))
        assertEquals(PsiphonProbes.MAX_SLOTS, PsiphonProbes.slotsFor((7.4 * gib).toLong()))
        assertEquals(2, PsiphonProbes.slotsFor(0))
    }

    // ── upstream proxy ──

    @Test fun upstreamUrlIsWhatPsiphonParses() {
        assertEquals("http://1.2.3.4:8080", PsiphonUpstream.urlOf("http", "1.2.3.4", 8080))
        assertEquals("socks5://[2001:db8::1]:1080", PsiphonUpstream.urlOf("socks5", "2001:db8::1", 1080))
        val u = PsiphonUpstream.urlOf("http", "proxy.example.com", 3128, "a b", "p@ss:")
        assertEquals("http://a%20b:p%40ss%3A@proxy.example.com:3128", u)
        assertEquals("http://a%20b:•••@proxy.example.com:3128", PsiphonUpstream.masked(u))
    }
}
