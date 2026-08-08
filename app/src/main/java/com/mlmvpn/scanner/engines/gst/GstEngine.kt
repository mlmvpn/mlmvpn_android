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
        GstLog.i("GstEngine", "Starting GST Engine via JNI (localPort=$localPort)...")

        if (isRunning && engineHandle != 0L) {
            GstLog.d("GstEngine", "GST Engine is already running.")
            return true
        }

        // 1. Initialize data directory for the rust core
        Native.setDataDir(context.filesDir.absolutePath)

        // 2. We fetch relays and best front IP
        val relays = GstConfigManager.getRelays(context)
        val validRelays = relays.filter { it.deploymentId.isNotBlank() }
        if (validRelays.isEmpty()) {
            GstLog.e("GstEngine", "No relays with a Deployment ID configured � cannot start.")
            return false
        }
        GstLog.i("GstEngine", "Configured relays: ${validRelays.size} " +
            "(deploymentIds=${validRelays.joinToString { GstLog.redact(it.deploymentId) }})")

        val snis = GstConfigManager.getSelectedSniList(context)
        val ips = GstConfigManager.getSelectedCleanIpList(context)

        // Defaults MUST match the upstream mhrv-rs reference config
        // (therealaleph/MasterHttpRelayVPN-RUST config.example.toml): a fixed Google
        // frontend IP + www.google.com. Random IPs (especially the Cloudflare ones that
        // used to be in the default list) break the direct Google tunnel that carries
        // YouTube/Google traffic. Only override when the user explicitly scanned/selected.
        val sni = if (snis.isNotEmpty()) snis.random() else "www.google.com"
        val finalIp = if (ips.isNotEmpty()) ips.random() else GstConfigManager.DEFAULT_GOOGLE_IP

        GstLog.i("GstEngine", "Selected front IP=$finalIp, SNI=$sni")

        // 3. Create config payload
        val tomlContent = createConfigPayload(context, localPort, validRelays, finalIp, sni)
        GstLog.d("GstEngine", "Core config:\n${maskConfig(tomlContent)}")

        // 4. Start the proxy via JNI
        engineHandle = Native.startProxy(tomlContent)

        if (engineHandle == 0L) {
            GstLog.e("GstEngine", "Failed to start GST proxy via JNI (handle returned 0).")
            return false
        }

        isRunning = true
        GstLog.i("GstEngine", "GST Engine started (handle=$engineHandle)")

        // 5. Start a background job to drain logs from Rust into the shared GstLog sink
        logJob = engineScope.launch {
            while (isActive && isRunning) {
                val logs = Native.drainLogs()
                if (logs.isNotEmpty()) {
                    logs.split("\n").forEach { line ->
                        if (line.isNotBlank()) GstLog.d("GstEngine-Rust", line)
                    }
                }
                delay(500) // Poll logs every 500ms
            }
        }

        return true
    }

    /**
     * Builds the TOML config for the mhrv-rs core.
     *
     * When one or more Cloudflare acceleration workers are configured (see
     * [GstConfigManager.getRelayUrls]) they are wired in as `relay_url` and the core
     * runs a parallel relay across the worker(s) and the Apps Script deployment(s),
     * so GST works standalone with Google alone and gets faster/steadier with CF.
     * All Apps Script deployment ids are passed as `script_ids` for failover.
     */
    private fun createConfigPayload(
        context: Context,
        localPort: Int,
        relays: List<GstRelay>,
        frontIp: String,
        sni: String
    ): String {
        val authKey = relays.first().authKey

        // IMPORTANT: the mhrv-rs core's TOML schema rejects unknown keys under [relay]
        // (verified from live logs: adding relay_url/parallel_relay makes startProxy
        // return handle 0). The tunnel itself does NOT need Cloudflare � it reaches
        // script.google.com/exec directly, which is not blocked. Cloudflare is used only
        // as a deploy-time proxy to bypass the sanctions block on script.googleapis.com
        // (see GstAutoDeployer). So we keep the tunnel config to the exact proven shape.
        GstLog.i("GstEngine", "Config: single script_id (tunnel runs directly on Google)")

        // Pass ALL deployment ids as an array (the core supports script_id as a list �
        // see config.exit-node.example.toml). The core blacklists any script that returns
        // 403 (e.g. a freshly auto-deployed one the user hasn't authorized yet) for 600s
        // and fails over to a working/authorized one, so a single un-authorized script no
        // longer breaks the whole tunnel.
        val scriptIdArray = relays.joinToString(", ") { "\"${it.deploymentId}\"" }

        return """
            [relay]
            mode = "apps_script"
            script_id = [$scriptIdArray]
            auth_key = "$authKey"
            youtube_via_relay = true

            [network]
            google_ip = "$frontIp"
            front_domain = "$sni"
            listen_host = "127.0.0.1"
            socks5_port = $localPort
            listen_port = ${localPort + 10000}
            verify_ssl = true

            [logging]
            log_level = "debug"
        """.trimIndent()
    }

    /** Redact auth_key / relay secrets before logging the raw config. */
    private fun maskConfig(config: String): String =
        config.replace(Regex("auth_key\\s*=\\s*\"([^\"]*)\"")) {
            "auth_key = \"${GstLog.redact(it.groupValues[1])}\""
        }

    override fun stop() {
        Log.d("GstEngine", "Stopping GST Engine...")
        // Idempotency: a racing second stop() (STOP path vs onDestroy) must not call
        // Native.stopProxy on an already-freed handle.
        if (!isRunning && engineHandle == 0L) {
            Log.d("GstEngine", "GST Engine already stopped — skipping.")
            return
        }
        isRunning = false

        // Stop the log-drain loop and WAIT for its current native drainLogs() call to return
        // before freeing the runtime. Cancelling the job only requests cancellation; if a
        // drainLogs() is in flight it keeps reading native state that stopProxy is about to
        // free → use-after-free crash (surfaces later as a native SIGABRT with nothing in the
        // app's own logcat). runBlocking-join is bounded and cheap here.
        logJob?.let { job ->
            job.cancel()
            try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(1000) { job.join() }
                }
            } catch (_: Exception) {}
        }
        logJob = null

        if (engineHandle != 0L) {
            val stopped = Native.stopProxy(engineHandle)
            Log.d("GstEngine", "GST Engine JNI stopProxy returned: $stopped")
            engineHandle = 0L
        }
    }
}
