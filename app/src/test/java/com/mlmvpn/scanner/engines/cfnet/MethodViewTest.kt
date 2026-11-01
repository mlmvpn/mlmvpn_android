package com.mlmvpn.scanner.engines.cfnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What the Cloud tab's «روش اتصال این شبکه» card says: the method's name, how old, and what the store holds. */
class MethodViewTest {

    private val registry = StrategyRegistry.default()
    private fun s(id: String) = registry.byId(id) ?: error("no such strategy $id")

    @Test fun the_headline_methods_are_named_in_plain_words() {
        assertEquals("ECH" to "ECH", MethodView.label(s("dat_ech_v4")))
        assertEquals("F&F (تکه‌کردن + فینگرپرینت)" to "F&F (fragment + fingerprint)", MethodView.label(s("dat_fragfp_v4")))
        assertEquals("کروم (بدون تغییر)" to "Chrome (unchanged)", MethodView.label(s("dat_chrome_v4")))
        assertEquals("بدون تغییر (همان کانفیگ)" to "As the config is", MethodView.label(s("dat_asis_v4")))
        assertEquals("IPv6 با کروم" to "IPv6 with Chrome", MethodView.label(s("dat_chrome_v6")))
    }

    @Test fun the_address_family_policy_and_port_are_said_too() {
        assertEquals("ECH · IPv6", MethodView.label(s("dat_ech_v6")).second)
        assertEquals("F&F (fragment + fingerprint) · clean IP", MethodView.label(s("dat_clean_fragfp")).second)
        assertEquals("ECH · working edge", MethodView.label(s("dat_cfedge_ech")).second)
        assertEquals("F&F (fragment + fingerprint) · IPv6 · IPv6 twin", MethodView.label(s("dat_v6twin_fragfp")).second)
        assertEquals("F&F (fragment + fingerprint) · port 8443", MethodView.label(s("dat_port8443_fragfp")).second)
        assertEquals("XHTTP over HTTP/2", MethodView.label(s("dat_xhttp_h2")).second)
    }

    @Test fun every_data_strategy_has_a_name_that_is_not_its_internal_id() {
        for (st in registry.all().filter { it.layer == Layer.DATA }) {
            val (fa, en) = MethodView.label(st)
            assertTrue(st.id, fa.isNotBlank() && en.isNotBlank())
            assertFalse("${st.id} leaks its id", fa.contains("dat_") || en.contains("dat_"))
        }
        assertEquals("—" to "—", MethodView.label(null))
    }

    @Test fun age_is_just_now_minutes_hours_or_days() {
        val now = 1_800_000_000_000L
        assertEquals(MethodView.Age(MethodView.Span.NOW, 0), MethodView.age(now, now - 30_000))
        assertEquals(MethodView.Age(MethodView.Span.NOW, 0), MethodView.age(now, now + 5_000))
        assertEquals(MethodView.Age(MethodView.Span.MINUTES, 4), MethodView.age(now, now - 4 * 60_000 - 10))
        assertEquals(MethodView.Age(MethodView.Span.MINUTES, 59), MethodView.age(now, now - 59 * 60_000))
        assertEquals(MethodView.Age(MethodView.Span.HOURS, 1), MethodView.age(now, now - 60 * 60_000))
        assertEquals(MethodView.Age(MethodView.Span.HOURS, 23), MethodView.age(now, now - 23 * 3_600_000L))
        assertEquals(MethodView.Age(MethodView.Span.DAYS, 1), MethodView.age(now, now - 24 * 3_600_000L))
        assertEquals(MethodView.Age(MethodView.Span.DAYS, 3), MethodView.age(now, now - 3 * 86_400_000L - 5))
    }

    @Test fun the_verdict_is_what_the_store_holds_for_this_network_connect_first() {
        val dir = Files.createTempDirectory("mv").toFile()
        try {
            val store = StrategyStore(File(dir, "strategies.json"))
            val net = NetworkProfile(key = NetworkProfile.hashKey("wifi|1.2.3.4"))
            var v = MethodView.verdict(registry, store, net)
            assertFalse(v.proven); assertNull(v.best)

            val now = System.currentTimeMillis()
            repeat(3) { store.record(net, CfStage.SCAN, "dat_ech_v4", true, 400, null, now - 600_000) }
            v = MethodView.verdict(registry, store, net)
            assertTrue(v.proven)
            assertEquals("dat_ech_v4", v.scan?.id); assertNull(v.connect)
            assertEquals("with no connect evidence the scan winner is the best", "dat_ech_v4", v.best?.id)
            assertTrue(v.bestAt > 0)

            repeat(3) { store.record(net, CfStage.CONNECT, "dat_fragfp_v4", true, 900, null, now - 60_000) }
            v = MethodView.verdict(registry, store, net)
            assertEquals("dat_fragfp_v4", v.connect?.id)
            assertEquals("a tunnel dials with the connect winner", "dat_fragfp_v4", v.best?.id)

            // another network has nothing
            assertFalse(MethodView.verdict(registry, store, NetworkProfile(key = NetworkProfile.hashKey("cell|9.9.9.9"))).proven)
            // a strategy whose last outcome was a failure is not a verdict
            store.record(net, CfStage.CONNECT, "dat_fragfp_v4", false, 0, FailureClass.TUNNEL_FAILURE, now)
            assertNull(MethodView.verdict(registry, store, net).connect)
        } finally { dir.deleteRecursively() }
    }
}
