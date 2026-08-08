package kittoku.mvc.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.mlmvpn.scanner.R
import kittoku.mvc.SharedBridge
import kittoku.mvc.control.Controller
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob


internal const val ACTION_VPN_CONNECT = "kittoku.mvc.connect"
internal const val ACTION_VPN_DISCONNECT = "kittoku.mvc.disconnect"

internal const val NOTIFICATION_ERROR_CHANNEL = "ERROR"
internal const val NOTIFICATION_RECONNECT_CHANNEL = "RECONNECT"
internal const val NOTIFICATION_DISCONNECT_CHANNEL = "DISCONNECT"
internal const val NOTIFICATION_CERTIFICATE_CHANNEL = "CERTIFICATE"

internal const val NOTIFICATION_ERROR_ID = 1
internal const val NOTIFICATION_RECONNECT_ID = 2
internal const val NOTIFICATION_DISCONNECT_ID = 3
internal const val NOTIFICATION_CERTIFICATE_ID = 4


internal class SoftEtherVpnService : VpnService() {
    // MLMVPN: the upstream client is driven from its own UI and reports through
    // notifications, so it exposes no state an engine can await. These two flags are the
    // whole of the addition — set at the two points Controller already knows the outcome.
    companion object {
        @Volatile internal var isConnected = false
        @Volatile internal var lastError: String? = null
        /** True from the moment a disconnect is requested, so teardown noise isn't an error. */
        @Volatile internal var isStopping = false

        /**
         * The bridge of the live session, or null when there is none. Read only to report
         * whether UDP acceleration actually negotiated: the preference says what was asked
         * for, this says what the server agreed to, and on most Iranian lines those differ.
         */
        @Volatile internal var liveBridge: SharedBridge? = null

        /** True only while a session is up AND its UDP channel is actually open. */
        internal val isUdpAccelerationActive: Boolean
            get() = isConnected &&
                liveBridge?.udpAccelerationConfig?.status == kittoku.mvc.teminal.UDPStatus.OPEN
    }

    private lateinit var notificationManager: NotificationManagerCompat
    private var client: Controller? = null

    override fun onCreate() {
        notificationManager = NotificationManagerCompat.from(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return if (ACTION_VPN_CONNECT == intent?.action ?: false) {
            isConnected = false
            lastError = null
            isStopping = false
            client?.kill(null)
            client = Controller(createBridge()).also {
                beForegrounded()
                it.run()
            }

            START_STICKY
        } else {
            isStopping = true
            client?.kill(null)
            client = null

            START_NOT_STICKY
        }
    }

    private fun createBridge(): SharedBridge {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val handler = CoroutineExceptionHandler { _, throwable ->
            if (isStopping) {
                // A deliberate disconnect closes the socket under a blocked read, so the IO
                // threads always unwind with an exception. Reporting it would put an
                // "ERR_UNEXPECTED" notification in front of the user every single time they
                // press disconnect.
                android.util.Log.d("SoftEther", "shutdown unwind: ${throwable.javaClass.simpleName}")
                client?.kill(null)
            } else {
                // MLMVPN: upstream only writes this to its optional log file, so the cause of
                // a failed negotiation never reaches logcat and every error looks like
                // "MVC: ERR_UNEXPECTED".
                android.util.Log.e("SoftEther", "negotiation threw", throwable)
                client?.kill(throwable)
            }
            client = null
        }

        val bridge = SharedBridge(scope, handler)

        bridge.service = this
        bridge.prepareParameters(PreferenceManager.getDefaultSharedPreferences(this))
        liveBridge = bridge

        return bridge
    }

    private fun beForegrounded() {
        arrayOf(
            NOTIFICATION_ERROR_CHANNEL,
            NOTIFICATION_RECONNECT_CHANNEL,
            NOTIFICATION_DISCONNECT_CHANNEL,
            NOTIFICATION_CERTIFICATE_CHANNEL,
        ).map {
            NotificationChannel(it, it, NotificationManager.IMPORTANCE_DEFAULT)
        }.also {
            notificationManager.createNotificationChannels(it)
        }

        val pendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SoftEtherVpnService::class.java).setAction(ACTION_VPN_DISCONNECT),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_DISCONNECT_CHANNEL).also {
            it.priority = NotificationCompat.PRIORITY_DEFAULT
            it.setOngoing(true)
            it.setAutoCancel(true)
            it.setSmallIcon(R.drawable.ic_baseline_vpn_lock_24)
            it.addAction(R.drawable.ic_baseline_close_24, "DISCONNECT", pendingIntent)
        }

        startForeground(NOTIFICATION_DISCONNECT_ID, builder.build())
    }
    internal fun notifyMessage(message: String, id: Int, channel: String) {
        NotificationCompat.Builder(this, channel).also {
            it.setSmallIcon(R.drawable.ic_baseline_vpn_lock_24)
            it.setContentText(message)
            it.priority = NotificationCompat.PRIORITY_DEFAULT
            it.setAutoCancel(true)

            tryNotify(it.build(), id)
        }
    }

    internal fun notifyError(message: String) {
        lastError = message // MLMVPN: surface the failure to the driving engine
        notifyMessage(message,
            NOTIFICATION_ERROR_ID,
            NOTIFICATION_ERROR_CHANNEL
        )
    }

    internal fun tryNotify(notification: Notification, id: Int) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(id, notification)
        }
    }

    internal fun cancelNotification(id: Int) {
        notificationManager.cancel(id)
    }

    internal fun close() {
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        isConnected = false
        liveBridge = null
        client?.kill(null)
        client = null
    }
}
