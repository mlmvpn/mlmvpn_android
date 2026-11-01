package com.mlmvpn.scanner.store

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class IranProfilesBuilderTest {

    private fun asset(): String {
        val f = listOf("app/src/main/assets/iran_profiles.json", "src/main/assets/iran_profiles.json")
            .map(::File).first { it.exists() }
        return f.readText()
    }

    private fun stock(list: JSONArray, remarks: String): String =
        (0 until list.length()).map { list.getJSONObject(it) }
            .first { it.optString("method") == "stock" && JSONObject(it.getString("config")).optString("remarks") == remarks }
            .getString("config")

    /** The nineteen rows the desktop generator made from v50 (ids iran-1…iran-19). */
    private fun original(list: JSONArray): List<JSONObject> =
        (0 until list.length()).map { list.getJSONObject(it) }.filter { it.getString("id").matches(Regex("""iran-\d+""")) }


    @Test fun commentsGoUrlsStay() {
        val text = """
            {
              // a comment with "quotes"
              "a": "https://cloudflare-dns.com/dns-query", /* block
                 comment */ "b": [1, 2,],
              "c": "it's // not a comment",
            }
        """.trimIndent()
        val o = JSONObject(IranProfilesBuilder.stripJsonComments(text))
        assertEquals("https://cloudflare-dns.com/dns-query", o.getString("a"))
        assertEquals(2, o.getJSONArray("b").length())
        assertEquals("it's // not a comment", o.getString("c"))
    }

    /** The phone's builder, fed the files the shipped rows came from, gives back those rows. */
    @Test fun rebuildsTheShippedListExactly() {
        val shipped = JSONArray(asset())
        val rows = original(shipped)
        val (low, high) = IranProfilesBuilder.v48From(asset())
        val built = IranProfilesBuilder.build(
            stock(shipped, "Serverless-v50-fragA"), stock(shipped, "Serverless-v50-fragB"), low, high)
        assertEquals(19, rows.size)
        assertEquals(19, built.size)
        built.forEachIndexed { i, p ->
            val s = rows[i]
            assertEquals(s.getString("id"), p.id)
            assertEquals(s.getString("name"), p.name)
            assertEquals(s.getString("method"), p.method)
            assertEquals(s.getString("dns"), p.dns)
            assertTrue("${p.id} config differs", JSONObject(s.getString("config")).similar(JSONObject(p.config)))
        }
    }

    /** An update adds the new version's configs on top and leaves every old row exactly as it was. */
    @Test fun mergeAddsNewAndKeepsOld() {
        val shipped = JSONArray(asset())
        val old = JSONArray().apply { original(shipped).forEach { put(it) } }
        val v52a = stock(shipped, "Serverless-v52-fragA")
        val v52b = stock(shipped, "Serverless-v52-fragB")
        val merged = JSONArray(IranProfilesBuilder.merge(old.toString(), listOf(v52a, v52b)))
        assertEquals(old.length() + 2, merged.length())
        assertEquals("iran-v52-fragA", merged.getJSONObject(0).getString("id"))
        assertEquals("iran-v52-fragB", merged.getJSONObject(1).getString("id"))
        for (i in 0 until old.length()) {
            assertEquals(old.getJSONObject(i).getString("id"), merged.getJSONObject(i + 2).getString("id"))
            assertEquals(old.getJSONObject(i).getString("config"), merged.getJSONObject(i + 2).getString("config"))
        }
        // The same files again: nothing new, nothing installed.
        try { IranProfilesBuilder.merge(merged.toString(), listOf(v52a, v52b)); fail() } catch (_: IranProfilesBuilder.BuildError) {}
    }


    /** Upstream's newest files, when a copy is at hand (-Diran.upstream.dir=…). */
    @Test fun buildsUpstreamWhenAvailable() {
        val dir = System.getProperty("iran.upstream.dir")?.let(::File) ?: return
        val (low, high) = IranProfilesBuilder.v48From(asset())
        val built = IranProfilesBuilder.build(
            File(dir, "Serverless-fragA.jsonc").readText(), File(dir, "Serverless-fragB.jsonc").readText(), low, high)
        assertEquals(19, built.size)
        val v = IranProfilesBuilder.VERSION.find(File(dir, "Serverless-fragA.jsonc").readText())!!.groupValues[1]
        assertTrue(built[0].name.startsWith("v$v fragA"))
        assertTrue(built[1].name.startsWith("v$v fragB"))
        println("upstream v$v: " + built.joinToString(" | ") { it.name })
    }

    @Test fun aServerIsRefused() {
        val cfg = JSONObject().put("outbounds", JSONArray()
            .put(JSONObject().put("protocol", "direct"))
            .put(JSONObject().put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray()))))
        try { IranProfilesBuilder.assertServerless(cfg, "x"); fail() } catch (_: IranProfilesBuilder.BuildError) {}
        val hidden = JSONObject().put("outbounds", JSONArray()
            .put(JSONObject().put("protocol", "freedom").put("settings", JSONObject().put("address", "203.0.113.9"))))
        try { IranProfilesBuilder.assertServerless(hidden, "x"); fail() } catch (_: IranProfilesBuilder.BuildError) {}
        IranProfilesBuilder.assertServerless(JSONObject().put("outbounds", JSONArray()
            .put(JSONObject().put("protocol", "block")).put(JSONObject().put("protocol", "dns"))), "ok")
    }

    @Test fun aMovedShapeThrowsInsteadOfHalfEditing() {
        val (low, _) = IranProfilesBuilder.v48From(asset())
        val cfg = JSONObject(low)
        val servers = cfg.getJSONObject("dns").getJSONArray("servers")
        servers.put(JSONObject().put("tag", "no-filter-dns").put("address", "https://example.invalid/dns-query"))
        try { IranProfilesBuilder.withReachableResolver(cfg.toString(), "https://8.8.8.8/dns-query"); fail() }
        catch (_: IranProfilesBuilder.BuildError) {}
        try { IranProfilesBuilder.normalizeSource("{\"outbounds\": []}"); fail() } catch (_: IranProfilesBuilder.BuildError) {}
    }

    @Test fun cleanRowsLeaveNoFragmentRouteForTheWholeInternet() {
        val (low, _) = IranProfilesBuilder.v48From(asset())
        val clean = JSONObject(IranProfilesBuilder.withoutFragmentation(low, "https://8.8.8.8/dns-query"))
        val rules = clean.getJSONObject("routing").getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val ip = r.optJSONArray("ip") ?: continue
            if ((0 until ip.length()).any { ip.getString(it) == "0.0.0.0/0" }) {
                assertFalse(r.optString("outboundTag").startsWith("tcp-fragment"))
            }
        }
        assertTrue(clean.getString("remarks").endsWith("-cleanDNS"))
    }
}
