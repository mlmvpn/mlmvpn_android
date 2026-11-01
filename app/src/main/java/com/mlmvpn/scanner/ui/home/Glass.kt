package com.mlmvpn.scanner.ui.home

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted glass, the way iOS does it: the card shows a blurred slice of what is actually behind
 * it, not a flat translucent fill.
 *
 * Compose has no backdrop blur, and `Modifier.blur` cannot see past its own subtree in any case
 * (nor does it exist below API 31). What makes this possible here is that we know exactly what is
 * behind every card: the wallpaper, painted once at the activity root. So the effect is built the
 * way a compositor would build it -- take a small blurred copy of the whole backdrop, work out
 * which rectangle of it this card is covering, and draw that slice stretched back to the card's
 * size. Because the copy is a fraction of the resolution, the upscale IS the blur, and it costs
 * one shared bitmap for the whole screen rather than one per card.
 *
 * The alignment is what sells it: `positionInWindow()` gives the card's offset in the same
 * coordinate space the backdrop is painted in, so the slice lines up with the pixels underneath
 * exactly. Get that wrong by a few pixels and it stops reading as glass and starts reading as a
 * texture.
 *
 * When the wallpaper is not behind the card at all -- Display > Wallpaper on every screen turned
 * off -- there is nothing to refract, so this falls back to a plain translucent card. Showing a
 * blurred wallpaper over a flat background would be inventing a reflection of something that is
 * not there.
 */
@Composable
fun Modifier.frostedGlass(
    shape: Shape,
    /** Off for a card that does not sit on the wallpaper. */
    overWallpaper: Boolean = Appearance.wallpaperEverywhere,
    /**
     * Whether this card sits on a screen that dims the wallpaper.
     *
     * It matters more than it sounds. A feature screen paints a scrim over the backdrop, and the
     * card draws the RAW wallpaper slice on top of that -- so without repeating the scrim the card
     * became a bright, sharp window cut through a dimmed screen, showing more of the photo than
     * the screen around it. The home screen has no scrim, so the dock passes false.
     */
    underScrim: Boolean = true,
): Modifier {
    val dark = Appearance.isDark()
    // Read from state, not from the View. See LocalWindowSizePx for why that mattered.
    val windowSize = com.mlmvpn.scanner.ui.LocalWindowSizePx.current
    val windowW = windowSize.width
    val windowH = windowSize.height

    // Both layers are needed. The tint sets the card's own value so text has something stable to
    // sit on; the wash is the milky lift that makes it read as glass rather than as a dark pane.
    // Light lifts the glass; it does not invert it. Text is white in both appearances, so the
    // wash has a ceiling: past roughly 0.22 over a pale wallpaper white type stops reading.
    val tint = if (dark) Color.Black.copy(alpha = 0.34f) else Color.Black.copy(alpha = 0.16f)
    val wash = if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.20f)
    // iOS catches a light edge on the top rim of every glass surface. A single hairline border all
    // the way round is the cheap version of it and survives any corner radius.
    val rim = if (dark) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.65f)

    val backdrop = rememberFrostedBackdrop(
        widthPx = if (overWallpaper) windowW else 0,
        heightPx = if (overWallpaper) windowH else 0,
    )

    // The same scrim AppScreen's feature host paints over the wallpaper. Repeated here so the
    // card sits at the screen's own value instead of punching a hole to the picture underneath.
    val scrim = when {
        !underScrim -> Color.Transparent
        dark -> Color.Black.copy(alpha = 0.45f)
        else -> Color.Black.copy(alpha = 0.20f)
    }

    // The blur setting has to reach the glass as well, and not only through the backdrop bitmap.
    // Softening a preset barely shows through a slice this small once the tint is on top of it,
    // and a photo slice is not blurred by the same code path the wallpaper uses at all -- so at a
    // high blur the cards were sitting on a sharper, brighter version of the picture behind them.
    // The same haze the wallpaper gets, applied here, keeps the two in step at every setting.
    val hazeAlpha = (Wallpapers.blur / 100f) * 0.42f

    var origin by remember { mutableStateOf(Offset.Zero) }

    return this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .clip(shape)
        .drawBehind {
            val image = backdrop
            if (image != null && windowW > 0 && windowH > 0 && image.width > 0 && image.height > 0) {
                val scaleX = image.width.toFloat() / windowW
                val scaleY = image.height.toFloat() / windowH
                val srcX = (origin.x * scaleX).toInt().coerceIn(0, image.width - 1)
                val srcY = (origin.y * scaleY).toInt().coerceIn(0, image.height - 1)
                val srcW = (size.width * scaleX).toInt()
                    .coerceAtLeast(1).coerceAtMost(image.width - srcX)
                val srcH = (size.height * scaleY).toInt()
                    .coerceAtLeast(1).coerceAtMost(image.height - srcY)
                drawImage(
                    image = image,
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize = IntSize(srcW, srcH),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.High,
                )
                if (scrim.alpha > 0f) drawRect(scrim)
                drawRect(tint)
                drawRect(wash)
                if (hazeAlpha > 0f) drawRect(Color.Black.copy(alpha = hazeAlpha))
            } else {
                // No backdrop to refract: an ordinary translucent card.
                drawRect(
                    if (dark) Color(0xFF1C1C1E).copy(alpha = 0.92f)
                    else Color(0xFF3A3A3C).copy(alpha = 0.94f)
                )
            }
        }
        .border(0.7.dp, rim, shape)
}

/**
 * Fades the top few dp of a scrolling surface to nothing, so content passing under the clock and
 * battery dissolves instead of ending on a hard line.
 *
 * The mask is a `DstIn` gradient, which multiplies what is already drawn by the gradient's alpha.
 * That only works if the content has been rendered to its own buffer first, which is what
 * `CompositingStrategy.Offscreen` is for -- without it the blend runs against whatever is on the
 * screen underneath and the fade turns into a grey smear.
 */
fun Modifier.fadingTopEdge(height: Dp): Modifier = this
    .fadingEdges(top = height, bottom = 0.dp)

/**
 * A small phone (Android 8, or a heap under 200 MB -- the TV boxes and the entry-level phones of the
 * freeze reports) does without the cosmetic fades. Each one is a full-screen offscreen layer the
 * whole page is drawn into on every frame, and on those phones that layer is what kept the main
 * thread drawing for seconds and ran the app out of memory (freeze and OOM reports, 1.2.41).
 */
internal val liteRendering: Boolean by lazy {
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P || Runtime.getRuntime().maxMemory() < 200L * 1024 * 1024
}

/**
 * The same fade at BOTH ends of a scrolling surface.
 *
 * The bottom one exists for the same reason as the top one and had simply been missing: content
 * scrolled under the system navigation bar and stopped at a hard line, so the last row on screen
 * was sliced in half by a transparent bar with nothing softening it. On a page that is edge to
 * edge -- which every page in this app now is -- that reads as a rendering fault.
 *
 * One pass, not two: each fade is a `DstIn` gradient over the same offscreen buffer, and running
 * them as separate modifiers would allocate a second layer for the whole scrolling surface.
 */
fun Modifier.fadingEdges(top: Dp, bottom: Dp): Modifier = if (liteRendering) this else this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val topPx = top.toPx()
        if (topPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black,
                    startY = 0f,
                    endY = topPx,
                ),
                size = Size(size.width, topPx),
                blendMode = BlendMode.DstIn,
            )
        }
        val bottomPx = bottom.toPx()
        if (bottomPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startY = size.height - bottomPx,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - bottomPx),
                size = Size(size.width, bottomPx),
                blendMode = BlendMode.DstIn,
            )
        }
    }

