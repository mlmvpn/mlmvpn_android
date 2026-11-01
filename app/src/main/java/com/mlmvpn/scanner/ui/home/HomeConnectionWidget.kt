package com.mlmvpn.scanner.ui.home

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.widget.WidgetConnect
import com.mlmvpn.scanner.widget.WidgetConnectService
import com.mlmvpn.scanner.widget.WidgetFormat
import com.mlmvpn.scanner.widget.WidgetLive
import com.mlmvpn.scanner.widget.WidgetMethods
import com.mlmvpn.scanner.widget.WidgetPainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * The connection widget at the top of the home board, iOS-style: which method is carrying the phone,
 * where it comes out, the traffic moving through it as a live chart, this session's usage, and the
 * button that ends it. Idle, it offers the last method used.
 *
 * It takes the top two rows of the first page (see BoardGrid), the way an iOS widget takes a block
 * of icon cells. Removing it -- the «−» badge while the board is being rearranged -- turns it off;
 * Settings > Display and Widget Studio turn it back on.
 */
@Composable
internal fun HomeConnectionWidget(
    editMode: Boolean,
    onOpenApp: (HomeApp) -> Unit,
    onEnterEditMode: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val conn by WidgetLive.conn.collectAsState()
    // Collected only while it is on screen and the app is in front: collecting is what keeps the
    // one-second sampler going, and a plain collect went on all night behind a stopped activity.
    val traffic by WidgetLive.traffic.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        WidgetLive.wantCountry("home", true)
        onDispose { WidgetLive.wantCountry("home", false) }
    }
    val method = conn.method?.takeIf { WidgetMethods.isMethod(it) }
    // Read off the main thread: the record's preferences file and, for a node, the node list behind
    // it took seconds on slow phones, and the home screen froze while it drew (1.2.42 #2110, #2327).
    var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    LaunchedEffect(conn.method) {
        last = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { lastMethod(context) }
    }
    val shown = method ?: last
    val app = WidgetMethods.app(shown)

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val m = last
        if (res.resultCode == Activity.RESULT_OK && m != null) WidgetConnectService.start(context, m, null)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun connectLast() {
        val m = last ?: return
        if (com.mlmvpn.scanner.data.ScanGuard.isScanBusy()) {
            android.widget.Toast.makeText(context, tr("یک اسکن در حال اجراست؛ اول آن را متوقف کنید", "A scan is running; stop it first"), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // VpnService.prepare is a call into the system that took seconds on a busy phone, with the
        // home screen frozen behind the tap (1.2.42 #2374): asked off the main thread.
        scope.launch {
            val prep = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { VpnService.prepare(context) }.getOrNull() }
            if (prep != null) runCatching { consent.launch(prep) } else WidgetConnectService.start(context, m, null)
        }
    }

    val green = Color(0xFF34C759)
    val amber = Color(0xFFFF9F0A)
    val accent = when {
        conn.up -> green
        conn.busy -> amber
        else -> Color(0xFF0A84FF)
    }
    val shape = RoundedCornerShape(26.dp)

    Box(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .shadow(10.dp, shape, clip = false)
                .frostedGlass(shape, overWallpaper = true, underScrim = false)
                .drawBehind {
                    // The accent's glow, from the button's corner: the widget's state at a glance.
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val cx = if (rtl) size.width * 0.16f else size.width * 0.84f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(accent.copy(alpha = if (conn.up) 0.30f else 0.14f), Color.Transparent),
                            center = Offset(cx, size.height * 0.55f),
                            radius = max(size.width, size.height) * 0.62f,
                        ),
                        radius = max(size.width, size.height),
                        center = Offset(cx, size.height * 0.55f),
                    )
                }
                .pointerInput(editMode, shown) {
                    detectTapGestures(
                        onTap = { if (!editMode) app?.let(onOpenApp) },
                        onLongPress = { onEnterEditMode() },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            val compact = maxHeight < 150.dp
            Column(modifier = Modifier.fillMaxSize()) {
                Header(conn, app, compact)
                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        when {
                            conn.up && !compact -> LiveChart(traffic, green, Color(0xFF64D2FF), Modifier.fillMaxSize())
                            conn.up -> Unit
                            conn.busy -> Text(
                                tr("در حال برقراری اتصال…", "Connecting…"),
                                color = amber, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                            else -> Text(
                                if (last != null) tr("آخرین روش شما آماده است؛ با یک لمس وصل شوید.", "Your last method is ready; one tap connects it.")
                                else tr("یکی از روش‌های اتصال پایین را انتخاب کنید.", "Pick one of the connection methods below."),
                                color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 20.sp,
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    PowerButton(
                        state = when {
                            conn.up -> 2
                            conn.busy -> 1
                            else -> 0
                        },
                        enabled = !editMode && (conn.busy || last != null),
                        size = if (compact) 52.dp else 64.dp,
                        onClick = {
                            if (conn.busy) WidgetConnect.disconnect(context) else connectLast()
                        },
                    )
                }
                if (conn.up) {
                    Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                    Footer(traffic)
                }
            }
        }
        // Physical top-left in both languages, where iOS puts it: TopEnd is the left in Persian.
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        if (editMode) RemoveBadge(onRemove, Modifier.align(if (rtl) Alignment.TopEnd else Alignment.TopStart))
    }
}

/** The method last connected, from the app's own record of it -- what «اتصال» brings back. */
private fun lastMethod(context: android.content.Context): String? = runCatching {
    when (val r = com.mlmvpn.scanner.data.LastEngine.read(context)) {
        is com.mlmvpn.scanner.data.LastEngine.Record.Tunnel -> r.transportId
        is com.mlmvpn.scanner.data.LastEngine.Record.OpenVpn -> "openvpn"
        is com.mlmvpn.scanner.data.LastEngine.Record.Xray -> ActiveEngines.homeOfNode(context, r.nodeId)
        null -> null
    }
}.getOrNull()?.takeIf { WidgetMethods.isMethod(it) }

@Composable
private fun Header(conn: WidgetLive.Conn, app: HomeApp?, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (app != null) AppIconTile(app = app, size = if (compact) 34.dp else 40.dp) else Box(Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app?.let { WidgetPainter.labelOf(it) } ?: "MLMVPN",
                color = Color.White, fontSize = if (compact) 15.sp else 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                conn.up -> conn.country?.let { WidgetFormat.flag(it) + " " + WidgetFormat.countryName(it) }
                    ?: tr("در حال یافتن کشور خروج…", "Finding the exit country…")
                conn.busy -> tr("در حال اتصال", "Connecting")
                app != null -> tr("آخرین روش استفاده‌شده", "Last method used")
                else -> tr("هنوز اتصالی برقرار نشده", "Not connected yet")
            }
            Text(sub, color = Color.White.copy(alpha = 0.72f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusPill(conn)
    }
}

@Composable
private fun StatusPill(conn: WidgetLive.Conn) {
    // The session clock's tick, kept down here: once a second it recomposes this pill, not the
    // whole widget around the chart.
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(conn.up, conn.since) {
        while (conn.up) {
            now.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val (color, text) = when {
        conn.up -> Color(0xFF34C759) to (tr("متصل", "Connected") + " " + WidgetFormat.clock(now.longValue - conn.since))
        conn.busy -> Color(0xFFFF9F0A) to tr("در حال اتصال", "Connecting")
        else -> Color(0xFF8E8E93) to tr("قطع", "Off")
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, style = tabular())
    }
}

@Composable
private fun Footer(traffic: WidgetLive.Traffic) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Stat("↓", WidgetFormat.rate(traffic.rxRate), Color(0xFF34C759))
        Stat("↑", WidgetFormat.rate(traffic.txRate), Color(0xFF64D2FF))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("مصرف نشست ", "Session "), color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
            Text(WidgetFormat.bytes(traffic.rx + traffic.tx), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = tabular())
        }
    }
}

@Composable
private fun Stat(arrow: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(arrow, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = tabular())
    }
}

/** Tabular figures: numbers that change every second keep their width, so nothing beside them shuffles sideways. */
@Composable
private fun tabular() = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")

/**
 * The live chart: a minute of download (filled) and upload (a thinner line), newest at the right.
 *
 * It scrolls on the display's own clock. Every frame works out how far into its second the newest
 * sample is ([WidgetLive.Traffic.atNanos] against the frame time, one time base), so the line
 * glides one step left per sample, the newest point slides in from past the right edge, and the
 * oldest slides out past the left one. Read in the draw phase: a redraw, never a recomposition.
 *
 * It used to restart an animation from a LaunchedEffect when a sample arrived. That effect runs
 * only after the frame that first draws the new sample, so that one frame showed the line a whole
 * step too far left -- a twitch once a second. The scale eases between levels for the same reason
 * instead of snapping to each new peak.
 */
@Composable
private fun LiveChart(traffic: WidgetLive.Traffic, down: Color, up: Color, modifier: Modifier) {
    // A flat line at zero looks the same at every offset: no frames are spent on an idle link.
    val moving = traffic.down.any { it > 0L } || traffic.up.any { it > 0L }
    val frameNanos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(moving) {
        while (moving) withFrameNanos { frameNanos.longValue = it }
    }
    val ceiling by animateFloatAsState(
        targetValue = chartCeiling(traffic),
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "chart scale",
    )
    Canvas(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        val w = size.width
        val h = size.height
        // A faint baseline grid.
        for (i in 1..2) {
            val y = h * i / 3f
            drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        val n = WidgetLive.HISTORY
        val top = ceiling
        val step = w / (n - 1)
        // One step right of its place as the sample lands, at its place a beat later. A late beat
        // carries the glide on a little rather than stopping it dead.
        val progress = if (traffic.atNanos == 0L || frameNanos.longValue == 0L) 1f
            else ((frameNanos.longValue - traffic.atNanos) / 1_000_000f / WidgetLive.TICK_MS).coerceIn(0f, 1.1f)
        val shift = (1f - progress) * step
        // Charts read left to right in any language: time runs that way on every meter.
        fun path(values: LongArray, close: Boolean): Path {
            val p = Path()
            if (values.size < 2) return p
            val startX = w - step * (values.size - 1) + shift
            var px = 0f
            var py = 0f
            values.forEachIndexed { i, v ->
                val x = startX + step * i
                val y = h - (v / top) * h
                if (i == 0) p.moveTo(x, y) else {
                    val mx = (px + x) / 2f
                    p.cubicTo(mx, py, mx, y, x, y)
                }
                px = x; py = y
            }
            if (close) {
                p.lineTo(px, h)
                p.lineTo(startX, h)
                p.close()
            }
            return p
        }
        if (traffic.down.size >= 2) {
            drawPath(path(traffic.down, true), Brush.verticalGradient(listOf(down.copy(alpha = 0.38f), Color.Transparent)))
            drawPath(path(traffic.down, false), down, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
        } else {
            drawLine(down.copy(alpha = 0.35f), Offset(0f, h - 2f), Offset(w, h - 2f), strokeWidth = 2f)
        }
        if (traffic.up.size >= 2) {
            drawPath(path(traffic.up, false), up.copy(alpha = 0.9f), style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/**
 * The chart's top: the peak with some headroom, rounded up to the next of a few round steps, so
 * the scale moves in clear jumps -- eased by the caller -- instead of every time the peak shifts.
 * A floor of 1 KB/s keeps a keepalive from filling the chart as if it were a download.
 */
private fun chartCeiling(traffic: WidgetLive.Traffic): Float {
    var peak = 0L
    traffic.down.forEach { peak = max(peak, it) }
    traffic.up.forEach { peak = max(peak, it) }
    val want = max(peak * 1.15f, 1024f)
    val magnitude = 10.0.pow(floor(log10(want.toDouble()))).toFloat()
    for (m in CEILING_STEPS) if (m * magnitude >= want) return m * magnitude
    return 10f * magnitude
}

private val CEILING_STEPS = floatArrayOf(1f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f, 10f)

/** 0 idle (connect), 1 connecting (cancel), 2 connected (disconnect). */
@Composable
private fun PowerButton(state: Int, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val color = when (state) {
        2 -> Color(0xFF34C759)
        1 -> Color(0xFFFF9F0A)
        else -> Color(0xFF0A84FF)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(if (state == 2) 14.dp else 4.dp, CircleShape, clip = false, ambientColor = color, spotColor = color)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(color.copy(alpha = if (enabled) 0.95f else 0.4f), color.copy(alpha = if (enabled) 0.70f else 0.3f))))
                .pointerInput(enabled) { detectTapGestures { if (enabled) onClick() } },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(size * 0.46f)) {
                val r = this.size.minDimension / 2f
                val stroke = r * 0.20f
                drawArc(
                    color = Color.White,
                    startAngle = 270f + 38f,
                    sweepAngle = 284f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                )
                drawLine(Color.White, Offset(center.x, center.y - r * 1.02f), Offset(center.x, center.y - r * 0.15f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (state) {
                2 -> tr("قطع", "Disconnect")
                1 -> tr("لغو", "Cancel")
                else -> tr("اتصال", "Connect")
            },
            color = Color.White.copy(alpha = if (enabled) 0.9f else 0.45f), fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
        )
    }
}

/** iOS's «−» on a widget while the board is being rearranged: removes it from the board. */
@Composable
private fun RemoveBadge(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .absoluteOffset(x = (-6).dp, y = (-6).dp)
            .size(24.dp)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF8E8E93))
            .pointerInput(Unit) { detectTapGestures { onRemove() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(11.dp).height(2.5.dp).clip(RoundedCornerShape(50)).background(Color.White))
    }
}
