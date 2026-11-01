package com.mlmvpn.scanner.engines.tgproxy

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * `filesDir/tgproxy/state.json`: every proxy the app made, so a build interrupted by the app being
 * killed can be found again and finished or removed. Written whole to a `.tmp` and renamed, right
 * away (the record of a resource must exist before the resource does); a file that does not parse
 * is moved aside rather than crashing the screen.
 *
 * Holds only sealed secrets and public facts. No Cloudflare credential ever goes in here.
 */
class TgStore(dir: File) {

    private val root = File(dir, "tgproxy").apply { mkdirs() }
    private val file = File(root, "state.json")
    private val tmp = File(root, "state.json.tmp")
    private val corrupt = File(root, "state.corrupt.json")

    private val _state = MutableStateFlow(load())
    val state: StateFlow<List<TgService>> = _state
    val current: List<TgService> get() = _state.value

    fun get(id: String): TgService? = _state.value.firstOrNull { it.id == id }

    @Synchronized
    fun update(change: (List<TgService>) -> List<TgService>): List<TgService> {
        val before = _state.value
        val after = change(before)
        if (after != before) {
            _state.value = after
            write(after)
        }
        return after
    }

    /** Replaces one service by id; no-op when it is gone. */
    fun put(id: String, change: (TgService) -> TgService) {
        update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun load(): List<TgService> {
        if (!file.exists()) return emptyList()
        return try {
            TgCodec.decode(file.readText())
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "state unreadable (${e.javaClass.simpleName}); starting fresh") }
            runCatching { corrupt.delete(); file.renameTo(corrupt) }
            emptyList()
        }
    }

    private fun write(list: List<TgService>) {
        try {
            tmp.writeText(TgCodec.encode(list))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "state not written: ${e.javaClass.simpleName}") }
        }
    }

    companion object {
        private const val TAG = "TgStore"
    }
}

object TgCodec {

    fun encode(list: List<TgService>): String = JSONObject().apply {
        put("v", 1)
        put("services", JSONArray().apply { list.forEach { put(one(it)) } })
    }.toString()

    private fun one(s: TgService) = JSONObject().apply {
        put("id", s.id); put("m", s.method.id); put("acc", s.accountId); put("cf", s.cfAccountId)
        put("res", s.resource); put("host", s.host); put("sec", s.sealedSecret); put("st", s.state.name)
        s.reason?.let { put("why", it) }
        if (s.blockedUntil > 0) put("until", s.blockedUntil)
        put("c", s.createdAt); put("u", s.updatedAt)
        if (s.rev.isNotEmpty()) put("rev", s.rev)
        s.domain?.let { d ->
            put("dom", JSONObject().apply {
                put("host", d.host); put("zone", d.zoneId); d.domainId?.let { put("did", it) }; d.recordId?.let { put("rid", it) }; put("ok", d.ready)
            })
        }
        s.probe?.let { p ->
            put("probe", JSONObject().apply {
                p.viaApp?.let { put("app", it) }; p.direct?.let { put("direct", it) }; p.step?.let { put("step", it) }; put("at", p.at)
            })
        }
    }

    fun decode(text: String): List<TgService> {
        val arr = JSONObject(text).optJSONArray("services") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val j = arr.optJSONObject(i) ?: return@mapNotNull null
            val method = TgMethod.of(j.optString("m")) ?: return@mapNotNull null
            val id = j.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TgService(
                id = id,
                method = method,
                accountId = j.optString("acc"),
                cfAccountId = j.optString("cf"),
                resource = j.optString("res").ifBlank { TgService.resourceName(id) },
                host = j.optString("host"),
                domain = j.optJSONObject("dom")?.let { d ->
                    TgDomain(d.optString("host"), d.optString("zone"), d.optString("did").ifBlank { null }, d.optString("rid").ifBlank { null }, d.optBoolean("ok"))
                },
                sealedSecret = j.optString("sec"),
                state = runCatching { TgState.valueOf(j.optString("st")) }.getOrDefault(TgState.FAILED),
                reason = j.optString("why").ifBlank { null },
                blockedUntil = j.optLong("until"),
                createdAt = j.optLong("c"),
                updatedAt = j.optLong("u"),
                probe = j.optJSONObject("probe")?.let { p ->
                    TgProbeResult(
                        viaApp = if (p.has("app")) p.optBoolean("app") else null,
                        direct = if (p.has("direct")) p.optBoolean("direct") else null,
                        step = p.optString("step").ifBlank { null },
                        at = p.optLong("at"),
                    )
                },
                rev = j.optString("rev"),
            )
        }
    }
}
