package com.mlmvpn.scanner.engines.amnezia

/**
 * What the section has learned and how it uses it -- pure functions over [AmzState], so every
 * rule here is tested in the JVM.
 *
 * Learning is per network: a server's results on Irancell say nothing about MCI. Each test moves a
 * server's smoothed delay and its failure streak; each source earns a quality from how many of its
 * servers worked. The next test goes to the servers most likely to be good first -- recent winners,
 * then untested servers from the sources that have been best -- so "test the best" finds working
 * servers in the first seconds, and gets faster the more it is used.
 */
object AmzBrain {

    const val MAX_POOL = 3000
    const val MAX_WARP = 250
    const val DEAD_STREAK = 3
    const val BEST_COUNT = 200
    private const val TOMBSTONE_MS = 7L * 24 * 3_600_000
    private const val NET_MAX = 6
    private const val NET_MAX_AGE_MS = 30L * 24 * 3_600_000
    private const val ALPHA = 0.4

    // ---------------------------------------------------------------- the pool

    /**
     * New servers in, better countries for known ones, removed ones kept out, and the pool held to
     * [MAX_POOL] (dropping first what has never worked anywhere and comes from the weakest lists).
     */
    fun merge(s: AmzState, incoming: List<AmzServer>, now: Long, net: String = ""): AmzState {
        val tomb = s.tombstones.filterValues { now - it < TOMBSTONE_MS }
        val byId = LinkedHashMap<String, AmzServer>(s.servers.size + incoming.size)
        s.servers.forEach { byId[it.id] = it }
        for (n in incoming) {
            val old = byId[n.id]
            if (old == null) {
                if (n.id in tomb && !n.user) continue
                byId[n.id] = n
            } else {
                // The same server from another list: its name's country only fills a gap.
                byId[n.id] = old.withCountry(n.country, n.countryFrom).let { if (n.user && !it.user) it.copy(sourceId = n.sourceId) else it }
            }
        }
        return cap(s.copy(servers = byId.values.toList(), tombstones = tomb), now, net)
    }

    private fun cap(s: AmzState, now: Long, net: String): AmzState {
        var servers = s.servers
        val warps = servers.filter { it.warp && !it.user }
        if (warps.size > MAX_WARP) {
            val drop = warps.sortedByDescending { value(it, s, net, now) }.drop(MAX_WARP).map { it.id }.toHashSet()
            servers = servers.filterNot { it.id in drop }
        }
        if (servers.size > MAX_POOL) {
            val keep = servers.sortedByDescending { if (it.user) Double.MAX_VALUE else value(it, s, net, now) }.take(MAX_POOL).map { it.id }.toHashSet()
            servers = servers.filter { it.id in keep }
        }
        return if (servers.size == s.servers.size) s else s.copy(servers = servers)
    }

    fun remove(s: AmzState, ids: Collection<String>, now: Long): AmzState {
        if (ids.isEmpty()) return s
        val set = ids.toHashSet()
        val gone = s.servers.filter { it.id in set }
        return s.copy(
            servers = s.servers.filterNot { it.id in set },
            stats = s.stats.mapValues { (_, m) -> m - set },
            tombstones = s.tombstones + gone.filterNot { it.user }.associate { it.id to now },
            lastConnectedId = s.lastConnectedId?.takeIf { it !in set },
        )
    }

    /** «حذف مرده‌ها»: every server whose last test on this network failed. Never the user's own files. */
    fun deadIds(s: AmzState, net: String): List<String> {
        val st = s.statsFor(net)
        return s.servers.filter { !it.user && st[it.id]?.let { x -> x.tested && !x.alive } == true }.map { it.id }
    }

    // ---------------------------------------------------------------- results

    data class Result(val id: String, val ms: Long?, val country: String? = null)

    /**
     * One test round's results. [offline]: every test failed AND the network itself did not answer
     * -- nothing is learned and nothing is removed, because the servers were never asked.
     */
    fun record(s: AmzState, net: String, results: List<Result>, now: Long, offline: Boolean = false): AmzState {
        if (results.isEmpty() || offline) return s
        val byId = s.servers.associateBy { it.id }
        val netStats = HashMap(s.statsFor(net))
        val sources = HashMap(s.sources)
        val countries = HashMap<String, String>()
        val dead = ArrayList<String>()
        for (r in results) {
            val server = byId[r.id] ?: continue
            val old = netStats[r.id] ?: AmzStat()
            val next = if (r.ms != null) old.copy(
                ok = old.ok + 1, streak = 0, lastMs = r.ms, lastOkAt = now, lastTestAt = now,
                ewmaMs = if (old.ewmaMs <= 0.0) r.ms.toDouble() else old.ewmaMs * (1 - ALPHA) + r.ms * ALPHA,
            ) else old.copy(fail = old.fail + 1, streak = old.streak + 1, lastMs = -1, lastTestAt = now)
            netStats[r.id] = next
            // A source is judged on a server's first verdict only, or a list with one good server
            // tested a hundred times would look like a good list.
            if (!old.tested) {
                val src = sources[server.sourceId] ?: AmzSourceStat()
                sources[server.sourceId] = src.copy(tested = src.tested + 1, alive = src.alive + if (r.ms != null) 1 else 0)
            }
            r.country?.let { countries[r.id] = it }
            if (r.ms == null && next.streak >= DEAD_STREAK && !server.user && s.prefs.autoRemoveDead) dead += r.id
        }
        var out = s.copy(
            stats = s.stats + (net to netStats),
            netSeen = s.netSeen + (net to now),
            sources = sources,
            servers = if (countries.isEmpty()) s.servers else s.servers.map { sv ->
                countries[sv.id]?.takeIf { !sv.warp }?.let { sv.withCountry(it, CountryFrom.PROBE) } ?: sv
            },
        )
        out = remove(out, dead, now)
        return prune(out, now)
    }

    /** The exit measured through the running tunnel: the server moves to the country it really is. */
    fun exitCountry(s: AmzState, id: String, cc: String): AmzState =
        s.copy(servers = s.servers.map { if (it.id == id && !it.warp) it.withCountry(cc, CountryFrom.EXIT) else it })

    fun prune(s: AmzState, now: Long): AmzState {
        val nets = s.netSeen.filterValues { now - it < NET_MAX_AGE_MS }.entries.sortedByDescending { it.value }.take(NET_MAX).map { it.key }.toHashSet()
        if (nets.size == s.netSeen.size && s.stats.keys.all { it in nets }) return s
        return s.copy(stats = s.stats.filterKeys { it in nets }, netSeen = s.netSeen.filterKeys { it in nets })
    }

    // ---------------------------------------------------------------- what to test, and in what order

    /**
     * How promising a server is on [net], 0..~2. Recent winners score by their smoothed delay;
     * untested servers by their list's quality; failures sink with their streak. Nothing is ever
     * zero, so a server that failed yesterday is still tested again eventually.
     */
    fun value(sv: AmzServer, s: AmzState, net: String, now: Long): Double {
        val st = s.statsFor(net)[sv.id]
        val src = s.sources[sv.sourceId]?.takeIf { it.tested >= 5 }?.quality ?: AmzSources.prior(sv.sourceId)
        val base = when {
            st == null || !st.tested -> 0.3 + 0.6 * src
            st.alive -> {
                val speed = 1.0 / (1.0 + st.ewmaMs / 600.0)
                val fresh = if (now - st.lastOkAt < 6 * 3_600_000L) 0.2 else 0.0
                1.0 + speed + fresh
            }
            else -> {
                val hadWorked = if (st.ok > 0) 0.3 else 0.0
                (0.15 + hadWorked + 0.2 * src) / (1 + st.streak)
            }
        }
        return base + if (sv.user) 0.5 else 0.0
    }

    /** The servers a test of [mode] covers, most promising first. */
    fun pickForTest(s: AmzState, net: String, mode: AmzTestMode, now: Long, country: String? = null, oneId: String? = null, kind: AmzKind? = null): List<AmzServer> {
        val pool = s.servers.filter { kind == null || it.kind == kind || (kind == AmzKind.WG && it.kind == AmzKind.AWG) }
        return when (mode) {
            AmzTestMode.ONE -> pool.filter { it.id == oneId }
            AmzTestMode.COUNTRY -> pool.filter { group(it) == (country ?: UNKNOWN) }.sortedByDescending { value(it, s, net, now) }
            AmzTestMode.ALL -> pool.sortedByDescending { value(it, s, net, now) }
            AmzTestMode.BEST -> {
                // The best known plus a share of untested ones from good lists, so the pool keeps
                // being explored instead of re-testing the same two hundred forever.
                val ranked = pool.sortedByDescending { value(it, s, net, now) }
                val st = s.statsFor(net)
                val known = ranked.filter { st[it.id]?.alive == true }.take(BEST_COUNT * 3 / 4)
                val fresh = ranked.filter { st[it.id]?.tested != true }.take(BEST_COUNT - known.size)
                val taken = (known + fresh).mapTo(HashSet()) { it.id }
                val rest = ranked.filter { it.id !in taken }.take(BEST_COUNT - known.size - fresh.size)
                (known + fresh + rest)
            }
        }
    }

    // ---------------------------------------------------------------- what the list shows

    data class Row(val server: AmzServer, val stat: AmzStat?)

    /** Working servers fastest first, then untested by promise, then failed. */
    fun sortRows(rows: List<Row>, s: AmzState, net: String, now: Long): List<Row> =
        rows.sortedWith(compareBy<Row>(
            { when { it.stat?.alive == true -> 0; it.stat?.tested != true -> 1; else -> 2 } },
            { if (it.stat?.alive == true) it.stat.lastMs.toDouble() else -value(it.server, s, net, now) },
        ))

    /**
     * Countries in the order the list shows them: those with a working server first, by their
     * fastest one; then the rest by size; «نامشخص» always last.
     */
    fun countryOrder(s: AmzState, net: String): List<Pair<String, Int>> {
        val st = s.statsFor(net)
        val groups = s.servers.groupBy { group(it) }
        return groups.entries.map { (cc, list) ->
            val best = list.mapNotNull { st[it.id]?.takeIf { x -> x.alive }?.lastMs }.minOrNull()
            Triple(cc, list.size, best)
        }.sortedWith(compareBy<Triple<String, Int, Long?>>(
            { if (it.first == UNKNOWN) 2 else if (it.third != null) 0 else 1 },
            { it.third ?: Long.MAX_VALUE },
            { -it.second },
        )).map { it.first to it.second }
    }

    /** The best working server, optionally in one country, for a one-tap connect. */
    fun best(s: AmzState, net: String, country: String? = null, exclude: Set<String> = emptySet()): AmzServer? {
        val st = s.statsFor(net)
        return s.servers.filter { it.id !in exclude && (country == null || group(it) == country) }
            .filter { st[it.id]?.alive == true }
            .minByOrNull { st[it.id]!!.lastMs }
    }

    /** Nothing tested on this network yet: the most promising server that has not failed here. */
    fun promising(s: AmzState, net: String, country: String?, exclude: Set<String>, now: Long): AmzServer? {
        val st = s.statsFor(net)
        return s.servers.filter { it.id !in exclude && (country == null || group(it) == country) && st[it.id]?.let { x -> x.alive || !x.tested } != false }
            .maxByOrNull { value(it, s, net, now) }
    }

    const val UNKNOWN = "??"

    /**
     * Cloudflare WARP accounts have a group of their own. WARP exits with an address that
     * geolocates to the user's own country (Cloudflare's trace says IR from Iran), so filing them
     * by the measured country would put a hundred "Iranian" servers at the top of the list.
     */
    const val WARP = "*warp"

    /** The group a server is listed under: WARP, its country, or «نامشخص». */
    fun group(sv: AmzServer): String = if (sv.warp) WARP else sv.country ?: UNKNOWN
}
