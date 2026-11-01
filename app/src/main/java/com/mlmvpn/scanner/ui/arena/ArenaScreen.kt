package com.mlmvpn.scanner.ui.arena

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.engines.arena.ArenaEngine
import com.mlmvpn.scanner.engines.arena.ArenaMode
import com.mlmvpn.scanner.engines.arena.ArenaPanels
import com.mlmvpn.scanner.engines.arena.ArenaScore
import com.mlmvpn.scanner.engines.arena.ArenaSession
import com.mlmvpn.scanner.engines.arena.ArenaStore
import com.mlmvpn.scanner.engines.arena.Category
import com.mlmvpn.scanner.engines.arena.Entry
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import kotlinx.coroutines.delay

private enum class Stage { LOBBY, CONFIRM, LIVE, RESULT }

/**
 * «میدان کانفیگ», designed the way the system designs its own screens: grouped cards on the
 * system background, one neutral tile per team, colour only for state, a Fitness-style 3-2-1 to
 * start, a live ranked list instead of a race track, and a single score ring at the end. Motion is
 * short and tied to real changes; nothing shows a number the engine did not measure.
 */
@Composable
fun ArenaScreen(
    account: CloudAccount,
    groupManager: GroupManager,
    onBack: () -> Unit,
    onGroupsUpdated: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ArenaStore.load(context) }
    val st by ArenaEngine.state.collectAsState()
    val history by ArenaStore.history.collectAsState()
    var stage by remember { mutableStateOf(if (st.running) Stage.LIVE else if (st.session != null) Stage.RESULT else Stage.LOBBY) }
    var mode by remember { mutableStateOf(ArenaMode.QUICK) }
    var preps by remember { mutableStateOf<List<ArenaEngine.Prep>>(emptyList()) }
    var shown by remember { mutableStateOf<ArenaSession?>(null) }
    var sound by remember { mutableStateOf(ArenaSounds.enabled(context)) }
    val setSound: (Boolean) -> Unit = { sound = it; ArenaSounds.setEnabled(context, it) }

    BackHandler {
        when (stage) {
            Stage.CONFIRM -> stage = Stage.LOBBY
            Stage.LIVE -> { ArenaEngine.cancel(); stage = Stage.LOBBY }
            Stage.RESULT -> { shown = null; ArenaEngine.reset(); stage = Stage.LOBBY }
            Stage.LOBBY -> onBack()
        }
    }

    fun begin(m: ArenaMode) {
        mode = m
        preps = ArenaEngine.plan(context, account)
        if (preps.all { it.action == ArenaEngine.Action.NONE }) {
            ArenaEngine.start(context, account, m, preps); stage = Stage.LIVE
        } else stage = Stage.CONFIRM
    }

    when (stage) {
        Stage.LOBBY -> Lobby(account, history, sound, setSound, onBack, onStart = { begin(it) }, onOpen = { shown = it; stage = Stage.RESULT })
        Stage.CONFIRM -> Confirm(account, preps,
            onCancel = { stage = Stage.LOBBY },
            onGo = { ArenaEngine.start(context, account, mode, preps); stage = Stage.LIVE })
        Stage.LIVE -> Live(st, sound, setSound, onFinished = { stage = Stage.RESULT }, onCancel = { ArenaEngine.cancel(); stage = Stage.LOBBY })
        Stage.RESULT -> {
            val session = shown ?: st.session
            if (session == null) Failure(st.error, onBack = { ArenaEngine.reset(); stage = Stage.LOBBY })
            else Result(session, account, groupManager, onGroupsUpdated, celebrate = shown == null,
                onBack = { shown = null; ArenaEngine.reset(); stage = Stage.LOBBY },
                onRematch = { ids ->
                    val p = ArenaEngine.plan(context, account, ArenaPanels.ALL.filter { it.id in ids })
                    shown = null
                    ArenaEngine.start(context, account, session.mode, p); stage = Stage.LIVE
                })
        }
    }
}

// ── pieces ──────────────────────────────────────────────────────────────────────────────────

/** A row inside a grouped card with a team tile where Settings would put its glyph. */
@Composable
private fun TeamRow(
    id: String, title: String, subtitle: String? = null, dim: Boolean = false,
    leading: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        TeamTile(id, 30.dp, dim = dim)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (dim) Ios.SecondaryLabel else Ios.Label, fontSize = 16.sp)
            subtitle?.let { Text(it, color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2) }
        }
        trailing()
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Ios.Blue else Ios.Gray.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

/** A thin value bar, the way Settings draws storage: grey track, one colour. */
@Composable
private fun ThinBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val w by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "bar")
    Box(modifier.height(4.dp).clip(CircleShape).background(Ios.Gray.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(w).height(4.dp).clip(CircleShape).background(color))
    }
}

private fun nameOf(id: String) = ArenaPanels.byId(id)?.name ?: id
private fun timeOf(ms: Long) = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))
private fun modeName(m: ArenaMode) = if (m == ArenaMode.FULL) tr("مسابقهٔ کامل", "Full Arena") else tr("مسابقهٔ سریع", "Quick Battle")

/** Latin figures inside a right-to-left line, isolated so "CF ✓" does not read back to front. */
private fun metrics(e: Entry): String = "⁦" + listOfNotNull(
    e.latencyMedian?.let { "${it} ms" },
    if (e.reachTried) (if (e.reachMs != null) "CF ✓" else "CF ✗") else null,
    e.mbps?.let { String.format(java.util.Locale.US, "%.1f Mbps", it) },
    e.stabilitySuccess?.let { "${(it * 100).toInt()}%" },
).joinToString("  ·  ") + "⁩"

@Composable
private fun SoundToggle(sound: Boolean, setSound: (Boolean) -> Unit) {
    Icon(if (sound) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
        contentDescription = tr("صدا", "Sound"), tint = Ios.Blue,
        modifier = Modifier.size(24.dp).clickable { setSound(!sound) })
}

// ── lobby ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Lobby(
    account: CloudAccount, history: List<ArenaSession>, sound: Boolean, setSound: (Boolean) -> Unit,
    onBack: () -> Unit, onStart: (ArenaMode) -> Unit, onOpen: (ArenaSession) -> Unit,
) {
    val context = LocalContext.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    IosScreen(title = tr("میدان کانفیگ", "Config Arena"), onBack = onBack, backLabel = tr("ابری", "Cloud")) {
        // The field: every team, as equals.
        Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ArenaPanels.ALL.chunked(4).forEachIndexed { row, chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    chunk.forEachIndexed { i, p ->
                        val k = (row * 4 + i) / ArenaPanels.ALL.size.toFloat()
                        val t = ((appear.value - k * 0.4f) / 0.6f).coerceIn(0f, 1f)
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.graphicsLayer { alpha = t; translationY = (1 - t) * 16f }) {
                            TeamTile(p.id, 48.dp)
                            Spacer(Modifier.height(5.dp))
                            Text(p.name, color = Ios.Label, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            val last = history.firstOrNull()?.board?.winner
            Text(
                if (last != null) tr("آخرین برنده: ", "Last winner: ") + nameOf(last) else tr("پنل‌های ابری شما، با اندازه‌گیری واقعی", "Your cloud panels, measured for real"),
                color = Ios.SecondaryLabel, fontSize = 13.sp,
            )
        }

        SettingsSectionHeader(tr("شروع مسابقه", "Start a race"))
        SettingsGroup {
            SettingsRow(modeName(ArenaMode.QUICK), icon = Icons.Default.Bolt, tint = Ios.Orange,
                subtitle = tr("تعیین صلاحیت، تأخیر و دسترسی — حدود یک و نیم دقیقه", "Qualifying, latency and reach — about a minute and a half"),
                onClick = { onStart(ArenaMode.QUICK) })
            Separator()
            SettingsRow(modeName(ArenaMode.FULL), icon = Icons.Default.Flag, tint = Ios.Green,
                subtitle = tr("به‌علاوهٔ سرعت و پایداری — حدود سه و نیم دقیقه، ۱ مگابایت برای هر پنل", "Plus speed and stability — about three and a half minutes, 1 MB per panel"),
                onClick = { onStart(ArenaMode.FULL) })
        }
        SettingsGroup(modifier = Modifier.padding(top = 12.dp)) {
            SettingsToggle(tr("افکت‌های صوتی", "Sound effects"), checked = sound, onCheckedChange = setSound,
                icon = Icons.Default.VolumeUp, tint = Ios.Pink)
        }
        SettingsFooter(tr(
            "همهٔ پنل‌ها با یک روش و به نوبت سنجیده می‌شوند تا نوسان شبکه به همه یکسان برسد. عددها واقعی‌اند و هیچ پنلی امتیاز ویژه ندارد.",
            "Every panel is measured the same way, in turns, so network swings land on all of them alike. The numbers are real and no panel gets special treatment."))

        SettingsSectionHeader(tr("شرکت‌کننده‌ها", "Competitors"))
        SettingsGroup {
            ArenaPanels.ALL.forEachIndexed { i, p ->
                if (i > 0) Separator()
                val installed = p.installed(context, account)
                TeamRow(p.id, p.name) {
                    Text(if (installed) tr("نصب‌شده", "Installed") else tr("در مسابقه نصب می‌شود", "Installed at race time"),
                        color = if (installed) Ios.SecondaryLabel else Ios.Orange, fontSize = 14.sp)
                }
            }
        }

        if (history.isNotEmpty()) {
            val dayMs = 86_400_000L
            val today = System.currentTimeMillis() / dayMs
            history.groupBy {
                when (today - it.startedAt / dayMs) { 0L -> tr("امروز", "Today"); 1L -> tr("دیروز", "Yesterday"); else -> tr("قدیمی‌تر", "Older") }
            }.forEach { (label, list) ->
                SettingsSectionHeader(label)
                SettingsGroup {
                    list.forEachIndexed { i, s ->
                        if (i > 0) Separator()
                        val w = s.board.winner
                        TeamRow(w ?: "-", if (w != null) nameOf(w) else tr("بدون برنده", "No winner"),
                            subtitle = listOf(timeOf(s.startedAt).takeLast(5), s.network.label, modeName(s.mode)).filter { it.isNotBlank() }.joinToString(" · "),
                            onClick = { onOpen(s) }) {
                            if (w != null) Icon(Icons.Default.EmojiEvents, null, tint = Ios.Yellow, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ── the one confirmation ──────────────────────────────────────────────────────────────────

@Composable
private fun Confirm(account: CloudAccount, preps: List<ArenaEngine.Prep>, onCancel: () -> Unit, onGo: () -> Unit) {
    IosScreen(title = tr("آماده‌سازی", "Preparation"), onBack = onCancel, backLabel = tr("میدان", "Arena")) {
        SettingsFooter(tr("برای این مسابقه روی حساب ${account.email}:", "For this race, on ${account.email}:"))
        SettingsGroup(modifier = Modifier.padding(top = 10.dp)) {
            preps.forEachIndexed { i, p ->
                if (i > 0) Separator()
                TeamRow(p.panel.id, p.panel.name) {
                    val (text, tint) = when (p.action) {
                        ArenaEngine.Action.INSTALL -> tr("نصب", "Install") to Ios.Orange
                        ArenaEngine.Action.UPDATE -> tr("به‌روزرسانی", "Update") to Ios.Blue
                        ArenaEngine.Action.NONE -> tr("آماده", "Ready") to Ios.Green
                    }
                    Text(text, color = tint, fontSize = 15.sp)
                }
            }
        }
        SettingsFooter(tr(
            "همه روی حساب کلادفلر خود شماست. به‌روزرسانی فقط کد ورکر را عوض می‌کند و کاربران و تنظیمات می‌مانند. پنلی که نصب نشود کنار می‌رود و بقیه ادامه می‌دهند.",
            "Everything goes on your own Cloudflare account. An update replaces only the Worker's code; users and settings stay. A panel that fails to install sits out and the others go on."))
        Spacer(Modifier.height(20.dp))
        PrimaryButton(tr("آماده‌سازی و شروع", "Prepare and start"), onClick = onGo)
        Spacer(Modifier.height(40.dp))
    }
}

// ── live ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Live(st: ArenaEngine.State, sound: Boolean, setSound: (Boolean) -> Unit, onFinished: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val preRace = st.phase == ArenaEngine.Phase.PREPARING || st.phase == ArenaEngine.Phase.CONFIGS || st.phase == ArenaEngine.Phase.CLEAN_IP
    var introDone by remember { mutableStateOf(false) }
    var lightsDone by remember { mutableStateOf(false) }

    // A soft cue when a car retires. The finish and win cues belong to the podium.
    val outCount = st.lanes.count { it.lane == ArenaEngine.Lane.OUT }
    var heardOut by remember { mutableStateOf(-1) }
    LaunchedEffect(outCount) {
        if (heardOut >= 0 && outCount > heardOut && !preRace && lightsDone) ArenaSounds.play(context, ArenaSounds.Cue.OUT)
        heardOut = outCount
    }

    when {
        preRace -> PrepList(st, sound, setSound, onCancel)
        !introDone -> GridReveal(st) { introDone = true }
        !lightsDone -> StartLights { lightsDone = true }
        st.phase != ArenaEngine.Phase.DONE -> Track(st, sound, setSound, onCancel)
        else -> Podium(st, onFinished)
    }
}

@Composable
private fun PrepList(st: ArenaEngine.State, sound: Boolean, setSound: (Boolean) -> Unit, onCancel: () -> Unit) {
    IosScreen(title = tr("آماده‌سازی", "Preparation"), onBack = onCancel, backLabel = tr("لغو", "Cancel"),
        trailing = { SoundToggle(sound, setSound) }) {
        SettingsFooter(when (st.phase) {
            ArenaEngine.Phase.PREPARING -> tr("نصب و به‌روزرسانی پنل‌ها…", "Installing and updating the panels…")
            ArenaEngine.Phase.CLEAN_IP -> tr("پیدا کردن یک آی‌پی تمیز پرسرعت و پایدار برای همه…", "Finding one fast, steady clean IP for everyone…")
            else -> tr("گرفتن کانفیگ از هر پنل…", "Getting a config from each panel…")
        })
        CleanIpGroup(st)
        SettingsGroup(modifier = Modifier.padding(top = 10.dp)) {
            st.lanes.forEachIndexed { i, l ->
                if (i > 0) Separator()
                val out = l.lane == ArenaEngine.Lane.OUT
                val ready = l.lane == ArenaEngine.Lane.READY || l.lane == ArenaEngine.Lane.RACING
                TeamRow(l.panelId, l.name, subtitle = l.note, dim = out) {
                    when {
                        out -> Text(tr("کنار رفت", "Out"), color = Ios.Red, fontSize = 14.sp)
                        ready && l.note?.contains("…") != true -> Icon(Icons.Default.CheckCircle, null, tint = Ios.Green, modifier = Modifier.size(20.dp))
                        else -> CircularProgressIndicator(Modifier.size(18.dp), color = Ios.SecondaryLabel, strokeWidth = 2.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * The clean-IP step as one row: what it is doing, then the IP it chose and why (the handshake time
 * and spread that made it steady, the download that made it fast), or why there is none.
 */
@Composable
private fun CleanIpGroup(st: ArenaEngine.State) {
    val active = st.phase == ArenaEngine.Phase.CLEAN_IP
    if (!active && st.cleanIp == null && st.cleanNote == null) return
    SettingsSectionHeader(tr("آی‌پی تمیز یکسان", "One clean IP"))
    SettingsGroup {
        val pick = st.cleanIp
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            com.mlmvpn.scanner.ui.settings.SettingsGlyph(Icons.Default.Language, Ios.Teal)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(pick?.let { "⁦${it.ip}⁩" } ?: tr("آی‌پی تمیز", "Clean IP"), color = Ios.Label, fontSize = 16.sp)
                val sub = pick?.let {
                    tr("تأخیر واقعی ", "real delay ") + "⁦${it.tlsMedianMs} ms" + (it.mbps?.let { m -> String.format(java.util.Locale.US, " · %.1f Mbps", m) } ?: "") + "⁩" +
                        tr(" · از ${it.tried} آی‌پی", " · of ${it.tried} IPs")
                } ?: st.cleanNote
                if (sub != null) Text(sub, color = Ios.SecondaryLabel, fontSize = 13.sp)
            }
            when {
                pick != null -> Icon(Icons.Default.CheckCircle, null, tint = Ios.Green, modifier = Modifier.size(20.dp))
                active -> CircularProgressIndicator(Modifier.size(18.dp), color = Ios.SecondaryLabel, strokeWidth = 2.dp)
            }
        }
    }
    SettingsFooter(tr(
        "همهٔ کانفیگ‌ها با همین یک آی‌پی مسابقه می‌دهند تا پنل‌ها با هم مقایسه شوند، نه نشانی‌هایشان. مثل اسکنر: ۲۰ آی‌پی که سریع پینگ می‌دهند، هر کدام دو بار با کانفیگ پایه (BPB) تست واقعی می‌شوند و سریع‌ترینِ پایدار، پس از یک دانلود کوتاه، برای همه گذاشته می‌شود.",
        "Every config races on this one IP, so the panels are compared rather than their addresses. Like the scanner: 20 IPs that ping quickly each carry two real requests through the base config (BPB), and the fastest steady one, after a short download, goes on everyone."))
}

// ── result ─────────────────────────────────────────────────────────────────────────────────

/** The score as one ring, the way the system shows a single measure against a goal. */
@Composable
private fun ScoreRing(score: Int, color: Color) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(score) { sweep.animateTo(score / 100f, tween(1100, easing = FastOutSlowInEasing)) }
    val shown by animateIntAsState(score, tween(1100), label = "score")
    val track = Ios.Gray.copy(alpha = 0.25f)
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(132.dp)) {
            val w = 12.dp.toPx()
            val inset = w / 2
            drawArc(track, 0f, 360f, false, Offset(inset, inset), Size(size.width - w, size.height - w), style = Stroke(w))
            drawArc(color, -90f, 360f * sweep.value, false, Offset(inset, inset), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$shown", color = Ios.Label, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Text(tr("از ۱۰۰", "of 100"), color = Ios.SecondaryLabel, fontSize = 12.sp)
        }
    }
}

private fun categoryGlyph(c: Category): Pair<ImageVector, String> = when (c) {
    Category.OVERALL -> Icons.Default.EmojiEvents to "yellow"
    Category.LATENCY -> Icons.Default.Timer to "blue"
    Category.SPEED -> Icons.Default.Speed to "orange"
    Category.STABLE -> Icons.Default.GraphicEq to "green"
    Category.REACH -> Icons.Default.Language to "teal"
}

@Composable
private fun tintOf(name: String): Color = when (name) {
    "yellow" -> Ios.Yellow; "blue" -> Ios.Blue; "orange" -> Ios.Orange; "green" -> Ios.Green; else -> Ios.Teal
}

private fun roundName(r: String) = when (r) {
    ArenaScore.LATENCY -> tr("تأخیر", "Latency")
    ArenaScore.REACH -> tr("دسترسی", "Reach")
    ArenaScore.SPEED -> tr("سرعت", "Speed")
    ArenaScore.STABILITY -> tr("پایداری", "Stability")
    else -> r
}

@Composable
private fun Result(
    session: ArenaSession, account: CloudAccount, groupManager: GroupManager, onGroupsUpdated: () -> Unit,
    celebrate: Boolean, onBack: () -> Unit, onRematch: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val board = session.board
    val winner = session.entries.firstOrNull { it.panelId == board.winner }
    var open by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf<Pair<String, String>?>(null) }
    // The win cue and the podium's rise already played on the race's own podium scene.

    fun startVpn(id: String, uri: String) {
        com.mlmvpn.scanner.utils.SafeStart.service(context, Intent(context, MyVpnService::class.java).apply {
            putExtra("NODE_URI", uri); putExtra("NODE_ID", id)
            putExtra("PROXY_MODE", com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(context))
            putExtra("LOCAL_PORT", com.mlmvpn.scanner.utils.LocalPort.getString(context))
        })
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val s = starting
        if (r.resultCode == Activity.RESULT_OK && s != null) startVpn(s.first, s.second)
        starting = null
    }
    /** The winner is filed as a group first, so the connection has a row in the app's lists. */
    fun saveWinner(): Pair<String, String>? {
        val w = winner ?: return null
        val g = com.mlmvpn.scanner.ui.cloud.PanelConfigs.file(context, groupManager, account, w.panelId,
            tr("میدان", "Arena") + " · " + w.name, listOf(w.name to w.uri!!)) ?: return null
        onGroupsUpdated()
        return g.nodes.first().id to g.nodes.first().uri
    }

    IosScreen(title = tr("نتیجه", "Result"), onBack = onBack, backLabel = tr("میدان", "Arena")) {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (winner != null) {
                ResultPodium(board, animate = false, modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                Text(tr("هیچ پنلی به خط پایان نرسید", "No panel reached the finish"), color = Ios.Label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Text(listOf(timeOf(session.startedAt), session.network.label, modeName(session.mode), session.cleanIp?.let { tr("آی‌پی ", "IP ") + "⁦$it⁩" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                color = Ios.SecondaryLabel, fontSize = 12.sp)
        }

        if (winner != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(tr("اتصال به برنده", "Connect the winner")) {
                val s = saveWinner() ?: return@PrimaryButton
                com.mlmvpn.scanner.data.ScanGuard.run(com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN) {
                    starting = s
                    val ask = runCatching { VpnService.prepare(context) }.getOrNull()
                    if (ask != null) consent.launch(ask) else { startVpn(s.first, s.second); starting = null }
                }
            }
            SettingsFooter(why(winner, board.categories))
            SettingsGroup(modifier = Modifier.padding(top = 12.dp)) {
                SettingsActionRow(tr("ذخیره در برنامه", "Save to the app"), Icons.Default.SaveAlt, onClick = {
                    if (saveWinner() != null) android.widget.Toast.makeText(context, tr("کانفیگ برنده ذخیره شد.", "The winner's config was saved."), android.widget.Toast.LENGTH_SHORT).show()
                })
                if (board.standings.size >= 2) {
                    Separator()
                    SettingsActionRow(tr("مسابقهٔ دوباره دو نفر اول", "Rematch the top two"), Icons.Default.Refresh, onClick = {
                        onRematch(board.standings.take(2).map { it.panelId }.toSet())
                    })
                }
                Separator()
                SettingsActionRow(tr("اشتراک نتیجه", "Share the result"), Icons.Default.Share, onClick = {
                    // Names and scores only: no address, host or key ever leaves in a share.
                    val text = buildString {
                        append(tr("میدان کانفیگ MLMVPN", "MLMVPN Config Arena")).append(" — ").append(timeOf(session.startedAt)).append('\n')
                        board.standings.forEachIndexed { i, s -> append("${i + 1}. ${nameOf(s.panelId)} — ${s.total.toInt()}\n") }
                    }
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
        }

        if (board.categories.isNotEmpty()) {
            SettingsSectionHeader(tr("مدال‌ها", "Medals"))
            SettingsGroup {
                board.categories.entries.forEachIndexed { i, (cat, id) ->
                    if (i > 0) Separator()
                    val (icon, tint) = categoryGlyph(cat)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsGlyph(icon, tintOf(tint))
                        Spacer(Modifier.width(12.dp))
                        Text(tr(cat.fa, cat.en), color = Ios.Label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(nameOf(id), color = Ios.SecondaryLabel, fontSize = 15.sp)
                    }
                }
            }
        }

        SettingsSectionHeader(tr("جدول نهایی", "Final standings"))
        SettingsGroup {
            board.standings.forEachIndexed { i, s ->
                if (i > 0) Separator()
                val e = session.entries.first { it.panelId == s.panelId }
                TeamRow(s.panelId, e.name, subtitle = metrics(e),
                    leading = { Text("${i + 1}", color = if (i == 0) Ios.Yellow else Ios.SecondaryLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp)) },
                    onClick = { open = if (open == s.panelId) null else s.panelId }) {
                    Text("${s.total.toInt()}", color = Ios.Label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                if (open == s.panelId) {
                    Column(Modifier.fillMaxWidth().padding(start = 96.dp, end = 16.dp, bottom = 12.dp)) {
                        board.weights.keys.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                                Text(roundName(r), color = Ios.SecondaryLabel, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                                ThinBar(((s.parts[r] ?: 0.0) / 100).toFloat(), Ios.Blue, Modifier.weight(1f))
                                Text("${(s.parts[r] ?: 0.0).toInt()}", color = Ios.SecondaryLabel, fontSize = 12.sp, modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                            }
                        }
                        Text(detailLine(e), color = Ios.SecondaryLabel, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        SettingsFooter(tr("برای دیدن امتیاز هر دور و زمان هر اندازه‌گیری روی یک ردیف بزنید.", "Tap a row for each round's score and the time of every measurement."))

        val out = session.entries.filter { !it.qualified }
        if (out.isNotEmpty()) {
            SettingsSectionHeader(tr("به خط پایان نرسیدند", "Did not finish"))
            SettingsGroup {
                out.forEachIndexed { i, e ->
                    if (i > 0) Separator()
                    TeamRow(e.panelId, e.name, subtitle = listOfNotNull(e.fail?.let { tr(it.fa, it.en) }, e.failDetail?.take(140)).joinToString(" — "), dim = true) {}
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Failure(error: String?, onBack: () -> Unit) {
    IosScreen(title = tr("میدان کانفیگ", "Config Arena"), onBack = onBack) {
        SettingsSectionHeader(tr("مسابقه تمام نشد", "The race did not finish"))
        SettingsFooter(error ?: "")
    }
}

/** «چرا برنده شد», from what was measured. */
private fun why(e: Entry, cats: Map<Category, String>): String {
    val won = cats.filterValues { it == e.panelId }.keys.filter { it != Category.OVERALL }
    val parts = mutableListOf<String>()
    e.latencyMedian?.let { parts += tr("تأخیر میانه $it میلی‌ثانیه", "median delay $it ms") }
    if (e.reachTried) parts += if (e.reachMs != null) tr("سایت‌های کلادفلر را هم باز کرد", "opened Cloudflare-hosted sites too") else tr("سایت‌های کلادفلر را باز نکرد", "could not open Cloudflare-hosted sites")
    e.mbps?.let { parts += tr(String.format(java.util.Locale.US, "%.1f مگابیت در ثانیه", it), String.format(java.util.Locale.US, "%.1f Mbit/s", it)) }
    e.stabilitySuccess?.let { parts += tr("${(it * 100).toInt()}٪ پاسخ در پایش پایداری", "${(it * 100).toInt()}% answered in the stability watch") }
    val medals = if (won.isNotEmpty()) tr("مدال: ", "Medals: ") + won.joinToString("، ") { tr(it.fa, it.en) } + ". " else ""
    return medals + parts.joinToString(tr("، ", ", "))
}

private fun detailLine(e: Entry): String {
    val t = { at: Long -> java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at)) }
    return listOfNotNull(
        e.qualifyMs?.let { tr("صلاحیت: ${it}ms", "Qualifying: ${it}ms") },
        if (e.latency.isNotEmpty()) tr("تأخیر: ", "Latency: ") + e.latency.joinToString(" ") { "${it.ms ?: "✗"}@${t(it.at)}" } else null,
        if (e.reachTried) tr("کلادفلر: ", "Cloudflare: ") + (e.reachMs?.let { "${it}ms" } ?: "✗") else null,
        e.mbps?.let { String.format(java.util.Locale.US, "%.2f Mbit/s", it) },
        if (e.stability.isNotEmpty()) tr("پایداری: ", "Stability: ") + e.stability.joinToString(" ") { "${it.ms ?: "✗"}" } else null,
        tr("${e.candidates} کانفیگ؛ سریع‌ترین مسابقه داد", "${e.candidates} configs; the fastest raced"),
    ).joinToString("\n")
}
