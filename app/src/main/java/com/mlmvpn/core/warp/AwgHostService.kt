package com.mlmvpn.core.warp

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import org.amnezia.awg.backend.AbstractBackend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream

/**
 * Runs the AmneziaWG tunnel in its own OS process (`:awg`, see AndroidManifest).
 *
 * WHY
 * ---
 * AmneziaWG (libam-go) and Xray (libgojni) each bring up a gomobile Go runtime, and two of them in
 * one process crash it (SIGSEGV/SIGABRT in the second one's init). Until now the app lived with
 * that by restarting itself whenever it switched between the two. The «آمنزیا» section cannot:
 * it measures servers with Xray and connects them with AmneziaWG, often seconds apart. So the
 * AmneziaWG half moves here, and the library's own VpnService with it (it must be in the same
 * process as the GoBackend that waits for it -- the library hands it over through a static
 * future). The main process never loads libam-go at all.
 *
 * Everything else stays where it was: MyVpnService still owns the session (notification, state
 * flows, the Quick Settings tile); it just asks this process to bring the tunnel up and down.
 */
class AwgHostService : Service() {

    companion object {
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val MSG_STATS = 3
        const val MSG_REPLY = 100

        const val KEY_CONFIG = "config"
        const val KEY_OK = "ok"
        const val KEY_ERROR = "error"
        const val KEY_RX = "rx"
        const val KEY_TX = "tx"
        const val KEY_HANDSHAKE = "hs"
        const val KEY_UP = "up"

        private const val TAG = "AwgHost"
    }

    private val actions = object : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>) {}
        override fun runPostUp(scripts: MutableCollection<String>) {}
        override fun runPreDown(scripts: MutableCollection<String>) {}
        override fun runPostDown(scripts: MutableCollection<String>) {}
    }

    // Must be the SAME instance for UP and DOWN: GoBackend matches the active tunnel by identity.
    private var backend: GoBackend? = null
    private var tunnel: Tunnel? = null

    // Native calls block (the handshake setup, DNS for the endpoint): never on the main thread.
    private val worker = HandlerThread("awg-host").apply { start() }
    private val messenger = Messenger(Handler(worker.looper) { msg -> handle(msg); true })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        runCatching { down() }
        worker.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        val reply = Bundle()
        when (msg.what) {
            MSG_START -> {
                val cfg = msg.data?.getString(KEY_CONFIG)
                val error = if (cfg == null) "no config" else up(cfg)
                reply.putBoolean(KEY_OK, error == null)
                error?.let { reply.putString(KEY_ERROR, it) }
            }
            MSG_STOP -> { down(); reply.putBoolean(KEY_OK, true) }
            MSG_STATS -> stats(reply)
            else -> return
        }
        runCatching { msg.replyTo?.send(Message.obtain(null, MSG_REPLY).apply { data = reply }) }
    }

    /** Null on success, or why it failed (never the config itself: it holds the private key). */
    private fun up(config: String): String? {
        down()
        return try {
            val b = GoBackend(applicationContext, actions)
            val t = object : Tunnel {
                override fun getName() = "amneziawg0"
                override fun onStateChange(state: Tunnel.State) {}
                override fun isIpv4ResolutionPreferred(): Boolean = true
            }
            backend = b; tunnel = t
            val parsed = Config.parse(ByteArrayInputStream(config.toByteArray()))
            b.setState(t, Tunnel.State.UP, parsed)
            if (b.getState(t) == Tunnel.State.UP) null else { down(); "tunnel did not come up" }
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.javaClass.simpleName} ${e.message}")
            down()
            e.javaClass.simpleName + (e.message?.let { ": ${it.take(120)}" } ?: "")
        }
    }

    /**
     * Down, and the library's VpnService stopped: it never stops itself (no stopSelf anywhere in
     * amneziawg-android 2.1.13), and an orphan keeps a stale owner that breaks the next connect.
     */
    private fun down() {
        val b = backend; val t = tunnel
        backend = null; tunnel = null
        if (b != null && t != null) {
            runCatching { b.setState(t, Tunnel.State.DOWN, null) }.onFailure { Log.w(TAG, "DOWN threw: ${it.message}") }
        }
        if (b != null) runCatching { stopService(Intent(this, AbstractBackend.VpnService::class.java)) }
    }

    private fun stats(out: Bundle) {
        val b = backend; val t = tunnel
        if (b == null || t == null) { out.putBoolean(KEY_UP, false); return }
        runCatching {
            val s = b.getStatistics(t)
            var hs = 0L
            s.peers().forEach { k -> s.peer(k)?.let { p -> hs = maxOf(hs, p.latestHandshakeEpochMillis()) } }
            out.putBoolean(KEY_UP, b.getState(t) == Tunnel.State.UP)
            out.putLong(KEY_RX, s.totalRx())
            out.putLong(KEY_TX, s.totalTx())
            out.putLong(KEY_HANDSHAKE, hs)
        }.onFailure { out.putBoolean(KEY_UP, false) }
    }
}
