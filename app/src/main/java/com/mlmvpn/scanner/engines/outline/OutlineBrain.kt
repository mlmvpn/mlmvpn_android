package com.mlmvpn.scanner.engines.outline

/**
 * The list's rules, kept free of Android so they are tested on their own.
 *
 *  - merge: new servers join, deleted ones stay out for [TOMBSTONE_MS], and a public server that
 *    failed [DROP_AFTER_FAILS] tests in a row on every network it was tried on is dropped. There is
 *    no size limit: the pool used to be cut at 1,500, so most of a big list was never even looked
 *    at (user, 2026-10-17: «محدودیت ۱۵۰۰ تا سرور داره اینم بردار»).
 *  - order: favourites first, then working servers fastest first, then untested ones by how
 *    trustworthy their list is, then the dead.
 */
object OutlineBrain {

    const val TOMBSTONE_MS = 14L * 24 * 3_600_000
    /** A public server that failed this many tests in a row on every network is dropped at refresh. */
    const val DROP_AFTER_FAILS = 4
    const val UNKNOWN = "??"

    data class Result(val id: String, val ms: Long?, val speedBps: Long? = null, val viaCf: Boolean = false)

    fun group(s: OutlineServer): String = s.country ?: UNKNOWN

    fun merge(state: OutlineState, incoming: List<OutlineServer>, now: Long): OutlineState {
        val tombs = state.tombstones.filterValues { now - it < TOMBSTONE_MS }
        val byId = LinkedHashMap<String, OutlineServer>()
        state.servers.forEach { byId[it.id] = it }
        for (s in incoming) {
            if (s.id in tombs) continue
            val old = byId[s.id]
            byId[s.id] = when {
                old == null -> s.copy(seenAt = now)
                // A user's own key keeps its name and source.
                old.user -> old.copy(seenAt = now)
                // A public one is named by the most trusted list carrying it: a Shadowmere key keeps
                // its tag and check time when a GitHub list carries it too, and takes them on when
                // Shadowmere starts carrying a server a GitHub list brought first.
                OutlineSources.prior(s.sourceId) >= OutlineSources.prior(old.sourceId) -> old.copy(
                    uri = s.uri, name = s.name, sourceId = s.sourceId, country = old.country ?: s.country,
                    prefix = old.prefix || s.prefix, seenAt = now,
                    checkedAt = if (s.checkedAt > 0) s.checkedAt else old.checkedAt, uptime = s.uptime ?: old.uptime,
                )
                else -> old.copy(country = old.country ?: s.country, prefix = old.prefix || s.prefix, seenAt = now)
            }
        }
        // Only a list that answered this time can say a server is gone from it: a list the line could
        // not reach says nothing, and its servers stay however long that lasts.
        val answered = incoming.mapTo(HashSet()) { it.sourceId }
        return state.copy(servers = cap(state.copy(servers = byId.values.toList()), now, answered), tombstones = tombs)
    }

    /** A public server its list has not carried for this long is gone (unless it is kept for a reason). */
    const val STALE_MS = 3L * 24 * 3_600_000

    /**
     * Keeps the pool clean, not small. Two kinds of public server go: one that failed
     * [DROP_AFTER_FAILS] tests in a row on every network it was tried on (straight AND from inside
     * WARP, since a test tries both), and one its list -- which answered this time -- has not carried
     * for [STALE_MS]. The user's own keys, favourites and anything that ever worked are never dropped.
     */
    private fun cap(state: OutlineState, now: Long, answered: Set<String> = emptySet()): List<OutlineServer> {
        val workedSomewhere = state.stats.values.flatMap { m -> m.filterValues { it.alive }.keys }.toHashSet()
        fun keptForAReason(s: OutlineServer) = s.user || s.id in state.favorites || s.id in workedSomewhere
        return state.servers.filter { s ->
            if (keptForAReason(s)) return@filter true
            val deadEverywhere = state.stats.values.any { (it[s.id]?.fails ?: 0) >= DROP_AFTER_FAILS } &&
                state.stats.values.all { m -> m[s.id]?.let { it.fails >= DROP_AFTER_FAILS } ?: true }
            val gone = s.sourceId in answered && now - s.lastSeen > STALE_MS
            !deadEverywhere && !gone
        }
    }

    fun remove(state: OutlineState, ids: Collection<String>, now: Long): OutlineState {
        val set = ids.toHashSet()
        return state.copy(
            servers = state.servers.filter { it.id !in set },
            stats = state.stats.mapValues { (_, m) -> m.filterKeys { it !in set } },
            tombstones = state.tombstones + set.associateWith { now },
            favorites = state.favorites - set,
        )
    }

    fun record(state: OutlineState, net: String, results: List<Result>, now: Long): OutlineState {
        if (results.isEmpty()) return state
        val m = state.statsFor(net).toMutableMap()
        for (r in results) {
            val old = m[r.id] ?: OutlineStat()
            m[r.id] = if (r.ms != null && r.ms >= 0) OutlineStat(r.ms, now, 0, r.speedBps ?: old.speedBps, r.viaCf)
            else old.copy(lastMs = -1, lastTestAt = now, fails = old.fails + 1)
        }
        return state.copy(stats = state.stats + (net to m))
    }

    fun deadIds(state: OutlineState, net: String): List<String> {
        val st = state.statsFor(net)
        return state.servers.filter { st[it.id]?.let { s -> s.tested && !s.alive } == true && it.id !in state.favorites }.map { it.id }
    }

    /** The list's order on this network. */
    fun sorted(state: OutlineState, net: String): List<OutlineServer> {
        val st = state.statsFor(net)
        val scout = state.scoutWinners[net]
        return state.servers.sortedWith(
            compareBy<OutlineServer>(
                { if (it.id in state.favorites) 0 else 1 },
                {
                    val s = st[it.id]
                    when {
                        s?.alive == true -> 0
                        it.id == scout -> 0
                        s == null || !s.tested -> 1
                        else -> 2
                    }
                },
                { st[it.id]?.takeIf { s -> s.alive }?.lastMs ?: Long.MAX_VALUE },
                { -OutlineSources.prior(it.sourceId) },
                { -(it.uptime ?: 0) },
                { st[it.id]?.fails ?: 0 },
            ),
        )
    }

    /** The fastest working server here, optionally of one country, not in [exclude]. */
    fun best(state: OutlineState, net: String, country: String? = null, exclude: Set<String> = emptySet()): OutlineServer? {
        val st = state.statsFor(net)
        return state.servers.asSequence()
            .filter { it.id !in exclude && (country == null || group(it) == country) }
            .filter { st[it.id]?.alive == true }
            .minByOrNull { st[it.id]!!.lastMs }
    }

    /**
     * Shadowmere carried it in the last fetch: it passed Shadowmere's own check within the hour.
     * The fetch that brought it may be up to an hour older than the latest refresh of another list.
     */
    fun listedByShadowmere(s: OutlineServer, state: OutlineState): Boolean =
        s.shadowmere && state.lastRefreshAt - s.lastSeen < 2 * 3_600_000L

    /**
     * What a quick test covers: favourites, everything that worked here, every key Shadowmere lists
     * now (user, 2026-10-25: all of them get a ping), then the most promising untested.
     */
    fun pickForTest(state: OutlineState, net: String, all: Boolean, limit: Int = 80): List<OutlineServer> {
        val ordered = sorted(state, net)
        if (all) return ordered
        val st = state.statsFor(net)
        val first = ordered.filter { it.id in state.favorites || st[it.id]?.alive == true || it.user || listedByShadowmere(it, state) }
        val rest = ordered.filter { it !in first && (st[it.id]?.fails ?: 0) < 2 }
        return (first + rest).take(limit.coerceAtLeast(first.size))
    }
}
