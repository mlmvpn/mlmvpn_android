package com.mlmvpn.scanner.utils

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * startService() that never takes the app down.
 *
 * Android 8+ refuses startService() while the app is in the background (Android 12+ throws
 * BackgroundServiceStartNotAllowedException, 8-11 a plain IllegalStateException). The app hits
 * that constantly: the network comes back with the screen off and the VPN reconnects itself, a
 * timer disconnects after the screen goes off, a connect finishes after the user has left the
 * screen. Each of those crashed the whole app -- about half of every crash report through 1.2.39.
 *
 * A command for a service that is already running does not need startService() at all: it is
 * handed to the live instance on the main thread, which is all startService() would have done.
 * A service that is not running and may not be started now is logged and skipped.
 */
object SafeStart {

    private const val TAG = "SafeStart"
    private val live = ConcurrentHashMap<String, Service>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Services call this in onCreate, so commands can reach them without startService(). */
    fun attach(s: Service) { live[s.javaClass.name] = s }

    /** …and this in onDestroy. */
    fun detach(s: Service) { live.remove(s.javaClass.name, s) }

    /** Starts [intent]'s service, or hands the command to the running instance. False if neither was possible. */
    fun service(ctx: Context, intent: Intent): Boolean = try {
        ctx.startService(intent)
        true
    } catch (e: IllegalStateException) {
        deliver(intent, e)
    } catch (e: SecurityException) {
        Log.w(TAG, "start refused: ${e.javaClass.simpleName}")
        false
    } catch (e: RuntimeException) {
        // DeadSystemRuntimeException: the system server is restarting (crash #730). Nothing can
        // be started now, and the app must not die for it.
        Log.w(TAG, "start failed: ${e.javaClass.simpleName}")
        false
    }

    /** The same command, straight to the running instance of its service. */
    fun deliver(intent: Intent, cause: Throwable? = null): Boolean {
        val s = intent.component?.className?.let { live[it] }
        if (s == null) {
            Log.w(TAG, "${intent.component?.shortClassName} ${intent.action ?: "start"} skipped: app in background, service not running (${cause?.javaClass?.simpleName})")
            return false
        }
        val run = Runnable {
            runCatching { s.onStartCommand(intent, 0, 0) }
                .onFailure { Log.w(TAG, "direct delivery failed: ${it.javaClass.simpleName}") }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
        return true
    }
}
