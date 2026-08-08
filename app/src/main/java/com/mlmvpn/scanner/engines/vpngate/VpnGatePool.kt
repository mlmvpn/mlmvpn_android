package com.mlmvpn.scanner.engines.vpngate

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The growing archive of every VPN Gate server this install has ever seen, plus the user's
 * curated "main list".
 *
 * VPN Gate only ever publishes ~100 servers at a time and rotates them hard — of the 97 in a
 * year-old snapshot, 4 were still listed today. So the way to end up with a large catalogue is
 * to keep what each refresh brings instead of replacing it. The browser screen reads from this
 * pool; the connect screen reads from the main list.
 *
 * Stored as one JSON file in filesDir. Profiles are ~13 KB each, so the pool is capped and
 * evicts the least-recently-seen entries — never anything the user has kept.
 */
object VpnGatePool {

    private const val TAG = "VpnGatePool"
    private const val DIR = "vpngate"
    private const val POOL_FILE = "pool.json"
    private const val PREFS = "vpngate_prefs"
    private const val KEY_KEPT = "kept_hosts"
    private const val KEY_HIDDEN = "hidden_hosts"

    /** Metadata only, so even a full pool is well under a megabyte. */
    private const val MAX_POOL = 3000

    /** Anything above this was written by a build that still stored OpenVPN profiles. */
    private const val LEGACY_SIZE_THRESHOLD = 2L * 1024 * 1024

    /** A pool entry: the server plus when we last saw it advertised. */
    data class Entry(val server: VpnGateServer, val lastSeen: Long)

    private val _pool = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val poolFlow: StateFlow<Map<String, Entry>> = _pool.asStateFlow()

    /** Hostnames the user explicitly keeps. Empty until the first curation. */
    private val _kept = MutableStateFlow<Set<String>>(emptySet())
    val keptFlow: StateFlow<Set<String>> = _kept.asStateFlow()

    /**
     * Hostnames the user pruned out of the main list. Kept as a deny-list rather than by
     * deleting, because the live list re-advertises the same servers on every refresh and a
     * deletion would silently come back.
     */
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hiddenFlow: StateFlow<Set<String>> = _hidden.asStateFlow()

    private var loaded = false

    private fun dir(context: Context) =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        loaded = true
        val p = prefs(context)
        _kept.value = p.getStringSet(KEY_KEPT, emptySet())?.toSet() ?: emptySet()
        _hidden.value = p.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet()
        val f = File(dir(context), POOL_FILE)
        if (!f.exists()) return@withContext
        try {
            val arr = JSONArray(f.readText())
            val map = HashMap<String, Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = fromJson(o) ?: continue
                map[s.hostName] = Entry(s, o.optLong("lastSeen", 0L))
            }
            _pool.value = map
            Log.d(TAG, "loaded ${map.size} pooled servers")

            // One-time shrink for archives written before profiles were dropped. Rewriting
            // here means the next launch reads a file two orders of magnitude smaller.
            if (f.length() > LEGACY_SIZE_THRESHOLD) {
                Log.d(TAG, "compacting legacy pool (${f.length() / 1024} KB)")
                save(context, map)
                Log.d(TAG, "compacted to ${File(dir(context), POOL_FILE).length() / 1024} KB")
            }
        } catch (e: Exception) {
            Log.w(TAG, "pool read failed — starting empty", e)
        }
    }

    /**
     * Folds a freshly fetched list into the pool. Existing entries are refreshed (score, ping
     * and session counts move constantly) and their lastSeen bumped.
     *
     * @return how many hostnames were new.
     */
    suspend fun merge(context: Context, servers: List<VpnGateServer>): Int =
        withContext(Dispatchers.IO) {
            if (servers.isEmpty()) return@withContext 0
            load(context)

            val now = System.currentTimeMillis()
            val map = HashMap(_pool.value)
            var added = 0
            for (s in servers) {
                if (!map.containsKey(s.hostName)) added++
                map[s.hostName] = Entry(s, now)
            }

            // Evict the stalest entries the user has not kept.
            if (map.size > MAX_POOL) {
                val kept = _kept.value
                map.values
                    .filter { it.server.hostName !in kept }
                    .sortedBy { it.lastSeen }
                    .take(map.size - MAX_POOL)
                    .forEach { map.remove(it.server.hostName) }
            }

            _pool.value = map
            save(context, map)
            Log.d(TAG, "merged ${servers.size} servers (+$added new), pool=${map.size}")
            added
        }

    fun keep(context: Context, hosts: Collection<String>) {
        if (hosts.isEmpty()) return
        _kept.value = _kept.value + hosts
        persistKept(context)
    }

    fun drop(context: Context, hosts: Collection<String>) {
        if (hosts.isEmpty()) return
        _kept.value = _kept.value - hosts.toSet()
        persistKept(context)
    }

    /** Servers of the user's main list, in pool order. */
    fun keptServers(): List<VpnGateServer> {
        val kept = _kept.value
        val pool = _pool.value
        return kept.mapNotNull { pool[it]?.server }
    }

    /** Prunes servers out of the main list. Also un-keeps them, so the two never disagree. */
    fun hide(context: Context, hosts: Collection<String>) {
        if (hosts.isEmpty()) return
        _hidden.value = _hidden.value + hosts
        _kept.value = _kept.value - hosts.toSet()
        prefs(context).edit()
            .putStringSet(KEY_HIDDEN, _hidden.value)
            .putStringSet(KEY_KEPT, _kept.value)
            .apply()
    }

    /**
     * Deletes servers from the archive outright — not the [hide] deny-list, actual removal.
     *
     * For servers a real handshake test has just proved dead. [hide] is the wrong tool: it
     * exists to keep the *live* list from re-advertising something the user pruned, and it
     * grows a second set forever while pool.json keeps carrying the dead entries anyway. A
     * server that fails the probe has nothing worth archiving, and if VPN Gate ever
     * re-advertises it the next refresh will merge it back in as a genuinely fresh entry —
     * which is the correct outcome, since by then it may well be alive again.
     *
     * Also drops the hosts from `kept`, so the two sets cannot disagree. Deliberately leaves
     * `hidden` alone — see [banAndPurge], which relies on that.
     *
     * NOTE this is enough for the archive browser, whose list IS the pool, but NOT for the
     * main list, which is `live + kept` — a purged server still advertised by VPN Gate comes
     * straight back from `live`. Use [banAndPurge] there.
     *
     * @return how many entries were actually removed.
     */
    suspend fun purge(context: Context, hosts: Collection<String>): Int =
        withContext(Dispatchers.IO) {
            if (hosts.isEmpty()) return@withContext 0
            val doomed = hosts.toSet()
            val map = HashMap(_pool.value)
            val removed = doomed.count { map.remove(it) != null }

            _pool.value = map
            _kept.value = _kept.value - doomed
            prefs(context).edit().putStringSet(KEY_KEPT, _kept.value).apply()
            save(context, map)
            Log.d(TAG, "purged $removed dead servers, pool=${map.size}")
            removed
        }

    /**
     * Removes servers from the main list for good: archived copy deleted [purge] *and*
     * hostname added to the `hidden` deny-list.
     *
     * Both halves are needed and neither is sufficient alone. The main list is built as
     * `live + keptServers` minus `hidden`, where `live` is whatever the VPN Gate CSV is
     * advertising right now:
     *
     *   - purge() alone deletes the archive entry, but the server keeps rendering out of
     *     `live`, so nothing appears to happen. (That was the bug: the rows stayed and only
     *     their test results vanished, making dead servers look untested.)
     *   - hide() alone takes it off the screen but leaves it accumulating in pool.json,
     *     which is the accumulation this was supposed to stop.
     */
    suspend fun banAndPurge(context: Context, hosts: Collection<String>): Int {
        if (hosts.isEmpty()) return 0
        val doomed = hosts.toSet()
        // Hidden first: purge() intentionally does not touch `hidden`, but doing it in this
        // order also means a failure halfway through leaves them hidden rather than visible.
        _hidden.value = _hidden.value + doomed
        prefs(context).edit().putStringSet(KEY_HIDDEN, _hidden.value).apply()
        purge(context, doomed)
        // Count the hosts removed from the LIST, not the pool entries deleted. A server that
        // only ever came from `live` has no archive entry, so purge() reports 0 for it while
        // it is very much gone from the user's view.
        return doomed.size
    }

    fun clearHidden(context: Context) {
        _hidden.value = emptySet()
        prefs(context).edit().putStringSet(KEY_HIDDEN, emptySet()).apply()
    }

    private fun persistKept(context: Context) {
        prefs(context).edit().putStringSet(KEY_KEPT, _kept.value).apply()
    }

    private fun save(context: Context, map: Map<String, Entry>) {
        try {
            val arr = JSONArray()
            map.values.forEach { arr.put(toJson(it)) }
            val target = File(dir(context), POOL_FILE)
            val tmp = File(target.parentFile, "$POOL_FILE.tmp")
            tmp.writeText(arr.toString())
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "pool write failed", e)
        }
    }

    private fun toJson(e: Entry): JSONObject = JSONObject().apply {
        val s = e.server
        put("hostName", s.hostName)
        put("ip", s.ip)
        put("score", s.score)
        put("ping", s.ping)
        put("speedBps", s.speedBps)
        put("countryLong", s.countryLong)
        put("countryShort", s.countryShort)
        put("numSessions", s.numSessions)
        put("uptimeMs", s.uptimeMs)
        put("totalUsers", s.totalUsers)
        put("totalTraffic", s.totalTraffic)
        put("logType", s.logType)
        put("operator", s.operator)
        put("message", s.message)
        // configBase64 is deliberately NOT persisted.
        //
        // It is the full OpenVPN profile, ~13 KB per server, and connecting goes over
        // SoftEther now — which needs the address, not the profile. Storing it grew pool.json
        // to 14 MB at 1033 servers, and every merge rewrote the whole file: measured at 6.8
        // seconds on device. Everything the app actually reads from the profile (the OpenVPN
        // endpoint, for the latency probe) is already extracted into remoteHost/remotePort.
        put("remoteHost", s.remoteHost)
        put("remotePort", s.remotePort)
        put("lastSeen", e.lastSeen)
    }

    private fun fromJson(o: JSONObject): VpnGateServer? {
        val host = o.optString("hostName").takeIf { it.isNotBlank() } ?: return null
        // Legacy files still carry the profile; new ones don't, and its absence is fine.
        val cfg = o.optString("configBase64")
        return VpnGateServer(
            hostName = host,
            ip = o.optString("ip"),
            score = o.optLong("score"),
            ping = o.optInt("ping", -1),
            speedBps = o.optLong("speedBps"),
            countryLong = o.optString("countryLong"),
            countryShort = o.optString("countryShort", "XX"),
            numSessions = o.optInt("numSessions"),
            uptimeMs = o.optLong("uptimeMs"),
            totalUsers = o.optLong("totalUsers"),
            totalTraffic = o.optLong("totalTraffic"),
            logType = o.optString("logType"),
            operator = o.optString("operator"),
            message = o.optString("message"),
            configBase64 = cfg,
            remoteHost = o.optString("remoteHost").ifBlank { o.optString("ip") },
            remotePort = o.optInt("remotePort", 0),
        )
    }
}
