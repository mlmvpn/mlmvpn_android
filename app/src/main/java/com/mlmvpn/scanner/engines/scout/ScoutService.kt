package com.mlmvpn.scanner.engines.scout

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a Scout run alive while the user looks at something else, for as long as the run lasts and
 * not a second more: the foreground notification shows the real progress and a «توقف» button, and
 * a wake lock holds the CPU. The run itself lives in [ScoutEngine]; this only gives Android a reason
 * not to freeze the process in the middle of a measurement.
 */
class ScoutService : Service() {

    companion object {
        private const val CHANNEL_ID = "scout_channel"
        private const val NOTIFICATION_ID = 5511
        const val ACTION_STOP = "ACTION_STOP_SCOUT"
        private const val WAKE_LOCK_TIMEOUT_MS = 20 * 60 * 1000L

        fun start(context: Context) {
            val app = context.applicationContext
            runCatching { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, ScoutService::class.java)) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitor: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground()
        runCatching {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mlmvpn:scout").apply {
                    setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS)
                }
        }
        monitor = scope.launch {
            delay(1_500)
            while (isActive) {
                val u = ScoutEngine.ui.value
                if (!u.running) { stopCleanly(); return@launch }
                val total = ScoutRegistry.ALL.size
                val done = u.run?.methods?.values?.count { it.status.finished && it.pending == 0 } ?: 0
                val text = when {
                    u.stopping -> tr("در حال توقف…", "Stopping…")
                    u.lineDown -> tr("اینترنت قطع است؛ صبر…", "The line is down; waiting…")
                    else -> passLabel(u.run?.pass) + " · " + tr("$done از $total روش", "$done of $total methods")
                }
                runCatching {
                    NotificationManagerCompat.from(this@ScoutService)
                        .notify(NOTIFICATION_ID, build(text, if (u.tasksTotal > 0) u.tasksDone to u.tasksTotal else null))
                }
                delay(1_000)
            }
        }
    }

    private fun passLabel(p: Pass?) = when (p) {
        Pass.QUICK, null -> tr("پاسخ سریع", "Quick answer")
        Pass.SPEED -> tr("نمونهٔ سرعت", "Speed samples")
        Pass.DEEP -> tr("بررسی کامل", "Full check")
        Pass.DONE -> tr("پایان", "Done")
    }

    /**
     * For every start command, not only the first: a start that reached an instance already
     * stopping (its run had just ended) went unanswered, and ForegroundServiceDidNotStartInTime
     * closed the app (1.2.41).
     */
    private fun goForeground() {
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(tr("آماده‌سازی…", "Preparing…"), null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure { android.util.Log.w("ScoutService", "could not go foreground: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_STOP) ScoutEngine.stop(EndReason.STOPPED)
        else if (!ScoutEngine.ui.value.running) {
            // Started for a run that has already ended: down again, but only after the start
            // above was answered.
            stopCleanly()
        }
        return START_NOT_STICKY
    }

    private fun stopCleanly() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        monitor?.cancel()
        scope.coroutineContext[Job]?.cancel()
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "MLM Scout", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = tr("پیشرفت بررسی MLM Scout", "MLM Scout's progress")
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun build(text: String, progress: Pair<Int, Int>?): Notification {
        val content = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, ScoutService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MLM Scout")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_scanner)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, tr("توقف", "Stop"), stop)
        content?.let { b.setContentIntent(it) }
        if (progress != null && progress.second > 0) b.setProgress(progress.second, progress.first, false) else b.setProgress(0, 0, true)
        return b.build()
    }
}
