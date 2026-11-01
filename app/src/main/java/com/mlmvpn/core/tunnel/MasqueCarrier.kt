package com.mlmvpn.core.tunnel

import android.content.Context

/**
 * «ماسک»'s carrier when its transport is «خودکار»: what connected on this network last time.
 *
 * The core tries HTTP/3 first (port 443, then the alternate UDP ports) and falls back to HTTP/2
 * with a fragmented ClientHello by itself -- but only when it is started on HTTP/3; started on
 * HTTP/2 it never tries HTTP/3. So the app starts on HTTP/3 unless this network is known to need
 * HTTP/2, in which case it starts there and skips the HTTP/3 timeouts (the user's request,
 * 2026-10-04: test once, remember, connect straight away next time).
 *
 * Learned from the core's own log line ("Accepted <peer> via HTTP/2"), so it records the carrier
 * that really carried the session, not the one that was asked for. Forgotten when a session that
 * started from it fails, and the next connect tries from HTTP/3 again. Per network (the hashed key
 * the Cloudflare engine uses -- never an SSID).
 */
object MasqueCarrier {
    const val AUTO = "auto"
    private const val PREFS = "masque_carrier"
    private val ACCEPTED = Regex("""Accepted \S+ via HTTP/([23])""")

    private fun network(context: Context): String =
        runCatching { com.mlmvpn.scanner.engines.cfnet.CfNetEnv.profile(context).key }.getOrDefault("unknown")

    /** The transport the core is started on for «خودکار»: "h2" where that is what worked, else "h3". */
    fun startOn(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(network(context), null)
            ?.takeIf { it == "h2" || it == "h3" } ?: "h3"

    /** The core reached a MASQUE gateway and was accepted on it: the session is close, not closed. */
    fun isAccepted(line: String): Boolean = ACCEPTED.containsMatchIn(line)

    /** Feed every core log line of a MASQUE session; remembers the carrier it was accepted on. */
    fun onCoreLog(context: Context, line: String) {
        val v = ACCEPTED.find(line)?.groupValues?.get(1) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(network(context), "h$v").apply()
    }

    /** A MASQUE session failed: what this network remembered is tested again next time. */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(network(context)).apply()
    }
}
