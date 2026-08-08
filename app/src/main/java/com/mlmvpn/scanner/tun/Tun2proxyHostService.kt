package com.mlmvpn.scanner.tun

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Runs tun2proxy in its own OS process (`:tun`, see AndroidManifest).
 *
 * WHY THIS EXISTS
 * ---------------
 * The tun2proxy native core does not let its host process outlive it. A few seconds after a
 * teardown that looks completely clean it calls exit(255) from native code: no signal, no
 * tombstone, no Java exception, nothing in logcat -- ApplicationExitInfo just reports
 * REASON_EXIT_SELF status=255. Nothing in-process can catch or prevent a clean native exit,
 * and the app's own comments record the same behaviour for both engines that use it
 * (AetherTunEngine and GstTunEngine, and see EmergencyLevel2Screen).
 *
 * Because it cannot be prevented, the only real fix is to make it not matter: run tun2proxy
 * somewhere whose death costs nothing. When this process exits(255), Android reaps it and the
 * app's main process -- UI, VPN service, engine state, everything the user is looking at --
 * never notices.
 *
 * WHY ONLY THIS PIECE
 * -------------------
 * Moving MyVpnService itself into another process would strand ~50 call sites that read its
 * state statically, plus AetherEngine's state flow that the Aether screen renders live, and
 * would need an IPC bridge for all of it. tun2proxy needs exactly two things -- a TUN fd and a
 * `socks5://127.0.0.1:PORT` address -- and both cross a process boundary: file descriptors ride
 * a binder transaction, and loopback is loopback from any process.
 *
 * WHY A MESSENGER AND NOT startService EXTRAS
 * -------------------------------------------
 * A ParcelFileDescriptor cannot travel in an Intent handed to startService. That Intent is
 * parceled by ActivityManager on its way through, and the platform refuses to serialize file
 * descriptors there ("Not allowed to write file descriptors here"). A bound Messenger is a
 * DIRECT binder transaction between the two processes, which is exactly where fd passing is
 * allowed. Sending the fd the wrong way makes the start throw, the engine abort, and the tunnel
 * come up and drop immediately.
 *
 * OWNERSHIP OF THE TUN FD
 * -----------------------
 * Binder DUPLICATES the descriptor on the way in: the sender keeps its own, this process gets
 * another. Both stay open for the life of the tunnel (the TUN goes down when the last one
 * closes), and tun2proxy always runs with `--close-fd-on-drop false` so it never closes a
 * descriptor it does not own -- the fdsan abort that produces is the one native crash on this
 * app that ever left a readable backtrace.
 */
class Tun2proxyHostService : Service() {

    companion object {
        const val MSG_START = 1
        const val MSG_STOP = 2

        /** ParcelFileDescriptor for the TUN. Duplicated by binder; this process owns its copy. */
        const val KEY_TUN_FD = "tun_fd"
        /** Everything for the tun2proxy CLI except `--tun-fd`, which is process-local. */
        const val KEY_CLI_ARGS = "cli_args"
        const val KEY_MTU = "mtu"

        private const val TAG = "Tun2proxyHost"

        /**
         * How long to let tun2proxy unwind by itself before signalling it.
         *
         * Generous for the reason AetherTunEngine documents: measured on device the natural
         * shutdown finished 3.10s after stop while the grace period was 3.00s, so the
         * cooperative stop fired 90ms before the worker would have exited on its own -- putting
         * a second shutdown into the library while the first was still unwinding.
         */
        private const val GRACE_JOIN_MS = 6_000L
    }

    private var worker: Thread? = null
    private var tunPfd: ParcelFileDescriptor? = null
    @Volatile private var running = false

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MSG_START -> { start(msg); true }
            MSG_STOP -> { stopTun2proxy(); stopSelf(); true }
            else -> false
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun start(msg: Message) {
        if (running) {
            Log.w(TAG, "start ignored: tun2proxy already running in this process")
            return
        }
        val data = msg.data
        data.classLoader = javaClass.classLoader
        @Suppress("DEPRECATION")
        val pfd = data.getParcelable<ParcelFileDescriptor>(KEY_TUN_FD)
        val baseArgs = data.getString(KEY_CLI_ARGS)
        val mtu = data.getInt(KEY_MTU, 1500)
        if (pfd == null || baseArgs == null) {
            Log.e(TAG, "start missing tun fd or args -- nothing to run")
            return
        }
        tunPfd = pfd
        // The fd NUMBER is process-local, so it is filled in here rather than by the caller.
        val cliArgs = "$baseArgs --tun-fd ${pfd.fd}"
        Log.i(TAG, "starting tun2proxy in :tun process (fd=${pfd.fd}, mtu=$mtu)")

        running = true
        worker = Thread({
            try {
                val rc = com.therealaleph.mhrv.Native.runTun2proxy(cliArgs, mtu)
                Log.i(TAG, "tun2proxy exited rc=$rc")
            } catch (t: Throwable) {
                Log.e(TAG, "tun2proxy threw: ${t.message}")
            } finally {
                running = false
            }
        }, "tun2proxy-host").apply { start() }
    }

    /**
     * Mirrors the teardown AetherTunEngine used to perform in-process: let the worker settle on
     * its own first, and only signal the library if it is still up. Two shutdowns racing through
     * the same native teardown is what produced the delayed crash in the first place.
     *
     * If it exits(255) anyway, that now kills only this process.
     */
    private fun stopTun2proxy() {
        val w = worker ?: return
        worker = null

        val deadline = System.currentTimeMillis() + GRACE_JOIN_MS
        while (System.currentTimeMillis() < deadline) {
            if (!w.isAlive) break
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }

        if (w.isAlive && running) {
            Log.i(TAG, "tun2proxy still running after grace period -- signalling stop")
            val stopper = Thread({
                try { com.github.shadowsocks.bg.Tun2proxy.stop() } catch (t: Throwable) {
                    Log.w(TAG, "Tun2proxy.stop threw: ${t.message}")
                }
            }, "tun2proxy-host-stop").apply { start() }
            try { stopper.join(2_000) } catch (_: InterruptedException) {}
        }

        try { w.join(4_000) } catch (_: InterruptedException) {}
        if (w.isAlive) Log.w(TAG, "tun2proxy thread still alive after join -- proceeding")

        // Our duplicate of the TUN descriptor. The VPN process still holds its own.
        try { tunPfd?.close() } catch (t: Throwable) { Log.w(TAG, "closing tun pfd: ${t.message}") }
        tunPfd = null
    }

    override fun onDestroy() {
        stopTun2proxy()
        super.onDestroy()
    }
}
