package com.mlmvpn.scanner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
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
import androidx.compose.ui.res.stringResource
import com.mlmvpn.scanner.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudTab(onNavigateToScanner: () -> Unit = {}) {
    val context = LocalContext.current
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
    var accountToDelete by remember { mutableStateOf<com.mlmvpn.scanner.models.CloudAccount?>(null) }

    // Physical back closes whichever settings/add sheet is open, instead of falling through to
    // the app-level handler (which would leave the Cloud tab entirely).
    val anySheetOpen = showAddModal || showNahanSettingsFor != null || showMlmUsersFor != null ||
        showMlmSettingsFor != null || showBpbSettingsFor != null || showEdgSettingsFor != null ||
        accountToDelete != null
    androidx.activity.compose.BackHandler(enabled = anySheetOpen) {
        showAddModal = false
        showNahanSettingsFor = null
        showMlmUsersFor = null
        showMlmSettingsFor = null
        showBpbSettingsFor = null
        showEdgSettingsFor = null
        accountToDelete = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                items(accounts) { account ->
                    AccountGroupCard(
                        account = account,
                        cloudGroups = cloudGroups.filter { it.accountId == account.id },
                        groupManager = groupManager,
                        cloudManager = cloudManager,
                        onGroupsUpdated = { cloudGroups = groupManager.cloudGroups.toList() },
                        onShowNahanSettings = { showNahanSettingsFor = it },
                        onShowMlmUsers = { showMlmUsersFor = it },
                        onShowMlmSettings = { showMlmSettingsFor = it },
                        onShowBpbSettings = { settings, acc -> 
                            bpbInitialSettings = settings
                            showBpbSettingsFor = acc 
                        },
                        onShowEdgSettings = { showEdgSettingsFor = it },
                        onDelete = {
                            accountToDelete = account
                        }
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
                item { Spacer(modifier = Modifier.height(140.dp)) } // padding for FAB
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 100.dp)
        ) {
            Surface(
                modifier = Modifier
                    .size(56.dp)
                    .clickable { showAddModal = true },
                shape = RoundedCornerShape(16.dp),
                color = Primary,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, contentDescription = "Add Account", modifier = Modifier.size(28.dp), tint = BgDark)
                }
            }
        }

        if (accountToDelete != null) {
            AlertDialog(
                onDismissRequest = { accountToDelete = null },
                containerColor = SurfaceDark,
                title = { Text(stringResource(R.string.dialog_delete_account_title), color = TextPrimary) },
                text = { Text(stringResource(R.string.dialog_delete_account_msg), color = TextMuted) },
                confirmButton = {
                    TextButton(onClick = {
                        cloudManager.accounts.remove(accountToDelete!!)
                        cloudManager.saveAccounts()
                        accounts = cloudManager.accounts.toList()
                        accountToDelete = null
                    }) {
                        Text(stringResource(R.string.nodes_confirm), color = RedError)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { accountToDelete = null }) {
                        Text(stringResource(R.string.nodes_cancel), color = TextMuted)
                    }
                }
            )
        }

        var isAdding by remember { mutableStateOf(false) }

        if (showAddModal) {
            AlertDialog(
                onDismissRequest = { if (!isAdding) showAddModal = false },
                containerColor = SurfaceDark,
                title = { Text(stringResource(R.string.cloud_add_account), color = TextPrimary) },
                text = {
                    Column {
                        Text(stringResource(R.string.cloud_email_address), color = TextMuted, fontSize = 12.sp)
                        var email by remember { mutableStateOf("") }
                        var api by remember { mutableStateOf("") }
                        
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            singleLine = true,
                            enabled = !isAdding,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                            colors = TextFieldDefaults.colors(
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedIndicatorColor = BorderDark,
                                focusedIndicatorColor = Primary,
                                cursorColor = Primary,
                                disabledContainerColor = Color.Transparent,
                                disabledIndicatorColor = BorderDark,
                                disabledTextColor = TextMuted
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Global API Key", color = TextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = api,
                            onValueChange = { api = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            singleLine = true,
                            enabled = !isAdding,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                            colors = TextFieldDefaults.colors(
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedIndicatorColor = BorderDark,
                                focusedIndicatorColor = Primary,
                                cursorColor = Primary,
                                disabledContainerColor = Color.Transparent,
                                disabledIndicatorColor = BorderDark,
                                disabledTextColor = TextMuted
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.cloud_api_key_hint),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val trimmedEmail = email.trim()
                                val trimmedApi = api.trim()
                                if (trimmedApi.isNotEmpty()) {
                                    if (accounts.any { (trimmedEmail.isNotEmpty() && it.email == trimmedEmail) || it.token == trimmedApi }) {
                                        android.widget.Toast.makeText(context, context.getString(R.string.cloud_account_already_added), android.widget.Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    isAdding = true
                                    scope.launch {
                                        val result = cloudManager.addAccount(trimmedApi, trimmedEmail)
                                        isAdding = false
                                        if (result.first) {
                                            accounts = cloudManager.accounts.toList()
                                            showAddModal = false
                                        } else {
                                            android.widget.Toast.makeText(context, result.second, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            enabled = !isAdding && api.trim().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, disabledContainerColor = Primary.copy(alpha = 0.5f))
                        ) {
                            if (isAdding) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = BgDark, strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.cloud_save_and_connect), color = BgDark, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }

        if (showNahanSettingsFor != null) {
            com.mlmvpn.scanner.engines.nahan.NahanSettingsScreen(
                account = showNahanSettingsFor!!,
                groupManager = groupManager,
                onGroupsUpdated = { cloudGroups = groupManager.cloudGroups.toList() },
                onDismiss = { showNahanSettingsFor = null }
            )
        }

        if (showMlmUsersFor != null) {
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

        if (showMlmSettingsFor != null) {
            com.mlmvpn.scanner.engines.mlm.MlmSettingsScreen(
                account = showMlmSettingsFor!!,
                onDismiss = { showMlmSettingsFor = null }
            )
        }
        
        if (showBpbSettingsFor != null) {
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
                            groupManager.saveCloudGroups()
                            android.widget.Toast.makeText(context, context.getString(R.string.cloud_new_group_received), android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, context.getString(R.string.cloud_error_fetching_nodes), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }

        if (showEdgSettingsFor != null) {
            com.mlmvpn.scanner.engines.edg.EdgSettingsScreen(
                account = showEdgSettingsFor!!,
                onDismiss = { showEdgSettingsFor = null }
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
    onShowBpbSettings: (org.json.JSONObject?, CloudAccount) -> Unit,
    onShowEdgSettings: (CloudAccount) -> Unit,
    onDelete: () -> Unit
) {
    var deployState by remember { mutableStateOf(if (account.status == "DEPLOYED" || account.status == "deployed") "done" else "idle") }
    var edgDeployState by remember { mutableStateOf(if (account.edgStatus == "DEPLOYED" || account.edgStatus == "deployed") "done" else "idle") }
    var progress by remember { mutableStateOf(0f) }
    var edgProgress by remember { mutableStateOf(0f) }
    var isFetching by remember { mutableStateOf(false) }
    var isEdgFetching by remember { mutableStateOf(false) }
    var showUsage by remember { mutableStateOf(false) }
    var usageData by remember { mutableStateOf(0f) }
    var isExpanded by remember { mutableStateOf(false) }
    var showSubdomainError by remember { mutableStateOf(false) }
    
    // Smart Verification States
    var isEmailVerified by remember { mutableStateOf(account.isEmailVerified) }
    var hasSubdomain by remember { mutableStateOf(account.hasSubdomain) }
    var showEmailModal by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("smart_verify", android.content.Context.MODE_PRIVATE)
    var emailSendTime by remember { mutableStateOf(prefs.getLong("email_${account.id}", 0L)) }
    var isCheckingStatus by remember { mutableStateOf(false) }
    var isCreatingSubdomain by remember { mutableStateOf(false) }
    var subdomainTimer by remember { mutableStateOf(0) }
    var showSubdomainCheck by remember { mutableStateOf(false) }
    var subdomainCreationError by remember { mutableStateOf("") }
    
    val scope = rememberCoroutineScope()
    
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
            .background(SurfaceDark, RoundedCornerShape(24.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.background(BgDark, CircleShape).border(1.dp, BorderDark, CircleShape).padding(8.dp)) {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(account.email, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("API: ${account.token.take(8)}...", color = TextMuted, fontSize = 10.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (deployState == "done") GreenOk else RedError))
                    }
                }
            }
            val isBusy = deployState == "deploying" || isFetching || edgDeployState == "deploying" || isEdgFetching || isCreatingSubdomain || isCheckingStatus
            IconButton(
                onClick = onDelete,
                enabled = !isBusy
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = if (!isBusy) TextMuted else BorderDark)
            }
        }

        // Action Buttons Column
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .background(BgDark.copy(alpha = 0.5f))
                // NOTE: was Modifier.blur(8.dp). Android's hardware blur (RenderEffect)
                // crashes the native RenderThread on some GPUs — notably Samsung — with
                // "pthread_mutex_lock called on a destroyed mutex" (SIGABRT) when the
                // blurred surface is torn down as this panel toggles on Cloudflare
                // account/subdomain state changes. A plain alpha dim gives the same
                // "locked/disabled" cue with zero native-blur risk.
                .then(if (!isEmailVerified || !hasSubdomain) Modifier.alpha(0.35f) else Modifier)
            ) {
            // --- ROW 1: BPB Engine ---
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // Deploy Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
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
                    val progressWidth by animateFloatAsState(targetValue = progress / 100f, animationSpec = tween(150))
                    if (deployState == "deploying") {
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progressWidth).background(Primary.copy(alpha = 0.2f)))
                    }
                    
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val color = if (deployState == "done") GreenOk else TextPrimary
                        Icon(if (deployState == "done") Icons.Default.CheckCircle else Icons.Default.PlayArrow, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Text(if (deployState == "deploying") stringResource(R.string.cloud_deploying) else if (deployState == "done") stringResource(R.string.cloud_deployed) else stringResource(R.string.cloud_deploy_bpb), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Divider(modifier = Modifier.width(1.dp).fillMaxHeight(), color = BorderDark)
                
                // Get Nodes Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
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
                    contentAlignment = Alignment.Center
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
                    val color = if (deployState == "done") Primary else TextDim
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 12.dp)) {
                        if (isFetching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        }
                        Text(if (isFetching) stringResource(R.string.cloud_fetching) else stringResource(R.string.cloud_fetch_bpb_node), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Divider(modifier = Modifier.fillMaxWidth().height(1.dp), color = BorderDark)
            
            // --- ROW 2: Usage Button ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (showUsage) BorderDark.copy(alpha = 0.5f) else Color.Transparent)
                    .clickable { 
                        showUsage = !showUsage
                        if (showUsage && usageData == 0f) {
                            scope.launch {
                                val result = cloudManager.getUsage(account)
                                if (result.first) {
                                    usageData = result.second.toFloatOrNull() ?: 0f
                                } else {
                                    android.widget.Toast.makeText(context, result.second, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DataUsage, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.cloud_daily_usage), color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }

            Divider(modifier = Modifier.fillMaxWidth().height(1.dp), color = BorderDark)

            // --- ROW 3: EDG Engine ---
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // EDG Deploy Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(0.dp))
                        .clickable(enabled = edgDeployState != "deploying") {
                            if (edgDeployState == "done") return@clickable
                            edgDeployState = "deploying"
                            edgProgress = 0f
                            scope.launch {
                                val result = cloudManager.deployEdgWorker(account) { percent, _ ->
                                    edgProgress = percent.toFloat()
                                }
                                if (result.first) {
                                    edgDeployState = "done"
                                    account.edgStatus = "DEPLOYED"
                                    cloudManager.saveAccounts()
                                } else {
                                    edgDeployState = "idle"
                                    if (result.second == "ERR_ACCOUNT_HAS_SUBDOMAIN") {
                                        showSubdomainError = true
                                    } else {
                                        android.widget.Toast.makeText(context, result.second, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                ) {
                    val edgProgressWidth by animateFloatAsState(targetValue = edgProgress / 100f, animationSpec = tween(150))
                    if (edgDeployState == "deploying") {
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(edgProgressWidth).background(Primary.copy(alpha = 0.2f)))
                    }
                    
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val color = if (edgDeployState == "done") GreenOk else TextPrimary
                        Icon(if (edgDeployState == "done") Icons.Default.CheckCircle else Icons.Default.PlayArrow, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Text(if (edgDeployState == "deploying") stringResource(R.string.cloud_deploying) else if (edgDeployState == "done") stringResource(R.string.cloud_deployed) else stringResource(R.string.cloud_deploy_edg), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                
                Divider(modifier = Modifier.width(1.dp).fillMaxHeight(), color = BorderDark)
                
                // EDG Get Nodes Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = edgDeployState == "done" && !isEdgFetching) {
                            if (edgDeployState == "done" && !isEdgFetching) {
                                scope.launch {
                                    isEdgFetching = true
                                    android.widget.Toast.makeText(context, context.getString(R.string.cloud_fetching_edg_configs), android.widget.Toast.LENGTH_SHORT).show()
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
                                        val newGroup = com.mlmvpn.scanner.data.CloudGroup(
                                            id = "edg_${account.id}_${System.currentTimeMillis()}",
                                            accountId = account.id,
                                            date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                            title = com.mlmvpn.scanner.utils.NamingHelper.generateMythName(),
                                            nodes = engineNodes
                                        )
                                        groupManager.cloudGroups.add(0, newGroup)
                                        groupManager.saveCloudGroups()
                                        android.widget.Toast.makeText(context, context.getString(R.string.cloud_configs_added_successfully, engineNodes.size), android.widget.Toast.LENGTH_SHORT).show()
                                        onGroupsUpdated()
                                    } else {
                                        android.widget.Toast.makeText(context, context.getString(R.string.cloud_error_fetching_configs), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    isEdgFetching = false
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
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
                    val color = if (edgDeployState == "done") Primary else TextDim
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 12.dp)) {
                        if (isEdgFetching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        }
                        Text(if (isEdgFetching) stringResource(R.string.cloud_fetching) else stringResource(R.string.cloud_fetch_edg_node), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                
                Divider(modifier = Modifier.width(1.dp).fillMaxHeight(), color = BorderDark)
                
                // EDG Settings Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = edgDeployState == "done") {
                            if (edgDeployState == "done") {
                                onShowEdgSettings(account)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val color = if (edgDeployState == "done") Primary else TextDim
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 12.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.cloud_edg_settings), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            
            Divider(modifier = Modifier.fillMaxWidth().height(1.dp), color = BorderDark)
            
            // --- ROW 4: Nahan Engine ---
            var nahanDeployState by remember { mutableStateOf(if (account.nahanStatus == "DEPLOYED" || account.nahanStatus == "deployed") "done" else "idle") }
            var nahanProgress by remember { mutableStateOf(0f) }
            var isNahanFetching by remember { mutableStateOf(false) }

            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // Nahan Deploy Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
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
                    val progressWidth by animateFloatAsState(targetValue = nahanProgress / 100f, animationSpec = tween(150))
                    if (nahanDeployState == "deploying") {
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progressWidth).background(Primary.copy(alpha = 0.2f)))
                    }
                    
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val color = if (nahanDeployState == "done") GreenOk else TextPrimary
                        Icon(if (nahanDeployState == "done") Icons.Default.CheckCircle else Icons.Default.PlayArrow, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Text(if (nahanDeployState == "deploying") stringResource(R.string.cloud_deploying_short) else if (nahanDeployState == "done") stringResource(R.string.cloud_deployed) else stringResource(R.string.cloud_deploy_nhn), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                
                Divider(modifier = Modifier.width(1.dp).fillMaxHeight(), color = BorderDark)
                
                // Nahan Get Nodes Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
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
                    contentAlignment = Alignment.Center
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
                    val color = if (nahanDeployState == "done") Primary else TextDim
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 12.dp)) {
                        if (isNahanFetching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        }
                        Text(if (isNahanFetching) stringResource(R.string.cloud_fetching_short) else stringResource(R.string.cloud_nhn_node), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Divider(modifier = Modifier.width(1.dp).fillMaxHeight(), color = BorderDark)
                
                // Nahan Settings Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = nahanDeployState == "done") {
                            if (nahanDeployState == "done") {
                                onShowNahanSettings(account)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val color = if (nahanDeployState == "done") TextPrimary else TextDim
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 12.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.cloud_settings), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } // end Nahan Engine

            Divider(modifier = Modifier.fillMaxWidth().height(1.dp), color = BorderDark)
            
            // --- ROW 5: MLM Engine ---
            var mlmDeployState by remember { mutableStateOf(if (account.mlmStatus == "DEPLOYED" || account.mlmStatus == "deployed") "done" else "idle") }
            var mlmProgress by remember { mutableStateOf(0f) }

            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // MLM Deploy Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
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
                    val progressWidth by animateFloatAsState(targetValue = mlmProgress / 100f, animationSpec = tween(150))
                    if (mlmDeployState == "deploying") {
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progressWidth).background(Primary.copy(alpha = 0.2f)))
                    }
                    
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val color = if (mlmDeployState == "done") GreenOk else TextPrimary
                        Icon(if (mlmDeployState == "done") Icons.Default.CheckCircle else Icons.Default.PlayArrow, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Text(if (mlmDeployState == "deploying") "در حال نصب" else if (mlmDeployState == "done") "نصب شده" else "نصب MLM", color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                
                Divider(modifier = Modifier.width(1.dp).fillMaxHeight(), color = BorderDark)
                
                // MLM Users Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = mlmDeployState == "done") {
                            if (mlmDeployState == "done") {
                                onShowMlmUsers(account)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val color = if (mlmDeployState == "done") Primary else TextDim
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 12.dp)) {
                        Icon(Icons.Default.Group, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Text("کاربران", color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Divider(modifier = Modifier.width(1.dp).fillMaxHeight(), color = BorderDark)
                
                // MLM Settings Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = mlmDeployState == "done") {
                            if (mlmDeployState == "done") {
                                onShowMlmSettings(account)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val color = if (mlmDeployState == "done") TextPrimary else TextDim
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 12.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Text("تنظیمات", color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } // end MLM Engine

            // Smart Verification Overlay
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
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.cloud_verify_email_required), color = Color.White)
                            }
                        } else if (!hasSubdomain) {
                            Button(
                                onClick = {
                                    isCreatingSubdomain = true
                                    scope.launch {
                                        val res = cloudManager.createSubdomainOnly(account)
                                        isCreatingSubdomain = false
                                        if (res.first) {
                                            account.hasSubdomain = true
                                            hasSubdomain = true
                                            cloudManager.saveAccounts()
                                            subdomainTimer = 180
                                        } else {
                                            if (res.second.contains("verify", ignoreCase = true)) {
                                                isEmailVerified = false
                                                account.isEmailVerified = false
                                                cloudManager.saveAccounts()
                                                android.widget.Toast.makeText(context, context.getString(R.string.cloud_error_email_not_verified), android.widget.Toast.LENGTH_LONG).show()
                                            } else {
                                                subdomainCreationError = res.second
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                enabled = !isCreatingSubdomain && subdomainTimer == 0
                            ) {
                                if (isCreatingSubdomain) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                } else if (subdomainTimer > 0) {
                                    Text(stringResource(R.string.cloud_wait_timer, subdomainTimer / 60, subdomainTimer % 60))
                                } else {
                                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.cloud_create_subdomain_required), color = Color.White)
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
                                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                    enabled = !isCheckingStatus
                                ) {
                                    if (isCheckingStatus) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgDark)
                                    } else {
                                        Text(stringResource(R.string.cloud_check_subdomain_status), color = BgDark)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Usage Panel
        AnimatedVisibility(visible = showUsage) {
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).border(1.dp, BorderDark).padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.cloud_free_daily_usage), color = TextMuted, fontSize = 12.sp)
                    Text("${usageData.toInt()} / 100,000", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                val usagePercent by animateFloatAsState(targetValue = usageData / 100000f, animationSpec = tween(1000))
                Box(modifier = Modifier.fillMaxWidth().height(8.dp).background(BgDark, CircleShape).border(1.dp, BorderDark, CircleShape)) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(usagePercent).background(Primary, CircleShape))
                }
            }
        }

        // Cloud Groups List
        if (cloudGroups.isNotEmpty()) {
            Divider(color = BorderDark)
            Row(
                modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded }.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Layers, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.cloud_received_groups), color = TextPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.background(BgDark, RoundedCornerShape(4.dp)).border(1.dp, BorderDark, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("${cloudGroups.size}", color = Primary, fontSize = 12.sp)
                    }
                }
                Icon(if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = TextMuted)
            }
            
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth().background(BgDark).padding(8.dp)) {
                    cloudGroups.forEachIndexed { index, group ->
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).background(SurfaceDark, RoundedCornerShape(12.dp)).border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(stringResource(R.string.cloud_group_nodes_count, group.title, group.nodes.size), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    val engine = group.nodes.firstOrNull()?.engineType ?: "BPB"
                                    Text("$engine - ${group.date}", color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(onClick = { 
                                        val nodeManager = com.mlmvpn.scanner.data.NodeManager(context)
                                        // Avoid duplicating nodes if possible
                                        val existingUris = nodeManager.nodes.map { it.uri }.toSet()
                                        var added = 0
                                        group.nodes.forEach { n ->
                                            if (!existingUris.contains(n.uri)) {
                                                nodeManager.nodes.add(n.copy(id = java.util.UUID.randomUUID().toString(), groupTitle = group.title))
                                                added++
                                            }
                                        }
                                        nodeManager.saveNodes()
                                        if (added > 0) {
                                            android.widget.Toast.makeText(context, context.getString(R.string.cloud_nodes_added_to_connection_list, added), android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, context.getString(R.string.cloud_nodes_already_added), android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }, modifier = Modifier.size(28.dp).background(BgDark, RoundedCornerShape(8.dp)).border(1.dp, BorderDark, RoundedCornerShape(8.dp))) {
                                        Icon(
                                            Icons.Default.ArrowForward,
                                            contentDescription = "Add Nodes",
                                            tint = GreenOk,
                                            modifier = Modifier.size(14.dp).scale(if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) -1f else 1f)
                                        )
                                    }
                                    
                                    IconButton(onClick = { 
                                        groupManager.cloudGroups.remove(group)
                                        groupManager.saveCloudGroups()
                                        onGroupsUpdated()
                                        android.widget.Toast.makeText(context, context.getString(R.string.cloud_group_deleted), android.widget.Toast.LENGTH_SHORT).show()
                                    }, modifier = Modifier.size(28.dp).background(BgDark, RoundedCornerShape(8.dp)).border(1.dp, BorderDark, RoundedCornerShape(8.dp))) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().background(BgDark.copy(alpha = 0.3f)).padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                group.nodes.take(3).forEach { n ->
                                    Box(modifier = Modifier.background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp)).border(1.dp, BorderDark, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 4.dp)) {
                                        Text(n.name.take(15), color = TextMuted, fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                                if (group.nodes.size > 3) {
                                    Box(modifier = Modifier.background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp)).border(1.dp, BorderDark, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 4.dp)) {
                                        Text("+${group.nodes.size - 3}", color = TextMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    } // Closes the main Column

    if (showSubdomainError) {
        AlertDialog(
            onDismissRequest = { showSubdomainError = false },
            containerColor = SurfaceDark,
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
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text(stringResource(R.string.cloud_login_to_cloudflare), color = BgDark)
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
            containerColor = SurfaceDark,
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
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text(stringResource(R.string.cloud_login_to_cloudflare), color = BgDark)
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
            containerColor = SurfaceDark,
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
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.cloud_login_cloudflare_profile), color = BgDark)
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
                        colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isCheckingStatus) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgDark)
                        } else {
                            Text(stringResource(R.string.cloud_check_verification_status), color = BgDark)
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
