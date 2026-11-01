package com.mlmvpn.scanner.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.chat.MlmChat
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.home.HomeApp
import com.mlmvpn.scanner.ui.home.HomeDestinations
import com.mlmvpn.scanner.utils.AppLocaleManager
import com.mlmvpn.scanner.widget.studio.MethodStyle
import com.mlmvpn.scanner.widget.studio.StudioStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * «گره» on the home screen: Gereh's tile, how many messages wait unread and who sent the last one,
 * and whether Gereh reaches anyone right now. A tap opens Gereh.
 *
 * Gereh runs in its own `:chat` process (MlmChat). Its bridge (org.mlmvpn.chat.bridge.GerehWidgetState)
 * writes its small state file -- a count, a name and one word for the network, never a message's
 * text -- and sends [GerehWidgets.ACTION_CHANGED] here only while a widget is placed, so a phone
 * without one never has this process woken for it. The look is the method widgets' own
 * ([WidgetPainter]), with the one setting they share ([MethodStyle]).
 */
class GerehWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        render(context, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == GerehWidgets.ACTION_CHANGED) {
            render(context, GerehWidgets.ids(context))
            return
        }
        super.onReceive(context, intent)
    }

    private fun render(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                GerehWidgets.render(app, AppWidgetManager.getInstance(app), ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

object GerehWidgets {

    /** Sent by Gereh's bridge in `:chat`; explicit, to [GerehWidget] only (named in MlmApp: GerehHost.setWidget). */
    const val ACTION_CHANGED = org.mlmvpn.chat.bridge.GerehWidgetState.ACTION_CHANGED

    /**
     * What the widget shows. [net] is one of `online`, `connecting`, `local` (the same Wi-Fi or
     * hotspot), `ble` (Bluetooth), `waiting` (no network), or empty when unknown.
     */
    data class State(val unread: Int = 0, val from: String = "", val net: String = "")

    /** As the bridge wrote it (its file, and its rule for a stale network word), shared with any app's widget. */
    fun read(context: Context): State =
        org.mlmvpn.chat.bridge.GerehWidgetState.read(context).let { State(it.unread, it.from, it.net) }

    fun ids(context: Context): IntArray =
        runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, GerehWidget::class.java)) }
            .getOrNull() ?: IntArray(0)

    fun placedCount(context: Context): Int = ids(context).size

    /** Asks the launcher to place the widget; false when it cannot (the caller explains the manual way). */
    fun requestPin(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !MethodWidgets.canPin(context)) return false
        return runCatching {
            AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, GerehWidget::class.java), null, null)
        }.getOrDefault(false)
    }

    /** Gereh's tile, or null in a build without it. */
    fun tile(): HomeApp? = HomeDestinations.gridById(MlmChat.TILE_ID)

    suspend fun refresh(context: Context) {
        val ids = ids(context)
        if (ids.isNotEmpty()) render(context, AppWidgetManager.getInstance(context), ids)
    }

    fun render(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val app = tile() ?: return
        val style = StudioStore.get(context).methodStyle
        val state = read(context)
        val density = context.resources.displayMetrics.density
        val rtl = AppLocaleManager.isFarsi()
        for (id in ids) {
            runCatching {
                val (w, h) = WidgetUpdater.sizeOf(context, runCatching { mgr.getAppWidgetOptions(id) }.getOrNull(), 76f, 92f)
                val bmp = GerehPainter.paint(context, app, style, state, w, h, density, rtl)
                val rv = RemoteViews(context.packageName, R.layout.widget_method)
                rv.setImageViewBitmap(R.id.widget_image, bmp)
                rv.setContentDescription(R.id.widget_root, WidgetFormat.joined(WidgetPainter.labelOf(app), GerehPainter.unreadText(state)))
                rv.setOnClickPendingIntent(R.id.widget_root, openIntent(context))
                mgr.updateAppWidget(id, rv)
            }.onFailure { android.util.Log.w("GerehWidget", "widget $id: ${it.message}") }
        }
    }

    /** Gereh itself; the app on Gereh's tile when this phone has no Gereh (a 32-bit build). */
    private fun openIntent(context: Context): PendingIntent {
        val intent = if (MlmChat.available(context)) {
            Intent().setClassName(context, "org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            WidgetOpen.intent(context, MlmChat.TILE_ID)
        }
        return PendingIntent.getActivity(context, 0x6765, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** The three layouts of [WidgetPainter], with an unread badge on the tile instead of a lamp. */
internal object GerehPainter {

    private const val GREEN = 0xFF5AF08A.toInt()
    private const val AMBER = 0xFFFFC15A.toInt()
    private const val DIM = 0xC8FFFFFF.toInt()
    private const val BADGE = 0xFFFF3B30.toInt()

    fun paint(context: Context, app: HomeApp, style: MethodStyle, state: GerehWidgets.State,
              widthDp: Float, heightDp: Float, density: Float, rtl: Boolean): Bitmap {
        val (bmp, d) = WidgetPainter.canvasFor(widthDp, heightDp, density)
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        when {
            widthDp >= 150f && heightDp < 110f -> wide(c, context, app, style, state, w, h, d, rtl)
            widthDp >= 110f && heightDp >= 110f -> large(c, context, app, style, state, w, h, d, rtl)
            else -> icon(c, context, app, style, state, w, h, d, rtl)
        }
        return bmp
    }

    /** «۳ پیام تازه از علی», the shorter wordings after it for a narrow widget. */
    private fun unreadLines(s: GerehWidgets.State): List<String> = when {
        s.unread <= 0 -> listOf(tr("پیام تازه‌ای نیست", "No new messages"), tr("بی پیام تازه", "Nothing new"))
        s.from.isNotBlank() -> listOf(
            WidgetFormat.digits(tr("${s.unread} پیام تازه از ${s.from}", "${s.unread} new from ${s.from}")),
            WidgetFormat.digits(tr("${s.unread} پیام تازه", "${s.unread} new")),
        )
        else -> listOf(WidgetFormat.digits(tr("${s.unread} پیام تازه", "${s.unread} new")))
    }

    fun unreadText(s: GerehWidgets.State): String = unreadLines(s).first()

    private fun netText(s: GerehWidgets.State): String? = when (s.net) {
        "online" -> tr("وصل", "Connected")
        "connecting" -> tr("در حال اتصال…", "Connecting…")
        "local" -> tr("از راه وای‌فای", "Over Wi-Fi")
        "ble" -> tr("از راه بلوتوث", "Over Bluetooth")
        "waiting" -> tr("در انتظار شبکه…", "Waiting for network…")
        else -> null
    }

    private fun netColor(s: GerehWidgets.State): Int = when (s.net) {
        "online", "local", "ble" -> GREEN
        "connecting" -> AMBER
        else -> DIM
    }

    private fun card(c: Canvas, w: Float, h: Float, d: Float, opacity: Int) {
        val rect = RectF(0f, 0f, w, h).apply { inset(d, d) }
        val path = WidgetPaint.round(rect, 22f * d)
        val a = opacity / 100f
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h,
                WidgetPaint.alpha(0xFF2C2C30.toInt(), 0.18f + a * 0.70f),
                WidgetPaint.alpha(0xFF0E0E10.toInt(), 0.24f + a * 0.70f),
                Shader.TileMode.CLAMP)
        })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = max(1f, 0.8f * d)
            shader = LinearGradient(0f, 0f, 0f, h, 0x52FFFFFF, 0x0FFFFFFF, Shader.TileMode.CLAMP)
        })
    }

    private fun shadowed(p: android.text.TextPaint, d: Float, on: Boolean) = p.apply {
        if (on) setShadowLayer(2.2f * d, 0f, 0.6f * d, 0xA6000000.toInt())
    }

    /** The red count at the tile's physical top-right, pulled slightly outside it, like a launcher's. */
    private fun badge(c: Canvas, ctx: Context, tile: RectF, s: GerehWidgets.State, d: Float) {
        if (s.unread <= 0) return
        val text = WidgetFormat.digits(if (s.unread > 99) "99+" else s.unread.toString())
        val size = (tile.width() * 0.30f).coerceIn(15f * d, 24f * d)
        val paint = WidgetArt.paint(ctx, size * 0.62f, 0xFFFFFFFF.toInt(), WidgetArt.Weight.BOLD)
        val line = WidgetArt.line(text, paint, tile.width(), false)
        val bw = max(size, line.width + size * 0.55f)
        val right = tile.right + size * 0.22f
        val top = tile.top - size * 0.22f
        val rect = RectF(right - bw, top, right, top + size)
        c.drawRoundRect(rect, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BADGE
            setShadowLayer(1.6f * d, 0f, 0.5f * d, 0x66000000)
        })
        line.draw(c, rect.centerX(), rect.centerY() - line.height / 2f, WidgetArt.Align.CENTER)
    }

    private fun icon(c: Canvas, ctx: Context, app: HomeApp, style: MethodStyle, s: GerehWidgets.State, w: Float, h: Float, d: Float, rtl: Boolean) {
        if (style.card) card(c, w, h, d, style.opacity)
        val labelH = if (style.label) 16f * d else 0f
        val gap = if (style.label) 5f * d else 0f
        val pad = if (style.card) 9f * d else 6f * d
        val room = min(w - pad * 2, h - pad * 2 - labelH - gap)
        val tile = room.coerceAtMost(76f * d).coerceAtLeast(20f * d)
        val top = (h - (tile + gap + labelH)) / 2f
        val box = RectF((w - tile) / 2f, top, (w + tile) / 2f, top + tile)
        WidgetArt.drawTile(c, ctx, app, box, shadow = true, rtl = rtl)
        badge(c, ctx, box, s, d)
        if (style.label) {
            val paint = shadowed(WidgetArt.paint(ctx, 11.5f * d, 0xFFFFFFFF.toInt(), WidgetArt.Weight.MEDIUM), d, !style.card)
            WidgetArt.fit(WidgetPainter.labelOf(app), paint, w - 4f * d, rtl).draw(c, w / 2f, box.bottom + gap, WidgetArt.Align.CENTER)
        }
    }

    private fun wide(c: Canvas, ctx: Context, app: HomeApp, style: MethodStyle, s: GerehWidgets.State, w: Float, h: Float, d: Float, rtl: Boolean) {
        if (style.card) card(c, w, h, d, style.opacity)
        val pad = 12f * d
        val tile = min(h - pad * 2, 58f * d).coerceAtLeast(24f * d)
        val tileLeft = if (rtl) w - pad - tile else pad
        val box = RectF(tileLeft, (h - tile) / 2f, tileLeft + tile, (h + tile) / 2f)
        WidgetArt.drawTile(c, ctx, app, box, shadow = true, rtl = rtl)
        badge(c, ctx, box, s, d)

        val textW = w - pad * 3 - tile
        val anchor = if (rtl) box.left - pad else box.right + pad
        val align = if (rtl) WidgetArt.Align.RIGHT else WidgetArt.Align.LEFT
        val name = WidgetArt.fit(WidgetPainter.labelOf(app), shadowed(WidgetArt.paint(ctx, 15f * d, 0xFFFFFFFF.toInt(), WidgetArt.Weight.BOLD), d, !style.card), textW, rtl, 0.76f)
        val unread = WidgetArt.firstFitting(unreadLines(s),
            shadowed(WidgetArt.paint(ctx, 11.5f * d, if (s.unread > 0) 0xFFFFFFFF.toInt() else DIM, WidgetArt.Weight.MEDIUM), d, !style.card), textW, rtl)
        val net = if (style.status) netText(s)?.let {
            WidgetArt.line(it, shadowed(WidgetArt.paint(ctx, 10.5f * d, netColor(s)), d, !style.card), textW, rtl)
        } else null
        val total = name.height + unread.height + 3f * d + (net?.let { it.height + 2f * d } ?: 0f)
        var y = (h - total) / 2f
        name.draw(c, anchor, y, align)
        y += name.height + 3f * d
        unread.draw(c, anchor, y, align)
        y += unread.height + 2f * d
        net?.draw(c, anchor, y, align)
    }

    private fun large(c: Canvas, ctx: Context, app: HomeApp, style: MethodStyle, s: GerehWidgets.State, w: Float, h: Float, d: Float, rtl: Boolean) {
        if (style.card) card(c, w, h, d, style.opacity)
        val pad = 12f * d
        val net = if (style.status) netText(s) else null
        val textBlock = (if (style.label) 20f * d else 0f) + 16f * d + (if (net != null) 15f * d else 0f)
        val tile = min(w - pad * 2, h - pad * 2 - textBlock - 8f * d).coerceIn(28f * d, 120f * d)
        val top = (h - tile - 8f * d - textBlock) / 2f
        val box = RectF((w - tile) / 2f, top, (w + tile) / 2f, top + tile)
        WidgetArt.drawTile(c, ctx, app, box, shadow = true, rtl = rtl)
        badge(c, ctx, box, s, d)
        var y = box.bottom + 8f * d
        if (style.label) {
            val name = WidgetArt.fit(WidgetPainter.labelOf(app), shadowed(WidgetArt.paint(ctx, 15f * d, 0xFFFFFFFF.toInt(), WidgetArt.Weight.BOLD), d, !style.card), w - pad * 2, rtl)
            name.draw(c, w / 2f, y, WidgetArt.Align.CENTER)
            y += name.height + 2f * d
        }
        val unread = WidgetArt.firstFitting(unreadLines(s),
            shadowed(WidgetArt.paint(ctx, 11.5f * d, if (s.unread > 0) 0xFFFFFFFF.toInt() else DIM), d, !style.card), w - pad * 2, rtl)
        unread.draw(c, w / 2f, y, WidgetArt.Align.CENTER)
        y += unread.height + 2f * d
        net?.let {
            WidgetArt.line(it, shadowed(WidgetArt.paint(ctx, 10.5f * d, netColor(s)), d, !style.card), w - pad * 2, rtl)
                .draw(c, w / 2f, y, WidgetArt.Align.CENTER)
        }
    }
}
