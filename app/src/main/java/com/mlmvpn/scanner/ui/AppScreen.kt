package com.mlmvpn.scanner.ui

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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.ui.res.stringResource
import com.mlmvpn.scanner.R
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import kotlinx.coroutines.launch

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
    // VPN Gate is the landing screen: one big connect button with a server already chosen is
    // the shortest path to "I am online". The pending-boost recovery still wins, because that
    // path exists specifically to drop the user back where the restart interrupted them.
    val homeTab = if (hasPendingBoost) "game" else "vpngate"
    var activeTab by remember { mutableStateOf(homeTab) }
    // Real back stack (was a single `previousTab`, which broke nested navigation: opening a screen
    // FROM another overlay clobbered the one shared "previous" value, so the parent's back button
    // went dead). openTab() pushes; goBack() pops; bottom-nav switches reset the stack.
    val navStack = remember { mutableStateListOf<String>() }
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
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Physical back-button behaviour (standard Android hierarchy):
    //   drawer open        -> close drawer
    //   emergency/other full-screen modal open -> close it (returns to the tab underneath)
    //   a sub-screen is stacked (e.g. Settings > VPN Settings) -> go up one level
    //   on a non-home tab  -> go to the home (Cloud) tab
    //   on the home tab    -> ask to exit (a second back / "Exit" leaves the app)
    // Screens with their OWN internal sub-navigation (About > Changelog, Help > FAQ, the
    // setup wizard, ...) register their own BackHandler, which takes priority while shown.
    var showExitDialog by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity
    BackHandler(enabled = true) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            activeModal != null -> activeModal = null
            navStack.isNotEmpty() -> goBack()
            activeTab != "cloud" -> switchTab("cloud")
            else -> showExitDialog = true
        }
    }
    
    val bgColor = Color(0xFF121212)
    val surfaceColor = Color(0xFF202124)
    val primaryColor = Color(0xFF8AB4F8)
    val textColor = Color(0xFFE8EAED)
    val mutedColor = Color(0xFF9AA0A6)
    val borderColor = Color(0xFF3C4043)
    val GreenOk = Color(0xFF81C995)
    var trafficDown by remember { mutableFloatStateOf(0f) }
    var trafficUp by remember { mutableFloatStateOf(0f) }

    val isRunning by com.mlmvpn.scanner.MyVpnService.isRunningFlow.collectAsState()
    var isConnecting by remember { mutableStateOf(false) }

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
    // Repointed after the migration: this preference now gates the Aether tab. We carry
    // over the legacy `enable_wireguard_tab` value once so existing users keep whatever
    // on/off choice they made for the old tab, then write back to `enable_aether_tab`.
    var enableAetherTab by remember { mutableStateOf(
        defaultPrefs.getBoolean("enable_aether_tab", defaultPrefs.getBoolean("enable_wireguard_tab", true))
    )}
    var enableGameTab by remember { mutableStateOf(defaultPrefs.getBoolean("enable_game_tab", false)) }

    LaunchedEffect(activeTab, activeModal) {
        if (activeModal == null) {
            showRealtimeTraffic = defaultPrefs.getBoolean("show_realtime_traffic", true)
            enableAetherTab = defaultPrefs.getBoolean("enable_aether_tab", defaultPrefs.getBoolean("enable_wireguard_tab", true))
            enableGameTab = defaultPrefs.getBoolean("enable_game_tab", false)
            if (!enableAetherTab && activeTab == "aether") {
                activeTab = "nodes"
            }
            if (!enableGameTab && activeTab == "game") {
                activeTab = "nodes"
            }
        }
    }

    LaunchedEffect(isRunning) {
        if (!isRunning) isConnecting = false
    }

    val sessionPrefs = remember { context.getSharedPreferences("vpn_session_traffic", android.content.Context.MODE_PRIVATE) }
    LaunchedEffect(isRunning, showRealtimeTraffic) {
        if (isRunning && showRealtimeTraffic) {
            while(true) {
                trafficDown = sessionPrefs.getLong("session_rx", 0L) / 1048576f
                trafficUp = sessionPrefs.getLong("session_tx", 0L) / 1048576f
                kotlinx.coroutines.delay(1000)
            }
        } else {
            trafficDown = 0f
            trafficUp = 0f
        }
    }

    val isEmergencyEnabled by com.mlmvpn.scanner.emergency.EmergencyStateManager.getInstance(context).isVercelEnabled.collectAsState()
    var glitchOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    LaunchedEffect(isEmergencyEnabled) {
        if (isEmergencyEnabled) {
            for (i in 0..10) {
                glitchOffset = androidx.compose.ui.geometry.Offset(
                    ((Math.random() - 0.5) * 40).toFloat(), 
                    ((Math.random() - 0.5) * 40).toFloat()
                )
                kotlinx.coroutines.delay(40)
            }
            glitchOffset = androidx.compose.ui.geometry.Offset.Zero
        }
    }

    // CRITICAL: the emergency vignette pulse is an infiniteRepeatable animation, which NEVER
    // stops on its own and keeps requesting a frame every vsync (60fps) for as long as the
    // `rememberInfiniteTransition` is in composition — even if its value is never read. Declaring
    // it unconditionally kept the whole app rendering 24/7, which destabilised the GPU RenderThread
    // and crashed swapBuffers (GL_INVALID_FRAMEBUFFER_OPERATION → SIGABRT on a destroyed mutex) a
    // few seconds after any heavy recomposition (e.g. a VPN disconnect). Gating the transition
    // behind `isEmergencyEnabled` REMOVES it from composition when the Vercel emergency is off, so
    // frame requests stop entirely and the app goes idle. The vignette (and its per-frame full-
    // screen draw layer) only exist while the emergency is actually active.
    val vignetteAlpha = if (isEmergencyEnabled) {
        val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "EmergencyPulse")
        val pulse by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(2000),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "EmergencyPulseValue"
        )
        0.3f * pulse
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (glitchOffset != androidx.compose.ui.geometry.Offset.Zero)
                    Modifier.offset { androidx.compose.ui.unit.IntOffset(glitchOffset.x.toInt(), glitchOffset.y.toInt()) }
                else Modifier
            )
            .then(
                if (vignetteAlpha > 0f)
                    Modifier.drawWithContent {
                        drawContent()
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(Color.Transparent, Color.Red.copy(alpha = vignetteAlpha)),
                                center = center,
                                radius = size.width
                            )
                        )
                    }
                else Modifier
            )
    ) {
        ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = surfaceColor,
                modifier = Modifier
                    .width(280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.drawer_main_menu), color = primaryColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { scope.launch { drawerState.close() } }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = mutedColor)
                        }
                    }
                }
                Divider(color = borderColor)
                Spacer(modifier = Modifier.height(16.dp))
                CustomDrawerItem(icon = Icons.Default.Settings, text = stringResource(R.string.drawer_settings), onClick = { scope.launch { drawerState.close() }; openTab("settings") })
                CustomDrawerItem(icon = Icons.Default.DataUsage, text = stringResource(R.string.drawer_usage), onClick = { scope.launch { drawerState.close() }; openTab("usage") })
                CustomDrawerItem(icon = Icons.Default.LocationOn, text = stringResource(R.string.drawer_fixed_ip), onClick = { scope.launch { drawerState.close() }; openTab("fixed_ip") })
                CustomDrawerItem(icon = Icons.Default.Dns, text = stringResource(R.string.drawer_workers_list), onClick = { scope.launch { drawerState.close() }; openTab("workers_list") })
                CustomDrawerItem(icon = Icons.Default.Shield, text = "DNS ضد تحریم شخصی", onClick = { scope.launch { drawerState.close() }; activeModal = "antisanction" })
                CustomDrawerItem(icon = Icons.Default.Link, text = stringResource(R.string.subgen_drawer_menu), onClick = { scope.launch { drawerState.close() }; openTab("sublink") })
                CustomDrawerItem(icon = Icons.Default.CloudUpload, text = "Deno Panel", onClick = { scope.launch { drawerState.close() }; openTab("deno") })
                CustomDrawerItem(icon = Icons.Default.Book, text = stringResource(R.string.drawer_tutorial), onClick = { scope.launch { drawerState.close() }; openTab("tutorial") })
                CustomDrawerItem(icon = Icons.Default.Info, text = stringResource(R.string.drawer_about), onClick = { scope.launch { drawerState.close() }; openTab("about") })
                
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = com.mlmvpn.scanner.emergency.EmergencyColors.GoogleRed.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(8.dp))
                EmergencyDrawerItem(stringResource(R.string.drawer_emergency_1), Icons.Default.Warning, onClick = { scope.launch { drawerState.close() }; activeModal = "emergency_vercel" })
                EmergencyDrawerItem(stringResource(R.string.drawer_emergency_2), Icons.Default.FlashOn, onClick = { scope.launch { drawerState.close() }; activeModal = "emergency_2" })
                EmergencyDrawerItem(stringResource(R.string.drawer_emergency_3), Icons.Default.Security, onClick = { scope.launch { drawerState.close() }; activeModal = "emergency_3" })
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    ) {
        Scaffold(
            containerColor = bgColor,
            topBar = {
                Surface(
                    color = surfaceColor,
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = textColor)
                        }

                        if (showRealtimeTraffic) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Download, contentDescription = "Down", tint = if (isRunning) GreenOk else mutedColor, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(String.format("%.1f MB", trafficDown), color = if (isRunning) GreenOk else mutedColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                }
                                Divider(modifier = Modifier.padding(horizontal = 16.dp).height(20.dp).width(1.dp), color = borderColor)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Upload, contentDescription = "Up", tint = if (isRunning) primaryColor else mutedColor, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(String.format("%.1f MB", trafficUp), color = if (isRunning) primaryColor else mutedColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        Icon(
                            Icons.Default.Shield,
                            contentDescription = "Status",
                            tint = if (isRunning) GreenOk else borderColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
        ) { paddingValues ->
            val visitedTabs = remember { mutableStateListOf<String>() }
            LaunchedEffect(activeTab) {
                if (!visitedTabs.contains(activeTab)) {
                    visitedTabs.add(activeTab)
                }
            }

            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                if (visitedTabs.contains("nodes")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "nodes") 0.dp else 10000.dp)) {
                        NodesTab()
                    }
                }
                if (visitedTabs.contains("cloud")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "cloud") 0.dp else 10000.dp)) {
                        CloudTab(onNavigateToScanner = { openTab("scanner") })
                    }
                }
                if (visitedTabs.contains("scanner")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "scanner") 0.dp else 10000.dp)) {
                        ScannerTab()
                    }
                }
                if (visitedTabs.contains("aether")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "aether") 0.dp else 10000.dp)) {
                        com.mlmvpn.scanner.ui.aether.AetherScreen()
                    }
                }
                // (WireguardTab routing was kept during the migration so existing call
                // sites don't error; it is no longer reachable from any nav entry. The
                // Aether tab above replaces it.)
                if (visitedTabs.contains("sublink")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "sublink") 0.dp else 10000.dp)) {
                        com.mlmvpn.scanner.engines.subgenerator.SubLinkScreen(
                            cloudManager = androidx.compose.runtime.remember { com.mlmvpn.scanner.data.CloudManager(context) },
                            nodeManager = androidx.compose.runtime.remember { com.mlmvpn.scanner.data.NodeManager(context) },
                            onNavigateToCloud = { switchTab("cloud") }
                        )
                    }
                }
                if (visitedTabs.contains("deno")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "deno") 0.dp else 10000.dp)) {
                        com.mlmvpn.scanner.engines.deno.DenoPanelScreen(
                            nodeManager = androidx.compose.runtime.remember { com.mlmvpn.scanner.data.NodeManager(context) },
                            onDismiss = { goBack() }
                        )
                    }
                }
                if (visitedTabs.contains("game")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "game") 0.dp else 10000.dp)) {
                        GameTab(onNavigateToCloud = { switchTab("cloud") })
                    }
                }
                if (visitedTabs.contains("settings")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "settings") 0.dp else 10000.dp)) {
                        SettingsModal(onDismiss = { goBack() }, onOpenVpnSettings = { openTab("vpn_settings") })
                    }
                }
                if (visitedTabs.contains("vpn_settings")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "vpn_settings") 0.dp else 10000.dp)) {
                        VpnSettingsScreen(onDismiss = { goBack() })
                    }
                }
                if (visitedTabs.contains("usage")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "usage") 0.dp else 10000.dp)) {
                        UsageModal(onDismiss = { goBack() })
                    }
                }
                if (visitedTabs.contains("tutorial")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "tutorial") 0.dp else 10000.dp)) {
                        HelpCenterScreen(onDismiss = { goBack() })
                    }
                }
                if (visitedTabs.contains("about")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "about") 0.dp else 10000.dp)) {
                        AboutScreen(onDismiss = { goBack() })
                    }
                }
                if (visitedTabs.contains("fixed_ip")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "fixed_ip") 0.dp else 10000.dp)) {
                        FixedIpScreen(onDismiss = { goBack() })
                    }
                }
                if (visitedTabs.contains("workers_list")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "workers_list") 0.dp else 10000.dp)) {
                        WorkersListModal(onDismiss = { goBack() })
                    }
                }
                if (visitedTabs.contains("vpngate")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "vpngate") 0.dp else 10000.dp)) {
                        VpnGateTab(onDismiss = { goBack() })
                    }
                }
            }
        }
        
        // Floating Bottom Menu Overlay
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
        ) {
            // Side items are built as a list so the VPN Gate button always lands dead centre
            // no matter how many optional tabs the user has enabled in settings. Each item
            // takes an equal weight share instead of a fixed 64.dp, which is what lets six
            // of them coexist on a narrow screen.
            val navItems = buildList {
                add(Triple(Icons.Default.Radar, stringResource(R.string.nav_scanner), "scanner"))
                add(Triple(Icons.Default.Cloud, stringResource(R.string.nav_cloud), "cloud"))
                if (enableAetherTab) {
                    add(Triple(Icons.Default.Security, stringResource(R.string.nav_wireguard), "aether"))
                }
                if (enableGameTab) {
                    add(Triple(Icons.Default.VideogameAsset, stringResource(R.string.nav_game), "game"))
                }
                add(Triple(Icons.Default.Shield, stringResource(R.string.nav_nodes), "nodes"))
            }
            // Odd counts put the extra item on the left, so the bar stays balanced when the
            // right side would otherwise be the shorter one.
            val leftItems = navItems.take((navItems.size + 1) / 2)
            val rightItems = navItems.drop(leftItems.size)
            // With an odd item count one half holds one fewer icon. Padding the short side with
            // an empty weighted slot keeps every icon the same width, so the row still reads as
            // one evenly spaced strip instead of two differently-pitched groups.
            val slotsPerSide = maxOf(leftItems.size, rightItems.size)

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    color = surfaceColor.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The two halves each take weight(1f), which is what puts the reserved
                        // gap EXACTLY at the centre of the bar. Distributing all items in one
                        // flat Row instead would place the gap after the left items — off-centre
                        // whenever the sides differ in count (5 tabs => 3 left, 2 right), and the
                        // centred button would then sit on top of the last left icon.
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            leftItems.forEach { (icon, label, tab) ->
                                CustomNavItem(
                                    icon = icon,
                                    text = label,
                                    isActive = activeTab == tab,
                                    onClick = { switchTab(tab) },
                                    activeColor = if (tab == "game") Color(0xFF00E676) else primaryColor,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(slotsPerSide - leftItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                        // Reserves the footprint of the raised centre button.
                        Spacer(modifier = Modifier.width(70.dp))
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            rightItems.forEach { (icon, label, tab) ->
                                CustomNavItem(
                                    icon = icon,
                                    text = label,
                                    isActive = activeTab == tab,
                                    onClick = { switchTab(tab) },
                                    activeColor = if (tab == "game") Color(0xFF00E676) else primaryColor,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(slotsPerSide - rightItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                CenterNavButton(
                    isActive = activeTab == "vpngate",
                    activeColor = primaryColor,
                    onClick = { switchTab("vpngate") },
                    // Lifts the button so roughly 40% of it sits proud of the bar — the
                    // standard raised-FAB read, without detaching it from the surface.
                    modifier = Modifier.offset(y = (-28).dp)
                )
            }
        }
        }
    }

    // Emergency Modals
    if (activeModal != null) {
        when (activeModal) {
            "emergency_vercel" -> com.mlmvpn.scanner.emergency.VercelEmergencyScreen(onBack = { activeModal = null })
            "emergency_2" -> com.mlmvpn.scanner.ui.emergency.EmergencyLevel2Screen(onBack = { activeModal = null })
            "emergency_3" -> com.mlmvpn.scanner.ui.emergency.EmergencyLevel3Screen(onBack = { activeModal = null })
            "antisanction" -> com.mlmvpn.scanner.ui.sanction.AntiSanctionScreen(onBack = { activeModal = null })
        }
    }

    if (showExitDialog) {
        // While the dialog is up, a second physical back press exits immediately (the classic
        // double-tap-to-exit), and this BackHandler out-prioritises the global one above.
        BackHandler(enabled = true) { activity?.finish() }
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = surfaceColor,
            icon = { Icon(Icons.Default.ExitToApp, contentDescription = null, tint = primaryColor) },
            title = { Text("خروج از برنامه؟", color = textColor, fontWeight = FontWeight.Bold) },
            text = { Text("برای خروج دوباره دکمه‌ی برگشت را بزنید یا «خروج» را انتخاب کنید.", color = mutedColor) },
            confirmButton = {
                Button(
                    onClick = { showExitDialog = false; activity?.finish() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF28B82))
                ) { Text("خروج", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("ماندن", color = primaryColor)
                }
            }
        )
    }
}

// Removed TutorialModal, now using HelpCenterScreen


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsModal(onDismiss: () -> Unit, onOpenVpnSettings: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }
    val defaultPrefs = remember { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    
    var dnsServer by remember { mutableStateOf(sharedPrefs.getString("backend_dns", "1.1.1.1") ?: "1.1.1.1") }
    var proxyMode by remember { mutableStateOf(defaultPrefs.getBoolean("proxy_mode", false)) }
    var localPort by remember { mutableStateOf(defaultPrefs.getString("local_port", "10808") ?: "10808") }
    var allowLan by remember { mutableStateOf(defaultPrefs.getBoolean("allow_lan", false)) }
    var showRealtimeTraffic by remember { mutableStateOf(defaultPrefs.getBoolean("show_realtime_traffic", true)) }
    var enableUsageTracking by remember { mutableStateOf(defaultPrefs.getBoolean("enable_usage_tracking", true)) }
    // Repointed after the migration: this preference now gates the Aether tab. We carry
    // over the legacy `enable_wireguard_tab` value once so existing users keep whatever
    // on/off choice they made for the old tab, then write back to `enable_aether_tab`.
    var enableAetherTab by remember { mutableStateOf(
        defaultPrefs.getBoolean("enable_aether_tab", defaultPrefs.getBoolean("enable_wireguard_tab", true))
    )}
    var enableGameTab by remember { mutableStateOf(defaultPrefs.getBoolean("enable_game_tab", false)) }

    var screenOffTimeout by remember { mutableStateOf(defaultPrefs.getString("screen_off_timeout", "0") ?: "0") }
    var expandedTimeoutMenu by remember { mutableStateOf(false) }
    
    var appLanguage by remember { mutableStateOf(com.mlmvpn.scanner.utils.AppLocaleManager.currentLanguage.value) }
    var expandedLanguageMenu by remember { mutableStateOf(false) }
    
    val languageOptions = mapOf(
        "auto" to stringResource(R.string.settings_lang_auto),
        "fa" to stringResource(R.string.settings_lang_fa),
        "en" to stringResource(R.string.settings_lang_en)
    )
    
    val timeoutOptions = mapOf(
        "0" to stringResource(R.string.settings_timeout_0),
        "1" to stringResource(R.string.settings_timeout_1),
        "5" to stringResource(R.string.settings_timeout_5),
        "30" to stringResource(R.string.settings_timeout_30),
        "60" to stringResource(R.string.settings_timeout_60)
    )
    
    val bgColor = Color(0xFF202124)
    val primaryColor = Color(0xFF8AB4F8)
    val textColor = Color(0xFFE8EAED)
    
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = primaryColor,
        checkedTrackColor = primaryColor.copy(alpha = 0.5f),
        uncheckedThumbColor = Color.Gray,
        uncheckedTrackColor = Color.DarkGray
    )
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(0.dp),
        color = bgColor
    ) {
        // bottom = 100.dp clears the floating bottom nav (≈78dp) so pinned buttons / the end of the
        // scroll area never hide under it. Matches the app-wide 100dp nav-clearance convention; the
        // root already handles the system nav bar via systemBarsPadding(), so this is a fixed,
        // version-independent value that looks identical on Android <15 and 15/16.
        Column(modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_title), color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_backend_dns), color = textColor, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = dnsServer,
                    onValueChange = { dnsServer = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = textColor),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor, unfocusedBorderColor = Color.DarkGray)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_proxy_mode), color = textColor, fontSize = 14.sp)
                        Text(stringResource(R.string.settings_proxy_mode_desc), color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = proxyMode, onCheckedChange = { proxyMode = it }, colors = switchColors)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(stringResource(R.string.settings_local_port), color = textColor, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = localPort,
                    onValueChange = { localPort = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = textColor),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor, unfocusedBorderColor = Color.DarkGray)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_allow_lan), color = textColor, fontSize = 14.sp)
                        Text(stringResource(R.string.settings_allow_lan_desc), color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = allowLan, onCheckedChange = { allowLan = it }, colors = switchColors)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_realtime_traffic), color = textColor, fontSize = 14.sp)
                        Text(stringResource(R.string.settings_realtime_traffic_desc), color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = showRealtimeTraffic, onCheckedChange = { showRealtimeTraffic = it }, colors = switchColors)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_usage_tracking), color = textColor, fontSize = 14.sp)
                        Text(stringResource(R.string.settings_usage_tracking_desc), color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = enableUsageTracking, onCheckedChange = { enableUsageTracking = it }, colors = switchColors)
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_enable_wireguard_tab), color = textColor, fontSize = 14.sp)
                        Text(stringResource(R.string.settings_enable_wireguard_tab_desc), color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = enableAetherTab, onCheckedChange = { enableAetherTab = it }, colors = switchColors)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_enable_game_tab), color = textColor, fontSize = 14.sp)
                        Text(stringResource(R.string.settings_enable_game_tab_desc), color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = enableGameTab, onCheckedChange = { enableGameTab = it }, colors = switchColors)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(stringResource(R.string.settings_screen_off_timeout), color = textColor, fontSize = 14.sp)
                Text(stringResource(R.string.settings_screen_off_timeout_desc), color = Color.Gray, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(8.dp))
                
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expandedTimeoutMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor)
                    ) {
                        Text(timeoutOptions[screenOffTimeout] ?: stringResource(R.string.settings_select))
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = expandedTimeoutMenu,
                        onDismissRequest = { expandedTimeoutMenu = false },
                        modifier = Modifier.background(Color(0xFF2D2E31))
                    ) {
                        timeoutOptions.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = textColor) },
                                onClick = {
                                    screenOffTimeout = key
                                    expandedTimeoutMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(stringResource(R.string.settings_language), color = textColor, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expandedLanguageMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor)
                    ) {
                        Text(languageOptions[appLanguage] ?: stringResource(R.string.settings_select))
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = expandedLanguageMenu,
                        onDismissRequest = { expandedLanguageMenu = false },
                        modifier = Modifier.background(Color(0xFF2D2E31))
                    ) {
                        languageOptions.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = textColor) },
                                onClick = {
                                    appLanguage = key
                                    expandedLanguageMenu = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onOpenVpnSettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88), contentColor = Color.Black),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_advanced_vpn), fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Cancel/Save are pinned outside the scroll area. Inside it they could be scrolled
            // under the floating nav bar (and, on Android 15/16 edge-to-edge, under the system
            // navigation bar); the explicit bottom padding keeps them clear of both.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 92.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel), color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { 
                        sharedPrefs.edit().putString("backend_dns", dnsServer).apply()
                        defaultPrefs.edit().putBoolean("proxy_mode", proxyMode)
                            .putString("local_port", localPort)
                            .putBoolean("allow_lan", allowLan)
                            .putBoolean("show_realtime_traffic", showRealtimeTraffic)
                            .putBoolean("enable_usage_tracking", enableUsageTracking)
                            .putBoolean("enable_aether_tab", enableAetherTab)
                            .putBoolean("enable_game_tab", enableGameTab)
                            .putString("screen_off_timeout", screenOffTimeout).apply()
                        
                        val oldLang = com.mlmvpn.scanner.utils.AppLocaleManager.currentLanguage.value
                        com.mlmvpn.scanner.utils.AppLocaleManager.setLanguage(context, appLanguage)
                        
                        android.widget.Toast.makeText(context, context.getString(R.string.settings_saved), android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss()
                        
                        if (oldLang != appLanguage) {
                            // Unwrap ContextWrapper chain to find the actual Activity
                            var ctx: android.content.Context = context
                            while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) {
                                ctx = ctx.baseContext
                            }
                            (ctx as? android.app.Activity)?.recreate()
                        }
                    }) {
                        Text(stringResource(R.string.common_save), color = primaryColor, fontWeight = FontWeight.Bold)
                    }
            }
        }
    }
}

@Composable
fun CustomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    isActive: Boolean,
    onClick: () -> Unit,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val scale by androidx.compose.animation.core.animateFloatAsState(targetValue = if (isActive) 1.1f else 1f)
    val color = if (isActive) activeColor else Color(0xFF9AA0A6)

    Box(
        modifier = modifier
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, // Removes the ripple effect for a cleaner look
                onClick = onClick
            )
            .padding(horizontal = 2.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(scale)) {
            Icon(icon, contentDescription = text, tint = color, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text,
                color = color,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The raised circular VPN Gate entry in the middle of the floating bar. Sits above the bar
 * surface rather than inside the Row, so adding it never costs the other items any width.
 */
@Composable
fun CenterNavButton(
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isActive) 1.06f else 1f, label = "centerNav"
    )
    Box(
        modifier = modifier.size(62.dp).scale(scale),
        contentAlignment = Alignment.Center
    ) {
        // Soft ring that separates the button from whatever content scrolls underneath.
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(Color(0xFF121212))
        )
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        if (isActive) listOf(activeColor, activeColor.copy(alpha = 0.75f))
                        else listOf(Color(0xFF3C4043), Color(0xFF2A2D30))
                    )
                )
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.VpnKey,
                contentDescription = "GATE MLMVPN",
                tint = if (isActive) Color(0xFF121212) else Color(0xFFE8EAED),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
fun CustomDrawerItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = text, tint = Color(0xFF9AA0A6), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, color = Color(0xFFE8EAED), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(
            Icons.Default.ChevronRight, 
            contentDescription = null, 
            tint = Color(0xFF5F6368), 
            modifier = Modifier.size(16.dp).scale(scaleX = if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) -1f else 1f, scaleY = 1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageModal(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val trafficManager = remember { com.mlmvpn.scanner.data.TrafficManager(context) }
    
    var today by remember { mutableStateOf(trafficManager.getTodayTraffic()) }
    var weekly by remember { mutableStateOf(trafficManager.getWeeklyTraffic()) }
    var monthly by remember { mutableStateOf(trafficManager.getMonthlyTraffic()) }
    var last7Days by remember { mutableStateOf(trafficManager.getTrafficForDays(7).reversed()) } // Oldest to newest
    
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000) // live update every 3s
            today = trafficManager.getTodayTraffic()
            weekly = trafficManager.getWeeklyTraffic()
            monthly = trafficManager.getMonthlyTraffic()
            last7Days = trafficManager.getTrafficForDays(7).reversed()
        }
    }
    
    val bgColor = Color(0xFF202124)
    val primaryColor = Color(0xFF8AB4F8)
    val textColor = Color(0xFFE8EAED)
    val greenOk = Color(0xFF81C995)
    
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        val mb = bytes / (1024f * 1024f)
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024f)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(0.dp),
        color = bgColor
    ) {
        // bottom = 100.dp clears the floating bottom nav (≈78dp) so pinned buttons / the end of the
        // scroll area never hide under it. Matches the app-wide 100dp nav-clearance convention; the
        // root already handles the system nav bar via systemBarsPadding(), so this is a fixed,
        // version-independent value that looks identical on Android <15 and 15/16.
        Column(modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 100.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.usage_title), color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                
                // Today Stats
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2E31)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.usage_today), color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(stringResource(R.string.usage_download), color = greenOk, fontSize = 12.sp)
                                Text(formatBytes(today.rxBytes), color = textColor, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text(stringResource(R.string.usage_upload), color = primaryColor, fontSize = 12.sp)
                                Text(formatBytes(today.txBytes), color = textColor, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text(stringResource(R.string.usage_total), color = Color.White, fontSize = 12.sp)
                                Text(formatBytes(today.rxBytes + today.txBytes), color = textColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Weekly Stats
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2E31)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.usage_last_7_days), color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(stringResource(R.string.usage_download), color = greenOk, fontSize = 12.sp)
                                Text(formatBytes(weekly.rxBytes), color = textColor, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text(stringResource(R.string.usage_upload), color = primaryColor, fontSize = 12.sp)
                                Text(formatBytes(weekly.txBytes), color = textColor, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text(stringResource(R.string.usage_total), color = Color.White, fontSize = 12.sp)
                                Text(formatBytes(weekly.rxBytes + weekly.txBytes), color = textColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Monthly Stats
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2E31)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.usage_last_30_days), color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(stringResource(R.string.usage_download), color = greenOk, fontSize = 12.sp)
                                Text(formatBytes(monthly.rxBytes), color = textColor, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text(stringResource(R.string.usage_upload), color = primaryColor, fontSize = 12.sp)
                                Text(formatBytes(monthly.txBytes), color = textColor, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text(stringResource(R.string.usage_total), color = Color.White, fontSize = 12.sp)
                                Text(formatBytes(monthly.rxBytes + monthly.txBytes), color = textColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 7-Day Chart
                Text(stringResource(R.string.usage_last_7_days), color = primaryColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                
                val maxTraffic = (last7Days.maxOfOrNull { it.rxBytes + it.txBytes } ?: 1L).coerceAtLeast(1L)
                
                Row(
                    modifier = Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF2D2E31), RoundedCornerShape(12.dp)).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    last7Days.forEach { record ->
                        val total = record.rxBytes + record.txBytes
                        val fraction = (total.toFloat() / maxTraffic.toFloat()).coerceIn(0f, 1f)
                        
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            val animatedHeight by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = fraction,
                                animationSpec = androidx.compose.animation.core.tween(1000)
                            )
                            
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom, modifier = Modifier.fillMaxHeight()) {
                                if (total > 0) {
                                    Text(
                                        text = if (total > 1024 * 1024 * 1024) String.format("%.1fG", total / (1024f * 1024f * 1024f))
                                               else if (total > 1024 * 1024) "${total / (1024 * 1024)}M"
                                               else "",
                                        color = Color.Gray,
                                        fontSize = 8.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .width(24.dp)
                                        .fillMaxHeight(animatedHeight.coerceAtLeast(0.02f))
                                        .background(primaryColor, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}

@Composable
fun EmergencyDrawerItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(com.mlmvpn.scanner.emergency.EmergencyColors.GoogleRed.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = text, tint = com.mlmvpn.scanner.emergency.EmergencyColors.GoogleRed, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, color = com.mlmvpn.scanner.emergency.EmergencyColors.GoogleRed, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Icon(
            Icons.Default.ChevronRight, 
            contentDescription = null, 
            tint = com.mlmvpn.scanner.emergency.EmergencyColors.GoogleRed.copy(alpha = 0.7f), 
            modifier = Modifier.size(16.dp).scale(scaleX = if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) -1f else 1f, scaleY = 1f)
        )
    }
}
