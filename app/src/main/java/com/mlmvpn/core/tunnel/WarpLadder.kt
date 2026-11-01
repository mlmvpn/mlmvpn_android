package com.mlmvpn.core.tunnel

import android.content.Context

/**
 * The three WARP tiles («ماسک», «وایرگارد», «وارپ در وارپ») as one family, per network.
 *
 * A filter closes them separately. On an Irancell TD-LTE line (2026-10-07) MASQUE got no gateway in
 * any form -- HTTP/3, HTTP/2 with fragments, PattNG's semi-python + tlshello, IPv4 and IPv6 -- while
 * WireGuard and WARP-in-WARP carried over IPv6 within seconds; on lines that drop all UDP it is the
 * other way round. A tile that keeps scanning for a transport the line has closed looks broken, and
 * the member that gets through is the same Cloudflare network with the same exit. So a tile that
 * does not come up within [budgetMs] hands the connect to the next member, and the network
 * remembers what it closed: the next tap there goes straight to what works, and the closed one is
 * tried again after [BLOCK_TTL_MS].
 *
 * Per network by the hashed key the Cloudflare engine uses (never an SSID). A hand-entered endpoint
 * is the user's own pin and stops the ladder; an exit COUNTRY does not any more (as on the desktop,
 * 2026-10-14): the member that takes over reads the same choice -- its own NAT64 gateway of that
 * country, or the same free proxy after it -- so «ماسک» handing over to «وایرگارد» keeps the country.
 */
object WarpLadder {
    private const val PREFS = "warp_ladder"

    /** How long a closed transport is skipped on the network that closed it. */
    const val BLOCK_TTL_MS = 12 * 3600_000L

    val MEMBERS = setOf("masque", "wireguard", "gool")

    /**
     * From the core's start to a usable tunnel, per transport. MASQUE: 30 s, the user's own number
     * (desktop, 2026-10-14: «وقتی بعد از ۳۰ ثانیه فهمید وصل نمی‌شود، وایرگارد بجاش وصل شود») -- a
     * MASQUE that has already reached a gateway by then gets [stageGraceMs] more, so a slow-but-working
     * one is not thrown away. WireGuard's turbo pass is 30 s per obfuscation profile, and two fit;
     * WARP-in-WARP adds its inner hop's validation.
     */
    fun budgetMs(protocol: String): Long = when (protocol) {
        "masque" -> 30_000L
        "gool" -> 80_000L
        else -> 70_000L
    }

    /** Extra time for a member whose core already reached a gateway (MASQUE: "Accepted … via HTTP/n"). */
    fun stageGraceMs(protocol: String): Long = if (protocol == "masque") 60_000L else 0L

    /**
     * Who takes over when [protocol] is closed. WireGuard and WARP-in-WARP ride the same UDP, so
     * where WireGuard is closed only MASQUE (TCP or QUIC) is left; where WARP-in-WARP's inner hop
     * fails, plain WireGuard may still carry.
     */
    fun successors(protocol: String): List<String> = when (protocol) {
        "masque" -> listOf("wireguard")
        "wireguard" -> listOf("masque")
        "gool" -> listOf("wireguard", "masque")
        else -> emptyList()
    }

    /**
     * The network, and whether it has IPv6 at the moment: what WARP lets through depends on the
     * family (the same router closed WARP over IPv4 and carried it over IPv6), so a line that loses
     * or gains IPv6 starts with a clean slate.
     */
    private fun network(context: Context): String = runCatching {
        val p = com.mlmvpn.scanner.engines.cfnet.CfNetEnv.profile(context)
        "${p.key}|${if (p.hasV6 && !com.mlmvpn.scanner.data.CfFamily.testNoV6) "v6" else "v4"}"
    }.getOrDefault("unknown")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When [protocol] was last found closed on this network, if that is still fresh. */
    fun blockedSince(context: Context, protocol: String, now: Long = System.currentTimeMillis()): Long? =
        prefs(context).getLong("${network(context)}|$protocol", 0L).takeIf { it > 0 && now - it < BLOCK_TTL_MS }

    fun markBlocked(context: Context, protocol: String) {
        prefs(context).edit().putLong("${network(context)}|$protocol", System.currentTimeMillis()).apply()
    }

    /** [protocol] carried data here: nothing about it is skipped on this network any more. */
    fun markWorks(context: Context, protocol: String) {
        prefs(context).edit().remove("${network(context)}|$protocol").apply()
    }

    /**
     * The member to start for a tap on [requested]: itself, unless this network closed it recently
     * and a successor is not known to be closed too. [tried] are the members this connect has
     * already used up.
     */
    fun pick(context: Context, requested: String, tried: Set<String> = emptySet()): String {
        if (requested !in MEMBERS || blockedSince(context, requested) == null) return requested
        return successors(requested).firstOrNull { it !in tried && blockedSince(context, it) == null } ?: requested
    }

    /** How long "every member was closed here" is answered at once, without scanning again. */
    const val FAIL_FAST_MS = 20 * 60_000L

    /**
     * Whether [protocol] and every member that could take over were all found closed on this network
     * within [FAIL_FAST_MS]: then a tap is answered at once instead of with three minutes of scans
     * that just ended the same way.
     */
    fun allClosedRecently(context: Context, protocol: String, now: Long = System.currentTimeMillis()): Boolean =
        protocol in MEMBERS && (listOf(protocol) + successors(protocol)).all { member ->
            blockedSince(context, member, now)?.let { now - it < FAIL_FAST_MS } == true
        }

    /** The member to try after [failed] in this connect, or null when the family is used up. */
    fun next(context: Context, failed: String, tried: Set<String>): String? =
        successors(failed).firstOrNull { it !in tried && blockedSince(context, it) == null }
            ?: successors(failed).firstOrNull { it !in tried }

    /** The protocol a core config was built for. */
    fun protocolOf(config: String): String =
        runCatching { org.json.JSONObject(config).optString("protocol") }.getOrDefault("").lowercase()
}
