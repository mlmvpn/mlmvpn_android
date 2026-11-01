package com.mlmvpn.scanner.ui.home

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import java.io.File

/**
 * The home screen's backdrop.
 *
 * Every preset is DRAWN, not shipped as an image. Ten wallpapers as WebP files would have added
 * roughly 3-6 MB to every APK -- and each one would still have been the wrong aspect ratio on
 * some phone, because a single crop cannot serve 18:9 and 21:9 and a foldable at once. A base
 * colour plus a handful of soft radial blobs costs zero bytes, resolves at whatever size the
 * screen happens to be, and stays sharp at any density.
 *
 * The brief for the palette was "not too bright, not too dark, not gaudy": every preset sits at
 * a low-mid lightness with restrained saturation, so white icon labels stay readable without a
 * scrim and nothing fights the icons for attention.
 */
data class Blob(
    /** Centre as a fraction of the canvas: 0f..1f on each axis. */
    val cx: Float,
    val cy: Float,
    /** Radius as a fraction of the canvas's larger side. */
    val r: Float,
    val color: Color,
)

data class WallpaperPreset(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    val base: Color,
    val blobs: List<Blob>,
)

object Wallpapers {

    const val CUSTOM_ID = "custom"
    const val DEFAULT_ID = "dusk"
    const val PREF_KEY = "wallpaper_id"
    const val BLUR_KEY = "wallpaper_blur"

    /**
     * Ten backdrops. The first echoes the warm brown haze of the reference screenshot; the rest
     * walk the hue circle at the same lightness, so switching between them changes the mood
     * without ever changing how legible the screen is.
     */
    val PRESETS: List<WallpaperPreset> = listOf(
        WallpaperPreset(
            "dusk", "شامگاه", "Dusk",
            base = Color(0xFF241A16),
            blobs = listOf(
                Blob(0.20f, 0.16f, 0.85f, Color(0xFF5C4033)),
                Blob(0.86f, 0.34f, 0.62f, Color(0xFF4A2F26)),
                Blob(0.50f, 0.94f, 0.75f, Color(0xFF1A1210)),
            ),
        ),
        WallpaperPreset(
            "amber", "کهربا", "Amber",
            base = Color(0xFF2A1E12),
            blobs = listOf(
                Blob(0.78f, 0.14f, 0.80f, Color(0xFF7A5320)),
                Blob(0.14f, 0.52f, 0.66f, Color(0xFF4B331A)),
                Blob(0.55f, 0.98f, 0.70f, Color(0xFF1C1409)),
            ),
        ),
        WallpaperPreset(
            "indigo", "نیلی", "Indigo",
            base = Color(0xFF161A2E),
            blobs = listOf(
                Blob(0.24f, 0.20f, 0.82f, Color(0xFF33407A)),
                Blob(0.84f, 0.60f, 0.68f, Color(0xFF283155)),
                Blob(0.50f, 0.96f, 0.72f, Color(0xFF0E1120)),
            ),
        ),
        WallpaperPreset(
            "charcoal", "زغالی", "Charcoal",
            base = Color(0xFF1A1A1C),
            blobs = listOf(
                Blob(0.30f, 0.18f, 0.86f, Color(0xFF3A3A3F)),
                Blob(0.82f, 0.70f, 0.60f, Color(0xFF2A2A2E)),
                Blob(0.50f, 1.00f, 0.70f, Color(0xFF101012)),
            ),
        ),
        WallpaperPreset(
            "mist", "مه صبح", "Morning Mist",
            base = Color(0xFF1E2626),
            blobs = listOf(
                Blob(0.22f, 0.24f, 0.84f, Color(0xFF44585A)),
                Blob(0.80f, 0.22f, 0.58f, Color(0xFF35494B)),
                Blob(0.55f, 0.95f, 0.72f, Color(0xFF141A1A)),
            ),
        ),
        WallpaperPreset(
            "violet", "ارغوانی", "Violet",
            base = Color(0xFF221831),
            blobs = listOf(
                Blob(0.76f, 0.18f, 0.80f, Color(0xFF56317A)),
                Blob(0.18f, 0.58f, 0.66f, Color(0xFF3A2352)),
                Blob(0.50f, 0.98f, 0.70f, Color(0xFF150F1F)),
            ),
        ),
        WallpaperPreset(
            "ocean", "اقیانوس", "Ocean",
            base = Color(0xFF10222B),
            blobs = listOf(
                Blob(0.26f, 0.22f, 0.84f, Color(0xFF1F4E63)),
                Blob(0.84f, 0.56f, 0.64f, Color(0xFF17394A)),
                Blob(0.50f, 0.97f, 0.72f, Color(0xFF0A161C)),
            ),
        ),
        WallpaperPreset(
            "slate", "خاکستری", "Slate",
            base = Color(0xFF1C2024),
            blobs = listOf(
                Blob(0.20f, 0.28f, 0.82f, Color(0xFF3B444E)),
                Blob(0.86f, 0.24f, 0.58f, Color(0xFF2C333B)),
                Blob(0.50f, 0.98f, 0.70f, Color(0xFF111417)),
            ),
        ),
        WallpaperPreset(
            "sunset", "غروب", "Sunset",
            base = Color(0xFF2B1720),
            blobs = listOf(
                Blob(0.72f, 0.20f, 0.82f, Color(0xFF7B3446)),
                Blob(0.16f, 0.44f, 0.64f, Color(0xFF4C2130)),
                Blob(0.50f, 0.98f, 0.72f, Color(0xFF1A0E14)),
            ),
        ),
        WallpaperPreset(
            "forest", "جنگل", "Forest",
            base = Color(0xFF15231A),
            blobs = listOf(
                Blob(0.24f, 0.20f, 0.84f, Color(0xFF2C4E36)),
                Blob(0.82f, 0.62f, 0.62f, Color(0xFF203A28)),
                Blob(0.50f, 0.98f, 0.70f, Color(0xFF0C150F)),
            ),
        ),
    )

    fun byId(id: String?): WallpaperPreset =
        PRESETS.firstOrNull { it.id == id } ?: PRESETS.first { it.id == DEFAULT_ID }

    /**
     * The live selection, mirrored as Compose state.
     *
     * The backdrop is painted at the activity root but chosen from a screen several levels deep
     * inside the navigation, and a composable cannot observe a SharedPreferences write. Holding
     * the choice here as well is what makes a tap in the picker repaint the wallpaper instantly
     * rather than on the next launch.
     */
    var current by mutableStateOf(DEFAULT_ID)
        private set

    /**
     * Bumped whenever the custom photo file is replaced. The id stays "custom" across a change
     * of photo, so without this the decoded bitmap would be cached against an unchanged key and
     * the old picture would stay on screen.
     */
    var customRevision by mutableStateOf(0)
        private set

    fun selectedId(context: Context): String =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, DEFAULT_ID) ?: DEFAULT_ID

    /** Seeds [current] from disk. Called once, at activity start. */
    fun init(context: Context) {
        current = selectedId(context)
        blur = blurOf(context)
    }

    fun select(context: Context, id: String) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_KEY, id).apply()
        current = id
    }

    internal fun noteCustomChanged() {
        customRevision++
    }

    /** 0..100. Mirrored as Compose state for the same reason [current] is. */
    var blur by mutableStateOf(0)
        private set

    fun blurOf(context: Context): Int =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(BLUR_KEY, 0).coerceIn(0, 100)

    fun setBlur(context: Context, percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putInt(BLUR_KEY, clamped).apply()
        blur = clamped
    }
}

/**
 * Paints one preset. Each blob is a radial gradient that fades to fully transparent, so the blobs
 * blend into each other and into the base instead of showing hard edges.
 *
 * [softness] is what the blur slider actually does to a preset, and it exists because rendering
 * small and upscaling -- which is how the photo path blurs -- does nothing here. A preset is three
 * enormous soft gradients: there is no detail in it to lose, so a downscaled copy comes back
 * looking identical, and the slider appeared to be broken. Flattening the blobs toward the base
 * colour is the honest equivalent of defocusing something that has nothing in focus.
 */
fun DrawScope.drawWallpaper(preset: WallpaperPreset, softness: Float = 0f) {
    drawRect(color = preset.base)
    val fade = softness.coerceIn(0f, 1f)
    val span = maxOf(size.width, size.height)
    preset.blobs.forEach { blob ->
        val radius = blob.r * span
        if (radius <= 0f) return@forEach
        val tone = androidx.compose.ui.graphics.lerp(blob.color, preset.base, fade * 0.88f)
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to tone,
                    0.55f to tone.copy(alpha = 0.45f),
                    1f to tone.copy(alpha = 0f),
                ),
                center = Offset(blob.cx * size.width, blob.cy * size.height),
                radius = radius,
            )
        )
    }
}

/**
 * The user's own photo.
 *
 * The picked image is COPIED into the app's own files directory rather than kept as a URI. A
 * content URI is only readable for as long as its grant lasts, and a grant does not reliably
 * survive a reboot or the photo being moved in the gallery -- which would leave the home screen
 * with no backdrop at all. Copying costs one file and makes the wallpaper permanent.
 */
object CustomWallpaper {
    /**
     * Longest edge, in pixels, that a decoded wallpaper may have.
     *
     * 2560 covers the tallest phone and the widest tablet this runs on with room to spare, and
     * caps one bitmap at about 26MB against the canvas's ~100MB refusal.
     */
    private const val MAX_DECODE_EDGE = 2560


    private const val FILE_NAME = "wallpaper.img"

    /** Decoded once and reused: without this the bitmap is re-decoded on every recomposition. */
    @Volatile private var cached: ImageBitmap? = null
    @Volatile private var cachedKey: String? = null

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0 }

    /** Copies the picked image in. Returns false (and changes nothing) if it cannot be read. */
    fun save(context: Context, uri: Uri): Boolean = try {
        val target = file(context)
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
            true
        } ?: false
        cached = null
        cachedKey = null
        Wallpapers.noteCustomChanged()
        copied && target.length() > 0
    } catch (e: Exception) {
        false
    }

    fun clear(context: Context) {
        file(context).delete()
        cached = null
        cachedKey = null
        Wallpapers.noteCustomChanged()
    }

    /**
     * Loads the photo downsampled to roughly the screen size. A full-resolution phone photo is
     * 12+ megapixels -- decoding one at native size to draw it at 1080x2400 wastes tens of MB of
     * heap and risks OutOfMemory on a low-end device for no visible gain.
     */
    fun load(context: Context, reqWidth: Int, reqHeight: Int): ImageBitmap? {
        val f = file(context)
        if (!f.isFile || f.length() == 0L) return null
        // The key carries the requested size as well as the file's timestamp: the blur control
        // works by asking for a SMALLER decode, so caching on the timestamp alone would hand back
        // the previous resolution and the slider would appear to do nothing.
        val key = "${'$'}{f.lastModified()}:${'$'}reqWidth:${'$'}reqHeight"
        cached?.let { if (key == cachedKey) return it }

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (reqWidth > 0 && reqHeight > 0 &&
                   bounds.outWidth / (sample * 2) >= reqWidth &&
                   bounds.outHeight / (sample * 2) >= reqHeight) {
                sample *= 2
            }

            // A ceiling that does not depend on the requested size, because the requested size is
            // ZERO on the first frame -- the composable has not been measured yet. With the loop
            // above skipped, an 8000-pixel-square photo was decoded whole, cached at that size,
            // and handed to the canvas, which refuses anything over 100MB: a user reached
            // "Canvas: trying to draw too large (262440000bytes) bitmap" on the home screen
            // before touching anything. No wallpaper needs more pixels than this, at any request.
            while (bounds.outWidth / sample > MAX_DECODE_EDGE ||
                   bounds.outHeight / sample > MAX_DECODE_EDGE) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeFile(f.absolutePath, opts)?.let { raw ->
                // Some decoders ignore inSampleSize (seen with a phone-camera photo on a Galaxy,
                // 1.2.39: a 207 MB bitmap and "Canvas: trying to draw too large"). Whatever came
                // back, nothing larger than the ceiling reaches the screen.
                val edge = maxOf(raw.width, raw.height)
                if (edge <= MAX_DECODE_EDGE) raw else {
                    val k = MAX_DECODE_EDGE.toFloat() / edge
                    android.graphics.Bitmap.createScaledBitmap(
                        raw, (raw.width * k).toInt().coerceAtLeast(1), (raw.height * k).toInt().coerceAtLeast(1), true,
                    ).also { if (it !== raw) raw.recycle() }
                }
            } ?: return null

            // `inSampleSize` only halves, and only while BOTH sides still exceed the request, so
            // it routinely stops several times larger than what was asked for. That is fine for
            // memory and fatal for the frosted glass: the effect blurs by drawing a small copy
            // back at full size, and a copy that came out five times too big is a copy that is
            // barely blurred at all. This is exactly why the cards showed a sharp wallpaper while
            // the slider appeared to do nothing. Scaling explicitly afterwards makes the
            // requested size the size you actually get.
            //
            // The scale COVERS the request rather than fitting it, matching the ContentScale.Crop
            // these bitmaps are drawn with -- fitting instead would letterbox, and stretching each
            // axis independently would distort the photo.
            val bitmap = if (reqWidth > 0 && reqHeight > 0 &&
                             (decoded.width > reqWidth || decoded.height > reqHeight)) {
                val cover = maxOf(
                    reqWidth.toFloat() / decoded.width,
                    reqHeight.toFloat() / decoded.height,
                )
                val targetW = kotlin.math.ceil(decoded.width * cover).toInt().coerceAtLeast(1)
                val targetH = kotlin.math.ceil(decoded.height * cover).toInt().coerceAtLeast(1)
                if (targetW < decoded.width || targetH < decoded.height) {
                    android.graphics.Bitmap
                        .createScaledBitmap(decoded, targetW, targetH, true)
                        .also { if (it !== decoded) decoded.recycle() }
                } else {
                    decoded
                }
            } else {
                decoded
            }

            bitmap.asImageBitmap().also {
                cached = it
                cachedKey = key
            }
        } catch (e: Throwable) {
            // OutOfMemory included: a missing backdrop is survivable, a crash on launch is not.
            null
        }
    }
}

/**
 * How much of the native resolution to render at, for a given blur percentage.
 *
 * Blur here is done by rendering small and letting the upscale do the smoothing, rather than by
 * `Modifier.blur`. `Modifier.blur` is a no-op below API 31, so on anything older than Android 12
 * the slider would have silently done nothing; this path behaves identically on every version,
 * and it costs LESS memory as the blur goes up rather than more.
 */
private fun blurScale(percent: Int): Float =
    1f / (1f + percent.coerceIn(0, 100) * 0.12f)

/** Renders a preset into an offscreen bitmap at [w] x [h], for the blurred path. */
private fun renderPreset(
    preset: WallpaperPreset,
    w: Int,
    h: Int,
    density: Density,
    softness: Float = 0f,
): ImageBitmap {
    val bitmap = ImageBitmap(w, h)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    CanvasDrawScope().draw(
        density = density,
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(w.toFloat(), h.toFloat()),
    ) {
        drawWallpaper(preset, softness)
    }
    return bitmap
}

/**
 * The backdrop itself, drawn edge to edge behind everything else.
 *
 * `wallpaperId` and `blurPercent` are passed in rather than read here, so a change made in the
 * picker repaints immediately -- a composable has no way to observe a SharedPreferences write --
 * and so the picker can preview a blur the user has not committed yet.
 */
@Composable
fun HomeWallpaper(
    wallpaperId: String,
    blurPercent: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val blur = blurPercent.coerceIn(0, 100)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val hPx = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(1)
        val scale = blurScale(blur)

        if (wallpaperId == Wallpapers.CUSTOM_ID) {
            // A real blur where the platform has one. `Modifier.blur` arrived in API 31, and
            // where it exists the photo is decoded at DISPLAY size and blurred properly -- which
            // is the fix for a 4K wallpaper coming out pixelated. The render-small trick was
            // blurring by throwing pixels away, and past a gentle setting that stops looking like
            // defocus and starts looking like a low-resolution image, which is exactly what it
            // was. Below API 31 there is no choice, so the old path stays as the fallback.
            val realBlur = android.os.Build.VERSION.SDK_INT >= 31
            val decodeScale = if (realBlur) 1f else scale
            // Decoded on a worker thread: a display-sized photo on the main thread froze the
            // home screen (ANR #719).
            val image by androidx.compose.runtime.produceState<ImageBitmap?>(null, Wallpapers.customRevision, decodeScale, wPx, hPx) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CustomWallpaper.load(
                        context,
                        (wPx * decodeScale).toInt().coerceAtLeast(16),
                        (hPx * decodeScale).toInt().coerceAtLeast(16),
                    )
                }
            }
            val photo = image
            if (photo != null) {
                Image(
                    bitmap = photo,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (realBlur && blur > 0) {
                                Modifier.blur(
                                    radius = (blur * 0.45f).dp,
                                    edgeTreatment = BlurredEdgeTreatment.Rectangle,
                                )
                            } else {
                                Modifier
                            }
                        ),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                )
                // iOS pairs a blur with a haze. Without one a heavily blurred photo is still a
                // bright, busy field of colour behind white type.
                if (blur > 0) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = (blur / 100f) * 0.42f))
                    )
                }
                return@BoxWithConstraints
            }
            // Falls through to the default preset when the photo is gone or undecodable.
        }

        val preset = Wallpapers.byId(wallpaperId)
        if (blur == 0) {
            // Drawn once into a picture on a worker thread, then only copied: drawn live, the
            // preset's gradients ran again with every frame anything on the board moved, and a
            // slow phone's main thread spent long enough there for Android to call it frozen
            // (1.2.42 #2302).
            val sharp by androidx.compose.runtime.produceState<ImageBitmap?>(null, preset.id, wPx, hPx) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { renderPreset(preset, wPx, hPx, density, 0f) }.getOrNull()
                }
            }
            val picture = sharp
            if (picture == null) {
                Canvas(modifier = Modifier.fillMaxSize()) { drawWallpaper(preset) }
            } else {
                Image(
                    bitmap = picture,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        } else {
            val softness = blur / 100f
            // Rendered on a worker thread (ANR #659); the sharp preset shows until it is ready.
            val small by androidx.compose.runtime.produceState<ImageBitmap?>(null, preset.id, blur, wPx, hPx) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    renderPreset(
                        preset,
                        (wPx * scale).toInt().coerceAtLeast(8),
                        (hPx * scale).toInt().coerceAtLeast(8),
                        density,
                        softness,
                    )
                }
            }
            val ready = small
            if (ready == null) {
                Canvas(modifier = Modifier.fillMaxSize()) { drawWallpaper(preset) }
                return@BoxWithConstraints
            }
            Image(
                bitmap = ready,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
        }
    }
}


/**
 * A box blur over the shared backdrop copy.
 *
 * Stretching a tiny bitmap back to full size is NOT a blur, and this is the third attempt at the
 * same effect for exactly that reason. Bilinear interpolation across a large upscale produces soft
 * SQUARES -- which is what "pixelated" looked like on the device. A blur needs a kernel: each
 * output pixel has to be the average of a neighbourhood, not a weighted pick of the two source
 * pixels either side of it.
 *
 * Three box passes approximate a Gaussian closely enough that the difference is invisible, and on
 * a bitmap this small -- a sixth of the window, so roughly 180 x 400 -- the whole thing is a few
 * hundred thousand integer operations, run once when the wallpaper or the window size changes.
 *
 * Written by hand rather than with RenderScript (deprecated, and gone from the modern toolchain)
 * or RenderEffect (API 31+, while this app supports 24).
 */
private fun boxBlur(source: android.graphics.Bitmap, radius: Int, passes: Int = 3): android.graphics.Bitmap {
    val w = source.width
    val h = source.height
    if (w < 3 || h < 3 || radius < 1) return source

    val a = IntArray(w * h)
    source.getPixels(a, 0, w, 0, 0, w, h)
    val b = IntArray(w * h)

    repeat(passes) {
        blurRows(a, b, w, h, radius)
        blurColumns(b, a, w, h, radius)
    }

    return android.graphics.Bitmap.createBitmap(a, w, h, android.graphics.Bitmap.Config.ARGB_8888)
}

/**
 * One horizontal pass, as a moving sum: the window is primed once per row and then slides, so the
 * cost does not grow with the radius.
 *
 * Reads past either end clamp to the edge pixel. Wrapping would bleed the far side of the picture
 * into the near one; treating them as transparent would darken every border.
 */
private fun blurRows(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
    val window = radius * 2 + 1
    for (y in 0 until h) {
        val row = y * w
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        for (i in -radius..radius) {
            val p = src[row + i.coerceIn(0, w - 1)]
            sa += (p ushr 24) and 0xFF; sr += (p ushr 16) and 0xFF
            sg += (p ushr 8) and 0xFF;  sb += p and 0xFF
        }
        for (x in 0 until w) {
            dst[row + x] = ((sa / window) shl 24) or ((sr / window) shl 16) or
                ((sg / window) shl 8) or (sb / window)
            val leaving = src[row + (x - radius).coerceIn(0, w - 1)]
            val entering = src[row + (x + radius + 1).coerceIn(0, w - 1)]
            sa += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
            sr += ((entering ushr 16) and 0xFF) - ((leaving ushr 16) and 0xFF)
            sg += ((entering ushr 8) and 0xFF) - ((leaving ushr 8) and 0xFF)
            sb += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

/** The same pass down each column; the two together make the blur separable. */
private fun blurColumns(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
    val window = radius * 2 + 1
    for (x in 0 until w) {
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        for (i in -radius..radius) {
            val p = src[i.coerceIn(0, h - 1) * w + x]
            sa += (p ushr 24) and 0xFF; sr += (p ushr 16) and 0xFF
            sg += (p ushr 8) and 0xFF;  sb += p and 0xFF
        }
        for (y in 0 until h) {
            dst[y * w + x] = ((sa / window) shl 24) or ((sr / window) shl 16) or
                ((sg / window) shl 8) or (sb / window)
            val leaving = src[(y - radius).coerceIn(0, h - 1) * w + x]
            val entering = src[(y + radius + 1).coerceIn(0, h - 1) * w + x]
            sa += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
            sr += ((entering ushr 16) and 0xFF) - ((leaving ushr 16) and 0xFF)
            sg += ((entering ushr 8) and 0xFF) - ((leaving ushr 8) and 0xFF)
            sb += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

/**
 * Redraws [src] into a [w] x [h] bitmap, cropped to cover, exactly as ContentScale.Crop would.
 *
 * This is the step whose absence broke the frosted glass, and the reason is worth stating because
 * it is not obvious. The glass works out which slice of the backdrop a card covers with a linear
 * mapping: `scale = image.width / windowWidth`. That mapping is only true when the image has the
 * SAME ASPECT as the window. The wallpaper is drawn with Crop, so a landscape photo on a portrait
 * screen has most of its width cut off -- and the glass, knowing nothing about that, both sampled
 * the wrong region and computed a scale several times too large. The visible result was a card
 * showing a sharp, slightly stretched, slightly misplaced piece of the picture: not blurred, and
 * not quite lined up either.
 *
 * Cropping here means the copy IS the window, so the mapping is exact and the upscale is the full
 * factor it was meant to be.
 */
private fun coverInto(src: ImageBitmap, w: Int, h: Int, density: Density): ImageBitmap {
    val out = ImageBitmap(w, h)
    val canvas = androidx.compose.ui.graphics.Canvas(out)
    val cover = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
    val drawW = (src.width * cover).toInt().coerceAtLeast(1)
    val drawH = (src.height * cover).toInt().coerceAtLeast(1)
    CanvasDrawScope().draw(
        density = density,
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(w.toFloat(), h.toFloat()),
    ) {
        drawImage(
            image = src,
            dstOffset = androidx.compose.ui.unit.IntOffset((w - drawW) / 2, (h - drawH) / 2),
            dstSize = androidx.compose.ui.unit.IntSize(drawW, drawH),
            filterQuality = FilterQuality.High,
        )
    }
    return out
}

/**
 * One small copy of the whole backdrop, in the window's own aspect, shared by every glass surface.
 *
 * A fourteenth of each side, and deliberately NOT tied to the wallpaper's blur setting: frosted
 * glass is frosted whether or not the picture behind it is in focus. The copy only ever gets
 * drawn back at 14x behind a wash, so that upscale is where all of the blur comes from.
 */
@Composable
fun rememberFrostedBackdrop(widthPx: Int, heightPx: Int): ImageBitmap? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val id = Wallpapers.current
    val revision = Wallpapers.customRevision

    // Made on a worker thread: blurring a photo-sized copy on the main thread froze the home
    // screen for seconds on slower phones (ANR #489, #659, #719). The glass is plain until it is ready.
    val blurSetting = Wallpapers.blur
    val made by androidx.compose.runtime.produceState<ImageBitmap?>(null, id, revision, widthPx, heightPx, blurSetting) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { makeFrostedBackdrop(context, density, id, widthPx, heightPx, blurSetting) }
    }
    return made
}

private fun makeFrostedBackdrop(context: Context, density: Density, id: String?, widthPx: Int, heightPx: Int, blurSetting: Int): ImageBitmap? {
    run {
        if (widthPx <= 0 || heightPx <= 0) return null
        // A sixth, not a fourteenth. The blur is done properly now, so the copy no longer has to
        // be tiny to hide the fact that it was not -- and a larger source means the 6x upscale
        // adds no blockiness of its own.
        val w = (widthPx / 6).coerceAtLeast(48)
        val h = (heightPx / 6).coerceAtLeast(48)
        val softness = blurSetting / 100f

        val flat = if (id == Wallpapers.CUSTOM_ID) {
            val photo = CustomWallpaper.load(context, w * 2, h * 2)
            if (photo != null) {
                coverInto(photo, w, h, density)
            } else {
                renderPreset(Wallpapers.byId(null), w, h, density, softness)
            }
        } else {
            // A preset is drawn straight into the target box, so it is already the right shape.
            renderPreset(Wallpapers.byId(id), w, h, density, softness)
        }

        // Roughly a tenth of the copy's width, so the frost scales with the screen rather than
        // being a fixed pixel count that looks heavy on a small phone and weak on a tablet.
        val radius = (w / 10).coerceIn(4, 24)
        return try {
            boxBlur(flat.asAndroidBitmap(), radius).asImageBitmap()
        } catch (e: Throwable) {
            // A blur is a nicety; a crash on the home screen is not.
            flat
        }
    }
}
