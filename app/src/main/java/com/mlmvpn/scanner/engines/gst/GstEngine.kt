package com.mlmvpn.scanner.engines.gst

import android.content.Context
import android.os.Build
import android.util.Log
import com.mlmvpn.core.warp.IVpnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import com.therealaleph.mhrv.Native

class GstEngine : IVpnEngine {
    private var engineHandle: Long = 0
    private val engineScope = CoroutineScope(Dispatchers.IO)
    private var isRunning = false
    private var logJob: Job? = null

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        Log.d("GstEngine", "Starting GST Engine via JNI...")
        
        if (isRunning && engineHandle != 0L) {
            Log.d("GstEngine", "GST Engine is already running.")
            return true
        }
        
        // 1. Initialize data directory for the rust core
        Native.setDataDir(context.filesDir.absolutePath)

        // 2. We fetch relays and best front IP
        val relays = GstConfigManager.getRelays(context)
        if (relays.isEmpty()) {
            Log.e("GstEngine", "No relays configured for GST Engine.")
            return false
        }

        val snis = GstConfigManager.getSelectedSniList(context)
        val ips = GstConfigManager.getSelectedCleanIpList(context)
        
        val sni = if (snis.isNotEmpty()) snis.random() else "www.google.com"
        val finalIp = if (ips.isNotEmpty()) ips.random() else "google.com"
        
        Log.d("GstEngine", "Selected SNI: $sni, IP: $finalIp")
        
        // 3. Create config payload
        val tomlContent = createConfigPayload(localPort, relays, finalIp, sni)
        
        // 4. Start the proxy via JNI
        engineHandle = Native.startProxy(tomlContent)
        
        if (engineHandle == 0L) {
            Log.e("GstEngine", "Failed to start GST proxy via JNI (handle returned 0).")
            return false
        }
        
        isRunning = true
        Log.d("GstEngine", "GST Engine started successfully via JNI with handle: $engineHandle")

        // 5. Start a background job to drain logs from Rust
        logJob = engineScope.launch {
            while (isActive && isRunning) {
                val logs = Native.drainLogs()
                if (logs.isNotEmpty()) {
                    logs.split("\n").forEach { line ->
                        if (line.isNotBlank()) Log.d("GstEngine-Rust", line)
                    }
                }
                delay(500) // Poll logs every 500ms
            }
        }

        return true
    }

    private fun createConfigPayload(localPort: Int, relays: List<GstRelay>, frontIp: String, sni: String): String {
        val primaryRelay = relays.first()
        
        return """
            [relay]
            mode = "apps_script"
            script_id = "${primaryRelay.deploymentId}"
            auth_key = "${primaryRelay.authKey}"
            
            [network]
            google_ip = "$frontIp"
            front_domain = "$sni"
            socks5_port = $localPort
            listen_port = 0
            
            [logging]
            log_level = "debug"
        """.trimIndent()
    }

    override fun stop() {
        Log.d("GstEngine", "Stopping GST Engine...")
        isRunning = false
        logJob?.cancel()
        
        if (engineHandle != 0L) {
            val stopped = Native.stopProxy(engineHandle)
            Log.d("GstEngine", "GST Engine JNI stopProxy returned: $stopped")
            engineHandle = 0L
        }
    }
}
