package com.mlmvpn.scanner.engines.outline

/**
 * One Outline access key: a Shadowsocks server. Outline's own keys and the public lists' `ss://`
 * links are the same thing, so both live here as one row.
 *
 * [uri] is the canonical SIP002 link the tunnel is started with (method and password in base64,
 * host plain, no query): what the core needs and nothing it would refuse. [prefix] records that the
 * original key carried Outline's salt prefix -- a client-side disguise the server does not need, so
 * the key still works without it.
 */
data class OutlineServer(
    val id: String,
    val uri: String,
    val name: String,
    val host: String,
    val port: Int,
    val method: String,
    val country: String? = null,
    val sourceId: String,
    val addedAt: Long,
    val prefix: Boolean = false,
    /** Fetched from the user's own `ssconf://` key: re-fetched when the server behind it changes. */
    val ssconf: String? = null,
    /**
     * When a list last carried this server (0 = never recorded: [addedAt] stands in). A public server
     * its list stopped carrying goes after [OutlineBrain.STALE_MS] -- the pool has no size limit, and
     * lists rebuilt every hour would otherwise pile up servers that no longer exist anywhere.
     */
    val seenAt: Long = 0,
    /**
     * When the list's own checker last found this server working -- Shadowmere re-checks every key
     * at the top of each hour; 0 when the list does not say.
     */
    val checkedAt: Long = 0,
    /** The share of the list's own checks it passed, in percent; null when the list does not say. */
    val uptime: Int? = null,
) {
    val lastSeen: Long get() = if (seenAt > 0) seenAt else addedAt

    val user: Boolean get() = sourceId == USER_SOURCE

    /** From shadowmere.xyz: tagged «shadowmere» in its name and on its row. */
    val shadowmere: Boolean get() = sourceId == OutlineSources.SHADOWMERE

    override fun toString() = "$name ($host:$port $method)"

    companion object {
        const val USER_SOURCE = "user"
    }
}

/** What a test learned about one server on one network. */
data class OutlineStat(
    /** The last real delay, or -1 when the last test failed. */
    val lastMs: Long = -1,
    val lastTestAt: Long = 0,
    /** Failures in a row; a working answer resets it. */
    val fails: Int = 0,
    /** Bits per second of the last speed sample, when one was taken. */
    val speedBps: Long? = null,
    /** It worked here only from inside Cloudflare WARP ([OutlineCf]): connect goes that way. */
    val viaCf: Boolean = false,
) {
    val tested: Boolean get() = lastTestAt > 0
    val alive: Boolean get() = tested && lastMs >= 0
}

data class OutlinePrefs(
    /** On a connect that carries nothing, move on to the next fastest (up to three servers). */
    val autoFallback: Boolean = true,
    /** Sources the user switched off, by id. */
    val disabledSources: Set<String> = emptySet(),
    /** The one-time "these are public servers" note has been read. */
    val warned: Boolean = false,
)

data class OutlineState(
    val servers: List<OutlineServer> = emptyList(),
    /** network key -> server id -> result. Delays on Wi-Fi say nothing about mobile data. */
    val stats: Map<String, Map<String, OutlineStat>> = emptyMap(),
    /** Deleted ids and when: a list refresh must not bring them back. */
    val tombstones: Map<String, Long> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val lastRefreshAt: Long = 0,
    val lastConnectedId: String? = null,
    /** The server MLM Scout last proved on each network. */
    val scoutWinners: Map<String, String> = emptyMap(),
    val prefs: OutlinePrefs = OutlinePrefs(),
) {
    fun statsFor(net: String): Map<String, OutlineStat> = stats[net] ?: emptyMap()
}

sealed class OutlineConnect {
    object Idle : OutlineConnect()
    data class Connecting(val serverId: String, val step: Step, val attempt: Int) : OutlineConnect()
    data class Connected(val serverId: String, val country: String?, val delayMs: Long?, val since: Long, val exitIp: String? = null) : OutlineConnect()
    data class Failed(val serverId: String?, val reason: Reason) : OutlineConnect()

    /** [WARP]: the app's own WARP engine is coming up for a connect from inside WARP (up to a minute). */
    enum class Step { PREPARING, WARP, STARTING, VERIFYING }
    enum class Reason { OFFLINE, NO_SERVERS, NO_DATA, BAD_CONFIG, VPN_REFUSED }
}

/**
 * A test in two passes, each counted on its own so the bar always moves one server at a time:
 * [Phase.DIRECT] decides every server straight from this line; [Phase.WARP] tries the ones that
 * gave nothing straight again from inside Cloudflare WARP. [done] and [total] belong to the pass
 * that is running; [alive] adds up both.
 */
data class OutlineTestProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val alive: Int = 0,
    val phase: Phase = Phase.DIRECT,
    /** During [Phase.DIRECT]: servers that gave nothing straight and wait for the WARP pass. */
    val warpQueued: Int = 0,
    /** When the pass that is running began, for the elapsed time beside the bar. */
    val phaseStartedAt: Long = 0,
    /** The WARP pass is waiting for the app's WARP engine to come up (up to a minute on a filtered line). */
    val warpStarting: Boolean = false,
    /** No way into WARP on this line: the servers that needed it stay as the first pass found them. */
    val warpUnreachable: Boolean = false,
) {
    enum class Phase { DIRECT, WARP }
}
