package com.mlmvpn.scanner.engines.game.booster.probe

import android.net.Network
import com.mlmvpn.scanner.engines.game.DnsRaceTester
import com.mlmvpn.scanner.engines.game.booster.doctor.HostVerdict
import com.mlmvpn.scanner.engines.game.booster.model.RegionCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress

/** Turning a region's anchor names into addresses that answer. */
object Anchors {

    /**
     * [host] as an IPv4 address: the system resolver first (bounded -- it can hang for tens of
     * seconds on a bad line -- and never a filtering answer), then DNS over HTTPS by IP address,
     * which passes where the named resolvers are filtered.
     */
    suspend fun resolve(host: String, network: Network? = null): InetAddress? {
        resolveV4(host, network)
            .firstOrNull { a -> a.hostAddress?.let { !HostVerdict.isFakeAnswer(it) && !HostVerdict.isPrivate(it) } == true }
            ?.let { return it }
        val doh = withTimeoutOrNull(3_000) { DnsRaceTester.resolveViaDohByIp(host) }.orEmpty()
        return doh.firstOrNull { !HostVerdict.isFakeAnswer(it) && !HostVerdict.isPrivate(it) }?.let {
            try { InetAddress.getByAddress(host, InetAddress.getByName(it).address) } catch (e: Exception) { null }
        }
    }

    /** The region's UDP echo beacon, resolved, or null where it has none or it does not resolve. */
    suspend fun echoTarget(region: RegionCatalog.Region, network: Network? = null): InetSocketAddress? =
        region.echo.firstNotNullOfOrNull { e -> resolve(e.host, network)?.let { InetSocketAddress(it, e.port) } }

    /** A tunnel resolves this name remotely even when every direct endpoint is blocked. */
    fun tunnelTarget(region: RegionCatalog.Region): InetSocketAddress? =
        region.tcp.firstOrNull()?.let { InetSocketAddress.createUnresolved(it.host, it.port) }

    /**
     * The fastest of the region's TCP endpoints that answers one handshake. Picking by answer, not
     * by list order, is what keeps one dead name from making a live region read as dark.
     */
    suspend fun pickTcp(region: RegionCatalog.Region, network: Network?, timeoutMs: Int = 1500): InetSocketAddress? = coroutineScope {
        region.tcp.map { t ->
            async {
                val ip = resolve(t.host, network) ?: return@async null
                val target = InetSocketAddress(ip, t.port)
                val r = tcpTrain(target, count = 1, timeoutMs = timeoutMs, network = network)
                r.stats.min?.let { target to it }
            }
        }.awaitAll().filterNotNull().minByOrNull { it.second }?.first
    }
}
