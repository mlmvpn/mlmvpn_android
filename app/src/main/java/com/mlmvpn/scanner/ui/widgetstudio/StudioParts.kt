package com.mlmvpn.scanner.ui.widgetstudio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.utils.AppLocaleManager
import com.mlmvpn.scanner.widget.MethodView
import com.mlmvpn.scanner.widget.WidgetLive
import com.mlmvpn.scanner.widget.studio.StudioPainter
import com.mlmvpn.scanner.widget.studio.StudioSize
import com.mlmvpn.scanner.widget.studio.WidgetTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sin

/** The state a preview shows: the real one, or a sample of each state for the designer. */
enum class PreviewState { LIVE, IDLE, CONNECTING, CONNECTED }

/** The widget's size in dp, for its preview: the shape it takes on a phone's home screen. */
fun StudioSize.previewDp(): Pair<Float, Float> = when (this) {
    StudioSize.SMALL -> 158f to 158f
    StudioSize.MEDIUM -> 330f to 150f
    StudioSize.LARGE -> 330f to 330f
}

/**
 * A believable session for the designer's "connected" preview: a German exit twelve minutes in, with
 * a minute of traffic on the chart. Never shown on a placed widget -- those show the truth.
 */
fun sampleView(state: PreviewState, template: WidgetTemplate): MethodView {
    val now = System.currentTimeMillis()
    return when (state) {
        PreviewState.IDLE -> MethodView(WidgetLive.Phase.IDLE)
        PreviewState.CONNECTING -> MethodView(WidgetLive.Phase.CONNECTING)
        PreviewState.CONNECTED, PreviewState.LIVE -> {
            val down = LongArray(WidgetLive.HISTORY) { i -> (1_100_000 + 700_000 * sin(i / 4.0) + 300_000 * sin(i / 1.6)).toLong().coerceAtLeast(0) }
            val up = LongArray(WidgetLive.HISTORY) { i -> (240_000 + 130_000 * sin(i / 3.0 + 1)).toLong().coerceAtLeast(0) }
            MethodView(
                WidgetLive.Phase.CONNECTED,
                since = now - 754_000,
                country = template.country ?: "DE",
                traffic = WidgetLive.Traffic(1_240_000_000, 260_000_000, down.last(), up.last(), down, up),
            )
        }
    }
}

/**
 * A design drawn by the same painter the home screen gets, so the preview IS the widget. Drawn off
 * the main thread, and only after the design has stopped changing for a moment -- a slider being
 * dragged asks for a picture every frame.
 */
@Composable
fun TemplatePreview(
    template: WidgetTemplate,
    state: PreviewState,
    modifier: Modifier = Modifier,
    /** The width the picture is drawn for; the height follows the widget's own shape. */
    width: Dp? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // The live state is followed only by a live preview: collecting the traffic is what keeps the
    // one-second sampler running, and a sample preview has no use for it.
    val liveConn = if (state == PreviewState.LIVE) WidgetLive.conn.collectAsState().value else null
    val liveTraffic = if (state == PreviewState.LIVE) WidgetLive.traffic.collectAsState().value else null
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val (wDp, hDp) = template.size.previewDp()
    LaunchedEffect(template, state, liveConn, liveTraffic) {
        delay(40)
        val view = if (liveConn != null) MethodView.of(template.method, liveConn, liveTraffic ?: WidgetLive.Traffic())
            else sampleView(state, template)
        val rtl = AppLocaleManager.isFarsi()
        bitmap = withContext(Dispatchers.Default) {
            runCatching { StudioPainter.paint(context, template, view, wDp, hDp, density, rtl) }.getOrNull()
        }
    }
    val bmp = bitmap
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .aspectRatio(wDp / hDp),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = template.name.ifBlank { template.style.title },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(wDp / hDp),
            )
        }
    }
}

/** A small three-way switch for the preview: «قطع» «در حال اتصال» «متصل». */
@Composable
fun PreviewStateChips(state: PreviewState, onChange: (PreviewState) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(
            PreviewState.IDLE to tr("قطع", "Off"),
            PreviewState.CONNECTING to tr("در حال اتصال", "Connecting"),
            PreviewState.CONNECTED to tr("متصل", "Connected"),
        ).forEach { (s, label) ->
            val on = s == state
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onChange(s) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.White else Ios.SecondaryLabel, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/**
 * The backdrop a preview sits on: a dusk-coloured "home screen", so glass and transparency read as
 * they will over a wallpaper rather than over the studio's own page.
 */
@Composable
fun PreviewStage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1B2A4A), Color(0xFF3B2A5C), Color(0xFF7A4A5E), Color(0xFF233B5A)),
                )
            )
            .border(0.7.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A gallery card: a preset's sample picture and its name under it. */
@Composable
fun PresetCard(template: WidgetTemplate, title: String, onClick: () -> Unit, cardWidth: Dp = 150.dp) {
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PreviewStage(modifier = Modifier.height(cardWidth * 0.82f)) {
            TemplatePreview(template, PreviewState.CONNECTED, width = cardWidth - 30.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(title, color = Ios.Label, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A colour swatch; a ring marks the chosen one. */
@Composable
fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit, size: Dp = 34.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .border(if (selected) 2.5.dp else 0.7.dp, if (selected) Color.White else Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
            .padding(if (selected) 4.dp else 0.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(color))
            .clickable(onClick = onClick),
    )
}
