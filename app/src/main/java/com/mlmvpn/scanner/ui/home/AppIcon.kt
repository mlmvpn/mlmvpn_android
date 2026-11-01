package com.mlmvpn.scanner.ui.home

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Apple's icons are not rounded rectangles but squircles -- a continuous curve with no visible
 * join between the straight edge and the corner. Compose has no squircle, and RoundedCornerShape
 * at the naive 20% reads noticeably squarer than the real thing. 26% is the closest a circular
 * corner gets to the same optical weight at these sizes.
 */
private val Squircle = RoundedCornerShape(percent = 26)

/**
 * The white label under an icon has to stay readable over ten different backdrops AND over
 * whatever photo the user picked, which could be a snowfield. A drop shadow does that without a
 * scrim behind every caption, which would look nothing like a home screen.
 */
private val LabelShadow = Shadow(
    color = Color.Black.copy(alpha = 0.65f),
    offset = Offset(0f, 1f),
    blurRadius = 4f,
)

/**
 * Just the coloured tile, no label -- also used for the drag preview and the picker previews.
 *
 * [connected] draws the running lamp. It is a parameter rather than something this reads for
 * itself so the drag preview and the layout picker, which render the same tile outside the grid,
 * do not have to subscribe to engine state to draw an icon.
 */
@Composable
fun AppIconTile(
    app: HomeApp,
    size: Dp,
    modifier: Modifier = Modifier,
    connected: Boolean = false,
    /** iOS's red count badge, top-right. 0 draws nothing. */
    badge: Int = 0,
) {
    // One Box around both shapes so the lamp is positioned against the tile rather than against
    // the cell: `size` is the tile, and the cell around it is wider by whatever the caption needs.
    Box(modifier = modifier.size(size)) {
        TileFace(app = app, size = size)
        if (connected) {
            // Physical top-left in both directions. `TopStart` alone would sit top-RIGHT on this
            // app's Persian layout, and hardcoding `TopEnd` would move it the day a screen is
            // shown in English -- so the corner is chosen from the direction rather than assumed.
            val topLeft = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                Alignment.TopEnd
            } else {
                Alignment.TopStart
            }
            RunningLamp(size = size, modifier = Modifier.align(topLeft))
        }
        if (badge > 0) {
            // Top-RIGHT physically, the corner iOS gives the badge -- the opposite corner to the
            // lamp, so an engine that is running and has news shows both.
            val topRight = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                Alignment.TopStart
            } else {
                Alignment.TopEnd
            }
            CountBadge(count = badge, size = size, modifier = Modifier.align(topRight))
        }
    }
}

/**
 * The red count badge iOS puts on an app with something waiting -- on Settings, "1" for a
 * software update. Same red, same white bold figure, pulled half outside the corner.
 */
@Composable
private fun CountBadge(count: Int, size: Dp, modifier: Modifier = Modifier) {
    val d = (size * 0.36f).coerceIn(18.dp, 24.dp)
    Box(
        modifier = modifier
            .absoluteOffset(x = d * 0.34f, y = -(d * 0.34f))
            .height(d)
            .widthIn(min = d)
            .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFFF3B30))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = (d.value * 0.58f).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * The lamp that says this engine is the one that is up.
 *
 * Top-LEFT physically, in a layout that is right-to-left everywhere else — so the alignment is
 * pinned by forcing LTR for this one child rather than by using TopEnd, which would silently
 * move to the other corner the day a screen is shown in English.
 *
 * The dark ring is not decoration: the lamp sits on top of the icon artwork, and a bare green
 * dot on a green tile (WireGuard, the DNS tile, Game Boost) disappears. The ring separates it
 * from whatever is underneath at any tile colour.
 */
@Composable
private fun RunningLamp(size: Dp, modifier: Modifier = Modifier) {
    val dot = (size * 0.26f).coerceIn(9.dp, 16.dp)
    Box(
        modifier = modifier
            // Pulled slightly outside the tile's corner, the way a badge sits on iOS, so it does
            // not cover the artwork. Absolute, not `offset`: the plain one is mirrored under RTL,
            // which would push the lamp INTO the tile instead of out of it.
            .absoluteOffset(x = -(dot * 0.22f), y = -(dot * 0.22f))
            .size(dot)
            .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(Color(0xFF0B0B0C))
            .padding(2.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF5AF08A), Color(0xFF2FBF5C))
                )
            ),
    )
}

/**
 * The tile itself. [shadow] is off for the miniature copies a folder draws of its members: at a
 * fifth of the size the drop shadow reads as a smudge, not as depth.
 */
@Composable
internal fun TileFace(
    app: HomeApp,
    size: Dp,
    modifier: Modifier = Modifier,
    shadow: Boolean = true,
) {
    val elevation = if (shadow) 6.dp else 0.dp
    if (app.imageRes != null) {
        // Finished artwork: it brings its own background and its own corner shape, so nothing is
        // drawn behind it and it is not clipped. Clipping would shave the corners of a silhouette
        // that is already the right one.
        Image(
            painter = painterResource(app.imageRes),
            contentDescription = null,
            modifier = modifier
                .size(size)
                // The artwork insets its own squircle to 90% of its viewport, the way a shipped
                // iOS icon asset does, while the glyph tiles fill their box edge to edge -- so at
                // face value this one lands visibly smaller than the icons either side of it. The
                // scale is on the layer, so the measured size is still `size` and the grid does
                // not shift; it sits BEFORE the shadow so the shadow scales along with it.
                .graphicsLayer {
                    scaleX = 100f / 90f
                    scaleY = 100f / 90f
                }
                .shadow(elevation = elevation, shape = Squircle, clip = false),
        )
        return
    }

    Box(
        modifier = modifier
            .size(size)
            .shadow(elevation = elevation, shape = Squircle, clip = false)
            .clip(Squircle)
            .background(
                // Three stops rather than two: a lit crown, the body colour, then a shaded base.
                // The two-stop version read as a flat tile with a stripe; the middle stop is what
                // gives the face a curve.
                Brush.verticalGradient(
                    listOf(
                        lerp(app.tint, Color.White, 0.24f),
                        app.tint,
                        lerp(app.tint, Color.Black, 0.22f),
                    )
                )
            )
            // The iOS gloss: a soft light across the top of the tile, drawn OVER the glyph as
            // well — the real thing has the sheen across the whole icon, not just the background.
            // It sits after `clip` in the chain, so the squircle still shapes it.
            .drawWithContent {
                drawContent()
                // `size` unqualified would be TileFace's own Dp parameter, not the
                // DrawScope's Size -- qualify through the receiver.
                val h = this.size.height
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.14f),
                            Color.White.copy(alpha = 0f),
                        ),
                        startY = 0f,
                        endY = h * 0.55f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = app.glyphRes?.let { ImageVector.vectorResource(it) } ?: app.icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * app.glyphScale),
        )
    }
}

/** A full home-screen cell: the tile plus its caption. */
@Composable
fun AppIconCell(
    app: HomeApp,
    tileSize: Dp,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    connected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    // Settings wears the update badge, the way an iPhone announces a new iOS: no banner, no extra
    // glyph in the status bar -- a red "1" on the Settings icon, wherever that icon sits.
    val update by com.mlmvpn.scanner.update.UpdateChecker.updateAvailableFlow.collectAsState()
    val badge = if (app.id == "settings" && update != null) 1 else 0
    Column(
        modifier = modifier.then(
            if (onClick != null) Modifier.noRippleClickable(onClick) else Modifier
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIconTile(app = app, size = tileSize, connected = connected, badge = badge)
        if (showLabel) {
            Spacer(Modifier.height(6.dp))
            TileCaption(stringResource(app.labelRes))
        }
    }
}

/** The caption under a tile -- shared by apps and folders so the two can never drift apart. */
@Composable
internal fun TileCaption(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = (11f * Appearance.labelScale / 100f).sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        style = TextStyle(shadow = LabelShadow),
    )
}

/** The running lamp, for a tile drawn outside [AppIconTile] -- a folder is live when a member is. */
@Composable
internal fun TileLamp(size: Dp, modifier: Modifier = Modifier) {
    val topLeft = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        Alignment.TopEnd
    } else {
        Alignment.TopStart
    }
    Box(modifier = modifier.size(size)) {
        RunningLamp(size = size, modifier = Modifier.align(topLeft))
    }
}

/**
 * Tap without the Material ripple. A ripple spreading over a home-screen icon looks like an
 * Android list row, not like an app launching; the scale animation in the grid carries the
 * feedback instead.
 */
@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
    )
}
