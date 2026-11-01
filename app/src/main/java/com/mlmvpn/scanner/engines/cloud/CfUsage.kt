package com.mlmvpn.scanner.engines.cloud

import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Worker requests per day, per account, for the Cloud tab's usage chart.
 *
 * The same figure the per-account quota row has always shown -- `workersInvocationsAdaptive`
 * requests for the whole account -- but for the last [DAYS] UTC days rather than only today,
 * bucketed by hour on the server and summed per UTC day here. UTC because that is when the free
 * plan's 100,000 a day starts over. An account whose token cannot read analytics gets `failed`,
 * and the chart says so for that account instead of drawing a zero.
 */
object CfUsage {

    const val DAYS = 7
    const val FREE_DAILY = 100_000L

    data class AccountUsage(
        val accountId: String,
        /** Saturday first, [DAYS] entries: this Iranian week in Tehran time (see [dayKeys]). */
        val days: List<Long>,
        val failed: String? = null,
    ) {
        val today: Long get() = days.getOrNull(todayIndex()) ?: 0L
    }

    data class Snapshot(val at: Long, val dayKeys: List<String>, val accounts: Map<String, AccountUsage>)

    private val _state = MutableStateFlow<Snapshot?>(null)
    val state: StateFlow<Snapshot?> = _state
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    // Same Cloudflare route as the deployers: a proven edge, ClientHello split past the name filter.
    private val http = OkHttpClient.Builder().dns(WorkerRoute.dns()).socketFactory(WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector())
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    /**
     * Why a Worker failed, from Cloudflare itself: requests per (script, status) over the last
     * [hours] -- `success`, `exceededResources` (the CPU limit), `scriptThrewException`,
     * `clientDisconnected`, `internalError`... Plus subrequests and p99 CPU per script. The
     * diagnosis the 2026-09-28 outage needed: every panel's WebSocket opened and then relayed
     * nothing, and only Cloudflare knows whether it killed the Worker or the Worker failed.
     */
    data class Failure(val script: String, val status: String, val requests: Long, val errors: Long, val subrequests: Long, val cpuP99: Double)

    fun failures(account: CloudAccount, hours: Int = 6): List<Failure> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        val q = "query F(\$a: String!, \$s: String!, \$e: String!) { viewer { accounts(filter: {accountTag: \$a}) { " +
            "workersInvocationsAdaptive(limit: 1000, filter: {datetime_geq: \$s, datetime_leq: \$e}) { sum { requests errors subrequests } quantiles { cpuTimeP99 } dimensions { scriptName status } } } } }"
        val body = JSONObject().put("query", q).put("variables", JSONObject().put("a", account.accountId)
            .put("s", iso.format(java.util.Date(now - hours * 3_600_000L))).put("e", iso.format(java.util.Date(now)))).toString()
        return runCatching {
            http.newCall(Request.Builder().url("https://api.cloudflare.com/client/v4/graphql").headers(CloudAuth.headers(account))
                .post(body.toRequestBody(JSON)).build()).execute().use { r ->
                val json = JSONObject(r.body?.string().orEmpty())
                json.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { android.util.Log.w("CfErrors", it.toString().take(300)) }
                val rows = json.optJSONObject("data")?.optJSONObject("viewer")?.optJSONArray("accounts")?.optJSONObject(0)
                    ?.optJSONArray("workersInvocationsAdaptive") ?: return@use emptyList()
                (0 until rows.length()).map { i ->
                    val o = rows.getJSONObject(i)
                    val d = o.optJSONObject("dimensions"); val sum = o.optJSONObject("sum")
                    Failure(d?.optString("scriptName").orEmpty(), d?.optString("status").orEmpty(),
                        sum?.optLong("requests") ?: 0, sum?.optLong("errors") ?: 0, sum?.optLong("subrequests") ?: 0,
                        o.optJSONObject("quantiles")?.optDouble("cpuTimeP99") ?: 0.0)
                }.sortedByDescending { it.requests }
            }
        }.getOrElse { android.util.Log.w("CfErrors", "query failed", it); emptyList() }
    }

    /** Requests per UTC hour and status over the last day, oldest first. */
    fun hourly(account: CloudAccount): List<Pair<String, Map<String, Long>>> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        val q = "query H(\$a: String!, \$s: String!, \$e: String!) { viewer { accounts(filter: {accountTag: \$a}) { " +
            "workersInvocationsAdaptive(limit: 2000, filter: {datetime_geq: \$s, datetime_leq: \$e}) { sum { requests } dimensions { datetimeHour status } } } } }"
        val body = JSONObject().put("query", q).put("variables", JSONObject().put("a", account.accountId)
            .put("s", iso.format(java.util.Date(now - 24 * 3_600_000L))).put("e", iso.format(java.util.Date(now)))).toString()
        return runCatching {
            http.newCall(Request.Builder().url("https://api.cloudflare.com/client/v4/graphql").headers(CloudAuth.headers(account))
                .post(body.toRequestBody(JSON)).build()).execute().use { r ->
                val rows = JSONObject(r.body?.string().orEmpty()).optJSONObject("data")?.optJSONObject("viewer")
                    ?.optJSONArray("accounts")?.optJSONObject(0)?.optJSONArray("workersInvocationsAdaptive") ?: return@use emptyList()
                val m = sortedMapOf<String, MutableMap<String, Long>>()
                for (i in 0 until rows.length()) {
                    val o = rows.getJSONObject(i); val d = o.optJSONObject("dimensions")
                    val h = d?.optString("datetimeHour").orEmpty().take(13)
                    m.getOrPut(h) { mutableMapOf() }.merge(d?.optString("status").orEmpty(), o.optJSONObject("sum")?.optLong("requests") ?: 0, Long::plus)
                }
                m.entries.map { it.key to it.value.toMap() }
            }
        }.getOrElse { emptyList() }
    }

    /** Iran's time: the chart's days and week are Tehran's, as the user reads them. */
    val TEHRAN: TimeZone = TimeZone.getTimeZone("Asia/Tehran")

    /**
     * This Iranian week's day keys, Saturday first (Iran's week), in Tehran time -- `yyyy-MM-dd`.
     * Days after today are included and simply have no requests yet, as a week view shows them.
     */
    fun dayKeys(now: Long = System.currentTimeMillis()): List<String> {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TEHRAN }
        val cal = Calendar.getInstance(TEHRAN).apply { timeInMillis = now }
        cal.add(Calendar.DAY_OF_YEAR, -todayIndex(now))
        return (0 until DAYS).map { i ->
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, i)
            f.format(c.time)
        }
    }

    /** Index of today in [dayKeys]: days since Saturday, in Tehran. */
    fun todayIndex(now: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance(TEHRAN).apply { timeInMillis = now }
        return (cal.get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + 7) % 7
    }

    /** Refreshes every account at once; a fresh snapshot (under [maxAgeMs]) is kept unless forced. */
    suspend fun refresh(accounts: List<CloudAccount>, force: Boolean = false, maxAgeMs: Long = 5 * 60_000L) {
        val cur = _state.value
        val ids = accounts.map { it.id }.toSet()
        if (!force && cur != null && System.currentTimeMillis() - cur.at < maxAgeMs && cur.accounts.keys == ids) return
        if (accounts.isEmpty()) { _state.value = null; return }
        _loading.value = true
        try {
            val keys = dayKeys()
            val rows = coroutineScope { accounts.map { a -> async(Dispatchers.IO) { a.id to fetch(a, keys) } }.awaitAll() }
            // The failure breakdown goes to the log on every refresh (tag CfErrors) -- the evidence a
            // "nothing connects" report needs, without anyone having to ask for it.
            for (a in accounts) Thread({
                // Hour by hour, success vs loadShed vs the rest: WHEN a failure started is half the
                // diagnosis (a network change, a deploy, or Cloudflare's own decision).
                hourly(a).forEach { (h, byStatus) -> android.util.Log.i("CfErrors", "hour $h " + byStatus.entries.joinToString(" ") { "${it.key}=${it.value}" }) }
                failures(a).filter { it.status != "success" || it.errors > 0 }.take(40).forEach { f ->
                    android.util.Log.i("CfErrors", "${f.script} ${f.status} req=${f.requests} err=${f.errors} sub=${f.subrequests} cpuP99=${f.cpuP99}")
                }
            }, "cf-errors").start()
            _state.value = Snapshot(System.currentTimeMillis(), keys, rows.toMap())
        } finally {
            _loading.value = false
        }
    }

    private suspend fun fetch(account: CloudAccount, keys: List<String>): AccountUsage = withContext(Dispatchers.IO) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        // Saturday 00:00 in Tehran, as UTC.
        val tehranDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TEHRAN }
        val start = iso.format(tehranDay.parse(keys.first())!!)
        val end = iso.format(java.util.Date())
        val q = "query U(\$a: String!, \$s: String!, \$e: String!) { viewer { accounts(filter: {accountTag: \$a}) { " +
            "workersInvocationsAdaptive(limit: 10000, filter: {datetime_geq: \$s, datetime_leq: \$e}) { sum { requests } dimensions { datetimeHour } } } } }"
        val body = JSONObject().put("query", q)
            .put("variables", JSONObject().put("a", account.accountId).put("s", start).put("e", end)).toString()
        runCatching {
            http.newCall(Request.Builder().url("https://api.cloudflare.com/client/v4/graphql")
                .headers(CloudAuth.headers(account)).post(body.toRequestBody(JSON)).build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) return@use AccountUsage(account.id, List(keys.size) { 0L }, "HTTP ${r.code}")
                val json = JSONObject(text)
                json.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let {
                    return@use AccountUsage(account.id, List(keys.size) { 0L }, it.optJSONObject(0)?.optString("message") ?: "GraphQL")
                }
                val perDay = LongArray(keys.size)
                val rows = json.optJSONObject("data")?.optJSONObject("viewer")?.optJSONArray("accounts")
                    ?.optJSONObject(0)?.optJSONArray("workersInvocationsAdaptive")
                if (rows != null) for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val hour = row.optJSONObject("dimensions")?.optString("datetimeHour").orEmpty()
                    // The UTC hour read as Tehran's day (+3:30), so the bars follow the user's calendar.
                    val at = runCatching { iso.parse(hour.take(19) + "Z")!!.time }.getOrNull()
                    val idx = if (at == null) -1 else keys.indexOf(tehranDay.format(java.util.Date(at + 30 * 60_000L)))
                    if (idx >= 0) perDay[idx] += row.optJSONObject("sum")?.optLong("requests") ?: 0L
                }
                AccountUsage(account.id, perDay.toList())
            }
        }.getOrElse { AccountUsage(account.id, List(keys.size) { 0L }, it.message ?: "network") }
    }
}
