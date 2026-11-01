package com.mlmvpn.scanner.ui.game

import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.game.booster.decide.ProbeMethod
import com.mlmvpn.scanner.engines.game.booster.doctor.GameKind
import com.mlmvpn.scanner.engines.game.booster.doctor.Obstacle
import com.mlmvpn.scanner.engines.game.booster.memory.RouteMemoryStore
import com.mlmvpn.scanner.engines.game.booster.model.RegionCatalog
import com.mlmvpn.scanner.engines.game.booster.model.Symptom
import com.mlmvpn.scanner.engines.game.booster.model.TrafficClass
import com.mlmvpn.scanner.engines.game.booster.session.ClassLine
import com.mlmvpn.scanner.engines.game.booster.session.FixedBy
import com.mlmvpn.scanner.engines.game.booster.session.LastSession
import com.mlmvpn.scanner.engines.game.booster.session.PingOverlay
import com.mlmvpn.scanner.engines.game.booster.session.SpikeWatch
import com.mlmvpn.scanner.engines.game.booster.model.BuiltInProfiles
import com.mlmvpn.scanner.engines.game.booster.model.GameProfile
import com.mlmvpn.scanner.engines.game.booster.model.GameProfileStore
import com.mlmvpn.scanner.engines.game.booster.model.ProbeDepth
import com.mlmvpn.scanner.engines.game.booster.model.RouteChoice
import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import com.mlmvpn.scanner.engines.game.booster.probe.ProbeStats
import com.mlmvpn.scanner.engines.game.booster.session.AccessState
import com.mlmvpn.scanner.engines.game.booster.session.BoostOutcome
import com.mlmvpn.scanner.engines.game.booster.session.BoostPhase
import com.mlmvpn.scanner.engines.game.booster.session.GameBoostController
import com.mlmvpn.scanner.engines.game.booster.session.GameBoostService
import com.mlmvpn.scanner.engines.game.booster.session.LiveReading
import com.mlmvpn.scanner.engines.game.booster.session.LiveStatus
import com.mlmvpn.scanner.engines.game.booster.session.Preflight
import com.mlmvpn.scanner.engines.game.booster.session.Stage
import com.mlmvpn.scanner.engines.game.booster.session.StageId
import com.mlmvpn.scanner.engines.game.booster.session.StageStatus
import com.mlmvpn.scanner.engines.game.booster.session.VerdictKind
import com.mlmvpn.scanner.engines.game.booster.session.WarpNote
import com.mlmvpn.scanner.ui.emergency.DialState
import com.mlmvpn.scanner.ui.emergency.EmergencyDial
import com.mlmvpn.scanner.ui.settings.AppInfo
import com.mlmvpn.scanner.ui.settings.AppPickerPage
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.rememberInstalledApps
import com.mlmvpn.scanner.ui.theme.BadgeShape
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.GreenOk
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.utils.AppLocaleManager
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Game tab: the new booster by default, the previous one one tap away until the new one has
 * everything it had.
 */
@Composable
fun GameBoosterHost(onBack: () -> Unit, onNavigateToCloud: (() -> Unit)?) {
    var classic by rememberSaveable { mutableStateOf(false) }
    if (classic) {
        com.mlmvpn.scanner.ui.GameTab(onBack = { classic = false }, onNavigateToCloud = onNavigateToCloud)
    } else {
        GameBoostScreen(onBack = onBack, onOpenClassic = { classic = true })
    }
}

private enum class Page { MAIN, PICK_GAME, PICK_REGION, ADD_GAME, ADVANCED, HELP }

/**
 * Game booster v2. A beginner picks a game and presses one button; everything else is measured.
 * The advanced page holds the choices a pro wants to make by hand.
 */
@Composable
fun GameBoostScreen(onBack: () -> Unit, onOpenClassic: () -> Unit) {
    val context = LocalContext.current
    val store = remember { GameProfileStore(context) }
    val prefs = remember { context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE) }
    var version by remember { mutableStateOf(0) } // bumps when profiles change
    val games = remember(version) { store.allSorted() }
    val ui by GameBoostController.state.collectAsState()

    var selectedId by rememberSaveable {
        mutableStateOf(ui.gameId ?: prefs.getString(PREF_LAST_GAME, null)
            ?: games.firstOrNull { it.second }?.first?.id ?: games.firstOrNull()?.first?.id)
    }
    // While a boost runs the page describes THAT game, whatever was picked before leaving the screen.
    val running = ui.phase == BoostPhase.MEASURING || ui.phase == BoostPhase.APPLYING || ui.phase == BoostPhase.ACTIVE
    val shownId = if (running && ui.gameId != null) ui.gameId else selectedId
    val selected = games.firstOrNull { it.first.id == shownId }?.first ?: games.firstOrNull()?.first
    val installed = games.firstOrNull { it.first.id == selected?.id }?.second == true
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var confirmVpn by remember { mutableStateOf(false) }
    // «امتحان با WARP» waiting on the VPN consent or on the confirm dialog.
    var pendingWarp by remember { mutableStateOf(false) }
    // A problem chosen in «چه مشکلی داری؟», waiting the same way.
    var pendingSymptom by remember { mutableStateOf<Symptom?>(null) }
    // Set by a «بوست و اجرا» home-screen icon: open the game once the boost settles.
    var launchAfter by rememberSaveable { mutableStateOf<String?>(null) }

    val startBoostFor: (GameProfile, Boolean) -> Unit = { game, forceWarp ->
        prefs.edit().putString(PREF_LAST_GAME, game.id).apply()
        GameBoostService.start(context, game.id, game.defaultRegion,
            thorough = game.prefs.depth == ProbeDepth.THOROUGH, forceWarp = forceWarp, symptom = pendingSymptom)
        pendingSymptom = null
    }
    // A per-game route needs the VPN permission. Asked for up front, once; if refused the boost
    // still runs and simply keeps the game on the direct line.
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        selected?.let { startBoostFor(it, pendingWarp) }
        pendingWarp = false
    }
    val requestAndStart: (GameProfile, Boolean) -> Unit = { game, forceWarp ->
        val consent = try { VpnService.prepare(context) } catch (e: Exception) { null }
        if (consent != null) {
            pendingWarp = forceWarp
            vpnConsent.launch(consent)
        } else startBoostFor(game, forceWarp)
    }
    /** Boost [game]; a game that is not installed is measured only, which needs no VPN at all. */
    val beginBoost: (GameProfile, Boolean, Boolean) -> Unit = { game, isInstalled, forceWarp ->
        when {
            !isInstalled -> startBoostFor(game, false)
            // Either of the app's VPN services, except a game route of the booster's own.
            (MyVpnService.isRunning && MyVpnService.connectedNodeId?.startsWith("game_") != true) ||
                com.mlmvpn.core.tunnel.TunnelStatus.isActive() -> { pendingWarp = forceWarp; confirmVpn = true }
            else -> requestAndStart(game, forceWarp)
        }
    }
    val onDial: () -> Unit = {
        // A measure-only card has nothing running behind it: the dial measures again.
        val measuredOnly = ui.phase == BoostPhase.ACTIVE && ui.outcome?.measureOnly == true
        when {
            GameBoostController.isActive && !measuredOnly -> GameBoostService.stop(context)
            selected == null -> Unit
            else -> {
                if (measuredOnly) GameBoostService.stop(context)
                beginBoost(selected, installed, false)
            }
        }
    }

    // A «بوست و اجرا» icon: boost that game now, open it when the route is in place.
    LaunchedEffect(Unit) {
        val id = BoostShortcut.consume(context) ?: return@LaunchedEffect
        val entry = games.firstOrNull { it.first.id == id } ?: return@LaunchedEffect
        if (!entry.second) return@LaunchedEffect
        selectedId = id
        launchAfter = id
        val s = GameBoostController.state.value
        val alreadyOn = s.gameId == id && s.phase == BoostPhase.ACTIVE && s.outcome?.measureOnly != true
        // A boost of another game is simply replaced: the service cancels it when a new one starts.
        if (!alreadyOn) beginBoost(entry.first, true, false)
    }
    LaunchedEffect(ui.phase, launchAfter) {
        val id = launchAfter ?: return@LaunchedEffect
        if (ui.phase != BoostPhase.ACTIVE && ui.phase != BoostPhase.FAILED) return@LaunchedEffect
        launchAfter = null
        val pkg = games.firstOrNull { it.first.id == id }?.first?.let { store.installedPackage(it) } ?: return@LaunchedEffect
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            try { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    when (page) {
        Page.PICK_GAME -> {
            GamePickerPage(games, selectedId, onPick = {
                selectedId = it
                page = Page.MAIN
                // A failure message belongs to the game that failed; picking another clears it.
                if (ui.phase == BoostPhase.FAILED) GameBoostService.stop(context)
            },
                onAdd = { page = Page.ADD_GAME }, onBack = { page = Page.MAIN })
            return
        }
        Page.PICK_REGION -> {
            val g = selected ?: run { page = Page.MAIN; return }
            RegionPickerPage(g, onPick = { key -> store.setRegion(g.id, key); version++; page = Page.MAIN },
                onBack = { page = Page.MAIN })
            return
        }
        Page.ADD_GAME -> {
            AddGamePage(existing = games.flatMap { it.first.packages }.toSet(),
                onPick = { pkg, label ->
                    val p = BuiltInProfiles.custom(pkg, label)
                    store.saveCustom(p)
                    version++
                    selectedId = p.id
                    page = Page.MAIN
                }, onBack = { page = Page.PICK_GAME })
            return
        }
        Page.HELP -> {
            HelpPage(onPick = { s ->
                page = Page.MAIN
                val g = selected ?: return@HelpPage
                pendingSymptom = s
                // A boost already running is replaced by this one (the service ends it first).
                beginBoost(g, installed, false)
            }, onBack = { page = Page.MAIN })
            return
        }
        Page.ADVANCED -> {
            val g = selected ?: run { page = Page.MAIN; return }
            AdvancedPage(g, onChange = { prefsNew -> store.setPrefs(g.id, prefsNew); version++ },
                onRemove = { store.removeCustom(g.id); version++; selectedId = null; page = Page.MAIN },
                onBack = { page = Page.MAIN })
            return
        }
        Page.MAIN -> Unit
    }

    IosScreen(
        title = stringResource(R.string.gb_title),
        onBack = onBack,
        trailing = {
            Text(stringResource(R.string.gb_classic), color = Ios.Blue, fontSize = 15.sp,
                modifier = Modifier.clickable(onClick = onOpenClassic).padding(8.dp))
        },
    ) {
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            GameRow(selected, installed) { if (!GameBoostController.isActive) page = Page.PICK_GAME }
            if (selected != null && selected.regions.size > 1) {
                Separator()
                ValueRow(stringResource(R.string.gb_region), regionLabel(selected)) {
                    if (!GameBoostController.isActive) page = Page.PICK_REGION
                }
            }
            if (selected != null) {
                Separator()
                // The player knows what the measurement cannot: that this game is filtered or
                // sanctioned here, or never loads. Then the direct line is never chosen for it --
                // the fastest of Amnezia, WireGuard, WARP and FENIX to the game's region is.
                com.mlmvpn.scanner.ui.settings.SettingsToggle(
                    title = stringResource(R.string.gb_blocked),
                    checked = selected.prefs.blocked,
                    onCheckedChange = { on ->
                        if (!GameBoostController.isActive) {
                            store.setPrefs(selected.id, selected.prefs.copy(blocked = on)); version++
                        }
                    },
                    icon = Icons.Default.Block,
                    tint = Ios.Red,
                    subtitle = stringResource(R.string.gb_blocked_sub),
                )
            }
            Separator()
            ValueRow(stringResource(R.string.gb_help_row), "", icon = Icons.Default.Bolt) {
                if (!GameBoostController.isActive || ui.phase == BoostPhase.ACTIVE) page = Page.HELP
            }
            Separator()
            ValueRow(stringResource(R.string.gb_advanced), "", icon = Icons.Default.Tune) { page = Page.ADVANCED }
            val shortcutGame = selected
            if (shortcutGame != null && installed && remember { BoostShortcut.supported(context) }) {
                Separator()
                ValueRow(stringResource(R.string.gb_add_shortcut), "", icon = Icons.Default.Add) {
                    val pkg = store.installedPackage(shortcutGame)
                    val ok = pkg != null && BoostShortcut.request(context, shortcutGame, pkg)
                    Toast.makeText(context, S(if (ok) R.string.gb_shortcut_requested else R.string.gb_shortcut_unsupported),
                        Toast.LENGTH_SHORT).show()
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        val measureOnlyShown = ui.phase == BoostPhase.ACTIVE && ui.outcome?.measureOnly == true
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmergencyDial(
                state = when (ui.phase) {
                    BoostPhase.IDLE -> DialState.IDLE
                    BoostPhase.MEASURING, BoostPhase.APPLYING -> DialState.STARTING
                    BoostPhase.ACTIVE -> if (measureOnlyShown) DialState.IDLE else DialState.RUNNING
                    BoostPhase.FAILED -> DialState.FAILED
                },
                idleAccent = GreenOk,
                idleIcon = Icons.Default.Bolt,
                idleLabel = stringResource(when {
                    ui.phase == BoostPhase.MEASURING || ui.phase == BoostPhase.APPLYING -> R.string.gb_measuring
                    !installed -> R.string.gb_measure
                    else -> R.string.gb_boost
                }),
                runningLabel = stringResource(R.string.gb_boosted),
                onClick = onDial,
            )
        }
        com.mlmvpn.scanner.ui.RouteChips()
        if (!installed && selected != null && ui.phase == BoostPhase.IDLE) {
            SettingsFooter(stringResource(R.string.gb_measure_only_hint, selected.name))
        }
        // What the last session's lag came with, and the one thing to do about it.
        var lastSessionVersion by remember { mutableStateOf(0) }
        val lastSession = remember(ui.phase, lastSessionVersion) {
            if (ui.phase == BoostPhase.IDLE) LastSession.read(context) else null
        }
        if (lastSession != null) {
            val gameName = games.firstOrNull { it.first.id == lastSession.gameId }?.first?.name ?: lastSession.gameId
            LastSessionCard(gameName, lastSession) {
                LastSession.dismiss(context)
                lastSessionVersion++
            }
        }
        // The one question, after a session that ran long enough to have an answer.
        var feedbackVersion by remember { mutableStateOf(0) }
        val pendingFeedback = remember(ui.phase, feedbackVersion) {
            if (ui.phase == BoostPhase.IDLE) com.mlmvpn.scanner.engines.game.booster.brain.Feedback.pending(context) else null
        }
        if (pendingFeedback != null) {
            val gameName = games.firstOrNull { it.first.id == pendingFeedback.gameId }?.first?.name ?: pendingFeedback.gameId
            FeedbackCard(gameName,
                onAnswer = { worked ->
                    com.mlmvpn.scanner.engines.game.booster.brain.Feedback.answer(context, worked)
                    Toast.makeText(context, S(R.string.gb_feedback_thanks), Toast.LENGTH_SHORT).show()
                    feedbackVersion++
                },
                onDismiss = {
                    com.mlmvpn.scanner.engines.game.booster.brain.Feedback.dismiss(context)
                    feedbackVersion++
                })
        }
        Spacer(Modifier.height(18.dp))

        if (ui.phase == BoostPhase.MEASURING || ui.phase == BoostPhase.APPLYING) {
            StageList(ui.stages, selected)
        }
        if (ui.phase == BoostPhase.FAILED) {
            SettingsFooter(stringResource(R.string.gb_error))
        }
        val outcome = ui.outcome
        if (ui.phase == BoostPhase.ACTIVE && outcome != null) {
            ui.live?.let { LiveCard(it) }
            Spacer(Modifier.height(12.dp))
            ResultCard(outcome)
            if (outcome.regions.count { it.answered } > 1 || outcome.regionAdvice?.switch == true ||
                outcome.regions.any { !it.answered }) {
                Spacer(Modifier.height(12.dp))
                RegionCard(outcome)
            }
            Spacer(Modifier.height(12.dp))
            ActionsRow(outcome, onStop = { GameBoostService.stop(context) }, onTryWarp = {
                selected?.let { g -> beginBoost(g, true, true) }
            })
            if (outcome.tips.isNotEmpty()) TipsCard(outcome.tips.take(3))
        }
        SettingsFooter(stringResource(R.string.gb_footer))
        Spacer(Modifier.height(24.dp))
    }

    if (confirmVpn) {
        IosAlert(
            title = stringResource(R.string.gb_confirm_vpn_title),
            message = stringResource(R.string.gb_confirm_vpn_text),
            actions = listOf(
                IosAlertAction(stringResource(R.string.gb_cancel), { confirmVpn = false; pendingWarp = false; launchAfter = null }),
                IosAlertAction(stringResource(R.string.gb_continue), {
                    confirmVpn = false
                    selected?.let { requestAndStart(it, pendingWarp) }
                }, preferred = true),
            ),
            onDismiss = { confirmVpn = false; pendingWarp = false; launchAfter = null },
        )
    }
}

private const val PREF_LAST_GAME = "gb_last_game"

// ── rows ────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun GameIcon(game: GameProfile, size: Int = 30) {
    val context = LocalContext.current
    val icon = remember(game.id) {
        game.packages.firstNotNullOfOrNull { pkg ->
            try { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (e: Exception) { null }
        }
    }
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size.dp).clip(BadgeShape))
    } else {
        Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) { Text(game.emoji, fontSize = (size - 8).sp) }
    }
}

@Composable
private fun GameRow(game: GameProfile?, installed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (game != null) GameIcon(game, 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(game?.name ?: stringResource(R.string.gb_game), color = Ios.Label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (game != null && !installed) {
                Text(stringResource(R.string.gb_not_installed), color = Ios.SecondaryLabel, fontSize = 12.sp)
            }
        }
        Chevron(20)
    }
}

@Composable
private fun ValueRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(title, color = Ios.Label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, color = Ios.SecondaryLabel, fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Chevron(18)
    }
}

/** Points the way navigation goes: right in English, left in Persian. */
@Composable
private fun Chevron(size: Int) {
    Icon(
        Icons.Default.ChevronRight, contentDescription = null, tint = Ios.Chevron,
        modifier = Modifier.size(size.dp)
            .scale(scaleX = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f, scaleY = 1f),
    )
}

@Composable
private fun regionLabel(game: GameProfile): String {
    val r = game.region(game.defaultRegion)
    return if (isFa()) r.labelFa else r.labelEn
}

private fun isFa(): Boolean = AppLocaleManager.isFarsi()

// ── measuring ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun StageList(stages: List<Stage>, game: GameProfile?) {
    val anchorName = game?.let { regionLabel(it) } ?: ""
    SettingsGroup {
        stages.forEachIndexed { i, st ->
            if (i > 0) Separator()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    when (st.status) {
                        StageStatus.RUNNING -> CircularProgressIndicator(strokeWidth = 2.dp, color = Ios.Blue, modifier = Modifier.size(18.dp))
                        StageStatus.DONE -> Icon(Icons.Default.Check, null, tint = GreenOk, modifier = Modifier.size(20.dp))
                        StageStatus.SKIPPED -> Icon(Icons.Default.Remove, null, tint = Ios.SecondaryLabel, modifier = Modifier.size(20.dp))
                        StageStatus.FAILED -> Icon(Icons.Default.Close, null, tint = Ios.Red, modifier = Modifier.size(20.dp))
                        StageStatus.PENDING -> Box(Modifier.size(8.dp).background(Ios.SecondaryLabel.copy(alpha = 0.4f), BadgeShape))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stageTitle(st.id, anchorName), color = if (st.status == StageStatus.PENDING) Ios.SecondaryLabel else Ios.Label, fontSize = 15.sp)
                    st.stats?.takeIf { it.answered }?.let {
                        Text(evidence(it), color = Ios.SecondaryLabel, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun stageTitle(id: StageId, anchor: String): String = when (id) {
    StageId.CHECK -> stringResource(R.string.gb_stage_check)
    StageId.REGION -> stringResource(R.string.gb_stage_region)
    StageId.DIRECT -> stringResource(R.string.gb_stage_direct, anchor)
    StageId.WARP -> stringResource(R.string.gb_stage_warp)
    StageId.DECIDE -> stringResource(R.string.gb_stage_decide)
    StageId.APPLY -> stringResource(R.string.gb_stage_apply)
}

@Composable
private fun evidence(s: PathStats): String =
    stringResource(R.string.gb_evidence, num(s.p50), ProbeStats.display(s.jitter), lossText(s))

private fun num(v: Int?): String = v?.toString() ?: "—"

private fun lossText(s: PathStats): String {
    val v = ProbeStats.display(s.loss)
    return if (s.lossIsMeaningful) v else "≈$v"
}

// ── result ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ResultCard(o: BoostOutcome) {
    val fa = isFa()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(CardShape).padding(16.dp)) {
        Text(verdictTitle(o), color = Ios.Label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        verdictDetail(o)?.let { Text(it, color = Ios.SecondaryLabel, fontSize = 13.sp) }

        o.symptom?.let { s ->
            Spacer(Modifier.height(8.dp))
            Text(symptomDone(s), color = Ios.Blue, fontSize = 13.sp)
        }
        // What kind of game this is on this line, and each part that is not simply open.
        o.kind?.let { k ->
            Spacer(Modifier.height(10.dp))
            Text(kindLine(k), color = if (k == GameKind.DIRECT) GreenOk else Ios.Orange, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        o.classLines.forEach { line ->
            Text("• ${classLineText(line, o)}", color = if (line.fixed) GreenOk else Ios.SecondaryLabel, fontSize = 13.sp)
        }
        (if (fa) o.noteFa else o.noteEn)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Ios.SecondaryLabel, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))

        // Before / after, on the same target, by the same method.
        Row(Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.weight(1.3f))
            listOf(R.string.gb_col_ping, R.string.gb_col_jitter, R.string.gb_col_loss).forEach {
                Text(stringResource(it), color = Ios.SecondaryLabel, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        val tunnelled = o.route.isWarp() || o.route == RouteKind.AMNEZIA || o.route == RouteKind.FENIX
        if (o.method != ProbeMethod.TLS_HANDSHAKE) {
            StatsRow(stringResource(R.string.gb_row_direct), o.direct, chosen = !tunnelled)
        }
        val tunnelLabel = when (o.route) {
            RouteKind.AMNEZIA -> R.string.gb_route_amnezia
            RouteKind.FENIX -> R.string.gb_route_fenix
            else -> R.string.gb_row_warp
        }
        o.warp?.let { StatsRow(stringResource(tunnelLabel), it, chosen = tunnelled) }
        Spacer(Modifier.height(8.dp))
        val anchor = if (fa) o.anchorLabelFa else o.anchorLabelEn
        Text(
            when {
                o.method == ProbeMethod.TLS_HANDSHAKE -> if (fa)
                    "زمان برقراری اتصال امن تا $anchor از داخل تونل؛ این عدد پینگ داخل بازی نیست."
                    else "Secure connection setup to $anchor through the tunnel; this is not in-game ping."
                o.method == ProbeMethod.ECHO -> stringResource(R.string.gb_target_echo, anchor)
                o.echoAvailable -> stringResource(R.string.gb_target_tcp, anchor)
                else -> stringResource(R.string.gb_target_tcp_no_echo, anchor)
            },
            color = Ios.SecondaryLabel, fontSize = 12.sp,
        )

        val claims = buildList {
            if (o.claims.lowerPing) add(stringResource(R.string.gb_claim_ping, num(o.claims.pingDeltaMs)))
            if (o.claims.steadier) add(stringResource(R.string.gb_claim_steadier))
            if (o.claims.lessLoss) add(stringResource(R.string.gb_claim_loss))
        }
        if (claims.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            claims.forEach { Text("✓ $it", color = GreenOk, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }

        Spacer(Modifier.height(10.dp))
        val notes = buildList {
            if (o.measureOnly) add(stringResource(R.string.gb_measure_only_note))
            else if (o.route == RouteKind.AMNEZIA || o.route == RouteKind.FENIX || o.route == RouteKind.WARP_WG) add(
                stringResource(R.string.gb_applied_route_tunnel, stringResource(when (o.route) {
                    RouteKind.AMNEZIA -> R.string.gb_route_amnezia
                    RouteKind.FENIX -> R.string.gb_route_fenix
                    else -> R.string.gb_route_wireguard
                }))
            )
            else add(stringResource(when {
                o.route.isWarp() -> R.string.gb_applied_route_warp
                o.route == RouteKind.DIRECT_DNS -> R.string.gb_applied_route_dns
                else -> R.string.gb_applied_route_direct
            }))
            val provider = if (fa) o.sanctionProviderFa else o.sanctionProviderEn
            if (provider != null) {
                add(stringResource(if (o.measureOnly) R.string.gb_sanction_would_fix else R.string.gb_sanction_fixed, provider))
            }
            if (o.sanctionUnfixed && provider == null) add(stringResource(R.string.gb_sanction_unfixed))
            if (o.warpYieldedToSignIn) add(stringResource(R.string.gb_warp_yielded))
            if (o.escalated) add(stringResource(R.string.gb_escalated))
            o.cellularBetterByMs?.let { add(stringResource(R.string.gb_cellular_better, it)) }
            if (o.warpOverTcp) add(stringResource(R.string.gb_warp_over_tcp))
            if (o.udpSilent) add(stringResource(R.string.gb_udp_silent))
            if (o.wifiPowerSaveOff) add(stringResource(R.string.gb_applied_wifi))
            if (o.crowdOkPct != null && o.crowdVotes != null) add(stringResource(R.string.gb_crowd_line, o.crowdOkPct, o.crowdVotes))
            when (o.warpNote) {
                WarpNote.SKIPPED_CROWD -> add(stringResource(R.string.gb_warp_skipped_crowd))
                WarpNote.SKIPPED_MEMORY -> add(stringResource(R.string.gb_warp_skipped_memory))
                WarpNote.SKIPPED_CHOICE -> add(stringResource(R.string.gb_warp_skipped_choice))
                WarpNote.NOT_READY -> add(stringResource(R.string.gb_warp_not_ready))
                WarpNote.FAILED -> add(stringResource(R.string.gb_warp_failed))
                else -> Unit
            }
            when (o.access) {
                AccessState.FIXED_BY_DNS -> add(stringResource(R.string.gb_access_dns))
                AccessState.BLOCKED -> add(stringResource(R.string.gb_access_blocked))
                else -> Unit
            }
            if (o.applyFellBack) add(stringResource(R.string.gb_fell_back))
            if (o.fromMemory) add(stringResource(R.string.gb_from_memory))
        }
        notes.forEach { Text("• $it", color = Ios.SecondaryLabel, fontSize = 13.sp) }
    }
}

@Composable
private fun StatsRow(label: String, s: PathStats?, chosen: Boolean) {
    val c = if (chosen) GreenOk else Ios.Label
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c, fontSize = 15.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1.3f))
        Text(s?.p50?.let { "$it" } ?: "—", color = c, fontSize = 15.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(s?.jitter?.let { ProbeStats.display(it) } ?: "—", color = c, fontSize = 15.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(s?.let { lossText(it) + "%" } ?: "—", color = c, fontSize = 15.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun verdictTitle(o: BoostOutcome): String = stringResource(when {
    o.measureOnly -> R.string.gb_verdict_measured
    o.verdict == VerdictKind.LEARNED -> R.string.gb_verdict_learned
    o.verdict == VerdictKind.NOTHING_WORKS -> R.string.gb_verdict_nothing
    o.verdict == VerdictKind.MUST_TUNNEL -> R.string.gb_verdict_must
    o.route.isWarp() -> R.string.gb_verdict_switch
    o.verdict == VerdictKind.UNMEASURED -> R.string.gb_verdict_unmeasured
    o.route == RouteKind.DIRECT_DNS -> R.string.gb_verdict_dns
    else -> R.string.gb_verdict_stay
})

@Composable
private fun verdictDetail(o: BoostOutcome): String? = when {
    o.measureOnly -> null
    o.verdict == VerdictKind.UNMEASURED -> stringResource(R.string.gb_verdict_unmeasured_detail)
    o.verdict == VerdictKind.STAY_DIRECT && o.route == RouteKind.DIRECT -> stringResource(R.string.gb_verdict_stay_detail)
    else -> null
}

@Composable
private fun kindLine(k: GameKind): String = stringResource(when (k) {
    GameKind.DIRECT -> R.string.gb_kind_direct
    GameKind.SANCTIONED -> R.string.gb_kind_sanctioned
    GameKind.FILTERED -> R.string.gb_kind_filtered
    GameKind.PARTIAL -> R.string.gb_kind_partial
    GameKind.BOTH -> R.string.gb_kind_both
})

@Composable
private fun classLineText(line: ClassLine, o: BoostOutcome): String {
    val part = stringResource(when (line.cls) {
        TrafficClass.LOGIN -> R.string.gb_cls_login
        TrafficClass.API -> R.string.gb_cls_api
        TrafficClass.CDN -> R.string.gb_cls_cdn
        TrafficClass.STORE -> R.string.gb_cls_store
        TrafficClass.SOCIAL -> R.string.gb_cls_social
        TrafficClass.VOICE -> R.string.gb_cls_voice
        TrafficClass.GAMEPLAY -> R.string.gb_cls_gameplay
    })
    val what = stringResource(when (line.obstacle) {
        Obstacle.DNS_POISONED -> R.string.gb_obs_dns
        Obstacle.SNI_BLOCKED, Obstacle.IP_BLOCKED -> R.string.gb_obs_filtered
        Obstacle.GEO_BLOCKED -> R.string.gb_obs_geo
        Obstacle.OK -> R.string.gb_obs_ok
        Obstacle.UNKNOWN -> R.string.gb_obs_unknown
    })
    val base = stringResource(R.string.gb_cls_line, part, what)
    if (!line.fixed) return base
    val by = when (line.fixedBy) {
        FixedBy.SANCTION_DNS -> (if (isFa()) o.sanctionProviderFa else o.sanctionProviderEn)
            ?.let { stringResource(R.string.gb_fixed_by, it) }
        FixedBy.CLEAN_DNS -> stringResource(R.string.gb_fixed_by_dns)
        FixedBy.FRAGMENT -> stringResource(R.string.gb_fixed_by_fragment)
        FixedBy.WARP -> stringResource(R.string.gb_fixed_by, "WARP")
        null -> null
    }
    return if (by != null) "$base — $by" else base
}

@Composable
private fun symptomDone(s: Symptom): String = stringResource(when (s) {
    Symptom.CANT_CONNECT -> R.string.gb_symdone_cant_connect
    Symptom.HIGH_PING -> R.string.gb_symdone_high_ping
    Symptom.LAG -> R.string.gb_symdone_lag
    Symptom.HIT_REG -> R.string.gb_symdone_hit_reg
    Symptom.DISCONNECTS -> R.string.gb_symdone_disconnects
    Symptom.UPDATE -> R.string.gb_symdone_update
})

/** «چه مشکلی داری؟»: one tap per problem, each running the thorough boost that problem needs. */
@Composable
private fun HelpPage(onPick: (Symptom) -> Unit, onBack: () -> Unit) {
    IosScreen(title = stringResource(R.string.gb_help_title), onBack = onBack, backLabel = stringResource(R.string.gb_title)) {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            listOf(
                Symptom.CANT_CONNECT to R.string.gb_sym_cant_connect,
                Symptom.HIGH_PING to R.string.gb_sym_high_ping,
                Symptom.LAG to R.string.gb_sym_lag,
                Symptom.HIT_REG to R.string.gb_sym_hit_reg,
                Symptom.DISCONNECTS to R.string.gb_sym_disconnects,
                Symptom.UPDATE to R.string.gb_sym_update,
            ).forEachIndexed { i, (s, label) ->
                if (i > 0) Separator()
                Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(label), color = Ios.Label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Chevron(18)
                }
            }
        }
        SettingsFooter(stringResource(R.string.gb_help_footer))
    }
}

@Composable
private fun causeText(c: SpikeWatch.Cause): String = stringResource(when (c) {
    SpikeWatch.Cause.BACKGROUND -> R.string.gb_cause_background
    SpikeWatch.Cause.WIFI_SIGNAL -> R.string.gb_cause_wifi_signal
    SpikeWatch.Cause.WIFI_RATE -> R.string.gb_cause_wifi_rate
    SpikeWatch.Cause.HEAT -> R.string.gb_cause_heat
    SpikeWatch.Cause.LINE -> R.string.gb_cause_line
})

@Composable
private fun LastSessionCard(gameName: String, s: LastSession.Summary, onDismiss: () -> Unit) {
    val minutes = (s.durationMs / 60_000L).toInt().coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).frostedGlass(CardShape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gb_last_session_title, gameName), color = Ios.Label, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Close, null, tint = Ios.SecondaryLabel, modifier = Modifier.size(20.dp).clickable(onClick = onDismiss))
        }
        Spacer(Modifier.height(4.dp))
        if (s.spikes == 0) {
            Text(stringResource(R.string.gb_last_session_clean, minutes), color = GreenOk, fontSize = 14.sp)
        } else {
            Text(stringResource(R.string.gb_last_session_line, s.spikes, minutes), color = Ios.Label, fontSize = 14.sp)
            s.mainCause?.let { c ->
                Text(stringResource(R.string.gb_last_session_cause, causeText(c)), color = Ios.Orange, fontSize = 14.sp)
                Text(stringResource(when (c) {
                    SpikeWatch.Cause.BACKGROUND -> R.string.gb_fix_background
                    SpikeWatch.Cause.WIFI_SIGNAL, SpikeWatch.Cause.WIFI_RATE -> R.string.gb_fix_wifi
                    SpikeWatch.Cause.HEAT -> R.string.gb_fix_heat
                    SpikeWatch.Cause.LINE -> R.string.gb_fix_line
                }), color = Ios.SecondaryLabel, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FeedbackCard(gameName: String, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).frostedGlass(CardShape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gb_feedback_title, gameName), color = Ios.Label, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Close, null, tint = Ios.SecondaryLabel,
                modifier = Modifier.size(20.dp).clickable(onClick = onDismiss))
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.gb_feedback_detail), color = Ios.SecondaryLabel, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).clip(CardShape).background(GreenOk).clickable { onAnswer(true) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.gb_feedback_yes), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.weight(1f).clip(CardShape).frostedGlass(CardShape).clickable { onAnswer(false) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.gb_feedback_no), color = Ios.Red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The game's regions as the sweep saw them, and which one to pick inside the game. In 2026 the
 * most useful line on the card: the Middle East servers most players knew are gone.
 */
@Composable
private fun RegionCard(o: BoostOutcome) {
    val fa = isFa()
    fun label(key: String?): String {
        val r = o.regions.firstOrNull { it.key == key }
        return r?.let { if (fa) it.labelFa else it.labelEn } ?: (key ?: "")
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(CardShape).padding(16.dp)) {
        Text(stringResource(R.string.gb_region_title), color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        o.regionAdvice?.let { a ->
            val text = when {
                a.fromCrowd -> stringResource(R.string.gb_region_advice_crowd, label(a.best), a.bestP50 ?: 0)
                a.switch && !a.currentAnswered -> stringResource(R.string.gb_region_advice_down, label(a.current), label(a.best))
                a.switch -> stringResource(R.string.gb_region_advice, label(a.best), a.gainMs ?: 0, label(a.current))
                else -> stringResource(R.string.gb_region_ok, label(a.best))
            }
            Text(text, color = if (a.switch) Ios.Orange else GreenOk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }
        o.regions.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (fa) r.labelFa else r.labelEn, color = Ios.Label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    when {
                        r.answered && r.p50 != null -> stringResource(R.string.gb_ms, r.p50)
                        r.status == RegionCatalog.Status.DOWN -> stringResource(R.string.gb_region_status_down)
                        else -> stringResource(R.string.gb_region_no_answer)
                    },
                    color = if (r.answered) Ios.SecondaryLabel else Ios.Red, fontSize = 14.sp,
                )
            }
        }
    }
}

private fun RouteKind.isWarp() = this == RouteKind.WARP_MASQUE_H3 || this == RouteKind.WARP_MASQUE_H2 || this == RouteKind.WARP_WG

@Composable
private fun ActionsRow(o: BoostOutcome, onStop: () -> Unit, onTryWarp: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val launch = o.gamePackage?.let { context.packageManager.getLaunchIntentForPackage(it) }
        if (!o.measureOnly) {
            Box(
                Modifier.weight(1f).clip(CardShape).background(GreenOk).clickable(enabled = launch != null) {
                    launch?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }.padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.gb_launch_game), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
        Box(
            Modifier.weight(1f).clip(CardShape).frostedGlass(CardShape).clickable(onClick = onStop).padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(if (o.measureOnly) R.string.gb_close else R.string.gb_stop),
                color = if (o.measureOnly) Ios.Blue else Ios.Red, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    // The test points were silent, or only WARP answered: the direct line was kept on purpose,
    // and WARP is one tap away if the game does not connect.
    if (o.offerWarp) {
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(CardShape).frostedGlass(CardShape)
                .clickable(onClick = onTryWarp).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.gb_try_warp), color = Ios.Blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun TipsCard(tips: List<Preflight.Tip>) {
    val context = LocalContext.current
    SettingsSectionHeader(stringResource(R.string.gb_tips_title))
    SettingsGroup {
        tips.forEachIndexed { i, t ->
            if (i > 0) Separator()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(t.titleRes), color = if (t.severity == Preflight.Severity.BAD) Ios.Red else Ios.Label, fontSize = 15.sp)
                    Text(stringResource(t.detailRes, *t.detailArgs.toTypedArray()), color = Ios.SecondaryLabel, fontSize = 12.sp)
                }
                val fix = Preflight.intentFor(t)
                if (fix != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.gb_fix), color = Ios.Blue, fontSize = 14.sp,
                        modifier = Modifier.clickable { try { context.startActivity(fix) } catch (_: Exception) {} }.padding(6.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

// ── live ───────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LiveCard(live: LiveReading) {
    val color = when (live.status) {
        LiveStatus.GOOD -> GreenOk
        LiveStatus.DEGRADED, LiveStatus.UNSTABLE -> Ios.Orange
        LiveStatus.LOSSY, LiveStatus.DOWN -> Ios.Red
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(CardShape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(live.p50?.toString() ?: "—", color = color, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("ms", color = Ios.SecondaryLabel, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            Text(stringResource(when (live.status) {
                LiveStatus.GOOD -> R.string.gb_live_good
                LiveStatus.DEGRADED -> R.string.gb_live_degraded
                LiveStatus.UNSTABLE -> R.string.gb_live_unstable
                LiveStatus.LOSSY -> R.string.gb_live_lossy
                LiveStatus.DOWN -> R.string.gb_live_down
            }), color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("${stringResource(R.string.gb_col_jitter)} ${ProbeStats.display(live.jitter)} · " +
            "${stringResource(R.string.gb_col_loss)} ${ProbeStats.display(live.loss)}${if (live.lossMeaningful) "" else "≈"}%",
            color = Ios.SecondaryLabel, fontSize = 13.sp)
        live.lastCause?.let { cause ->
            Text(stringResource(R.string.gb_live_last_spike, causeText(cause)), color = Ios.Orange, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Sparkline(live.series, color)
    }
}

@Composable
private fun Sparkline(points: List<Int?>, color: Color) {
    val values = points.filterNotNull()
    if (values.size < 2) return
    val lo = values.min().toFloat()
    val hi = values.max().toFloat().coerceAtLeast(lo + 10f)
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)
        var prev: Offset? = null
        points.forEachIndexed { i, v ->
            if (v == null) { prev = null; return@forEachIndexed }
            val y = size.height - (v - lo) / (hi - lo) * size.height
            val p = Offset(i * stepX, y)
            prev?.let { drawLine(color, it, p, strokeWidth = 3f, cap = StrokeCap.Round) }
            prev = p
        }
    }
}

// ── pages ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun GamePickerPage(
    games: List<Pair<GameProfile, Boolean>>, selectedId: String?,
    onPick: (String) -> Unit, onAdd: () -> Unit, onBack: () -> Unit,
) {
    IosScreen(title = stringResource(R.string.gb_game), onBack = onBack, backLabel = stringResource(R.string.gb_title)) {
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            Row(Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null, tint = Ios.Blue, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.gb_add_game), color = Ios.Blue, fontSize = 16.sp)
            }
        }
        listOf(true to R.string.gb_installed, false to R.string.gb_not_installed).forEach { (inst, header) ->
            val rows = games.filter { it.second == inst }
            if (rows.isEmpty()) return@forEach
            SettingsSectionHeader(stringResource(header))
            SettingsGroup {
                rows.forEachIndexed { i, (g, _) ->
                    if (i > 0) Separator()
                    Row(Modifier.fillMaxWidth().clickable { onPick(g.id) }.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        GameIcon(g, 28)
                        Spacer(Modifier.width(12.dp))
                        Text(g.name, color = Ios.Label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (g.id == selectedId) Icon(Icons.Default.Check, null, tint = Ios.Blue, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RegionPickerPage(game: GameProfile, onPick: (String) -> Unit, onBack: () -> Unit) {
    val fa = isFa()
    IosScreen(title = stringResource(R.string.gb_region), onBack = onBack, backLabel = stringResource(R.string.gb_title)) {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            game.regions.forEachIndexed { i, r ->
                if (i > 0) Separator()
                Row(Modifier.fillMaxWidth().clickable { onPick(r.key) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (fa) r.labelFa else r.labelEn, color = Ios.Label, fontSize = 16.sp)
                        // Known outages are said up front: in 2026 the Gulf datacentres went dark.
                        if (RegionCatalog.find(r.anchorRegion)?.status == RegionCatalog.Status.DOWN) {
                            Text(stringResource(R.string.gb_region_status_down), color = Ios.Red, fontSize = 12.sp)
                        }
                    }
                    if (r.key == game.defaultRegion) Icon(Icons.Default.Check, null, tint = Ios.Blue, modifier = Modifier.size(19.dp))
                }
            }
        }
        SettingsFooter(stringResource(R.string.gb_region_footer))
    }
}

@Composable
private fun AddGamePage(existing: Set<String>, onPick: (String, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val (apps, loading) = rememberInstalledApps(emptySet())
    // Games first (the ones that tell the system they are games), then everything else; never
    // this app, and nothing already on the list. Sorted off the main thread: it asks the package
    // manager once per installed app.
    var sorted by remember { mutableStateOf<List<AppInfo>?>(null) }
    LaunchedEffect(apps, loading) {
        sorted = null
        sorted = withContext(Dispatchers.Default) {
            val pm = context.packageManager
            apps.filter { it.packageName != context.packageName && it.packageName !in existing }
                .map { app ->
                    val game = try {
                        android.os.Build.VERSION.SDK_INT >= 26 &&
                            pm.getApplicationInfo(app.packageName, 0).category == android.content.pm.ApplicationInfo.CATEGORY_GAME
                    } catch (e: Exception) { false }
                    app to game
                }
                .sortedByDescending { it.second }
                .map { it.first }
        }
    }
    val list = sorted.orEmpty()
    AppPickerPage(
        apps = list, isLoading = loading || sorted == null, selected = emptySet(),
        backLabel = stringResource(R.string.gb_game), onBack = onBack,
        onToggle = { pkg -> onPick(pkg, list.firstOrNull { it.packageName == pkg }?.name ?: pkg) },
        title = stringResource(R.string.gb_add_game),
    )
}

@Composable
private fun AdvancedPage(
    game: GameProfile, onChange: (com.mlmvpn.scanner.engines.game.booster.model.RoutePrefs) -> Unit,
    onRemove: () -> Unit, onBack: () -> Unit,
) {
    val context = LocalContext.current
    var p by remember(game.id) { mutableStateOf(game.prefs) }
    fun set(n: com.mlmvpn.scanner.engines.game.booster.model.RoutePrefs) { p = n; onChange(n) }
    IosScreen(title = stringResource(R.string.gb_advanced), onBack = onBack, backLabel = stringResource(R.string.gb_title)) {
        SettingsSectionHeader(stringResource(R.string.gb_adv_route))
        SettingsGroup {
            listOf(
                RouteChoice.AUTO to R.string.gb_adv_route_auto,
                RouteChoice.DIRECT to R.string.gb_adv_route_direct,
                RouteChoice.WARP to R.string.gb_adv_route_warp,
                RouteChoice.WIREGUARD to R.string.gb_adv_route_wireguard,
                RouteChoice.AMNEZIA to R.string.gb_adv_route_amnezia,
                RouteChoice.FENIX to R.string.gb_adv_route_fenix,
            ).forEachIndexed { i, (choice, label) ->
                if (i > 0) Separator()
                CheckRow(stringResource(label), p.route == choice) { set(p.copy(route = choice)) }
            }
        }
        SettingsFooter(stringResource(R.string.gb_adv_route_footer))

        SettingsSectionHeader(stringResource(R.string.gb_adv_depth))
        SettingsGroup {
            CheckRow(stringResource(R.string.gb_adv_depth_quick), p.depth == ProbeDepth.QUICK) { set(p.copy(depth = ProbeDepth.QUICK)) }
            Separator()
            CheckRow(stringResource(R.string.gb_adv_depth_thorough), p.depth == ProbeDepth.THOROUGH) { set(p.copy(depth = ProbeDepth.THOROUGH)) }
        }

        SettingsSectionHeader(stringResource(R.string.gb_adv_sdns))
        SettingsGroup {
            CheckRow(stringResource(R.string.gb_adv_sdns_on), p.sanctionDns) { set(p.copy(sanctionDns = !p.sanctionDns)) }
        }
        SettingsFooter(stringResource(R.string.gb_adv_sdns_footer))

        SettingsSectionHeader(stringResource(R.string.gb_adv_overlay))
        SettingsGroup {
            CheckRow(stringResource(R.string.gb_adv_overlay_on), p.overlay) {
                val on = !p.overlay
                set(p.copy(overlay = on))
                if (on && !PingOverlay.canDraw(context)) {
                    // Android grants "display over other apps" only from its own settings screen.
                    Toast.makeText(context, S(R.string.gb_overlay_permission), Toast.LENGTH_LONG).show()
                    try {
                        context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }
            }
        }
        SettingsFooter(stringResource(R.string.gb_adv_overlay_footer))

        // One switch for the whole booster, not per game: it is about the player, not the game.
        var sharing by remember { mutableStateOf(com.mlmvpn.scanner.engines.game.booster.crowd.CrowdClient.sharing(context)) }
        SettingsSectionHeader(stringResource(R.string.gb_adv_learning))
        SettingsGroup {
            CheckRow(stringResource(R.string.gb_adv_share), sharing) {
                sharing = !sharing
                com.mlmvpn.scanner.engines.game.booster.crowd.CrowdClient.setSharing(context, sharing)
            }
        }
        SettingsFooter(stringResource(R.string.gb_adv_share_footer))

        Spacer(Modifier.height(20.dp))
        SettingsGroup {
            Row(Modifier.fillMaxWidth().clickable {
                RouteMemoryStore(context).clear()
                com.mlmvpn.scanner.engines.game.booster.brain.BrainStore(context).clear()
                com.mlmvpn.scanner.engines.game.booster.doctor.GameDoctor.forget()
                com.mlmvpn.scanner.engines.game.booster.paths.SanctionDns.forget()
                Toast.makeText(context, S(R.string.gb_adv_forget_done), Toast.LENGTH_SHORT).show()
            }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.gb_adv_forget), color = Ios.Blue, fontSize = 16.sp)
            }
            if (game.isCustom) {
                Separator()
                Row(Modifier.fillMaxWidth().clickable(onClick = onRemove).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.gb_adv_remove_game), color = Ios.Red, fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CheckRow(title: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Ios.Label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (checked) Icon(Icons.Default.Check, null, tint = Ios.Blue, modifier = Modifier.size(19.dp))
    }
}
