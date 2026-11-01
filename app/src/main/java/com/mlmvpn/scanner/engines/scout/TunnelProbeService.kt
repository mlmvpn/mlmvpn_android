package com.mlmvpn.scanner.engines.scout

import android.app.Service
import android.content.Intent
import android.os.*
import com.mlmvpn.core.tunnel.TunnelEngine
import com.mlmvpn.core.warp.AwgHostService
import org.json.JSONObject
import java.io.File

/** A bound, non-exported process dedicated to one Scout native-core session. No device TUN. */
class TunnelProbeService : Service() {
    companion object {
        const val OPEN = 1; const val START = 2; const val STATUS = 3; const val STOP = 4
        const val TOKEN = "token"; const val PID = "pid"; const val OK = "ok"
        const val CONFIG = "config"; const val PHASE = "phase"
    }
    private val control = Handler(Looper.getMainLooper())
    private var token: String? = null
    private var worker: Thread? = null
    @Volatile private var retiring = false
    @Volatile private var phase = "idle"
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
        val data = request.data
        val supplied = data.getString(TOKEN).orEmpty()
        var ok = false
        when (request.what) {
            OPEN -> if (token == null && supplied.matches(Regex("[a-f0-9-]{36}"))) {
                token = supplied; ok = true
            }
            START -> if (token == supplied && !retiring && worker == null) {
                val config = runCatching { JSONObject(data.getString(CONFIG).orEmpty()) }.getOrNull()
                val directory = File(filesDir, "scout/tunnel/runs/$supplied")
                if (config != null && ScoutTunnelPolicy.owned(config, directory)) {
                    phase = "preparing"
                    worker = Thread({
                        try {
                            System.getenv().keys.filter { it.startsWith("AETHER_") || it.lowercase().endsWith("_proxy") }
                                .forEach { android.system.Os.unsetenv(it) }
                            check(TunnelEngine.isAvailable)
                            TunnelEngine.prepareForProbe(config.toString())
                            if (!retiring) {
                                phase = "running"
                                TunnelEngine.startProxyForProbe(config.toString())
                            }
                        } catch (_: Throwable) { }
                        finally { phase = if (retiring) "stopped" else "failed" }
                    }, "scout-native-core").apply { start() }
                    ok = true
                }
            }
            STATUS -> ok = token == supplied
            STOP -> if (token == supplied) { ok = true; retire() }
        }
        val response = Bundle().apply {
            putBoolean(OK, ok); putInt(PID, android.os.Process.myPid())
            putString(TOKEN, token); putString(PHASE, phase)
        }
        runCatching { request.replyTo?.send(Message.obtain(null, AwgHostService.MSG_REPLY).apply { this.data = response }) }
        true
    })

    private fun retire() {
        if (retiring) return
        retiring = true
        worker?.interrupt()
        if (worker != null) Thread({ runCatching { TunnelEngine.stopForProbe() } }, "scout-native-stop").start()
        // JNI prepare/stop may wait on each other; control stays responsive and only this process exits.
        control.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 750)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onUnbind(intent: Intent?): Boolean { retire(); return false }
    override fun onDestroy() { retire(); super.onDestroy() }
}
