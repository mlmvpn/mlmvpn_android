package com.mlmvpn.scanner.engines.gateway

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The phone and the desktop must hand out the SAME configs for the same central Worker, byte for byte:
 * same names, same addresses, same query-parameter order, same method links. The expected values are
 * what the Windows app's manager builds from fixed inputs (`scripts/gen-gateway-golden.js`), and the
 * Worker's code must hash exactly as the Windows app hashes it, so «the deployed code is this build's
 * code» reads the same on both.
 */
class GatewayParityTest {

    private val golden: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("gateway_golden.json")!!.bufferedReader().readText())
    }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test fun single_links_are_identical_to_the_windows_apps() {
        val arr = golden.getJSONArray("vless")
        assertTrue(arr.length() >= 3)
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val inp = c.getJSONObject("in")
            val got = GatewayLinks.vlessLink(
                inp.getString("uuid"), inp.getString("host"), inp.getString("wsPath"), inp.getString("name"), inp.getInt("port"),
                if (inp.isNull("address")) null else inp.getString("address"),
            )
            assertEquals(c.getString("expected"), got)
        }
    }

    private class Offline(val aliveScript: String) : GatewayCloud {
        override val by = "windows"
        override fun registryGet(): SharedRaw? = null
        override fun registryPut(script: String, url: String, s: JSONObject) {}
        override fun registryRemove(script: String) {}
        override fun scriptExists(script: String) = true
        override fun subdomain() = error("not used")
        override fun workerName() = error("not used")
        override fun upload(script: String, files: List<Pair<String, String>>, bindings: List<JSONObject>) = error("not used")
        override fun enableWorkersDev(script: String) {}
        override fun removeWorker(script: String) {}
        override fun randomUuid() = error("not used")
        override fun randomPath() = error("not used")
    }

    @Test fun the_engine_builds_exactly_the_set_the_windows_manager_builds_for_every_case() {
        GatewayEngine.resetForTest()
        val r = golden.getJSONObject("record")
        val dns = golden.getJSONObject("dns")
        val cases = golden.getJSONArray("cases")
        assertTrue(cases.length() >= 4)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            // defaults, then this case's overrides — the generator does the same
            val s = JSONObject(golden.getJSONObject("defaults").toString())
            val over = c.getJSONObject("settings")
            for (k in over.keys()) s.put(k, over.get(k))
            val panels = LinkedHashMap<String, PanelId>()
            val p = r.getJSONObject("panels")
            for (code in p.keys()) panels[code] = PanelId(p.getJSONObject(code).getString("uuid"), p.getJSONObject(code).getLong("at"))
            var record: GatewayRecord? = GatewayRecord(
                accountId = "golden", script = r.getString("script"), host = r.getString("host"), uuid = r.getString("uuid"),
                wsPath = r.getString("wsPath"), fallback = r.getString("fallback"), codeHash = "x", at = r.getLong("at"),
                panels = panels, settings = GatewaySettings.fromJson(s),
            )
            val store = object : GatewayStore {
                override fun get() = record
                override fun put(rec: GatewayRecord) { record = rec }
                override fun del() { record = null }
            }
            val engine = GatewayEngine("golden", Offline(r.getString("script")), store, { emptyList() },
                { DohAnswer(strings(dns.getJSONArray("A")), strings(dns.getJSONArray("AAAA"))) })
            val got = engine.configs(c.getString("panel")).configs
            assertEquals(c.getString("name"), strings(c.getJSONArray("expected")), got)
            GatewayEngine.resetForTest()
        }
    }

    @Test fun the_workers_modules_hash_exactly_as_the_windows_app_hashes_them() {
        val dir = File("src/main/assets/edge_gateway")
        assertTrue("the Worker's code is shipped in assets/edge_gateway", dir.isDirectory)
        val files = dir.listFiles { f -> f.name.endsWith(".js") }!!.map { it.name to it.readText() }
        assertTrue(files.any { it.first == Gateway.MAIN_MODULE })
        assertEquals(7, files.size)
        assertEquals(golden.getString("modulesHash"), Gateway.codeHash(files))
    }

    @Test fun the_shipped_modules_are_the_windows_repos_modules_when_it_is_beside_us() {
        val src = File("../../cloudflare-worker/edge-gateway")
        assumeTrue("a standalone Android checkout has no Windows repo beside it", src.isDirectory)
        val mine = File("src/main/assets/edge_gateway").listFiles { f -> f.name.endsWith(".js") }!!.associate { it.name to it.readText() }
        val theirs = src.listFiles { f -> f.name.endsWith(".js") }!!.associate { it.name to it.readText().replace("\r\n", "\n") }
        assertEquals("the same set of modules", theirs.keys, mine.keys)
        for ((name, text) in theirs) assertEquals("$name drifted from cloudflare-worker/edge-gateway (run the copy in scripts/sync-gateway-assets.js)", text, mine.getValue(name))
    }
}
