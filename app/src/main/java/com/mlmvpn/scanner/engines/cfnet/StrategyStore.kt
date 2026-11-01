package com.mlmvpn.scanner.engines.cfnet

import org.json.JSONObject
import java.io.File
import kotlin.math.pow

/** Confidence with no evidence yet -- the "unknown" midpoint the learned rate blends away from. */
private const val CONF_UNKNOWN = 0.5
private const val CONF_HALF_LIFE_MS = 7L * 24 * 3600_000   // a week

/**
 * What the engine has learned, per network and per (stage, strategy). Persisted as one small JSON
 * file, rewritten atomically (temp + rename). It holds ONLY ids, counts, timestamps, a latency
 * average, the last failure class and the network signature the outcome was recorded under --
 * never a token, key, address, hostname or body. The pure nature of this file is what lets the
 * "no secrets are ever stored" test actually prove it.
 *
 * Confidence is a recency-decayed, Laplace-smoothed success rate: fresh evidence pulls a strategy's
 * score toward its measured rate, and as that evidence ages the score drifts back to the static
 * prior, so a line that changed character is re-measured rather than trusted forever. Evidence
 * recorded under a different network signature (families / DNS sanity flipped on the same network)
 * is treated as older and is never a fast-path winner -- but it is kept, not wiped: one flaky DNS
 * check must not erase everything learned on a network.
 */
class StrategyStore(private val file: File, private val engineVersion: Int = ENGINE_VERSION) {

    data class Stat(
        var success: Int = 0,
        var fail: Int = 0,
        var lastSuccess: Long = 0,
        var lastFail: Long = 0,
        var latencyEwmaMs: Long = 0,
        var lastFailure: FailureClass? = null,
        /**
         * An EWMA of the last outcomes (1 for success, 0 for failure, α = 0.5), so the MOST recent
         * result weighs ~half. This is what makes healing work: a strategy with a long success
         * history still drops below a fresh alternative the moment it fails, instead of coasting on
         * its past. The long-run success rate is kept separately in the counts.
         */
        var recent: Double = CONF_UNKNOWN,
        /** The network signature the last outcome was recorded under ("" = unknown, older files). */
        var sig: String = "",
    ) {
        /**
         * Confidence in [0,1] at [now]. No evidence → the unknown midpoint. With evidence it blends
         * the long-run smoothed rate with the recent-outcomes EWMA, then drifts back toward the
         * midpoint as the last outcome ages past the half-life; [aged] doubles the effective age for
         * a network whose signature has changed.
         */
        fun confidence(now: Long, halfLifeMs: Long = CONF_HALF_LIFE_MS, aged: Boolean = false): Double {
            val total = success + fail
            if (total == 0) return CONF_UNKNOWN
            val rate = (success + 1.0) / (total + 2.0)   // Laplace-smoothed long-run rate
            val blend = 0.5 * rate + 0.5 * recent        // recent behaviour weighs as much as history
            val last = maxOf(lastSuccess, lastFail)
            val age = (now - last).coerceAtLeast(0).let { if (aged) it * 2 + halfLifeMs / 2 else it }
            val recency = 0.5.pow(age.toDouble() / halfLifeMs)
            return CONF_UNKNOWN + (blend - CONF_UNKNOWN) * recency
        }

        /** The most recent outcome was a failure. */
        val lastWasFailure: Boolean get() = lastFail > lastSuccess
    }

    private val nets = HashMap<String, MutableMap<String, Stat>>()
    private var loaded = false
    private val lock = Any()

    private fun keyOf(stage: CfStage, strategyId: String) = "${stage.key}|$strategyId"

    private fun load() {
        if (loaded) return
        loaded = true
        val backup = File(file.parentFile, file.name + ".bak")
        val text = runCatching { when { file.isFile -> file.readText(); backup.isFile -> backup.readText(); else -> null } }.getOrNull() ?: return
        runCatching {
            val root = JSONObject(text)
            // A different engine version means different strategies or validators: what was learned
            // under the old one is not evidence for the new one.
            if (root.optInt("v") != engineVersion) return
            val netsJson = root.optJSONObject("nets") ?: return
            for (k in netsJson.keys()) {
                val statsJson = netsJson.getJSONObject(k).optJSONObject("stats") ?: JSONObject()
                val book = HashMap<String, Stat>()
                for (sk in statsJson.keys()) {
                    val s = statsJson.getJSONObject(sk)
                    book[sk] = Stat(
                        s.optInt("s"), s.optInt("f"), s.optLong("ls"), s.optLong("lf"),
                        s.optLong("lat"),
                        s.optString("lc").takeIf { it.isNotEmpty() }?.let { runCatching { FailureClass.valueOf(it) }.getOrNull() },
                        s.optDouble("r", CONF_UNKNOWN),
                        s.optString("g"),
                    )
                }
                nets[k] = book
            }
        }
    }

    private fun persist() {
        val root = JSONObject().put("v", engineVersion)
        val netsJson = JSONObject()
        for ((k, book) in nets) {
            val statsJson = JSONObject()
            for ((sk, s) in book) {
                statsJson.put(sk, JSONObject().apply {
                    put("s", s.success); put("f", s.fail); put("ls", s.lastSuccess); put("lf", s.lastFail)
                    put("lat", s.latencyEwmaMs); put("r", s.recent); put("g", s.sig)
                    s.lastFailure?.let { put("lc", it.name) }
                })
            }
            netsJson.put(k, JSONObject().put("stats", statsJson))
        }
        root.put("nets", netsJson)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(tmp).use { out -> out.write(root.toString().toByteArray(Charsets.UTF_8)); out.fd.sync() }
            if (!tmp.renameTo(file)) {
                val backup = File(file.parentFile, file.name + ".bak")
                if (backup.exists() && !backup.delete()) return@runCatching
                if (file.exists() && !file.renameTo(backup)) return@runCatching
                if (tmp.renameTo(file)) backup.delete() else backup.renameTo(file)
            }
        }
    }

    private fun bookFor(profile: NetworkProfile): MutableMap<String, Stat> = nets.getOrPut(profile.key) { HashMap() }

    /** True when [s] was last recorded under another signature of this network. */
    private fun aged(s: Stat, profile: NetworkProfile): Boolean = s.sig.isNotEmpty() && s.sig != profile.signature()

    fun stat(profile: NetworkProfile, stage: CfStage, strategyId: String): Stat = synchronized(lock) {
        load()
        bookFor(profile)[keyOf(stage, strategyId)]?.copy() ?: Stat()
    }

    /** Record one real outcome and persist. [failure] is null on success. */
    fun record(profile: NetworkProfile, stage: CfStage, strategyId: String, ok: Boolean, latencyMs: Long, failure: FailureClass?, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            load()
            val s = bookFor(profile).getOrPut(keyOf(stage, strategyId)) { Stat() }
            if (ok) {
                s.success++; s.lastSuccess = maxOf(now, s.lastFail + 1)
                s.latencyEwmaMs = if (s.latencyEwmaMs == 0L) latencyMs else (s.latencyEwmaMs * 7 + latencyMs * 3) / 10
                s.lastFailure = null
                s.recent = s.recent * 0.5 + 0.5
            } else {
                s.fail++; s.lastFail = maxOf(now, s.lastSuccess + 1); s.lastFailure = failure
                s.recent = s.recent * 0.5
            }
            s.sig = profile.signature()
            persist()
        }
    }

    /**
     * Rank [candidates] for [stage] on [profile], best first. Blends learned confidence (trusted more
     * as evidence accumulates) with the static prior, minus a small cost penalty, so a cold start
     * follows the priors and a learned network follows experience.
     */
    fun rank(profile: NetworkProfile, stage: CfStage, candidates: List<Strategy>, now: Long = System.currentTimeMillis(),
        /** This network's firewall family, when known: its strategies are tried first ([FAMILY_BONUS]). */
        family: FirewallFamily? = null,
    ): List<Strategy> = synchronized(lock) {
        load()
        candidates.sortedByDescending { score(profile, stage, it, now) + if (family != null && FirewallFamily.of(it) == family) FAMILY_BONUS else 0.0 }
    }

    /**
     * The firewall family this network has shown: the family of the DATA strategy (SCAN or CONNECT)
     * that most recently carried data here, on this signature -- or null before any did. One stage's
     * proof is the other's best first guess: a line where ECH found the scan's addresses is an
     * MCI-type line for the connect too.
     */
    fun family(profile: NetworkProfile, byId: (String) -> Strategy?): FirewallFamily? = synchronized(lock) {
        load()
        bookFor(profile).entries
            .filter { (k, s) -> (k.startsWith(CfStage.SCAN.key + "|") || k.startsWith(CfStage.CONNECT.key + "|")) &&
                s.success > 0 && !s.lastWasFailure && !aged(s, profile) }
            .mapNotNull { (k, s) -> byId(k.substringAfter('|'))?.let(FirewallFamily::of)?.let { it to s.lastSuccess } }
            .maxByOrNull { it.second }?.first
    }

    /**
     * The ranking score. A strategy whose LAST outcome was a failure in the last half hour is pushed
     * below untried alternatives, however long its history: it has just been shown not to work here,
     * so trying it again first would only repeat that failure before discovery moves on.
     */
    fun score(profile: NetworkProfile, stage: CfStage, strategy: Strategy, now: Long = System.currentTimeMillis()): Double = synchronized(lock) {
        load()
        val s = bookFor(profile)[keyOf(stage, strategy.id)] ?: return@synchronized strategy.prior - strategy.cost * 0.01
        val n = s.success + s.fail
        val w = n / (n + 3.0)            // trust the learned value more as evidence grows
        val c = s.confidence(now, aged = aged(s, profile))
        val freshFailure = s.lastWasFailure && now - s.lastFail < FRESH_FAILURE_MS
        (w * c + (1 - w) * strategy.prior) - strategy.cost * 0.01 - (if (freshFailure) FRESH_FAILURE_PENALTY else 0.0)
    }

    /**
     * The best-proven strategy for a quick fast-path re-check: its most recent outcome was a success
     * on this very network signature, with top confidence -- or null.
     */
    fun fastPath(profile: NetworkProfile, stage: CfStage, candidates: List<Strategy>, now: Long = System.currentTimeMillis()): Strategy? = synchronized(lock) {
        load()
        val book = bookFor(profile)
        candidates
            .mapNotNull { st -> book[keyOf(stage, st.id)]?.let { st to it } }
            .filter { (_, s) -> s.success > 0 && !s.lastWasFailure && s.lastFailure == null && !aged(s, profile) &&
                s.confidence(now) >= FAST_PATH_MIN_CONFIDENCE }
            .maxWithOrNull(compareBy<Pair<Strategy, Stat>> { it.second.confidence(now) }.thenByDescending { it.second.latencyEwmaMs })
            ?.first
    }

    data class RuntimePath(val source: CfStage, val strategy: Strategy)

    /**
     * The strategy a live dial uses for [stage], and the stage whose evidence chose it.
     *
     * Only CONNECT borrows SCAN evidence. A SCAN success is a candidate for CONNECT, never evidence
     * of CONNECT success, and it stops being one once CONNECT itself failed with it on this network
     * signature after that scan (a failure under another signature says nothing here).
     *
     * [literal]: the config dials one literal address -- a clean IP the scanner found and a combine
     * built on. SCAN proved its handshake on exactly such addresses, so a SCAN winner goes first, for
     * as long as the fast-path rules trust it. A config that dials a NAME follows its own CONNECT
     * evidence first and borrows a SCAN winner only while fresh: a handshake proven on sampled clean
     * IPs says little about the edges a name resolves to.
     */
    fun runtimePath(profile: NetworkProfile, stage: CfStage, candidates: List<Strategy>,
        now: Long = System.currentTimeMillis(), literal: Boolean = false): RuntimePath? = synchronized(lock) {
        load()
        if (stage != CfStage.CONNECT) return@synchronized fastPath(profile, stage, candidates, now)?.let { RuntimePath(stage, it) }
        val fromScan = candidates.filter { s ->
            CfStage.SCAN in s.stages && s.data != null && !connectFailedSinceScan(profile, s.id) &&
                (literal || provenWithin(profile, CfStage.SCAN, s.id, SCAN_HANDOFF_MS, now))
        }
        val connect = fastPath(profile, CfStage.CONNECT, candidates, now)?.let { RuntimePath(CfStage.CONNECT, it) }
        val scan = fastPath(profile, CfStage.SCAN, fromScan, now)?.let { RuntimePath(CfStage.SCAN, it) }
        if (literal) scan ?: connect else connect ?: scan
    }

    /** CONNECT failed with [strategyId] on THIS network signature, last, after its last SCAN success. */
    private fun connectFailedSinceScan(profile: NetworkProfile, strategyId: String): Boolean {
        val book = bookFor(profile)
        val connect = book[keyOf(CfStage.CONNECT, strategyId)] ?: return false
        val scan = book[keyOf(CfStage.SCAN, strategyId)] ?: return true
        return !aged(connect, profile) && connect.lastWasFailure && connect.lastFail > scan.lastSuccess
    }

    /** True when [strategyId] last succeeded for [stage] on this signature within [withinMs] -- trusted without re-checking. */
    fun provenWithin(profile: NetworkProfile, stage: CfStage, strategyId: String, withinMs: Long, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        load()
        val s = bookFor(profile)[keyOf(stage, strategyId)] ?: return@synchronized false
        !s.lastWasFailure && !aged(s, profile) && s.lastSuccess > 0 && now - s.lastSuccess in 0..withinMs
    }

    fun confidence(profile: NetworkProfile, stage: CfStage, strategyId: String, now: Long = System.currentTimeMillis()): Double = synchronized(lock) {
        load()
        bookFor(profile)[keyOf(stage, strategyId)]?.let { it.confidence(now, aged = aged(it, profile)) } ?: CONF_UNKNOWN
    }

    /** Every recorded (stage, strategy) on [profile]'s network, for the developer view. */
    fun all(profile: NetworkProfile): Map<String, Stat> = synchronized(lock) {
        load()
        bookFor(profile).mapValues { it.value.copy() }
    }

    companion object {
        /** v3: per-outcome network signature, no wipe on a signature change, route-neutral verdicts. */
        const val ENGINE_VERSION = 3
        const val FAST_PATH_MIN_CONFIDENCE = 0.6
        const val DEFAULT_HALF_LIFE_MS = CONF_HALF_LIFE_MS
        const val FRESH_FAILURE_MS = 30 * 60_000L
        const val FRESH_FAILURE_PENALTY = 0.15
        /** How long a SCAN winner may stand in for CONNECT on a config that dials a name. */
        const val SCAN_HANDOFF_MS = 20 * 60_000L
        /** Enough to put a family's strategies ahead of every other's prior, not past their own order. */
        const val FAMILY_BONUS = 0.3
    }
}
