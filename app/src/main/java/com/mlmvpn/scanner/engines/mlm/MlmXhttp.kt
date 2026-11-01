package com.mlmvpn.scanner.engines.mlm

import android.content.Context
import com.mlmvpn.scanner.data.PanelBuild
import com.mlmvpn.scanner.engines.arena.ArenaPanels
import com.mlmvpn.scanner.engines.cfnet.CfNetManager
import com.mlmvpn.scanner.engines.cfnet.CfStage
import com.mlmvpn.scanner.engines.cfnet.StrategyEngine
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «دریافت کانفیگ xhttp»: an XHTTP config from the user's own MLM panel, proven before it is handed out.
 *
 * The panel has always served XHTTP packet-up, but on workers.dev a session's upload POSTs landed in
 * other isolates than its download and the connection stalled (measured 2026-09-26). Build 23 hands
 * every request of a session to that session's Durable Object, where they meet -- so XHTTP is offered
 * again, here, and only once this phone has carried real data through it.
 *
 * The config is the app's own panel user (the arena's, never one of the operator's people) with its
 * credential, address and name exactly as its WebSocket config has them, on XHTTP packet-up over
 * HTTP/2 (ALPN h2). It is then put through the Cloudflare engine's CONNECT stage on THIS network:
 * a real core, HTTP 204 + HTTPS + an exact download + an echoed upload. Nothing is saved unless that
 * passes, and the way that passed (as written, ECH, XHTTP over HTTP/1.1, …) is remembered for it.
 */
object MlmXhttp {

    sealed interface Outcome {
        /** Proven on this network; [winner] is the strategy that carried it. */
        data class Ready(val link: String, val winner: String) : Outcome
        /** The deployed panel predates build 23: it must be updated before XHTTP can work. */
        data object Outdated : Outcome
        data class Failed(val reason: Reason) : Outcome
    }

    enum class Reason { NO_PANEL_CONFIG, NOT_WORKING, PANEL_ERROR }

    /** [ws] -- a VLESS-over-WebSocket link of the panel -- as the same server's XHTTP config, or null. */
    fun fromWebSocket(ws: String, name: String): String? {
        val c = VpnConfig.parseUri(ws) ?: return null
        if (c.protocol != "vless" || !c.tls.equals("tls", true) || !c.network.equals("ws", true)) return null
        val host = c.wsHost.ifBlank { c.sni }.ifBlank { c.address }
        if (host.isBlank() || c.uuid.isBlank()) return null
        return c.copy(
            network = "xhttp",
            // The worker's packet-up routes live at the root: GET /{session}, POST /{session}/{seq}.
            xhttpPath = "/", xhttpHost = host, xhttpMode = "packet-up", xhttpExtra = "",
            wsHost = "", wsPath = "",
            sni = c.sni.ifBlank { host },
            alpn = "h2", fingerprint = "chrome",
            name = name, rawUri = "",
        ).toUriString()
    }

    suspend fun obtain(context: Context, account: CloudAccount): Outcome = withContext(Dispatchers.IO) {
        if (PanelBuild.isStale(account, "MLM")) return@withContext Outcome.Outdated
        val links = try { ArenaPanels.MLM.candidates(context, account) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return@withContext Outcome.Failed(Reason.PANEL_ERROR)
        }
        val link = links.firstNotNullOfOrNull { fromWebSocket(it, "MLM · XHTTP") }
            ?: return@withContext Outcome.Failed(Reason.NO_PANEL_CONFIG)
        val config = VpnConfig.parseUri(link) ?: return@withContext Outcome.Failed(Reason.NO_PANEL_CONFIG)
        // Real data through a real core, on this network, before anything is saved.
        val out = CfNetManager.get(context).diagnose(CfStage.CONNECT, config,
            budget = StrategyEngine.StageBudget(parallel = 1, deadlineMs = 150_000))
        if (out.ok) Outcome.Ready(link, out.winner!!.id) else Outcome.Failed(Reason.NOT_WORKING)
    }
}
