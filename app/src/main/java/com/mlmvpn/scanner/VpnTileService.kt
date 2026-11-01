package com.mlmvpn.scanner

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.mlmvpn.core.tunnel.TunnelStatus
import com.mlmvpn.core.tunnel.TunnelVpnService
import com.mlmvpn.scanner.data.EngineLadder
import com.mlmvpn.scanner.data.LastEngineReplay
import com.mlmvpn.scanner.data.NodeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * The app's one Quick Settings tile.
 *
 * There were two, and neither could be right. One only knew about the Xray family and one only
 * about the five transports, so from the shade the user was asked to remember which of the app's
 * two VPN services had been carrying their traffic and pick the matching tile -- and picking the
 * wrong one did nothing they could see, because each tile reported the state of a service the
 * other one was running. One of them was also still labelled "NetDoctor", the name of the project
 * the transport stack came from, so the pair read as two unrelated apps.
 *
 * One tile, and it works out the answer itself:
 *
 *  - **Something is up** -> stop it, whichever service or engine holds it ([com.mlmvpn.scanner.data.VpnSwitch]).
 *  - **A transport was last used** -> bring that transport back. Likewise an OpenVPN profile, and the
 *    engines that pick their own route (Amnezia/Proton, Outline, FLUX, FENIX, MAE, Windscribe).
 *  - **A special engine was last used** (Aether, VPN Gate, the anti-sanction relay, the game
 *    booster, a Quick Connect server) -> replay exactly that, because the engine's identity is
 *    in the URI and re-racing a list would connect to something else entirely.
 *  - **A config from the user's own list was last used** -> race the most recent N of them and
 *    take the fastest. N is Settings > Advanced VPN > Quick-connect servers, default 20. This is
 *    the one case where replaying the same entry is the wrong move: the list is the user's own
 *    and the point of the tile is to land on whichever of them is working right now.
 *  - **Nothing was ever connected and there are no configs** -- a fresh install -> walk the
 *    transports, Psiphon first, until one is actually carrying traffic. See [EngineLadder].
 *  - **Nothing was ever connected but configs exist** -> race them, as the best available guess.
 *
 * See [LastEngine] for how "last used" is recorded -- on CONNECTED, never on an attempt.
 */
class VpnTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var busy = false

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return

        // --- Disconnect, whichever service holds the tunnel -------------------------------
        //
        // A ladder in progress counts as "something is happening", so a second tap stops it. It
        // walks for the better part of a minute on a blocked network, and a button that ignores
        // taps for that long is indistinguishable from one that is broken.
        if (EngineLadder.running.value) {
            EngineLadder.cancel(applicationContext)
            setTile(Tile.STATE_INACTIVE)
            return
        }
        // Every service and engine, not just the two VPN services this tile used to know: OpenVPN
        // (and Windscribe's OpenVPN family) read as "off" and a tap connected something else on top.
        if (com.mlmvpn.scanner.data.VpnSwitch.isUp(this) || com.mlmvpn.scanner.data.VpnSwitch.engineBusy()) {
            com.mlmvpn.scanner.data.VpnSwitch.stopAll(this)
            setTile(Tile.STATE_INACTIVE)
            return
        }

        // --- Connect: first-time VPN consent must be granted in-app -----------------------
        //
        // A TileService cannot host the consent dialog, so there is nothing to do here but open
        // the app and let it ask. Silently returning left the user tapping a tile that visibly
        // did nothing.
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }

        // MLM Scout is measuring the line itself: a tunnel now would make every number about the
        // tunnel instead. The tile has no screen to ask on, so it says why and does nothing.
        if (com.mlmvpn.scanner.engines.scout.ScoutEngine.isRunning()) {
            toast(com.mlmvpn.scanner.store.tr("MLM Scout در حال بررسی است؛ اول آن را متوقف کنید", "MLM Scout is running; stop it first"))
            return
        }

        reconnectLast()
    }

    /** Bring back whatever the user was last actually connected with. */
    private fun reconnectLast() {
        busy = true
        setTile(Tile.STATE_UNAVAILABLE, S(R.string.tile_connecting))
        scope.launch {
            try {
                // Shared with MLM Scout's «وصل کردن دوباره»: see LastEngineReplay.
                if (!LastEngineReplay.replay(applicationContext)) {
                    // Nothing has ever connected on this device. Rather than opening the app and
                    // giving up -- which is what this used to do and what read as a dead button --
                    // walk the transports until one is actually carrying traffic. See EngineLadder.
                    if (withContext(Dispatchers.IO) { NodeManager(applicationContext).nodes.isEmpty() }) {
                        EngineLadder.start(applicationContext)
                    } else if (!LastEngineReplay.raceUserConfigs(applicationContext)) {
                        toast(S(R.string.no_server_available_add_a_config_first))
                    }
                }
            } catch (e: Exception) {
                toast(S(R.string.quick_connect_error))
            } finally {
                busy = false
                refreshTile()
            }
        }
    }

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(launch)
        }
    }

    private fun refreshTile() {
        if (EngineLadder.running.value) {
            setTile(Tile.STATE_UNAVAILABLE, S(R.string.tile_connecting))
            return
        }
        val up = com.mlmvpn.scanner.data.VpnSwitch.isUp(this)
        setTile(if (up) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
    }

    /**
     * The name stays put; the state goes in the subtitle.
     *
     * The label used to be swapped for "Connected" while the tunnel was up, so the tile lost its
     * own name exactly when the user most needed to identify it -- and on a shade holding a dozen
     * tiles, "Connected" identifies nothing. Android already draws an active tile as filled, and
     * the subtitle line exists for precisely this.
     */
    private fun setTile(state: Int, subtitle: String? = null) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = S(R.string.tile_label_vpn)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle ?: S(
                if (state == Tile.STATE_ACTIVE) R.string.vpn_connected else R.string.vpn_disconnected
            )
        }
        try { tile.updateTile() } catch (e: Exception) { /* ignore */ }
    }

    private fun toast(msg: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(this@VpnTileService, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
