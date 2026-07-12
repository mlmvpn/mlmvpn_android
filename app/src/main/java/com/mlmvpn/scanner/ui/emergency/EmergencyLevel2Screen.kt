package com.mlmvpn.scanner.ui.emergency

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.mlmvpn.scanner.auth.GoogleAuthManager
import com.mlmvpn.scanner.data.CloudManager
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Science
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
    var showLogDialog by remember { mutableStateOf(false) }
    var showCertRequiredDialog by remember { mutableStateOf(false) }
    var isCertInstalled by remember { mutableStateOf(false) }
    var showTooltip by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var showBatchAuthDialog by remember { mutableStateOf(false) }
    var needAuthUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    val isVpnRunning by MyVpnService.isRunningFlow.collectAsState()
    val screenScope = rememberCoroutineScope()

    // Starts the GST tunnel service, mirroring the app's Proxy Mode + Local Port settings.
    val startGstService: () -> Unit = {
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

    // VPN consent flow � every other connect path (WarpTab/GameTab/NodesTab) does this;
    // without it establish() returns null → no TUN → no VPN key icon.
    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res -> if (res.resultCode == Activity.RESULT_OK) startGstService() }
    
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
            ) { /* consume clicks � prevent pass-through to the tab behind */ }
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
                    IconButton(onClick = { installCaCertificate(context) }) {
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
            
            IconButton(
                enabled = !isTesting,
                onClick = {
                    val valid = relays.filter { it.deploymentId.isNotBlank() }
                    if (valid.isEmpty()) {
                        android.widget.Toast.makeText(context, "ابتدا حداقل یک Deployment ID وارد کنید", android.widget.Toast.LENGTH_SHORT).show()
                        return@IconButton
                    }
                    isTesting = true
                    screenScope.launch {
                        com.mlmvpn.scanner.engines.gst.GstLog.i("RelayTest", "شروع تست ${valid.size} رله...")
                        // Test all relays in parallel so hundreds finish quickly.
                        val reports = valid.mapIndexed { i, r ->
                            async(Dispatchers.IO) {
                                val rep = com.mlmvpn.scanner.engines.gst.GstDiagnostics.testDeployment(
                                    com.mlmvpn.scanner.engines.gst.GstDiagnostics.execUrl(r.deploymentId),
                                    r.authKey
                                )
                                com.mlmvpn.scanner.engines.gst.GstLog.i("RelayTest", "رله ${i + 1}: ${rep.message}")
                                r to rep
                            }
                        }.awaitAll()
                        val okCount = reports.count { it.second.result == com.mlmvpn.scanner.engines.gst.GstDiagnostics.Result.OK }
                        // Collect /exec URLs of relays that only need the one-time authorization.
                        needAuthUrls = reports
                            .filter { it.second.result == com.mlmvpn.scanner.engines.gst.GstDiagnostics.Result.REDIRECT_BLOCKED }
                            .map { com.mlmvpn.scanner.engines.gst.GstDiagnostics.execUrl(it.first.deploymentId) }
                        isTesting = false
                        android.widget.Toast.makeText(context, "✅ $okCount از ${valid.size} رله سالم است", android.widget.Toast.LENGTH_LONG).show()
                        if (needAuthUrls.isNotEmpty()) showBatchAuthDialog = true else showLogDialog = true
                    }
                }
            ) {
                Icon(Icons.Default.Science, contentDescription = "Test relays", tint = EmergencyColors.GoogleGreen)
            }

            IconButton(onClick = { showLogDialog = true }) {
                Icon(Icons.Default.Description, contentDescription = "Logs", tint = EmergencyColors.GoogleMuted)
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
            item {
                GoogleAutoDeployCard(
                    authKey = relays.firstOrNull()?.authKey ?: "",
                    onDeployed = { relays = GstConfigManager.getRelays(context) }
                )
            }

            item {
                CloudflareAccelCard(authKey = relays.firstOrNull()?.authKey ?: "")
            }

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
                Button(
                    onClick = {
                        val sharedKey = relays.firstOrNull()?.authKey
                            ?.takeIf { it.isNotEmpty() }
                            ?: java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                        val updated = relays.toMutableList()
                        updated.add(GstRelay(deploymentId = "", authKey = sharedKey))
                        relays = updated
                        GstConfigManager.saveRelays(context, updated)
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = EmergencyColors.GoogleBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("افزودن رله جدید (دستی)", color = EmergencyColors.GoogleBlue, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Manual path: get the Apps Script code to paste at script.google.com yourself.
                TextButton(
                    onClick = { showScriptDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = EmergencyColors.GoogleMuted)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("دریافت کد اسکریپت (نصب دستی)", color = EmergencyColors.GoogleMuted, fontSize = 13.sp)
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

                        // The GST core MITMs HTTPS, so without the trusted CA installed no
                        // HTTPS traffic can flow � connecting would be pointless. Warn and
                        // guide the user to install it instead of starting a dead tunnel.
                        if (!isCertInstalled) {
                            showCertRequiredDialog = true
                            return@Button
                        }

                        // Request VPN consent first (like every other connect path). Only
                        // needed for TUN mode; in proxy mode prepare() is a harmless no-op.
                        val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
                        if (prep != null) {
                            vpnPrepareLauncher.launch(prep)
                        } else {
                            startGstService()
                        }
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

    if (showLogDialog) {
        GstLogDialog(onDismiss = { showLogDialog = false })
    }

    if (showBatchAuthDialog) {
        AlertDialog(
            onDismissRequest = { showBatchAuthDialog = false },
            containerColor = EmergencyColors.GoogleSurface,
            title = { Text("${needAuthUrls.size} رله نیاز به تأیید دارند", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "این رله‌ها ساخته شده‌اند اما هنوز تأیید (Authorize) نشده‌اند. برای هرکدام دکمه‌ی زیر " +
                            "را بزنید، در مرورگر با همان حساب گوگل وارد شوید و Review Permissions → Advanced → Allow را بزنید. " +
                            "بعد دوباره «تست رله» را بزنید.",
                        color = EmergencyColors.GoogleMuted, fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    needAuthUrls.forEachIndexed { i, url ->
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                try { context.startActivity(intent) } catch (_: Exception) {}
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleBlue)
                        ) { Text("تأیید رله ${i + 1}", color = Color.White) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBatchAuthDialog = false }) {
                    Text("بستن", color = EmergencyColors.GoogleMuted)
                }
            }
        )
    }

    if (showCertRequiredDialog) {
        val caExists = java.io.File(context.filesDir, "ca/ca.crt").exists()
        AlertDialog(
            onDismissRequest = { showCertRequiredDialog = false },
            containerColor = EmergencyColors.GoogleSurface,
            title = { Text("گواهینامه نصب نشده", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (caExists)
                        "برای عبور امن ترافیک، ابتدا باید گواهی امنیتی (CA) را نصب کنید. بدون آن، " +
                            "هیچ سایت HTTPSی باز نمی‌شود. دکمه‌ی زیر گواهی را ذخیره و صفحه‌ی نصب را باز می‌کند."
                    else
                        "گواهی هنوز ساخته نشده است. یک‌بار برای چند ثانیه متصل شوید تا گواهی ساخته شود، " +
                            "سپس آن را نصب کنید.",
                    color = EmergencyColors.GoogleMuted, fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCertRequiredDialog = false
                        installCaCertificate(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleBlue)
                ) { Text(if (caExists) "نصب گواهینامه" else "باشه") }
            },
            dismissButton = {
                TextButton(onClick = { showCertRequiredDialog = false }) {
                    Text("بستن", color = EmergencyColors.GoogleMuted)
                }
            }
        )
    }
}

/**
 * Exports the MITM CA (generated by the GST core at filesDir/ca/ca.crt) to Downloads and
 * opens the system security settings so the user can install it as a trusted CA. Shared
 * by the toolbar shield button and the "certificate required" connect guard.
 */
fun installCaCertificate(context: android.content.Context) {
    try {
        val caFile = java.io.File(context.filesDir, "ca/ca.crt")
        if (!caFile.exists()) {
            android.widget.Toast.makeText(context, "گواهی ساخته نشده! یکبار دکمه اتصال را بزنید.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
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
                resolver.openOutputStream(u)?.use { out -> out.write(caFile.readBytes()) }
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
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "خطا: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun GoogleAutoDeployCard(authKey: String, onDeployed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(GoogleAuthManager.lastAccountEmail(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showApiEnableDialog by remember { mutableStateOf(false) }
    var showAuthorizeDialog by remember { mutableStateOf(false) }
    var deployedExecUrl by remember { mutableStateOf("") }

    // Non-observable holder so the ActivityResult callbacks (created first) can invoke
    // the deploy flow without writing to State during composition.
    val proceedRef = remember { arrayOfNulls<(android.accounts.Account) -> Unit>(1) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val account = GoogleAuthManager.lastAccount(context)
        if (account != null) proceedRef[0]?.invoke(account) else { busy = false; status = "" }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val acct = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            email = acct.email
            val account = acct.account
            if (account != null) proceedRef[0]?.invoke(account)
            else { busy = false; status = "" }
        } catch (e: Exception) {
            busy = false; status = ""
            android.widget.Toast.makeText(context, "ورود گوگل ناموفق بود: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    proceedRef[0] = { account ->
        scope.launch {
            when (val tok = GoogleAuthManager.fetchAccessToken(context, account)) {
                is GoogleAuthManager.TokenResult.NeedsConsent -> consentLauncher.launch(tok.intent)
                is GoogleAuthManager.TokenResult.Error -> {
                    busy = false; status = ""
                    android.widget.Toast.makeText(context, "خطای احراز هویت: ${tok.message}", android.widget.Toast.LENGTH_LONG).show()
                }
                is GoogleAuthManager.TokenResult.Success -> {
                    // Tunnel the deploy through the Cloudflare relay worker when available,
                    // so it works on networks where Google Cloud APIs are geo/sanctions-blocked.
                    val relayUrl = GstConfigManager.getRelayUrls(context).firstOrNull()
                    val res = com.mlmvpn.scanner.engines.gst.GstAutoDeployer.deploy(
                        context, tok.token, authKey,
                        relayUrl = relayUrl,
                        relayAuthKey = GstConfigManager.getOrCreateRelayProxyKey(context)
                    ) { _, label -> status = label }
                    busy = false; status = ""
                    if (res.success) {
                        onDeployed()
                        // API-created web apps still need the deploying user to authorize
                        // the script's scopes once (the "Review Permissions" step the manual
                        // flow shows). Without it, every relay request gets HTTP 403. Guide
                        // the user to complete it in a browser.
                        deployedExecUrl = res.deploymentId?.let {
                            com.mlmvpn.scanner.engines.gst.GstDiagnostics.execUrl(it)
                        } ?: ""
                        showAuthorizeDialog = true
                    } else if (res.needsApiEnable) {
                        // Guide the user through the mandatory one-time per-account step.
                        showApiEnableDialog = true
                    } else {
                        android.widget.Toast.makeText(context, res.message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = EmergencyColors.GoogleSurface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, EmergencyColors.GoogleSurface2)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("نصب خودکار با گوگل", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (email != null) "حساب: $email" else "بدون کپی دستی؛ اپ خودش اسکریپت را می‌سازد و Deploy می‌کند.",
                color = EmergencyColors.GoogleMuted, fontSize = 12.sp
            )
            if (status.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(status, color = EmergencyColors.GoogleBlue, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    busy = true
                    status = "انتخاب حساب گوگل..."
                    // Always show the Google account chooser so the user can pick a
                    // DIFFERENT account each time and stack scripts across multiple Google
                    // accounts (each gives its own 20k/day quota � like mhrv). Signing out
                    // first forces the picker (and lets them add a new account).
                    val client = GoogleAuthManager.signInClient(context)
                    client.signOut().addOnCompleteListener {
                        signInLauncher.launch(client.signInIntent)
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleBlue)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (busy) "در حال استقرار..." else "افزودن حساب گوگل و Deploy", color = Color.White)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "می‌توانید چند حساب گوگل متصل کنید؛ هر حساب سهمیه‌ی جدا (۲۰هزار/روز) دارد و همه با هم استفاده می‌شوند.",
                color = EmergencyColors.GoogleMuted, fontSize = 11.sp
            )
        }
    }

    if (showAuthorizeDialog) {
        AlertDialog(
            onDismissRequest = { showAuthorizeDialog = false },
            containerColor = EmergencyColors.GoogleSurface,
            title = { Text("یک قدم آخر: تأیید مجوز اسکریپت", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "اسکریپت ساخته شد ✅ اما برای اینکه بتواند ترافیک را رله کند، باید یک‌بار " +
                            "به آن مجوز بدهید (همان مرحله‌ای که در نصب دستی هست).",
                        color = EmergencyColors.GoogleText, fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "۱. دکمه‌ی زیر را بزنید تا در مرورگر باز شود.\n" +
                            "۲. با همان حساب گوگل" + (if (email != null) " ($email)" else "") + " وارد شوید.\n" +
                            "۳. REVIEW PERMISSIONS → Advanced → Go to � (unsafe) → Allow.\n" +
                            "۴. برگردید و دکمه‌ی «تست رله» (🧪) را بزنید.",
                        color = EmergencyColors.GoogleMuted, fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (deployedExecUrl.isNotEmpty()) {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(deployedExecUrl))
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            try { context.startActivity(intent) } catch (_: Exception) {}
                        }
                        showAuthorizeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleBlue)
                ) { Text("باز کردن و تأیید مجوز") }
            },
            dismissButton = {
                TextButton(onClick = { showAuthorizeDialog = false }) {
                    Text("بعداً", color = EmergencyColors.GoogleMuted)
                }
            }
        )
    }

    if (showApiEnableDialog) {
        AlertDialog(
            onDismissRequest = { showApiEnableDialog = false },
            containerColor = EmergencyColors.GoogleSurface,
            title = { Text("یک قدم لازم: فعال‌سازی دسترسی", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "برای اینکه اپ بتواند به‌جای شما اسکریپت را بسازد، باید یک‌بار دسترسی " +
                            "�Google Apps Script API� را در حساب گوگل خودتان روشن کنید:",
                        color = EmergencyColors.GoogleText, fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "۱. دکمه‌ی زیر را بزنید تا صفحه‌ی تنظیمات گوگل باز شود.\n" +
                            "۲. مطمئن شوید با همان حسابی وارد هستید که در اپ انتخاب کردید" +
                            (if (email != null) " ($email)" else "") + ".\n" +
                            "۳. سوییچ �Google Apps Script API� را روی ON بگذارید.\n" +
                            "۴. به اپ برگردید، ۱ تا ۲ دقیقه صبر کنید و «فعال کردم، دوباره تلاش» را بزنید.",
                        color = EmergencyColors.GoogleMuted, fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(GoogleAuthManager.USER_SETTINGS_URL))
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        try { context.startActivity(intent) } catch (_: Exception) {
                            android.widget.Toast.makeText(context, "لینک: ${GoogleAuthManager.USER_SETTINGS_URL}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleBlue)
                ) { Text("باز کردن صفحه فعال‌سازی") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showApiEnableDialog = false
                    val account = GoogleAuthManager.lastAccount(context)
                    if (account != null) {
                        busy = true; status = "در حال تلاش دوباره..."
                        proceedRef[0]?.invoke(account)
                    }
                }) { Text("فعال کردم، دوباره تلاش", color = EmergencyColors.GoogleGreen) }
            }
        )
    }
}

@Composable
fun CloudflareAccelCard(authKey: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud = remember { CloudManager(context) }
    val accounts by cloud.accountsFlow.collectAsState()

    var enabled by remember { mutableStateOf(GstConfigManager.getRelayUrls(context).isNotEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = EmergencyColors.GoogleSurface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, EmergencyColors.GoogleSurface2)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("پروکسی Cloudflare (برای نصب)", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("برای عبور از تحریم هنگام ساخت خودکار اسکریپت گوگل", color = EmergencyColors.GoogleMuted, fontSize = 11.sp)
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = EmergencyColors.GoogleBlue,
                        strokeWidth = 2.dp
                    )
                } else {
                    Switch(
                        checked = enabled,
                        onCheckedChange = onCheckedChange@{ want ->
                            if (!want) {
                                enabled = false
                                GstConfigManager.saveRelayUrls(context, emptyList())
                                status = "غیرفعال شد"
                                return@onCheckedChange
                            }
                            // Always (re)deploy the worker with the CURRENT auth key so the
                            // worker's AUTH_KEY binding always matches the key the app sends
                            // when using it as a proxy. Reusing a stale worker deployed with
                            // an older key causes the worker to reject requests with its
                            // decoy page (seen as HTTP 502 "did not return anything").
                            val acct = accounts.firstOrNull()
                            if (acct == null) {
                                android.widget.Toast.makeText(context, "ابتدا یک حساب Cloudflare در بخش ابری اضافه کنید", android.widget.Toast.LENGTH_LONG).show()
                                return@onCheckedChange
                            }
                            // Optimistically show ON while the worker deploys; revert on failure.
                            enabled = true
                            busy = true
                            status = "در حال استقرار Worker..."
                            scope.launch {
                                // Deploy the proxy worker with the STABLE proxy key (not the
                                // relay auth key) so it always matches what the deployer sends.
                                val proxyKey = GstConfigManager.getOrCreateRelayProxyKey(context)
                                val (ok, msg) = cloud.deployGstRelayWorker(acct, proxyKey) { _, label -> status = label }
                                busy = false
                                if (ok) {
                                    enabled = true
                                    GstConfigManager.saveRelayUrls(context, listOf(msg))
                                    status = "فعال شد ✅"
                                } else {
                                    enabled = false
                                    status = "خطا: $msg"
                                    android.widget.Toast.makeText(context, "استقرار Worker ناموفق: $msg", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = EmergencyColors.GoogleBlue,
                            checkedBorderColor = EmergencyColors.GoogleBlue,
                            uncheckedThumbColor = EmergencyColors.GoogleMuted,
                            uncheckedTrackColor = EmergencyColors.GoogleSurface2,
                            uncheckedBorderColor = EmergencyColors.GoogleSurface2
                        )
                    )
                }
            }
            if (status.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(status, color = EmergencyColors.GoogleBlue, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun GstLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val entries by com.mlmvpn.scanner.engines.gst.GstLog.lines.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmergencyColors.GoogleSurface,
        title = {
            Text("لاگ زنده‌ی رله گوگل", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold)
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                if (entries.isEmpty()) {
                    Text("هنوز لاگی ثبت نشده. «تست رله» را بزنید یا متصل شوید.",
                        color = EmergencyColors.GoogleMuted, fontSize = 12.sp)
                } else {
                    Column {
                        entries.forEach { e ->
                            val color = when (e.level) {
                                com.mlmvpn.scanner.engines.gst.GstLog.Level.E -> Color(0xFFFF6B6B)
                                com.mlmvpn.scanner.engines.gst.GstLog.Level.W -> Color(0xFFFFD166)
                                com.mlmvpn.scanner.engines.gst.GstLog.Level.I -> Color(0xFF8AB4F8)
                                else -> Color(0xFF9AA0A6)
                            }
                            Text(e.format(), color = color, fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(
                        com.mlmvpn.scanner.engines.gst.GstLog.dump()))
                    android.widget.Toast.makeText(context, "لاگ کپی شد", android.widget.Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleBlue)
            ) { Text("کپی لاگ") }
        },
        dismissButton = {
            TextButton(onClick = { com.mlmvpn.scanner.engines.gst.GstLog.clear() }) {
                Text("پاک کردن", color = EmergencyColors.GoogleMuted)
            }
        }
    )
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "راهنمای نصب دستی اسکریپت:",
                    color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "۱. دکمه‌ی «کپی کردن اسکریپت» را بزنید (کلید امنیتی از قبل داخل کد قرار گرفته).\n\n" +
                    "۲. در مرورگر به script.google.com بروید و �New project� را بزنید.\n\n" +
                    "۳. کد پیش‌فرض را پاک کنید و کد کپی‌شده را Paste کنید، سپس ذخیره (Ctrl+S).\n\n" +
                    "۴. روی �Deploy� → �New deployment� بزنید و این تنظیمات را دقیقاً اعمال کنید:\n" +
                    "    � Select type: Web app\n" +
                    "    � Execute as: Me\n" +
                    "    � Who has access: Anyone\n\n" +
                    "۵. �Deploy� را بزنید، سپس Authorize کنید:\n" +
                    "    Review Permissions → Advanced → Go to � (unsafe) → Allow\n\n" +
                    "۶. �Deployment ID� را کپی کنید (رشته‌ای که با AKfy� شروع می‌شود).\n\n" +
                    "۷. به اپ برگردید → «افزودن رله جدید (دستی)� → Deployment ID را در فیلد بالای کارت وارد کنید (Auth Key از قبل پر است).\n\n" +
                    "۸. دکمه‌ی 🧪 «تست رله» را بزنید؛ اگر ✅ شد، «اتصال» را بزنید.\n\n" +
                    "⚠️ اگر خطای ۴۰۱ یا ۴۰۳ گرفتید، یعنی مرحله‌ی ۴ (Anyone/Me) یا مرحله‌ی ۵ (Authorize) درست انجام نشده.",
                    color = EmergencyColors.GoogleMuted, fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("کد اسکریپت:", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
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
