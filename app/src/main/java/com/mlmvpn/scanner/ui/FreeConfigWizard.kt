package com.mlmvpn.scanner.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.freeconfig.FreeConfigEngine
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.launch

private enum class WizardStep { CHECKING, PICK_COUNT, FETCHING, RESULTS, FAILED }

/**
 * Big, prominent "Get free configs" entry point for the Add-Node sheet, and the multi-step flow
 * behind it: check how many candidates are available -> let the user pick how many they actually
 * want -> fetch + real-connection-test that many -> hand the working ones off to be imported.
 */
@Composable
fun FreeConfigLauncherButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(listOf(Color(0xFF7C3AED), Color(0xFF2563EB)))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("دریافت کانفیگ رایگان", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("کانفیگ‌های آنلاین و تست‌شده", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
            }
            Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Color.White.copy(alpha = 0.9f))
        }
    }
}

@Composable
fun FreeConfigWizard(
    onImport: (List<VpnNode>) -> Unit,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(WizardStep.CHECKING) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var desiredCount by remember { mutableStateOf(100) }
    var progress by remember { mutableStateOf(FreeConfigEngine.TestProgress(0, 0, 0, 0)) }
    var results by remember { mutableStateOf<List<VpnNode>>(emptyList()) }
    var newResults by remember { mutableStateOf<List<VpnNode>>(emptyList()) }
    var alreadyOwnedCount by remember { mutableStateOf(0) }
    var stopRequested by remember { mutableStateOf(false) }

    fun startCheck() {
        step = WizardStep.CHECKING
        scope.launch {
            val found = try { FreeConfigEngine.fetchCandidates() } catch (e: Exception) { emptyList() }
            candidates = found
            desiredCount = minOf(100, found.size).coerceAtLeast(if (found.isEmpty()) 0 else 1)
            step = if (found.isEmpty()) WizardStep.FAILED else WizardStep.PICK_COUNT
        }
    }

    LaunchedEffect(Unit) { startCheck() }

    // Title/back button are provided by the parent AddNodeModal header (see getTitleForForm),
    // consistent with the other embedded forms (DEFAULT_SNI, SUB_LINK) -- no separate header here.
    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(targetState = step, label = "FreeConfigStep") { s ->
            when (s) {
                WizardStep.CHECKING -> CheckingStep()
                WizardStep.FAILED -> FailedStep(onRetry = { startCheck() })
                WizardStep.PICK_COUNT -> PickCountStep(
                    available = candidates.size,
                    desiredCount = desiredCount,
                    onCountChange = { desiredCount = it },
                    onConfirm = {
                        step = WizardStep.FETCHING
                        stopRequested = false
                        scope.launch {
                            val working = FreeConfigEngine.collectWorking(
                                context = context,
                                candidates = candidates,
                                targetCount = desiredCount,
                                shouldStop = { stopRequested }
                            ) { p -> progress = p }
                            results = working

                            // Compare against what the user already has saved (by real server
                            // identity, not our randomized "mlmvpnNNNN" name) so a re-fetch of an
                            // already-owned server doesn't get imported as a duplicate.
                            val existingUris = com.mlmvpn.scanner.data.NodeManager(context).nodes.map { it.uri }
                            val (fresh, owned) = FreeConfigEngine.splitAlreadyOwned(working, existingUris)
                            newResults = fresh
                            alreadyOwnedCount = owned.size

                            step = WizardStep.RESULTS
                        }
                    }
                )
                WizardStep.FETCHING -> FetchingStep(progress, stopping = stopRequested, onStopHere = { stopRequested = true })
                WizardStep.RESULTS -> ResultsStep(
                    results = results,
                    newCount = newResults.size,
                    alreadyOwnedCount = alreadyOwnedCount,
                    onTransfer = { onImport(newResults) }
                )
            }
        }
    }
}

@Composable
private fun StepScaffold(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(34.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(subtitle, color = TextMuted, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(20.dp))
        content()
    }
}

@Composable
private fun CheckingStep() {
    StepScaffold(
        icon = Icons.Default.TravelExplore,
        title = "در حال بررسی منابع...",
        subtitle = "چند لحظه صبر کنید تا تعداد کانفیگ‌های در دسترس مشخص شود"
    ) {
        CircularProgressIndicator(color = Primary)
    }
}

@Composable
private fun FailedStep(onRetry: () -> Unit) {
    StepScaffold(
        icon = Icons.Default.CloudOff,
        title = "دریافت منابع ناموفق بود",
        subtitle = "اتصال اینترنت را بررسی کنید و دوباره تلاش کنید"
    ) {
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("تلاش مجدد", color = BgDark, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PickCountStep(
    available: Int,
    desiredCount: Int,
    onCountChange: (Int) -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Primary, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "شما در حال حاضر می‌توانید تا $available کانفیگ دریافت کنید",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text("چند تا می‌خواهید؟", color = TextMuted, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(20.dp))

        Text(desiredCount.toString(), color = Primary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Slider(
            value = desiredCount.toFloat(),
            onValueChange = { onCountChange(it.toInt().coerceAtLeast(1)) },
            valueRange = 1f..available.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(50, 100, 300, available).filter { it in 1..available }.distinct().forEach { quick ->
                QuickPickChip(quick, selected = desiredCount == quick) { onCountChange(quick) }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text("دریافت $desiredCount کانفیگ", color = BgDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun QuickPickChip(value: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Primary else BgDark)
            .border(1.dp, if (selected) Primary else BorderDark, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(value.toString(), color = if (selected) BgDark else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FetchingStep(progress: FreeConfigEngine.TestProgress, stopping: Boolean, onStopHere: () -> Unit) {
    val fraction = if (progress.candidates == 0) 0f else (progress.tested.toFloat() / progress.candidates.toFloat()).coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = fraction, label = "fetchProgress")

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text("در حال دریافت و تست کانفیگ‌ها...", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text("فقط کانفیگ‌های واقعاً متصل به لیست اضافه می‌شوند", color = TextMuted, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(20.dp))

        LinearProgressIndicator(
            progress = animated,
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Primary,
            trackColor = BorderDark
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("تست‌شده: ${progress.tested}/${progress.candidates}", color = TextMuted, fontSize = 12.sp)
            Text("متصل: ${progress.working}/${progress.target}", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.weight(1f))
        // Lets the user stop early and keep whatever's already been found (e.g. asked for 50,
        // happy with the 20 found so far) instead of waiting for the full target or candidate list.
        // Stopping isn't instant -- an in-flight chunk of up to ~12 real Xray tests has to finish
        // first -- so once pressed the button switches to a spinner + "در حال توقف..." instead of
        // looking unresponsive while that chunk (checked every ~1-2 small chunks now, not every
        // big 50-wide batch) winds down.
        OutlinedButton(
            onClick = onStopHere,
            enabled = progress.working > 0 && !stopping,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
            border = androidx.compose.foundation.BorderStroke(1.dp, Primary)
        ) {
            if (stopping) {
                CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("در حال توقف...", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            } else {
                Text("همین تعداد کافیه (${progress.working})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ResultsStep(results: List<VpnNode>, newCount: Int, alreadyOwnedCount: Int, onTransfer: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Primary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("${results.size} کانفیگ متصل و آماده", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        if (alreadyOwnedCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE8A33D).copy(alpha = 0.12f))
                    .border(1.dp, Color(0xFFE8A33D).copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFE8A33D), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "از این ${results.size} کانفیگ، $alreadyOwnedCount تا را از قبل در بخش اتصال دارید — $newCount کانفیگ جدید منتقل می‌شود",
                    color = TextPrimary,
                    fontSize = 12.5.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results) { node ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BgDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Primary.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("mlmvpn", color = Primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(node.name, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(node.type, color = TextMuted, fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onTransfer,
            enabled = newCount > 0,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary, disabledContainerColor = Primary.copy(alpha = 0.4f))
        ) {
            Text(
                if (alreadyOwnedCount > 0) "انتقال $newCount کانفیگ جدید به بخش اتصال" else "انتقال به بخش اتصال",
                color = BgDark, fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
        }
    }
}
