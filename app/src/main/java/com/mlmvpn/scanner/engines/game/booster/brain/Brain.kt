package com.mlmvpn.scanner.engines.game.booster.brain

import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import org.json.JSONObject
import kotlin.math.pow

/**
 * What this phone learned from its own sessions: how each plan did for each game on each network,
 * and which anti-sanction DNS opened sign-ins there.
 *
 * Counts, not a last decision. Each outcome adds weight to "worked" or "did not" (an answer to
 * «درست کار کرد؟» weighs three sessions, a session that merely ran long weighs one, a quick stop
 * half of one), and everything fades with a [HALF_LIFE_MS] half-life -- a line that was bad a month
 * ago is not bad now. Read as a Beta posterior with a flat prior, so two good sessions are "probably
 * fine" and twenty are "fine".
 *
 * Pure logic with an injected clock; [BrainStore] persists it.
 */
class Brain(private val now: () -> Long = System::currentTimeMillis) {

    private data class Counts(var ok: Double = 0.0, var bad: Double = 0.0, var at: Long = 0L)

    private val cells = LinkedHashMap<String, Counts>()

    private fun decayed(c: Counts, t: Long): Counts {
        if (c.at == 0L || t <= c.at) return c
        val f = 0.5.pow((t - c.at).toDouble() / HALF_LIFE_MS)
        return Counts(c.ok * f, c.bad * f, t)
    }

    private fun add(key: String, ok: Double, bad: Double) {
        val t = now()
        val c = decayed(cells[key] ?: Counts(at = t), t)
        c.ok += ok
        c.bad += bad
        c.at = t
        cells[key] = c
        evict()
    }

    /** A session of [plan] for [game] on network [net]: outcome +1 worked, −1 did not, 0 unknown. */
    fun record(net: String, game: String, plan: String, outcome: Int, weight: Double) {
        if (net.isEmpty() || outcome == 0 || weight <= 0.0) return
        add(planKey(net, game, plan), if (outcome > 0) weight else 0.0, if (outcome < 0) weight else 0.0)
    }

    /** One anti-sanction DNS trial on network [net]: did it open the refused hosts? */
    fun recordSdns(net: String, provider: String, fixed: Boolean) {
        if (net.isEmpty()) return
        add(sdnsKey(net, provider), if (fixed) 1.0 else 0.0, if (fixed) 0.0 else 1.0)
    }

    /** Posterior mean of "worked" for [plan], or null with under [MIN_WEIGHT] of evidence. */
    fun planRate(net: String, game: String, plan: String): Double? = rate(planKey(net, game, plan))

    /** The anti-sanction DNS that has done best on this network, if the evidence says so. */
    fun bestSdns(net: String): String? {
        val prefix = sdnsKey(net, "")
        return cells.keys.filter { it.startsWith(prefix) }
            .mapNotNull { k -> rate(k)?.let { k.removePrefix(prefix) to it } }
            .filter { it.second >= 0.5 }
            .maxByOrNull { it.second }?.first
    }

    private fun rate(key: String): Double? {
        val c = cells[key]?.let { decayed(it, now()) } ?: return null
        if (c.ok + c.bad < MIN_WEIGHT) return null
        return (c.ok + 1.0) / (c.ok + c.bad + 2.0)
    }

    private fun evict() {
        if (cells.size <= MAX_CELLS) return
        cells.entries.sortedBy { it.value.at }.take(cells.size - MAX_CELLS).map { it.key }.forEach { cells.remove(it) }
    }

    fun clear() = cells.clear()

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("c", JSONObject().apply {
            cells.forEach { (k, c) -> put(k, org.json.JSONArray().put(c.ok).put(c.bad).put(c.at)) }
        })
    }

    fun loadJson(o: JSONObject) {
        cells.clear()
        val c = o.optJSONObject("c") ?: return
        c.keys().forEach { k ->
            val a = c.optJSONArray(k) ?: return@forEach
            cells[k] = Counts(a.optDouble(0, 0.0), a.optDouble(1, 0.0), a.optLong(2, 0L))
        }
    }

    companion object {
        const val HALF_LIFE_MS = 14 * 24 * 60 * 60 * 1000L
        const val MIN_WEIGHT = 1.5
        const val MAX_CELLS = 400

        private fun planKey(net: String, game: String, plan: String) = "p|$net|$game|$plan"
        private fun sdnsKey(net: String, provider: String) = "s|$net|$provider"

        /** The short plan codes shared with the crowd service. */
        fun planCode(route: RouteKind): String = when (route) {
            RouteKind.DIRECT -> "D"
            RouteKind.DIRECT_DNS -> "DD"
            RouteKind.WARP_MASQUE_H3 -> "W3"
            RouteKind.WARP_MASQUE_H2 -> "W2"
            RouteKind.WARP_WG -> "WG"
            RouteKind.ACCESS_HELPER -> "D"
            RouteKind.AMNEZIA -> "AZ"
            RouteKind.FENIX -> "FX"
        }
    }
}

/** [Brain] kept in `files/game_booster/brain.json`, written atomically. */
class BrainStore(context: android.content.Context) {
    private val file = java.io.File(java.io.File(context.applicationContext.filesDir, "game_booster").apply { mkdirs() }, "brain.json")
    val brain = Brain()

    init {
        try { if (file.exists()) brain.loadJson(JSONObject(file.readText())) } catch (_: Exception) {}
    }

    fun save() {
        try {
            val tmp = java.io.File(file.parentFile, "brain.json.tmp")
            tmp.writeText(brain.toJson().toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
        }
    }

    fun clear() {
        brain.clear()
        save()
    }
}
