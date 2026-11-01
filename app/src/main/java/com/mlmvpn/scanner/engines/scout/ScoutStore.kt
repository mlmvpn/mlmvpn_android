package com.mlmvpn.scanner.engines.scout

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Scout's history: one small file per run, written atomically after every result, so a closed or
 * killed app comes back with everything measured up to that moment (and the method that was under
 * test marked «نامشخص»).
 *
 * Kept per network: at most [PER_NET] runs for each of at most [MAX_NETS] networks, none older than
 * [MAX_AGE_MS]. Nothing in a file identifies the user: the network is a hash, configs are absent.
 */
object ScoutStore {

    const val PER_NET = 5
    const val MAX_NETS = 20
    const val MAX_AGE_MS = 30L * 24 * 3600_000
    /** Past this a result is shown as «قدیمی — دوباره بررسی کن». */
    const val FRESH_MS = 24L * 3600_000

    private val _runs = MutableStateFlow<List<ScoutRun>>(emptyList())
    /** Newest first. */
    val runs: StateFlow<List<ScoutRun>> = _runs.asStateFlow()

    @Volatile private var loaded = false
    private val lock = Any()

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "scout/runs").apply { mkdirs() }

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val list = dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
                runCatching { ScoutRun.from(JSONObject(f.readText())) }.getOrNull()
            }
            _runs.value = list.sortedByDescending { it.startedAt }
            loaded = true
        }
    }

    /** Writes [run] (replacing its earlier copy) and drops what [trim] no longer keeps. */
    fun put(ctx: Context, run: ScoutRun) {
        load(ctx)
        synchronized(lock) {
            val next = trim(listOf(run) + _runs.value.filter { it.id != run.id }, System.currentTimeMillis())
            val dropped = _runs.value.map { it.id }.toSet() - next.map { it.id }.toSet()
            _runs.value = next
            val d = dir(ctx)
            runCatching {
                val f = File(d, "${safe(run.id)}.json")
                val tmp = File(d, "${safe(run.id)}.tmp")
                tmp.writeText(run.toJson().toString())
                if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            }
            dropped.forEach { runCatching { File(d, "${safe(it)}.json").delete() } }
        }
    }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "run" }

    /**
     * Newest first, [PER_NET] per network, [MAX_NETS] networks (the most recently used), nothing past
     * [MAX_AGE_MS]. A run still in progress is never dropped.
     */
    fun trim(list: List<ScoutRun>, now: Long): List<ScoutRun> {
        val sorted = list.sortedByDescending { it.startedAt }
        val live = sorted.filter { it.finishedAt == null }.map { it.id }.toSet()
        val fresh = sorted.filter { it.id in live || now - it.startedAt in 0..MAX_AGE_MS }
        val nets = fresh.map { it.netKey }.distinct().take(MAX_NETS).toSet()
        val perNet = HashMap<String, Int>()
        return fresh.filter { r ->
            if (r.id in live) return@filter true
            if (r.netKey !in nets) return@filter false
            val n = perNet.getOrDefault(r.netKey, 0)
            if (n >= PER_NET) false else { perNet[r.netKey] = n + 1; true }
        }
    }

    fun latest(): ScoutRun? = _runs.value.firstOrNull()

    fun latestFor(netKey: String): ScoutRun? = _runs.value.firstOrNull { it.netKey == netKey }

    /**
     * For each method, how often it worked in earlier finished runs on [netKey] (worked, tested) --
     * «در ۳ بررسی اخیر همین شبکه، ۳ بار کار کرد», and the score's small "trust" part.
     */
    fun history(netKey: String, excludeRunId: String? = null): Map<String, Pair<Int, Int>> =
        historyOf(_runs.value.filter { it.netKey == netKey && it.id != excludeRunId && it.finishedAt != null }.take(PER_NET))

    fun historyOf(runs: List<ScoutRun>): Map<String, Pair<Int, Int>> {
        val out = HashMap<String, Pair<Int, Int>>()
        for (r in runs) for (m in r.methods.values) {
            if (!(m.status.healthy || m.status == ScoutStatus.FAILED)) continue
            val (ok, n) = out[m.id] ?: (0 to 0)
            out[m.id] = (ok + if (m.status == ScoutStatus.WORKS) 1 else 0) to (n + 1)
        }
        return out
    }

    /** The variant of [methodId] that worked most recently on [netKey]: tried first next time. */
    fun lastGoodVariant(netKey: String, methodId: String): String? =
        _runs.value.asSequence().filter { it.netKey == netKey }
            .mapNotNull { r -> r.methods[methodId]?.variants?.filter { it.status == ScoutStatus.WORKS }?.maxByOrNull { it.at }?.id }
            .firstOrNull()
}
