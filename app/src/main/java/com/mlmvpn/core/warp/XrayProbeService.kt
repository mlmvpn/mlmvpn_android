package com.mlmvpn.core.warp

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.runBlocking

/**
 * Runs the «آمنزیا» delay-test cores in their own process (`:xprobe`).
 *
 * WHY
 * ---
 * A test core holds dozens of public servers at once, and some of them drive Xray into a Go panic
 * ("close of closed channel", measured on a phone 2026-10-01). A Go panic cannot be caught: it
 * aborts the whole process. Here it aborts only this one -- the app, its VPN and the test carry
 * on, and the batch that was running is simply tried again in smaller pieces.
 *
 * The core listens on loopback SOCKS ports; the main process measures through them directly
 * (loopback is loopback from any process), so only start and stop cross the process boundary.
 */
class XrayProbeService : Service() {

    companion object {
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val KEY_CONFIG = "config"
        const val KEY_OK = "ok"
    }

    private var core: VlessXrayInjector? = null
    private val worker = HandlerThread("xray-probe").apply { start() }
    private val messenger = Messenger(Handler(worker.looper) { msg ->
        val reply = Bundle()
        when (msg.what) {
            MSG_START -> {
                stopCore()
                val cfg = msg.data?.getString(KEY_CONFIG)
                val c = VlessXrayInjector(0)
                val ok = cfg != null && runCatching { runBlocking { c.start(applicationContext, cfg, 0) } }.getOrDefault(false)
                if (ok) core = c else runCatching { c.stop() }
                reply.putBoolean(KEY_OK, ok)
            }
            MSG_STOP -> { stopCore(); reply.putBoolean(KEY_OK, true) }
        }
        runCatching { msg.replyTo?.send(Message.obtain(null, AwgHostService.MSG_REPLY).apply { data = reply }) }
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        stopCore()
        worker.quitSafely()
        super.onDestroy()
    }

    private fun stopCore() {
        core?.let { runCatching { it.stop() } }
        core = null
    }
}
