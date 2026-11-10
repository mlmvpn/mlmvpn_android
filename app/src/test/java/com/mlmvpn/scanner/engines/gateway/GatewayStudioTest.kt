package com.mlmvpn.scanner.engines.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * The central Worker carries «کانفیگ استدیو»'s people in its one id list. Every upload — the
 * Studio's own, a panel connected, the code updated — must carry them, must never push a panel off
 * for them or them off silently, and must write the digest that lets a phone tell whether the Worker
 * still has them. The Worker's code is not changed for any of it; only the list it is given grows.
 */
class GatewayStudioTest {

    private class Acct {
        var clock = 1_790_000_000_000L
        var registry: SharedRaw? = null
        val alive = mutableSetOf<String>()
        var lastIds: List<String> = emptyList()
        var uploads = 0
    }

    private class Phone(val acct: Acct, var studio: List<String> = emptyList(), val modules: List<Pair<String, String>> = MODULES) {
        var record: GatewayRecord? = null
        val steps = mutableListOf<String>()
        val cloud = object : GatewayCloud {
            override val by = "android"
            override fun registryGet() = acct.registry?.let { it.copy(s = JSONObject(it.s.toString())) }
            override fun registryPut(script: String, url: String, s: JSONObject) { acct.registry = SharedRaw(script, url, JSONObject(s.toString()), by, ++acct.clock) }
            override fun registryRemove(script: String) { if (acct.registry?.script == script) acct.registry = null }
            override fun scriptExists(script: String) = script in acct.alive
            override fun subdomain() = "sub"
            override fun workerName() = "core-gw"
            override fun upload(script: String, files: List<Pair<String, String>>, bindings: List<JSONObject>) {
                acct.lastIds = bindings.first { it.getString("name") == "UUID" }.getString("text").split(',')
                acct.alive += script
                acct.uploads++
            }
            override fun enableWorkersDev(script: String) {}
            override fun removeWorker(script: String) { acct.alive.remove(script) }
            override fun randomUuid() = UUID.randomUUID().toString()
            override fun randomPath() = "/0123456789abcdef"
        }
        val store = object : GatewayStore {
            override fun get() = record
            override fun put(rec: GatewayRecord) { record = rec }
            override fun del() { record = null }
        }
        val engine = GatewayEngine("acc", cloud, store, { modules },
            { DohAnswer(listOf("104.21.8.12"), emptyList()) }, now = { ++acct.clock }, pulledAt = mutableMapOf(), studioIds = { studio })
    }

    @Before fun fresh() { GatewayEngine.resetForTest(); GatewayEngine.language = { "fa" } }

    private fun ids(n: Int) = (1..n).map { UUID.randomUUID().toString() }

    @Test fun every_upload_carries_the_studio_people_after_bpb_and_the_panels() {
        val acct = Acct(); val people = ids(3); val phone = Phone(acct, people)
        phone.engine.deploy()
        val rec = phone.record!!
        assertEquals(listOf(rec.uuid) + people, acct.lastIds)
        phone.engine.connectPanel("EDG")
        assertEquals(listOf(rec.uuid, phone.record!!.panels.getValue("EDG").uuid) + people, acct.lastIds)
        // the account's record names the digest of what was uploaded, and only the phone writes it
        assertEquals(Gateway.idsMarker(people), acct.registry!!.s.getString("studio"))
        assertEquals(Gateway.idsMarker(people), phone.engine.status().studioMark)
    }

    @Test fun the_studios_own_upload_uses_exactly_its_list_and_an_empty_one_takes_everyone_off() {
        val acct = Acct(); val people = ids(2); val phone = Phone(acct, ids(5))
        phone.engine.deploy(studio = people)
        assertEquals(people, acct.lastIds.drop(1))
        phone.engine.deploy(studio = emptyList())
        assertEquals(1, acct.lastIds.size)
        assertFalse("no Studio people: the record reads exactly as the Windows app writes it", acct.registry!!.s.has("studio"))
        assertEquals("", phone.engine.status().studioMark)
    }

    @Test fun ids_that_are_not_ids_or_repeat_bpb_or_a_panel_are_never_uploaded() {
        val acct = Acct(); val phone = Phone(acct)
        phone.engine.connectPanel("EDG")
        val bpb = phone.record!!.uuid; val edg = phone.record!!.panels.getValue("EDG").uuid
        val good = UUID.randomUUID().toString()
        phone.studio = listOf("not-an-id", bpb, edg.uppercase(), good, good)
        phone.engine.deploy()
        assertEquals(listOf(bpb, edg, good), acct.lastIds)
    }

    @Test fun the_worker_never_gets_more_ids_than_its_code_takes_and_the_newest_people_are_the_ones_left_out() {
        val acct = Acct(); val people = ids(20); val phone = Phone(acct, people)
        val steps = mutableListOf<String>()
        phone.engine.deploy(onStep = { steps += it })
        assertEquals(16, acct.lastIds.size)
        assertEquals(people.take(15), acct.lastIds.drop(1))
        assertTrue("said, not silent", steps.any { it.contains("جا نشد") })
        assertEquals(15, phone.engine.studioRoom())
    }

    @Test fun a_panel_that_would_push_a_person_off_the_worker_is_refused_before_anything_is_written() {
        val acct = Acct(); val phone = Phone(acct, ids(15))
        phone.engine.deploy()
        val uploads = acct.uploads
        val record = acct.registry!!.s.toString()
        try { phone.engine.connectPanel("ZEU"); fail() } catch (e: GatewayException) {
            assertTrue(e.fa.contains("جای شناسهٔ تازه ندارد"))
        }
        assertEquals(uploads, acct.uploads)
        assertEquals(record, acct.registry!!.s.toString())
        assertFalse(phone.engine.status().connected("ZEU"))
        // with one person fewer there is room again
        phone.studio = phone.studio.drop(1)
        phone.engine.connectPanel("ZEU")
        assertEquals(16, acct.lastIds.size)
        assertEquals(14, phone.engine.studioRoom())
    }

    @Test fun the_cap_is_read_from_the_workers_own_code() {
        assertEquals(Gateway.DEFAULT_MAX_IDS, Gateway.maxIdsOf(MODULES))
        assertEquals(128, Gateway.maxIdsOf(listOf("config.js" to "export const DEFAULTS = Object.freeze({\n  maxFallbacks: 3,\n  maxUuids: 128,\n});\n")))
        assertEquals(16, Gateway.maxIdsOf(listOf("config.js" to "  maxUuids: 16,")))
        assertEquals(Gateway.DEFAULT_MAX_IDS, Gateway.maxIdsOf(listOf("config.js" to "nothing here")))
        val acct = Acct(); val people = ids(40)
        val phone = Phone(acct, people, MODULES + ("config.js" to "  maxUuids: 32,\n"))
        phone.engine.deploy()
        assertEquals(32, acct.lastIds.size)
        assertEquals(31, phone.engine.studioRoom())
    }

    @Test fun a_marker_that_another_device_dropped_is_taken_in_by_a_pull_even_when_nothing_else_changed() {
        val acct = Acct(); val people = ids(2); val phone = Phone(acct, people)
        phone.engine.deploy()
        assertNotEquals("", phone.record!!.studioMark)
        // the Windows app republishes the same Worker without the field
        val s = acct.registry!!.s
        s.remove("studio")
        acct.registry = acct.registry!!.copy(s = s, by = "windows", at = ++acct.clock)
        phone.engine.sync()
        assertEquals("", phone.engine.status().studioMark)
    }

    @Test fun a_persons_links_are_the_panels_set_with_their_id_and_name() {
        val acct = Acct(); val phone = Phone(acct)
        phone.engine.deploy()
        val bpb = phone.engine.configs().configs
        val id = UUID.randomUUID().toString()
        val mine = phone.engine.linksFor(id, "ali").configs
        assertEquals(bpb.size, mine.size)
        assertTrue(mine.all { it.startsWith("vless://$id@") })
        assertTrue(mine.all { java.net.URLDecoder.decode(it.substringAfter('#'), "UTF-8").startsWith("ali-") })
        // the same addresses and parameters as the panel's, only the id and the name differ
        assertEquals(bpb.map { it.substringAfter('@').substringBefore('#') }, mine.map { it.substringAfter('@').substringBefore('#') })
        try { phone.engine.linksFor("nope", "x"); fail() } catch (e: GatewayException) { }
    }

    companion object {
        val MODULES = listOf("worker.js" to "export default {};\n", "gateway.js" to "export const a = 1;\n")
    }
}
