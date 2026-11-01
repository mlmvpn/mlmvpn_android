package com.mlmvpn.core.warp

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.util.concurrent.Semaphore

/**
 * The app's delay tests ([com.mlmvpn.scanner.data.CombineEngine.measureDelay]) in a process of
 * their own (`:xmeasure`).
 *
 * Every "test all" measures public configs, and some of them drive Xray into a Go panic ("close of
 * closed channel": Amnezia 2026-10-01, Outline 2026-10-11 on the user's phone). A Go panic cannot be
 * caught; in the main process it closed the whole app, VPN and all. Here it ends this process: the
 * tests that were running come back empty, the next one starts it again.
 */
class XrayMeasureService : Service() {

    companion object {
        const val MSG_MEASURE = 1
        const val KEY_CONFIG = "config"
        const val KEY_URL = "url"
        const val KEY_DELAY = "delay"
    }

    private val worker = HandlerThread("xray-measure").apply { start() }
    /** As many cores at once as the main process allowed itself (each holds a thread until it returns). */
    private val slots = Semaphore(com.mlmvpn.scanner.utils.ThreadBudget.parallel(64))
    @Volatile private var envReady = false

    private val messenger = Messenger(Handler(worker.looper) { msg ->
        if (msg.what != MSG_MEASURE) return@Handler true
        val json = msg.data?.getString(KEY_CONFIG)
        val url = msg.data?.getString(KEY_URL)
        val replyTo = msg.replyTo
        if (!envReady) {
            com.mlmvpn.scanner.data.CombineEngine.ensureCoreEnv(applicationContext)
            envReady = true
        }
        Thread({
            slots.acquire()
            val v = try {
                if (json == null || url == null) 0L else libv2ray.Libv2ray.measureOutboundDelay(json, url)
            } catch (t: Throwable) { 0L } finally { slots.release() }
            runCatching {
                replyTo?.send(Message.obtain(null, AwgHostService.MSG_REPLY).apply { data = Bundle().apply { putLong(KEY_DELAY, v) } })
            }
        }, "xray-measure-one").apply { isDaemon = true }.start()
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }
}
