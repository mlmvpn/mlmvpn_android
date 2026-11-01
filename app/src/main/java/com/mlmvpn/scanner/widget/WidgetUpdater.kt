package com.mlmvpn.scanner.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.RemoteViews
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.utils.AppLocaleManager
import com.mlmvpn.scanner.utils.ScreenState
import com.mlmvpn.scanner.widget.studio.Info
import com.mlmvpn.scanner.widget.studio.StudioPainter
import com.mlmvpn.scanner.widget.studio.StudioSize
import com.mlmvpn.scanner.widget.studio.StudioStore
import com.mlmvpn.scanner.widget.studio.StudioWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps every placed widget -- method widgets and studio designs -- showing the truth.
 *
 * A widget is a picture the launcher holds; nothing changes on it unless the app sends a new one.
 * This sends one when the connection changes (any engine, any phase), when a design is edited, when
 * the launcher resizes or re-creates a widget, and -- only while the screen is on and a session is
 * up -- every few seconds for the designs that show live numbers. With no widget placed, all of it
 * is a handful of empty lookups.
 */
object WidgetUpdater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val renderLock = Mutex()
    @Volatile private var attached = false

    /** Something placed shows live numbers (speed, usage, time, chart). */
    private val liveDemand = MutableStateFlow(false)

    /** How often a design with live numbers is redrawn while the screen is on. */
    private const val LIVE_MS = 3_000L
    /** How often the method widgets' «متصل · ۱۲ دقیقه» is brought up to date. */
    private const val MINUTE_MS = 60_000L

    fun attach(context: Context) {
        if (attached) return
        synchronized(this) {
            if (attached) return
            attached = true
        }
        val app = context.applicationContext
        WidgetLive.attach(app)
        scope.launch {
            // Every change of phase, method, start or country: redraw everything placed.
            WidgetLive.conn.collect { refreshAll(app) }
        }
        scope.launch {
            combine(WidgetLive.conn, ScreenState.on, liveDemand) { c, screen, live -> Triple(c.up, screen, live) }
                .distinctUntilChanged()
                .collectLatest { (up, screen, live) ->
                    WidgetLive.widgetsWantTraffic.value = up && live
                    if (!up || !screen) return@collectLatest
                    var sinceMinute = 0L
                    while (true) {
                        delay(if (live) LIVE_MS else MINUTE_MS)
                        sinceMinute += if (live) LIVE_MS else MINUTE_MS
                        if (live) refreshStudio(app, liveOnly = true)
                        if (sinceMinute >= MINUTE_MS) {
                            sinceMinute = 0L
                            refreshMethods(app)
                        }
                    }
                }
        }
    }

    /** Redraws every placed widget. Safe from any thread. */
    fun refreshAllAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { refreshAll(app) }
    }

    suspend fun refreshAll(context: Context) {
        refreshMethods(context)
        refreshStudio(context, liveOnly = false)
        // It wears the method widgets' style, and speaks the app's language.
        GerehWidgets.refresh(context)
    }

    private fun manager(context: Context): AppWidgetManager? = runCatching { AppWidgetManager.getInstance(context) }.getOrNull()

    suspend fun refreshMethods(context: Context) {
        val mgr = manager(context) ?: return
        for ((method, cls) in MethodWidgets.PROVIDERS) {
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, cls)) }.getOrNull() ?: continue
            if (ids.isNotEmpty()) renderMethod(context, mgr, method, ids)
        }
    }

    suspend fun refreshStudio(context: Context, liveOnly: Boolean) {
        val mgr = manager(context) ?: return
        val data = StudioStore.get(context)
        var anyLive = false
        var anyCountry = false
        for (size in StudioSize.entries) {
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, StudioWidgetProvider.classFor(size))) }.getOrNull() ?: continue
            val draw = ArrayList<Int>()
            for (id in ids) {
                val t = data.template(data.bindings[id])
                val live = t != null && (t.shows(Info.SPEED) || t.shows(Info.USAGE) || t.shows(Info.CHART) || t.shows(Info.TIME))
                if (live) anyLive = true
                if (t != null && t.shows(Info.COUNTRY)) anyCountry = true
                if (!liveOnly || live) draw += id
            }
            if (draw.isNotEmpty()) renderStudio(context, mgr, size, draw.toIntArray())
        }
        if (!liveOnly) {
            liveDemand.value = anyLive
            WidgetLive.wantCountry("placed", anyCountry)
        }
    }

    /** Whether anything placed shows live numbers or the country, without drawing anything. */
    fun recomputeDemand(context: Context) {
        val mgr = manager(context) ?: return
        val data = StudioStore.get(context)
        var anyLive = false
        var anyCountry = false
        for (size in StudioSize.entries) {
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, StudioWidgetProvider.classFor(size))) }.getOrNull() ?: continue
            for (id in ids) {
                val t = data.template(data.bindings[id]) ?: continue
                if (t.shows(Info.SPEED) || t.shows(Info.USAGE) || t.shows(Info.CHART) || t.shows(Info.TIME)) anyLive = true
                if (t.shows(Info.COUNTRY)) anyCountry = true
            }
        }
        liveDemand.value = anyLive
        WidgetLive.wantCountry("placed", anyCountry)
    }

    private val rtl: Boolean get() = AppLocaleManager.isFarsi()

    /**
     * The widget's size in dp, as the launcher reports it. Portrait takes the narrow width and the
     * tall height, landscape the other pair -- the documented meaning of the four options.
     */
    internal fun sizeOf(context: Context, options: Bundle?, fallbackW: Float, fallbackH: Float): Pair<Float, Float> {
        if (options == null) return fallbackW to fallbackH
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = if (landscape) options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) else options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = if (landscape) options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) else options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return (if (w > 0) w.toFloat() else fallbackW) to (if (h > 0) h.toFloat() else fallbackH)
    }

    suspend fun renderMethod(context: Context, mgr: AppWidgetManager, method: String, ids: IntArray) = renderLock.withLock {
        val app = WidgetMethods.app(method) ?: return@withLock
        val style = StudioStore.get(context).methodStyle
        val view = MethodView.of(method, WidgetLive.conn.value, WidgetLive.traffic.value)
        val density = context.resources.displayMetrics.density
        val name = WidgetPainter.labelOf(app)
        for (id in ids) {
            runCatching {
                val (w, h) = sizeOf(context, runCatching { mgr.getAppWidgetOptions(id) }.getOrNull(), 76f, 92f)
                val bmp = WidgetPainter.paintMethod(context, app, style, view, w, h, density, rtl)
                val rv = RemoteViews(context.packageName, R.layout.widget_method)
                rv.setImageViewBitmap(R.id.widget_image, bmp)
                rv.setContentDescription(R.id.widget_root, WidgetFormat.joined(name, describe(view)))
                rv.setOnClickPendingIntent(
                    R.id.widget_root,
                    WidgetActions.pending(context, if (style.tapConnects) WidgetActions.ACTION_TOGGLE else WidgetActions.ACTION_OPEN, method, id),
                )
                mgr.updateAppWidget(id, rv)
            }.onFailure { android.util.Log.w("WidgetUpdater", "method widget $id: ${it.message}") }
        }
    }

    suspend fun renderStudio(context: Context, mgr: AppWidgetManager, size: StudioSize, ids: IntArray) = renderLock.withLock {
        val data = StudioStore.get(context)
        val density = context.resources.displayMetrics.density
        val conn = WidgetLive.conn.value
        val traffic = WidgetLive.traffic.value
        for (id in ids) {
            runCatching {
                val (fw, fh) = when (size) {
                    StudioSize.SMALL -> 158f to 158f
                    StudioSize.MEDIUM -> 330f to 150f
                    StudioSize.LARGE -> 330f to 330f
                }
                val (w, h) = sizeOf(context, runCatching { mgr.getAppWidgetOptions(id) }.getOrNull(), fw, fh)
                val layout = when (size) {
                    StudioSize.SMALL -> R.layout.widget_studio_small
                    StudioSize.MEDIUM -> R.layout.widget_studio_medium
                    StudioSize.LARGE -> R.layout.widget_studio_large
                }
                val rv = RemoteViews(context.packageName, layout)
                val t = data.template(data.bindings[id])
                if (t == null) {
                    // A design that was deleted, or a widget not bound yet: say so, and let a tap fix it.
                    rv.setImageViewBitmap(R.id.widget_image, StudioPainter.paintMissing(context, w, h, density, rtl))
                    val open = WidgetActions.pending(context, WidgetActions.ACTION_STUDIO, null, id)
                    zoneIds(size).forEach { rv.setOnClickPendingIntent(it, open) }
                    rv.setContentDescription(R.id.widget_root, tr("این قالب حذف شده است", "This design was deleted"))
                    mgr.updateAppWidget(id, rv)
                    return@runCatching
                }
                // The design's own size decides the layout's proportions; a widget placed in the
                // picker's other size still draws in the shape the launcher gave it.
                val shaped = if (t.size == size) t else t.copy(size = size)
                val view = MethodView.of(t.method, conn, traffic)
                rv.setImageViewBitmap(R.id.widget_image, StudioPainter.paint(context, shaped, view, w, h, density, rtl))
                val toggle = WidgetActions.pending(context, WidgetActions.ACTION_TOGGLE, t.method, id, t.country)
                val open = WidgetActions.pending(context, WidgetActions.ACTION_OPEN, t.method, id)
                when (size) {
                    StudioSize.SMALL -> {
                        rv.setOnClickPendingIntent(R.id.zone_open, open)
                        rv.setOnClickPendingIntent(R.id.zone_toggle, toggle)
                    }
                    StudioSize.MEDIUM -> {
                        // The button is on the END half: left in Persian, right in English.
                        rv.setOnClickPendingIntent(R.id.zone_left, if (rtl) toggle else open)
                        rv.setOnClickPendingIntent(R.id.zone_right, if (rtl) open else toggle)
                    }
                    StudioSize.LARGE -> {
                        rv.setOnClickPendingIntent(R.id.zone_open, open)
                        rv.setOnClickPendingIntent(R.id.zone_toggle, toggle)
                        rv.setOnClickPendingIntent(R.id.zone_open_bottom, open)
                    }
                }
                val name = WidgetMethods.app(t.method)?.let { WidgetPainter.labelOf(it) } ?: t.method
                rv.setContentDescription(R.id.widget_root, WidgetFormat.joined(name, describe(view)))
                mgr.updateAppWidget(id, rv)
            }.onFailure { android.util.Log.w("WidgetUpdater", "studio widget $id: ${it.message}") }
        }
    }

    private fun zoneIds(size: StudioSize): List<Int> = when (size) {
        StudioSize.SMALL -> listOf(R.id.zone_open, R.id.zone_toggle)
        StudioSize.MEDIUM -> listOf(R.id.zone_left, R.id.zone_right)
        StudioSize.LARGE -> listOf(R.id.zone_open, R.id.zone_toggle, R.id.zone_open_bottom)
    }

    /** The state in words, for TalkBack. */
    private fun describe(view: MethodView): String = when {
        view.up -> tr("متصل", "Connected")
        view.connecting -> tr("در حال اتصال", "Connecting")
        else -> tr("قطع", "Disconnected")
    }
}
