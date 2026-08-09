package com.mlmvpn.scanner.ui.sanction

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.emergency.EmergencyColors
import com.mlmvpn.scanner.engines.sanction.AntiSanctionManager
import kotlinx.coroutines.launch

private val Bg = EmergencyColors.GoogleBg
private val Surface = EmergencyColors.GoogleSurface
private val Surface2 = EmergencyColors.GoogleSurface2
private val TextC = EmergencyColors.GoogleText
private val Muted = EmergencyColors.GoogleMuted
private val Blue = EmergencyColors.GoogleBlue
private val Green = EmergencyColors.GoogleGreen
private val Red = EmergencyColors.GoogleRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntiSanctionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud = remember { CloudManager(context) }
    val accounts by cloud.accountsFlow.collectAsState()

    val isVpnRunning by MyVpnService.isRunningFlow.collectAsState()
    val connectedNode by MyVpnService.connectedNodeIdFlow.collectAsState()
    val isActive = isVpnRunning && connectedNode == AntiSanctionManager.NODE_ID

    var domains by remember { mutableStateOf(AntiSanctionManager.getDomains(context)) }
    var deploying by remember { mutableStateOf(false) }
    var deployStatus by remember { mutableStateOf("") }
    var pendingConfig by remember { mutableStateOf<String?>(null) }
    // deployEdgWorker() mutates the CloudAccount object in place, so the list saveAccounts()
    // pushes into accountsFlow is structurally equal to what's already there (same mutated
    // object references) -- StateFlow's distinctUntilChanged then skips the emission and this
    // screen never recomposes with the new worker URL until it's fully recreated. Track success
    // locally instead of relying purely on the flow round-tripping.
    var justDeployed by remember { mutableStateOf(false) }

    val workerAccounts = accounts.filter { !it.edgWorkerUrl.isNullOrEmpty() && !it.edgUuid.isNullOrEmpty() }
    val hasWorker = workerAccounts.isNotEmpty() || justDeployed

    fun startService(cfg: String) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val isProxyMode = prefs.getBoolean("proxy_mode", false)
        val localPort = prefs.getString("local_port", "10808")
        val intent = Intent(context, MyVpnService::class.java).apply {
            putExtra("NODE_URI", cfg)
            putExtra("NODE_ID", AntiSanctionManager.NODE_ID)
            putExtra("PROXY_MODE", isProxyMode)
            putExtra("LOCAL_PORT", localPort)
        }
        context.startService(intent)
    }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) pendingConfig?.let { startService(it) }
        pendingConfig = null
    }

    fun turnOn() {
        scope.launch {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val localPort = prefs.getString("local_port", "10808")?.toIntOrNull() ?: 10808
            val cfg = AntiSanctionManager.buildConfig(context, localPort)
            if (cfg == null) {
                toast(context, "وورکر کلادفلر پیدا نشد؛ ابتدا وورکر را بسازید")
                return@launch
            }
            pendingConfig = cfg
            val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
            if (prep != null) vpnPrepareLauncher.launch(prep) else { startService(cfg); pendingConfig = null }
        }
    }

    fun turnOff() {
        val stop = Intent(context, MyVpnService::class.java).apply { action = "STOP" }
        context.startService(stop)
    }

    fun deployWorker() {
        val acct = accounts.firstOrNull()
        if (acct == null) {
            toast(context, "ابتدا در بخش «ابری» یک حساب کلادفلر اضافه کنید")
            return
        }
        deploying = true
        deployStatus = "در حال ساخت وورکر روی حساب کلادفلر شما…"
        scope.launch {
            val (ok, msg) = cloud.deployEdgWorker(acct) { _, label -> deployStatus = label }
            deploying = false
            deployStatus = ""
            if (ok) {
                justDeployed = true
                toast(context, "وورکر ساخته شد ✅")
            }
            else android.widget.Toast.makeText(context, "ساخت وورکر ناموفق: $msg", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(
                top = com.mlmvpn.scanner.ui.LocalSystemTopPadding.current,
                bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت", tint = Muted)
            }
            Spacer(Modifier.width(8.dp))
            Text("DNS ضد تحریم شخصی", color = TextC, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(
                "سایت‌های تحریم‌شده (که خودِ شرکت آی‌پی ایران را رد می‌کند، مثل ChatGPT، Gemini، GitHub، Steam) " +
                    "را از طریق وورکرِ کلادفلرِ خودتان باز می‌کند؛ ترافیک این سایت‌ها از آی‌پی تمیز کلادفلر خارج می‌شود " +
                    "و تحریم واقعاً دور می‌خورد. بقیه‌ی سایت‌ها مستقیم و بدون تغییر می‌مانند."
            )

            // Exit worker (Cloudflare account)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Surface2)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("وورکر خروجی (کلادفلر)", color = TextC, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    if (hasWorker) {
                        val readyAccount = workerAccounts.firstOrNull() ?: accounts.firstOrNull()
                        val label = readyAccount?.email?.ifEmpty { readyAccount.accountId } ?: "—"
                        Text("آماده ✅ — از حساب: $label", color = Green, fontSize = 12.sp)
                    } else {
                        Text("هنوز وورکری ساخته نشده. یک‌بار روی حساب کلادفلر خودتان بسازید.", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        if (deploying) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Blue, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(deployStatus.ifEmpty { "در حال ساخت…" }, color = Blue, fontSize = 12.sp)
                            }
                        } else {
                            Button(
                                onClick = { deployWorker() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("ساخت وورکر روی حساب کلادفلر من", color = Color.White) }
                        }
                    }
                }
            }

            // Domain checker + add
            DomainCheckerCard(
                onAdded = { domains = AntiSanctionManager.getDomains(context) }
            )

            // Domain list
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Surface2)
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "دامنه‌های تحریمی (${domains.size}) — این‌ها از کلادفلر رد می‌شوند",
                        color = TextC, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    domains.forEach { d ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(d, color = TextC, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                AntiSanctionManager.removeDomain(context, d)
                                domains = AntiSanctionManager.getDomains(context)
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "حذف", tint = Muted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (domains.isEmpty()) {
                        Text("لیست خالی است — با «استعلام و افزودن» بالای صفحه دامنه اضافه کنید.",
                            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // Big on/off button
        Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    if (isActive) turnOff()
                    else if (!hasWorker) toast(context, "ابتدا وورکر کلادفلر را بسازید")
                    else turnOn()
                },
                modifier = Modifier.size(120.dp).scale(if (isActive) 1.03f else 1f),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = if (isActive) Red else Blue)
            ) {
                Text(
                    if (isActive) "خاموش" else "روشن",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DomainCheckerCard(onAdded: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<AntiSanctionManager.Report?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Surface2)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("استعلام و افزودن دامنه", color = TextC, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "لیست کامل نیست؛ هر دامنه‌ای که باز نمی‌شود را اینجا استعلام بگیرید و اگر «تحریم» بود اضافه کنید.",
                color = Muted, fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; report = null },
                label = { Text("مثلاً: chat.openai.com", color = Muted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = Blue, unfocusedBorderColor = Surface2,
                    focusedTextColor = TextC, unfocusedTextColor = TextC
                )
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val d = AntiSanctionManager.normalize(input)
                        if (d == null) { toast(context, "دامنه معتبر وارد کنید"); return@Button }
                        checking = true; report = null
                        scope.launch {
                            report = AntiSanctionManager.classifyDomain(d)
                            checking = false
                        }
                    },
                    enabled = !checking,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Surface2)
                ) {
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Blue, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, tint = TextC, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("استعلام", color = TextC)
                }
                Button(
                    onClick = {
                        val d = AntiSanctionManager.normalize(input)
                        if (d == null) { toast(context, "دامنه معتبر وارد کنید"); return@Button }
                        AntiSanctionManager.addDomain(context, d)
                        onAdded()
                        toast(context, "«$d» اضافه شد")
                        input = ""; report = null
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("افزودن", color = Color.White)
                }
            }

            report?.let { r ->
                Spacer(Modifier.height(12.dp))
                val color = when (r.state) {
                    AntiSanctionManager.State.SANCTIONED -> Green
                    AntiSanctionManager.State.OPEN -> Blue
                    AntiSanctionManager.State.FILTERED -> Red
                    AntiSanctionManager.State.UNKNOWN -> Muted
                }
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(r.domain, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(r.note, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Blue.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Blue.copy(alpha = 0.3f))
    ) {
        Text(text, color = TextC, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(14.dp))
    }
}

private fun toast(context: android.content.Context, msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}
