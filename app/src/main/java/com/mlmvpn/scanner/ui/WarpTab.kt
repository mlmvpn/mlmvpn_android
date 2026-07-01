package com.mlmvpn.scanner.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import android.content.Context
import android.content.Intent
import com.mlmvpn.core.warp.WarpAccountManager
import com.mlmvpn.core.warp.WarpAntiDpi
import com.mlmvpn.core.warp.WarpHandshakeTester
import com.mlmvpn.core.warp.WarpBatchTester
import com.mlmvpn.core.warp.WarpIpGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
// 1. Color Palette (Defined as requested by developer)
// ============================================================================
object AppColors {
    val BgDark = Color(0xFF171717)         // پس‌زمینه اصلی صفحه
    val SurfaceDark = Color(0xFF222222)    // پس‌زمینه کارت‌ها
    val SurfaceVariant = Color(0xFF2A2A2A) // پس‌زمینه عناصر داخلی کارت‌ها
    val Primary = Color(0xFF81C995)        // رنگ اصلی (سبز نئونی مشابه اسکرین‌شات)
    val PrimaryBlue = Color(0xFF8AB4F8)    // رنگ ثانویه (آبی کلودفلر)
    val TextPrimary = Color(0xFFE8EAED)    // متن اصلی
    val TextMuted = Color(0xFF9AA0A6)      // متن فرعی
    val BorderDark = Color(0xFF333333)     // حاشیه کارت‌ها
    val Error = Color(0xFFF28B82)          // قرمز (برای پینگ بالا یا خطا)
}

// ============================================================================
// 2. Data Classes (State Models)
// ============================================================================
data class WarpAccountState(
    val isRegistered: Boolean = false,
    val accountType: String = "WARP",
    val localIp: String = "نامشخص",
    val isGenerating: Boolean = false,
    val account: WarpAccountManager.WarpAccountData? = null
)

data class EndpointModel(
    val id: String,
    val ip: String,
    val port: Int,
    val pingMs: Int?,
    val isSelected: Boolean = false
)

// ============================================================================
// Main Tab Composable
// ============================================================================
@Composable
fun WarpTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val warpManager = remember { WarpAccountManager(context) }
    
    var accountState by remember { mutableStateOf(WarpAccountState()) }
    var endpoints by remember { mutableStateOf<List<EndpointModel>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var isReservedBytesEnabled by remember { mutableStateOf(true) }
    var scanCount by remember { mutableStateOf(50f) }
    var scanProgress by remember { mutableStateOf(Pair(0, 0)) }

    var scanPhase by remember { mutableStateOf("") }

    // Load initial account state
    LaunchedEffect(Unit) {
        val savedAccount = warpManager.getSavedAccount()
        if (savedAccount != null) {
            accountState = WarpAccountState(
                isRegistered = true,
                localIp = savedAccount.ipv4,
                account = savedAccount
            )
        }
    }

    WarpScreenContent(
        accountState = accountState,
        endpoints = endpoints,
        isScanning = isScanning,
        scanCount = scanCount,
        scanProgress = scanProgress,
        scanPhase = scanPhase,
        isReservedBytesEnabled = isReservedBytesEnabled,
        onScanCountChange = { scanCount = it },
        onGenerateAccountClick = {
            scope.launch {
                accountState = accountState.copy(isGenerating = true)
                val result = warpManager.registerNewAccount()
                if (result.isSuccess) {
                    val acc = result.getOrNull()!!
                    accountState = WarpAccountState(
                        isRegistered = true,
                        localIp = acc.ipv4,
                        isGenerating = false,
                        account = acc
                    )
                    Toast.makeText(context, "اکانت WARP با موفقیت ساخته شد", Toast.LENGTH_SHORT).show()
                } else {
                    accountState = accountState.copy(isGenerating = false)
                    Toast.makeText(context, "خطا در ساخت اکانت: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        },
        onStartScanClick = {
            if (accountState.account == null) {
                Toast.makeText(context, "ابتدا یک اکانت WARP ایجاد کنید", Toast.LENGTH_SHORT).show()
                return@WarpScreenContent
            }
            scope.launch(Dispatchers.IO) {
                isScanning = true
                val targetCount = scanCount.toInt()
                scanProgress = Pair(0, targetCount)
                scanPhase = "جستجوی اولیه آی‌پی (مرحله ۱)"
                endpoints = emptyList() // clear previous
                
                val reservedBytes = if (isReservedBytesEnabled) WarpAntiDpi.generateReservedBytes() else listOf(0,0,0)
                val foundEndpoints = mutableListOf<WarpBatchTester.BatchEndpoint>()
                
                // Phase 1: Keep generating and fast-pinging until we reach targetCount
                var testedSoFar = 0
                while (isScanning && foundEndpoints.size < targetCount) {
                    val batchToTest = WarpIpGenerator.generateRandomEndpoints(20) // Test 20 at a time
                    
                    val deferreds = batchToTest.map { endpointStr ->
                        async {
                            val lastColonIndex = endpointStr.lastIndexOf(':')
                            val ip = if (lastColonIndex != -1) endpointStr.substring(0, lastColonIndex) else endpointStr
                            val port = if (lastColonIndex != -1) endpointStr.substring(lastColonIndex + 1).toIntOrNull() ?: 2408 else 2408
                            
                            val isAlive = WarpBatchTester.stage1FastPing(ip, 1000)
                            if (isAlive) WarpBatchTester.BatchEndpoint(ip, port, 0) else null
                        }
                    }
                    
                    val results = deferreds.awaitAll().filterNotNull()
                    foundEndpoints.addAll(results)
                    testedSoFar += 20
                    
                    withContext(Dispatchers.Main) {
                        scanProgress = Pair(foundEndpoints.size, targetCount)
                    }
                    if (foundEndpoints.size >= targetCount) break
                }
                
                if (!isScanning) return@launch
                
                // Truncate to targetCount if we exceeded
                val phase2Endpoints = foundEndpoints.take(targetCount)
                
                // Phase 2: WireGuard test
                withContext(Dispatchers.Main) {
                    scanPhase = "تست نهایی پینگ وایرگارد (مرحله ۲)"
                    scanProgress = Pair(0, targetCount)
                }
                
                val scannedEndpoints = mutableListOf<EndpointModel>()
                val chunkedEndpoints = phase2Endpoints.chunked(20)
                var phase2Progress = 0
                
                for (chunk in chunkedEndpoints) {
                    if (!isScanning) break
                    
                    val results = WarpBatchTester.testBatch(
                        context = context,
                        endpoints = chunk,
                        account = accountState.account!!,
                        reservedBytes = reservedBytes
                    )
                    
                    phase2Progress += chunk.size
                    
                    synchronized(scannedEndpoints) {
                        results.forEach { res ->
                            if (res.pingMs != null) {
                                val endpointModel = EndpointModel(
                                    id = "${res.endpointIp}:${res.endpointPort}",
                                    ip = res.endpointIp,
                                    port = res.endpointPort,
                                    pingMs = res.pingMs,
                                    isSelected = false
                                )
                                scannedEndpoints.add(endpointModel)
                            }
                        }
                        
                        val newEndpoints = scannedEndpoints.toList().sortedBy { it.pingMs ?: 99999 }
                        
                        // Select the best one automatically
                        if (newEndpoints.isNotEmpty() && newEndpoints.first().pingMs != null) {
                            val best = newEndpoints.first()
                            withContext(Dispatchers.Main) {
                                scanProgress = Pair(phase2Progress, targetCount)
                                endpoints = newEndpoints.map { it.copy(isSelected = (it.id == best.id)) }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                scanProgress = Pair(phase2Progress, targetCount)
                                endpoints = newEndpoints
                            }
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    isScanning = false
                    Toast.makeText(context, "اسکن دو مرحله‌ای به پایان رسید", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onStopScanClick = {
            isScanning = false
        },
        onEndpointSelect = { selected ->
            endpoints = endpoints.map { it.copy(isSelected = (it.id == selected.id)) }
        },
        onPingClick = { endpoint ->
            scope.launch(Dispatchers.IO) {
                val ping = WarpHandshakeTester.testEndpoint(
                    context = context,
                    endpointIp = endpoint.ip,
                    endpointPort = endpoint.port,
                    account = accountState.account!!,
                    reserved = if (isReservedBytesEnabled) WarpAntiDpi.generateReservedBytes() else listOf(0,0,0),
                    localPort = 20999
                )
                withContext(Dispatchers.Main) {
                    endpoints = endpoints.map {
                        if (it.id == endpoint.id) it.copy(pingMs = ping) else it
                    }.sortedBy { it.pingMs ?: 99999 }
                }
            }
        },
        onReservedBytesToggle = { isReservedBytesEnabled = it },
        onConnectClick = {
            val selectedEndpoint = endpoints.find { it.isSelected }
            if (accountState.account == null) {
                Toast.makeText(context, "اکانت WARP ساخته نشده است", Toast.LENGTH_SHORT).show()
                return@WarpScreenContent
            }
            if (selectedEndpoint == null) {
                Toast.makeText(context, "لطفاً یک اندپوینت انتخاب کنید", Toast.LENGTH_SHORT).show()
                return@WarpScreenContent
            }

            val reservedBytes = if (isReservedBytesEnabled) WarpAntiDpi.generateReservedBytes() else listOf(0,0,0)
            
            // Generate the config JSON to pass to MyVpnService
            val configJson = JSONObject().apply {
                put("type", "warp")
                put("privateKey", accountState.account!!.privateKey)
                put("endpointIp", selectedEndpoint.ip)
                put("endpointPort", selectedEndpoint.port)
                put("ipv4", accountState.account!!.ipv4)
                put("ipv6", accountState.account!!.ipv6)
                val reservedArray = JSONArray()
                reservedBytes.forEach { reservedArray.put(it) }
                put("reserved", reservedArray)
            }.toString()

            // Save standard format for service
            val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("selected_config", configJson).apply()
            
            val startIntent = android.content.Intent(context, com.mlmvpn.scanner.MyVpnService::class.java).apply {
                putExtra("NODE_ID", "warp_tunnel")
                putExtra("NODE_URI", configJson) // We pass the JSON string as the URI
            }
            context.startService(startIntent)
            android.widget.Toast.makeText(context, "در حال اتصال به WARP...", android.widget.Toast.LENGTH_SHORT).show()
        }
    )
}

// ============================================================================
// 3. Stateless Composable (Developer provided code)
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarpScreenContent(
    accountState: WarpAccountState,
    endpoints: List<EndpointModel>,
    isScanning: Boolean,
    scanCount: Float,
    scanProgress: Pair<Int, Int>,
    isReservedBytesEnabled: Boolean,
    onScanCountChange: (Float) -> Unit,
    onGenerateAccountClick: () -> Unit,
    onStartScanClick: () -> Unit,
    onStopScanClick: () -> Unit,
    onEndpointSelect: (EndpointModel) -> Unit,
    onPingClick: (EndpointModel) -> Unit,
    onReservedBytesToggle: (Boolean) -> Unit,
    onConnectClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.BgDark)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 24.dp, bottom = 180.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Text(
                    text = "تونل WARP + WireGuard",
                    color = AppColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "دور زدن فیلترینگ با ترکیب وارپ و اندپوینت‌های اختصاصی",
                    color = AppColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Section 1: Account Info
            item {
                WarpAccountCard(
                    accountState = accountState,
                    onGenerateClick = onGenerateAccountClick
                )
            }

            // Section 2: Advanced Settings (Reserved Bytes)
            item {
                SettingsCard(
                    isReservedBytesEnabled = isReservedBytesEnabled,
                    onToggle = onReservedBytesToggle
                )
            }

            // Section 3: Endpoint Scanner
            item {
                ScannerHeader(
                    isScanning = isScanning,
                    scanCount = scanCount,
                    scanProgress = scanProgress,
                    onScanCountChange = onScanCountChange,
                    onStartScanClick = onStartScanClick,
                    onStopScanClick = onStopScanClick
                )
            }

            // Endpoint List
            if (endpoints.isEmpty() && !isScanning) {
                item {
                    EmptyEndpointState()
                }
            } else {
                items(endpoints, key = { it.id }) { endpoint ->
                    EndpointItemCard(
                        endpoint = endpoint,
                        onClick = { onEndpointSelect(endpoint) },
                        onPingClick = { onPingClick(endpoint) }
                    )
                }
            }
        }

        // Section 4: Main Connect Button
        ConnectFabArea(
            modifier = Modifier.align(Alignment.BottomCenter),
            onConnectClick = onConnectClick
        )
    }
}

// ============================================================================
// 4. Sub-Components
// ============================================================================

@Composable
fun WarpAccountCard(
    accountState: WarpAccountState,
    onGenerateClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark),
        border = BorderStroke(1.dp, AppColors.BorderDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.AccountCircle,
                    contentDescription = null,
                    tint = AppColors.PrimaryBlue,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "وضعیت اکانت وارپ",
                        color = AppColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (accountState.isRegistered) "اکانت ${accountState.accountType} فعال است" else "هیچ اکانتی ثبت نشده",
                        color = if (accountState.isRegistered) AppColors.Primary else AppColors.Error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                
                Icon(
                    imageVector = Icons.Rounded.CloudSync,
                    contentDescription = null,
                    tint = AppColors.TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp)
                )
            }

            if (accountState.isRegistered) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.SurfaceVariant)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "IP داخلی (Local):", color = AppColors.TextMuted, fontSize = 12.sp)
                    Text(text = accountState.localIp, color = AppColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onGenerateClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !accountState.isGenerating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.SurfaceVariant,
                    contentColor = AppColors.PrimaryBlue
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (accountState.isGenerating) {
                    CircularProgressIndicator(color = AppColors.PrimaryBlue, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("در حال ساخت اکانت...", fontSize = 13.sp)
                } else {
                    Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (accountState.isRegistered) "بازسازی اکانت جدید" else "دریافت اکانت رایگان", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun SettingsCard(
    isReservedBytesEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark),
        border = BorderStroke(1.dp, AppColors.BorderDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "تزریق Reserved Bytes",
                    color = AppColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "محاسبه خودکار بایت‌های ضد فیلترینگ برای دور زدن محدودیت‌های مخابرات",
                    color = AppColors.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp, end = 16.dp)
                )
            }
            Switch(
                checked = isReservedBytesEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.BgDark,
                    checkedTrackColor = AppColors.Primary,
                    uncheckedThumbColor = AppColors.TextMuted,
                    uncheckedTrackColor = AppColors.SurfaceVariant
                )
            )
        }
    }
}

@Composable
fun ScannerHeader(
    isScanning: Boolean,
    scanCount: Float,
    scanProgress: Pair<Int, Int>,
    scanPhase: String,
    onScanCountChange: (Float) -> Unit,
    onStartScanClick: () -> Unit,
    onStopScanClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark),
        border = BorderStroke(1.dp, AppColors.BorderDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "اسکنر اندپوینت دو مرحله‌ای",
                        color = AppColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isScanning) {
                        Text(
                            text = scanPhase,
                            color = AppColors.PrimaryBlue,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = "${scanProgress.first} از ${scanProgress.second}",
                            color = AppColors.Primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    } else {
                        Text(
                            text = "تعداد آی‌پی برای تست: ${scanCount.toInt()}",
                            color = AppColors.TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Button(
                    onClick = if (isScanning) onStopScanClick else onStartScanClick,
                    enabled = true,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) AppColors.Error.copy(alpha = 0.15f) else AppColors.Primary.copy(alpha = 0.15f),
                        contentColor = if (isScanning) AppColors.Error else AppColors.Primary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    if (isScanning) {
                        Icon(imageVector = Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("توقف اسکن", fontSize = 12.sp)
                    } else {
                        Icon(imageVector = Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("شروع اسکن", fontSize = 12.sp)
                    }
                }
            }
            
            if (!isScanning) {
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = scanCount,
                    onValueChange = onScanCountChange,
                    valueRange = 10f..2000f,
                    steps = 199,
                    colors = SliderDefaults.colors(
                        thumbColor = AppColors.Primary,
                        activeTrackColor = AppColors.Primary,
                        inactiveTrackColor = AppColors.SurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
fun EndpointItemCard(
    endpoint: EndpointModel,
    onClick: () -> Unit,
    onPingClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (endpoint.isSelected) AppColors.Primary.copy(alpha = 0.1f) else AppColors.SurfaceDark
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (endpoint.isSelected) AppColors.Primary.copy(alpha = 0.5f) else AppColors.BorderDark
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Radio button indicator
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (endpoint.isSelected) AppColors.Primary else AppColors.SurfaceVariant)
                    .padding(4.dp)
            ) {
                if (endpoint.isSelected) {
                    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(AppColors.BgDark))
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // IP & Port
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = endpoint.ip,
                    color = AppColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Port: ${endpoint.port}",
                    color = AppColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            
            // Ping Badge
            val pingColor = when {
                endpoint.pingMs == null -> AppColors.TextMuted
                endpoint.pingMs < 150 -> AppColors.Primary
                endpoint.pingMs < 300 -> Color(0xFFFDE293)
                else -> AppColors.Error
            }
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(pingColor.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (endpoint.pingMs != null) "${endpoint.pingMs} ms" else "Timeout",
                    color = pingColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Ping Refresh Button
            IconButton(
                onClick = onPingClick,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.SurfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Test Ping",
                    tint = AppColors.PrimaryBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyEndpointState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.WifiOff,
            contentDescription = null,
            tint = AppColors.BorderDark,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "هیچ اندپوینتی یافت نشد",
            color = AppColors.TextMuted,
            fontSize = 14.sp
        )
        Text(
            text = "برای پیدا کردن آی‌پی‌های تمیز روی دکمه اسکن کلیک کنید.",
            color = AppColors.TextMuted.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun ConnectFabArea(
    modifier: Modifier = Modifier,
    onConnectClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = AppColors.BgDark.copy(alpha = 0.9f)
            )
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 90.dp)
    ) {
        Button(
            onClick = onConnectClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.Primary,
                contentColor = AppColors.BgDark
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "اتصال یکپارچه به شبکه",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
