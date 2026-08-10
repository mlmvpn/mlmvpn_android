package com.mlmvpn.scanner.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.game.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private object GameColors {
    val BgDark = Color(0xFF171717)
    val SurfaceDark = Color(0xFF222222)
    val SurfaceVariant = Color(0xFF2A2A2A)
    val GameGreen = Color(0xFF00E676)
    val GameGreenDim = Color(0xFF00C853)
    val PrimaryBlue = Color(0xFF8AB4F8)
    val TextPrimary = Color(0xFFE8EAED)
    val TextMuted = Color(0xFF9AA0A6)
    val BorderDark = Color(0xFF333333)
    val PingGood = Color(0xFF00E676)
    val PingMedium = Color(0xFFFFD600)
    val PingBad = Color(0xFFFF6D00)
    val PingTerrible = Color(0xFFF28B82)
    val Gold = Color(0xFFFFD700)
}

private fun pingColor(ping: Long): Color = when {
    ping < 0 -> GameColors.TextMuted
    ping < 60 -> GameColors.PingGood
    ping < 100 -> GameColors.PingMedium
    ping < 150 -> GameColors.PingBad
    else -> GameColors.PingTerrible
}

private fun pingLabel(ping: Long): String = when {
    ping < 0 -> "—"
    else -> "${ping}ms"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameTab(onNavigateToCloud: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val boosterManager = remember { GameBoosterManager(context) }

    // Dedicated DNS state
    val cloudManager = remember { com.mlmvpn.scanner.data.CloudManager(context) }
    val cloudAccounts by cloudManager.accountsFlow.collectAsState()
    val hasDnsWorker = cloudAccounts.any { it.dnsStatus == "deployed" && !it.dnsWorkerUrl.isNullOrEmpty() }
    val hasAnyCloudAccount = cloudAccounts.isNotEmpty()
    val dnsPrefs = remember { context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE) }
    var dnsEnabled by remember { mutableStateOf(dnsPrefs.getBoolean("dedicated_dns_enabled", true)) }
    var dnsMode by remember { mutableStateOf(dnsPrefs.getString("dedicated_dns_mode", "auto") ?: "auto") }
    var dnsManualRegion by remember { mutableStateOf(dnsPrefs.getString("dedicated_dns_manual_region", com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.DEFAULT_REGION) ?: com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.DEFAULT_REGION) }
    var dnsDeploying by remember { mutableStateOf(false) }
    var dnsDeployProgress by remember { mutableStateOf(0 to "") }
    var dnsProbing by remember { mutableStateOf(false) }
    // Per-region comparison results from the "Test all regions" button (best-ping first).
    var dnsRegionResults by remember { mutableStateOf<List<com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.RegionRaceResult>>(emptyList()) }
    // Bumped after a probe/deploy to force re-reading the per-game cached region/ping below.
    var dnsRefreshTick by remember { mutableStateOf(0) }

    val allGamesSorted = remember { GameDatabase.getAllGamesSorted(context) }
    var selectedGame by remember { mutableStateOf(allGamesSorted.firstOrNull { it.second }?.first ?: allGamesSorted.firstOrNull()?.first) }
    var selectedRegion by remember { mutableStateOf(selectedGame?.defaultRegion ?: "ME") }
    var selectedMode by remember { mutableStateOf(BoostMode.AUTO) }

    // Which Aether protocol a boost uses. Seeded from the pref so the choice survives leaving
    // the tab, and written back on every change — GameBoosterManager reads the pref rather
    // than this state, because the AUTO race builds its config without going through the UI.
    var aetherProtocol by remember {
        mutableStateOf(GameBoosterManager.gameAetherProtocol(context))
    }
    var aetherScan by remember {
        mutableStateOf(GameBoosterManager.gameAetherScan(context))
    }

    // Real connect progress from the engine, or null when nothing is in flight.
    val boostProgress by boosterManager.boostProgress.collectAsState()

    // If the DNS-only mode was selected but the worker got turned off/removed, fall back to AUTO
    // so the selector never points at a mode that's no longer offered.
    LaunchedEffect(hasDnsWorker, dnsEnabled) {
        if (selectedMode == BoostMode.DEDICATED_DNS && !(hasDnsWorker && dnsEnabled)) {
            selectedMode = BoostMode.AUTO
        }
    }

    val boosterState by boosterManager.boosterState.collectAsState()
    val bestResult by boosterManager.bestResult.collectAsState()
    val allResults by boosterManager.allResults.collectAsState()
    val testProgress by boosterManager.testProgress.collectAsState()
    val livePing by boosterManager.livePing.collectAsState()
    val originalPing by boosterManager.originalPing.collectAsState()

    var testJob by remember { mutableStateOf<Job?>(null) }
    var livePingJob by remember { mutableStateOf<Job?>(null) }
    var pendingBoostResult by remember { mutableStateOf<BoostResult?>(null) }

    // Engine-conflict modal state (WireGuard/Xray/SNI can't switch families without a process restart).
    var showEngineConflict by remember { mutableStateOf(false) }
    var conflictEngineName by remember { mutableStateOf("") }

    // Resume after an engine-conflict restart: pre-select the mode the user was trying and prompt
    // them to tap Start (now that the process is clean and the old engine is gone).
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
        val pendingMode = prefs.getString("pending_boost_mode", null)
        if (pendingMode != null && !com.mlmvpn.scanner.MyVpnService.isRunning &&
            !com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.isRunningFlow.value) {
            prefs.edit()
                .remove("pending_boost_mode")
                .remove("pending_boost_game")
                .remove("pending_boost_region")
                .apply()
            runCatching { selectedMode = BoostMode.valueOf(pendingMode) }
            Toast.makeText(context, "موتورِ قبلی غیرفعال شد. اکنون روی «شروع» بزنید.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(selectedGame) {
        val game = selectedGame ?: return@LaunchedEffect
        val regions = game.servers.map { it.region }
        if (selectedRegion !in regions) {
            selectedRegion = game.defaultRegion.takeIf { it in regions } ?: regions.firstOrNull() ?: "ME"
        }
        // Per-region DNS test results belong to a specific game -- drop them when the game changes.
        dnsRegionResults = emptyList()
    }

    LaunchedEffect(boosterState) {
        if (boosterState == BoosterState.BOOSTED) {
            val server = boosterManager.selectedServer.value
            if (server != null && livePingJob == null) {
                // Tunnel/WARP/Hybrid all expose a local SOCKS inbound on the same port -- ping
                // through it so the health monitor measures the actual tunneled path, not the
                // phone's raw internet (which is what a bare directPing would measure instead).
                // Direct DNS Boost (nodeUri != null) also runs a local VPN with its own SOCKS
                // inbound; the plain "already clean" Direct case has no VPN at all.
                val isVpnBackedMode = bestResult?.mode == BoostMode.TUNNEL ||
                    ((bestResult?.mode == BoostMode.DIRECT ||
                        bestResult?.mode == BoostMode.DEDICATED_DNS) && bestResult?.nodeUri != null)
                val proxyPort = when {
                    // Aether must be measured through ITS OWN SOCKS listener, not directly.
                    //
                    // A game boost routes only the game package through the TUN
                    // (addAllowedApplication), so this app is deliberately OUTSIDE the tunnel. A
                    // direct ping from here therefore measured the raw ISP path — the opposite
                    // of what the card claims to show — and on a filtered line those game
                    // endpoints are usually unreachable that way, so it returned -1 and the UI
                    // sat on "در حال اندازه‌گیری…" forever. Occasionally one endpoint answered
                    // directly, which is why a number sometimes appeared.
                    //
                    // 20810 is the engine's fixed SOCKS port and GamePingTester already speaks
                    // SOCKS5, so this measures the real tunneled round trip.
                    bestResult?.mode == BoostMode.AETHER ->
                        com.mlmvpn.core.aether.AetherEngine.AETHER_SOCKS_PORT

                    isVpnBackedMode -> {
                        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        prefs.getString("local_port", "10808")?.toIntOrNull() ?: 10808
                    }

                    else -> null
                }
                livePingJob = scope.launch {
                    boosterManager.startLivePingMonitor(server, proxyPort)
                }
            }
        } else {
            livePingJob?.cancel()
            livePingJob = null
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pendingResult = pendingBoostResult
            val game = selectedGame
            if (pendingResult != null && game != null) {
                val allPackages = listOf(game.packageName) + game.alternatePackages
                val pm = context.packageManager
                val installedPkg = allPackages.firstOrNull { pkg ->
                    try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
                } ?: game.packageName
                boosterManager.connectWithBestResult(pendingResult, installedPkg)
            }
            pendingBoostResult = null
        } else {
            pendingBoostResult = null
            Toast.makeText(context, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Historically AUTO could pick WireGuard purely from an RTT estimate with no nodeUri yet, and
    // this materialized the real AmneziaWG config from the live 1-hour UAE trial. Aether needs no
    // such trial/account step -- GameBoosterManager.measureAetherReachability already attaches a
    // ready-to-use Aether config to every AETHER result it produces -- so this is now a passthrough.
    // Kept as a named seam in case a future mode needs the same "resolve before connect" shape.
    fun resolveForConnect(result: BoostResult): BoostResult? = result

    fun startBoost(result: BoostResult) {
        // Direct mode only needs the VPN permission dialog when it's actually going to start a
        // local pass-through VPN (Direct DNS Boost, nodeUri != null) -- the plain "connection is
        // already clean" case has no VPN and can connect immediately, same as before.
        if (result.mode == BoostMode.DIRECT && result.nodeUri == null) {
            val game = selectedGame ?: return
            val allPackages = listOf(game.packageName) + game.alternatePackages
            val pm = context.packageManager
            val installedPkg = allPackages.firstOrNull { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
            } ?: game.packageName
            boosterManager.connectWithBestResult(result, installedPkg)
            return
        }

        pendingBoostResult = result
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            vpnLauncher.launch(vpnIntent)
        } else {
            val game = selectedGame ?: return
            val allPackages = listOf(game.packageName) + game.alternatePackages
            val pm = context.packageManager
            val installedPkg = allPackages.firstOrNull { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
            } ?: game.packageName
            boosterManager.connectWithBestResult(result, installedPkg)
            pendingBoostResult = null
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(GameColors.BgDark),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Header ──
        item {
            Column {
                Text(
                    stringResource(R.string.game_title),
                    color = GameColors.GameGreen,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.game_subtitle),
                    color = GameColors.TextMuted,
                    fontSize = 13.sp
                )
            }
        }

        // ── Game Selector ──
        item {
            Text(
                stringResource(R.string.game_select_game),
                color = GameColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(allGamesSorted) { (game, isInstalled) ->
                    GameCard(
                        game = game,
                        isInstalled = isInstalled,
                        isSelected = selectedGame?.id == game.id,
                        onClick = {
                            selectedGame = game
                            if (boosterState == BoosterState.BOOSTED) {
                                boosterManager.disconnect()
                            }
                        }
                    )
                }
            }
        }

        // ── Region Selector ──
        item {
            val game = selectedGame
            if (game != null && game.servers.size > 1) {
                var expanded by remember { mutableStateOf(false) }
                val currentServer = game.servers.find { it.region == selectedRegion } ?: game.servers.first()

                Text(
                    stringResource(R.string.game_select_region),
                    color = GameColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GameColors.TextPrimary),
                        border = BorderStroke(1.dp, GameColors.BorderDark),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(currentServer.displayName)
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(GameColors.SurfaceDark)
                    ) {
                        game.servers.forEach { server ->
                            DropdownMenuItem(
                                text = { Text(server.displayName, color = GameColors.TextPrimary) },
                                onClick = {
                                    selectedRegion = server.region
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── Mode Selector ──
        item {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // The Game tab exposes exactly these modes. DIRECT and TUNNEL are retired (no
                // real ping reduction); UAE_DNS is retired too — the UAE server it resolved
                // through has been decommissioned, so offering it could only ever time out.
                // AUTO races what remains: the user's Cloudflare DNS worker and Aether.
                val modes = buildList {
                    add(BoostMode.AUTO to stringResource(R.string.game_mode_auto))
                    if (hasDnsWorker && dnsEnabled) add(BoostMode.DEDICATED_DNS to "DNS کلادفلر")
                    add(BoostMode.AETHER to "Aether")
                }
                modes.forEach { (mode, label) ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GameColors.GameGreen.copy(alpha = 0.2f),
                            selectedLabelColor = GameColors.GameGreen,
                            containerColor = GameColors.SurfaceDark,
                            labelColor = GameColors.TextMuted
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = GameColors.BorderDark,
                            selectedBorderColor = GameColors.GameGreen.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        // ── Dedicated DNS Card ──
        item {
            val game = selectedGame
            val isFa = java.util.Locale.getDefault().language == "fa"
            val cachedRegion = remember(game?.id, dnsRefreshTick, dnsMode, dnsManualRegion) {
                if (game == null) null
                else if (dnsMode == "manual") dnsManualRegion
                else dnsPrefs.getString(com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.cacheKeyFor(game.id), null)
            }
            val cachedPing = remember(game?.id, dnsRefreshTick, dnsMode, dnsManualRegion) {
                if (game == null) -1L
                else dnsPrefs.getLong("${com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.cacheKeyFor(game.id)}_ping", -1L)
            }
            DedicatedDnsCard(
                hasAnyCloudAccount = hasAnyCloudAccount,
                isDeployed = hasDnsWorker,
                deploying = dnsDeploying,
                deployProgress = dnsDeployProgress,
                probing = dnsProbing,
                enabled = dnsEnabled,
                mode = dnsMode,
                manualRegion = dnsManualRegion,
                activeRegionCode = cachedRegion,
                activePing = cachedPing,
                regionResults = dnsRegionResults,
                isFa = isFa,
                onNavigateToCloud = onNavigateToCloud,
                onEnabledChange = { on ->
                    dnsEnabled = on
                    dnsPrefs.edit().putBoolean("dedicated_dns_enabled", on).apply()
                },
                onModeChange = { newMode ->
                    dnsMode = newMode
                    dnsPrefs.edit().putString("dedicated_dns_mode", newMode).apply()
                },
                onManualRegionChange = { code ->
                    dnsManualRegion = code
                    dnsPrefs.edit().putString("dedicated_dns_manual_region", code).apply()
                    dnsRefreshTick++
                },
                onDeploy = {
                    val account = cloudAccounts.firstOrNull()
                    if (account != null) {
                        scope.launch {
                            dnsDeploying = true
                            dnsDeployProgress = 0 to (if (isFa) "در حال شروع..." else "Starting...")
                            val (ok, msg) = cloudManager.deployDnsWorker(account) { pct, m ->
                                dnsDeployProgress = pct to m
                            }
                            dnsDeploying = false
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            if (ok) dnsRefreshTick++
                        }
                    }
                },
                onTestAll = {
                    val g = game
                    if (g != null) {
                        scope.launch {
                            dnsProbing = true
                            dnsRegionResults = emptyList()
                            val results = boosterManager.testAllDnsRegions(g, selectedRegion)
                            dnsRegionResults = results
                            dnsProbing = false
                            dnsRefreshTick++ // refresh the cached "best region" badge
                            if (results.isEmpty()) {
                                Toast.makeText(context, if (isFa) "هیچ منطقه‌ای جواب نداد — DNS اختصاصی یا اتصال را بررسی کنید" else "No region responded — check dedicated DNS or your connection", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onPickRegion = { code ->
                    // Tapping a region in the results list switches to Manual + that region.
                    dnsMode = "manual"
                    dnsManualRegion = code
                    dnsPrefs.edit()
                        .putString("dedicated_dns_mode", "manual")
                        .putString("dedicated_dns_manual_region", code)
                        .apply()
                    dnsRefreshTick++
                }
            )
        }

        // ── Aether Status Card ──
        // No trial/account step needed here (unlike the old WireGuard-tab UAE trial) -- Aether
        // self-enrolls and picks a healthy endpoint from Cloudflare's WARP pool on first connect.
        if (selectedMode == BoostMode.AETHER) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = GameColors.SurfaceDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.SportsEsports, contentDescription = null, tint = GameColors.GameGreen, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("موتور Aether — فقط بازی انتخاب‌شده از تونل رد می‌شود. برای اتصال روی «شروع» بزنید.", color = GameColors.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)

                        // Protocol picker. WireGuard is the lower-overhead data plane — no HTTP
                        // framing around each datagram — so for a latency-bound workload it is
                        // worth being able to measure against MASQUE on the actual line rather
                        // than assuming. MASQUE stays the default because it is the mode proven
                        // to carry traffic here.
                        Spacer(Modifier.height(12.dp))
                        Text("پروتکل", color = GameColors.TextMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                com.mlmvpn.core.aether.AetherProtocol.MASQUE to "MASQUE (پیش‌فرض)",
                                com.mlmvpn.core.aether.AetherProtocol.WG to "WireGuard",
                            ).forEach { (proto, label) ->
                                FilterChip(
                                    selected = aetherProtocol == proto,
                                    onClick = {
                                        aetherProtocol = proto
                                        context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
                                            .edit()
                                            .putString(GameBoosterManager.PREF_AETHER_PROTOCOL, proto.name)
                                            .apply()
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GameColors.GameGreen.copy(alpha = 0.2f),
                                        selectedLabelColor = GameColors.GameGreen,
                                    ),
                                )
                            }
                        }
                        // Scan mode. TURBO takes the first endpoint that works and is
                        // connected in seconds; BALANCED keeps looking and picks the
                        // lowest-RTT one, which is a real ping win on a line where more than
                        // one endpoint survives — and wasted minutes on a line where none do.
                        // The user's connection decides which is true, so the user chooses.
                        Spacer(Modifier.height(10.dp))
                        Text("حالت اسکن", color = GameColors.TextMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                com.mlmvpn.core.aether.AetherScan.TURBO to "توربو (سریع)",
                                com.mlmvpn.core.aether.AetherScan.BALANCED to "متعادل (کم‌پینگ‌تر)",
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = aetherScan == mode,
                                    onClick = {
                                        aetherScan = mode
                                        context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
                                            .edit()
                                            .putString(GameBoosterManager.PREF_AETHER_SCAN, mode.name)
                                            .apply()
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GameColors.GameGreen.copy(alpha = 0.2f),
                                        selectedLabelColor = GameColors.GameGreen,
                                    ),
                                )
                            }
                        }

                        Text(
                            "هر دو پروتکل روی UDP/QUIC اجرا می‌شوند — برای بازی لازم است.\n" +
                                "توربو معمولاً چند ثانیه، متعادل تا دو دقیقه طول می‌کشد.",
                            color = GameColors.TextMuted, fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )

                        // Live connect progress. Without this the button flipped to "بوست شد"
                        // the instant the service was asked to start — before identity
                        // enrolment, before the gateway scan, before any traffic moved.
                        boostProgress?.let { stage ->
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = GameColors.GameGreen,
                                )
                                Text(stage, color = GameColors.GameGreen, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ── Boost Button ──
        item {
            BoostButton(
                state = boosterState,
                progress = testProgress,
                onBoostClick = {
                    val game = selectedGame ?: return@BoostButton
                    if (boosterState == BoosterState.BOOSTED) {
                        boosterManager.disconnect()
                        return@BoostButton
                    }
                    if (boosterState == BoosterState.TESTING) {
                        testJob?.cancel()
                        testJob = null
                        boosterManager.boosterState.value = BoosterState.IDLE
                        return@BoostButton
                    }

                    // Engine-conflict guard: Aether (separate process, owns its own TUN) and Xray
                    // (libgojni, spins up a gomobile Go runtime) cannot both be up at once without
                    // one fighting the other for the TUN/VpnService. So if a different engine family
                    // is already running, warn and require a clean restart before the boost.
                    val activeEngine = activeEngineLabelFa()
                    if (activeEngine != null) {
                        // commit() (synchronous): the confirm path kills the process, so an async
                        // apply() could be lost before the restart and the resume would never fire.
                        context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE).edit()
                            .putString("pending_boost_mode", selectedMode.name)
                            .putString("pending_boost_game", game.id)
                            .putString("pending_boost_region", selectedRegion)
                            .commit()
                        conflictEngineName = activeEngine
                        showEngineConflict = true
                        return@BoostButton
                    }

                    if (selectedMode == BoostMode.AETHER) {
                        // Aether needs no trial/account step -- it self-enrolls and picks a healthy
                        // endpoint from Cloudflare's WARP pool on its own the first time it connects.
                        //
                        // buildGameConfig(), NOT a locally-assembled AetherOptions. This path used
                        // to construct its own plain MASQUE/TURBO options, so the Game tab's whole
                        // latency profile (h3, ping-ranked scanning, light obfuscation, short
                        // keepalive) applied to the AUTO race and silently skipped the button the
                        // user actually presses. One definition now, in AetherTunEngine.
                        val aetherProto = GameBoosterManager.gameAetherProtocol(context)
                        val aetherScanMode = GameBoosterManager.gameAetherScan(context)
                        // Measure the un-boosted baseline FIRST, while the tunnel is still down.
                        //
                        // Every other mode reaches runBoostTest(), which records this as a side
                        // effect. The Aether button skips that entirely — it builds its result
                        // inline and connects — so originalPing was never written and the card
                        // read "نامشخص (مسدود)" on every Aether boost. It has to happen here,
                        // before the TUN exists, or it measures the tunnel instead of the line.
                        selectedGame?.servers?.find { it.region == selectedRegion }?.let { srv ->
                            scope.launch { boosterManager.measureBaselinePing(srv) }
                        }
                        val aetherResult = BoostResult(
                            mode = BoostMode.AETHER,
                            pingMs = 1L, // placeholder -- real ping only known once connected
                            jitterMs = 0L,
                            nodeId = "game_aether",
                            nodeName = "Aether",
                            nodeUri = com.mlmvpn.core.aether.AetherTunEngine.buildGameConfig(aetherProto, aetherScanMode),
                            details = "موتور Aether (${aetherProto.displayFa})"
                        )
                        startBoost(aetherResult)
                    } else {
                        testJob = scope.launch {
                            val result = boosterManager.runBoostTest(game, selectedRegion, selectedMode)
                            if (result != null) {
                                val ready = resolveForConnect(result)
                                if (ready != null) startBoost(ready)
                            }
                        }
                    }
                }
            )
        }

        // ── Test Results ──
        if (allResults.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.game_result_best),
                    color = GameColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            items(allResults) { result ->
                ResultCard(
                    result = result,
                    isBest = result == bestResult,
                    onConnect = { startBoost(result) }
                )
            }
        }

        // ── Live Ping Monitor ──
        if (boosterState == BoosterState.BOOSTED) {
            item {
                LivePingCard(ping = livePing, bestResult = bestResult)
            }
            item {
                val origStr = if (originalPing > 0) "$originalPing" else "نامشخص (مسدود)"
                // Prefer the live measured ping; fall back to the test result. Ignore the WireGuard
                // placeholder (1ms) so we never show a fake "1".
                val currentStr = when {
                    livePing > 0 -> "$livePing"
                    (bestResult?.pingMs ?: 0L) > 1L -> "${bestResult?.pingMs}"
                    else -> "در حال اندازه‌گیری…"
                }
                val gameName = selectedGame?.name ?: "بازی"
                
                Surface(
                    color = GameColors.GameGreen.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, GameColors.GameGreen.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "پینگ شما در حالت عادی با اینترنت خودتان در $gameName $origStr هست و بعد از روشن شدن بوست $currentStr شد.",
                            color = GameColors.GameGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                        
                        val nodeUri = bestResult?.nodeUri ?: ""
                        val isWorker = nodeUri.contains("workers.dev", ignoreCase = true) || nodeUri.contains("pages.dev", ignoreCase = true)
                        val isCdnTunnel = bestResult?.mode == BoostMode.TUNNEL && (
                            nodeUri.contains("workers.dev", ignoreCase = true) ||
                            nodeUri.contains("pages.dev", ignoreCase = true) ||
                            // VLESS/Trojan over WebSocket on Cloudflare CDN = TCP-only, no UDP
                            (nodeUri.contains("type=ws", ignoreCase = true) && !nodeUri.startsWith("{"))
                        )
                        val isTcpOnly = isWorker || isCdnTunnel
                        if (isTcpOnly) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isWorker) {
                                    "⚠️ سرور متصل شده از نوع Cloudflare Worker است که از پروتکل UDP پشتیبانی نمی‌کند. پینگ داخل بازی کاهش نخواهد یافت. برای بهترین نتیجه از «Aether» استفاده کنید."
                                } else {
                                    "⚠️ نود تونل فعلی روی CDN (TCP-only) است و ترافیک UDP بازی رو پشتیبانی نمی‌کنه. برای کاهش پینگ بازی از «Aether» استفاده کنید."
                                },
                                color = GameColors.PingTerrible,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // ── Failed State ──
        if (boosterState == BoosterState.FAILED) {
            item {
                Surface(
                    color = GameColors.SurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = GameColors.PingTerrible, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.game_failed),
                            color = GameColors.PingTerrible,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    if (showEngineConflict) {
        EngineConflictDialog(
            engineName = conflictEngineName,
            onConfirm = {
                showEngineConflict = false
                stopAllEnginesAndRestart(context)
            },
            onDismiss = {
                showEngineConflict = false
                // User cancelled -- drop the pending resume so it doesn't fire later.
                context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE).edit()
                    .remove("pending_boost_mode")
                    .remove("pending_boost_game")
                    .remove("pending_boost_region")
                    .apply()
            }
        )
    }
}

private fun getGameIcon(context: android.content.Context, game: GameInfo): Drawable? {
    val pm = context.packageManager
    val allPackages = listOf(game.packageName) + game.alternatePackages
    for (pkg in allPackages) {
        try { return pm.getApplicationIcon(pkg) } catch (_: PackageManager.NameNotFoundException) {}
    }
    return null
}

// ── Game Card ──
@Composable
private fun GameCard(
    game: GameInfo,
    isInstalled: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val scale by animateFloatAsState(if (isSelected) 1.05f else 1f, label = "scale")
    val borderColor = if (isSelected) GameColors.GameGreen else GameColors.BorderDark
    val gameIcon = remember(game.id) { getGameIcon(context, game) }

    Surface(
        modifier = Modifier
            .width(90.dp)
            .scale(scale)
            .clickable(onClick = onClick),
        color = GameColors.SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (gameIcon != null) {
                Image(
                    bitmap = gameIcon.toBitmap(48, 48).asImageBitmap(),
                    contentDescription = game.name,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Text(game.iconEmoji, fontSize = 28.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                game.name,
                color = if (isSelected) GameColors.GameGreen else GameColors.TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (isInstalled) {
                Box(
                    modifier = Modifier
                        .background(GameColors.GameGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("✓", color = GameColors.GameGreen, fontSize = 9.sp)
                }
            } else {
                Text("—", color = GameColors.TextMuted, fontSize = 9.sp)
            }
        }
    }
}

// ── Boost Button ──
@Composable
private fun BoostButton(
    state: BoosterState,
    progress: Pair<Int, Int>,
    onBoostClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "boost")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "rotate"
    )

    val buttonScale = when (state) {
        BoosterState.IDLE, BoosterState.FAILED -> pulseScale
        else -> 1f
    }

    val gradientColors = when (state) {
        BoosterState.BOOSTED -> listOf(GameColors.GameGreen, GameColors.GameGreenDim)
        BoosterState.TESTING, BoosterState.CONNECTING -> listOf(Color(0xFFFF9800), Color(0xFFFF5722))
        else -> listOf(GameColors.GameGreen, Color(0xFF00BFA5))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .scale(buttonScale)
                .clip(CircleShape)
                .background(Brush.radialGradient(gradientColors))
                // Not clickable while a connect is in flight.
                //
                // The button used to accept taps in every state. With Aether that is actively
                // harmful: the connect takes seconds (identity enrolment, then a gateway scan),
                // and a second tap during that window re-enters the handler, finds the engine
                // it just started already running, and shows the "a VPN is already on" conflict
                // dialog — for a boost the user themselves started one tap earlier. Which is
                // exactly the reported "first tap does nothing, second tap warns about the VPN".
                .clickable(
                    enabled = state != BoosterState.TESTING && state != BoosterState.CONNECTING,
                    onClick = onBoostClick,
                ),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                BoosterState.TESTING, BoosterState.CONNECTING -> {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp).rotate(rotation)
                    )
                }
                BoosterState.BOOSTED -> {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
                else -> {
                    Icon(
                        Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val statusText = when (state) {
            BoosterState.IDLE, BoosterState.FAILED -> stringResource(R.string.game_boost_btn)
            BoosterState.TESTING -> {
                if (progress.second > 0) {
                    stringResource(R.string.game_testing_progress, progress.first, progress.second)
                } else {
                    stringResource(R.string.game_testing)
                }
            }
            BoosterState.CONNECTING -> stringResource(R.string.game_testing)
            BoosterState.BOOSTED -> stringResource(R.string.game_boosted)
        }

        Text(
            statusText,
            color = when (state) {
                BoosterState.BOOSTED -> GameColors.GameGreen
                BoosterState.TESTING -> Color(0xFFFF9800)
                else -> GameColors.TextMuted
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Result Card ──
@Composable
private fun ResultCard(
    result: BoostResult,
    isBest: Boolean,
    onConnect: () -> Unit
) {
    val modeIcon = when (result.mode) {
        BoostMode.DIRECT -> Icons.Default.FlashOn
        BoostMode.TUNNEL -> Icons.Default.Shield
        BoostMode.WARP -> Icons.Default.Speed
        BoostMode.DEDICATED_DNS -> Icons.Default.Dns
        BoostMode.UAE_DNS -> Icons.Default.Dns
        BoostMode.AUTO -> Icons.Default.AutoAwesome
        BoostMode.AETHER -> Icons.Default.VpnLock
    }
    val modeLabel = when (result.mode) {
        BoostMode.DIRECT -> "Direct"
        BoostMode.TUNNEL -> "Tunnel"
        BoostMode.WARP -> "WARP"
        BoostMode.DEDICATED_DNS -> "DNS اختصاصی"
        BoostMode.UAE_DNS -> "DNS امارات"
        BoostMode.AUTO -> "Auto"
        BoostMode.AETHER -> "Aether"
    }
    val borderColor = if (isBest) GameColors.Gold else GameColors.BorderDark

    Surface(
        color = GameColors.SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (isBest) 2.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (isBest) GameColors.Gold.copy(alpha = 0.15f) else GameColors.SurfaceVariant,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    modeIcon,
                    contentDescription = null,
                    tint = if (isBest) GameColors.Gold else GameColors.TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(modeLabel, color = GameColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (isBest) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("★", color = GameColors.Gold, fontSize = 12.sp)
                    }
                }
                if (result.details.isNotEmpty()) {
                    Text(result.details, color = GameColors.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // Ping
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    pingLabel(result.pingMs),
                    color = pingColor(result.pingMs),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (result.jitterMs > 0) {
                    Text(
                        "±${result.jitterMs}ms",
                        color = GameColors.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (!isBest) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onConnect,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = GameColors.GameGreen, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ── Live Ping Card ──
@Composable
private fun LivePingCard(ping: Long, bestResult: BoostResult?) {
    Surface(
        color = GameColors.SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, GameColors.GameGreen.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(GameColors.GameGreen, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.game_ping_live),
                    color = GameColors.GameGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.weight(1f))
                if (bestResult != null) {
                    val modeLabel = when (bestResult.mode) {
                        BoostMode.DIRECT -> "Direct"
                        BoostMode.TUNNEL -> "Tunnel"
                        BoostMode.WARP -> "WARP"
                        BoostMode.DEDICATED_DNS -> "DNS اختصاصی"
                        BoostMode.UAE_DNS -> "DNS امارات"
                        BoostMode.AUTO -> "Auto"
                        BoostMode.AETHER -> "Aether"
                    }
                    Text(modeLabel, color = GameColors.TextMuted, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    if (ping > 0) "${ping}" else "—",
                    color = pingColor(ping),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (ping > 0) {
                    Text(
                        "ms",
                        color = pingColor(ping).copy(alpha = 0.7f),
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Ping bar
            if (ping > 0) {
                val fraction = (ping.toFloat() / 200f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(GameColors.SurfaceVariant, RoundedCornerShape(3.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(GameColors.PingGood, pingColor(ping))
                                ),
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedicatedDnsCard(
    hasAnyCloudAccount: Boolean,
    isDeployed: Boolean,
    deploying: Boolean,
    deployProgress: Pair<Int, String>,
    probing: Boolean,
    enabled: Boolean,
    mode: String,
    manualRegion: String,
    activeRegionCode: String?,
    activePing: Long,
    regionResults: List<com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.RegionRaceResult>,
    isFa: Boolean,
    onNavigateToCloud: (() -> Unit)?,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (String) -> Unit,
    onManualRegionChange: (String) -> Unit,
    onDeploy: () -> Unit,
    onTestAll: () -> Unit,
    onPickRegion: (String) -> Unit
) {
    val accent = GameColors.PrimaryBlue
    val title = if (isFa) "DNS اختصاصی" else "Dedicated DNS"

    Surface(
        color = GameColors.SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, GameColors.BorderDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = if (isDeployed) GameColors.GameGreen else accent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    color = if (isDeployed) GameColors.GameGreen else GameColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                if (isDeployed && !deploying) {
                    // Master on/off switch: when off, no dedicated DNS is applied to any boost mode.
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GameColors.GameGreen,
                            checkedTrackColor = GameColors.GameGreen.copy(alpha = 0.4f),
                            uncheckedThumbColor = GameColors.TextMuted,
                            uncheckedTrackColor = GameColors.SurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when {
                // 1. No Cloudflare account connected
                !hasAnyCloudAccount -> {
                    Text(
                        text = if (isFa)
                            "برای فعال‌سازی، ابتدا باید یک حساب Cloudflare خودتان را در تب کلاد وصل کنید."
                        else
                            "To enable this, first connect your own Cloudflare account in the Cloud tab.",
                        color = GameColors.TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    if (onNavigateToCloud != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onNavigateToCloud,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isFa) "اتصال حساب کلادفلر" else "Connect Cloudflare Account")
                        }
                    }
                }

                // 2. Deploying
                deploying -> {
                    Text(
                        text = deployProgress.second.ifEmpty { if (isFa) "در حال استقرار..." else "Deploying..." },
                        color = GameColors.TextPrimary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = (deployProgress.first / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                        color = GameColors.GameGreen,
                        trackColor = GameColors.BorderDark
                    )
                }

                // 3. Account present, not deployed yet
                !isDeployed -> {
                    Text(
                        text = if (isFa)
                            "یک DNS شخصی روی حساب Cloudflare خودتان مستقر می‌شود که با کنترل هوشمند منطقه، پینگ بازی را کم می‌کند."
                        else
                            "Deploys a private DNS resolver on your own Cloudflare account that lowers game ping by smart region steering.",
                        color = GameColors.TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onDeploy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = GameColors.GameGreenDim),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isFa) "فعال‌سازی DNS اختصاصی" else "Deploy Dedicated DNS")
                    }
                }

                // 4. Deployed but turned off via the master switch
                !enabled -> {
                    Text(
                        text = if (isFa)
                            "DNS اختصاصی مستقر شده ولی خاموش است. برای استفاده در بوست، کلید بالا را روشن کنید."
                        else
                            "Dedicated DNS is deployed but turned off. Flip the switch above to use it during boost.",
                        color = GameColors.TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }

                // 5. Active / deployed / enabled
                else -> {
                    // Region badge + ping
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val region = com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.regionByCode(activeRegionCode)
                        val regionName = if (isFa) region.nameFa else region.nameEn
                        Surface(
                            color = GameColors.GameGreen.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = (if (isFa) "منطقه: " else "Region: ") + regionName,
                                color = GameColors.GameGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        if (activePing > 0) {
                            Text(
                                text = pingLabel(activePing),
                                color = pingColor(activePing),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Auto / Manual toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val options = listOf(
                            "auto" to (if (isFa) "خودکار" else "Auto"),
                            "manual" to (if (isFa) "دستی" else "Manual")
                        )
                        options.forEach { (value, label) ->
                            FilterChip(
                                selected = mode == value,
                                onClick = { onModeChange(value) },
                                label = { Text(label, fontSize = 12.sp) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = GameColors.GameGreen.copy(alpha = 0.2f),
                                    selectedLabelColor = GameColors.GameGreen,
                                    containerColor = GameColors.SurfaceVariant,
                                    labelColor = GameColors.TextMuted
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = GameColors.BorderDark,
                                    selectedBorderColor = GameColors.GameGreen.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }

                    // Manual region dropdown
                    if (mode == "manual") {
                        Spacer(modifier = Modifier.height(8.dp))
                        var expanded by remember { mutableStateOf(false) }
                        val selected = com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.regionByCode(manualRegion)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = GameColors.TextPrimary),
                                border = BorderStroke(1.dp, GameColors.BorderDark),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isFa) selected.nameFa else selected.nameEn)
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.background(GameColors.SurfaceDark)
                            ) {
                                com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.ALL_REGIONS.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text(if (isFa) r.nameFa else r.nameEn, color = GameColors.TextPrimary) },
                                        onClick = {
                                            onManualRegionChange(r.code)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Test-all-regions button: measures every region on the user's own connection
                    // for the selected game and lists the results so they can compare and pick.
                    Button(
                        onClick = onTestAll,
                        enabled = !probing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GameColors.PrimaryBlue.copy(alpha = 0.18f),
                            contentColor = GameColors.PrimaryBlue,
                            disabledContainerColor = GameColors.SurfaceVariant,
                            disabledContentColor = GameColors.TextMuted
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (probing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = GameColors.PrimaryBlue
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isFa) "در حال تست همه مناطق..." else "Testing all regions...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isFa) "تست همه مناطق برای این بازی" else "Test all regions for this game", fontSize = 13.sp)
                        }
                    }

                    // Per-region results, best (lowest ping) first.
                    if (regionResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isFa) "نتیجه (پینگ واقعی به سرور بازی):" else "Results (real ping to game server):",
                            color = GameColors.TextMuted,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        regionResults.forEachIndexed { index, r ->
                            val isBest = index == 0
                            val rowBg = if (isBest) GameColors.GameGreen.copy(alpha = 0.12f) else GameColors.SurfaceVariant
                            val isCurrent = r.region.code == activeRegionCode
                            Surface(
                                color = rowBg,
                                shape = RoundedCornerShape(8.dp),
                                border = if (isCurrent) BorderStroke(1.dp, GameColors.GameGreen.copy(alpha = 0.6f)) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable { onPickRegion(r.region.code) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                                ) {
                                    if (isBest) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = null,
                                            tint = GameColors.Gold,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = if (isFa) r.region.nameFa else r.region.nameEn,
                                        color = if (isBest) GameColors.GameGreen else GameColors.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    if (r.jitterMs > 0) {
                                        Text(
                                            text = (if (isFa) "نوسان " else "jit ") + "${r.jitterMs}ms",
                                            color = GameColors.TextMuted,
                                            fontSize = 10.sp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = pingLabel(r.pingMs),
                                        color = pingColor(r.pingMs),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isFa) "برای انتخاب دستی یک منطقه، روی آن ضربه بزنید." else "Tap a region to select it manually.",
                            color = GameColors.TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

