package com.mlmvpn.scanner.engines.amnezia

/**
 * The «آمنزیا» section's data: WireGuard (with AmneziaWG obfuscation) and Hysteria2 servers from
 * public lists and from the user's own files, each tested for a real delay and filed under the
 * country it really exits in.
 *
 * Pure Kotlin on purpose (no android.*), so the parser, the scoring and the codec run in the JVM
 * tests exactly as they run on a phone.
 */
enum class AmzKind(val code: String) {
    /** Hysteria2, run on the app's Xray core. */
    HY2("h"),
    /** Plain WireGuard. Connected through AmneziaWG with junk packets the server never notices. */
    WG("w"),
    /** A config that already carries AmneziaWG settings of its own. */
    AWG("a");

    companion object {
        fun of(code: String?): AmzKind = values().firstOrNull { it.code == code } ?: WG
    }
}

/**
 * Where a server's country came from, in rising order of trust. A later, better answer replaces
 * an earlier one; never the other way round.
 */
enum class CountryFrom(val code: String, val rank: Int) {
    NONE("n", 0),
    /** The link's own name ("🇩🇪 Frankfurt"). What the publisher wrote, not proof. */
    LABEL("l", 1),
    /** A per-country list the source publishes. */
    SOURCE("s", 2),
    /** Asked through the server during a delay test (Cloudflare's trace). */
    PROBE("p", 3),
    /** Asked through the running tunnel after connecting. */
    EXIT("e", 4);

    companion object {
        fun of(code: String?): CountryFrom = values().firstOrNull { it.code == code } ?: NONE
    }
}

data class AmzServer(
    /** Stable across refreshes: the same server for the same account is the same id. */
    val id: String,
    val kind: AmzKind,
    /** A sanitised `hysteria2://` link, or a WireGuard config in canonical `.conf` form. */
    val raw: String,
    val name: String,
    val host: String,
    val port: Int,
    /** ISO code, or null for «نامشخص». */
    val country: String?,
    val countryFrom: CountryFrom,
    val sourceId: String,
    val addedAt: Long,
    /** A Cloudflare WARP account (all share one peer key); capped so they do not drown the rest. */
    val warp: Boolean = false,
    /** Only the AmneziaWG core can talk to it (custom S/H, or I-packets Xray cannot imitate). */
    val needsAwgCore: Boolean = false,
) {
    val user: Boolean get() = sourceId == USER_SOURCE

    /** A better-sourced country replaces this one; a weaker one does not. */
    fun withCountry(cc: String?, from: CountryFrom): AmzServer =
        if (cc == null || from.rank < countryFrom.rank || (from.rank == countryFrom.rank && cc == country)) this
        else copy(country = cc, countryFrom = from)

    override fun toString(): String = "${kind.name.lowercase()} ${id.take(8)} ${country ?: "??"}"

    companion object {
        const val USER_SOURCE = "user"
    }
}

/** One server on one network. Results are kept per network: Irancell and MCI are different worlds. */
data class AmzStat(
    val ok: Int = 0,
    val fail: Int = 0,
    /** Failed tests in a row. Three of them and the server is gone. */
    val streak: Int = 0,
    /** Smoothed delay (ms), 0 before the first success. */
    val ewmaMs: Double = 0.0,
    /** The last test's delay, or -1 when the last test failed. */
    val lastMs: Long = -1,
    val lastOkAt: Long = 0,
    val lastTestAt: Long = 0,
) {
    val tested: Boolean get() = lastTestAt > 0
    val alive: Boolean get() = tested && lastMs >= 0
}

/** How useful a source's servers have been, so a better list is tested first next time. */
data class AmzSourceStat(val tested: Int = 0, val alive: Int = 0) {
    /** Laplace-smoothed share of tested servers that worked. */
    val quality: Double get() = (alive + 1.0) / (tested + 2.0)
}

data class AmzPrefs(
    /** Junk packets in front of a plain WireGuard handshake. */
    val obfuscate: Boolean = true,
    /** Drop a server after three failed tests in a row. */
    val autoRemoveDead: Boolean = true,
    /** Try the next best server of the same country when one fails to connect. */
    val autoFallback: Boolean = true,
)

data class AmzState(
    val servers: List<AmzServer> = emptyList(),
    /** network key -> server id -> stat. */
    val stats: Map<String, Map<String, AmzStat>> = emptyMap(),
    /** network key -> when it was last seen, so old networks can be dropped. */
    val netSeen: Map<String, Long> = emptyMap(),
    val sources: Map<String, AmzSourceStat> = emptyMap(),
    /** Removed server ids -> when, so a refresh does not bring a dead one straight back. */
    val tombstones: Map<String, Long> = emptyMap(),
    val prefs: AmzPrefs = AmzPrefs(),
    val lastRefreshAt: Long = 0,
    /** The server the user last connected to. */
    val lastConnectedId: String? = null,
) {
    fun statsFor(net: String): Map<String, AmzStat> = stats[net] ?: emptyMap()
}

/** What the screen draws: everything here is immutable, so rows only recompose when they change. */
sealed class AmzConnect {
    object Idle : AmzConnect()
    /** [step] is what is happening right now; [serverId] the server being tried. */
    data class Connecting(val serverId: String, val step: Step, val attempt: Int = 1) : AmzConnect()
    data class Connected(val serverId: String, val country: String?, val delayMs: Long?, val since: Long) : AmzConnect()
    data class Failed(val serverId: String?, val reason: Reason) : AmzConnect()

    enum class Step { PREPARING, STARTING, HANDSHAKE, VERIFYING }
    enum class Reason { NO_DATA, NO_HANDSHAKE, VPN_REFUSED, BAD_CONFIG, OFFLINE }
}

enum class AmzTestMode { BEST, ALL, COUNTRY, ONE }

data class AmzTestProgress(
    val running: Boolean = false,
    val mode: AmzTestMode = AmzTestMode.BEST,
    val done: Int = 0,
    val total: Int = 0,
    val alive: Int = 0,
)
