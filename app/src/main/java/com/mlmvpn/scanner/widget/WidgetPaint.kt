package com.mlmvpn.scanner.widget

import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * The small pieces every widget design is drawn from: colours, the lamp, the power glyph, the
 * arrows, the live chart. Shared by the method widgets and the studio's designs so the two families
 * speak the same visual language.
 */
object WidgetPaint {

    // The lamp colours: iOS's system green and orange, as the board's lamp uses.
    const val GREEN = 0xFF34C759.toInt()
    const val AMBER = 0xFFFF9F0A.toInt()
    const val GRAY = 0xFF8E8E93.toInt()
    const val RED = 0xFFFF453A.toInt()

    fun alpha(color: Int, a: Float): Int = ColorUtils.setAlphaComponent(color, (a.coerceIn(0f, 1f) * 255).toInt())

    /** [color]'s own alpha multiplied by [a]. */
    fun fade(color: Int, a: Float): Int = alpha(color, (AColor.alpha(color) / 255f) * a)

    fun lighten(color: Int, f: Float): Int = ColorUtils.blendARGB(color, AColor.WHITE, f.coerceIn(0f, 1f)).let { keepAlpha(it, color) }

    fun darken(color: Int, f: Float): Int = ColorUtils.blendARGB(color, AColor.BLACK, f.coerceIn(0f, 1f)).let { keepAlpha(it, color) }

    private fun keepAlpha(c: Int, src: Int) = ColorUtils.setAlphaComponent(c, AColor.alpha(src))

    fun hueShift(color: Int, degrees: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[0] = ((hsl[0] + degrees) % 360f + 360f) % 360f
        return keepAlpha(ColorUtils.HSLToColor(hsl), color)
    }

    fun luminance(color: Int): Double = ColorUtils.calculateLuminance(color or 0xFF000000.toInt())

    /** True when text on [color] should be dark: a pale card, opaque enough to read on. */
    fun isLight(color: Int, opacity: Int): Boolean = luminance(color) > 0.55 && opacity >= 40

    /**
     * The board's running lamp, at [cx],[cy]: a green dot in a dark ring so it shows on any tile
     * colour (see AppIcon.RunningLamp). Amber while connecting.
     */
    fun lamp(canvas: Canvas, cx: Float, cy: Float, d: Float, connecting: Boolean) {
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0B0B0C.toInt()
            setShadowLayer(d * 0.2f, 0f, d * 0.08f, 0x66000000)
        }
        canvas.drawCircle(cx, cy, d / 2f, ring)
        val inner = d / 2f - max(1.5f, d * 0.14f)
        val top = if (connecting) 0xFFFFC15A.toInt() else 0xFF5AF08A.toInt()
        val bottom = if (connecting) 0xFFE58A00.toInt() else 0xFF2FBF5C.toInt()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, cy - inner, 0f, cy + inner, top, bottom, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, inner, dot)
    }

    /** A plain status dot. */
    fun dot(canvas: Canvas, cx: Float, cy: Float, d: Float, color: Int, glow: Boolean = false) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        if (glow) p.setShadowLayer(d * 0.9f, 0f, 0f, alpha(color, 0.75f))
        canvas.drawCircle(cx, cy, d / 2f, p)
    }

    /** The power glyph: an open ring and a stem, centred on [cx],[cy], [r] the ring's radius. */
    fun power(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, stroke: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, 270f + 38f, 284f, false, p)
        canvas.drawLine(cx, cy - r * 1.12f, cx, cy - r * 0.18f, p)
    }

    /** A small arrow, down or up, inside [box]. */
    fun arrow(canvas: Canvas, box: RectF, down: Boolean, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = box.width() * 0.16f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        val cx = box.centerX()
        val top = box.top + box.height() * 0.12f
        val bottom = box.bottom - box.height() * 0.12f
        val wing = box.width() * 0.34f
        val path = Path()
        if (down) {
            path.moveTo(cx, top); path.lineTo(cx, bottom)
            path.moveTo(cx - wing, bottom - wing); path.lineTo(cx, bottom); path.lineTo(cx + wing, bottom - wing)
        } else {
            path.moveTo(cx, bottom); path.lineTo(cx, top)
            path.moveTo(cx - wing, top + wing); path.lineTo(cx, top); path.lineTo(cx + wing, top + wing)
        }
        canvas.drawPath(path, p)
    }

    /** A small chevron pointing down: "this can be changed". */
    fun chevronDown(canvas: Canvas, cx: Float, cy: Float, w: Float, color: Int, stroke: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        val path = Path().apply {
            moveTo(cx - w / 2f, cy - w / 4f)
            lineTo(cx, cy + w / 4f)
            lineTo(cx + w / 2f, cy - w / 4f)
        }
        canvas.drawPath(path, p)
    }

    /**
     * The live chart: download as a filled line in [downColor], upload as a thinner line in
     * [upColor], both against the larger of the two so they share a scale. With no samples yet it
     * draws a calm flat line rather than nothing, so the space does not look broken.
     */
    fun chart(canvas: Canvas, box: RectF, allDown: LongArray, allUp: LongArray, downColor: Int, upColor: Int, stroke: Float) {
        // The live history keeps one sample more than the chart spans, for the in-app chart to
        // slide out past its left edge; a still picture draws only the minute that fits.
        val down = allDown.lastSamples(WidgetLive.HISTORY)
        val up = allUp.lastSamples(WidgetLive.HISTORY)
        val n = max(down.size, up.size)
        if (n < 2) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; color = fade(downColor, 0.35f); strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(box.left, box.bottom - stroke, box.right, box.bottom - stroke, p)
            return
        }
        var peak = 1L
        for (v in down) peak = max(peak, v)
        for (v in up) peak = max(peak, v)
        // Headroom, so the line does not ride the top edge.
        val top = peak * 1.18f

        fun pathOf(values: LongArray): Path {
            val path = Path()
            val count = values.size
            if (count == 0) return path
            // Right-aligned: the newest sample is always at the right edge, as on every live meter.
            val step = box.width() / (WidgetLive.HISTORY - 1).coerceAtLeast(1)
            val startX = box.right - step * (count - 1)
            var px = 0f
            var py = 0f
            for (i in 0 until count) {
                val x = startX + step * i
                val y = box.bottom - (values[i] / top) * box.height()
                if (i == 0) path.moveTo(x, y) else {
                    // A gentle curve between samples instead of a jagged polyline.
                    val mx = (px + x) / 2f
                    path.cubicTo(mx, py, mx, y, x, y)
                }
                px = x; py = y
            }
            return path
        }

        val downPath = pathOf(down)
        if (down.size >= 2) {
            val area = Path(downPath)
            val step = box.width() / (WidgetLive.HISTORY - 1).coerceAtLeast(1)
            val startX = box.right - step * (down.size - 1)
            area.lineTo(box.right, box.bottom)
            area.lineTo(startX, box.bottom)
            area.close()
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, box.top, 0f, box.bottom, fade(downColor, 0.40f), fade(downColor, 0f), Shader.TileMode.CLAMP)
            }
            canvas.drawPath(area, fill)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        if (up.size >= 2) {
            line.color = upColor
            line.strokeWidth = stroke * 0.8f
            canvas.drawPath(pathOf(up), line)
        }
        if (down.size >= 2) {
            line.color = downColor
            line.strokeWidth = stroke
            line.setShadowLayer(stroke * 2.2f, 0f, 0f, fade(downColor, 0.55f))
            canvas.drawPath(downPath, line)
        }
    }

    /** A rounded rectangle path, its radius clamped to what the rectangle can carry. */
    fun round(rect: RectF, radius: Float): Path = Path().apply {
        val r = min(radius, min(rect.width(), rect.height()) / 2f)
        addRoundRect(rect, r, r, Path.Direction.CW)
    }

    private fun LongArray.lastSamples(count: Int): LongArray = if (size > count) copyOfRange(size - count, size) else this
}
