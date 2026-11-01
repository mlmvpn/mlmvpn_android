package com.mlmvpn.scanner.ui.arena

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.arena.ArenaEngine
import com.mlmvpn.scanner.engines.arena.ArenaMode
import com.mlmvpn.scanner.engines.arena.ArenaPanels
import com.mlmvpn.scanner.engines.arena.ArenaScore
import com.mlmvpn.scanner.engines.arena.Entry
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * The race itself: grid reveal, start lights, the live track, the podium. The feeling of a race,
 * in the system's own dark palette -- neutral greys for the field, one neutral tile per car, and
 * colour only where it means something: red lights, the green GO, a red retirement, gold, silver
 * and bronze on the podium, the blue of progress.
 */
object RaceColors {
    val Top = Color(0xFF1C1C1E)
    val Bottom = Color(0xFF000000)
    val Lane = Color(0xFF2C2C2E)
    val LaneLine = Color(0x26FFFFFF)
    val Label = Color.White
    val Secondary = Color(0x99EBEBF5)
    val LightHousing = Color(0xFF0A0A0A)
    val LightOff = Color(0xFF3A1414)
    val LightOn = Color(0xFFFF3B30)
    val Go = Color(0xFF30D158)
    val Out = Color(0xFFFF453A)
    val Progress = Color(0xFF8B5CF6)
    val Gold = Color(0xFFFFD60A)
    val Silver = Color(0xFFC7C7CC)
    val Bronze = Color(0xFFC08B5C)
}

private fun nameOf(id: String) = ArenaPanels.byId(id)?.name ?: id

/** The dark stage every race scene stands on. */
@Composable
fun RaceStage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(RaceColors.Top, RaceColors.Bottom)))) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { content() }
    }
}

@Composable
private fun RaceBar(title: String, sound: Boolean, setSound: (Boolean) -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clickable(onClick = onCancel), verticalAlignment = Alignment.CenterVertically) {
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Icon(Icons.Default.ChevronLeft, null, tint = RaceColors.Progress, modifier = Modifier.size(26.dp).scale(if (rtl) -1f else 1f, 1f))
            Text(tr("لغو", "Cancel"), color = RaceColors.Progress, fontSize = 17.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(title, color = RaceColors.Label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Icon(if (sound) Icons.Default.VolumeUp else Icons.Default.VolumeOff, tr("صدا", "Sound"), tint = RaceColors.Progress,
            modifier = Modifier.size(24.dp).clickable { setSound(!sound) })
        Spacer(Modifier.width(8.dp))
    }
}

// ── grid reveal ────────────────────────────────────────────────────────────────────────────

/** Each car takes the stage in turn, then the field lines up with VS between them. */
@Composable
fun GridReveal(st: ArenaEngine.State, onDone: () -> Unit) {
    val context = LocalContext.current
    val teams = st.lanes.filter { it.lane != ArenaEngine.Lane.OUT }.ifEmpty { st.lanes }
    var index by remember { mutableStateOf(0) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        for (i in teams.indices) {
            index = i
            ArenaSounds.play(context, ArenaSounds.Cue.TICK)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(480)
        }
        index = teams.size
        delay(1200)
        onDone()
    }
    RaceStage {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (index < teams.size) {
                AnimatedContent(targetState = index, transitionSpec = {
                    (slideInHorizontally(tween(360)) { if (targetState % 2 == 0) it else -it } + fadeIn(tween(240))) togetherWith
                        (slideOutHorizontally(tween(280)) { if (targetState % 2 == 0) -it else it } + fadeOut(tween(180)))
                }, label = "team") { i ->
                    val l = teams[i]
                    val s = remember { Animatable(0.85f) }
                    LaunchedEffect(i) { s.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(s.value)) {
                        TeamTile(l.panelId, 132.dp)
                        Spacer(Modifier.height(18.dp))
                        Text(l.name, color = RaceColors.Label, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                        Text(tr("شرکت‌کنندهٔ ${i + 1} از ${teams.size}", "Competitor ${i + 1} of ${teams.size}"), color = RaceColors.Secondary, fontSize = 14.sp)
                    }
                }
            } else {
                val a = remember { Animatable(0f) }
                LaunchedEffect(Unit) { a.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(0.85f + 0.15f * a.value).alpha(a.value)) {
                    teams.chunked(3).forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            row.forEachIndexed { k, l ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TeamTile(l.panelId, 60.dp)
                                    Text(l.name, color = RaceColors.Secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                                if (k < row.size - 1) Text("VS", color = RaceColors.Secondary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp).offset(y = (-10).dp))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

// ── start lights ───────────────────────────────────────────────────────────────────────────

/** Five red lights, one by one; after a short random hold they go out -- GO. */
@Composable
fun StartLights(onGo: () -> Unit) {
    val context = LocalContext.current
    var lit by remember { mutableStateOf(0) }
    var go by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        delay(350)
        repeat(5) {
            lit = it + 1
            ArenaSounds.play(context, ArenaSounds.Cue.LIGHT)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(600)
        }
        delay(Random.nextLong(350, 900))
        lit = 0; go = true
        ArenaSounds.play(context, ArenaSounds.Cue.START)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(850)
        onGo()
    }
    RaceStage {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(RaceColors.LightHousing)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(5) { i ->
                    val glow by animateFloatAsState(if (i < lit) 1f else 0f, tween(110), label = "l$i")
                    Box(Modifier.size(40.dp).clip(CircleShape).background(
                        Brush.radialGradient(listOf(
                            Color.White.copy(alpha = 0.55f * glow),
                            RaceColors.LightOn.copy(alpha = 0.25f + 0.75f * glow),
                            RaceColors.LightOff,
                        ))
                    ))
                }
            }
            Spacer(Modifier.height(36.dp))
            val s by animateFloatAsState(if (go) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "go")
            Text(tr("برو!", "GO!"), color = RaceColors.Go, fontSize = 68.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.scale(0.4f + 0.6f * s).alpha(s))
        }
    }
}

// ── the track ──────────────────────────────────────────────────────────────────────────────

private fun raceTitle(p: ArenaEngine.Phase): String = when (p) {
    ArenaEngine.Phase.QUALIFY -> tr("تعیین صلاحیت", "Qualifying")
    ArenaEngine.Phase.LATENCY -> tr("تأخیر", "Latency")
    ArenaEngine.Phase.REACH -> tr("اتصال و دسترسی", "Connection")
    ArenaEngine.Phase.SPEED -> tr("سرعت", "Performance")
    ArenaEngine.Phase.STABILITY -> tr("پایداری", "Stability")
    else -> tr("پایان", "Finish")
}

private fun order(mode: ArenaMode) = if (mode == ArenaMode.FULL)
    listOf(ArenaEngine.Phase.QUALIFY, ArenaEngine.Phase.LATENCY, ArenaEngine.Phase.REACH, ArenaEngine.Phase.SPEED, ArenaEngine.Phase.STABILITY)
else listOf(ArenaEngine.Phase.QUALIFY, ArenaEngine.Phase.LATENCY, ArenaEngine.Phase.REACH)

/** Latin figures inside a right-to-left line, isolated so "CF ✓" does not read back to front. */
internal fun raceMetrics(e: Entry): String = "⁦" + listOfNotNull(
    e.latencyMedian?.let { "${it} ms" },
    if (e.reachTried) (if (e.reachMs != null) "CF ✓" else "CF ✗") else null,
    e.mbps?.let { String.format(java.util.Locale.US, "%.1f Mbps", it) },
    e.stabilitySuccess?.let { "${(it * 100).toInt()}%" },
).joinToString("  ·  ") + "⁩"

@Composable
fun Track(st: ArenaEngine.State, sound: Boolean, setSound: (Boolean) -> Unit, onCancel: () -> Unit) {
    val live = remember(st.lanes) { ArenaScore.score(st.lanes.map { it.entry }) }
    val top = live.standings.maxOfOrNull { it.total }?.takeIf { it > 0 } ?: 1.0
    val phases = order(st.mode)
    val idx = phases.indexOf(st.phase).coerceAtLeast(0)
    val progress = ((idx + st.progress) / phases.size).coerceIn(0f, 1f)

    // How each car feels about where it is (ArenaMoods): one emoji per change, for ~1.8 s.
    val moodEngine = remember { ArenaMoods() }
    val shown = remember { androidx.compose.runtime.mutableStateMapOf<String, ArenaMoods.Mood>() }
    val measuredIds = st.lanes.filter { it.lane != ArenaEngine.Lane.OUT && (it.entry.latencyMedian != null || it.entry.reachTried) }
        .map { it.panelId }.toSet()
    val order = live.standings.map { it.panelId }.filter { it in measuredIds }
    val retired = st.lanes.filter { it.lane == ArenaEngine.Lane.OUT }.map { it.panelId }.toSet()
    LaunchedEffect(order, retired, st.phase, progress >= 0.75f) {
        val moods = moodEngine.update(order, live.standings.associate { it.panelId to it.total }, retired, progress, st.phase, System.currentTimeMillis())
        for (m in moods) {
            shown[m.panelId] = m
            launch { delay(1_800); if (shown[m.panelId] == m) shown.remove(m.panelId) }
        }
    }

    RaceStage {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            RaceBar(tr("میدان کانفیگ", "Config Arena"), sound, setSound, onCancel)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(tr("مرحلهٔ ${idx + 1} از ${phases.size}", "Stage ${idx + 1} of ${phases.size}") +
                    (st.cleanIp?.let { tr(" · همه روی آی‌پی ", " · all on IP ") + "⁦${it.ip}⁩" } ?: ""),
                    color = RaceColors.Secondary, fontSize = 13.sp)
                AnimatedContent(targetState = st.phase, transitionSpec = {
                    (slideInHorizontally(tween(380)) { -it / 3 } + fadeIn(tween(260))) togetherWith fadeOut(tween(160))
                }, label = "phase") { p ->
                    Text(raceTitle(p), color = RaceColors.Label, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                val w by animateFloatAsState(progress, tween(500), label = "progress")
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(RaceColors.Lane)) {
                    Box(Modifier.fillMaxWidth(w).height(4.dp).clip(CircleShape).background(RaceColors.Progress))
                }
            }
            Spacer(Modifier.height(18.dp))
            // A track runs left to right in every language: start on the left, finish on the right.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    st.lanes.forEach { l ->
                        val standing = live.standings.firstOrNull { it.panelId == l.panelId }
                        val rel = ((standing?.total ?: 0.0) / top).toFloat()
                        val out = l.lane == ArenaEngine.Lane.OUT
                        Lane(l, standing?.total?.toInt(), if (out) 0.02f else 0.05f + 0.9f * progress * (0.55f + 0.45f * rel), shown[l.panelId])
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun Lane(l: ArenaEngine.LaneState, score: Int?, position: Float, mood: ArenaMoods.Mood? = null) {
    val out = l.lane == ArenaEngine.Lane.OUT
    val pos by animateFloatAsState(position, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow), label = "pos")
    val spin by animateFloatAsState(if (out) 1f else 0f, tween(800), label = "spin")
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.name, color = if (out) RaceColors.Secondary else RaceColors.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(raceMetrics(l.entry), color = RaceColors.Secondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            val measured = l.entry.latencyMedian != null || l.entry.reachTried
            if (!out) Text(if (measured && score != null) "$score" else "—", color = RaceColors.Label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(5.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp).background(RaceColors.Lane, RoundedCornerShape(12.dp))) {
            val car = 30.dp
            Canvas(Modifier.fillMaxSize()) {
                drawLine(RaceColors.LaneLine, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 14f)))
                // A quiet finish line: a narrow checkered strip.
                val cell = size.height / 5
                for (r in 0 until 5) for (c in 0 until 2) if ((r + c) % 2 == 0)
                    drawRect(Color.White.copy(alpha = 0.28f), Offset(size.width - cell * (c + 1) - 6f, r * cell), Size(cell, cell))
            }
            val x = (maxWidth - car - 20.dp) * pos
            TeamTile(l.panelId, car, dim = out,
                modifier = Modifier.align(Alignment.CenterStart).offset(x = x + 5.dp).rotate(spin * 540f).alpha(1f - spin * 0.4f))
            // The car's mood: pops onto its shoulder, then fades.
            if (mood != null) {
                val pop = remember(mood) { Animatable(0f) }
                LaunchedEffect(mood) {
                    pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
                    delay(1_150)
                    pop.animateTo(0f, tween(300))
                }
                Text(mood.emoji, fontSize = 22.sp,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = x + 5.dp + car - 8.dp, y = (-16).dp)
                        .scale(0.4f + 0.6f * pop.value).alpha(pop.value.coerceIn(0f, 1f)))
            }
        }
        if (out) Text(l.note ?: "", color = RaceColors.Out, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

// ── podium ─────────────────────────────────────────────────────────────────────────────────

/**
 * The 2-1-3 podium: each car on its step, gold, silver and bronze, the score on each step. [rise]
 * runs 0→1 (the steps grow, the cars drop onto them); the result page shows it at 1 or rising once.
 */
@Composable
fun PodiumStand(top3: List<com.mlmvpn.scanner.engines.arena.Standing>, rise: Float, showScores: Boolean = false) =
    // A podium reads 2-1-3 in every language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { PodiumSteps(top3, rise, showScores) }

@Composable
private fun PodiumSteps(top3: List<com.mlmvpn.scanner.engines.arena.Standing>, rise: Float, showScores: Boolean) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val placed = listOfNotNull(top3.getOrNull(1)?.let { it to 2 }, top3.getOrNull(0)?.let { it to 1 }, top3.getOrNull(2)?.let { it to 3 })
        placed.forEach { (s, place) ->
            val h = when (place) { 1 -> 140.dp; 2 -> 104.dp; else -> 76.dp }
            val tint = when (place) { 1 -> RaceColors.Gold; 2 -> RaceColors.Silver; else -> RaceColors.Bronze }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TeamTile(s.panelId, if (place == 1) 64.dp else 50.dp,
                    modifier = Modifier.graphicsLayer { translationY = (1 - rise) * -160f; alpha = rise.coerceIn(0f, 1f) })
                if (showScores) Text(nameOf(s.panelId), color = RaceColors.Label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 5.dp))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(86.dp).height(h * rise).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.9f), tint.copy(alpha = 0.35f)))),
                    contentAlignment = Alignment.TopCenter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 6.dp)) {
                        Text("$place", color = Color.Black.copy(alpha = 0.65f), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        if (showScores) Text("⁦${s.total.toInt()}⁩", color = Color.Black.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** The result page's header: the podium on the race's own dark stage, rising once when fresh. */
@Composable
fun ResultPodium(board: com.mlmvpn.scanner.engines.arena.Scoreboard, animate: Boolean, modifier: Modifier = Modifier) {
    val top3 = board.standings.take(3)
    val rise = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) rise.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow)) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(RaceColors.Top, RaceColors.Bottom)))
            .padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.EmojiEvents, null, tint = RaceColors.Gold, modifier = Modifier.size(34.dp))
        Text(tr("برنده", "Winner"), color = RaceColors.Secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Text(nameOf(top3.first().panelId), color = RaceColors.Label, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(tr("⁦${top3.first().total.toInt()}⁩ از ۱۰۰", "${top3.first().total.toInt()} of 100"), color = RaceColors.Secondary, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        PodiumStand(top3, rise.value, showScores = true)
    }
}

/** A checkered flag sweeps once, the top three rise, the winner is crowned, a light confetti. */
@Composable
fun Podium(st: ArenaEngine.State, onDone: () -> Unit) {
    val context = LocalContext.current
    val board = st.session?.board
    val wipe = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    val confetti = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        ArenaSounds.play(context, ArenaSounds.Cue.FINISH)
        wipe.animateTo(1f, tween(650, easing = LinearEasing))
        ArenaSounds.play(context, ArenaSounds.Cue.WIN)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        launch { confetti.animateTo(1f, tween(2400, easing = LinearEasing)) }
        rise.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow))
        delay(2200)
        onDone()
    }
    val top3 = board?.standings?.take(3).orEmpty()
    RaceStage {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (top3.isEmpty()) {
                    Text(tr("هیچ پنلی به خط پایان نرسید", "No panel reached the finish"), color = RaceColors.Label, fontSize = 18.sp)
                } else {
                    Icon(Icons.Default.EmojiEvents, null, tint = RaceColors.Gold, modifier = Modifier.size(50.dp).scale(rise.value))
                    Text(nameOf(top3.first().panelId), color = RaceColors.Label, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.alpha(rise.value))
                    Spacer(Modifier.height(26.dp))
                    PodiumStand(top3, rise.value)
                }
            }
            // Light confetti: 70 pieces in white, silver and gold, one fall.
            if (top3.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                val p = confetti.value
                if (p <= 0f || p >= 1f) return@Canvas
                val rnd = Random(11)
                val colors = listOf(RaceColors.Gold, Color.White, RaceColors.Silver)
                repeat(70) {
                    val x0 = rnd.nextFloat() * size.width
                    val drift = (rnd.nextFloat() - 0.5f) * 160f
                    val speed = 0.6f + rnd.nextFloat() * 0.7f
                    val y = -30f + (size.height + 60f) * (p * speed).coerceAtMost(1f)
                    val rot = rnd.nextFloat() * 360f + p * 600f
                    val w = 7f + rnd.nextFloat() * 6f
                    val c = colors[rnd.nextInt(colors.size)]
                    val cx = x0 + drift * p
                    drawContext.transform.rotate(rot, Offset(cx, y))
                    drawRoundRect(c.copy(alpha = 0.9f - p * 0.6f), Offset(cx - w / 2, y - w / 4), Size(w, w / 2), CornerRadius(2f))
                    drawContext.transform.rotate(-rot, Offset(cx, y))
                }
            }
            // The checkered flag, once across.
            if (wipe.value < 1f) Canvas(Modifier.fillMaxSize()) {
                val x = size.width * (wipe.value * 1.4f - 0.2f)
                val cell = 26f
                var r = 0
                while (r * cell < size.height) {
                    for (c in 0 until 6) {
                        drawRect(if ((r + c) % 2 == 0) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.8f),
                            Offset(x + c * cell - 3 * cell, r * cell), Size(cell, cell))
                    }
                    r++
                }
            }
        }
    }
}
