package com.mlmvpn.core.warp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The main process's handle on [AwgHostService] (`:awg`) or [AwgProbeService] (`:awgprobe`).
 * Every call blocks its caller briefly and must run off the main thread.
 */
class AwgHostClient(private val service: Class<*>, private val tag: String) {

    @Volatile private var host: Messenger? = null
    private var connection: ServiceConnection? = null
    private val bindLock = Any()

    /** Called when the host process dies under a running tunnel (a native crash over there). */
    @Volatile var onDied: (() -> Unit)? = null

    /** How many times the host process has died; a caller compares it before and after a job. */
    @Volatile var deaths: Int = 0
        private set

    private val replies = HandlerThread("$tag-replies").apply { start() }

    fun call(context: Context, what: Int, data: Bundle?, timeoutMs: Long): Bundle? {
        val h = bind(context.applicationContext) ?: return null
        val latch = CountDownLatch(1)
        val out = AtomicReference<Bundle?>(null)
        val replyTo = Messenger(Handler(replies.looper) { m ->
            out.set(m.data); latch.countDown(); true
        })
        return try {
            h.send(Message.obtain(null, what).apply { this.data = data ?: Bundle(); this.replyTo = replyTo })
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) out.get() else null
        } catch (t: Throwable) {
            Log.w(tag, "call $what failed: ${t.message}")
            null
        }
    }

    fun release(context: Context) {
        synchronized(bindLock) {
            connection?.let { runCatching { context.applicationContext.unbindService(it) } }
            connection = null
            host = null
        }
    }

    private fun bind(app: Context): Messenger? {
        host?.let { return it }
        synchronized(bindLock) {
            host?.let { return it }
            val latch = CountDownLatch(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    host = service?.let { Messenger(it) }
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(tag, "host process went away")
                    host = null
                    deaths++
                    // A fresh bind next time, not Android's automatic rebind of this one: the
                    // caller decides whether the dead process is wanted back.
                    synchronized(bindLock) {
                        if (connection === this) { runCatching { app.unbindService(this) }; connection = null }
                    }
                    onDied?.invoke()
                }
            }
            return try {
                if (!app.bindService(Intent(app, service), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
                    runCatching { app.unbindService(conn) }
                    null
                } else {
                    connection = conn
                    latch.await(5_000, TimeUnit.MILLISECONDS)
                    host
                }
            } catch (t: Throwable) {
                Log.w(tag, "bind failed: ${t.message}")
                null
            }
        }
    }

    companion object {
        /** The tunnel host. One per app. */
        val tunnel = AwgHostClient(AwgHostService::class.java, "AwgHostClient")
        /** The AmneziaWG tester. One per app. */
        val probe = AwgHostClient(AwgProbeService::class.java, "AwgProbeClient")
        /** The Xray test cores, out of the main process (see XrayProbeService). */
        val xray = AwgHostClient(XrayProbeService::class.java, "XrayProbeClient")

        data class Stats(val up: Boolean, val rx: Long, val tx: Long, val handshakeAt: Long)

        fun stats(context: Context): Stats? = tunnel.call(context, AwgHostService.MSG_STATS, null, 3_000)?.let {
            Stats(it.getBoolean(AwgHostService.KEY_UP), it.getLong(AwgHostService.KEY_RX), it.getLong(AwgHostService.KEY_TX), it.getLong(AwgHostService.KEY_HANDSHAKE))
        }
    }
}
