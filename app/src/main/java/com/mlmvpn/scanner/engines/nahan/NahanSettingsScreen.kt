package com.mlmvpn.scanner.engines.nahan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ============================================================================
// NahanSettingsScreen — Full-screen Dialog for Nahan Worker Configuration
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NahanSettingsScreen(
    account: CloudAccount,
    groupManager: com.mlmvpn.scanner.data.GroupManager,
    onGroupsUpdated: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deployer = remember { NahanDeployer(context) }

    // Loading state
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Config fields
    var protocol by remember { mutableStateOf("Both") }
    var apiRoute by remember { mutableStateOf("sync") }
    var masterKey by remember { mutableStateOf("admin") }
    var maintenanceHost by remember { mutableStateOf("ubuntu.com") }
    var resolveIp by remember { mutableStateOf("1.1.1.1") }
    var customDns by remember { mutableStateOf("https://cloudflare-dns.com/dns-query") }
    var customRelay by remember { mutableStateOf("") }
    var backupRelay by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("") }
    var cleanIps by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<NahanUser>>(emptyList()) }

    val allTlsPorts = listOf(443, 8443, 2053, 2083, 2087, 2096)
    var selectedTlsPorts by remember { mutableStateOf(allTlsPorts.toSet()) }

    // UI state
    var showAddUserDialog by remember { mutableStateOf(false) }
    var protocolExpanded by remember { mutableStateOf(false) }

    // Load settings on first composition
    LaunchedEffect(Unit) {
        try {
            val config = deployer.fetchSettings(account)
            if (config != null) {
                protocol = config.protocol
                apiRoute = config.apiRoute
                masterKey = config.masterKey.ifEmpty { "admin" }
                maintenanceHost = config.maintenanceHost
                resolveIp = config.resolveIp
                customDns = config.customDns
                customRelay = config.customRelay
                backupRelay = config.backupRelay
                agent = config.agent
                cleanIps = config.cleanIps
                users = config.users.toList()
                selectedTlsPorts = config.tlsPorts.toSet()
                loadError = null
            } else {
                loadError = context.getString(com.mlmvpn.scanner.R.string.nhn_fetch_error)
            }
        } catch (e: Exception) {
            loadError = context.getString(com.mlmvpn.scanner.R.string.nhn_connection_error, e.message)
        } finally {
            isLoading = false
        }
    }

    Surface(
        shape = RoundedCornerShape(0.dp),
        color = Color(0xFF202124),
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
                // ── Header ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Primary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_settings_title),
                                color = TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                account.nahanWorkerUrl ?: androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_unknown),
                                color = TextMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp)
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Icon(Icons.Default.Close, contentDescription = androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.common_close), tint = TextMuted)
                    }
                }

                Divider(color = BorderDark)

                // ── Content ──
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Primary, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_fetching_settings), color = TextMuted, fontSize = 14.sp)
                        }
                    }
                } else if (loadError != null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = RedError,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(loadError!!, color = TextMuted, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceDark,
                                    contentColor = TextPrimary
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                            ) {
                                Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_go_back))
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // ── Section 1: Basic Settings ──
                        Column(modifier = Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(12.dp)).border(1.dp, BorderDark, RoundedCornerShape(12.dp)).padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_basic_settings), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Protocol Dropdown
                            ExposedDropdownMenuBox(
                                expanded = protocolExpanded,
                                onExpandedChange = { protocolExpanded = !protocolExpanded }
                            ) {
                                OutlinedTextField(
                                    value = protocol,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_output_protocol), color = TextMuted) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded)
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Primary,
                                        unfocusedBorderColor = BorderDark,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = protocolExpanded,
                                    onDismissRequest = { protocolExpanded = false }
                                ) {
                                    listOf("VLESS", "Trojan", "Both").forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option) },
                                            onClick = {
                                                protocol = option
                                                protocolExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = apiRoute,
                                onValueChange = { apiRoute = it },
                                label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_api_route), color = TextMuted) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary, unfocusedBorderColor = BorderDark,
                                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = masterKey,
                                onValueChange = { masterKey = it },
                                label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_master_key), color = TextMuted) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary, unfocusedBorderColor = BorderDark,
                                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                                )
                            )
                        }

                        Divider(color = BorderDark)

                        // ── Section 2: Network & Camouflage ──
                        NahanSectionHeader(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_network_camouflage), Icons.Default.Security)

                        // TLS Ports
                        Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_tls_ports), color = TextMuted, fontSize = 12.sp)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            allTlsPorts.forEach { port ->
                                val isSelected = selectedTlsPorts.contains(port)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) Primary.copy(alpha = 0.2f)
                                            else Color(0xFF3C4043)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) Primary.copy(alpha = 0.5f) else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            selectedTlsPorts = if (isSelected) {
                                                selectedTlsPorts - port
                                            } else {
                                                selectedTlsPorts + port
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        port.toString(),
                                        color = if (isSelected) Primary else TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        // Maintenance Host
                        NahanTextField(
                            label = "Maintenance Host",
                            value = maintenanceHost,
                            onValueChange = { maintenanceHost = it }
                        )

                        // Resolve IP
                        NahanTextField(
                            label = "Resolve IP (DNS)",
                            value = resolveIp,
                            onValueChange = { resolveIp = it }
                        )

                        // Custom DNS
                        OutlinedTextField(
                            value = customDns,
                            onValueChange = { customDns = it },
                            label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_custom_dns), color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary, unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customRelay,
                            onValueChange = { customRelay = it },
                            label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_custom_relay), color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary, unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = backupRelay,
                            onValueChange = { backupRelay = it },
                            label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_backup_relay), color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary, unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = cleanIps,
                            onValueChange = { cleanIps = it },
                            label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_clean_ips), color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary, unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                            )
                        )

                        Divider(color = BorderDark)

                        // ── Section 3: Multi-User Management ──
                        NahanSectionHeader(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_user_management), Icons.Default.People)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_active_users, users.size),
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                            Button(
                                onClick = { showAddUserDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Primary.copy(alpha = 0.15f),
                                    contentColor = Primary
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    Icons.Default.PersonAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_add_user), fontSize = 12.sp)
                            }
                        }

                        if (users.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(BgDark, RoundedCornerShape(12.dp))
                                    .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.PersonOff,
                                        contentDescription = null,
                                        tint = TextDim,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_no_users),
                                        color = TextMuted,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                users.forEachIndexed { index, user ->
                                    NahanUserCard(
                                        user = user,
                                        onDelete = {
                                            users = users.toMutableList().also {
                                                it.removeAt(index)
                                            }
                                        },
                                        onEdit = { editedUser ->
                                            users = users.toMutableList().also {
                                                it[index] = editedUser
                                            }
                                        },
                                        onFetchNodes = {
                                            scope.launch {
                                                android.widget.Toast.makeText(context, context.getString(com.mlmvpn.scanner.R.string.nhn_fetching_config, user.name), android.widget.Toast.LENGTH_SHORT).show()
                                                try {
                                                    val userNodes = deployer.fetchUserNodes(account, user.name)
                                                    if (userNodes.isNotEmpty()) {
                                                        val newGroup = com.mlmvpn.scanner.data.CloudGroup(
                                                            id = "nhn_${account.id}_${user.uuid.take(6)}_${System.currentTimeMillis()}",
                                                            accountId = account.id,
                                                            date = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                                            title = user.name,
                                                            nodes = userNodes.mapIndexed { idx, uri ->
                                                                com.mlmvpn.scanner.models.VpnNode(
                                                                    id = java.util.UUID.randomUUID().toString(),
                                                                    name = "NHN - ${user.name}",
                                                                    uri = uri,
                                                                    type = if (uri.startsWith("trojan://")) "trojan" else "vless",
                                                                    engineType = "NHN"
                                                                )
                                                            }
                                                        )
                                                        groupManager.cloudGroups.add(0, newGroup)
                                                        groupManager.saveCloudGroups()
                                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                            onGroupsUpdated()
                                                            android.widget.Toast.makeText(context, context.getString(com.mlmvpn.scanner.R.string.nhn_configs_received, userNodes.size, user.name), android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    } else {
                                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                            android.widget.Toast.makeText(context, context.getString(com.mlmvpn.scanner.R.string.nhn_no_config_found, user.name), android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                        android.widget.Toast.makeText(context, context.getString(com.mlmvpn.scanner.R.string.cloud_error, e.message), android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // Extra spacing for floating nav bar
                        Spacer(modifier = Modifier.height(100.dp))
                    }

                    // ── Save Button ──
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceDark)
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = {
                                isSaving = true
                                scope.launch {
                                    try {
                                        val config = NahanConfig(
                                            protocol = protocol,
                                            apiRoute = apiRoute,
                                            masterKey = masterKey,
                                            tlsPorts = selectedTlsPorts.toList().sorted(),
                                            maintenanceHost = maintenanceHost,
                                            resolveIp = resolveIp,
                                            customDns = customDns,
                                            customRelay = customRelay,
                                            backupRelay = backupRelay,
                                            agent = agent,
                                            cleanIps = cleanIps,
                                            users = users.toMutableList()
                                        )
                                        val success = deployer.syncSettings(account, config)
                                        if (success) {
                                            // Update account fields if changed
                                            account.nahanApiRoute = apiRoute
                                            account.nahanMasterKey = masterKey

                                            withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(com.mlmvpn.scanner.R.string.nhn_settings_saved),
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                onDismiss()
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(com.mlmvpn.scanner.R.string.nhn_save_error),
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(
                                                context,
                                                context.getString(com.mlmvpn.scanner.R.string.cloud_error, e.message),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary,
                                contentColor = BgDark
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = BgDark,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_saving),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            } else {
                                Icon(
                                    Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_save_sync),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(100.dp))
            }
        }

    // ── Add User Dialog ──
    if (showAddUserDialog) {
        AddNahanUserDialog(
            onDismiss = { showAddUserDialog = false },
            onConfirm = { newUser ->
                users = users + newUser
                showAddUserDialog = false
            }
        )
    }
}

// ============================================================================
// Sub-Components
// ============================================================================

@Composable
private fun NahanSectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, color = Primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NahanTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextMuted, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            unfocusedBorderColor = BorderDark,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = Primary
        ),
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
private fun NahanUserCard(
    user: NahanUser,
    onDelete: () -> Unit,
    onEdit: (NahanUser) -> Unit,
    onFetchNodes: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var editName by remember(user) { mutableStateOf(user.name) }
    var editLimit by remember(user) { mutableStateOf(if (user.limit > 0) (user.limit / 6000L).toString() else "0") }
    var editDailyLimit by remember(user) { mutableStateOf(if (user.limitDailyReq > 0) (user.limitDailyReq / 6000L).toString() else "0") }
    
    val currentDays = remember(user.expiryMs) {
        if (user.expiryMs > 0) {
            val remaining = user.expiryMs - System.currentTimeMillis()
            if (remaining > 0) (remaining / (1000 * 60 * 60 * 24)).toString() else "0"
        } else "0"
    }
    var editExpiryDays by remember(user) { mutableStateOf(currentDays) }
    var editIsPaused by remember(user) { mutableStateOf(user.isPaused) }
    var editProxyIp by remember(user) { mutableStateOf(user.proxyIp) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgDark, RoundedCornerShape(12.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Primary.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    user.name.firstOrNull()?.uppercase() ?: "?",
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    user.name.ifEmpty { androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_no_name) },
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    user.uuid,
                    color = TextDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (user.isPaused) {
                    Text(
                        androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_paused),
                        color = RedError,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                
                if (user.expiryMs > 0) {
                    val remaining = user.expiryMs - System.currentTimeMillis()
                    val days = remaining / (1000 * 60 * 60 * 24)
                    if (days > 0) {
                        Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_expiry_days, days), color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    } else {
                        Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_expired), color = RedError, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                
                if (user.limit > 0 || user.limitDailyReq > 0) {
                    val usedGB = user.used.toFloat() / 6000f
                    val limitGB = user.limit.toFloat() / 6000f
                    val dailyStr = if (user.limitDailyReq > 0) context.getString(com.mlmvpn.scanner.R.string.nhn_daily_suffix, user.limitDailyReq / 6000L) else ""
                    val limitStr = if (user.limit > 0) "%.0f GB".format(limitGB) else context.getString(com.mlmvpn.scanner.R.string.nhn_unlimited)
                    
                    Text(
                        androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_usage_format, "%.1f / $limitStr$dailyStr".format(usedGB)),
                        color = if (user.limit > 0 && usedGB > limitGB * 0.9f) RedError else GreenOk,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                } else {
                    Text(
                        androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_unlimited_traffic),
                        color = GreenOk,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Actions
            IconButton(
                onClick = onFetchNodes,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_fetch_config_button),
                    tint = GreenOk,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = { isEditing = !isEditing },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.node_edit),
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.node_delete),
                    tint = RedError.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Edit Section
        AnimatedVisibility(visible = isEditing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_user_name), fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = editLimit,
                    onValueChange = { editLimit = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_limit_gb), fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = editDailyLimit,
                    onValueChange = { editDailyLimit = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_daily_limit_gb), fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = editExpiryDays,
                    onValueChange = { editExpiryDays = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_expiry_days_input), fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = editProxyIp,
                    onValueChange = { editProxyIp = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_user_proxy_ip), fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(
                        checked = editIsPaused,
                        onCheckedChange = { editIsPaused = it },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = BgDark,
                            checkedTrackColor = RedError
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_pause_user), color = if (editIsPaused) RedError else TextPrimary, fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        val limitBytes = (editLimit.toLongOrNull() ?: 0L) * 6000L
                        val dailyLimitBytes = (editDailyLimit.toLongOrNull() ?: 0L) * 6000L
                        val days = editExpiryDays.toLongOrNull() ?: 0L
                        val expiryTimeMs = if (days > 0) System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000) else 0L

                        onEdit(
                            user.copy(
                                name = editName.trim().ifEmpty { user.name },
                                limit = limitBytes,
                                limitDailyReq = dailyLimitBytes,
                                expiryMs = expiryTimeMs,
                                isPaused = editIsPaused,
                                proxyIp = editProxyIp.trim()
                            )
                        )
                        isEditing = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GreenOk,
                        contentColor = BgDark
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_apply_changes), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun AddNahanUserDialog(
    onDismiss: () -> Unit,
    onConfirm: (NahanUser) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var limitGB by remember { mutableStateOf("0") }
    var dailyLimitGB by remember { mutableStateOf("0") }
    var expiryDays by remember { mutableStateOf("0") }
    var userProxyIp by remember { mutableStateOf("") }
    val generatedUuid = remember { UUID.randomUUID().toString() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_add_new_user), color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_user_name), color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                // UUID display (read-only)
                OutlinedTextField(
                    value = generatedUuid,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_uuid_auto), color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BorderDark,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextDim,
                        unfocusedTextColor = TextDim
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = limitGB,
                    onValueChange = { limitGB = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_total_limit_gb), color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = dailyLimitGB,
                    onValueChange = { dailyLimitGB = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_daily_traffic_limit), color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = expiryDays,
                    onValueChange = { expiryDays = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_expiry_days_input), color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = userProxyIp,
                    onValueChange = { userProxyIp = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_user_proxy_ip), color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val limitBytes = (limitGB.toLongOrNull() ?: 0L) * 6000L
                    val dailyLimitBytes = (dailyLimitGB.toLongOrNull() ?: 0L) * 6000L
                    val days = expiryDays.toLongOrNull() ?: 0L
                    val expiryTimeMs = if (days > 0) System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000) else 0L

                    onConfirm(
                        NahanUser(
                            name = name.trim().ifEmpty { "User" },
                            uuid = generatedUuid,
                            limit = limitBytes,
                            reset = 0,
                            used = 0,
                            expiryMs = expiryTimeMs,
                            limitDailyReq = dailyLimitBytes,
                            isPaused = false,
                            proxyIp = userProxyIp.trim()
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = BgDark
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.nhn_add), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.common_cancel), color = TextMuted)
            }
        }
    )
}
