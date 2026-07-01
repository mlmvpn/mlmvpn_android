package com.mlmvpn.scanner.ui.emergency

import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mlmvpn.scanner.ui.tlsPing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.emergency.EmergencyColors
import com.mlmvpn.scanner.engines.gst.GstConfigManager
import com.mlmvpn.scanner.engines.gst.GstRelay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyLevel2Screen(onBack: () -> Unit) {
    val context = LocalContext.current
    var relays by remember { mutableStateOf(GstConfigManager.getRelays(context)) }
    var showScriptDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isCertInstalled by remember { mutableStateOf(false) }
    var showTooltip by remember { mutableStateOf(false) }
    val isVpnRunning by MyVpnService.isRunningFlow.collectAsState()
    
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isCertInstalled = isCaInstalled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        isCertInstalled = isCaInstalled(context)
        showTooltip = true
        kotlinx.coroutines.delay(3000)
        showTooltip = false
        
        if (relays.isEmpty() || relays[0].authKey.isEmpty()) {
            val newKey = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            val updated = if (relays.isEmpty()) {
                listOf(GstRelay(deploymentId = "", authKey = newKey))
            } else {
                val list = relays.toMutableList()
                list[0] = list[0].copy(authKey = newKey)
                list
            }
            relays = updated
            GstConfigManager.saveRelays(context, updated)
        }
    }
    
    // Animate connect button
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isVpnRunning) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EmergencyColors.GoogleBg)
            .padding(top = com.mlmvpn.scanner.ui.LocalSystemTopPadding.current, bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { /* consume clicks — prevent pass-through to the tab behind */ }
    ) {
        // TopBar
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = EmergencyColors.GoogleMuted)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("اضطراری ۲ (زیرساخت گوگل)", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Row {
                Box(contentAlignment = Alignment.TopCenter) {
                    IconButton(onClick = {
                    try {
                        val caFile = java.io.File(context.filesDir, "ca/ca.crt")
                        if (!caFile.exists()) {
                            android.widget.Toast.makeText(context, "گواهی ساخته نشده! یکبار دکمه اتصال را بزنید.", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            val resolver = context.contentResolver
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                val values = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "MLMVPN_CA.crt")
                                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
                                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                                }
                                val sel = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?"
                                resolver.delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel, arrayOf("MLMVPN_CA.crt"))
                                
                                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                uri?.let { u ->
                                    resolver.openOutputStream(u)?.use { out ->
                                        out.write(caFile.readBytes())
                                    }
                                }
                            } else {
                                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                val exportedCert = java.io.File(downloadsDir, "MLMVPN_CA.crt")
                                caFile.copyTo(exportedCert, overwrite = true)
                                android.media.MediaScannerConnection.scanFile(context, arrayOf(exportedCert.absolutePath), arrayOf("application/x-x509-ca-cert"), null)
                            }
                            
                            android.widget.Toast.makeText(context, "گواهی در پوشه دانلودها ذخیره شد. لطفاً آن را از تنظیمات گوشی نصب کنید.", android.widget.Toast.LENGTH_LONG).show()
                            
                            val intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "خطا: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.Security, contentDescription = "Install CA", tint = if (isCertInstalled) androidx.compose.ui.graphics.Color.Green else EmergencyColors.GoogleRed)
                }
                
                if (showTooltip) {
                    Popup(
                        alignment = Alignment.BottomCenter,
                        offset = androidx.compose.ui.unit.IntOffset(0, 140)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = androidx.compose.ui.graphics.Color.DarkGray,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Text(
                                text = if (isCertInstalled) "✅ گواهی با موفقیت نصب شده است" else "❌ گواهی نصب نشده است",
                                color = androidx.compose.ui.graphics.Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
            
            IconButton(onClick = { showSettingsDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = EmergencyColors.GoogleMuted)
            }

            IconButton(onClick = { showScriptDialog = true }) {
                Icon(Icons.Default.Code, contentDescription = "Script", tint = EmergencyColors.GoogleBlue)
            }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            itemsIndexed(relays) { index, relay ->
                DynamicRelayCard(
                    deploymentId = relay.deploymentId,
                    authKey = relay.authKey,
                    isActive = isVpnRunning && index == 0, // Mock active state
                    onDeploymentIdChange = { newId ->
                        val updated = relays.toMutableList()
                        updated[index] = relay.copy(deploymentId = newId)
                        relays = updated
                        GstConfigManager.saveRelays(context, updated)
                    },
                    onAuthKeyChange = { newKey ->
                        val updated = relays.toMutableList()
                        updated[index] = relay.copy(authKey = newKey)
                        relays = updated
                        GstConfigManager.saveRelays(context, updated)
                    },
                    onRemove = {
                        val updated = relays.toMutableList()
                        updated.removeAt(index)
                        relays = updated
                        GstConfigManager.saveRelays(context, updated)
                    }
                )
            }
            
            item {
                val canAddMore = relays.isNotEmpty() && relays.last().deploymentId.isNotBlank()
                Button(
                    onClick = {
                        val sharedKey = relays.firstOrNull()?.authKey ?: ""
                        val updated = relays.toMutableList()
                        updated.add(GstRelay(deploymentId = "", authKey = sharedKey))
                        relays = updated
                        GstConfigManager.saveRelays(context, updated)
                    },
                    enabled = canAddMore,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmergencyColors.GoogleSurface,
                        disabledContainerColor = EmergencyColors.GoogleSurface.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = EmergencyColors.GoogleBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("افزودن رله جدید", color = EmergencyColors.GoogleBlue, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Connect Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    if (isVpnRunning) {
                        val stopIntent = Intent(context, MyVpnService::class.java).apply { action = "STOP" }
                        context.startService(stopIntent)
                    } else {
                        val hasValidRelay = relays.any { it.deploymentId.isNotBlank() }
                        if (!hasValidRelay) {
                            android.widget.Toast.makeText(context, "لطفاً شناسه استقرار (Deployment ID) را وارد کنید", android.widget.Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        
                        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        val isProxyMode = prefs.getBoolean("proxy_mode", false)
                        val localPort = prefs.getString("local_port", "10808")

                        val startIntent = Intent(context, MyVpnService::class.java).apply {
                            putExtra("NODE_URI", "{\"type\":\"gst\"}")
                            putExtra("NODE_ID", "GST_EMERGENCY")
                            putExtra("PROXY_MODE", isProxyMode)
                            putExtra("LOCAL_PORT", localPort)
                        }
                        context.startService(startIntent)
                    }
                },
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isVpnRunning) EmergencyColors.GoogleRed else EmergencyColors.GoogleBlue
                )
            ) {
                Text(
                    text = if (isVpnRunning) "قطع اتصال" else "اتصال",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }

    if (showScriptDialog) {
        val currentKey = relays.firstOrNull()?.authKey ?: ""
        GoogleScriptDialog(authKey = currentKey, onDismiss = { showScriptDialog = false })
    }

    if (showSettingsDialog) {
        AdvancedScannerDialog(onDismiss = { showSettingsDialog = false })
    }
}

@Composable
fun GoogleScriptDialog(authKey: String, onDismiss: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scriptCode = remember(authKey) {
        try {
            var rawScript = context.assets.open("gst/Code.gs").bufferedReader().use { it.readText() }
            if (authKey.isNotEmpty()) {
                rawScript = rawScript.replace("CHANGE_ME_TO_A_STRONG_SECRET", authKey)
            }
            rawScript
        } catch (e: Exception) {
            "// خطا در بارگذاری اسکریپت"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmergencyColors.GoogleSurface,
        title = {
            Text("اسکریپت سرور گوگل", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "این کد را در پروژه Google Apps Script کپی کنید.\n\n" +
                    "⚠️ بسیار مهم: هنگام استقرار (Deploy) حتماً تنظیمات زیر را اعمال کنید:\n" +
                    "1. Execute as: Me\n" +
                    "2. Who has access: Anyone\n\n" +
                    "اگر ارور 401 دریافت می‌کنید، دلیل آن عدم رعایت موارد بالاست.",
                    color = EmergencyColors.GoogleMuted,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(scriptCode, color = Color.Green, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(scriptCode))
                    android.widget.Toast.makeText(context, "کپی شد", android.widget.Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleBlue)
            ) {
                Text("کپی کردن اسکریپت")
            }
        },
        dismissButton = {
            TextButton(onClick = { /* Open Video URL */ }) {
                Text("مشاهده ویدیو آموزشی", color = EmergencyColors.GoogleBlue)
            }
        }
    )
}

private fun isCaInstalled(context: android.content.Context): Boolean {
    try {
        val caFile = java.io.File(context.filesDir, "ca/ca.crt")
        if (!caFile.exists()) return false

        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val ourCert = cf.generateCertificate(caFile.inputStream()) as java.security.cert.X509Certificate
        val ourFingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(ourCert.encoded)

        val ks = java.security.KeyStore.getInstance("AndroidCAStore")
        ks.load(null)
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val cert = ks.getCertificate(alias) ?: continue
            val encoded = try { cert.encoded } catch (e: Exception) { continue }
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
            if (fingerprint.contentEquals(ourFingerprint)) return true
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return false
}

@Composable
fun AdvancedScannerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val allSnis = GstConfigManager.DEFAULT_SNI_LIST
    val allIps = GstConfigManager.DEFAULT_IP_LIST
    
    val selectedSnis = remember { mutableStateListOf(*GstConfigManager.getSelectedSniList(context).toTypedArray()) }
    val selectedIps = remember { mutableStateListOf(*GstConfigManager.getSelectedCleanIpList(context).toTypedArray()) }
    
    val pings = remember { mutableStateMapOf<String, Int>() }
    var isScanning by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    
    val coroutineScope = rememberCoroutineScope()
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.8f).padding(top = com.mlmvpn.scanner.ui.LocalSystemTopPadding.current, bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current),
            shape = RoundedCornerShape(16.dp),
            color = EmergencyColors.GoogleSurface
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("اسکنر پیشرفته SNI و IP", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = EmergencyColors.GoogleText)
                Spacer(modifier = Modifier.height(16.dp))
                
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = EmergencyColors.GoogleBlue
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("SNI ها") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("آی‌پی‌ها") })
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(modifier = Modifier.weight(1f)) {
                    val itemsList = if (selectedTab == 0) allSnis else allIps
                    val selectedList = if (selectedTab == 0) selectedSnis else selectedIps
                    
                    itemsIndexed(itemsList) { _, item ->
                        val ping = pings[item]
                        val isChecked = selectedList.contains(item)
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (isChecked) selectedList.remove(item) else selectedList.add(item)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) selectedList.add(item) else selectedList.remove(item)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = EmergencyColors.GoogleBlue)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item, color = EmergencyColors.GoogleText, fontSize = 14.sp)
                                val desc = GstConfigManager.ITEM_DESCRIPTIONS[item]
                                if (desc != null) {
                                    Text(desc, color = EmergencyColors.GoogleMuted, fontSize = 10.sp)
                                }
                            }
                            
                            if (ping != null) {
                                val color = if (ping > 0 && ping < 200) Color.Green else if (ping > 0 && ping < 9999) Color.Yellow else Color.Red
                                Text(if (ping > 0 && ping < 9999) "${ping}ms" else "Timeout", color = color, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(
                        onClick = {
                            if (isScanning) return@Button
                            isScanning = true
                            coroutineScope.launch {
                                val jobs = (allSnis + allIps).map { target ->
                                    async(Dispatchers.IO) {
                                        val time = tlsPing(target, 443, if (allSnis.contains(target)) target else "www.google.com")
                                        pings[target] = time
                                    }
                                }
                                jobs.awaitAll()
                                isScanning = false
                            }
                        },
                        enabled = !isScanning,
                        colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleBlue)
                    ) {
                        Text(if (isScanning) "در حال اسکن..." else "اسکن همه")
                    }
                    
                    Button(
                        onClick = {
                            GstConfigManager.saveSelectedSniList(context, selectedSnis)
                            GstConfigManager.saveSelectedCleanIpList(context, selectedIps)
                            android.widget.Toast.makeText(context, "ذخیره شد", android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleGreen)
                    ) {
                        Text("ذخیره و خروج")
                    }
                }
            }
        }
    }
}
