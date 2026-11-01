package com.mlmvpn.scanner.ui.emergency

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The pieces the two emergency engine screens are built from.
//
// Both «ضد فیلتر SNI» and «گوگل اسکریپت» are the same shape -- one big dial, a status line under
// it, inset grouped cards, action rows and grey footers -- and before this each kept its own
// private copy of every one of them. Three magic sizes and a colour ramp duplicated across two
// files is how two screens that are supposed to look identical stop looking identical, one edit
// at a time.
// =================================================================================================

/**
 * The connect control the emergency engines share.
 *
 * There is one of these on the transport screens too ([com.mlmvpn.scanner.ui.tunnel.TransportScreen]'s
 * private ConnectDial), and the numbers here are its numbers: a 196dp halo, a 168dp glass ring,
 * a 134dp pressable disc. That is the point of the file. Moving between «ضد فیلتر SNI», «گوگل
 * اسکریپت» and MASQUE must not move the button out from under the thumb, and two screens each
 * keeping a private copy of three magic sizes is how that drifts apart one edit at a time.
 *
 * Every layer is a FIXED size. Both engines' old dials animated a scale on the disc for as long
 * as the engine was up; `Modifier.scale` on a dp size is a LAYOUT value, so every frame of it
 * re-measured the column and nudged everything below the button. The sweep below is drawn inside
 * a fixed-size Canvas, so it cannot move anything, and it exists only while something is actually
 * starting -- an `infiniteRepeatable` asks for a frame every vsync for as long as it is composed.
 */
internal enum class DialState { IDLE, STARTING, RUNNING, FAILED }

/**
 * The accent for a state.
 *
 * [idleAccent] is the colour of the feature's own home-screen tile, which is what every transport
 * screen does: it is the thread between the icon you tapped and the page you land on.
 *
 * A failure is ORANGE rather than the app's usual red. Both emergency tiles are themselves red or
 * near it, so a red failure state would be indistinguishable from the resting state on exactly
 * the two screens where those need telling apart.
 */
@Composable
internal fun dialAccent(state: DialState, idleAccent: Color): Color = when (state) {
    DialState.RUNNING -> Ios.Green
    DialState.FAILED -> Ios.Orange
    else -> idleAccent
}

@Composable
internal fun EmergencyDial(
    state: DialState,
    idleAccent: Color,
    idleIcon: ImageVector,
    idleLabel: String,
    runningLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Icon shown while running. Defaults to the plug, which reads as "pull this out". */
    runningIcon: ImageVector = Icons.Default.Power,
) {
    val accent = dialAccent(state, idleAccent)
    val working = state == DialState.STARTING

    val spin = if (working) {
        rememberInfiniteTransition(label = "emergency-dial").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "spin",
        ).value
    } else {
        0f
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(196.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (state == DialState.RUNNING) 0.16f else 0.07f))
        )
        Box(
            modifier = Modifier
                .size(168.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.07f))
                .border(1.dp, accent.copy(alpha = 0.28f), CircleShape)
        )

        // Indeterminate on purpose. Neither engine reports progress of its own -- one is a process
        // spawn and a port check, the other is a service handshake -- and a bar that moves on a
        // timer while nothing happens is worse than no bar.
        if (working) {
            Canvas(modifier = Modifier.size(168.dp).rotate(spin)) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2f
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 96f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(134.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.62f))
                    )
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (state == DialState.RUNNING) runningIcon else idleIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state == DialState.RUNNING) runningLabel else idleLabel,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The one line under the dial: what the engine is doing, and what that means for the user.
 *
 * [headline] carries the state in one word and [sub] the consequence, because a status that only
 * says "failed" leaves the user with nothing to do next.
 */
@Composable
internal fun EmergencyStatusLine(
    state: DialState,
    idleAccent: Color,
    headline: String,
    sub: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            headline,
            color = if (state == DialState.IDLE) Ios.SecondaryLabel else dialAccent(state, idleAccent),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (sub != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                sub,
                color = Ios.SecondaryLabel,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
    // Every method page shows what the app-wide settings do to its connection.
    com.mlmvpn.scanner.ui.RouteChips()
}


// -------------------------------------------------------------------------------------------
// Rows and text
// -------------------------------------------------------------------------------------------

/**
 * Latency, in the app's own bands.
 *
 * [com.mlmvpn.scanner.ui.tlsPing] returns -1 when the TLS handshake never completed, which is not
 * a slow route but an unreachable one -- and on both of these screens that distinction is the
 * whole point of measuring.
 */
@Composable
internal fun pingTone(ms: Int): Color = when {
    ms <= 0 -> Ios.Red
    ms <= 200 -> Ios.Green
    else -> Ios.Yellow
}

/** Persian digits when the app is in Persian; left alone in English. */
internal fun faDigits(text: String): String {
    if (!com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()) return text
    val digits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    return buildString {
        for (c in text) append(if (c in '0'..'9') digits[c - '0'] else c)
    }
}

internal fun openExternalUrl(context: android.content.Context, url: String) {
    try {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url),
        ).apply { flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK }
        context.startActivity(intent)
    } catch (e: Exception) {
        shortToast(context, S(R.string.link_url, url))
    }
}

internal fun shortToast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}
