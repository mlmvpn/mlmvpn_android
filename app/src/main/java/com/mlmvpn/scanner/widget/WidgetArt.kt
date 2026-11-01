package com.mlmvpn.scanner.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.home.HomeApp
import java.util.concurrent.ConcurrentHashMap

/**
 * Drawing for widgets, on a plain [Canvas]: a widget lives in the launcher's process, which can only
 * be handed pictures, so everything a widget shows is painted here into a bitmap first.
 *
 * The tile is drawn the way the in-app board draws it (`AppIcon.TileFace`): finished artwork as it
 * is, scaled out of its own 90% inset; a glyph on the tile's tint, top lighter and bottom darker. The
 * same artwork resources and the same Material glyphs are used, so a widget and the tile it stands
 * for are the same picture rather than a lookalike.
 */
object WidgetArt {

    enum class Weight(val res: Int) {
        REGULAR(R.font.iransansx_regular),
        MEDIUM(R.font.iransansx_medium),
        DEMIBOLD(R.font.iransansx_demibold),
        BOLD(R.font.iransansx_bold),
    }

    private val fonts = ConcurrentHashMap<Weight, Typeface>()

    /** The app's own typeface, so a widget reads like the app it opens. Falls back to the system one. */
    fun font(context: Context, weight: Weight): Typeface = fonts[weight] ?: run {
        val face = runCatching { ResourcesCompat.getFont(context, weight.res) }.getOrNull()
            ?: Typeface.create(Typeface.DEFAULT, if (weight == Weight.REGULAR) Typeface.NORMAL else Typeface.BOLD)
        fonts[weight] = face
        face
    }

    /** The squircle the board's tiles use: 26% corners (see AppIcon's note on why not 20%). */
    fun squircle(rect: RectF): Path = Path().apply {
        val r = minOf(rect.width(), rect.height()) * 0.26f
        addRoundRect(rect, r, r, Path.Direction.CW)
    }

    /**
     * [app]'s tile inside [dst] (a square), as the board draws it.
     *
     * [shadow] is the board's soft drop shadow; off for the miniatures inside a larger design, where
     * a shadow at that size reads as a smudge rather than as depth.
     */
    fun drawTile(canvas: Canvas, context: Context, app: HomeApp, dst: RectF, shadow: Boolean = true, rtl: Boolean = false) {
        val size = minOf(dst.width(), dst.height())
        val box = RectF(dst.centerX() - size / 2f, dst.centerY() - size / 2f, dst.centerX() + size / 2f, dst.centerY() + size / 2f)
        if (shadow) {
            // The shadow is cast by an opaque squircle drawn first and then covered by the tile. It is
            // inset well inside the tile so no artwork with rounder corners lets its edge show.
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF000000.toInt()
                setShadowLayer(size * 0.08f, 0f, size * 0.035f, 0x59000000)
            }
            canvas.drawPath(squircle(RectF(box).apply { inset(size * 0.06f, size * 0.06f) }), p)
        }
        val art = app.imageRes
        if (art != null) {
            val d = drawable(context, art) ?: return drawGlyphTile(canvas, context, app, box, rtl)
            // The artwork insets its own squircle to 90% of its viewport, like a shipped iOS icon;
            // drawn 100/90 larger, it lands exactly on the tile's edge (AppIcon does the same).
            val grow = size * (100f / 90f - 1f) / 2f
            d.setBounds(
                (box.left - grow).toInt(), (box.top - grow).toInt(),
                (box.right + grow).toInt(), (box.bottom + grow).toInt(),
            )
            (d as? BitmapDrawable)?.paint?.isFilterBitmap = true
            d.draw(canvas)
            return
        }
        drawGlyphTile(canvas, context, app, box, rtl)
    }

    private fun drawGlyphTile(canvas: Canvas, context: Context, app: HomeApp, box: RectF, rtl: Boolean) {
        val top = lerp(app.tint, Color.White, 0.18f).toArgb()
        val bottom = lerp(app.tint, Color.Black, 0.14f).toArgb()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, box.top, 0f, box.bottom, top, bottom, Shader.TileMode.CLAMP)
        }
        canvas.drawPath(squircle(box), fill)
        val glyph = box.width() * app.glyphScale
        val g = RectF(box.centerX() - glyph / 2f, box.centerY() - glyph / 2f, box.centerX() + glyph / 2f, box.centerY() + glyph / 2f)
        val res = app.glyphRes
        if (res != null) {
            drawable(context, res)?.let { d ->
                d.setBounds(g.left.toInt(), g.top.toInt(), g.right.toInt(), g.bottom.toInt())
                d.draw(canvas)
                return
            }
        }
        drawVector(canvas, app.icon, g, 0xFFFFFFFF.toInt(), mirror = rtl && app.icon.autoMirror)
    }

    /** A drawable resource, fresh (mutated) so bounds and tints set here never leak to other users. */
    fun drawable(context: Context, id: Int): Drawable? =
        runCatching { ContextCompat.getDrawable(context, id)?.mutate() }.getOrNull()

    /** The app's own launcher icon -- the brand mark on the studio's designs. */
    fun drawAppIcon(canvas: Canvas, context: Context, dst: RectF) {
        val d = drawable(context, R.mipmap.ic_launcher) ?: return
        d.setBounds(dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt())
        (d as? BitmapDrawable)?.paint?.isFilterBitmap = true
        d.draw(canvas)
    }

    /**
     * A Material [ImageVector] (the board's fallback glyphs) onto a plain canvas, filled with [color].
     *
     * Compose would need a composition to turn one into a painter. The vector is only data -- groups
     * and path commands -- so it is walked here directly and every path drawn through Android's own
     * Path, which is exactly what the painter would have done.
     */
    fun drawVector(canvas: Canvas, vector: ImageVector, dst: RectF, color: Int, mirror: Boolean = false) {
        if (vector.viewportWidth <= 0f || vector.viewportHeight <= 0f) return
        canvas.save()
        canvas.translate(dst.left, dst.top)
        canvas.scale(dst.width() / vector.viewportWidth, dst.height() / vector.viewportHeight)
        if (mirror) {
            canvas.translate(vector.viewportWidth, 0f)
            canvas.scale(-1f, 1f)
        }
        drawGroup(canvas, vector.root, color)
        canvas.restore()
    }

    private fun drawGroup(canvas: Canvas, group: VectorGroup, color: Int) {
        canvas.save()
        canvas.translate(group.translationX + group.pivotX, group.translationY + group.pivotY)
        if (group.rotation != 0f) canvas.rotate(group.rotation)
        if (group.scaleX != 1f || group.scaleY != 1f) canvas.scale(group.scaleX, group.scaleY)
        canvas.translate(-group.pivotX, -group.pivotY)
        if (group.clipPathData.isNotEmpty()) {
            canvas.clipPath(PathParser().addPathNodes(group.clipPathData).toPath().asAndroidPath())
        }
        for (i in 0 until group.size) {
            when (val node = group[i]) {
                is VectorGroup -> drawGroup(canvas, node, color)
                is VectorPath -> drawPath(canvas, node, color)
            }
        }
        canvas.restore()
    }

    private fun drawPath(canvas: Canvas, node: VectorPath, color: Int) {
        val path = PathParser().addPathNodes(node.pathData).toPath().asAndroidPath()
        path.fillType = if (node.pathFillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val baseAlpha = (color ushr 24) and 0xFF
        if (node.fill != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = color
                alpha = (baseAlpha * node.fillAlpha * alphaOf(node.fill)).toInt().coerceIn(0, 255)
            }
            canvas.drawPath(path, paint)
        }
        if (node.stroke != null && node.strokeLineWidth > 0f) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.color = color
                alpha = (baseAlpha * node.strokeAlpha * alphaOf(node.stroke)).toInt().coerceIn(0, 255)
                strokeWidth = node.strokeLineWidth
                strokeMiter = node.strokeLineMiter
                strokeCap = when (node.strokeLineCap) {
                    StrokeCap.Round -> Paint.Cap.ROUND
                    StrokeCap.Square -> Paint.Cap.SQUARE
                    else -> Paint.Cap.BUTT
                }
                strokeJoin = when (node.strokeLineJoin) {
                    StrokeJoin.Round -> Paint.Join.ROUND
                    StrokeJoin.Bevel -> Paint.Join.BEVEL
                    else -> Paint.Join.MITER
                }
            }
            canvas.drawPath(path, paint)
        }
    }

    /** A solid brush's own alpha; any other brush counts as opaque (the glyphs only use solid ones). */
    private fun alphaOf(brush: androidx.compose.ui.graphics.Brush?): Float =
        (brush as? SolidColor)?.value?.alpha ?: 1f

    // ---------------------------------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------------------------------

    enum class Align { LEFT, CENTER, RIGHT }

    /**
     * One line of text, measured and laid out by the platform so Persian joins, mixed Persian and
     * Latin runs keep their order, and a line too long for [maxWidth] ends in an ellipsis instead of
     * running off the widget.
     */
    class Line(val layout: StaticLayout) {
        val height: Float get() = layout.height.toFloat()
        val width: Float get() = if (layout.lineCount == 0) 0f else layout.getLineRight(0) - layout.getLineLeft(0)

        /**
         * Draws the line with its [align] edge at [x] (left edge, centre or right edge) and its top
         * at [top]. Absolute, so the caller places text the same way in either language.
         */
        fun draw(canvas: Canvas, x: Float, top: Float, align: Align) {
            if (layout.lineCount == 0) return
            val left = layout.getLineLeft(0)
            val right = layout.getLineRight(0)
            val dx = when (align) {
                Align.LEFT -> x - left
                Align.RIGHT -> x - right
                Align.CENTER -> x - (left + right) / 2f
            }
            canvas.save()
            canvas.translate(dx, top)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    fun line(
        text: CharSequence,
        paint: TextPaint,
        maxWidth: Float,
        rtl: Boolean,
    ): Line {
        val width = maxWidth.coerceAtLeast(1f).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        return Line(layout)
    }

    /**
     * Like [line], but a text a little too long is first set a little smaller -- down to [minScale]
     * of [paint]'s size -- and only then cut: a name cut to «ضد فیلتر …» no longer says which one.
     */
    fun fit(text: CharSequence, paint: TextPaint, maxWidth: Float, rtl: Boolean, minScale: Float = 0.8f): Line {
        val natural = Layout.getDesiredWidth(text, paint)
        if (natural <= maxWidth || natural <= 0f) return line(text, paint, maxWidth, rtl)
        val smaller = TextPaint(paint).apply { textSize = paint.textSize * (maxWidth / natural * 0.98f).coerceAtLeast(minScale) }
        return line(text, smaller, maxWidth, rtl)
    }

    /**
     * The first of [texts] that fits [maxWidth] whole -- the longest wording first, a shorter one
     * after it -- and the last one, cut, when none does. «متصل، ۱۲ دقی…» says less than «متصل».
     */
    fun firstFitting(texts: List<CharSequence>, paint: TextPaint, maxWidth: Float, rtl: Boolean): Line {
        val pick = texts.firstOrNull { Layout.getDesiredWidth(it, paint) <= maxWidth } ?: texts.last()
        return line(pick, paint, maxWidth, rtl)
    }

    fun paint(context: Context, sizePx: Float, color: Int, weight: Weight = Weight.MEDIUM): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = font(context, weight)
            textSize = sizePx
            this.color = color
        }
}
