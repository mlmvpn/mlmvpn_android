package com.mlmvpn.scanner.ui.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.quick.QuickSavedStore
import com.mlmvpn.scanner.ui.tunnel.TunnelController
import com.mlmvpn.scanner.ui.tunnel.TunnelStage

/**
 * Which home-screen icons are currently carrying something.
 *
 * The home grid is a launcher for a dozen different ways of getting online, and until now none of
 * them looked any different when it was the one that was up — so "am I connected, and through
 * what" could only be answered by opening icons one at a time. This is the single place that
 * answers it, and [AppIconTile] draws a lamp from it.
 *
 * The state comes from three independent places, because the engines genuinely are independent:
 *
 *  - the five-transport stack has its own service and its own controller;
 *  - everything built on Xray — nodes, Quick Connect, VPN Gate, the game booster, the
 *    anti-sanction resolver, the Google Script tunnel — shares [MyVpnService] and is told apart
 *    by the node id it was started with;
 *  - the SNI spoofer counts as up only while a config is riding it -- the front process on
 *    its own is a door nobody is walking through, and drawing that as a live engine is what
 *    put the SNI lamp on over unrelated tunnels.
 *
 * Only ONE of the Xray family can be live at a time (they share a service), and only one of them
 * and the transport stack (they share the device's tun — see `TunnelExclusion`). The set can
 * still hold more than one entry, because an SNI session is not a tunnel of its own and genuinely
 * coexists with one.
 */
object ActiveEngines {

    /**
     * The home ids that should show a lamp right now.
     *
     * Composable rather than a Flow: every input is already a Flow or a cheap read, and the only
     * consumer is the grid, which is composed anyway.
     */
    @Composable
    fun ids(): Set<String> {
        val context = LocalContext.current

        val tunnel by TunnelController.state.collectAsState()
        val openvpn by com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connection.collectAsState()
        val xrayRunning by MyVpnService.isRunningFlow.collectAsState()
        val xrayNode by MyVpnService.connectedNodeIdFlow.collectAsState()
        // Asked of the service that is carrying the config, not of the node list. See
        // [MyVpnService.sniSessionFlow] for why the list could not answer it reliably.
        val sniSession by MyVpnService.sniSessionFlow.collectAsState()

        // Keyed on every input, so the set is rebuilt when an engine changes and NOT on the many
        // other recompositions the board goes through -- a drag re-runs the grid on every touch
        // move, and `xrayHome` reads preferences and parses the saved-server list.
        val tunnelId = tunnel.active?.id?.takeIf { tunnel.stage == TunnelStage.RUNNING }
        return remember(tunnelId, xrayRunning, xrayNode, sniSession, openvpn.phase) {
            buildSet {
                if (openvpn.phase == com.mlmvpn.scanner.openvpn.ConnectionPhase.CONNECTED) add("openvpn")
                tunnelId?.let { add(it) }
                if (xrayRunning) xrayHome(context, xrayNode, sniSession)?.let { add(it) }
                // The SESSION, not the front process.
                //
                // This used to light on `RstaSpoofManager.isRunningFlow` as well, and that is a
                // process being alive -- which is not the same fact. The front carries nothing by
                // itself; it is a door, and it only means anything while a config is walking
                // through it. Measuring a config raises the same door, and so did every delay
                // test over a list with one SNI config in it, so the lamp came on over a MASQUE
                // or a WireGuard tunnel with no SNI anywhere near it. The user saw it and asked
                // exactly the right question: why is the SNI light on when I am on the tunnel?
                //
                // (The stray process was a real leak too, and is fixed at the source -- the front
                // is now claimed and released rather than switched on. This is the second half:
                // even if something did strand it, an open door is not a connection and must not
                // be drawn as one.)
                if (sniSession) add("emergency_3")
            }
        }
    }

    /**
     * Which icon owns the node id [MyVpnService] is currently running.
     *
     * A lookup rather than a flag on the service, because the id is what every caller already
     * passes and adding a parallel "who started me" field would be one more thing to keep in
     * step at eight call sites. The order matters: the specific engines claim their own ids
     * first, and anything left is an ordinary node from the connection tab.
     *
     * Returns null rather than guessing when the id belongs to no icon — the retired Aether
     * screen still sets `aether`, and lighting an unrelated tile would be worse than lighting
     * none.
     */
    private fun xrayHome(context: Context, nodeId: String?, sniSession: Boolean): String? {
        val id = nodeId?.trim().orEmpty()
        if (id.isEmpty()) return null
        return when {
            // An SNI session is Xray AND the local TLS front working as one thing, so it must
            // light one lamp, not two. Without this the V2Ray icon came on beside the SNI one and
            // the user was shown two engines for a single connection -- and tapping the V2Ray one
            // took them to a list where nothing looked connected.
            //
            // The test is the service's own flag rather than a lookup in the node list. The
            // lookup version is what shipped, and it kept failing: it ran during the home
            // screen's first composition, which is regularly BEFORE NodeManager has finished
            // loading its list off disk, so it found no node and answered "not SNI".
            sniSession -> "emergency_3"
            id == "ANTISANCTION" -> "antisanction"
            // The two built-in profiles have their own icons now, so their lamp belongs there.
            // Left to fall through, they lit the V2Ray tile -- which sent anyone who tapped it to
            // a list where the connected config is in a folder they have to go looking for.
            id.startsWith("default_mlmvpn_") -> "iran"
            id == com.mlmvpn.scanner.mitm.MitmProfile.NODE_ID -> "fronting"
            id == "GST_EMERGENCY" -> "emergency_2"
            id == com.mlmvpn.scanner.engines.mae.MaeEngine.NODE_ID -> "mae"
            id == com.mlmvpn.scanner.engines.flux.FluxEngine.NODE_ID -> "flux"
            id == com.mlmvpn.scanner.engines.amnezia.AmzEngine.NODE_ID -> "amnezia"
            id.startsWith(com.mlmvpn.scanner.engines.github.GtEngine.NODE_PREFIX) -> "github"
            // The booster's own ids, plus the two DNS-only modes it can run without a tunnel.
            id.startsWith("game_") || id.startsWith("dedicated_dns") ||
                id.startsWith("direct_dns") -> "game"
            id.startsWith("vpngate_") -> "vpngate"
            // A user's own node boosted for a game keeps ITS id, so the booster's own flag is the
            // only thing that can tell that case from an ordinary connect.
            gameModeActive(context) -> "game"
            // Quick Connect's ids come from the feed's server key, and the connection tab's are
            // UUIDs — but neither shape is a rule worth relying on. Asking the store that owns
            // the row is exact, and it is a short list held in preferences.
            isQuickNode(context, id) -> "quick"
            id == "aether" -> null
            else -> "nodes"
        }
    }

    private fun gameModeActive(context: Context): Boolean =
        context.getSharedPreferences("game_booster_prefs", Context.MODE_PRIVATE)
            .getBoolean("game_mode_active", false)

    /**
     * The saved list is no longer the whole answer.
     *
     * Servers from the shared MLMVPN pool are deliberately never written to disk, so connecting
     * to one left this returning false and the id fell through to `else -> "nodes"` -- the V2Ray
     * lamp came on for a Quick Connect session. The marker Quick Connect leaves when it starts a
     * tunnel covers both cases and is a cheap preference read, so it goes first.
     */
    private fun isQuickNode(context: Context, id: String): Boolean = runCatching {
        QuickSavedStore.lastConnectedId(context) == id ||
            QuickSavedStore.all(context).any { it.id == id }
    }.getOrDefault(false)
}
