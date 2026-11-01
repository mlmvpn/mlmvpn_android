package com.mlmvpn.core.geph

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One Geph exit server, as the network lists it (net_status). */
data class GephExit(
    val key: String,
    val country: String,
    val city: String,
    /** 0..1, how busy the server is. */
    val load: Double,
    /** `core` or `streaming`; `auto` only ever picks core. */
    val category: String,
    /** Account levels allowed on it: "Free", "Plus". */
    val levels: Set<String>,
) {
    val streaming: Boolean get() = category == "streaming"
    fun allows(level: GephAccount.Level): Boolean =
        levels.isEmpty() || if (level == GephAccount.Level.FREE) "Free" in levels else "Plus" in levels
}

object GephExits {

    data class City(val name: String, val exits: List<GephExit>) {
        val lightestLoad: Double get() = exits.minOfOrNull { it.load } ?: 1.0
        val streaming: Boolean get() = exits.any { it.streaming }
    }

    data class Country(val code: String, val cities: List<City>) {
        val exits: List<GephExit> get() = cities.flatMap { it.exits }
        fun usable(level: GephAccount.Level): Int = exits.count { it.allows(level) }
    }

    /** net_status: `{exits: {key: [pubkey, ExitDescriptor, ExitMetadata]}}`. */
    fun parse(netStatus: JSONObject): List<GephExit> {
        val exits = netStatus.optJSONObject("exits") ?: return emptyList()
        val out = ArrayList<GephExit>()
        exits.keys().forEach { key ->
            val tuple = exits.optJSONArray(key) ?: return@forEach
            val desc = tuple.optJSONObject(1) ?: return@forEach
            val meta = tuple.optJSONObject(2)
            val levels = meta?.optJSONArray("allowed_levels")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.toSet()
            }.orEmpty()
            out += GephExit(
                key = key,
                country = desc.optString("country").uppercase(),
                city = desc.optString("city"),
                load = desc.optDouble("load", 0.0),
                category = meta?.optString("category", "core") ?: "core",
                levels = levels,
            )
        }
        return out.filter { it.country.length == 2 }
    }

    /** Grouped by country and city; countries with something this account can use first. */
    fun countries(exits: List<GephExit>, level: GephAccount.Level): List<Country> =
        exits.groupBy { it.country }
            .map { (code, list) ->
                Country(code, list.groupBy { it.city }.map { (city, e) -> City(city, e) }.sortedBy { it.lightestLoad })
            }
            .sortedWith(compareBy<Country> { if (it.usable(level) > 0) 0 else 1 }.thenBy { it.code })

    private fun cacheFile(ctx: Context) = File(GephEngine.dir(ctx), "exits.json")

    /** The last list the network gave, so the picker has something to show at once. */
    fun cached(ctx: Context): List<GephExit> = runCatching {
        parse(JSONObject(cacheFile(ctx).readText()))
    }.getOrDefault(emptyList())

    fun refresh(ctx: Context): Result<List<GephExit>> = runCatching {
        val status = GephEngine.withControl(ctx) { it.netStatus() } ?: throw IllegalStateException("no answer")
        val list = parse(status)
        if (list.isNotEmpty()) cacheFile(ctx).writeText(status.toString())
        list
    }

    // ── the fastest server ──────────────────────────────────────────────────────────────────────

    data class Probe(
        val exit: GephExitChoice,
        val ok: Boolean,
        val connectMs: Long = 0,
        val ttfbMs: Long = 0,
        val mbit: Double = 0.0,
        val error: String? = null,
    )

    /**
     * How long one country gets to make a session. The real connection gets two minutes
     * (TunnelVpnService), and on a phone on an Iranian line a fresh engine routinely needs more than
     * the half minute this used to allow -- so every country "did not answer" while connecting to
     * the same countries worked. A country that does connect still ends its probe at once.
     */
    private const val SESSION_BUDGET_MS = 90_000L

    /** Why a probe failed, for the screen to say in words. */
    const val NO_SESSION = "GEPH_PROBE_NO_SESSION"
    const val NO_DATA = "GEPH_PROBE_NO_DATA"
    const val ENGINE_EXITED = "GEPH_PROBE_ENGINE_EXITED"

    /**
     * Measures each candidate for real, one at a time: connect, time to first byte, and half a
     * megabyte from Google's CDN. Exit choice is Geph's biggest speed lever -- measured on the
     * desktop, the best free exit was 3.5 times faster than the one `auto` picked.
     *
     * One at a time, on a COPY of the account's cache, and never while connected. The copy is
     * the point: it already holds today's connect token, and the network allows twenty of those
     * per account per day (rpc_impl.rs) -- a probe per country, each with a cache of its own,
     * would spend them and could leave the real connection without one for the rest of the day.
     */
    fun findFastest(
        ctx: Context,
        candidates: List<GephExitChoice>,
        onProgress: (index: Int, total: Int, probing: GephExitChoice, done: Probe?) -> Unit,
        cancelled: () -> Boolean,
    ): List<Probe> {
        val app = ctx.applicationContext
        if (GephEngine.isMainAlive) throw IllegalStateException(BUSY)
        val cred = GephAccount.credential(app) ?: throw IllegalStateException(GephEngine.NO_ACCOUNT)
        val dir = File(GephEngine.dir(app), "probe").apply { deleteRecursively(); mkdirs() }
        val db = File(dir, "db")
        val main = GephEngine.cacheFile(app, cred)
        listOf("", "-wal", "-shm").forEach { suffix ->
            val src = File(main.path + suffix)
            if (src.exists()) src.copyTo(File(db.path + suffix), overwrite = true)
        }

        val hadCache = main.exists()
        val results = ArrayList<Probe>()
        candidates.forEachIndexed { i, exit ->
            if (cancelled()) return results
            onProgress(i, candidates.size, exit, null)
            val probe = probeOne(app, cred, exit, db, dir)
            results += probe
            onProgress(i, candidates.size, exit, probe)
        }
        // An account that never connected has just paid for its first token, auth and route in
        // the probe's cache. Keeping that cache makes the first real connect a warm one instead of
        // paying for all of it a second time.
        if (!hadCache && db.exists()) {
            listOf("", "-wal", "-shm").forEach { suffix ->
                val src = File(db.path + suffix)
                if (src.exists()) runCatching { src.copyTo(File(main.path + suffix), overwrite = true) }
            }
        }
        dir.deleteRecursively()
        val ranked = results.sortedWith(compareByDescending<Probe> { it.ok }.thenByDescending { it.mbit }.thenBy { it.ttfbMs })
        // The whole run is kept -- every country, with its numbers or why it failed -- so it is
        // still on the screen after the user connects and comes back. The best one is also kept at
        // the top level, where the Geph screen's "last fastest" row reads it.
        if (results.isNotEmpty()) {
            val best = ranked.firstOrNull { it.ok }
            GephSettings.setLastFastest(app, JSONObject()
                .put("at", System.currentTimeMillis())
                .apply {
                    if (best != null) {
                        put("country", best.exit.country)
                        put("city", best.exit.city ?: JSONObject.NULL)
                        put("mbit", best.mbit)
                        put("ttfb", best.ttfbMs)
                    }
                }
                .put("ranked", JSONArray().apply {
                    ranked.forEach { p ->
                        put(JSONObject()
                            .put("country", p.exit.country).put("city", p.exit.city ?: JSONObject.NULL)
                            .put("ok", p.ok).put("mbit", p.mbit).put("ttfb", p.ttfbMs)
                            .put("connect", p.connectMs).put("error", p.error ?: JSONObject.NULL))
                    }
                }))
        }
        return ranked
    }

    /**
     * One country, measured exactly the way the real connection is checked: the engine's own
     * port forwards to Google, with TLS from here (GephEngine.httpsViaForward) -- not a SOCKS proxy,
     * a different path the real connection never takes. "Answered" is a request that crossed; the
     * speed is measured after that and only ranks the countries, so a slow or failed download no
     * longer turns a working country into "did not answer".
     */
    private fun probeOne(ctx: Context, cred: GephCredential, exit: GephExitChoice, db: File, dir: File): Probe {
        val control = File(dir, "probe.sock").apply { delete() }.absolutePath
        val verifyPort = GephEngine.freePort()
        val speedPort = GephEngine.freePort()
        val run = GephRun(
            credential = cred,
            exit = exit,
            dryRun = false,
            controlSocket = control,
            cacheFile = db.absolutePath,
            allowDirect = GephSettings.allowDirect(ctx),
            forwards = listOf(
                GephForward("127.0.0.1:$verifyPort", "${GephEngine.VERIFY_HOST}:443"),
                GephForward("127.0.0.1:$speedPort", "${GephEngine.SPEED_HOST}:443"),
            ),
        )
        val cfg = File(dir, "config.json").apply { writeText(GephConfig.build(run).toString()) }
        val started = SystemClock.elapsedRealtime()
        val proc = GephProcess.start(ctx, "probe", cfg, dir, null, onLine = {}, onExit = {})
        val c = GephControl(control)
        try {
            var connected = false
            while (SystemClock.elapsedRealtime() - started < SESSION_BUDGET_MS && proc.isAlive) {
                if (runCatching { c.connInfo(3_000).connected }.getOrDefault(false)) {
                    connected = true
                    break
                }
                Thread.sleep(400)
            }
            if (!connected) return Probe(exit, ok = false, error = if (proc.isAlive) NO_SESSION else ENGINE_EXITED)
            val connectMs = SystemClock.elapsedRealtime() - started

            // A request across, twice at most: the free tier's first request can be slow.
            var ttfb = -1L
            repeat(2) {
                if (ttfb >= 0) return@repeat
                val t0 = SystemClock.elapsedRealtime()
                val ok = runCatching {
                    GephEngine.httpsViaForward(verifyPort, GephEngine.VERIFY_HOST, "/generate_204", 20_000, maxBody = 0).first in 200..399
                }.getOrDefault(false)
                if (ok) ttfb = SystemClock.elapsedRealtime() - t0
            }
            if (ttfb < 0) return Probe(exit, ok = false, connectMs = connectMs, error = NO_DATA)

            // Half a megabyte from Google's download CDN, for the ranking.
            val size = 524_288
            val t1 = SystemClock.elapsedRealtime()
            val got = runCatching {
                val (status, _) = GephEngine.httpsViaForward(
                    speedPort, GephEngine.SPEED_HOST, GephEngine.SPEED_PATH, 30_000, maxBody = size,
                    extraHeaders = "Range: bytes=0-${size - 1}\r\n",
                )
                if (status in 200..299) GephEngine.lastBodyBytes else 0L
            }.getOrDefault(0L)
            val ms = SystemClock.elapsedRealtime() - t1
            val mbit = if (got > 0 && ms > 0) got * 8.0 / ms / 1000.0 else 0.0
            return Probe(exit, ok = true, connectMs = connectMs, ttfbMs = ttfb, mbit = mbit)
        } catch (e: Exception) {
            return Probe(exit, ok = false, error = e.message ?: e.javaClass.simpleName)
        } finally {
            proc.stop(c)
        }
    }

    /** The last run as it was measured, and when (ms); null before the first. */
    fun lastRun(ctx: Context): Pair<Long, List<Probe>>? {
        val o = GephSettings.lastFastest(ctx) ?: return null
        val arr = o.optJSONArray("ranked") ?: return null
        val list = (0 until arr.length()).mapNotNull { i ->
            val x = arr.optJSONObject(i) ?: return@mapNotNull null
            val country = x.optString("country").takeIf { it.length == 2 } ?: return@mapNotNull null
            Probe(
                exit = GephExitChoice(country, x.optString("city").takeIf { it.isNotBlank() && !x.isNull("city") }),
                ok = x.optBoolean("ok"),
                connectMs = x.optLong("connect"),
                ttfbMs = x.optLong("ttfb"),
                mbit = x.optDouble("mbit", 0.0),
                error = if (x.isNull("error")) null else x.optString("error").ifBlank { null },
            )
        }
        return if (list.isEmpty()) null else o.optLong("at") to list
    }

    const val BUSY = "GEPH_BUSY"
}
