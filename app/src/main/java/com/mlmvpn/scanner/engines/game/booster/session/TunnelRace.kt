package com.mlmvpn.scanner.engines.game.booster.session

import android.content.Context
import android.util.Log
import com.mlmvpn.core.aether.AetherEngine
import com.mlmvpn.core.aether.AetherOptions
import com.mlmvpn.core.aether.AetherProtocol
import com.mlmvpn.core.aether.AetherTunEngine
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.engines.cfdoctor.NativeProbe
import com.mlmvpn.scanner.engines.fenix.FenixEngine
import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import com.mlmvpn.scanner.engines.game.booster.probe.ProbeStats
import com.mlmvpn.scanner.engines.game.booster.probe.ProbeBudget
import com.mlmvpn.scanner.engines.game.booster.probe.tlsTrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress

/**
 * «بازی‌ام فیلتر یا تحریم است»: which tunnel reaches THIS game's region fastest, measured.
 *
 * For a game the line cannot carry -- filtered, sanctioned, or that simply does not load -- the
 * direct line is not a candidate however it measures. The candidates are the app's tunnels that
 * leave Iran (or at least the filter): Amnezia's free servers abroad, WARP over WireGuard (with the
 * exit country chosen on its screen), WARP over MASQUE, and FENIX on the user's Firefox account.
 *
 * Each is brought up on its own -- only one tunnel runs at a time -- with a local SOCKS port, and
 * timed the same way: a full TLS handshake to the game region's own anchor (`ec2.<region>` on 443)
 * THROUGH it. A SOCKS proxy answers the CONNECT before the far end does, so a bare connect would
 * time the hop to the proxy and every tunnel would look instant; the handshake is a real round trip
 * to the region, and the same method for every candidate, so the numbers compare. Amnezia and FENIX
 * run in the probe process (their configs are Xray's); the two WARPs run in Aether.
 */
class TunnelRace(private val ctx: Context, private val aetherOwner: Any = Any()) {

    data class Result(
        val route: RouteKind,
        val stats: PathStats,
        /** The Aether options measured, for the WARP routes. */
        val aether: AetherOptions? = null,
        /** The Amnezia server measured. */
        val amneziaServer: String? = null,
    ) {
        val reachable: Boolean get() = stats.answered
        val score: Int get() = ProbeStats.score(stats)
    }

    private val aetherSocks = InetSocketAddress("127.0.0.1", AetherEngine.AETHER_SOCKS_PORT)

    /**
     * Measure [routes] in order, each within [eachMs], and report as each lands. Unavailable ones
     * (no Amnezia server, no Firefox account) come back unanswered rather than missing, so the
     * screen can say why one was not chosen.
     */
    suspend fun run(
        target: InetSocketAddress,
        routes: List<RouteKind>,
        warpOverTcp: Boolean,
        eachMs: Long,
        totalMs: Long = eachMs * routes.size,
        onResult: (Result) -> Unit = {},
    ): List<Result> {
        val out = ArrayList<Result>()
        val budget = ProbeBudget(totalMs)
        var keepForApply = false
        try {
        for ((index, route) in routes.withIndex()) {
            if (!budget.allows(1000)) break
            val slot = minOf(eachMs, budget.left() / (routes.size - index))
            val r = runCatching { withTimeoutOrNull(slot) { measure(route, target, warpOverTcp) } }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull() ?: Result(route, PathStats.empty(0))
            Log.i(TAG, "${route.name}: p50=${r.stats.p50} n=${r.stats.n}/${r.stats.sent} score=${r.score}")
            out += r
            onResult(r)
        }
        val winner = out.filter { it.reachable }.maxByOrNull { it.score }
        keepForApply = winner?.aether?.let { AetherEngine.get(ctx).runningOptions == it } == true
        return out
        } finally {
            if (!keepForApply) stopAether()
        }
    }

    private suspend fun measure(route: RouteKind, target: InetSocketAddress, warpOverTcp: Boolean): Result = when (route) {
        RouteKind.WARP_WG, RouteKind.WARP_MASQUE_H3, RouteKind.WARP_MASQUE_H2 -> {
            val opts = aetherOptions(route, warpOverTcp)
            val stats = if (bringUpAether(opts)) handshakes(target, aetherSocks) else PathStats.empty(0)
            Result(route, stats, aether = opts)
        }
        RouteKind.AMNEZIA -> {
            val probe = AmzEngine.probeForGame(ctx)
            if (probe == null) Result(route, PathStats.empty(0)) else {
                val (_, stats) = NativeProbe(ctx).withCore(probe.second, keepRouting = true) { port ->
                    handshakes(target, InetSocketAddress("127.0.0.1", port))
                }
                Result(route, stats ?: PathStats.empty(0), amneziaServer = probe.first)
            }
        }
        RouteKind.FENIX -> {
            val cfg = withContext(Dispatchers.IO) { FenixEngine.probeConfig(ctx) }
            if (cfg == null) Result(route, PathStats.empty(0)) else {
                val (_, stats) = NativeProbe(ctx).withCore(cfg, keepRouting = true) { port ->
                    handshakes(target, InetSocketAddress("127.0.0.1", port))
                }
                Result(route, stats ?: PathStats.empty(0))
            }
        }
        else -> Result(route, PathStats.empty(0))
    }

    /**
     * The options a WARP route runs with: the game profile (light obfuscation, TURBO), and for
     * WireGuard the exit country's gateway when one is chosen on the WireGuard screen -- the route
     * that actually changes the country a sanctioned game sees.
     */
    fun aetherOptions(route: RouteKind, warpOverTcp: Boolean): AetherOptions {
        val base = AetherTunEngine.optionsOf(AetherTunEngine.buildGameConfig(
            protocol = if (route == RouteKind.WARP_WG) AetherProtocol.WG else AetherProtocol.MASQUE,
            transport = if (route == RouteKind.WARP_MASQUE_H2 || warpOverTcp) "h2" else "h3",
        ))
        if (route != RouteKind.WARP_WG) return base
        val peer = runCatching {
            com.mlmvpn.core.warp.Nat64Exits.pick(ctx, com.mlmvpn.core.warp.Nat64Exits.Proto.WG)
        }.getOrNull()
        return if (peer.isNullOrBlank()) base else base.copy(wgPeer = peer, quickReconnect = false)
    }

    private suspend fun bringUpAether(opts: AetherOptions): Boolean {
        val eng = AetherEngine.get(ctx)
        if (eng.runningOptions != null && eng.runningOptions != opts) {
            // Another WARP route's measurement, or a WARP the user started elsewhere that is not a
            // tunnel right now: replaced for the time of this one.
            eng.stopOwned(aetherOwner)
            if (eng.runningOptions != null) return false
        }
        if (eng.runningOptions == null && !eng.startOwned(opts, aetherOwner) { Log.w(TAG, "aether: $it") }) return false
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < AETHER_READY_MS) {
            val s = eng.state.value
            if (s.connected && eng.isSocksAlive()) return true
            if (s.stage == com.mlmvpn.core.aether.AetherStage.FAILED || s.stage == com.mlmvpn.core.aether.AetherStage.CRASHED) return false
            delay(300)
        }
        return false
    }

    private fun stopAether() {
        runCatching { AetherEngine.get(ctx).stopOwned(aetherOwner) }
    }

    /** [COUNT] TLS handshakes to [target] (by name, resolved at the far end) through [socks]. */
    private suspend fun handshakes(target: InetSocketAddress, socks: InetSocketAddress): PathStats {
        return tlsTrain(target, count = COUNT, gapMs = GAP_MS, timeoutMs = HANDSHAKE_MS, socks = socks).stats
    }

    companion object {
        private const val TAG = "TunnelRace"
        private const val COUNT = 5
        private const val GAP_MS = 150L
        private const val HANDSHAKE_MS = 6_000
        private const val AETHER_READY_MS = 50_000L

        /** The candidates, before what this network learned reorders them. */
        val CANDIDATES = listOf(RouteKind.AMNEZIA, RouteKind.WARP_WG, RouteKind.WARP_MASQUE_H3, RouteKind.FENIX)
    }
}
