package com.mlmvpn.scanner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.*
import kotlin.math.max

data class ScannedIP(
    val id: Int,
    val ip: String,
    val ping: Int,
    val downloadSpeed: Float,
    var selected: Boolean
)

@Composable
fun ScannerHeader(scanning: Boolean, pulseScale: Float, radarRotation: Float) {
    // Radar Icon with Pulse
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        if (scanning) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = 0.2f))
            )
        }
        Icon(
            Icons.Default.Radar, 
            contentDescription = null, 
            tint = Primary, 
            modifier = Modifier
                .size(72.dp)
                .then(if (scanning) Modifier.rotate(radarRotation) else Modifier)
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text(stringResource(R.string.scanner_title), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text(
        stringResource(R.string.scanner_desc),
        color = TextMuted,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
    )
}

@Composable
fun ScannerProgressCard(scanPhase: com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase, foundCount: Int, progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(24.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(24.dp))
            .padding(24.dp)
    ) {
        // Status Texts
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val phaseText = when (scanPhase) {
                com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase.PINGING -> stringResource(R.string.scanner_phase_pinging, foundCount)
                com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase.SPEED_TESTING -> stringResource(R.string.scanner_phase_speed_testing)
                com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase.DONE -> stringResource(R.string.scanner_phase_done)
                else -> stringResource(R.string.scanner_phase_ready)
            }
            Text(phaseText, color = TextPrimary, fontSize = 12.sp)
            Text("${progress.toInt()}%", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        val progressWidth by animateFloatAsState(targetValue = progress / 100f, animationSpec = tween(50))
        Box(modifier = Modifier.fillMaxWidth().height(8.dp).background(BgDark, CircleShape)) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progressWidth).background(Primary, CircleShape))
        }
    }
}

@Composable
fun ScannerSettingsCard(
    baseConfig: String,
    onBaseConfigChange: (String) -> Unit,
    testLimit: String,
    onTestLimitChange: (String) -> Unit,
    successTarget: String,
    onSuccessTargetChange: (String) -> Unit,
    onStartScan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(16.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = baseConfig,
            onValueChange = onBaseConfigChange,
            label = { Text(stringResource(R.string.scanner_base_config_label), color = TextMuted, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = BorderDark,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            singleLine = true
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = testLimit,
                onValueChange = onTestLimitChange,
                label = { Text(stringResource(R.string.scanner_test_limit_label), color = TextMuted, fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            )
            OutlinedTextField(
                value = successTarget,
                onValueChange = onSuccessTargetChange,
                label = { Text(stringResource(R.string.scanner_success_target_label), color = TextMuted, fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            )
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Surface(
        modifier = Modifier.clickable { onStartScan() },
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Radar, contentDescription = null, tint = Primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.scanner_start_scan), color = TextPrimary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun ScannerTab() {
    val scanning by com.mlmvpn.scanner.data.ScannerManager.isScanning.collectAsState()
    val progress by com.mlmvpn.scanner.data.ScannerManager.progress.collectAsState()
    val scannedIPs by com.mlmvpn.scanner.data.ScannerManager.scannedIPs.collectAsState()
    val scanPhase by com.mlmvpn.scanner.data.ScannerManager.phase.collectAsState()
    val foundCount by com.mlmvpn.scanner.data.ScannerManager.foundCount.collectAsState()
    val healthTestResults by com.mlmvpn.scanner.data.ScannerManager.healthTestResults.collectAsState()
    val lastScanNewCount by com.mlmvpn.scanner.data.ScannerManager.lastScanNewCount.collectAsState()
    val lastScanDuplicateCount by com.mlmvpn.scanner.data.ScannerManager.lastScanDuplicateCount.collectAsState()

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val groupManager = remember { com.mlmvpn.scanner.data.GroupManager(context) }
    val nodeManager = remember { com.mlmvpn.scanner.data.NodeManager(context) }
    val scannerGroupsFlowState by groupManager.scannerGroupsFlow.collectAsState()
    var scannerGroups by remember(scannerGroupsFlowState) { mutableStateOf(scannerGroupsFlowState) }
    var showActionSheet by remember { mutableStateOf(false) }
    var selectedCloudGroup by remember { mutableStateOf<String?>(null) }
    var pingingGroup by remember { mutableStateOf<String?>(null) }
    var pingProgressMap by remember { mutableStateOf(mapOf<String, Pair<Int, Int>>()) }
    var healthyNodesMap by remember { mutableStateOf(mapOf<String, List<com.mlmvpn.scanner.models.VpnNode>>()) }

    val baseConfig by com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.collectAsState()
    var testLimit by remember { mutableStateOf("50") }
    var successTarget by remember { mutableStateOf("10") }
    var showVpnDialog by remember { mutableStateOf(false) }

    val ipArchivesFlowState by groupManager.ipArchivesFlow.collectAsState()
    var ipArchives by remember(ipArchivesFlowState) { mutableStateOf(ipArchivesFlowState) }
    var mixingArchive by remember { mutableStateOf<com.mlmvpn.scanner.data.IpArchive?>(null) }
    var showRetestDialog by remember { mutableStateOf<com.mlmvpn.scanner.data.IpArchive?>(null) }
    var retestBaseConfig by remember { mutableStateOf("") }
    
    var mixToDelete by remember { mutableStateOf<com.mlmvpn.scanner.data.ScannerGroup?>(null) }
    var archiveToDelete by remember { mutableStateOf<com.mlmvpn.scanner.data.IpArchive?>(null) }

    LaunchedEffect(scanPhase) {
        if (scanPhase == com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase.DONE) {
            delay(500)
            groupManager.loadIpArchives()
            ipArchives = groupManager.ipArchives.toList()
        }
    }

    fun isVpnActive(ctx: android.content.Context): Boolean {
        try {
            val connectivityManager = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    val infiniteTransition = rememberInfiniteTransition(label = "radarPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (scanning) 2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )

    val radarRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarRotation"
    )

    var viewingGroup by remember { mutableStateOf<com.mlmvpn.scanner.data.ScannerGroup?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minScreenHeight = maxHeight
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Layout(
                content = {
                    // TOP CONTENT
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))

                        ScannerHeader(scanning, pulseScale, radarRotation)

                        if (scanning) {
                            ScannerProgressCard(scanPhase, foundCount, progress)
                        }
                        
                        if (scannedIPs.isNotEmpty()) {
                            // Results List
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                val title = if (scanPhase == com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase.DONE) stringResource(R.string.scanner_final_results) else stringResource(R.string.scanner_found_ips)
                                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Box(modifier = Modifier.background(GreenOk.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                    val countText = if (scanPhase == com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase.DONE) {
                                        if (lastScanDuplicateCount == 0) {
                                            stringResource(R.string.scanner_ips_found_no_duplicates, lastScanNewCount)
                                        } else {
                                            stringResource(R.string.scanner_ips_found_detailed, lastScanNewCount, lastScanDuplicateCount)
                                        }
                                    } else {
                                        stringResource(R.string.scanner_ips_found, scannedIPs.size)
                                    }
                                    Text(countText, color = GreenOk, fontSize = 12.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).background(SurfaceDark, RoundedCornerShape(16.dp)).border(1.dp, BorderDark, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
                                items(scannedIPs.size, key = { index -> scannedIPs[index].id }) { index ->
                                    val ip = scannedIPs[index]
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                com.mlmvpn.scanner.data.ScannerManager.toggleSelection(ip.id)
                                            }
                                            .background(if (ip.selected) Primary.copy(alpha = 0.05f) else Color.Transparent)
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                if (ip.selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                                contentDescription = null,
                                                tint = if (ip.selected) Primary else TextDim,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(ip.ip, color = TextPrimary, fontSize = 14.sp)
                                                Text("Cloudflare CDN", color = TextMuted, fontSize = 10.sp)
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val color = if (ip.ping < 50) GreenOk else if (ip.ping < 80) YellowWarn else RedError
                                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text("Ping: ${ip.ping}ms", color = if (ip.ping < 50) GreenOk else TextPrimary, fontSize = 10.sp)
                                                if (ip.downloadSpeed > 0) {
                                                    Text("Real: ${ip.downloadSpeed.toInt()}ms", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                    if (index < scannedIPs.size - 1) {
                                        Divider(color = BorderDark)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // Action Buttons
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        val selected = scannedIPs.filter { it.selected }.joinToString("\n") { it.ip }
                                        if (selected.isNotEmpty()) {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IPs", selected))
                                            android.widget.Toast.makeText(context, context.getString(R.string.scanner_ips_copied), android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = TextPrimary),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                                ) {
                                    Icon(Icons.Default.CopyAll, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.scanner_copy_ips), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }

                                Button(
                                    onClick = {
                                        groupManager.loadCloudGroups()
                                        if (groupManager.cloudGroups.isEmpty()) {
                                            android.widget.Toast.makeText(context, context.getString(R.string.scanner_no_cloud_group), android.widget.Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        showActionSheet = true
                                    },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = BgDark)
                                ) {
                                    Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.scanner_combine_with_config), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }

                            TextButton(onClick = { com.mlmvpn.scanner.data.ScannerManager.reset() }, modifier = Modifier.padding(top = 8.dp)) {
                                Text(stringResource(R.string.scanner_cancel_and_rescan), color = TextMuted, fontSize = 14.sp)
                            }
                        } else if (!scanning && scannedIPs.isEmpty()) {
                            ScannerSettingsCard(
                                baseConfig = baseConfig,
                                onBaseConfigChange = { com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.value = it },
                                testLimit = testLimit,
                                onTestLimitChange = { testLimit = it },
                                successTarget = successTarget,
                                onSuccessTargetChange = { successTarget = it },
                                onStartScan = {
                                    if (baseConfig.trim().isEmpty()) {
                                        android.widget.Toast.makeText(context, context.getString(R.string.scanner_enter_base_config_warning), android.widget.Toast.LENGTH_LONG).show()
                                    } else if (isVpnActive(context)) {
                                        showVpnDialog = true
                                    } else {
                                        com.mlmvpn.scanner.data.ScannerManager.startScan(
                                            context = context,
                                            baseConfig = baseConfig.trim(),
                                            limit = testLimit.toIntOrNull() ?: 50,
                                            successTarget = successTarget.toIntOrNull() ?: 10
                                        )
                                    }
                                }
                            )
                        }

                        if (!showActionSheet && scannerGroups.isNotEmpty()) {
                            // Combinations History Groups
                            Spacer(modifier = Modifier.height(24.dp))
                            Divider(color = BorderDark)
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Layers, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.scanner_combined_groups), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            
                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                scannerGroups.forEachIndexed { index, group ->
                                    Column(
                                        modifier = Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(16.dp)).border(1.dp, BorderDark, RoundedCornerShape(16.dp)).padding(16.dp)
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                            Column {
                                                Text(group.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                                Text(stringResource(R.string.scanner_combine_info, group.ipCount, group.sourceGroupCount), color = TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                                Text(group.date, color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                                            }
                                            val engineType = group.nodes.firstOrNull()?.engineType ?: stringResource(R.string.scanner_unknown)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Box(modifier = Modifier.background(
                                                    (if (engineType == "NHN") GreenOk else Primary).copy(alpha = 0.15f),
                                                    RoundedCornerShape(4.dp)
                                                ).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                                    Text(engineType, color = if (engineType == "NHN") GreenOk else Primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Box(modifier = Modifier.background(Primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).border(1.dp, Primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                    Text(stringResource(R.string.scanner_node_count, group.nodes.size), color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        // Progress bar for health test
                                        val progressData = pingProgressMap[group.id]
                                        if (progressData != null && pingingGroup == group.id) {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            val animatedProgress by animateFloatAsState(
                                                targetValue = if (progressData.second > 0) progressData.first.toFloat() / progressData.second else 0f,
                                                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                                            )
                                            Column {
                                                LinearProgressIndicator(
                                                    progress = animatedProgress,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp)),
                                                    color = Primary,
                                                    trackColor = BorderDark
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    "${progressData.first} / ${progressData.second}",
                                                    color = TextMuted,
                                                    fontSize = 10.sp,
                                                    modifier = Modifier.align(Alignment.End)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            IconButton(onClick = { 
                                                viewingGroup = group
                                            }, modifier = Modifier.size(48.dp).background(BgDark, RoundedCornerShape(12.dp)).border(1.dp, BorderDark, RoundedCornerShape(12.dp))) {
                                                Icon(Icons.Default.Visibility, contentDescription = "View", tint = Primary)
                                            }

                                            IconButton(onClick = { mixToDelete = group }, modifier = Modifier.size(48.dp).background(BgDark, RoundedCornerShape(12.dp)).border(1.dp, BorderDark, RoundedCornerShape(12.dp))) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted)
                                            }
                                            
                                            val isPinging = pingingGroup == group.id
                                            val healthyNodes = healthyNodesMap[group.id]

                                            if (healthyNodes == null) {
                                                Button(
                                                    onClick = { 
                                                        pingingGroup = group.id
                                                        pingProgressMap = pingProgressMap + (group.id to Pair(0, group.nodes.size))
                                                        scope.launch(Dispatchers.IO) {
                                                            try { 
                                                                val keyBytes = ByteArray(32)
                                                                java.security.SecureRandom().nextBytes(keyBytes)
                                                                val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                                                                val xudpBaseKey = android.util.Base64.encodeToString(keyBytes, flags)
                                                                libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey) 
                                                            } catch (e: Exception) {}

                                                            val measureSemaphore = kotlinx.coroutines.sync.Semaphore(3)
                                                            val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
                                                            val totalCount = group.nodes.size

                                                            val healthy = group.nodes.map { node ->
                                                                async {
                                                                    measureSemaphore.acquire()
                                                                    try {
                                                                        val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                                                                        if (config != null && config.address.isNotEmpty()) {
                                                                            val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(config)
                                                                            val delayMs = libv2ray.Libv2ray.measureOutboundDelay(jsonConfig, "https://clients3.google.com/generate_204")
                                                                            if (delayMs > 0) node else null
                                                                        } else {
                                                                            null
                                                                        }
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    } finally {
                                                                        measureSemaphore.release()
                                                                        val done = completedCount.incrementAndGet()
                                                                        withContext(Dispatchers.Main) {
                                                                            pingProgressMap = pingProgressMap + (group.id to Pair(done, totalCount))
                                                                        }
                                                                    }
                                                                }
                                                            }.awaitAll().filterNotNull() as List<com.mlmvpn.scanner.models.VpnNode>
                                                            
                                                            withContext(Dispatchers.Main) {
                                                                val newMap = healthyNodesMap.toMutableMap()
                                                                newMap[group.id] = healthy
                                                                healthyNodesMap = newMap
                                                                pingingGroup = null
                                                                pingProgressMap = pingProgressMap - group.id
                                                                android.widget.Toast.makeText(context, context.getString(R.string.scanner_configs_connected, healthy.size, group.nodes.size), android.widget.Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f).height(48.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = BgDark, contentColor = Primary),
                                                    border = androidx.compose.foundation.BorderStroke(1.dp, Primary)
                                                ) {
                                                    if (isPinging) {
                                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Primary, strokeWidth = 2.dp)
                                                    } else {
                                                        Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(stringResource(R.string.scanner_ping_health_test), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                    }
                                                }
                                            } else {
                                                if (group.sourceGroupId?.startsWith("mlm_") == true) {
                                                    val mlmParts = group.sourceGroupId.split("_")
                                                    if (mlmParts.size >= 3) {
                                                        val mlmAccountId = mlmParts[1]
                                                        val mlmUsername = mlmParts[2]
                                                        var isUpdatingSub by remember { mutableStateOf(false) }
                                                        Button(
                                                            onClick = {
                                                                isUpdatingSub = true
                                                                scope.launch {
                                                                    val ips = healthyNodes.mapNotNull {
                                                                        com.mlmvpn.scanner.utils.VpnConfig.parseUri(it.uri)?.address
                                                                    }.distinct().joinToString("\n")
                                                                    
                                                                    val api = com.mlmvpn.scanner.engines.mlm.MlmApiManager(context)
                                                                    // Get account from db
                                                                    val accManager = com.mlmvpn.scanner.data.CloudManager(context)
                                                                    accManager.loadAccounts()
                                                                    val acc = accManager.accounts.find { account -> account.id == mlmAccountId }
                                                                    if (acc != null) {
                                                                        val users = api.getUsers(acc)
                                                                        val usr = users?.find { it.username == mlmUsername }
                                                                        if (usr != null) {
                                                                            val req = com.mlmvpn.scanner.engines.mlm.MlmUpdateUserRequest(
                                                                                limitGb = usr.limitGb,
                                                                                dailyLimitGb = usr.dailyLimitGb,
                                                                                expiryDays = usr.expiryDays,
                                                                                ips = ips,
                                                                                tls = usr.tls,
                                                                                port = usr.port,
                                                                                fingerprint = usr.fingerprint,
                                                                                proxyIp = usr.proxyIp
                                                                            )
                                                                            val success = api.updateUser(acc, mlmUsername, req)
                                                                            if (success) {
                                                                                android.widget.Toast.makeText(context, "ساب‌اسکریپشن کاربر با IP های سالم بروزرسانی شد", android.widget.Toast.LENGTH_LONG).show()
                                                                            } else {
                                                                                android.widget.Toast.makeText(context, "خطا در بروزرسانی ساب‌اسکریپشن", android.widget.Toast.LENGTH_LONG).show()
                                                                            }
                                                                        } else {
                                                                            android.widget.Toast.makeText(context, "کاربر یافت نشد!", android.widget.Toast.LENGTH_SHORT).show()
                                                                        }
                                                                    } else {
                                                                        android.widget.Toast.makeText(context, "اکانت MLM یافت نشد!", android.widget.Toast.LENGTH_SHORT).show()
                                                                    }
                                                                    isUpdatingSub = false
                                                                }
                                                            },
                                                            modifier = Modifier.weight(1f).height(48.dp),
                                                            shape = RoundedCornerShape(12.dp),
                                                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = BgDark)
                                                        ) {
                                                            if (isUpdatingSub) {
                                                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = BgDark, strokeWidth = 2.dp)
                                                            } else {
                                                                Text("بروزرسانی ساب (${healthyNodes.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                                
                                                Button(
                                                    onClick = { 
                                                        nodeManager.nodes.addAll(healthyNodes)
                                                        nodeManager.saveNodes()
                                                        android.widget.Toast.makeText(context, context.getString(R.string.scanner_healthy_nodes_transferred, healthyNodes.size), android.widget.Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.weight(1f).height(48.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk, contentColor = BgDark)
                                                ) {
                                                    Text(stringResource(R.string.scanner_transfer_healthy_count, healthyNodes.size), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // BOTTOM CONTENT (IP Archives)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!showActionSheet && ipArchives.isNotEmpty()) {
                            // IP Archives
                            Spacer(modifier = Modifier.height(24.dp))
                            Divider(color = BorderDark)
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Archive, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.scanner_clean_ips_archive), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }

                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ipArchives.forEach { archive ->
                                    Column(
                                        modifier = Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(16.dp)).border(1.dp, BorderDark, RoundedCornerShape(16.dp)).padding(16.dp)
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                            Column {
                                                Text(stringResource(R.string.scanner_archive_ip_count, archive.ips.size), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                Text(archive.date, color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                                            }
                                            IconButton(onClick = { archiveToDelete = archive }, modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Default.Close, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = { 
                                                    showRetestDialog = archive
                                                },
                                                modifier = Modifier.weight(1f).height(48.dp),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = BgDark, contentColor = Primary),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Primary)
                                            ) {
                                                Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(stringResource(R.string.scanner_health_test), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                            
                                            Button(
                                                onClick = { 
                                                    groupManager.loadCloudGroups()
                                                    if (groupManager.cloudGroups.isEmpty()) {
                                                        android.widget.Toast.makeText(context, context.getString(R.string.scanner_no_cloud_group), android.widget.Toast.LENGTH_SHORT).show()
                                                        return@Button
                                                    }
                                                    mixingArchive = archive
                                                    showActionSheet = true
                                                },
                                                modifier = Modifier.weight(1f).height(48.dp),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = BgDark)
                                            ) {
                                                Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(stringResource(R.string.scanner_combine_node), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Bottom spacer to ensure room
                        Spacer(modifier = Modifier.height(140.dp))
                    }
                }
            ) { measurables, constraints ->
                val topPlaceable = measurables[0].measure(constraints.copy(minHeight = 0))
                val bottomPlaceable = measurables[1].measure(constraints.copy(minHeight = 0))
                
                // We want the total height to be at least minScreenHeight - 48.dp (because of 24.dp padding on top and bottom)
                // minus the 140.dp spacer at the bottom to ensure the menu doesn't cover it.
                val requiredMinHeight = with(density) { (minScreenHeight - 48.dp - 140.dp).roundToPx() }
                val totalHeight = max(requiredMinHeight, topPlaceable.height + bottomPlaceable.height)
                
                layout(constraints.maxWidth, totalHeight) {
                    topPlaceable.placeRelative(0, 0)
                    bottomPlaceable.placeRelative(0, totalHeight - bottomPlaceable.height)
                }
            }
        }

        if (showActionSheet) {
            AlertDialog(
                onDismissRequest = { 
                    showActionSheet = false
                    mixingArchive = null
                },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                containerColor = SurfaceDark,
                title = {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            val title = if (mixingArchive != null) stringResource(R.string.scanner_archive_ips) else if (scanPhase == com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase.DONE) stringResource(R.string.scanner_final_results) else stringResource(R.string.scanner_found_ips)
                            Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            val count = mixingArchive?.ips?.size ?: scannedIPs.size
                            Text(stringResource(R.string.scanner_ips_with_good_ping, count), color = TextMuted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { 
                            showActionSheet = false
                            mixingArchive = null
                        }) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = TextMuted)
                        }
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.scanner_which_cloud_group), color = TextMuted, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                            items(groupManager.cloudGroups) { group ->
                                val isSelected = selectedCloudGroup == group.id
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).clickable { selectedCloudGroup = group.id }.background(if (isSelected) Primary.copy(alpha = 0.1f) else BgDark).border(1.dp, if (isSelected) Primary else BorderDark, RoundedCornerShape(16.dp)).padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedCloudGroup = group.id },
                                        colors = RadioButtonDefaults.colors(selectedColor = Primary, unselectedColor = TextDim)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        val engine = group.nodes.firstOrNull()?.engineType ?: "BPB"
                                        Text(stringResource(R.string.scanner_group_info_format, group.title, engine, group.nodes.size), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text(group.date, color = TextMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (selectedCloudGroup == null) {
                                android.widget.Toast.makeText(context, context.getString(R.string.scanner_please_select_group), android.widget.Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val targetGroup = groupManager.cloudGroups.find { it.id == selectedCloudGroup } ?: return@Button
                            val selectedIPs = if (mixingArchive != null) {
                                mixingArchive!!.ips.mapIndexed { index, ip ->
                                    ScannedIP(id = index, ip = ip, ping = 100, downloadSpeed = 100f, selected = true)
                                }
                            } else {
                                scannedIPs.filter { it.selected }
                            }
                            
                            val combinedNodes = mutableListOf<com.mlmvpn.scanner.models.VpnNode>()
                            val newGroupTitle = targetGroup.title
                            targetGroup.nodes.forEach { node ->
                                selectedIPs.forEach { ip ->
                                    var newUri = node.uri
                                    val newName = "${node.name} [${ip.ip}]"
                                    try {
                                        val schemeIndex = newUri.indexOf("://")
                                        if (schemeIndex != -1) {
                                            val atIndex = newUri.indexOf("@", schemeIndex)
                                            if (atIndex != -1) {
                                                val endOfHostPortIndex = listOf(newUri.indexOf("/", atIndex), newUri.indexOf("?", atIndex), newUri.indexOf("#", atIndex), newUri.length).filter { it != -1 }.minOrNull() ?: newUri.length
                                                val hostPort = newUri.substring(atIndex + 1, endOfHostPortIndex)
                                                val portIndex = hostPort.lastIndexOf(":")
                                                val originalHost = if (portIndex != -1) hostPort.substring(0, portIndex) else hostPort
                                                val port = if (portIndex != -1) hostPort.substring(portIndex + 1) else "443"
                                                val newHostPort = if (ip.ip.contains(":")) "[${ip.ip}]:$port" else "${ip.ip}:$port"
                                                
                                                newUri = newUri.substring(0, atIndex + 1) + newHostPort + newUri.substring(endOfHostPortIndex)
                                                
                                                if (!newUri.contains("sni=")) {
                                                    val qIndex = newUri.indexOf("?")
                                                    val hIndex = newUri.indexOf("#")
                                                    val insertPos = if (qIndex != -1) qIndex + 1 else if (hIndex != -1) hIndex else newUri.length
                                                    val prefix = if (qIndex == -1) "?" else ""
                                                    val suffix = if (qIndex != -1) "&" else ""
                                                    newUri = newUri.substring(0, insertPos) + prefix + "sni=$originalHost" + suffix + newUri.substring(insertPos)
                                                }
                                                if (!newUri.contains("host=")) {
                                                    val qIndex = newUri.indexOf("?")
                                                    val hIndex = newUri.indexOf("#")
                                                    val insertPos = if (qIndex != -1) qIndex + 1 else if (hIndex != -1) hIndex else newUri.length
                                                    val prefix = if (qIndex == -1) "?" else ""
                                                    val suffix = if (qIndex != -1) "&" else ""
                                                    newUri = newUri.substring(0, insertPos) + prefix + "host=$originalHost" + suffix + newUri.substring(insertPos)
                                                }
                                                
                                                val hashIndex = newUri.indexOf("#")
                                                val encodedName = android.net.Uri.encode(newName)
                                                if (hashIndex != -1) {
                                                    newUri = newUri.substring(0, hashIndex) + "#" + encodedName
                                                } else {
                                                    newUri += "#" + encodedName
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                    
                                    combinedNodes.add(
                                        node.copy(
                                            id = java.util.UUID.randomUUID().toString(),
                                            name = newName,
                                            uri = newUri,
                                            groupTitle = newGroupTitle
                                        )
                                    )
                                }
                            }
                            
                            val newComboGroup = com.mlmvpn.scanner.data.ScannerGroup(
                                id = System.currentTimeMillis().toString(),
                                date = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                title = newGroupTitle,
                                sourceGroupCount = targetGroup.nodes.size,
                                ipCount = selectedIPs.size,
                                sourceGroupId = targetGroup.id,
                                nodes = combinedNodes
                            )
                            
                            groupManager.scannerGroups.add(0, newComboGroup)
                            groupManager.saveScannerGroups()
                            scannerGroups = groupManager.scannerGroups.toList()
                            
                            showActionSheet = false
                            mixingArchive = null
                            viewingGroup = newComboGroup
                            android.widget.Toast.makeText(context, context.getString(R.string.scanner_combined_nodes_created, combinedNodes.size), android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = BgDark),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.scanner_confirm_and_combine), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
        val currentGroup = viewingGroup
        if (currentGroup != null) {
            AlertDialog(
                onDismissRequest = { viewingGroup = null },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                containerColor = SurfaceDark,
                title = {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(stringResource(R.string.scanner_combined_nodes), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.scanner_configs_connected_to_cloudflare, currentGroup.nodes.size), color = TextMuted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { viewingGroup = null }) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = TextMuted)
                        }
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).background(BgDark, RoundedCornerShape(12.dp)).padding(12.dp)) {
                            items(currentGroup.nodes) { node ->
                                Text(node.uri, color = TextPrimary, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
                                Divider(color = BorderDark, modifier = Modifier.padding(bottom = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val healthyNodes = healthyNodesMap[currentGroup.id]
                        if (healthyNodes != null) {
                            Button(
                                onClick = {
                                    val healthyConfigs = healthyNodes.joinToString("\n") { it.uri }
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Healthy Configs", healthyConfigs))
                                    android.widget.Toast.makeText(context, context.getString(R.string.scanner_healthy_configs_copied, healthyNodes.size), android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenOk, contentColor = BgDark),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.scanner_copy_healthy_configs, healthyNodes.size), fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = {
                                val ips = currentGroup.nodes.mapNotNull { 
                                    Regex("address=([^&#\\s]+)").find(it.uri)?.groupValues?.get(1) 
                                }.distinct().joinToString("\n")
                                
                                if (ips.isNotEmpty()) {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IPs", ips))
                                    android.widget.Toast.makeText(context, context.getString(R.string.scanner_ips_copied_short), android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, context.getString(R.string.scanner_ip_not_found_in_configs), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = Primary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CopyAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.scanner_copy_ips_button), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        }
        if (showVpnDialog) {
            AlertDialog(
                onDismissRequest = { showVpnDialog = false },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                containerColor = SurfaceDark,
                title = { Text(stringResource(R.string.scanner_vpn_active_warning), color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.scanner_vpn_active_desc), color = TextMuted, fontSize = 14.sp) },
                confirmButton = {
                    if (com.mlmvpn.scanner.MyVpnService.isRunning) {
                        Button(
                            onClick = {
                                val stopIntent = android.content.Intent(context, com.mlmvpn.scanner.MyVpnService::class.java).apply { action = "STOP" }
                                context.startService(stopIntent)
                                showVpnDialog = false
                                scope.launch {
                                    delay(500)
                                    com.mlmvpn.scanner.data.ScannerManager.startScan(
                                        context = context,
                                        baseConfig = baseConfig.trim(),
                                        limit = testLimit.toIntOrNull() ?: 50,
                                        successTarget = successTarget.toIntOrNull() ?: 10
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = BgDark)
                        ) {
                            Text(stringResource(R.string.scanner_turn_off_vpn_and_scan), fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { showVpnDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = BgDark)
                        ) {
                            Text(stringResource(R.string.scanner_understood), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showVpnDialog = false }) {
                        Text(stringResource(R.string.scanner_cancel), color = TextMuted)
                    }
                }
            )
        }

        if (showRetestDialog != null) {
            AlertDialog(
                onDismissRequest = { showRetestDialog = null },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                containerColor = SurfaceDark,
                title = { Text(stringResource(R.string.scanner_test_archive_ips_health), color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.scanner_enter_base_config_for_test), color = TextMuted, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = retestBaseConfig,
                            onValueChange = { retestBaseConfig = it },
                            label = { Text(stringResource(R.string.scanner_base_config_label), color = TextMuted, fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (retestBaseConfig.trim().isEmpty()) {
                                android.widget.Toast.makeText(context, context.getString(R.string.scanner_enter_base_config), android.widget.Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            if (isVpnActive(context)) {
                                android.widget.Toast.makeText(context, context.getString(R.string.scanner_turn_off_vpn_first), android.widget.Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            val archive = showRetestDialog!!
                            showRetestDialog = null
                            com.mlmvpn.scanner.data.ScannerManager.startScan(
                                context = context,
                                baseConfig = retestBaseConfig.trim(),
                                limit = testLimit.toIntOrNull() ?: 50,
                                successTarget = successTarget.toIntOrNull() ?: 10,
                                customIps = archive.ips,
                                isHealthTest = true
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = BgDark)
                    ) {
                        Text(stringResource(R.string.scanner_start_test), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRetestDialog = null }) {
                        Text(stringResource(R.string.scanner_cancel), color = TextMuted)
                    }
                }
            )
        }

        if (mixToDelete != null) {
            AlertDialog(
                onDismissRequest = { mixToDelete = null },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                containerColor = SurfaceDark,
                title = { Text(stringResource(R.string.dialog_delete_mix_title), color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.dialog_delete_mix_msg), color = TextMuted, fontSize = 14.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            val group = mixToDelete!!
                            mixToDelete = null
                            groupManager.scannerGroups.remove(group)
                            groupManager.saveScannerGroups()
                            android.widget.Toast.makeText(context, context.getString(R.string.scanner_combined_group_deleted), android.widget.Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedError, contentColor = Color.White)
                    ) {
                        Text(stringResource(R.string.node_delete), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { mixToDelete = null }) {
                        Text(stringResource(R.string.scanner_cancel), color = TextMuted)
                    }
                }
            )
        }

        if (archiveToDelete != null) {
            AlertDialog(
                onDismissRequest = { archiveToDelete = null },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                containerColor = SurfaceDark,
                title = { Text(stringResource(R.string.dialog_delete_archive_title), color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.dialog_delete_archive_msg), color = TextMuted, fontSize = 14.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            val archive = archiveToDelete!!
                            archiveToDelete = null
                            groupManager.ipArchives.remove(archive)
                            groupManager.saveIpArchives()
                            android.widget.Toast.makeText(context, context.getString(R.string.scanner_archive_deleted), android.widget.Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedError, contentColor = Color.White)
                    ) {
                        Text(stringResource(R.string.node_delete), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { archiveToDelete = null }) {
                        Text(stringResource(R.string.scanner_cancel), color = TextMuted)
                    }
                }
            )
        }

        if (healthTestResults != null) {
            val total = healthTestResults!!.first
            val blocked = healthTestResults!!.second
            AlertDialog(
                onDismissRequest = { com.mlmvpn.scanner.data.ScannerManager.dismissHealthTestCleanup() },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                containerColor = SurfaceDark,
                title = { Text(stringResource(R.string.scanner_health_test_cleanup_title), color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.scanner_health_test_cleanup_desc, total, blocked), color = TextMuted, fontSize = 14.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            com.mlmvpn.scanner.data.ScannerManager.confirmHealthTestCleanup(context)
                            // Reload archives visually
                            groupManager.loadIpArchives()
                            ipArchives = groupManager.ipArchives.toList()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedError, contentColor = Color.White)
                    ) {
                        Text(stringResource(R.string.node_delete), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { com.mlmvpn.scanner.data.ScannerManager.dismissHealthTestCleanup() }) {
                        Text(stringResource(R.string.scanner_cancel), color = TextMuted)
                    }
                }
            )
        }
    }
}
