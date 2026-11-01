package com.mlmvpn.scanner.frontlab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mlmvpn.scanner.mitm.MitmCertManager
import com.mlmvpn.scanner.mitm.MitmProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only, shell permission: holds the domain-fronting config running in a core of its own on a
 * loopback port, so the path can be driven from the PC (`adb forward` + `curl -k --socks5-hostname`).
 *
 *   am broadcast -n com.mlmvpn.scanner/.frontlab.FrontLabReceiver --es mode start [--ei port 10870] [--es log debug]
 *   am broadcast -n com.mlmvpn.scanner/.frontlab.FrontLabReceiver --es mode stop
 *   am broadcast -n com.mlmvpn.scanner/.frontlab.FrontLabReceiver --es mode connect|off   (the screen's real connect)
 *
 * The core's own log lines go to logcat (tag GoLog); this receiver logs under FrontLab.
 */
class FrontLabReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val mode = intent.getStringExtra("mode") ?: "start"
        val port = intent.getIntExtra("port", 10870)
        val level = intent.getStringExtra("log") ?: "warning"
        scope.launch {
            synchronized(lock) {
                runCatching { core?.stopLoop() }
                core = null
            }
            if (mode == "stop") { Log.i(TAG, "stopped"); return@launch }
            if (mode == "connect" || mode == "off") {
                // The fronting screen's own path: the wizard's first step, then its row's connect.
                val svc = Intent(app, com.mlmvpn.scanner.MyVpnService::class.java)
                if (mode == "off") {
                    com.mlmvpn.scanner.utils.SafeStart.service(app, svc.apply { action = "STOP" })
                    Log.i(TAG, "vpn stop sent"); return@launch
                }
                if (!MitmProfile.setUp(app)) { Log.i(TAG, "setUp failed"); return@launch }
                val node = com.mlmvpn.scanner.data.NodeManager(app).nodes.firstOrNull { it.id == MitmProfile.NODE_ID }
                    ?: run { Log.i(TAG, "no node"); return@launch }
                com.mlmvpn.scanner.utils.SafeStart.service(app, svc.apply {
                    putExtra("NODE_URI", node.uri)
                    putExtra("NODE_ID", node.id)
                    putExtra("PROXY_MODE", com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(app))
                    putExtra("LOCAL_PORT", com.mlmvpn.scanner.utils.LocalPort.getString(app))
                })
                Log.i(TAG, "vpn connect sent (trusted=${MitmCertManager.isTrusted(app)})")
                return@launch
            }
            if (!MitmCertManager.ensure(app)) { Log.i(TAG, "certificate could not be made"); return@launch }
            val base = MitmProfile.buildConfig(app) ?: run { Log.i(TAG, "no config"); return@launch }
            val j = org.json.JSONObject(base)
            val inbounds = j.getJSONArray("inbounds")
            for (i in 0 until inbounds.length()) {
                val ib = inbounds.getJSONObject(i)
                if (ib.optString("protocol") == "mixed") ib.put("port", port).put("listen", "127.0.0.1")
            }
            j.put("log", org.json.JSONObject().put("loglevel", level).put("access", "none"))
            val c = com.mlmvpn.core.warp.XrayCore.newController(app)
            try {
                c.startLoop(j.toString(), 0)
                synchronized(lock) { core = c }
                Log.i(TAG, "running on 127.0.0.1:$port (trusted=${MitmCertManager.isTrusted(app)})")
            } catch (e: Exception) {
                Log.i(TAG, "core refused: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "FrontLab"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Any()
        private var core: libv2ray.CoreController? = null
    }
}
