package com.mlmvpn.scanner.widget.studio

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.AppLocaleManager
import com.mlmvpn.scanner.widget.MethodView
import com.mlmvpn.scanner.widget.WidgetLive
import com.mlmvpn.scanner.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The studio's widgets, one provider per size: the system picker lists each size as a widget of its
 * own («ویجت استدیو · متوسط»), and every placed one wears whichever design it is bound to.
 *
 * Binding happens in up to three places, because launchers differ in what they do after a pin
 * request: the configuration activity (most launchers open it), this provider's first update (for
 * those that do not), and the pin request's own callback. Whichever comes first binds; the others
 * find the widget bound and leave it.
 */
abstract class StudioWidgetProvider(private val size: StudioSize) : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        // A widget placed for a pin request whose callback has not arrived (or never will).
        for (id in appWidgetIds) {
            if (!StudioStore.isBound(app, id) && StudioStore.hasPending()) {
                StudioStore.takePending()?.let { StudioStore.bind(app, id, it) }
            }
        }
        render(app, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        render(context.applicationContext, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val app = context.applicationContext
        StudioStore.unbind(app, appWidgetIds)
        WidgetUpdater.refreshAllAsync(app)
    }

    private fun render(app: Context, ids: IntArray) {
        val pending = goAsync()
        WidgetUpdater.attach(app)
        scope.launch {
            try {
                WidgetUpdater.renderStudio(app, AppWidgetManager.getInstance(app), size, ids)
                // Whether anything placed now needs live numbers or the country.
                WidgetUpdater.recomputeDemand(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun classFor(size: StudioSize): Class<out StudioWidgetProvider> = when (size) {
            StudioSize.SMALL -> StudioWidgetSmall::class.java
            StudioSize.MEDIUM -> StudioWidgetMedium::class.java
            StudioSize.LARGE -> StudioWidgetLarge::class.java
        }

        fun sizeOf(provider: ComponentName?): StudioSize? = when (provider?.className) {
            StudioWidgetSmall::class.java.name -> StudioSize.SMALL
            StudioWidgetMedium::class.java.name -> StudioSize.MEDIUM
            StudioWidgetLarge::class.java.name -> StudioSize.LARGE
            else -> null
        }
    }
}

class StudioWidgetSmall : StudioWidgetProvider(StudioSize.SMALL)
class StudioWidgetMedium : StudioWidgetProvider(StudioSize.MEDIUM)
class StudioWidgetLarge : StudioWidgetProvider(StudioSize.LARGE)

/** The pin request's callback: the launcher placed the widget, here is its id. Not exported. */
class StudioPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val template = intent.getStringExtra(StudioPin.EXTRA_TEMPLATE)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || template.isNullOrBlank()) return
        if (!StudioStore.isBound(app, id)) StudioStore.bind(app, id, template)
        // The pending request is answered; a later widget must not inherit it.
        StudioStore.takePending()
        WidgetUpdater.refreshAllAsync(app)
    }
}

object StudioPin {
    const val EXTRA_TEMPLATE = "studio_template"

    fun canPin(context: Context): Boolean = Build.VERSION.SDK_INT >= 26 &&
        runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }.getOrDefault(false)

    /**
     * Asks the launcher to place [template] in its own size. The confirmation the launcher shows
     * carries the design itself as its preview. False when the launcher cannot do it -- the caller
     * then explains the manual way (long-press the home screen, Widgets, MLMVPN).
     */
    fun request(context: Context, template: WidgetTemplate): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !canPin(context)) return false
        val app = context.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        StudioStore.setPending(template.id)
        val callback = PendingIntent.getBroadcast(
            app,
            template.id.hashCode(),
            Intent(app, StudioPinReceiver::class.java).putExtra(EXTRA_TEMPLATE, template.id),
            // Mutable: the system adds the new widget's id to it.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        val extras = Bundle().apply {
            runCatching { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, preview(app, template)) }
        }
        return runCatching {
            mgr.requestPinAppWidget(ComponentName(app, StudioWidgetProvider.classFor(template.size)), extras, callback)
        }.getOrDefault(false)
    }

    private fun preview(context: Context, t: WidgetTemplate): RemoteViews {
        val (w, h) = when (t.size) {
            StudioSize.SMALL -> 158f to 158f
            StudioSize.MEDIUM -> 330f to 150f
            StudioSize.LARGE -> 330f to 330f
        }
        val view = MethodView.of(t.method, WidgetLive.conn.value, WidgetLive.traffic.value)
        val bmp = StudioPainter.paint(context, t, view, w, h, context.resources.displayMetrics.density, AppLocaleManager.isFarsi())
        return RemoteViews(context.packageName, R.layout.widget_method).apply { setImageViewBitmap(R.id.widget_image, bmp) }
    }
}
