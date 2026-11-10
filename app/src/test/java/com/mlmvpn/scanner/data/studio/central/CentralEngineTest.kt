package com.mlmvpn.scanner.data.studio.central

import com.mlmvpn.scanner.data.studio.api.RenewMode
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.UserStatus
import com.mlmvpn.scanner.engines.gateway.Gateway
import org.json.JSONArray
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
 * «کانفیگ استدیو» on the central Worker, run against a fake Cloudflare account: the people live on
 * the phones, the Worker only ever learns a list of ids, and no device — this phone, a second phone,
 * the Windows app — loses a person or lets one in who should be out.
 */
class CentralEngineTest {

    /** One Cloudflare account: its registry record, its central Worker, a clock, and the order of events. */
    class Account(var room: Int = 7) {
        var clock = 1_790_000_000_000L
        fun tick(ms: Long = 1) = run { clock += ms; clock }
        var record: RegistryRead.Found? = null
        var noAccess = false
        var readFails = false
        var writeFails = false
        var refuse = false
        var installed = true
        val script = "core-a1b2c3-gw"
        val host = "core-a1b2c3-gw.sub.workers.dev"
        var workerIds: List<String> = emptyList()
        var mark = ""
        val order = mutableListOf<String>()
        var uploads = 0

        /** Another device uploads the Worker without knowing the Studio (the Windows app, an older phone). */
        fun foreignUpload() { workerIds = emptyList(); mark = ""; uploads++; order += "foreign-upload" }
    }

    class Device(val acct: Account, val name: String) {
        var doc: CentralDoc? = null
        val steps = mutableListOf<String>()
        private var gwSeq = 0
        val cloud = object : CentralCloud {
            override fun registryGet(): RegistryRead {
                if (acct.readFails) throw IllegalStateException("registry down")
                if (acct.noAccess) return RegistryRead.NoAccess
                return acct.record?.let { it.copy(s = JSONObject(it.s.toString())) } ?: RegistryRead.Missing
            }
            override fun registryPut(script: String, url: String, s: JSONObject) {
                if (acct.writeFails) throw IllegalStateException("registry refused")
                acct.order += "reg-put"
                acct.record = RegistryRead.Found(script, url, JSONObject(s.toString()), "android", acct.tick())
            }
            override fun registryRemove(script: String) {
                acct.order += "reg-remove"
                if (acct.record?.script == script) acct.record = null
            }
            override fun gateway(): GatewayView =
                GatewayView(acct.installed, if (acct.installed) acct.script else "", if (acct.installed) acct.host else "", acct.room, acct.mark)
            override fun apply(ids: List<String>, onStep: (String) -> Unit): GatewayView {
                acct.order += "upload"
                if (acct.refuse) throw IllegalStateException("script_startup_exception")
                assertTrue("never more than the Worker takes", ids.size <= acct.room)
                acct.installed = true
                acct.workerIds = ids
                acct.mark = Gateway.idsMarker(ids)
                acct.uploads++
                return GatewayView(true, acct.script, acct.host, acct.room, acct.mark)
            }
        }
        val local = object : CentralLocal {
            override fun get() = doc
            override fun put(doc: CentralDoc) { this@Device.doc = doc }
        }
        val engine = CentralEngine("acc-$name", "cf-1", cloud, local, now = { acct.clock }, locks = mutableMapOf())

        fun newGw() = UUID.randomUUID().toString()
        fun create(name: String, days: Int = 30): CentralUser = engine.mutate({ steps += it }) { d, room ->
            CentralRules.create(d, name, if (days > 0) acct.clock + days * Central.DAY_MS else 0L, acct.clock, room, Central::newUserId, ::newGw)
        }
        fun idOf(user: String) = engine.current().people.first { it.name == user }.id
    }

    @Before fun lang() { CentralEngine.language = { "fa" } }

    private fun expectRule(code: String, block: () -> Unit) {
        try { block() } catch (e: CentralRules.RuleError) { assertEquals(code, e.code); return }
        fail("expected $code")
    }

    // ── what is written, and when ─────────────────────────────────────────────────────────

    @Test fun a_new_person_is_written_to_the_account_BEFORE_the_worker_and_then_put_on_it() {
        val acct = Account(); val phone = Device(acct, "phone")
        val ali = phone.create("ali")
        assertEquals(listOf("reg-put", "upload"), acct.order)
        assertEquals(listOf(ali.gw), acct.workerIds)
        val s = acct.record!!.s
        assertEquals(acct.script, acct.record!!.script)
        assertEquals(listOf(ali.gw), CentralDoc.sharedLiveIds(s, acct.clock))
        assertEquals(ali.expiresAt, s.getJSONArray("ids").getJSONObject(0).getLong("until"))
        val doc = phone.engine.current()
        assertEquals(listOf(ali.gw), doc.appliedIds)
        assertEquals(acct.script, doc.script); assertEquals(acct.host, doc.host)
        assertFalse(doc.unshared)
        assertTrue(Gateway.UUID_RE.matches(ali.gw)); assertTrue(ali.id.startsWith("c"))
    }

    @Test fun a_worker_that_refuses_leaves_everything_as_it_was() {
        val acct = Account(); val phone = Device(acct, "phone")
        val ali = phone.create("ali")
        val before = acct.record!!.s.toString()
        acct.refuse = true
        try { phone.create("sara"); fail() } catch (e: IllegalStateException) { }
        assertEquals("the account's list went back", before, acct.record!!.s.toString())
        assertEquals(listOf(ali.gw), acct.workerIds)
        assertEquals("this phone kept its old copy", listOf("ali"), phone.engine.current().people.map { it.name })

        // the very first person on an account with no list: a refusal removes the list again
        val acct2 = Account(); val other = Device(acct2, "phone")
        acct2.refuse = true
        try { other.create("ali"); fail() } catch (e: IllegalStateException) { }
        assertNull(acct2.record)
        assertTrue(acct2.order.contains("reg-remove"))
        assertTrue(other.engine.current().people.isEmpty())
    }

    @Test fun a_list_that_cannot_be_read_stops_a_change_and_nothing_is_written() {
        val acct = Account(); val phone = Device(acct, "phone")
        phone.create("ali")
        acct.order.clear()
        acct.readFails = true
        expectRule(CentralEngine.UNREADABLE) { phone.create("sara") }
        assertTrue("no write over a list nobody has seen", acct.order.isEmpty())
    }

    @Test fun a_credential_without_kv_rights_still_works_and_shares_nothing() {
        val acct = Account(); acct.noAccess = true
        val phone = Device(acct, "phone")
        val ali = phone.create("ali")
        assertEquals(listOf("upload"), acct.order)
        assertEquals(listOf(ali.gw), acct.workerIds)
        assertFalse("nothing to share, so nothing left unshared", phone.engine.current().unshared)
    }

    @Test fun a_list_write_that_fails_does_not_stop_the_worker_and_is_published_later() {
        val acct = Account(); val phone = Device(acct, "phone")
        acct.writeFails = true
        val ali = phone.create("ali")
        assertEquals(listOf(ali.gw), acct.workerIds)
        assertTrue(phone.engine.current().unshared)
        assertTrue(phone.steps.joinToString().contains("ثبت نشد"))
        acct.writeFails = false
        val r = phone.engine.reconcile()
        assertTrue(r.published); assertFalse("the Worker was already right", r.uploaded)
        assertEquals(listOf(ali.gw), CentralDoc.sharedLiveIds(acct.record!!.s, acct.clock))
        assertFalse(phone.engine.current().unshared)
    }

    @Test fun a_change_that_moves_nobody_on_or_off_the_worker_is_written_to_the_account_without_an_upload() {
        val acct = Account(); val phone = Device(acct, "phone")
        val ali = phone.create("ali")
        val uploads = acct.uploads
        acct.order.clear()
        phone.engine.mutate { d, room -> CentralRules.patch(d, ali.id, acct.clock, room, note = "pays monthly", tags = listOf("vip")) }
        phone.engine.mutate { d, room -> CentralRules.renew(d, ali.id, 10, com.mlmvpn.scanner.data.studio.api.RenewMode.EXTEND, acct.clock, room) }
        assertEquals("a note and a renewal inside the term change nobody on the Worker", uploads, acct.uploads)
        assertEquals(listOf("reg-put", "reg-put"), acct.order)
        val shared = CentralDoc.parseUsers(acct.record!!.s).first { it.id == ali.id }
        assertEquals("pays monthly", shared.note)
        assertEquals(ali.expiresAt + 10 * Central.DAY_MS, shared.expiresAt)
        // switching them off does: their id has to leave the Worker
        phone.engine.mutate { d, room -> CentralRules.setEnabled(d, ali.id, false, acct.clock, room) }
        assertEquals(uploads + 1, acct.uploads)
        assertTrue(acct.workerIds.isEmpty())
    }

    @Test fun the_first_person_on_an_account_without_a_central_worker_installs_it_in_the_same_upload() {
        val acct = Account(); acct.installed = false
        val phone = Device(acct, "phone")
        val ali = phone.create("ali")
        assertEquals("the script is only known after the install", listOf("upload", "reg-put"), acct.order)
        assertTrue(acct.installed)
        assertEquals(listOf(ali.gw), acct.workerIds)
        assertEquals(acct.script, acct.record!!.script)
    }

    // ── room on the Worker ─────────────────────────────────────────────────────────────────

    @Test fun the_worker_never_gets_more_people_than_it_has_room_for() {
        val acct = Account(room = 2); val phone = Device(acct, "phone")
        phone.create("a"); phone.create("b")
        expectRule(CentralRules.NO_ROOM) { phone.create("c") }
        assertEquals(2, acct.workerIds.size)
        // someone switched off frees their place; switching them back needs it again
        val a = phone.idOf("a")
        phone.engine.mutate { d, room -> CentralRules.setEnabled(d, a, false, acct.clock, room) }
        phone.create("c")
        expectRule(CentralRules.NO_ROOM) { phone.engine.mutate<CentralUser> { d, room -> CentralRules.setEnabled(d, a, true, acct.clock, room) } }
        assertEquals(2, acct.workerIds.size)
    }

    @Test fun an_ended_term_frees_a_place_and_a_renewal_takes_one() {
        val acct = Account(room = 1); val phone = Device(acct, "phone")
        phone.create("a", days = 1)
        acct.tick(2 * Central.DAY_MS)
        val r = phone.engine.reconcile()
        assertTrue("the ended term came off the Worker", r.uploaded)
        assertTrue(acct.workerIds.isEmpty())
        phone.create("b")
        val a = phone.idOf("a")
        expectRule(CentralRules.NO_ROOM) { phone.engine.mutate<CentralUser> { d, room -> CentralRules.renew(d, a, 30, RenewMode.EXTEND, acct.clock, room) } }
    }

    // ── a second phone ─────────────────────────────────────────────────────────────────────

    @Test fun a_second_phone_sees_the_same_people_and_never_drops_one() {
        val acct = Account(); val phone = Device(acct, "phone"); val tablet = Device(acct, "tablet")
        val ali = phone.create("ali")
        assertEquals(listOf("ali"), tablet.engine.pull().people.map { it.name })
        // the tablet adds someone without having looked first: it takes the phone's list in FIRST
        val tablet2 = Device(acct, "tablet2")
        val sara = tablet2.create("sara")
        assertEquals(setOf(ali.gw, sara.gw), acct.workerIds.toSet())
        assertEquals(setOf("ali", "sara"), phone.engine.pull().people.map { it.name }.toSet())
    }

    @Test fun two_phones_that_change_in_the_same_moment_are_put_right_by_the_next_look() {
        val acct = Account(); val phone = Device(acct, "phone"); val tablet = Device(acct, "tablet")
        val base = phone.create("base")
        tablet.engine.pull()
        val beforeSara = acct.record
        val sara = tablet.create("sara")
        // The phone read the list in the instant BEFORE the tablet wrote sara into it: its write and
        // its upload both go without her (the documented same-second race, as on the `GW` record).
        acct.record = beforeSara
        val ali = phone.create("ali")
        assertFalse(acct.workerIds.contains(sara.gw))
        // the next look from the tablet — which still has her — puts everything right
        val r = tablet.engine.reconcile()
        assertTrue(r.published); assertTrue(r.uploaded)
        assertEquals(setOf(base.gw, sara.gw, ali.gw), acct.workerIds.toSet())
        assertEquals(setOf(base.gw, sara.gw, ali.gw), CentralDoc.sharedLiveIds(acct.record!!.s, acct.clock).toSet())
        // and the phone, taking that in, agrees without another upload
        val uploads = acct.uploads
        assertFalse(phone.engine.reconcile().uploaded)
        assertEquals(uploads, acct.uploads)
        assertEquals(setOf("base", "sara", "ali"), phone.engine.current().people.map { it.name }.toSet())
    }

    // ── the Windows app and older phones ───────────────────────────────────────────────────

    @Test fun an_upload_that_dropped_the_studio_ids_is_put_right_and_two_phones_never_take_turns() {
        val acct = Account(); val phone = Device(acct, "phone"); val tablet = Device(acct, "tablet")
        val ali = phone.create("ali"); tablet.engine.pull()
        acct.foreignUpload()
        assertTrue(acct.workerIds.isEmpty())
        val r1 = phone.engine.reconcile()
        assertTrue(r1.uploaded)
        assertEquals(listOf(ali.gw), acct.workerIds)
        val uploads = acct.uploads
        assertFalse(phone.engine.reconcile().uploaded)
        assertFalse("the other phone agrees, so it does not upload too", tablet.engine.reconcile().uploaded)
        assertFalse(phone.engine.reconcile().uploaded)
        assertEquals(uploads, acct.uploads)
    }

    @Test fun nobody_is_uploaded_for_an_account_with_no_studio_people() {
        val acct = Account(); val phone = Device(acct, "phone")
        val r = phone.engine.reconcile()
        assertFalse(r.uploaded); assertFalse(r.published)
        assertEquals(0, acct.uploads)
        acct.installed = false
        assertFalse(phone.engine.reconcile().uploaded)
    }

    @Test fun a_panel_connected_elsewhere_that_shrinks_the_room_leaves_the_newest_person_out_and_says_so() {
        val acct = Account(room = 2); val phone = Device(acct, "phone")
        val a = phone.create("a"); acct.tick(); phone.create("b")
        acct.room = 1
        acct.foreignUpload()
        val r = phone.engine.reconcile()
        assertTrue(r.uploaded)
        assertEquals(1, r.leftOut)
        assertEquals("the oldest keeps their place", listOf(a.gw), acct.workerIds)
    }

    // ── terms ──────────────────────────────────────────────────────────────────────────────

    @Test fun renewal_reads_like_an_engines() {
        val now = 1_790_000_000_000L
        var doc = CentralDoc("acc")
        val (d1, u) = CentralRules.create(doc, "ali", now + 10 * Central.DAY_MS, now, 10, Central::newUserId, { UUID.randomUUID().toString() })
        doc = d1
        // early renewal is not punished: from the current end
        val (d2, extended) = CentralRules.renew(doc, u.id, 30, RenewMode.EXTEND, now, 10)
        assertEquals(now + 40 * Central.DAY_MS, extended.expiresAt)
        // taking back never goes below now, and a correction does not switch anyone back on
        val (d3, off) = CentralRules.setEnabled(d2, u.id, false, now, 10)
        val (d4, back) = CentralRules.renew(d3, u.id, -500, RenewMode.EXTEND, now, 10)
        assertEquals(now, back.expiresAt); assertFalse(back.enabled)
        // a grant switches them back on
        val (d5, again) = CentralRules.renew(d4, u.id, 7, RenewMode.EXTEND, now, 10)
        assertTrue(again.enabled); assertEquals(now + 7 * Central.DAY_MS, again.expiresAt)
        // restart starts the term over from now
        val (_, restarted) = CentralRules.renew(d5, u.id, 3, RenewMode.RESTART, now + Central.DAY_MS, 10)
        assertEquals(now + 4 * Central.DAY_MS, restarted.expiresAt)
        // nothing to renew with is refused, a plan's days fill in
        expectRule(CentralRules.BAD_REQUEST) { CentralRules.renew(d5, u.id, null, RenewMode.EXTEND, now, 10) }
        val (_, byPlan) = CentralRules.renew(d5, u.id, null, RenewMode.EXTEND, now, 10, planDays = 30, planId = "p30")
        assertEquals("p30", byPlan.planId)
        assertTrue(off.updatedAt > 0)
    }

    @Test fun names_are_unique_on_an_account_and_a_deleted_name_is_free_again() {
        val now = 1_790_000_000_000L
        val gen = { UUID.randomUUID().toString() }
        val (d1, u) = CentralRules.create(CentralDoc("acc"), "Ali", 0, now, 10, Central::newUserId, gen)
        expectRule(CentralRules.TAKEN) { CentralRules.create(d1, "  ali ", 0, now, 10, Central::newUserId, gen) }
        val d2 = CentralRules.delete(d1, u.id, now + 1)
        val (d3, again) = CentralRules.create(d2, "ali", 0, now + 2, 10, Central::newUserId, gen)
        assertNotEquals(u.id, again.id)
        assertNotEquals("a tombstone's Worker id never comes back", u.gw, again.gw)
        assertEquals(1, d3.people.size)
        assertTrue(d3.user(u.id)!!.isDeleted)
        expectRule(CentralRules.NOT_FOUND) { CentralRules.delete(d3, u.id, now + 3) }
    }

    @Test fun a_new_link_is_a_new_id_for_that_person_alone() {
        val acct = Account(); val phone = Device(acct, "phone")
        val ali = phone.create("ali"); val sara = phone.create("sara")
        val fresh = phone.engine.mutate { d, _ -> CentralRules.rotate(d, ali.id, acct.clock, phone::newGw) }
        assertNotEquals(ali.gw, fresh.gw)
        assertEquals(setOf(fresh.gw, sara.gw), acct.workerIds.toSet())
        assertFalse(acct.workerIds.contains(ali.gw))
    }

    @Test fun deletion_wins_over_an_older_edit_from_another_phone_and_a_newer_edit_wins_over_an_older_one() {
        val now = 1_790_000_000_000L
        val base = CentralUser("c1", "ali", UUID.randomUUID().toString(), updatedAt = now)
        val deleted = base.asTombstone(now + 5)
        val olderEdit = base.copy(note = "x", updatedAt = now + 3)
        assertEquals(deleted, CentralUser.newer(deleted, olderEdit))
        assertEquals(deleted, CentralUser.newer(olderEdit, deleted))
        val sameMoment = base.copy(note = "y", updatedAt = now + 5)
        assertEquals("deleted and edited in the same instant stays deleted", deleted, CentralUser.newer(sameMoment, deleted))
        val later = base.copy(note = "z", updatedAt = now + 9)
        assertEquals(later, CentralUser.newer(deleted, later))
        // the merge itself
        val local = CentralDoc("acc", users = listOf(deleted))
        val remote = JSONObject().put("users", JSONArray().put(olderEdit.toJson()))
        assertTrue(CentralDoc.merge(local, remote, now).user("c1")!!.isDeleted)
    }

    // ── what the account's list may carry ────────────────────────────────────────────────

    @Test fun a_hostile_or_broken_list_is_read_as_far_as_it_is_valid() {
        val good = CentralUser("c1", "ali", UUID.randomUUID().toString(), updatedAt = 5)
        val s = JSONObject().put("users", JSONArray()
            .put(good.toJson())
            .put(JSONObject().put("id", "c2").put("name", "no-id").put("gw", "not-a-uuid"))
            .put(JSONObject().put("id", "c3").put("name", "   ").put("gw", UUID.randomUUID().toString()))
            .put(JSONObject().put("name", "no-local-id").put("gw", UUID.randomUUID().toString()))
            .put("a string")
        ).put("ids", JSONArray()
            .put(JSONObject().put("id", good.gw).put("until", 0))
            .put(JSONObject().put("id", "nope").put("until", 0))
            .put(JSONObject().put("id", UUID.randomUUID().toString()).put("until", 10))
        )
        assertEquals(listOf("c1"), CentralDoc.parseUsers(s).map { it.id })
        assertEquals("an ended term and a broken id are left out", listOf(good.gw), CentralDoc.sharedLiveIds(s, 100))
        assertTrue(CentralDoc.parseUsers(null).isEmpty())
        assertTrue(CentralDoc.sharedLiveIds(JSONObject(), 0).isEmpty())
    }

    @Test fun this_phones_copy_survives_a_round_trip_exactly() {
        val u = CentralUser("c1", "ali", UUID.randomUUID().toString(), note = "n", tags = listOf("t1", "t2"), planId = "p",
            expiresAt = 99, createdAt = 1, updatedAt = 2, rotatedAt = 3, movedFrom = "inst|u1")
        val doc = CentralDoc("acc", "cf", listOf(u, u.copy(id = "c2", gw = UUID.randomUUID().toString()).asTombstone(7)),
            listOf(Plan("p", "سی‌روزه", durationDays = 30, isDefault = true, createdAt = 1, updatedAt = 2)),
            contact = "@me", script = "s-gw", host = "s-gw.x.workers.dev", appliedIds = listOf(u.gw), appliedAt = 4,
            sharedAt = 5, unshared = true, audit = listOf(CentralAudit(1, "user.create", "c1", "ali")))
        val back = CentralDoc.fromLocal(JSONObject(doc.toLocal().toString()))
        assertEquals(doc, back)
    }

    @Test fun the_domain_view_says_what_kind_of_stop_it_is() {
        val now = 1_000L
        val u = CentralUser("c1", "ali", UUID.randomUUID().toString(), expiresAt = 2_000, createdAt = 0, updatedAt = 1)
        assertEquals(UserStatus.ACTIVE, u.toStudioUser("x#gw", now).status)
        assertEquals(UserStatus.EXPIRED, u.toStudioUser("x#gw", 2_000).status)
        assertEquals(UserStatus.DISABLED, u.copy(enabled = false).toStudioUser("x#gw", now).status)
        assertEquals(UserStatus.DELETED, u.asTombstone(5).toStudioUser("x#gw", now).status)
        val view = u.toStudioUser("x#gw", now)
        assertEquals(2_000L, view.policy.expiresAt)
        assertNull("no volume on the central Worker", view.policy.quotaBytes)
        assertEquals("x#gw", view.installationId)
        assertEquals("1970-01-01 00:00:00", view.createdAt)
        assertNull(u.copy(expiresAt = 0).toStudioUser("x#gw", now).policy.expiresAt)
    }

    @Test fun one_default_plan_and_archiving_takes_the_default_away() {
        var doc = CentralDoc("acc")
        doc = CentralRules.putPlan(doc, Plan("a", "A", durationDays = 30, isDefault = true), 10)
        doc = CentralRules.putPlan(doc, Plan("b", "B", durationDays = 60, isDefault = true), 20)
        assertEquals(listOf("b"), doc.plans.filter { it.isDefault }.map { it.id })
        doc = CentralRules.archivePlan(doc, "b", 30)
        assertTrue(doc.plans.first { it.id == "b" }.archived)
        assertTrue(doc.plans.none { it.isDefault })
        expectRule(CentralRules.NOT_FOUND) { CentralRules.archivePlan(doc, "zzz", 40) }
    }

    @Test fun ids_on_the_worker_are_in_a_stable_order_oldest_first() {
        val now = 100L
        val a = CentralUser("c1", "a", UUID.randomUUID().toString(), createdAt = 5, updatedAt = 5)
        val b = CentralUser("c2", "b", UUID.randomUUID().toString(), createdAt = 3, updatedAt = 3)
        val c = CentralUser("c3", "c", UUID.randomUUID().toString(), createdAt = 4, updatedAt = 4, enabled = false)
        val d = CentralUser("c4", "d", UUID.randomUUID().toString(), createdAt = 1, updatedAt = 1, expiresAt = 50)
        val doc = CentralDoc("acc", users = listOf(a, b, c, d))
        assertEquals(listOf(b.gw, a.gw), doc.liveIds(now))
        assertEquals(doc.liveIds(now), CentralDoc("acc", users = listOf(d, c, a, b)).liveIds(now))
    }

    @Test fun room_is_what_the_worker_takes_less_its_own_id_and_the_panels() {
        assertEquals(15, Central.capacity(16, 0))
        assertEquals(7, Central.capacity(16, 8))
        assertEquals(119, Central.capacity(128, 8))
        assertEquals(0, Central.capacity(1, 3))
        assertTrue(Central.isCentral(Central.installationIdOf("abc")))
        assertFalse(Central.isCentral("abc"))
        assertEquals("abc", Central.accountIdOf(Central.installationIdOf("abc")))
        assertNotNull(Central.newUserId().takeIf { it.length == 16 })
    }
}
