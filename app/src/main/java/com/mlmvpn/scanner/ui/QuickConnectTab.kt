package com.mlmvpn.scanner.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.quick.*
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.PanelShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// Quick connect: one button that finds a working server and connects to it.
//
// Two lists feed it. The shared MLMVPN pool holds servers other people on the SAME operator have
// proven within the hour -- see MlmPoolClient -- and the user's own saved list is the fallback for
// when the pool cannot be reached. The button races whichever it gets and connects to the first
// server that answers.
//
// Everything visual here is the app's shared language: grouped rows, section headers, footers,
// tinted panels. The ring is the one bespoke element, because on this screen it IS the screen.
// =================================================================================================

enum class QuickState { IDLE, SEARCHING, CONNECTING, CONNECTED, DISCONNECTING }

@Composable
fun QuickConnectTab(onBack: () -> Unit = {}) {
    val hostContext = LocalContext.current
    var savedRevision by remember { mutableStateOf(0) }
    var showServers by remember { mutableStateOf(false) }

    // A pushed page, not a child of the frame below: it carries its own navigation bar, and two
    // bars stacked is what happens if it renders inside this screen's content instead of in
    // place of it.
    if (showServers) {
        QuickServersScreen(
            onDismiss = {
                showServers = false
                savedRevision++
            },
            onAdopt = { nodes ->
                val added = QuickSavedStore.addAll(hostContext, nodes)
                savedRevision++
                showServers = false
                Toast.makeText(
                    hostContext,
                    faCount(added) + S(R.string.servers_added_to_the_connection_page),
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
        return
    }

    IosScreen(
        title = S(R.string.quick_connect),
        onBack = onBack,
        backLabel = S(R.string.home),
        scrollable = false,
    ) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val connectedNodeId by MyVpnService.connectedNodeIdFlow.collectAsState()

    var saved by remember { mutableStateOf(QuickSavedStore.all(context).toList()) }
    fun reloadSaved() { saved = QuickSavedStore.all(context).toList() }
    // Adopting servers on the pushed page writes straight to the store; this is what tells the
    // list to read it again once the page pops.
    LaunchedEffect(savedRevision) { reloadSaved() }

    var statusLine by remember { mutableStateOf("") }
    var egress by remember { mutableStateOf<EgressResult?>(null) }
    var testingAll by remember { mutableStateOf(false) }
    var testProgress by remember { mutableStateOf(0 to 0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<String?>(null) }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    // Pool servers live here and nowhere else -- never in QuickSavedStore, never on disk.
    var poolNodes by remember { mutableStateOf<List<QuickNode>>(emptyList()) }
    var connectedFromPool by remember { mutableStateOf(false) }

    // Only a node this screen started counts as connected. A tunnel raised from the V2Ray tab or
    // a transport screen must not light this button up.
    val isOurs = connectedNodeId != null &&
        (saved.any { it.id == connectedNodeId } || poolNodes.any { it.id == connectedNodeId })
    val state = when {
        isOurs && phase == MyVpnService.Phase.CONNECTED -> QuickState.CONNECTED
        isOurs && phase == MyVpnService.Phase.CONNECTING -> QuickState.CONNECTING
        pendingUri != null -> QuickState.CONNECTING
        searching -> QuickState.SEARCHING
        else -> QuickState.IDLE
    }

    LaunchedEffect(Unit) {
        QuickSavedStore.markSeen(context)
        reloadSaved()
    }

    fun startService(id: String, uri: String) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val isProxyMode = com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(context)
        val localPort = com.mlmvpn.scanner.utils.LocalPort.getString(context)
        com.mlmvpn.scanner.utils.SafeStart.service(context, 
            Intent(context, MyVpnService::class.java).apply {
                putExtra("NODE_URI", uri)
                putExtra("NODE_ID", id)
                putExtra("MTU_PROFILE", com.mlmvpn.scanner.utils.NetworkSettings.Method.QUICK_CONNECT.id)
                putExtra("PROXY_MODE", isProxyMode)
                putExtra("LOCAL_PORT", localPort)
            }
        )
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = pendingUri
        val id = pendingId
        if (res.resultCode == Activity.RESULT_OK && uri != null && id != null) {
            startService(id, uri)
        } else {
            statusLine = ""
        }
        pendingUri = null
        pendingId = null
    }

    fun connectTo(id: String, uri: String) = com.mlmvpn.scanner.data.ScanGuard.run(
        // A tunnel steals the default route from a running IP scan; the guard asks first.
        com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN
    ) {
        pendingId = id
        pendingUri = uri
        // Claim the tunnel before it exists, so the home screen's lamp is right from the first
        // frame -- and so a pool server, which is in no list on disk, is still recognisably ours.
        QuickSavedStore.markConnected(context, id)
        statusLine = S(R.string.connecting)
        egress = null
        val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
        if (prep != null) {
            vpnLauncher.launch(prep)
        } else {
            startService(id, uri)
            pendingUri = null
            pendingId = null
        }
    }

    fun disconnect() {
        statusLine = ""
        egress = null
        com.mlmvpn.scanner.utils.SafeStart.service(context, Intent(context, MyVpnService::class.java).apply { action = "STOP" })
    }

    /**
     * The one button.
     *
     * Connected, it disconnects. Otherwise it asks the shared pool for servers other people on
     * this same operator have just proven, races them all at once, and connects to the first that
     * answers -- falling back to the user's own saved list if the pool is unreachable.
     *
     * Racing rather than walking is the whole reason this reaches a usable success rate. Public
     * free configs are individually unreliable: measured on the live catalogue, about one in a
     * hundred works at any moment, and even a pool-vetted config can die between being verified
     * and being tried. Twenty at once turns twenty independent coin flips into one that almost
     * always lands, and it costs no more wall-clock time than testing one slow server.
     */
    fun onBigButton() {
        when (state) {
            QuickState.CONNECTED -> { disconnect(); return }
            QuickState.SEARCHING -> {
                QuickScanner.stopMeasuring()
                searchJob?.cancel()
                searchJob = null
                searching = false
                statusLine = ""
                return
            }
            QuickState.CONNECTING, QuickState.DISCONNECTING -> return
            QuickState.IDLE -> Unit
        }

        searching = true
        statusLine = S(R.string.fetching_fresh_servers)
        searchJob = scope.launch {
            val pooled = MlmPoolClient.fetch(context)
            if (!searching) return@launch

            // The user's own list is the fallback, ordered fastest-first: a server already known
            // to be quick is worth trying before anything unproven.
            val fallback = saved.sortedWith(
                compareBy(
                    { if (it.delay in 1..QuickScanner.GOOD_ENOUGH_MS) 0 else 1 },
                    { if (it.delay > 0) it.delay else Int.MAX_VALUE },
                )
            ).map { it.id to it.uri }

            val candidates = if (pooled.isNotEmpty()) {
                poolNodes = pooled
                // Pool servers, plus a few of the user's own that the pool has never seen.
                // Without those the race is a closed loop -- it only ever tries what the pool
                // sent, so the only configs that can ever be reported back are the ones already
                // in it, and the list can never grow past whatever seeded it. The extra probes
                // are free: they run in the same parallel round, and the first answer still wins.
                val known = pooled.mapTo(HashSet()) { it.uri }
                pooled.map { it.id to it.uri } + fallback.filter { it.second !in known }.take(6)
            } else fallback

            if (candidates.isEmpty()) {
                searching = false
                statusLine = ""
                showServers = true
                return@launch
            }

            statusLine = S(R.string.testing) + faCount(candidates.size) + S(R.string.servers_at_once)

            // Everything at once, and the first real answer wins. `measure` is a full proxied
            // request, so a winner here is a server that actually carried traffic a second ago.
            val winner = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>(null)
            coroutineScope {
                val jobs = candidates.map { (id, uri) ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        if (winner.get() != null) return@async
                        // Nothing is reported here any more: `measure` itself tells the pool, so
                        // a result counts no matter which screen measured it.
                        if (QuickScanner.measure(context, uri) > 0) {
                            winner.compareAndSet(null, id to uri)
                        }
                    }
                }
                // Stop as soon as one lands; the rest are no longer interesting.
                while (jobs.any { it.isActive } && winner.get() == null && searching) {
                    kotlinx.coroutines.delay(120)
                }
                jobs.forEach { it.cancel() }
            }

            searching = false
            val won = winner.get()
            if (won == null) {
                statusLine = S(R.string.no_server_answered_try_again)
            } else {
                connectedFromPool = poolNodes.any { it.id == won.first }
                connectTo(won.first, won.second)
            }
        }
    }

    fun testOne(row: SavedServer) {
        scope.launch {
            QuickSavedStore.updateResult(context, row.id, SavedServer.TESTING)
            reloadSaved()
            val ms = QuickScanner.measure(context, row.uri)
            QuickSavedStore.updateResult(context, row.id, ms)
            reloadSaved()
        }
    }

    fun testAllSaved() {
        if (testingAll) return
        val batch = saved.map { it.id to it.uri }
        if (batch.isEmpty()) return
        testingAll = true
        testProgress = 0 to batch.size
        scope.launch {
            try {
                QuickScanner.measureAll(
                    context = context,
                    uris = batch,
                    onResult = { id, delay ->
                        QuickSavedStore.updateResult(context, id, delay)
                        reloadSaved()
                    },
                    onProgress = { done, total -> testProgress = done to total },
                )
            } finally {
                QuickSavedStore.clearTestingMarkers(context)
                QuickSavedStore.resort(context)
                testingAll = false
                reloadSaved()
            }
        }
    }

    // Once the tunnel is up, prove where it actually comes out. A reading that matches the
    // phone's own address means the request never entered the tunnel, and the banner says so
    // rather than claiming a country it cannot support.
    LaunchedEffect(state) {
        if (state == QuickState.CONNECTED) {
            statusLine = S(R.string.connected)
            val port = com.mlmvpn.scanner.utils.LocalPort.get(context)
            val trace = EgressTracer.traceWhenReady(port)
            egress = trace

            // The trace is evidence; the feed's flag is only a claim. Where they disagree the
            // evidence wins, and it has to win HERE -- on the row the user is looking at -- not
            // just in the catalogue some later refresh rebuilds. Both halves of this were
            // written months ago and never called, which is why a server that plainly exits in
            // Germany kept flying an American flag and stayed filed under the wrong country.
            //
            // WARP paths are excluded by `countryTrusted`: they report the USER's country by
            // design, so trusting one would file every node under Iran. See [EgressTracer].
            val measured = trace.takeIf { it.ok && it.countryTrusted }?.country?.code
            val id = connectedNodeId
            if (measured != null && !id.isNullOrBlank()) {
                QuickVerifiedStore.record(context, id, measured)
                if (QuickSavedStore.applyMeasuredCountry(context, id, measured)) reloadSaved()
                poolNodes.firstOrNull { it.id == id }?.let { node ->
                    node.applyVerified(measured)
                    poolNodes = poolNodes.toList()   // a new list, so the row actually redraws
                }
            }

            // Ask for the minute-later second opinion, and carry the measured country with it
            // so everyone else gets the corrected flag rather than the feed's claim. Handed to
            // the client rather than awaited here: leaving this screen must not cancel it, and
            // leaving this screen is what people do the moment they are connected.
            if (connectedFromPool && !id.isNullOrBlank()) {
                poolNodes.firstOrNull { it.id == id }?.let { node ->
                    MlmPoolClient.confirmIfStillUp(context, id, node.uri, measured)
                }
            }
        } else if (state == QuickState.IDLE) {
            egress = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = LocalSystemBottomPadding.current + 24.dp,
            )
        ) {
            item {
                // The navigation bar already carries the screen's name; this is the breathing
                // room the removed 22sp title used to provide.
                Spacer(Modifier.height(28.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ConnectOrb(state = state, onClick = { onBigButton() })
                }
                Spacer(Modifier.height(18.dp))
            }

            item {
                Text(
                    text = statusLine.ifBlank {
                        when (state) {
                            QuickState.IDLE ->
                                if (saved.any { it.delay > 0 }) S(R.string.ready_to_connect)
                                else S(R.string.tap_to_connect_automatically)
                            QuickState.CONNECTED -> S(R.string.connected)
                            else -> ""
                        }
                    },
                    color = when (state) {
                        QuickState.CONNECTED -> Ios.Green
                        QuickState.DISCONNECTING -> Ios.Destructive
                        else -> Ios.SecondaryLabel
                    },
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                )
                com.mlmvpn.scanner.ui.RouteChips()
                Spacer(Modifier.height(14.dp))
            }

            egress?.let {
                item {
                    EgressBanner(it)
                    Spacer(Modifier.height(8.dp))
                }
            }

            item {
                // Above the list, not below it: three rows under a list of twenty servers are
                // three rows nobody scrolls to, and "browse the catalogue" is the first thing a
                // new user needs -- their list is empty, so there is nothing above it to read.
                SettingsGroup {
                    SettingsActionRow(
                        label = S(R.string.server_list_and_country_scan),
                        icon = Icons.Default.Dns,
                        tint = Ios.Gray,
                    ) { showServers = true }
                    if (saved.isNotEmpty()) {
                        Separator()
                        SettingsActionRow(
                            label = if (testingAll) S(R.string.stop_testing) else S(R.string.test_all_my_servers),
                            icon = if (testingAll) Icons.Default.Stop else Icons.Default.NetworkCheck,
                            tint = if (testingAll) Ios.Orange else Ios.Gray,
                            labelColor = Ios.Label,
                        ) { if (testingAll) QuickScanner.stopMeasuring() else testAllSaved() }
                        Separator()
                        SettingsActionRow(
                            label = S(R.string.clear_list),
                            icon = Icons.Default.DeleteSweep,
                            tint = Ios.Destructive,
                        ) { confirmDelete = true }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            if (poolNodes.isNotEmpty()) {
                item {
                    SettingsSectionHeader(S(R.string.mlmvpn_private_list) + faCount(poolNodes.size))
                    SettingsGroup {
                        poolNodes.forEachIndexed { i, node ->
                            if (i > 0) Separator()
                            PoolRow(
                                node = node,
                                connected = state == QuickState.CONNECTED && connectedNodeId == node.id,
                            )
                        }
                    }
                    SettingsFooter(
                        S(R.string.other_people_on_this_same_carrier_have) +
                            S(R.string.they_are_never_saved_on_your_phone) +
                            S(R.string.connect_button)
                    )
                }
            }

            item {
                SettingsSectionHeader(
                    if (saved.isEmpty()) S(R.string.my_servers)
                    else S(R.string.my_servers_2) + faCount(saved.size)
                )
                if (testingAll) {
                    val (done, total) = testProgress
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            S(R.string.testing_2) + faCount(done) + S(R.string.of) + faCount(total),
                            color = Ios.SecondaryLabel,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = if (total > 0) done.toFloat() / total else 0f,
                            color = Ios.Blue,
                            trackColor = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            if (saved.isEmpty()) {
                item {
                    SettingsFooter(
                        S(R.string.no_servers_saved_yet_the_button_above) +
                            S(R.string.on_its_own_or_open_server_list) +
                            S(R.string.results_back_here)
                    )
                }
            } else {
                item {
                    SettingsGroup {
                        saved.forEachIndexed { i, row ->
                            if (i > 0) Separator()
                            SavedRow(
                                row = row,
                                connected = state == QuickState.CONNECTED && connectedNodeId == row.id,
                                onConnect = {
                                    if (state == QuickState.CONNECTED && connectedNodeId == row.id) disconnect()
                                    else connectTo(row.id, row.uri)
                                },
                                onTest = { testOne(row) },
                                onDelete = {
                                    QuickSavedStore.remove(context, listOf(row.id))
                                    QuickConnectRepository.invalidate()
                                    reloadSaved()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val deadCount = saved.count { it.isDead }
        IosAlert(
            title = S(R.string.clear_list),
            message = S(R.string.deleted_servers_are_not_downloaded_again_by) +
                S(R.string.dead) + faCount(deadCount) + S(R.string.all) + faCount(saved.size),
            onDismiss = { confirmDelete = false },
            actions = listOf(
                IosAlertAction(S(R.string.cancel), onClick = { confirmDelete = false }),
                IosAlertAction(
                    S(R.string.dead_only) + faCount(deadCount) + ")",
                    onClick = {
                        QuickSavedStore.removeDead(context)
                        QuickConnectRepository.invalidate()
                        reloadSaved()
                        confirmDelete = false
                    },
                ),
                IosAlertAction(
                    S(R.string.all_2),
                    onClick = {
                        QuickSavedStore.remove(context, saved.map { it.id })
                        QuickConnectRepository.invalidate()
                        reloadSaved()
                        confirmDelete = false
                    },
                    destructive = true,
                ),
            ),
        )
    }
    }
}

/**
 * The connect button.
 *
 * Kept as a ring rather than folded into the shared EmergencyDial: this screen has exactly one
 * control and the ring IS the screen, where the transport screens use the dial as one element
 * among several. Each state gets its own motion, so the button reads at a glance without the
 * label: idle breathes slowly, working sweeps a rotating arc, connected is still (a solid ring --
 * motion on a settled state is noise), disconnecting sweeps.
 */
@Composable
private fun ConnectOrb(state: QuickState, onClick: () -> Unit) {
    val ringColor = when (state) {
        QuickState.CONNECTED -> Ios.Green
        QuickState.DISCONNECTING -> Ios.Destructive
        QuickState.IDLE -> Ios.SecondaryLabel
        else -> Ios.Blue
    }
    val working = state == QuickState.SEARCHING || state == QuickState.CONNECTING

    // Each motion exists only while it is shown, and only on screen. Both used to run in every
    // state, all the time -- an infinite transition asks for every frame for as long as it is
    // composed, read or not, and this tab stays composed (parked out of sight) once visited.
    val onScreen by com.mlmvpn.scanner.ui.rememberOnScreen()
    val sweep: androidx.compose.runtime.State<Float> =
        if (onScreen && (working || state == QuickState.DISCONNECTING)) {
            rememberInfiniteTransition(label = "orb").animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
                label = "sweep"
            )
        } else {
            remember { mutableStateOf(0f) }
        }
    val sweepAngle by sweep
    val breathe = if (onScreen && state == QuickState.IDLE) {
        rememberInfiniteTransition(label = "orbBreathe").animateFloat(
            initialValue = 0.97f, targetValue = 1.03f,
            animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe"
        ).value
    } else {
        1f
    }
    val pressScale by animateFloatAsState(if (state == QuickState.IDLE) breathe else 1f, label = "scale")

    Box(
        modifier = Modifier.size(210.dp).scale(pressScale),
        contentAlignment = Alignment.Center
    ) {
        // Read outside the draw lambda: the palette is composable, and a DrawScope is not a
        // composable context.
        val trackColor = Color.White.copy(alpha = 0.10f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2 + 8.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            // Halo -- the soft outer field. Faint, so it reads as depth rather than decoration.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(ringColor.copy(alpha = 0.20f), Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2f
                ),
                radius = size.minDimension / 2f
            )

            // Track -- always the full circle, so a partial arc reads as progress against it.
            drawArc(
                color = trackColor,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            when {
                working -> drawArc(
                    color = ringColor,
                    startAngle = sweepAngle, sweepAngle = 90f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                state == QuickState.DISCONNECTING -> drawArc(
                    color = ringColor,
                    startAngle = -sweepAngle, sweepAngle = 70f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                state == QuickState.CONNECTED -> drawArc(
                    color = ringColor,
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                else -> drawArc(
                    color = ringColor.copy(alpha = 0.45f),
                    startAngle = -90f, sweepAngle = 120f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(
                    if (state == QuickState.IDLE) Color.White.copy(alpha = 0.07f)
                    else ringColor.copy(alpha = 0.16f)
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = when (state) {
                        QuickState.SEARCHING -> Icons.Default.Search
                        else -> Icons.Default.PowerSettingsNew
                    },
                    contentDescription = null,
                    // White, not the accent: the ring already carries the state, and an accent
                    // glyph inside an accent ring is the same word said twice.
                    tint = Ios.Label,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when (state) {
                        QuickState.IDLE -> S(R.string.connect)
                        QuickState.SEARCHING -> S(R.string.searching)
                        QuickState.CONNECTING -> S(R.string.connecting_2)
                        QuickState.CONNECTED -> S(R.string.disconnect_2)
                        QuickState.DISCONNECTING -> S(R.string.disconnecting)
                    },
                    color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** What the live trace found, once the tunnel is up. */
@Composable
private fun EgressBanner(res: EgressResult) {
    val (text, tint) = when {
        !res.ok -> S(R.string.egress_not_checked, res.error) to Ios.Orange
        // A reading whose IP matches the phone's own is not the exit; it is a request that
        // never entered the tunnel. Saying "verified" there would be a lie.
        !res.tunnelled ->
            S(R.string.connected_but_the_exit_country_was_not) to Ios.Orange
        // WARP reports the USER's country by design, so this is transit, not location.
        res.warp ->
            S(R.string.route_verified_warp_the_exit_country_cannot, res.colo ?: "—") to Ios.Green
        else ->
            S(R.string.real_exit, res.country?.label ?: res.loc, res.colo ?: "—", res.ip ?: "") to Ios.Green
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(PanelShape)
            .background(tint.copy(alpha = 0.13f))
            .padding(12.dp),
    ) {
        Text(text, color = Ios.Label, fontSize = 12.sp, lineHeight = 20.sp)
    }
}

/**
 * One server from the shared pool.
 *
 * Country, flag and latency, and nothing else. No test button, no delete, and above all no way to
 * reach the URI: these are the app's own list, and the point of them is that they cannot be
 * copied out. The padlock is there so it reads as deliberate rather than as a missing feature.
 */
@Composable
private fun PoolRow(node: QuickNode, connected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(node.flag ?: "🏳️", fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (connected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Ios.Green,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    node.countryName ?: node.country ?: S(R.string.unknown),
                    color = Ios.Label,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(node.protocol.uppercase(), color = Ios.SecondaryLabel, fontSize = 11.sp)
        }
        if (node.delay > 0) {
            Text(
                faCount(node.delay) + " ms",
                color = if (node.delay < 400) Ios.Green else Ios.Orange,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = Ios.SecondaryLabel.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * One saved server.
 *
 * A grouped-list row, and "connected" is a tick rather than a border around the whole card.
 */
@Composable
private fun SavedRow(
    row: SavedServer,
    connected: Boolean,
    onConnect: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val testing = row.isTesting
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(row.flag ?: "🏳️", fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onConnect() }
                .padding(vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (connected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Ios.Green,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    row.name,
                    color = Ios.Label,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.isNew) {
                    Spacer(Modifier.width(6.dp))
                    Badge(S(R.string.new_str), Ios.Green)
                }
                if (row.isDead) {
                    Spacer(Modifier.width(6.dp))
                    Badge(S(R.string.dead_2), Ios.Destructive)
                }
            }
            Text(
                listOfNotNull(row.countryName, row.protocol.uppercase()).joinToString(" · "),
                color = Ios.SecondaryLabel,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                testing -> "…"
                row.delay > 0 -> faCount(row.delay) + " ms"
                row.testedAt > 0 -> S(R.string.dead_2)
                else -> "—"
            },
            color = when {
                row.delay in 1..399 -> Ios.Green
                row.delay in 400..999 -> Ios.Orange
                row.isDead -> Ios.Destructive
                else -> Ios.SecondaryLabel
            },
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
        Icon(
            Icons.Default.Refresh,
            contentDescription = S(R.string.retest),
            tint = Ios.SecondaryLabel,
            modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onTest).padding(7.dp),
        )
        Icon(
            Icons.Default.Close,
            contentDescription = S(R.string.delete),
            tint = Ios.Destructive,
            modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onDelete).padding(7.dp),
        )
    }
}

@Composable
internal fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}
