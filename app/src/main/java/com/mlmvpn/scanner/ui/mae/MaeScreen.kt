package com.mlmvpn.scanner.ui.mae

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.mlmvpn.scanner.ui.collectWhileVisible
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.mae.MaeEngine
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.engines.mae.policy.RepairLadder
import com.mlmvpn.scanner.engines.mae.policy.Symptom
import com.mlmvpn.scanner.engines.mae.route.DirectRoute
import com.mlmvpn.scanner.engines.mae.route.FragmentRoute
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.ServerlessRoute
import com.mlmvpn.scanner.engines.mae.route.UsExitRoute
import com.mlmvpn.scanner.engines.mae.route.UserConfigRoute
import com.mlmvpn.scanner.engines.mae.route.WarpRoute
import com.mlmvpn.scanner.engines.mae.route.WorkerRoute
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader

/** A service's display name: its localized string, or the domain for a custom site. */
fun serviceName(context: Context, def: ServiceDef): String {
    if (def.custom) return def.displayName
    val id = context.resources.getIdentifier("mae_service_${def.nameKey}", "string", context.packageName)
    return if (id != 0) context.getString(id) else def.displayName
}

/** A route in the words the user knows -- never an id like `cfg-3a9f1c`. */
fun routeName(context: Context, routeId: String?, family: FamilyPolicy? = null, country: String? = null): String {
    if (routeId == null) return "—"
    val base = when {
        routeId == DirectRoute.id -> context.getString(R.string.mae_route_direct)
        routeId == ServerlessRoute.id || routeId == FragmentRoute.id -> context.getString(R.string.mae_route_bypass)
        routeId == WarpRoute.ID -> context.getString(R.string.mae_route_warp)
        routeId == WorkerRoute.ID -> context.getString(R.string.mae_route_worker)
        routeId == UsExitRoute.ID -> context.getString(R.string.mae_route_usexit)
        routeId.startsWith(UserConfigRoute.CLOUD_PREFIX) -> context.getString(R.string.mae_route_cloud)
        com.mlmvpn.scanner.engines.flux.FluxRoute.isFluxRoute(routeId) -> context.getString(R.string.flux_title_short)
        routeId == com.mlmvpn.scanner.engines.fenix.FenixRoute.ID -> context.getString(R.string.fenix_title)
        routeId == com.mlmvpn.scanner.engines.fenix.FenixRoute.US_ID ->
            context.getString(R.string.fenix_title) + com.mlmvpn.scanner.store.tr(" · آمریکا", " · US")
        routeId == com.mlmvpn.core.lantern.LanternRoute.ID -> context.getString(R.string.home_lantern)
        routeId == com.mlmvpn.core.urnetwork.UrnetworkRoute.ID -> context.getString(R.string.home_urnetwork)
        com.mlmvpn.scanner.engines.outline.OutlineRoute.isOutlineRoute(routeId) -> context.getString(R.string.outline_title)
        com.mlmvpn.scanner.engines.amnezia.WgExitRoute.isProton(routeId) -> com.mlmvpn.scanner.store.tr("پروتون", "Proton")
        com.mlmvpn.scanner.engines.amnezia.WgExitRoute.isWindscribe(routeId) -> com.mlmvpn.scanner.store.tr("ویندسکرایب", "Windscribe")
        routeId.startsWith(UserConfigRoute.PREFIX) -> context.getString(R.string.mae_route_config)
        else -> routeId
    }
    // The family matters where it was chosen for a reason: direct and exits abroad.
    val fam = if (routeId == DirectRoute.id || routeId == WorkerRoute.ID || UserConfigRoute.isConfigRoute(routeId)) when (family) {
        FamilyPolicy.V4_ONLY -> " · IPv4"
        FamilyPolicy.V6_ONLY -> " · IPv6"
        else -> ""
    } else ""
    return base + fam + (country?.let { " · ${flag(it)}" } ?: "")
}

/** A country's flag from its two-letter code. */
private fun flag(cc: String): String =
    if (cc.length != 2 || !cc.all { it.isLetter() }) cc
    else cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

private enum class Page { MAIN, MANAGE, PICKER, DIAGNOSTICS }

/**
 * MAE's screen. First run: onboarding ("which apps do you use most?"). After that: one connect
 * button and the user's apps, each with its route in plain words, what just happened to it, and
 * "opened / didn't open". Everything technical sits on the diagnostics page, reached by a long
 * press on the status line.
 */
@Composable
fun MaeScreen(onBack: () -> Unit, onOpenTile: (String) -> Unit = {}) {
    val context = LocalContext.current
    val ready by MaeEngine.ready.collectAsState()
    // The registry and the state are read off the main thread: the file grows with every network.
    LaunchedEffect(Unit) { MaeEngine.initInBackground(context) }
    if (!ready) {
        IosScreen(title = stringResource(R.string.mae_title), onBack = onBack, backLabel = stringResource(R.string.home)) {
            Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Ios.Blue)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.mae_loading), color = Ios.SecondaryLabel, fontSize = 14.sp)
                }
            }
        }
        return
    }
    MaeContent(onBack, onOpenTile)
}

@Composable
private fun MaeContent(onBack: () -> Unit, onOpenTile: (String) -> Unit) {
    val context = LocalContext.current
    // Only whether onboarding is done: the whole state changes many times a second during checks.
    val onboarded by remember { MaeEngine.store.state.map { it.onboarded }.distinctUntilChanged() }
        .collectAsState(MaeEngine.store.current.onboarded)
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    val ask by MaeEngine.pendingAsk.collectAsState()

    // The Cloudflare account comes first: MAE's foreign exits all live on it. Shared with the
    // Cloud tab, so an account added in either place is the other's too. Null while loading.
    val cloud = remember { com.mlmvpn.scanner.data.CloudManager(context) }
    val accounts by cloud.accountsFlow.collectAsState()
    val hasAccount by produceState<Boolean?>(null, accounts) {
        value = accounts.isNotEmpty() || withContext(Dispatchers.IO) { cloud.loadedAccounts().isNotEmpty() }
    }
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val nodeId by MyVpnService.connectedNodeIdFlow.collectAsState()
    val ours = nodeId == MaeEngine.NODE_ID
    // A tunnel already up is never hidden behind the gate: it must stay possible to disconnect.
    val live = ours && phase != MyVpnService.Phase.IDLE
    when (hasAccount) {
        null -> {
            IosScreen(title = stringResource(R.string.mae_title), onBack = onBack, backLabel = stringResource(R.string.home)) {
                Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Ios.Blue)
                }
            }
            return
        }
        false -> if (!live) { MaeCloudGate(onBack); return }
        true -> Unit
    }

    ask?.let { a ->
        SymptomDialog(a, onAnswer = { symptom -> MaeEngine.answerSymptom(a.serviceId, symptom) }, onDismiss = { MaeEngine.cancelAsk() })
    }

    if (!onboarded) {
        MaeOnboarding(onBack = onBack, onDone = { ids, sites ->
            MaeEngine.setSelection(ids)
            sites.forEach { MaeEngine.addSite(it) }
        }, onAddApp = { ids, sites ->
            // The apps first, then the sites: setSelection replaces the list, so sites added
            // before it were silently dropped.
            MaeEngine.setSelection(ids)
            sites.forEach { MaeEngine.addSite(it) }
            page = Page.PICKER
        })
        return
    }
    // System Back closes a MAE sub-page before it leaves MAE.
    androidx.activity.compose.BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
    when (page) {
        Page.MANAGE -> { MaeManageScreen(onBack = { page = Page.MAIN }, onOpenTile = onOpenTile); return }
        Page.PICKER -> { MaeManageScreen(onBack = { page = Page.MAIN }, openPicker = true, onOpenTile = onOpenTile); return }
        Page.DIAGNOSTICS -> { MaeDiagnosticsScreen(onBack = { page = Page.MAIN }); return }
        Page.MAIN -> Unit
    }

    val testing by MaeEngine.testingFlow.collectAsState()
    // Collected only while this page is on screen: the rows are worked out by the engine for
    // whoever is subscribed, and a parked tab or a backgrounded app used to keep that going.
    val views by MaeEngine.viewsFlow.collectWhileVisible()
    val connected = ours && phase == MyVpnService.Phase.CONNECTED
    val connecting = ours && phase == MyVpnService.Phase.CONNECTING
    // FENIX, Proton and Windscribe not set up yet: named under an app that found no exit abroad, a
    // tap from their own tile -- one opens at once, several are offered to choose from.
    val unset by produceState(emptyList<SetupExit>(), connected) {
        value = withContext(Dispatchers.IO) { setupExits(context).filter { !it.ready } }
    }
    var setUpFor by remember { mutableStateOf<String?>(null) }
    // Set on the tap, cleared when the tunnel's state moves: building the config takes a moment,
    // and a button that shows nothing invites a second tap.
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(phase, nodeId) { busy = false }
    LaunchedEffect(busy) { if (busy) { delay(12_000); busy = false } }
    // One row open at a time, as in iOS lists: opening another closes the last.
    var openRow by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<ServiceDef?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) MaeEngine.startTunnelAsync(context) else busy = false
    }
    // False when a running scan holds the tap for the user's answer: then nothing is starting yet.
    fun connect(): Boolean = com.mlmvpn.scanner.data.ScanGuard.run(com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN) {
        val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
        if (prep != null) launcher.launch(prep) else MaeEngine.startTunnelAsync(context)
    }

    removing?.let { def ->
        MaeConfirmRemove(serviceName(context, def), stringResource(R.string.mae_remove_confirm_body),
            onConfirm = { MaeEngine.remove(def.id) }, onDismiss = { removing = null })
    }
    setUpFor?.let { app ->
        com.mlmvpn.scanner.ui.settings.IosAlert(
            title = com.mlmvpn.scanner.store.tr("کدام را راه‌اندازی کنیم؟", "Which one to set up?"),
            message = com.mlmvpn.scanner.store.tr(
                "برای $app هنوز مسیر خارجی سالمی پیدا نشده. هر کدام از این‌ها را راه‌اندازی کنید، MAE خودش می‌سنجد کدام برای هر برنامه بهتر کار می‌کند؛ هرچه بیشتر، احتمال پیدا شدن راه بیشتر.",
                "No working route abroad has been found for $app yet. Set up any of these and MAE measures by itself which works best for each app; the more of them, the likelier a way is found.",
            ),
            actions = unset.map { e ->
                com.mlmvpn.scanner.ui.settings.IosAlertAction(e.title, onClick = { setUpFor = null; onOpenTile(e.tile) })
            } + com.mlmvpn.scanner.ui.settings.IosAlertAction(stringResource(R.string.mae_cancel), onClick = { setUpFor = null }, preferred = true),
            onDismiss = { setUpFor = null },
        )
    }

    IosScreen(
        title = stringResource(R.string.mae_title), onBack = onBack, backLabel = stringResource(R.string.home),
        // Pull down to check every app again -- the gesture iOS lists refresh with.
        onRefresh = { MaeEngine.recheckAll(); delay(600) },
    ) {
        Spacer(Modifier.height(24.dp))
        ConnectButton(connected, connecting || busy) {
            if (busy) return@ConnectButton
            if (connected || connecting) { busy = true; MaeEngine.stopTunnelAsync(context) } else busy = connect()
        }
        Spacer(Modifier.height(12.dp))
        val active = views.count { !it.paused }
        val readyCount = views.count { it.phase == MaeEngine.Phase.READY }
        Text(
            text = when {
                connected && testing.isNotEmpty() -> stringResource(R.string.mae_status_checking, testing.size)
                connected -> stringResource(R.string.mae_status_connected, readyCount, active)
                connecting || busy -> stringResource(R.string.mae_connecting)
                testing.isNotEmpty() -> stringResource(R.string.mae_status_checking, testing.size)
                else -> stringResource(R.string.mae_status_idle)
            },
            color = Ios.SecondaryLabel,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            // A developer's door, not a button: no click role for a screen reader to announce.
            modifier = Modifier.fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onLongPress = { page = Page.DIAGNOSTICS }) },
        )
        com.mlmvpn.scanner.ui.RouteChips()

        // The section's own action on its trailing edge, where iOS puts "Edit" or "See All".
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 22.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.mae_your_services), color = Ios.SecondaryLabel, fontSize = 13.sp, modifier = Modifier.weight(1f))
            val idle = views.any { !it.paused } && testing.size < views.count { !it.paused }
            Row(
                Modifier.clip(RoundedCornerShape(50)).then(if (idle) Modifier.combinedClickableCompat { MaeEngine.recheckAll() } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = if (idle) Ios.Blue else Ios.SecondaryLabel, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.mae_recheck_all), color = if (idle) Ios.Blue else Ios.SecondaryLabel, fontSize = 14.sp)
            }
        }
        SettingsGroup {
            views.forEachIndexed { i, v ->
                if (i > 0) Separator()
                androidx.compose.runtime.key(v.def.id) {
                    SwipeActionsRow(
                        id = v.def.id,
                        openId = openRow,
                        onOpenChange = { openRow = it },
                        onDelete = { removing = v.def },
                        onRecheck = { MaeEngine.recheck(v.def.id) },
                    ) {
                        ServiceRow(v, connected, testing = v.def.id in testing,
                            onRecheck = { MaeEngine.recheck(v.def.id) },
                            onOk = {
                                MaeEngine.feedbackOk(v.def.id)
                                if (connected) Toast.makeText(context, context.getString(R.string.mae_thanks), Toast.LENGTH_SHORT).show()
                            }, onFail = { MaeEngine.feedbackFailed(v.def.id) },
                            setUp = unset,
                            onSetUp = { if (unset.size == 1) onOpenTile(unset[0].tile) else setUpFor = serviceName(context, v.def) })
                    }
                }
            }
        }
        SettingsFooter(stringResource(R.string.mae_swipe_hint))
        SettingsGroup(modifier = Modifier.padding(top = 14.dp)) {
            SettingsRow(title = stringResource(R.string.mae_manage), icon = Icons.Default.Tune, tint = Ios.Blue,
                onClick = { page = Page.MANAGE })
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * A row that swipes like one in iOS's Phone app: to the left it shows Delete, to the right Check
 * again. A short swipe leaves the button open for a tap; a long one does it at once. Directions
 * are physical, as the finger moves, whatever the layout direction.
 */
@Composable
private fun SwipeActionsRow(
    id: String,
    openId: String?,
    onOpenChange: (String?) -> Unit,
    onDelete: () -> Unit,
    onRecheck: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val actionPx = with(density) { 88.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(0) }
    // Another row opened: this one closes.
    LaunchedEffect(openId) { if (openId != id && offset.value != 0f) offset.animateTo(0f, tween(220)) }
    // Unconditional: this row's buttons are only there while it is the open one, and a tap
    // handler set up once would otherwise compare against the open row of its first frame.
    fun close() { scope.launch { offset.animateTo(0f, tween(220)) }; onOpenChange(null) }

    Box(
        Modifier.fillMaxWidth().onSizeChanged { width = it.width }.clipToBounds()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    val limit = width * 0.92f
                    scope.launch { offset.snapTo((offset.value + delta).coerceIn(-limit, limit)) }
                },
                onDragStarted = { if (openId != id) onOpenChange(id) },
                onDragStopped = { velocity ->
                    val v = offset.value
                    when {
                        // A long swipe does it at once, like iOS's full swipe.
                        v < 0 && (-v > width * 0.55f || (velocity < -2500f && -v > actionPx)) -> {
                            offset.animateTo(0f, tween(220)); onOpenChange(null); onDelete()
                        }
                        v > 0 && (v > width * 0.45f || (velocity > 2500f && v > actionPx)) -> {
                            offset.animateTo(0f, tween(220)); onOpenChange(null); onRecheck()
                        }
                        v < 0 && -v > actionPx / 2 -> offset.animateTo(-actionPx, tween(200))
                        v > 0 && v > actionPx / 2 -> offset.animateTo(actionPx, tween(200))
                        else -> { offset.animateTo(0f, tween(200)); if (openId == id) onOpenChange(null) }
                    }
                },
            ),
    ) {
        val shown = offset.value
        if (shown < 0f) {
            // Delete, on the physical right: revealed by a swipe to the left.
            SwipeAction(
                width = with(density) { (-shown).toDp() },
                color = Ios.Red,
                icon = Icons.Default.Delete,
                label = stringResource(R.string.mae_remove),
                modifier = Modifier.matchParentSize().wrapContentWidth(AbsoluteAlignment.Right),
                onClick = { close(); onDelete() },
            )
        } else if (shown > 0f) {
            // Check again, on the physical left: revealed by a swipe to the right.
            SwipeAction(
                width = with(density) { shown.toDp() },
                color = Ios.Blue,
                icon = Icons.Default.Refresh,
                label = stringResource(R.string.mae_recheck),
                modifier = Modifier.matchParentSize().wrapContentWidth(AbsoluteAlignment.Left),
                onClick = { close(); onRecheck() },
            )
        }
        Box(Modifier.fillMaxWidth().absoluteOffset { IntOffset(offset.value.roundToInt(), 0) }) {
            content()
            // While open, a tap on the row closes it rather than pressing what is under the finger.
            if (shown != 0f) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { close() } })
        }
    }
}

@Composable
private fun SwipeAction(
    width: androidx.compose.ui.unit.Dp,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(modifier) {
        Box(
            Modifier.fillMaxHeight().width(width).background(color).combinedClickableCompat(onClick),
            contentAlignment = Alignment.Center,
        ) {
            // The label appears once there is room for it, as iOS's does.
            if (width > 44.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    if (width > 70.dp) {
                        Spacer(Modifier.height(2.dp))
                        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectButton(connected: Boolean, connecting: Boolean, onClick: () -> Unit) {
    val ring = when {
        connected -> Ios.Green
        connecting -> Ios.Orange
        else -> Ios.Gray
    }
    val label = stringResource(if (connected || connecting) R.string.mae_disconnect else R.string.mae_connect)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(ring.copy(alpha = 0.18f))
                .border(3.dp, ring, CircleShape)
                .semantics { contentDescription = label }
                .combinedClickableCompat(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = ring, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) =
    this.combinedClickable(role = Role.Button, onClick = onClick)

/** How long a note stays on its row. The ones that ask the user to act stay longer. */
private fun noteLifeMs(kind: MaeEngine.NoteKind): Long? = when (kind) {
    MaeEngine.NoteKind.CHECKING -> null // replaced by the outcome
    MaeEngine.NoteKind.NO_FOREIGN_EXIT, MaeEngine.NoteKind.NOT_FOUND, MaeEngine.NoteKind.FIXED -> 10 * 60_000L
    else -> 90_000L
}

@Composable
private fun noteText(v: MaeEngine.ServiceView, n: MaeEngine.Note): String {
    val context = LocalContext.current
    val route = routeName(context, n.routeId ?: v.routeId, v.family, v.country)
    return when (n.kind) {
        MaeEngine.NoteKind.CHECKING -> stringResource(R.string.mae_note_checking)
        MaeEngine.NoteKind.FIXED -> if (n.level >= 2) stringResource(R.string.mae_note_fixed_level, route, n.level)
            else stringResource(R.string.mae_note_fixed, route)
        MaeEngine.NoteKind.VERIFIED -> stringResource(R.string.mae_note_verified)
        MaeEngine.NoteKind.NOT_FOUND -> if (n.level >= RepairLadder.MAX) stringResource(R.string.mae_note_exhausted)
            else stringResource(R.string.mae_note_not_found)
        MaeEngine.NoteKind.NO_FOREIGN_EXIT -> stringResource(R.string.mae_note_no_foreign)
        MaeEngine.NoteKind.HEALED -> stringResource(R.string.mae_note_healed)
        MaeEngine.NoteKind.OFFLINE -> stringResource(R.string.mae_note_offline)
        MaeEngine.NoteKind.NOT_CONNECTED -> stringResource(R.string.mae_note_not_connected)
    }
}

@Composable
private fun ServiceRow(
    v: MaeEngine.ServiceView,
    connected: Boolean,
    testing: Boolean,
    onRecheck: () -> Unit,
    onOk: () -> Unit,
    onFail: () -> Unit,
    setUp: List<SetupExit> = emptyList(),
    onSetUp: () -> Unit = {},
) {
    val context = LocalContext.current
    val name = serviceName(context, v.def)
    val (statusRes, dot) = when (v.phase) {
        MaeEngine.Phase.READY -> R.string.mae_phase_ready to Ios.Green
        MaeEngine.Phase.TESTING -> R.string.mae_phase_testing to Ios.Orange
        MaeEngine.Phase.WAITING -> R.string.mae_phase_waiting to Ios.Gray
        MaeEngine.Phase.NO_FOREIGN_ROUTE -> R.string.mae_phase_no_foreign to Ios.Red
        MaeEngine.Phase.BLOCKED -> R.string.mae_phase_blocked to Ios.Red
        MaeEngine.Phase.PAUSED -> R.string.mae_phase_paused to Ios.Gray
    }
    val status = buildString {
        append(stringResource(statusRes))
        if (v.routeId != null && v.phase != MaeEngine.Phase.PAUSED && v.phase != MaeEngine.Phase.TESTING) {
            append(" · ").append(routeName(context, v.routeId, v.family, v.country))
            if (v.pinned) append(" · ").append(stringResource(R.string.mae_route_manual))
        }
    }
    val note = v.note
    // A note fades by itself; the screen tells the engine so the row goes back to its status.
    LaunchedEffect(note?.at) {
        val n = note ?: return@LaunchedEffect
        val life = noteLifeMs(n.kind) ?: return@LaunchedEffect
        delay((life - (System.currentTimeMillis() - n.at)).coerceAtLeast(0))
        MaeEngine.clearNote(v.def.id)
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The app's icon, with its state as a small dot on the corner.
            Box {
                MaeAppIcon(v.def, name, 36.dp)
                Box(
                    Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                        .background(Ios.Card).padding(2.dp).clip(CircleShape).background(dot),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Ios.Label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // No exit abroad worked for this app: right under it, what is not set up yet -- the
                // user's ask (2026-10-18), shown only while one of FENIX / Proton / Windscribe is missing.
                val noExit = v.phase == MaeEngine.Phase.NO_FOREIGN_ROUTE || note?.kind == MaeEngine.NoteKind.NO_FOREIGN_EXIT
                if (noExit && setUp.isNotEmpty()) {
                    val names = sayList(setUp.map { it.title })
                    Text(
                        com.mlmvpn.scanner.store.tr("$names را راه‌اندازی کنید", "Set up $names"),
                        color = Ios.Orange, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.combinedClickableCompat(onSetUp),
                    )
                }
            }
            if (!v.paused) {
                // Check again: a spinner while this app is being checked.
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ios.Blue)
                    else IconButton(onClick = onRecheck, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.mae_recheck_cd, name), tint = Ios.Blue, modifier = Modifier.size(20.dp))
                    }
                }
                // Feedback is about MAE's route: without MAE connected it would grade a route
                // that carried nothing. Dimmed, and a tap says why.
                val a = if (connected) 1f else 0.35f
                IconButton(onClick = onOk, modifier = Modifier.size(40.dp).alpha(a)) {
                    Icon(Icons.Outlined.ThumbUp, contentDescription = stringResource(R.string.mae_feedback_opened_cd, name), tint = Ios.Green, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onFail, modifier = Modifier.size(40.dp).alpha(a)) {
                    Icon(Icons.Outlined.ThumbDown, contentDescription = stringResource(R.string.mae_feedback_failed_cd, name), tint = Ios.Red, modifier = Modifier.size(20.dp))
                }
            }
        }
        // What just happened to this app, in words. The SIM / time-zone warning that used to sit
        // here is gone: the user found it wrong on a working phone (TikTok opened), and a warning
        // that is wrong once is read as noise ever after.
        if (note != null) {
            val warn = note.kind == MaeEngine.NoteKind.NO_FOREIGN_EXIT || note.kind == MaeEngine.NoteKind.NOT_FOUND ||
                note.kind == MaeEngine.NoteKind.OFFLINE
            Text(
                noteText(v, note),
                color = if (warn) Ios.Orange else Ios.SecondaryLabel,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, bottom = 10.dp),
            )
        }
    }
}

/** A selectable chip for onboarding. */
@Composable
internal fun ServiceChip(label: String, selected: Boolean, highlighted: Boolean, def: ServiceDef? = null, onClick: () -> Unit) {
    val bg = if (selected) Ios.Blue else Color.Transparent
    val border = when {
        selected -> Ios.Blue
        highlighted -> Ios.Green
        else -> Ios.Separator
    }
    Box(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .combinedClickableCompat(onClick)
            .semantics { contentDescription = label }
            .padding(start = if (def != null) 8.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (def != null) {
                MaeAppIcon(def, label, 28.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * "What's wrong?" -- asked from the second "didn't open" on, so the next attempt is a different
 * approach aimed at what the user actually sees, not the same check again. Cancelling costs
 * nothing: no rung is used and no route is marked until an answer is given.
 */
@Composable
private fun SymptomDialog(
    ask: MaeEngine.Ask,
    onAnswer: (Symptom) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val name = MaeEngine.serviceDef(ask.serviceId)?.let { serviceName(context, it) } ?: ask.serviceId
    val options = listOf(
        Symptom.NOT_OPENING to R.string.mae_symptom_not_opening,
        Symptom.APP_SAYS_OFFLINE to R.string.mae_symptom_app_offline,
        Symptom.GEO_BLOCKED to R.string.mae_symptom_geo,
        Symptom.PARTIAL_LOAD to R.string.mae_symptom_partial,
        Symptom.SLOW to R.string.mae_symptom_slow,
        Symptom.LOGIN to R.string.mae_symptom_login,
        Symptom.MEDIA_CALLS to R.string.mae_symptom_calls,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ios.Card,
        title = { Text(stringResource(R.string.mae_ask_title, name), color = Ios.Label, fontSize = 18.sp) },
        text = {
            Column {
                Text(stringResource(R.string.mae_ask_subtitle, ask.level), color = Ios.SecondaryLabel, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                options.forEach { (symptom, label) ->
                    val chosenBefore = symptom == ask.previous
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (chosenBefore) Ios.Blue.copy(alpha = 0.15f) else Color.Transparent)
                            .combinedClickableCompat { onAnswer(symptom) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(label), color = Ios.Label, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.mae_cancel)) }
        },
    )
}
