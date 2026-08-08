package com.mlmvpn.scanner.ui

// Deliberately in the `ui` package: getNodeFlagEmoji() lives here (NodesTab.kt) and is used
// as-is, with no import and no third copy of the regional-indicator maths.

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.engines.vpngate.*
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SortMode(val label: String) {
    VERIFIED("تست‌شده‌ها اول"),
    OFFICIAL("رسمی‌ها اول"),
    PING("سریع‌ترین"),
    SCORE("امتیاز"),
    SPEED("پهنای باند"),
    SESSIONS("کاربران"),
    COUNTRY("کشور"),
}

@Composable
fun VpnGateTab(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { VpnGateRepository(context) }

    val live by repo.serversFlow.collectAsState()
    val loading by repo.loadingFlow.collectAsState()
    val error by repo.errorFlow.collectAsState()
    val source by repo.sourceFlow.collectAsState()

    val connectedNodeId by MyVpnService.connectedNodeIdFlow.collectAsState()
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()

    val selectedHost by VpnGateStore.selectedHostFlow.collectAsState()
    val pings by VpnGateStore.pingsFlow.collectAsState()
    val connectedSince by VpnGateStore.connectedSinceFlow.collectAsState()

    val kept by VpnGatePool.keptFlow.collectAsState()
    val hidden by VpnGatePool.hiddenFlow.collectAsState()
    val udpAcceleration by VpnGateStore.udpAccelerationFlow.collectAsState()
    val handshakes by VpnGateStore.handshakesFlow.collectAsState()

    var showPicker by remember { mutableStateOf(false) }
    var showBrowse by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf(false) }
    var autoPicked by remember { mutableStateOf(false) }

    // The main list: whatever VPN Gate is advertising right now, plus everything the user
    // promoted from the archive, minus everything they pruned. Servers they added keep working
    // after VPN Gate rotates them out of the public window, which is the whole point of the
    // archive — the live list turns over almost completely inside a year.
    val servers = remember(live, kept, hidden) {
        val keptServers = VpnGatePool.keptServers()
        (live + keptServers)
            .distinctBy { it.hostName }
            .filter { it.hostName !in hidden }
    }

    val selected = remember(servers, selectedHost) {
        servers.firstOrNull { it.hostName == selectedHost }
    }

    // Only this screen's own node counts as connected — a VLESS/Aether session running from
    // another tab must not light this button up.
    val isOurs = connectedNodeId != null && connectedNodeId == selected?.id
    val isConnected = isOurs && phase == MyVpnService.Phase.CONNECTED
    val isConnecting = isOurs && phase == MyVpnService.Phase.CONNECTING

    LaunchedEffect(Unit) {
        VpnGateStore.load(context)
        VpnGatePool.load(context)
        repo.refresh()
    }

    // Session clock: start it when our tunnel comes up, clear it when it goes down.
    LaunchedEffect(isConnected) {
        if (isConnected && connectedSince == 0L) VpnGateStore.markConnected(context)
        if (!isConnected && !isConnecting && connectedSince != 0L) VpnGateStore.markDisconnected(context)
    }

    // First visit: pick a server for the user instead of showing an empty button. Measures the
    // real latency of the highest-scoring candidates and keeps the fastest one that answered.
    LaunchedEffect(servers) {
        if (servers.isEmpty() || autoPicked) return@LaunchedEffect
        autoPicked = true
        if (selectedHost != null && servers.any { it.hostName == selectedHost }) return@LaunchedEffect

        val candidates = servers.sortedByDescending { it.score }.take(12)
        VpnGatePinger.pingAll(
            servers = candidates,
            ovpnOf = { repo.ovpnTextFor(it) },
            onResult = { s, rtt -> VpnGateStore.putPing(s.hostName, rtt) },
        )
        val best = candidates
            .filter { (VpnGateStore.pingOf(it.hostName) ?: VpnGatePinger.FAILED) > 0 }
            .minByOrNull { VpnGateStore.pingOf(it.hostName)!! }
            ?: candidates.firstOrNull()
        best?.let { VpnGateStore.select(context, it.hostName) }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val server = selected
        if (pendingConnect && result.resultCode == android.app.Activity.RESULT_OK && server != null) {
            scope.launch { VpnGateController.connect(context, server, repo.ovpnTextFor(server)) }
        }
        pendingConnect = false
    }

    fun toggle() {
        if (isConnected || isConnecting) {
            VpnGateController.disconnect(context)
            VpnGateStore.markDisconnected(context)
            return
        }
        val server = selected ?: return
        val consent = VpnGateController.needsConsent(context)
        if (consent != null) {
            pendingConnect = true
            consentLauncher.launch(consent)
        } else {
            scope.launch { VpnGateController.connect(context, server, repo.ovpnTextFor(server)) }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- header -------------------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت", tint = TextMuted)
                }
                Spacer(Modifier.weight(1f))
                Text("GATE MLMVPN", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (loading) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Primary
                        )
                    }
                } else {
                    // Refresh moved into the browser: the list keeps itself current on entry,
                    // and the button worth having here is the one that grows the catalogue.
                    IconButton(onClick = { showBrowse = true }) {
                        Icon(Icons.Default.TravelExplore, contentDescription = "سرورهای بیشتر", tint = Primary)
                    }
                }
            }

            SourceBadge(source = source, count = servers.size, error = error)

            Spacer(Modifier.weight(1f))

            // ---- the button ---------------------------------------------------------
            PowerButton(
                isConnected = isConnected,
                isConnecting = isConnecting,
                enabled = selected != null,
                onClick = { toggle() },
            )

            Spacer(Modifier.height(18.dp))

            StatusLine(
                isConnected = isConnected,
                isConnecting = isConnecting,
                hasServer = selected != null,
                connectedSince = connectedSince,
            )

            Spacer(Modifier.weight(1f))

            // ---- selected server ----------------------------------------------------
            SelectedServerCard(
                server = selected,
                ping = selected?.let { pings[it.hostName] },
                enabled = !isConnecting,
                onClick = { showPicker = true },
            )

            Spacer(Modifier.height(10.dp))

            // Polled rather than pushed: the UDP channel opens some seconds into the session,
            // after the SSL one is already up, so a value read once at connect time would
            // always say "off".
            var udpActive by remember { mutableStateOf(false) }
            LaunchedEffect(isConnected) {
                if (!isConnected) { udpActive = false; return@LaunchedEffect }
                while (true) {
                    udpActive = SoftEtherEngine.isUdpAccelerationActive()
                    kotlinx.coroutines.delay(1500)
                }
            }

            UdpAccelerationRow(
                enabled = udpAcceleration,
                canChange = !isConnected && !isConnecting,
                isConnected = isConnected,
                isActive = udpActive,
                onChange = { VpnGateStore.setUdpAcceleration(context, it) },
            )

            Spacer(Modifier.height(96.dp))
        }
    }

    if (showPicker) {
        ServerPickerDialog(
            servers = servers,
            pings = pings,
            selectedHost = selectedHost,
            loading = loading,
            onPick = { server ->
                val wasConnected = isConnected
                VpnGateStore.select(context, server.hostName)
                showPicker = false
                // Switching servers while connected should just move the tunnel, not silently
                // leave the user on the old one with a new name on screen.
                if (wasConnected) {
                    scope.launch {
                        VpnGateController.connect(context, server, repo.ovpnTextFor(server))
                    }
                }
            },
            onDismiss = { showPicker = false },
            onPrune = { dead ->
                // `dead` arrives already filtered by the dialog, which is the only place that
                // holds both result maps.
                //
                // banAndPurge, not purge: this list is `live + kept`, so deleting the archive
                // entry alone leaves the row on screen coming out of `live` — the server looks
                // untouched except that its test result is gone. It has to be banned from the
                // list AND dropped from the archive.
                scope.launch {
                    VpnGatePool.banAndPurge(context, dead)
                    VpnGateStore.forgetHandshakes(dead)
                }
            },
            onPingAll = { list ->
                if (!VpnGateSweep.isRunning()) {
                    // LAZY + begin() + start(): registering the sweep before the body can run
                    // means a short list cannot call end() before begin() and strand the strip.
                    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        try {
                            VpnGatePinger.pingAll(
                                servers = list,
                                ovpnOf = { repo.ovpnTextFor(it) },
                                onResult = { s, rtt ->
                                    VpnGateStore.putPing(s.hostName, rtt)
                                    VpnGateSweep.tick()
                                },
                            )
                        } finally { VpnGateSweep.end() }
                    }
                    VpnGateSweep.begin(VpnGateSweep.Kind.PING, list.size, job)
                    job.start()
                }
            },
            handshakes = handshakes,
            onHandshakeAll = { list ->
                if (!VpnGateSweep.isRunning()) {
                    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        try {
                            SoftEtherProbe.probeAll(list) { s, r ->
                                VpnGateStore.putHandshake(s.hostName, r)
                                VpnGateSweep.tick()
                            }
                        } finally { VpnGateSweep.end() }
                    }
                    VpnGateSweep.begin(VpnGateSweep.Kind.PROBE, list.size, job)
                    job.start()
                }
            },
            onOpenBrowse = {
                showPicker = false
                showBrowse = true
            },
        )
    }

    if (showBrowse) {
        Dialog(
            onDismissRequest = { showBrowse = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(color = BgDark, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxSize()) {
                VpnGateBrowseScreen(onDismiss = { showBrowse = false })
            }
        }
    }
}

// =============================================================================================
// Main screen pieces
// =============================================================================================

@Composable
private fun PowerButton(
    isConnected: Boolean,
    isConnecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = when {
        isConnected -> GreenOk
        isConnecting -> YellowWarn
        else -> Primary
    }

    val transition = rememberInfiniteTransition(label = "power")
    // One halo that breathes while connected and sweeps outward while connecting; both are
    // driven off the same value so the button never shows two competing animations.
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnecting) 900 else 2200, easing = FastOutSlowInEasing),
            repeatMode = if (isConnecting) RepeatMode.Restart else RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val active = isConnected || isConnecting
    val haloScale = if (active) (if (isConnecting) 1f + pulse * 0.35f else 1.06f + pulse * 0.06f) else 1f
    val haloAlpha = if (active) (if (isConnecting) (1f - pulse) * 0.35f else 0.12f + pulse * 0.10f) else 0.06f

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size((186 * haloScale).dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = haloAlpha))
        )
        Box(
            modifier = Modifier
                .size(158.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.08f))
                .border(1.dp, accent.copy(alpha = 0.30f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(126.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = if (enabled) 0.95f else 0.35f), accent.copy(alpha = if (enabled) 0.65f else 0.25f))
                    )
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = if (isConnected) "قطع اتصال" else "اتصال",
                tint = BgDark,
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

@Composable
private fun StatusLine(
    isConnected: Boolean,
    isConnecting: Boolean,
    hasServer: Boolean,
    connectedSince: Long,
) {
    val label = when {
        isConnecting -> "در حال اتصال…"
        isConnected -> "متصل"
        !hasServer -> "سروری انتخاب نشده"
        else -> "قطع"
    }
    val color = when {
        isConnecting -> YellowWarn
        isConnected -> GreenOk
        else -> TextMuted
    }

    Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

    AnimatedVisibility(visible = isConnected && connectedSince > 0L) {
        var elapsed by remember { mutableStateOf(0L) }
        LaunchedEffect(connectedSince) {
            while (true) {
                elapsed = System.currentTimeMillis() - connectedSince
                delay(1000)
            }
        }
        Text(
            text = formatDuration(elapsed),
            color = TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SelectedServerCard(
    server: VpnGateServer?,
    ping: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (server == null) {
            Icon(Icons.Default.Language, contentDescription = null, tint = TextDim, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text("انتخاب سرور", color = TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        } else {
            Text(getNodeFlagEmoji(server.countryShort), fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.countryLong.ifBlank { server.countryShort },
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (server.isOfficialRelay) {
                        Text("رسمی", color = GreenOk, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(" • ", color = TextDim, fontSize = 11.sp)
                    }
                    // Host name only — never the address or port. Those are what someone
                    // reading over a shoulder, or a screenshot, would need to block it.
                    Text(
                        text = server.hostName,
                        color = TextDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    if (ping != null) {
                        Text(" • ", color = TextDim, fontSize = 11.sp)
                        Text(
                            text = if (ping > 0) "$ping ms" else "بدون پاسخ",
                            color = pingColor(ping),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = Primary, modifier = Modifier.size(22.dp))
    }
}

/**
 * Opt-in switch for SoftEther's UDP acceleration.
 *
 * Off by default because it fails silently where UDP is filtered — the tunnel reports itself
 * open and drops every packet — but it removes the TCP-in-TCP penalty on networks that do pass
 * UDP, which is a large speed difference. Locked while a session is up: the setting is read
 * once at connect time.
 */
@Composable
private fun UdpAccelerationRow(
    enabled: Boolean,
    canChange: Boolean,
    isConnected: Boolean,
    isActive: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("شتاب‌دهی UDP", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                // While connected the switch is locked, so without this badge there is no way
                // to tell whether UDP actually came up — and asking for it does not mean
                // getting it.
                if (isConnected && enabled) {
                    Spacer(Modifier.width(6.dp))
                    val (label, tint) = if (isActive) "فعال" to GreenOk else "برقرار نشد" to TextMuted
                    Text(
                        text = label,
                        color = tint,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(tint.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = when {
                    isConnected && enabled && isActive ->
                        "این نشست روی UDP در حال کار است."
                    isConnected && enabled ->
                        "درخواست شد ولی شبکه یا سرور اجازه نداد؛ نشست روی TCP ادامه دارد."
                    isConnected ->
                        "خاموش است. برای تغییر، اول اتصال را قطع کنید."
                    else ->
                        "روی شبکه‌هایی که UDP باز است سرعت را زیاد می‌کند. اگر بعد از " +
                            "روشن کردن اینترنت قطع شد، خاموشش کنید."
                },
                color = TextDim,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { if (canChange) onChange(it) },
            enabled = canChange,
            // Only the track carries the accent. Tinting the thumb the same blue left a blue
            // dot on a blue track, which reads as one flat blob with no visible knob.
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Primary,
                checkedBorderColor = Primary,
            ),
        )
    }
}

@Composable
private fun SourceBadge(source: VpnGateRepository.Source?, count: Int, error: String?) {
    val (text, color) = when (source) {
        VpnGateRepository.Source.LIVE -> "$count سرور • لیست زنده" to GreenOk
        VpnGateRepository.Source.CACHE -> "$count سرور • از حافظه" to YellowWarn
        VpnGateRepository.Source.BUNDLED -> "$count سرور • لیست همراه برنامه" to YellowWarn
        null -> (if (error != null) "خطا در دریافت لیست" else "در حال دریافت لیست…") to TextDim
    }
    Text(text, color = color, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
}

// =============================================================================================
// Server picker
// =============================================================================================

@Composable
private fun ServerPickerDialog(
    servers: List<VpnGateServer>,
    pings: Map<String, Int>,
    selectedHost: String?,
    loading: Boolean,
    onPick: (VpnGateServer) -> Unit,
    onDismiss: () -> Unit,
    /** Receives the hostnames to delete, already filtered — see the call site. */
    onPrune: (List<String>) -> Unit,
    onPingAll: (List<VpnGateServer>) -> Unit,
    onHandshakeAll: (List<VpnGateServer>) -> Unit,
    handshakes: Map<String, SoftEtherProbe.Result>,
    onOpenBrowse: () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    // Collected, not read through VpnGateSweep.isRunning(). A plain call is invisible to
    // Compose: it is fine inside an onClick (that runs on every tap) but as a visibility
    // condition it means the button never re-evaluates when a sweep ENDS. That is why the
    // delete button failed to come back after a second test — the last recomposition
    // happened when the final result landed, while the sweep was still marked running, and
    // clearing it afterwards invalidated nothing.
    val sweep by VpnGateSweep.stateFlow.collectAsState()
    // Opens on the verified ordering, so the moment a real test lands the servers that will
    // actually connect rise to the top without the user having to know to re-sort.
    var sortMode by remember { mutableStateOf(SortMode.VERIFIED) }
    var countryFilter by remember { mutableStateOf<String?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    var countryOpen by remember { mutableStateOf(false) }

    val countries = remember(servers) { servers.map { it.countryShort }.distinct().sorted() }

    val visible = remember(servers, sortMode, countryFilter, pings, handshakes) {
        servers
            .filter { countryFilter == null || it.countryShort == countryFilter }
            .sortedWith(
                when (sortMode) {
                    // Servers proven to complete a handshake first, fastest of those on top.
                    // Untested rank above ones that failed — a failure is information, an
                    // absent test is not.
                    SortMode.VERIFIED -> compareBy {
                        when (val r = handshakes[it.hostName]) {
                            is SoftEtherProbe.Result.Ok -> r.ms
                            null -> 1_000_000
                            else -> 2_000_000
                        }
                    }
                    // The project's own relays first, best-scoring within them. They are the
                    // ones on port 443 with real hardware behind them.
                    SortMode.OFFICIAL -> compareBy<VpnGateServer> { !it.isOfficialRelay }
                        .thenByDescending { it.score }
                    // Untested and unreachable servers sink to the bottom rather than
                    // masquerading as instant.
                    SortMode.PING -> compareBy {
                        val p = pings[it.hostName] ?: Int.MAX_VALUE - 1
                        if (p <= 0) Int.MAX_VALUE else p
                    }
                    SortMode.SCORE -> compareByDescending { it.score }
                    SortMode.SPEED -> compareByDescending { it.speedBps }
                    SortMode.SESSIONS -> compareByDescending { it.numSessions }
                    SortMode.COUNTRY -> compareBy({ it.countryShort }, { -it.score })
                }
            )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = BgDark,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Box so the prune button can float over the list instead of competing for room
            // in the filter row.
            Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text(
                        "انتخاب سرور",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Default.HelpOutline, contentDescription = "راهنما", tint = Primary)
                    }
                    IconButton(onClick = onOpenBrowse) {
                        Icon(Icons.Default.TravelExplore, contentDescription = "سرورهای بیشتر", tint = Primary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "بستن", tint = TextMuted)
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    Box {
                        FilterChipButton(Icons.Default.Sort, sortMode.label) { sortOpen = true }
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            SortMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = { sortMode = mode; sortOpen = false },
                                )
                            }
                        }
                    }
                    Box {
                        FilterChipButton(
                            Icons.Default.Public,
                            countryFilter?.let { "${getNodeFlagEmoji(it)} $it" } ?: "همه",
                        ) { countryOpen = true }
                        DropdownMenu(expanded = countryOpen, onDismissRequest = { countryOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("همه کشورها") },
                                onClick = { countryFilter = null; countryOpen = false },
                            )
                            countries.forEach { cc ->
                                DropdownMenuItem(
                                    text = { Text("${getNodeFlagEmoji(cc)}  $cc") },
                                    onClick = { countryFilter = cc; countryOpen = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    FilterChipButton(Icons.Default.NetworkPing, "تست پینگ") { onPingAll(visible) }
                    FilterChipButton(Icons.Default.VerifiedUser, "تست واقعی") { onHandshakeAll(visible) }
                    // The prune control used to be a third chip here and had no room — it now
                    // floats at the bottom of the dialog.
                }

                // Above the LazyColumn, so it stays fixed while the results scroll. Same
                // strip the "more servers" browser uses — one sweep, one indicator.
                VpnGateSweepStrip(modifier = Modifier.padding(bottom = 6.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    // Room for the floating prune button, so the last row isn't stuck under it.
                    contentPadding = PaddingValues(bottom = 76.dp),
                ) {
                    items(visible, key = { it.hostName }) { server ->
                        ServerRow(
                            server = server,
                            ping = pings[server.hostName],
                            handshake = handshakes[server.hostName],
                            isSelected = server.hostName == selectedHost,
                            onClick = { onPick(server) },
                        )
                    }
                }
            }

            // ---- floating prune button ------------------------------------------------
            // "Dead" means a test actually condemned the server, never merely untested —
            // otherwise the first tap would wipe most of the list. Counts BOTH result kinds:
            // whichever test the user ran is the one that has evidence, and the old code
            // looked only at pings, which is why this did nothing after a real test.
            val dead = remember(visible, pings, handshakes) {
                visible.filter {
                    handshakes[it.hostName] is SoftEtherProbe.Result.Failed ||
                        (pings[it.hostName] ?: 0) < 0
                }.map { it.hostName }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = dead.isNotEmpty() && sweep == null,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Button(
                    onClick = { onPrune(dead) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RedError),
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    // Count in the label: one tap deletes in bulk, so it must not be blind.
                    Text(
                        "حذف سرورهای قطع (${dead.size})",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            }
        }
    }

    if (showHelp) {
        ServerListHelpDialog(onDismiss = { showHelp = false })
    }
}

/**
 * Explains the list and walks through every control in the picker. Most of these concepts
 * (a handshake test that disagrees with ping, an archive that outgrows the live window) are
 * not guessable from the icons alone.
 */
@Composable
private fun ServerListHelpDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = BgDark,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.85f),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text(
                        "راهنمای لیست سرورها",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "بستن", tint = TextMuted)
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 20.dp),
                ) {
                    item {
                        Text(
                            "این سرورها داوطلبانه توسط کاربران سراسر دنیا به اشتراک گذاشته " +
                                "می‌شوند و مدام کم و زیاد می‌شوند. اپ همهٔ سرورهایی را که تا " +
                                "امروز دیده در یک آرشیو نگه می‌دارد، پس لیست شما معمولاً خیلی " +
                                "بزرگ‌تر از تعداد سرورهای زندهٔ همین لحظه است.",
                            color = TextDim,
                            fontSize = 13.sp,
                            lineHeight = 21.sp,
                        )
                    }
                    item {
                        HelpItem(
                            Icons.Default.NetworkPing,
                            "تست پینگ",
                            "فقط اندازه می‌گیرد که بستهٔ شما چقدر طول می‌کشد به سرور برسد. " +
                                "سریع است ولی تضمین نمی‌کند اتصال برقرار شود.",
                        )
                    }
                    item {
                        HelpItem(
                            Icons.Default.VerifiedUser,
                            "تست واقعی",
                            "واقعاً دست‌دادن (handshake) با سرور را انجام می‌دهد؛ یعنی نتیجهٔ " +
                                "سبز آن یعنی این سرور روی خط اینترنت شما واقعاً وصل می‌شود. " +
                                "کندتر است ولی روی خطوط ایران بسیار قابل‌اعتمادتر از پینگ است. " +
                                "اگر سروری پینگ خوبی می‌دهد ولی وصل نمی‌شود، این تست علتش را نشان می‌دهد.",
                        )
                    }
                    item {
                        HelpItem(
                            Icons.Default.Sort,
                            "مرتب‌سازی",
                            "پیش‌فرض روی «تأییدشده» است: سرورهایی که تست واقعی را رد کرده‌اند " +
                                "بالا می‌آیند. می‌توانید بر اساس پینگ، امتیاز، سرعت، تعداد " +
                                "نشست یا کشور هم مرتب کنید.",
                        )
                    }
                    item {
                        HelpItem(
                            Icons.Default.Public,
                            "فیلتر کشور",
                            "لیست را به یک کشور محدود می‌کند. برای دسترسی به سرویس‌هایی که " +
                                "به منطقه حساس‌اند مفید است.",
                        )
                    }
                    item {
                        HelpItem(
                            Icons.Default.DeleteSweep,
                            "حذف بی‌پاسخ‌ها",
                            "فقط سرورهایی را از لیست شما پاک می‌کند که تست شده‌اند و پاسخ " +
                                "نداده‌اند. سرورهای تست‌نشده هرگز حذف نمی‌شوند.",
                        )
                    }
                    item {
                        HelpItem(
                            Icons.Default.TravelExplore,
                            "سرورهای بیشتر",
                            "صفحهٔ مرور بر اساس قاره و کشور را باز می‌کند. آنجا می‌توانید " +
                                "چند کشور را با هم انتخاب کنید، همه را تست بگیرید و سالم‌ها " +
                                "را به لیست اصلی اضافه کنید.",
                        )
                    }
                    item {
                        HelpItem(
                            Icons.Default.VerifiedUser,
                            "نشان «رسمی»",
                            "سرورهایی که روی زیرساخت اصلی سرویس اجرا می‌شوند. معمولاً " +
                                "پایدارترند و پورت‌شان کمتر بسته می‌شود.",
                        )
                    }
                    item {
                        Text(
                            "پیشنهاد: اولین بار «تست واقعی» را بزنید، چند دقیقه صبر کنید، " +
                                "بعد اولین سرور سبز لیست را انتخاب کنید.",
                            color = Primary,
                            fontSize = 13.sp,
                            lineHeight = 21.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpItem(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Primary.copy(alpha = 0.14f)),
        ) {
            Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(body, color = TextDim, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun ServerRow(
    server: VpnGateServer,
    ping: Int?,
    handshake: SoftEtherProbe.Result?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Primary.copy(alpha = 0.12f) else SurfaceDark)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) Primary.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Text(getNodeFlagEmoji(server.countryShort), fontSize = 34.sp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = server.countryLong.ifBlank { server.countryShort },
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (server.isOfficialRelay) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "رسمی",
                        color = GreenOk,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(GreenOk.copy(alpha = 0.15f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            // Port, session count and advertised bandwidth are deliberately not shown. The
            // first two leak the endpoint, and the bandwidth figure is measured by VPN Gate's
            // own probes in Japan — it says nothing useful from here. All three are still
            // available as sort keys.
        }
        Spacer(Modifier.width(8.dp))
        HandshakeBadge(handshake)
        Spacer(Modifier.width(6.dp))
        PingBadge(ping)
    }
}

/**
 * Outcome of the real handshake test. Shown next to — never instead of — the ping, because the
 * whole point is that the two can disagree.
 */
@Composable
private fun HandshakeBadge(result: SoftEtherProbe.Result?) {
    if (result == null) return

    val (label, color) = when (result) {
        // Last digit dropped — see the matching note in VpnGateBrowseScreen.HandshakeText.
        // Kept identical here so the same server doesn't show two different numbers.
        is SoftEtherProbe.Result.Ok -> "✓ ${result.ms / 10}" to GreenOk
        is SoftEtherProbe.Result.Failed -> when (result.reason) {
            SoftEtherProbe.Failure.UNREACHABLE -> "بسته" to RedError
            SoftEtherProbe.Failure.TLS_BLOCKED -> "TLS" to RedError
            SoftEtherProbe.Failure.NOT_SOFTETHER -> "غیرفعال" to YellowWarn
            SoftEtherProbe.Failure.REFUSED -> "رد شد" to RedError
            SoftEtherProbe.Failure.TIMEOUT -> "بی‌پاسخ" to RedError
        }
    }

    Text(
        text = label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun PingBadge(ping: Int?) {
    val text = when {
        ping == null -> "—"
        ping > 0 -> "$ping"
        else -> "✕"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(46.dp)) {
        Text(text, color = pingColor(ping), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (ping != null && ping > 0) Text("ms", color = TextDim, fontSize = 8.sp)
    }
}

@Composable
private fun FilterChipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = TextPrimary, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, color = TextMuted, fontSize = 10.sp, maxLines = 1)
    }
}

// =============================================================================================

private fun pingColor(ping: Int?): Color = when {
    ping == null -> TextDim
    ping <= 0 -> RedError
    ping < 150 -> GreenOk
    ping < 400 -> YellowWarn
    else -> RedError
}

private fun formatSpeed(bps: Long): String {
    val mbps = bps * 8.0 / 1_000_000.0
    return if (mbps >= 10) "${mbps.toInt()} Mbps" else String.format("%.1f Mbps", mbps)
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
