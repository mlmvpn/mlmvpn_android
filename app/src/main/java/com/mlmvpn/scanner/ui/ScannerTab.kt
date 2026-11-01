package com.mlmvpn.scanner.ui

import com.mlmvpn.scanner.ui.home.frostedGlass
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
import com.mlmvpn.scanner.utils.S
import com.mlmvpn.scanner.utils.ScanPreflight

data class ScannedIP(
    val id: Int,
    val ip: String,
    val ping: Int,
    val downloadSpeed: Float,
    /** The port this address actually answered on -- see [CloudflareScanner.ScanResult.port]. */
    val port: Int = 443,
    var selected: Boolean,
    /** The SCAN strategy whose handshake verified this address -- see [com.mlmvpn.scanner.data.ScanResult.strategy]. */
    val strategy: String? = null,
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
            tint = TextMuted, 
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

/**
 * What the scan is doing, in numbers.
 *
 * One bar was wrong twice over. The scan is a pipeline -- the cheap TCP sweep and the real
 * proxied verification run at the same time, feeding each other -- so a bar driven by
 * probed/total filled to 100% while the slow half still had a queue, and then sat there while
 * the work that matters carried on invisibly. And with no counts at all there was no way to
 * tell a scan that was working from one that had stalled.
 *
 * Two bars, because there are two stages; the counts beside them, because a moving number is
 * the only proof the screen can offer that anything is happening.
 */
@Composable
fun ScannerProgressCard(
    scanPhase: com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase,
    foundCount: Int,
    progress: Float,
    stats: com.mlmvpn.scanner.data.CloudflareScanner.ScanStats,
    pinnedToRealLine: Boolean,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass(CardShape)
            .padding(20.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val phaseText = when (scanPhase) {
                com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase.DONE ->
                    stringResource(R.string.scanner_phase_done)
                com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase.IDLE ->
                    stringResource(R.string.scanner_phase_ready)
                else -> stringResource(R.string.scanner_phase_running)
            }
            Text(phaseText, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                faCount(progress.toInt()) + "٪",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        ScanStageRow(
            label = stringResource(R.string.scanner_stage_probe),
            done = stats.probed,
            total = stats.total,
            trailing = stringResource(R.string.scanner_stage_probe_found, faCount(stats.alive)),
            tint = Primary,
        )

        Spacer(modifier = Modifier.height(10.dp))
        if (stats.healthTest) {
            // A health test is finished when the LIST has been swept, not when enough addresses
            // pass -- most of a stale archive is expected to fail. So the second bar counts the
            // definitive tests against the addresses that opened, which is the work that remains.
            ScanStageRow(
                label = stringResource(R.string.scanner_stage_verify),
                done = stats.tested,
                total = stats.alive,
                trailing = stringResource(
                    R.string.scanner_stage_verify_healthy,
                    faCount(stats.healthy),
                ),
                tint = GreenOk,
            )
        } else {
            ScanStageRow(
                label = stringResource(R.string.scanner_stage_verify),
                done = stats.healthy,
                total = stats.target,
                // Tested against its ceiling, not just tested. Both numbers end the scan -- the
                // target when it is reached, the budget when it runs out -- so showing only one
                // left the other free to end a run the user had no way to see coming.
                trailing = stringResource(
                    R.string.scanner_stage_verify_tested_budget,
                    faCount(stats.tested),
                    faCount(stats.testBudget),
                ),
                tint = GreenOk,
            )
        }

        // Under someone else's VPN the two halves of the scan are measuring different things, and
        // saying so is the difference between a number and a misleading number.
        if (pinnedToRealLine) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.scanner_pinned_real_line),
                color = YellowWarn,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        // Always present while a scan runs. It used to appear only once the first verified IP had
        // arrived -- which on a blocked network is never -- so a scan that was going nowhere could
        // not be stopped from the screen that started it.
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = ControlShape,
            colors = iosButtonColors(RedError),
            border = iosButtonBorder(RedError),
        ) {
            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.scanner_stop),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
    }
}

/** One stage: a caption with its counts, and the bar underneath. */
@Composable
private fun ScanStageRow(
    label: String,
    done: Int,
    total: Int,
    trailing: String,
    tint: androidx.compose.ui.graphics.Color,
) {
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val width by animateFloatAsState(targetValue = fraction, animationSpec = tween(160))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Text(
            faCount(done) + " / " + faCount(total) + " · " + trailing,
            color = TextMuted,
            fontSize = 11.sp,
        )
    }
    Spacer(modifier = Modifier.height(5.dp))
    Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(BgDark, CircleShape)) {
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(width).background(tint, CircleShape))
    }
}

@Composable
fun NetworkPausedBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(YellowWarn.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .border(1.dp, YellowWarn.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = YellowWarn, strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WifiOff, contentDescription = null, tint = YellowWarn, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(S(R.string.no_internet_connection), color = YellowWarn, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(message, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
fun ScannerSettingsCard(
    baseConfig: String,
    onBaseConfigChange: (String) -> Unit,
    onPickBaseConfig: () -> Unit,
    strategy: com.mlmvpn.scanner.data.ScanStrategy,
    onStrategyChange: (com.mlmvpn.scanner.data.ScanStrategy) -> Unit,
    ports: List<Int>,
    testLimit: String,
    onTestLimitChange: (String) -> Unit,
    successTarget: String,
    onSuccessTargetChange: (String) -> Unit,
    /** True while the preflight is moving a byte, so the button is not mistaken for dead. */
    busy: Boolean = false,
    onStartScan: () -> Unit
) {
    val picked = androidx.compose.runtime.remember(baseConfig) {
        if (baseConfig.isBlank()) null else com.mlmvpn.scanner.utils.VpnConfig.parseUri(baseConfig)
    }

    com.mlmvpn.scanner.ui.settings.SettingsSectionHeader(horizontal = 4.dp, text = S(R.string.base_config))
    com.mlmvpn.scanner.ui.settings.SettingsGroup(horizontal = 0.dp) {
        com.mlmvpn.scanner.ui.settings.SettingsRow(
            title = S(R.string.config),
            subtitle = when {
                baseConfig.isBlank() -> S(R.string.not_chosen_yet)
                picked != null -> "${picked.address}:${picked.port}"
                else -> S(R.string.could_not_be_read)
            },
            icon = Icons.Default.Storage,
            tint = if (baseConfig.isBlank()) com.mlmvpn.scanner.ui.settings.Ios.Gray
            else com.mlmvpn.scanner.ui.settings.Ios.Indigo,
            value = if (baseConfig.isBlank()) S(R.string.choose) else picked?.name?.take(18),
            onClick = onPickBaseConfig,
        )
    }
    if (ports.size > 1) {
        com.mlmvpn.scanner.ui.settings.SettingsFooter(
            S(R.string.this_group_s_configs_work_on) + ports.joinToString(S(R.string.str_3)) { com.mlmvpn.scanner.ui.faCount(it) } +
                S(R.string.so_every_ip_is_tested_on_those) +
                S(R.string.goes_into_the_combined_config)
        )
    }

    // ---- what the scan optimises for --------------------------------------------------------
    //
    // Three real algorithms, not three labels: the probe depth, what disqualifies a candidate, how
    // candidates are ordered, and how many times each is verified all change. See ScanStrategy.
    com.mlmvpn.scanner.ui.settings.SettingsSectionHeader(horizontal = 4.dp, text = S(R.string.what_matters_more_to_you))
    com.mlmvpn.scanner.ui.settings.SettingsGroup(horizontal = 0.dp) {
        com.mlmvpn.scanner.data.ScanStrategy.values().forEachIndexed { index, s ->
            if (index > 0) Separator()
            val selected = strategy == s
            com.mlmvpn.scanner.ui.settings.SettingsRow(
                title = when (s) {
                    com.mlmvpn.scanner.data.ScanStrategy.FAST_SCAN -> S(R.string.scan_speed)
                    com.mlmvpn.scanner.data.ScanStrategy.FAST_CONFIG -> S(R.string.config_speed)
                    com.mlmvpn.scanner.data.ScanStrategy.STABLE -> S(R.string.stability)
                },
                subtitle = when (s) {
                    com.mlmvpn.scanner.data.ScanStrategy.FAST_SCAN ->
                        S(R.string.finishes_sooner_every_ip_that_opens_is)
                    com.mlmvpn.scanner.data.ScanStrategy.FAST_CONFIG ->
                        S(R.string.finds_the_lowest_latency_ones_takes_a)
                    com.mlmvpn.scanner.data.ScanStrategy.STABLE ->
                        S(R.string.ips_that_do_not_fluctuate_the_slowest)
                },
                icon = if (selected) Icons.Default.CheckCircle else when (s) {
                    com.mlmvpn.scanner.data.ScanStrategy.FAST_SCAN -> Icons.Default.Bolt
                    com.mlmvpn.scanner.data.ScanStrategy.FAST_CONFIG -> Icons.Default.Speed
                    com.mlmvpn.scanner.data.ScanStrategy.STABLE -> Icons.Default.Shield
                },
                tint = if (selected) com.mlmvpn.scanner.ui.settings.Ios.Green
                else com.mlmvpn.scanner.ui.settings.Ios.Gray,
                showChevron = false,
                onClick = { onStrategyChange(s) },
            )
        }
    }
    com.mlmvpn.scanner.ui.settings.SettingsFooter(
        when (strategy) {
            com.mlmvpn.scanner.data.ScanStrategy.FAST_SCAN ->
                S(R.string.probes_each_ip_once_and_sends_whatever) +
                    S(R.string.you_get_an_answer_sooner_but_the)
            com.mlmvpn.scanner.data.ScanStrategy.FAST_CONFIG ->
                S(R.string.probes_each_ip_three_times_and_holds) +
                    S(R.string.is_always_tested_first_it_also_runs)
            com.mlmvpn.scanner.data.ScanStrategy.STABLE ->
                S(R.string.probes_five_times_and_sets_aside_any) +
                    S(R.string.then_tests_each_winner_twice_in_full)
        }
    )

    com.mlmvpn.scanner.ui.settings.SettingsSectionHeader(horizontal = 4.dp, text = S(R.string.amount))
    com.mlmvpn.scanner.ui.settings.SettingsGroup(horizontal = 0.dp) {
        com.mlmvpn.scanner.ui.settings.SettingsTextRow(
            title = stringResource(R.string.scanner_success_target_label),
            value = successTarget,
            onValueChange = onSuccessTargetChange,
            numeric = true,
        )
        Separator()
        com.mlmvpn.scanner.ui.settings.SettingsTextRow(
            title = stringResource(R.string.scanner_test_limit_label),
            value = testLimit,
            onValueChange = onTestLimitChange,
            numeric = true,
        )
    }
    com.mlmvpn.scanner.ui.settings.SettingsFooter(
        S(R.string.the_scan_stops_as_soon_as_it) +
            S(R.string.how_long_it_takes)
    )

    Spacer(modifier = Modifier.height(14.dp))
    com.mlmvpn.scanner.ui.settings.SettingsGroup(horizontal = 0.dp) {
        com.mlmvpn.scanner.ui.settings.SettingsActionRow(
            label = if (busy) S(R.string.preflight_checking) else stringResource(R.string.scanner_start_scan),
            icon = Icons.Default.TravelExplore,
            tint = com.mlmvpn.scanner.ui.settings.Ios.Green,
            busy = busy,
            enabled = baseConfig.isNotBlank() && !busy,
            onClick = onStartScan,
        )
    }
    if (baseConfig.isBlank()) {
        com.mlmvpn.scanner.ui.settings.SettingsFooter(
            S(R.string.without_a_base_config_there_can_be) +
                S(R.string.whether_it_really_works_or_merely_has)
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
}

/**
 * The id the combine sheet uses for the Config Studio subscriber.
 *
 * A sentinel rather than a real group id, because there is no group: the person's configs arrive
 * through [com.mlmvpn.scanner.data.studio.StudioCombineHandoff] and never become a cloud group.
 * Chosen so it cannot collide with a cloud group's id, which is a timestamp.
 */
private const val STUDIO_SOURCE_ID = "__studio__"

/**
 * What «بروزرسانی ساب» did: the sentence to show, and whether it is good news.
 *
 * The sentence alone was enough while a person was reading it off a button. The assistant also
 * acts on it — a failed publish has to stop the guide on an orange bar rather than end it on a
 * green one — and deciding that by matching the string it was about to display would break the
 * first time either string was reworded.
 */
private data class StudioPublishOutcome(val ok: Boolean, val message: String)

/**
 * «بروزرسانی ساب» for one combined group: addresses in, the sentence to show out.
 *
 * Shared by the two places the button appears — the group's card and the group's detail dialog —
 * and by the assistant's own last step, because it is one action. A second copy of it would be a
 * second place for the disarm-on-success to be forgotten, which is how the sheet would end up
 * still offering somebody who has already been moved.
 */
private suspend fun publishStudioGroup(
    context: android.content.Context,
    group: com.mlmvpn.scanner.data.ScannerGroup,
    nodes: List<com.mlmvpn.scanner.models.VpnNode>,
): StudioPublishOutcome {
    fun fail(id: Int) = StudioPublishOutcome(false, context.getString(id))
    val installationId = group.studioInstallationId
    val userId = group.studioUserId
    if (installationId.isNullOrBlank() || userId.isNullOrBlank()) {
        return fail(R.string.studio_publish_failed)
    }
    val account = com.mlmvpn.scanner.data.CloudManager(context)
        .accounts.firstOrNull { it.id == installationId }
        ?: return fail(R.string.studio_combine_no_account)
    val addresses = nodes
        .mapNotNull { node ->
            val parsed = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
            parsed?.address?.takeIf { it.isNotBlank() }
                ?.let { it to (parsed.port.takeIf { p -> p in 1..65535 } ?: 443) }
        }
        .distinctBy { it.first }
    if (addresses.isEmpty()) return fail(R.string.studio_publish_no_address)
    val res = com.mlmvpn.scanner.data.studio.StudioCombine.publishGroup(
        store = com.mlmvpn.scanner.data.studio.StudioStore.get(context),
        account = account,
        userId = userId,
        configIds = group.studioConfigIds,
        addresses = addresses,
        username = group.studioUsername.orEmpty(),
    )
    // Disarmed on success, so the combine sheet stops offering somebody who has already been
    // moved. Left armed on failure, because the operator is going to press it again.
    if (res is com.mlmvpn.scanner.data.studio.api.StudioResult.Ok) {
        com.mlmvpn.scanner.data.studio.StudioCombineHandoff.clear(context)
    }
    return when (res) {
        is com.mlmvpn.scanner.data.studio.api.StudioResult.Ok -> StudioPublishOutcome(
            ok = true,
            message = context.getString(R.string.studio_publish_done)
                .replace("%1\$s", faCount(res.value.movedCount))
                .replace("%2\$s", faCount(res.value.endpointCount)),
        )
        is com.mlmvpn.scanner.data.studio.api.StudioResult.Err ->
            fail(R.string.studio_publish_failed)
    }
}

@Composable
fun ScannerTab(onBack: () -> Unit = {}, onOpenNodes: () -> Unit = {}) {
    val scanning by com.mlmvpn.scanner.data.ScannerManager.isScanning.collectAsState()
    val progress by com.mlmvpn.scanner.data.ScannerManager.progress.collectAsState()
    val scanStats by com.mlmvpn.scanner.data.ScannerManager.stats.collectAsState()
    val scannedIPs by com.mlmvpn.scanner.data.ScannerManager.scannedIPs.collectAsState()
    val scanPhase by com.mlmvpn.scanner.data.ScannerManager.phase.collectAsState()
    val foundCount by com.mlmvpn.scanner.data.ScannerManager.foundCount.collectAsState()
    val healthTestResults by com.mlmvpn.scanner.data.ScannerManager.healthTestResults.collectAsState()
    val lastScanNewCount by com.mlmvpn.scanner.data.ScannerManager.lastScanNewCount.collectAsState()
    val lastScanDuplicateCount by com.mlmvpn.scanner.data.ScannerManager.lastScanDuplicateCount.collectAsState()
    val pauseMessage by com.mlmvpn.scanner.data.ScannerManager.pauseMessage.collectAsState()
    val pinnedToRealLine by com.mlmvpn.scanner.data.ScannerManager.pinnedToRealLine.collectAsState()

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val groupManager = remember { com.mlmvpn.scanner.data.GroupManager(context) }
    val nodeManager = remember { com.mlmvpn.scanner.data.NodeManager(context) }
    val scannerGroupsFlowState by groupManager.scannerGroupsFlow.collectAsState()
    var scannerGroups by remember(scannerGroupsFlowState) { mutableStateOf(scannerGroupsFlowState) }
    var showActionSheet by remember { mutableStateOf(false) }
    var showBasePicker by remember { mutableStateOf(false) }
    val baseConfig by com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.collectAsState()

    val scanStrategy by com.mlmvpn.scanner.data.ScannerManager.strategy.collectAsState()
    val sourceGroupId by com.mlmvpn.scanner.data.ScannerManager.selectedSourceGroupId.collectAsState()

    // Which ports to probe, derived from the configs that will actually be combined. A group whose
    // workers answer on 2053 is unreachable to a scan that only ever tries 443.
    //
    // In an effect on IO, not inside `remember`. It reads SharedPreferences and parses every cloud
    // group's JSON, which on a phone with a few large groups is tens of milliseconds -- and inside
    // `remember` that ran on the main thread DURING composition, on every key change. It also
    // wrote to a shared StateFlow from composition, which is a side effect Compose is entitled to
    // run more than once.
    val scanPorts by com.mlmvpn.scanner.data.ScannerManager.scanPorts.collectAsState()
    LaunchedEffect(sourceGroupId, baseConfig) {
        val ports = withContext(Dispatchers.IO) {
            val gm = com.mlmvpn.scanner.data.GroupManager(context).also { it.loadCloudGroups() }
            // A copy under the manager's lock: its own loader runs on another thread as it is made,
            // and walking the live list under it was a ConcurrentModificationException (1.2.41).
            val group = gm.cloudGroupsSnapshot().find { it.id == sourceGroupId }
            when {
                group != null -> com.mlmvpn.scanner.data.CombineEngine.portsOf(group.nodes)
                baseConfig.isNotBlank() ->
                    com.mlmvpn.scanner.utils.VpnConfig.parseUri(baseConfig)?.port
                        ?.takeIf { it in 1..65535 }?.let { listOf(it) } ?: listOf(443)
                else -> listOf(443)
            }
        }
        com.mlmvpn.scanner.data.ScannerManager.scanPorts.value = ports
    }
    var selectedCloudGroup by remember { mutableStateOf<String?>(null) }
    var pingingGroup by remember { mutableStateOf<String?>(null) }
    var pingProgressMap by remember { mutableStateOf(mapOf<String, Pair<Int, Int>>()) }
    var healthyNodesMap by remember { mutableStateOf(mapOf<String, List<com.mlmvpn.scanner.models.VpnNode>>()) }

    var testLimit by remember { mutableStateOf("50") }
    var successTarget by remember { mutableStateOf("10") }
    // What preflight found in the way, or null when nothing is. Replaces the old
    // showVpnDialog boolean, which could only ever say "a VPN is on" and had nothing to say
    // about a phone that was connected to a network carrying no data at all.
    var preflight by remember {
        mutableStateOf<com.mlmvpn.scanner.utils.ScanPreflight.Verdict?>(null)
    }
    var preflightChecking by remember { mutableStateOf(false) }

    /**
     * What to do once the verdict is answered, as a function of "use the real line".
     *
     * The dialog is shared by the discovery scan and the archive health test, and the two start
     * different things. Holding the continuation here is what lets one dialog serve both without
     * either of them re-implementing the verdict handling -- which is how the health test ended up
     * with its own, weaker VPN check that also blocked our own tunnel.
     */
    var preflightAction by remember { mutableStateOf<(Boolean) -> Unit>({ }) }
    var disconnectingOwnVpn by remember { mutableStateOf(false) }

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
            // SharedPreferences plus a JSON parse of every archived address. On the main thread
            // that is a visible stall on a phone with a large archive, and this effect runs on
            // the composition's dispatcher.
            ipArchives = withContext(Dispatchers.IO) {
                groupManager.loadIpArchives()
                groupManager.ipArchives.toList()
            }
        }
    }

    // One way in, so the preflight cannot be bypassed by a second call site drifting out of step.
    //
    // @param useRealLine pin the TCP sweep to the non-VPN interface. Only ever true when the user
    //   chose "scan anyway" under somebody else's tunnel -- under OUR tunnel this app is already
    //   excluded from it, so the default route is the real line.
    fun startScanNow(useRealLine: Boolean = false) {
        com.mlmvpn.scanner.data.ScannerManager.startScan(
            context = context,
            baseConfig = baseConfig.trim(),
            testBudget = testLimit.toIntOrNull()?.coerceIn(1, 5000) ?: 50,
            successTarget = successTarget.toIntOrNull()?.coerceIn(1, 500) ?: 10,
            useRealLine = useRealLine,
        )
    }

    /**
     * Run the preflight, then either start or put the verdict on screen.
     *
     * The one entry point for "the user pressed start", so the discovery scan and the archive
     * health test cannot drift into two different ideas of what is in the way.
     */
    fun startWithPreflight(action: (useRealLine: Boolean) -> Unit) {
        preflightChecking = true
        preflightAction = action
        scope.launch {
            val verdict = ScanPreflight.check(context)
            preflightChecking = false
            if (verdict is ScanPreflight.Verdict.Ready) action(false) else preflight = verdict
        }
    }

    // Run the radar pulse/rotation ONLY while scanning. An infiniteRepeatable never stops on its
    // own and keeps requesting a frame every vsync as long as it's composed — and this tab stays
    // composed (offset off-screen) even when another tab is active, so an unconditional transition
    // burned frames 24/7 in the background. That relentless RenderThread load contributes to the
    // GPU swapBuffers crash (SIGABRT) seen on some devices. Gating it behind `scanning` removes the
    // animation from composition when idle, so it stops requesting frames. radarRotation is only
    // used while scanning anyway, and a static pulseScale=1f is the correct idle appearance.
    val pulseScale: Float
    val radarRotation: Float
    if (scanning) {
        val infiniteTransition = rememberInfiniteTransition(label = "radarPulse")
        pulseScale = infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulseScale"
        ).value
        radarRotation = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "radarRotation"
        ).value
    } else {
        pulseScale = 1f
        radarRotation = 0f
    }

    var viewingGroup by remember { mutableStateOf<com.mlmvpn.scanner.data.ScannerGroup?>(null) }

    // The Config Studio subscriber «ترکیب» armed, if any. Restored from disk rather than held in
    // memory: a scan runs for minutes with the screen on, which is the window in which a
    // backgrounded process is killed, and coming back to a finished scan that has forgotten who it
    // was for is the failure this guards against.
    LaunchedEffect(Unit) { com.mlmvpn.scanner.data.studio.StudioCombineHandoff.restore(context) }
    val studioTarget by com.mlmvpn.scanner.data.studio.StudioCombineHandoff.armed.collectAsState()
    // The armed subscriber's own config becomes the scan's base, and the subscriber becomes the
    // pre-picked combine source. «ترکیب» in Config Studio has already said whose configs these
    // are; arriving on a scanner with an empty base-config field and a picker that lists cloud
    // groups and knows nothing about Studio is asking a question that was answered a screen ago.
    //
    // Keyed on the target, so it fills the field once per handover: a base config the operator
    // changes by hand afterwards is left alone, and a target restored from disk after the process
    // was killed mid-scan fills it again, which is the case the in-memory field cannot survive on
    // its own.
    LaunchedEffect(studioTarget) {
        val t = studioTarget ?: return@LaunchedEffect
        if (com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.value !in t.nodes.map { it.uri }) {
            com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.value =
                t.nodes.firstOrNull()?.uri.orEmpty()
            // No cloud group is the source here; a stale id left over from an earlier combine
            // would otherwise decide which ports the sweep probes.
            com.mlmvpn.scanner.data.ScannerManager.selectedSourceGroupId.value = ""
        }
        // Pre-picked, not merely offered: the sheet is being opened because of this
        // person, and a radio list whose first row is the answer but is not selected is a
        // list that reads as "none of these".
        selectedCloudGroup = STUDIO_SOURCE_ID
    }
    /** True while a group's addresses are being written into somebody's subscription. */
    var publishing by remember { mutableStateOf(false) }

    if (showBasePicker) {
        androidx.activity.compose.BackHandler(enabled = true) { showBasePicker = false }
        com.mlmvpn.scanner.ui.BaseConfigPickerScreen(
            current = baseConfig,
            onPick = { option ->
                com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.value = option.uri
                // Remember which cloud group it came from, so a combine started by hand can also
                // multiply the WHOLE group rather than the one config the field happens to hold.
                com.mlmvpn.scanner.data.ScannerManager.selectedSourceGroupId.value = option.groupId ?: ""
            },
            onManual = { uri ->
                com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.value = uri
                com.mlmvpn.scanner.data.ScannerManager.selectedSourceGroupId.value = ""
            },
            onBack = { showBasePicker = false },
        )
        return
    }

    // The app's own navigation bar, like Settings and the transports. The screen keeps its own
    // scrolling -- it measures its content with a custom Layout -- so IosScreen supplies only the
    // bar and the inset that goes with it.
    com.mlmvpn.scanner.ui.settings.IosScreen(
        title = stringResource(R.string.nav_scanner),
        onBack = onBack,
        backLabel = S(R.string.home),
        scrollable = false,
    ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minScreenHeight = maxHeight

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 10.dp,
                    end = 10.dp,
                    top = 8.dp,
                    bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current + 16.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- the guided combine -------------------------------------------------------
            //
            // Step 3 is not an instruction, it is work: the coach combines the group it started
            // from against the IPs this scan found, measures every combination for real, and
            // transfers the ones that answered. The user presses nothing, and nothing is claimed
            // that did not happen -- if any stage produces nothing the coach stops and says why.
            val coachStep by com.mlmvpn.scanner.ui.CombineCoach.step.collectAsState()
            val coachGroupId by com.mlmvpn.scanner.ui.CombineCoach.groupId.collectAsState()
            val coachWork by com.mlmvpn.scanner.ui.CombineCoach.work.collectAsState()
            val coachWorkProgress by com.mlmvpn.scanner.ui.CombineCoach.workProgress.collectAsState()
            val coachFailure by com.mlmvpn.scanner.ui.CombineCoach.failure.collectAsState()
            val coachTransferred by com.mlmvpn.scanner.ui.CombineCoach.transferred.collectAsState()
            // Blank on an ordinary cloud combine; the subscriber's name on a Config Studio one.
            val coachStudioUser by com.mlmvpn.scanner.ui.CombineCoach.studioUser.collectAsState()
            val coachPublished by com.mlmvpn.scanner.ui.CombineCoach.published.collectAsState()

            androidx.compose.runtime.LaunchedEffect(scanning) {
                if (scanning) {
                    // Only out of a step that is WAITING for a scan. A guide that has finished
                    // leaves its last bar on screen, and the operator's next move is often another
                    // scan -- the next subscriber, or the same one again. Treating that as step 2
                    // of the run that just ended would restart a finished guide with a person it
                    // has already published, and then publish them a second time.
                    if (coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.SET_COUNT ||
                        coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.FAILED
                    ) {
                        com.mlmvpn.scanner.ui.CombineCoach.advance(com.mlmvpn.scanner.ui.CombineCoach.Step.SCANNING)
                    }
                } else if (coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.SCANNING) {
                    com.mlmvpn.scanner.ui.CombineCoach.advance(com.mlmvpn.scanner.ui.CombineCoach.Step.WORKING)
                }
            }

            androidx.compose.runtime.LaunchedEffect(coachStep) {
                if (coachStep != com.mlmvpn.scanner.ui.CombineCoach.Step.WORKING) return@LaunchedEffect

                val coach = com.mlmvpn.scanner.ui.CombineCoach
                val healthyIps = scannedIPs.filter { it.selected }.map { it.ip }

                // The group this step already built, if it was interrupted after building it.
                // Rebuilding would save a second identical group; measuring it is the resume.
                val alreadyBuilt = coach.producedGroupId.value.takeIf { it.isNotBlank() }?.let { id ->
                    withContext(Dispatchers.IO) {
                        groupManager.loadScannerGroupsPublic()
                        groupManager.scannerGroups.find { it.id == id }
                    }
                }

                // Where the configs to multiply come from, and the ONLY place the two runs
                // differ before this point. A Config Studio run has no cloud group to look up:
                // «ترکیب» armed one subscriber and their rendered configs travelled in the
                // handoff, which is what this screen can read with no network and no API key.
                //
                // Read from the handoff itself rather than from the composed state, because the
                // case this whole feature is built to survive is the one where they disagree: on
                // the first composition after the process was killed mid-scan, nothing has been
                // restored yet and the composed value is still null. `restore` is a no-op once
                // something is loaded, so on every other pass this costs nothing.
                val studioSource = if (coachStudioUser.isBlank()) null else withContext(Dispatchers.IO) {
                    com.mlmvpn.scanner.data.studio.StudioCombineHandoff.restore(context)
                    com.mlmvpn.scanner.data.studio.StudioCombineHandoff.armed.value
                }
                // Reading every cloud group is SharedPreferences plus a JSON parse per group, and
                // this effect runs on the composition's Main dispatcher.
                val sourceGroup = if (studioSource != null) null else withContext(Dispatchers.IO) {
                    groupManager.loadCloudGroups()
                    groupManager.cloudGroups.find { it.id == coachGroupId }
                }
                val sourceNodes = studioSource?.nodes ?: sourceGroup?.nodes.orEmpty()
                val sourceTitle = studioSource?.username ?: sourceGroup?.title.orEmpty()

                if (alreadyBuilt == null && (healthyIps.isEmpty() || sourceNodes.isEmpty())) {
                    coach.failed(
                        com.mlmvpn.scanner.data.CombineEngine.diagnose(
                            context = context,
                            baseConfig = baseConfig,
                            testedCount = scanStats.tested,
                            foundCount = healthyIps.size,
                            combinedCount = 0,
                            healthyCount = 0,
                        )
                    )
                    return@LaunchedEffect
                }

                val comboGroup: com.mlmvpn.scanner.data.ScannerGroup
                val combined: List<com.mlmvpn.scanner.models.VpnNode>
                if (alreadyBuilt != null) {
                    comboGroup = alreadyBuilt
                    combined = alreadyBuilt.nodes
                } else {
                    coach.setWork(S(R.string.building_combinations), 0f)
                    val portByIp = scannedIPs.associate { it.ip to it.port }
                    // The handshake that verified each address goes into every config built on it,
                    // so the measurement below -- and every later connect -- dials it the same way.
                    val shapeByIp = scannedIPs.associate { it.ip to it.strategy }
                    val cfnet = com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(context)
                    combined = withContext(Dispatchers.IO) {
                        com.mlmvpn.scanner.data.CombineEngine.combine(
                            sourceNodes = sourceNodes,
                            ips = healthyIps,
                            groupTitle = sourceTitle,
                            portFor = { portByIp[it] },
                            handshakeFor = { node, ip -> cfnet.handshakeParams(node.uri, ip, shapeByIp[ip]) },
                        )
                    }

                    comboGroup = com.mlmvpn.scanner.data.ScannerGroup(
                        id = System.currentTimeMillis().toString(),
                        date = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                        title = sourceTitle,
                        sourceGroupCount = sourceNodes.size,
                        ipCount = healthyIps.size,
                        sourceGroupId = sourceGroup?.id,
                        nodes = combined,
                        // Stamped, so a group the assistant built is a Studio group exactly like a
                        // hand-combined one. The publish step below addresses the subscription
                        // through these ids, and they are also what keeps «بروزرسانی ساب» on the
                        // group's own card -- which is what the operator needs if the publish has
                        // to be repeated later.
                        studioInstallationId = studioSource?.installationId,
                        studioUserId = studioSource?.userId,
                        studioUsername = studioSource?.username,
                        studioConfigIds = studioSource?.configIds.orEmpty(),
                    )
                    groupManager.scannerGroups.add(0, comboGroup)
                    groupManager.saveScannerGroups()
                    scannerGroups = groupManager.scannerGroups.toList()
                    // Recorded BEFORE the slow half, because it is the slow half that gets
                    // interrupted -- and an interruption after this point must resume, not repeat.
                    coach.setProducedGroup(comboGroup.id)
                }

                coach.setWork(S(R.string.measuring_combinations), 0.01f)
                val healthy = com.mlmvpn.scanner.data.CombineEngine.measureHealthy(
                    context = context,
                    nodes = combined,
                ) { doneCount, total ->
                    coach.setWork(
                        S(R.string.measuring_combinations_2) + faCount(doneCount) + S(R.string.of) + faCount(total),
                        doneCount.toFloat() / total.coerceAtLeast(1),
                    )
                }

                if (healthy.isEmpty()) {
                    coach.failed(
                        com.mlmvpn.scanner.data.CombineEngine.diagnose(
                            context = context,
                            baseConfig = baseConfig,
                            // The real count, from the scan's own stats. This used to be the
                            // "test limit" the user typed, which was not what happened -- and
                            // that field did not even reach the scanner at the time.
                            testedCount = scanStats.tested,
                            foundCount = healthyIps.size,
                            combinedCount = combined.size,
                            healthyCount = 0,
                        )
                    )
                    return@LaunchedEffect
                }

                healthyNodesMap = healthyNodesMap.toMutableMap().apply { put(comboGroup.id, healthy) }
                // Onto THIS phone's connection list only when the configs are for the person
                // holding it. A Studio combine is done on somebody else's behalf, and moving their
                // configs here would put a stranger's credential in the operator's own list, on
                // every subscriber they ever fix.
                if (!comboGroup.isStudio) {
                    nodeManager.nodes.addAll(0, healthy)
                    nodeManager.saveNodes()
                }
                coach.setWork("", 0f)
                coach.succeeded(healthy.size)
            }

            // ---- step 4 of a Config Studio run: the subscriber goes onto the addresses --------
            //
            // The last move, and the reason «ترکیب» in Config Studio exists at all. It is the same
            // «بروزرسانی ساب» the group's own card offers, pressed by the assistant instead of by
            // the operator: the clean addresses become endpoints on the installation and the
            // person's configs are pointed at them, so their existing link serves the new
            // addresses on its next refresh with no credential changed and nothing to re-send.
            //
            // Keyed on the step rather than chained onto the block above, because this is the half
            // that is now slow enough to be interrupted -- and the coach holds the built group's
            // id across a process death precisely so a resumed PUBLISHING publishes it rather than
            // starting the whole combine again.
            androidx.compose.runtime.LaunchedEffect(coachStep) {
                if (coachStep != com.mlmvpn.scanner.ui.CombineCoach.Step.PUBLISHING) return@LaunchedEffect
                val coach = com.mlmvpn.scanner.ui.CombineCoach

                val built = withContext(Dispatchers.IO) {
                    groupManager.loadScannerGroupsPublic()
                    groupManager.scannerGroups.find { it.id == coach.producedGroupId.value }
                }
                if (built == null || !built.isStudio) {
                    coach.failed(context.getString(R.string.studio_publish_failed))
                    return@LaunchedEffect
                }

                coach.setWork(S(R.string.studio_coach_publishing), 0f)
                // The measured ones when this run measured them, the whole group when the process
                // was rebuilt since -- the map is in memory only. Publishing an address that was
                // measured dead is the one outcome worse than not publishing at all, so the
                // measured list wins whenever there is one.
                val source = healthyNodesMap[built.id] ?: built.nodes
                val outcome = publishStudioGroup(context, built, source)
                coach.setWork("", 0f)
                if (outcome.ok) coach.published(outcome.message) else coach.failed(outcome.message)
            }

            val coachDone = coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.PUBLISHED
            val coachStopped = coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.FAILED
            if (coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.SET_COUNT ||
                coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.SCANNING ||
                coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.WORKING ||
                coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.PUBLISHING ||
                coachDone || coachStopped
            ) {
                com.mlmvpn.scanner.ui.CombineCoachBar(
                    step = coachStep,
                    onSkip = { com.mlmvpn.scanner.ui.CombineCoach.skip() },
                    body = when (coachStep) {
                        // Named, on a Studio run, at every step. The operator is fixing somebody
                        // who is not here, often one of several in a sitting, and an assistant
                        // that says «کانفیگ» while writing a stranger's subscription is doing the
                        // one thing that must not be gotten wrong without saying whose it is.
                        com.mlmvpn.scanner.ui.CombineCoach.Step.SET_COUNT ->
                            if (coachStudioUser.isNotBlank())
                                S(R.string.studio_coach_set_count).replace("%1\$s", coachStudioUser)
                            else S(R.string.config_chosen_now_set_how_many_working)
                        com.mlmvpn.scanner.ui.CombineCoach.Step.SCANNING ->
                            if (coachStudioUser.isNotBlank())
                                S(R.string.studio_coach_scanning).replace("%1\$s", coachStudioUser)
                            else S(R.string.looking_for_clean_ips_when_it_is)
                        com.mlmvpn.scanner.ui.CombineCoach.Step.WORKING ->
                            S(R.string.i_am_building_the_combinations_and_measuring)
                        com.mlmvpn.scanner.ui.CombineCoach.Step.PUBLISHING ->
                            S(R.string.studio_coach_publish_body).replace("%1\$s", coachStudioUser)
                        com.mlmvpn.scanner.ui.CombineCoach.Step.PUBLISHED ->
                            S(R.string.studio_coach_done).replace("%1\$s", coachStudioUser) +
                                "\n" + coachPublished
                        com.mlmvpn.scanner.ui.CombineCoach.Step.FAILED -> coachFailure
                        else -> ""
                    },
                    working = coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.WORKING ||
                        coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.PUBLISHING,
                    workText = coachWork,
                    workProgress = coachWorkProgress,
                    failed = coachStopped,
                    actionLabel = when {
                        coachDone -> S(R.string.studio_coach_finish)
                        coachStopped -> S(R.string.close_the_guide)
                        else -> null
                    },
                    onAction = if (coachDone || coachStopped) {
                        { com.mlmvpn.scanner.ui.CombineCoach.skip() }
                    } else null,
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Step 3 succeeded here, so this screen is the one that hands over.
            androidx.compose.runtime.LaunchedEffect(coachStep) {
                if (coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.MEASURE && coachTransferred > 0) {
                    onOpenNodes()
                }
            }

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
                            ScannerProgressCard(
                                scanPhase = scanPhase,
                                foundCount = foundCount,
                                progress = progress,
                                stats = scanStats,
                                pinnedToRealLine = pinnedToRealLine,
                                onStop = { com.mlmvpn.scanner.data.ScannerManager.stopScan() },
                            )
                        }

                        pauseMessage?.let { msg ->
                            NetworkPausedBanner(msg)
                        }

                        if (scannedIPs.isNotEmpty()) {
                            // Results List
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                val title = if (scanPhase == com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase.DONE) stringResource(R.string.scanner_final_results) else stringResource(R.string.scanner_found_ips)
                                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Box(modifier = Modifier.background(GreenOk.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                    // The new/duplicate breakdown only exists for a DISCOVERY scan,
                                    // which is the only thing that merges into the archive. After
                                    // an archive health test both counters are zero and this read
                                    // "0 new IPs found" over a list that had just proven a dozen
                                    // of them still work.
                                    val hasBreakdown = lastScanNewCount > 0 || lastScanDuplicateCount > 0
                                    val countText = if (scanPhase == com.mlmvpn.scanner.data.CloudflareScanner.ScanPhase.DONE && hasBreakdown) {
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

                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).frostedGlass(RoundedCornerShape(16.dp)).border(1.dp, BorderDark, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
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
                                                    Text("Real: ${ip.downloadSpeed.toInt()}ms", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                            if (com.mlmvpn.scanner.utils.SafeClip.copy(context, "IPs", selected))
                                                android.widget.Toast.makeText(context, context.getString(R.string.scanner_ips_copied), android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = iosNeutralButtonColors(),
                                    border = iosNeutralBorder(),
                                ) {
                                    Icon(Icons.Default.CopyAll, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.scanner_copy_ips), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }

                                Button(
                                    onClick = {
                                        groupManager.loadCloudGroups()
                                        if (groupManager.cloudGroups.isEmpty() && studioTarget == null) {
                                            android.widget.Toast.makeText(context, context.getString(R.string.scanner_no_cloud_group), android.widget.Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        showActionSheet = true
                                    },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = iosButtonColors(Primary),
                                    border = iosButtonBorder(Primary)) {
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
                                onPickBaseConfig = { showBasePicker = true },
                                strategy = scanStrategy,
                                onStrategyChange = { com.mlmvpn.scanner.data.ScannerManager.strategy.value = it },
                                ports = scanPorts,
                                testLimit = testLimit,
                                onTestLimitChange = { testLimit = it },
                                successTarget = successTarget,
                                onSuccessTargetChange = { successTarget = it },
                                busy = preflightChecking,
                                onStartScan = {
                                    if (baseConfig.trim().isEmpty()) {
                                        android.widget.Toast.makeText(context, context.getString(R.string.scanner_enter_base_config_warning), android.widget.Toast.LENGTH_LONG).show()
                                    } else {
                                        // The check moves a byte, so it is not instant. The
                                        // button shows it is working rather than appearing dead.
                                        startWithPreflight { real -> startScanNow(real) }
                                    }
                                }
                            )
                        }

                        if (!showActionSheet && scannerGroups.isNotEmpty()) {
                            // Combinations History Groups
                            Spacer(modifier = Modifier.height(24.dp))
                            Divider(color = BorderDark)
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Layers, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.scanner_combined_groups), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            
                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                scannerGroups.forEachIndexed { index, group ->
                                    Column(
                                        modifier = Modifier.fillMaxWidth().frostedGlass(RoundedCornerShape(16.dp)).border(1.dp, BorderDark, RoundedCornerShape(16.dp)).padding(16.dp)
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
                                                    RoundedCornerShape(8.dp)
                                                ).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                                    Text(engineType, color = if (engineType == "NHN") GreenOk else Primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Box(modifier = Modifier.background(Primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).border(1.dp, Primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                    Text(stringResource(R.string.scanner_node_count, group.nodes.size), color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                                        .clip(RoundedCornerShape(8.dp)),
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
                                        // Says what has to happen before "transfer" can exist.
                                        // Without it the card showed a test button and a group of
                                        // configs with no hint that the second is useless until
                                        // the first has run.
                                        if (healthyNodesMap[group.id] == null && pingingGroup != group.id) {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                S(R.string.to_move_these_combinations_to_the_connection),
                                                color = TextMuted,
                                                fontSize = 12.sp,
                                                lineHeight = 19.sp,
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            IconButton(onClick = { 
                                                viewingGroup = group
                                            }, modifier = Modifier.size(48.dp).background(BgDark, ControlShape).border(1.dp, BorderDark, ControlShape)) {
                                                Icon(Icons.Default.Visibility, contentDescription = "View", tint = Primary)
                                            }

                                            IconButton(onClick = { mixToDelete = group }, modifier = Modifier.size(48.dp).background(BgDark, ControlShape).border(1.dp, BorderDark, ControlShape)) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted)
                                            }
                                            
                                            val isPinging = pingingGroup == group.id
                                            val healthyNodes = healthyNodesMap[group.id]

                                            if (healthyNodes == null) {
                                                Button(
                                                    onClick = { 
                                                        // ScanGuard first: this is the same core
                                                        // and the same link a running scan uses, so
                                                        // measured underneath one every combination
                                                        // reads dead.
                                                        com.mlmvpn.scanner.data.ScanGuard.run(
                                                            com.mlmvpn.scanner.data.ScanGuard.Reason.DELAY_TEST
                                                        ) {
                                                        pingingGroup = group.id
                                                        pingProgressMap = pingProgressMap + (group.id to Pair(0, group.nodes.size))
                                                        scope.launch {
                                                            // CombineEngine.measureHealthy, not a
                                                            // fourth copy of the same loop. The
                                                            // copies had already drifted -- this one
                                                            // swallowed Errors as successes and
                                                            // carried no timeout, so one stalled
                                                            // handshake froze the whole test.
                                                            val healthy = com.mlmvpn.scanner.data.CombineEngine.measureHealthy(
                                                                context = context,
                                                                nodes = group.nodes,
                                                            ) { done, total ->
                                                                pingProgressMap =
                                                                    pingProgressMap + (group.id to Pair(done, total))
                                                            }

                                                            healthyNodesMap = healthyNodesMap.toMutableMap()
                                                                .apply { put(group.id, healthy) }
                                                            pingingGroup = null
                                                            pingProgressMap = pingProgressMap - group.id
                                                            android.widget.Toast.makeText(context, context.getString(R.string.scanner_configs_connected, healthy.size, group.nodes.size), android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f).height(48.dp),
                                                    shape = ControlShape,
                                                    colors = iosNeutralButtonColors(),
                                                    border = iosNeutralBorder(),
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
                                                                                android.widget.Toast.makeText(context, S(R.string.the_user_s_subscription_was_updated_with), android.widget.Toast.LENGTH_LONG).show()
                                                                            } else {
                                                                                android.widget.Toast.makeText(context, S(R.string.failed_to_update_the_subscription), android.widget.Toast.LENGTH_LONG).show()
                                                                            }
                                                                        } else {
                                                                            android.widget.Toast.makeText(context, S(R.string.user_not_found), android.widget.Toast.LENGTH_SHORT).show()
                                                                        }
                                                                    } else {
                                                                        android.widget.Toast.makeText(context, S(R.string.mlm_account_not_found), android.widget.Toast.LENGTH_SHORT).show()
                                                                    }
                                                                    isUpdatingSub = false
                                                                }
                                                            },
                                                            modifier = Modifier.weight(1f).height(48.dp),
                                                            shape = ControlShape,
                                                            colors = iosButtonColors(Primary),
                                                            border = iosButtonBorder(Primary)) {
                                                            if (isUpdatingSub) {
                                                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                                            } else {
                                                                Text(S(R.string.update_sub, healthyNodes.size), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                                
                                                // «بروزرسانی ساب» on the card itself, not only behind the eye
                                                // icon. A group combined FOR a Config Studio subscriber exists in
                                                // order to be published, and the moment to publish it is the one
                                                // right after the health test — which is on this card, not one
                                                // dialog further in. The label carries the count for the same
                                                // reason the transfer button does: what gets published is the
                                                // MEASURED addresses, not every combination in the group.
                                                if (group.isStudio) {
                                                    var publishingCard by remember(group.id) { mutableStateOf(false) }
                                                    Button(
                                                        onClick = {
                                                            publishingCard = true
                                                            scope.launch {
                                                                val outcome = publishStudioGroup(context, group, healthyNodes)
                                                                publishingCard = false
                                                                android.widget.Toast.makeText(
                                                                    context, outcome.message, android.widget.Toast.LENGTH_LONG,
                                                                ).show()
                                                            }
                                                        },
                                                        enabled = !publishingCard,
                                                        modifier = Modifier.weight(1f).height(48.dp),
                                                        shape = ControlShape,
                                                        colors = iosButtonColors(Primary),
                                                        border = iosButtonBorder(Primary)) {
                                                        if (publishingCard) {
                                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                                        } else {
                                                            Text(S(R.string.update_sub, healthyNodes.size), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        }
                                                    }
                                                }

                                                Button(
                                                    // Transferring and then leaving the user on the
                                                    // scanner made them hunt for where the configs
                                                    // went. It goes where they went.
                                                    onClick = { 
                                                        nodeManager.nodes.addAll(0, healthyNodes)
                                                        nodeManager.saveNodes()
                                                        android.widget.Toast.makeText(context, context.getString(R.string.scanner_healthy_nodes_transferred, healthyNodes.size), android.widget.Toast.LENGTH_SHORT).show()
                                                        onOpenNodes()
                                                    },
                                                    modifier = Modifier.weight(1f).height(48.dp),
                                                    shape = ControlShape,
                                                    colors = iosButtonColors(GreenOk),
                                                    border = iosButtonBorder(GreenOk)) {
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
                                Icon(Icons.Default.Archive, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.scanner_clean_ips_archive), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }

                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ipArchives.forEach { archive ->
                                    Column(
                                        modifier = Modifier.fillMaxWidth().frostedGlass(RoundedCornerShape(16.dp)).border(1.dp, BorderDark, RoundedCornerShape(16.dp)).padding(16.dp)
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
                                                shape = ControlShape,
                                                colors = iosNeutralButtonColors(),
                                                border = iosNeutralBorder(),
                                            ) {
                                                Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(stringResource(R.string.scanner_health_test), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                            
                                            Button(
                                                onClick = { 
                                                    groupManager.loadCloudGroups()
                                                    if (groupManager.cloudGroups.isEmpty() && studioTarget == null) {
                                                        android.widget.Toast.makeText(context, context.getString(R.string.scanner_no_cloud_group), android.widget.Toast.LENGTH_SHORT).show()
                                                        return@Button
                                                    }
                                                    mixingArchive = archive
                                                    showActionSheet = true
                                                },
                                                modifier = Modifier.weight(1f).height(48.dp),
                                                shape = ControlShape,
                                                colors = iosButtonColors(Primary),
                                                border = iosButtonBorder(Primary)) {
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
                
                // We want the total height to be at least minScreenHeight - 24.dp (the 8.dp top and
                // 16.dp bottom padding of the scroll column) minus the 140.dp spacer at the bottom
                // to ensure the menu doesn't cover it.
                val requiredMinHeight = with(density) { (minScreenHeight - 24.dp - 140.dp).roundToPx() }
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
                modifier = Modifier.fillMaxWidth().clip(CardShape).border(0.7.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f), CardShape),
                containerColor = DialogSurface,
                shape = CardShape,
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
                            // The Config Studio subscriber, when «ترکیب» armed one, and FIRST in
                            // the list because it is the reason this sheet was opened. It carries
                            // its own badge for the reason the operator asked for one: a cloud
                            // group and a subscriber both read as "a set of configs with a name",
                            // and combining the wrong one produces a group that looks right and
                            // belongs to somebody else.
                            studioTarget?.let { target ->
                                item(key = "studio") {
                                    val isSelected = selectedCloudGroup == STUDIO_SOURCE_ID
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable { selectedCloudGroup = STUDIO_SOURCE_ID }
                                            .background(if (isSelected) Primary.copy(alpha = 0.1f) else BgDark)
                                            .border(1.dp, if (isSelected) Primary else BorderDark, RoundedCornerShape(16.dp))
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { selectedCloudGroup = STUDIO_SOURCE_ID },
                                            colors = RadioButtonDefaults.colors(selectedColor = Primary, unselectedColor = TextDim)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                target.username,
                                                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                stringResource(R.string.scanner_studio_source_sub, target.nodes.size),
                                                color = TextMuted, fontSize = 10.sp,
                                            )
                                        }
                                        Text(
                                            stringResource(R.string.scanner_studio_badge),
                                            color = Primary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Primary.copy(alpha = 0.14f))
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
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
                            // The Config Studio subscriber, or a cloud group. Both end up as a
                            // title and a list of nodes; only the first carries the three ids that
                            // let the finished group offer «بروزرسانی ساب».
                            val studioPick = studioTarget?.takeIf { selectedCloudGroup == STUDIO_SOURCE_ID }
                            val targetGroup = if (studioPick != null) null
                                else groupManager.cloudGroups.find { it.id == selectedCloudGroup } ?: return@Button
                            val sourceNodes = studioPick?.nodes ?: targetGroup!!.nodes
                            val sourceTitle = studioPick?.username ?: targetGroup!!.title
                            // The IPs to combine, with the port each of them actually answered
                            // on. An archive carries no port -- those addresses were proven on
                            // whatever the scan used at the time -- so those fall back to the
                            // config's own, which is what the old code did for everything.
                            val selectedIPs = if (mixingArchive != null) {
                                mixingArchive!!.ips.distinct()
                            } else {
                                scannedIPs.filter { it.selected }.map { it.ip }
                            }
                            val portByIp = if (mixingArchive != null) emptyMap() else {
                                scannedIPs.associate { it.ip to it.port }
                            }
                            // The handshake that verified each scanned address; an archive address has
                            // no record of its own and takes this network's proven SCAN handshake.
                            val shapeByIp = if (mixingArchive != null) emptyMap() else {
                                scannedIPs.associate { it.ip to it.strategy }
                            }
                            val cfnet = com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(context)

                            // CombineEngine, not a second copy of the same string surgery. This
                            // block used to be its own implementation and had already drifted: it
                            // ignored the port the scan had found, and it silently no-opped on
                            // vmess and legacy ss links, producing N renamed copies of the
                            // ORIGINAL config that every later test then reported as dead.
                            val newGroupTitle = sourceTitle
                            val archiveUsed = mixingArchive
                            showActionSheet = false
                            // Off the main thread: every source config times every address, each
                            // one parsed and re-shaped, then written to disk -- seconds on a big
                            // group, and the screen froze for all of them (freeze reports, 1.2.41).
                            scope.launch {
                            val combinedNodes = withContext(Dispatchers.Default) {
                                com.mlmvpn.scanner.data.CombineEngine.combine(
                                    sourceNodes = sourceNodes,
                                    ips = selectedIPs,
                                    groupTitle = newGroupTitle,
                                    portFor = { portByIp[it] },
                                    handshakeFor = { node, ip -> cfnet.handshakeParams(node.uri, ip, shapeByIp[ip]) },
                                )
                            }

                            val newComboGroup = com.mlmvpn.scanner.data.ScannerGroup(
                                id = System.currentTimeMillis().toString(),
                                date = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                title = newGroupTitle,
                                sourceGroupCount = sourceNodes.size,
                                ipCount = selectedIPs.size,
                                sourceGroupId = targetGroup?.id,
                                nodes = combinedNodes,
                                // Carried onto the group so the card can offer «بروزرسانی ساب»
                                // and know whose subscription to write. Null on a cloud combine,
                                // which is what keeps the button off every other group.
                                studioInstallationId = studioPick?.installationId,
                                studioUserId = studioPick?.userId,
                                studioUsername = studioPick?.username,
                                studioConfigIds = studioPick?.configIds.orEmpty(),
                            )
                            
                            groupManager.scannerGroups.add(0, newComboGroup)
                            withContext(Dispatchers.IO) { groupManager.saveScannerGroups() }
                            scannerGroups = groupManager.scannerGroups.toList()

                            if (mixingArchive === archiveUsed) mixingArchive = null
                            viewingGroup = newComboGroup
                            android.widget.Toast.makeText(context, context.getString(R.string.scanner_combined_nodes_created, combinedNodes.size), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = iosButtonColors(Primary),
                        border = iosButtonBorder(Primary),
                        shape = ControlShape
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
                modifier = Modifier.fillMaxWidth().clip(CardShape).border(0.7.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f), CardShape),
                containerColor = DialogSurface,
                shape = CardShape,
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
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).background(BgDark, ControlShape).padding(12.dp)) {
                            items(currentGroup.nodes) { node ->
                                Text(node.uri, color = TextPrimary, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
                                Divider(color = BorderDark, modifier = Modifier.padding(bottom = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // ---- «بروزرسانی ساب» --------------------------------------------------
                        //
                        // Only on a group that was combined FOR a Config Studio subscriber, which
                        // is what `isStudio` records. On any other group there is no subscription
                        // to update and no way to guess whose it would be.
                        //
                        // What it does is not what the words suggest on the surface: it does not
                        // upload these links. A Studio subscription is rendered by the engine from
                        // the config rows crossed with the endpoint list, so the way to make it
                        // serve these addresses is to make them endpoints and point this person's
                        // configs at them. The result is the same links on screen — and the
                        // subscription URL and every credential stay exactly as they were, which
                        // is what makes this safe to press on somebody who is connected right now.
                        if (currentGroup.isStudio) {
                            val healthy = healthyNodesMap[currentGroup.id]
                            Button(
                                onClick = {
                                    // The measured addresses when a health test has run, all of them
                                    // otherwise. Publishing an address that was measured dead is the one
                                    // outcome worse than not publishing at all.
                                    val source = healthy ?: currentGroup.nodes
                                    publishing = true
                                    scope.launch {
                                        val outcome = publishStudioGroup(context, currentGroup, source)
                                        publishing = false
                                        android.widget.Toast.makeText(
                                            context, outcome.message, android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                },
                                enabled = !publishing,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = iosButtonColors(Primary),
                                border = iosButtonBorder(Primary),
                                shape = ControlShape
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (publishing) stringResource(R.string.studio_publish_working)
                                    else stringResource(R.string.studio_publish_button, currentGroup.studioUsername.orEmpty()),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                stringResource(R.string.studio_publish_note),
                                color = TextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }

                        val healthyNodes = healthyNodesMap[currentGroup.id]
                        if (healthyNodes != null) {
                            Button(
                                onClick = {
                                    val healthyConfigs = healthyNodes.joinToString("\n") { it.uri }
                                    if (com.mlmvpn.scanner.utils.SafeClip.copy(context, "Healthy Configs", healthyConfigs))
                                        android.widget.Toast.makeText(context, context.getString(R.string.scanner_healthy_configs_copied, healthyNodes.size), android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = iosButtonColors(GreenOk),
                                border = iosButtonBorder(GreenOk),
                                shape = ControlShape
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
                                    if (com.mlmvpn.scanner.utils.SafeClip.copy(context, "IPs", ips))
                                        android.widget.Toast.makeText(context, context.getString(R.string.scanner_ips_copied_short), android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, context.getString(R.string.scanner_ip_not_found_in_configs), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = iosNeutralButtonColors(),
                            border = iosNeutralBorder(),
                            shape = ControlShape
                        ) {
                            Icon(Icons.Default.CopyAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.scanner_copy_ips_button), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        }
        // ---- what the preflight found, and the two ways out of it ------------------------
        //
        // Every verdict here offers a real choice rather than a wall, because none of these is
        // actually a reason a scan CANNOT run -- they are reasons its numbers would mean something
        // different from what the user assumes.
        //
        //   * Our own tunnel: the scan is already accurate, since this app excludes itself from
        //     its own VPN. So one button disconnects (and waits, properly) and the other simply
        //     starts, and the text says why both are fine.
        //   * Someone else's tunnel: cannot be switched off from here, and saying otherwise would
        //     be a lie. One button opens the place that can, and the other scans anyway -- with
        //     the TCP sweep pinned to the real line, which is the honest half of that offer.
        //   * No data / captive portal / no network: nothing to choose, just what to go and fix.
        preflight?.let { verdict ->
            val afterVerdict = preflightAction
            AlertDialog(
                onDismissRequest = { preflight = null },
                modifier = Modifier.fillMaxWidth().clip(CardShape).border(0.7.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f), CardShape),
                containerColor = DialogSurface,
                shape = CardShape,
                title = {
                    Text(
                        when (verdict) {
                            is ScanPreflight.Verdict.NoNetwork -> stringResource(R.string.preflight_no_network_title)
                            is ScanPreflight.Verdict.NoInternet -> stringResource(R.string.preflight_no_internet_title)
                            is ScanPreflight.Verdict.CaptivePortal -> stringResource(R.string.preflight_captive_title)
                            is ScanPreflight.Verdict.OwnVpn -> stringResource(R.string.preflight_own_vpn_title)
                            else -> stringResource(R.string.preflight_foreign_vpn_title)
                        },
                        color = TextPrimary, fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        when (verdict) {
                            is ScanPreflight.Verdict.NoNetwork -> stringResource(R.string.preflight_no_network_body)
                            is ScanPreflight.Verdict.NoInternet ->
                                if (verdict.onWifi) stringResource(R.string.preflight_no_internet_wifi_body)
                                else stringResource(R.string.preflight_no_internet_mobile_body)
                            is ScanPreflight.Verdict.CaptivePortal -> stringResource(R.string.preflight_captive_body)
                            is ScanPreflight.Verdict.OwnVpn ->
                                stringResource(R.string.preflight_own_vpn_body, verdict.engine)
                            is ScanPreflight.Verdict.ForeignVpn ->
                                if (verdict.canUseRealLine) stringResource(R.string.preflight_foreign_vpn_body_pinnable)
                                else stringResource(R.string.preflight_foreign_vpn_body)
                            else -> ""
                        },
                        color = TextMuted, fontSize = 14.sp, lineHeight = 21.sp,
                    )
                },
                confirmButton = {
                    when (verdict) {
                        // Ours to switch off, so the button does it -- and WAITS for the interface
                        // to actually go before starting. A fixed 600ms sleep used to stand in for
                        // that wait, and on a slow teardown the scan began through a tunnel that
                        // was still up, measuring it instead of the line.
                        is ScanPreflight.Verdict.OwnVpn -> Button(
                            onClick = {
                                preflight = null
                                disconnectingOwnVpn = true
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        com.mlmvpn.scanner.ui.tunnel.TunnelController.disconnect(context)
                                        com.mlmvpn.scanner.ui.tunnel.TunnelExclusion
                                            .releaseForTunnelStack(context)
                                        com.mlmvpn.scanner.ui.tunnel.TunnelExclusion
                                            .releaseForXray(context)
                                    }
                                    disconnectingOwnVpn = false
                                    afterVerdict(false)
                                }
                            },
                            colors = iosButtonColors(Primary),
                            border = iosButtonBorder(Primary),
                        ) {
                            Text(stringResource(R.string.preflight_disconnect_and_scan), fontWeight = FontWeight.Bold)
                        }
                        // Not ours. All this button can do is take the user to the place that
                        // can switch it off -- claiming to do it here would be a lie.
                        is ScanPreflight.Verdict.ForeignVpn -> Button(
                            onClick = {
                                preflight = null
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent("android.settings.VPN_SETTINGS")
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                            colors = iosButtonColors(Primary),
                            border = iosButtonBorder(Primary),
                        ) {
                            Text(stringResource(R.string.preflight_open_vpn_settings), fontWeight = FontWeight.Bold)
                        }
                        else -> Button(
                            onClick = { preflight = null },
                            colors = iosButtonColors(Primary),
                            border = iosButtonBorder(Primary),
                        ) {
                            Text(stringResource(R.string.scanner_understood), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    // A VPN does not make a scan impossible, only different -- and under our own
                    // tunnel it is not even different, since this app sits outside it. So the
                    // second button is "scan anyway", not "cancel".
                    when (verdict) {
                        is ScanPreflight.Verdict.OwnVpn -> TextButton(onClick = {
                            preflight = null
                            afterVerdict(false)
                        }) {
                            Text(
                                stringResource(R.string.preflight_scan_without_disconnecting),
                                color = TextMuted,
                            )
                        }
                        is ScanPreflight.Verdict.ForeignVpn -> TextButton(onClick = {
                            preflight = null
                            // Pinned when it can be, so the sweep sees the real link and not
                            // theirs. The dialog's text has already said which half that covers.
                            afterVerdict(verdict.canUseRealLine)
                        }) {
                            Text(stringResource(R.string.preflight_scan_anyway), color = TextMuted)
                        }
                        else -> TextButton(onClick = { preflight = null }) {
                            Text(stringResource(R.string.scanner_cancel), color = TextMuted)
                        }
                    }
                },
            )
        }

        // The disconnect is a real wait now, so it needs a real "working" state -- otherwise the
        // screen simply sat there between the tap and the scan starting.
        if (disconnectingOwnVpn) {
            AlertDialog(
                onDismissRequest = { },
                modifier = Modifier.fillMaxWidth().clip(CardShape).border(0.7.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f), CardShape),
                containerColor = DialogSurface,
                shape = CardShape,
                title = {
                    Text(
                        stringResource(R.string.preflight_disconnecting),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Primary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.preflight_disconnecting_body),
                            color = TextMuted,
                            fontSize = 14.sp,
                        )
                    }
                },
                confirmButton = {},
            )
        }

        if (showRetestDialog != null) {
            AlertDialog(
                onDismissRequest = { showRetestDialog = null },
                modifier = Modifier.fillMaxWidth().clip(CardShape).border(0.7.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f), CardShape),
                containerColor = DialogSurface,
                shape = CardShape,
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
                            colors = iosFieldColors(),
                            singleLine = true
                        ,
        shape = ControlShape,)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val uri = retestBaseConfig.trim()
                            if (uri.isEmpty() ||
                                com.mlmvpn.scanner.utils.VpnConfig.parseUri(uri) == null
                            ) {
                                android.widget.Toast.makeText(context, context.getString(R.string.scanner_enter_base_config), android.widget.Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            val archive = showRetestDialog!!
                            showRetestDialog = null
                            // The same preflight the discovery scan uses, instead of a private
                            // VPN check that also refused to run under OUR OWN tunnel -- which
                            // this app is excluded from, so it was never in the way.
                            //
                            // No target and no budget: a health test asks which of THESE
                            // addresses still work, and its answer is used to delete the rest.
                            // ScannerManager therefore tests every one of them; passing the
                            // screen's "stop at 10" here is what used to cut a 60-address
                            // archive down to 10 and call the other 50 blocked.
                            startWithPreflight { real ->
                                com.mlmvpn.scanner.data.ScannerManager.startScan(
                                    context = context,
                                    baseConfig = uri,
                                    customIps = archive.ips,
                                    isHealthTest = true,
                                    useRealLine = real,
                                )
                            }
                        },
                        colors = iosButtonColors(Primary),
                        border = iosButtonBorder(Primary)) {
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
                modifier = Modifier.fillMaxWidth().clip(CardShape).border(0.7.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f), CardShape),
                containerColor = DialogSurface,
                shape = CardShape,
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
                        colors = iosButtonColors(RedError),
                        border = iosButtonBorder(RedError)) {
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
                modifier = Modifier.fillMaxWidth().clip(CardShape).border(0.7.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f), CardShape),
                containerColor = DialogSurface,
                shape = CardShape,
                title = { Text(stringResource(R.string.dialog_delete_archive_title), color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.dialog_delete_archive_msg), color = TextMuted, fontSize = 14.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            archiveToDelete = null
                            scope.launch {
                                // Through the manager's own locked API rather than mutating its
                                // list from here: the scan's teardown writes the same list from a
                                // background thread, and there is only ever one unified archive.
                                withContext(Dispatchers.IO) { groupManager.clearIpArchives() }
                                ipArchives = groupManager.ipArchives.toList()
                                android.widget.Toast.makeText(context, context.getString(R.string.scanner_archive_deleted), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = iosButtonColors(RedError),
                        border = iosButtonBorder(RedError)) {
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
                modifier = Modifier.fillMaxWidth().clip(CardShape).border(0.7.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f), CardShape),
                containerColor = DialogSurface,
                shape = CardShape,
                title = { Text(stringResource(R.string.scanner_health_test_cleanup_title), color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.scanner_health_test_cleanup_desc, total, blocked), color = TextMuted, fontSize = 14.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                ipArchives = withContext(Dispatchers.IO) {
                                    com.mlmvpn.scanner.data.ScannerManager
                                        .confirmHealthTestCleanup(context)
                                    groupManager.loadIpArchives()
                                    groupManager.ipArchives.toList()
                                }
                            }
                        },
                        colors = iosButtonColors(RedError),
                        border = iosButtonBorder(RedError)) {
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
}
