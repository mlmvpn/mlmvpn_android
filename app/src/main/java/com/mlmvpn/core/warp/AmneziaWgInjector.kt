package com.mlmvpn.core.warp

import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Brings an AmneziaWG tunnel up for MyVpnService.
 *
 * The tunnel itself runs in the `:awg` process ([AwgHostService]); this is the main process's
 * side of it. AmneziaWG's Go runtime must never load next to Xray's, and the «آمنزیا» section
 * tests with Xray while it connects with AmneziaWG -- see AwgHostService for the whole story.
 *
 * The teardown rules the in-process version learned still hold over there: the same GoBackend
 * and Tunnel instance for UP and DOWN, nothing left half-up after a failed start, and the
 * library's own VpnService stopped by hand after DOWN.
 */
class AmneziaWgInjector(@Suppress("unused") private val fd: Int) : IVpnEngine {
    private var appContext: Context? = null
    @Volatile private var started = false

    /** Why the last start failed (for the log; never the config). */
    var lastError: String? = null
        private set

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean = withContext(Dispatchers.IO) {
        // SECURITY: never log the raw config -- it holds the PrivateKey and the server Endpoint.
        Log.d(TAG, "start(): config length=${config.length}")
        val app = context.applicationContext
        appContext = app
        val reply = AwgHostClient.tunnel.call(app, AwgHostService.MSG_START, Bundle().apply {
            putString(AwgHostService.KEY_CONFIG, config)
        }, START_TIMEOUT_MS)
        val ok = reply?.getBoolean(AwgHostService.KEY_OK) == true
        lastError = if (ok) null else (reply?.getString(AwgHostService.KEY_ERROR) ?: "no answer from the :awg process")
        Log.d(TAG, "start(): ${if (ok) "UP" else "failed ($lastError)"}")
        started = ok
        if (!ok) AwgHostClient.tunnel.release(app)
        ok
    }

    override fun stop() {
        com.mlmvpn.scanner.CrashReporter.note("AmneziaWgInjector.stop() begin")
        val app = appContext
        if (app != null) {
            AwgHostClient.tunnel.onDied = null
            if (started) AwgHostClient.tunnel.call(app, AwgHostService.MSG_STOP, null, STOP_TIMEOUT_MS)
            AwgHostClient.tunnel.release(app)
        }
        started = false
        appContext = null
        com.mlmvpn.scanner.CrashReporter.note("AmneziaWgInjector.stop() end")
    }

    companion object {
        private const val TAG = "AmneziaWgInjector"
        private const val START_TIMEOUT_MS = 25_000L
        private const val STOP_TIMEOUT_MS = 4_000L
    }
}
