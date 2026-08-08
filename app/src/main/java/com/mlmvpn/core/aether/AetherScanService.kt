package com.mlmvpn.core.aether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mlmvpn.scanner.MainActivity
import com.mlmvpn.scanner.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the Aether process.
 *
 * Why a foreground service:
 *   - Android will not keep a background app's child process alive when the app is
 *     swiped away. The engine outputs a SOCKS5 on 127.0.0.1:20810 — anything dialing
 *     through it (Xray, cloud scan…) needs that listener to live at least as long as
 *     the user wants the tunnel. Foreground service + wake lock equals stickiness.
 *   - The notification is mandatory on Android 13+. It just shows "Aether — متصل" so
 *     the user knows what's running.
 *
 * Lifecycle:
 *   - ACTION_START carries an [AetherOptions] (encoded via parcelable extras).
 *   - ACTION_STOP tears the engine down and stops the service.
 *
 * The notification text tracks the engine state — when the state flow flips to
 * CONNECTED, the title becomes "Aether — متصل شد". When the engine dies, the service
 * runs a short timer (3s) before stopping so a transient reconnect can re-attach to the
 * same notification rather than rip it down then put it back up.
 */
class AetherScanService : Service() {

    private val engine: AetherEngine by lazy { AetherEngine.get(this) }
    private var stopTimer: java.util.concurrent.ScheduledFuture<*>? = null
    private val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    // Cancellable handle for the state collector we launch in observeStateForNotification().
    // Without this, repeated ACTION_START in onStartCommand would stack collectors in
    // GlobalScope and over-write each other's notifications indefinitely.
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification("در حال راه‌اندازی…", null))
        // Mirror state changes into the notification so the user can tell what the
        // foreground service is doing without opening the app.
        engine.state
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val opts = AetherOptions(
                    protocol = AetherProtocol.valueOf(intent.getStringExtra(EXTRA_PROTOCOL) ?: "MASQUE"),
                    scan = AetherScan.valueOf(intent.getStringExtra(EXTRA_SCAN) ?: "TURBO"),
                    ipFamily = AetherIp.valueOf(intent.getStringExtra(EXTRA_IP) ?: "V4"),
                    transport = intent.getStringExtra(EXTRA_TRANSPORT) ?: "h3",
                    fragment = intent.getBooleanExtra(EXTRA_FRAGMENT, false),
                    keepalive = intent.getIntExtra(EXTRA_KEEPALIVE, 5),
                    noize = intent.getStringExtra(EXTRA_NOIZE) ?: "firewall",
                    quickReconnect = intent.getBooleanExtra(EXTRA_QUICK, true),
                    verbose = intent.getBooleanExtra(EXTRA_VERBOSE, false),
                )
                cancelStopTimer()
                // engine.start() now publishes the FAILED state itself, so the notification
                // gets corrected by the state collector. We still consume engine.start's
                // return value to avoid overwriting a brief "خطا" notification with
                // "در حال اتصال…" when the engine didn't actually start.
                val started = engine.start(opts) { err ->
                    Log.w("AetherScanService", err)
                    // Don't update notification here — AetherEngine has already set
                    // AetherState.failed(), and the state collector started below will
                    // emit the proper "Aether — خطا" title within microseconds. If we
                    // also update here, we'd race with the collector and the user could
                    // see "خطا: $err" flash and immediately disappear.
                }
                if (started) {
                    updateNotification(buildNotification("در حال اتصال…", null))
                }
                observeStateForNotification()
            }
            ACTION_STOP -> {
                engine.stop()
                // Hold the notification briefly so a quick reconnect doesn't have to drop
                // and recreate it.
                scheduleStopTimer()
            }
        }
        return START_NOT_STICKY
    }

    private fun observeStateForNotification() {
        // Always cancel the previous collector first so a quick START→STOP→START
        // sequence doesn't leak observers in GlobalScope. dispatchers.Main is the
        // right choice because NotificationManager.notify must run on a Looper thread.
        stateJob?.cancel()
        stateJob = GlobalScope.launch(Dispatchers.Main) {
            engine.state.collect { s ->
                val title = when (s.stage) {
                    AetherStage.CONNECTED -> "Aether — متصل"
                    AetherStage.FAILED, AetherStage.CRASHED -> "Aether — خطا"
                    AetherStage.STOPPED, AetherStage.IDLE -> "Aether — خاموش"
                    else -> "Aether — ${s.stageFa}"
                }
                val body = s.server?.let { "$it${s.rtt?.let { r -> "  •  ${r}" } ?: ""}" }
                updateNotification(buildNotification(title, body))
                if (s.stage == AetherStage.STOPPED || s.stage == AetherStage.IDLE) {
                    scheduleStopTimer()
                } else if (s.connected) {
                    cancelStopTimer()
                }
            }
        }
    }

    private fun scheduleStopTimer() {
        cancelStopTimer()
        stopTimer = executor.schedule({
            stopSelf()
        }, 3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun cancelStopTimer() {
        stopTimer?.cancel(false)
        stopTimer = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(notification: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, body: String?): Notification {
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, AetherScanService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(title)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (body != null) builder.setContentText(body)
        builder.addAction(0, "توقف", stopIntent)
        return builder.build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Aether engine",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "موتور Aether (MASQUE / WireGuard / WARP-in-WARP)"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelStopTimer()
        stateJob?.cancel()
        executor.shutdownNow()
        // Don't stop the engine here — the engine is shared as a singleton, and stopping
        // it would tear down a tunnel that another flow (e.g., the main UI) might want to
        // manage independently of this service.
    }

    companion object {
        // Const fields live in companion because Kotlin forbids `const val` in regular
        // class bodies. NOTIFICATION_ID and CHANNEL_ID sit here for the same reason.
        private const val NOTIFICATION_ID = 7710
        private const val CHANNEL_ID = "aether_engine"
        const val ACTION_START = "com.mlmvpn.scanner.aether.START"
        const val ACTION_STOP = "com.mlmvpn.scanner.aether.STOP"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_SCAN = "scan"
        const val EXTRA_IP = "ip"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_FRAGMENT = "fragment"
        const val EXTRA_KEEPALIVE = "keepalive"
        const val EXTRA_NOIZE = "noize"
        const val EXTRA_QUICK = "quickReconnect"
        const val EXTRA_VERBOSE = "verbose"

        fun start(context: Context, opts: AetherOptions) {
            val i = Intent(context, AetherScanService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROTOCOL, opts.protocol.name)
                putExtra(EXTRA_SCAN, opts.scan.name)
                putExtra(EXTRA_IP, opts.ipFamily.name)
                putExtra(EXTRA_TRANSPORT, opts.transport)
                putExtra(EXTRA_FRAGMENT, opts.fragment)
                putExtra(EXTRA_KEEPALIVE, opts.keepalive)
                putExtra(EXTRA_NOIZE, opts.noize)
                putExtra(EXTRA_QUICK, opts.quickReconnect)
                putExtra(EXTRA_VERBOSE, opts.verbose)
            }
            // Both startForegroundService and the older startService can throw silently on
            // some Android versions (12+ throttling, BackgroundServiceStartNotAllowedException,
            // ANR after the 5-second startForeground deadline). Catching and surfacing the
            // real message to the user beats the previous behaviour, where a start failure
            // left them staring at a button that looked dead.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                android.widget.Toast.makeText(context,
                    "خطا در راه‌اندازی سرویس: ${t.javaClass.simpleName}: ${t.message ?: "—"}",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, AetherScanService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}
