// Taken from NetDoctor (app/src/main/java/com/netdoctor/app/ui/components/DriftingLights.kt).
// The reasoning in the comments below is theirs and is the reason it is worth having: it is the
// difference between a bar that is lit and a bar with a gradient painted on it.
//
// One thing diverges from the original, and it is the clock -- see [DriftingLights]. NetDoctor
// drives it with `rememberInfiniteTransition`, which is correct on a screen you look at and wrong
// on one you scroll; the drift is on a `delay` loop read in the draw phase here instead.
package com.mlmvpn.scanner.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * The moving lights along the bottom of the connect screen.
 *
 * Three things had to be true, and each of them was wrong in an earlier attempt:
 *
 *  1. **No edge where the colour begins.** This is why the wash is built from
 *     transparency rather than from colours mixed towards the page. Fading a
 *     colour towards white reaches white at some row, and on a four-row mesh
 *     that row is a line across the screen; fading alpha to zero reaches
 *     *nothing*, which cannot be seen at all - and it works the same in dark
 *     theme, where mixing towards white would be exactly wrong.
 *
 *  2. **Lights, not shapes.** Each light is a radial gradient far wider than the
 *     surface, so only its soft middle is ever visible and it has no rim.
 *     Where two overlap they make a third colour, which is what reads as light
 *     mixing rather than as circles stacked.
 *
 *  3. **Always moving, never on a path.** Centres and sizes are driven by value
 *     noise on separate tracks, so a light wanders and swells. Anything on a
 *     circle or a line reads immediately as an animation rather than as water.
 *
 * Deliberately hand-written and small. A mesh-gradient library was tried here
 * and brought OpenGL, a second Compose version, and a 4x4 grid whose top row is
 * a visible boundary - all to draw something four gradients and a mask do.
 */
@Composable
fun DriftingLights(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    /**
     * False to fill the whole surface instead of dissolving towards its top.
     *
     * The fade is what stops the wash having an edge where it begins, and every
     * page that lights its bottom half wants it. A shape that is *entirely*
     * wash - the batch screen's bar, sixty dp of it - has no page above to
     * dissolve into: the fade there simply erases most of the colour, and the
     * bar looks like it has a faint glow at the bottom rather than being lit.
     */
    fade: Boolean = true,
    /**
     * How much wider than usual the coloured lights are drawn.
     *
     * A light is sized off the shorter side of the surface, which is right on a
     * page and wrong on a bar: sixty dp of height makes sixty dp of light, and
     * three small circles crossing a wide bar read as circles rather than as the
     * bar being lit red or green. Above one they are broad fields that overlap
     * everywhere, so what is seen is the mix and not the shapes.
     *
     * Every light but the last, which [washLights] builds as the white
     * highlight. Blowing that one up whitens the wash rather than colouring it,
     * which is the opposite of what a caller reaching for this wants.
     */
    colourSpread: Float = 1f
) {
    // One clock, read at a different rate and offset per light. Cheaper than an
    // animation each, and it keeps them on the same timebase.
    //
    // NetDoctor runs this on `rememberInfiniteTransition`, and that is what it costs here that it
    // does not cost there. An infinite transition asks the window for a frame every vsync for as
    // long as it is composed -- whatever its target, and even if nobody reads its value -- and
    // this wash sits at the bottom of the config screen the entire time that screen is open.
    // Measured on the reporting device: 371 frames in six idle seconds, 50th-percentile frame
    // 34ms, on the one screen that also has to scroll a list of hundreds.
    //
    // Freezing the drift unless the tunnel was coming up was the first answer to that, and it
    // bought the frames by taking the movement out of the two states the bar is actually in --
    // green while it carries and red while it is down looked painted rather than lit. So the
    // clock is thrown away instead of the movement:
    //
    //   * it advances on `delay`, which requests no frame at all -- the loop only writes a float;
    //   * it is read inside the Canvas's draw lambda below, so a tick invalidates ONE display
    //     list rather than recomposing anything;
    //   * and it ticks at [TICK_MS] rather than at vsync, which it can afford because a cycle
    //     here is three minutes.
    // Rising forever, and deliberately NOT wrapped at [CYCLE_MS].
    //
    // `noise` has no period -- its lattice is hashed, so noise(CYCLE * 0.55) and noise(0) are
    // unrelated numbers -- and a clock that returns to zero therefore teleports every light at
    // once. Replaying this file's own noise over the real geometry: a 617px jump every three
    // minutes, against per-tick steps of about 20. The infinite transition did that too
    // (`RepeatMode.Restart`), and it went unseen only because the drift used to run for the few
    // seconds a tunnel takes to come up rather than for as long as the screen is open.
    //
    // Nothing has to be wrapped to keep the precision, which was the only reason to: the same
    // replay in Float, at a 24-hour session, puts the largest step at 28px and the clock's rate
    // 0.7% off ideal. It degrades by running imperceptibly fast, never by jumping.
    //
    // And only while it can be seen. The tick is a `delay` loop, which -- unlike a frame-driven
    // animation -- does not stop when the app goes to the background or this page is parked out of
    // sight: it went on writing a float fifteen times a second for as long as the tunnel kept the
    // process alive. Paused, it picks up from where the drift stopped, so nothing jumps.
    // In performance mode the drift is drawn once, static: the loop below writes a float every
    // tick and each tick invalidates the Canvas's display list, which is frame budget an old
    // GPU does not have. The lights still render — the clock simply stays at 0.
    val animate = !com.mlmvpn.scanner.utils.Perf.isLowEnd(
        androidx.compose.ui.platform.LocalContext.current
    )
    val clock = remember { mutableFloatStateOf(0f) }
    // Keyed on `animate` as well as visibility: flipping performance mode while this screen is
    // open starts or stops the loop in place instead of at the next visit.
    com.mlmvpn.scanner.ui.LaunchedWhileVisible(animate) {
        if (!animate) return@LaunchedWhileVisible
        // Read off the wall clock rather than accumulated per tick, so a tick the dispatcher was
        // late for costs one longer step instead of permanently slowing the drift down.
        val resumeAt = clock.floatValue
        val origin = System.nanoTime()
        while (true) {
            val elapsedMs = (System.nanoTime() - origin) / 1_000_000f
            clock.floatValue = resumeAt + elapsedMs * (CYCLE / CYCLE_MS)
            delay(TICK_MS)
        }
    }

    // Colours cross-fade when the state changes, so connecting does not snap
    // from one palette to another.
    //
    // Slow on purpose, and staggered. A single fast fade reads as the screen
    // being repainted; four slow ones that arrive at slightly different times
    // read as the light in the room changing, which is what red drifting
    // through orange into green is meant to feel like. The stagger matters more
    // than the duration - in lockstep, any speed still looks like one switch.
    val faded = colors.mapIndexed { index, target ->
        animateColorAsState(
            targetValue = target,
            animationSpec = tween(
                durationMillis = COLOUR_FADE_MS + index * COLOUR_FADE_STAGGER_MS,
                easing = FastOutSlowInEasing
            ),
            label = "light$index"
        ).value
    }

    Canvas(
        modifier = modifier
            // An offscreen layer, because the mask below has to erase this
            // drawing only. Without it the blend would punch through to the
            // page behind.
            //
            // Only where there is a mask. The layer is a buffer the size of this
            // surface, allocated and composited every frame these lights move -
            // which is every frame, always - and with no mask to contain there is
            // nothing for it to do but cost that. See the fade parameter.
            .graphicsLayer {
                compositingStrategy = if (fade) {
                    CompositingStrategy.Offscreen
                } else {
                    CompositingStrategy.Auto
                }
            }
    ) {
        // Read HERE, not in the composable body. A snapshot read inside the draw lambda makes a
        // tick a draw invalidation; the same read one scope out would make it a recomposition.
        val time = clock.floatValue
        faded.forEachIndexed { index, color ->
            val seed = index * 41f
            // Three unrelated tracks: sideways, vertical, and size. Two of them
            // in step would read as a diagonal slide.
            val x = noise(time * 0.55f + seed)
            val y = noise(time * 0.40f + seed + 13f)
            val swell = noise(time * 0.30f + seed + 29f)

            // Travelling well past both edges, so a light arrives from off
            // screen and leaves again rather than turning around in view.
            val centre = Offset(
                x = size.width * (-0.25f + 1.5f * x),
                // Free to move over most of the height. Holding them low kept
                // every light in the same band, and a band is a stripe rather
                // than weather.
                y = size.height * (0.15f + 0.95f * y)
            )
            // Smaller than the surface, not larger. Blobs wider than the box
            // overlap everywhere at once and average into one flat tint - which
            // is what made the first version look like a single colour with a
            // gradient on it rather than separate lights meeting.
            val spread = if (index == faded.lastIndex) 1f else colourSpread
            val radius = size.minDimension * (0.62f + 0.28f * swell) * spread

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, Color.Transparent),
                    center = centre,
                    radius = radius
                ),
                radius = radius,
                center = centre
            )
        }

        // The mask. Alpha only - DstIn keeps what is already drawn in proportion
        // to this gradient's alpha, so the lights simply cease to exist towards
        // the top instead of turning into some colour.
        if (fade) {
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = FADE_STOPS,
                    startY = 0f,
                    endY = size.height
                ),
                blendMode = BlendMode.DstIn
            )
        }
    }
}

/**
 * How the lights fade out upward.
 *
 * Most of the height is spent invisible or nearly so: the top half contributes
 * almost nothing, and full strength only arrives at the very bottom. That is
 * what removes the boundary - there is no point at which the wash starts, it
 * only ever gets weaker until there is nothing left of it.
 */
private val FADE_STOPS = arrayOf(
    0.00f to Color.Transparent,
    0.25f to Color.Black.copy(alpha = 0.04f),
    0.45f to Color.Black.copy(alpha = 0.16f),
    0.62f to Color.Black.copy(alpha = 0.38f),
    0.78f to Color.Black.copy(alpha = 0.66f),
    0.90f to Color.Black.copy(alpha = 0.88f),
    1.00f to Color.Black
)

/**
 * Smooth value noise in one dimension, 0..1.
 *
 * A hash at each whole number, smoothstepped between them. Not simplex - that
 * earns its complexity in two dimensions and every track here is one - but it
 * has the property that matters: no period a viewer could notice, and a
 * continuous slope, so the motion never jerks.
 */
private fun noise(t: Float): Float {
    val i = floor(t)
    val f = t - i
    val eased = f * f * (3f - 2f * f)
    return hash(i) * (1f - eased) + hash(i + 1f) * eased
}

private fun hash(n: Float): Float {
    val x = sin(n * 12.9898f) * 43758.5453f
    return abs(x - floor(x))
}

/** Arbitrary length for the clock; only the rate it implies matters -- see the clock's comment. */
// How long one state's colours take to become the next state's. Long enough
// that the change is noticed rather than watched.
private const val COLOUR_FADE_MS = 2200
private const val COLOUR_FADE_STAGGER_MS = 320

private const val CYCLE = 60f


/**
 * Three minutes per cycle.
 *
 * Slow enough that the movement is felt rather than watched, which is the
 * difference between water and an animation.
 */
private const val CYCLE_MS = 180_000

/**
 * How often the clock advances: about fifteen times a second, not sixty.
 *
 * Affordable precisely because [CYCLE_MS] is three minutes. Working the drift through: a light's
 * horizontal track advances one noise unit every 5.5 seconds, smoothstep peaks at a slope of 1.5,
 * and a centre travels 1.5x the surface width across the unit interval -- so on a bar around a
 * thousand pixels wide the centre moves about 6px between ticks typically and 26px at the rare
 * steepest moment. Each light is a radial gradient whose falloff is some four hundred pixels
 * wide and which, by design, has no edge anywhere: there is nothing for a step that size to
 * step. Radius and vertical drift are slower again, single pixels per tick.
 *
 * Four times fewer frames than vsync, and each one a redraw of a 132dp box rather than a pass
 * over the tree.
 */
private const val TICK_MS = 64L

/**
 * The four lights a two-colour wash is actually made of.
 *
 * Reading Samsung's screens frame by frame, what looked like two colours is
 * four things:
 *
 *  - the two colours themselves, in separate places and moving apart;
 *  - a light carrying the blend of them, which is what fills the seam where
 *    they meet so the change from one to the other is gradual rather than a
 *    boundary;
 *  - and a white one. This is the piece that is easy to miss and the reason
 *    their wash looks lit rather than painted: bright areas move through it,
 *    so the surface has highlights instead of being one flat tint.
 *
 * Alphas differ per light on purpose. Equal weights average out to a single
 * muddy colour wherever they overlap, which is exactly what a wash must not do.
 */
fun washLights(first: Color, second: Color): List<Color> = listOf(
    first.copy(alpha = 0.42f),
    second.copy(alpha = 0.40f),
    blend(first, second).copy(alpha = 0.34f),
    Color.White.copy(alpha = 0.34f)
)

/**
 * The same four lights, turned up for a small surface.
 *
 * [washLights] is mixed for a page: strong enough to catch across a room, weak
 * enough to read a caption over half a screen of it. In sixty dp of bar that
 * same mix is a hint of colour - the state it is describing, red through amber
 * to green, cannot be told apart at a glance, which is the one thing the wash is
 * there to do. Roughly double, and the white light left where it is: raising
 * that one whitens the lot rather than colouring it.
 */
fun strongWashLights(first: Color, second: Color): List<Color> = listOf(
    first.copy(alpha = 0.82f),
    second.copy(alpha = 0.78f),
    blend(first, second).copy(alpha = 0.62f),
    Color.White.copy(alpha = 0.30f)
)

private fun blend(from: Color, to: Color) = Color(
    red = (from.red + to.red) / 2f,
    green = (from.green + to.green) / 2f,
    blue = (from.blue + to.blue) / 2f
)
