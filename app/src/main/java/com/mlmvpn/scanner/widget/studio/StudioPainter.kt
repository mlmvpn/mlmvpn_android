package com.mlmvpn.scanner.widget.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.home.HomeApp
import com.mlmvpn.scanner.widget.MethodView
import com.mlmvpn.scanner.widget.WidgetArt
import com.mlmvpn.scanner.widget.WidgetFormat
import com.mlmvpn.scanner.widget.WidgetMethods
import com.mlmvpn.scanner.widget.WidgetPaint
import com.mlmvpn.scanner.widget.WidgetPainter
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Paints a studio design ([WidgetTemplate]) into a bitmap: the widget on the phone's home screen
 * and the studio's live preview are this same picture, so what the user designs is exactly what
 * they get.
 *
 * Every layout leaves the button inside the part of the widget that is wired to connect, and the
 * information inside the part wired to open the method -- see [zones]; the two must agree, so
 * both live in this file.
 */
object StudioPainter {

    /**
     * Where a placed widget's taps go, as fractions of its size: each design keeps its button inside
     * [toggle] and its information outside it. The layouts in res/layout/widget_studio_*.xml carve the
     * same regions.
     */
    enum class Zones { SMALL_TOP_BOTTOM, MEDIUM_HALVES, LARGE_BANDS }

    fun zones(size: StudioSize): Zones = when (size) {
        StudioSize.SMALL -> Zones.SMALL_TOP_BOTTOM
        StudioSize.MEDIUM -> Zones.MEDIUM_HALVES
        StudioSize.LARGE -> Zones.LARGE_BANDS
    }

    /** SMALL: the top part opens the method, the rest is the button. */
    const val SMALL_TOP = 0.38f
    /** LARGE: header band and footer band open the method; the middle band is the button. */
    const val LARGE_TOP = 0.30f
    const val LARGE_BOTTOM = 0.30f

    private class Frame(val w: Float, val h: Float, val d: Float, val rtl: Boolean, val fs: Float) {
        fun dp(v: Float) = v * d
        fun sp(v: Float) = v * d * fs
        /** A rectangle given from the start edge, mirrored in right-to-left. */
        fun rect(xStart: Float, top: Float, width: Float, height: Float): RectF =
            if (rtl) RectF(w - xStart - width, top, w - xStart, top + height) else RectF(xStart, top, xStart + width, top + height)
        /** The x of a point [xStart] from the start edge. */
        fun x(xStart: Float): Float = if (rtl) w - xStart else xStart
        val start get() = if (rtl) WidgetArt.Align.RIGHT else WidgetArt.Align.LEFT
        val end get() = if (rtl) WidgetArt.Align.LEFT else WidgetArt.Align.RIGHT
    }

    /** The colours a design resolves to, once: text on a pale card is dark, everywhere else white. */
    private class Ink(t: WidgetTemplate) {
        val light = WidgetPaint.isLight(t.background, t.opacity) && t.style != StudioStyle.COLORFUL
        val text = if (light) 0xFF111114.toInt() else 0xFFFFFFFF.toInt()
        val soft = if (light) 0x993C3C43.toInt() else 0xB3FFFFFF.toInt()
        val faint = if (light) 0x1F000000 else 0x1FFFFFFF
        val chip = if (light) 0x14000000 else 0x1AFFFFFF
        /** A card painted in the accent itself (رنگارنگ): anything else in the accent would vanish on it. */
        val onAccent = t.style == StudioStyle.COLORFUL
        /** Download: the chart's first line and its arrow. */
        val down = if (onAccent) 0xFFFFFFFF.toInt() else t.accent
        /** Upload: related to the accent but clearly another colour. */
        val up = if (onAccent) WidgetPaint.lighten(WidgetPaint.hueShift(t.accent, 55f), 0.55f)
            else WidgetPaint.hueShift(t.accent, if (light) -40f else 55f)
        /** The button's colour once connected. */
        val live = if (onAccent) 0xFFFFFFFF.toInt() else t.accent
    }

    fun paint(
        context: Context,
        t: WidgetTemplate,
        view: MethodView,
        widthDp: Float,
        heightDp: Float,
        density: Float,
        rtl: Boolean,
    ): Bitmap {
        val (bmp, d) = WidgetPainter.canvasFor(widthDp, heightDp, density)
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val base = when (t.size) {
            StudioSize.SMALL -> min(widthDp, heightDp) / 158f
            StudioSize.MEDIUM -> heightDp / 150f
            StudioSize.LARGE -> min(widthDp / 330f, heightDp / 330f)
        }.coerceIn(0.82f, 1.22f)
        val f = Frame(w, h, d, rtl, base * t.textScale / 100f)
        val ink = Ink(t)
        val app = WidgetMethods.app(t.method)
        val button = buttonCenter(t, f)
        background(c, t, f, ink, view, button)
        when (t.size) {
            StudioSize.SMALL -> if (t.style == StudioStyle.COMPACT) smallCompact(c, context, t, app, view, f, ink) else small(c, context, t, app, view, f, ink)
            StudioSize.MEDIUM -> medium(c, context, t, app, view, f, ink)
            StudioSize.LARGE -> large(c, context, t, app, view, f, ink)
        }
        return bmp
    }

    /** A placed widget whose design was deleted: says so instead of turning into something else. */
    fun paintMissing(context: Context, widthDp: Float, heightDp: Float, density: Float, rtl: Boolean): Bitmap {
        val (bmp, d) = WidgetPainter.canvasFor(widthDp, heightDp, density)
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val rect = RectF(d, d, w - d, h - d)
        c.drawPath(WidgetPaint.round(rect, 24f * d), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD91C1C1E.toInt() })
        val title = WidgetArt.line(tr("این قالب حذف شده است", "This design was deleted"),
            WidgetArt.paint(context, 14f * d, 0xFFFFFFFF.toInt(), WidgetArt.Weight.BOLD), w - 24f * d, rtl)
        val sub = WidgetArt.line(tr("برای انتخاب قالب دیگر بزنید", "Tap to pick another design"),
            WidgetArt.paint(context, 11.5f * d, 0xB3FFFFFF.toInt()), w - 24f * d, rtl)
        val y = (h - title.height - sub.height - 4f * d) / 2f
        title.draw(c, w / 2f, y, WidgetArt.Align.CENTER)
        sub.draw(c, w / 2f, y + title.height + 4f * d, WidgetArt.Align.CENTER)
        return bmp
    }

    // ---------------------------------------------------------------------------------------------
    // Card
    // ---------------------------------------------------------------------------------------------

    private fun cardRect(f: Frame) = RectF(f.dp(1f), f.dp(1f), f.w - f.dp(1f), f.h - f.dp(1f))

    private fun background(c: Canvas, t: WidgetTemplate, f: Frame, ink: Ink, view: MethodView, button: Pair<Float, Float>) {
        val rect = cardRect(f)
        val path = WidgetPaint.round(rect, f.dp(t.corner.toFloat()))
        val op = t.opacity / 100f
        val bg = t.background
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        when (t.style) {
            StudioStyle.COLORFUL -> {
                val a = t.accent
                fill.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    intArrayOf(WidgetPaint.alpha(a, op), WidgetPaint.alpha(WidgetPaint.hueShift(a, 42f), op), WidgetPaint.alpha(bg, op)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                c.drawPath(path, fill)
            }
            StudioStyle.WAVE -> {
                fill.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                    WidgetPaint.alpha(WidgetPaint.lighten(bg, 0.06f), op), WidgetPaint.alpha(WidgetPaint.darken(bg, 0.35f), op), Shader.TileMode.CLAMP)
                c.drawPath(path, fill)
            }
            StudioStyle.GLASS, StudioStyle.IOS -> {
                fill.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    WidgetPaint.alpha(WidgetPaint.lighten(bg, 0.10f), op), WidgetPaint.alpha(WidgetPaint.darken(bg, 0.10f), op), Shader.TileMode.CLAMP)
                c.drawPath(path, fill)
            }
            else -> {
                fill.color = WidgetPaint.alpha(bg, op)
                c.drawPath(path, fill)
            }
        }

        c.save()
        c.clipPath(path)
        when (t.style) {
            StudioStyle.GLASS, StudioStyle.IOS -> {
                // The sheen a pane of glass catches across its top corner.
                val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(rect.left, rect.top, rect.left + rect.width() * 0.7f, rect.top + rect.height() * 0.9f,
                        if (ink.light) 0x66FFFFFF else 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                }
                c.drawRect(rect, sheen)
            }
            StudioStyle.COLORFUL -> {
                // Soft light circles for depth.
                val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
                listOf(Triple(0.86f, 0.12f, 0.42f), Triple(0.10f, 0.95f, 0.36f), Triple(0.55f, 0.55f, 0.22f)).forEach { (x, y, r) ->
                    val cx = rect.left + rect.width() * x
                    val cy = rect.top + rect.height() * y
                    val rad = max(rect.width(), rect.height()) * r
                    bubble.shader = RadialGradient(cx, cy, rad, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                    c.drawCircle(cx, cy, rad, bubble)
                }
            }
            StudioStyle.WAVE -> waves(c, rect, t.accent)
            StudioStyle.DARK_PRO -> {
                // A fine diagonal hatch and an accent hairline across the top: an instrument panel.
                val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x08FFFFFF; strokeWidth = f.dp(1f) }
                val step = f.dp(7f)
                var x = rect.left - rect.height()
                while (x < rect.right) {
                    c.drawLine(x, rect.bottom, x + rect.height(), rect.top, hatch)
                    x += step
                }
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(rect.left, 0f, rect.right, 0f,
                        intArrayOf(0x00000000, t.accent, 0x00000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    strokeWidth = f.dp(1.6f)
                }
                c.drawLine(rect.left, rect.top + f.dp(1f), rect.right, rect.top + f.dp(1f), line)
            }
            else -> Unit
        }
        // The accent's glow where the button is; stronger while connected.
        if (t.style != StudioStyle.MINIMAL && t.style != StudioStyle.IOS) {
            val (bx, by) = button
            val radius = max(rect.width(), rect.height()) * 0.55f
            val glowA = when {
                view.up -> 0.30f
                view.connecting -> 0.18f
                else -> 0.10f
            }
            val glowColor = if (view.connecting) WidgetPaint.AMBER else t.accent
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(bx, by, radius, WidgetPaint.alpha(glowColor, glowA), WidgetPaint.alpha(glowColor, 0f), Shader.TileMode.CLAMP)
            }
            c.drawRect(rect, glow)
        }
        c.restore()

        // The rim.
        when (t.style) {
            StudioStyle.NEON -> {
                val inset = RectF(rect).apply { inset(f.dp(3f), f.dp(3f)) }
                val neonPath = WidgetPaint.round(inset, f.dp(t.corner.toFloat()) - f.dp(2f))
                val blur = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = f.dp(2.4f)
                    color = WidgetPaint.alpha(t.accent, 0.85f)
                    maskFilter = BlurMaskFilter(f.dp(5f), BlurMaskFilter.Blur.NORMAL)
                }
                c.drawPath(neonPath, blur)
                val crisp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = f.dp(1.3f)
                    color = WidgetPaint.lighten(t.accent, 0.25f)
                }
                c.drawPath(neonPath, crisp)
            }
            else -> {
                val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = f.dp(1.1f)
                    shader = when (t.style) {
                        StudioStyle.MINIMAL, StudioStyle.DARK_PRO -> null
                        else -> LinearGradient(0f, rect.top, 0f, rect.bottom,
                            if (ink.light) 0xB3FFFFFF.toInt() else 0x6BFFFFFF, if (ink.light) 0x40FFFFFF else 0x0FFFFFFF, Shader.TileMode.CLAMP)
                    }
                    if (shader == null) color = 0x14FFFFFF
                }
                c.drawPath(path, rim)
            }
        }
    }

    private fun waves(c: Canvas, rect: RectF, accent: Int) {
        val layers = listOf(
            Triple(0.62f, 0.00f, WidgetPaint.alpha(accent, 0.32f)),
            Triple(0.70f, 1.10f, WidgetPaint.alpha(WidgetPaint.hueShift(accent, 40f), 0.24f)),
            Triple(0.80f, 2.20f, WidgetPaint.alpha(WidgetPaint.hueShift(accent, -30f), 0.20f)),
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((level, phase, color) in layers) {
            val path = Path()
            val baseY = rect.top + rect.height() * level
            val amp = rect.height() * 0.07f
            val len = rect.width() * 0.95f
            path.moveTo(rect.left, rect.bottom)
            var x = rect.left
            while (x <= rect.right + 2f) {
                val y = baseY + amp * sin(((x - rect.left) / len) * 2.0 * PI + phase).toFloat()
                path.lineTo(x, y)
                x += 4f
            }
            path.lineTo(rect.right, rect.bottom)
            path.close()
            p.shader = LinearGradient(0f, baseY - amp, 0f, rect.bottom, color, WidgetPaint.fade(color, 0.25f), Shader.TileMode.CLAMP)
            c.drawPath(path, p)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared pieces
    // ---------------------------------------------------------------------------------------------

    private fun methodName(app: HomeApp?, t: WidgetTemplate): String = app?.let { WidgetPainter.labelOf(it) } ?: t.method

    private fun statusText(view: MethodView): String = when {
        view.up -> tr("متصل", "Connected")
        view.connecting -> tr("در حال اتصال…", "Connecting…")
        view.other != null -> WidgetMethods.app(view.other)?.let { tr("«${WidgetPainter.labelOf(it)}» وصل است", "${WidgetPainter.labelOf(it)} is on") }
            ?: tr("قطع", "Disconnected")
        else -> tr("قطع", "Disconnected")
    }

    /**
     * [status] with the session's length after it when the design shows the time -- and [status]
     * alone after that, the wording [WidgetArt.firstFitting] falls back to on a narrow widget.
     */
    private fun withTime(status: String, t: WidgetTemplate, view: MethodView, always: Boolean = false): List<String> =
        if ((always || t.shows(Info.TIME)) && view.up && view.since > 0)
            listOf(WidgetFormat.joined(status, WidgetFormat.duration(System.currentTimeMillis() - view.since)), status)
        else listOf(status)

    private fun statusColor(view: MethodView, ink: Ink): Int = when {
        view.up -> WidgetPaint.GREEN
        view.connecting -> WidgetPaint.AMBER
        else -> if (ink.light) 0xFF8E8E93.toInt() else 0xFF9A9AA0.toInt()
    }

    /** The country this widget shows: where the session comes out, else the one chosen, else «خودکار». */
    private fun countryOf(t: WidgetTemplate, view: MethodView): String? = if (view.up && view.country != null) view.country else t.country

    private fun countryLabel(code: String?): String =
        if (code == null) tr("کشور خودکار", "Auto country") else WidgetFormat.countryName(code)

    /** Method tile, or the app icon for a method this build does not have. */
    private fun icon(c: Canvas, ctx: Context, app: HomeApp?, box: RectF, rtl: Boolean) {
        if (app != null) WidgetArt.drawTile(c, ctx, app, box, shadow = false, rtl = rtl) else WidgetArt.drawAppIcon(c, ctx, box)
    }

    /** A rounded chip with a flag and the country name, and a chevron saying it can be changed. */
    private fun countryChip(c: Canvas, ctx: Context, t: WidgetTemplate, view: MethodView, f: Frame, ink: Ink, xStart: Float, top: Float, maxW: Float, height: Float): Float {
        val code = countryOf(t, view)
        val flag = WidgetFormat.flag(code)
        val textPaint = WidgetArt.paint(ctx, f.sp(11.5f), ink.text, WidgetArt.Weight.MEDIUM)
        val flagPaint = WidgetArt.paint(ctx, f.sp(13f), ink.text)
        val chevronW = f.dp(8f)
        val padIn = f.dp(9f)
        val flagLine = WidgetArt.line(flag, flagPaint, f.dp(30f), false)
        val name = WidgetArt.line(countryLabel(code), textPaint, maxW - padIn * 3 - flagLine.width - chevronW - f.dp(6f), f.rtl)
        val width = min(maxW, padIn * 2 + flagLine.width + f.dp(6f) + name.width + f.dp(6f) + chevronW)
        val r = f.rect(xStart, top, width, height)
        c.drawPath(WidgetPaint.round(r, height / 2f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.chip })
        // Flag at the start, name after it, chevron at the end.
        val flagX = f.x(xStart + padIn)
        flagLine.draw(c, flagX, top + (height - flagLine.height) / 2f, f.start)
        val nameX = f.x(xStart + padIn + flagLine.width + f.dp(6f))
        name.draw(c, nameX, top + (height - name.height) / 2f, f.start)
        val chevX = f.x(xStart + width - padIn - chevronW / 2f)
        WidgetPaint.chevronDown(c, chevX, top + height / 2f, chevronW, ink.soft, f.dp(1.6f))
        return width
    }

    /** «↓ 1.2 MB/s   ↑ 300 KB/s» from the start edge; returns the height used. */
    private fun speeds(c: Canvas, ctx: Context, view: MethodView, f: Frame, ink: Ink, xStart: Float, top: Float, maxW: Float, size: Float = 11f): Float {
        val p = WidgetArt.paint(ctx, f.sp(size), ink.text, WidgetArt.Weight.MEDIUM)
        val arrow = f.sp(size) * 1.05f
        val down = WidgetArt.line(WidgetFormat.rate(view.traffic.rxRate), p, maxW / 2f - arrow, f.rtl)
        val up = WidgetArt.line(WidgetFormat.rate(view.traffic.txRate), p, maxW / 2f - arrow, f.rtl)
        val h = max(down.height, arrow)
        WidgetPaint.arrow(c, f.rect(xStart, top + (h - arrow) / 2f, arrow, arrow), down = true, color = ink.down)
        down.draw(c, f.x(xStart + arrow + f.dp(3f)), top + (h - down.height) / 2f, f.start)
        val second = xStart + arrow + f.dp(3f) + down.width + f.dp(12f)
        if (second + arrow + up.width <= xStart + maxW) {
            WidgetPaint.arrow(c, f.rect(second, top + (h - arrow) / 2f, arrow, arrow), down = false, color = ink.up)
            up.draw(c, f.x(second + arrow + f.dp(3f)), top + (h - up.height) / 2f, f.start)
        }
        return h
    }

    /** The button's centre, for the glow behind it; each layout puts it in its own place. */
    private fun buttonCenter(t: WidgetTemplate, f: Frame): Pair<Float, Float> = when (t.size) {
        StudioSize.SMALL -> f.w / 2f to f.h * (SMALL_TOP + (1f - SMALL_TOP) * 0.52f)
        StudioSize.MEDIUM -> f.x(f.w * 0.75f) to f.h / 2f
        StudioSize.LARGE -> f.w / 2f to f.h * (LARGE_TOP + (1f - LARGE_TOP - LARGE_BOTTOM) / 2f)
    }

    /** The round buttons: [r] is the button's radius. */
    private fun roundButton(c: Canvas, t: WidgetTemplate, view: MethodView, ink: Ink, cx: Float, cy: Float, r: Float) {
        val stateColor = when {
            view.connecting -> WidgetPaint.AMBER
            view.up -> ink.live
            else -> null
        }
        when (t.button) {
            ButtonStyle.SOLID -> {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    val base = stateColor ?: if (ink.light) 0x1A000000 else 0x24FFFFFF
                    shader = LinearGradient(0f, cy - r, 0f, cy + r, WidgetPaint.lighten(base, 0.12f), WidgetPaint.darken(base, 0.10f), Shader.TileMode.CLAMP)
                    if (stateColor != null) setShadowLayer(r * 0.35f, 0f, r * 0.08f, WidgetPaint.alpha(stateColor, 0.55f))
                }
                c.drawCircle(cx, cy, r, fill)
                val glyph = if (stateColor != null) (if (WidgetPaint.luminance(stateColor) > 0.6) 0xFF111114.toInt() else 0xFFFFFFFF.toInt()) else ink.text
                WidgetPaint.power(c, cx, cy, r * 0.42f, glyph, r * 0.11f)
            }
            else -> {
                val glowing = t.button == ButtonStyle.GLOW
                // Inner disc, so the ring reads as a button and not a drawing.
                c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (stateColor != null) WidgetPaint.alpha(stateColor, 0.14f) else ink.faint
                })
                if (glowing && stateColor != null) {
                    c.drawCircle(cx, cy, r * 1.16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = r * 0.04f
                        color = WidgetPaint.alpha(stateColor, 0.35f)
                    })
                }
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = r * 0.11f
                    color = stateColor ?: (if (ink.light) 0x40000000 else 0x55FFFFFF)
                    strokeCap = Paint.Cap.ROUND
                    if (view.connecting) pathEffect = DashPathEffect(floatArrayOf(r * 0.30f, r * 0.18f), 0f)
                    if (stateColor != null) setShadowLayer(r * (if (glowing) 0.55f else 0.30f), 0f, 0f, WidgetPaint.alpha(stateColor, if (glowing) 0.95f else 0.65f))
                }
                c.drawCircle(cx, cy, r * 0.94f, ring)
                WidgetPaint.power(c, cx, cy, r * 0.40f, stateColor ?: ink.text, r * 0.10f)
            }
        }
    }

    /** The pill button inside [rect]: «اتصال», «در حال اتصال…», or «متصل» with a lamp. */
    private fun pillButton(c: Canvas, ctx: Context, t: WidgetTemplate, view: MethodView, f: Frame, ink: Ink, rect: RectF) {
        val color = when {
            view.connecting -> WidgetPaint.AMBER
            view.up -> t.accent
            else -> t.accent
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = rect.height() / 2f
        // On a card painted in the accent, the pill is white instead: an accent pill would vanish on it.
        val white = ink.onAccent && !view.connecting
        if (view.up) {
            // Connected: a quieter pill in the accent with a lamp -- the action now is "disconnect".
            val tone = if (white) 0xFFFFFFFF.toInt() else color
            fill.color = WidgetPaint.alpha(tone, if (white) 0.20f else 0.22f)
            c.drawPath(WidgetPaint.round(rect, radius), fill)
            c.drawPath(WidgetPaint.round(rect, radius), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = f.dp(1.2f); this.color = WidgetPaint.alpha(tone, 0.85f)
            })
        } else if (white) {
            fill.color = 0xF7FFFFFF.toInt()
            fill.setShadowLayer(f.dp(6f), 0f, f.dp(2f), 0x33000000)
            c.drawPath(WidgetPaint.round(rect, radius), fill)
        } else {
            fill.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                WidgetPaint.lighten(color, 0.10f), WidgetPaint.darken(color, 0.08f), Shader.TileMode.CLAMP)
            fill.setShadowLayer(f.dp(6f), 0f, f.dp(2f), WidgetPaint.alpha(color, 0.45f))
            c.drawPath(WidgetPaint.round(rect, radius), fill)
        }
        val label = when {
            view.up -> tr("متصل", "Connected")
            view.connecting -> tr("در حال اتصال…", "Connecting…")
            else -> tr("اتصال", "Connect")
        }
        val onFill = when {
            white && view.up -> 0xFFFFFFFF.toInt()
            white -> WidgetPaint.darken(color, 0.25f)
            view.up -> if (ink.light) WidgetPaint.darken(color, 0.35f) else WidgetPaint.lighten(color, 0.55f)
            WidgetPaint.luminance(color) > 0.6 -> 0xFF111114.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        val paint = WidgetArt.paint(ctx, min(f.sp(13.5f), rect.height() * 0.40f), onFill, WidgetArt.Weight.BOLD)
        val dot = if (view.up) rect.height() * 0.24f else 0f
        val line = WidgetArt.line(label, paint, rect.width() - dot * 2 - f.dp(16f), f.rtl)
        val total = line.width + (if (dot > 0) dot + f.dp(7f) else 0f)
        val left = rect.centerX() - total / 2f
        if (dot > 0) {
            // The lamp goes at the start of the word: on the right in Persian.
            val dotX = if (f.rtl) left + total - dot / 2f else left + dot / 2f
            WidgetPaint.dot(c, dotX, rect.centerY(), dot, WidgetPaint.GREEN, glow = true)
            val textX = if (f.rtl) left + total - dot - f.dp(7f) else left + dot + f.dp(7f)
            line.draw(c, textX, rect.centerY() - line.height / 2f, if (f.rtl) WidgetArt.Align.RIGHT else WidgetArt.Align.LEFT)
        } else {
            line.draw(c, rect.centerX(), rect.centerY() - line.height / 2f, WidgetArt.Align.CENTER)
        }
    }

    /**
     * Header: the method's tile and name, or the brand over the method; returns its height.
     * [subtitle] off leaves the method out from under the brand, for a layout that shows it elsewhere.
     */
    private fun header(c: Canvas, ctx: Context, t: WidgetTemplate, app: HomeApp?, view: MethodView, f: Frame, ink: Ink,
                       xStart: Float, top: Float, maxW: Float, iconSize: Float, titleSp: Float, statusDot: Boolean,
                       subtitle: Boolean = true): Float {
        val brand = t.shows(Info.BRAND)
        val method = t.shows(Info.METHOD) || !brand
        val iconBox = f.rect(xStart, top, iconSize, iconSize)
        if (brand) WidgetArt.drawAppIcon(c, ctx, iconBox) else icon(c, ctx, app, iconBox, f.rtl)
        val textX = xStart + iconSize + f.dp(8f)
        val dotRoom = if (statusDot) f.dp(14f) else 0f
        val textW = maxW - iconSize - f.dp(8f) - dotRoom
        val title = WidgetArt.fit(if (brand) "MLMVPN" else methodName(app, t),
            WidgetArt.paint(ctx, f.sp(titleSp), ink.text, WidgetArt.Weight.BOLD), textW, f.rtl)
        val sub = if (brand && method && subtitle) WidgetArt.fit(methodName(app, t),
            WidgetArt.paint(ctx, f.sp(titleSp * 0.74f), ink.soft, WidgetArt.Weight.MEDIUM), textW, f.rtl) else null
        val block = title.height + (sub?.height ?: 0f)
        var y = top + (iconSize - block) / 2f
        title.draw(c, f.x(textX), y, f.start)
        y += title.height
        sub?.draw(c, f.x(textX), y, f.start)
        if (statusDot) {
            val d = f.dp(8f)
            WidgetPaint.dot(c, f.x(xStart + maxW - d / 2f), top + iconSize / 2f, d, statusColor(view, ink), glow = view.up)
        }
        return iconSize
    }

    // ---------------------------------------------------------------------------------------------
    // Layouts
    // ---------------------------------------------------------------------------------------------

    private fun small(c: Canvas, ctx: Context, t: WidgetTemplate, app: HomeApp?, view: MethodView, f: Frame, ink: Ink) {
        val pad = f.dp(13f)
        val innerW = f.w - pad * 2
        var y = pad
        header(c, ctx, t, app, view, f, ink, pad, y, innerW, f.dp(30f) * f.fs.coerceIn(0.9f, 1.15f), 14f, t.shows(Info.STATUS))
        y += f.dp(30f) * f.fs.coerceIn(0.9f, 1.15f) + f.dp(7f)
        if (t.shows(Info.COUNTRY) && y + f.dp(24f) < f.h * SMALL_TOP + f.dp(14f)) {
            countryChip(c, ctx, t, view, f, ink, pad, y, innerW, f.dp(24f))
        }
        // The button area: the bottom part, the toggle zone.
        val zoneTop = f.h * SMALL_TOP
        val footer = if (t.shows(Info.SPEED) && view.up) f.dp(18f) else if (t.button != ButtonStyle.PILL) f.dp(16f) else 0f
        val areaTop = zoneTop + f.dp(4f)
        val areaBottom = f.h - pad - footer
        if (t.button == ButtonStyle.PILL) {
            val ph = min(f.dp(38f), (areaBottom - areaTop) * 0.8f)
            val rect = RectF(pad, areaTop + (areaBottom - areaTop - ph) / 2f, f.w - pad, areaTop + (areaBottom - areaTop + ph) / 2f)
            pillButton(c, ctx, t, view, f, ink, rect)
        } else {
            val r = min((areaBottom - areaTop) / 2f, innerW * 0.26f)
            roundButton(c, t, view, ink, f.w / 2f, (areaTop + areaBottom) / 2f, r)
        }
        if (footer > 0f) {
            if (t.shows(Info.SPEED) && view.up) {
                speeds(c, ctx, view, f, ink, pad, f.h - pad - f.dp(15f), innerW, 10.5f)
            } else {
                val captions = when {
                    view.up -> withTime(tr("متصل", "Connected"), t, view)
                    view.connecting -> listOf(tr("در حال اتصال…", "Connecting…"))
                    view.other != null -> listOf(statusText(view), tr("برای اتصال بزنید", "Tap to connect"))
                    else -> listOf(tr("برای اتصال بزنید", "Tap to connect"))
                }
                WidgetArt.firstFitting(captions, WidgetArt.paint(ctx, f.sp(10.5f), ink.soft), innerW, f.rtl)
                    .draw(c, f.w / 2f, f.h - pad - f.dp(14f), WidgetArt.Align.CENTER)
            }
        }
    }

    /** «جمع‌وجور»: the tile, the name and a switch -- the least a widget can be. */
    private fun smallCompact(c: Canvas, ctx: Context, t: WidgetTemplate, app: HomeApp?, view: MethodView, f: Frame, ink: Ink) {
        val pad = f.dp(13f)
        val innerW = f.w - pad * 2
        val icon = f.dp(34f) * f.fs.coerceIn(0.9f, 1.15f)
        icon(c, ctx, app, f.rect(pad, pad, icon, icon), f.rtl)
        if (t.shows(Info.STATUS)) WidgetPaint.dot(c, f.x(pad + innerW - f.dp(4f)), pad + f.dp(6f), f.dp(8f), statusColor(view, ink), glow = view.up)
        var y = pad + icon + f.dp(8f)
        val name = WidgetArt.line(methodName(app, t), WidgetArt.paint(ctx, f.sp(14f), ink.text, WidgetArt.Weight.BOLD), innerW, f.rtl)
        name.draw(c, f.x(pad), y, f.start)
        y += name.height + f.dp(2f)
        if (t.shows(Info.COUNTRY)) {
            val code = countryOf(t, view)
            WidgetArt.line(WidgetFormat.flag(code) + " " + countryLabel(code), WidgetArt.paint(ctx, f.sp(11f), ink.soft), innerW, f.rtl)
                .draw(c, f.x(pad), y, f.start)
        }
        // The switch, bottom end: ON in the accent, OFF grey, amber while connecting. As wide as its
        // longer word needs («خاموش» did not fit a fixed track).
        val sh = f.dp(30f)
        val knob = sh - f.dp(6f)
        val on = view.up || view.connecting
        val wordPaint = WidgetArt.paint(ctx, f.sp(10f), if (on) 0xFFFFFFFF.toInt() else ink.soft, WidgetArt.Weight.BOLD)
        val onWord = tr("روشن", "ON")
        val offWord = tr("خاموش", "OFF")
        val wordRoom = max(android.text.Layout.getDesiredWidth(onWord, wordPaint), android.text.Layout.getDesiredWidth(offWord, wordPaint))
        val sw = max(f.dp(58f), f.dp(3f) + knob + f.dp(7f) + wordRoom + f.dp(9f)).coerceAtMost(innerW)
        val rect = f.rect(pad + innerW - sw, f.h - pad - sh, sw, sh)
        val color = if (view.connecting) WidgetPaint.AMBER else ink.live
        c.drawPath(WidgetPaint.round(rect, sh / 2f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (on) color else if (ink.light) 0x29000000 else 0x33FFFFFF
        })
        // ON moves the knob toward the end, as the app's own switches do: to the left in Persian.
        val knobAtLeft = on == f.rtl
        val kx = if (knobAtLeft) rect.left + f.dp(3f) + knob / 2f else rect.right - f.dp(3f) - knob / 2f
        c.drawCircle(kx, rect.centerY(), knob / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt(); setShadowLayer(f.dp(2f), 0f, f.dp(1f), 0x40000000)
        })
        // The word in the part of the track the knob is not in.
        val free = sw - knob - f.dp(3f)
        val wordColor = if (on && WidgetPaint.luminance(color) > 0.6) 0xFF111114.toInt() else wordPaint.color
        val word = WidgetArt.line(if (on) onWord else offWord, WidgetArt.paint(ctx, f.sp(10f), wordColor, WidgetArt.Weight.BOLD), free, f.rtl)
        val wx = if (knobAtLeft) rect.right - free / 2f else rect.left + free / 2f
        word.draw(c, wx, rect.centerY() - word.height / 2f, WidgetArt.Align.CENTER)
    }

    /** One row of a column measured before it is drawn: its height, the gap under it, and how to draw it at a top. */
    private class Row(val height: Float, val gapAfter: Float, val chart: Boolean = false, val draw: (top: Float, height: Float) -> Unit)

    /**
     * Draws [rows] down the space between [top] and [bottom], centred in it: a launcher's 4x2 is
     * often much taller than the 150dp a medium design is drawn for, and a column stuck to the top
     * left the lower part of the card empty. Rows that do not fit are left out; spare height first
     * goes to a chart, if there is one, so a tall widget gets a taller chart.
     */
    private fun column(rows: List<Row>, top: Float, bottom: Float, chartGrowth: Float) {
        val avail = bottom - top
        val fitting = ArrayList<Row>()
        var total = 0f
        for (r in rows) {
            val need = (fitting.lastOrNull()?.gapAfter ?: 0f) + r.height
            if (total + need > avail) continue
            total += need
            fitting += r
        }
        val grow = if (fitting.any { it.chart }) ((avail - total) * 0.6f).coerceIn(0f, chartGrowth) else 0f
        total += grow
        var y = top + ((avail - total) / 2f).coerceAtLeast(0f)
        fitting.forEachIndexed { i, r ->
            if (i > 0) y += fitting[i - 1].gapAfter
            val h = r.height + if (r.chart) grow else 0f
            r.draw(y, h)
            y += h
        }
    }

    /** The height [speeds] takes at [size]. */
    private fun speedsHeight(ctx: Context, f: Frame, size: Float): Float {
        val metrics = WidgetArt.paint(ctx, f.sp(size), 0, WidgetArt.Weight.MEDIUM).fontMetrics
        return max(metrics.descent - metrics.ascent, f.sp(size) * 1.05f)
    }

    private fun medium(c: Canvas, ctx: Context, t: WidgetTemplate, app: HomeApp?, view: MethodView, f: Frame, ink: Ink) {
        val pad = f.dp(14f)
        val half = f.w / 2f
        // Information on the start half, the button on the end half -- see [zones].
        val infoW = half - pad * 1.4f
        val iconSize = f.dp(32f) * f.fs.coerceIn(0.9f, 1.15f)
        val rows = ArrayList<Row>()
        rows += Row(iconSize, f.dp(8f)) { y, _ -> header(c, ctx, t, app, view, f, ink, pad, y, infoW, iconSize, 15f, false) }
        if (t.shows(Info.STATUS) || t.shows(Info.TIME)) {
            val d = f.dp(7f)
            val line = WidgetArt.firstFitting(withTime(statusText(view), t, view),
                WidgetArt.paint(ctx, f.sp(11.5f), ink.text, WidgetArt.Weight.MEDIUM), infoW - d - f.dp(6f), f.rtl)
            rows += Row(line.height, f.dp(6f)) { y, _ ->
                WidgetPaint.dot(c, f.x(pad + d / 2f), y + line.height / 2f, d, statusColor(view, ink), glow = view.up)
                line.draw(c, f.x(pad + d + f.dp(6f)), y, f.start)
            }
        }
        if (t.shows(Info.COUNTRY)) {
            rows += Row(f.dp(24f), f.dp(6f)) { y, _ -> countryChip(c, ctx, t, view, f, ink, pad, y, infoW, f.dp(24f)) }
        }
        if (t.shows(Info.CHART) && view.up) {
            rows += Row(f.dp(20f), f.dp(4f), chart = true) { y, h ->
                WidgetPaint.chart(c, f.rect(pad, y, infoW, h), view.traffic.down, view.traffic.up, ink.down, ink.up, f.dp(1.6f))
            }
        }
        if (t.shows(Info.SPEED)) {
            rows += Row(speedsHeight(ctx, f, 11f), f.dp(4f)) { y, _ -> speeds(c, ctx, view, f, ink, pad, y, infoW, 11f) }
        }
        if (t.shows(Info.USAGE) && view.up) {
            val line = WidgetArt.line(tr("مصرف: ", "Used: ") + WidgetFormat.bytes(view.traffic.rx + view.traffic.tx),
                WidgetArt.paint(ctx, f.sp(10.5f), ink.soft), infoW, f.rtl)
            rows += Row(line.height, 0f) { y, _ -> line.draw(c, f.x(pad), y, f.start) }
        }
        column(rows, pad, f.h - pad, chartGrowth = f.dp(40f))

        // The button, centred in the end half.
        val bx = f.x(half + half / 2f)
        if (t.button == ButtonStyle.PILL) {
            val pw = half - pad * 2
            val ph = min(f.dp(42f), f.h * 0.34f)
            val rect = RectF(bx - pw / 2f, f.h / 2f - ph / 2f, bx + pw / 2f, f.h / 2f + ph / 2f)
            pillButton(c, ctx, t, view, f, ink, rect)
            if (!view.up && !view.connecting) {
                WidgetArt.line(tr("یک لمس تا اتصال", "One tap to connect"), WidgetArt.paint(ctx, f.sp(10f), ink.soft), pw, f.rtl)
                    .draw(c, bx, rect.bottom + f.dp(6f), WidgetArt.Align.CENTER)
            }
        } else {
            val r = min(f.h / 2f - pad - f.dp(8f), half * 0.30f)
            val cy = f.h / 2f - f.dp(6f)
            roundButton(c, t, view, ink, bx, cy, r)
            val caption = when {
                view.up -> tr("برای قطع بزنید", "Tap to disconnect")
                view.connecting -> tr("در حال اتصال…", "Connecting…")
                else -> tr("برای اتصال بزنید", "Tap to connect")
            }
            WidgetArt.line(caption, WidgetArt.paint(ctx, f.sp(10f), ink.soft), half - pad, f.rtl)
                .draw(c, bx, cy + r + f.dp(7f), WidgetArt.Align.CENTER)
        }
    }

    private fun large(c: Canvas, ctx: Context, t: WidgetTemplate, app: HomeApp?, view: MethodView, f: Frame, ink: Ink) {
        val pad = f.dp(16f)
        val innerW = f.w - pad * 2
        val topBand = f.h * LARGE_TOP
        val bottomBandTop = f.h * (1f - LARGE_BOTTOM)

        // Header: brand (or method) at the start, a status pill at the end. With the brand there and
        // the method in a chip of its own below, the method is not said a second time under the brand.
        val iconSize = f.dp(30f) * f.fs.coerceIn(0.9f, 1.15f)
        val rowTop = pad + iconSize + f.dp(10f)
        val chipH = f.dp(30f)
        val chipRow = rowTop + chipH <= topBand + f.dp(6f)
        val methodChip = chipRow && t.shows(Info.BRAND) && t.shows(Info.METHOD)
        val pillW = if (t.shows(Info.STATUS)) min(innerW * 0.46f, f.dp(150f)) else 0f
        header(c, ctx, t, app, view, f, ink, pad, pad, innerW - pillW - f.dp(8f), iconSize, 16f, false, subtitle = !methodChip)
        if (t.shows(Info.STATUS)) {
            val ph = f.dp(28f)
            val rect = f.rect(pad + innerW - pillW, pad + (iconSize - ph) / 2f, pillW, ph)
            c.drawPath(WidgetPaint.round(rect, ph / 2f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.chip })
            val d = f.dp(7f)
            val texts = withTime(statusText(view), t, view, always = true) +
                (if (view.other != null) listOf(tr("قطع", "Disconnected")) else emptyList())
            val line = WidgetArt.firstFitting(texts, WidgetArt.paint(ctx, f.sp(11f), ink.text, WidgetArt.Weight.MEDIUM), pillW - d - f.dp(20f), f.rtl)
            val total = d + f.dp(6f) + line.width
            // Centred in the pill, the lamp at the start of the words.
            val startEdge = if (f.rtl) rect.centerX() + total / 2f else rect.centerX() - total / 2f
            val dotX = if (f.rtl) startEdge - d / 2f else startEdge + d / 2f
            WidgetPaint.dot(c, dotX, rect.centerY(), d, statusColor(view, ink), glow = view.up)
            val textX = if (f.rtl) startEdge - d - f.dp(6f) else startEdge + d + f.dp(6f)
            line.draw(c, textX, rect.centerY() - line.height / 2f, f.start)
        }

        // Second row: the method chip and the country chip.
        if (chipRow) {
            var x = pad
            if (methodChip) {
                // With the brand in the header, the method gets a chip of its own, with its tile.
                val name = WidgetArt.fit(methodName(app, t), WidgetArt.paint(ctx, f.sp(12f), ink.text, WidgetArt.Weight.BOLD), innerW * 0.40f, f.rtl)
                val tile = chipH - f.dp(8f)
                val w = f.dp(4f) + tile + f.dp(7f) + name.width + f.dp(12f)
                val r = f.rect(x, rowTop, w, chipH)
                c.drawPath(WidgetPaint.round(r, chipH / 2f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.chip })
                icon(c, ctx, app, f.rect(x + f.dp(4f), rowTop + f.dp(4f), tile, tile), f.rtl)
                name.draw(c, f.x(x + f.dp(4f) + tile + f.dp(7f)), rowTop + (chipH - name.height) / 2f, f.start)
                x += w + f.dp(8f)
            }
            if (t.shows(Info.COUNTRY)) countryChip(c, ctx, t, view, f, ink, x, rowTop, innerW - (x - pad), chipH)
        }

        // The button, in the middle band.
        val midTop = topBand
        val midBottom = bottomBandTop
        val cy = (midTop + midBottom) / 2f
        if (t.button == ButtonStyle.PILL) {
            val pw = min(innerW * 0.70f, f.dp(240f))
            val ph = min(f.dp(54f), (midBottom - midTop) * 0.55f)
            pillButton(c, ctx, t, view, f, ink, RectF(f.w / 2f - pw / 2f, cy - ph / 2f, f.w / 2f + pw / 2f, cy + ph / 2f))
        } else {
            val r = min((midBottom - midTop) / 2f - f.dp(6f), innerW * 0.22f)
            roundButton(c, t, view, ink, f.w / 2f, cy, r)
        }

        // Bottom band: the chart over the numbers.
        var y = bottomBandTop + f.dp(4f)
        val statsH = f.dp(34f)
        if (t.shows(Info.CHART)) {
            val chartH = (f.h - pad - statsH - y - f.dp(4f)).coerceAtLeast(0f)
            if (chartH >= f.dp(14f)) {
                WidgetPaint.chart(c, RectF(pad, y, f.w - pad, y + chartH),
                    view.traffic.down, view.traffic.up, ink.down, ink.up, f.dp(1.8f))
                y += chartH + f.dp(4f)
            }
        }
        val cells = buildList {
            if (t.shows(Info.SPEED)) {
                add(Triple(tr("دانلود", "Download"), WidgetFormat.rate(view.traffic.rxRate), 1))
                add(Triple(tr("آپلود", "Upload"), WidgetFormat.rate(view.traffic.txRate), 2))
            }
            if (t.shows(Info.USAGE)) add(Triple(tr("مصرف نشست", "Session"), WidgetFormat.bytes(view.traffic.rx + view.traffic.tx), 0))
            if (t.shows(Info.TIME)) add(Triple(tr("مدت اتصال", "Connected for"),
                if (view.up && view.since > 0) WidgetFormat.duration(System.currentTimeMillis() - view.since) else "—", 0))
        }.take(4)
        if (cells.isNotEmpty()) {
            val cellW = innerW / cells.size
            val top = f.h - pad - statsH
            cells.forEachIndexed { i, (label, value, arrow) ->
                val cx = f.x(pad + cellW * i + cellW / 2f)
                val v = WidgetArt.line(value, WidgetArt.paint(ctx, f.sp(13f), ink.text, WidgetArt.Weight.BOLD), cellW - f.dp(6f), f.rtl)
                val l = WidgetArt.line(label, WidgetArt.paint(ctx, f.sp(10f), ink.soft), cellW - f.dp(6f), f.rtl)
                if (arrow != 0) {
                    // The arrow before the word, as in the speed rows: on its right in Persian.
                    val a = f.sp(10f)
                    val total = a + f.dp(3f) + l.width
                    val left = cx - total / 2f
                    val arrowLeft = if (f.rtl) left + l.width + f.dp(3f) else left
                    val labelLeft = if (f.rtl) left else left + a + f.dp(3f)
                    WidgetPaint.arrow(c, RectF(arrowLeft, top + (l.height - a) / 2f, arrowLeft + a, top + (l.height + a) / 2f), arrow == 1,
                        if (arrow == 1) ink.down else ink.up)
                    l.draw(c, labelLeft, top, WidgetArt.Align.LEFT)
                } else {
                    l.draw(c, cx, top, WidgetArt.Align.CENTER)
                }
                v.draw(c, cx, top + l.height + f.dp(2f), WidgetArt.Align.CENTER)
            }
        }
    }
}
