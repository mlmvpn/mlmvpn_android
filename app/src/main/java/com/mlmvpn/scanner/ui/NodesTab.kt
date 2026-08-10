package com.mlmvpn.scanner.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import com.mlmvpn.scanner.R
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.*

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.models.VpnNode

@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun NodesTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val nodeManager = remember { com.mlmvpn.scanner.data.NodeManager(context) }
    val subscriptionManager = remember { com.mlmvpn.scanner.data.SubscriptionManager(context) }
    val subscriptions by subscriptionManager.subscriptionsFlow.collectAsState()
    val nodesFlowState by nodeManager.nodesFlow.collectAsState()
    var nodes by remember(nodesFlowState) { mutableStateOf(nodesFlowState) }

    val isRunning by MyVpnService.isRunningFlow.collectAsState()
    val connectedNodeId by MyVpnService.connectedNodeIdFlow.collectAsState()

    var activeNodeId by remember { mutableStateOf<String?>(null) }
    var expandedNodeId by remember { mutableStateOf<String?>(null) }
    val sharedPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    var sortState by remember { mutableStateOf(sharedPrefs.getInt("sort_state", 0)) } // 0: None, 1: Best-to-Worst, 2: Worst-to-Best
    var isConnecting by remember { mutableStateOf(false) }

    // Guard: switching to an Xray node while the WireGuard trial is up crashes the shared
    // Go runtime. Ask the user to disable WireGuard first.
    var showWgConflict by remember { mutableStateOf(false) }
    var pendingNodeConnect by remember { mutableStateOf<com.mlmvpn.scanner.models.VpnNode?>(null) }

    if (showWgConflict) {
        WireguardConflictDialog(
            onDismiss = { showWgConflict = false; pendingNodeConnect = null },
            onConfirm = {
                showWgConflict = false
                val node = pendingNodeConnect
                pendingNodeConnect = null
                stopActiveVpn(context)
                if (node != null) {
                    scope.launch {
                        kotlinx.coroutines.delay(800) // let WireGuard tear down first
                        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        val startIntent = Intent(context, com.mlmvpn.scanner.MyVpnService::class.java).apply {
                            putExtra("NODE_URI", node.uri)
                            putExtra("NODE_ID", node.id)
                            putExtra("PROXY_MODE", prefs.getBoolean("proxy_mode", false))
                            putExtra("LOCAL_PORT", prefs.getString("local_port", "10808"))
                        }
                        context.startService(startIntent)
                        com.mlmvpn.scanner.MyVpnService.isRunning = true
                        com.mlmvpn.scanner.MyVpnService.connectedNodeId = node.id
                    }
                }
            }
        )
    }

    var isGroupedByPanel by remember { mutableStateOf(false) }
    var selectedTabEngine by remember { mutableStateOf<String?>(null) }
    var selectedManualGroup by remember { mutableStateOf<String?>(null) }
    
    var activeRealCountry by remember { mutableStateOf<String?>(null) }

    // Platform Testing State
    var isPlatformMode by remember { mutableStateOf(false) }
    var selectedPlatform by remember { mutableStateOf<com.mlmvpn.scanner.utils.Platform?>(null) }
    val platformTestResults = remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
    var topNodeHighlightedId by remember { mutableStateOf<String?>(null) }
    var isPlatformTesting by remember { mutableStateOf(false) }

    var isAutoSwitchEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("auto_switch_enabled", false)) }
    var showAutoSwitchModal by remember { mutableStateOf(false) }
    var autoSwitchPlatform by remember { mutableStateOf(sharedPrefs.getString("auto_switch_platform", "None") ?: "None") }
    var autoSwitchInterval by remember { mutableStateOf(sharedPrefs.getInt("auto_switch_interval", 15)) }
    var autoSwitchTestCount by remember { mutableStateOf(sharedPrefs.getInt("auto_switch_test_count", 20)) }
    
    var showAddManual by remember { mutableStateOf(false) }

    // Test Progress State
    var pingProgress by remember { mutableIntStateOf(0) }
    var pingTotal by remember { mutableIntStateOf(0) }
    var isPingTesting by remember { mutableStateOf(false) }
    var pingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var delayProgress by remember { mutableIntStateOf(0) }
    var delayTotal by remember { mutableIntStateOf(0) }
    var isDelayTesting by remember { mutableStateOf(false) }
    var delayJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var speedProgress by remember { mutableIntStateOf(0) }
    var speedTotal by remember { mutableIntStateOf(0) }
    var isSpeedTesting by remember { mutableStateOf(false) }
    var speedJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var isHeaderExpanded by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    val distinctEngines = remember(nodes) {
        nodes.map { it.engineType }.distinct().sorted()
    }
    
    var emptyCustomGroups = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    val distinctManualGroups = remember(nodes, emptyCustomGroups.toList()) {
        val groups = nodes.filter { it.engineType == "Manual" }.mapNotNull { it.groupTitle }.toMutableList()
        groups.addAll(emptyCustomGroups)
        // The domain-fronting folder is always listed, even before setup: its whole flow (making
        // the certificate, installing it, adding the config) happens inside the folder, so the
        // folder has to be reachable while it is still empty.
        groups.add(com.mlmvpn.scanner.mitm.MitmProfile.GROUP)
        groups.distinct().sorted()
    }

    LaunchedEffect(isGroupedByPanel, distinctEngines) {
        if (((isGroupedByPanel || distinctEngines.size <= 1) && distinctEngines.isNotEmpty())) {
            if (selectedTabEngine == null || !distinctEngines.contains(selectedTabEngine)) {
                selectedTabEngine = distinctEngines.first()
            }
        }
    }

    // Synchronize active node with connected node
    LaunchedEffect(connectedNodeId, isRunning) {
        if (connectedNodeId != null) activeNodeId = connectedNodeId
        if (isRunning || !isRunning) isConnecting = false
    }

    // Auto-rename SNI configs
    LaunchedEffect(nodesFlowState) {
        if (nodesFlowState.isNotEmpty()) {
            var changed = false
            val newNodes = nodesFlowState.map { node ->
                if (node.engineType == "NHN") return@map node
                
                var newUri = node.uri
                var currentName = ""
                var changedLocal = false
                val isVmess = node.uri.startsWith("vmess://")
                
                if (isVmess) {
                    try {
                        val base64 = node.uri.substring(8)
                        val jsonStr = String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
                        val jsonObj = org.json.JSONObject(jsonStr)
                        if (jsonObj.optString("add") == "127.0.0.1" || jsonObj.optString("host") == "127.0.0.1") {
                            currentName = jsonObj.optString("ps", "")
                            if (!currentName.startsWith("mlmvpn")) {
                                val randomSuffix = (10000..99999).random()
                                val newName = "mlmvpn-$randomSuffix"
                                jsonObj.put("ps", newName)
                                currentName = newName
                                val newBase64 = android.util.Base64.encodeToString(jsonObj.toString().toByteArray(), android.util.Base64.NO_WRAP)
                                newUri = "vmess://$newBase64"
                                changedLocal = true
                            }
                        }
                    } catch (e: Exception) {}
                } else {
                    val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                    val isLocal = config?.address == "127.0.0.1" || node.uri.contains("@127.0.0.1:")
                    currentName = config?.name ?: (if (node.uri.contains("#")) android.net.Uri.decode(node.uri.substringAfterLast("#")) else "")
                    
                    if (isLocal && !currentName.startsWith("mlmvpn")) {
                        val randomSuffix = (10000..99999).random()
                        currentName = "mlmvpn-$randomSuffix"
                        val fragmentIndex = node.uri.lastIndexOf("#")
                        newUri = if (fragmentIndex != -1) {
                            node.uri.substring(0, fragmentIndex) + "#" + android.net.Uri.encode(currentName)
                        } else {
                            node.uri + "#" + android.net.Uri.encode(currentName)
                        }
                        changedLocal = true
                    }
                }

                if (changedLocal) {
                    changed = true
                    node.copy(name = currentName, uri = newUri)
                } else {
                    node
                }
            }
            if (changed) {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    nodeManager.nodes.clear()
                    nodeManager.nodes.addAll(newNodes)
                    nodeManager.saveNodes()
                }
                nodes = newNodes
            }
        }
    }
    
    // Fetch Real IP when VPN connects or active node changes
    LaunchedEffect(isRunning, connectedNodeId) {
        if (isRunning) {
            activeRealCountry = null
            var success = false
            var attempts = 0
            while (!success && attempts < 5) {
                // Wait for VPN tunnel and routes to establish
                kotlinx.coroutines.delay(2000)
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        val localPort = prefs.getString("local_port", "10808")?.toIntOrNull() ?: 10808
                        val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", localPort + 10000))
                        val client = okhttp3.OkHttpClient.Builder()
                            .proxy(proxy)
                            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        // Was Cloudflare's /cdn-cgi/trace `loc=` field -- that's Cloudflare's OWN
                        // geoIP database for the tunnel's exit IP, which disagreed with reality for
                        // some exit IPs (reported: showed Canada for an IP that ip.me/every other
                        // geoIP source calls the US). Switched to the same api.ip.sb/geoip lookup
                        // CountryLookup.kt (per-node flags) already uses, so both flags in the app
                        // -- this one and the per-node one in the node list -- always agree with
                        // each other and with third-party checkers like ip.me.
                        val request = okhttp3.Request.Builder().url("https://api.ip.sb/geoip").build()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            val bodyText = response.body?.string() ?: ""
                            val json = org.json.JSONObject(bodyText)
                            val country = json.optString("country_code", "")
                            android.util.Log.d("ConnCheck", "tunnel exit → ip=${json.optString("ip")} country=$country")
                            if (country.isNotEmpty() && country != "XX") {
                                activeRealCountry = country.uppercase()
                                success = true
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                attempts++
            }
        } else {
            activeRealCountry = null
        }
    }

    val sortedNodes = remember(nodes, sortState, isPlatformMode, selectedPlatform, platformTestResults.toMap()) {
        if (isPlatformMode && selectedPlatform != null && platformTestResults.keys.any { it.endsWith(selectedPlatform!!.name) }) {
            val comparator = Comparator<VpnNode> { a, b ->
                val delayA = platformTestResults["${a.id}_${selectedPlatform!!.name}"] ?: 999999L
                val delayB = platformTestResults["${b.id}_${selectedPlatform!!.name}"] ?: 999999L
                delayA.compareTo(delayB)
            }
            nodes.sortedWith(comparator)
        } else if (sortState == 0) {
            nodes
        } else {
            val comparator = Comparator<VpnNode> { a, b ->
                val getSpeed = { node: VpnNode ->
                    if (node.speed.contains("MB/s")) node.speed.replace(" MB/s", "").toDoubleOrNull() ?: -1.0 else -1.0
                }
                
                val delayA = if (a.delay.contains("ms")) a.delay.replace("ms", "").toDoubleOrNull() else null
                val delayB = if (b.delay.contains("ms")) b.delay.replace("ms", "").toDoubleOrNull() else null
                
                val speedA = getSpeed(a)
                val speedB = getSpeed(b)
                
                val pingA = if (a.ping.contains("ms")) a.ping.replace("ms", "").toDoubleOrNull() else null
                val pingB = if (b.ping.contains("ms")) b.ping.replace("ms", "").toDoubleOrNull() else null
                
                val comparison = if (delayA != null && delayB != null && delayA != delayB) {
                    delayA.compareTo(delayB)
                } else if (delayA != null && delayB == null) {
                    -1
                } else if (delayB != null && delayA == null) {
                    1
                } else if (speedA > 0 || speedB > 0) {
                    speedB.compareTo(speedA) // Descending for speed
                } else if (pingA != null && pingB != null) {
                    pingA.compareTo(pingB)
                } else if (pingA != null) {
                    -1
                } else if (pingB != null) {
                    1
                } else {
                    0
                }
                
                if (sortState == 2) -comparison else comparison
            }
            nodes.sortedWith(comparator)
        }
    }

    val displayedNodes = remember(sortedNodes, isGroupedByPanel, selectedTabEngine, selectedManualGroup) {
        if (((isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine != null)) {
            val engineFiltered = sortedNodes.filter { it.engineType == selectedTabEngine }
            if (selectedTabEngine == "Manual") {
                engineFiltered.filter { it.groupTitle == selectedManualGroup }
            } else {
                engineFiltered
            }
        } else {
            sortedNodes
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedId = activeNodeId
            val node = nodes.find { it.id == selectedId }
            if (node != null) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val isProxyMode = prefs.getBoolean("proxy_mode", false)
                val localPort = prefs.getString("local_port", "10808")

                val intent = Intent(context, MyVpnService::class.java).apply {
                    putExtra("NODE_URI", node.uri)
                    putExtra("NODE_ID", node.id)
                    putExtra("PROXY_MODE", isProxyMode)
                    putExtra("LOCAL_PORT", localPort)
                }
                context.startService(intent)
                MyVpnService.isRunning = true
                MyVpnService.connectedNodeId = node.id
                isConnecting = false
                Toast.makeText(context, "VPN Tunnel Initialized!", Toast.LENGTH_SHORT).show()
            } else {
                isConnecting = false
            }
        } else {
            isConnecting = false
            Toast.makeText(context, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        var showDeleteAllModal by remember { mutableStateOf(false) }
        var nodeToDelete by remember { mutableStateOf<com.mlmvpn.scanner.models.VpnNode?>(null) }
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            fun updateNodes(indicatorProp: String, action: suspend (com.mlmvpn.scanner.models.VpnNode, com.mlmvpn.scanner.utils.VpnConfig) -> com.mlmvpn.scanner.models.VpnNode, successMsg: String) {
                val job = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val targetNodes = if (((isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine != null)) {
                        val filtered = nodes.filter { it.engineType == selectedTabEngine }
                        if (selectedTabEngine == "Manual") filtered.filter { it.groupTitle == selectedManualGroup } else filtered
                    } else nodes

                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (indicatorProp == "ping") {
                            pingTotal = targetNodes.size
                            pingProgress = 0
                            isPingTesting = true
                        } else {
                            speedTotal = targetNodes.size
                            speedProgress = 0
                            isSpeedTesting = true
                        }
                    }

                    try {
                        val keyBytes = ByteArray(32)
                        java.security.SecureRandom().nextBytes(keyBytes)
                        val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                        val xudpBaseKey = android.util.Base64.encodeToString(keyBytes, flags)
                        libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey)
                    } catch (e: Exception) {}

                    // Real delay/speed tests are actual Xray-proxied connections, not raw pings --
                    // running too many at once against the same server(s) reads as suspicious
                    // concurrent-handshake traffic to Cloudflare/DPI and gets throttled, which
                    // shows up as inflated per-node delay/speed numbers that are really just
                    // self-inflicted congestion, not real network conditions. Capped at 3,
                    // matching the same safety margin already documented next to the other
                    // real-delay semaphore below.
                    val semaphore = kotlinx.coroutines.sync.Semaphore(3)

                    // Check if we need RSTA
                    val needsRsta = targetNodes.any { 
                        val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(it.uri)
                        config?.address == "127.0.0.1"
                    }
                    if (needsRsta) {
                        com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.ensureRunning(context)
                        kotlinx.coroutines.delay(500) // Give it a moment to bind the port
                    }
                    
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        nodes = nodes.map { node ->
                            val isInTargetGroup = if (((isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine != null)) {
                                if (selectedTabEngine == "Manual") {
                                    node.engineType == "Manual" && node.groupTitle == selectedManualGroup
                                } else {
                                    node.engineType == selectedTabEngine
                                }
                            } else true
                            
                            if (!isInTargetGroup) node
                            else {
                                if (indicatorProp == "ping") node.copy(ping = "...")
                                else if (indicatorProp == "delay") node.copy(delay = "...")
                                else node.copy(speed = "...")
                            }
                        }
                    }
                    
                    val deferreds = nodes.mapIndexed { index, node ->
                        async {
                            val isInTargetGroup = if (((isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine != null)) {
                                if (selectedTabEngine == "Manual") {
                                    node.engineType == "Manual" && node.groupTitle == selectedManualGroup
                                } else {
                                    node.engineType == selectedTabEngine
                                }
                            } else true
                            
                            if (!isInTargetGroup) return@async
                            semaphore.acquire()
                            try {
                                val isJsonConfig = node.uri.trimStart().startsWith("{") || node.type == "JSON"
                                val config = if (isJsonConfig) null else com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                                val updatedNode = if (isJsonConfig) {
                                    // JSON/Serverless configs don't support direct ping/delay/speed tests
                                    if (indicatorProp == "ping") node.copy(ping = "N/A")
                                    else if (indicatorProp == "delay") node.copy(delay = "N/A")
                                    else node.copy(speed = "N/A")
                                } else if (config != null && config.address.isNotEmpty()) {
                                    kotlinx.coroutines.withTimeoutOrNull(10000L) {
                                        action(node, config)
                                    } ?: node.copy(
                                        ping = if (indicatorProp == "ping") "Timeout" else node.ping,
                                        delay = if (indicatorProp == "delay") "Timeout" else node.delay,
                                        speed = if (indicatorProp == "speed") "Timeout" else node.speed
                                    )
                                } else {
                                    if (indicatorProp == "ping") node.copy(ping = "Error")
                                    else if (indicatorProp == "delay") node.copy(delay = "Error")
                                    else node.copy(speed = "Error")
                                }
                                
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val currentList = nodes.toMutableList()
                                    currentList[index] = updatedNode
                                    nodes = currentList
                                    if (indicatorProp == "ping") pingProgress++ else speedProgress++
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val currentList = nodes.toMutableList()
                                    currentList[index] = if (indicatorProp == "ping") node.copy(ping = "Error")
                                    else if (indicatorProp == "delay") node.copy(delay = "Error")
                                    else node.copy(speed = "Error")
                                    nodes = currentList
                                    if (indicatorProp == "ping") pingProgress++ else speedProgress++
                                }
                            } finally {
                                semaphore.release()
                            }
                        }
                    }
                    try {
                        deferreds.awaitAll()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                nodes = nodes.map { node ->
                                    if (indicatorProp == "ping" && node.ping == "...") node.copy(ping = "Cancelled")
                                    else if (indicatorProp == "delay" && node.delay == "...") node.copy(delay = "Cancelled")
                                    else if (indicatorProp == "speed" && node.speed == "...") node.copy(speed = "Cancelled")
                                    else node
                                }
                                android.widget.Toast.makeText(context, "تست لغو شد", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    
                    nodeManager.nodes.clear()
                    nodeManager.nodes.addAll(nodes)
                    nodeManager.saveNodes()
                    
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (indicatorProp == "ping") {
                            isPingTesting = false
                            pingJob = null
                        } else {
                            isSpeedTesting = false
                            speedJob = null
                        }
                        android.widget.Toast.makeText(context, successMsg, android.widget.Toast.LENGTH_SHORT).show()
                        kotlinx.coroutines.delay(100)
                        listState.scrollToItem(0)
                    }
                }
                if (indicatorProp == "ping") pingJob = job else speedJob = job
            }

            val pingAllNodes = {
                android.widget.Toast.makeText(context, "Checking ping...", android.widget.Toast.LENGTH_SHORT).show()
                updateNodes("ping", { node, config ->
                    val latency = if (config.address == "127.0.0.1") {
                        realSniPing(config.address, config.port, config.sni.ifEmpty { config.wsHost })
                    } else if (config.tls == "tls" || config.sni.isNotEmpty()) {
                        tlsPing(config.address, config.port, config.sni.ifEmpty { config.wsHost })
                    } else {
                        tcpPing(config.address, config.port)
                    }
                    node.copy(ping = if (latency >= 0) "${latency}ms" else "Timeout")
                }, "Ping check completed")
            }

            val delayAllNodes = {
                val job = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val activeNodes = nodes.filter { node ->
                        if (((isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine != null)) {
                            if (selectedTabEngine == "Manual") {
                                node.engineType == "Manual" && node.groupTitle == selectedManualGroup
                            } else {
                                node.engineType == selectedTabEngine
                            }
                        } else true
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Starting Real Delay test...", android.widget.Toast.LENGTH_SHORT).show()
                        delayTotal = activeNodes.size
                        delayProgress = 0
                        isDelayTesting = true
                        nodes = nodes.map { node ->
                            val isInTargetGroup = if (((isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine != null)) {
                                if (selectedTabEngine == "Manual") {
                                    node.engineType == "Manual" && node.groupTitle == selectedManualGroup
                                } else {
                                    node.engineType == selectedTabEngine
                                }
                            } else true
                            
                            if (!isInTargetGroup) node
                            else node.copy(delay = "...")
                        }
                    }

                    // Check if we need RSTA
                    val needsRsta = activeNodes.any { 
                        val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(it.uri)
                        config?.address == "127.0.0.1" 
                    }
                    if (needsRsta) {
                        com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.ensureRunning(context)
                        kotlinx.coroutines.delay(500)
                    }

                    val validIndices = mutableListOf<Int>()
                    val validConfigs = mutableListOf<com.mlmvpn.scanner.utils.VpnConfig>()

                    nodes.forEachIndexed { idx, node ->
                        val isInTargetGroup = if (((isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine != null)) {
                            if (selectedTabEngine == "Manual") {
                                node.engineType == "Manual" && node.groupTitle == selectedManualGroup
                            } else {
                                node.engineType == selectedTabEngine
                            }
                        } else true
                        
                        if (!isInTargetGroup) return@forEachIndexed
                        val isJsonConfig = node.uri.trimStart().startsWith("{") || node.type == "JSON"
                        val config = if (isJsonConfig) null else com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                        if (isJsonConfig) {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                val currentList = nodes.toMutableList()
                                currentList[idx] = currentList[idx].copy(delay = "N/A")
                                nodes = currentList
                                delayProgress++
                            }
                        } else if (config != null && config.address.isNotEmpty()) {
                            validIndices.add(idx)
                            validConfigs.add(config)
                        } else {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                val currentList = nodes.toMutableList()
                                currentList[idx] = currentList[idx].copy(delay = "Error")
                                nodes = currentList
                                delayProgress++
                            }
                        }
                    }

                    if (validConfigs.isNotEmpty()) {
                        try {
                            // Copy dat files before init
                            try {
                                val filesToCopy = listOf("geosite.dat", "geoip.dat")
                                for (filename in filesToCopy) {
                                    val destFile = java.io.File(context.filesDir, filename)
                                    if (!destFile.exists() || destFile.length() < 1000) {
                                        context.assets.open(filename).use { input ->
                                            java.io.FileOutputStream(destFile).use { output ->
                                                val buffer = ByteArray(4096)
                                                var read: Int
                                                while (input.read(buffer).also { read = it } != -1) {
                                                    output.write(buffer, 0, read)
                                                }
                                                output.flush()
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {}
                            
                            try { 
                                val keyBytes = ByteArray(32)
                                java.security.SecureRandom().nextBytes(keyBytes)
                                val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                                val xudpBaseKey = android.util.Base64.encodeToString(keyBytes, flags)
                                libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey) 
                            } catch (e: Exception) {}

                            val measureSemaphore = kotlinx.coroutines.sync.Semaphore(3) // Cloudflare/DPI might block if >3 concurrent handshakes -- was set to 8 here, directly contradicting this comment
                            // Country lookup is much heavier (spins up its own CoreController + SOCKS
                            // proxy, see CountryLookup.kt) than the lightweight measureOutboundDelay
                            // ping above, so it gets its own small concurrency cap and runs as a
                            // separate fire-and-forget coroutine per node -- NOT inside `deferreds`,
                            // so it never blocks the delay test's own completion/progress. It only
                            // ever runs once per node: the result is cached on countryCode, and every
                            // later delay test for that node skips this block entirely.
                            val countryLookupSemaphore = kotlinx.coroutines.sync.Semaphore(3)

                            val deferreds = validConfigs.mapIndexed { i, config ->
                                async(kotlinx.coroutines.Dispatchers.IO) {
                                    measureSemaphore.acquire()
                                    var delayStr = "Timeout"
                                    var succeeded = false
                                    try {
                                        val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(config)
                                        val delayMs = kotlinx.coroutines.withTimeoutOrNull(10000L) {
                                            libv2ray.Libv2ray.measureOutboundDelay(jsonConfig, "https://clients3.google.com/generate_204")
                                        } ?: 0L
                                        if (delayMs > 0) {
                                            delayStr = "${delayMs}ms"
                                            succeeded = true
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        android.util.Log.e("BatchTest", "Native proxy test failed for config ${config.address}", e)
                                    } finally {
                                        measureSemaphore.release()
                                    }

                                    val originalIndex = validIndices[i]
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        val currentList = nodes.toMutableList()
                                        currentList[originalIndex] = currentList[originalIndex].copy(delay = delayStr)
                                        nodes = currentList
                                        delayProgress++
                                    }

                                    if (succeeded && nodes.getOrNull(originalIndex)?.countryCode == null) {
                                        // Launched on the composable's own scope, not this async's --
                                        // structured concurrency would otherwise make deferreds.awaitAll()
                                        // below wait for every country lookup too, defeating the point.
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            countryLookupSemaphore.acquire()
                                            try {
                                                val localPort = 21000 + (originalIndex % 900)
                                                val nodeUriSnapshot = nodes.getOrNull(originalIndex)?.uri ?: return@launch
                                                val country = com.mlmvpn.scanner.utils.CountryLookup.resolveCountry(context, nodeUriSnapshot, localPort)
                                                if (country != null) {
                                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                        val idx = nodes.indexOfFirst { it.uri == nodeUriSnapshot }
                                                        if (idx >= 0) {
                                                            val currentList = nodes.toMutableList()
                                                            currentList[idx] = currentList[idx].copy(countryCode = country)
                                                            nodes = currentList
                                                            nodeManager.nodes.clear()
                                                            nodeManager.nodes.addAll(nodes)
                                                            nodeManager.saveNodes()
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.d("BatchTest", "Country lookup failed: ${e.message}")
                                            } finally {
                                                countryLookupSemaphore.release()
                                            }
                                        }
                                    }
                                }
                            }
                            try {
                                deferreds.awaitAll()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                withContext(kotlinx.coroutines.NonCancellable) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        nodes = nodes.map { if (it.delay == "...") it.copy(delay = "Cancelled") else it }
                                        android.widget.Toast.makeText(context, "تست لغو شد", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    nodes = nodes.map { if (it.delay == "...") it.copy(delay = "Cancelled") else it }
                                }
                            }
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("BatchTest", "Xray Batch test failed", e)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Batch Test Error", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    nodeManager.nodes.clear()
                    nodeManager.nodes.addAll(nodes)
                    nodeManager.saveNodes()
                    
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        isDelayTesting = false
                        delayJob = null
                        android.widget.Toast.makeText(context, "Real Delay check completed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                delayJob = job
            }

            val speedAllNodes = {
                android.widget.Toast.makeText(context, "Testing speed...", android.widget.Toast.LENGTH_SHORT).show()
                updateNodes("speed", { node, config ->
                    val speed = realSpeedTest(config, context)
                    node.copy(speed = speed)
                }, "Speed test completed")
            }

            // Action Bar (Sticky)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark, RoundedCornerShape(16.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
                    .padding(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ActionButton(Icons.Default.FlashOn, stringResource(com.mlmvpn.scanner.R.string.action_ping)) { pingAllNodes() }
                    Divider(Modifier.height(24.dp).width(1.dp), color = BorderDark)
                    ActionButton(Icons.Default.AccessTime, stringResource(com.mlmvpn.scanner.R.string.action_delay)) { delayAllNodes() }
                    Divider(Modifier.height(24.dp).width(1.dp), color = BorderDark)
                    ActionButton(Icons.Default.Download, stringResource(com.mlmvpn.scanner.R.string.action_speed)) { speedAllNodes() }
                    Divider(Modifier.height(24.dp).width(1.dp), color = BorderDark)
                    ActionButton(Icons.Default.Add, stringResource(com.mlmvpn.scanner.R.string.action_add)) { showAddManual = true }
                    Divider(Modifier.height(24.dp).width(1.dp), color = BorderDark)
                    ActionButton(Icons.Default.Sort, stringResource(com.mlmvpn.scanner.R.string.action_sort), if (sortState != 0) Primary else TextMuted) {
                        sortState = if (sortState == 1) 2 else 1
                        sharedPrefs.edit().putInt("sort_state", sortState).apply()
                    }
                }

                androidx.compose.animation.AnimatedVisibility(visible = isPingTesting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "پینگ: $pingProgress / $pingTotal",
                            color = Primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        LinearProgressIndicator(
                            progress = if (pingTotal > 0) pingProgress.toFloat() / pingTotal else 0f,
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Primary,
                            trackColor = BorderDark
                        )
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = "Cancel",
                            tint = TextDim,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable {
                                    pingJob?.cancel()
                                    isPingTesting = false
                                    pingProgress = 0
                                }
                        )
                    }
                }

                androidx.compose.animation.AnimatedVisibility(visible = isDelayTesting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "دیلی: $delayProgress / $delayTotal",
                            color = Primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        LinearProgressIndicator(
                            progress = if (delayTotal > 0) delayProgress.toFloat() / delayTotal else 0f,
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Primary,
                            trackColor = BorderDark
                        )
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = "Cancel",
                            tint = TextDim,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable {
                                    delayJob?.cancel()
                                    isDelayTesting = false
                                    delayProgress = 0
                                }
                        )
                    }
                }

                androidx.compose.animation.AnimatedVisibility(visible = isSpeedTesting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "سرعت: $speedProgress / $speedTotal",
                            color = Primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        LinearProgressIndicator(
                            progress = if (speedTotal > 0) speedProgress.toFloat() / speedTotal else 0f,
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Primary,
                            trackColor = BorderDark
                        )
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = "Cancel",
                            tint = TextDim,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable {
                                    speedJob?.cancel()
                                    isSpeedTesting = false
                                    speedProgress = 0
                                }
                        )
                    }
                }
            }

            if (showAddManual) {
                AddNodeModal(
                    onDismiss = { showAddManual = false },
                    onNodesAdded = { newNodes ->
                        nodeManager.nodes.addAll(0, newNodes)
                        nodeManager.saveNodes()
                        nodes = newNodes + nodes
                    },
                    onSubscriptionAdded = { name, url ->
                        val sub = subscriptionManager.addSubscription(name, url)
                        android.widget.Toast.makeText(context, "در حال دریافت کانفیگ‌ها...", android.widget.Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val res = subscriptionManager.updateSubscription(sub, nodeManager)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(context, res.second, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onUpdateSubscriptions = {
                        android.widget.Toast.makeText(context, "در حال بروزرسانی ساب‌ها...", android.widget.Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val res = subscriptionManager.updateAllSubscriptions(nodeManager)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "موفق: ${res.first} | ناموفق: ${res.second}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    selectedManualGroup = selectedManualGroup
                )
            }


            Spacer(modifier = Modifier.height(24.dp))

            // Nodes List Header & Toggle Switch
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark, RoundedCornerShape(12.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
            ) {
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { isHeaderExpanded = !isHeaderExpanded }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isHeaderExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = "Details", tint = TextPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        val moreText = stringResource(R.string.nodes_more)
                        val headerText = if (((isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine != null)) stringResource(R.string.nodes_of_panel, selectedTabEngine!!) else moreText
                        Text(headerText, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(color = BgDark, shape = CircleShape, border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)) {
                        Text(stringResource(R.string.nodes_servers_count, displayedNodes.size), color = Primary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
                
                androidx.compose.animation.AnimatedVisibility(visible = isHeaderExpanded) {
                    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (distinctEngines.size > 1) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(com.mlmvpn.scanner.R.string.nodes_group_panels), color = TextPrimary, fontSize = 14.sp)
                                Switch(
                                    checked = isGroupedByPanel,
                                    onCheckedChange = { isGroupedByPanel = it },
                                    modifier = Modifier.scale(0.8f).height(24.dp),
                                    colors = SwitchDefaults.colors(checkedThumbColor = Primary, checkedTrackColor = Primary.copy(alpha = 0.5f), uncheckedThumbColor = TextDim, uncheckedTrackColor = BorderDark)
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(com.mlmvpn.scanner.R.string.nodes_platform_mode), color = TextPrimary, fontSize = 14.sp)
                            Switch(
                                checked = isPlatformMode,
                                onCheckedChange = { 
                                    isPlatformMode = it
                                    if (it && selectedPlatform == null) {
                                        selectedPlatform = com.mlmvpn.scanner.utils.Platform.values().first()
                                    }
                                },
                                modifier = Modifier.scale(0.8f).height(24.dp),
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF03A9F4), checkedTrackColor = Color(0xFF03A9F4).copy(alpha = 0.5f), uncheckedThumbColor = TextDim, uncheckedTrackColor = BorderDark)
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(com.mlmvpn.scanner.R.string.nodes_auto_switch), color = TextPrimary, fontSize = 14.sp)
                            Switch(
                                checked = isAutoSwitchEnabled,
                                onCheckedChange = { 
                                    if (it) {
                                        showAutoSwitchModal = true
                                    } else {
                                        isAutoSwitchEnabled = false
                                        sharedPrefs.edit().putBoolean("auto_switch_enabled", false).apply()
                                    }
                                },
                                modifier = Modifier.scale(0.8f).height(24.dp),
                                colors = SwitchDefaults.colors(checkedThumbColor = GreenOk, checkedTrackColor = GreenOk.copy(alpha = 0.5f), uncheckedThumbColor = TextDim, uncheckedTrackColor = BorderDark)
                            )
                        }
                        if (displayedNodes.isNotEmpty()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(com.mlmvpn.scanner.R.string.nodes_delete_current), color = RedError, fontSize = 14.sp)
                                IconButton(onClick = { showDeleteAllModal = true }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Clear", tint = RedError)
                                }
                            }
                        }
                    }
                }
            }

            if (distinctEngines.size > 1 && isGroupedByPanel) {
                Spacer(modifier = Modifier.height(8.dp))
                // Stylish custom tabs (Segmented Control)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark, RoundedCornerShape(12.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    distinctEngines.forEach { engine ->
                        val isSelected = selectedTabEngine == engine
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Primary else Color.Transparent)
                                .clickable { selectedTabEngine = engine }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (engine == "BPB") "BPB" else engine,
                                color = if (isSelected) BgDark else TextMuted,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Secondary Tabs for Manual Group
            var showCreateGroupDialog by remember { mutableStateOf(false) }
            var showGroupMenu by remember { mutableStateOf<String?>(null) }
            var groupToRename by remember { mutableStateOf<String?>(null) }
            
            if (((isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine == "Manual")) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(SurfaceDark, RoundedCornerShape(8.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        // Add Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { showCreateGroupDialog = true }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Group", tint = TextMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                    
                    // Default Tab
                    item {
                        val isSelected = selectedManualGroup == null
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) Primary else Color.Transparent)
                                .clickable { selectedManualGroup = null }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "پیش‌فرض",
                                color = if (isSelected) BgDark else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                    
                    // Custom Groups & Subs
                    items(distinctManualGroups) { group ->
                        val isSelected = selectedManualGroup == group
                        val isSub = subscriptions.any { it.id == group || it.name == group }
                        val displayName = if (isSub) subscriptions.find { it.id == group || it.name == group }?.name ?: group else group
                        
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) Primary else Color.Transparent)
                                .combinedClickable(
                                    onClick = { selectedManualGroup = group },
                                    onLongClick = { showGroupMenu = group }
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isSub) Icons.Default.CloudDownload else Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = if (isSelected) BgDark else TextPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    displayName,
                                    color = if (isSelected) BgDark else TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            GroupManagementDialogs(
                showCreateGroupDialog = showCreateGroupDialog,
                onCreateGroupDismiss = { showCreateGroupDialog = false },
                onCreateGroupConfirm = { newGroup ->
                    if (!emptyCustomGroups.contains(newGroup)) emptyCustomGroups.add(newGroup)
                    selectedManualGroup = newGroup
                    showCreateGroupDialog = false
                },
                showGroupMenu = showGroupMenu,
                onGroupMenuDismiss = { showGroupMenu = null },
                onRenameClick = { 
                    groupToRename = showGroupMenu
                    showGroupMenu = null 
                },
                onDeleteClick = { group ->
                    nodeManager.removeNodesByGroup(group)
                    subscriptionManager.removeSubscription(group)
                    if (selectedManualGroup == group) selectedManualGroup = null
                    emptyCustomGroups.remove(group)
                    showGroupMenu = null
                },
                onUpdateSubClick = { subId ->
                    val sub = subscriptions.find { it.id == subId || it.name == subId }
                    if (sub != null) {
                        android.widget.Toast.makeText(context, "در حال بروزرسانی ساب...", android.widget.Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val res = subscriptionManager.updateSubscription(sub, nodeManager)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(context, res.second, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    showGroupMenu = null
                },
                groupToRename = groupToRename,
                onRenameDismiss = { groupToRename = null },
                onRenameConfirm = { oldName, newName ->
                    val updated = nodeManager.nodes.map { 
                        if (it.engineType == "Manual" && it.groupTitle == oldName) it.copy(groupTitle = newName) else it 
                    }
                    nodeManager.nodes.clear()
                    nodeManager.nodes.addAll(updated)
                    nodeManager.saveNodes()
                    if (emptyCustomGroups.contains(oldName)) {
                        emptyCustomGroups.remove(oldName)
                        emptyCustomGroups.add(newName)
                    }
                    if (selectedManualGroup == oldName) selectedManualGroup = newName
                    groupToRename = null
                },
                isSubscription = subscriptions.any { it.id == showGroupMenu || it.name == showGroupMenu }
            )

            if (isPlatformMode) {
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark, RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF03A9F4).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(com.mlmvpn.scanner.utils.Platform.values()) { platform ->
                        val isSelected = selectedPlatform == platform
                        val iconRes = when (platform) {
                            com.mlmvpn.scanner.utils.Platform.INSTAGRAM -> Icons.Default.CameraAlt
                            com.mlmvpn.scanner.utils.Platform.YOUTUBE -> Icons.Default.PlayArrow
                            com.mlmvpn.scanner.utils.Platform.TIKTOK -> Icons.Default.MusicNote
                            com.mlmvpn.scanner.utils.Platform.TWITTER -> Icons.Default.Close
                            com.mlmvpn.scanner.utils.Platform.WHATSAPP -> Icons.Default.Chat
                            com.mlmvpn.scanner.utils.Platform.GEMINI -> Icons.Default.AutoAwesome
                            com.mlmvpn.scanner.utils.Platform.ANTIGRAVITY -> Icons.Default.Build
                            com.mlmvpn.scanner.utils.Platform.CLAUDE -> Icons.Default.Face
                            com.mlmvpn.scanner.utils.Platform.TRAE -> Icons.Default.Edit
                            com.mlmvpn.scanner.utils.Platform.CAPCUT -> Icons.Default.Movie
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF03A9F4) else Color.Transparent)
                                .clickable { selectedPlatform = platform }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(iconRes, contentDescription = platform.displayName, tint = if (isSelected) BgDark else TextMuted, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = platform.displayName,
                                    color = if (isSelected) BgDark else TextMuted,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Nodes List
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // The built-in Iran configs are upstream Serverless-for-Iran files, injected
                // untouched -- their inbound is hardcoded to 10808 and we deliberately do NOT
                // rewrite it to the user's Local Port, because editing these configs is exactly
                // what breaks them. So in proxy mode a changed Local Port silently points at a
                // port nothing is listening on. Warn inside the group instead of guessing.
                if (selectedTabEngine == "Manual" &&
                    selectedManualGroup == com.mlmvpn.scanner.data.NodeManager.IRAN_GROUP) {
                    item {
                        val userPort = androidx.preference.PreferenceManager
                            .getDefaultSharedPreferences(context)
                            .getString("local_port", "10808") ?: "10808"
                        val portIsWrong = userPort != "10808"
                        val accent = if (portIsWrong) RedError else YellowWarn
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(accent.copy(alpha = 0.10f))
                                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                if (portIsWrong) Icons.Default.Error else Icons.Default.Info,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    if (portIsWrong) "پورت محلی باید ۱۰۸۰۸ باشد"
                                    else "پورت محلی را روی ۱۰۸۰۸ نگه دارید",
                                    color = accent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    if (portIsWrong)
                                        "پورت محلی شما روی $userPort تنظیم شده. کانفیگ‌های ایران فقط با پورت ۱۰۸۰۸ کار می‌کنند و این کانفیگ‌ها عمداً دست‌نخورده باقی می‌مانند. از تنظیمات، پورت محلی را به ۱۰۸۰۸ برگردانید."
                                    else
                                        "این کانفیگ‌ها دقیقاً همان فایل‌های اصلی سرورلس هستند و پورت داخلی‌شان ۱۰۸۰۸ است. اگر پورت محلی را در تنظیمات تغییر دهید، در حالت پروکسی کار نمی‌کنند.",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
                // Domain-fronting folder: the setup card sits above the config so the user can
                // go from empty folder to a connectable node without leaving this screen.
                //
                // Gated on the same condition that draws the folder row itself, not just on
                // `selectedManualGroup`: with grouping off and more than one engine the folder row
                // is hidden but `selectedManualGroup` keeps its last value, so checking the group
                // alone leaked this card into the flat all-nodes list.
                val inMitmFolder = (isGroupedByPanel || distinctEngines.size <= 1) &&
                    selectedTabEngine == "Manual" &&
                    selectedManualGroup == com.mlmvpn.scanner.mitm.MitmProfile.GROUP
                if (inMitmFolder) {
                    item { com.mlmvpn.scanner.ui.mitm.MitmSetupCard() }
                }
                if (displayedNodes.isEmpty() && !inMitmFolder) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .fillParentMaxHeight(0.7f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Layers, contentDescription = null, tint = TextDim, modifier = Modifier.size(96.dp))
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(stringResource(R.string.nodes_no_config_available), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                } else {
                    items(
                        items = displayedNodes
                    ) { node ->
                        NodeCard(
                            node = node,
                            isActive = activeNodeId == node.id,
                            isExpanded = expandedNodeId == node.id,
                            onClick = {
                                activeNodeId = node.id
                                if (isWireguardTrialActive()) {
                                    // WireGuard trial is up — starting an Xray node now would crash.
                                    pendingNodeConnect = node
                                    showWgConflict = true
                                } else if (isRunning) {
                                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                                    val startIntent = android.content.Intent(context, com.mlmvpn.scanner.MyVpnService::class.java).apply {
                                        putExtra("NODE_URI", node.uri)
                                        putExtra("NODE_ID", node.id)
                                        putExtra("PROXY_MODE", prefs.getBoolean("proxy_mode", false))
                                        putExtra("LOCAL_PORT", prefs.getString("local_port", "10808"))
                                    }
                                    context.startService(startIntent)
                                    com.mlmvpn.scanner.MyVpnService.connectedNodeId = node.id
                                    android.widget.Toast.makeText(context, "Switching server...", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            onExpandClick = { expandedNodeId = if (expandedNodeId == node.id) null else node.id },
                            onDelete = { nodeToDelete = node },
                            highlight = topNodeHighlightedId == node.id,
                            platformDelay = if (isPlatformMode && selectedPlatform != null) platformTestResults["${node.id}_${selectedPlatform!!.name}"] else null,
                            onPingClick = {
                                val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                                if (config != null) {
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            val currentList = nodes.toMutableList()
                                            val index = currentList.indexOfFirst { it.id == node.id }
                                            if (index != -1) {
                                                currentList[index] = currentList[index].copy(ping = "...")
                                                nodes = currentList
                                            }
                                        }
                                        val latency = if (config.address == "127.0.0.1") {
                                            realSniPing(config.address, config.port, config.sni.ifEmpty { config.wsHost })
                                        } else if (config.tls == "tls" || config.sni.isNotEmpty()) {
                                            tlsPing(config.address, config.port, config.sni.ifEmpty { config.wsHost })
                                        } else {
                                            tcpPing(config.address, config.port)
                                        }
                                        val newPing = if (latency >= 0) "${latency}ms" else "Timeout"
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            val currentList = nodes.toMutableList()
                                            val index = currentList.indexOfFirst { it.id == node.id }
                                            if (index != -1) {
                                                currentList[index] = currentList[index].copy(ping = newPing)
                                                nodes = currentList
                                                nodeManager.nodes.clear()
                                                nodeManager.nodes.addAll(nodes)
                                                nodeManager.saveNodes()
                                            }
                                        }
                                    }
                                }
                            },
                            onDelayClick = {
                                val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                                if (config != null) {
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            val currentList = nodes.toMutableList()
                                            val index = currentList.indexOfFirst { it.id == node.id }
                                            if (index != -1) {
                                                currentList[index] = currentList[index].copy(delay = "...")
                                                nodes = currentList
                                            }
                                        }
                                        if (config.address == "127.0.0.1") {
                                            com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.ensureRunning(context)
                                            kotlinx.coroutines.delay(500)
                                        }
                                        var delayStr = "Timeout"
                                        try {
                                            // Copy dat files before init
                                            try {
                                                val filesToCopy = listOf("geosite.dat", "geoip.dat")
                                                for (filename in filesToCopy) {
                                                    val destFile = java.io.File(context.filesDir, filename)
                                                    if (!destFile.exists() || destFile.length() < 1000) {
                                                        context.assets.open(filename).use { input ->
                                                            java.io.FileOutputStream(destFile).use { output ->
                                                                val buffer = ByteArray(4096)
                                                                var read: Int
                                                                while (input.read(buffer).also { read = it } != -1) {
                                                                    output.write(buffer, 0, read)
                                                                }
                                                                output.flush()
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {}
                                            
                                            try { 
                                                val keyBytes = ByteArray(32)
                                                java.security.SecureRandom().nextBytes(keyBytes)
                                                val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                                                val xudpBaseKey = android.util.Base64.encodeToString(keyBytes, flags)
                                                libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey) 
                                            } catch (e: Exception) {}

                                            val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(config)
                                            val delayMs = libv2ray.Libv2ray.measureOutboundDelay(jsonConfig, "https://clients3.google.com/generate_204")
                                            if (delayMs > 0) {
                                                delayStr = "${delayMs}ms"
                                            }
                                        } catch (e: Exception) {
                                            // Keep Timeout
                                        }
                                        
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            val currentList = nodes.toMutableList()
                                            val index = currentList.indexOfFirst { it.id == node.id }
                                            if (index != -1) {
                                                currentList[index] = currentList[index].copy(delay = delayStr)
                                                nodes = currentList
                                                nodeManager.nodes.clear()
                                                nodeManager.nodes.addAll(nodes)
                                                nodeManager.saveNodes()
                                            }
                                        }
                                    }
                                }
                            },
                            onSpeedClick = {
                                val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                                if (config != null) {
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            val currentList = nodes.toMutableList()
                                            val index = currentList.indexOfFirst { it.id == node.id }
                                            if (index != -1) {
                                                currentList[index] = currentList[index].copy(speed = "...")
                                                nodes = currentList
                                            }
                                        }
                                        if (config.address == "127.0.0.1") {
                                            com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.ensureRunning(context)
                                            kotlinx.coroutines.delay(500)
                                        }
                                        val speedStr = realSpeedTest(config, context)
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            val currentList = nodes.toMutableList()
                                            val index = currentList.indexOfFirst { it.id == node.id }
                                            if (index != -1) {
                                                currentList[index] = currentList[index].copy(speed = speedStr)
                                                nodes = currentList
                                                nodeManager.nodes.clear()
                                                nodeManager.nodes.addAll(nodes)
                                                nodeManager.saveNodes()
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(140.dp)) // Extra padding so FAB doesn't hide last item
                }
            }
        }

        if (showDeleteAllModal) {
            AlertDialog(
                onDismissRequest = { showDeleteAllModal = false },
                containerColor = SurfaceDark,
                title = { Text(stringResource(R.string.dialog_delete_all_nodes_title), color = TextPrimary) },
                text = { Text(stringResource(R.string.dialog_delete_all_nodes_msg), color = TextMuted) },
                confirmButton = {
                    TextButton(onClick = {
                        // Delete exactly what's currently on screen (displayedNodes) — i.e. only the
                        // selected folder when a group is open, not every folder in the tab. Protected
                        // Iran defaults are always kept.
                        val displayedIds = displayedNodes.map { it.id }.toSet()
                        val remaining = nodes.filterNot {
                            it.id in displayedIds && !com.mlmvpn.scanner.data.NodeManager.isProtected(it)
                        }
                        nodeManager.nodes.clear()
                        nodeManager.nodes.addAll(remaining)
                        nodeManager.saveNodes()
                        nodes = remaining
                        showDeleteAllModal = false
                        android.widget.Toast.makeText(context, context.getString(R.string.nodes_cleared), android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.nodes_confirm), color = RedError)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAllModal = false }) {
                        Text(stringResource(R.string.nodes_cancel), color = TextMuted)
                    }
                }
            )
        }

        if (nodeToDelete != null) {
            AlertDialog(
                onDismissRequest = { nodeToDelete = null },
                containerColor = SurfaceDark,
                title = { Text(stringResource(R.string.dialog_delete_node_title), color = TextPrimary) },
                text = { Text(stringResource(R.string.dialog_delete_node_msg), color = TextMuted) },
                confirmButton = {
                    TextButton(onClick = {
                        nodes = nodes.filter { it.id != nodeToDelete!!.id || com.mlmvpn.scanner.data.NodeManager.isProtected(it) }
                        nodeToDelete = null
                    }) {
                        Text(stringResource(R.string.nodes_confirm), color = RedError)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { nodeToDelete = null }) {
                        Text(stringResource(R.string.nodes_cancel), color = TextMuted)
                    }
                }
            )
        }

        if (showAutoSwitchModal) {
            AlertDialog(
                onDismissRequest = { 
                    showAutoSwitchModal = false
                    isAutoSwitchEnabled = false
                },
                containerColor = SurfaceDark,
                title = { Text(stringResource(R.string.auto_switch_title), color = Primary, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.auto_switch_app), color = TextPrimary)
                        var expanded by remember { mutableStateOf(false) }
                        val entireDeviceStr = stringResource(R.string.auto_switch_entire_device)
                        val platforms = listOf(entireDeviceStr) + com.mlmvpn.scanner.utils.Platform.values().map { it.name }
                        
                        Box(modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = { expanded = true }, 
                                modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                            ) {
                                Text(if (autoSwitchPlatform == "None") entireDeviceStr else autoSwitchPlatform, color = TextPrimary)
                            }
                            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = SurfaceDark, primary = Primary)) {
                                DropdownMenu(
                                    expanded = expanded, 
                                    onDismissRequest = { expanded = false },
                                    modifier = Modifier.background(SurfaceDark)
                                ) {
                                    platforms.forEach { plat ->
                                        val platVal = if (plat == entireDeviceStr) "None" else plat
                                        DropdownMenuItem(
                                            text = { Text(plat, color = TextPrimary) },
                                            onClick = { autoSwitchPlatform = platVal; expanded = false },
                                            colors = androidx.compose.material3.MenuDefaults.itemColors(
                                                textColor = TextPrimary,
                                                leadingIconColor = Primary,
                                                trailingIconColor = Primary
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        Text(stringResource(R.string.auto_switch_interval, autoSwitchInterval), color = TextPrimary)
                        Slider(
                            value = autoSwitchInterval.toFloat(),
                            onValueChange = { autoSwitchInterval = it.toInt() },
                            valueRange = 1f..60f,
                            steps = 59,
                            colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
                        )

                        val allServersStr = stringResource(R.string.auto_switch_all_servers)
                        Text(stringResource(R.string.auto_switch_test_count, if (autoSwitchTestCount == 0) allServersStr else autoSwitchTestCount.toString()), color = TextPrimary)
                        Slider(
                            value = autoSwitchTestCount.toFloat(),
                            onValueChange = { autoSwitchTestCount = it.toInt() },
                            valueRange = 0f..100f,
                            steps = 19,
                            colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
                        )

                        // Removed test type checkboxes as per user request
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        sharedPrefs.edit()
                            .putBoolean("auto_switch_enabled", true)
                            .putString("auto_switch_platform", autoSwitchPlatform)
                            .putInt("auto_switch_interval", autoSwitchInterval)
                            .putInt("auto_switch_test_count", autoSwitchTestCount)
                            .apply()
                        isAutoSwitchEnabled = true
                        showAutoSwitchModal = false
                        val toastStr = context.getString(R.string.auto_switch_enabled_toast)
                        android.widget.Toast.makeText(context, toastStr, android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.common_confirm), color = Primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showAutoSwitchModal = false
                        isAutoSwitchEnabled = false 
                    }) {
                        Text(stringResource(R.string.common_cancel), color = TextMuted)
                    }
                }
            )
        }

        // Floating Connect Button (FAB)
        if (displayedNodes.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 100.dp)
            ) {
                val btnBgColor by animateColorAsState(if (isRunning) GreenOk else if (isConnecting) BgDark else Primary, label = "fabBg")
                val btnContentColor by animateColorAsState(if (isRunning || !isConnecting) BgDark else Primary, label = "fabContent")

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isRunning && activeRealCountry != null) {
                    Box(modifier = Modifier.padding(bottom = 12.dp)) {
                        Text(
                            text = getNodeFlagEmoji(activeRealCountry ?: "XX"),
                            fontSize = 36.sp
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 6.dp, y = 6.dp)
                                .background(BgDark, CircleShape)
                                .padding(2.dp)
                        ) {
                            Text(
                                text = activeRealCountry ?: "",
                                color = TextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(Primary, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                if (isPlatformMode && selectedPlatform != null) {
                    Surface(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .size(56.dp)
                            .clickable {
                                if (!isPlatformTesting) {
                                    isPlatformTesting = true
                                    scope.launch {
                                        android.widget.Toast.makeText(context, "Testing ${selectedPlatform!!.displayName}...", android.widget.Toast.LENGTH_SHORT).show()
                                        
                                        val nodesToTest = displayedNodes.take(20)
                                        val semaphore = kotlinx.coroutines.sync.Semaphore(5)
                                        val deferredResults = nodesToTest.mapIndexed { index, node ->
                                            async {
                                                semaphore.acquire()
                                                try {
                                                    if (!isActive) return@async node.id to -1L
                                                    val delay = com.mlmvpn.scanner.utils.PlatformTester.testNodeForPlatform(context, node.uri, selectedPlatform!!, 20000 + index)
                                                    node.id to delay
                                                } finally {
                                                    semaphore.release()
                                                }
                                            }
                                        }
                                        
                                        deferredResults.forEach { deferred ->
                                            val (nodeId, delay) = deferred.await()
                                            if (delay > 0) {
                                                platformTestResults["${nodeId}_${selectedPlatform!!.name}"] = delay
                                            }
                                        }
                                        
                                        isPlatformTesting = false
                                        
                                        val sorted = nodesToTest.filter { (platformTestResults["${it.id}_${selectedPlatform!!.name}"] ?: -1) > 0 }
                                            .sortedBy { platformTestResults["${it.id}_${selectedPlatform!!.name}"] }
                                        
                                        if (sorted.isNotEmpty()) {
                                            val topNode = sorted.first()
                                            topNodeHighlightedId = topNode.id
                                            activeNodeId = topNode.id
                                            // Ensure it stays highlighted until another platform is tested
                                        } else {
                                            android.widget.Toast.makeText(context, "No server connected successfully", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isPlatformTesting) BgDark else Color(0xFFFFC107),
                        border = if (isPlatformTesting) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFFC107)) else null,
                        shadowElevation = 8.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isPlatformTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFFFFC107), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.FlashOn, contentDescription = "Test Platform", modifier = Modifier.size(26.dp), tint = BgDark)
                            }
                        }
                    }
                }
                
                Surface(
                    modifier = Modifier
                        .size(56.dp)
                        .clickable {
                            if (activeNodeId == null) {
                                android.widget.Toast.makeText(context, "Config copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                return@clickable
                            }
                            if (isRunning) {
                                // stopVpnSafely, not a bare STOP: if the WireGuard trial is what is
                                // running, the process must be relaunched or the Go runtime exits
                                // by itself seconds later. No-op for every other engine.
                                stopVpnSafely(context)
                                MyVpnService.isRunning = false
                            } else if (!isConnecting) {
                                isConnecting = true
                                val intent = VpnService.prepare(context)
                                if (intent != null) {
                                    vpnLauncher.launch(intent)
                                } else {
                                    val selectedId = activeNodeId
                                    val node = nodes.find { it.id == selectedId }
                                    if (node != null) {
                                        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                                        val isProxyMode = prefs.getBoolean("proxy_mode", false)
                                        val localPort = prefs.getString("local_port", "10808")

                                        val startIntent = Intent(context, MyVpnService::class.java).apply {
                                            putExtra("NODE_URI", node.uri)
                                            putExtra("NODE_ID", node.id)
                                            putExtra("PROXY_MODE", isProxyMode)
                                            putExtra("LOCAL_PORT", localPort)
                                        }
                                        context.startService(startIntent)
                                        MyVpnService.isRunning = true
                                        MyVpnService.connectedNodeId = node.id
                                        isConnecting = false
                                        Toast.makeText(context, "VPN Tunnel Initialized!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        isConnecting = false
                                    }
                                }
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = btnBgColor,
                    border = if (isConnecting) androidx.compose.foundation.BorderStroke(2.dp, Primary) else null,
                    shadowElevation = 8.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isConnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Primary, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Connect", modifier = Modifier.size(26.dp), tint = btnContentColor)
                        }
                    }
                }
                    }
            }
        }
    }
}

@Composable
fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = TextMuted, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(if (isFocused) Primary.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(8.dp))
            .border(if (isFocused) 1.dp else 0.dp, if (isFocused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 12.dp)
    ) {
        Icon(icon, contentDescription = label, tint = if (isFocused) Color.White else tint, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = if (isFocused) Color.White else tint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

suspend fun tlsPing(host: String, port: Int, sni: String): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val socket = java.net.Socket()
        MyVpnService.instance?.protect(socket)
        val start = System.currentTimeMillis()
        socket.soTimeout = 3000
        socket.connect(java.net.InetSocketAddress(host, port), 3000)
        
        val sslSocketFactory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        val sslSocket = sslSocketFactory.createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
        
        val params = sslSocket.sslParameters
        val sniHostName = javax.net.ssl.SNIHostName(if (sni.isNotEmpty()) sni else host)
        params.serverNames = listOf(sniHostName)
        sslSocket.sslParameters = params
        
        sslSocket.startHandshake()
        
        val end = System.currentTimeMillis()
        sslSocket.close()
        (end - start).toInt()
    } catch (e: Exception) {
        -1
    }
}

suspend fun realSniPing(host: String, port: Int, sni: String): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val socket = java.net.Socket()
        val start = System.currentTimeMillis()
        socket.soTimeout = 3000
        socket.connect(java.net.InetSocketAddress(host, port), 3000)
        
        val sslSocketFactory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        val sslSocket = sslSocketFactory.createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
        
        val params = sslSocket.sslParameters
        val sniHostName = javax.net.ssl.SNIHostName(if (sni.isNotEmpty()) sni else host)
        params.serverNames = listOf(sniHostName)
        sslSocket.sslParameters = params
        
        sslSocket.startHandshake()
        
        val out = sslSocket.outputStream
        val request = "GET / HTTP/1.1\r\nHost: $sni\r\nConnection: close\r\n\r\n"
        out.write(request.toByteArray())
        out.flush()
        
        val input = sslSocket.inputStream.bufferedReader()
        val responseLine = input.readLine()
        
        val end = System.currentTimeMillis()
        sslSocket.close()
        
        if (responseLine == null) return@withContext -1
        
        if (responseLine.contains("404") || responseLine.contains("502") || 
            responseLine.contains("530") || responseLine.contains("521") || 
            responseLine.contains("1004") || responseLine.contains("1000")) {
            return@withContext -1
        }
        
        (end - start).toInt()
    } catch (e: Exception) {
        -1
    }
}

suspend fun tcpPing(host: String, port: Int): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val socket = java.net.Socket()
        MyVpnService.instance?.protect(socket)
        val start = System.currentTimeMillis()
        socket.soTimeout = 3000
        socket.connect(java.net.InetSocketAddress(host, port), 3000)
        val end = System.currentTimeMillis()
        socket.close()
        (end - start).toInt()
    } catch (e: Exception) {
        -1
    }
}

suspend fun httpDelay(host: String, port: Int): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val socket = java.net.Socket()
        MyVpnService.instance?.protect(socket)
        val start = System.currentTimeMillis()
        socket.soTimeout = 3000
        socket.connect(java.net.InetSocketAddress(host, port), 3000)
        
        val output = socket.getOutputStream()
        output.write("GET / HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
        output.flush()
        
        val input = socket.getInputStream()
        input.read() // read first byte
        val end = System.currentTimeMillis()
        socket.close()
        (end - start).toInt()
    } catch (e: Exception) {
        tcpPing(host, port)
    }
}

suspend fun realSpeedTest(config: com.mlmvpn.scanner.utils.VpnConfig, context: android.content.Context): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val localPort = (20000..30000).random()
        val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateConfig(config, localPort, includeTun = false)
        
        var speedStr = "Timeout"
        var coreController: libv2ray.CoreController? = null
        try {
            coreController = libv2ray.Libv2ray.newCoreController(object : libv2ray.CoreCallbackHandler {
                override fun onEmitStatus(status: Long, message: String): Long = 0
                override fun shutdown(): Long = 0
                override fun startup(): Long = 0
            })

            // Log the JSON config for debugging
            android.util.Log.d("XraySpeedTest", "Speed Test JSON Config:\n$jsonConfig")
            coreController.startLoop(jsonConfig, 0)
            
            // Give Xray time to start listening on localPort
            kotlinx.coroutines.delay(500)

            val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", localPort + 10000))
            val client = okhttp3.OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url("https://proof.ovh.net/files/1Mb.dat") // 1MB payload from OVH (non-Cloudflare)
                .build()

            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()?.size ?: 0
                val endTime = System.currentTimeMillis()
                val durationMs = endTime - startTime
                if (durationMs > 0 && bytes > 0) {
                    val speedMb = (bytes.toDouble() / 1024.0 / 1024.0) / (durationMs.toDouble() / 1000.0)
                    speedStr = String.format(java.util.Locale.US, "%.1f MB/s", speedMb)
                }
            } else {
                speedStr = "Error"
            }
        } catch (e: Exception) {
            speedStr = "Timeout"
        } finally {
            try { coreController?.stopLoop() } catch (e: Exception) {}
        }
        speedStr
    } catch (e: Exception) {
        "Error"
    }
}

fun showShareDialog(context: android.content.Context, uri: String) {
    var activityCtx: android.content.Context = context
    while (activityCtx is android.content.ContextWrapper && activityCtx !is android.app.Activity) {
        activityCtx = activityCtx.baseContext
    }
    val themedContext = if (activityCtx is android.app.Activity) activityCtx else context
    val dialogBuilder = com.google.android.material.dialog.MaterialAlertDialogBuilder(themedContext, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_Dialog_Alert)
    val dialogView = android.view.LayoutInflater.from(dialogBuilder.context).inflate(com.mlmvpn.scanner.R.layout.dialog_share_node, null)
    val btnCopy = dialogView.findViewById<com.google.android.material.button.MaterialButton>(com.mlmvpn.scanner.R.id.btn_copy)
    val btnClose = dialogView.findViewById<com.google.android.material.button.MaterialButton>(com.mlmvpn.scanner.R.id.btn_close)
    val ivQr = dialogView.findViewById<android.widget.ImageView>(com.mlmvpn.scanner.R.id.iv_qr_code)
    
    btnCopy.setOnClickListener {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("VPN Node", uri)
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(context, context.getString(com.mlmvpn.scanner.R.string.node_copied), android.widget.Toast.LENGTH_SHORT).show()
    }

    try {
        val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType::class.java)
        hints[com.google.zxing.EncodeHintType.MARGIN] = 1
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val bitMatrix = writer.encode(uri, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        ivQr.setImageBitmap(bitmap)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, context.getString(com.mlmvpn.scanner.R.string.node_qr_error), android.widget.Toast.LENGTH_SHORT).show()
    }

    val dialog = dialogBuilder.setView(dialogView).create()
        
    btnClose.setOnClickListener { dialog.dismiss() }
    dialog.show()
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
}

fun showEditNodeDialog(context: android.content.Context, node: com.mlmvpn.scanner.models.VpnNode, nodeManager: com.mlmvpn.scanner.data.NodeManager, onUpdated: () -> Unit) {
    // Unwrap ContextWrapper chain to find Activity with proper theme
    var activityCtx: android.content.Context = context
    while (activityCtx is android.content.ContextWrapper && activityCtx !is android.app.Activity) {
        activityCtx = activityCtx.baseContext
    }
    val themedContext = if (activityCtx is android.app.Activity) activityCtx else context
    val dialogBuilder = com.google.android.material.dialog.MaterialAlertDialogBuilder(themedContext, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_Dialog_Alert)
    val dialogView = android.view.LayoutInflater.from(dialogBuilder.context).inflate(com.mlmvpn.scanner.R.layout.dialog_edit_node, null)
    val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri) ?: com.mlmvpn.scanner.utils.VpnConfig(name = node.name)
    
    val etName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(com.mlmvpn.scanner.R.id.et_name)
    val etAddress = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(com.mlmvpn.scanner.R.id.et_address)
    val etPort = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(com.mlmvpn.scanner.R.id.et_port)
    val etUuid = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(com.mlmvpn.scanner.R.id.et_uuid)
    val etNetwork = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(com.mlmvpn.scanner.R.id.et_network)
    val etWsHost = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(com.mlmvpn.scanner.R.id.et_wshost)
    val etWsPath = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(com.mlmvpn.scanner.R.id.et_wspath)
    val etTls = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(com.mlmvpn.scanner.R.id.et_tls)
    val etSni = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(com.mlmvpn.scanner.R.id.et_sni)
    val etAlpn = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(com.mlmvpn.scanner.R.id.et_alpn)
    
    etName.setText(config.name)
    etAddress.setText(config.address)
    etPort.setText(config.port.toString())
    etUuid.setText(config.uuid)
    etNetwork.setText(config.network)
    etWsHost.setText(config.wsHost)
    etWsPath.setText(config.wsPath)
    etTls.setText(config.tls)
    etSni.setText(config.sni)
    etAlpn.setText(config.alpn)

    val dialog = dialogBuilder.setView(dialogView).create()

    dialogView.findViewById<android.widget.ImageView>(com.mlmvpn.scanner.R.id.btn_close).setOnClickListener { dialog.dismiss() }
    
    dialogView.findViewById<android.widget.Button>(com.mlmvpn.scanner.R.id.btn_save).setOnClickListener {
        config.name = etName.text.toString().trim()
        config.address = etAddress.text.toString().trim()
        config.port = etPort.text.toString().toIntOrNull() ?: 443
        config.uuid = etUuid.text.toString().trim()
        config.network = etNetwork.text.toString().trim()
        config.wsHost = etWsHost.text.toString().trim()
        config.wsPath = etWsPath.text.toString().trim()
        config.tls = etTls.text.toString().trim()
        config.sni = etSni.text.toString().trim()
        config.alpn = etAlpn.text.toString().trim()
        
        val newUri = config.toUriString()
        
        val index = nodeManager.nodes.indexOf(node)
        if (index != -1) {
            val updatedNode = node.copy(name = config.name, uri = newUri, countryCode = null)
            updatedNode.ping = node.ping
            updatedNode.delay = node.delay
            updatedNode.speed = node.speed
            nodeManager.nodes[index] = updatedNode
            nodeManager.saveNodes()
            onUpdated()
        }
        dialog.dismiss()
    }
    
    dialog.show()
}


@Composable
fun NodeCard(
    node: com.mlmvpn.scanner.models.VpnNode,
    isActive: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onExpandClick: () -> Unit,
    onDelete: () -> Unit,
    highlight: Boolean = false,
    platformDelay: Long? = null,
    onPingClick: () -> Unit = {},
    onDelayClick: () -> Unit = {},
    onSpeedClick: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    val bgColor = if (isFocused) Primary.copy(alpha = 0.3f) else if (highlight) Primary.copy(alpha = 0.2f) else if (isActive) Primary.copy(alpha = 0.05f) else SurfaceDark
    val borderColor = if (isFocused) Color.White else if (highlight) Primary else if (isActive) Primary.copy(alpha = 0.5f) else BorderDark
    val highlightAlpha by androidx.compose.animation.core.animateFloatAsState(targetValue = if (highlight) 1f else 0f, animationSpec = androidx.compose.animation.core.tween(500))

    val isDefaultConfig = node.id.startsWith("default_mlmvpn")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(if (isFocused || highlight) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (isActive) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp).align(Alignment.TopStart))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = if (isActive) 24.dp else 0.dp)) {
                if (node.engineType == "MLM") {
                    Box(
                        modifier = Modifier.background(if (isActive) Primary else BorderDark, androidx.compose.foundation.shape.CircleShape).padding(2.dp)
                    ) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val bitmap = remember {
                            val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                            if (drawable != null) {
                                val bmp = android.graphics.Bitmap.createBitmap(
                                    drawable.intrinsicWidth.takeIf { it > 0 } ?: 100, 
                                    drawable.intrinsicHeight.takeIf { it > 0 } ?: 100, 
                                    android.graphics.Bitmap.Config.ARGB_8888
                                )
                                val canvas = android.graphics.Canvas(bmp)
                                drawable.setBounds(0, 0, canvas.width, canvas.height)
                                drawable.draw(canvas)
                                bmp.asImageBitmap()
                            } else null
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            )
                        } else {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = if (isActive) BgDark else TextPrimary, modifier = Modifier.size(20.dp).padding(4.dp))
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.background(if (isActive) Primary else BorderDark, RoundedCornerShape(8.dp)).padding(8.dp)
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = if (isActive) BgDark else TextPrimary, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                            Text(node.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (node.countryCode != null) {
                                Text(getNodeFlagEmoji(node.countryCode ?: "XX"), fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        if (highlight) {
                            Box(
                                modifier = Modifier
                                    .background(Primary.copy(alpha = 0.15f), RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    stringResource(com.mlmvpn.scanner.R.string.nodes_fastest), 
                                    color = Primary, 
                                    fontSize = 10.sp, 
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isDefaultConfig) {
                            Box(modifier = Modifier.background(Primary.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).border(1.dp, Primary.copy(alpha=0.3f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text("\uD83C\uDDEE\uD83C\uDDF7 MLMVPN", color = Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                            val isFreeConfig = node.engineType == "Manual" &&
                                node.groupTitle == com.mlmvpn.scanner.engines.freeconfig.FreeConfigEngine.GROUP_NAME
                            // The domain-fronting profile is a plain "Manual" node like an
                            // imported config, so without this it fell through to the `else`
                            // branch and was labelled BPB -- a panel it has nothing to do with.
                            val isMitmConfig = node.id == com.mlmvpn.scanner.mitm.MitmProfile.NODE_ID
                            val badgeText = if (config?.address == "127.0.0.1" && node.engineType != "NHN") "SNI" else when {
                                isMitmConfig -> "فرانتینگ"
                                isFreeConfig -> "رایگان"
                                node.engineType == "EDG" -> "EDG"
                                node.engineType == "NHN" -> "NHN"
                                node.engineType == "MLM" -> "MLM"
                                else -> "BPB"
                            }
                            val badgeColor = if (config?.address == "127.0.0.1" && node.engineType != "NHN") Primary else when {
                                isMitmConfig -> Color(0xFF26A69A)
                                isFreeConfig -> Color(0xFF7C3AED)
                                node.engineType == "NHN" -> GreenOk
                                node.engineType == "MLM" -> Color(0xFFAB47BC)
                                else -> Primary
                            }
                            Box(modifier = Modifier.background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).border(1.dp, badgeColor.copy(alpha=0.3f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(badgeText, color = badgeColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            
                            Spacer(modifier = Modifier.width(6.dp))
                            
                            Box(modifier = Modifier.background(SurfaceDark, RoundedCornerShape(6.dp)).border(1.dp, BorderDark, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(node.type.uppercase(), color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        if (platformDelay != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(modifier = Modifier.background(Color(0xFF03A9F4).copy(alpha = 0.15f), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF03A9F4).copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text("${platformDelay}ms", color = Color(0xFF03A9F4), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!isDefaultConfig) {
                        IconButton(onClick = onExpandClick, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.nodes_more), tint = TextMuted)
                        }
                    }
                    if (!node.groupTitle.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val groupColors = listOf(
                            Color(0xFFE91E63),
                            Color(0xFF9C27B0),
                            Color(0xFF673AB7),
                            Color(0xFF3F51B5),
                            Color(0xFF2196F3),
                            Color(0xFF00BCD4),
                            Color(0xFF009688),
                            Color(0xFF4CAF50),
                            Color(0xFFFF9800),
                            Color(0xFFFF5722)
                        )
                        val titleHash = kotlin.math.abs(node.groupTitle!!.hashCode())
                        val badgeColor = groupColors[titleHash % groupColors.size]
                        
                        Box(
                            modifier = Modifier
                                .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .border(0.5.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = node.groupTitle!!,
                                color = badgeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (node.ping == "Test" && node.delay == "Test" && node.speed == "Test") {
                ChipData(stringResource(com.mlmvpn.scanner.R.string.nodes_untested), TextDim)
            } else {
                if (node.ping != "Test") {
                    if (node.ping == "...") {
                        ChipData("Ping...", Primary, isLoading = true)
                    } else {
                        val pingInt = node.ping.replace("ms", "").toIntOrNull()
                        if (pingInt != null) {
                            val color = if (pingInt < 150) GreenOk else if (pingInt < 500) Color(0xFFFFA500) else RedError
                            ChipData("Ping: " + node.ping, color)
                        } else {
                            ChipData("Ping: " + node.ping, TextDim)
                        }
                    }
                }
                
                if (node.delay != "Test") {
                    if (node.delay == "...") {
                        ChipData("Delay...", Primary, isLoading = true)
                    } else {
                        val delayInt = node.delay.replace("ms", "").toIntOrNull()
                        if (delayInt != null) {
                            val color = if (delayInt < 300) GreenOk else if (delayInt < 800) Color(0xFFFFA500) else RedError
                            ChipData("Delay: " + node.delay, color)
                        } else {
                            ChipData("Delay: " + node.delay, TextDim)
                        }
                    }
                }

                if (node.speed != "Test") {
                    if (node.speed == "...") {
                        ChipData("Speed...", Primary, isLoading = true)
                    } else {
                        val speedDouble = node.speed.replace(" MB/s", "").toDoubleOrNull()
                        if (speedDouble != null) {
                            val color = if (speedDouble > 2.0) GreenOk else if (speedDouble > 0.5) Color(0xFFFFA500) else RedError
                            ChipData("Speed: " + node.speed, color)
                        } else {
                            ChipData("Speed: " + node.speed, TextDim)
                        }
                    }
                }
            }
        }

        if (isExpanded) {
            Divider(color = BorderDark)
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(vertical = 4.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onPingClick, modifier = Modifier.weight(1f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.SettingsEthernet, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(com.mlmvpn.scanner.R.string.action_ping), color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Divider(modifier = Modifier.height(30.dp).width(1.dp), color = BorderDark)
                    TextButton(onClick = onDelayClick, modifier = Modifier.weight(1f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Timer, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(com.mlmvpn.scanner.R.string.action_delay), color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Divider(modifier = Modifier.height(30.dp).width(1.dp), color = BorderDark)
                    TextButton(onClick = onSpeedClick, modifier = Modifier.weight(1f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.TrendingUp, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(com.mlmvpn.scanner.R.string.action_speed), color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                
                if (!isDefaultConfig) {
                    Divider(color = BorderDark)
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val nodeManager = remember { com.mlmvpn.scanner.data.NodeManager(context) }
                        
                        TextButton(onClick = { showShareDialog(context, node.uri) }, modifier = Modifier.weight(1f)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(stringResource(com.mlmvpn.scanner.R.string.node_share), color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Divider(modifier = Modifier.height(30.dp).width(1.dp), color = BorderDark)
                        TextButton(onClick = { showEditNodeDialog(context, node, nodeManager, onUpdated = { }) }, modifier = Modifier.weight(1f)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(stringResource(com.mlmvpn.scanner.R.string.node_edit), color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        // Built-in Iran configs are non-deletable, so don't offer a delete button for them.
                        if (!com.mlmvpn.scanner.data.NodeManager.isProtected(node)) {
                            Divider(modifier = Modifier.height(30.dp).width(1.dp), color = BorderDark)
                            TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = RedError, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(stringResource(com.mlmvpn.scanner.R.string.node_delete), color = RedError, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun ChipData(text: String, iconTint: Color, isLoading: Boolean = false) {
    Row(
        modifier = Modifier.background(BgDark, RoundedCornerShape(4.dp)).border(1.dp, BorderDark, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(8.dp),
                color = iconTint,
                strokeWidth = 1.dp
            )
        } else {
            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(iconTint))
        }
        Spacer(modifier = Modifier.width(3.dp))
        Text(text, color = TextPrimary, fontSize = 9.sp, maxLines = 1)
    }
}

fun getNodeFlagEmoji(countryCode: String?): String {
    if (countryCode.isNullOrEmpty() || countryCode == "XX" || countryCode.length != 2) return "??"
    return try {
        val firstLetter = Character.codePointAt(countryCode.uppercase(java.util.Locale.US), 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(countryCode.uppercase(java.util.Locale.US), 1) - 0x41 + 0x1F1E6
        String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
    } catch (e: Exception) {
        "??"
    }
}



@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun GroupManagementDialogs(
    showCreateGroupDialog: Boolean,
    onCreateGroupDismiss: () -> Unit,
    onCreateGroupConfirm: (String) -> Unit,
    showGroupMenu: String?,
    onGroupMenuDismiss: () -> Unit,
    onRenameClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onUpdateSubClick: ((String) -> Unit)? = null,
    groupToRename: String?,
    onRenameDismiss: () -> Unit,
    onRenameConfirm: (String, String) -> Unit,
    isSubscription: Boolean
) {
    if (showCreateGroupDialog) {
        var groupName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onCreateGroupDismiss,
            title = { androidx.compose.material3.Text("ایجاد گروه جدید") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { androidx.compose.material3.Text("نام گروه") },
                    singleLine = true
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { onCreateGroupConfirm(groupName) }) {
                    androidx.compose.material3.Text("ایجاد")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onCreateGroupDismiss) {
                    androidx.compose.material3.Text("انصراف")
                }
            }
        )
    }

    if (showGroupMenu != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onGroupMenuDismiss,
            title = { androidx.compose.material3.Text("مدیریت $showGroupMenu") },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (isSubscription && onUpdateSubClick != null) {
                        androidx.compose.material3.TextButton(onClick = { onUpdateSubClick(showGroupMenu) }) {
                            androidx.compose.material3.Text("بروزرسانی ساب")
                        }
                    }
                    androidx.compose.material3.TextButton(onClick = { onRenameClick(showGroupMenu) }) {
                        androidx.compose.material3.Text("تغییر نام")
                    }
                    androidx.compose.material3.TextButton(onClick = { onDeleteClick(showGroupMenu) }) {
                        androidx.compose.material3.Text("حذف گروه و محتویات", color = androidx.compose.ui.graphics.Color.Red)
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onGroupMenuDismiss) {
                    androidx.compose.material3.Text("بستن")
                }
            }
        )
    }

    if (groupToRename != null) {
        var newName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(groupToRename) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onRenameDismiss,
            title = { androidx.compose.material3.Text("تغییر نام گروه") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { androidx.compose.material3.Text("نام جدید") },
                    singleLine = true
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { onRenameConfirm(groupToRename, newName) }) {
                    androidx.compose.material3.Text("ذخیره")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onRenameDismiss) {
                    androidx.compose.material3.Text("انصراف")
                }
            }
        )
    }
}



