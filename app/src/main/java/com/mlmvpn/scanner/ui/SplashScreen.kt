package com.mlmvpn.scanner.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/**
 * Opening animation, built around the app's own mark: the ring draws itself, the M appears,
 * and the small circle sitting in the ring's bottom gap becomes a rocket that ignites and
 * climbs away.
 *
 * The mark is drawn with Canvas rather than shown as a bitmap because the whole idea depends
 * on one of its parts — that bottom dot — being animatable on its own.
 *
 * Motion is sold three ways at once, which is what separates this from a sliding icon: the
 * rocket rises, the exhaust flickers frame to frame, and the starfield streams *downward*
 * past it. The last one does most of the work — an object moving up against a still background
 * reads as an object being dragged; the same object against descending stars reads as flight.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val arcSweep = remember { Animatable(0f) }
    val markAlpha = remember { Animatable(0f) }
    val dotScale = remember { Animatable(0f) }
    val morph = remember { Animatable(0f) }      // dot → rocket
    val ignite = remember { Animatable(0f) }     // engine lighting up
    val launch = remember { Animatable(0f) }     // climb
    val taglineAlpha = remember { Animatable(0f) }
    val exitAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        arcSweep.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
        markAlpha.animateTo(1f, tween(280))
        dotScale.animateTo(1f, tween(240, easing = FastOutSlowInEasing))

        // Slow enough to actually be read as a transformation. The first version ran this in
        // 320 ms with the dot vanishing on frame one, which just looked like a swap.
        morph.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))

        // Engine first, movement second — a rocket that moves before it lights looks pushed.
        ignite.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        delay(120)

        taglineAlpha.animateTo(1f, tween(500))
        launch.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))

        delay(220)
        exitAlpha.animateTo(0f, tween(340))
        onFinished()
    }

    // Free-running clocks: flame flicker and the starfield must not stop between phases.
    val loop = rememberInfiniteTransition(label = "splashLoop")
    val flicker by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse),
        label = "flicker",
    )
    val starClock by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "stars",
    )

    val stars = remember {
        val r = Random(7)
        List(46) {
            Star(
                x = r.nextFloat(),
                phase = r.nextFloat(),
                radius = 0.8f + r.nextFloat() * 1.9f,
                speed = 0.55f + r.nextFloat() * 0.9f,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A101C), BgDark, Color(0xFF0A101C))))
            .alpha(exitAlpha.value),
        contentAlignment = Alignment.Center,
    ) {
        // Starfield spans the whole screen so the streaks read as depth, not a decorated box.
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (launch.value <= 0f && ignite.value <= 0f) return@Canvas
            val intensity = maxOf(ignite.value * 0.35f, launch.value)
            stars.forEach { s ->
                val travel = ((starClock * s.speed + s.phase) % 1f)
                val y = travel * (size.height + 120f) - 60f
                val a = intensity * 0.75f * sin(travel * Math.PI).toFloat()
                if (a > 0.01f) {
                    // A short streak rather than a dot: length tracks speed, so the field has
                    // parallax instead of looking like uniform snow.
                    val len = 6f + s.speed * 22f * launch.value
                    drawLine(
                        color = Color(0xFFAECBFA).copy(alpha = a),
                        start = Offset(s.x * size.width, y),
                        end = Offset(s.x * size.width, y + len),
                        strokeWidth = s.radius,
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            Canvas(modifier = Modifier.size(240.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.minDimension * 0.30f
                val stroke = size.minDimension * 0.055f

                // ---- the mark ------------------------------------------------------------
                // Ring with a gap at the bottom, exactly where the dot lives.
                val ringAlpha = (1f - morph.value).coerceIn(0f, 1f)
                if (ringAlpha > 0f) {
                    drawArc(
                        color = Primary.copy(alpha = ringAlpha),
                        startAngle = 118f,
                        sweepAngle = 304f * arcSweep.value,
                        useCenter = false,
                        topLeft = Offset(cx - r, cy - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                    drawM(cx, cy, r, stroke, Primary.copy(alpha = markAlpha.value * ringAlpha))
                }

                // ---- the dot, and what it becomes ---------------------------------------
                val dotY = cy + r * 0.98f
                // Climbs clear of the canvas so the exhaust never reaches the tagline.
                val rocketY = dotY - (dotY + size.height * 0.22f) * launch.value

                // The dot and the rocket overlap through the whole morph: the ring stays until
                // the rocket is already legible, and only then hands over. Cross-fading two
                // shapes that share a centre is what makes it read as one thing becoming
                // another instead of one thing replacing another.
                val m = morph.value
                if (m < 0.85f) {
                    // The dot swells and thins as the rocket grows out of it.
                    val a = (1f - m / 0.85f).coerceIn(0f, 1f)
                    drawCircle(
                        color = Primary.copy(alpha = a),
                        radius = stroke * (1.15f + 1.5f * m) * dotScale.value,
                        center = Offset(cx, dotY),
                        style = Stroke(width = stroke * 0.62f * (1f - m * 0.55f)),
                    )
                }

                if (m > 0f) {
                    translate(left = cx, top = rocketY) {
                        val scale = stroke * (0.95f + 1.35f * m)
                        drawFlame(scale, ignite.value, flicker, launch.value)
                        // Fades in over the first two thirds, so nose and fins emerge from
                        // inside the dot rather than popping in complete.
                        drawRocket(scale, Primary, (m / 0.66f).coerceAtMost(1f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(64.dp))

            Text(
                text = "با MLMVPN همیشه، در هر شرایطی، متصل بمان",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 36.dp)
                    .alpha(taglineAlpha.value),
            )
        }
    }
}

private data class Star(val x: Float, val phase: Float, val radius: Float, val speed: Float)

/** The mark's M, drawn to sit inside the ring. */
private fun DrawScope.drawM(cx: Float, cy: Float, r: Float, stroke: Float, color: Color) {
    if (color.alpha <= 0f) return
    val half = r * 0.52f
    val top = cy - r * 0.46f
    val bottom = cy + r * 0.40f
    val path = Path().apply {
        moveTo(cx - half, bottom)
        lineTo(cx - half, top)
        lineTo(cx, bottom * 0.98f)
        lineTo(cx + half, top)
        lineTo(cx + half, bottom)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        ),
    )
}

/**
 * Rocket drawn in the mark's own line language — same stroke weight and rounded caps — so the
 * transformation looks like the logo changing rather than a stock icon appearing.
 */
private fun DrawScope.drawRocket(s: Float, color: Color, alpha: Float) {
    val c = color.copy(alpha = alpha.coerceIn(0f, 1f))
    val w = s * 0.62f      // half body width
    val h = s * 1.25f      // half body height

    val body = Path().apply {
        moveTo(0f, -h * 1.35f)                       // nose
        cubicTo(w * 1.1f, -h * 0.55f, w, h * 0.35f, w * 0.72f, h)
        lineTo(-w * 0.72f, h)
        cubicTo(-w, h * 0.35f, -w * 1.1f, -h * 0.55f, 0f, -h * 1.35f)
        close()
    }
    drawPath(body, c, style = Stroke(width = s * 0.28f, join = androidx.compose.ui.graphics.StrokeJoin.Round))

    // Fins
    val finL = Path().apply {
        moveTo(-w * 0.78f, h * 0.32f)
        lineTo(-w * 1.75f, h * 1.05f)
        lineTo(-w * 0.72f, h * 0.98f)
        close()
    }
    val finR = Path().apply {
        moveTo(w * 0.78f, h * 0.32f)
        lineTo(w * 1.75f, h * 1.05f)
        lineTo(w * 0.72f, h * 0.98f)
        close()
    }
    drawPath(finL, c, style = Stroke(width = s * 0.22f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawPath(finR, c, style = Stroke(width = s * 0.22f, join = androidx.compose.ui.graphics.StrokeJoin.Round))

    // Porthole — the detail that makes it read as a rocket at small sizes.
    drawCircle(color = c, radius = s * 0.30f, center = Offset(0f, -h * 0.28f), style = Stroke(width = s * 0.20f))
}

/** Exhaust: two nested teardrops plus sparks, all re-jittered every frame. */
private fun DrawScope.drawFlame(s: Float, ignite: Float, flicker: Float, launch: Float) {
    if (ignite <= 0.01f) return

    val base = s * 1.25f                                  // bottom of the body
    val len = s * (1.15f + 1.35f * launch) * ignite * (0.86f + 0.28f * flicker)
    val w = s * 0.52f * (0.88f + 0.22f * (1f - flicker))

    fun teardrop(width: Float, length: Float) = Path().apply {
        moveTo(-width, base)
        cubicTo(-width * 0.9f, base + length * 0.45f, -width * 0.28f, base + length * 0.8f, 0f, base + length)
        cubicTo(width * 0.28f, base + length * 0.8f, width * 0.9f, base + length * 0.45f, width, base)
        close()
    }

    drawPath(teardrop(w, len), color = Color(0xFFFF8A2B).copy(alpha = 0.85f * ignite))
    drawPath(teardrop(w * 0.58f, len * 0.66f), color = Color(0xFFFFD23F).copy(alpha = 0.95f * ignite))
    drawPath(teardrop(w * 0.26f, len * 0.34f), color = Color(0xFFFFF4CC).copy(alpha = ignite))

    // Sparks falling away from the nozzle.
    val sparks = 5
    for (i in 0 until sparks) {
        val t = ((flicker + i / sparks.toFloat()) % 1f)
        val y = base + len * (0.6f + t * 0.9f)
        val x = (if (i % 2 == 0) 1f else -1f) * w * (0.25f + t * 0.75f)
        drawCircle(
            color = Color(0xFFFFB258).copy(alpha = (1f - t) * 0.8f * ignite),
            radius = s * 0.10f * (1f - t),
            center = Offset(x, y),
        )
    }
}
