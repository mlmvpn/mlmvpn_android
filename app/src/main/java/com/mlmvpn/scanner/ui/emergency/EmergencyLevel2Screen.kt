package com.mlmvpn.scanner.ui.emergency

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mlmvpn.scanner.ui.tlsPing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.therealaleph.mhrv.Native
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyLevel2Screen(onBack: () -> Unit) {
    val context = LocalContext.current
    var relays by remember { mutableStateOf(GstConfigManager.getRelays(context)) }

    // First-run / empty state opens the step-by-step wizard directly.
    var showWizard by remember { mutableStateOf(relays.none { it.deploymentId.isNotBlank() }) }
    var wizardEditIndex by remember { mutableStateOf<Int?>(null) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isCertInstalled by remember { mutableStateOf(false) }
    var isPriming by remember { mutableStateOf(false) }
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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { isCertInstalled = isCaInstalled(context) }

    // Batch-test every configured relay in parallel; collects the ones needing a one-time
    // browser authorization so the user can finish them.
    val testAllRelays: () -> Unit = {
        val valid = relays.filter { it.deploymentId.isNotBlank() }
        if (valid.isEmpty()) {
            android.widget.Toast.makeText(context, "ابتدا حداقل یک Deployment ID وارد کنید", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            isTesting = true
            screenScope.launch {
                com.mlmvpn.scanner.engines.gst.GstLog.i("RelayTest", "شروع تست ${valid.size} رله...")
                val reports = valid.mapIndexed { i, r ->
                    async(Dispatchers.IO) {
                        val rep = com.mlmvpn.scanner.engines.gst.GstDiagnostics.testDeployment(
                            com.mlmvpn.scanner.engines.gst.GstDiagnostics.execUrl(r.deploymentId), r.authKey
                        )
                        com.mlmvpn.scanner.engines.gst.GstLog.i("RelayTest", "رله ${i + 1}: ${rep.message}")
                        r to rep
                    }
                }.awaitAll()
                val okCount = reports.count { it.second.result == com.mlmvpn.scanner.engines.gst.GstDiagnostics.Result.OK }
                needAuthUrls = reports
                    .filter { it.second.result == com.mlmvpn.scanner.engines.gst.GstDiagnostics.Result.REDIRECT_BLOCKED }
                    .map { com.mlmvpn.scanner.engines.gst.GstDiagnostics.execUrl(it.first.deploymentId) }
                isTesting = false
                android.widget.Toast.makeText(context, "✅ $okCount از ${valid.size} رله سالم است", android.widget.Toast.LENGTH_LONG).show()
                if (needAuthUrls.isNotEmpty()) showBatchAuthDialog = true else showLogDialog = true
            }
        }
    }

    // ---- Wizard takes over the whole screen on first run / when adding-editing a relay ----
    if (showWizard) {
        GstSetupWizard(
            sharedAuthKey = relays.firstOrNull()?.authKey ?: "",
            editRelay = wizardEditIndex?.let { relays.getOrNull(it) },
            onComplete = { relay ->
                val updated = relays.toMutableList()
                val idx = wizardEditIndex
                if (idx != null && idx < updated.size) {
                    updated[idx] = relay
                } else {
                    val blankIdx = updated.indexOfFirst { it.deploymentId.isBlank() }
                    if (blankIdx >= 0) updated[blankIdx] = relay else updated.add(relay)
                }
                relays = updated
                GstConfigManager.saveRelays(context, updated)
                wizardEditIndex = null
                showWizard = false
            },
            onClose = {
                showWizard = false
                wizardEditIndex = null
                // If they cancelled the very first setup with nothing configured, leave the screen.
                if (relays.none { it.deploymentId.isNotBlank() }) onBack()
            }
        )
        return
    }

    // Animate the connect button ONLY while connected (see git history: an unconditional
    // infiniteRepeatable kept requesting frames after disconnect → native RenderThread crash).
    val scale = if (isVpnRunning) {
        val infiniteTransition = rememberInfiniteTransition(label = "connectPulse")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "connectPulseScale"
        ).value
    } else 1f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EmergencyColors.GoogleBg)
            .padding(top = com.mlmvpn.scanner.ui.LocalSystemTopPadding.current, bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { }
    ) {
        // TopBar: back + title + single overflow menu (replaces the old confusing icon row).
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
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "منو", tint = EmergencyColors.GoogleMuted)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(EmergencyColors.GoogleSurface)
                ) {
                    DropdownMenuItem(
                        text = { Text("تست اتصال همه‌ی رله‌ها", color = EmergencyColors.GoogleText) },
                        leadingIcon = { Icon(Icons.Default.Science, null, tint = EmergencyColors.GoogleGreen) },
                        enabled = !isTesting,
                        onClick = { showMenu = false; testAllRelays() }
                    )
                    DropdownMenuItem(
                        text = { Text("گزارش زنده", color = EmergencyColors.GoogleText) },
                        leadingIcon = { Icon(Icons.Default.Description, null, tint = EmergencyColors.GoogleMuted) },
                        onClick = { showMenu = false; showLogDialog = true }
                    )
                    DropdownMenuItem(
                        text = { Text("تنظیمات پیشرفته (SNI/IP)", color = EmergencyColors.GoogleText) },
                        leadingIcon = { Icon(Icons.Default.Settings, null, tint = EmergencyColors.GoogleMuted) },
                        onClick = { showMenu = false; showSettingsDialog = true }
                    )
                    DropdownMenuItem(
                        text = { Text("دریافت / ویرایش کد اسکریپت", color = EmergencyColors.GoogleText) },
                        leadingIcon = { Icon(Icons.Default.Code, null, tint = EmergencyColors.GoogleBlue) },
                        onClick = { showMenu = false; wizardEditIndex = relays.indexOfFirst { it.deploymentId.isNotBlank() }.takeIf { it >= 0 }; showWizard = true }
                    )
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Certificate status + one-tap install that works WITHOUT connecting first
            // (primes the CA by briefly running the core in proxy mode — see primeAndInstallCa).
            item {
                CertificateCard(
                    isInstalled = isCertInstalled,
                    isBusy = isPriming,
                    onInstall = {
                        isPriming = true
                        screenScope.launch {
                            val ok = primeCaCertificate(context)
                            isPriming = false
                            if (ok) installCaCertificate(context)
                            else android.widget.Toast.makeText(context, "ساخت گواهی ناموفق بود؛ یک‌بار «اتصال» را بزنید و دوباره تلاش کنید.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            itemsIndexed(relays) { index, relay ->
                DynamicRelayCard(
                    deploymentId = relay.deploymentId,
                    isActive = isVpnRunning && index == 0,
                    onEdit = { wizardEditIndex = index; showWizard = true },
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
                    onClick = { wizardEditIndex = null; showWizard = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = EmergencyColors.GoogleBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("افزودن رله جدید (حساب گوگل دیگر)", color = EmergencyColors.GoogleBlue, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Connect Button
        Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    if (isVpnRunning) {
                        // Just stop. This used to be followed by a deliberate relaunch of the whole
                        // app, because the tun2proxy core calls exit(255) from native code a few
                        // seconds after a normal teardown and the app would otherwise vanish to the
                        // launcher on its own. tun2proxy now runs in the :tun process
                        // (Tun2proxyHostService), so that exit takes down only that process and
                        // this one carries on — no restart to stage, nothing for the user to see.
                        val stopIntent = Intent(context, MyVpnService::class.java).apply { action = "STOP" }
                        context.startService(stopIntent)
                        android.widget.Toast.makeText(context, "در حال قطع اتصال…", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val hasValidRelay = relays.any { it.deploymentId.isNotBlank() }
                        if (!hasValidRelay) {
                            android.widget.Toast.makeText(context, "لطفاً شناسه استقرار (Deployment ID) را وارد کنید", android.widget.Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!isCertInstalled) {
                            android.widget.Toast.makeText(
                                context,
                                "در حال اتصال… اگر سایت‌های HTTPS باز نشدند، گواهی امنیتی را از کارت بالای صفحه نصب کنید.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
                        if (prep != null) vpnPrepareLauncher.launch(prep) else startGstService()
                    }
                },
                modifier = Modifier.size(120.dp).scale(scale),
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
                            "بعد دوباره «تست اتصال همه‌ی رله‌ها» را بزنید.",
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
}

@Composable
private fun CertificateCard(isInstalled: Boolean, isBusy: Boolean, onInstall: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = EmergencyColors.GoogleSurface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, EmergencyColors.GoogleSurface2)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isInstalled) Icons.Default.VerifiedUser else Icons.Default.Security,
                contentDescription = null,
                tint = if (isInstalled) EmergencyColors.GoogleGreen else EmergencyColors.GoogleRed
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isInstalled) "گواهی امنیتی نصب شده" else "گواهی امنیتی نصب نشده",
                    color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
                Text(
                    if (isInstalled) "سایت‌های HTTPS به‌درستی باز می‌شوند." else "برای باز شدن سایت‌های HTTPS لازم است.",
                    color = EmergencyColors.GoogleMuted, fontSize = 12.sp
                )
            }
            if (!isInstalled) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = EmergencyColors.GoogleBlue, strokeWidth = 2.dp)
                } else {
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(containerColor = EmergencyColors.GoogleBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("نصب", color = Color.White) }
                }
            }
        }
    }
}

/**
 * Ensures the MITM CA (filesDir/ca/ca.crt) exists so it can be installed WITHOUT the user
 * having to run a full VPN connection first. The CA is only minted as a side effect of the
 * native core starting, so if it's missing we briefly boot the core in proxy mode (localhost
 * listeners only — no VpnService/TUN, hence no consent and none of the tun2proxy teardown),
 * wait for the cert file to appear, then stop. No-op (returns true) once the cert exists.
 */
suspend fun primeCaCertificate(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
    val caFile = java.io.File(context.filesDir, "ca/ca.crt")
    if (caFile.exists()) return@withContext true
    // Don't touch the core while a real tunnel is running.
    if (MyVpnService.isRunningFlow.value) return@withContext caFile.exists()
    var handle = 0L
    try {
        Native.setDataDir(context.filesDir.absolutePath)
        // Minimal config on an unlikely-to-conflict port; a dummy script_id is fine because
        // we only need the core to boot far enough to mint the CA, not to relay traffic.
        val cfg = """
            [relay]
            mode = "apps_script"
            script_id = ["CA_PRIMING_PLACEHOLDER"]
            auth_key = "ca_priming"
            youtube_via_relay = true

            [network]
            google_ip = "${GstConfigManager.DEFAULT_GOOGLE_IP}"
            front_domain = "www.google.com"
            listen_host = "127.0.0.1"
            socks5_port = 39917
            listen_port = 49917
            verify_ssl = true

            [logging]
            log_level = "error"
        """.trimIndent()
        handle = Native.startProxy(cfg)
        var waited = 0
        while (!caFile.exists() && waited < 3000) {
            kotlinx.coroutines.delay(100); waited += 100
        }
    } catch (e: Exception) {
        com.mlmvpn.scanner.engines.gst.GstLog.e("CertPrime", "priming failed: ${e.message}")
    } finally {
        if (handle != 0L) try { Native.stopProxy(handle) } catch (_: Exception) {}
    }
    caFile.exists()
}

/**
 * Exports the MITM CA (generated by the GST core at filesDir/ca/ca.crt) to Downloads and
 * opens the system security settings so the user can install it as a trusted CA.
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
fun GstLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val entries by com.mlmvpn.scanner.engines.gst.GstLog.lines.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmergencyColors.GoogleSurface,
        title = { Text("لاگ زنده‌ی رله گوگل", color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold) },
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
                    Text("هنوز لاگی ثبت نشده. «تست اتصال همه‌ی رله‌ها» را بزنید یا متصل شوید.",
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

@OptIn(ExperimentalMaterial3Api::class)
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
