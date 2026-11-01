package com.mlmvpn.scanner.ui

import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.CheckCircle
import com.mlmvpn.scanner.ui.home.frostedGlass
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.mlmvpn.scanner.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.mlmvpn.scanner.utils.S

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudTab(
    onBack: () -> Unit = {},
    onNavigateToScanner: () -> Unit = {},
    onOpenCloudflareResources: () -> Unit = {},
    /** Opens the V2Ray node list. The cloud groups hand their configs over to it. */
    onOpenV2Ray: () -> Unit = {},
    /** Opens Config Studio -- where an account's MLM users live once Config Studio manages it. */
    onOpenConfigStudio: () -> Unit = {},
) {
    val context = LocalContext.current
    val doctorPrefs = remember { context.getSharedPreferences("cfnet_ui", android.content.Context.MODE_PRIVATE) }
    // Cloudflare Doctor runs as a setup the first time Cloud opens after an install or an update
    // (or a new engine version): every stage is diagnosed on this network, then Cloud opens.
    // «ورود به Cloud» or «رد شدن» marks it done for this version; leaving with Back does not.
    val setupVersion = remember {
        val code = runCatching {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        }.getOrDefault(0L)
        "$code:${com.mlmvpn.scanner.engines.cfnet.StrategyStore.ENGINE_VERSION}"
    }
    var doctorOpen by remember { mutableStateOf(doctorPrefs.getString("setup_version", null) != setupVersion) }
    var doctorSetup by remember { mutableStateOf(doctorOpen) }
    if (doctorOpen) {
        com.mlmvpn.scanner.ui.doctor.CloudDoctorScreen(setup = doctorSetup,
            onBack = { if (doctorSetup) onBack() else doctorOpen = false },
            onContinue = { doctorPrefs.edit().putString("setup_version", setupVersion).apply(); doctorOpen = false; doctorSetup = false },
            onOpenScanner = onNavigateToScanner)
        return
    }
    val scope = rememberCoroutineScope()
    val cloudManager = remember { CloudManager(context) }
    val groupManager = remember { com.mlmvpn.scanner.data.GroupManager(context) }
    val accountsFlowState by cloudManager.accountsFlow.collectAsState()
    var accounts by remember(accountsFlowState) { mutableStateOf(accountsFlowState) }
    val cloudGroupsFlowState by groupManager.cloudGroupsFlow.collectAsState()
    var cloudGroups by remember(cloudGroupsFlowState) { mutableStateOf(cloudGroupsFlowState) }
    var showAddModal by remember { mutableStateOf(false) }
    var showNahanSettingsFor by remember { mutableStateOf<CloudAccount?>(null) }
    var showMlmUsersFor by remember { mutableStateOf<CloudAccount?>(null) }
    var showMlmSettingsFor by remember { mutableStateOf<CloudAccount?>(null) }
    var showBpbSettingsFor by remember { mutableStateOf<CloudAccount?>(null) }
    var bpbInitialSettings by remember { mutableStateOf<JSONObject?>(null) }
    var showEdgSettingsFor by remember { mutableStateOf<CloudAccount?>(null) }
    var showGatewaySettingsFor by remember { mutableStateOf<CloudAccount?>(null) }
    var showSpiderFor by remember { mutableStateOf<CloudAccount?>(null) }
    var showArenaFor by remember { mutableStateOf<CloudAccount?>(null) }
    var accountToDelete by remember { mutableStateOf<com.mlmvpn.scanner.models.CloudAccount?>(null) }
    // With several accounts one card is open at a time and the rest fold to their header row, so
    // five accounts are five rows rather than five screens of panels. The first starts open.
    var openAccountId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(accountsFlowState.firstOrNull()?.id) }

    // Physical back closes whichever settings/add sheet is open, instead of falling through to
    // the app-level handler (which would leave the Cloud tab entirely).
    val anySheetOpen = showAddModal || showNahanSettingsFor != null || showMlmUsersFor != null ||
        showMlmSettingsFor != null || showBpbSettingsFor != null || showEdgSettingsFor != null || showGatewaySettingsFor != null ||
        showSpiderFor != null || showArenaFor != null || accountToDelete != null
    androidx.activity.compose.BackHandler(enabled = anySheetOpen) {
        showAddModal = false
        showNahanSettingsFor = null
        showMlmUsersFor = null
        showMlmSettingsFor = null
        showBpbSettingsFor = null
        showEdgSettingsFor = null
        showGatewaySettingsFor = null
        showSpiderFor = null
        showArenaFor = null
        accountToDelete = null
    }

    // One received group opens as a page: four actions of similar weight cannot live on a 40dp
    // row, and the one that matters most -- combining for speed -- had nowhere to go at all.
    var openGroupId by remember { mutableStateOf<String?>(null) }
    val openGroup = cloudGroups.find { it.id == openGroupId }

    // 720dp, the same threshold the rest of the app uses: the width at which a page fits BESIDE
    // the list instead of on top of it.
    val twoPane = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720

    // Each of the three full pages this screen can open is collected as a value rather than
    // returned early. On a phone the effect is identical -- the page is shown and nothing else --
    // but on a tablet or a television it can then be put in the second column, with the account
    // list still visible beside it. Returning early made that impossible: the function was over
    // before the list had been described.
    val groupDetail: (@Composable () -> Unit)? = if (openGroup == null) null else ({
        androidx.activity.compose.BackHandler(enabled = true) { openGroupId = null }
        CloudGroupScreen(
            group = openGroup,
            onSendToV2Ray = {
                val nm = com.mlmvpn.scanner.data.NodeManager(context)
                val existing = nm.nodes.map { it.uri }.toSet()
                openGroup.nodes.forEach { n ->
                    if (n.uri !in existing) {
                        nm.nodes.add(
                            n.copy(
                                id = java.util.UUID.randomUUID().toString(),
                                groupTitle = openGroup.title,
                            )
                        )
                    }
                }
                nm.saveNodes()
            },
            onOpenV2Ray = { openGroupId = null; onOpenV2Ray() },
            onCombine = {
                // The button IS the answer to "shall we combine?", so no offer dialog: hand the
                // scanner its base config and let the coach take it from step 1.
                com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.value =
                    openGroup.nodes.firstOrNull()?.uri.orEmpty()
                com.mlmvpn.scanner.data.ScannerManager.selectedSourceGroupId.value = openGroup.id
                com.mlmvpn.scanner.ui.CombineCoach.start(
                    context,
                    openGroup.id,
                    openGroup.nodes.firstOrNull()?.uri.orEmpty(),
                )
                openGroupId = null
                onNavigateToScanner()
            },
            onDelete = {
                groupManager.cloudGroups.remove(openGroup)
                groupManager.saveCloudGroups()
                cloudGroups = groupManager.cloudGroups.toList()
            },
            onBack = { openGroupId = null },
        )
    })

    var isAdding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    // Troubleshooting, in its two modes. An added account carries everything the probe asks about;
    // a credential that would not add carries none of it, and is the case the wrench on the account
    // card can never reach -- there is no card yet.
    var troubleshootFor by remember { mutableStateOf<CloudAccount?>(null) }
    var troubleshootCredential by remember { mutableStateOf<Pair<String, String>?>(null) }

    // The cloud coach. Offered once, to someone opening an empty Cloud screen for the first time --
    // which is exactly the person who cannot tell what this screen wants from them.
    val cloudStep by CloudCoach.step.collectAsState()
    val cloudWork by CloudCoach.work.collectAsState()
    val cloudProgress by CloudCoach.workProgress.collectAsState()
    val cloudFailure by CloudCoach.failure.collectAsState()
    LaunchedEffect(accounts.size, cloudGroups.size) {
        CloudCoach.init(context)
        if (accounts.isEmpty() && cloudGroups.isEmpty()) CloudCoach.offerOnce(context)
        // The account step completes itself the moment an account exists.
        if (cloudStep == CloudCoach.Step.ACCOUNT && accounts.isNotEmpty()) {
            CloudCoach.advance(CloudCoach.Step.INSTALL)
        }
    }

    // «هماهنگ با ویندوز» (engines/cloud/PanelRegistry): once per session per account, the account's
    // shared panel installs → this phone, and this phone's → the account. Quiet: a panel that cannot
    // be synced stays as it was; the list is refreshed only when something moved.
    LaunchedEffect(accounts.size) {
        val moved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            accounts.toList().map { acc -> runCatching { com.mlmvpn.scanner.engines.cloud.PanelSync.syncOnce(context, acc) }.getOrDefault(false) }.any { it }
        }
        if (moved) accounts = cloudManager.accounts.toList()
    }

    val troubleshootDetail: (@Composable () -> Unit)? =
        if (troubleshootFor == null && troubleshootCredential == null) null else ({
        androidx.activity.compose.BackHandler(enabled = true) {
            troubleshootFor = null
            troubleshootCredential = null
        }
        CloudTroubleshootScreen(
            cloudManager = cloudManager,
            account = troubleshootFor,
            credential = troubleshootCredential,
            backLabel = if (troubleshootCredential != null) S(R.string.add_cloudflare_account)
                        else S(R.string.nav_cloud),
            onBack = {
                troubleshootFor = null
                troubleshootCredential = null
            },
            onAccountChanged = { accounts = cloudManager.accounts.toList() },
            onOpenScanner = onNavigateToScanner,
        )
    })

    val addDetail: (@Composable () -> Unit)? = if (!showAddModal) null else ({
        androidx.activity.compose.BackHandler(enabled = !isAdding) { showAddModal = false }
        CloudAddAccountScreen(
            onTroubleshoot = { email, key -> troubleshootCredential = key to email },
            isAdding = isAdding,
            error = addError,
            onAdd = { email, key ->
                if (accounts.any { (email.isNotEmpty() && it.email == email) || it.token == key }) {
                    addError = context.getString(R.string.cloud_account_already_added)
                    return@CloudAddAccountScreen
                }
                isAdding = true
                addError = null
                scope.launch {
                    val result = cloudManager.addAccount(key, email)
                    isAdding = false
                    if (result.first) {
                        accounts = cloudManager.accounts.toList()
                        addError = null
                        showAddModal = false
                    } else {
                        addError = result.second
                    }
                }
            },
            onBack = { if (!isAdding) showAddModal = false },
        )
    })

    val detail = groupDetail ?: troubleshootDetail ?: addDetail

    // A phone shows the page and nothing else, exactly as it always did.
    if (detail != null && !twoPane) {
        detail()
        return
    }

    val root: @Composable () -> Unit = {
        // The app's own navigation bar rather than the generic feature chrome: this screen pushes
    // full-screen settings sheets of its own, and two stacked bars is what that looked like.
    com.mlmvpn.scanner.ui.settings.IosScreen(
        title = stringResource(R.string.nav_cloud),
        onBack = onBack,
        backLabel = S(R.string.home),
        scrollable = false,
    ) {
        run {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = 8.dp,
                    bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                // «دکتر کلادفلر»: where to go when a part of Cloud does not work -- each part is
                // diagnosed on its own, and the way that works is saved for this network.
                item {
                    com.mlmvpn.scanner.ui.settings.SettingsGroup {
                        com.mlmvpn.scanner.ui.settings.SettingsRow(
                            title = stringResource(R.string.cf_doctor_title),
                            subtitle = stringResource(R.string.cfnet_cloud_row_sub),
                            icon = Icons.Default.HealthAndSafety,
                            tint = com.mlmvpn.scanner.ui.settings.Ios.Blue,
                            onClick = { doctorSetup = false; doctorOpen = true })
                    }
                }

                // The coach rides at the top of the list, above the account it is talking about.
                if (cloudStep != CloudCoach.Step.IDLE && cloudStep != CloudCoach.Step.OFFERED) {
                    item {
                        val failed = cloudStep == CloudCoach.Step.FAILED
                        val installing = cloudStep == CloudCoach.Step.INSTALL && cloudWork.isNotEmpty()
                        val fetching = cloudStep == CloudCoach.Step.FETCH && cloudWork.isNotEmpty()
                        CloudCoachBar(
                            step = cloudStep,
                            onSkip = { CloudCoach.skip() },
                            failed = failed,
                            working = installing || fetching,
                            workText = cloudWork,
                            workProgress = cloudProgress,
                            body = when {
                                failed -> cloudFailure
                                cloudStep == CloudCoach.Step.ACCOUNT ->
                                    S(R.string.first_we_connect_a_free_cloudflare_account) +
                                        S(R.string.on_it_and_nobody_but_you_can) +
                                        S(R.string.if_you_already_have_an_account_enter)
                                cloudStep == CloudCoach.Step.INSTALL && installing ->
                                    S(R.string.installing_the_panel_on_your_account_this)
                                cloudStep == CloudCoach.Step.INSTALL ->
                                    S(R.string.account_connected_now_i_will_install_a) +
                                        S(R.string.builds_your_configs_it_needs_nothing_from) +
                                        S(R.string.tap_to_start)
                                cloudStep == CloudCoach.Step.FETCH && fetching ->
                                    S(R.string.fetching_the_configs_from_the_panel)
                                else ->
                                    S(R.string.panel_installed_now_i_will_fetch_the) +
                                        S(R.string.on_to_combining_them_with_a_clean)
                            },
                            actionLabel = when {
                                failed -> S(R.string.try_again)
                                installing || fetching -> null
                                cloudStep == CloudCoach.Step.ACCOUNT -> S(R.string.add_cloudflare_account)
                                cloudStep == CloudCoach.Step.INSTALL -> S(R.string.install_the_panel)
                                else -> S(R.string.fetch_the_configs)
                            },
                            onAction = when {
                                installing || fetching -> null
                                failed -> ({
                                    // Back to the step that failed, not to the start: the account
                                    // is still connected and re-doing that would be theatre.
                                    CloudCoach.advance(
                                        if (accounts.isEmpty()) CloudCoach.Step.ACCOUNT
                                        else CloudCoach.Step.INSTALL
                                    )
                                })
                                cloudStep == CloudCoach.Step.ACCOUNT -> ({ showAddModal = true })
                                cloudStep == CloudCoach.Step.INSTALL ->
                                    ({ CloudCoach.ask(CloudCoach.Request.INSTALL) })
                                else -> ({ CloudCoach.ask(CloudCoach.Request.FETCH) })
                            },
                        )
                    }
                }

                // «روش اتصال این شبکه»: one button that finds which way of reaching Cloudflare works on this network, kept
                // for it and used by the scanner, the connect and the configs the combine centre builds. Above the chart.
                if (accounts.isNotEmpty()) item { com.mlmvpn.scanner.ui.cloud.CfMethodCard(accounts) }

                // «مصرف روزانه»: the opening module -- every account's Worker requests, seven days.
                if (accounts.isNotEmpty()) item { com.mlmvpn.scanner.ui.cloud.CloudUsageModule(accounts) }

                // «میدان کانفیگ»: races the panels of the first account (the one the coach works on).
                if (accounts.isNotEmpty()) item {
                    com.mlmvpn.scanner.ui.arena.ArenaEntryCard(onOpen = { showArenaFor = accounts.first() })
                }

                itemsIndexed(accounts) { accountIndex, account ->
                    // With one account the card's own header is identity enough. With several,
                    // four identical Cloudflare cards stack up and the only thing telling them
                    // apart is an email in the middle of one of them -- so they get numbered, and
                    // the coach says out loud which one it is working on.
                    if (accounts.size > 1) {
                        com.mlmvpn.scanner.ui.settings.SettingsSectionHeader(
                            S(R.string.account_of, faCount(accountIndex + 1), faCount(accounts.size)) +
                                if (accountIndex == 0 && cloudStep != CloudCoach.Step.IDLE) S(R.string.the_guide_is_working_on_this_account) else ""
                        )
                    }
                    // Inset like the modules above it: SettingsGroup adds 16dp inside the list's 10dp.
                    Box(Modifier.padding(horizontal = 16.dp)) {
                    AccountGroupCard(
                        collapsed = accounts.size > 1 && openAccountId != account.id,
                        onToggleCollapsed = if (accounts.size > 1) ({
                            openAccountId = if (openAccountId == account.id) null else account.id
                        }) else null,
                        isPrimary = accountIndex == 0,
                        onCoachHandOff = { _, _ -> onNavigateToScanner() },
                        onOpenV2Ray = onOpenV2Ray,
                        onOpenGroup = { openGroupId = it },
                        account = account,
                        cloudGroups = cloudGroups.filter { it.accountId == account.id },
                        groupManager = groupManager,
                        cloudManager = cloudManager,
                        onGroupsUpdated = { cloudGroups = groupManager.cloudGroups.toList() },
                        onShowNahanSettings = { showNahanSettingsFor = it },
                        onShowMlmUsers = { showMlmUsersFor = it },
                        onShowMlmSettings = { showMlmSettingsFor = it },
                        onOpenConfigStudio = onOpenConfigStudio,
                        onShowBpbSettings = { settings, acc -> 
                            bpbInitialSettings = settings
                            showBpbSettingsFor = acc 
                        },
                        onShowEdgSettings = { showEdgSettingsFor = it },
                        onShowGatewaySettings = { showGatewaySettingsFor = it },
                        onShowSpider = { showSpiderFor = it },
                        onDelete = {
                            accountToDelete = account
                        },
                        onTroubleshoot = { troubleshootFor = account },
                    )
                    }
                }
                // «کانفیگ‌های دریافتی»: every account's received groups, below the accounts.
                if (cloudGroups.isNotEmpty()) item {
                    com.mlmvpn.scanner.ui.cloud.CloudReceivedModule(
                        accounts = accounts,
                        groups = cloudGroups,
                        onOpenGroup = { openGroupId = it },
                    )
                }
                if (accounts.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .fillParentMaxHeight(0.7f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Cloud, contentDescription = null, tint = TextDim, modifier = Modifier.size(96.dp))
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(stringResource(R.string.cloud_no_account_connected), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.cloud_press_add_button), color = TextMuted, fontSize = 14.sp)
                        }
                    }
                }
                // A row, not a floating disc parked over the content. Adding an account is a
                // once-a-month action, and it does not need to sit on top of the panels you use
                // every day -- which is also what it was covering.
                item {
                    com.mlmvpn.scanner.ui.settings.SettingsGroup {
                        com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                            label = S(R.string.add_cloudflare_account),
                            icon = Icons.Default.Add,
                        ) { showAddModal = true }
                        // The offer is made once and never again, so the walkthrough needs a door
                        // that stays open -- for the second account, or for the user who dismissed
                        // it the first time and then wished they had not.
                        if (cloudStep == CloudCoach.Step.IDLE) {
                            Separator()
                            com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                                label = S(R.string.step_by_step_guide),
                                icon = Icons.Default.AutoAwesome,
                            ) { CloudCoach.start(context, hasAccount = accounts.isNotEmpty()) }
                        }
                        // Everything these accounts actually hold on Cloudflare -- workers, D1,
                        // KV -- including what the app did not deploy itself. It lives in
                        // Settings because it is about the account rather than about a panel;
                        // this is the door to it from where the accounts are.
                        if (accounts.isNotEmpty()) {
                            Separator()
                            com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                                label = S(R.string.cloudflare_resources),
                                icon = Icons.Default.Dns,
                            ) { onOpenCloudflareResources() }
                        }
                    }
                    com.mlmvpn.scanner.ui.settings.SettingsFooter(
                        S(R.string.the_guide_walks_you_from_connecting_an) +
                            S(R.string.technical_work_itself_you_can_leave_it)
                    )
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }
        }
    }

        if (accountToDelete != null) {
            com.mlmvpn.scanner.ui.settings.IosAlert(
                title = stringResource(R.string.dialog_delete_account_title),
                message = stringResource(R.string.dialog_delete_account_msg),
                onDismiss = { accountToDelete = null },
                actions = listOf(
                    com.mlmvpn.scanner.ui.settings.IosAlertAction(
                        stringResource(R.string.nodes_cancel),
                        onClick = { accountToDelete = null },
                    ),
                    com.mlmvpn.scanner.ui.settings.IosAlertAction(
                        stringResource(R.string.nodes_confirm),
                        onClick = {
                            cloudManager.accounts.remove(accountToDelete!!)
                            cloudManager.saveAccounts()
                            accounts = cloudManager.accounts.toList()
                            accountToDelete = null
                        },
                        destructive = true,
                    ),
                ),
            )
        }

    if (showNahanSettingsFor != null) {
            com.mlmvpn.scanner.ui.home.IosModalHost(modifier = Modifier.fillMaxSize()) {
                com.mlmvpn.scanner.engines.nahan.NahanSettingsScreen(
                    account = showNahanSettingsFor!!,
                    groupManager = groupManager,
                    onGroupsUpdated = { cloudGroups = groupManager.cloudGroups.toList() },
                    onDismiss = { showNahanSettingsFor = null }
                )
            }
        }

        showArenaFor?.let { acc ->
            com.mlmvpn.scanner.ui.home.IosModalHost(modifier = Modifier.fillMaxSize()) {
                com.mlmvpn.scanner.ui.arena.ArenaScreen(
                    account = acc,
                    groupManager = groupManager,
                    onBack = { showArenaFor = null },
                    onGroupsUpdated = { cloudGroups = groupManager.cloudGroups.toList() },
                )
            }
        }

        showSpiderFor?.let { acc ->
            com.mlmvpn.scanner.ui.home.IosModalHost(modifier = Modifier.fillMaxSize()) {
                com.mlmvpn.scanner.ui.spider.SpiderScreen(
                    account = acc,
                    onBack = { showSpiderFor = null },
                    onAddConfigs = { links ->
                        if (com.mlmvpn.scanner.ui.spider.SpiderConfigs.file(context, groupManager, acc, links) != null) {
                            cloudGroups = groupManager.cloudGroups.toList()
                            android.widget.Toast.makeText(context, context.getString(R.string.cloud_admin_configs_received, links.size), android.widget.Toast.LENGTH_SHORT).show()
                            // Back to the Cloud tab, where the combine offer shows under the new group.
                            showSpiderFor = null
                        }
                    },
                )
            }
        }

        if (showMlmUsersFor != null) {
            com.mlmvpn.scanner.ui.home.IosModalHost(modifier = Modifier.fillMaxSize()) {
                val acc = showMlmUsersFor!!
                com.mlmvpn.scanner.engines.mlm.MlmUsersScreen(
                    account = acc,
                    onDismiss = { showMlmUsersFor = null },
                    onScanUser = { configStr ->
                        showMlmUsersFor = null
                        com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.value = configStr
                        onNavigateToScanner()
                    },
                    onGroupsUpdated = {
                        groupManager.loadCloudGroups()
                        cloudGroups = groupManager.cloudGroups.toList()
                    }
                )
            }
        }

        if (showMlmSettingsFor != null) {
            com.mlmvpn.scanner.ui.home.IosModalHost(modifier = Modifier.fillMaxSize()) {
                com.mlmvpn.scanner.engines.mlm.MlmSettingsScreen(
                    account = showMlmSettingsFor!!,
                    onDismiss = { showMlmSettingsFor = null }
                )
            }
        }
        
        if (showBpbSettingsFor != null) {
            com.mlmvpn.scanner.ui.home.IosModalHost(modifier = Modifier.fillMaxSize()) {
                BpbSettingsModal(
                    initialSettings = bpbInitialSettings,
                    onDismiss = { showBpbSettingsFor = null },
                    onConfirm = { settingsJson ->
                        val acc = showBpbSettingsFor!!
                        showBpbSettingsFor = null
                        scope.launch {
                            android.widget.Toast.makeText(context, context.getString(R.string.cloud_updating_settings_and_fetching), android.widget.Toast.LENGTH_SHORT).show()
                        
                            val updateResult = cloudManager.updateWorkerSettings(acc, settingsJson)
                            if (!updateResult.first) {
                                android.widget.Toast.makeText(context, updateResult.second, android.widget.Toast.LENGTH_LONG).show()
                                return@launch
                            }

                            val result = cloudManager.fetchCloudConfigs(acc)
                            if (result.first) {
                                val newGroup = com.mlmvpn.scanner.data.CloudGroup(
                                    id = System.currentTimeMillis().toString(),
                                    accountId = acc.id,
                                    date = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                    title = com.mlmvpn.scanner.utils.NamingHelper.generateMythName(),
                                    nodes = result.second.map { uri ->
                                        com.mlmvpn.scanner.models.VpnNode(
                                            id = java.util.UUID.randomUUID().toString(),
                                            name = "VLESS - " + acc.email.substringBefore("@"),
                                            uri = uri,
                                            type = "vless"
                                        )
                                    }
                                )
                                groupManager.cloudGroups.add(0, newGroup)
                                    com.mlmvpn.scanner.ui.CombineCoach.offer(context, newGroup.id, newGroup.nodes.firstOrNull()?.uri ?: "")
                                groupManager.saveCloudGroups()
                                android.widget.Toast.makeText(context, context.getString(R.string.cloud_new_group_received), android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, context.getString(R.string.cloud_error_fetching_nodes), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
        }

    val coachStep by com.mlmvpn.scanner.ui.CombineCoach.step.collectAsState()
    val coachUri by com.mlmvpn.scanner.ui.CombineCoach.uri.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { com.mlmvpn.scanner.ui.CombineCoach.init(context) }

    if (coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.OFFERED) {
    if (cloudStep == CloudCoach.Step.OFFERED) {
        CloudOfferDialog(
            onYes = { CloudCoach.accept(hasAccount = accounts.isNotEmpty()) },
            onNo = { CloudCoach.skip() },
        )
    }

        com.mlmvpn.scanner.ui.CombineOfferDialog(
            onYes = {
                com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.value = coachUri
                com.mlmvpn.scanner.ui.CombineCoach.accept()
                onNavigateToScanner()
            },
            onNo = { com.mlmvpn.scanner.ui.CombineCoach.skip() },
        )
    }

    if (showEdgSettingsFor != null) {
        com.mlmvpn.scanner.ui.home.IosModalHost(modifier = Modifier.fillMaxSize()) {
            com.mlmvpn.scanner.engines.edg.EdgSettingsScreen(
                account = showEdgSettingsFor!!,
                onDismiss = { showEdgSettingsFor = null }
            )
        }
    }

    showGatewaySettingsFor?.let { acc ->
        com.mlmvpn.scanner.ui.home.IosModalHost(modifier = Modifier.fillMaxSize()) {
            com.mlmvpn.scanner.ui.cloud.GatewaySettingsScreen(account = acc, onDismiss = { showGatewaySettingsFor = null })
        }
    }
    }

    if (!twoPane) {
        root()
        return
    }

    // A Row, mirrored by right-to-left for nothing: the account list lands on the right in
    // Persian and on the left in English, with the open page opposite it.
    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { root() }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(com.mlmvpn.scanner.ui.settings.Ios.Separator),
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (detail != null) detail() else CloudDetailPlaceholder()
        }
    }
}

/** What fills the second column before an account or a group has been opened. */
@Composable
private fun CloudDetailPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = null,
                tint = com.mlmvpn.scanner.ui.settings.Ios.SecondaryLabel.copy(alpha = 0.5f),
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                S(R.string.cloud_pick_a_row),
                color = com.mlmvpn.scanner.ui.settings.Ios.SecondaryLabel,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
fun AccountGroupCard(
    account: CloudAccount,
    cloudGroups: List<com.mlmvpn.scanner.data.CloudGroup>,
    groupManager: com.mlmvpn.scanner.data.GroupManager,
    cloudManager: com.mlmvpn.scanner.data.CloudManager,
    onGroupsUpdated: () -> Unit,
    onShowNahanSettings: (CloudAccount) -> Unit,
    onShowMlmUsers: (CloudAccount) -> Unit,
    onShowMlmSettings: (CloudAccount) -> Unit,
    /** Opens Config Studio on its user list, for an account Config Studio manages. */
    onOpenConfigStudio: () -> Unit = {},
    onShowBpbSettings: (org.json.JSONObject?, CloudAccount) -> Unit,
    onShowEdgSettings: (CloudAccount) -> Unit,
    /** Opens the central Worker's settings for this account (ui/cloud/GatewaySettingsScreen). */
    onShowGatewaySettings: (CloudAccount) -> Unit = {},
    /** Opens «پنل اسپایدر» for this account, as a page of the Cloud tab. */
    onShowSpider: (CloudAccount) -> Unit = {},
    onDelete: () -> Unit,
    /**
     * Opens the troubleshooting page for this account.
     *
     * Hoisted rather than hosted here: the card is a row inside a LazyColumn, and a full page
     * rendered from inside one is clipped to the row's own bounds. Every other full screen this
     * card reaches -- the panel settings, the user list -- goes up the same way.
     */
    onTroubleshoot: () -> Unit = {},
    /** Opens the V2Ray node list, for the group rows that hand their configs to it. */
    onOpenV2Ray: () -> Unit = {},
    onOpenGroup: (String) -> Unit = {},
    /** Only the top card answers the coach; see the request handler below. */
    isPrimary: Boolean = false,
    onCoachHandOff: (groupId: String, uri: String) -> Unit = { _, _ -> },
    /** Folded to its header row (the Cloud tab folds all but one card when there are several). */
    collapsed: Boolean = false,
    /** Set when the card can fold; the header row then toggles it. */
    onToggleCollapsed: (() -> Unit)? = null,
) {
    // Which panel is mid-removal, so only that row spins.
    var removingPanel by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    // One panel open at a time; the rest stay one row tall.
    var openPanel by remember { mutableStateOf<String?>(null) }
    val cfgCount: (String) -> Int = { engine ->
        cloudGroups.count { g -> (g.nodes.firstOrNull()?.engineType ?: "BPB") == engine }
    }
    var deployState by remember { mutableStateOf(if (account.status == "DEPLOYED" || account.status == "deployed") "done" else "idle") }
    var edgDeployState by remember { mutableStateOf(if (account.edgStatus == "DEPLOYED" || account.edgStatus == "deployed") "done" else "idle") }
    var nahanDeployState by remember { mutableStateOf(if (account.nahanStatus == "DEPLOYED" || account.nahanStatus == "deployed") "done" else "idle") }
    var mlmDeployState by remember { mutableStateOf(if (account.mlmStatus == "DEPLOYED" || account.mlmStatus == "deployed") "done" else "idle") }
    var progress by remember { mutableStateOf(0f) }
    var edgProgress by remember { mutableStateOf(0f) }
    var mlmProgress by remember { mutableStateOf(0f) }
    // «دریافت کانفیگ xhttp»: busy while the config is made and proven on this network (MlmXhttp).
    var mlmXhttpBusy by remember { mutableStateOf(false) }
    var isNahanFetching by remember { mutableStateOf(false) }
    var nahanProgress by remember { mutableStateOf(0f) }
    var isFetching by remember { mutableStateOf(false) }
    var isEdgFetching by remember { mutableStateOf(false) }
    var showUsage by remember { mutableStateOf(false) }
    var usageData by remember { mutableStateOf(0f) }
    var isLoadingUsage by remember { mutableStateOf(false) }

    var isExpanded by remember { mutableStateOf(false) }
    var showSubdomainError by remember { mutableStateOf(false) }
    
    // Smart Verification States
    var isEmailVerified by remember { mutableStateOf(account.isEmailVerified) }
    var hasSubdomain by remember { mutableStateOf(account.hasSubdomain) }
    var showEmailModal by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    // Which panels are installed as FULL panels on this account. A full panel keeps its own row below; a panel that is not
    // installed gets a row of the account's ONE central Worker instead of a «Deploy» button of its own (ui/cloud/GatewayRows.kt).
    var thirdPartyTick by remember { mutableStateOf(0) }
    val spiderInstall = remember(thirdPartyTick) { com.mlmvpn.scanner.engines.spider.SpiderPanel.install(context, account.accountId) }
    val netra = remember(thirdPartyTick) { com.mlmvpn.scanner.engines.netra.NetraPanel.install(context, account.accountId) }
    val gozargah = remember(thirdPartyTick) { com.mlmvpn.scanner.engines.gozargah.GozargahPanel.install(context, account.accountId) }
    val nova = remember(thirdPartyTick) { com.mlmvpn.scanner.engines.nova.NovaPanel.install(context, account.accountId) }
    val gwVersion by com.mlmvpn.scanner.engines.gateway.EdgeGateway.version.collectAsState()
    val gw = remember(gwVersion, account.accountId) { com.mlmvpn.scanner.engines.gateway.EdgeGateway.status(context, account) }
    var edgGwBusy by remember { mutableStateOf(false) }
    val legacyPanels = buildSet {
        if (edgDeployState != "idle") add("EDG")
        if (nahanDeployState != "idle") add("NHN")
        if (mlmDeployState != "idle") add("MLM")
        if (spiderInstall != null) add("SPD")
        if (netra != null) add("NTR")
        if (gozargah != null) add("GZG")
        if (nova != null) add("NVA")
    }
    val prefs = context.getSharedPreferences("smart_verify", android.content.Context.MODE_PRIVATE)
    var emailSendTime by remember { mutableStateOf(prefs.getLong("email_${account.id}", 0L)) }
    var isCheckingStatus by remember { mutableStateOf(false) }
    var isCreatingSubdomain by remember { mutableStateOf(false) }
    var subdomainTimer by remember { mutableStateOf(0) }
    var showSubdomainCheck by remember { mutableStateOf(false) }
    var subdomainCreationError by remember { mutableStateOf("") }
    
    val scope = rememberCoroutineScope()

    // A redeploy is the same upload the deploy button runs -- but onto the script name already
    // recorded for this panel, so it replaces what is there instead of adding a second worker
    // beside it. The state goes back to "deploying" so the row's progress bar reports it.
    val redeployPanel: (String) -> Unit = { key ->
        scope.launch {
            when (key) {
                "BPB" -> {
                    deployState = "deploying"; progress = 0f
                    val r = cloudManager.deployWorker(account) { p, _ -> progress = p.toFloat() }
                    // A failed REdeploy leaves the panel exactly as it was -- the old script is
                    // still up there serving -- so the row goes back to "deployed", not to "idle".
                    deployState = "done"
                    if (!r.first) android.widget.Toast.makeText(context, r.second, android.widget.Toast.LENGTH_LONG).show()
                }
                "EDG" -> {
                    edgDeployState = "deploying"; edgProgress = 0f
                    val r = cloudManager.deployEdgWorker(account) { p, _ -> edgProgress = p.toFloat() }
                    edgDeployState = "done"
                    if (!r.first) android.widget.Toast.makeText(context, r.second, android.widget.Toast.LENGTH_LONG).show()
                }
                "NHN" -> {
                    nahanDeployState = "deploying"; nahanProgress = 0f
                    val d = com.mlmvpn.scanner.engines.nahan.NahanDeployer(context)
                    val r = d.deployNahan(account) { p, _ -> nahanProgress = p.toFloat() }
                    nahanDeployState = "done"
                    if (!r.first) android.widget.Toast.makeText(context, r.second, android.widget.Toast.LENGTH_LONG).show()
                }
                "MLM" -> {
                    mlmDeployState = "deploying"; mlmProgress = 0f
                    val d = com.mlmvpn.scanner.engines.mlm.MlmDeployer(context)
                    val r = d.deployMlm(account) { p, _ -> mlmProgress = p.toFloat() }
                    mlmDeployState = "done"
                    if (!r.first) android.widget.Toast.makeText(context, r.second, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            cloudManager.saveAccounts()
        }
    }

    val removePanel: (String) -> Unit = { key ->
        scope.launch {
            removingPanel = key
            // Read before the removal clears the worker address it is decided from.
            val wasStudio = key == "MLM" && account.isStudioManaged
            val (ok, message) = cloudManager.removePanel(account, key)
            removingPanel = null
            if (ok && wasStudio) {
                com.mlmvpn.scanner.data.studio.StudioStore.get(context).disconnect(account.id)
            }
            if (ok) {
                when (key) {
                    "BPB" -> deployState = "idle"
                    "EDG" -> edgDeployState = "idle"
                    "NHN" -> nahanDeployState = "idle"
                    "MLM" -> mlmDeployState = "idle"
                }
                openPanel = null
            }
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // The install and the fetch, as functions rather than as two onClick bodies.
    //
    // The coach has to be able to run exactly what the buttons run -- not a second implementation
    // that drifts from them. Both report through CloudCoach when the coach is the caller, and
    // both are otherwise unchanged from the rows that used to own them.
    // EDG is a row of the account's central Worker now: «install» connects it to the ONE Worker (installing that first when
    // the account has none) and «fetch» takes its links from there. An EDG panel installed the old way keeps working as it did.
    val runEdgInstall: (Boolean) -> Unit = { coached ->
        if (edgDeployState == "idle" && !edgGwBusy) {
            edgGwBusy = true
            scope.launch {
                val result = com.mlmvpn.scanner.engines.gateway.EdgeGateway.connectPanel(context, account, "EDG") { stepText ->
                    if (coached) CloudCoach.setWork(stepText, 0.5f)
                }
                edgGwBusy = false
                if (result.isSuccess) {
                    if (coached) CloudCoach.advance(CloudCoach.Step.FETCH)
                } else {
                    val raw = com.mlmvpn.scanner.engines.gateway.EdgeGateway.localized(result.exceptionOrNull()!!)
                    if (coached) {
                        CloudCoach.failed(
                            CloudCoach.diagnose(
                                emailVerified = isEmailVerified,
                                hasSubdomain = hasSubdomain,
                                installed = false,
                                rawError = raw,
                            )
                        )
                    } else {
                        android.widget.Toast.makeText(context, raw, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val runEdgFetch: (Boolean) -> Unit = { coached ->
        val viaGateway = edgDeployState != "done"
        val ready = !viaGateway || com.mlmvpn.scanner.engines.gateway.EdgeGateway.status(context, account).connected("EDG")
        if (ready && !isEdgFetching) {
            scope.launch {
                isEdgFetching = true
                if (coached) {
                    CloudCoach.setWork(S(R.string.fetching_configs_from_the_panel), 0f)
                } else {
                    android.widget.Toast.makeText(context, context.getString(R.string.cloud_fetching_edg_configs), android.widget.Toast.LENGTH_SHORT).show()
                }
                val newGroup: com.mlmvpn.scanner.data.CloudGroup? = if (viaGateway) {
                    com.mlmvpn.scanner.ui.cloud.GatewayConfigs.fetch(context, groupManager, account, "EDG", offer = false).getOrNull()?.let {
                        groupManager.cloudGroups.firstOrNull { g -> g.id.startsWith(com.mlmvpn.scanner.ui.cloud.GatewayConfigs.groupPrefix("EDG", account.id)) }
                    }
                } else {
                    val result = cloudManager.fetchEdgConfigs(account)
                    if (result.first && result.second.isNotEmpty()) {
                        val engineNodes = result.second.mapIndexed { index, uri ->
                            com.mlmvpn.scanner.models.VpnNode(
                                id = "edg_${account.id}_$index",
                                name = "EDG Node ${index + 1}",
                                uri = uri,
                                type = if (uri.startsWith("vless")) "vless" else "trojan",
                                engineType = "EDG"
                            )
                        }
                        com.mlmvpn.scanner.data.CloudGroup(
                            id = "edg_${account.id}_${System.currentTimeMillis()}",
                            accountId = account.id,
                            date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                            title = com.mlmvpn.scanner.utils.NamingHelper.generateMythName(),
                            nodes = engineNodes
                        ).also {
                            groupManager.cloudGroups.add(0, it)
                            groupManager.saveCloudGroups()
                        }
                    } else null
                }
                if (newGroup != null) {
                    onGroupsUpdated()
                    if (coached) {
                        // Straight into the combine coach at step 4 of 8 -- no second offer, the
                        // user already said yes to being walked through this.
                        //
                        // The scanner needs the base config handed to it, not just the coach state:
                        // without this the strip said "config chosen" over a picker that still read
                        // "not chosen yet".
                        com.mlmvpn.scanner.data.ScannerManager.globalBaseConfig.value =
                            newGroup.nodes.firstOrNull()?.uri.orEmpty()
                        com.mlmvpn.scanner.data.ScannerManager.selectedSourceGroupId.value = newGroup.id
                        CloudCoach.handOff(context, newGroup.id, newGroup.nodes.firstOrNull()?.uri ?: "")
                        onCoachHandOff(newGroup.id, newGroup.nodes.firstOrNull()?.uri ?: "")
                    } else {
                        com.mlmvpn.scanner.ui.CombineCoach.offer(context, newGroup.id, newGroup.nodes.firstOrNull()?.uri ?: "")
                        android.widget.Toast.makeText(context, context.getString(R.string.cloud_configs_added_successfully, newGroup.nodes.size), android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (coached) {
                        CloudCoach.failed(
                            CloudCoach.diagnose(
                                emailVerified = isEmailVerified,
                                hasSubdomain = hasSubdomain,
                                installed = true,
                                rawError = "",
                            )
                        )
                    } else {
                        android.widget.Toast.makeText(context, context.getString(R.string.cloud_error_fetching_configs), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                isEdgFetching = false
            }
        }
    }

    // MLM on the phone is Config Studio, and Config Studio now runs on this account's central Worker
    // (data/studio/central): the row opens it here instead of installing the old full engine.
    val installFullMlm: () -> Unit = {
        com.mlmvpn.scanner.data.studio.central.CentralFleet.register(context, account)
        onOpenConfigStudio()
    }

    // Only the first card answers the coach; with two accounts connected, "install the panel"
    // has to mean one account, and the top one is the one the strip is sitting above.
    val coachRequest by CloudCoach.request.collectAsState()
    LaunchedEffect(coachRequest, isPrimary) {
        if (!isPrimary) return@LaunchedEffect
        when (coachRequest) {
            CloudCoach.Request.INSTALL -> {
                CloudCoach.consume()
                openPanel = CloudCoach.PANEL
                runEdgInstall(true)
            }
            CloudCoach.Request.FETCH -> {
                CloudCoach.consume()
                openPanel = CloudCoach.PANEL
                runEdgFetch(true)
            }
            else -> Unit
        }
    }
    
    LaunchedEffect(subdomainTimer) {
        if (subdomainTimer > 0) {
            kotlinx.coroutines.delay(1000)
            subdomainTimer--
            if (subdomainTimer > 0 && subdomainTimer % 60 == 0) {
                showSubdomainCheck = true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass(CardShape)
    ) {
        // The account, in the shape Settings gives the Cloudflare row: the real mark rather than
        // a grey cloud glyph in a bordered circle, the address as the title, and the state as a
        // sentence instead of an eight-character slice of the API token -- which told the user
        // nothing they could act on and put a credential fragment on screen for no reason.
        Row(
            modifier = Modifier.fillMaxWidth()
                .then(if (onToggleCollapsed != null) Modifier.clickable { onToggleCollapsed() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_app_cloudflare),
                contentDescription = null,
                modifier = Modifier.size(42.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    account.email,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(1.dp))
                if (!isEmailVerified || !hasSubdomain) {
                    // These block every panel at once, so they are still one sentence.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.height(22.dp))
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(YellowWarn))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (!isEmailVerified) S(R.string.email_not_verified) else S(R.string.subdomain_not_created),
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                } else {
                    PanelLamps(
                        lamps = listOf(
                            // A panel is «on» when it is installed as a full panel OR connected to the account's central Worker.
                            "BPB" to lampOf(if (deployState == "done" || gw.installed) "done" else "idle", cfgCount("BPB")),
                            "EDG" to lampOf(if (edgDeployState == "done" || gw.connected("EDG")) "done" else "idle", cfgCount("EDG")),
                            "NHN" to lampOf(if (nahanDeployState == "done" || gw.connected("NHN")) "done" else "idle", cfgCount("NHN")),
                            "MLM" to lampOf(if (mlmDeployState == "done" || gw.connected("MLM")) "done" else "idle", cfgCount("MLM")),
                            "NTR" to lampOf(if (netra != null || gw.connected("NTR")) "done" else "idle", cfgCount("NTR")),
                            "GZG" to lampOf(if (gozargah != null || gw.connected("GZG")) "done" else "idle", cfgCount("GZG")),
                            "NVA" to lampOf(if (nova != null || gw.connected("NVA")) "done" else "idle", cfgCount("NVA")),
                            "SPD" to lampOf(if (spiderInstall != null || gw.connected("SPD")) "done" else "idle", cfgCount("SPD")),
                        ),
                        onSelect = { picked ->
                            // BPB with no full panel on the account is the central Worker's own row.
                            val key = if (picked == "BPB" && deployState == "idle") "GW" else picked
                            openPanel = if (openPanel == key) null else key
                        },
                    )
                }
            }
            val isBusy = deployState == "deploying" || isFetching || edgDeployState == "deploying" || isEdgFetching || isCreatingSubdomain || isCheckingStatus
            // Reachable whether or not the panel is locked. The lock is only one of the ways this
            // account can fail to work, and a user whose deploys are being refused needs the same
            // screen -- what Cloudflare answered, and the levers for their own account.
            IconButton(
                onClick = onTroubleshoot,
                enabled = !isBusy
            ) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = S(R.string.cf_fix_open),
                    tint = if (!isBusy) TextMuted else BorderDark,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onDelete,
                enabled = !isBusy
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = if (!isBusy) TextMuted else BorderDark)
            }
            if (onToggleCollapsed != null) {
                val turn by animateFloatAsState(if (collapsed) 0f else 180f, label = "fold")
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (collapsed) com.mlmvpn.scanner.store.tr("باز کردن", "Expand") else com.mlmvpn.scanner.store.tr("بستن", "Collapse"),
                    tint = com.mlmvpn.scanner.ui.settings.Ios.Chevron,
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = turn },
                )
            }
        }
        // With several accounts a card can fold to its header row; nothing in it is removed.
        if (!collapsed) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 70.dp)
                .height(0.5.dp)
                .background(com.mlmvpn.scanner.ui.settings.Ios.Separator)
        )

        // Action Buttons Column
        Box(modifier = Modifier.fillMaxWidth()) {
            // The rows sit on the card itself, as an iOS grouped list does (it was a darker slab).
            Column(modifier = Modifier
                .fillMaxWidth()
                // NOTE: was Modifier.blur(8.dp). Android's hardware blur (RenderEffect)
                // crashes the native RenderThread on some GPUs — notably Samsung — with
                // "pthread_mutex_lock called on a destroyed mutex" (SIGABRT) when the
                // blurred surface is torn down as this panel toggles on Cloudflare
                // account/subdomain state changes. A plain alpha dim gives the same
                // "locked/disabled" cue with zero native-blur risk.
                .then(if (!isEmailVerified || !hasSubdomain) Modifier.alpha(0.35f) else Modifier)
            ) {
            // ── the account's ONE central Worker (BPB's row) and a row for every panel that is not installed as a full panel ──
            com.mlmvpn.scanner.ui.cloud.GatewayBlock(
                account = account,
                groupManager = groupManager,
                cfgCount = cfgCount,
                legacy = legacyPanels,
                openPanel = openPanel,
                setOpenPanel = { openPanel = it },
                onShowSettings = { onShowGatewaySettings(account) },
                onGroupsUpdated = onGroupsUpdated,
                onInstallFullMlm = installFullMlm,
            )
            // ── full panels, only the ones that are installed ──
            if (deployState != "idle" || legacyPanels.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
            }
            if (deployState != "idle") {
            CloudPanelHeaderRow(
                title = "BPB",
                subtitle = S(R.string.full_panel_with_more_settings),
                state = deployState,
                configCount = cfgCount("BPB"),
                expanded = openPanel == "BPB",
                stale = com.mlmvpn.scanner.data.PanelBuild.isStale(account, "BPB"),
                onClick = { openPanel = if (openPanel == "BPB") null else "BPB" },
            )
            if (openPanel == "BPB") {
            Column(modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.035f))) {
                val progressWidth by animateFloatAsState(targetValue = progress / 100f, animationSpec = tween(150))
                // Deploy Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                        .clickable(enabled = deployState != "deploying") {
                            if (deployState == "done") return@clickable
                            deployState = "deploying"
                            progress = 0f
                            scope.launch {
                                val result = cloudManager.deployWorker(account) { percent, _ ->
                                    progress = percent.toFloat()
                                }
                                if (result.first) {
                                    deployState = "done"
                                    account.status = "DEPLOYED"
                                    cloudManager.saveAccounts()
                                } else {
                                    deployState = "idle"
                                    if (result.second == "ERR_ACCOUNT_HAS_SUBDOMAIN") {
                                        showSubdomainError = true
                                    } else {
                                        android.widget.Toast.makeText(context, result.second, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                ) {
                    // Progress Bar Background
                    
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val color = if (deployState == "done") GreenOk else TextPrimary
                        Icon(if (deployState == "done") Icons.Default.CheckCircle else Icons.Default.PlayArrow, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (deployState == "deploying") stringResource(R.string.cloud_deploying) else if (deployState == "done") stringResource(R.string.cloud_deployed) else stringResource(R.string.cloud_deploy_bpb), color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                // The deploy really does report a percentage; this is that number, at the
                // width it has actually reached, under the row it belongs to.
                if (deployState == "deploying") {
                    LinearProgressIndicator(
                        progress = progressWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 16.dp, bottom = 10.dp)
                            .height(3.dp)
                            .clip(CircleShape),
                        color = com.mlmvpn.scanner.ui.settings.Ios.Blue,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
                
                // Get Nodes Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = deployState == "done" && !isFetching,
                            onClick = {
                                if (deployState == "done" && !isFetching) {
                                    scope.launch {
                                        isFetching = true
                                        android.widget.Toast.makeText(context, context.getString(R.string.cloud_fetching_current_settings), android.widget.Toast.LENGTH_SHORT).show()
                                        val currentSettings = cloudManager.fetchWorkerSettings(account)
                                        if (currentSettings != null) {
                                            onShowBpbSettings(currentSettings, account)
                                        } else {
                                            onShowBpbSettings(null, account)
                                        }
                                        isFetching = false
                                    }
                                }
                            }
                        ),
                ) {
                    if (isFetching) {
                        val infiniteTransition = rememberInfiniteTransition(label = "fetchBg")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0f, targetValue = 0.3f,
                            animationSpec = infiniteRepeatable(animation = tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
                            label = "fetchAlpha"
                        )
                        Box(modifier = Modifier.fillMaxSize().background(Primary.copy(alpha = alpha)))
                    }
                    val color = if (deployState == "done") TextPrimary else TextDim
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isFetching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (isFetching) stringResource(R.string.cloud_fetching) else stringResource(R.string.cloud_fetch_bpb_node), color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                // Deployed panels can be replaced or taken down; see PanelBuild.
                if (deployState == "done") {
                    CloudPanelManageRows(
                        stale = com.mlmvpn.scanner.data.PanelBuild.isStale(account, "BPB"),
                        busy = deployState == "deploying",
                        removing = removingPanel == "BPB",
                        onRedeploy = { redeployPanel("BPB") },
                        onRemove = { confirmRemove = "BPB" },
                    )
                }
            }
            }

            Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
            }

            if (edgDeployState != "idle") {
            CloudPanelHeaderRow(
                title = "EDG",
                subtitle = S(R.string.the_quickest_way_to_get_a_config),
                state = edgDeployState,
                configCount = cfgCount("EDG"),
                expanded = openPanel == "EDG",
                stale = com.mlmvpn.scanner.data.PanelBuild.isStale(account, "EDG"),
                onClick = { openPanel = if (openPanel == "EDG") null else "EDG" },
            )
            if (openPanel == "EDG") {
            Column(modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.035f))) {
                val edgProgressWidth by animateFloatAsState(targetValue = edgProgress / 100f, animationSpec = tween(150))
                // EDG Deploy Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                        .clickable(enabled = edgDeployState != "deploying") { runEdgInstall(false) }
                ) {
                    
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val color = if (edgDeployState == "done") GreenOk else TextPrimary
                        Icon(if (edgDeployState == "done") Icons.Default.CheckCircle else Icons.Default.PlayArrow, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (edgDeployState == "deploying") stringResource(R.string.cloud_deploying) else if (edgDeployState == "done") stringResource(R.string.cloud_deployed) else stringResource(R.string.cloud_deploy_edg), color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                // The deploy really does report a percentage; this is that number, at the
                // width it has actually reached, under the row it belongs to.
                if (edgDeployState == "deploying") {
                    LinearProgressIndicator(
                        progress = edgProgressWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 16.dp, bottom = 10.dp)
                            .height(3.dp)
                            .clip(CircleShape),
                        color = com.mlmvpn.scanner.ui.settings.Ios.Blue,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }
                
                Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
                
                // EDG Get Nodes Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = edgDeployState == "done" && !isEdgFetching) { runEdgFetch(false) }
                ) {
                    if (isEdgFetching) {
                        val infiniteTransition = rememberInfiniteTransition(label = "fetchBgEDG")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0f, targetValue = 0.3f,
                            animationSpec = infiniteRepeatable(animation = tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
                            label = "fetchAlphaEDG"
                        )
                        Box(modifier = Modifier.fillMaxSize().background(Primary.copy(alpha = alpha)))
                    }
                    val color = if (edgDeployState == "done") TextPrimary else TextDim
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isEdgFetching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (isEdgFetching) stringResource(R.string.cloud_fetching) else stringResource(R.string.cloud_fetch_edg_node), color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                
                Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
                
                // EDG Settings Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = edgDeployState == "done") {
                            if (edgDeployState == "done") {
                                onShowEdgSettings(account)
                            }
                        },
                ) {
                    val color = if (edgDeployState == "done") TextPrimary else TextDim
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.cloud_edg_settings), color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                // Deployed panels can be replaced or taken down; see PanelBuild.
                if (edgDeployState == "done") {
                    CloudPanelManageRows(
                        stale = com.mlmvpn.scanner.data.PanelBuild.isStale(account, "EDG"),
                        busy = edgDeployState == "deploying",
                        removing = removingPanel == "EDG",
                        onRedeploy = { redeployPanel("EDG") },
                        onRemove = { confirmRemove = "EDG" },
                    )
                }
            }
            }
            
            Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
            }

            if (nahanDeployState != "idle") {
            // --- ROW 4: Nahan Engine ---
            CloudPanelHeaderRow(
                title = "Nahan",
                subtitle = S(R.string.with_a_username_and_password),
                state = nahanDeployState,
                configCount = cfgCount("NHN"),
                expanded = openPanel == "NHN",
                stale = com.mlmvpn.scanner.data.PanelBuild.isStale(account, "NHN"),
                onClick = { openPanel = if (openPanel == "NHN") null else "NHN" },
            )
            if (openPanel == "NHN") {
            Column(modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.035f))) {
                val progressWidth by animateFloatAsState(targetValue = nahanProgress / 100f, animationSpec = tween(150))
                // Nahan Deploy Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = nahanDeployState != "deploying") {
                            if (nahanDeployState == "done") return@clickable
                            nahanDeployState = "deploying"
                            nahanProgress = 0f
                            scope.launch {
                                val nahanDeployer = com.mlmvpn.scanner.engines.nahan.NahanDeployer(context)
                                val result = nahanDeployer.deployNahan(account) { percent, _ ->
                                    nahanProgress = percent.toFloat()
                                }
                                if (result.first) {
                                    nahanDeployState = "done"
                                    cloudManager.saveAccounts()
                                } else {
                                    nahanDeployState = "idle"
                                    if (result.second == "ERR_ACCOUNT_HAS_SUBDOMAIN") {
                                        showSubdomainError = true
                                    } else {
                                        android.widget.Toast.makeText(context, result.second, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                ) {
                    
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val color = if (nahanDeployState == "done") GreenOk else TextPrimary
                        Icon(if (nahanDeployState == "done") Icons.Default.CheckCircle else Icons.Default.PlayArrow, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (nahanDeployState == "deploying") stringResource(R.string.cloud_deploying_short) else if (nahanDeployState == "done") stringResource(R.string.cloud_deployed) else stringResource(R.string.cloud_deploy_nhn), color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                // The deploy really does report a percentage; this is that number, at the
                // width it has actually reached, under the row it belongs to.
                if (nahanDeployState == "deploying") {
                    LinearProgressIndicator(
                        progress = progressWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 16.dp, bottom = 10.dp)
                            .height(3.dp)
                            .clip(CircleShape),
                        color = com.mlmvpn.scanner.ui.settings.Ios.Blue,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }
                
                Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
                
                // Nahan Get Nodes Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = nahanDeployState == "done" && !isNahanFetching) {
                            if (nahanDeployState == "done" && !isNahanFetching) {
                                scope.launch {
                                    isNahanFetching = true
                                    android.widget.Toast.makeText(context, context.getString(R.string.cloud_fetching_admin_configs), android.widget.Toast.LENGTH_SHORT).show()
                                    val nahanDeployer = com.mlmvpn.scanner.engines.nahan.NahanDeployer(context)
                                    try {
                                        if (account.nahanWorkerUrl.isNullOrEmpty() || !account.nahanWorkerUrl!!.startsWith("http")) {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, context.getString(R.string.cloud_invalid_worker_address), android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            isNahanFetching = false
                                            return@launch
                                        }
                                        
                                        // Fetch default admin configs (no ?sub= param)
                                        val baseUrl = account.nahanWorkerUrl?.trimEnd('/') ?: ""
                                        val apiRoute = (account.nahanApiRoute ?: "sync").trim('/')
                                        val subUrl = "$baseUrl/$apiRoute?flag=a"
                                        val configs = mutableListOf<String>()
                                        
                                        var isSuccess = false
                                        var errorMessage = ""
                                        
                                        val fetchedConfigs = nahanDeployer.fetchNahanNodes(account)
                                        if (fetchedConfigs.isNotEmpty()) {
                                            configs.addAll(fetchedConfigs)
                                            isSuccess = true
                                        } else {
                                            errorMessage = context.getString(R.string.cloud_no_configs_received)
                                        }
                                        
                                        if (!isSuccess && errorMessage.isNotEmpty()) {
                                            android.widget.Toast.makeText(context, errorMessage, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                        
                                        if (configs.isNotEmpty()) {
                                            val engineNodes = configs.mapIndexed { index, uri ->
                                                com.mlmvpn.scanner.models.VpnNode(
                                                    id = "nhn_${account.id}_admin_$index",
                                                    name = java.net.URLDecoder.decode(uri.substringAfterLast("#", "NHN Node"), "UTF-8"),
                                                    uri = uri,
                                                    type = if (uri.startsWith("trojan://")) "trojan" else "vless",
                                                    engineType = "NHN"
                                                )
                                            }
                                            val newGroup = com.mlmvpn.scanner.data.CloudGroup(
                                                id = "nhn_admin_${account.id}_${System.currentTimeMillis()}",
                                                accountId = account.id,
                                                date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                                title = com.mlmvpn.scanner.utils.NamingHelper.generateMythName(),
                                                nodes = engineNodes
                                            )
                                            groupManager.cloudGroups.add(0, newGroup)
                                    com.mlmvpn.scanner.ui.CombineCoach.offer(context, newGroup.id, newGroup.nodes.firstOrNull()?.uri ?: "")
                                            groupManager.saveCloudGroups()
                                            android.widget.Toast.makeText(context, context.getString(R.string.cloud_admin_configs_received, engineNodes.size), android.widget.Toast.LENGTH_SHORT).show()
                                            onGroupsUpdated()
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, context.getString(R.string.cloud_error, e.toString()), android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    isNahanFetching = false
                                }
                            }
                        },
                ) {
                    if (isNahanFetching) {
                        val infiniteTransition = rememberInfiniteTransition(label = "fetchBgNHN")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0f, targetValue = 0.3f,
                            animationSpec = infiniteRepeatable(animation = tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
                            label = "fetchAlphaNHN"
                        )
                        Box(modifier = Modifier.fillMaxSize().background(Primary.copy(alpha = alpha)))
                    }
                    val color = if (nahanDeployState == "done") TextPrimary else TextDim
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isNahanFetching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (isNahanFetching) stringResource(R.string.cloud_fetching_short) else stringResource(R.string.cloud_nhn_node), color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
                
                // Nahan Settings Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = nahanDeployState == "done") {
                            if (nahanDeployState == "done") {
                                onShowNahanSettings(account)
                            }
                        },
                ) {
                    val color = if (nahanDeployState == "done") TextPrimary else TextDim
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.cloud_settings), color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                // Deployed panels can be replaced or taken down; see PanelBuild.
                if (nahanDeployState == "done") {
                    CloudPanelManageRows(
                        stale = com.mlmvpn.scanner.data.PanelBuild.isStale(account, "NHN"),
                        busy = nahanDeployState == "deploying",
                        removing = removingPanel == "NHN",
                        onRedeploy = { redeployPanel("NHN") },
                        onRemove = { confirmRemove = "NHN" },
                    )
                }
            } // end Nahan Engine
            }

            Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
            }

            if (spiderInstall != null) {
            // --- Spider: get configs here like the others; install, users and exits on its page ---
            CloudPanelHeaderRow(
                title = "Spider",
                subtitle = com.mlmvpn.scanner.store.tr("خروجی مسابقه‌ای و محدودیت IP", "Racing exits and IP limits"),
                state = if (spiderInstall != null) "done" else "idle",
                configCount = cfgCount("SPD"),
                expanded = openPanel == "SPD",
                onClick = { openPanel = if (openPanel == "SPD") null else "SPD" },
            )
            if (openPanel == "SPD") {
                com.mlmvpn.scanner.ui.spider.SpiderCloudRows(
                    account = account,
                    groupManager = groupManager,
                    onOpen = { onShowSpider(account) },
                    onGroupsUpdated = onGroupsUpdated,
                )
            }
            }

            // --- Netra and Gozargah: third-party panels, deployed from their developers' releases ---
            if (netra != null) {
            Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
            CloudPanelHeaderRow(
                title = "Netra",
                subtitle = com.mlmvpn.scanner.store.tr("پنل ساده، بر پایهٔ BPB", "A simple panel built on BPB"),
                state = if (netra != null) "done" else "idle",
                configCount = cfgCount("NTR"),
                expanded = openPanel == "NTR",
                onClick = { openPanel = if (openPanel == "NTR") null else "NTR" },
            )
            if (openPanel == "NTR") {
                com.mlmvpn.scanner.ui.cloud.PanelRows(
                    account = account, groupManager = groupManager, engine = "NTR", label = "Netra",
                    installed = netra != null,
                    install = { step -> com.mlmvpn.scanner.engines.netra.NetraPanel.deploy(context, account, step) },
                    links = {
                        val i = com.mlmvpn.scanner.engines.netra.NetraPanel.install(context, account.accountId) ?: error("not installed")
                        com.mlmvpn.scanner.engines.netra.NetraPanel.configs(context, i).map { com.mlmvpn.scanner.ui.cloud.PanelConfigs.nameOf(it) to it }
                    },
                    remove = { com.mlmvpn.scanner.engines.netra.NetraPanel.remove(context, account) },
                    onGroupsUpdated = onGroupsUpdated,
                    onChanged = { thirdPartyTick++ },
                    extra = netra?.let { n -> { com.mlmvpn.scanner.ui.cloud.OpenPanelRow("${n.url}/${n.subPath}/panel") } },
                )
            }
            }

            if (gozargah != null) {
            Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
            CloudPanelHeaderRow(
                title = "Gozargah",
                subtitle = com.mlmvpn.scanner.store.tr("چندکاربره با دیتابیس D1", "Multi-user, on a D1 database"),
                state = if (gozargah != null) "done" else "idle",
                configCount = cfgCount("GZG"),
                expanded = openPanel == "GZG",
                onClick = { openPanel = if (openPanel == "GZG") null else "GZG" },
            )
            if (openPanel == "GZG") {
                com.mlmvpn.scanner.ui.cloud.PanelRows(
                    account = account, groupManager = groupManager, engine = "GZG", label = "Gozargah",
                    installed = gozargah != null,
                    install = { step -> com.mlmvpn.scanner.engines.gozargah.GozargahPanel.deploy(context, account, step) },
                    links = {
                        val i = com.mlmvpn.scanner.engines.gozargah.GozargahPanel.install(context, account.accountId) ?: error("not installed")
                        com.mlmvpn.scanner.engines.gozargah.GozargahPanel.configs(context, i).map { com.mlmvpn.scanner.ui.cloud.PanelConfigs.nameOf(it) to it }
                    },
                    remove = { com.mlmvpn.scanner.engines.gozargah.GozargahPanel.remove(context, account) },
                    onGroupsUpdated = onGroupsUpdated,
                    onChanged = { thirdPartyTick++ },
                    extra = gozargah?.let { g -> {
                        // The password the app set is the one way in; copied as the page opens.
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        com.mlmvpn.scanner.ui.cloud.OpenPanelRow("${g.url}/${g.panelPath}",
                            note = com.mlmvpn.scanner.store.tr("رمز پنل کپی شد.", "The panel password was copied."),
                            beforeOpen = { cm.setPrimaryClip(android.content.ClipData.newPlainText("gozargah", g.password)) })
                    } },
                )
            }
            }

            // --- Nova: fetched from the developer's repository at install, checked against its SHA-256 ---
            if (nova != null) {
            Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
            CloudPanelHeaderRow(
                title = "Nova",
                subtitle = com.mlmvpn.scanner.store.tr("پنل کامل با آی‌پی تمیز و ربات تلگرام", "A full panel with clean IPs and a Telegram bot"),
                state = if (nova != null) "done" else "idle",
                configCount = cfgCount("NVA"),
                expanded = openPanel == "NVA",
                onClick = { openPanel = if (openPanel == "NVA") null else "NVA" },
            )
            if (openPanel == "NVA") {
                com.mlmvpn.scanner.ui.cloud.PanelRows(
                    account = account, groupManager = groupManager, engine = "NVA", label = "Nova",
                    installed = nova != null,
                    install = { step -> com.mlmvpn.scanner.engines.nova.NovaPanel.deploy(context, account, step) },
                    links = {
                        val i = com.mlmvpn.scanner.engines.nova.NovaPanel.install(context, account.accountId) ?: error("not installed")
                        com.mlmvpn.scanner.engines.nova.NovaPanel.configs(context, i).map { com.mlmvpn.scanner.ui.cloud.PanelConfigs.nameOf(it) to it }
                    },
                    remove = { com.mlmvpn.scanner.engines.nova.NovaPanel.remove(context, account) },
                    onGroupsUpdated = onGroupsUpdated,
                    onChanged = { thirdPartyTick++ },
                    extra = nova?.let { n -> {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        com.mlmvpn.scanner.ui.cloud.OpenPanelRow("${n.url}/admin",
                            note = com.mlmvpn.scanner.store.tr("رمز پنل کپی شد.", "The panel password was copied."),
                            beforeOpen = { cm.setPrimaryClip(android.content.ClipData.newPlainText("nova", n.password)) })
                    } },
                )
            }
            }

            if (mlmDeployState != "idle") {
            Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
            
            // --- ROW 5: MLM Engine ---
            CloudPanelHeaderRow(
                title = "MLM",
                // The same engine as Config Studio's; said, so the two are not taken for two panels.
                subtitle = if (account.isStudioManaged) S(R.string.cloud_mlm_studio_subtitle)
                else S(R.string.our_own_panel_with_user_management),
                state = mlmDeployState,
                configCount = cfgCount("MLM"),
                expanded = openPanel == "MLM",
                stale = com.mlmvpn.scanner.data.PanelBuild.isStale(account, "MLM"),
                onClick = { openPanel = if (openPanel == "MLM") null else "MLM" },
            )
            if (openPanel == "MLM") {
            Column(modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.035f))) {
                val progressWidth by animateFloatAsState(targetValue = mlmProgress / 100f, animationSpec = tween(150))
                // MLM Deploy Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = mlmDeployState != "deploying") {
                            if (mlmDeployState == "done") return@clickable
                            mlmDeployState = "deploying"
                            mlmProgress = 0f
                            scope.launch {
                                val deployer = com.mlmvpn.scanner.engines.mlm.MlmDeployer(context)
                                val result = deployer.deployMlm(account) { percent, _ ->
                                    mlmProgress = percent.toFloat()
                                }
                                if (result.first) {
                                    mlmDeployState = "done"
                                    account.mlmStatus = "DEPLOYED"
                                    cloudManager.saveAccounts()
                                } else {
                                    mlmDeployState = "idle"
                                    android.widget.Toast.makeText(context, result.second, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                ) {
                    
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val color = if (mlmDeployState == "done") GreenOk else TextPrimary
                        Icon(if (mlmDeployState == "done") Icons.Default.CheckCircle else Icons.Default.PlayArrow, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (mlmDeployState == "deploying") S(R.string.installing_2) else if (mlmDeployState == "done") S(R.string.installed) else S(R.string.install_on_account), color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                // The deploy really does report a percentage; this is that number, at the
                // width it has actually reached, under the row it belongs to.
                if (mlmDeployState == "deploying") {
                    LinearProgressIndicator(
                        progress = progressWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 16.dp, bottom = 10.dp)
                            .height(3.dp)
                            .clip(CircleShape),
                        color = com.mlmvpn.scanner.ui.settings.Ios.Blue,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }
                
                Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))

                // «دریافت کانفیگ xhttp»: the panel's own XHTTP config (packet-up over HTTP/2), saved only
                // after it has carried real data on this network. Needs the panel at build 23, where a
                // session's requests meet in its Durable Object (MlmXhttp, 03-entry.js).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = mlmDeployState == "done" && !mlmXhttpBusy) {
                            mlmXhttpBusy = true
                            scope.launch {
                                val outcome = try {
                                    com.mlmvpn.scanner.engines.mlm.MlmXhttp.obtain(context, account)
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    com.mlmvpn.scanner.engines.mlm.MlmXhttp.Outcome.Failed(com.mlmvpn.scanner.engines.mlm.MlmXhttp.Reason.PANEL_ERROR)
                                } finally { mlmXhttpBusy = false }
                                val message = when (outcome) {
                                    is com.mlmvpn.scanner.engines.mlm.MlmXhttp.Outcome.Ready -> {
                                        val filed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            runCatching {
                                                com.mlmvpn.scanner.data.NodeManager(context).addConfigs(listOf(outcome.link), groupTitle = "MLM XHTTP")
                                                com.mlmvpn.scanner.ui.cloud.PanelConfigs.file(context, groupManager, account, "MLM", "XHTTP",
                                                    listOf(com.mlmvpn.scanner.ui.cloud.PanelConfigs.nameOf(outcome.link) to outcome.link))
                                            }.isSuccess
                                        }
                                        onGroupsUpdated()
                                        if (filed) S(R.string.mlm_xhttp_ready) else S(R.string.mlm_xhttp_failed)
                                    }
                                    com.mlmvpn.scanner.engines.mlm.MlmXhttp.Outcome.Outdated -> S(R.string.mlm_xhttp_outdated)
                                    is com.mlmvpn.scanner.engines.mlm.MlmXhttp.Outcome.Failed -> when (outcome.reason) {
                                        com.mlmvpn.scanner.engines.mlm.MlmXhttp.Reason.NOT_WORKING -> S(R.string.mlm_xhttp_not_working)
                                        com.mlmvpn.scanner.engines.mlm.MlmXhttp.Reason.NO_PANEL_CONFIG -> S(R.string.mlm_xhttp_no_config)
                                        com.mlmvpn.scanner.engines.mlm.MlmXhttp.Reason.PANEL_ERROR -> S(R.string.mlm_xhttp_failed)
                                    }
                                }
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                ) {
                    val color = if (mlmDeployState == "done") TextPrimary else TextDim
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.SwapVert, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(S(R.string.mlm_get_xhttp), color = color, fontSize = 15.sp)
                            if (mlmXhttpBusy) Text(S(R.string.mlm_xhttp_checking), color = TextDim, fontSize = 12.sp)
                        }
                        if (mlmXhttpBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = com.mlmvpn.scanner.ui.settings.Ios.Blue, strokeWidth = 2.dp)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
                
                // MLM Users Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = mlmDeployState == "done") {
                            if (mlmDeployState == "done") {
                                // Config Studio's own list on an account it manages: the old one
                                // cannot show those people's links, and its edits and deletes do
                                // not mean what Config Studio's do.
                                if (account.isStudioManaged) {
                                    com.mlmvpn.scanner.ui.configstudio.StudioLaunch.openUsers = true
                                    onOpenConfigStudio()
                                } else {
                                    onShowMlmUsers(account)
                                }
                            }
                        },
                ) {
                    val color = if (mlmDeployState == "done") TextPrimary else TextDim
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Group, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            if (account.isStudioManaged) S(R.string.cloud_mlm_users_in_studio) else S(R.string.users),
                            color = color, fontSize = 15.sp, modifier = Modifier.weight(1f),
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(com.mlmvpn.scanner.ui.settings.Ios.Separator))
                
                // MLM Settings Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = mlmDeployState == "done") {
                            if (mlmDeployState == "done") {
                                onShowMlmSettings(account)
                            }
                        },
                ) {
                    val color = if (mlmDeployState == "done") TextPrimary else TextDim
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(S(R.string.settings), color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                // Deployed panels can be replaced or taken down; see PanelBuild.
                if (mlmDeployState == "done") {
                    CloudPanelManageRows(
                        stale = com.mlmvpn.scanner.data.PanelBuild.isStale(account, "MLM"),
                        busy = mlmDeployState == "deploying",
                        removing = removingPanel == "MLM",
                        onRedeploy = { redeployPanel("MLM") },
                        onRemove = { confirmRemove = "MLM" },
                    )
                }
            } // end MLM Engine
            }
            }

            // Smart Verification Overlay
            //
            // The panel is unusable until Cloudflare will accept a worker, and there are two
            // reasons it will not: the account's email is unverified, or it has no workers.dev
            // subdomain yet. Which one is shown matters -- the first sends the user to an external
            // dashboard, the second is one button here -- and the app used to get it wrong. The
            // probe behind `isEmailVerified` asked an endpoint that has no email-verification
            // field at all, so a verified account with no subdomain was shown the email overlay
            // and had no way past it. That inference is gone (see CloudManager.probeAccountStatus)
            // and the report button below is what tells us whether anything still lands a working
            // account here.
            Box(modifier = Modifier.fillMaxSize()) {
                if (!isEmailVerified || !hasSubdomain) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                        // Consume clicks
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { }
                        )

                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isEmailVerified) {
                            Button(
                                onClick = { showEmailModal = true },
                                colors = iosButtonColors(Color(0xFFF59E0B)),
                                border = iosButtonBorder(Color(0xFFF59E0B))) {
                                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.cloud_verify_email_required),)
                            }
                        } else if (!hasSubdomain) {
                            Button(
                                onClick = {
                                    isCreatingSubdomain = true
                                    scope.launch {
                                        val res = cloudManager.createSubdomain(account)
                                        isCreatingSubdomain = false
                                        if (res.ok) {
                                            account.hasSubdomain = true
                                            hasSubdomain = true
                                            cloudManager.saveAccounts()
                                            subdomainTimer = 180
                                        } else if (res.failure == com.mlmvpn.scanner.data.CloudManager.SubdomainFailure.EMAIL_UNVERIFIED) {
                                            // Cloudflare itself named the email as the reason for
                                            // refusing the one operation that requires it. That is
                                            // the only evidence in this app allowed to say so. The
                                            // old rule guessed it from the word "verify" appearing
                                            // anywhere in the error -- which also matches
                                            // permission and rate-limit messages -- and then
                                            // confirmed the guess against an endpoint that always
                                            // answered no.
                                            isEmailVerified = false
                                            account.isEmailVerified = false
                                            cloudManager.saveAccounts()
                                            android.widget.Toast.makeText(context, context.getString(R.string.cloud_error_email_not_verified), android.widget.Toast.LENGTH_LONG).show()
                                        } else {
                                            subdomainCreationError = res.errors.ifBlank { res.message }
                                        }
                                    }
                                },
                                colors = iosButtonColors(Color(0xFF3B82F6)),
                                border = iosButtonBorder(Color(0xFF3B82F6), enabled = !isCreatingSubdomain && subdomainTimer == 0),
                                enabled = !isCreatingSubdomain && subdomainTimer == 0
                            ) {
                                if (isCreatingSubdomain) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp),)
                                } else if (subdomainTimer > 0) {
                                    Text(stringResource(R.string.cloud_wait_timer, subdomainTimer / 60, subdomainTimer % 60))
                                } else {
                                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.cloud_create_subdomain_required),)
                                }
                            }

                            if (showSubdomainCheck) {
                                Button(
                                    onClick = {
                                        isCheckingStatus = true
                                        scope.launch {
                                            val status = cloudManager.checkAccountStatus(account)
                                            isCheckingStatus = false
                                            if (status.first) {
                                                account.hasSubdomain = true
                                                hasSubdomain = true
                                                cloudManager.saveAccounts()
                                                subdomainTimer = 0
                                            } else {
                                                android.widget.Toast.makeText(context, context.getString(R.string.cloud_subdomain_not_issued_yet), android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    colors = iosButtonColors(Primary),
                                    border = iosButtonBorder(Primary, enabled = !isCheckingStatus),
                                    enabled = !isCheckingStatus
                                ) {
                                    if (isCheckingStatus) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp),)
                                    } else {
                                        Text(stringResource(R.string.cloud_check_subdomain_status),)
                                    }
                                }
                            }

                            // Cloudflare's own words, not ours. A create can fail for a scope the
                            // token was never granted, for a name already taken, or because the
                            // request never left the phone, and "it did not work" points the user
                            // at the wrong fix for all three.
                            if (subdomainCreationError.isNotBlank()) {
                                Text(
                                    stringResource(R.string.cloud_subdomain_failed_reason, subdomainCreationError),
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.widthIn(max = 300.dp),
                                )
                            }
                        }

                        // Always reachable while the panel is locked, because this is exactly the
                        // population it is for: whoever is looking at this overlay is either
                        // genuinely blocked or blocked by us, and only what Cloudflare actually
                        // answered can tell those apart. Opening it re-probes, so an account that
                        // is in fact fine is unlocked by the same tap.
                        TextButton(onClick = onTroubleshoot) {
                            Text(
                                stringResource(R.string.cf_fix_open),
                                color = TextMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                }
            }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 70.dp)
                .height(0.5.dp)
                .background(com.mlmvpn.scanner.ui.settings.Ios.Separator)
        )
        // --- Daily quota -------------------------------------------------------------------
        //
        // This number is not "traffic". It is `workersInvocationsAdaptive.sum.requests` for the
        // whole Cloudflare account since 00:00 UTC -- every request served by every panel on it --
        // and the denominator is the free plan's 100,000/day. It used to be a bare `12400 / 100,000`
        // behind a row labelled "مصرف روزانه": the one number on this screen whose meaning cannot
        // be guessed, and the one that silently ends the user's day when it runs out.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 70.dp)
                .height(0.5.dp)
                .background(com.mlmvpn.scanner.ui.settings.Ios.Separator)
        )
        val quotaFraction = (usageData / 100_000f).coerceIn(0f, 1f)
        val quotaTone = when {
            quotaFraction >= 0.9f -> com.mlmvpn.scanner.ui.settings.Ios.Red
            quotaFraction >= 0.7f -> YellowWarn
            else -> GreenOk
        }
        val loadUsage: () -> Unit = {
            isLoadingUsage = true
            scope.launch {
                val result = cloudManager.getUsage(account)
                isLoadingUsage = false
                if (result.first) {
                    usageData = result.second.toFloatOrNull() ?: 0f
                } else {
                    android.widget.Toast.makeText(context, result.second, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    showUsage = !showUsage
                    if (showUsage && usageData == 0f) loadUsage()
                }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.09f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.DataUsage,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.cloud_daily_usage),
                    color = TextPrimary,
                    fontSize = 15.sp,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    S(R.string.cloudflare_s_free_allowance_for_this_whole),
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }
            when {
                isLoadingUsage -> CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    color = TextMuted,
                    strokeWidth = 2.dp,
                )
                usageData > 0f -> Text(
                    S(R.string.str) + faCount((quotaFraction * 100).toInt()),
                    color = quotaTone,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                else -> Text(S(R.string.show), color = TextMuted, fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                if (showUsage) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = com.mlmvpn.scanner.ui.settings.Ios.Chevron,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(visible = showUsage) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 57.dp, end = 16.dp, bottom = 14.dp)) {
                val usagePercent by animateFloatAsState(
                    targetValue = quotaFraction,
                    animationSpec = tween(700),
                )
                LinearProgressIndicator(
                    progress = usagePercent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = quotaTone,
                    trackColor = Color.White.copy(alpha = 0.10f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        faGrouped(usageData.toInt()) + S(R.string.of) + faGrouped(100_000),
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        faGrouped((100_000 - usageData.toInt()).coerceAtLeast(0)) + S(R.string.left),
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    S(R.string.this_number_is_not_data_usage_it) +
                        S(R.string.cloudflare_today_each_time_you_connect_to) +
                        S(R.string.request_and_nothing_is_added_while_you) +
                        S(R.string.every_disconnect_and_reconnect_and_every_switch),
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    S(R.string.the_free_allowance_is_100_000_requests) +
                        S(R.string.together_not_each_on_its_own_it) +
                        S(R.string.this_account_s_configs_will_not_connect) +
                        S(R.string.cloudflare_account_and_spread_the_load_across),
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                )
                Spacer(modifier = Modifier.height(11.dp))
                Row(
                    modifier = Modifier
                        .clip(com.mlmvpn.scanner.ui.theme.BadgeShape)
                        .clickable(enabled = !isLoadingUsage) { loadUsage() }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(S(R.string.refresh), color = TextPrimary, fontSize = 13.sp)
                }
            }
        }


        // The groups this account received are listed in the Cloud tab's «کانفیگ‌های دریافتی»
        // module (CloudReceivedModule), so a folded card does not hide them.

    }
        } // collapsed
    } // Closes the main Column

    // Deleting a worker cannot be undone from here, and every config already handed out from that
    // panel stops working the moment it goes -- so it is said plainly before it happens.
    confirmRemove?.let { key ->
        val panelName = when (key) {
            "BPB" -> "BPB"
            "EDG" -> "EDG"
            "NHN" -> "Nahan"
            else -> "MLM"
        }
        com.mlmvpn.scanner.ui.settings.IosAlert(
            title = S(R.string.delete_the_panelname_panel, panelName),
            // The MLM panel of a Config Studio account IS Config Studio's engine there.
            message = if (key == "MLM" && account.isStudioManaged) S(R.string.cloud_mlm_remove_studio_body)
            else S(R.string.this_panel_s_worker_will_be_removed) +
                S(R.string.this_panel_will_stop_working_you_can),
            onDismiss = { confirmRemove = null },
            actions = listOf(
                com.mlmvpn.scanner.ui.settings.IosAlertAction(S(R.string.cancel), onClick = { confirmRemove = null }),
                com.mlmvpn.scanner.ui.settings.IosAlertAction(
                    S(R.string.delete_2),
                    onClick = { removePanel(key); confirmRemove = null },
                    destructive = true,
                ),
            ),
        )
    }

    if (showSubdomainError) {
        AlertDialog(
            onDismissRequest = { showSubdomainError = false },
            containerColor = DialogSurface,
            modifier = androidx.compose.ui.Modifier.border(
                0.7.dp,
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f),
                CardShape,
            ),
                shape = CardShape,
            title = { Text(stringResource(R.string.cloud_manual_change_required), color = TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.cloud_subdomain_duplicate_error),
                    color = TextMuted, fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val url = "https://dash.cloudflare.com/?to=/:account/workers/overview"
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        context.startActivity(intent)
                        showSubdomainError = false
                    },
                    colors = iosButtonColors(Primary),
                    border = iosButtonBorder(Primary)) {
                    Text(stringResource(R.string.cloud_login_to_cloudflare),)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubdomainError = false }) {
                    Text(stringResource(R.string.cloud_close), color = TextMuted)
                }
            }
        )
    }

    if (subdomainCreationError.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { subdomainCreationError = "" },
            containerColor = DialogSurface,
            modifier = androidx.compose.ui.Modifier.border(
                0.7.dp,
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f),
                CardShape,
            ),
                shape = CardShape,
            title = { Text(stringResource(R.string.cloud_error_creating_subdomain), color = TextPrimary) },
            text = {
                val errorMsg = subdomainCreationError
                val displayMsg = when {
                    errorMsg.contains("10009") || errorMsg.contains("terms", ignoreCase = true) -> 
                        stringResource(R.string.cloud_cloudflare_rules_not_accepted) +
                        stringResource(R.string.cloud_solution_accept_rules)
                    errorMsg.contains("10000") || errorMsg.contains("Authentication") ->
                        stringResource(R.string.cloud_access_error_10000) +
                        stringResource(R.string.cloud_solution_api_token_permissions) +
                        "- Account -> Workers Scripts -> Edit\n\n" +
                        stringResource(R.string.cloud_or_use_global_api_key)
                    errorMsg.contains("10014") || errorMsg.contains("taken") ->
                        stringResource(R.string.cloud_subdomain_taken)
                    else -> stringResource(R.string.cloud_unknown_cloudflare_error, errorMsg)
                }
                Text(displayMsg, color = TextMuted, fontSize = 14.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        val url = "https://dash.cloudflare.com/?to=/:account/workers/overview"
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        context.startActivity(intent)
                        subdomainCreationError = ""
                    },
                    colors = iosButtonColors(Primary),
                    border = iosButtonBorder(Primary)) {
                    Text(stringResource(R.string.cloud_login_to_cloudflare),)
                }
            },
            dismissButton = {
                TextButton(onClick = { subdomainCreationError = "" }) {
                    Text(stringResource(R.string.cloud_close), color = TextMuted)
                }
            }
        )
    }

    if (showEmailModal) {
        AlertDialog(
            onDismissRequest = { showEmailModal = false },
            containerColor = DialogSurface,
            modifier = androidx.compose.ui.Modifier.border(
                0.7.dp,
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f),
                CardShape,
            ),
                shape = CardShape,
            title = { Text(stringResource(R.string.cloud_verify_cloudflare_email), color = TextPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.cloud_cloudflare_sends_email_once) +
                        stringResource(R.string.cloud_step1_login_profile) +
                        stringResource(R.string.cloud_step2_send_verification) +
                        stringResource(R.string.cloud_step3_verify_inbox) +
                        stringResource(R.string.cloud_step4_return_and_check),
                        color = TextMuted, fontSize = 14.sp
                    )
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val url = "https://dash.cloudflare.com/profile"
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            context.startActivity(intent)
                        },
                        colors = iosButtonColors(Primary),
                        border = iosButtonBorder(Primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.cloud_login_cloudflare_profile),)
                    }
                    
                    Button(
                        onClick = {
                            isCheckingStatus = true
                            scope.launch {
                                val status = cloudManager.checkAccountStatus(account)
                                isCheckingStatus = false
                                if (status.second) {
                                    account.isEmailVerified = true
                                    isEmailVerified = true
                                    cloudManager.saveAccounts()
                                    showEmailModal = false
                                    android.widget.Toast.makeText(context, context.getString(R.string.cloud_email_verified), android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, context.getString(R.string.cloud_not_verified_try_again), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = iosButtonColors(GreenOk),
                        border = iosButtonBorder(GreenOk),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isCheckingStatus) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp),)
                        } else {
                            Text(stringResource(R.string.cloud_check_verification_status),)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmailModal = false }) {
                    Text(stringResource(R.string.cloud_close), color = TextMuted)
                }
            }
        )
    }


}
}


/**
 * The foot of a cloud group card: hand these configs to V2Ray, and get there.
 *
 * Three states, and each one is readable without pressing anything:
 *
 *  - nothing transferred yet: a labelled button saying how many configs it will add.
 *  - some new ones since last time: the same button, counting only what is actually new, so a
 *    group that gained two configs does not look identical to one that gained none.
 *  - everything already there: no button at all, just a confirmation and the way into V2Ray.
 *
 * The old green arrow could only express the first state; discovering the third meant pressing it
 * and reading a toast.
 */
@Composable
private fun CloudGroupHandoff(
    group: com.mlmvpn.scanner.data.CloudGroup,
    knownUris: Set<String>,
    onAdded: (Set<String>) -> Unit,
    onOpenV2Ray: () -> Unit,
) {
    val context = LocalContext.current
    val pending = group.nodes.count { it.uri !in knownUris }
    val allThere = pending == 0 && group.nodes.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (allThere) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = GreenOk,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.cloud_group_in_v2ray),
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
        } else {
            Button(
                onClick = {
                    val nodeManager = com.mlmvpn.scanner.data.NodeManager(context)
                    val existing = nodeManager.nodes.map { it.uri }.toSet()
                    group.nodes.forEach { n ->
                        if (n.uri !in existing) {
                            nodeManager.nodes.add(
                                n.copy(
                                    id = java.util.UUID.randomUUID().toString(),
                                    groupTitle = group.title,
                                )
                            )
                        }
                    }
                    nodeManager.saveNodes()
                    // Report the new truth to the section so every row re-reads its own state,
                    // including any other group that shares these configs.
                    onAdded(nodeManager.nodes.map { it.uri }.toSet())
                },
                modifier = Modifier.weight(1f).height(40.dp),
                colors = iosButtonColors(Primary),
                border = iosButtonBorder(Primary),
                shape = ControlShape,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.cloud_group_send_to_v2ray, pending),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }

        // Always available, whether or not anything was just added: this is the section's way
        // into the list these configs live in.
        TextButton(
            onClick = onOpenV2Ray,
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Primary),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Text(stringResource(R.string.cloud_open_v2ray), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Icon(
                Icons.Default.ChevronLeft,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    // Points the way the reader is going: leading edge in RTL, trailing in LTR.
                    .scale(
                        if (androidx.compose.ui.platform.LocalLayoutDirection.current ==
                            androidx.compose.ui.unit.LayoutDirection.Rtl
                        ) 1f else -1f
                    ),
            )
        }
    }
}

