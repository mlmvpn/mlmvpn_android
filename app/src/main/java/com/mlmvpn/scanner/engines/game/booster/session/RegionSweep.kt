package com.mlmvpn.scanner.engines.game.booster.session

import android.net.Network
import com.mlmvpn.scanner.engines.game.booster.decide.RegionAdvisor
import com.mlmvpn.scanner.engines.game.booster.model.GameRegion
import com.mlmvpn.scanner.engines.game.booster.model.RegionCatalog
import com.mlmvpn.scanner.engines.game.booster.probe.Anchors
import com.mlmvpn.scanner.engines.game.booster.probe.BEACON_PPS_PER_FLOW
import com.mlmvpn.scanner.engines.game.booster.probe.EchoProtocol
import com.mlmvpn.scanner.engines.game.booster.probe.TrainResult
import com.mlmvpn.scanner.engines.game.booster.probe.UdpVia
import com.mlmvpn.scanner.engines.game.booster.probe.tcpTrain
import com.mlmvpn.scanner.engines.game.booster.probe.udpTrainFlows
import com.mlmvpn.scanner.engines.game.booster.probe.collectWithin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress

/**
 * One short measurement of each of the game's regions, all at once, on the direct line.
 *
 * It answers two questions: which region to recommend inside the game ([RegionAdvisor]), and which
 * of them the rest of the boost should measure -- the region the player is on if it answers, else
 * the best one that does. Its slice of the chosen region is reused as the first direct slice, so
 * the sweep costs about one slice of time, not one per region.
 */
object RegionSweep {

    /** Flows per region: fewer than a full direct slice, because every region is measured at once. */
    private const val FLOWS = 6

    data class Probe(
        val gameRegion: GameRegion,
        val anchor: RegionCatalog.Region,
        val echoTarget: InetSocketAddress?,
        val tcpTarget: InetSocketAddress?,
        val echo: TrainResult?,
        val tcp: TrainResult?,
    ) {
        fun sample() = RegionAdvisor.Sample(gameRegion.key, echo?.stats, tcp?.stats)
        val answered: Boolean get() = sample().answered
    }

    suspend fun run(regions: List<GameRegion>, network: Network?, sliceMs: Long = 2000, budgetMs: Long = 8000): List<Probe> =
        collectWithin(regions.distinctBy { it.key }, budgetMs) { gr ->
                val anchor = RegionCatalog.byKey(gr.anchorRegion)
                val (echoTarget, tcpTarget) = coroutineScope {
                    val e = async { Anchors.echoTarget(anchor, network) }
                    val t = async { Anchors.pickTcp(anchor, network) }
                    e.await() to t.await()
                }
                val (echo, tcp) = coroutineScope {
                    val e = async {
                        echoTarget?.let {
                            udpTrainFlows(it, EchoProtocol, List(FLOWS) { UdpVia.Direct(network) },
                                ppsPerFlow = BEACON_PPS_PER_FLOW, durationMs = sliceMs)
                        }
                    }
                    val t = async {
                        tcpTarget?.let { tcpTrain(it, count = (sliceMs / 500).toInt().coerceAtLeast(2), gapMs = 250, timeoutMs = 1500, network = network) }
                    }
                    e.await() to t.await()
                }
                Probe(gr, anchor, echoTarget, tcpTarget, echo, tcp)
        }
}
