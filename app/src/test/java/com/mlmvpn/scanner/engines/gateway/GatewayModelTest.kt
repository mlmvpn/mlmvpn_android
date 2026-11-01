package com.mlmvpn.scanner.engines.gateway

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The central Worker's rules, as a plain JVM test: addresses, the Proxy IP, settings, the shared record. */
class GatewayModelTest {

    private val uuid = "0b5c4f1e-6d2a-4c9b-8f31-7a2d9e4c1b60"
    private val edgeId = "11111111-2222-4333-8444-555555555555"

    private fun fails(block: () -> Unit): GatewayException {
        try { block() } catch (e: GatewayException) { return e }
        fail("expected a GatewayException")
        throw AssertionError()
    }

    // ── addresses ─────────────────────────────────────────────────────────────────────────

    @Test fun ipv4_is_strict_like_nodes_isIPv4() {
        assertTrue(Gateway.isIPv4("188.114.98.0"))
        assertTrue(Gateway.isIPv4("0.0.0.0"))
        assertFalse(Gateway.isIPv4("256.1.1.1"))
        assertFalse(Gateway.isIPv4("01.1.1.1"))
        assertFalse(Gateway.isIPv4("1.1.1"))
        assertFalse(Gateway.isIPv4("1.1.1.1.1"))
        assertFalse(Gateway.isIPv4("a.b.c.d"))
    }

    @Test fun ipv6_accepts_the_forms_cloudflare_uses_and_nothing_else() {
        for (ok in listOf("2606:4700::1111", "2a06:98c1:3121::7", "::", "::1", "1::", "2606:4700:3030:0:0:0:6815:80c", "::ffff:1.2.3.4", "1:2:3:4:5:6:7:8")) {
            assertTrue(ok, Gateway.isIPv6(ok))
        }
        for (bad in listOf("", "1.2.3.4", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", ":::", "1::2::3", "12345::1", "g::1", ":1", "1:", "fe80::1%eth0", "1.2.3.4::1")) {
            assertFalse(bad, Gateway.isIPv6(bad))
        }
    }

    @Test fun classify_strips_brackets_lowercases_domains_and_refuses_junk() {
        assertEquals(Gateway.Classified("104.16.1.1", "ipv4"), Gateway.classifyAddress(" 104.16.1.1 "))
        assertEquals(Gateway.Classified("2606:4700::1111", "ipv6"), Gateway.classifyAddress("[2606:4700::1111]"))
        assertEquals(Gateway.Classified("zula.ir", "domain"), Gateway.classifyAddress("Zula.IR"))
        assertNull(Gateway.classifyAddress("not an address"))
        assertNull(Gateway.classifyAddress("localhost"))
        assertNull(Gateway.classifyAddress(""))
        assertNull(Gateway.classifyAddress(null))
    }

    @Test fun address_list_dedupes_caps_and_names_the_bad_entry() {
        assertEquals(listOf("www.speedtest.net", "1.1.1.1", "zula.ir"), Gateway.parseAddressList("www.speedtest.net, 1.1.1.1\nzula.ir;1.1.1.1"))
        assertEquals(emptyList<String>(), Gateway.parseAddressList("  "))
        val e = fails { Gateway.parseAddressList("1.1.1.1 nope!") }
        assertTrue(e.fa, e.fa.contains("«nope!»"))
        val many = (1..31).joinToString("\n") { "10.0.0.$it" }
        assertTrue(fails { Gateway.parseAddressList(many) }.fa.contains("حداکثر 30"))
        assertEquals(30, Gateway.parseAddressList((1..30).joinToString("\n") { "10.0.0.$it" }).size)
    }

    // ── the Proxy IP ──────────────────────────────────────────────────────────────────────

    @Test fun fallback_is_normalised_the_way_the_worker_parses_it() {
        assertEquals("a.example,b.example:8443,[2001:db8::1]:9", Gateway.normalizeFallback(" a.example ,b.example:8443\n[2001:db8::1]:9"))
        assertEquals("[2001:db8::2]", Gateway.normalizeFallback("2001:db8::2"))
        assertEquals("", Gateway.normalizeFallback(""))
        assertEquals("", Gateway.normalizeFallback(null))
        assertEquals("proxy.example:8443,[2001:db8::5]", Gateway.normalizeFallback("proxy.example:8443, 2001:db8::5"))
        for (bad in listOf("a.example:0", "http://a.example", "bad host!", "a.example:70000", "[2001:db8::1", "a.example:99999")) {
            fails { Gateway.normalizeFallback(bad) }
        }
        assertTrue(fails { Gateway.normalizeFallback("a.example,b.example,c.example,d.example") }.fa.contains("حداکثر"))
    }

    // ── settings ──────────────────────────────────────────────────────────────────────────

    @Test fun settings_default_to_BPBs_own_and_validate_before_anything_is_kept() {
        assertEquals(GatewaySettings(listOf("www.speedtest.net"), listOf(443), false, true, true), GatewaySettings.DEFAULT)
        val s = GatewaySettings.normalize(SettingsPatch(cleanIPs = "www.speedtest.net, 1.1.1.1", ports = listOf(443, 2053, 443, 80), ipv6 = true, methods = false))
        assertEquals(listOf("www.speedtest.net", "1.1.1.1"), s.cleanIPs)
        assertEquals(listOf(443, 2053), s.ports)
        assertTrue(s.ipv6); assertFalse(s.methods); assertTrue(s.withResolved)
        assertTrue(fails { GatewaySettings.normalize(SettingsPatch(ports = listOf(80, 8080))) }.fa.contains("درگاه‌های TLS"))
        assertTrue(fails { GatewaySettings.normalize(SettingsPatch(cleanIPs = "not an address")) }.fa.contains("نه آی‌پی است و نه دامنه"))
        // a patch changes only what it names
        assertEquals(s.copy(withResolved = false), GatewaySettings.normalize(SettingsPatch(withResolved = false), s))
    }

    @Test fun settings_from_another_device_fall_back_instead_of_failing() {
        val ok = GatewaySettings.fromJson(JSONObject("""{"cleanIPs":["1.1.1.1"],"ports":[8443],"ipv6":true,"withResolved":false,"methods":false}"""))
        assertEquals(GatewaySettings(listOf("1.1.1.1"), listOf(8443), true, false, false), ok)
        assertEquals(GatewaySettings.DEFAULT, GatewaySettings.fromJson(JSONObject("""{"ports":[80]}""")))
        assertEquals(GatewaySettings.DEFAULT, GatewaySettings.fromJson(JSONObject("""{"cleanIPs":["not an address"]}""")))
        assertEquals(GatewaySettings.DEFAULT, GatewaySettings.fromJson(null))
        assertEquals(ok, GatewaySettings.fromJson(ok.toJson()))
    }

    // ── the code's digest ─────────────────────────────────────────────────────────────────

    @Test fun code_hash_ignores_line_ends_and_file_order_but_not_content() {
        val a = listOf("worker.js" to "export default {};\nlet a = 1;\n", "gateway.js" to "x\ny\n")
        val crlf = a.map { (n, c) -> n to c.replace("\n", "\r\n") }
        assertEquals(Gateway.codeHash(a), Gateway.codeHash(crlf))
        assertEquals(Gateway.codeHash(a), Gateway.codeHash(a.reversed()))
        assertTrue(Gateway.codeHash(a) != Gateway.codeHash(listOf("worker.js" to "export default {};\nlet a = 2;\n", "gateway.js" to "x\ny\n")))
        assertTrue(Gateway.codeHash(a) != Gateway.codeHash(listOf("worker.js" to a[0].second, "other.js" to a[1].second)))
        assertEquals(16, Gateway.codeHash(a).length)
    }

    @Test fun ids_text_lists_BPB_first_then_each_connected_panel() {
        val panels = linkedMapOf("EDG" to PanelId(edgeId), "NTR" to PanelId("66666666-7777-4888-8999-aaaaaaaaaaaa"))
        assertEquals("$uuid,$edgeId,66666666-7777-4888-8999-aaaaaaaaaaaa", Gateway.idsText(uuid, panels))
        assertEquals(uuid, Gateway.idsText(uuid, emptyMap()))
        assertEquals(listOf("EDG", "ZEU", "SPD", "NTR", "GZG", "NVA", "NHN", "MLM"), Gateway.PANEL_CODES)
    }

    // ── the local record ──────────────────────────────────────────────────────────────────

    private fun record() = GatewayRecord(
        accountId = "acc", script = "app-core-a3f2b1-gw", host = "app-core-a3f2b1-gw.mysub.workers.dev", uuid = uuid, wsPath = "/a1b2c3d4e5f60718",
        fallback = Gateway.DEFAULT_FALLBACK, codeHash = "abc", at = 5L, panels = linkedMapOf("EDG" to PanelId(edgeId, 3L, 4L, 7)),
        settings = GatewaySettings(listOf("1.1.1.1"), listOf(443, 8443), true, false, true), fetchedAt = 9L, configCount = 2,
        resolved = Resolved(8L, listOf("104.21.8.12"), listOf("2606:4700::1")), sharedAt = 6L, sharedBy = "windows", unshared = true, replaced = "old-gw",
    )

    @Test fun the_record_round_trips_and_a_damaged_one_is_refused() {
        val r = record()
        assertEquals(r, GatewayRecord.fromJson(JSONObject(r.toJson().toString())))
        assertEquals("https://app-core-a3f2b1-gw.mysub.workers.dev", r.url)
        assertEquals(uuid, r.idOf("BPB")); assertEquals(edgeId, r.idOf("EDG")); assertNull(r.idOf("ZEU"))
        for (patch in listOf("uuid" to "nope", "wsPath" to "x y", "host" to "evil.example", "script" to "other-gw")) {
            assertNull(patch.first, GatewayRecord.fromJson(r.toJson().put(patch.first, patch.second)))
        }
        // an unknown panel code or a malformed id inside is dropped, not fatal
        val withJunk = r.toJson()
        withJunk.getJSONObject("panels").put("XYZ", JSONObject().put("uuid", edgeId)).put("ZEU", JSONObject().put("uuid", "bad"))
        assertEquals(setOf("EDG"), GatewayRecord.fromJson(withJunk)!!.panels.keys)
    }

    // ── the shared record ─────────────────────────────────────────────────────────────────

    private fun s(rec: GatewayRecord = record()) = GatewayShared.toS(rec)

    @Test fun shared_s_has_exactly_the_keys_the_windows_app_writes() {
        val o = s()
        assertEquals(setOf("uuid", "wsPath", "fallback", "panels", "settings", "codeHash"), o.keys().asSequence().toSet())
        assertEquals(setOf("uuid", "at"), o.getJSONObject("panels").getJSONObject("EDG").keys().asSequence().toSet())
        assertEquals(setOf("cleanIPs", "ports", "ipv6", "withResolved", "methods"), o.getJSONObject("settings").keys().asSequence().toSet())
        // local-only counters never travel
        assertFalse(o.toString().contains("configCount")); assertFalse(o.toString().contains("fetchedAt"))
    }

    @Test fun shared_parse_round_trips_and_is_strict_about_what_it_trusts() {
        val r = record()
        val g = GatewayShared.parse(r.script, r.url, s(r), "windows", 99L)!!
        assertEquals(listOf(r.script, r.host, r.uuid, r.wsPath, r.fallback, r.codeHash, "windows", 99L), listOf(g.script, g.host, g.uuid, g.wsPath, g.fallback, g.codeHash, g.by, g.at))
        assertEquals(mapOf("EDG" to PanelId(edgeId, 3L)), g.panels)
        assertEquals(r.settings, g.settings)

        fun bad(vararg edit: Pair<String, Any>, url: String = r.url, script: String = r.script): SharedGateway? {
            val o = s(r); for ((k, v) in edit) o.put(k, v); return GatewayShared.parse(script, url, o, "x", 1L)
        }
        assertNull(bad("uuid" to "nope"))
        assertNull(bad("wsPath" to "no-slash"))
        assertNull(bad(url = "https://evil.example"))
        assertNull(bad(url = "https://other-gw.mysub.workers.dev"))
        assertNull(bad(script = ""))
        assertNull(GatewayShared.parse(r.script, r.url, null, "x", 1L))
        assertNull(GatewayShared.parse(r.script, "not a url", s(r), "x", 1L))
        // what a newer app may add is tolerated, not trusted
        val odd = s(r)
        odd.getJSONObject("panels").put("XYZ", JSONObject().put("uuid", edgeId)).put("ZEU", JSONObject().put("uuid", "bad"))
        odd.put("settings", JSONObject().put("ports", JSONArray(listOf(80)))).put("fallback", "bad host!")
        val p = GatewayShared.parse(r.script, r.url, odd, "x", 1L)!!
        assertEquals(setOf("EDG"), p.panels.keys)
        assertEquals(GatewaySettings.DEFAULT, p.settings)
        assertEquals("", p.fallback)
    }

    @Test fun pull_plan_registry_wins_on_a_different_worker_and_the_newest_side_wins_on_the_same_one() {
        val local = record().copy(unshared = false, at = 100L)
        val shared = GatewayShared.parse(local.script, local.url, s(local), "android", 100L)!!
        assertEquals(PullPlan.Adopt, GatewayShared.plan(null, shared))
        assertEquals(PullPlan.Same, GatewayShared.plan(local, shared))
        assertEquals(PullPlan.Replace("app-core-a3f2b1-gw"), GatewayShared.plan(local, shared.copy(script = "phone-gw", host = "phone-gw.mysub.workers.dev")))

        val edited = shared.copy(panels = emptyMap(), at = 200L)
        assertEquals("the account's record is newer", PullPlan.Update, GatewayShared.plan(local, edited))
        assertEquals("this side is newer: it was never published", PullPlan.Push, GatewayShared.plan(local.copy(at = 300L), edited))
        assertEquals("a tie goes to the account…", PullPlan.Update, GatewayShared.plan(local.copy(at = 200L), edited))
        assertEquals("…unless this side knows it holds an unpublished change", PullPlan.Push, GatewayShared.plan(local.copy(at = 200L, unshared = true), edited))
        assertEquals("a changed setting alone is a change", PullPlan.Update,
            GatewayShared.plan(local, shared.copy(settings = shared.settings.copy(ports = listOf(2053)), at = 400L)))
    }

    @Test fun adopting_keeps_the_counters_only_while_the_ids_did() {
        val local = record()
        val same = GatewayShared.parse(local.script, local.url, s(local), "windows", 50L)!!
        val kept = GatewayShared.adopt("acc", local, same, null, 1000L)
        assertEquals(7, kept.panels.getValue("EDG").configCount)
        assertEquals(4L, kept.panels.getValue("EDG").fetchedAt)
        assertEquals(2, kept.configCount); assertNotNull(kept.resolved)
        assertFalse(kept.unshared); assertEquals("windows", kept.sharedBy); assertEquals(1000L, kept.sharedAt)

        val rotated = same.copy(uuid = "9f3a6c1e-1b2d-4e5f-8a7b-0c9d8e7f6a5b", panels = mapOf("EDG" to PanelId("77777777-2222-4333-8444-555555555555", 60L)))
        val fresh = GatewayShared.adopt("acc", local, rotated, null, 1000L)
        assertEquals("the old links died, so the count restarts", 0, fresh.panels.getValue("EDG").configCount)
        assertEquals(0, fresh.configCount); assertNull(fresh.resolved)

        val other = GatewayShared.adopt("acc", local, same.copy(script = "phone-gw", host = "phone-gw.mysub.workers.dev"), local.script, 1000L)
        assertEquals("app-core-a3f2b1-gw", other.replaced)
        assertEquals("phone-gw", other.script)
    }
}
