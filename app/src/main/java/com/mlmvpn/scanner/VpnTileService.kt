package com.mlmvpn.scanner

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.ui.httpDelay
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile: one tap to toggle the VPN. When turning ON, it delay-
 * tests the most recent N servers (default 20, configurable in VPN settings)
 * and connects to the fastest.
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

        if (MyVpnService.isRunning) {
            // --- Disconnect ---
            startService(Intent(this, MyVpnService::class.java).apply { action = "STOP" })
            MyVpnService.isRunning = false
            MyVpnService.connectedNodeId = null
            refreshTile()
            return
        }

        // --- Connect: first-time VPN consent must be granted in-app ---
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }
        quickConnect()
    }

    private fun quickConnect() {
        busy = true
        setTile(Tile.STATE_UNAVAILABLE, "در حال تست…")
        scope.launch {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@VpnTileService)
                val count = prefs.getInt("quick_tile_count", 20).coerceIn(1, 200)
                val nodes = NodeManager(this@VpnTileService).nodes
                    .sortedByDescending { it.addedAt }
                    .take(count)

                if (nodes.isEmpty()) {
                    toast("سروری موجود نیست � اول یک کانفیگ اضافه کن")
                    return@launch
                }

                // Delay-test all candidates in parallel; pick the fastest.
                val scored = nodes.map { node ->
                    scope.async(Dispatchers.IO) {
                        val cfg = VpnConfig.parseUri(node.uri)
                        val ms = if (cfg != null && cfg.address.isNotBlank())
                            try { httpDelay(cfg.address, cfg.port) } catch (e: Exception) { -1 }
                        else -1
                        node to ms
                    }
                }.awaitAll()

                val chosen = scored.filter { it.second in 1..8000 }.minByOrNull { it.second }?.first
                    ?: nodes.first() // if none responded, just use the newest

                val isProxyMode = prefs.getBoolean("proxy_mode", false)
                val localPort = prefs.getString("local_port", "10808")
                val intent = Intent(this@VpnTileService, MyVpnService::class.java).apply {
                    putExtra("NODE_URI", chosen.uri)
                    putExtra("NODE_ID", chosen.id)
                    putExtra("PROXY_MODE", isProxyMode)
                    putExtra("LOCAL_PORT", localPort)
                }
                withContext(Dispatchers.Main) {
                    // MyVpnService is a plain VpnService started via startService()
                    // (it never calls startForeground); startForegroundService()
                    // would ANR with "did not then call startForeground()".
                    startService(intent)
                    MyVpnService.isRunning = true
                    MyVpnService.connectedNodeId = chosen.id
                }
            } catch (e: Exception) {
                toast("خطا در اتصال سریع")
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
        if (MyVpnService.isRunning) setTile(Tile.STATE_ACTIVE, "متصل")
        else setTile(Tile.STATE_INACTIVE, "mlmvpn")
    }

    private fun setTile(state: Int, label: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = label
        try { tile.updateTile() } catch (e: Exception) { /* ignore */ }
    }

    private fun toast(msg: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(this@VpnTileService, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
