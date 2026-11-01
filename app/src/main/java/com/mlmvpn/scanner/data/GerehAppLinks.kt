package com.mlmvpn.scanner.data

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.core.content.ContextCompat
import com.mlmvpn.scanner.chat.MlmChat
import com.mlmvpn.scanner.ui.home.HomeApp
import com.mlmvpn.scanner.ui.home.HomeDestinations
import com.mlmvpn.scanner.ui.settings.SText
import com.mlmvpn.scanner.ui.settings.SettingsEntry
import com.mlmvpn.scanner.ui.settings.SettingsIndex
import com.mlmvpn.scanner.widget.WidgetOpen
import org.mlmvpn.chat.bridge.GerehLinks

/**
 * «گره»'s tiles and settings in chats (Ehsan, 30 Oct 2026, q11 and q12): what the attach menu
 * offers -- the home screen's tiles, and the rows of Settings the home search finds -- the names
 * a received card shows, and opening the app on one. It fills
 * [org.mlmvpn.chat.bridge.GerehLinks] in the chat's own `:chat` process (MlmApp); opening goes to
 * the main process as MainActivity's intent ([WidgetOpen]).
 */
object GerehAppLinks {

    fun bind(context: Context) {
        val app = context.applicationContext
        GerehLinks.set(object : GerehLinks.Hook {
            override fun appName() = "MLMVPN"

            override fun tiles(): List<GerehLinks.Item> =
                tileApps().map { GerehLinks.Item(it.id, app.getString(it.labelRes), "", it.glyphRes ?: 0) }

            override fun settings(): List<GerehLinks.Item> = SettingsIndex.entries.map { e ->
                GerehLinks.Item(keyOf(app, e), e.title.current(app), e.path.joinToString(" › ") { it.current(app) }, 0)
            }

            override fun titleOf(tile: Boolean, key: String): String? =
                if (tile) tileApps().firstOrNull { it.id == key }?.let { app.getString(it.labelRes) }
                else SettingsIndex.entries.firstOrNull { keyOf(app, it) == key }?.title?.current(app)

            override fun open(activity: Activity?, tile: Boolean, key: String) {
                val ctx: Context = activity ?: app
                if (tile) WidgetOpen.open(ctx, key) else WidgetOpen.openSetting(ctx, key)
            }

            override fun tileFace(key: String, size: Int): Bitmap? =
                tileApps().firstOrNull { it.id == key }?.let { face(app, it, size) }

            override fun card(tile: Boolean, key: String, label: String, title: String, open: String, rtl: Boolean, width: Int, height: Int): Bitmap? {
                val a = if (tile) tileApps().firstOrNull { it.id == key } ?: return null else SETTINGS_FACE
                // the open words go on the button under the picture, not in it
                return card(app, a, label, title, rtl, width, height)
            }
        })
    }

    /** The face a setting's card shows: the app's own blue with a gear. */
    private val SETTINGS_FACE = HomeApp(id = "settings", labelRes = 0, icon = Icons.Filled.Settings, tint = Color(0xFF3D6BEA))

    /**
     * A tile's or setting's card for a chat (Ehsan, 31 Oct 2026: «تصویر تامنیل کارتی شیک»): the
     * tile's colour as a deep gradient with soft circles, its face, a small line and its name in
     * large letters; mirrored for a left-to-right language.
     */
    private fun card(context: Context, a: HomeApp, label: String, title: String, rtl: Boolean, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, wf, hf, lerp(a.tint, Color.Black, 0.05f).toArgb(), lerp(a.tint, Color.Black, 0.55f).toArgb(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, wf, hf, bg)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        glow.alpha = 22
        c.drawCircle(if (rtl) wf * 0.08f else wf * 0.92f, -hf * 0.15f, hf * 0.62f, glow)
        glow.alpha = 14
        c.drawCircle(if (rtl) wf * 0.30f else wf * 0.70f, hf * 1.12f, hf * 0.45f, glow)
        glow.alpha = 10
        c.drawCircle(if (rtl) wf * 0.92f else wf * 0.08f, hf * 0.95f, hf * 0.30f, glow)

        val pad = hf * 0.115f
        val faceSide = (hf * 0.48f).toInt()
        val face = face(context, a, faceSide)
        val faceLeft = if (rtl) wf - pad - faceSide else pad
        val faceTop = (hf - faceSide) / 2f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; alpha = 70 }
        shadow.maskFilter = android.graphics.BlurMaskFilter(hf * 0.05f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(faceLeft + 4f, faceTop + 10f, faceLeft + faceSide + 4f, faceTop + faceSide + 10f, faceSide * 0.26f, faceSide * 0.26f, shadow)
        c.drawBitmap(face, faceLeft, faceTop, null)

        val textW = (wf - pad * 2 - faceSide - pad * 0.8f).toInt()
        val textLeft = if (rtl) pad else pad + faceSide + pad * 0.8f
        val dir = if (rtl) android.text.TextDirectionHeuristics.RTL else android.text.TextDirectionHeuristics.LTR
        fun layout(text: String, size: Float, bold: Boolean, alpha: Int, lines: Int): android.text.StaticLayout {
            val p = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                this.alpha = alpha
                textSize = size
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            return android.text.StaticLayout.Builder.obtain(text, 0, text.length, p, textW)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(dir)
                .setMaxLines(lines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        }
        // no button drawn in the picture: the chat puts a real one under it (Ehsan, 31 Oct 2026:
        // «فقط عکس باشه با دکمه شیشه ای زیرش»)
        val small = layout(label, hf * 0.075f, false, 200, 1)
        val big = layout(title, hf * 0.14f, true, 255, 2)
        val gap = hf * 0.035f
        var y = (hf - (small.height + gap + big.height)) / 2f
        c.save()
        c.translate(textLeft, y)
        small.draw(c)
        c.restore()
        y += small.height + gap
        c.save()
        c.translate(textLeft, y)
        big.draw(c)
        c.restore()
        return bmp
    }

    /**
     * A tile as the home screen's TileFace draws it, outside Compose (the chat's picker is a View):
     * its finished artwork, or its glyph in white on its colour's gradient, in the same squircle.
     * Most tiles have only a Material [HomeApp.icon], whose paths are drawn here one by one.
     */
    private fun face(context: Context, a: HomeApp, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        a.imageRes?.let { ContextCompat.getDrawable(context, it) }?.let { art ->
            // the artwork insets its squircle to 90% of its box (TileFace scales it the same way)
            val out = (s * (100f / 90f - 1f) / 2f).toInt()
            art.setBounds(-out, -out, size + out, size + out)
            art.draw(c)
            return bmp
        }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, s, lerp(a.tint, Color.White, 0.18f).toArgb(), lerp(a.tint, Color.Black, 0.14f).toArgb(), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(0f, 0f, s, s, s * 0.26f, s * 0.26f, bg)
        val g = s * a.glyphScale
        val off = (s - g) / 2f
        val glyph = a.glyphRes?.let { ContextCompat.getDrawable(context, it)?.mutate() }
        if (glyph != null) {
            glyph.setTint(android.graphics.Color.WHITE)
            glyph.setBounds(off.toInt(), off.toInt(), (off + g).toInt(), (off + g).toInt())
            glyph.draw(c)
        } else {
            c.save()
            c.translate(off, off)
            c.scale(g / a.icon.viewportWidth, g / a.icon.viewportHeight)
            drawGroup(c, a.icon.root, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
            c.restore()
        }
        return bmp
    }

    private fun drawGroup(c: Canvas, group: VectorGroup, paint: Paint) {
        c.save()
        c.translate(group.translationX + group.pivotX, group.translationY + group.pivotY)
        c.rotate(group.rotation)
        c.scale(group.scaleX, group.scaleY)
        c.translate(-group.pivotX, -group.pivotY)
        for (node in group) {
            when (node) {
                is VectorGroup -> drawGroup(c, node, paint)
                is VectorPath -> {
                    val path = node.pathData.toPath().asAndroidPath()
                    path.fillType = if (node.pathFillType == PathFillType.EvenOdd) android.graphics.Path.FillType.EVEN_ODD else android.graphics.Path.FillType.WINDING
                    if (node.fill != null) {
                        paint.style = Paint.Style.FILL
                        paint.alpha = (255 * node.fillAlpha).toInt()
                        c.drawPath(path, paint)
                    }
                    if (node.stroke != null && node.strokeLineWidth > 0f) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = node.strokeLineWidth
                        paint.alpha = (255 * node.strokeAlpha).toInt()
                        c.drawPath(path, paint)
                    }
                }
            }
        }
        c.restore()
    }

    /** Every tile of the home screen and its dock, but Gereh's own. */
    private fun tileApps(): List<HomeApp> =
        (HomeDestinations.DOCK + HomeDestinations.GRID_DEFAULT).filter { it.id != MlmChat.TILE_ID }.distinctBy { it.id }

    /**
     * A settings row's key in a link: its page, and the name of its title's string (the same on
     * every phone, build and language) or, for a title with no string of its own, its English words.
     */
    internal fun keyOf(context: Context, e: SettingsEntry): String = e.route + "." + when (val t = e.title) {
        is SText.Res -> context.resources.getResourceEntryName(t.id)
        is SText.Lit -> t.en.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    /** The row a key names, on this phone's version of the app. */
    internal fun entryOf(context: Context, key: String): SettingsEntry? =
        SettingsIndex.entries.firstOrNull { keyOf(context, it) == key }
}
