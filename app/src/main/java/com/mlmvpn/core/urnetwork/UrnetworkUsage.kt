package com.mlmvpn.core.urnetwork

import android.content.Context
import org.json.JSONObject

/** «URnetwork»'s own choices, kept where the tile's other settings are. */
object UrnetworkSettings {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("urnetwork", Context.MODE_PRIVATE)

    /** URnetwork's own ad and tracker blocker (the official apps' «Block ads & trackers»). Off by default. */
    fun blocker(ctx: Context): Boolean = prefs(ctx).getBoolean("blocker", false)
    fun setBlocker(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("blocker", on).apply()

    /** The chosen location as the core reads it (an sdk.ConnectLocation in JSON); "" = the best available. */
    fun location(ctx: Context): String = prefs(ctx).getString("location", "").orEmpty()
    fun locationName(ctx: Context): String? = prefs(ctx).getString("location_name", null)?.ifBlank { null }
    fun locationCode(ctx: Context): String? = prefs(ctx).getString("location_cc", null)?.ifBlank { null }

    fun setLocation(ctx: Context, json: String, name: String, code: String) =
        prefs(ctx).edit().putString("location", json).putString("location_name", name).putString("location_cc", code).apply()

    fun setBest(ctx: Context) = prefs(ctx).edit().remove("location").remove("location_name").remove("location_cc").apply()

    /** Whether MAE may use URnetwork as one of its exits abroad (see UrnetworkMae). On by default, as Lantern's is. */
    fun forMae(ctx: Context): Boolean = prefs(ctx).getBoolean("for_mae", true)
    fun setForMae(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("for_mae", on).apply()
}

/**
 * The account's data, as URnetwork's `/subscription/balance` answers it -- the numbers the official
 * apps' usage bar shows, never a figure of our own.
 *
 * The free plan is a daily allowance: URnetwork's server grants it at 00:00 UTC to every account
 * without Pro, valid until an hour past the end of that day (its RefreshFreeTransferBalances and
 * FreeGrantWindow); Pro is granted monthly. So, as in the official app's «About your data»:
 *
 *  - [total]: what the account's balances started with -- today's free allowance, plus anything
 *    bought -- as the server says it (`start_balance_byte_count`), never an amount of our own;
 *  - [remaining]: what is still free to use;
 *  - [pending]: tied up in transfers still open, settled shortly either way;
 *  - [used]: total - remaining - pending, never below zero (the SDK's own formula);
 *  - [resetAt]: when the balance in use ends -- the soonest end among the active free balances,
 *    else among all of them (for Pro, the monthly grant's end);
 *  - [refreshAt]: when new data comes -- the next 00:00 UTC on the free plan, [resetAt] on Pro.
 */
data class UrnetworkUsage(
    val total: Long,
    val remaining: Long,
    val pending: Long,
    val used: Long,
    val pro: Boolean,
    val periodStart: Long?,
    val resetAt: Long?,
    val fetchedAt: Long,
) {
    val fraction: Float get() = if (total <= 0L) 0f else (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    /** Whole days until [resetAt], rounded up: "3 days" while there is any part of a third day left. */
    fun daysLeft(now: Long = System.currentTimeMillis()): Int? = resetAt?.let { end ->
        val left = end - now
        if (left <= 0) 0 else ((left + DAY - 1) / DAY).toInt()
    }

    /** When new data comes: the free plan's next daily grant, or the end of Pro's month. */
    fun refreshAt(now: Long = System.currentTimeMillis()): Long? = if (pro) resetAt else nextFreeRefresh(now)

    companion object {
        private const val DAY = 86_400_000L
        private const val MINUTE = 60_000L

        /** The first 00:00 UTC after [now], when the server grants the free daily data (epoch days are whole). */
        fun nextFreeRefresh(now: Long): Long = Math.floorDiv(now, DAY) * DAY + DAY

        /** Hours and minutes until [at], minutes rounded up so it never reads zero while still ahead. */
        fun countdown(now: Long, at: Long): Pair<Long, Long> {
            val minutes = ((at - now).coerceAtLeast(0L) + MINUTE - 1) / MINUTE
            return minutes / 60 to minutes % 60
        }

        fun parse(json: JSONObject, fetchedAt: Long): UrnetworkUsage {
            val start = json.optLong("start_balance_byte_count")
            val available = json.optLong("balance_byte_count")
            val pending = json.optLong("open_transfer_byte_count")
            val pro = json.opt("current_subscription").let { it != null && it != JSONObject.NULL }
            // The SDK binds lists as {"values": [...]} for gomobile; plain arrays are read too.
            val raw = json.opt("active_transfer_balances")
            val list = when (raw) {
                is org.json.JSONArray -> raw
                is JSONObject -> raw.optJSONArray("values") ?: raw.optJSONArray("list")
                else -> null
            }
            data class B(val start: Long?, val end: Long?, val paid: Boolean)
            val balances = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                list?.optJSONObject(i)?.let { b -> B(time(b.optString("start_time")), time(b.optString("end_time")), b.optBoolean("paid")) }
            }
            val now = System.currentTimeMillis()
            val live = balances.filter { (it.end ?: Long.MAX_VALUE) > now }
            val free = live.filter { !it.paid }
            val pick = (free.ifEmpty { live }).filter { it.end != null }.minByOrNull { it.end!! }
            return UrnetworkUsage(
                total = start,
                remaining = available,
                pending = pending,
                used = (start - available - pending).coerceAtLeast(0L),
                pro = pro,
                periodStart = pick?.start,
                resetAt = pick?.end,
                fetchedAt = fetchedAt,
            )
        }

        private val TIME = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(\.\d+)?\s*(Z|UTC|[+-]\d{2}:?\d{2})?""")

        /** A server time, RFC 3339 or Go's default form, to epoch milliseconds; null when unreadable. */
        fun time(text: String?): Long? {
            val m = TIME.find(text?.trim().orEmpty()) ?: return null
            val g = m.groupValues
            return runCatching {
                val base = java.time.LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
                val zone = g[8].let { z ->
                    if (z.isEmpty() || z == "Z" || z == "UTC") java.time.ZoneOffset.UTC
                    else java.time.ZoneOffset.of(if (z.contains(':')) z else z.substring(0, 3) + ":" + z.substring(3))
                }
                val nanos = g[7].removePrefix(".").padEnd(9, '0').take(9).ifEmpty { "0" }.toLong()
                base.toInstant(zone).toEpochMilli() + nanos / 1_000_000
            }.getOrNull()
        }
    }
}
