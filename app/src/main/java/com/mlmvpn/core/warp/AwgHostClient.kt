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
class AwgHostClient(
    private val service: Class<*>,
    private val tag: String,
    /** How long a call waits for the host's process to come up and bind. */
    private val bindWaitMs: Long = 5_000,
) {

    @Volatile private var host: Messenger? = null
    @Volatile private var connection: ServiceConnection? = null
    private val bindLock = Any()
    /** The bind in flight, which a second caller waits for outside the lock instead of starting another. */
    private var pending: CountDownLatch? = null

    /** Called when the host process dies under a running tunnel (a native crash over there). */
    @Volatile var onDied: (() -> Unit)? = null

    /** How many times the host process has died; a caller compares it before and after a job. */
    @Volatile var deaths: Int = 0
        private set

    private val replies = HandlerThread("$tag-replies").apply { start() }

    /** The host's binder whose death [deaths] already counted, so the disconnect that follows does not count it twice. */
    @Volatile private var countedDead: IBinder? = null
    /** Guards [countedDead] and [deaths] only, never held while waiting: the disconnect callback takes it on the main thread. */
    private val deathLock = Any()

    fun call(context: Context, what: Int, data: Bundle?, timeoutMs: Long): Bundle? = callDetailed(context, what, data, timeoutMs).reply

    /** What a call came back with, and whether the host's process ended under it (no reply was ever coming). */
    class Result(val reply: Bundle?, val died: Boolean)

    /**
     * [call], telling a host that died under the call from one that did not answer in time. A death
     * ends the wait at once: before, the caller sat out the whole timeout (20 s for a core start)
     * waiting on a process that was already gone.
     */
    fun callDetailed(context: Context, what: Int, data: Bundle?, timeoutMs: Long): Result {
        val first = bind(context.applicationContext) ?: return Result(null, false)
        // A host that ended itself a moment ago (XrayProbeService does after every stop) can still be
        // the bound one until the system reports it gone: a send to it fails at once, and the call
        // goes to a fresh process instead.
        val h = if (first.binder.pingBinder()) first else {
            forget(context.applicationContext, first)
            bind(context.applicationContext) ?: return Result(null, false)
        }
        val latch = CountDownLatch(1)
        val out = AtomicReference<Bundle?>(null)
        val died = java.util.concurrent.atomic.AtomicBoolean(false)
        val replyTo = Messenger(Handler(replies.looper) { m ->
            out.set(m.data); latch.countDown(); true
        })
        val binder = h.binder
        val death = IBinder.DeathRecipient {
            synchronized(deathLock) { if (countedDead !== binder) { countedDead = binder; deaths++ } }
            died.set(true)
            latch.countDown()
        }
        if (runCatching { binder.linkToDeath(death, 0) }.isFailure) {
            forget(context.applicationContext, h)
            return Result(null, true)
        }
        return try {
            h.send(Message.obtain(null, what).apply { this.data = data ?: Bundle(); this.replyTo = replyTo })
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val reply = out.get()
            Result(reply, reply == null && died.get())
        } catch (t: Throwable) {
            Log.w(tag, "call $what failed: ${t.message}")
            Result(null, !binder.isBinderAlive)
        } finally {
            runCatching { binder.unlinkToDeath(death, 0) }
        }
    }

    private fun forget(app: Context, dead: Messenger) {
        synchronized(bindLock) {
            if (host !== dead) return
            connection?.let { runCatching { app.unbindService(it) } }
            connection = null
            host = null
            synchronized(deathLock) { if (countedDead !== dead.binder) { countedDead = dead.binder; deaths++ } }
        }
    }

    fun release(context: Context) {
        synchronized(bindLock) {
            connection?.let { runCatching { context.applicationContext.unbindService(it) } }
            connection = null
            host = null
        }
    }

    /**
     * The host, bound if need be. [bindLock] is held only to set the bind up, never while waiting for
     * it: onServiceConnected arrives on the main thread, and anything there that needs the lock (a
     * disconnect callback, [release] during a VPN stop) froze the app for the whole wait while a
     * worker sat in here holding it (freeze report #1026, 2026-10-11).
     */
    private fun bind(app: Context): Messenger? {
        host?.let { return it }
        val latch: CountDownLatch = synchronized(bindLock) {
            host?.let { return it }
            pending?.let { return@synchronized it }
            val latch = CountDownLatch(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    // A connection released while it was still binding is not the host any more.
                    if (connection === this) host = service?.let { Messenger(it) }
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(tag, "host process went away")
                    val gone = host?.binder
                    host = null
                    synchronized(deathLock) {
                        // A call that was waiting on it may have counted this death already.
                        if (gone == null || countedDead !== gone) { countedDead = gone; deaths++ }
                    }
                    // A fresh bind next time, not Android's automatic rebind of this one: the
                    // caller decides whether the dead process is wanted back. Off the main thread:
                    // this callback runs there, and a bind() on a worker holds [bindLock] while it
                    // waits for onServiceConnected -- which also runs there. Taking the lock here
                    // froze the app for the bind's whole 5 s wait, again for every dead test process
                    // (Outline's WARP probe on a tester's phone, 2026-10-11, freeze report #1026).
                    val conn = this
                    // On the replies thread this client already has, not a new one per death: a
                    // phone out of thread memory threw OutOfMemoryError ("pthread_create failed")
                    // right here and closed the app (1.2.41).
                    val unbind = Runnable {
                        synchronized(bindLock) {
                            if (connection === conn) { runCatching { app.unbindService(conn) }; connection = null }
                        }
                    }
                    if (!Handler(replies.looper).post(unbind)) runCatching { Thread(unbind, "$tag-unbind").apply { isDaemon = true }.start() }
                    runCatching { onDied?.invoke() }.onFailure { Log.w(tag, "after the host died: ${it.javaClass.simpleName}") }
                }
            }
            // Before bindService: its onServiceConnected may run on the main thread before this
            // worker gets past the call, and it checks that it is still the connection.
            connection = conn
            try {
                if (!app.bindService(Intent(app, service), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
                    connection = null
                    runCatching { app.unbindService(conn) }
                    return null
                }
            } catch (t: Throwable) {
                connection = null
                Log.w(tag, "bind failed: ${t.message}")
                return null
            }
            pending = latch
            latch
        }
        try {
            latch.await(bindWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(bindLock) { if (pending === latch) pending = null }
        return host
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
