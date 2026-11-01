package com.mlmvpn.scanner.data.studio.central

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * «محدودیت دستگاه» and «ساب چندلوکیشنی» on the central Worker: the rules for device places and
 * locations, the record's round trip, and the three shapes a subscription is written in.
 */
class CentralSubTest {

    private val now = 1_790_000_000_000L
    private fun gw() = UUID.randomUUID().toString()

    private fun expect(code: String, block: () -> Unit) {
        try { block() } catch (e: CentralRules.RuleError) { assertEquals(code, e.code); return }
        fail("expected $code")
    }

    private fun docWith(vararg names: String, room: Int = 10): Pair<CentralDoc, List<CentralUser>> {
        var doc = CentralDoc(accountId = "a", cloudAccountId = "cf")
        val made = mutableListOf<CentralUser>()
        for (n in names) {
            val (d, u) = CentralRules.create(doc, n, 0L, now, room, Central::newUserId, ::gw)
            doc = d; made += u
        }
        return doc to made
    }

    // ── device places ─────────────────────────────────────────────────────────────────────

    @Test fun a_device_limit_gives_each_place_its_own_id_and_takes_the_shared_one_off_the_worker() {
        val (doc, users) = docWith("ali")
        val ali = users[0]
        val (d2, a2) = CentralRules.setDevices(doc, ali.id, 3, Central.NOHWID_ALLOW, now, 10, ::gw)
        assertEquals(3, a2.slots.size)
        assertEquals(3, a2.slots.toSet().size)
        assertFalse(a2.gw in a2.slots)
        assertEquals(a2.slots, d2.liveIds(now))
        assertFalse("the shared id is not on the Worker under a limit", ali.gw in d2.liveIds(now))
        // A lower limit keeps the first places and their ids; none gives the shared id back.
        val (d3, a3) = CentralRules.setDevices(d2, ali.id, 2, Central.NOHWID_BLOCK, now, 10, ::gw)
        assertEquals(a2.slots.take(2), a3.slots)
        assertEquals(Central.NOHWID_BLOCK, a3.noHwid)
        val (d4, a4) = CentralRules.setDevices(d3, ali.id, 0, Central.NOHWID_ALLOW, now, 10, ::gw)
        assertTrue(a4.slots.isEmpty())
        assertEquals(listOf(ali.gw), d4.liveIds(now))
    }

    @Test fun device_places_need_room_on_the_worker_and_fewer_never_do() {
        val (doc, users) = docWith("a", "b", room = 4)
        val (d2, _) = CentralRules.setDevices(doc, users[0].id, 3, Central.NOHWID_ALLOW, now, 4, ::gw)
        assertEquals(4, d2.liveCount(now))
        expect(CentralRules.NO_ROOM) { CentralRules.setDevices(d2, users[1].id, 2, Central.NOHWID_ALLOW, now, 4, ::gw) }
        expect(CentralRules.NO_ROOM) { CentralRules.create(d2, "c", 0L, now, 4, Central::newUserId, ::gw) }
        // Someone switched off frees all their places; switching them back needs all of them again.
        val (d3, _) = CentralRules.setEnabled(d2, users[0].id, false, now, 4)
        assertEquals(1, d3.liveCount(now))
        val (d4, _) = CentralRules.create(d3, "c", 0L, now, 4, Central::newUserId, ::gw)
        expect(CentralRules.NO_ROOM) { CentralRules.setEnabled(d4, users[0].id, true, now, 4) }
        // Lowering a limit on a full Worker is always allowed.
        val (d5, _) = CentralRules.setEnabled(d3, users[0].id, true, now, 4)
        CentralRules.setDevices(d5, users[0].id, 1, Central.NOHWID_ALLOW, now, 4, ::gw)
    }

    @Test fun freeing_a_place_changes_that_places_id_alone() {
        val (doc, users) = docWith("ali")
        val (d2, a2) = CentralRules.setDevices(doc, users[0].id, 3, Central.NOHWID_ALLOW, now, 10, ::gw)
        val (d3, a3) = CentralRules.freeDevicePlace(d2, users[0].id, 1, now + 1, ::gw)
        assertEquals(a2.slots[0], a3.slots[0])
        assertNotEquals(a2.slots[1], a3.slots[1])
        assertEquals(a2.slots[2], a3.slots[2])
        assertFalse(a2.slots[1] in d3.liveIds(now))
        expect(CentralRules.NOT_FOUND) { CentralRules.freeDevicePlace(d3, users[0].id, 3, now, ::gw) }
    }

    @Test fun a_new_link_changes_every_id_and_the_subscription_address() {
        val (doc, users) = docWith("ali")
        val (d2, _) = CentralRules.setDevices(doc, users[0].id, 2, Central.NOHWID_ALLOW, now, 10, ::gw)
        val (d3, a3) = CentralRules.ensureToken(d2, users[0].id, now)
        val (_, a4) = CentralRules.rotate(d3, users[0].id, now + 1, ::gw)
        assertNotEquals(a3.gw, a4.gw)
        assertTrue(a4.slots.none { it in a3.slots })
        assertNotEquals(a3.token, a4.token)
        assertTrue(Central.TOKEN_RE.matches(a4.token!!))
        // Someone with no subscription is not given one by a new link.
        val (_, b) = CentralRules.rotate(doc, users[0].id, now, ::gw)
        assertNull(b.token)
    }

    @Test fun a_subscription_address_is_made_once_and_kept() {
        val (doc, users) = docWith("ali")
        val (d2, a2) = CentralRules.ensureToken(doc, users[0].id, now)
        assertTrue(Central.TOKEN_RE.matches(a2.token!!))
        val (_, a3) = CentralRules.ensureToken(d2, users[0].id, now + 5)
        assertEquals(a2.token, a3.token)
        assertEquals(24, Central.newToken().length)
    }

    // ── locations ─────────────────────────────────────────────────────────────────────────

    private fun px(ip: String, kind: String = ExitProxy.SOCKS5) = ExitProxy(ip, 1080, kind)

    @Test fun locations_are_checked_before_they_are_kept() {
        val (doc, users) = docWith("ali")
        val id = users[0].id
        val us = CentralLocation("US", listOf(px("1.2.3.4"), px("5.6.7.8", ExitProxy.HTTP)))
        val (_, a) = CentralRules.setLocations(doc, id, listOf(us, CentralLocation("DE", listOf(px("9.9.9.9")), fixed = true)), now)
        assertEquals(2, a.locations.size)
        expect(CentralRules.BAD_REQUEST) { CentralRules.setLocations(doc, id, listOf(us, us), now) }
        expect(CentralRules.BAD_REQUEST) { CentralRules.setLocations(doc, id, listOf(us.copy(fixed = true)), now) }
        expect(CentralRules.BAD_REQUEST) { CentralRules.setLocations(doc, id, listOf(CentralLocation("US", emptyList())), now) }
        expect(CentralRules.BAD_REQUEST) { CentralRules.setLocations(doc, id, listOf(CentralLocation("US", listOf(ExitProxy("1.2.3.4", 1080, "socks4")))), now) }
        expect(CentralRules.BAD_REQUEST) { CentralRules.setLocations(doc, id, listOf(CentralLocation("US", listOf(px("999.1.1.1")))), now) }
    }

    // ── the record ────────────────────────────────────────────────────────────────────────

    @Test fun every_new_field_survives_the_round_trip_and_a_broken_one_is_dropped() {
        val (doc, users) = docWith("ali")
        var (d, _) = CentralRules.setDevices(doc, users[0].id, 2, Central.NOHWID_BLOCK, now, 10, ::gw)
        d = CentralRules.ensureToken(d, users[0].id, now).first
        d = CentralRules.setLocations(d, users[0].id, listOf(CentralLocation("US", listOf(ExitProxy("1.2.3.4", 1080, "socks5", "US", 420, now)))), now).first
        d = CentralRules.freeDevicePlace(d, users[0].id, 0, now, ::gw, deviceKey = "h:0123456789abcdef01234567").first
        d = d.copy(sub = SubWorker("x-sb", "x-sb.sub.workers.dev", "0123456789abcdef0123456789abcdef", "c0de"),
            subPushed = mapOf(users[0].id to SubPushed(now, d.users[0].token)), subLinks = "abc")
        assertEquals(listOf("h:0123456789abcdef01234567"), d.users[0].banned)
        val back = CentralDoc.fromLocal(JSONObject(d.toLocal().toString()))!!
        assertEquals(d.users, back.users)
        assertEquals(d.sub, back.sub)
        assertEquals(d.subPushed, back.subPushed)
        assertEquals("abc", back.subLinks)
        // A key the sub Worker would never write is not a ban.
        assertTrue(CentralUser.fromJson(JSONObject(d.users[0].toJson().toString()).put("ban", JSONArray().put("whatever")))!!.banned.isEmpty())
        // Places that do not add up mean no limit, never a limit with someone else's ids.
        val bad = JSONObject(d.users[0].toJson().toString()).put("slots", JSONArray().put(gw()))
        val u = CentralUser.fromJson(bad)!!
        assertEquals(0, u.deviceLimit)
        assertTrue(u.slots.isEmpty())
        assertNull(CentralUser.fromJson(JSONObject(d.users[0].toJson().toString()).put("token", "short"))!!.token)
        // A shared record carries the sub Worker, and a second phone learns it from there.
        val other = CentralDoc(accountId = "b", cloudAccountId = "cf")
        assertEquals(d.sub, CentralDoc.merge(other, d.toShared(now), now).sub)
        // The places' ids are what another device must keep on the Worker.
        assertEquals(d.liveIds(now), CentralDoc.sharedLiveIds(d.toShared(now), now))
    }

    // ── the three shapes ──────────────────────────────────────────────────────────────────

    private val host = "core-a1b2c3-gw.sub.workers.dev"
    private fun link(addr: String, name: String, extra: String = "") =
        "vless://${CentralSubRender.DUMMY_ID}@$addr:443?encryption=none&security=tls&type=ws&host=$host&sni=$host&fp=chrome&alpn=http%2F1.1&path=%2Fp1%3Fed%3D2560$extra#$name"

    private val links = listOf(
        link(host, "ali-core-Domain"),
        link("104.21.8.12", "ali-core-IPv4-104.21.8.12"),
        link("[2606:4700:3030::6815:80c]", "ali-core-IPv6"),
        link("188.114.98.0", "ali-core-ECH", "&mlm_method=ech&ech=cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1"),
        link("188.114.98.0", "ali-core-FF", "&mlm_method=ff&cs=semi-python&fm=tlshello-0-len"),
    )

    @Test fun a_link_is_read_back_into_its_parts() {
        val l = CentralSubRender.parse(links[2])!!
        assertEquals("2606:4700:3030::6815:80c", l.address)
        assertEquals(443, l.port)
        assertEquals("/p1?ed=2560", l.path)
        assertEquals(listOf("http/1.1"), l.alpn)
        assertEquals("ali-core-IPv6", l.name)
        val ech = CentralSubRender.parse(links[3])!!
        assertEquals("cloudflare-ech.com+udp://1.1.1.1", ech.ech)
        assertFalse(CentralSubRender.parse(links[4])!!.portable)
        assertNull(CentralSubRender.parse("vmess://abc"))
    }

    @Test fun locations_go_through_the_worker_to_their_proxy_and_switch_between_several() {
        val us = CentralLocation("US", listOf(ExitProxy("1.2.3.4", 1080, "socks5"), ExitProxy("5.6.7.8", 8080, "http")))
        val de = CentralLocation("DE", listOf(ExitProxy("9.9.9.9", 1080, "socks5")), fixed = true)
        val t = CentralSubRender.render(links, listOf(us, de), { if (it == "US") "آمریکا" else "آلمان" })
        assertFalse(t.x.contains(CentralSubRender.DUMMY_ID) || t.c.contains(CentralSubRender.DUMMY_ID) || t.l.contains(CentralSubRender.DUMMY_ID))
        val x = JSONArray(t.x)
        // Locations first, then the four ways in other apps can read (not F&F).
        assertEquals(2 + 4, x.length())
        val first = x.getJSONObject(0)
        assertTrue(first.getString("remarks").contains("آمریکا"))
        for (k in listOf("inbounds", "outbounds", "routing")) assertTrue("v2rayNG reads a config by its $k", first.has(k))
        val outs = first.getJSONArray("outbounds")
        val px0 = outs.getJSONObject(0)
        assertEquals("socks", px0.getString("protocol"))
        assertEquals("cw", px0.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        assertEquals("http", outs.getJSONObject(1).getString("protocol"))
        val cw = outs.getJSONObject(2)
        assertEquals("cw", cw.getString("tag"))
        assertEquals(CentralSubRender.PLACEHOLDER, cw.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0).getString("id"))
        assertEquals(host, cw.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertEquals("loc", first.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0).getString("tag"))
        assertTrue(first.has("observatory"))
        // A fixed IP is one proxy, no balancer, and says so in its name.
        val second = x.getJSONObject(1)
        assertTrue(second.getString("remarks").contains("IP ثابت"))
        assertFalse(second.getJSONObject("routing").has("balancers"))
        // DNS over the Worker, other UDP refused, in both.
        val rules = second.getJSONObject("routing").getJSONArray("rules")
        assertEquals("cw", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("block", rules.getJSONObject(1).getString("outboundTag"))
        // The ECH way in keeps its ECH.
        val echCfg = (0 until x.length()).map { x.getJSONObject(it) }.first { it.getString("remarks") == "ali-core-ECH" }
        assertEquals("cloudflare-ech.com+udp://1.1.1.1", echCfg.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("echConfigList"))
    }

    @Test fun the_clash_profile_chains_each_proxy_through_the_worker() {
        val us = CentralLocation("US", listOf(ExitProxy("1.2.3.4", 1080, "socks5"), ExitProxy("5.6.7.8", 8080, "http")))
        val t = CentralSubRender.render(links, listOf(us), { "آمریکا" })
        assertTrue(t.c.contains("dialer-proxy: \"ali-core-Domain\""))
        assertTrue(t.c.contains("type: fallback"))
        assertTrue(t.c.contains("uuid: {{ID}}"))
        assertTrue(t.c.contains("type: http, server: \"5.6.7.8\""))
        assertFalse("no ECH or F&F way in for mihomo", t.c.contains("ECH") || t.c.contains("FF"))
        assertTrue(t.c.trimEnd().endsWith("MATCH,PROXY"))
    }

    @Test fun plain_links_carry_no_location_and_no_f_and_f() {
        val t = CentralSubRender.render(links, listOf(CentralLocation("US", listOf(ExitProxy("1.2.3.4", 1080, "socks5")))), { "US" })
        val lines = t.l.lines()
        assertEquals(4, lines.size)
        assertTrue(lines.all { it.startsWith("vless://{{ID}}@") })
        assertFalse(t.l.contains("mlm_method=ff"))
    }
}
