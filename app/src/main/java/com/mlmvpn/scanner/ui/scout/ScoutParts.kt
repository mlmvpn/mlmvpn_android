package com.mlmvpn.scanner.ui.scout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.scout.Evidence
import com.mlmvpn.scanner.engines.scout.FamState
import com.mlmvpn.scanner.engines.scout.LineFacts
import com.mlmvpn.scanner.engines.scout.MethodResult
import com.mlmvpn.scanner.engines.scout.Numbers
import com.mlmvpn.scanner.engines.scout.ScoutRegistry
import com.mlmvpn.scanner.engines.scout.ScoutStatus
import com.mlmvpn.scanner.engines.scout.Step
import com.mlmvpn.scanner.engines.scout.Tri
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.home.AppIconTile
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import java.util.Locale

internal val CardShape22 = RoundedCornerShape(22.dp)

// ---- words -------------------------------------------------------------------------------------

internal fun statusLabel(s: ScoutStatus): String = when (s) {
    ScoutStatus.WAITING -> tr("در انتظار", "Waiting")
    ScoutStatus.CHECKING -> tr("در حال بررسی", "Checking")
    ScoutStatus.WORKS -> tr("کار می‌کند", "Works")
    ScoutStatus.WEAK -> tr("ضعیف", "Weak")
    ScoutStatus.FAILED -> tr("کار نمی‌کند", "Does not work")
    ScoutStatus.NEEDS_SETUP -> tr("راه‌اندازی نشده", "Not set up")
    ScoutStatus.NOT_INSTALLED -> tr("نصب نیست", "Not installed")
    ScoutStatus.SERVICE_DOWN -> tr("سرویس در دسترس نیست", "Service unavailable")
    ScoutStatus.NOT_APPLICABLE -> tr("مناسب این خط نیست", "Not for this line")
    ScoutStatus.ON_DEMAND -> tr("تست با اجازهٔ شما", "Test on request")
    ScoutStatus.CANCELLED -> tr("لغو شد", "Cancelled")
    ScoutStatus.INCONCLUSIVE -> tr("نامشخص", "Inconclusive")
    ScoutStatus.UPCOMING -> tr("به‌زودی", "Soon")
}

@Composable
internal fun statusColor(s: ScoutStatus): Color = when (s) {
    ScoutStatus.WORKS -> Ios.Green
    ScoutStatus.WEAK -> Ios.Orange
    ScoutStatus.FAILED -> Ios.Red
    ScoutStatus.CHECKING, ScoutStatus.ON_DEMAND -> Ios.Blue
    ScoutStatus.SERVICE_DOWN -> Ios.Yellow
    else -> Ios.Gray
}

internal fun stepLabel(s: Step?): String = when (s) {
    Step.PREPARING, null -> tr("آماده‌سازی", "Preparing")
    Step.CONNECTING -> tr("در حال اتصال", "Connecting")
    Step.MEASURING -> tr("سنجش", "Measuring")
}

/** The method's name as the user knows it: its home icon's label, with two clearer exceptions. */
@Composable
internal fun methodTitle(id: String): String = when (id) {
    "panels" -> tr("پنل‌های کلادفلر", "Cloudflare panels")
    "myconfigs" -> tr("کانفیگ‌های من", "My configs")
    else -> ScoutRegistry.byId(id)?.homeApp()?.let { stringResource(it.labelRes) } ?: id
}

internal fun ms(v: Long?) = v?.let { "$it ms" }
internal fun mbps(v: Double?) = v?.let { String.format(Locale.US, "%.1f Mbps", it) }
internal fun stab(n: Numbers) = if (n.stabilityOk != null && n.stabilityTotal != null)
    tr("${n.stabilityOk} از ${n.stabilityTotal}", "${n.stabilityOk} of ${n.stabilityTotal}") else null

internal fun flag(cc: String?): String =
    if (cc == null || cc.length != 2 || !cc.all { it.isLetter() }) "" else cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

internal fun countryLabel(cc: String?): String? = cc?.let {
    flag(it) + " " + Locale("", it).getDisplayCountry(com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale()).ifBlank { it }
}

/** One short line under a method's name: the numbers when it works, the live step while it runs, else why. */
internal fun methodLine(m: MethodResult): String {
    val a = ScoutRegistry.byId(m.id)
    return when {
        m.status == ScoutStatus.CHECKING || (m.step != null && !m.status.finished) ->
            listOfNotNull(stepLabel(m.step), m.stepNote).joinToString(" · ")
        m.step != null && m.status.healthy -> m.stepNote ?: stepLabel(m.step)
        m.status.healthy && a?.evidence == Evidence.HANDSHAKE -> tr("تأیید تا مرحلهٔ دست‌دهی", "Confirmed up to the handshake")
        m.status.healthy -> {
            val n = m.best?.numbers ?: Numbers()
            listOfNotNull(
                m.best?.label?.takeIf { m.variants.size > 1 },
                ms(n.latencyMs)?.let { tr("تأخیر $it", "delay $it") },
                stab(n),
                mbps(n.mbps),
                n.exitCountry?.let { flag(it) },
            ).joinToString(" · ").ifEmpty { m.reason.orEmpty() }.let { s ->
                if (m.status == ScoutStatus.WEAK && m.reason != null) "${m.reason} · $s" else s
            }
        }
        else -> m.reason.orEmpty()
    }
}

internal fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
    val m = ((now - t) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> tr("همین الان", "just now")
        m < 60 -> tr("$m دقیقه پیش", "$m min ago")
        m < 24 * 60 -> tr("${m / 60} ساعت پیش", "${m / 60} h ago")
        else -> tr("${m / (24 * 60)} روز پیش", "${m / (24 * 60)} days ago")
    }
}

internal fun mmss(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return String.format(Locale.US, "%d:%02d", s / 60, s % 60) }

internal fun megabytes(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)

// ---- the logo, with its radar ---------------------------------------------------------------

/** Scout's logo, with radar rings that breathe slowly at rest and sweep outward while it works. */
@Composable
internal fun ScoutLogo(running: Boolean, size: Dp = 128.dp) {
    val t = rememberInfiniteTransition(label = "radar")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (running) 1_800 else 3_600, easing = LinearEasing)), label = "phase")
    val ring = Ios.Blue
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val max = this.size.minDimension / 2
            val inner = max * 0.48f
            for (k in 0 until 3) {
                val p = (phase + k / 3f) % 1f
                val r = inner + (max - inner) * p
                drawCircle(ring.copy(alpha = (1f - p) * if (running) 0.55f else 0.30f), r, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Image(
            painterResource(R.drawable.ic_app_scout), contentDescription = null,
            modifier = Modifier.size(size * 0.6f).clip(RoundedCornerShape(size * 0.14f)),
        )
    }
}

/** Progress that only moves when work is really done: done / total of the run's own tasks. */
@Composable
internal fun ProgressRing(fraction: Float, center: @Composable () -> Unit) {
    val anim by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "ring")
    val track = Color.White.copy(alpha = 0.12f)
    val fill = Ios.Blue
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(150.dp)) {
            val w = 9.dp.toPx()
            val d = this.size.minDimension - w
            val tl = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(w))
            drawArc(fill, -90f, 360f * anim, false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
        }
        center()
    }
}

// ---- status pill and method row ----------------------------------------------------------------

@Composable
internal fun StatusPill(s: ScoutStatus) {
    val c by animateColorAsState(statusColor(s), tween(400), label = "pill")
    val pulse = if (s == ScoutStatus.CHECKING) {
        val t = rememberInfiniteTransition(label = "checking")
        t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a").value
    } else 1f
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.18f * pulse + 0.04f)).padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(statusLabel(s), color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun MethodIcon(id: String, size: Dp) {
    val app = ScoutRegistry.byId(id)?.homeApp()
    if (app != null) AppIconTile(app = app, size = size)
    else Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(Ios.Gray))
}

@Composable
internal fun MethodRow(m: MethodResult, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MethodIcon(m.id, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(methodTitle(m.id), color = Ios.Label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = methodLine(m)
            if (line.isNotBlank()) Text(line, color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(if (m.step != null && !m.status.finished) ScoutStatus.CHECKING else m.status)
    }
}

/** A card of rows that slide in softly the first time each one appears. */
@Composable
internal fun MethodCard(items: List<MethodResult>, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(CardShape22)) {
        items.forEachIndexed { i, m ->
            androidx.compose.runtime.key(m.id) {
                val shown = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(shown, enter = fadeIn(tween(350)) + expandVertically(tween(350))) {
                    Column {
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 62.dp).height(0.5.dp).background(Ios.Separator))
                        MethodRow(m) { onOpen(m.id) }
                    }
                }
            }
        }
    }
}

/** A section that folds away: the failures and the set-up list stay one tap from view. */
@Composable
internal fun FoldSection(title: String, items: List<MethodResult>, startOpen: Boolean = false, onOpen: (String) -> Unit) {
    if (items.isEmpty()) return
    var open by remember(title) { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 0f else if (LocalLayoutDirection.current == LayoutDirection.Rtl) 90f else -90f, tween(250), label = "fold")
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$title (${items.size})", color = Ios.SecondaryLabel, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ExpandMore, null, tint = Ios.SecondaryLabel, modifier = Modifier.size(18.dp).rotate(turn))
    }
    AnimatedVisibility(open) { MethodCard(items, onOpen) }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, color = Ios.SecondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 7.dp))
}

// ---- «اینترنت شما» in brief ----------------------------------------------------------------------

private data class Chip(val label: String, val value: String?, val good: Boolean?)

private fun triWord(t: Tri?, yes: String, no: String): Pair<String?, Boolean?> = when (t) {
    null -> null to null
    Tri.YES -> yes to true
    Tri.NO -> no to false
    Tri.UNKNOWN -> tr("نامشخص", "unknown") to null
}

private fun famWord(f: FamState?): Pair<String?, Boolean?> = when (f) {
    null -> null to null
    FamState.WORKS -> "✓" to true
    FamState.BROKEN -> tr("کار نمی‌کند", "broken") to false
    FamState.NONE -> tr("ندارد", "none") to null
}

private fun chips(l: LineFacts): List<Chip> = buildList {
    val net = when (l.netType) {
        "wifi" -> "Wi-Fi"
        "cell" -> com.mlmvpn.scanner.engines.scout.ScoutEngine.operatorName(l.operator)
        "ethernet" -> "Ethernet"
        null -> null
        else -> tr("شبکه", "Network")
    }
    add(Chip(tr("شبکه", "Network"), net, null))
    famWord(l.v4).let { add(Chip("IPv4", it.first, it.second)) }
    famWord(l.v6).let { add(Chip("IPv6", it.first, it.second)) }
    triWord(l.udp, tr("باز", "open"), tr("بسته", "blocked")).let { add(Chip("UDP", it.first, it.second)) }
    triWord(l.quic, tr("کار می‌کند", "works"), tr("بسته", "blocked")).let { add(Chip("QUIC", it.first, it.second)) }
    triWord(l.dnsTampered, tr("دستکاری‌شده", "tampered"), tr("سالم", "clean")).let { add(Chip("DNS", it.first, it.second?.not())) }
    triWord(l.sniFilter, tr("دارد", "yes"), tr("ندارد", "no")).let { add(Chip(tr("فیلتر نام سایت", "SNI filter"), it.first, it.second?.not())) }
    add(Chip(tr("تأخیر خط", "Line delay"), ms(l.rttMs), null))
    add(Chip(tr("سرعت مستقیم", "Direct speed"), mbps(l.mbps), null))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LineCard(l: LineFacts, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(CardShape22).clickable(onClick = onOpen).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("اینترنت شما", "Your internet"), color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = Ios.Chevron, modifier = Modifier.size(18.dp)
                .scale(if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f, 1f))
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips(l).forEach { c -> LineChip(c) }
        }
    }
}

@Composable
private fun LineChip(c: Chip) {
    val color = when (c.good) { true -> Ios.Green; false -> Ios.Orange; null -> Ios.Label }
    val pending = c.value == null
    val a = if (pending) {
        val t = rememberInfiniteTransition(label = "chip")
        t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "c").value
    } else 1f
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.label, color = Ios.SecondaryLabel, fontSize = 12.sp)
        Spacer(Modifier.width(5.dp))
        Text(c.value ?: "…", color = color.copy(alpha = a), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A big, plain number with its honest label underneath. */
@Composable
internal fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Ios.Label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, color = Ios.SecondaryLabel, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
internal fun PillButton(text: String, color: Color, filled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Color.White else color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))
