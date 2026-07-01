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
fun GameTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val boosterManager = remember { GameBoosterManager(context) }

    val allGamesSorted = remember { GameDatabase.getAllGamesSorted(context) }
    var selectedGame by remember { mutableStateOf(allGamesSorted.firstOrNull { it.second }?.first ?: allGamesSorted.firstOrNull()?.first) }
    var selectedRegion by remember { mutableStateOf(selectedGame?.defaultRegion ?: "ME") }
    var selectedMode by remember { mutableStateOf(BoostMode.AUTO) }

    val boosterState by boosterManager.boosterState.collectAsState()
    val bestResult by boosterManager.bestResult.collectAsState()
    val allResults by boosterManager.allResults.collectAsState()
    val testProgress by boosterManager.testProgress.collectAsState()
    val livePing by boosterManager.livePing.collectAsState()
    val originalPing by boosterManager.originalPing.collectAsState()

    var testJob by remember { mutableStateOf<Job?>(null) }
    var livePingJob by remember { mutableStateOf<Job?>(null) }
    var pendingBoostResult by remember { mutableStateOf<BoostResult?>(null) }

    LaunchedEffect(selectedGame) {
        val game = selectedGame ?: return@LaunchedEffect
        val regions = game.servers.map { it.region }
        if (selectedRegion !in regions) {
            selectedRegion = game.defaultRegion.takeIf { it in regions } ?: regions.firstOrNull() ?: "ME"
        }
    }

    LaunchedEffect(boosterState) {
        if (boosterState == BoosterState.BOOSTED) {
            val server = boosterManager.selectedServer.value
            if (server != null && livePingJob == null) {
                val proxyPort = if (bestResult?.mode == BoostMode.TUNNEL || bestResult?.mode == BoostMode.WORKER) {
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    prefs.getString("local_port", "10808")?.toIntOrNull() ?: 10808
                } else null
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

    fun startBoost(result: BoostResult) {
        if (result.mode == BoostMode.DIRECT) {
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val modes = listOf(
                    BoostMode.AUTO to stringResource(R.string.game_mode_auto),
                    BoostMode.DIRECT to stringResource(R.string.game_mode_direct),
                    BoostMode.WARP to "WARP",
                    BoostMode.TUNNEL to stringResource(R.string.game_mode_tunnel)
                )
                modes.forEach { (mode, label) ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
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
                    testJob = scope.launch {
                        val result = boosterManager.runBoostTest(game, selectedRegion, selectedMode)
                        if (result != null) {
                            startBoost(result)
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
                val currentStr = bestResult?.pingMs?.toString() ?: "نامشخص"
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
                        if (isWorker) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "⚠️ سرور متصل شده از نوع Cloudflare Worker است که از پروتکل UDP (مخصوص بازی) پشتیبانی نمی‌کند. اتصال بازی برای جلوگیری از قطعی مستقیم شده است، بنابراین پینگ داخل بازی کاهش نخواهد یافت. برای کاهش پینگ نیازمند سرور مجازی (VPS) واقعی هستید.",
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
                .clickable(onClick = onBoostClick),
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
        BoostMode.WORKER -> Icons.Default.Cloud
        BoostMode.WARP -> Icons.Default.Speed
        BoostMode.AUTO -> Icons.Default.AutoAwesome
    }
    val modeLabel = when (result.mode) {
        BoostMode.DIRECT -> "Direct"
        BoostMode.TUNNEL -> "Tunnel"
        BoostMode.WORKER -> "Worker"
        BoostMode.WARP -> "WARP"
        BoostMode.AUTO -> "Auto"
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
                        BoostMode.WORKER -> "Worker"
                        BoostMode.WARP -> "WARP"
                        BoostMode.AUTO -> "Auto"
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
