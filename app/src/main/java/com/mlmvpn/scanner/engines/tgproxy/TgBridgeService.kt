package com.mlmvpn.scanner.engines.tgproxy

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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.utils.SafeStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the Telegram bridge alive while Telegram uses it: Telegram talks to 127.0.0.1, so the
 * bridge has to outlive the app's screen. The notification says which address carries it and has a
 * «خاموش» button; nothing runs once it is stopped.
 */
class TgBridgeService : Service() {

    companion object {
        const val ACTION_START = "com.mlmvpn.scanner.TG_BRIDGE_START"
        const val ACTION_STOP = "com.mlmvpn.scanner.TG_BRIDGE_STOP"
        const val EXTRA_ID = "service_id"
        private const val CHANNEL_ID = "tg_bridge"
        private const val NOTIFICATION_ID = 5611
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var run: Job? = null
    private var watch: Job? = null

    override fun onCreate() {
        super.onCreate()
        SafeStart.attach(this)
        createChannel()
        goForeground()
        watch = scope.launch {
            TgBridgeEngine.state.collectLatest { s ->
                val text = when (s.phase) {
                    TgBridgeEngine.Phase.PICKING -> tr("در حال پیدا کردن آی‌پی تمیز…", "Finding a clean IP…")
                    TgBridgeEngine.Phase.ON -> tr("روشن · ${s.ip} · ${s.streams} اتصال", "On · ${s.ip} · ${s.streams} connections")
                    else -> return@collectLatest
                }
                runCatching { NotificationManagerCompat.from(this@TgBridgeService).notify(NOTIFICATION_ID, build(text)) }
            }
        }
    }

    /**
     * In the foreground, for every start command and not only the first: each startForegroundService
     * must be answered by startForeground, and a start that reached an instance already on its way
     * down (stopped a moment before, not yet destroyed) was not -- ForegroundServiceDidNotStartInTime
     * closed the app when the bridge was switched off and on again quickly (1.2.41).
     */
    private fun goForeground() {
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(tr("در حال پیدا کردن آی‌پی تمیز…", "Finding a clean IP…")),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure { android.util.Log.w("TgBridgeService", "could not go foreground: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_STOP -> stopCleanly()
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_ID)
                if (id == null) stopCleanly() else {
                    run?.cancel()
                    run = scope.launch {
                        try {
                            TgBridgeEngine.run(applicationContext, id)
                        } finally {
                            // run() returns only when it could not start; a stop cancels it.
                            if (TgBridgeEngine.state.value.phase == TgBridgeEngine.Phase.FAILED) stopCleanly()
                        }
                    }
                }
            }
            // Restarted by the system without a command: nothing to resume safely.
            else -> stopCleanly()
        }
        return START_NOT_STICKY
    }

    private fun stopCleanly() {
        run?.cancel()
        run = null
        TgBridgeEngine.ended()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        run?.cancel()
        watch?.cancel()
        scope.cancel()
        TgBridgeEngine.ended()
        SafeStart.detach(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, tr("پل تلگرام", "Telegram bridge"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = tr("پل پروکسی تلگرام روی گوشی", "The Telegram proxy bridge on this phone")
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun build(text: String): Notification {
        val content = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, TgBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(tr("پل تلگرام", "Telegram bridge"))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_scanner)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, tr("خاموش", "Turn off"), stop)
        content?.let { b.setContentIntent(it) }
        return b.build()
    }
}
