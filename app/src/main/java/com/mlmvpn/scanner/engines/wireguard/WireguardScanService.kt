package com.mlmvpn.scanner.engines.wireguard

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
import com.mlmvpn.core.warp.WarpAccountManager
import com.mlmvpn.scanner.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WireguardScanService : Service() {

    companion object {
        private const val TAG = "WireguardScanService"
        private const val CHANNEL_ID = "wg_scan_channel"
        private const val NOTIFICATION_ID = 5502

        const val ACTION_START_SCAN = "ACTION_START_SCAN"
        const val ACTION_START_TEST = "ACTION_START_TEST"
        const val ACTION_STOP = "ACTION_STOP"
        
        const val EXTRA_MAX_SAMPLES = "EXTRA_MAX_SAMPLES"
        const val EXTRA_TARGET_SUCCESS = "EXTRA_TARGET_SUCCESS"
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
            buildNotification("در حال آماده‌سازی...", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        acquireWakeLock()
        observeScanState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            WireguardScannerEngine.stopAll()
            stopSelfCleanly()
            return START_NOT_STICKY
        }
        
        if (action == ACTION_START_SCAN) {
            val maxSamples = intent.getIntExtra(EXTRA_MAX_SAMPLES, 2000)
            val targetSuccess = intent.getIntExtra(EXTRA_TARGET_SUCCESS, 20)
            
            // Standard + Engc CIDRs
            val cidrs = listOf(
                "162.159.192.0/24", "162.159.193.0/24", "162.159.195.0/24", "162.159.204.0/24",
                "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13",
                "131.0.72.0/22", "141.101.64.0/18", "108.162.192.0/18",
                "190.93.240.0/20", "197.234.240.0/22", "198.41.128.0/17",
                "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
                "162.159.0.0/16", "188.114.96.0/20", "185.221.160.0/22"
            )
            val ports = listOf(
                7103, 7152, 7281, 854, 859, 908, 942, 1002, 1074, 1180, 5000, 2408
            )
            
            WireguardScannerEngine.startScan(this, cidrs, ports, maxSamples, targetSuccess)
        }
        
        if (action == ACTION_START_TEST) {
            val accounts = WarpAccountManager(this).getSavedAccounts()
            WireguardScannerEngine.startTest100(this, accounts)
        }

        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MLMVPN::WgScanWakeLock")
            wakeLock?.acquire(2 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun observeScanState() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                val state = WireguardScannerEngine.engineState.value
                if (state == WireguardScannerEngine.EngineState.IDLE) {
                    stopSelfCleanly()
                    return@launch
                }
                
                val scanned = WireguardScannerEngine.scannedCount.value
                val total = WireguardScannerEngine.totalScanCount.value
                val endpoints = WireguardScannerEngine.endpointsList.value.size

                val (text, progress) = when (state) {
                    WireguardScannerEngine.EngineState.SCANNING ->
                        "جستجو: $scanned از $total تست شد ($endpoints سالم)" to Pair(scanned, total)
                    WireguardScannerEngine.EngineState.TESTING ->
                        "تست واقعی (۱۰۰٪): $scanned از $total" to Pair(scanned, total)
                    else -> "آماده‌سازی..." to null
                }
                
                try {
                    NotificationManagerCompat.from(this@WireguardScanService)
                        .notify(NOTIFICATION_ID, buildNotification(text, progress))
                } catch (e: SecurityException) {
                    // Ignore if missing permissions
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
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
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
                "اسکن وایرگارد",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = "پیشرفت اسکن اندپوینت‌های وایرگارد"
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
            .setContentTitle("اسکنر هوشمند وایرگارد")
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
