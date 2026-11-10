package com.mlmvpn.scanner.engines.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Two devices, ONE central Worker — the protocol of docs/PANEL-REGISTRY.md (key `GW`) run against a
 * fake Cloudflare account, exactly as the Windows app's tests/edge-gateway/shared.test.js runs it
 * against its own: neither device makes a second Worker, neither revokes an id the other handed out.
 */
class GatewayEngineTest {

    /** One Cloudflare account: its Workers, its registry, a clock, and the order things happened in. */
    class Account {
        var clock = 1_790_000_000_000L
        fun tick() = ++clock
        var registry: SharedRaw? = null
        val workers = linkedMapOf<String, List<String>>()
        val alive = mutableSetOf<String>()
        val order = mutableListOf<String>()
        val uploads = mutableListOf<Triple<String, List<String>, String>>()
        var refuseUpload = false
        var registryReadFails = false
        var registryWriteFails = false
        val lastIds get() = uploads.last().second
    }

    /** A device: the phone, or a second copy standing in for the desktop. [by] is what it writes as `by`. */
    class Device(val acct: Account, val name: String, val by: String = "android") {
        var record: GatewayRecord? = null
        val steps = mutableListOf<String>()
        var lookups = 0
        var onUpload: () -> Unit = {}
        val store = object : GatewayStore {
            override fun get() = record
            override fun put(rec: GatewayRecord) { record = rec }
            override fun del() { record = null }
        }
        private val pulled = mutableMapOf<String, Long>()
        val cloud = object : GatewayCloud {
            override val by: String = this@Device.by
            override fun registryGet(): SharedRaw? {
                if (acct.registryReadFails) throw IllegalStateException("registry down")
                return acct.registry?.let { it.copy(s = JSONObject(it.s.toString())) }
            }
            override fun registryPut(script: String, url: String, s: JSONObject) {
                if (acct.registryWriteFails) throw IllegalStateException("registry refused")
                acct.order += "reg-put"
                acct.registry = SharedRaw(script, url, JSONObject(s.toString()), by, acct.tick())
            }
            override fun registryRemove(script: String) {
                acct.order += "reg-remove"
                if (acct.registry?.script == script) acct.registry = null
            }
            override fun scriptExists(script: String) = script in acct.alive
            override fun subdomain() = "mysub"
            override fun workerName() = "$name-gw"
            override fun upload(script: String, files: List<Pair<String, String>>, bindings: List<JSONObject>) {
                acct.order += "upload:$script"
                onUpload()
                if (acct.refuseUpload) throw IllegalStateException("script_startup_exception")
                val ids = bindings.first { it.getString("name") == "UUID" }.getString("text").split(',')
                assertEquals("secret_text", bindings.first { it.getString("name") == "UUID" }.getString("type"))
                acct.workers[script] = ids
                acct.alive += script
                acct.uploads += Triple(script, ids, name)
            }
            override fun enableWorkersDev(script: String) {}
            override fun removeWorker(script: String) { acct.workers.remove(script); acct.alive.remove(script) }
            override fun randomUuid() = UUID.randomUUID().toString()
            override fun randomPath() = "/" + (1..16).joinToString("") { "0123456789abcdef".random().toString() }
        }
        val engine = GatewayEngine("acc1", cloud, store, { MODULES }, { lookups++; DohAnswer(listOf("104.21.8.12", "172.67.140.9"), listOf("2606:4700:3030::6815:80c")) }, now = { acct.tick() }, pulledAt = pulled)
        val rec: GatewayRecord get() = record!!
        fun deploy(fallback: String? = null, rotate: Boolean = false) = engine.deploy(fallback, rotate, onStep = { steps += it })
        fun sync() = engine.sync { steps += it }
        fun connect(code: String) = engine.connectPanel(code) { steps += it }
        fun disconnect(code: String) = engine.disconnectPanel(code) { steps += it }
    }

    @Before fun fresh() {
        GatewayEngine.resetForTest()
        GatewayEngine.language = { "fa" }
    }

    private fun failure(block: () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        fail("expected a failure")
        throw AssertionError()
    }

    private fun userOf(link: String) = link.substringAfter("vless://").substringBefore('@')

    // ── what is written, and when ─────────────────────────────────────────────────────────

    @Test fun install_writes_the_record_to_the_account_BEFORE_the_worker_is_touched() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows")
        pc.deploy()
        assertEquals(listOf("reg-put", "upload:pc-core-gw"), acct.order)
        val g = acct.registry!!
        assertEquals("pc-core-gw", g.script); assertEquals("https://pc-core-gw.mysub.workers.dev", g.url); assertEquals("windows", g.by)
        assertEquals(setOf("uuid", "wsPath", "fallback", "panels", "settings", "codeHash"), g.s.keys().asSequence().toSet())
        assertEquals(pc.rec.uuid, g.s.getString("uuid")); assertEquals(Gateway.DEFAULT_FALLBACK, g.s.getString("fallback"))
        assertEquals(Gateway.codeHash(MODULES), g.s.getString("codeHash"))
        val view = pc.engine.status()
        assertTrue(view.installed); assertTrue(view.shared); assertEquals("windows", view.sharedBy); assertTrue(view.upToDate)
        assertFalse("the public view never carries the id", view.toString().contains(pc.rec.uuid))
        assertFalse(view.toString().contains(pc.rec.wsPath))
    }

    @Test fun the_worker_gets_the_ids_and_path_as_bindings() {
        val acct = Account(); val pc = Device(acct, "pc-core")
        pc.connect("EDG")
        assertEquals(listOf(pc.rec.uuid, pc.rec.panels.getValue("EDG").uuid), acct.lastIds)
        assertTrue(acct.lastIds.all { Gateway.UUID_RE.matches(it) })
        assertTrue(Gateway.WS_PATH_RE.matches(pc.rec.wsPath))
    }

    @Test fun the_first_connect_on_an_empty_account_is_ONE_upload_with_the_panels_id_already_in_it() {
        val acct = Account(); val pc = Device(acct, "pc-core")
        val view = pc.connect("EDG")
        assertEquals(1, acct.uploads.size)
        assertTrue(view.installed); assertTrue(view.connected("EDG"))
        assertEquals(setOf("pc-core-gw"), acct.workers.keys)
        pc.connect("EDG")
        assertEquals("connecting a connected panel uploads nothing", 1, acct.uploads.size)
        pc.connect("ZEU")
        assertEquals(2, acct.uploads.size)
        assertEquals("still one Worker", setOf("pc-core-gw"), acct.workers.keys)
    }

    @Test fun only_the_panels_this_build_knows_never_BPB() {
        val acct = Account(); val pc = Device(acct, "pc-core")
        for (bad in listOf("BPB", "XYZ", "")) assertTrue(failure { pc.connect(bad) } is GatewayException)
        assertTrue(failure { pc.disconnect("BPB") } is GatewayException)
        assertEquals(0, acct.uploads.size)
        for (c in Gateway.PANEL_CODES) pc.connect(c)
        assertEquals("BPB + eight panels fit the Worker's sixteen ids", 9, acct.lastIds.size)
        assertEquals(9, acct.lastIds.toSet().size)
    }

    // ── a second device ────────────────────────────────────────────────────────────────────

    @Test fun a_second_device_finds_the_accounts_worker_and_uses_it_with_the_very_same_links() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows"); val phone = Device(acct, "phone-core")
        pc.deploy()
        val (pulled, view) = phone.sync()
        assertEquals("adopted", pulled.state); assertTrue(pulled.changed); assertTrue(view.shared); assertEquals("windows", view.sharedBy)
        assertTrue(phone.steps.joinToString().contains("ورکر تازه‌ای ساخته نمی‌شود"))
        assertEquals("pc-core-gw", phone.rec.script); assertEquals(pc.rec.uuid, phone.rec.uuid); assertEquals(pc.rec.wsPath, phone.rec.wsPath)
        assertEquals(pc.engine.configs().configs, phone.engine.configs().configs)

        val tablet = Device(acct, "tablet-core")
        tablet.deploy()
        assertEquals("one Worker on the account", setOf("pc-core-gw"), acct.workers.keys)
        assertEquals("pc-core-gw", tablet.rec.script)
        assertTrue(acct.uploads.all { it.first == "pc-core-gw" })
    }

    @Test fun a_device_with_no_record_gets_configs_from_the_worker_another_device_installed_without_any_upload() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows"); val phone = Device(acct, "phone-core")
        pc.deploy()
        val uploads = acct.uploads.size
        val r = phone.engine.configs()
        assertTrue(r.configs.isNotEmpty())
        assertEquals(uploads, acct.uploads.size)
        assertEquals(pc.rec.uuid, userOf(r.configs[0]))
    }

    @Test fun a_panel_connected_on_one_device_is_connected_on_the_other_and_a_stale_device_never_revokes_it() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows"); val phone = Device(acct, "phone-core")
        pc.deploy(); phone.sync()
        phone.connect("EDG")
        val edgId = phone.rec.panels.getValue("EDG").uuid
        assertEquals(listOf(pc.rec.uuid, edgId), acct.lastIds)
        assertTrue("the PC has not looked yet", pc.rec.panels.isEmpty())

        // the PC, still on its old copy, connects another panel: it takes the phone's change in FIRST
        pc.connect("ZEU")
        assertEquals(listOf(pc.rec.uuid, edgId, pc.rec.panels.getValue("ZEU").uuid), acct.lastIds)
        assertEquals(edgId, pc.rec.panels.getValue("EDG").uuid)

        val (pulled, view) = phone.sync()
        assertTrue(pulled.changed)
        assertEquals(setOf("EDG", "ZEU"), view.panels.keys)
        assertEquals(pc.rec.panels.getValue("ZEU").uuid, userOf(phone.engine.configs("ZEU").configs[0]))
        assertTrue(acct.uploads.all { it.first == "pc-core-gw" })
    }

    @Test fun the_account_always_names_at_least_every_id_the_worker_accepts() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows"); val phone = Device(acct, "phone-core")
        fun check() {
            val s = acct.registry!!.s
            val named = setOf(s.getString("uuid")) + s.getJSONObject("panels").keys().asSequence().map { s.getJSONObject("panels").getJSONObject(it).getString("uuid") }
            for (id in acct.lastIds) assertTrue("the Worker accepts $id but the account does not name it", id in named)
        }
        pc.connect("EDG"); check()
        phone.connect("NTR"); check()
        pc.connect("SPD"); check()
        phone.disconnect("EDG"); check()
        pc.deploy(rotate = true); check()
        phone.engine.saveSettings(SettingsPatch(), fallback = "proxy.example:8443"); check()
        assertEquals(setOf("NTR", "SPD"), acct.registry!!.s.getJSONObject("panels").keys().asSequence().toSet())
    }

    @Test fun disconnecting_on_one_device_takes_the_id_off_the_worker_and_off_the_other_devices_list() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows"); val phone = Device(acct, "phone-core")
        pc.connect("EDG"); pc.connect("NTR"); phone.sync()
        phone.disconnect("EDG")
        assertEquals(listOf(phone.rec.uuid, phone.rec.panels.getValue("NTR").uuid), acct.lastIds)
        assertTrue(pc.sync().first.changed)
        assertEquals(setOf("NTR"), pc.engine.status().panels.keys)
        val e = failure { pc.engine.configs("EDG") }
        assertTrue(e is GatewayException && e.fa.contains("به ورکر مرکزی وصل نیست"))
    }

    @Test fun rotate_on_one_device_the_other_adopts_the_new_ids_and_starts_its_counts_over() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows"); val phone = Device(acct, "phone-core")
        pc.connect("EDG"); phone.sync(); phone.engine.configs("EDG")
        assertTrue(phone.rec.panels.getValue("EDG").configCount > 0)
        val old = phone.rec
        pc.deploy(rotate = true)
        assertTrue(phone.sync().first.changed)
        assertNotEquals(old.uuid, phone.rec.uuid); assertNotEquals(old.wsPath, phone.rec.wsPath)
        assertNotEquals(old.panels.getValue("EDG").uuid, phone.rec.panels.getValue("EDG").uuid)
        assertEquals(0, phone.rec.panels.getValue("EDG").configCount)
        assertEquals(old.script, phone.rec.script)
    }

    @Test fun the_settings_are_shared_both_devices_list_the_same_links_after_a_change_on_either() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows"); val phone = Device(acct, "phone-core")
        pc.deploy(); phone.sync()
        val uploads = acct.uploads.size
        phone.engine.saveSettings(SettingsPatch(cleanIPs = "104.16.1.1\nzula.ir", ports = listOf(443, 8443), methods = false))
        assertEquals("link-shaping settings need no upload", uploads, acct.uploads.size)
        assertEquals(listOf(443, 8443), GatewaySettings.fromJson(acct.registry!!.s.getJSONObject("settings")).ports)
        pc.sync()
        assertEquals(pc.engine.configs().configs, phone.engine.configs().configs)
        assertTrue(pc.engine.configs().configs.any { it.contains("@zula.ir:8443?") })
    }

    // ── when the account cannot be reached, or the Worker refuses ───────────────────────────

    @Test fun a_registry_that_refuses_never_stops_an_install_and_the_next_sync_tells_the_account() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows")
        acct.registryWriteFails = true
        val view = pc.deploy()
        assertTrue(view.installed); assertFalse("the screen says the other device does not know it yet", view.shared)
        assertTrue(pc.steps.joinToString().contains("ثبت در حساب نشد"))
        assertNull(acct.registry)

        acct.registryWriteFails = false
        val (pulled, shared) = pc.sync()
        assertEquals("published", pulled.state); assertTrue(shared.shared)
        assertEquals("pc-core-gw", acct.registry!!.script)

        // a later change that did not reach the account is published by the next sync, not lost
        acct.registryWriteFails = true
        pc.connect("EDG")
        assertFalse(pc.engine.status().shared)
        assertNull(acct.registry!!.s.getJSONObject("panels").optJSONObject("EDG"))
        acct.registryWriteFails = false
        assertEquals("pushed", pc.sync().first.state)
        assertNotNull(acct.registry!!.s.getJSONObject("panels").optJSONObject("EDG"))
        assertTrue(pc.engine.status().shared)
    }

    @Test fun a_registry_that_cannot_be_read_leaves_this_device_working_on_its_own_copy() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows")
        pc.deploy()
        acct.registryReadFails = true
        assertTrue(pc.engine.configs(refresh = true).configs.isNotEmpty())
        pc.connect("EDG")
        assertNotNull(pc.rec.panels["EDG"])
        assertTrue(pc.steps.joinToString().contains("ثبت مشترک حساب خوانده نشد"))
    }

    @Test fun a_worker_that_refuses_the_upload_puts_the_accounts_record_back() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows")
        acct.refuseUpload = true
        assertTrue(failure { pc.connect("EDG") } is IllegalStateException)
        assertNull("a first install that failed leaves nothing in the account", acct.registry)
        assertNull(pc.record)

        acct.refuseUpload = false
        pc.deploy()
        val held = acct.registry!!
        acct.refuseUpload = true
        failure { pc.connect("EDG") }
        failure { pc.deploy(rotate = true) }
        assertEquals("the account still names exactly the ids the Worker has", held.s.toString(), acct.registry!!.s.toString())
        assertEquals(held.script, acct.registry!!.script)
        assertNull("the record does not claim a panel the Worker never took", pc.rec.panels["EDG"])
    }

    // ── records that must not be trusted ─────────────────────────────────────────────────────

    private fun seed(acct: Account, script: String, alive: Boolean = true, uuid: String = "0b5c4f1e-6d2a-4c9b-8f31-7a2d9e4c1b60", at: Long = acct.tick()) {
        acct.registry = SharedRaw(script, "https://$script.mysub.workers.dev", JSONObject()
            .put("uuid", uuid).put("wsPath", "/a1b2c3d4e5f60718").put("fallback", "").put("panels", JSONObject()).put("settings", JSONObject()).put("codeHash", "x"), "android", at)
        if (alive) acct.alive += script
    }

    @Test fun a_record_whose_worker_is_gone_and_one_that_is_not_valid_are_ignored() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows")
        seed(acct, "old-gw", alive = false)
        assertEquals("dead", pc.sync().first.state); assertNull(pc.record)
        seed(acct, "old-gw", uuid = "nope")
        assertEquals("invalid", pc.sync().first.state); assertNull(pc.record)
        pc.deploy()
        assertEquals("a deploy over a bad record makes the Worker and replaces the record", "pc-core-gw", acct.registry!!.script)
    }

    @Test fun two_central_workers_the_accounts_wins_and_this_devices_own_is_reported_and_left_alone() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows")
        pc.deploy()
        seed(acct, "phone-core-gw", uuid = "9f3a6c1e-1b2d-4e5f-8a7b-0c9d8e7f6a5b")
        val (pulled, view) = pc.sync()
        assertEquals("adopted", pulled.state); assertEquals("pc-core-gw", pulled.replaced); assertEquals("pc-core-gw", view.extraScript)
        assertEquals("phone-core-gw", pc.rec.script)
        assertTrue("nothing was deleted from the account", "pc-core-gw" in acct.alive)
        assertTrue(pc.steps.joinToString().contains("دست‌نخورده روی حساب مانده است"))
    }

    // ── cleaning up ─────────────────────────────────────────────────────────────────────────

    @Test fun remove_takes_the_account_record_with_the_worker_and_the_stale_device_finds_nothing() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows"); val phone = Device(acct, "phone-core")
        pc.deploy(); phone.sync()
        assertTrue(phone.engine.remove())
        assertFalse("the Worker the PC installed is the one that goes", "pc-core-gw" in acct.alive)
        assertNull("and the other device no longer points at a Worker that is gone", acct.registry)
        assertNull(phone.record)
        assertTrue("the PC's copy is stale: its own record is simply dropped", pc.engine.remove())
        assertFalse(pc.engine.remove())
    }

    @Test fun the_pull_is_not_asked_for_on_every_call() {
        val acct = Account(); val pc = Device(acct, "pc-core", "windows")
        pc.deploy()
        assertEquals("asked a moment ago, by the deploy", "skipped", pc.engine.pull(force = false).state)
        acct.registryReadFails = true
        assertEquals("the registry is not even asked within the minute", "skipped", pc.engine.pull(force = false).state)
        assertEquals("a forced pull (a sync) always asks", "unreadable", pc.engine.pull(force = true).state)
        acct.clock += 61_000
        assertEquals("after a minute it asks again", "unreadable", pc.engine.pull(force = false).state)
    }

    @Test fun two_installs_at_once_for_one_account_the_second_is_refused_not_interleaved() {
        val acct = Account(); val pc = Device(acct, "pc-core")
        var inner: Exception? = null
        pc.onUpload = { inner = failure { pc.deploy() } }
        pc.deploy()
        assertTrue(inner is GatewayException && (inner as GatewayException).fa.contains("در حال انجام"))
        pc.onUpload = {}
        pc.deploy()   // free again afterwards
    }

    // ── the links ───────────────────────────────────────────────────────────────────────────

    @Test fun a_fresh_answer_is_reused_for_hours_and_not_asked_again_until_refreshed() {
        val acct = Account(); val pc = Device(acct, "pc-core")
        pc.deploy()
        val first = pc.engine.configs()
        assertEquals("live", first.resolvedFrom); assertEquals(2, first.resolvedV4)
        val asked = pc.lookups
        assertEquals("cache", pc.engine.configs().resolvedFrom)
        assertEquals(asked, pc.lookups)
        pc.engine.configs(refresh = true)
        assertEquals(asked + 1, pc.lookups)
    }

    @Test fun a_lookup_that_fails_still_returns_the_name_and_the_clean_ips_and_a_stale_answer_beats_none() {
        val acct = Account()
        val pc = Device(acct, "pc-core")
        pc.deploy()
        val broken = GatewayEngine("acc1", pc.cloud, pc.store, { MODULES }, { throw IllegalStateException("fetch failed") }, now = { acct.tick() }, pulledAt = mutableMapOf())
        val none = broken.configs()
        assertEquals("none", none.resolvedFrom)
        assertEquals(2, none.configs.count { !it.contains("mlm_method") })
        // an old answer already on the record is used when the lookup fails
        pc.record = pc.rec.copy(resolved = Resolved(acct.clock - 48 * 3600_000L, listOf("104.21.0.9"), emptyList()))
        val stale = broken.configs()
        assertEquals("stale", stale.resolvedFrom)
        assertTrue(stale.configs.any { it.contains("@104.21.0.9:") })
    }

    @Test fun a_panels_links_carry_its_own_id_and_its_own_name_and_BPBs_are_untouched() {
        val acct = Account(); val pc = Device(acct, "pc-core")
        pc.connect("EDG")
        val edge = pc.engine.configs("EDG").configs
        assertTrue(edge.size >= 5)
        for (l in edge) {
            assertEquals(pc.rec.panels.getValue("EDG").uuid, userOf(l))
            assertTrue(java.net.URLDecoder.decode(l.substringAfterLast('#'), "UTF-8").startsWith("Edge-"))
        }
        assertTrue(edge.any { it.contains("-ECH-") || it.contains("%2DECH%2D") || java.net.URLDecoder.decode(it.substringAfterLast('#'), "UTF-8").contains("-ECH-") })
        assertEquals(edge.size, pc.rec.panels.getValue("EDG").configCount)
        assertEquals(0, pc.rec.configCount)
        val bpb = pc.engine.configs().configs
        for (l in bpb) assertEquals(pc.rec.uuid, userOf(l))
        assertTrue(failure { pc.engine.configs("ZEU") } is GatewayException)
        assertEquals(edge.size, pc.engine.status().panels.getValue("EDG").configs)
    }

    @Test fun update_keeps_every_panels_id_and_rotate_renews_them_all() {
        val acct = Account(); val pc = Device(acct, "pc-core")
        pc.connect("EDG"); pc.connect("NTR")
        val first = pc.rec
        pc.deploy()
        assertEquals(listOf(first.uuid, first.panels.getValue("EDG").uuid, first.panels.getValue("NTR").uuid), acct.lastIds)
        pc.deploy(rotate = true)
        val next = pc.rec
        assertNotEquals(first.uuid, next.uuid)
        assertNotEquals(first.panels.getValue("EDG").uuid, next.panels.getValue("EDG").uuid)
        assertEquals(listOf(next.uuid, next.panels.getValue("EDG").uuid, next.panels.getValue("NTR").uuid), acct.lastIds)
        assertEquals(first.script, next.script)
    }

    companion object {
        val MODULES = listOf("worker.js" to "export default {};\n", "gateway.js" to "export const a = 1;\n")
    }
}
