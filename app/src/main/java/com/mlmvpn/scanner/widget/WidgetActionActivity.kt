package com.mlmvpn.scanner.widget

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import com.mlmvpn.scanner.data.ScanGuard
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.tunnel.TunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where every widget tap lands: an activity with no window of its own, gone before it is seen.
 *
 * An activity rather than a receiver for three reasons. The VPN consent dialog, the first time,
 * can only be asked for from an activity. A foreground service -- which the connect needs, see
 * [WidgetConnectService] -- may be started from an activity in the foreground without any of the
 * background-start limits. And opening the app on a tile is an activity start, which Android allows
 * from a tap and refuses from the background.
 */
class WidgetActionActivity : Activity() {

    private var method: String? = null
    private var country: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        // A recreation after the consent dialog keeps going where it was; nothing to redo here.
        if (savedInstanceState != null) {
            method = savedInstanceState.getString(KEY_METHOD)
            country = savedInstanceState.getString(KEY_COUNTRY)
            return
        }
        method = intent?.getStringExtra(WidgetActions.EXTRA_METHOD)
        country = intent?.getStringExtra(WidgetActions.EXTRA_COUNTRY)
        when (intent?.action) {
            WidgetActions.ACTION_OPEN -> {
                method?.let { WidgetOpen.open(this, it) }
                done()
            }
            WidgetActions.ACTION_STUDIO -> {
                val widgetId = intent.getIntExtra(WidgetActions.EXTRA_WIDGET_ID, -1)
                if (widgetId > 0) {
                    // A widget with no design (deleted, or never bound): choose one for it right here.
                    runCatching {
                        startActivity(
                            Intent(this, com.mlmvpn.scanner.widget.studio.StudioConfigureActivity::class.java)
                                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                                .putExtra(com.mlmvpn.scanner.widget.studio.StudioConfigureActivity.EXTRA_RECONFIGURE, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure { WidgetOpen.open(this, STUDIO_TILE) }
                } else {
                    WidgetOpen.open(this, STUDIO_TILE)
                }
                done()
            }
            WidgetActions.ACTION_TOGGLE -> toggle()
            else -> done()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_METHOD, method)
        outState.putString(KEY_COUNTRY, country)
    }

    private fun toggle() {
        val m = method
        if (m == null || !WidgetMethods.isMethod(m)) {
            done()
            return
        }
        val conn = WidgetLive.now(this)
        // This method is up, or on its way up: the tap means "off".
        if (conn.method == m && conn.busy) {
            WidgetConnect.disconnect(this)
            done()
            return
        }
        // Something only the method's own page can settle (an account, a server list, a terms
        // screen): open it there rather than starting a connect that cannot work. Asked with a short
        // patience -- this window is invisible but it is on top of the home screen, so it must not
        // linger; a slow answer (a store read right after a reboot) is left to the service, which
        // asks again and says so in a notification.
        scope.launch {
            val ready = withTimeoutOrNull(READY_WAIT_MS) { WidgetConnect.readiness(this@WidgetActionActivity, m) }
            when (ready) {
                is WidgetConnect.Ready.Setup -> {
                    say(ready.reason)
                    WidgetOpen.open(this@WidgetActionActivity, m)
                    done()
                }
                is WidgetConnect.Ready.No -> {
                    say(ready.reason)
                    done()
                }
                else -> proceed(m)
            }
        }
    }

    private fun proceed(m: String) {
        // A clean-IP scan or MLM Scout owns the network: a tunnel now would spoil its results.
        if (ScanGuard.isScanBusy()) {
            say(tr("یک اسکن در حال اجراست؛ اول آن را در برنامه متوقف کنید", "A scan is running; stop it in the app first"))
            WidgetOpen.open(this, m)
            done()
            return
        }
        if (!TunnelController.hasNetwork(this)) {
            say(tr("گوشی به اینترنت وصل نیست", "The phone has no internet connection"))
            done()
            return
        }
        val consent = runCatching { VpnService.prepare(this) }.getOrNull()
        if (consent != null) {
            // The first connect ever: Android's own VPN permission dialog, then on with the connect.
            @Suppress("DEPRECATION")
            runCatching { startActivityForResult(consent, REQUEST_VPN) }.onFailure {
                WidgetOpen.open(this, m)
                done()
            }
            return
        }
        start(m)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        val m = method
        if (resultCode == RESULT_OK && m != null) start(m) else done()
    }

    private fun start(m: String) {
        WidgetConnectService.start(this, m, country)
        done()
    }

    private fun say(text: String) {
        runCatching { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }
    }

    private fun done() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        /** How long the invisible window waits for a readiness answer before handing over. */
        private const val READY_WAIT_MS = 1_500L
        private const val REQUEST_VPN = 7301
        private const val KEY_METHOD = "method"
        private const val KEY_COUNTRY = "country"
        const val STUDIO_TILE = "widgetstudio"
    }
}
