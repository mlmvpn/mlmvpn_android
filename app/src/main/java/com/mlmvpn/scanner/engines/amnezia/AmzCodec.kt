package com.mlmvpn.scanner.engines.amnezia

import org.json.JSONArray
import org.json.JSONObject

/** [AmzState] to and from JSON. Short keys: the pool is a few thousand servers. */
object AmzCodec {

    private const val VERSION = 1

    fun encode(s: AmzState): String {
        val o = JSONObject().put("v", VERSION)
        o.put("servers", JSONArray().also { a ->
            s.servers.forEach { sv ->
                a.put(JSONObject()
                    .put("i", sv.id).put("k", sv.kind.code).put("r", sv.raw).put("n", sv.name)
                    .put("h", sv.host).put("p", sv.port)
                    .put("c", sv.country ?: "").put("cf", sv.countryFrom.code)
                    .put("s", sv.sourceId).put("a", sv.addedAt)
                    .put("w", sv.warp).put("x", sv.needsAwgCore))
            }
        })
        o.put("stats", JSONObject().also { nets ->
            s.stats.forEach { (net, m) ->
                nets.put(net, JSONObject().also { mo ->
                    m.forEach { (id, st) ->
                        mo.put(id, JSONArray().put(st.ok).put(st.fail).put(st.streak).put(st.ewmaMs).put(st.lastMs).put(st.lastOkAt).put(st.lastTestAt))
                    }
                })
            }
        })
        o.put("seen", JSONObject(s.netSeen as Map<*, *>))
        o.put("src", JSONObject().also { so -> s.sources.forEach { (id, st) -> so.put(id, JSONArray().put(st.tested).put(st.alive)) } })
        o.put("tomb", JSONObject(s.tombstones as Map<*, *>))
        o.put("prefs", JSONObject().put("obf", s.prefs.obfuscate).put("dead", s.prefs.autoRemoveDead).put("fb", s.prefs.autoFallback))
        o.put("refresh", s.lastRefreshAt)
        o.put("last", s.lastConnectedId ?: "")
        return o.toString()
    }

    fun decode(text: String): AmzState {
        val o = JSONObject(text)
        val servers = ArrayList<AmzServer>()
        o.optJSONArray("servers")?.let { a ->
            for (n in 0 until a.length()) {
                val x = a.optJSONObject(n) ?: continue
                servers += AmzServer(
                    id = x.getString("i"), kind = AmzKind.of(x.optString("k")), raw = x.getString("r"),
                    name = x.optString("n"), host = x.optString("h"), port = x.optInt("p"),
                    country = x.optString("c").ifEmpty { null }, countryFrom = CountryFrom.of(x.optString("cf")),
                    sourceId = x.optString("s"), addedAt = x.optLong("a"),
                    warp = x.optBoolean("w"), needsAwgCore = x.optBoolean("x"),
                )
            }
        }
        val stats = HashMap<String, Map<String, AmzStat>>()
        o.optJSONObject("stats")?.let { nets ->
            nets.keys().forEach { net ->
                val mo = nets.optJSONObject(net) ?: return@forEach
                val m = HashMap<String, AmzStat>()
                mo.keys().forEach { id ->
                    val a = mo.optJSONArray(id) ?: return@forEach
                    m[id] = AmzStat(a.optInt(0), a.optInt(1), a.optInt(2), a.optDouble(3, 0.0), a.optLong(4, -1), a.optLong(5), a.optLong(6))
                }
                stats[net] = m
            }
        }
        val seen = HashMap<String, Long>()
        o.optJSONObject("seen")?.let { j -> j.keys().forEach { seen[it] = j.optLong(it) } }
        val src = HashMap<String, AmzSourceStat>()
        o.optJSONObject("src")?.let { j -> j.keys().forEach { k -> j.optJSONArray(k)?.let { a -> src[k] = AmzSourceStat(a.optInt(0), a.optInt(1)) } } }
        val tomb = HashMap<String, Long>()
        o.optJSONObject("tomb")?.let { j -> j.keys().forEach { tomb[it] = j.optLong(it) } }
        val p = o.optJSONObject("prefs")
        return AmzState(
            servers = servers, stats = stats, netSeen = seen, sources = src, tombstones = tomb,
            prefs = AmzPrefs(
                obfuscate = p?.optBoolean("obf", true) ?: true,
                autoRemoveDead = p?.optBoolean("dead", true) ?: true,
                autoFallback = p?.optBoolean("fb", true) ?: true,
            ),
            lastRefreshAt = o.optLong("refresh"),
            lastConnectedId = o.optString("last").ifEmpty { null },
        )
    }
}
