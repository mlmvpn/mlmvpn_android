package com.mlmvpn.scanner.engines.game.booster.doctor

import android.net.Network
import android.util.Log
import com.mlmvpn.scanner.engines.game.DnsRaceTester
import com.mlmvpn.scanner.engines.game.booster.model.TrafficClass
import com.mlmvpn.scanner.engines.game.booster.probe.resolveV4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks each part of a game's traffic from this line and says what, if anything, is in its way:
 * nothing, a lying resolver, a filter on the name or the address, or the host refusing Iran.
 *
 * All hosts are checked at once and each is bounded, so a doctor visit costs one to two seconds
 * on a healthy line and never more than [HOST_BUDGET_MS]. The match itself is never checked --
 * match servers answer no probe -- so a verdict here is about signing in, the game's services,
 * downloads and the store, and the card says so.
 *
 * Results are kept per network and game for [TTL_MS]: which of a game's hosts are filtered or
 * sanctioned on a line changes on the scale of days, not boosts.
 */
object GameDoctor {

    private const val TAG = "GameDoctor"
    const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_HOSTS = 8
    private const val HOST_BUDGET_MS = 8_000L

    data class Report(val hosts: List<HostCheck>, val at: Long = System.currentTimeMillis()) {
        val kind: GameKind? get() = KindClassifier.classify(hosts)
        val perClass: Map<TrafficClass, Obstacle> get() = KindClassifier.perClass(hosts)

        /** Poisoned hosts whose clean answers served: pin them and the DNS is fixed. */
        fun pins(): Map<String, List<String>> = hosts
            .filter { it.obstacle == Obstacle.DNS_POISONED && it.cleanIps.isNotEmpty() }
            .associate { it.host to it.cleanIps }

        /** Hosts that refuse this line's country: the anti-sanction DNS's job. */
        fun geoBlocked(): List<HostCheck> = hosts.filter { it.obstacle == Obstacle.GEO_BLOCKED }

        /** Hosts filtered on their name that a fragmented ClientHello opens: host → the address that works. */
        fun fragmentable(): Map<String, String> = hosts
            .filter { it.obstacle == Obstacle.SNI_BLOCKED && it.fragmentIp != null }
            .associate { it.host to it.fragmentIp!! }

        /**
         * True when the game cannot start on this line whatever the DNS: every checked sign-in /
         * game-service host is filtered on its name or address, and fragmenting does not open all
         * of them. Only then may a tunnel be forced.
         */
        val coreNeedsTunnel: Boolean
            get() {
                val core = hosts.filter { it.cls.isCore && it.obstacle != Obstacle.UNKNOWN }
                val filtered = core.isNotEmpty() && core.all { it.obstacle == Obstacle.SNI_BLOCKED || it.obstacle == Obstacle.IP_BLOCKED }
                return filtered && !core.all { it.fragmentIp != null }
            }

        val coreGeoBlocked: Boolean get() = hosts.any { it.cls.isCore && it.obstacle == Obstacle.GEO_BLOCKED }

        /** Nothing in the way of any checked host. */
        val allOk: Boolean get() = hosts.none { it.obstacle != Obstacle.OK && it.obstacle != Obstacle.UNKNOWN }

        companion object {
            val EMPTY = Report(emptyList(), 0L)
        }
    }

    private val cache = ConcurrentHashMap<String, Report>()

    fun cached(networkKey: String, gameId: String): Report? {
        if (networkKey.isEmpty()) return null
        val r = cache["$networkKey|$gameId"] ?: return null
        return r.takeIf { System.currentTimeMillis() - it.at in 0..TTL_MS }
    }

    fun remember(networkKey: String, gameId: String, report: Report) {
        if (networkKey.isEmpty() || report.hosts.isEmpty()) return
        cache["$networkKey|$gameId"] = report
    }

    fun forget() = cache.clear()

    /**
     * Check every host of [classes] (up to [MAX_HOSTS], sign-in first) from the app's own uid --
     * which sits outside this app's own VPN, so it sees the real line -- pinned to [network].
     */
    suspend fun examine(classes: Map<TrafficClass, List<String>>, network: Network?): Report = coroutineScope {
        val jobs = classes.entries
            .sortedBy { it.key.ordinal }
            .flatMap { (cls, hosts) -> hosts.map { cls to it } }
            .distinctBy { it.second }
            .take(MAX_HOSTS)
            .map { (cls, host) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(HOST_BUDGET_MS) { checkHost(host, cls, network) }
                        ?: HostCheck(host, cls, emptyList(), null, emptyList(), null, Obstacle.UNKNOWN)
                }
            }
        val report = Report(jobs.awaitAll())
        report.hosts.forEach {
            Log.i(TAG, "${it.cls} ${it.host}: ${it.obstacle} sys=${it.systemIps.take(2)}/${it.systemOutcome} " +
                "clean=${it.cleanIps.take(2)}/${it.cleanOutcome}")
        }
        report
    }

    private suspend fun checkHost(host: String, cls: TrafficClass, network: Network?): HostCheck = coroutineScope {
        // The system resolver can hang for tens of seconds on a bad line; it is asked on the side
        // and abandoned after 1.5 s.
        val sysIps = resolveV4(host, network).mapNotNull { it.hostAddress }
        val usable = sysIps.firstOrNull { !HostVerdict.isFakeAnswer(it) && !HostVerdict.isPrivate(it) }
        val sysOutcome = usable?.let { SanctionProbe.probe(host, it, network, connectTimeoutMs = 1500, tlsTimeoutMs = 2000).outcome }
        if (usable != null && (sysOutcome == SanctionProbe.Outcome.OPEN || sysOutcome == SanctionProbe.Outcome.GEO_BLOCKED)) {
            return@coroutineScope HostCheck(host, cls, sysIps, sysOutcome, emptyList(), null,
                HostVerdict.classify(true, sysOutcome, null))
        }
        // The system's answer did not serve: ask a clean resolver what the address really is.
        val clean = withTimeoutOrNull(3_000) { DnsRaceTester.resolveViaDohByIp(host) }.orEmpty()
            .filter { !HostVerdict.isFakeAnswer(it) && !HostVerdict.isPrivate(it) }
        val cleanIp = clean.firstOrNull { it != usable } ?: clean.firstOrNull()
        val cleanOutcome = when {
            cleanIp == null -> null
            cleanIp == usable -> sysOutcome // same address: same answer, no need to ask twice
            else -> SanctionProbe.probe(host, cleanIp, network, connectTimeoutMs = 1500, tlsTimeoutMs = 2000).outcome
        }
        val obstacle = HostVerdict.classify(usable != null, sysOutcome, cleanOutcome)
        // Filtered on its name: does it open with the name split across packets? Tried on the
        // real address (the clean one when the system's was a fake), from this line, now.
        val fragmentIp = if (obstacle == Obstacle.SNI_BLOCKED) {
            val ip = cleanIp ?: usable
            ip?.takeIf {
                FragmentProbe.probe(host, it, network).outcome.let { o ->
                    o == SanctionProbe.Outcome.OPEN || o == SanctionProbe.Outcome.GEO_BLOCKED
                }
            }
        } else null
        HostCheck(host, cls, sysIps, sysOutcome, clean, cleanOutcome, obstacle, fragmentIp)
    }
}
