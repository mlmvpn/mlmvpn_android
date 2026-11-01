package com.mlmvpn.scanner.widget

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
import com.mlmvpn.scanner.data.VpnSwitch
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Carries a connect started from a widget, from the tap until the method is up (or has given up).
 *
 * Without it the connect would run in an app with nothing on screen. Many engines do real work
 * before their VPN service starts -- FLUX races routes, MAE compiles its config, FENIX fetches a
 * pass, the config list is raced -- and in the meantime Android freezes or kills a background
 * process, and refuses the service start that would have followed (SafeStart then skips it
 * silently: the tap would do nothing). A foreground service for those seconds is what makes the
 * app count as in use until the engine's own service has taken over.
 *
 * Its notification says what is happening; it goes away by itself as soon as the method is up. If
 * the method does not come up, a short notice says so and opens the method's page, which shows why.
 */
class WidgetConnectService : Service() {

    companion object {
        private const val CHANNEL_ID = "widget_connect"
        private const val NOTIFICATION_ID = 7302
        private const val FAILED_ID = 7303
        private const val EXTRA_METHOD = "method"
        private const val EXTRA_COUNTRY = "country"

        /** How long a connect may take before this stops waiting for it. Tor and Psiphon are slow. */
        private const val CONNECT_MS = 150_000L

        /** How long an engine may stay silent before its first report (its own pre-tunnel work). */
        private const val SILENT_MS = 75_000L

        fun start(context: Context, method: String, country: String?) {
            val app = context.applicationContext
            val intent = Intent(app, WidgetConnectService::class.java)
                .putExtra(EXTRA_METHOD, method)
                .putExtra(EXTRA_COUNTRY, country)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(app, intent) }
                .onFailure {
                    // Not allowed (a launcher that did not count the tap as the user's): the method's
                    // own page can still do it, one more tap away.
                    WidgetOpen.open(app, method)
                }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val method = intent?.getStringExtra(EXTRA_METHOD)
        val country = intent?.getStringExtra(EXTRA_COUNTRY)
        val name = WidgetMethods.app(method)?.let { WidgetPainter.labelOf(it) } ?: method.orEmpty()
        // Always answered first, whatever happens next: a start that is not followed by
        // startForeground in time closes the app (ForegroundServiceDidNotStartInTime).
        goForeground(tr("در حال اتصال به «$name»…", "Connecting $name…"))
        if (method == null || !WidgetMethods.isMethod(method)) {
            stopNow()
            return START_NOT_STICKY
        }
        // A newer tap replaces an older one still in progress.
        job?.cancel()
        job = scope.launch { run(method, country, name) }
        return START_NOT_STICKY
    }

    private suspend fun run(method: String, country: String?, name: String) {
        val app = applicationContext
        NotificationManagerCompat.from(this).cancel(FAILED_ID)
        // Asked again here: the tap's own window only waits a moment for the answer.
        when (val ready = WidgetConnect.readiness(app, method)) {
            is WidgetConnect.Ready.Setup -> {
                notice(method, tr("«$name» آماده نیست", "$name is not ready"), ready.reason)
                stopNow()
                return
            }
            is WidgetConnect.Ready.No -> {
                notice(method, tr("«$name» روی این گوشی اجرا نمی‌شود", "$name does not run on this phone"), ready.reason)
                stopNow()
                return
            }
            else -> Unit
        }
        WidgetLive.noteRequested(method)
        // Another method is up: take it down first, and wait until it really is down. Starting the
        // next one over a tunnel still unwinding is how two services end up fighting over the tun.
        if (!WidgetConnect.allDown(app)) {
            WidgetConnect.disconnect(app)
            WidgetLive.noteRequested(method)
            withTimeoutOrNull(10_000L) {
                while (!WidgetConnect.allDown(app)) delay(200)
            }
            delay(400)
        }
        val started = runCatching { WidgetConnect.connect(app, method, country) }
            .onFailure { android.util.Log.w("WidgetConnect", "$method: ${it.message}") }
            .getOrDefault(false)
        if (!started) {
            WidgetLive.clearRequested()
            failed(method, name)
            stopNow()
            return
        }
        // Until the method is up -- or has visibly given up: an engine that was busy and then went
        // quiet for a few seconds. An engine that has not reported anything yet (its own work before
        // the tunnel: a race, a config compiled, a session fetched) is given the longer grace; the
        // widget's own "asked for" marker is not taken as a sign of life.
        var seenBusy = false
        var idleSince = 0L
        val ok = withTimeoutOrNull(CONNECT_MS) {
            var result = false
            while (true) {
                val c = WidgetLive.conn.value
                if (c.method == method && c.up) { result = true; break }
                val busyHere = c.busy && !c.pending && (c.method == method || c.method == null)
                if (busyHere) {
                    seenBusy = true
                    idleSince = 0L
                } else {
                    if (idleSince == 0L) idleSince = System.currentTimeMillis()
                    val quiet = System.currentTimeMillis() - idleSince
                    if ((seenBusy && quiet > 4_000) || (!seenBusy && quiet > SILENT_MS)) break
                }
                delay(300)
            }
            result
        } ?: false
        WidgetLive.clearRequested()
        if (ok && method == "vpngate") {
            // Its screen starts the session clock when it sees the tunnel up; nothing else would here.
            runCatching { com.mlmvpn.scanner.engines.vpngate.VpnGateStore.markConnected(app) }
        }
        if (!ok) failed(method, name)
        stopNow()
    }

    private fun failed(method: String, name: String) =
        notice(method, tr("«$name» وصل نشد", "$name did not connect"), tr("برای دیدن علت و راه‌های دیگر بزنید", "Tap to see why, and what else to try"))

    /** A notice that opens [method]'s page: what a widget would otherwise have no screen to say. */
    private fun notice(method: String, title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, FAILED_ID, WidgetOpen.intent(this, method),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(FAILED_ID, n) }
    }

    private fun goForeground(text: String) {
        val n = build(text)
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        }.onFailure { android.util.Log.w("WidgetConnect", "could not go foreground: ${it.message}") }
    }

    private fun build(text: String): Notification {
        val content = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MLMVPN")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .apply { content?.let { setContentIntent(it) } }
            .build()
    }

    private fun stopNow() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, tr("اتصال از ویجت", "Connecting from a widget"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = tr("پیشرفت اتصالی که از ویجت صفحهٔ اصلی گوشی شروع شده", "Progress of a connect started from a home-screen widget")
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
}
