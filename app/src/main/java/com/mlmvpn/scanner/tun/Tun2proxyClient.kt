package com.mlmvpn.scanner.tun

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Starts and stops tun2proxy in the `:tun` process, in place of calling `Native.runTun2proxy`
 * in-process. See [Tun2proxyHostService] for why tun2proxy is not allowed to run in the app's
 * own process, and why the TUN descriptor goes over a bound Messenger rather than an Intent.
 */
object Tun2proxyClient {

    private const val TAG = "Tun2proxyClient"
    private const val BIND_TIMEOUT_MS = 5_000L

    private var connection: ServiceConnection? = null
    @Volatile private var host: Messenger? = null

    /**
     * Hands the TUN to the `:tun` process. Blocks briefly while the service binds, so call it
     * off the main thread (both callers already run on a background dispatcher).
     *
     * @param rawTunFd a fd this process owns and KEEPS owning. Binder duplicates it for the host
     *   rather than transferring it, so the caller must still close its own during teardown --
     *   the TUN stays up only while at least one descriptor for it is open.
     * @param cliArgs the tun2proxy command line WITHOUT `--tun-fd`; the host fills that in with
     *   its own descriptor number, because fd numbers mean nothing across processes.
     * @return false if the host could not be reached, in which case nothing was started.
     */
    fun start(context: Context, rawTunFd: Int, cliArgs: String, mtu: Int): Boolean {
        val app = context.applicationContext
        val messenger = bind(app) ?: run {
            Log.e(TAG, "could not bind Tun2proxyHostService")
            return false
        }

        // fromFd(), never adoptFd(): adoptFd would make the ParcelFileDescriptor the owner and
        // close the caller's descriptor when released, taking the TUN down under the engine
        // that is still using it.
        val pfd = try {
            ParcelFileDescriptor.fromFd(rawTunFd)
        } catch (t: Throwable) {
            Log.e(TAG, "could not wrap tun fd $rawTunFd: ${t.message}")
            return false
        }

        return try {
            val msg = Message.obtain(null, Tun2proxyHostService.MSG_START).apply {
                data = Bundle().apply {
                    putParcelable(Tun2proxyHostService.KEY_TUN_FD, pfd)
                    putString(Tun2proxyHostService.KEY_CLI_ARGS, cliArgs)
                    putInt(Tun2proxyHostService.KEY_MTU, mtu)
                }
            }
            messenger.send(msg)
            Log.i(TAG, "tun2proxy start sent to :tun process")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not send start to host: ${t.message}")
            false
        } finally {
            // The host has its own duplicate now; this one is ours to drop.
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Asks the `:tun` process to tear tun2proxy down, and lets go of it.
     *
     * Returns immediately: the teardown runs over there, and so does the exit(255) that may
     * follow it. Callers do not need to wait for or care about either -- that is the point.
     */
    fun stop(context: Context) {
        try { host?.send(Message.obtain(null, Tun2proxyHostService.MSG_STOP)) } catch (t: Throwable) {
            Log.w(TAG, "could not signal host: ${t.message}")
        }
        host = null
        connection?.let {
            try { context.applicationContext.unbindService(it) } catch (t: Throwable) {
                Log.w(TAG, "unbind failed: ${t.message}")
            }
        }
        connection = null
    }

    /**
     * Binds the host service and waits for the connection. The binding is kept for the life of
     * the tunnel, which is also what keeps the `:tun` process alive.
     */
    private fun bind(app: Context): Messenger? {
        host?.let { return it }

        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                host = service?.let { Messenger(it) }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Expected whenever the native core exits(255) on its way out: the process is
                // gone, we simply forget it. Nothing here needs to react -- not crashing the app
                // is the entire reason tun2proxy lives over there.
                Log.i(TAG, ":tun process went away")
                host = null
            }
        }

        return try {
            val ok = app.bindService(
                Intent(app, Tun2proxyHostService::class.java),
                conn,
                Context.BIND_AUTO_CREATE,
            )
            if (!ok) {
                Log.e(TAG, "bindService returned false")
                try { app.unbindService(conn) } catch (_: Throwable) {}
                return null
            }
            connection = conn
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            host
        } catch (t: Throwable) {
            Log.e(TAG, "bind threw: ${t.message}")
            null
        }
    }
}
