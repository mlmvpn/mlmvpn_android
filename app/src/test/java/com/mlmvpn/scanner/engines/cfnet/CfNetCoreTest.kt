package com.mlmvpn.scanner.engines.cfnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CfNetCoreTest {

    private val reg = StrategyRegistry.default()
    private val profA = NetworkProfile(key = NetworkProfile.hashKey("netA"), hasV4 = true, hasV6 = false)

    // ---- catalog -------------------------------------------------------------------------------

    @Test fun catalogHasAtLeastFortyMeaningfulStrategies() {
        assertTrue("expected >= 40, got ${reg.all().size}", reg.all().size >= 40)
    }

    @Test fun everyStrategyIdIsUnique() {
        val ids = reg.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun everyStageHasStrategies() {
        for (stage in CfStage.entries) {
            assertTrue("no strategy for $stage", reg.forStage(stage).isNotEmpty())
        }
    }

    @Test fun controlStrategiesCarryRouteAndDataCarryShape() {
        for (s in reg.all()) {
            if (s.layer == Layer.CONTROL) { assertNotNull(s.route); assertNull(s.data) }
            else { assertNotNull(s.data); assertNull(s.route) }
        }
    }

    @Test fun prereqFilteringHidesV6AndChainsWhenUnavailable() {
        val ctx = EngineContext(hasV6 = false, warpSupported = false, torAvailable = false, psiphonAvailable = false)
        val connect = reg.forStage(CfStage.CONNECT, ctx)
        assertTrue(connect.none { it.data?.family == Family.V6 })
        assertTrue(connect.none { it.data?.shape in setOf(TunnelShape.WARP, TunnelShape.MASQUE_H2, TunnelShape.CHAIN_TOR, TunnelShape.CHAIN_PSIPHON) })
        // With everything available, they come back.
        val full = reg.forStage(CfStage.CONNECT, EngineContext(hasV6 = true, warpSupported = true, torAvailable = true, psiphonAvailable = true))
        assertTrue("Unimplemented engine adapters must not be advertised as executable strategies",
            full.none { it.data?.shape in setOf(TunnelShape.CHAIN_TOR, TunnelShape.CHAIN_PSIPHON, TunnelShape.WARP, TunnelShape.MASQUE_H2) })
        assertTrue(full.any { it.data?.family == Family.V6 })
    }

    // ---- ranking / confidence / store ----------------------------------------------------------

    private fun store() = StrategyStore(File(Files.createTempDirectory("cfnet").toFile(), "s.json"))

    @Test fun coldStartRanksByPrior() {
        val st = store()
        val ranked = st.rank(profA, CfStage.ACCOUNT, reg.forStage(CfStage.ACCOUNT, EngineContext()))
        // The highest-prior CONTROL route (cfedge_split, prior .65) should lead at cold start.
        assertEquals("ctl_cfedge_split", ranked.first().id)
    }

    @Test fun aTunnelRouteNeedsAnActiveProxy() {
        assertTrue(reg.forStage(CfStage.ACCOUNT, EngineContext()).none { it.id == "ctl_current_tunnel" })
        val active = reg.forStage(CfStage.ACCOUNT, EngineContext(hasProxy = true))
        assertEquals("ctl_current_tunnel", store().rank(profA, CfStage.ACCOUNT, active).first().id)
    }

    @Test fun provenStrategyOutranksAndBecomesFastPath() {
        val st = store()
        val cands = reg.forStage(CfStage.SCAN)
        val weak = "dat_chrome_v4"       // high nothing, just baseline
        repeat(5) { st.record(profA, CfStage.SCAN, weak, ok = true, latencyMs = 100, failure = null, now = 1000) }
        val ranked = st.rank(profA, CfStage.SCAN, cands, now = 2000)
        assertEquals(weak, ranked.first().id)
        val fp = st.fastPath(profA, CfStage.SCAN, cands, now = 2000)
        assertEquals(weak, fp?.id)
    }

    @Test fun repeatedFailureIsNotFastPath() {
        val st = store()
        val cands = reg.forStage(CfStage.SCAN)
        repeat(4) { st.record(profA, CfStage.SCAN, "dat_fragchrome_v4", ok = false, latencyMs = 0, failure = FailureClass.TLS_FAILURE, now = 1000) }
        assertNull(st.fastPath(profA, CfStage.SCAN, cands, now = 1100))
    }

    @Test fun confidenceDecaysAsEvidenceAges() {
        val st = store()
        repeat(6) { st.record(profA, CfStage.CONNECT, "dat_fragfp_v4", ok = true, latencyMs = 50, failure = null, now = 0) }
        val fresh = st.confidence(profA, CfStage.CONNECT, "dat_fragfp_v4", now = 0)
        val week = st.confidence(profA, CfStage.CONNECT, "dat_fragfp_v4", now = StrategyStore.DEFAULT_HALF_LIFE_MS)
        val month = st.confidence(profA, CfStage.CONNECT, "dat_fragfp_v4", now = 4 * StrategyStore.DEFAULT_HALF_LIFE_MS)
        assertTrue("fresh should be high", fresh > 0.8)
        assertTrue("should decay toward 0.5", week < fresh && week > month)
        assertTrue("old evidence drifts to unknown", month in 0.5..0.6)
    }

    @Test fun aSignatureFlipAgesEvidenceButDoesNotWipeIt() {
        val st = store()
        repeat(6) { st.record(profA, CfStage.CONNECT, "dat_ech_v4", ok = true, latencyMs = 50, failure = null, now = 1000) }
        val flipped = profA.copy(dnsSane = false)
        // A flaky DNS check on the same network: the old winner is not reused blindly...
        assertNull(st.fastPath(flipped, CfStage.CONNECT, reg.forStage(CfStage.CONNECT), 1100))
        // ...but recording something else under the new signature keeps what was learned.
        st.record(flipped, CfStage.ACCOUNT, "ctl_sys_plain", ok = true, latencyMs = 20, failure = null, now = 1100)
        assertEquals(6, st.stat(profA, CfStage.CONNECT, "dat_ech_v4").success)
        // Back on the original signature, it is the fast-path winner again.
        assertEquals("dat_ech_v4", st.fastPath(profA, CfStage.CONNECT, reg.forStage(CfStage.CONNECT), 1200)?.id)
    }

    @Test fun aFreshFailureRanksBelowUntriedAlternatives() {
        val st = store()
        val cands = reg.forStage(CfStage.SCAN)
        repeat(20) { st.record(profA, CfStage.SCAN, "dat_fragfp_v4", ok = true, latencyMs = 40, failure = null, now = 1000L + it) }
        st.record(profA, CfStage.SCAN, "dat_fragfp_v4", ok = false, latencyMs = 0, failure = FailureClass.TLS_FAILURE, now = 2000)
        val ranked = st.rank(profA, CfStage.SCAN, cands, now = 2001)
        val bestUntried = cands.filter { it.id != "dat_fragfp_v4" }.maxOf { st.score(profA, CfStage.SCAN, it, 2001) }
        assertTrue("just-failed winner must not lead the fallback", st.score(profA, CfStage.SCAN, reg.byId("dat_fragfp_v4")!!, 2001) < bestUntried)
        assertTrue(ranked.first().id != "dat_fragfp_v4")
        // An hour later the penalty is gone and its long history counts again.
        assertTrue(st.score(profA, CfStage.SCAN, reg.byId("dat_fragfp_v4")!!, 2000 + 3_600_000) > bestUntried)
    }

    @Test fun provenWithinTrustsOnlyARecentSuccess() {
        val st = store()
        st.record(profA, CfStage.CONNECT, "dat_ech_v4", ok = true, latencyMs = 50, failure = null, now = 10_000)
        assertTrue(st.provenWithin(profA, CfStage.CONNECT, "dat_ech_v4", 60_000, now = 30_000))
        assertFalse(st.provenWithin(profA, CfStage.CONNECT, "dat_ech_v4", 60_000, now = 90_000))
        st.record(profA, CfStage.CONNECT, "dat_ech_v4", ok = false, latencyMs = 0, failure = FailureClass.TIMEOUT, now = 31_000)
        assertFalse(st.provenWithin(profA, CfStage.CONNECT, "dat_ech_v4", 60_000, now = 32_000))
    }

    @Test fun networkSignatureChangeCutsConfidence() {
        val st = store()
        repeat(6) { st.record(profA, CfStage.CONNECT, "dat_ech_v4", ok = true, latencyMs = 50, failure = null, now = 0) }
        val sameNet = st.confidence(profA, CfStage.CONNECT, "dat_ech_v4", now = StrategyStore.DEFAULT_HALF_LIFE_MS)
        val changed = profA.copy(hasV6 = true)   // same key, flipped signature
        val afterChange = st.confidence(changed, CfStage.CONNECT, "dat_ech_v4", now = StrategyStore.DEFAULT_HALF_LIFE_MS)
        assertTrue("a changed network should trust old evidence less", afterChange < sameNet)
    }

    @Test fun storeRoundTripsAndKeepsNoSecrets() {
        val dir = Files.createTempDirectory("cfnet").toFile()
        val f = File(dir, "s.json")
        StrategyStore(f).apply {
            record(profA, CfStage.ACCOUNT, "ctl_cfedge_split", ok = true, latencyMs = 123, failure = null, now = 500)
            record(profA, CfStage.DEPLOY, "ctl_sys_plain", ok = false, latencyMs = 0, failure = FailureClass.AUTH_FAILURE, now = 600)
        }
        val reopened = StrategyStore(f)
        assertEquals(1, reopened.stat(profA, CfStage.ACCOUNT, "ctl_cfedge_split").success)
        assertEquals(FailureClass.AUTH_FAILURE, reopened.stat(profA, CfStage.DEPLOY, "ctl_sys_plain").lastFailure)
        // The key is a hash, not the raw "netA": the raw network identity never reaches disk.
        val text = f.readText()
        assertFalse(text.contains("netA"))
        assertTrue(text.contains(NetworkProfile.hashKey("netA")))
        // Nothing secret-looking is in the file.
        for (bad in listOf("Bearer", "token", "X-Auth", "@", "password")) assertFalse("leaked $bad", text.contains(bad))
    }

    // ---- failure classification ----------------------------------------------------------------

    @Test fun classifierUsesPhasesAndCodesNotText() {
        assertEquals(FailureClass.CANCELLED, FailureClassifier.classify(WirePhase.CONNECT, cancelled = true))
        assertEquals(FailureClass.DNS_POISONED, FailureClassifier.classify(WirePhase.DNS, dnsPoisoned = true))
        assertEquals(FailureClass.TCP_TIMEOUT, FailureClassifier.classify(WirePhase.CONNECT, timedOut = true))
        assertEquals(FailureClass.TCP_RESET, FailureClassifier.classify(WirePhase.CONNECT))
        assertEquals(FailureClass.TLS_FAILURE, FailureClassifier.classify(WirePhase.SECURE_CONNECT))
        // A 403 is a credential verdict only with Cloudflare's own error codes; a bare one is the route's.
        assertEquals(FailureClass.AUTH_FAILURE, FailureClassifier.classify(WirePhase.DONE, http = 403, cfErrors = listOf(9109)))
        assertEquals(FailureClass.AUTH_FAILURE, FailureClassifier.classify(WirePhase.DONE, http = 401, cfErrors = listOf(1000)))
        assertEquals(FailureClass.HTTP_BLOCKED, FailureClassifier.classify(WirePhase.DONE, http = 403))
        assertEquals(FailureClass.AUTH_FAILURE, FailureClassifier.classify(WirePhase.DONE, http = 200, cfErrors = listOf(10000)))
        assertEquals(FailureClass.RATE_LIMITED, FailureClassifier.classify(WirePhase.DONE, http = 429))
        assertEquals(FailureClass.RATE_LIMITED, FailureClassifier.classify(WirePhase.RESPONSE, http = 200, cfErrors = listOf(1015)))
    }

    @Test fun silenceAfterTheRequestIsANetworkFailureNotAnApiVerdict() {
        // Sent over a finished handshake, nothing came back: filtering, not Cloudflare's answer.
        assertEquals(FailureClass.TCP_RESET, FailureClassifier.classify(WirePhase.REQUEST))
        assertEquals(FailureClass.TCP_RESET, FailureClassifier.classify(WirePhase.RESPONSE))
        assertEquals(FailureClass.TIMEOUT, FailureClassifier.classify(WirePhase.RESPONSE, timedOut = true))
        assertTrue(FailureClassifier.classify(WirePhase.RESPONSE).countsAgainstStrategy)
    }

    @Test fun wirePhasesMapToNetworkClassesNeverToApiVerdicts() {
        assertEquals(FailureClass.DNS_POISONED, FailureClassifier.ofWirePhase("dns.bogon"))
        assertEquals(FailureClass.DNS_FAILURE, FailureClassifier.ofWirePhase("dns.empty"))
        assertEquals(FailureClass.IP_UNREACHABLE, FailureClassifier.ofWirePhase("edge.none"))
        assertEquals(FailureClass.IP_UNREACHABLE, FailureClassifier.ofWirePhase("v6.none"))
        assertEquals(FailureClass.TCP_TIMEOUT, FailureClassifier.ofWirePhase("tcp", timedOut = true))
        assertEquals(FailureClass.TCP_RESET, FailureClassifier.ofWirePhase("tcp"))
        assertEquals(FailureClass.TLS_CERT, FailureClassifier.ofWirePhase("tls.cert"))
        assertEquals(FailureClass.TLS_FAILURE, FailureClassifier.ofWirePhase("tls.alert"))
        assertEquals(FailureClass.TIMEOUT, FailureClassifier.ofWirePhase("wait", timedOut = true))
        assertEquals(FailureClass.TCP_RESET, FailureClassifier.ofWirePhase("body"))
        assertEquals(FailureClass.PROXY_FAILURE, FailureClassifier.ofWirePhase("proxy.failure"))
        for (p in listOf("dns", "dns.bogon", "tcp", "tls", "send", "wait", "body", "edge.none", "proxy.missing"))
            assertTrue("$p must count against the route", FailureClassifier.ofWirePhase(p).countsAgainstStrategy)
        assertEquals(WirePhase.SECURE_CONNECT, FailureClassifier.wirePhaseOf("tls.cert"))
        assertEquals(WirePhase.REQUEST, FailureClassifier.wirePhaseOf("wait"))
    }

    @Test fun anAccountNotSetUpEndsTheStageWithoutBlamingTheRoute() {
        assertTrue(FailureClass.ACCOUNT_SETUP.endsStage)
        assertFalse(FailureClass.ACCOUNT_SETUP.countsAgainstStrategy)
    }

    @Test fun authoritativeAnswersAreNotHeldAgainstTheRoute() {
        for (f in listOf(FailureClass.AUTH_FAILURE, FailureClass.RATE_LIMITED, FailureClass.API_FAILURE,
            FailureClass.CONFIG_PARSE_FAILURE, FailureClass.PREREQ_MISSING, FailureClass.CANCELLED)) {
            assertFalse("$f must be route-neutral", f.countsAgainstStrategy)
        }
        for (f in listOf(FailureClass.TLS_FAILURE, FailureClass.TCP_TIMEOUT, FailureClass.HTTP_BLOCKED,
            FailureClass.DATA_TRANSFER_FAILURE, FailureClass.TUNNEL_FAILURE, FailureClass.SCAN_FAILURE)) {
            assertTrue("$f is evidence against the strategy", f.countsAgainstStrategy)
        }
        assertTrue(FailureClass.AUTH_FAILURE.endsStage)
        assertFalse(FailureClass.HTTP_BLOCKED.endsStage)
        assertFalse(FailureClass.RATE_LIMITED.endsStage)
    }

    @Test fun catalogCountIsExactAndEveryDataShapeHasAnApplier() {
        // 35 CONTROL routes + 7 IDENTITY carriers + 34 DATA shapes. A change here is deliberate.
        assertEquals(76, reg.all().size)
        assertEquals(35, reg.all().count { it.id.startsWith("ctl_") })
        assertEquals(7, reg.all().count { it.id.startsWith("id_") })
        assertEquals(34, reg.all().count { it.id.startsWith("dat_") })
        for (s in reg.all().filter { it.layer == Layer.DATA }) {
            val network = if (s.data!!.needsXhttp) "xhttp" else "ws"
            val stream = org.json.JSONObject().put("network", network).put("security", "tls")
                .put("tlsSettings", org.json.JSONObject().put("serverName", "x.example.com"))
            assertTrue("${s.id} has no runtime applier", CfShape.apply(stream, s.data!!, network))
        }
    }

    @Test fun xhttpTuningsApplyOnlyToXhttpAndSetOneAlpnAndTheMode() {
        val wsStream = org.json.JSONObject().put("network", "ws")
            .put("tlsSettings", org.json.JSONObject().put("fingerprint", "firefox").put("alpn", org.json.JSONArray().put("http/1.1")))
        val before = wsStream.toString()
        for (shape in ShapeSpec.XHTTP_SHAPES) {
            assertFalse("$shape must not touch a WebSocket config", CfShape.apply(wsStream, ShapeSpec(shape), "ws"))
        }
        assertEquals(before, wsStream.toString())

        fun xhttp() = org.json.JSONObject().put("network", "xhttp")
            .put("tlsSettings", org.json.JSONObject().put("alpn", org.json.JSONArray().put("h2").put("http/1.1")).put("echConfigList", "x"))
            .put("xhttpSettings", org.json.JSONObject().put("path", "/p").put("mode", "packet-up"))
        val h2 = xhttp().also { CfShape.apply(it, ShapeSpec(TunnelShape.XHTTP_H2), "xhttp") }
        assertEquals("[\"h2\"]", h2.getJSONObject("tlsSettings").getJSONArray("alpn").toString())
        assertEquals("packet-up", h2.getJSONObject("xhttpSettings").getString("mode"))
        assertEquals("chrome", h2.getJSONObject("tlsSettings").getString("fingerprint"))
        assertEquals("/p", h2.getJSONObject("xhttpSettings").getString("path"))
        assertFalse(h2.has("finalmask"))
        assertFalse(h2.getJSONObject("tlsSettings").has("echConfigList"))
        val stream = xhttp().also { CfShape.apply(it, ShapeSpec(TunnelShape.XHTTP_H2_STREAM), "xhttp") }
        assertEquals("stream-up", stream.getJSONObject("xhttpSettings").getString("mode"))
        val h11 = xhttp().also { CfShape.apply(it, ShapeSpec(TunnelShape.XHTTP_H11), "xhttp") }
        assertEquals("[\"http/1.1\"]", h11.getJSONObject("tlsSettings").getJSONArray("alpn").toString())
        val frag = xhttp().also { CfShape.apply(it, ShapeSpec(TunnelShape.XHTTP_H2_FRAG), "xhttp") }
        assertTrue(frag.has("finalmask"))
        assertEquals("[\"h2\"]", frag.getJSONObject("tlsSettings").getJSONArray("alpn").toString())
    }

    @Test fun xhttpStrategiesAreCandidatesOnlyForAnXhttpConfig() {
        val ws = reg.forStage(CfStage.CONNECT, EngineContext(xhttp = false))
        assertTrue(ws.none { it.data!!.needsXhttp })
        val xh = reg.forStage(CfStage.SCAN, EngineContext(xhttp = true))
        assertEquals(4, xh.count { it.data!!.needsXhttp })
    }

    @Test fun everyFingerprintShapeWritesItsOwnUtlsName() {
        for (shape in listOf(TunnelShape.FIREFOX, TunnelShape.SAFARI, TunnelShape.RANDOMIZED, TunnelShape.IOS, TunnelShape.EDGE, TunnelShape.ANDROID)) {
            val stream = org.json.JSONObject().put("tlsSettings", org.json.JSONObject().put("echConfigList", "x"))
                .put("finalmask", org.json.JSONObject())
            CfShape.apply(stream, ShapeSpec(shape), "ws")
            assertEquals(shape.key, stream.getJSONObject("tlsSettings").getString("fingerprint"))
            assertFalse(stream.has("finalmask"))
            assertFalse(stream.getJSONObject("tlsSettings").has("echConfigList"))
        }
        // AS_IS leaves the config's own handshake alone.
        val own = org.json.JSONObject().put("tlsSettings", org.json.JSONObject().put("fingerprint", "qq"))
        CfShape.apply(own, ShapeSpec(TunnelShape.AS_IS), "ws")
        assertEquals("qq", own.getJSONObject("tlsSettings").getString("fingerprint"))
    }

    @Test fun cfErrorsParsedFromBody() {
        assertEquals(listOf(10000), FailureClassifier.cfErrorsOf("""{"success":false,"errors":[{"code":10000,"message":"x"}]}"""))
        assertTrue(FailureClassifier.cfErrorsOf("not json").isEmpty())
        assertTrue(FailureClassifier.cfErrorsOf(null).isEmpty())
    }
}
