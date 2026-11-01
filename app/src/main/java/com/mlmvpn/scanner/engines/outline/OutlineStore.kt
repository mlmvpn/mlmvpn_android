package com.mlmvpn.scanner.engines.outline

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `filesDir/outline/state.json`, kept the way Amnezia keeps its own: written whole to a `.tmp` and
 * renamed, off the caller's thread and at most every couple of seconds; a file that does not parse
 * is moved aside rather than crashing the section.
 */
class OutlineStore(dir: File) {

    private val root = File(dir, "outline").apply { mkdirs() }
    private val file = File(root, "state.json")
    private val tmp = File(root, "state.json.tmp")
    private val corrupt = File(root, "state.corrupt.json")

    private val _state = MutableStateFlow(load())
    val state: StateFlow<OutlineState> = _state
    val current: OutlineState get() = _state.value

    @Synchronized
    fun update(change: (OutlineState) -> OutlineState): OutlineState {
        val cur = _state.value
        val next = change(cur)
        if (next !== cur) {
            _state.value = next
            scheduleWrite()
        }
        return next
    }

    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "outline-store").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)

    private fun scheduleWrite() {
        if (pending.compareAndSet(false, true)) {
            writer.schedule({ pending.set(false); write(_state.value) }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun load(): OutlineState {
        if (!file.exists()) return OutlineState()
        return try {
            OutlineCodec.decode(file.readText())
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "state unreadable (${e.javaClass.simpleName}); starting fresh") }
            runCatching { corrupt.delete(); file.renameTo(corrupt) }
            OutlineState()
        }
    }

    private val fileLock = Any()

    private fun write(s: OutlineState) {
        synchronized(fileLock) {
            try {
                tmp.writeText(OutlineCodec.encode(s))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
                Unit
            } catch (e: Exception) {
                runCatching { Log.w(TAG, "state not written: ${e.javaClass.simpleName}") }
            }
        }
    }

    companion object {
        private const val TAG = "OutlineStore"
        private const val WRITE_DELAY_MS = 2_000L
    }
}

object OutlineCodec {

    fun encode(s: OutlineState): String = JSONObject().apply {
        put("v", 1)
        put("servers", JSONArray().apply {
            s.servers.forEach { sv ->
                put(JSONObject().apply {
                    put("id", sv.id); put("uri", sv.uri); put("name", sv.name); put("host", sv.host); put("port", sv.port)
                    put("method", sv.method); sv.country?.let { put("cc", it) }; put("src", sv.sourceId); put("at", sv.addedAt)
                    if (sv.prefix) put("prefix", true)
                    sv.ssconf?.let { put("ssconf", it) }
                    if (sv.seenAt > 0) put("seen", sv.seenAt)
                    if (sv.checkedAt > 0) put("chk", sv.checkedAt)
                    sv.uptime?.let { put("up", it) }
                })
            }
        })
        put("stats", JSONObject().apply {
            s.stats.forEach { (net, m) ->
                put(net, JSONObject().apply {
                    m.forEach { (id, st) ->
                        put(id, JSONArray().apply { put(st.lastMs); put(st.lastTestAt); put(st.fails); put(st.speedBps ?: -1L); put(if (st.viaCf) 1 else 0) })
                    }
                })
            }
        })
        put("tomb", JSONObject().apply { s.tombstones.forEach { (k, v) -> put(k, v) } })
        put("fav", JSONArray(s.favorites.toList()))
        put("refreshAt", s.lastRefreshAt)
        s.lastConnectedId?.let { put("last", it) }
        put("scout", JSONObject().apply { s.scoutWinners.forEach { (k, v) -> put(k, v) } })
        put("prefs", JSONObject().apply {
            put("fallback", s.prefs.autoFallback)
            put("off", JSONArray(s.prefs.disabledSources.toList()))
            put("warned", s.prefs.warned)
        })
    }.toString()

    fun decode(text: String): OutlineState {
        val o = JSONObject(text)
        val servers = o.optJSONArray("servers")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val j = a.optJSONObject(i) ?: return@mapNotNull null
                val uri = j.optString("uri")
                if (uri.isBlank()) return@mapNotNull null
                OutlineServer(
                    id = j.optString("id"), uri = uri, name = j.optString("name"), host = j.optString("host"), port = j.optInt("port"),
                    method = j.optString("method"), country = j.optString("cc").ifBlank { null }, sourceId = j.optString("src"),
                    addedAt = j.optLong("at"), prefix = j.optBoolean("prefix"), ssconf = j.optString("ssconf").ifBlank { null },
                    seenAt = j.optLong("seen"), checkedAt = j.optLong("chk"),
                    uptime = if (j.has("up")) j.optInt("up") else null,
                )
            }
        }.orEmpty()
        val stats = HashMap<String, Map<String, OutlineStat>>()
        o.optJSONObject("stats")?.let { so ->
            so.keys().forEach { net ->
                val m = HashMap<String, OutlineStat>()
                so.optJSONObject(net)?.let { no ->
                    no.keys().forEach { id ->
                        val a = no.optJSONArray(id) ?: return@forEach
                        m[id] = OutlineStat(a.optLong(0, -1), a.optLong(1), a.optInt(2), a.optLong(3, -1).takeIf { it >= 0 }, a.optInt(4) == 1)
                    }
                }
                stats[net] = m
            }
        }
        val tomb = HashMap<String, Long>()
        o.optJSONObject("tomb")?.let { t -> t.keys().forEach { tomb[it] = t.optLong(it) } }
        val fav = o.optJSONArray("fav")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        val scout = HashMap<String, String>()
        o.optJSONObject("scout")?.let { t -> t.keys().forEach { scout[it] = t.optString(it) } }
        val p = o.optJSONObject("prefs")
        val prefs = OutlinePrefs(
            autoFallback = p?.optBoolean("fallback", true) ?: true,
            disabledSources = p?.optJSONArray("off")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty(),
            warned = p?.optBoolean("warned") ?: false,
        )
        return OutlineState(servers, stats, tomb, fav, o.optLong("refreshAt"), o.optString("last").ifBlank { null }, scout, prefs)
    }
}
