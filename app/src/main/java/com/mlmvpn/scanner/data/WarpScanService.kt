package com.mlmvpn.scanner.data

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mlmvpn.scanner.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the WARP endpoint scan (WarpScanManager) alive when the app is swiped away from recents
 * or the screen locks. A plain background coroutine dies with the process once Android reclaims
 * it; a foreground service with an ongoing notification is exempt from that, and a partial wake
 * lock keeps the CPU/network working while the screen is off. This is purely a keep-alive +
 * progress-display shell -- all the actual scanning logic still lives in WarpScanManager, and this
 * service just mirrors its state into a notification and auto-stops once isScanning goes false.
 */
class WarpScanService : Service() {

    companion object {
        private const val TAG = "WarpScanService"
        private const val CHANNEL_ID = "warp_scan_channel"
        private const val NOTIFICATION_ID = 5501

        fun ensureRunning(context: Context) {
            val intent = Intent(context, WarpScanService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("در حال آماده‌سازی اسکن WARP...", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        acquireWakeLock()
        observeScanState()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MLMVPN::WarpScanWakeLock")
            // Safety cap so a stuck scan can't hold a wake lock forever if something goes wrong.
            wakeLock?.acquire(2 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    /**
     * Polls WarpScanManager's state once a second instead of reacting to every single StateFlow
     * emission -- during discovery, attemptedCount can change hundreds of times per second (300
     * probes in flight at once), and calling notify() that fast gets rate-limited/dropped by the
     * system (logcat: "NotificationService ... Package enqueue rate is X. Shedding ..."). Once a
     * second is plenty for a progress counter and stays well under Android's notification-update
     * rate limit.
     */
    private fun observeScanState() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                if (!WarpScanManager.isScanning.value) {
                    stopSelfCleanly()
                    return@launch
                }
                val phase = WarpScanManager.phase.value
                val discovered = WarpScanManager.discoveredCount.value
                val attempted = WarpScanManager.attemptedCount.value
                val testProgress = WarpScanManager.testProgress.value

                val (text, progress) = when (phase) {
                    WarpScanManager.ScanPhase.DISCOVERING ->
                        "مرحله ۱: $attempted آی‌پی تست شد، $discovered پیدا شد" to null
                    WarpScanManager.ScanPhase.TESTING ->
                        "مرحله ۲: تست پینگ ${testProgress.first} از ${testProgress.second}" to testProgress
                    else -> "در حال اسکن WARP..." to null
                }
                try {
                    NotificationManagerCompat.from(this@WarpScanService)
                        .notify(NOTIFICATION_ID, buildNotification(text, progress))
                } catch (e: SecurityException) {
                    // Notification permission not granted -- the service (and the scan) keeps
                    // running regardless, the user just won't see the counter until they grant it.
                }
                delay(1000)
            }
        }
    }

    private fun stopSelfCleanly() {
        monitorJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // Already released -- harmless.
        }
        wakeLock = null
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "اسکن WARP",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = "پیشرفت اسکن اندپوینت‌های WARP در پس‌زمینه"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Pair<Int, Int>?): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("اسکن اندپوینت WARP")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_scanner)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        if (progress != null && progress.second > 0) {
            builder.setProgress(progress.second, progress.first, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}
