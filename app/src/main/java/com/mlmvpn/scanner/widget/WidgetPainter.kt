package com.mlmvpn.scanner.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.home.HomeApp
import com.mlmvpn.scanner.widget.studio.MethodStyle
import kotlin.math.max
import kotlin.math.min

/**
 * What one widget shows about its own method: its phase (idle when it is another method that is
 * up), since when, where it comes out, and its numbers.
 */
data class MethodView(
    val phase: WidgetLive.Phase,
    val since: Long = 0L,
    val country: String? = null,
    /** Another method that is up or connecting, while this one is idle. */
    val other: String? = null,
    val traffic: WidgetLive.Traffic = WidgetLive.Traffic(),
) {
    val up: Boolean get() = phase == WidgetLive.Phase.CONNECTED
    val connecting: Boolean get() = phase == WidgetLive.Phase.CONNECTING

    companion object {
        fun of(method: String, conn: WidgetLive.Conn, traffic: WidgetLive.Traffic): MethodView =
            if (conn.method == method) MethodView(conn.phase, conn.since, conn.country, null, if (conn.up) traffic else WidgetLive.Traffic())
            else MethodView(WidgetLive.Phase.IDLE, other = conn.method?.takeIf { conn.busy })
    }
}

/**
 * Paints the plain method widgets: the tile a user knows from the in-app board, its name, and its
 * lamp -- one per connection method, so a method is one tap from the phone's own home screen.
 *
 * Three layouts, chosen from the space the launcher gave the widget rather than from a setting, so a
 * user resizing it gets the layout that fits instead of a stretched icon:
 *  - a cell or so: the tile and its caption, exactly like an app icon;
 *  - wide and short: the tile beside the name and the state in words;
 *  - large: a big tile over the name and the state.
 */
object WidgetPainter {

    /** Upper bound on a widget bitmap: big enough for a 4x4 on a dense screen, small enough to send. */
    private const val MAX_PIXELS = 1_400_000

    /** A bitmap for a widget of [widthDp] x [heightDp], scaled down if needed to stay within [MAX_PIXELS]. */
    fun canvasFor(widthDp: Float, heightDp: Float, density: Float): Pair<Bitmap, Float> {
        var scale = density
        val px = widthDp * heightDp * scale * scale
        if (px > MAX_PIXELS) scale *= kotlin.math.sqrt(MAX_PIXELS / px)
        val w = (widthDp * scale).toInt().coerceAtLeast(1)
        val h = (heightDp * scale).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) to scale
    }

    fun paintMethod(
        context: Context,
        app: HomeApp,
        style: MethodStyle,
        view: MethodView,
        widthDp: Float,
        heightDp: Float,
        density: Float,
        rtl: Boolean,
    ): Bitmap {
        val (bmp, d) = canvasFor(widthDp, heightDp, density)
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        when {
            widthDp >= 150f && heightDp < 110f -> wide(c, context, app, style, view, w, h, d, rtl)
            widthDp >= 110f && heightDp >= 110f -> large(c, context, app, style, view, w, h, d, rtl)
            else -> icon(c, context, app, style, view, w, h, d, rtl)
        }
        return bmp
    }

    /** The glass card behind a method widget, when the user chose one. */
    private fun card(c: Canvas, w: Float, h: Float, d: Float, opacity: Int) {
        val rect = RectF(0f, 0f, w, h).apply { inset(d, d) }
        val path = WidgetPaint.round(rect, 22f * d)
        val a = opacity / 100f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h,
                WidgetPaint.alpha(0xFF2C2C30.toInt(), 0.18f + a * 0.70f),
                WidgetPaint.alpha(0xFF0E0E10.toInt(), 0.24f + a * 0.70f),
                Shader.TileMode.CLAMP)
        }
        c.drawPath(path, fill)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, 0.8f * d)
            shader = LinearGradient(0f, 0f, 0f, h, 0x52FFFFFF, 0x0FFFFFFF, Shader.TileMode.CLAMP)
        }
        c.drawPath(path, rim)
    }

    /** The label's drop shadow, the board's own: readable over any wallpaper without a scrim. */
    private fun shadowed(p: android.text.TextPaint, d: Float, on: Boolean) = p.apply {
        if (on) setShadowLayer(2.2f * d, 0f, 0.6f * d, 0xA6000000.toInt())
    }

    /** The state in words, the fullest wording first and shorter ones after it for a narrow widget. */
    private fun stateLines(view: MethodView, style: MethodStyle): List<String> = when {
        view.up -> {
            val on = tr("متصل", "Connected")
            if (view.since > 0) listOf(WidgetFormat.joined(on, WidgetFormat.duration(System.currentTimeMillis() - view.since)), on)
            else listOf(on)
        }
        view.connecting -> listOf(tr("در حال اتصال…", "Connecting…"))
        view.other != null -> listOfNotNull(
            WidgetMethods.app(view.other)?.let { tr("«${labelOf(it)}» وصل است", "${labelOf(it)} is on") },
            tr("روش دیگری وصل است", "Another method is on"),
        )
        style.tapConnects -> listOf(tr("برای اتصال بزنید", "Tap to connect"), tr("اتصال", "Connect"))
        else -> listOf(tr("برای باز کردن بزنید", "Tap to open"), tr("باز کردن", "Open"))
    }

    fun labelOf(app: HomeApp): String = com.mlmvpn.scanner.utils.S(app.labelRes)

    private fun lampFor(c: Canvas, tile: RectF, view: MethodView, d: Float) {
        if (!view.up && !view.connecting) return
        val dot = (tile.width() * 0.26f).coerceIn(9f * d, 16f * d)
        // Physical top-left, pulled slightly outside the tile -- where the board puts it.
        WidgetPaint.lamp(c, tile.left + dot * 0.28f, tile.top + dot * 0.28f, dot, view.connecting)
    }

    private fun icon(c: Canvas, ctx: Context, app: HomeApp, style: MethodStyle, view: MethodView, w: Float, h: Float, d: Float, rtl: Boolean) {
        if (style.card) card(c, w, h, d, style.opacity)
        val labelH = if (style.label) 16f * d else 0f
        val gap = if (style.label) 5f * d else 0f
        val pad = if (style.card) 9f * d else 4f * d
        val room = min(w - pad * 2, h - pad * 2 - labelH - gap)
        val tile = room.coerceAtMost(76f * d).coerceAtLeast(20f * d)
        val top = (h - (tile + gap + labelH)) / 2f
        val box = RectF((w - tile) / 2f, top, (w + tile) / 2f, top + tile)
        WidgetArt.drawTile(c, ctx, app, box, shadow = true, rtl = rtl)
        if (style.status) lampFor(c, box, view, d)
        if (style.label) {
            val paint = shadowed(WidgetArt.paint(ctx, 11.5f * d, 0xFFFFFFFF.toInt(), WidgetArt.Weight.MEDIUM), d, !style.card)
            val line = WidgetArt.fit(labelOf(app), paint, w - 4f * d, rtl)
            line.draw(c, w / 2f, box.bottom + gap, WidgetArt.Align.CENTER)
        }
    }

    private fun wide(c: Canvas, ctx: Context, app: HomeApp, style: MethodStyle, view: MethodView, w: Float, h: Float, d: Float, rtl: Boolean) {
        if (style.card) card(c, w, h, d, style.opacity)
        val pad = 12f * d
        val tile = min(h - pad * 2, 58f * d).coerceAtLeast(24f * d)
        val tileLeft = if (rtl) w - pad - tile else pad
        val box = RectF(tileLeft, (h - tile) / 2f, tileLeft + tile, (h + tile) / 2f)
        WidgetArt.drawTile(c, ctx, app, box, shadow = true, rtl = rtl)
        if (style.status) lampFor(c, box, view, d)

        val textW = w - pad * 3 - tile
        val anchor = if (rtl) box.left - pad else box.right + pad
        val align = if (rtl) WidgetArt.Align.RIGHT else WidgetArt.Align.LEFT
        val name = WidgetArt.fit(labelOf(app), shadowed(WidgetArt.paint(ctx, 15f * d, 0xFFFFFFFF.toInt(), WidgetArt.Weight.BOLD), d, !style.card), textW, rtl, 0.76f)
        val stateColor = when {
            view.up -> 0xFF5AF08A.toInt()
            view.connecting -> 0xFFFFC15A.toInt()
            else -> 0xC8FFFFFF.toInt()
        }
        val state = if (style.status || style.label) WidgetArt.firstFitting(stateLines(view, style),
            shadowed(WidgetArt.paint(ctx, 11.5f * d, stateColor, WidgetArt.Weight.MEDIUM), d, !style.card), textW, rtl) else null
        val total = name.height + (state?.let { it.height + 3f * d } ?: 0f)
        var y = (h - total) / 2f
        name.draw(c, anchor, y, align)
        y += name.height + 3f * d
        state?.draw(c, anchor, y, align)
    }

    private fun large(c: Canvas, ctx: Context, app: HomeApp, style: MethodStyle, view: MethodView, w: Float, h: Float, d: Float, rtl: Boolean) {
        if (style.card) card(c, w, h, d, style.opacity)
        val pad = 12f * d
        val textBlock = (if (style.label) 20f * d else 0f) + (if (style.status) 16f * d else 0f)
        val tile = min(w - pad * 2, h - pad * 2 - textBlock - 8f * d).coerceIn(28f * d, 120f * d)
        val top = (h - tile - 8f * d - textBlock) / 2f
        val box = RectF((w - tile) / 2f, top, (w + tile) / 2f, top + tile)
        WidgetArt.drawTile(c, ctx, app, box, shadow = true, rtl = rtl)
        if (style.status) lampFor(c, box, view, d)
        var y = box.bottom + 8f * d
        if (style.label) {
            val name = WidgetArt.fit(labelOf(app), shadowed(WidgetArt.paint(ctx, 15f * d, 0xFFFFFFFF.toInt(), WidgetArt.Weight.BOLD), d, !style.card), w - pad * 2, rtl)
            name.draw(c, w / 2f, y, WidgetArt.Align.CENTER)
            y += name.height + 2f * d
        }
        if (style.status) {
            val color = when {
                view.up -> 0xFF5AF08A.toInt()
                view.connecting -> 0xFFFFC15A.toInt()
                else -> 0xC8FFFFFF.toInt()
            }
            WidgetArt.firstFitting(stateLines(view, style), shadowed(WidgetArt.paint(ctx, 11.5f * d, color), d, !style.card), w - pad * 2, rtl)
                .draw(c, w / 2f, y, WidgetArt.Align.CENTER)
        }
    }
}
