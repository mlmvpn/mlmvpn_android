package com.mlmvpn.scanner.engines.mlm

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MlmUsersScreen(
    account: CloudAccount,
    onDismiss: () -> Unit,
    onScanUser: (String) -> Unit,
    onGroupsUpdated: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiManager = remember { MlmApiManager(context) }
    
    var users by remember { mutableStateOf<List<MlmUser>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddUserDialog by remember { mutableStateOf(false) }
    var editingUser by remember { mutableStateOf<MlmUser?>(null) }

    fun loadUsers() {
        isLoading = true
        scope.launch {
            val fetchedUsers = apiManager.getUsers(account)
            if (fetchedUsers != null) {
                users = fetchedUsers
            } else {
                Toast.makeText(context, "Error fetching users", Toast.LENGTH_SHORT).show()
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadUsers()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BgDark
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                }
                Text("مدیریت کاربران (MLM)", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { loadUsers() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Primary)
                }
            }

            Divider(color = BorderDark)

            // List
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    items(users) { user ->
                        MlmUserCard(
                            user = user,
                            account = account,
                            apiManager = apiManager,
                            onRefresh = { loadUsers() },
                            onScanUser = onScanUser,
                            onGroupsUpdated = onGroupsUpdated,
                            onEditUser = {
                                editingUser = user
                                showAddUserDialog = true
                            }
                        )
                    }
                }
            }

            // Add FAB
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 100.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                FloatingActionButton(
                    onClick = {
                        editingUser = null
                        showAddUserDialog = true
                    },
                    containerColor = Primary,
                    contentColor = BgDark
                ) {
                    Icon(Icons.Default.Add, "Add User")
                }
            }
        }
    }

    if (showAddUserDialog) {
        AddMlmUserDialog(
            account = account,
            apiManager = apiManager,
            editingUser = editingUser,
            onDismiss = {
                showAddUserDialog = false
                editingUser = null
            },
            onUserAdded = {
                showAddUserDialog = false
                editingUser = null
                loadUsers()
            }
        )
    }
}

@Composable
fun MlmUserCard(
    user: MlmUser,
    account: CloudAccount,
    apiManager: MlmApiManager,
    onRefresh: () -> Unit,
    onScanUser: (String) -> Unit,
    onGroupsUpdated: () -> Unit = {},
    onEditUser: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExpanded by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(16.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable { isExpanded = !isExpanded }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            user.isActive == 0 -> RedError
                            user.isOnline == 1 -> GreenOk
                            else -> TextDim
                        }
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(user.username, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                val usedMb = ((user.usedGb ?: 0f) * 1024).toInt()
                val limitStr = if (user.limitGb != null) "${user.limitGb} گیگابایت" else "نامحدود"
                Text("مصرف کاربر تا کنون: $usedMb مگابایت از $limitStr", color = TextMuted, fontSize = 12.sp)
                if (user.limitGb != null && user.limitGb > 0f) {
                    val progress = ((user.usedGb ?: 0f) / user.limitGb).coerceIn(0f, 1f)
                    val progColor = if (progress > 0.9f) RedError else if (progress > 0.7f) YellowWarn else GreenOk
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = progColor,
                        trackColor = BorderDark
                    )
                }
            }
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Primary, strokeWidth = 2.dp)
            } else {
                IconButton(onClick = {
                    isBusy = true
                    scope.launch {
                        val success = apiManager.toggleUser(account, user.username)
                        isBusy = false
                        if (success) {
                            onRefresh()
                        } else {
                            Toast.makeText(context, "خطا در تغییر وضعیت کاربر", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Icon(
                        if (user.isActive == 1) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Toggle",
                        tint = if (user.isActive == 1) TextMuted else GreenOk
                    )
                }
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.fillMaxWidth().background(BgDark.copy(alpha = 0.5f)).padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    InfoItem("پورت", user.port.toString())
                    InfoItem("اعتبار", if (user.expiryDays != null) "${user.expiryDays} روز" else "∞")
                    InfoItem("پروتکل", (user.connectionType ?: "xhttp").uppercase())
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ActionBtn(Icons.Default.Download, "دریافت کانفیگ") {
                        isBusy = true
                        scope.launch {
                            val configs = apiManager.getUserConfigs(account, user.username)
                            if (configs.isNotEmpty()) {
                                val engineNodes = configs.mapIndexed { index, uri ->
                                    com.mlmvpn.scanner.models.VpnNode(
                                        id = "mlm_${account.id}_${user.username}_$index",
                                        name = java.net.URLDecoder.decode(uri.substringAfterLast("#", "MLM ${user.username}"), "UTF-8"),
                                        uri = uri,
                                        type = if (uri.startsWith("vless")) "vless" else "trojan",
                                        engineType = "MLM"
                                    )
                                }
                                val groupManager = com.mlmvpn.scanner.data.GroupManager(context)
                                val newGroup = com.mlmvpn.scanner.data.CloudGroup(
                                    id = "mlm_${account.id}_${user.username}_${System.currentTimeMillis()}",
                                    accountId = account.id,
                                    date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                    title = "کانفیگ‌های کاربر: ${user.username}",
                                    nodes = engineNodes
                                )
                                groupManager.cloudGroups.add(0, newGroup)
                                groupManager.saveCloudGroups()
                                onGroupsUpdated()
                                Toast.makeText(context, "کانفیگ‌ها به تب ابری افزوده شدند", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "کانفیگی یافت نشد", Toast.LENGTH_SHORT).show()
                            }
                            isBusy = false
                        }
                    }
                    ActionBtn(Icons.Default.Language, "وضعیت") {
                        val statusLink = "${account.mlmWorkerUrl?.trimEnd('/')}/status/${user.username}"
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("status", statusLink)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "لینک صفحه وضعیت کپی شد", Toast.LENGTH_SHORT).show()
                    }
                    ActionBtn(Icons.Default.Speed, "اسکن IP") {
                        isBusy = true
                        scope.launch {
                            val configs = apiManager.getUserConfigs(account, user.username)
                            if (configs.isNotEmpty()) {
                                onScanUser(configs.first())
                            } else {
                                Toast.makeText(context, "کانفیگی یافت نشد", Toast.LENGTH_SHORT).show()
                            }
                            isBusy = false
                        }
                    }
                    ActionBtn(Icons.Default.Edit, "ویرایش") {
                        onEditUser()
                    }
                    ActionBtn(Icons.Default.Delete, "حذف", RedError) {
                        showDeleteConfirm = true
                    }
                }
            }
        }
    }
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("حذف کاربر", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("آیا از حذف کاربر ${user.username} اطمینان دارید؟\nبا حذف این کاربر تمام کانفیگ‌های مرتبط با آن قطع و از دسترس خارج خواهند شد!", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    isBusy = true
                    scope.launch {
                        val success = apiManager.deleteUser(account, user.username)
                        isBusy = false
                        if (success) {
                            onRefresh()
                        } else {
                            Toast.makeText(context, "خطا در حذف کاربر", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text("بله، حذف شود", color = RedError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("انصراف", color = Primary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun InfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextMuted, fontSize = 10.sp)
        Text(value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ActionBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Primary, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 10.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMlmUserDialog(
    account: CloudAccount,
    apiManager: MlmApiManager,
    editingUser: MlmUser?,
    onDismiss: () -> Unit,
    onUserAdded: () -> Unit
) {
    val isEditMode = editingUser != null
    var username by remember { mutableStateOf(editingUser?.username ?: "") }
    var limitGb by remember { mutableStateOf(editingUser?.limitGb?.toString() ?: "") }
    var dailyLimitGb by remember { mutableStateOf(editingUser?.dailyLimitGb?.toString() ?: "") }
    var expiryDays by remember { mutableStateOf(editingUser?.expiryDays?.toString() ?: "") }
    var isSecure by remember { mutableStateOf(editingUser?.tls == "tls" || editingUser?.tls == null) }
    val initialPorts = editingUser?.port?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: setOf("443")
    var selectedPorts by remember { mutableStateOf(initialPorts) }
    var fingerprint by remember { mutableStateOf(editingUser?.fingerprint ?: "chrome") }
    var proxyIp by remember { mutableStateOf(editingUser?.proxyIp ?: "") }
    var isBusy by remember { mutableStateOf(false) }
    var portExpanded by remember { mutableStateOf(false) }
    val securePorts = listOf("443", "8443", "2053", "2083", "2087", "2096")
    val normalPorts = listOf("80", "8080", "8880", "2052", "2082", "2086", "2095")
    val currentPorts = if (isSecure) securePorts else normalPorts
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text(if (isEditMode) "ویرایش کاربر" else "کاربر جدید", color = TextPrimary) },
        text = {
            Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("نام کاربری", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = isEditMode, // Cannot change username when editing
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = limitGb,
                    onValueChange = { limitGb = it },
                    label = { Text("محدودیت کل (GB) - خالی: نامحدود", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = dailyLimitGb,
                    onValueChange = { dailyLimitGb = it },
                    label = { Text("محدودیت روزانه (GB) - خالی: نامحدود", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = expiryDays,
                    onValueChange = { expiryDays = it },
                    label = { Text("اعتبار (روز) - خالی: نامحدود", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("نوع پورت:", color = TextMuted, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { 
                        isSecure = true
                        selectedPorts = selectedPorts.filter { it in securePorts }.toSet().ifEmpty { setOf("443") }
                    }) {
                        RadioButton(
                            selected = isSecure,
                            onClick = { 
                                isSecure = true
                                selectedPorts = selectedPorts.filter { it in securePorts }.toSet().ifEmpty { setOf("443") }
                            }, 
                            colors = RadioButtonDefaults.colors(selectedColor = Primary)
                        )
                        Text("امن (TLS)", color = TextPrimary, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { 
                        isSecure = false
                        selectedPorts = selectedPorts.filter { it in normalPorts }.toSet().ifEmpty { setOf("80") }
                    }) {
                        RadioButton(
                            selected = !isSecure, 
                            onClick = { 
                                isSecure = false
                                selectedPorts = selectedPorts.filter { it in normalPorts }.toSet().ifEmpty { setOf("80") }
                            }, 
                            colors = RadioButtonDefaults.colors(selectedColor = Primary)
                        )
                        Text("معمولی", color = TextPrimary, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                
                ExposedDropdownMenuBox(
                    expanded = portExpanded,
                    onExpandedChange = { portExpanded = !portExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedPorts.joinToString(", "),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("انتخاب پورت", color = TextMuted) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = portExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                    )
                    ExposedDropdownMenu(
                        expanded = portExpanded,
                        onDismissRequest = { portExpanded = false }
                    ) {
                        currentPorts.forEach { p ->
                            DropdownMenuItem(
                                text = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = selectedPorts.contains(p),
                                            onCheckedChange = null,
                                            colors = CheckboxDefaults.colors(checkedColor = Primary, uncheckedColor = TextMuted)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(p, color = TextPrimary)
                                    }
                                },
                                onClick = {
                                    selectedPorts = if (selectedPorts.contains(p)) {
                                        selectedPorts - p
                                    } else {
                                        selectedPorts + p
                                    }
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text("فرگمنت (Fingerprint)", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = proxyIp,
                    onValueChange = { proxyIp = it },
                    label = { Text("پروکسی اختصاصی (IP)", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (username.isBlank()) {
                        Toast.makeText(context, "نام کاربری الزامی است", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isBusy = true
                    scope.launch {
                        val success = if (isEditMode) {
                            val req = MlmUpdateUserRequest(
                                limitGb = limitGb.toFloatOrNull(),
                                dailyLimitGb = dailyLimitGb.toFloatOrNull(),
                                expiryDays = expiryDays.toIntOrNull(),
                                tls = if (isSecure) "tls" else "none",
                                port = selectedPorts.joinToString(",").ifEmpty { if (isSecure) "443" else "80" },
                                fingerprint = fingerprint.trim().ifEmpty { "chrome" },
                                proxyIp = proxyIp.trim().ifEmpty { null },
                                ips = editingUser?.ips
                            )
                            apiManager.updateUser(account, username.trim(), req)
                        } else {
                            val req = MlmCreateUserRequest(
                                username = username.trim(),
                                limitGb = limitGb.toFloatOrNull(),
                                dailyLimitGb = dailyLimitGb.toFloatOrNull(),
                                expiryDays = expiryDays.toIntOrNull(),
                                tls = if (isSecure) "tls" else "none",
                                port = selectedPorts.joinToString(",").ifEmpty { if (isSecure) "443" else "80" },
                                fingerprint = fingerprint.trim().ifEmpty { "chrome" },
                                proxyIp = proxyIp.trim().ifEmpty { null }
                            )
                            apiManager.createUser(account, req)
                        }
                        isBusy = false
                        if (success) {
                            Toast.makeText(context, if (isEditMode) "کاربر با موفقیت ویرایش شد" else "کاربر با موفقیت ایجاد شد", Toast.LENGTH_SHORT).show()
                            onUserAdded()
                        } else {
                            Toast.makeText(context, if (isEditMode) "خطا در ویرایش کاربر" else "خطا در ایجاد کاربر", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isBusy
            ) {
                if (isBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgDark)
                else Text(if (isEditMode) "ذخیره" else "ایجاد", color = BgDark)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("انصراف", color = TextMuted)
            }
        }
    )
}
