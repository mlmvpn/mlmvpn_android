package com.mlmvpn.scanner.ui

import com.mlmvpn.scanner.ui.theme.*
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.ui.res.stringResource
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.home.HomeDestinations
import com.mlmvpn.scanner.ui.home.HomeScreen
import com.mlmvpn.scanner.ui.home.fadingTopEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.utils.S

/**
 * Screens that already draw their own header with a back arrow. Wrapping these in the feature
 * chrome as well would put two back arrows on the same screen, so they are opted out by name.
 */
/** The floating chrome bar's height, shared with the inset every hosted screen leaves for it. */
private val FEATURE_BAR_HEIGHT = 56.dp

private val SELF_HEADED = setOf(
    "settings", "vpn_settings", "usage", "tutorial", "nodes", "cloud", "scanner", "quick", "game", "sublink", "fixed_ip", "vpngate", "freeconfig", "lan",
    "configstudio", "github", "store", "openvpn", "mae", "flux", "cfdoctor", "amnezia",
    // The five transports. Each draws an iOS navigation bar of its own, and pushes pages under
    // it that carry their own back chevron -- the app chrome on top would be a second one.
) + com.mlmvpn.scanner.ui.tunnel.Transport.IDS

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AppScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // After an engine-conflict restart the game boost left a "pending_boost_mode" flag -- land the
    // user straight back on the Game tab so they can retap Start on a clean process.
    val hasPendingBoost = remember {
        context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
            .getString("pending_boost_mode", null) != null
    }
    // Tapping the game booster's notification opens the app on the Game tab too.
    val openedFromBooster = remember {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.intent
            ?.getBooleanExtra(com.mlmvpn.scanner.engines.game.booster.session.GameBoostService.EXTRA_OPEN_BOOSTER, false) == true
    }

    // The landing screen is the icon grid. Everything else in the app is reached from it: the
    // four dock apps, the fourteen grid apps, and nothing hidden behind a drawer any more.
    val homeTab = "home"
    // A `.conf` opened with the app (AmzImportActivity) lands on the «آمنزیا» section.
    val openedForTab = remember {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.intent
            ?.getStringExtra(com.mlmvpn.scanner.ui.amnezia.AmzImportActivity.EXTRA_OPEN_TAB)
    }
    var activeTab by remember { mutableStateOf(if (hasPendingBoost || openedFromBooster) "game" else openedForTab ?: homeTab) }
    // Real back stack (was a single `previousTab`, which broke nested navigation: opening a screen
    // FROM another overlay clobbered the one shared "previous" value, so the parent's back button
    // went dead). openTab() pushes; goBack() pops; switchTab() resets the stack.
    //
    // The boost recovery seeds the stack rather than moving `homeTab`: back from the recovered
    // Game tab has to land on the icon grid, and `homeTab` is also what decides when back offers
    // to exit the app -- pointing it at a feature screen would make the exit prompt unreachable.
    val navStack = remember {
        mutableStateListOf<String>().apply { if (hasPendingBoost || openedFromBooster || openedForTab != null) add(homeTab) }
    }
    // Breadcrumbs: a native abort produces no Java stack, so the last screen the user reached
    // is often the only clue about where it happened.
    fun openTab(tab: String) {
        if (activeTab != tab) {
            com.mlmvpn.scanner.CrashReporter.note("openTab $activeTab -> $tab")
            navStack.add(activeTab); activeTab = tab
        }
    }
    fun goBack() {
        val to = navStack.removeLastOrNull() ?: homeTab
        com.mlmvpn.scanner.CrashReporter.note("goBack $activeTab -> $to")
        activeTab = to
    }
    fun switchTab(tab: String) {
        com.mlmvpn.scanner.CrashReporter.note("switchTab $activeTab -> $tab")
        navStack.clear(); activeTab = tab
    }
    var activeModal by remember { mutableStateOf<String?>(null) }
    // A settings page requested from somewhere else; consumed on arrival. Declared here rather
    // than beside the tab hosts because the update notice, which lives at the root of this
    // composable, is one of the things that asks for one.
    var settingsRoute by remember { mutableStateOf<String?>(null) }

    // The update check still runs, and it no longer says anything.
    //
    // A dialog used to appear over whatever the user was doing the moment a new version was found.
    // Nobody asked for it at that moment -- they were opening a tunnel, or reading a config -- and
    // an interruption that can only be dismissed teaches people to dismiss it without reading.
    // Settings > Software update is where a version is checked for and installed now, on the
    // user's own initiative.
    //
    // The check itself is kept deliberately. It is silent, it costs one request, and it is what
    // makes that Settings page answer instantly instead of starting from "checking..." every time
    // it is opened -- and it is what the "download over Wi-Fi automatically" switch runs on.
    LaunchedEffect(Unit) {
        com.mlmvpn.scanner.update.UpdateChecker.checkForUpdate(context)
    }

    // Physical back-button behaviour (standard Android hierarchy):
    //   a full-screen modal is open -> close it (returns to the screen underneath)
    //   a sub-screen is stacked (e.g. Settings > Wallpaper) -> go up one level
    //   on any other screen        -> go to the icon grid
    //   on the icon grid           -> ask to exit (a second back / "Exit" leaves the app)
    // Screens with their OWN internal sub-navigation (Settings > About > Changelog, Help > FAQ,
    // the setup wizard, the home screen's edit mode, ...) register their own BackHandler, which
    // takes priority while shown.
    var showExitDialog by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity
    BackHandler(enabled = true) {
        when {
            activeModal != null -> activeModal = null
            navStack.isNotEmpty() -> goBack()
            // Must be `homeTab`, not a hardcoded tab name. It was pinned to "cloud" while the
            // landing screen was something else, so back on the landing screen walked SIDEWAYS
            // into Cloud instead of offering to exit, and the exit prompt was unreachable from
            // the one screen that is supposed to own it.
            activeTab != homeTab -> switchTab(homeTab)
            else -> showExitDialog = true
        }
    }

    val bgColor = BgDark
    val surfaceColor = SurfaceDark
    val primaryColor = Primary
    val textColor = TextPrimary
    val mutedColor = TextMuted
    val borderColor = BorderDark
    val GreenOk = Color(0xFF81C995)
    var trafficDown by remember { mutableFloatStateOf(0f) }
    var trafficUp by remember { mutableFloatStateOf(0f) }

    val xrayRunning by com.mlmvpn.scanner.MyVpnService.isRunningFlow.collectAsState()
    // The header's live meter has to follow BOTH services, not just the Xray one. The
    // five-transport stack runs under its own VpnService, so a MASQUE or Psiphon session left
    // the readout at zero and the header looked like nothing was connected while the device's
    // whole traffic was going through it. Its stage is a flow, so this recomposes with it.
    val tunnelStage by com.mlmvpn.scanner.ui.tunnel.TunnelController.state.collectAsState()
    val isRunning = xrayRunning ||
        tunnelStage.stage == com.mlmvpn.scanner.ui.tunnel.TunnelStage.RUNNING

    // WireGuard trial usage tracker — driven by the REAL VPN state at the app level, so the
    // countdown / server tick / auto-stop-on-expiry run no matter which tab started the trial
    // (Game tab or WireGuard tab) and no matter which tab is currently open. On first launch we
    // also refresh status once so a trial obtained in a previous session is picked up.
    val trialPhase by com.mlmvpn.scanner.MyVpnService.connectionPhaseFlow.collectAsState()
    val trialNodeId by com.mlmvpn.scanner.MyVpnService.connectedNodeIdFlow.collectAsState()
    val trialConnected = trialPhase == com.mlmvpn.scanner.MyVpnService.Phase.CONNECTED &&
        trialNodeId == "game_uae_trial"
    LaunchedEffect(Unit) { UaeTrialEngine.checkStatus(context) }
    LaunchedEffect(trialConnected) {
        if (trialConnected) UaeTrialEngine.startUsageTracker(context)
        else UaeTrialEngine.stopUsageTracker(context)
    }

    val defaultPrefs = remember { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    var showRealtimeTraffic by remember { mutableStateOf(defaultPrefs.getBoolean("show_realtime_traffic", true)) }

    // The Aether and Game tabs used to be behind on/off switches because the old bottom bar had
    // no room for six items. Both have a permanent home now -- Aether in the dock, Game on the
    // grid -- so nothing gates them any more.
    LaunchedEffect(activeTab, activeModal) {
        if (activeModal == null) {
            showRealtimeTraffic = defaultPrefs.getBoolean("show_realtime_traffic", true)
        }
    }

    // The live meter, pushed from memory by whichever tunnel service is running. It used to be a
    // preference-file read every second -- with the app in the background too, for as long as the
    // tunnel kept the process alive. Now nothing runs unless the app is in front.
    LaunchedEffect(isRunning, showRealtimeTraffic) {
        if (!(isRunning && showRealtimeTraffic)) {
            trafficDown = 0f
            trafficUp = 0f
        }
    }
    LaunchedWhileVisible(isRunning, showRealtimeTraffic) {
        if (!(isRunning && showRealtimeTraffic)) return@LaunchedWhileVisible
        com.mlmvpn.scanner.data.SessionTraffic.totals.collect { t ->
            trafficDown = t.rx / 1048576f
            trafficUp = t.tx / 1048576f
        }
    }

    val onHome = activeTab == homeTab
    val darkTheme = com.mlmvpn.scanner.ui.home.Appearance.isDark()

    // The status and navigation bar icons have to contrast with whatever is actually behind them,
    // and that changes per screen rather than per app: the home screen always sits on a dark
    // wallpaper, while a light-theme feature screen sits on a near-white sheet. Driving the
    // appearance from here rather than once in onCreate is what keeps the clock readable on both.
    // Always false: every surface in the app is dark enough to carry white type, so the clock and
    // battery are white everywhere as well. This used to flip per screen, back when light mode
    // meant a near-white sheet.
    val lightBarIcons = false
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(lightBarIcons) {
        (context as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBarIcons
                isAppearanceLightNavigationBars = lightBarIcons
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // The feature host is ALWAYS composed, even while the home screen is showing.
        //
        // Every visited tab stays in the composition and is parked off-screen at x = 10000.dp
        // rather than being removed. That is deliberate and load-bearing: a running server sweep,
        // a half-filled scanner, a connected session's tab state all live in composition, and
        // dropping them on navigation would cancel work the user is waiting on. Wrapping this
        // block in `if (!onHome)` would do exactly that every time they went back to the grid.
        //
        // On the home screen nothing here is visible anyway -- every tab is parked off-screen and
        // the background is left transparent so the wallpaper behind the activity shows through.
        Box(
            modifier = Modifier
                .fillMaxSize()
                // No opaque background and no inset here: the wallpaper painted at the activity
                // root has to reach the very top and bottom of the glass on every screen, not
                // only on the home grid. A feature screen gets a scrim over it instead, which
                // dims the backdrop enough to read content against while still showing it.
                //
                // The content does not get an inset either. It runs the full height of the
                // glass and FADES OUT under the status bar, which is the only way to be rid of
                // the cut line there -- a padded column just moves the line down. Each hosted
                // screen keeps its first item clear using LocalContentTopInset instead.
                .then(
                    when {
                        onHome -> Modifier
                        // Off means "plain background everywhere but home" -- the way back to a
                        // flat, high-contrast sheet for anyone who finds content over a backdrop
                        // hard to read.
                        !com.mlmvpn.scanner.ui.home.Appearance.wallpaperEverywhere ->
                            Modifier.background(if (darkTheme) Color(0xFF000000) else Color(0xFF1C1C1E))
                        // A scrim rather than a sheet: the wallpaper stays visible but stops
                        // competing with the content on top of it. Light mode washes it out
                        // instead of dimming it, for the same reason.
                        darkTheme -> Modifier.background(Color.Black.copy(alpha = 0.45f))
                        // Light thins the scrim rather than inverting it, for the same reason the
                        // glass does: the type on top is white either way.
                        else -> Modifier.background(Color.Black.copy(alpha = 0.20f))
                    }
                )
        ) {
            val topInset = LocalSystemTopPadding.current
            val hasChrome = !onHome && activeTab !in SELF_HEADED
            // What a hosted screen must leave clear at the top of its own scroll: the status bar,
            // plus the chrome bar when one is floating over it.
            val contentTopInset = topInset + if (hasChrome) FEATURE_BAR_HEIGHT else 0.dp

            CompositionLocalProvider(LocalContentTopInset provides contentTopInset) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .fadingTopEdge(contentTopInset)
                ) {
                val visitedTabs = remember { mutableStateListOf<String>() }
                LaunchedEffect(activeTab) {
                    if (activeTab != homeTab && !visitedTabs.contains(activeTab)) {
                        visitedTabs.add(activeTab)
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (visitedTabs.contains("nodes")) {
                        // No contentTopInset padding: the connection screen draws its own iOS
                        // navigation bar now, like Settings and the transports, so the app chrome
                        // above it would be a second bar.
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "nodes") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "nodes")) {
                                NodesTab(onBack = { goBack() })
                            }
                        }
                    }
                    if (visitedTabs.contains("cloud")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "cloud") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "cloud")) {
                                CloudTab(
                                    onBack = { goBack() },
                                    onOpenCloudflareResources = {
                                        settingsRoute = com.mlmvpn.scanner.ui.settings.ROUTE_CF_RESOURCES
                                        openTab("settings")
                                    },
                                    onNavigateToScanner = { openTab("scanner") },
                                    onOpenV2Ray = { openTab("nodes") },
                                    onOpenConfigStudio = { openTab("configstudio") },
                                )
                            }
                        }
                    }
                    if (visitedTabs.contains("scanner")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "scanner") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "scanner")) {
                                ScannerTab(onBack = { goBack() }, onOpenNodes = { openTab("nodes") })
                            }
                        }
                    }
                    if (visitedTabs.contains("aether")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "aether") 0.dp else 10000.dp).padding(top = contentTopInset, bottom = LocalSystemBottomPadding.current)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "aether")) {
                                com.mlmvpn.scanner.ui.aether.AetherScreen()
                            }
                        }
                    }
                    // The five transports. One entry each rather than one shared entry with the
                    // protocol as an argument, because each is kept alive independently once
                    // visited -- the same contract as every other tab here -- and a shared slot
                    // would tear down the screen the user was on whenever they opened another.
                    com.mlmvpn.scanner.ui.tunnel.Transport.entries.forEach { transport ->
                        if (visitedTabs.contains(transport.id)) {
                            Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == transport.id) 0.dp else 10000.dp)) {
                                CompositionLocalProvider(LocalTabVisible provides (activeTab == transport.id)) {
                                    com.mlmvpn.scanner.ui.tunnel.TransportHost(
                                        transport = transport,
                                        onExit = { goBack() },
                                    )
                                }
                            }
                        }
                    }
                    // (WireguardTab routing was kept during the migration so existing call
                    // sites don't error; it is no longer reachable from any nav entry. The
                    // Aether tab above replaces it.)
                    if (visitedTabs.contains("sublink")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "sublink") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "sublink")) {
                                com.mlmvpn.scanner.ui.sublink.SubLinkScreen(
                                    onBack = { goBack() },
                                    cloudManager = androidx.compose.runtime.remember { com.mlmvpn.scanner.data.CloudManager(context) },
                                    nodeManager = androidx.compose.runtime.remember { com.mlmvpn.scanner.data.NodeManager(context) },
                                    onNavigateToCloud = { switchTab("cloud") }
                                )
                            }
                        }
                    }
                    if (visitedTabs.contains("game")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "game") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "game")) {
                                com.mlmvpn.scanner.ui.game.GameBoosterHost(onBack = { goBack() }, onNavigateToCloud = { switchTab("cloud") })
                            }
                        }
                    }
                    if (visitedTabs.contains("settings")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "settings") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "settings")) {
                                com.mlmvpn.scanner.ui.settings.SettingsScreen(
                                    openRoute = settingsRoute,
                                    onRouteOpened = { settingsRoute = null },
                                    onDismiss = { goBack() },
                                    onOpenVpnSettings = { openTab("vpn_settings") },
                                    onOpenCloud = { openTab("cloud") },
                                    onOpenUsage = { openTab("usage") },
                                )
                            }
                        }
                    }
                    if (visitedTabs.contains("vpn_settings")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "vpn_settings") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "vpn_settings")) {
                                com.mlmvpn.scanner.ui.settings.VpnSettingsScreen(onDismiss = { goBack() })
                            }
                        }
                    }
                    if (visitedTabs.contains("usage")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "usage") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "usage")) {
                                com.mlmvpn.scanner.ui.settings.UsageScreen(onDismiss = { goBack() })
                            }
                        }
                    }
                    if (visitedTabs.contains("store")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "store") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "store")) {
                                com.mlmvpn.scanner.ui.store.StoreScreen(onBack = { goBack() })
                            }
                        }
                    }
                    if (visitedTabs.contains("tutorial")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "tutorial") 0.dp else 10000.dp).padding(top = contentTopInset, bottom = LocalSystemBottomPadding.current)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "tutorial")) {
                                HelpCenterScreen(onDismiss = { goBack() })
                            }
                        }
                    }
                    if (visitedTabs.contains("fixed_ip")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "fixed_ip") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "fixed_ip")) {
                                FixedIpScreen(onDismiss = { goBack() })
                            }
                        }
                    }
                    if (visitedTabs.contains("configstudio")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "configstudio") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "configstudio")) {
                                com.mlmvpn.scanner.ui.configstudio.ConfigStudioHost(
                                    onExit = { goBack() },
                                    // Parked off to the side once visited; its back handling must stop
                                    // while another tab is the one on screen.
                                    visible = activeTab == "configstudio",
                                    // «ترکیب» arms a handoff and comes here rather than carrying a
                                    // scanner of its own. openTab, not switchTab: the operator is
                                    // going to press back afterwards and expects Config Studio.
                                    onOpenScanner = { openTab("scanner") },
                                )
                            }
                        }
                    }
                    if (visitedTabs.contains("github")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "github") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "github")) {
                                com.mlmvpn.scanner.ui.github.GithubTunnelScreen(
                                    onBack = { goBack() },
                                    onOpenScanner = { openTab("scanner") },
                                    onOpenCloud = { openTab("cloud") },
                                )
                            }
                        }
                    }
                    if (visitedTabs.contains("cfdoctor")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "cfdoctor") 0.dp else 10000.dp)) {
                            com.mlmvpn.scanner.ui.doctor.CfDoctorScreen(onBack = { goBack() }, visible = activeTab == "cfdoctor")
                        }
                    }
                    if (visitedTabs.contains("lan")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "lan") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "lan")) {
                                com.mlmvpn.scanner.ui.lan.LanScreen(
                                    onBack = { goBack() },
                                    // switchTab, not openTab: a user sent away to bring a tunnel up
                                    // is done with this screen for now, and leaving it on the stack
                                    // would send them back here from the engine they just started.
                                    onConnectVpn = { switchTab(homeTab) },
                                    onOpenProxyMode = {
                                        settingsRoute = com.mlmvpn.scanner.ui.settings.ROUTE_PROXY_MODE
                                        openTab("settings")
                                    },
                                )
                            }
                        }
                    }
                    if (visitedTabs.contains("openvpn")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "openvpn") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "openvpn")) {
                                OpenVpnScreen(onBack = { goBack() }, onStore = { openTab("store") },
                                    onIran = { activeModal = "iran" }, visible = activeTab == "openvpn")
                            }
                        }
                    }
                    if (visitedTabs.contains("vpngate")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "vpngate") 0.dp else 10000.dp).padding(top = contentTopInset, bottom = LocalSystemBottomPadding.current)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "vpngate")) {
                                VpnGateTab(onDismiss = { goBack() }, onOpenCloud = { openTab("cloud") })
                            }
                        }
                    }
                    if (visitedTabs.contains("mae")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "mae") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "mae")) {
                                com.mlmvpn.scanner.ui.mae.MaeScreen(onBack = { goBack() })
                            }
                        }
                    }
                    if (visitedTabs.contains("flux")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "flux") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "flux")) {
                                com.mlmvpn.scanner.ui.flux.FluxScreen(onBack = { goBack() })
                            }
                        }
                    }
                    if (visitedTabs.contains("amnezia")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "amnezia") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "amnezia")) {
                                com.mlmvpn.scanner.ui.amnezia.AmneziaScreen(onBack = { goBack() })
                            }
                        }
                    }
                    if (visitedTabs.contains("quick")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "quick") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "quick")) {
                                QuickConnectTab(onBack = { goBack() })
                            }
                        }
                    }
                    if (visitedTabs.contains("freeconfig")) {
                        Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "freeconfig") 0.dp else 10000.dp)) {
                            CompositionLocalProvider(LocalTabVisible provides (activeTab == "freeconfig")) {
                                FreeConfigTab(
                                    onBack = { goBack() },
                                    // switchTab, not openTab: after the handoff the user is done with
                                    // the importer, and leaving it on the back stack would send them
                                    // from the configs they just imported back to the screen that
                                    // imported them.
                                    onOpenNodes = { switchTab("nodes") },
                                )
                            }
                        }
                    }
                }
                }

                // The chrome bar FLOATS over the content rather than sitting above it. That is
                // what lets the screen behind it scroll all the way up and dissolve under the
                // status bar; a bar in a Column would just be a second hard edge lower down.
                if (hasChrome) {
                    FeatureTopBar(
                        titleRes = HomeDestinations.DOCK.plus(HomeDestinations.GRID_DEFAULT)
                            .firstOrNull { it.id == activeTab }?.labelRes,
                        isRunning = isRunning,
                        showTraffic = showRealtimeTraffic,
                        trafficDown = trafficDown,
                        trafficUp = trafficUp,
                        onBack = { if (navStack.isNotEmpty()) goBack() else switchTab(homeTab) },
                        textColor = textColor,
                        mutedColor = mutedColor,
                        borderColor = borderColor,
                        primaryColor = primaryColor,
                        greenOk = GreenOk,
                        modifier = Modifier.padding(top = topInset),
                    )
                }

                // Mounted here, above every tab, because a scan collides with things started from
                // half a dozen screens and from outside the app entirely. See ScanGuardHost.
                ScanGuardHost()
            }
        }

        // The icon grid sits on top of the parked tabs, and is composed only while it is the
        // active screen -- unlike the tabs it holds no work worth preserving, since the icon
        // order is persisted the moment a drag ends.
        if (onHome) {
            HomeScreen(
                isRunning = isRunning,
                showTraffic = showRealtimeTraffic,
                trafficDown = trafficDown,
                trafficUp = trafficUp,
                onOpen = { app ->
                    if (app.opensAsModal) activeModal = app.id else openTab(app.id)
                },
                onOpenUpdate = {
                    settingsRoute = com.mlmvpn.scanner.ui.settings.ROUTE_UPDATE_DOWNLOAD
                    openTab("settings")
                },
            )
        }
    }

    // Emergency Modals
    //
    // These are drawn into the same Box as the home grid rather than into a Dialog, so without a
    // backdrop of their own the icons underneath show straight through them. They each used to
    // paint an opaque sheet; now that every screen sits on the wallpaper, IosModalHost gives them
    // the same wallpaper and scrim a hosted tab gets -- so each opens as its own page.
    if (activeModal != null) {
        com.mlmvpn.scanner.ui.home.IosModalHost(modifier = Modifier.fillMaxSize()) {
            when (activeModal) {
                "emergency_2" -> com.mlmvpn.scanner.ui.emergency.EmergencyLevel2Screen(onBack = { activeModal = null })
                "emergency_3" -> com.mlmvpn.scanner.ui.emergency.EmergencyLevel3Screen(onBack = { activeModal = null })
                "antisanction" -> com.mlmvpn.scanner.ui.sanction.AntiSanctionScreen(onBack = { activeModal = null })
                "iran" -> com.mlmvpn.scanner.ui.home.IranConfigsScreen(onBack = { activeModal = null })
                "fronting" -> com.mlmvpn.scanner.ui.home.DomainFrontingScreen(onBack = { activeModal = null })
            }
        }
    }

    // Proxy Mode is a switch whose consequence the app never stated out loud, and the
    // consequence is severe: with the Xray family it means NO device-wide tunnel at all, so the
    // app reports "connected" while the browser goes out in the clear. That is the exact shape
    // of "the VPN is broken" from a user's side, and the cause is a setting they may have turned
    // on months ago for one experiment.
    //
    // Two ways out on purpose, and no way to just dismiss it: turn the switch off, or say you
    // know. Anything else and the app has not established that the user actually knows -- which
    // is the whole point of showing it.
    //
    // Shown on the home screen at launch, and again the moment a tunnel comes up if it has still
    // not been acknowledged, because that is the instant the surprise would otherwise land. The
    // connect trigger reads the Xray phase specifically: it is the family the warning is TRUE
    // for. Since startNativeProxyTunnel, MASQUE/WireGuard/WARP-on-WARP in Proxy Mode still carry
    // the whole device, so firing on those would be telling the user something false.
    var proxyWarn by remember {
        mutableStateOf<Boolean>(
            com.mlmvpn.scanner.utils.NetworkSettings.proxyModeNeedsWarning(context)
        )
    }
    LaunchedEffect(trialPhase, xrayRunning) {
        if (trialPhase == com.mlmvpn.scanner.MyVpnService.Phase.CONNECTED &&
            com.mlmvpn.scanner.utils.NetworkSettings.proxyModeNeedsWarning(context)
        ) {
            proxyWarn = true
        }
    }
    if (proxyWarn) {
        AlertDialog(
            // No onDismissRequest escape: tapping outside leaves it up. The user has to pick one
            // of the two, or the app has learned nothing about whether they understand.
            onDismissRequest = {},
            containerColor = surfaceColor,
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFFF9F0A)) },
            title = {
                Text(
                    S(R.string.proxy_warn_title),
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = { Text(S(R.string.proxy_warn_body), color = mutedColor) },
            confirmButton = {
                Button(
                    onClick = {
                        com.mlmvpn.scanner.utils.NetworkSettings.setProxyMode(context, false)
                        com.mlmvpn.scanner.utils.NetworkSettings.setProxyModeAcknowledged(context, true)
                        proxyWarn = false
                        android.widget.Toast.makeText(
                            context,
                            S(R.string.proxy_warn_turned_off),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    },
                    colors = iosButtonColors(primaryColor),
                    border = iosButtonBorder(primaryColor),
                ) { Text(S(R.string.proxy_warn_turn_off)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    com.mlmvpn.scanner.utils.NetworkSettings.setProxyModeAcknowledged(context, true)
                    proxyWarn = false
                }) { Text(S(R.string.proxy_warn_i_know), color = mutedColor) }
            },
        )
    }

    // Offered once per crash, on the launch that follows it, and only on the home screen so it
    // cannot land on top of something the user is in the middle of.
    //
    // This exists because reports of "it crashes when I open X" kept arriving with nothing
    // attached: the app has always written a full stack to files/crashlogs/, and no user has ever
    // had a way to send one. A row buried in Settings would not have closed that gap either --
    // the people who hit the crash are the ones who never go looking.
    var crashOffer by remember {
        mutableStateOf<Boolean>(com.mlmvpn.scanner.CrashReporter.unreportedCrash(context) != null)
    }
    // With automatic sending on (the default) nothing is asked: the report left with the crash.
    // The first time, the user is told so, once, and offered the switch right there.
    var crashNotice by remember { mutableStateOf(com.mlmvpn.scanner.CrashReporter.noticeDue(context)) }
    // One at a time. Both of these fire on the same launch often enough -- a crash while Proxy
    // Mode is on is exactly the kind of session that produces both -- and stacked dialogs read
    // as a broken app rather than two pieces of information.
    var crashSending by remember { mutableStateOf(false) }
    val crashScope = rememberCoroutineScope()
    if (crashNotice && !proxyWarn && activeTab == homeTab) {
        AlertDialog(
            onDismissRequest = {
                com.mlmvpn.scanner.CrashReporter.markNoticeShown(context)
                crashNotice = false
            },
            containerColor = surfaceColor,
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = TextMuted) },
            title = {
                Text(S(R.string.crash_notice_title), color = textColor, fontWeight = FontWeight.Bold)
            },
            text = { Text(S(R.string.crash_notice_body), color = mutedColor) },
            confirmButton = {
                Button(
                    onClick = {
                        com.mlmvpn.scanner.CrashReporter.markNoticeShown(context)
                        crashNotice = false
                    },
                    colors = iosButtonColors(primaryColor),
                    border = iosButtonBorder(primaryColor),
                ) { Text(S(R.string.crash_notice_ok)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        com.mlmvpn.scanner.CrashReporter.setAutoSend(false)
                        com.mlmvpn.scanner.CrashReporter.markNoticeShown(context)
                        crashNotice = false
                    },
                ) { Text(S(R.string.crash_notice_off), color = mutedColor) }
            },
        )
    }

    if (crashOffer && !crashNotice && !proxyWarn && activeTab == homeTab) {
        AlertDialog(
            onDismissRequest = {
                com.mlmvpn.scanner.CrashReporter.markOffered(context)
                crashOffer = false
            },
            containerColor = surfaceColor,
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = TextMuted) },
            title = {
                Text(S(R.string.crash_offer_title), color = textColor, fontWeight = FontWeight.Bold)
            },
            text = { Text(S(R.string.crash_offer_body), color = mutedColor) },
            confirmButton = {
                Button(
                    enabled = !crashSending,
                    onClick = {
                        crashSending = true
                        crashScope.launch {
                            val sent = com.mlmvpn.scanner.CrashReporter.upload(context)
                            com.mlmvpn.scanner.CrashReporter.markOffered(context)
                            crashSending = false
                            crashOffer = false
                            android.widget.Toast.makeText(
                                context,
                                S(
                                    if (sent) R.string.crash_offer_sent
                                    else R.string.crash_offer_send_failed
                                ),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            // Nothing to fall back to on failure but the manual route, and the
                            // user is already here -- so offer it rather than losing the report.
                            if (!sent) com.mlmvpn.scanner.CrashReporter.share(context)
                        }
                    },
                    colors = iosButtonColors(primaryColor),
                    border = iosButtonBorder(primaryColor),
                ) {
                    Text(
                        S(
                            if (crashSending) R.string.crash_offer_sending
                            else R.string.crash_offer_send
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !crashSending,
                    onClick = {
                        com.mlmvpn.scanner.CrashReporter.markOffered(context)
                        crashOffer = false
                    },
                ) { Text(S(R.string.crash_offer_skip), color = mutedColor) }
            },
        )
    }

    if (showExitDialog) {
        // While the dialog is up, a second physical back press exits immediately (the classic
        // double-tap-to-exit), and this BackHandler out-prioritises the global one above.
        BackHandler(enabled = true) { activity?.finish() }
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = surfaceColor,
            icon = { Icon(Icons.Default.ExitToApp, contentDescription = null, tint = TextMuted) },
            title = { Text(S(R.string.exit_the_app), color = textColor, fontWeight = FontWeight.Bold) },
            text = { Text(S(R.string.press_back_again_to_exit_or_choose), color = mutedColor) },
            confirmButton = {
                Button(
                    onClick = { showExitDialog = false; activity?.finish() },
                    colors = iosButtonColors(RedError),
                    border = iosButtonBorder(RedError)) { Text(S(R.string.exit),) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(S(R.string.stay), color = primaryColor)
                }
            }
        )
    }

}

/**
 * The slim header over a feature that has no header of its own.
 *
 * It carries the one control the old top bar had that still matters -- a way back -- plus the
 * live traffic counters, which used to be the only place in the app they appeared. On the home
 * screen this bar is absent entirely; the grid draws its own status strip over the wallpaper.
 */
@Composable
private fun FeatureTopBar(
    titleRes: Int?,
    isRunning: Boolean,
    showTraffic: Boolean,
    trafficDown: Float,
    trafficUp: Float,
    onBack: () -> Unit,
    textColor: Color,
    mutedColor: Color,
    borderColor: Color,
    primaryColor: Color,
    greenOk: Color,
    modifier: Modifier = Modifier,
) {
    // No Surface and no colour. This used to be an opaque 56dp slab, which is the dark bar that
    // sat across the top of every feature screen and refused to match the home screen or Settings.
    // Transparent, the backdrop runs behind it unbroken.
    Box(modifier = modifier.fillMaxWidth().height(FEATURE_BAR_HEIGHT)) {
        Row(
            modifier = Modifier.fillMaxSize().padding(end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                if (titleRes != null) {
                    Text(
                        stringResource(titleRes),
                        color = textColor,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (showTraffic) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, contentDescription = "Down", tint = if (isRunning) greenOk else mutedColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(String.format("%.1f", trafficDown), color = if (isRunning) greenOk else mutedColor, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Divider(modifier = Modifier.padding(horizontal = 10.dp).height(16.dp).width(1.dp), color = borderColor)
                    Icon(Icons.Default.Upload, contentDescription = "Up", tint = if (isRunning) primaryColor else mutedColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(String.format("%.1f", trafficUp), color = if (isRunning) primaryColor else mutedColor, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            } else {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = "Status",
                    tint = if (isRunning) greenOk else borderColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// Removed TutorialModal, now using HelpCenterScreen
