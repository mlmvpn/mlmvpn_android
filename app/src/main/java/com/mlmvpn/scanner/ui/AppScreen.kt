package com.mlmvpn.scanner.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val hasCloudAccounts = remember { com.mlmvpn.scanner.data.CloudManager(context).accounts.isNotEmpty() }
    // After an engine-conflict restart the game boost left a "pending_boost_mode" flag -- land the
    // user straight back on the Game tab so they can retap Start on a clean process.
    val hasPendingBoost = remember {
        context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
            .getString("pending_boost_mode", null) != null
    }
    val homeTab = if (hasPendingBoost) "game" else if (hasCloudAccounts) "nodes" else "cloud"
    var activeTab by remember { mutableStateOf(homeTab) }
    // Real back stack (was a single `previousTab`, which broke nested navigation: opening a screen
    // FROM another overlay clobbered the one shared "previous" value, so the parent's back button
    // went dead). openTab() pushes; goBack() pops; bottom-nav switches reset the stack.
    val navStack = remember { mutableStateListOf<String>() }
    fun openTab(tab: String) { if (activeTab != tab) { navStack.add(activeTab); activeTab = tab } }
    fun goBack() { activeTab = navStack.removeLastOrNull() ?: homeTab }
    fun switchTab(tab: String) { navStack.clear(); activeTab = tab }
    var activeModal by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
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
    var enableWarpTab by remember { mutableStateOf(defaultPrefs.getBoolean("enable_warp_tab", false)) }
    var enableWireguardTab by remember { mutableStateOf(defaultPrefs.getBoolean("enable_wireguard_tab", false)) }
    var enableGameTab by remember { mutableStateOf(defaultPrefs.getBoolean("enable_game_tab", false)) }

    LaunchedEffect(activeTab, activeModal) {
        if (activeModal == null) {
            showRealtimeTraffic = defaultPrefs.getBoolean("show_realtime_traffic", true)
            enableWarpTab = defaultPrefs.getBoolean("enable_warp_tab", false)
            enableWireguardTab = defaultPrefs.getBoolean("enable_wireguard_tab", false)
            enableGameTab = defaultPrefs.getBoolean("enable_game_tab", false)
            if (!enableWarpTab && activeTab == "warp") {
                activeTab = "nodes"
            }
            if (!enableWireguardTab && activeTab == "wireguard") {
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

    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(2000),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "EmergencyPulse"
    )

    val targetVignette by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isEmergencyEnabled) 0.3f else 0f,
        animationSpec = androidx.compose.animation.core.tween(1000),
        label = "EmergencyFade"
    )
    
    val vignetteAlpha = targetVignette * pulse

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { androidx.compose.ui.unit.IntOffset(glitchOffset.x.toInt(), glitchOffset.y.toInt()) }
            .drawWithContent {
                drawContent()
                if (vignetteAlpha > 0f) {
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color.Red.copy(alpha = vignetteAlpha)),
                            center = center,
                            radius = size.width
                        )
                    )
                }
            }
    ) {
        ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = surfaceColor,
                modifier = Modifier.width(280.dp)
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
                CustomDrawerItem(icon = Icons.Default.Link, text = stringResource(R.string.subgen_drawer_menu), onClick = { scope.launch { drawerState.close() }; openTab("sublink") })
                CustomDrawerItem(icon = Icons.Default.CloudUpload, text = "Deno Panel", onClick = { scope.launch { drawerState.close() }; openTab("deno") })
                CustomDrawerItem(icon = Icons.Default.Book, text = stringResource(R.string.drawer_tutorial), onClick = { scope.launch { drawerState.close() }; openTab("tutorial") })
                CustomDrawerItem(icon = Icons.Default.Info, text = stringResource(R.string.drawer_about), onClick = { scope.launch { drawerState.close() }; openTab("about") })
                
                Spacer(modifier = Modifier.weight(1f))
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
                if (visitedTabs.contains("warp")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "warp") 0.dp else 10000.dp)) {
                        WarpTab()
                    }
                }
                if (visitedTabs.contains("wireguard")) {
                    Box(modifier = Modifier.fillMaxSize().offset(x = if (activeTab == "wireguard") 0.dp else 10000.dp)) {
                        WireguardTab()
                    }
                }
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
            }
        }
        
        // Floating Bottom Menu Overlay
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
        ) {
            Surface(
                color = surfaceColor.copy(alpha = 0.85f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CustomNavItem(
                        icon = Icons.Default.Radar,
                        text = stringResource(R.string.nav_scanner),
                        isActive = activeTab == "scanner",
                        onClick = { switchTab("scanner") },
                        activeColor = primaryColor
                    )
                    CustomNavItem(
                        icon = Icons.Default.Cloud,
                        text = stringResource(R.string.nav_cloud),
                        isActive = activeTab == "cloud",
                        onClick = { switchTab("cloud") },
                        activeColor = primaryColor
                    )
                    if (enableWarpTab) {
                        CustomNavItem(
                            icon = Icons.Default.Public,
                            text = stringResource(R.string.nav_warp),
                            isActive = activeTab == "warp",
                            onClick = { switchTab("warp") },
                            activeColor = Color(0xFF81C995)
                        )
                    }
                    if (enableWireguardTab) {
                        CustomNavItem(
                            icon = Icons.Default.Security,
                            text = stringResource(R.string.nav_wireguard),
                            isActive = activeTab == "wireguard",
                            onClick = { switchTab("wireguard") },
                            activeColor = Color(0xFF00E676)
                        )
                    }
                    if (enableGameTab) {
                        CustomNavItem(
                            icon = Icons.Default.VideogameAsset,
                            text = stringResource(R.string.nav_game),
                            isActive = activeTab == "game",
                            onClick = { switchTab("game") },
                            activeColor = Color(0xFF00E676)
                        )
                    }
                    CustomNavItem(
                        icon = Icons.Default.Shield,
                        text = stringResource(R.string.nav_nodes),
                        isActive = activeTab == "nodes",
                        onClick = { switchTab("nodes") },
                        activeColor = primaryColor
                    )
                }
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
        }
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
    var enableWarpTab by remember { mutableStateOf(defaultPrefs.getBoolean("enable_warp_tab", false)) }
    var enableWireguardTab by remember { mutableStateOf(defaultPrefs.getBoolean("enable_wireguard_tab", false)) }
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
        Column(modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 100.dp)) {
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
                    colors = TextFieldDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor, unfocusedBorderColor = Color.DarkGray)
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
                    colors = TextFieldDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor, unfocusedBorderColor = Color.DarkGray)
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
                        Text(stringResource(R.string.settings_warp_tab), color = textColor, fontSize = 14.sp)
                        Text(stringResource(R.string.settings_warp_tab_desc), color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = enableWarpTab, onCheckedChange = { enableWarpTab = it }, colors = switchColors)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_enable_wireguard_tab), color = textColor, fontSize = 14.sp)
                        Text(stringResource(R.string.settings_enable_wireguard_tab_desc), color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = enableWireguardTab, onCheckedChange = { enableWireguardTab = it }, colors = switchColors)
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
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                            .putBoolean("enable_warp_tab", enableWarpTab)
                            .putBoolean("enable_wireguard_tab", enableWireguardTab)
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
    activeColor: Color
) {
    val scale by androidx.compose.animation.core.animateFloatAsState(targetValue = if (isActive) 1.1f else 1f)
    val color = if (isActive) activeColor else Color(0xFF9AA0A6)

    Box(
        modifier = Modifier
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, // Removes the ripple effect for a cleaner look
                onClick = onClick
            )
            .padding(8.dp)
            .width(64.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(scale)) {
            Icon(icon, contentDescription = text, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
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
