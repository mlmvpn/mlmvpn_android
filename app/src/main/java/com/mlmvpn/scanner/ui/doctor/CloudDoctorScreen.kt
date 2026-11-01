package com.mlmvpn.scanner.ui.doctor

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.engines.arena.ArenaPanels
import com.mlmvpn.scanner.engines.cfnet.*
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.LocalTabVisible
import com.mlmvpn.scanner.ui.cloud.PanelConfigs
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.ControlShape
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * Cloudflare Doctor, inside Cloud. Two faces of one screen:
 *  - SETUP: the first time Cloud is opened after an install or an update, every stage is diagnosed
 *    in order and the five steps tick off one by one; then the user goes on into Cloud.
 *  - HUB: «مشکل در کدام بخش است؟» -- the five steps as rows; tapping one opens that step's own page,
 *    which diagnoses only that step, shows the verdict, and offers what comes next (install a panel,
 *    get configs, scan them).
 *
 * Built only from the app's own iOS pieces (IosScreen, glass cards, SettingsGroup rows, IosAlert)
 * so it reads as part of Cloud rather than a page of its own. A step is green only when every stage
 * behind it passed REAL validation; a missing account or config reads «بعد از افزودن …», never a
 * pass. Strategy names, ECH, fragments and fingerprints appear only in developer mode.
 */

private data class PanelOption(val key: String, val label: String, val detail: Int)

private val DEPLOY_PANELS = listOf(
    PanelOption("BPB", "BPB", R.string.cfnet_panel_bpb),
    PanelOption("EDG", "EDG", R.string.cfnet_panel_edg),
    PanelOption("NHN", "Nahan", R.string.cfnet_panel_nahan),
    PanelOption("SPD", "Spider", R.string.cfnet_panel_spider),
    PanelOption("NTR", "Netra", R.string.cfnet_panel_netra),
    PanelOption("GZG", "Gozargah", R.string.cfnet_panel_gozargah),
    PanelOption("NVA", "Nova", R.string.cfnet_panel_nova),
    PanelOption("MLM", "MLM", R.string.cfnet_panel_mlm),
)

private suspend fun deployPanel(context: Context, cloud: CloudManager, account: CloudAccount, key: String): Boolean =
    try {
        // A panel that is not installed as a full panel is CONNECTED to the account's ONE central Worker instead of getting a
        // Worker of its own (the Cloud tab's rows); one installed the old way is updated as before. BPB is the Worker itself.
        val fullPanel = ArenaPanels.byId(key)?.let { p -> p.installed(context, account) && !com.mlmvpn.scanner.engines.gateway.EdgeGateway.status(context, account).let { if (key == "BPB") it.installed else it.connected(key) } } ?: false
        val viaGateway = !fullPanel && key in com.mlmvpn.scanner.engines.gateway.Gateway.PANEL_TITLE.keys
        val ok = if (viaGateway) {
            (if (key == "BPB") com.mlmvpn.scanner.engines.gateway.EdgeGateway.deploy(context, account)
            else com.mlmvpn.scanner.engines.gateway.EdgeGateway.connectPanel(context, account, key)).isSuccess
        } else when (key) {
            "BPB" -> cloud.deployWorker(account) { _, _ -> }.first
            "EDG" -> cloud.deployEdgWorker(account) { _, _ -> }.first
            "NHN" -> com.mlmvpn.scanner.engines.nahan.NahanDeployer(context).deployNahan(account) { _, _ -> }.first
            "MLM" -> com.mlmvpn.scanner.engines.mlm.MlmDeployer(context).deployMlm(account) { _, _ -> }.first
            "SPD" -> com.mlmvpn.scanner.engines.spider.SpiderPanel.deploy(context, account) { }.isSuccess
            "NTR" -> com.mlmvpn.scanner.engines.netra.NetraPanel.deploy(context, account) { }.isSuccess
            "GZG" -> com.mlmvpn.scanner.engines.gozargah.GozargahPanel.deploy(context, account) { }.isSuccess
            "NVA" -> com.mlmvpn.scanner.engines.nova.NovaPanel.deploy(context, account) { }.isSuccess
            else -> false
        }
        // Nahan and MLM update the in-memory account but leave persistence to the caller. Saving here
        // makes a Doctor deployment behave like the Cloud tab's own deploy rows.
        if (ok) cloud.saveAccounts()
        ok
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

/** Configs from one installed panel, filed as a Cloud group. The count, or null when it gave none. */
private suspend fun fetchPanel(context: Context, groups: GroupManager, account: CloudAccount, key: String, label: String): Int? =
    try {
        val panel = ArenaPanels.byId(key)
        if (panel == null || !panel.installed(context, account)) null
        else {
            val links = panel.candidates(context, account)
            if (links.isEmpty()) null
            else PanelConfigs.file(context, groups, account, key, label, links.map { PanelConfigs.nameOf(it) to it })?.let { links.size }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/** The five steps the user sees. ACCOUNT and IDENTITY are two real stages behind the first. */
private enum class Step(val stages: List<CfStage>, val title: Int, val desc: Int, val ok: Int, val icon: ImageVector) {
    ACCOUNT(listOf(CfStage.ACCOUNT, CfStage.IDENTITY), R.string.cfnet_step_account, R.string.cfnet_desc_account, R.string.cfnet_ok_account, Icons.Default.Person),
    DEPLOY(listOf(CfStage.DEPLOY), R.string.cfnet_step_deploy, R.string.cfnet_desc_deploy, R.string.cfnet_ok_deploy, Icons.Default.CloudUpload),
    CONFIG(listOf(CfStage.CONFIG), R.string.cfnet_step_config, R.string.cfnet_desc_config, R.string.cfnet_ok_config, Icons.Default.CloudDownload),
    SCAN(listOf(CfStage.SCAN), R.string.cfnet_step_scan, R.string.cfnet_desc_scan, R.string.cfnet_ok_scan, Icons.Default.Radar),
    CONNECT(listOf(CfStage.CONNECT), R.string.cfnet_step_connect, R.string.cfnet_desc_connect, R.string.cfnet_ok_connect, Icons.Default.SwapVert),
}

@Composable
private fun Step.tint(): Color = when (this) {
    Step.ACCOUNT -> Ios.Orange
    Step.DEPLOY -> Ios.Indigo
    Step.CONFIG -> Ios.Teal
    Step.SCAN -> Ios.Purple
    Step.CONNECT -> Ios.Green
}

/** Where a step stands. Only [Passed] is green, and only when every stage behind it passed. */
private sealed interface StepState {
    data object Waiting : StepState
    data object Running : StepState
    data object Passed : StepState
    data class Later(val text: Int) : StepState
    data class Failed(val text: Int) : StepState
}

private fun laterText(reason: String): Int = when {
    reason.endsWith("no_panel") -> R.string.cfnet_later_panel
    reason.endsWith("no_config") -> R.string.cfnet_later_config
    else -> R.string.cfnet_later_account
}

private fun failureText(out: StrategyEngine.StageOutcome): Int = when (out.dominantFailure) {
    FailureClass.AUTH_FAILURE -> R.string.cfnet_fail_auth
    FailureClass.ACCOUNT_SETUP -> R.string.cfnet_fail_account_setup
    FailureClass.RATE_LIMITED -> R.string.cfnet_fail_rate
    FailureClass.DNS_FAILURE, FailureClass.DNS_POISONED -> R.string.cfnet_fail_dns
    FailureClass.DATA_TRANSFER_FAILURE, FailureClass.TUNNEL_FAILURE -> R.string.cfnet_fail_data
    FailureClass.CONFIG_PARSE_FAILURE -> R.string.cfnet_fail_parse
    FailureClass.API_FAILURE -> R.string.cfnet_fail_api
    FailureClass.SCAN_FAILURE -> R.string.cfnet_fail_scan
    else -> R.string.cfnet_fail_generic
}

private fun stateOf(step: Step, results: Map<CfStage, StrategyEngine.StageOutcome>, running: CfStage?): StepState {
    if (running != null && running in step.stages) return StepState.Running
    val outs = step.stages.mapNotNull(results::get)
    if (outs.isEmpty()) return StepState.Waiting
    if (outs.size == step.stages.size && outs.all { it.ok }) return StepState.Passed
    outs.firstOrNull { it.skippedReason?.startsWith("prerequisite") == true }?.let { return StepState.Later(laterText(it.skippedReason!!)) }
    if (step == Step.ACCOUNT && results[CfStage.ACCOUNT]?.ok == true && results[CfStage.IDENTITY]?.ok == false)
        return StepState.Failed(R.string.cfnet_fail_identity_only)
    val failing = outs.firstOrNull { !it.ok } ?: return StepState.Waiting
    return StepState.Failed(failureText(failing))
}

@Composable
fun CloudDoctorScreen(
    setup: Boolean = false,
    onBack: () -> Unit,
    onContinue: () -> Unit = onBack,
    account: CloudAccount? = null,
    credential: Pair<String, String>? = null,
    onAccountAdded: () -> Unit = {},
    onOpenScanner: () -> Unit = {},
) {
    val context = LocalContext.current
    val manager = remember { CfNetManager.get(context) }
    val cloud = remember { CloudManager(context) }
    val savedAccounts by cloud.accountsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val visible = LocalTabVisible.current
    val live by manager.live.collectAsState()
    val developer = remember { context.getSharedPreferences("cfnet_ui", Context.MODE_PRIVATE).getBoolean("developer", false) }

    var results by remember { mutableStateOf<Map<CfStage, StrategyEngine.StageOutcome>>(emptyMap()) }
    var running by remember { mutableStateOf<CfStage?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var stopped by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    // A typed credential from the add-account page lands straight on step 1.
    var page by remember { mutableStateOf(if (credential != null && !setup) Step.ACCOUNT else null) }
    var legacy by remember { mutableStateOf(false) }

    var pendingCredential by remember(credential) { mutableStateOf(credential) }
    var emailInput by remember { mutableStateOf(credential?.second.orEmpty()) }
    var tokenInput by remember { mutableStateOf("") }
    var doctorAccount by remember(account) { mutableStateOf(account) }
    var accountDecision by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var accountBusy by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf<String?>(null) }

    var deploySelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deployBusy by remember { mutableStateOf(false) }
    var deployResults by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var configSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var configBusy by remember { mutableStateOf(false) }
    var configResults by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var scanChoices by remember { mutableStateOf<List<DoctorScanChoice>>(emptyList()) }
    var scanSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scanHandedOff by remember { mutableStateOf(false) }

    val activeAccount = doctorAccount ?: savedAccounts.firstOrNull()
    val busy = running != null || deployBusy || configBusy || accountBusy

    fun run(stages: List<CfStage>) {
        if (job?.isActive == true) return
        stopped = false; finished = false; accountMessage = null
        deployResults = emptyMap(); configResults = emptyMap(); scanHandedOff = false; scanChoices = emptyList()
        results = results - stages.toSet()
        job = scope.launch {
            try {
                for (stage in stages) {
                    running = stage
                    // The account stage proves an unsaved credential as typed; nothing is stored yet.
                    val temporary = if (stage == CfStage.ACCOUNT) pendingCredential?.let { (token, email) ->
                        CloudAccount("doctor-temporary", token, email, "", "", "", "") } else null
                    // The first-run setup keeps each stage to 90 s: it is a first look, not an exhaustive search
                    // (each step's own page runs the full one).
                    val out = manager.diagnose(stage, accountOverride = temporary ?: doctorAccount, maxMs = if (setup) 90_000L else null)
                    results = results + (stage to out)
                    if (stage == CfStage.ACCOUNT && pendingCredential != null) {
                        if (!out.ok) break
                        val decision = CompletableDeferred<Boolean>()
                        accountDecision = decision
                        val accepted = decision.await()
                        accountDecision = null
                        val (token, email) = pendingCredential ?: break
                        pendingCredential = null
                        if (!accepted) { accountMessage = S(R.string.cfnet_account_rejected); break }
                        accountBusy = true
                        val added = try { cloud.addAccount(token, email) } finally { accountBusy = false }
                        if (!added.first) { accountMessage = S(R.string.cfnet_account_add_failed, added.second); break }
                        val normalized = token.replace(Regex("[^a-zA-Z0-9_-]"), "").trim()
                        val saved = cloud.loadedAccounts().lastOrNull { it.token == normalized && it.email == email.trim() }
                        if (saved == null) { accountMessage = S(R.string.cfnet_account_lost); break }
                        doctorAccount = saved
                        accountMessage = S(R.string.cfnet_account_added)
                        tokenInput = ""
                        onAccountAdded()
                    }
                }
                finished = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                stopped = true
                throw e
            } catch (e: Exception) {
                // Never a crash from a diagnosis: the step simply shows as stopped, with Retry.
                android.util.Log.w("CfNet", "doctor run failed: ${e.javaClass.simpleName}")
                stopped = true
            } finally {
                running = null
                accountDecision = null
            }
        }
    }

    // Setup runs once, by itself, the first time it is shown. Leaving the screen stops it (a run
    // nobody is watching is battery spent for nothing); coming back offers to start it again.
    LaunchedEffect(setup, visible) {
        if (setup && visible && job == null && !finished) run(CfStage.entries)
        if (!setup && visible && credential != null && job == null && results.isEmpty()) run(listOf(CfStage.ACCOUNT))
        if (!visible) job?.cancel()
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    BackHandler(enabled = visible) {
        when {
            legacy -> legacy = false
            page != null && !setup -> page = null
            else -> { job?.cancel(); onBack() }
        }
    }

    if (legacy && developer) {
        CfDoctorScreen(onBack = { legacy = false }, visible = visible)
        return
    }

    val current = page
    if (current != null && !setup) {
        StepPage(
            step = current,
            state = stateOf(current, results, running),
            tried = if (live.stage in current.stages) live.tried else 0,
            busy = busy,
            onStart = { run(current.stages) },
            onStop = { job?.cancel() },
            onBack = { page = null },
        ) {
            when (current) {
                Step.ACCOUNT -> if (activeAccount == null && running == null) CredentialSection(
                    email = emailInput, token = tokenInput,
                    onEmail = { emailInput = it }, onToken = { tokenInput = it },
                    enabled = tokenInput.isNotBlank() && !busy,
                    onCheck = {
                        pendingCredential = tokenInput.trim() to emailInput.trim()
                        run(listOf(CfStage.ACCOUNT, CfStage.IDENTITY))
                    },
                )
                Step.DEPLOY -> if (stateOf(current, results, running) == StepState.Passed && activeAccount != null) {
                    SettingsSectionHeader(stringResource(R.string.cfnet_deploy_header))
                    SettingsGroup {
                        DEPLOY_PANELS.forEachIndexed { i, option ->
                            if (i > 0) Separator()
                            SelectRow(option.label, stringResource(option.detail), option.key in deploySelection,
                                result = deployResults[option.key]?.let { if (it) stringResource(R.string.cfnet_installed) else stringResource(R.string.cfnet_not_installed) },
                                resultOk = deployResults[option.key], enabled = !busy,
                            ) { deploySelection = if (option.key in deploySelection) deploySelection - option.key else deploySelection + option.key }
                        }
                    }
                    SettingsFooter(stringResource(R.string.cfnet_deploy_footer))
                    Spacer(Modifier.height(12.dp))
                    DoctorCapsule(stringResource(if (deployBusy) R.string.cfnet_deploy_busy else R.string.cfnet_deploy_go), Ios.Blue, Color.White,
                        enabled = deploySelection.isNotEmpty() && !busy, busy = deployBusy, inset = true) {
                        val target = activeAccount
                        scope.launch {
                            deployBusy = true
                            try {
                                val out = LinkedHashMap<String, Boolean>()
                                for (key in DEPLOY_PANELS.map { it.key }.filter { it in deploySelection }) {
                                    out[key] = deployPanel(context, cloud, target, key)
                                    deployResults = out.toMap()
                                }
                                deploySelection = emptySet()
                            } finally { deployBusy = false }
                        }
                    }
                }
                Step.CONFIG -> if (stateOf(current, results, running) == StepState.Passed && activeAccount != null) {
                    val installed = DEPLOY_PANELS.filter { ArenaPanels.byId(it.key)?.installed(context, activeAccount) == true }
                    SettingsSectionHeader(stringResource(R.string.cfnet_config_header))
                    if (installed.isEmpty()) {
                        SettingsFooter(stringResource(R.string.cfnet_config_no_panel))
                    } else {
                        SettingsGroup {
                            installed.forEachIndexed { i, option ->
                                if (i > 0) Separator()
                                val got = configResults[option.key]
                                SelectRow(option.label, stringResource(option.detail), option.key in configSelection,
                                    result = if (option.key in configResults) got?.let { stringResource(R.string.cfnet_config_count, it) } ?: stringResource(R.string.cfnet_config_none) else null,
                                    resultOk = if (option.key in configResults) got != null else null, enabled = !busy,
                                ) { configSelection = if (option.key in configSelection) configSelection - option.key else configSelection + option.key }
                            }
                        }
                        SettingsFooter(stringResource(R.string.cfnet_config_footer))
                        Spacer(Modifier.height(12.dp))
                        DoctorCapsule(stringResource(if (configBusy) R.string.cfnet_config_busy else R.string.cfnet_config_go), Ios.Blue, Color.White,
                            enabled = configSelection.isNotEmpty() && !busy, busy = configBusy, inset = true) {
                            val target = activeAccount
                            scope.launch {
                                configBusy = true
                                try {
                                    val groups = GroupManager(context)
                                    // Doctor can be the first Cloud surface opened after process start: make sure
                                    // the groups are loaded before the first panel writes a new one.
                                    withContext(Dispatchers.IO) { runCatching { groups.loadCloudGroups() } }
                                    val out = LinkedHashMap<String, Int?>()
                                    for (option in installed.filter { it.key in configSelection }) {
                                        out[option.key] = fetchPanel(context, groups, target, option.key, option.label)
                                        configResults = out.toMap()
                                    }
                                    configSelection = emptySet()
                                } finally { configBusy = false }
                            }
                        }
                    }
                }
                Step.SCAN -> if (stateOf(current, results, running) == StepState.Passed && activeAccount != null) {
                    LaunchedEffect(activeAccount.id, configResults) {
                        scanChoices = withContext(Dispatchers.IO) {
                            runCatching {
                                val groups = GroupManager(context).also { it.loadCloudGroups() }
                                doctorScanChoices(groups.cloudGroups, activeAccount.id)
                            }.getOrDefault(emptyList())
                        }
                    }
                    SettingsSectionHeader(stringResource(R.string.cfnet_scan_header))
                    if (scanChoices.isEmpty()) {
                        SettingsFooter(stringResource(R.string.cfnet_scan_none))
                    } else {
                        SettingsGroup {
                            scanChoices.forEachIndexed { i, choice ->
                                if (i > 0) Separator()
                                SelectRow(choice.node.name.ifBlank { choice.groupTitle }, choice.groupTitle, choice.id in scanSelection, enabled = !busy && !scanHandedOff) {
                                    scanSelection = if (choice.id in scanSelection) scanSelection - choice.id else scanSelection + choice.id
                                }
                            }
                        }
                        SettingsFooter(stringResource(R.string.cfnet_scan_footer))
                        Spacer(Modifier.height(12.dp))
                        DoctorCapsule(stringResource(R.string.cfnet_scan_go), Ios.Blue, Color.White, enabled = scanSelection.isNotEmpty() && !busy && !scanHandedOff, inset = true) {
                            val target = activeAccount
                            scope.launch {
                                val stamp = System.currentTimeMillis()
                                val source = doctorScanGroup(target.id, scanChoices, scanSelection, stamp, S(R.string.cfnet_scan_group)) ?: return@launch
                                val filed = source.copy(date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(stamp)))
                                val saved = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val groups = GroupManager(context).also { it.loadCloudGroups() }
                                        groups.cloudGroups.add(0, filed)
                                        groups.saveCloudGroups()
                                    }.isSuccess
                                }
                                if (!saved) return@launch
                                // The same handoff CloudTab's «ترکیب» uses: the real scanner, its preflight, the
                                // combine and the move of healthy configs into the V2Ray list.
                                com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.value = filed.nodes.first().uri
                                com.mlmvpn.scanner.data.ScannerManager.selectedSourceGroupId.value = filed.id
                                com.mlmvpn.scanner.ui.CombineCoach.start(context = context, groupId = filed.id, configUri = filed.nodes.first().uri, stepsBefore = 3)
                                scanHandedOff = true
                                scanSelection = emptySet()
                                onOpenScanner()
                            }
                        }
                    }
                }
                Step.CONNECT -> Unit
            }
            accountMessage?.takeIf { current == Step.ACCOUNT }?.let { SettingsFooter(it) }
            if (developer) DeveloperSection(manager, current.stages.mapNotNull(results::get), onLegacy = { legacy = true })
        }
    } else {
        val stepStates = Step.entries.associateWith { stateOf(it, results, running) }
        IosScreen(
            onBack = { job?.cancel(); onBack() },
            backLabel = stringResource(if (setup) R.string.home else R.string.nav_cloud),
            largeTitle = stringResource(R.string.cf_doctor_title),
        ) {
            if (setup) SetupHero(
                states = stepStates, running = running != null, finished = finished, stopped = stopped,
                onRetry = { run(CfStage.entries) }, onContinue = { job?.cancel(); onContinue() },
            ) else HubHero(running = running?.let { r -> Step.entries.first { r in it.stages } }, tried = live.tried)

            SettingsSectionHeader(stringResource(if (setup) R.string.cfnet_steps else R.string.cfnet_which_part))
            SettingsGroup {
                Step.entries.forEachIndexed { i, step ->
                    if (i > 0) Separator()
                    StepRow(step, stepStates.getValue(step), clickable = !setup) { page = step }
                }
            }
            if (setup && running != null) {
                Spacer(Modifier.height(16.dp))
                // Quiet on purpose: the setup is the thing on screen, leaving it is the side door.
                Text(
                    stringResource(R.string.cfnet_skip), color = Ios.SecondaryLabel, fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clip(ControlShape)
                        .clickable(role = Role.Button) { job?.cancel(); onContinue() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            if (developer) DeveloperSection(manager, results.values.toList(), onLegacy = { legacy = true })
            Spacer(Modifier.height(40.dp))
        }
    }

    if (accountDecision != null || accountBusy) {
        IosAlert(
            title = stringResource(R.string.cfnet_account_found),
            message = if (accountBusy) null else stringResource(R.string.cfnet_account_found_msg),
            onDismiss = { if (!accountBusy) accountDecision?.complete(false) },
            content = if (accountBusy) ({ CircularProgressIndicator(Modifier.size(22.dp), color = Ios.Label, strokeWidth = 2.dp) }) else null,
            actions = if (accountBusy) emptyList() else listOf(
                IosAlertAction(stringResource(R.string.cfnet_account_not_now), { accountDecision?.complete(false) }),
                IosAlertAction(stringResource(R.string.cfnet_account_add), { accountDecision?.complete(true) }, preferred = true),
            ),
        )
    }
}

// ------------------------------------------------------------------------------ hero cards

@Composable
private fun HeroFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(CardShape).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun HeroTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_app_doctor), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios.Label, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun HubHero(running: Step?, tried: Int) {
    HeroFrame {
        HeroTitle(stringResource(R.string.cf_doctor_title), stringResource(R.string.cfnet_hub_subtitle))
        AnimatedVisibility(running != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Ios.Blue, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.cfnet_trying), color = Ios.Label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (tried > 0) Text(stringResource(R.string.cfnet_tried_n, tried), color = Ios.SecondaryLabel, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SetupHero(states: Map<Step, StepState>, running: Boolean, finished: Boolean, stopped: Boolean, onRetry: () -> Unit, onContinue: () -> Unit) {
    val done = states.values.count { it !is StepState.Waiting && it !is StepState.Running }
    val ready = finished && states.values.all { it == StepState.Passed }
    HeroFrame {
        when {
            finished || (stopped && !running) -> {
                val tone = if (ready) Ios.Green else if (stopped && !finished) Ios.SecondaryLabel else Ios.Orange
                PopIn(Modifier.size(84.dp).clip(CircleShape).background(tone.copy(alpha = 0.18f))) {
                    Icon(if (ready) Icons.Default.CheckCircle else if (stopped && !finished) Icons.Default.PauseCircle else Icons.Default.Info,
                        contentDescription = null, tint = tone, modifier = Modifier.size(46.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(when { ready -> R.string.cfnet_setup_ready; finished -> R.string.cfnet_setup_partial; else -> R.string.cfnet_stopped }),
                    color = Ios.Label, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (finished) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(if (ready) R.string.cfnet_setup_ready_sub else R.string.cfnet_setup_partial_sub),
                        color = Ios.SecondaryLabel, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(18.dp))
                DoctorCapsule(stringResource(R.string.cfnet_go_cloud), Ios.Blue, Color.White, onClick = onContinue)
                if (stopped && !finished) {
                    Spacer(Modifier.height(10.dp))
                    DoctorCapsule(stringResource(R.string.cfnet_retry), Color.White.copy(alpha = 0.12f), Ios.Label, onClick = onRetry)
                }
            }
            else -> {
                val fraction by animateFloatAsState(done / Step.entries.size.toFloat(), animationSpec = tween(600), label = "setup-progress")
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
                    CircularProgressIndicator(progress = 1f, color = Color.White.copy(alpha = 0.12f), strokeWidth = 7.dp, modifier = Modifier.size(112.dp))
                    CircularProgressIndicator(progress = fraction, color = Ios.Blue, strokeWidth = 7.dp, modifier = Modifier.size(112.dp))
                    Image(painterResource(R.drawable.ic_app_doctor), contentDescription = null, modifier = Modifier.size(52.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.cfnet_checking_you), color = Ios.Label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.cfnet_checking_sub), color = Ios.SecondaryLabel, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

// ------------------------------------------------------------------------------ one step's page

@Composable
private fun StepPage(
    step: Step, state: StepState, tried: Int, busy: Boolean,
    onStart: () -> Unit, onStop: () -> Unit, onBack: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit,
) {
    IosScreen(title = stringResource(step.title), onBack = onBack, backLabel = stringResource(R.string.cf_doctor_title)) {
        Spacer(Modifier.height(14.dp))
        HeroFrame {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(ControlShape).background(step.tint()), contentAlignment = Alignment.Center) {
                    Icon(step.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(step.title), color = Ios.Label, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(step.desc), color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            AnimatedContent(targetState = state, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "step-state") { s ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (s) {
                        StepState.Running -> {
                            CircularProgressIndicator(Modifier.size(40.dp), color = Ios.Blue, strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.cfnet_trying), color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                            if (tried > 0) Text(stringResource(R.string.cfnet_tried_n, tried), color = Ios.SecondaryLabel, fontSize = 13.sp)
                            Spacer(Modifier.height(16.dp))
                            DoctorCapsule(stringResource(R.string.cfnet_stop), Color.White.copy(alpha = 0.14f), Ios.Red, onClick = onStop)
                        }
                        StepState.Passed -> {
                            Verdict(Icons.Default.CheckCircle, Ios.Green, stringResource(R.string.cfnet_fixed), stringResource(step.ok))
                            Spacer(Modifier.height(16.dp))
                            DoctorCapsule(stringResource(R.string.cfnet_check_again), Color.White.copy(alpha = 0.12f), Ios.Label, enabled = !busy, onClick = onStart)
                        }
                        is StepState.Failed -> {
                            Verdict(Icons.Default.Error, Ios.Orange, stringResource(s.text), null)
                            Spacer(Modifier.height(16.dp))
                            DoctorCapsule(stringResource(R.string.cfnet_retry), Ios.Blue, Color.White, enabled = !busy, onClick = onStart)
                        }
                        is StepState.Later -> {
                            Verdict(Icons.Default.Schedule, Ios.SecondaryLabel, stringResource(s.text), null)
                            if (s.text != R.string.cfnet_later_account) {
                                Spacer(Modifier.height(16.dp))
                                DoctorCapsule(stringResource(R.string.cfnet_retry), Color.White.copy(alpha = 0.12f), Ios.Label, enabled = !busy, onClick = onStart)
                            }
                        }
                        StepState.Waiting -> DoctorCapsule(stringResource(R.string.cfnet_start), Ios.Blue, Color.White, enabled = !busy, onClick = onStart)
                    }
                }
            }
        }
        extra()
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Verdict(icon: ImageVector, tone: Color, title: String, subtitle: String?) {
    PopIn(Modifier.size(72.dp).clip(CircleShape).background(tone.copy(alpha = 0.18f))) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(40.dp))
    }
    Spacer(Modifier.height(12.dp))
    Text(title, color = Ios.Label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, textAlign = TextAlign.Center)
    if (subtitle != null) {
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Ios.SecondaryLabel, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
    }
}

/** A verdict that arrives the way iOS shows a completed step: a quick, slightly springy grow-in. */
@Composable
private fun PopIn(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val s by animateFloatAsState(if (shown) 1f else 0.6f, animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "pop")
    Box(modifier.scale(s), contentAlignment = Alignment.Center, content = content)
}

// ------------------------------------------------------------------------------ rows

@Composable
private fun StepRow(step: Step, state: StepState, clickable: Boolean, onClick: () -> Unit) {
    val detail = when (state) {
        StepState.Passed -> stringResource(step.ok)
        is StepState.Failed -> stringResource(state.text)
        is StepState.Later -> stringResource(state.text)
        StepState.Running -> stringResource(R.string.cfnet_trying)
        StepState.Waiting -> stringResource(if (clickable) R.string.cfnet_tap_to_check else step.desc)
    }
    val detailColor by animateColorAsState(when (state) {
        StepState.Passed -> Ios.Green
        is StepState.Failed -> Ios.Orange
        StepState.Running -> Ios.Blue
        else -> Ios.SecondaryLabel
    }, animationSpec = tween(350), label = "step-detail")
    Row(
        Modifier.fillMaxWidth()
            .then(if (clickable) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(step.icon, step.tint())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(step.title), color = Ios.Label, fontSize = 16.sp)
            Text(detail, color = detailColor, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        AnimatedContent(targetState = state, transitionSpec = {
            (fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), initialScale = 0.6f)) togetherWith fadeOut(tween(120))
        }, label = "step-pill") { s ->
            when (s) {
                StepState.Running -> CircularProgressIndicator(Modifier.size(18.dp), color = Ios.Blue, strokeWidth = 2.dp)
                StepState.Passed -> Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.cfnet_pill_ok), tint = Ios.Green, modifier = Modifier.size(24.dp))
                is StepState.Failed -> Pill(stringResource(R.string.cfnet_pill_fail), Ios.Orange)
                is StepState.Later -> Pill(stringResource(R.string.cfnet_pill_later), Ios.SecondaryLabel)
                StepState.Waiting -> if (clickable) Chevron() else Pill(stringResource(R.string.cfnet_pill_waiting), Ios.SecondaryLabel)
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun Chevron() {
    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Ios.Chevron, modifier = Modifier.size(18.dp).scale(
        scaleX = if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) -1f else 1f, scaleY = 1f))
}

/** A row in a multiple-choice list, iOS-style: the tick on the trailing edge, the outcome under the name. */
@Composable
private fun SelectRow(title: String, detail: String, selected: Boolean, result: String? = null, resultOk: Boolean? = null, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable(role = Role.Checkbox, onClick = onToggle) else Modifier)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios.Label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(result ?: detail, color = when (resultOk) { true -> Ios.Green; false -> Ios.Orange; null -> Ios.SecondaryLabel },
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (selected) Ios.Blue else Color.Transparent)
                .then(if (selected) Modifier else Modifier.background(Color.White.copy(alpha = 0.14f), CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun CredentialSection(email: String, token: String, onEmail: (String) -> Unit, onToken: (String) -> Unit, enabled: Boolean, onCheck: () -> Unit) {
    SettingsSectionHeader(stringResource(R.string.cfnet_account_header))
    SettingsGroup {
        DoctorField(stringResource(R.string.cfnet_account_email), email, onEmail, secret = false)
        Separator()
        DoctorField(stringResource(R.string.cfnet_account_token), token, onToken, secret = true)
    }
    SettingsFooter(stringResource(R.string.cfnet_account_footer))
    Spacer(Modifier.height(12.dp))
    DoctorCapsule(stringResource(R.string.cfnet_account_check), Ios.Blue, Color.White, enabled = enabled, inset = true, onClick = onCheck)
}

@Composable
private fun DoctorField(placeholder: String, value: String, onChange: (String) -> Unit, secret: Boolean) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, color = Ios.SecondaryLabel.copy(alpha = 0.7f), fontSize = 16.sp)
        BasicTextField(
            value = value, onValueChange = { onChange(it.trim().take(200)) }, singleLine = true,
            textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp), cursorBrush = SolidColor(Ios.Blue),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The app's full-width action button: 50dp tall, the control radius, one strong label. [inset] is
 * for a button standing on the page rather than inside a card: it lines up with the cards' edges.
 */
@Composable
private fun DoctorCapsule(text: String, bg: Color, fg: Color, enabled: Boolean = true, busy: Boolean = false, inset: Boolean = false, onClick: () -> Unit) {
    val live = enabled && !busy
    Box(
        Modifier.fillMaxWidth().padding(horizontal = if (inset) 16.dp else 0.dp)
            .heightIn(min = 50.dp).clip(ControlShape).background(if (live) bg else bg.copy(alpha = bg.alpha * 0.45f))
            .then(if (live) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = fg, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = if (live) fg else fg.copy(alpha = 0.6f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ------------------------------------------------------------------------------ developer mode

/**
 * Hidden unless developer mode is on (seven taps on the version in About). Ids, latencies,
 * confidence and counts only: no token, key, address or response ever reaches this view.
 */
@Composable
private fun DeveloperSection(manager: CfNetManager, outcomes: List<StrategyEngine.StageOutcome>, onLegacy: () -> Unit) {
    val p = remember(outcomes) { manager.profile() }
    SettingsSectionHeader(stringResource(R.string.cfnet_dev_header))
    SettingsGroup {
        DevLine(stringResource(R.string.cfnet_dev_network), "${p.carrier} · IPv4=${p.hasV4} IPv6=${p.hasV6} DNS=${if (p.dnsSane) "ok" else "bad"} · ${p.key.take(8)}")
        outcomes.forEach { out ->
            Separator()
            val winner = out.winner
            val stat = winner?.let { manager.store.stat(p, out.stage, it.id) }
            DevLine(out.stage.name, buildString {
                append(stringResource(R.string.cfnet_dev_strategy)).append(": ").append(winner?.id ?: (out.skippedReason ?: out.dominantFailure?.name ?: "—"))
                if (winner != null) {
                    append("\n").append(stringResource(R.string.cfnet_dev_latency)).append(": ").append(out.result?.latencyMs ?: 0).append(" ms")
                    append(" · ").append(stringResource(R.string.cfnet_dev_validation)).append(": ").append(out.result?.detail.orEmpty())
                    append("\n").append(stringResource(R.string.cfnet_dev_confidence)).append(": ")
                        .append((manager.store.confidence(p, out.stage, winner.id) * 100).toInt()).append("%")
                    append(" · ").append(stringResource(R.string.cfnet_dev_counts)).append(": ").append(stat?.success ?: 0).append(" / ").append(stat?.fail ?: 0)
                    append(" · ").append(stringResource(R.string.cfnet_dev_last_failure)).append(": ").append(stat?.lastFailure?.name ?: "—")
                }
                if (out.tried.isNotEmpty()) {
                    append("\n").append(stringResource(R.string.cfnet_dev_tried)).append(":")
                    out.tried.forEach { (s, r) -> append("\n  ").append(s.id).append(" → ").append(if (r.ok) "PASS" else r.failure?.name).append(" ").append(r.latencyMs).append("ms ").append(r.detail) }
                }
            })
        }
        Separator()
        SettingsActionRow(label = stringResource(R.string.cfnet_dev_legacy), icon = Icons.Default.Description, onClick = onLegacy)
    }
}

@Composable
private fun DevLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, color = Ios.Label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = Ios.SecondaryLabel, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}
