package com.mlmvpn.scanner.engines.subgenerator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class SubGenGroup(
    val id: String,
    val displayName: String,
    val nodes: List<VpnNode>
)

@OptIn(ExperimentalMaterial3Api::class)
val BackgroundDark = Color(0xFF121212)

@Composable
fun SubLinkScreen(
    cloudManager: CloudManager,
    nodeManager: NodeManager,
    onNavigateToCloud: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subGenManager = remember { SubGenManager(context) }
    val deployer = remember { SubGenDeployer(context) }

    val accounts by cloudManager.accountsFlow.collectAsState()
    
    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    val activeAccount = accounts.find { it.id == selectedAccountId } ?: accounts.firstOrNull()

    LaunchedEffect(activeAccount?.id) {
        if (activeAccount != null && selectedAccountId != activeAccount.id) {
            selectedAccountId = activeAccount.id
        }
    }

    var isChecking by remember { mutableStateOf(true) }
    var accountData by remember { mutableStateOf<SubGenAccountData?>(null) }
    var subLinks by remember { mutableStateOf(emptyList<SubLinkConfig>()) }

    var isDeploying by remember { mutableStateOf(false) }
    var deployProgress by remember { mutableStateOf(0) }
    var deployStatus by remember { mutableStateOf("") }
    var deployError by remember { mutableStateOf<String?>(null) }

    var showCreateModal by remember { mutableStateOf(false) }

    // Fetch account data on load
    LaunchedEffect(activeAccount) {
        if (activeAccount != null) {
            isChecking = true
            val data = subGenManager.checkWorkerExistsOnline(activeAccount)
            if (data != null) {
                subGenManager.syncSubLinksOnline(activeAccount, data)
            }
            subLinks = subGenManager.getSubLinks().filter { it.accountId == activeAccount.id || it.accountId.isEmpty() }
            accountData = data
            isChecking = false
        } else {
            isChecking = false
            accountData = null
            subLinks = emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (accounts.size > 1) {
            androidx.compose.material3.ScrollableTabRow(
                selectedTabIndex = accounts.indexOfFirst { it.id == activeAccount?.id }.coerceAtLeast(0),
                containerColor = SurfaceDark,
                contentColor = Primary,
                edgePadding = 8.dp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                indicator = { tabPositions ->
                    androidx.compose.material3.TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[accounts.indexOfFirst { it.id == activeAccount?.id }.coerceAtLeast(0)]),
                        color = Primary
                    )
                }
            ) {
                accounts.forEachIndexed { index, account ->
                    val isSelected = activeAccount?.id == account.id
                    androidx.compose.material3.Tab(
                        selected = isSelected,
                        onClick = { selectedAccountId = account.id },
                        text = { Text(account.email.ifEmpty { "اکانت ${index + 1}" }, color = if (isSelected) Primary else TextMuted, fontSize = 14.sp) }
                    )
                }
            }
        }

        if (activeAccount == null) {
            // State 1: No Cloudflare Account
            Spacer(modifier = Modifier.height(50.dp))
            Icon(Icons.Default.CloudOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.subgen_no_account_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.subgen_no_account_desc), color = TextMuted, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onNavigateToCloud,
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.subgen_go_cloud), color = Color.White)
            }
        } else if (isChecking) {
            // State 2: Checking worker status
            Spacer(modifier = Modifier.height(100.dp))
            CircularProgressIndicator(color = Primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.subgen_checking), color = TextMuted)
        } else if (accountData == null) {
            // State 3: Deploy worker
            Spacer(modifier = Modifier.height(50.dp))
            Icon(Icons.Default.Build, contentDescription = null, tint = Primary, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.subgen_not_active_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.subgen_not_active_desc), color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(modifier = Modifier.height(32.dp))

            if (isDeploying) {
                Text(deployStatus, color = Primary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = deployProgress / 100f,
                    color = Primary,
                    trackColor = SurfaceDark,
                    modifier = Modifier.fillMaxWidth(0.8f).height(8.dp).clip(RoundedCornerShape(4.dp))
                )
            } else {
                Button(
                    onClick = {
                        isDeploying = true
                        scope.launch {
                            val result = deployer.deploySubWorker(activeAccount) { prog, stat ->
                                scope.launch(Dispatchers.Main) {
                                    deployProgress = prog
                                    deployStatus = stat
                                }
                            }
                            if (result.first) {
                                accountData = subGenManager.getAccountData(activeAccount.id)
                            } else {
                                deployError = result.second
                            }
                            isDeploying = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.subgen_deploy_btn), color = Color.White)
                }
            }

            if (deployError != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { deployError = null },
                    title = { Text("خطای استقرار سیستم", color = Color.White) },
                    text = { 
                        androidx.compose.foundation.lazy.LazyColumn {
                            item { Text(deployError!!, color = TextMuted) }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { deployError = null }) {
                            Text("باشه", color = Primary)
                        }
                    },
                    containerColor = SurfaceDark
                )
            }
        } else {
            // State 4: Dashboard
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.subgen_your_links), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = { showCreateModal = true },
                    modifier = Modifier.background(SurfaceDark, CircleShape).border(1.dp, BorderDark, CircleShape)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Primary)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (subLinks.isEmpty()) {
                Spacer(modifier = Modifier.height(50.dp))
                Text(stringResource(R.string.subgen_no_links), color = TextMuted)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(subLinks) { link ->
                        SubLinkCard(
                            link = link,
                            accountData = accountData!!,
                            activeAccount = activeAccount,
                            subGenManager = subGenManager,
                            nodeManager = nodeManager,
                            groupManager = remember { GroupManager(context) },
                            onUpdateList = { subLinks = subGenManager.getSubLinks().filter { it.accountId == activeAccount.id || it.accountId.isEmpty() } }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }

            if (showCreateModal) {
                CreateSubLinkModal(
                    nodeManager = nodeManager,
                    subGenManager = subGenManager,
                    accountData = accountData!!,
                    activeAccount = activeAccount,
                    onDismiss = { showCreateModal = false },
                    onCreated = {
                        subLinks = subGenManager.getSubLinks().filter { it.accountId == activeAccount.id || it.accountId.isEmpty() }
                        showCreateModal = false
                    }
                )
            }
        }
    }
}

@Composable
fun SubLinkCard(
    link: SubLinkConfig,
    accountData: SubGenAccountData,
    activeAccount: CloudAccount,
    subGenManager: SubGenManager,
    nodeManager: NodeManager,
    groupManager: GroupManager,
    onUpdateList: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isUpdating by remember { mutableStateOf(false) }
    var isFetchingStats by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val fullUrl = "${accountData.workerUrl}/sub/${link.slug}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(link.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val groupText = link.mappedGroupName ?: stringResource(R.string.subgen_free_group)
                Text(stringResource(R.string.subgen_group, groupText), color = TextDim, fontSize = 12.sp)
                if (link.expiryTimestamp > 0) {
                    val daysLeft = ((link.expiryTimestamp - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
                    Text(stringResource(R.string.subgen_expiry_days, daysLeft), color = if (daysLeft > 0) Primary else Color.Red, fontSize = 12.sp)
                }
            }
            IconButton(
                onClick = { showDeleteDialog = true }
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
            }
        }

        if (showDeleteDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("حذف لینک ساب", color = Color.White) },
                text = { Text("آیا از حذف این لینک از سرور اطمینان دارید؟", color = TextMuted) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            val success = subGenManager.deleteSubLinkFromKV(activeAccount, accountData, link.slug)
                            if (success) {
                                android.widget.Toast.makeText(context, context.getString(R.string.subgen_deleted), android.widget.Toast.LENGTH_SHORT).show()
                                onUpdateList()
                            }
                        }
                    }) {
                        Text("حذف", color = Color.Red)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showDeleteDialog = false }) {
                        Text("لغو", color = TextPrimary)
                    }
                },
                containerColor = SurfaceDark
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BackgroundDark, RoundedCornerShape(8.dp))
                .padding(8.dp)
                .clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Sub Link", fullUrl))
                    Toast.makeText(context, context.getString(R.string.subgen_copied), Toast.LENGTH_SHORT).show()
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(fullUrl, color = TextMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Primary, modifier = Modifier.size(16.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            // Update Button
            Button(
                onClick = {
                    if (link.mappedGroupName == null) return@Button
                    isUpdating = true
                    scope.launch {
                        val parts = link.mappedGroupName?.split(":") ?: listOf()
                        val nodes = if (parts.size >= 2) {
                            val type = parts[0]
                            when(type) {
                                "manual" -> {
                                    val engine = parts[1]
                                    val groupTitle = parts.getOrNull(2)?.let { if (it == "null") null else it }
                                    nodeManager.nodes.filter { it.engineType == engine && (groupTitle == null || it.groupTitle == groupTitle) }
                                }
                                "cloud" -> groupManager.cloudGroups.find { it.id == parts[1] }?.nodes?.filter { it.engineType == parts.getOrNull(2) } ?: emptyList()
                                "scanner" -> groupManager.scannerGroups.find { it.id == parts[1] }?.nodes?.filter { it.engineType == parts.getOrNull(2) } ?: emptyList()
                                else -> emptyList()
                            }
                        } else {
                            nodeManager.nodes.filter { it.engineType == link.mappedGroupName }
                        }
                        
                        val configsStr = nodes.joinToString("\n") { it.uri }
                        val result = subGenManager.uploadConfigs(activeAccount, accountData, link.slug, configsStr, link.expiryTimestamp)
                        if (result.first) {
                            Toast.makeText(context, context.getString(R.string.subgen_updated_success), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.subgen_updated_error) + ": " + result.second, Toast.LENGTH_LONG).show()
                        }
                        isUpdating = false
                    }
                },
                enabled = !isUpdating,
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isUpdating) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.subgen_update_configs), fontSize = 12.sp)
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))

            // Stats Button
            Button(
                onClick = {
                    isFetchingStats = true
                    scope.launch {
                        subGenManager.fetchStats(activeAccount, accountData, link.slug)
                        onUpdateList()
                        isFetchingStats = false
                    }
                },
                enabled = !isFetchingStats,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isFetchingStats) {
                    CircularProgressIndicator(color = Primary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.BarChart, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.subgen_stats, link.hits), color = Primary, fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSubLinkModal(
    nodeManager: NodeManager,
    subGenManager: SubGenManager,
    accountData: SubGenAccountData,
    activeAccount: CloudAccount,
    onDismiss: () -> Unit,
    onCreated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var slug by remember { mutableStateOf(java.util.UUID.randomUUID().toString().substring(0, 8)) }
    
    val currentNodes by nodeManager.nodesFlow.collectAsState()
    val groupManager = remember { GroupManager(context) }
    val subscriptionManager = remember { com.mlmvpn.scanner.data.SubscriptionManager(context) }
    val subscriptions by subscriptionManager.subscriptionsFlow.collectAsState()
    val cloudGroups by groupManager.cloudGroupsFlow.collectAsState()
    val scannerGroups by groupManager.scannerGroupsFlow.collectAsState()
    
    val availableGroups = remember(currentNodes, cloudGroups, scannerGroups) {
        val list = mutableListOf<SubGenGroup>()
        
        // Manual nodes
        currentNodes.groupBy { it.engineType }.forEach { (engine, engineNodes) ->
            if (engine.equals("Manual", ignoreCase = true)) {
                engineNodes.groupBy { it.groupTitle }.forEach { (groupTitle, nodes) ->
                    val isSub = subscriptions.any { it.id == groupTitle || it.name == groupTitle }
                    val icon = if (isSub) "🔗" else "📁"
                    val displayName = if (isSub) subscriptions.find { it.id == groupTitle || it.name == groupTitle }?.name ?: groupTitle else groupTitle
                    val label = if (groupTitle == null) "دستی: پیش‌فرض" else "دستی: $icon $displayName"
                    list.add(SubGenGroup("manual:$engine:${groupTitle ?: "null"}", "$label (${nodes.size} کانفیگ)", nodes))
                }
            } else {
                list.add(SubGenGroup("manual:$engine:null", "بخش اتصال: $engine (${engineNodes.size} کانفیگ)", engineNodes))
            }
        }
        
        // Cloud groups
        cloudGroups.forEach { cg ->
            cg.nodes.groupBy { it.engineType }.forEach { (engine, nodes) ->
                list.add(SubGenGroup("cloud:${cg.id}:$engine", "ابری: ${cg.title} ($engine) (${nodes.size} کانفیگ)", nodes))
            }
        }
        
        // Scanner groups
        scannerGroups.forEach { sg ->
            sg.nodes.groupBy { it.engineType }.forEach { (engine, nodes) ->
                list.add(SubGenGroup("scanner:${sg.id}:$engine", "اسکنر: ${sg.title} ($engine) (${nodes.size} کانفیگ)", nodes))
            }
        }
        
        list
    }
    
    var selectedGroup by remember(availableGroups) { mutableStateOf<SubGenGroup?>(availableGroups.firstOrNull()) }
    var expiryDays by remember { mutableStateOf("") }
    
    var isSaving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundDark,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = { Text(stringResource(R.string.subgen_create_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.subgen_create_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedLabelColor = Primary,
                        unfocusedLabelColor = TextMuted,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = slug,
                    onValueChange = { slug = it.replace(Regex("[^a-zA-Z0-9_-]"), "") },
                    label = { Text(stringResource(R.string.subgen_create_slug)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedLabelColor = Primary,
                        unfocusedLabelColor = TextMuted,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(stringResource(R.string.subgen_create_mapped_group), color = TextDim, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                
                if (availableGroups.isEmpty()) {
                    Text("هیچ کانفیگی در برنامه یافت نشد! ابتدا در بخش اتصال یا ابری کانفیگ اضافه کنید.", color = Color(0xFFCF6679), fontSize = 14.sp)
                } else {
                    // Simple dropdown simulation with a LazyRow or just a scrollable row of chips
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availableGroups) { group ->
                            val isSelected = selectedGroup?.id == group.id
                            Box(
                                modifier = Modifier
                                    .background(if (isSelected) Primary else SurfaceDark, RoundedCornerShape(8.dp))
                                    .border(1.dp, if (isSelected) Primary else BorderDark, RoundedCornerShape(8.dp))
                                    .clickable { selectedGroup = group }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(group.displayName, color = if (isSelected) Color.White else TextMuted, fontSize = 14.sp)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = expiryDays,
                    onValueChange = { expiryDays = it.filter { char -> char.isDigit() } },
                    label = { Text(stringResource(R.string.subgen_create_expiry)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedLabelColor = Primary,
                        unfocusedLabelColor = TextMuted,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isEmpty() || slug.isEmpty() || selectedGroup == null) {
                        Toast.makeText(context, "Please fill all required fields (Name, Slug, Group)", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (selectedGroup!!.nodes.isEmpty()) {
                        Toast.makeText(context, "No configs available for the selected group", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSaving = true
                    scope.launch {
                        val expiryTs = if (expiryDays.isNotEmpty()) System.currentTimeMillis() + (expiryDays.toLong() * 24 * 60 * 60 * 1000) else 0L
                        val nodes = selectedGroup!!.nodes
                        val configsStr = nodes.joinToString("\n") { it.uri }
                        
                        val result = subGenManager.uploadConfigs(activeAccount, accountData, slug, configsStr, expiryTs)
                        if (result.first) {
                            val newLink = SubLinkConfig(
                                slug = slug,
                                name = name,
                                mappedGroupName = selectedGroup!!.id,
                                expiryTimestamp = expiryTs,
                                accountId = activeAccount.id
                            )
                            subGenManager.addSubLink(newLink)
                            Toast.makeText(context, context.getString(R.string.subgen_create_success), Toast.LENGTH_SHORT).show()
                            onCreated()
                        } else {
                            Toast.makeText(context, context.getString(R.string.subgen_create_error) + ": " + result.second, Toast.LENGTH_LONG).show()
                        }
                        isSaving = false
                    }
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                else Text(stringResource(R.string.subgen_create_save), color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel), color = TextMuted)
            }
        }
    )
}
