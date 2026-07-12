package com.mlmvpn.scanner.engines.deno

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.data.CloudGroup
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Deno Deploy panel � Google-dark themed, multi-account.
 *
 * Users can add multiple Deno organization accounts, see each account's
 * daily/weekly/monthly request count + bandwidth, deploy new VLESS-over-WS
 * backends, and pull the resulting configs into the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DenoPanelScreen(
    nodeManager: NodeManager,
    onDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { DenoManager(context) }
    val deployer = remember { DenoDeployer(context) }
    val stats = remember { DenoStats(context) }
    val groupManager = remember { GroupManager(context) }

    var accounts by remember { mutableStateOf(manager.getAccounts()) }
    var selectedId by remember { mutableStateOf(accounts.firstOrNull()?.id) }
    var deployments by remember { mutableStateOf(manager.getDeployments()) }
    val usageMap = remember { mutableStateMapOf<String, DenoStats.AccountUsage>() }
    var usageLoading by remember { mutableStateOf<String?>(null) }

    // Add-account form state
    var newLabel by remember { mutableStateOf("") }
    var newToken by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var showAddForm by remember { mutableStateOf(accounts.isEmpty()) }

    // Deploy state
    var deploying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("") }
    var qrLink by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        accounts = manager.getAccounts()
        deployments = manager.getDeployments()
        if (selectedId == null || accounts.none { it.id == selectedId }) {
            selectedId = accounts.firstOrNull()?.id
        }
    }

    fun loadUsage(accId: String) {
        usageLoading = accId
        scope.launch {
            // Exact usage polled from each of this account's servers' own stats
            // endpoints (in-memory counters), accumulated persistently on-device.
            usageMap[accId] = stats.pollAccount(accId, manager.getDeploymentsFor(accId))
            usageLoading = null
        }
    }

    // Auto-load usage when selection changes.
    LaunchedEffect(selectedId, deployments.size) {
        selectedId?.let { if (!usageMap.containsKey(it)) loadUsage(it) }
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("Deno Panel", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .background(BgDark)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            InfoBanner(context)

            // --- Accounts row ---
            SectionTitle("اکانت‌های Deno", Icons.Filled.AccountCircle)
            if (accounts.isEmpty()) {
                Text("هنوز اکانتی اضافه نشده.", color = TextMuted, fontSize = 13.sp)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    accounts.forEach { acc ->
                        AccountChip(
                            label = acc.label,
                            selected = acc.id == selectedId,
                            onClick = { selectedId = acc.id }
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { showAddForm = !showAddForm },
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary)
            ) {
                Icon(if (showAddForm) Icons.Filled.Close else Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (showAddForm) "بستن فرم" else "افزودن اکانت جدید")
            }

            if (showAddForm) {
                AddAccountForm(
                    context = context,
                    label = newLabel, onLabel = { newLabel = it },
                    token = newToken, onToken = { newToken = it.trim() },
                    tokenVisible = tokenVisible, onToggleVisible = { tokenVisible = !tokenVisible },
                    onAdd = {
                        if (!newToken.startsWith("ddo_")) {
                            Toast.makeText(context, "توکن باید با ddo_ شروع شود", Toast.LENGTH_SHORT).show()
                        } else {
                            val acc = manager.addAccount(newLabel, newToken)
                            newLabel = ""; newToken = ""; showAddForm = false
                            refresh(); selectedId = acc.id
                            Toast.makeText(context, "اکانت اضافه شد ✅", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // --- Selected account body ---
            val account = accounts.firstOrNull { it.id == selectedId }
            if (account != null) {
                Divider(color = BorderDark)

                // Usage card
                SectionTitle("مصرف اکانت �${account.label}�", Icons.Filled.BarChart)
                UsageCard(
                    usage = usageMap[account.id],
                    loading = usageLoading == account.id,
                    onRefresh = { loadUsage(account.id) }
                )

                // Deploy button
                Button(
                    onClick = {
                        deploying = true; progress = 0; statusText = "شروع…"
                        scope.launch {
                            val r = deployer.deploy(account.id, account.token) { p, s -> progress = p; statusText = s }
                            deploying = false
                            if (r.success) {
                                refresh(); loadUsage(account.id)
                                Toast.makeText(context, "استقرار موفق ✅", Toast.LENGTH_SHORT).show()
                            } else {
                                statusText = r.message
                                Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !deploying,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = BgDark, disabledContainerColor = SurfaceDark)
                ) {
                    if (deploying) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BgDark)
                        Spacer(Modifier.width(10.dp))
                        Text("در حال استقرار…", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Filled.CloudUpload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("استقرار سرور جدید", fontWeight = FontWeight.Bold)
                    }
                }
                if (deploying) {
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier.fillMaxWidth(),
                        color = Primary, trackColor = SurfaceDark
                    )
                    Text(statusText, color = TextMuted, fontSize = 12.sp)
                }

                // Remove account
                TextButton(
                    onClick = {
                        manager.removeAccount(account.id)
                        usageMap.remove(account.id); refresh()
                        Toast.makeText(context, "اکانت حذف شد (سرورهای ساخته‌شده روی Deno باقی می‌مانند)", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Filled.DeleteOutline, null, Modifier.size(16.dp), tint = RedError)
                    Spacer(Modifier.width(4.dp))
                    Text("حذف این اکانت", color = RedError, fontSize = 12.sp)
                }

                Divider(color = BorderDark)

                // Deployments of this account
                val accDeployments = deployments.filter { it.accountId == account.id }
                SectionTitle("سرورهای این اکانت (${accDeployments.size})", Icons.Filled.Dns)
                if (accDeployments.isEmpty()) {
                    Text("هنوز سروری برای این اکانت ساخته نشده.", color = TextMuted, fontSize = 13.sp)
                }
                accDeployments.forEach { d ->
                    DeploymentCard(
                        host = d.host,
                        hasXhttp = d.xhttpPath.isNotBlank(),
                        onCopy = {
                            copyToClipboard(context, "VLESS", d.vlessLink)
                            Toast.makeText(context, "کپی شد", Toast.LENGTH_SHORT).show()
                        },
                        onGetConfig = { getConfig(context, nodeManager, groupManager, d) },
                        onGetXhttp = {
                            if (d.xhttpPath.isBlank()) {
                                Toast.makeText(context, "این سرور xHTTP ندارد؛ یک سرور جدید Deploy کن.", Toast.LENGTH_LONG).show()
                            } else {
                                // Two configs on the Deno domain (:443): one VLESS, one Trojan.
                                val n = generateXhttpConfigs(
                                    context, nodeManager, groupManager, d,
                                    protocols = listOf("vless", "trojan"),
                                    ports = listOf(443),
                                    ips = emptyList()
                                )
                                Toast.makeText(
                                    context,
                                    if (n > 0) "$n کانفیگ xHTTP ساخته شد ✅ (VLESS + Trojan)" else "کانفیگ‌ها قبلاً ساخته شده‌اند",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        onShowQr = { qrLink = d.vlessLink },
                        onDelete = {
                            manager.removeDeployment(d.projectId); refresh()
                            Toast.makeText(context, "از لیست حذف شد", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // QR dialog
    qrLink?.let { link ->
        AlertDialog(
            onDismissRequest = { qrLink = null },
            containerColor = SurfaceDark,
            confirmButton = { TextButton(onClick = { qrLink = null }) { Text("بستن", color = Primary) } },
            title = { Text("QR کانفیگ", color = TextPrimary) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val bmp = remember(link) { generateQr(link) }
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), "QR", modifier = Modifier.size(240.dp))
                    } else Text("خطا در ساخت QR", color = RedError)
                }
            }
        )
    }
}

// ---- Sub-composables -------------------------------------------------------

@Composable
private fun InfoBanner(context: Context) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("استقرار خودکار سرور VLESS روی Deno (رایگان)", color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(
            "⚠️ قبل از استقرار، یک کانفیگ کارآمد mlmvpn را روشن کن � api.deno.com در ایران مسدود است و باید از تونل رد شود.",
            color = YellowWarn, fontSize = 12.sp
        )
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.deno.com/"))
                    )
                } catch (e: Exception) {
                    Toast.makeText(context, "مرورگری یافت نشد", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary)
        ) {
            Icon(Icons.Filled.OpenInNew, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("باز کردن console.deno.com و دریافت توکن (ddo_)")
        }
    }
}

@Composable
private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = Primary)
        Spacer(Modifier.width(6.dp))
        Text(text, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun AccountChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Primary.copy(alpha = 0.18f) else SurfaceDark)
            .border(1.dp, if (selected) Primary else BorderDark, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (selected) Primary else TextMuted, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAccountForm(
    context: Context,
    label: String, onLabel: (String) -> Unit,
    token: String, onToken: (String) -> Unit,
    tokenVisible: Boolean, onToggleVisible: () -> Unit,
    onAdd: () -> Unit
) {
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
        focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark,
        cursorColor = Primary,
        focusedBorderColor = Primary, unfocusedBorderColor = BorderDark,
        focusedLabelColor = Primary, unfocusedLabelColor = TextMuted
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = label, onValueChange = onLabel,
            label = { Text("نام اکانت (دلخواه)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), colors = colors
        )
        OutlinedTextField(
            value = token, onValueChange = onToken,
            label = { Text("Organization Token (ddo_�)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), colors = colors,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions.Default,
            isError = token.isNotEmpty() && !token.startsWith("ddo_"),
            trailingIcon = {
                IconButton(onClick = onToggleVisible) {
                    Icon(if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, tint = TextMuted)
                }
            }
        )
        Button(
            onClick = onAdd,
            enabled = token.startsWith("ddo_"),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = BgDark, disabledContainerColor = BgDark)
        ) {
            Icon(Icons.Filled.Check, null); Spacer(Modifier.width(8.dp)); Text("ذخیره اکانت", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UsageCard(
    usage: DenoStats.AccountUsage?,
    loading: Boolean,
    onRefresh: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("درخواست‌ها و حجم مصرف", color = TextMuted, fontSize = 12.sp)
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary)
            } else {
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, "refresh", Modifier.size(18.dp), tint = Primary)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageColumn("روزانه", usage?.daily, Modifier.weight(1f))
            UsageColumn("هفتگی", usage?.weekly, Modifier.weight(1f))
            UsageColumn("ماهانه", usage?.monthly, Modifier.weight(1f))
        }
        // Lifetime total for the account (sum of all its servers).
        val total = usage?.total ?: DenoStats.Usage()
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Primary.copy(alpha = 0.10f))
                .border(1.dp, Primary.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("کل (از ابتدا)", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(formatCount(total.requests) + " درخواست", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ArrowDownward, null, Modifier.size(11.dp), tint = GreenOk)
                Text(formatBytes(total.downloadBytes), color = GreenOk, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.ArrowUpward, null, Modifier.size(11.dp), tint = Primary)
                Text(formatBytes(total.uploadBytes), color = Primary, fontSize = 11.sp)
            }
        }
        if (usage != null && !usage.ok) {
            Text("خواندن آمار ناموفق بود. مطمئن شو یک کانفیگ سالم وصل است و سرور Deno بالا است.", color = YellowWarn, fontSize = 11.sp)
        } else {
            Text("آمار دقیق و لحظه‌ای، مستقیم از خود سرورها شمارش می‌شود.", color = TextDim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun UsageColumn(title: String, u: DenoStats.Usage?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BgDark)
            .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(title, color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(formatCount(u?.requests ?: 0), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("درخواست", color = TextMuted, fontSize = 9.sp)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowDownward, null, Modifier.size(11.dp), tint = GreenOk)
            Text(formatBytes(u?.downloadBytes ?: 0), color = GreenOk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowUpward, null, Modifier.size(11.dp), tint = Primary)
            Text(formatBytes(u?.uploadBytes ?: 0), color = Primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("دانلود / آپلود", color = TextMuted, fontSize = 8.sp)
    }
}

@Composable
private fun DeploymentCard(
    host: String,
    hasXhttp: Boolean,
    onCopy: () -> Unit,
    onGetConfig: () -> Unit,
    onGetXhttp: () -> Unit,
    onShowQr: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(GreenOk))
            Spacer(Modifier.width(8.dp))
            Text(host, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionChip("دریافت WS", Icons.Filled.Download, Primary, onGetConfig, Modifier.weight(1f))
            ActionChip("کپی", Icons.Filled.ContentCopy, TextMuted, onCopy, Modifier.weight(1f))
            ActionChip("QR", Icons.Filled.QrCode2, TextMuted, onShowQr, Modifier.weight(1f))
            ActionChip("حذف", Icons.Filled.DeleteOutline, RedError, onDelete, Modifier.weight(1f))
        }
        if (hasXhttp) {
            // Distinct, prominent xHTTP action � glassy Primary-tinted button.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Primary.copy(alpha = 0.12f))
                    .border(1.dp, Primary.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .clickable(onClick = onGetXhttp)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Bolt, null, Modifier.size(18.dp), tint = Primary)
                Spacer(Modifier.width(8.dp))
                Text("دریافت کانفیگ xHTTP (کم‌مصرف)", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BgDark)
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Text(label, color = tint, fontSize = 10.sp)
    }
}

// ---- helpers ---------------------------------------------------------------

private fun getConfig(
    context: Context,
    nodeManager: NodeManager,
    groupManager: GroupManager,
    d: DenoManager.DenoDeployment
) = importDenoConfig(context, nodeManager, groupManager, d, d.vlessLink, "WS")

/** One VLESS/Trojan-over-xHTTP link. `addr` is the connect target; sni/host = Deno domain. */
private fun buildXhttpConfigFor(
    proto: String, // "vless" | "trojan"
    d: DenoManager.DenoDeployment,
    addr: String,
    port: Int
): String {
    val host = d.host
    val encPath = java.net.URLEncoder.encode(d.xhttpPath, "UTF-8")
    val addrTag = if (addr == host) "deno" else addr
    val name = java.net.URLEncoder.encode("Deno-${host.substringBefore('.')}-$proto-$addrTag-$port", "UTF-8")
    val common = "security=tls&sni=$host&fp=chrome&type=xhttp&host=$host&path=$encPath&mode=packet-up"
    return if (proto == "trojan") {
        "trojan://${d.uuid}@$addr:$port?$common#$name"
    } else {
        "vless://${d.uuid}@$addr:$port?encryption=none&$common#$name"
    }
}

/**
 * Generate every combination: protocols � ports � (selected clean IPs + the
 * Deno domain), import them all as DENO nodes + one cloud group. Returns count.
 */
private fun generateXhttpConfigs(
    context: Context,
    nodeManager: NodeManager,
    groupManager: GroupManager,
    d: DenoManager.DenoDeployment,
    protocols: List<String>,
    ports: List<Int>,
    ips: List<String>
): Int {
    val addresses = ips + d.host // clean IPs + the Deno domain (always)
    val newNodes = mutableListOf<VpnNode>()
    for (proto in protocols) for (port in ports) for (addr in addresses) {
        val uri = buildXhttpConfigFor(proto, d, addr, port)
        if (nodeManager.nodes.any { it.uri == uri }) continue
        newNodes.add(
            VpnNode(
                id = "deno_${d.projectId}_${proto}_${addr}_${port}_${System.currentTimeMillis()}_${newNodes.size}",
                name = "Deno ${d.host.substringBefore('.')} $proto ${if (addr == d.host) "deno" else addr}:$port",
                uri = uri,
                type = proto,
                engineType = "DENO"
            )
        )
    }
    if (newNodes.isNotEmpty()) {
        nodeManager.nodes.addAll(0, newNodes)
        nodeManager.saveNodes()
        groupManager.loadCloudGroups()
        groupManager.cloudGroups.add(0, CloudGroup(
            id = "deno_xhttp_${d.projectId}_${System.currentTimeMillis()}",
            accountId = "deno",
            date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
            title = "Deno ${d.host.substringBefore('.')} xHTTP (${newNodes.size})",
            nodes = newNodes.toList()
        ))
        groupManager.saveCloudGroups()
    }
    return newNodes.size
}

private fun importDenoConfig(
    context: Context,
    nodeManager: NodeManager,
    groupManager: GroupManager,
    d: DenoManager.DenoDeployment,
    uri: String,
    variant: String
) {
    val node = VpnNode(
        id = "deno_${d.projectId}_$variant",
        name = "Deno ${d.host.substringBefore('.')} ($variant)",
        uri = uri,
        type = "vless",
        engineType = "DENO"
    )
    val already = nodeManager.nodes.any { it.uri == uri }
    if (!already) {
        nodeManager.nodes.add(0, node.copy(id = "deno_${d.projectId}_${variant}_${System.currentTimeMillis()}"))
        nodeManager.saveNodes()
    }
    groupManager.loadCloudGroups()
    val groupExists = groupManager.cloudGroups.any { g -> g.nodes.any { it.uri == uri } }
    if (!groupExists) {
        groupManager.cloudGroups.add(0, CloudGroup(
            id = "deno_${d.projectId}_${variant}_${System.currentTimeMillis()}",
            accountId = "deno",
            date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
            title = "Deno ${d.host.substringBefore('.')} ($variant)",
            nodes = listOf(node)
        ))
        groupManager.saveCloudGroups()
    }
    Toast.makeText(
        context,
        if (already && groupExists) "این کانفیگ قبلاً دریافت شده" else "کانفیگ $variant دریافت شد ✅ (تب DENO + اسکنر)",
        Toast.LENGTH_LONG
    ).show()
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun formatCount(n: Long): String {
    return when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }
}

private fun formatBytes(b: Long): String {
    if (b <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "${b} B" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

private fun generateQr(text: String): Bitmap? = try {
    val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType::class.java)
    hints[com.google.zxing.EncodeHintType.MARGIN] = 1
    val matrix = com.google.zxing.qrcode.QRCodeWriter()
        .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, hints)
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width)
        for (y in 0 until matrix.height)
            bmp.setPixel(x, y, if (matrix.get(x, y)) AColor.BLACK else AColor.WHITE)
    bmp
} catch (e: Exception) { null }
