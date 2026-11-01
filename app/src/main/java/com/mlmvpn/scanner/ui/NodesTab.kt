package com.mlmvpn.scanner.ui

import com.mlmvpn.scanner.ui.home.fadingEdges
import com.mlmvpn.scanner.ui.home.frostedGlass
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
import androidx.compose.foundation.layout.PaddingValues
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosPrompt
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.utils.S

/** At most this many characters go to the clipboard in one copy (it rides one binder call). */
private const val MAX_CLIP_CHARS = 200_000

@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun NodesTab(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val nodeManager = remember { com.mlmvpn.scanner.data.NodeManager(context) }
    val subscriptionManager = remember { com.mlmvpn.scanner.data.SubscriptionManager(context) }
    val subscriptions by subscriptionManager.subscriptionsFlow.collectAsState()
    val nodesFlowState by nodeManager.nodesFlow.collectAsState()
    var nodes by remember { mutableStateOf(nodesFlowState) }

    /**
     * The stored list decides WHICH configs exist; this screen decides what they measured.
     *
     * `remember(nodesFlowState) { mutableStateOf(nodesFlowState) }` re-seeded the whole screen
     * from disk every time anything saved -- and a delay test saves as it goes (a country lookup
     * lands, a result is merged), each of those resetting every row still showing "..." back to
     * whatever the disk last held. That is why a long test looked like it did nothing until the
     * end: the numbers WERE arriving, and were being overwritten by the save that followed.
     *
     * Membership comes from the store, so an import or a deletion elsewhere still lands here.
     * The three measurement fields are kept from what this screen already has, because a test in
     * flight is the freshest thing anyone knows about them and the store is by definition behind.
     */
    LaunchedEffect(nodesFlowState) {
        val onScreen = nodes.associateBy { it.id }
        nodes = nodesFlowState.map { stored ->
            val mine = onScreen[stored.id] ?: return@map stored
            stored.copy(ping = mine.ping, delay = mine.delay, speed = mine.speed)
        }
    }

    val isRunning by MyVpnService.isRunningFlow.collectAsState()
    val connectedNodeId by MyVpnService.connectedNodeIdFlow.collectAsState()

    var activeNodeId by remember { mutableStateOf<String?>(null) }
    var expandedNodeId by remember { mutableStateOf<String?>(null) }
    val sharedPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    var sortState by remember { mutableStateOf(sharedPrefs.getInt("sort_state", 0)) } // 0: None, 1: Best-to-Worst, 2: Worst-to-Best
    var isConnecting by remember { mutableStateOf(false) }

    // isRunning means "the service is up", which it is from the first millisecond. Whether the
    // TUNNEL is up is a different question and only the phase answers it.
    val connectionPhase by MyVpnService.connectionPhaseFlow.collectAsState()
    val tunnelUp = connectionPhase == com.mlmvpn.scanner.MyVpnService.Phase.CONNECTED
    val tunnelStarting = isConnecting ||
        connectionPhase == com.mlmvpn.scanner.MyVpnService.Phase.CONNECTING
    val tunnelFailed = connectionPhase == com.mlmvpn.scanner.MyVpnService.Phase.FAILED

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
                            putExtra("PROXY_MODE", com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(context))
                            putExtra("LOCAL_PORT", com.mlmvpn.scanner.utils.LocalPort.getString(context))
                        }
                        com.mlmvpn.scanner.utils.SafeStart.service(context, startIntent)
                    }
                }
            }
        )
    }

    // Remembered on disk, not just in the composition.
    //
    // It was a plain `mutableStateOf(false)`, so turning it on and closing the app put it back --
    // and this switch changes the whole shape of the screen, which makes losing it look like the
    // setting had never been saved at all rather than like a default being restored. The two
    // selections it drives are kept with it for the same reason: coming back to a grouped list
    // that has forgotten which folder you were in is only half a restore.
    var isGroupedByPanel by remember {
        mutableStateOf(sharedPrefs.getBoolean("nodes_group_by_panel", false))
    }
    var selectedTabEngine by remember {
        mutableStateOf(sharedPrefs.getString("nodes_tab_engine", null))
    }
    var selectedManualGroup by remember {
        mutableStateOf(sharedPrefs.getString("nodes_manual_group", null))
    }

    // Written whenever they change rather than at every call site that assigns them: there are
    // several, and one of them forgetting to persist is exactly the bug being fixed here.
    LaunchedEffect(isGroupedByPanel, selectedTabEngine, selectedManualGroup) {
        sharedPrefs.edit()
            .putBoolean("nodes_group_by_panel", isGroupedByPanel)
            .putString("nodes_tab_engine", selectedTabEngine)
            .putString("nodes_manual_group", selectedManualGroup)
            .apply()
    }
    
    var activeRealCountry by remember { mutableStateOf<String?>(null) }

    // Platform Testing State
    var isPlatformMode by remember { mutableStateOf(false) }
    var selectedPlatform by remember { mutableStateOf<com.mlmvpn.scanner.utils.Platform?>(null) }
    val platformTestResults = remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }

    /**
     * Readings a test has taken but not yet written back, by node id.
     *
     * The reason this exists is the whole reason results used to appear only when a run ended.
     * Every reading was published by REPLACING the list -- `nodes = nodes.map { ... }` -- and this
     * screen derives a great deal from that list: it filters the SNI configs out (parsing every
     * config's URI to decide), sorts the rest through a comparator, then filters again by panel
     * and folder. All of it, on the main thread, for one number. At three hundred configs that is
     * a hundred thousand URI parses and three hundred sorts over a run, so the frames the results
     * were supposed to appear in never got drawn, and everything landed at once at the end when
     * the storm stopped.
     *
     * A snapshot map moves the subscription to where the value is actually read: the row. Writing
     * one entry invalidates that one row and nothing else -- no filter, no sort, no re-derivation.
     * It is what the SNI config list already does, which is why that one was never slow.
     *
     * The list itself is written once, when the run finishes. See [flushLive].
     */
    val livePing = remember { LiveMarks() }
    val liveDelay = remember { LiveMarks() }
    val liveSpeed = remember { LiveMarks() }
    var topNodeHighlightedId by remember { mutableStateOf<String?>(null) }
    var isPlatformTesting by remember { mutableStateOf(false) }

    var isAutoSwitchEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("auto_switch_enabled", false)) }
    var showAutoSwitchModal by remember { mutableStateOf(false) }
    var autoSwitchPlatform by remember { mutableStateOf(sharedPrefs.getString("auto_switch_platform", "None") ?: "None") }
    var autoSwitchInterval by remember { mutableStateOf(sharedPrefs.getInt("auto_switch_interval", 15)) }
    var autoSwitchTestCount by remember { mutableStateOf(sharedPrefs.getInt("auto_switch_test_count", 20)) }
    

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

    /**
     * The nodes this screen is allowed to show.
     *
     * SNI-spoof configs live in the same store as everything else, but they are not V2Ray configs
     * from the user's side: they are one half of the SNI engine, they only work while its local
     * TLS front is up, and the SNI screen is where they are created, listed and connected. They
     * were appearing here as well -- and inside whichever engine folder the config they were
     * derived FROM belonged to, usually BPB, because `spoofToSni` copies the source node and only
     * overrides its address and group. So a user who built ten SNI configs found ten strangers in
     * their BPB folder that fail to connect unless the SNI engine happens to be running.
     *
     * The Iran defaults and the domain-fronting profile are gone from here for the same reason,
     * now that each has an icon of its own. They are not configs this screen is about: nothing
     * deployed them, no account owns them, and they ship with the app. Leaving them here as well
     * would mean two places showing one thing, which is two places to keep in step and one more
     * list for a user to search when the one they want is somewhere else entirely.
     *
     * Filtered HERE and not in `nodes` itself. That used to be load-bearing rather than tidy:
     * several actions on this screen wrote the list back wholesale, so a filtered `nodes` would
     * have deleted every SNI config from disk the first time anyone measured a ping. Nothing on
     * this screen replaces the whole list any more -- results are merged by id -- but the split
     * stays, because what this screen SHOWS and what it STORES are still two different questions:
     * these configs are still stored, still connectable, and still exactly where their own
     * screens look for them.
     */
    val visibleNodes = remember(nodes) {
        nodes.filterNot {
            com.mlmvpn.scanner.engines.rstaspoof.SniSession.isSniNode(it) ||
                it.groupTitle == com.mlmvpn.scanner.data.NodeManager.IRAN_GROUP ||
                it.groupTitle == com.mlmvpn.scanner.mitm.MitmProfile.GROUP
        }
    }

    val distinctEngines = remember(visibleNodes) {
        visibleNodes.map { it.engineType }.distinct().sorted()
    }
    
    var emptyCustomGroups = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    val distinctManualGroups = remember(visibleNodes, emptyCustomGroups.toList()) {
        val groups = visibleNodes.filter { it.engineType == "Manual" }.mapNotNull { it.groupTitle }.toMutableList()
        groups.addAll(emptyCustomGroups)
        // The domain-fronting folder used to be listed here even when empty, because its whole
        // flow happened inside it. It has its own screen now, so conjuring an empty folder into
        // this list would only offer a way into something that is no longer there.
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
    LaunchedEffect(connectedNodeId) {
        if (connectedNodeId != null) activeNodeId = connectedNodeId
    }

    // Another screen asked this tab to open on something -- today, the free-config importer
    // handing over the configs it just brought in.
    //
    // Keyed on the node list too, not only on the request: the importer posts the request and
    // switches tabs in the same frame, so the node it names may not have reached `nodesFlow` yet.
    // Re-running when the list arrives is what makes the handoff land on the config rather than
    // on nothing.
    val focusRequest by NodesFocus.pending.collectAsState()
    LaunchedEffect(focusRequest, nodesFlowState) {
        val request = focusRequest ?: return@LaunchedEffect
        val target = request.nodeId?.let { id -> nodesFlowState.firstOrNull { it.id == id } }
        if (request.nodeId != null && target == null) return@LaunchedEffect
        if (request.groupTitle != null) {
            selectedTabEngine = "Manual"
            selectedManualGroup = request.groupTitle
        }
        // Only when nothing is live. Pointing the connect button somewhere else while a tunnel is
        // up would leave the bar naming one config and the session running another.
        if (target != null && connectedNodeId == null) activeNodeId = target.id
        NodesFocus.consume()
    }

    // The optimistic flag covers only the milliseconds between the tap and the service publishing
    // Phase.CONNECTING. Once the service has an opinion, the service wins.
    LaunchedEffect(connectionPhase) {
        if (connectionPhase != com.mlmvpn.scanner.MyVpnService.Phase.CONNECTING) isConnecting = false
    }

    // Auto-rename SNI configs
    LaunchedEffect(nodesFlowState) {
        if (nodesFlowState.isNotEmpty()) {
            val source = nodesFlowState
            // Off the main thread, and only links that can point at loopback get parsed: this
            // ran a full parse of every config on the main thread each time the list changed --
            // seconds on a long list (freeze reports, 1.2.41).
            val newNodes = withContext(kotlinx.coroutines.Dispatchers.Default) { source.map { node ->
                if (node.engineType == "NHN") return@map node
                if (!node.uri.startsWith("vmess://") && !node.uri.contains("127.0.0.1")) return@map node
                
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

                if (changedLocal) node.copy(name = currentName, uri = newUri) else node
            } }
            val changed = newNodes.indices.any { newNodes[it] !== source[it] }
            if (changed) {
                // Only the ones it renamed. This used to replace the entire stored list with a
                // snapshot taken when the effect started, so anything added since -- by the SNI
                // screen, the scanner, a subscription refresh -- was deleted by a rename it had
                // nothing to do with.
                val renamed = newNodes.filterIndexed { i, n -> n !== source[i] }
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    nodeManager.mergeById(renamed)
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
                        val localPort = com.mlmvpn.scanner.utils.LocalPort.get(context)
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

    val sortedNodes = remember(visibleNodes, sortState, isPlatformMode, selectedPlatform, platformTestResults.toMap()) {
        if (isPlatformMode && selectedPlatform != null && platformTestResults.keys.any { it.endsWith(selectedPlatform!!.name) }) {
            val comparator = Comparator<VpnNode> { a, b ->
                val delayA = platformTestResults["${a.id}_${selectedPlatform!!.name}"] ?: 999999L
                val delayB = platformTestResults["${b.id}_${selectedPlatform!!.name}"] ?: 999999L
                delayA.compareTo(delayB)
            }
            visibleNodes.sortedWith(comparator)
        } else if (sortState == 0) {
            visibleNodes
        } else {
            // Each node's numbers are read once, here, not inside the comparator: there they were
            // read again for every comparison, and toDoubleOrNull runs a regex each time -- a sort
            // of a few thousand configs was hundreds of thousands of regex matches on the main
            // thread (the node list's freezes and ANRs, 1.2.41).
            val keys = HashMap<String, NodeSortKey>(visibleNodes.size * 2)
            visibleNodes.forEach { keys[it.id] = NodeSortKey.of(it) }
            val comparator = Comparator<VpnNode> { a, b ->
                val ka = keys[a.id] ?: NodeSortKey.of(a)
                val kb = keys[b.id] ?: NodeSortKey.of(b)
                val delayA = ka.delay
                val delayB = kb.delay
                val speedA = ka.speed
                val speedB = kb.speed
                val pingA = ka.ping
                val pingB = kb.ping

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
            visibleNodes.sortedWith(comparator)
        }
    }

    /**
     * The order stops moving while a measurement is in flight.
     *
     * Pressing "delay" marks every target row `...`, and `...` is not a reading -- so the sort
     * key of the whole list vanishes in one frame and the list reshuffles instantly. LazyColumn
     * anchors the viewport to the first visible item by key, so the rows the user was looking at
     * slide away underneath them: from the outside the list "jumps into the middle". Then each
     * result arriving reshuffles it again, which is also why the numbers looked like they only
     * showed up at the end -- rows were moving away faster than they could be read.
     *
     * So: when a run starts, the ids are frozen in the order they had, the list scrolls to the
     * top, and results fill in WHERE THEY ARE. When the run ends the freeze lifts and the list
     * sorts once, fastest first, with the user still at the top.
     */
    val busyTesting = isPingTesting || isDelayTesting || isSpeedTesting
    var frozenOrder by remember { mutableStateOf<Map<String, Int>?>(null) }
    LaunchedEffect(busyTesting) {
        if (busyTesting) {
            frozenOrder = sortedNodes.withIndex().associate { (i, n) -> n.id to i }
            listState.scrollToItem(0)
        } else {
            frozenOrder = null
        }
    }

    val orderedNodes = frozenOrder?.let { fixed ->
        sortedNodes.sortedBy { fixed[it.id] ?: Int.MAX_VALUE }
    } ?: sortedNodes

    val displayedNodes = remember(orderedNodes, isGroupedByPanel, selectedTabEngine, selectedManualGroup) {
        if (((isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine != null)) {
            val engineFiltered = orderedNodes.filter { it.engineType == selectedTabEngine }
            if (selectedTabEngine == "Manual") {
                engineFiltered.filter { it.groupTitle == selectedManualGroup }
            } else {
                engineFiltered
            }
        } else {
            orderedNodes
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedId = activeNodeId
            val node = nodes.find { it.id == selectedId }
            if (node != null) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val isProxyMode = com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(context)
                val localPort = com.mlmvpn.scanner.utils.LocalPort.getString(context)

                val intent = Intent(context, MyVpnService::class.java).apply {
                    putExtra("NODE_URI", node.uri)
                    putExtra("NODE_ID", node.id)
                    putExtra("PROXY_MODE", isProxyMode)
                    putExtra("LOCAL_PORT", localPort)
                }
                com.mlmvpn.scanner.utils.SafeStart.service(context, intent)
            } else {
                isConnecting = false
            }
        } else {
            isConnecting = false
            Toast.makeText(context, S(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }


    // ---- pushed pages -----------------------------------------------------------------------
    //
    // A node used to expand IN PLACE into two rows of six TextButtons, and its share and edit
    // sheets were inflated XML MaterialAlertDialogs. Everything that is a place you go and come
    // back from is a page now; see NodesScreens.kt.
    var page by remember { mutableStateOf<NodesPage>(NodesPage.List) }
    androidx.activity.compose.BackHandler(enabled = page != NodesPage.List) {
        page = when (page) {
            is NodesPage.Edit -> NodesPage.Detail((page as NodesPage.Edit).id)
            is NodesPage.AutoSwitch -> NodesPage.Settings
            else -> NodesPage.List
        }
    }

    /**
     * Fold the readings a run collected back into the list, once.
     *
     * Called when a run ends, and nowhere else. Until it runs the numbers live only in the live
     * map, which is exactly what keeps a result cheap: the list, the SNI filter, the comparator
     * and the panel filter are all untouched while the run is in flight.
     *
     * Values that are still `...` are dropped rather than written. A run that was stopped leaves
     * rows it never reached, and "in progress" is not a measurement to remember.
     */
    fun flushLive() {
        if (livePing.isEmpty() && liveDelay.isEmpty() && liveSpeed.isEmpty()) return
        nodes = nodes.map { node ->
            val p = livePing[node.id]?.takeIf { it != "..." } ?: node.ping
            val d = liveDelay[node.id]?.takeIf { it != "..." } ?: node.delay
            val v = liveSpeed[node.id]?.takeIf { it != "..." } ?: node.speed
            if (p == node.ping && d == node.delay && v == node.speed) node
            else node.copy(ping = p, delay = d, speed = v)
        }
        livePing.clear()
        liveDelay.clear()
        liveSpeed.clear()
    }

    /** Rewrites one node in place and persists, the way every per-node action here does. */
    fun replaceNode(id: String, transform: (com.mlmvpn.scanner.models.VpnNode) -> com.mlmvpn.scanner.models.VpnNode) {
        val list = nodes.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return
        list[index] = transform(list[index])
        nodes = list
        nodeManager.mergeById(listOf(list[index]))
    }

    fun deleteNode(id: String) {
        val remaining = nodes.filterNot {
            it.id == id && !com.mlmvpn.scanner.data.NodeManager.isProtected(it)
        }
        nodes = remaining
        nodeManager.removeByIds(setOf(id))
    }

    /** Remove [victims] from the saved list and say how many went. Protected configs never go. */
    fun removeNodes(victims: Set<String>) {
        val remaining = nodes.filterNot {
            it.id in victims && !com.mlmvpn.scanner.data.NodeManager.isProtected(it)
        }
        val removed = nodes.size - remaining.size
        nodeManager.removeByIds(victims)
        nodes = remaining
        android.widget.Toast.makeText(
            context,
            if (removed > 0) context.getString(R.string.nodes_deleted_count, removed)
            else context.getString(R.string.nodes_delete_dead_none),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    // The three per-node measurements, lifted out of the list item so the detail page can
    // run exactly the same code. The bodies are unchanged.
    val pingOneNodeRef: (com.mlmvpn.scanner.models.VpnNode) -> Unit = { node ->
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
                        nodeManager.mergeById(listOf(currentList[index]))
                    }
                }
            }
        }
    }

    val delayOneNodeNow: (com.mlmvpn.scanner.models.VpnNode) -> Unit = { node ->
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
                // Held for the length of the probe, and given back in the `finally` below.
                //
                // An SNI config points at a local port that only exists while the front process
                // is up, so a measurement has to raise it. What it must not do is leave it up:
                // started-and-forgotten is why one delay test on a list containing one SNI config
                // left that process running for the rest of the app's life, and why the home
                // screen then showed the SNI lamp over whatever the user connected next.
                val front = config.address == "127.0.0.1"
                if (front) {
                    com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.acquire(context)
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

                    com.mlmvpn.scanner.data.CombineEngine.ensureCoreEnv(context)

                    // Measured the way the connect will run it (CfEdgeHeal: IPv6 twin when Cloudflare
                    // IPv4 is measured dead here).
                    val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(
                        com.mlmvpn.scanner.data.CfEdgeHeal.heal(context, config, probe = false))
                    // Through CombineEngine, which puts a timeout on it that can actually fire:
                    // the bare JNI call has been measured sitting for over three minutes, and there
                    // was no timeout on this one at all -- one stalled config froze the row for good.
                    // A config that goes through the local SNI front needs far longer than one
                    // that dials its server directly: the forged, fragmented handshake alone was
                    // measured at 9 to 14 seconds on an Iranian mobile line. At the ordinary budget
                    // every such config reports "no ping" while being perfectly able to connect.
                    val budget = if (config.address == "127.0.0.1") 25_000L else 12_000L
                    val delayMs = com.mlmvpn.scanner.data.CombineEngine
                        .measureDelay(jsonConfig, timeoutMs = budget)
                    if (delayMs > 0) {
                        delayStr = "${delayMs}ms"
                    }
                } catch (e: Exception) {
                    // Keep Timeout
                } finally {
                    if (front) com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.release()
                }

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val currentList = nodes.toMutableList()
                    val index = currentList.indexOfFirst { it.id == node.id }
                    if (index != -1) {
                        currentList[index] = currentList[index].copy(delay = delayStr)
                        nodes = currentList
                        nodeManager.mergeById(listOf(currentList[index]))
                    }
                }
            }
        }
    }

    /**
     * The button's version, which asks first.
     *
     * A scan holds up to 256 sockets open and drives the same Xray core this test uses, so a
     * measurement taken underneath one describes the scan rather than the server -- and reads as
     * "Timeout" on configs that are perfectly healthy. ScanGuard raises the question once, at the
     * app root, and either runs this straight away or after the user chooses to stop the scan.
     */
    val delayOneNodeRef: (com.mlmvpn.scanner.models.VpnNode) -> Unit = { node ->
        com.mlmvpn.scanner.data.ScanGuard.run(
            com.mlmvpn.scanner.data.ScanGuard.Reason.DELAY_TEST
        ) { delayOneNodeNow(node) }
    }

    val speedOneNodeNow: (com.mlmvpn.scanner.models.VpnNode) -> Unit = { node ->
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
                // Same as the delay test above: the front is borrowed, not switched on.
                val front = config.address == "127.0.0.1"
                if (front) {
                    com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.acquire(context)
                    kotlinx.coroutines.delay(500)
                }
                val speedStr = try {
                    realSpeedTest(config, context)
                } finally {
                    if (front) com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.release()
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val currentList = nodes.toMutableList()
                    val index = currentList.indexOfFirst { it.id == node.id }
                    if (index != -1) {
                        currentList[index] = currentList[index].copy(speed = speedStr)
                        nodes = currentList
                        nodeManager.mergeById(listOf(currentList[index]))
                    }
                }
            }
        }
    }

    /** Same reasoning as [delayOneNodeRef]; a speed test is a scan's competitor twice over. */
    val speedOneNodeRef: (com.mlmvpn.scanner.models.VpnNode) -> Unit = { node ->
        com.mlmvpn.scanner.data.ScanGuard.run(
            com.mlmvpn.scanner.data.ScanGuard.Reason.DELAY_TEST
        ) { speedOneNodeNow(node) }
    }

    when (val current = page) {
        is NodesPage.Detail -> {
            val node = nodes.find { it.id == current.id }
            if (node == null) page = NodesPage.List
            else {
                NodeDetailScreen(
                    node = node,
                    isActive = tunnelUp && connectedNodeId == node.id,
                    onPing = { pingOneNodeRef(node) },
                    onDelay = { delayOneNodeRef(node) },
                    onSpeed = { speedOneNodeRef(node) },
                    onEdit = { page = NodesPage.Edit(node.id) },
                    onDelete = { deleteNode(node.id) },
                    onBack = { page = NodesPage.List },
                )
                return
            }
        }
        is NodesPage.Edit -> {
            val node = nodes.find { it.id == current.id }
            if (node == null) page = NodesPage.List
            else {
                NodeEditScreen(
                    node = node,
                    onSave = { config ->
                        replaceNode(node.id) {
                            it.copy(name = config.name, uri = config.toUriString(), countryCode = null)
                                .also { updated ->
                                    updated.ping = it.ping
                                    updated.delay = it.delay
                                    updated.speed = it.speed
                                }
                        }
                    },
                    onBack = { page = NodesPage.Detail(node.id) },
                )
                return
            }
        }
        NodesPage.Settings -> {
            val deadIds = displayedNodes
                .filter { isNodeMeasuredDead(it) && !com.mlmvpn.scanner.data.NodeManager.isProtected(it) }
                .map { it.id }
                .toSet()
            // What "this folder" actually means right now, in the user's own words. The rows
            // below act on the VISIBLE list, which is the whole library when nothing is grouped
            // and one panel or one folder when something is -- and "delete every config in this
            // folder" is not a button to press while guessing which folder that is.
            val scopeLabel: String? = if (
                (isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine != null
            ) {
                if (selectedTabEngine == "Manual") selectedManualGroup else selectedTabEngine
            } else {
                null
            }
            val healthyUris = displayedNodes
                .filter { !isNodeMeasuredDead(it) }
                .map { it.uri }

            NodeListSettingsScreen(
                scopeLabel = scopeLabel,
                healthyCount = healthyUris.size,
                onCopyHealthy = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    // The clipboard travels through one binder call (~1 MB, and text is two bytes
                    // a character): thousands of configs crashed the app (crash #677). As many
                    // whole lines as fit, and the toast says how many that was.
                    var size = 0
                    val fit = healthyUris.takeWhile { size += it.length + 1; size <= MAX_CLIP_CHARS }
                    runCatching {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("configs", fit.joinToString("\n")))
                    }
                    android.widget.Toast.makeText(
                        context,
                        S(R.string.nodes_copied_healthy, faCount(fit.size)),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
                canGroupByPanel = distinctEngines.size > 1,
                groupByPanel = isGroupedByPanel,
                onGroupByPanel = { isGroupedByPanel = it },
                platformMode = isPlatformMode,
                onPlatformMode = {
                    isPlatformMode = it
                    if (it && selectedPlatform == null) {
                        selectedPlatform = com.mlmvpn.scanner.utils.Platform.values().first()
                    }
                },
                autoSwitchEnabled = isAutoSwitchEnabled,
                autoSwitchSummary = S(R.string.every_minutes, faCount(autoSwitchInterval)),
                onOpenAutoSwitch = { page = NodesPage.AutoSwitch },
                onDisableAutoSwitch = {
                    isAutoSwitchEnabled = false
                    sharedPrefs.edit().putBoolean("auto_switch_enabled", false).apply()
                },
                visibleCount = displayedNodes.size,
                deadCount = deadIds.size,
                onCleanUp = { removeNodes(deadIds) },
                onDeleteAll = { removeNodes(displayedNodes.map { it.id }.toSet()) },
                onBack = { page = NodesPage.List },
            )
            return
        }
        NodesPage.Add -> {
            AddNodeScreen(
                onBack = { page = NodesPage.List },
                onNodesAdded = { newNodes ->
                    nodeManager.nodes.addAll(0, newNodes)
                    nodeManager.saveNodes()
                    nodes = newNodes + nodes
                },
                onSubscriptionAdded = { name, url ->
                    val sub = subscriptionManager.addSubscription(name, url)
                    android.widget.Toast.makeText(context, S(R.string.fetching_configs), android.widget.Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val res = subscriptionManager.updateSubscription(sub, nodeManager)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            nodes = nodeManager.nodes.toList()
                            android.widget.Toast.makeText(context, res.second, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onUpdateSubscriptions = {
                    android.widget.Toast.makeText(context, S(R.string.updating_subscriptions), android.widget.Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val res = subscriptionManager.updateAllSubscriptions(nodeManager)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            nodes = nodeManager.nodes.toList()
                            android.widget.Toast.makeText(context, S(R.string.subscriptions_configs, res.first, res.second), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                selectedManualGroup = selectedManualGroup,
            )
            return
        }
        NodesPage.AutoSwitch -> {
            AutoSwitchScreen(
                platform = autoSwitchPlatform,
                onPlatform = { autoSwitchPlatform = it },
                intervalMinutes = autoSwitchInterval,
                onInterval = { autoSwitchInterval = it },
                testCount = autoSwitchTestCount,
                onTestCount = { autoSwitchTestCount = it },
                enabled = isAutoSwitchEnabled,
                onEnable = {
                    isAutoSwitchEnabled = true
                    sharedPrefs.edit()
                        .putBoolean("auto_switch_enabled", true)
                        .putString("auto_switch_platform", autoSwitchPlatform)
                        .putInt("auto_switch_interval", autoSwitchInterval)
                        .putInt("auto_switch_test_count", autoSwitchTestCount)
                        .apply()
                },
                onBack = { page = NodesPage.Settings },
            )
            return
        }
        NodesPage.List -> Unit
    }

    IosScreen(
        title = stringResource(R.string.home_v2ray),
        onBack = onBack,
        backLabel = S(R.string.home),
        scrollable = false,
    ) {
        fun updateNodes(indicatorProp: String, action: suspend (com.mlmvpn.scanner.models.VpnNode, com.mlmvpn.scanner.utils.VpnConfig) -> com.mlmvpn.scanner.models.VpnNode, successMsg: String) {
            val job = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                // The configs this screen is SHOWING, which is not the same set as "every config
                // that matches the tab".
                //
                // Each test rebuilt its own target list from `nodes` with its own copy of the
                // panel/folder rule -- and `nodes` still contains the SNI configs, which this
                // screen deliberately hides (they have their own screen, their own list and their
                // own measurement, and measuring one here would dial a local port with no engine
                // behind it). So the counter said two configs when one was on screen, and four
                // when three were: the extras were the hidden SNI copies of the same panel, being
                // measured where nobody could see the result.
                //
                // `displayedNodes` is that set, already filtered once, by the same expression the
                // list itself draws from -- so the count, the rows and the work cannot disagree.
                val targetNodes = displayedNodes

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

                com.mlmvpn.scanner.data.CombineEngine.ensureCoreEnv(context)

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
                    // Acquired, and released in the `finally` on the await below -- which also
                    // covers the cancelled run, the case that used to strand the process most
                    // often because stopping a long test is the normal way it ends.
                    com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.acquire(context)
                    kotlinx.coroutines.delay(500) // Give it a moment to bind the port
                }

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val marks = if (indicatorProp == "ping") livePing
                    else if (indicatorProp == "delay") liveDelay else liveSpeed
                    marks.reset(targetNodes.map { it.id }, "...")
                }

                val deferreds = targetNodes.map { node ->
                    async {
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
                                // By id, NOT by the index this node had when the test started.
                                //
                                // `index` comes from a snapshot taken before any measurement ran,
                                // and `nodes` moves underneath a test that takes minutes: a
                                // subscription refresh lands, an import adds configs, the user
                                // deletes a row. Writing `currentList[index]` then put this node's
                                // result on TOP OF A DIFFERENT ROW -- leaving two entries carrying
                                // one id, which LazyColumn answers by throwing on its next measure
                                // pass ("Key ... was already used"), and which saveNodes() below
                                // then wrote to disk so the crash came back on every launch.
                                // Reported by seven separate users before this was found.
                                if (indicatorProp == "ping") livePing[updatedNode.id] = updatedNode.ping
                                else if (indicatorProp == "delay") liveDelay[updatedNode.id] = updatedNode.delay
                                else liveSpeed[updatedNode.id] = updatedNode.speed
                                if (indicatorProp == "ping") pingProgress++ else speedProgress++
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                val failed = if (indicatorProp == "ping") node.copy(ping = "Error")
                                else if (indicatorProp == "delay") node.copy(delay = "Error")
                                else node.copy(speed = "Error")
                                if (indicatorProp == "ping") livePing[failed.id] = failed.ping
                                else if (indicatorProp == "delay") liveDelay[failed.id] = failed.delay
                                else liveSpeed[failed.id] = failed.speed
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
                            val marks = if (indicatorProp == "ping") livePing
                            else if (indicatorProp == "delay") liveDelay else liveSpeed
                            marks.replaceValue("...", "Cancelled")
                            android.widget.Toast.makeText(context, S(R.string.test_cancelled), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } finally {
                    if (needsRsta) com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.release()
                }

                // The one write of the run. Everything above went into the live map, so the
                // list is rebuilt and saved once here rather than three hundred times.
                //
                // Merged, not replaced. A bulk test runs for minutes and knows only about the
                // configs it measured; replacing the whole stored list with its snapshot deleted
                // everything anyone had added in the meantime.
                withContext(kotlinx.coroutines.Dispatchers.Main) { flushLive() }
                nodeManager.mergeById(nodes)

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
            android.widget.Toast.makeText(context, S(R.string.checking_ping), android.widget.Toast.LENGTH_SHORT).show()
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
                // The configs this screen is SHOWING, which is not the same set as "every config
                // that matches the tab".
                //
                // Each test rebuilt its own target list from `nodes` with its own copy of the
                // panel/folder rule -- and `nodes` still contains the SNI configs, which this
                // screen deliberately hides (they have their own screen, their own list and their
                // own measurement, and measuring one here would dial a local port with no engine
                // behind it). So the counter said two configs when one was on screen, and four
                // when three were: the extras were the hidden SNI copies of the same panel, being
                // measured where nobody could see the result.
                //
                // `displayedNodes` is that set, already filtered once, by the same expression the
                // list itself draws from -- so the count, the rows and the work cannot disagree.
                val activeNodes = displayedNodes
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, S(R.string.starting_real_delay_test), android.widget.Toast.LENGTH_SHORT).show()
                    delayTotal = activeNodes.size
                    delayProgress = 0
                    isDelayTesting = true
                    liveDelay.reset(activeNodes.map { it.id }, "...")
                }

                // Ids, not positions. `validIndices` held offsets into a snapshot of `nodes`
                // taken before any measurement began, and by the time a result came back that
                // snapshot could be a different list -- shorter, longer, or reordered by an import
                // or a deletion. Writing a result at a stale offset either landed on the wrong row
                // (two rows, one id, and the V2Ray list throws on its next measure) or ran off the
                // end outright: six users crashed here with "Index 25 out of bounds for length 20"
                // and the like. An id cannot go stale.
                val validIds = mutableListOf<String>()
                val validConfigs = mutableListOf<com.mlmvpn.scanner.utils.VpnConfig>()

                activeNodes.forEach { node ->
                    val isJsonConfig = node.uri.trimStart().startsWith("{") || node.type == "JSON"
                    val config = if (isJsonConfig) null else com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                    if (isJsonConfig) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            liveDelay[node.id] = "N/A"
                            delayProgress++
                        }
                    } else if (config != null && config.address.isNotEmpty()) {
                        validIds.add(node.id)
                        validConfigs.add(config)
                    } else {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            liveDelay[node.id] = "Error"
                            delayProgress++
                        }
                    }
                }

                if (validConfigs.isNotEmpty()) {
                    // Asked of the configs that are actually about to be measured, and claimed
                    // inside the try so the `finally` at the end of it always gives it back. It
                    // used to be raised above this block and never lowered, so a list with one
                    // SNI config in it left the front process running for good.
                    val needsRsta = validConfigs.any { it.address == "127.0.0.1" }
                    try {
                        if (needsRsta) {
                            com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.acquire(context)
                            kotlinx.coroutines.delay(500)
                        }

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

                        com.mlmvpn.scanner.data.CombineEngine.ensureCoreEnv(context)

                        val measureSemaphore = kotlinx.coroutines.sync.Semaphore(3) // Cloudflare/DPI might block if >3 concurrent handshakes -- was set to 8 here, directly contradicting this comment
                        // Country lookup is much heavier (spins up its own CoreController + SOCKS
                        // proxy, see CountryLookup.kt) than the lightweight measureOutboundDelay
                        // ping above, so it gets its own small concurrency cap and runs as a
                        // separate fire-and-forget coroutine per node -- NOT inside `deferreds`,
                        // so it never blocks the delay test's own completion/progress. It only
                        // ever runs once per node: the result is cached on countryCode, and every
                        // later delay test for that node skips this block entirely.
                        val countryLookupSemaphore = kotlinx.coroutines.sync.Semaphore(3)

                        // No verdict yet on this network and Cloudflare configs in the batch: settle
                        // it once with one config (CfEdgeHeal), so the whole list is measured the
                        // way a connect would run -- IPv6 twins when IPv4 carries nothing here.
                        if (com.mlmvpn.scanner.data.CfFamily.current(context) == null) {
                            (validConfigs.firstOrNull { com.mlmvpn.scanner.data.CfEdgeHeal.isCfFronted(it) && com.mlmvpn.scanner.data.CfEdgeHeal.v4ToLong(it.address) != null }
                                ?: validConfigs.firstOrNull { com.mlmvpn.scanner.data.CfEdgeHeal.isCfFronted(it) })
                                ?.let { runCatching { com.mlmvpn.scanner.data.CfEdgeHeal.heal(context, it, probe = true) } }
                        }
                        val deferreds = validConfigs.mapIndexed { i, config ->
                            async(kotlinx.coroutines.Dispatchers.IO) {
                                measureSemaphore.acquire()
                                var delayStr = "Timeout"
                                var succeeded = false
                                try {
                                    val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(
                                        com.mlmvpn.scanner.data.CfEdgeHeal.heal(context, config, probe = false))
                                    // withTimeoutOrNull around the JNI call did nothing: it can
                                    // only cancel at a suspension point and a blocking native call
                                    // is not one. CombineEngine.measureDelay runs it somewhere it
                                    // can be abandoned, which is the only way this bounds.
                                    // Same reason as the single-row test above: a config through
                                    // the SNI front is slow to hand-shake, not dead.
                                    val delayMs = com.mlmvpn.scanner.data.CombineEngine.measureDelay(
                                        jsonConfig,
                                        timeoutMs = if (config.address == "127.0.0.1") 25_000L else 10_000L,
                                    )
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

                                val nodeId = validIds[i]
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    liveDelay[nodeId] = delayStr
                                    delayProgress++
                                }

                                val measured = nodes.firstOrNull { it.id == nodeId }
                                if (succeeded && measured != null && measured.countryCode == null) {
                                    // Launched on the composable's own scope, not this async's --
                                    // structured concurrency would otherwise make deferreds.awaitAll()
                                    // below wait for every country lookup too, defeating the point.
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        countryLookupSemaphore.acquire()
                                        try {
                                            // Derived from the id so two nodes never share a
                                            // port, and stable however the list moves -- the old
                                            // version keyed it on a snapshot offset.
                                            val localPort = 21000 + (nodeId.hashCode().and(0x7fffffff) % 900)
                                            val nodeUriSnapshot =
                                                nodes.firstOrNull { it.id == nodeId }?.uri ?: return@launch
                                            val country = com.mlmvpn.scanner.utils.CountryLookup.resolveCountry(context, nodeUriSnapshot, localPort)
                                            if (country != null) {
                                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    if (nodes.any { it.id == nodeId }) {
                                                        nodes = nodes.map {
                                                            if (it.id == nodeId) it.copy(countryCode = country)
                                                            else it
                                                        }
                                                        nodes.firstOrNull { it.id == nodeId }
                                                            ?.let { nodeManager.mergeById(listOf(it)) }
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
                                    liveDelay.replaceValue("...", "Cancelled")
                                    android.widget.Toast.makeText(context, S(R.string.test_cancelled), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                liveDelay.replaceValue("...", "Cancelled")
                            }
                        }
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("BatchTest", "Xray Batch test failed", e)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, S(R.string.batch_test_error), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        if (needsRsta) com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.release()
                    }
                }

                // Merged, not replaced. A bulk test runs for minutes and knows only about the
                // configs it measured; replacing the whole stored list with its snapshot deleted
                // everything anyone had added in the meantime.
                withContext(kotlinx.coroutines.Dispatchers.Main) { flushLive() }
                nodeManager.mergeById(nodes)

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    isDelayTesting = false
                    delayJob = null
                    android.widget.Toast.makeText(context, S(R.string.real_delay_check_completed), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            delayJob = job
        }


        // ---- the one toolbar -------------------------------------------------------------
        //
        // Was five icon buttons in a glass slab with hairline dividers between them, plus a
        // separate collapsible "بیشتر" header holding three switches and a delete. The switches
        // moved to a settings page: a setting behind a chevron is a setting nobody finds, which
        // is the same reason this app's hamburger menu is gone.
        NodeToolbar(
            sortActive = sortState != 0,
            busy = isPingTesting || isDelayTesting || isSpeedTesting,
            onPing = { pingAllNodes() },
            // Both go through ScanGuard for the same reason a single test does -- more so, since
            // a bulk run occupies the core for minutes. A plain TCP ping is left alone: it uses
            // neither the core nor enough of the link to be worth interrupting a scan over.
            onDelay = {
                com.mlmvpn.scanner.data.ScanGuard.run(
                    com.mlmvpn.scanner.data.ScanGuard.Reason.DELAY_TEST
                ) { delayAllNodes() }
            },
            onAdd = { page = NodesPage.Add },
            onSort = {
                sortState = if (sortState == 1) 2 else 1
                sharedPrefs.edit().putInt("sort_state", sortState).apply()
                // Re-ordering a list under someone and leaving them where they were shows them
                // rows they did not ask for: the answer to "which is fastest" is at the top, so
                // that is where the press has to leave them.
                scope.launch { listState.animateScrollToItem(0) }
            },
            onSettings = { page = NodesPage.Settings },
        )

        // ---- the guided combine, last two steps ------------------------------------------
        //
        // The coach arrives here having already measured and transferred, so step 4 does NOT tell
        // the user to measure things that are already measured. It reads what actually landed and
        // says the next real move -- and if nothing landed it does not pretend otherwise.
        val coachStep by com.mlmvpn.scanner.ui.CombineCoach.step.collectAsState()
        val coachTransferred by com.mlmvpn.scanner.ui.CombineCoach.transferred.collectAsState()
        val measuredCount = remember(nodes, coachStep) { nodes.count { it.delay.contains("ms") } }

        androidx.compose.runtime.LaunchedEffect(isDelayTesting, measuredCount) {
            if (coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.MEASURE && measuredCount > 0) {
                com.mlmvpn.scanner.ui.CombineCoach.advance(com.mlmvpn.scanner.ui.CombineCoach.Step.CONNECT)
            }
        }
        androidx.compose.runtime.LaunchedEffect(tunnelUp) {
            if (tunnelUp && coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.CONNECT) {
                com.mlmvpn.scanner.ui.CombineCoach.advance(com.mlmvpn.scanner.ui.CombineCoach.Step.DONE)
            }
        }

        if (coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.MEASURE ||
            coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.CONNECT
        ) {
            Spacer(Modifier.height(10.dp))
            com.mlmvpn.scanner.ui.CombineCoachBar(
                step = coachStep,
                onSkip = { com.mlmvpn.scanner.ui.CombineCoach.skip() },
                body = when {
                    coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.MEASURE && coachTransferred == 0 ->
                        S(R.string.nothing_was_transferred_to_measure_go_back)
                    coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.MEASURE && measuredCount > 0 ->
                        S(R.string.working_combinations_were_transferred_and_their_delay, faCount(coachTransferred))
                    coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.MEASURE ->
                        S(R.string.working_combinations_were_transferred_tap_delay_to, faCount(coachTransferred))
                    else ->
                        S(R.string.tap_sort_to_bring_the_fastest_to)
                },
                failed = coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.MEASURE && coachTransferred == 0,
                actionLabel = if (coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.MEASURE && coachTransferred == 0) {
                    S(R.string.close_the_guide)
                } else null,
                onAction = if (coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.MEASURE && coachTransferred == 0) {
                    { com.mlmvpn.scanner.ui.CombineCoach.skip() }
                } else null,
            )
            Spacer(Modifier.height(6.dp))
        }

        NodeTestProgressRow(S(R.string.ping), isPingTesting, pingProgress, pingTotal) {
            pingJob?.cancel(); isPingTesting = false; pingProgress = 0
        }
        NodeTestProgressRow(S(R.string.real_delay), isDelayTesting, delayProgress, delayTotal) {
            delayJob?.cancel(); isDelayTesting = false; delayProgress = 0
        }

        // ---- scope: which configs the list and the batch tests are about --------------------
        val showEngineTabs = distinctEngines.size > 1 && isGroupedByPanel
        val showFolders = (isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine == "Manual"

        var showCreateGroupDialog by remember { mutableStateOf(false) }
        var showGroupMenu by remember { mutableStateOf<String?>(null) }
        var groupToRename by remember { mutableStateOf<String?>(null) }

        if (showEngineTabs) {
            Spacer(Modifier.height(10.dp))
            NodeScopeChips(
                items = distinctEngines,
                selected = selectedTabEngine,
                label = { it ?: "" },
                onSelect = { selectedTabEngine = it },
            )
        }

        if (showFolders) {
            val defaultGroupLabel = S(R.string.default_str)
            Spacer(Modifier.height(10.dp))
            NodeScopeChips(
                items = listOf<String?>(null) + distinctManualGroups,
                selected = selectedManualGroup,
                // A subscription's servers are filed under its id; the chip says its name.
                label = { g -> g?.let { id -> subscriptions.firstOrNull { it.id == id }?.name ?: id } ?: defaultGroupLabel },
                onSelect = { selectedManualGroup = it },
                onLongPress = { if (it != null) showGroupMenu = it },
                onAdd = { showCreateGroupDialog = true },
            )
        }

        if (isPlatformMode) {
            Spacer(Modifier.height(10.dp))
            NodePlatformChips(
                selected = selectedPlatform,
                onSelect = { selectedPlatform = it },
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- the list ----------------------------------------------------------------------
        // The list, with a jump button floating over it. See [ListJumpButton].
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            // The list ends in a fade, not a cut.
            //
            // It sits between two fixed things -- the filter row above it and the connect card
            // below -- and a scrolling list clipped to its own bounds meets both with a hard
            // edge: a row is fully drawn at one pixel and gone at the next, which reads as a
            // seam across the screen rather than as content continuing past the frame. The same
            // `fadingEdges` the settings screens use dissolves the last few dp instead, so a row
            // leaving the viewport thins out into the surface it is passing under.
            //
            // Bottom is longer than top on purpose: rows leave downward under a card that is
            // itself opaque, and a short fade there still leaves a visible line where the two
            // meet.
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            // What is left on the subscription whose servers these are, when its server said
            // (Subscription-Userinfo): the figures other apps show as its first entries.
            val listedGroup = if ((isGroupedByPanel || distinctEngines.size <= 1) && selectedTabEngine == "Manual") selectedManualGroup else null
            val listedSub = listedGroup?.let { g -> subscriptions.firstOrNull { it.id == g || it.name == g } }
            val listedUsage = listedSub?.usage
            if (listedSub != null && listedUsage != null) {
                item(key = "sub-usage") { SubscriptionUsageCard(listedSub, listedUsage) }
            }

            // The Iran port notice and the domain-fronting setup card used to live here, each
            // inside its own folder. Both moved with their configs to the home-screen icons that
            // now own them -- and neither is reachable from here any more, since the folders they
            // belonged to are filtered out above.

            if (displayedNodes.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillParentMaxWidth().fillParentMaxHeight(0.65f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = null,
                            tint = Ios.SecondaryLabel.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.nodes_no_config_available),
                            color = Ios.Label,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            S(R.string.add_one_with_the_add_button_at),
                            color = Ios.SecondaryLabel,
                            fontSize = 13.sp,
                        )
                    }
                }
            } else {
                items(items = displayedNodes, key = { it.id }) { node ->
                    // Read HERE, inside the item.
                    //
                    // This is the whole point of the live map: the lookup happens in the row's own
                    // composition, so a reading landing invalidates that row and nothing above it.
                    // Hoisting it even one level -- into the list, into `displayedNodes` -- would
                    // put the subscription back on the whole screen and undo it.
                    val shown = if (livePing.isEmpty() && liveDelay.isEmpty() && liveSpeed.isEmpty()) {
                        node
                    } else {
                        node.copy(
                            ping = livePing[node.id] ?: node.ping,
                            delay = liveDelay[node.id] ?: node.delay,
                            speed = liveSpeed[node.id] ?: node.speed,
                        )
                    }
                    NodeRow(
                        node = shown,
                        isActive = activeNodeId == node.id,
                        isConnected = tunnelUp && connectedNodeId == node.id,
                        highlight = topNodeHighlightedId == node.id,
                        platformDelay = if (isPlatformMode && selectedPlatform != null)
                            platformTestResults["${node.id}_${selectedPlatform!!.name}"] else null,
                        onClick = {
                            activeNodeId = node.id
                            if (isWireguardTrialActive()) {
                                // WireGuard trial is up — starting an Xray node now would crash.
                                pendingNodeConnect = node
                                showWgConflict = true
                            } else if (isRunning) {
                                val startIntent = android.content.Intent(context, com.mlmvpn.scanner.MyVpnService::class.java).apply {
                                    putExtra("NODE_URI", node.uri)
                                    putExtra("NODE_ID", node.id)
                                    putExtra("PROXY_MODE", com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(context))
                                    putExtra("LOCAL_PORT", com.mlmvpn.scanner.utils.LocalPort.getString(context))
                                }
                                com.mlmvpn.scanner.utils.SafeStart.service(context, startIntent)
                            }
                        },
                        onOpenDetail = { page = NodesPage.Detail(node.id) },
                    )
                }
            }
        }

        ListJumpButton(
            state = listState,
            itemCount = displayedNodes.size,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 16.dp),
        )
        }

        // ---- connect ------------------------------------------------------------------------
        //
        // A bar, not a floating disc. The FAB sat in the bottom corner over the list, which put
        // the one control the screen exists for on top of the content it acts on -- and it hid
        // whichever config happened to be under it. This says which server it will use.
        com.mlmvpn.scanner.ui.RouteChips()
        NodeConnectBar(
            node = nodes.find { it.id == activeNodeId },
            isRunning = tunnelUp,
            isConnecting = tunnelStarting,
            hasFailed = tunnelFailed,
            exitCountry = activeRealCountry,
            platformMode = isPlatformMode && selectedPlatform != null,
            platformTesting = isPlatformTesting,
            platformName = selectedPlatform?.displayName,
            onPlatformTest = {
                if (!isPlatformTesting) {
                    isPlatformTesting = true
                    scope.launch {
                        android.widget.Toast.makeText(context, S(R.string.measuring_2, selectedPlatform!!.displayName), android.widget.Toast.LENGTH_SHORT).show()
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
                        } else {
                            android.widget.Toast.makeText(context, S(R.string.no_server_connected), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onToggle = {
                if (activeNodeId == null) {
                    android.widget.Toast.makeText(context, S(R.string.choose_a_server_first), android.widget.Toast.LENGTH_SHORT).show()
                } else if (isRunning) {
                    // The service being up covers both "connected" and "still trying", and the
                    // answer is the same for both: stop it. stopVpnSafely, not a bare STOP -- if
                    // the WireGuard trial is what is running, the process must be relaunched or
                    // the Go runtime exits by itself seconds later. No-op for every other engine.
                    stopVpnSafely(context)
                    isConnecting = false
                } else {
                    // Bringing up a tunnel takes the default route out from under every socket a
                    // running scan has open: the scan does not fail, it finishes early having
                    // marked thousands of untested addresses dead. So the guard asks first, and
                    // `isConnecting` is only set once the action is actually going ahead --
                    // otherwise the button sat spinning behind a dialog the user might dismiss.
                    com.mlmvpn.scanner.data.ScanGuard.run(
                        com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN
                    ) {
                        isConnecting = true
                        val intent = VpnService.prepare(context)
                        if (intent != null) {
                            vpnLauncher.launch(intent)
                        } else {
                            val node = nodes.find { it.id == activeNodeId }
                            if (node != null) {
                                val startIntent = Intent(context, MyVpnService::class.java).apply {
                                    putExtra("NODE_URI", node.uri)
                                    putExtra("NODE_ID", node.id)
                                    putExtra("PROXY_MODE", com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(context))
                                    putExtra("LOCAL_PORT", com.mlmvpn.scanner.utils.LocalPort.getString(context))
                                }
                                com.mlmvpn.scanner.utils.SafeStart.service(context, startIntent)
                            } else {
                                isConnecting = false
                            }
                        }
                    }
                }
            },
        )

        if (coachStep == com.mlmvpn.scanner.ui.CombineCoach.Step.DONE) {
            com.mlmvpn.scanner.ui.CombineDoneDialog(
                onClose = { com.mlmvpn.scanner.ui.CombineCoach.finish() }
            )
        }

        GroupManagementDialogs(
            showCreateGroupDialog = showCreateGroupDialog,
            onCreateGroupDismiss = { showCreateGroupDialog = false },
            onCreateGroupConfirm = { name ->
                if (name.isNotBlank() && !distinctManualGroups.contains(name)) {
                    emptyCustomGroups.add(name)
                    selectedManualGroup = name
                }
                showCreateGroupDialog = false
            },
            showGroupMenu = showGroupMenu,
            onGroupMenuDismiss = { showGroupMenu = null },
            onRenameClick = { groupToRename = showGroupMenu; showGroupMenu = null },
            onDeleteClick = { group ->
                val doomed = nodes.filter { it.groupTitle == group }.map { it.id }.toSet()
                val remaining = nodes.filterNot { it.groupTitle == group }
                nodes = remaining
                nodeManager.removeByIds(doomed)
                emptyCustomGroups.remove(group)
                subscriptions.firstOrNull { it.name == group }?.let { subscriptionManager.removeSubscription(it.id) }
                if (selectedManualGroup == group) selectedManualGroup = null
                showGroupMenu = null
            },
            onUpdateSubClick = { group ->
                val sub = subscriptions.firstOrNull { it.name == group }
                showGroupMenu = null
                if (sub != null) {
                    android.widget.Toast.makeText(context, S(R.string.updating), android.widget.Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val res = subscriptionManager.updateSubscription(sub, nodeManager)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            nodes = nodeManager.nodes.toList()
                            android.widget.Toast.makeText(context, res.second, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            groupToRename = groupToRename,
            onRenameDismiss = { groupToRename = null },
            onRenameConfirm = { old, new ->
                if (new.isNotBlank()) {
                    val updated = nodes.map { if (it.groupTitle == old) it.copy(groupTitle = new) else it }
                    nodes = updated
                    nodeManager.mergeById(updated.filter { it.groupTitle == new })
                    if (emptyCustomGroups.contains(old)) { emptyCustomGroups.remove(old); emptyCustomGroups.add(new) }
                    if (selectedManualGroup == old) selectedManualGroup = new
                }
                groupToRename = null
            },
            isSubscription = subscriptions.any { it.id == showGroupMenu || it.name == showGroupMenu },
        )
    }
}


fun isNodeMeasuredDead(node: com.mlmvpn.scanner.models.VpnNode): Boolean {
    fun verdictOf(value: String): Boolean? = when {
        value.contains("ms") -> false                                    // measured, alive
        value == "Timeout" || value == "N/A" || value == "Error" -> true // measured, down
        else -> null                                                     // no verdict
    }
    return verdictOf(node.delay) ?: verdictOf(node.ping) ?: false
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
        IosPrompt(
            title = S(R.string.new_folder),
            message = S(R.string.keep_manual_configs_in_separate_folders_so),
            placeholder = S(R.string.folder_name),
            confirmLabel = S(R.string.create_2),
            onConfirm = onCreateGroupConfirm,
            onDismiss = onCreateGroupDismiss,
        )
    }

    if (showGroupMenu != null) {
        // A menu, so the actions stack. Four verbs on one line is not a choice, it is a puzzle.
        val actions = buildList {
            if (isSubscription && onUpdateSubClick != null) {
                add(IosAlertAction(S(R.string.update_subscription), onClick = { onUpdateSubClick(showGroupMenu) }))
            }
            add(IosAlertAction(S(R.string.rename), onClick = { onRenameClick(showGroupMenu) }))
            add(IosAlertAction(S(R.string.delete_the_folder_and_its_contents), onClick = { onDeleteClick(showGroupMenu) }, destructive = true))
            add(IosAlertAction(S(R.string.close), onClick = onGroupMenuDismiss))
        }
        IosAlert(
            title = showGroupMenu,
            message = S(R.string.manage_the_folder_deleting_it_also_removes),
            actions = actions,
            onDismiss = onGroupMenuDismiss,
        )
    }

    if (groupToRename != null) {
        IosPrompt(
            title = S(R.string.rename_folder),
            initial = groupToRename,
            placeholder = S(R.string.new_name),
            confirmLabel = S(R.string.save),
            onConfirm = { onRenameConfirm(groupToRename, it) },
            onDismiss = onRenameDismiss,
        )
    }
}

// =================================================================================================
// The measurement primitives.
//
// Four network calls the whole app leans on: a TCP connect, a TLS handshake with a chosen SNI, the
// same handshake without VpnService.protect for the local SNI front, and a real download through a
// generated Xray config. They are used from here, from the emergency screens and from the free-config
// wizard, so they are top-level and stay top-level.
// =================================================================================================

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
        // A test core, in the test host process (XrayHosts): a node that panics Xray ends that
        // process, not the app.
        var core: com.mlmvpn.core.warp.VlessXrayInjector? = null
        try {
            val c = com.mlmvpn.core.warp.VlessXrayInjector(0, quiet = true)
            if (!c.start(context, jsonConfig, localPort)) return@withContext "Error"
            core = c
            
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
            try { core?.stop() } catch (e: Exception) {}
        }
        speedStr
    } catch (e: Exception) {
        "Error"
    }
}

/**
 * Test results as they arrive, by node id, for the rows to read.
 *
 * Was a SnapshotStateMap, whose every write compares the old map with the new one entry by entry:
 * one result cost a pass over every marked node, so "delay all" over a few thousand configs was
 * millions of lookups on the main thread, which froze the list (freeze and ANR reports, 1.2.41).
 * Here a write is one copy of a plain map, and marking or cancelling a whole run is one write.
 */
class LiveMarks {
    private var marks by mutableStateOf<Map<String, String>>(emptyMap(), referentialEqualityPolicy())

    operator fun get(id: String): String? = marks[id]
    operator fun set(id: String, value: String) { marks = HashMap(marks).apply { put(id, value) } }
    fun isEmpty(): Boolean = marks.isEmpty()
    fun clear() { if (marks.isNotEmpty()) marks = emptyMap() }

    /** Every id in [ids] set to [value], everything else dropped: one write. */
    fun reset(ids: Collection<String>, value: String) { marks = ids.associateWithTo(HashMap(ids.size * 2)) { value } }

    /** Every entry holding [from] set to [to]: one write. */
    fun replaceValue(from: String, to: String) {
        if (marks.values.none { it == from }) return
        marks = marks.mapValuesTo(HashMap(marks.size * 2)) { (_, v) -> if (v == from) to else v }
    }
}

/** A node's measurements as numbers, for sorting: delay and ping in ms (null: none), speed in MB/s (-1: none). */
class NodeSortKey(val delay: Double?, val speed: Double, val ping: Double?) {
    companion object {
        fun of(node: VpnNode) = NodeSortKey(
            delay = if (node.delay.contains("ms")) number(node.delay.replace("ms", "")) else null,
            speed = if (node.speed.contains("MB/s")) number(node.speed.replace(" MB/s", "")) ?: -1.0 else -1.0,
            ping = if (node.ping.contains("ms")) number(node.ping.replace("ms", "")) else null,
        )

        /** A plain decimal ("123", "1.5") without toDoubleOrNull's regex; anything else is null. */
        private fun number(raw: String): Double? {
            val t = raw.trim()
            if (t.isEmpty() || t.length > 24) return null
            var dot = false
            for ((i, c) in t.withIndex()) {
                when {
                    c in '0'..'9' -> {}
                    c == '.' && !dot -> dot = true
                    c == '-' && i == 0 && t.length > 1 -> {}
                    else -> return null
                }
            }
            return try { t.toDouble() } catch (_: NumberFormatException) { null }
        }
    }
}
