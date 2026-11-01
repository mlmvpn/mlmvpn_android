package com.mlmvpn.scanner.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences values as JSON that keeps their type: `{"s":"…"}`, `{"b":true}`, `{"i":1}`,
 * `{"l":1}`, `{"f":1.5}`, `{"ss":["…"]}`. A plain JSON number cannot say whether it was an Int or a
 * Long, and reading one back as the wrong type throws a ClassCastException in the screen that asks
 * for it -- so the type travels with the value.
 */
internal object PrefCodec {

    fun encode(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put("s", value)
        is Boolean -> JSONObject().put("b", value)
        is Int -> JSONObject().put("i", value)
        is Long -> JSONObject().put("l", value)
        is Float -> if (value.isFinite()) JSONObject().put("f", value.toDouble()) else null
        is Set<*> -> JSONObject().put("ss", JSONArray(value.filterIsInstance<String>().sorted()))
        else -> null
    }

    /** Null for anything that is not one of the six shapes; a newer app's seventh is skipped, not fatal. */
    fun decode(o: JSONObject?): Any? {
        if (o == null || o.length() != 1) return null
        return runCatching {
            when {
                o.has("s") -> o.getString("s")
                o.has("b") -> o.getBoolean("b")
                o.has("i") -> o.getInt("i")
                o.has("l") -> o.getLong("l")
                o.has("f") -> o.getDouble("f").toFloat().takeIf { it.isFinite() }
                o.has("ss") -> o.getJSONArray("ss").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> null
            }
        }.getOrNull()
    }

    /** A whole map, in key order so two exports of the same settings are the same bytes. */
    fun encodeMap(values: Map<String, Any?>): JSONObject {
        val out = JSONObject()
        for (k in values.keys.sorted()) encode(values[k])?.let { out.put(k, it) }
        return out
    }

    fun decodeMap(o: JSONObject?): Map<String, Any> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for (k in o.keys()) decode(o.optJSONObject(k))?.let { out[k] = it }
        return out
    }
}
