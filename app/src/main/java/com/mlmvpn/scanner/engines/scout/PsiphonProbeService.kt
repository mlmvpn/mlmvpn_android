package com.mlmvpn.scanner.engines.scout

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import ca.psiphon.PsiphonTunnel
import com.mlmvpn.core.warp.AwgHostService

/**
 * Scout's Psiphon test in a process of its own. Psiphon's library carries its own Go runtime, and
 * the main process already has Xray's: two Go runtimes in one process abort it ("unexpected return
 * pc for runtime.cgocallback", seen on a Galaxy S26 Ultra on 2026-10-10, crash #5).
 *
 * «سایفون»'s real tests ([com.mlmvpn.core.tunnel.PsiphonProbes]) run on the same protocol and keep
 * one process for a whole run: [HALT] stops a core and keeps the process for the next [START].
 */
open class PsiphonProbeService : Service() {
    companion object {
        const val OPEN = 1; const val START = 2; const val STATUS = 3; const val STOP = 4
        /**
         * Stop the running core and wait for it, keeping the process (and its token) for the next
         * [START]. The reply comes once the core is down, so its data folder can be deleted.
         */
        const val HALT = 5
        const val TOKEN = "token"; const val OK = "ok"; const val CONFIG = "config"
        const val PHASE = "phase"; const val PORT = "port"
        /** START: the server entries to load instead of the shipped list (one encoded entry per line). */
        const val ENTRIES = "entries"
        /**
         * STATUS: the address the connected tunnel was dialled at (ConnectedServer ·
         * meekResolvedIPAddress), when the config emits network parameters; empty otherwise.
         */
        const val IP = "ip"
    }
    private val control = Handler(Looper.getMainLooper())
    @Volatile private var token: String? = null
    /** Guards [tunnel] against [generation]: a core is only ever started for the current generation. */
    private val lock = Any()
    @Volatile private var tunnel: PsiphonTunnel? = null
    @Volatile private var retiring = false
    /** idle, connecting, connected, failed. */
    @Volatile private var phase = "idle"
    @Volatile private var port = 0
    @Volatile private var dialed = ""

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
        val data = request.data
        val supplied = data.getString(TOKEN).orEmpty()
        // The message is recycled when this returns; a reply sent later needs its own reference.
        val replyTo = request.replyTo
        var ok = false
        when (request.what) {
            OPEN -> if (token == null && supplied.matches(Regex("[a-f0-9-]{36}"))) { token = supplied; ok = true }
            // A START while one runs is the next rung of the ladder: in this process, as the tile does
            // it. A process per rung was restarted by the system under the next rung's bind and
            // killed half a second into it (A50, 2026-10-11).
            START -> if (token == supplied && !retiring) {
                val config = data.getString(CONFIG).orEmpty()
                if (config.isNotBlank()) { start(config, data.getString(ENTRIES)); ok = true }
            }
            STATUS -> ok = token == supplied
            STOP -> if (token == supplied) { ok = true; retire() }
            HALT -> if (token == supplied && !retiring) {
                halt { reply(replyTo, true) }
                return@Handler true
            }
        }
        reply(replyTo, ok)
        true
    })

    private fun reply(to: Messenger?, ok: Boolean) {
        val response = Bundle().apply {
            putBoolean(OK, ok); putString(TOKEN, token); putString(PHASE, phase); putInt(PORT, port); putString(IP, dialed)
        }
        runCatching { to?.send(Message.obtain(null, AwgHostService.MSG_REPLY).apply { this.data = response }) }
    }

    @Volatile private var generation = 0

    private fun start(config: String, entriesGiven: String?) {
        val mine: Int
        val previous: PsiphonTunnel?
        synchronized(lock) {
            phase = "connecting"
            port = 0
            dialed = ""
            mine = ++generation
            previous = tunnel
            tunnel = null
        }
        val app = applicationContext
        val host = object : PsiphonTunnel.HostService {
            override fun getContext(): Context = app
            override fun getPsiphonConfig(): String = config
            override fun onListeningSocksProxyPort(p: Int) { if (mine == generation) port = p }
            override fun onConnected() { if (!retiring && mine == generation) phase = "connected" }
            override fun onExiting() { if (mine == generation && phase != "connected") phase = "failed" }
            // Psiphon's own account of what it tried, in logcat: without it a «no server in time»
            // could not be told apart from a broken test.
            override fun onDiagnosticMessage(message: String) {
                if (mine == generation && message.startsWith("ConnectedServer")) {
                    Regex("\"meekResolvedIPAddress\":\"([^\"]*)\"").find(message)?.groupValues?.get(1)?.let { dialed = it }
                }
                android.util.Log.d("PsiphonProbe", message.take(400))
            }
            override fun onConnecting() { android.util.Log.i("PsiphonProbe", "connecting") }
        }
        Thread({
            try {
                if (previous != null) {
                    runCatching { previous.stop() }
                    Thread.sleep(1_200)
                }
                if (retiring || mine != generation) return@Thread
                val entries = entriesGiven ?: runCatching {
                    com.mlmvpn.scanner.store.StoreFiles.open(app, "server_entries.txt").bufferedReader().use { it.readText().trim() }
                }.getOrDefault("")
                val t = PsiphonTunnel.newPsiphonTunnel(host)
                // Checked and set together with [halt]'s take: a HALT that came in while the core was
                // being made leaves this generation stale, and the core is never started.
                synchronized(lock) {
                    if (retiring || mine != generation) return@Thread
                    tunnel = t
                }
                t.setVpnMode(false)
                t.startTunneling(entries)
            } catch (_: Throwable) { if (mine == generation) phase = "failed" }
        }, "scout-psiphon").start()
    }

    /** The running core down (waited for), the process kept; [done] runs once it is. */
    private fun halt(done: () -> Unit) {
        Thread({
            val t = synchronized(lock) {
                generation++
                phase = "idle"
                port = 0
                dialed = ""
                tunnel.also { tunnel = null }
            }
            // Psiphon's stop waits for its own goroutines -- and for a start still in progress.
            runCatching { t?.stop() }
            done()
        }, "scout-psiphon-halt").start()
    }

    private fun retire() {
        if (retiring) return
        retiring = true
        tunnel?.let { t -> Thread({ runCatching { t.stop() } }, "scout-psiphon-stop").start() }
        // Psiphon's stop can wait on its own goroutines; only this process exits.
        control.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 750)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onUnbind(intent: Intent?): Boolean { retire(); return false }
    override fun onDestroy() { retire(); super.onDestroy() }
}
