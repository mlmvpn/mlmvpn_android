package com.mlmvpn.scanner.data

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import com.mlmvpn.core.tunnel.CoreConfig
import com.mlmvpn.core.tunnel.TunnelVpnService
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.engines.rstaspoof.SniSession
import com.mlmvpn.scanner.ui.httpDelay
import com.mlmvpn.scanner.ui.tunnel.Transport
import com.mlmvpn.scanner.ui.tunnel.TunnelExclusion
import com.mlmvpn.scanner.utils.LocalPort
import com.mlmvpn.scanner.utils.NetworkSettings
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Bring back what [LastEngine] recorded -- the Quick Settings tile's "connect" and MLM Scout's
 * «وصل کردن دوباره» after a run, one implementation for both. Moved here out of the tile unchanged:
 * the tile is still the place that decides what to do when nothing was ever recorded.
 *
 * The caller has VPN consent already (VpnService.prepare returned null).
 */
object LastEngineReplay {

    /** Replays the recorded engine. False when there is no record; the caller decides what then. */
    suspend fun replay(context: Context): Boolean {
        val app = context.applicationContext
        when (val last = LastEngine.read(app)) {
            null -> return false
            is LastEngine.Record.Tunnel -> connectTransport(app, last.transportId)

            is LastEngine.Record.OpenVpn -> {
                val known = withContext(Dispatchers.IO) {
                    com.mlmvpn.scanner.openvpn.OpenVpnRepository.get(app).data.value.profiles.any { it.id == last.profileId }
                }
                if (!known) return raceUserConfigs(app)
                withContext(Dispatchers.IO) { runCatching { TunnelExclusion.releaseForOpenVpn(app) } }
                com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connect(app, last.profileId, last.accountId)
            }

            is LastEngine.Record.Xray -> if (last.nodeId == LastEngine.MAE_MARKER) {
                // MAE decides per network: a fresh config for this one, never the last one.
                startMae(app)
            } else if (last.nodeId == LastEngine.FENIX_MARKER) {
                withContext(Dispatchers.IO) { TunnelExclusion.releaseForXray(app) }
                com.mlmvpn.scanner.engines.fenix.FenixEngine.connectAsync(app)
            } else if (last.nodeId == LastEngine.FLUX_MARKER) {
                // FLUX connects itself: this network's known route at once, or a race.
                withContext(Dispatchers.IO) { TunnelExclusion.releaseForXray(app) }
                com.mlmvpn.scanner.engines.flux.FluxEngine.connectAsync(app)
            } else if (last.nodeId == LastEngine.WINDSCRIBE_MARKER) {
                com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.connect(app)
            } else if (last.uri == LastEngine.AMNEZIA_CF_MARKER || last.nodeId == LastEngine.AMNEZIA_MARKER) {
                // The same Amnezia server again, the way this network reaches it. A Proton server
                // stays among Proton's (its relay is set up per connect; see LastEngine).
                val amz = com.mlmvpn.scanner.engines.amnezia.AmzEngine
                amz.init(app)
                val id = amz.store.current.lastConnectedId
                val proton = com.mlmvpn.scanner.engines.proton.ProtonEngine.storedIds(app)
                amz.connectAsync(app, serverId = id, only = if (id != null && id in proton) proton else null)
            } else if (last.nodeId == LastEngine.OUTLINE_MARKER) {
                withContext(Dispatchers.IO) { TunnelExclusion.releaseForXray(app) }
                com.mlmvpn.scanner.engines.outline.OutlineEngine.connectAsync(app)
            } else {
                // An SNI config is in the user's list too, but racing the list would
                // connect to something else entirely -- and the SNI configs all point at
                // the same local port, so a race between them measures one socket eight
                // times. It is replayed exactly; MyVpnService brings the SNI front up
                // itself when it sees where the config points.
                val fromUserList = !SniSession.isSniUri(last.uri) &&
                    last.nodeId != null &&
                    withContext(Dispatchers.IO) {
                        NodeManager(app).nodes.any { it.id == last.nodeId }
                    }
                if (fromUserList) raceUserConfigs(app) else replayXray(app, last.uri, last.nodeId)
            }
        }
        return true
    }

    /** One of the five transports, by id. */
    suspend fun connectTransport(app: Context, transportId: String) {
        val transport = Transport.entries.firstOrNull { it.id == transportId }
        if (transport == null) {
            // The id no longer maps to a transport -- a build that dropped one. Fall back to the
            // configs rather than doing nothing, which is what the user would see otherwise.
            raceUserConfigs(app)
            return
        }
        withContext(Dispatchers.IO) {
            // Only one VpnService may hold the device tun; the second establish() takes it from
            // the first rather than failing. See TunnelExclusion.
            TunnelExclusion.releaseForTunnelStack(app)
            val config = CoreConfig.json(app, transport.value)
            androidx.core.content.ContextCompat.startForegroundService(
                app,
                Intent(app, TunnelVpnService::class.java)
                    .setAction(TunnelVpnService.ACTION_CONNECT)
                    .putExtra(TunnelVpnService.EXTRA_CONFIG, config)
            )
        }
    }

    /** MAE, compiled now for this network (its engine then follows the session by itself). */
    private suspend fun startMae(app: Context) {
        withContext(Dispatchers.IO) { TunnelExclusion.releaseForXray(app) }
        val config = withContext(Dispatchers.IO) { com.mlmvpn.scanner.engines.mae.MaeEngine.freshConfig(app) }
        startXray(app, config, com.mlmvpn.scanner.engines.mae.MaeEngine.NODE_ID)
    }

    /** Replay one specific engine, exactly as it was. */
    private suspend fun replayXray(app: Context, uri: String, nodeId: String?) {
        withContext(Dispatchers.IO) { TunnelExclusion.releaseForXray(app) }
        startXray(app, uri, nodeId)
    }

    /**
     * Test the most recent N configs and connect to the fastest. False when there are none.
     *
     * N is the Quick-connect server count from Advanced VPN settings. All N are probed at once,
     * because the point of a tile is that it finishes while the shade is still open -- a serial
     * walk of twenty servers would not.
     */
    suspend fun raceUserConfigs(app: Context): Boolean {
        val count = PreferenceManager.getDefaultSharedPreferences(app)
            .getInt("quick_tile_count", 20)
            .coerceIn(1, 200)
        val nodes = withContext(Dispatchers.IO) {
            NodeManager(app).nodes
                .sortedByDescending { it.addedAt }
                .take(count)
        }
        if (nodes.isEmpty()) return false

        val scored = coroutineScope {
            nodes.map { node ->
                async(Dispatchers.IO) {
                    val cfg = VpnConfig.parseUri(node.uri)
                    val ms = if (cfg != null && cfg.address.isNotBlank()) {
                        try { httpDelay(cfg.address, cfg.port) } catch (e: Exception) { -1 }
                    } else {
                        -1
                    }
                    node to ms
                }
            }.awaitAll()
        }

        // If nothing answered, the newest is still a better guess than refusing to act: the probe
        // is a plain reachability check and a blocked network fails all of them equally.
        val chosen = scored.filter { it.second in 1..8000 }.minByOrNull { it.second }?.first
            ?: nodes.first()
        startXray(app, chosen.uri, chosen.id)
        return true
    }

    private suspend fun startXray(app: Context, uri: String, nodeId: String?) {
        val intent = Intent(app, MyVpnService::class.java).apply {
            putExtra("NODE_URI", uri)
            putExtra("NODE_ID", nodeId)
            // The app-wide settings, the same ones every other start path reads.
            putExtra("PROXY_MODE", NetworkSettings.proxyMode(app))
            putExtra("LOCAL_PORT", LocalPort.getString(app))
        }
        withContext(Dispatchers.Main) {
            // MyVpnService is a plain VpnService started via startService() (it never calls
            // startForeground); startForegroundService() would ANR with "did not then call
            // startForeground()".
            com.mlmvpn.scanner.utils.SafeStart.service(app, intent)
            MyVpnService.isRunning = true
            MyVpnService.connectedNodeId = nodeId
        }
    }
}
