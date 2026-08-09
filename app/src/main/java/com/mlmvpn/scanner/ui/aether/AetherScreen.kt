package com.mlmvpn.scanner.ui.aether

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mlmvpn.core.aether.AetherEngine
import com.mlmvpn.core.aether.AetherIp
import com.mlmvpn.core.aether.AetherOptions
import com.mlmvpn.core.aether.AetherProtocol
import com.mlmvpn.core.aether.AetherScan
import com.mlmvpn.core.aether.AetherStage
import com.mlmvpn.core.aether.AetherState
import kotlinx.coroutines.launch

/**
 * Compose UI for the Aether engine on Android.
 *
 * Mirrors public/components/aether.js (the desktop panel) one-for-one:
 *   - protocol tabs on top — of the three the desktop panel shows, only MASQUE is
 *     user-visible here (see AetherProtocol.userVisible for why), so the row hides itself
 *   - per-tab settings pane (transport, fragment, noize, ...)
 *   - shared settings card (scan mode, IP family, quick reconnect, verbose)
 *   - live status card with steps + server/RTT/profile
 *   - one primary "connect" button that flips to "توقف" when running
 *
 * The flow is purely local: state comes from [AetherEngine.state], which the service
 * owns. UI control translates to one [AetherOptions] struct, which the service hands to
 * the Rust binary.
 */
@Composable
fun AetherScreen() {
    val context = LocalContext.current
    val engine = remember { AetherEngine.get(context) }
    val state by engine.state.collectAsStateWithLifecycle()

    // Announce terminal outcomes. The status card shows the stage, but a user who tapped
    // connect and looked away — a scan can run for minutes — needs something that reaches
    // them without staring at the card. Keyed on stage so it fires once per transition,
    // not on every state copy (server/RTT/bytes all mutate the same object constantly).
    LaunchedEffect(state.stage) {
        when (state.stage) {
            AetherStage.CONNECTED -> Toast.makeText(
                context,
                "متصل شد${state.server?.let { " • $it" } ?: ""}",
                Toast.LENGTH_SHORT,
            ).show()
            // `stageFa` carries the headline (AetherState.failed puts the message there);
            // `error` is the supporting detail, which is often empty.
            AetherStage.FAILED, AetherStage.CRASHED -> Toast.makeText(
                context,
                state.stageFa + (state.error?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
                Toast.LENGTH_LONG,
            ).show()
            else -> Unit
        }
    }

    var activeTab by remember { mutableStateOf(AetherProtocol.MASQUE) }

    // Per-tab settings (remembered across recompositions, process-scope).
    var transport by remember { mutableStateOf("h3") }
    var fragment by remember { mutableStateOf(false) }
    var fragmentSize by remember { mutableStateOf("") }
    var fragmentDelay by remember { mutableStateOf("") }
    var ech by remember { mutableStateOf("") }
    var wgNoize by remember { mutableStateOf("balanced") }
    var wgRetry by remember { mutableStateOf(true) }
    var keepalive by remember { mutableStateOf(5) }

    // Shared
    var scanMode by remember { mutableStateOf(AetherScan.TURBO) }
    var ipFamily by remember { mutableStateOf(AetherIp.V4) }
    var quick by remember { mutableStateOf(true) }
    var verbose by remember { mutableStateOf(false) }
    var noDataCheck by remember { mutableStateOf(false) }

    // Full-device tunnel plumbing.
    //
    // Connecting routes the WHOLE device through Aether (VpnService TUN → tun2proxy →
    // the engine's SOCKS5), matching the Windows app. Previously this screen only started
    // AetherScanService, which publishes a SOCKS5 listener on 127.0.0.1 and nothing more —
    // usable only by pointing a separate proxy app at it.
    //
    // Requesting the VPN permission is an Activity result, so the config is parked in
    // `pendingConfig` across the round trip and sent once the user grants it.
    var pendingConfig by remember { mutableStateOf<String?>(null) }

    fun startTunnel(cfg: String) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val intent = android.content.Intent(context, com.mlmvpn.scanner.MyVpnService::class.java).apply {
            putExtra("NODE_URI", cfg)
            putExtra("NODE_ID", "aether")
            // Always false: a proxy-mode Aether connect is what AetherScanService is for.
            // Forwarding the global proxy_mode preference here would silently give the user
            // no TUN and no explanation.
            putExtra("PROXY_MODE", false)
            putExtra("LOCAL_PORT", prefs.getString("local_port", "10808"))
        }
        context.startService(intent)
    }

    val vpnPrepareLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            pendingConfig?.let { startTunnel(it) }
        } else {
            Toast.makeText(context, "بدون اجازهٔ VPN، تونل کل دستگاه ممکن نیست", Toast.LENGTH_LONG).show()
        }
        pendingConfig = null
    }

    var showAdvanced by remember { mutableStateOf(false) }

    // Automatic recovery. Both of these were things a user had to know to do by hand, and
    // neither is guessable from the failure the screen shows. Each is attempted once per
    // session so a genuinely dead network still fails instead of looping.
    var triedIdentityReset by remember { mutableStateOf(false) }
    var triedH2Fallback by remember { mutableStateOf(false) }
    var autoRetryNote by remember { mutableStateOf<String?>(null) }
    /** A run the user ended themselves must not be "recovered" from. */
    var userStopped by remember { mutableStateOf(false) }

    fun doConnect() {
        userStopped = false
        val opts = AetherOptions(
            protocol = activeTab,
            scan = scanMode,
            ipFamily = ipFamily,
            transport = transport,
            ech = ech,
            fragment = fragment,
            fragmentSize = fragmentSize.toIntOrNull(),
            fragmentDelay = fragmentDelay.toIntOrNull(),
            noize = when (activeTab) {
                AetherProtocol.MASQUE -> if (transport == "h3") "firewall" else "balanced"
                AetherProtocol.WG -> wgNoize
                AetherProtocol.WARP_IN_WARP -> wgNoize
            },
            keepalive = keepalive,
            noProfileRetry = !wgRetry,
            quickReconnect = quick,
            verbose = verbose,
            noDataCheck = noDataCheck,
        )
        val cfg = com.mlmvpn.core.aether.AetherTunEngine.buildConfig(opts)
        pendingConfig = cfg
        val prep = try { android.net.VpnService.prepare(context) } catch (e: Exception) { null }
        if (prep != null) {
            vpnPrepareLauncher.launch(prep)
        } else {
            startTunnel(cfg)
            pendingConfig = null
        }
    }

    fun doStop() {
        userStopped = true
        // MyVpnService is the only owner of the engine now that connecting always goes
        // through the full-device tunnel, so stopping it is the whole job.
        //
        // Do NOT also poke AetherScanService here. Its stop() goes through startService,
        // which *creates* the service (posting a foreground notification) purely to tear it
        // down, and its ACTION_STOP calls engine.stop() on the shared singleton concurrently
        // with the one AetherTunEngine.stop() is already running — two threads through the
        // same process teardown, for no gain.
        context.startService(
            android.content.Intent(context, com.mlmvpn.scanner.MyVpnService::class.java)
                .apply { action = "STOP" }
        )
    }

    // Reset the one-shot guards whenever the user starts a run themselves.
    LaunchedEffect(state.running) {
        if (state.running) {
            autoRetryNote = null
        }
    }

    // Trigger on the run ENDING without a connection, not on a FAILED stage.
    //
    // When the gateway hunt runs out of budget, AetherTunEngine aborts the tunnel and
    // MyVpnService tears the engine down — the state lands on STOPPED, never FAILED. Keying
    // this on FAILED is why the h3 → h2 fallback never fired.
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(state.running, state.connected, state.stage) {
        val ended = wasRunning && !state.running
        android.util.Log.d(
            "AetherRetry",
            "stage=${state.stage} running=${state.running} connected=${state.connected} " +
                "ended=$ended noGw=${state.sawNoGateway} denied=${state.sawAccessDenied} " +
                "transport=$transport tab=$activeTab triedH2=$triedH2Fallback triedId=$triedIdentityReset"
        )
        wasRunning = state.running
        if (!ended || state.connected || userStopped) return@LaunchedEffect

        when {
            // Cloudflare accepted the TLS connection and refused the session: the stored
            // MASQUE identity is stale. Re-enrol and dial again.
            state.sawAccessDenied && !triedIdentityReset -> {
                triedIdentityReset = true
                val n = engine.resetIdentity()
                autoRetryNote = "هویت پذیرفته نشد — هویت تازه ساخته شد ($n فایل) و دوباره تلاش می‌کنم"
                kotlinx.coroutines.delay(1200)
                doConnect()
            }

            // An h3 run that ends without connecting is reason enough to try h2, whatever the
            // engine did or didn't manage to print.
            //
            // This used to also require sawNoGateway, and that is why it never fired: when the
            // gateway hunt overruns, AetherTunEngine aborts on its own 90-second budget
            // ("give up after 90234ms: budget exhausted") while the prober is still scanning,
            // so the engine never reaches the line that would have set the flag.
            activeTab == AetherProtocol.MASQUE && transport == "h3" && !triedH2Fallback -> {
                triedH2Fallback = true
                transport = "h2"
                autoRetryNote = "با HTTP/3 سروری پیدا نشد — احتمالاً UDP بسته است؛ خودکار HTTP/2 را امتحان می‌کنم"
                kotlinx.coroutines.delay(1200)
                doConnect()
            }
        }
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        // bottom = 100.dp clears the floating bottom nav (≈78dp) so the connect/disconnect
        // buttons never hide under it. Matches the app-wide convention used everywhere else.
        .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 100.dp)) {

        // Title + protocol tabs, kept compact so the button dominates the screen.
        Text(
            "موتور وایرگارد",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // TabRow owns the gap above it, so that when it draws nothing (single protocol)
        // the title isn't left with a stray 10.dp of padding under it.
        TabRow(active = activeTab, onSelect = { activeTab = it })

        Spacer(modifier = Modifier.weight(1f))

        // The button, and only the button. Everything that used to compete with it for
        // attention — five settings cards stacked above a small text button — now lives
        // behind "تنظیمات پیشرفته".
        AetherPowerButton(
            state = state,
            onClick = { if (state.running) doStop() else doConnect() },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(modifier = Modifier.height(16.dp))

        AetherStatusLine(state, modifier = Modifier.align(Alignment.CenterHorizontally))

        autoRetryNote?.also { note ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = note,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // The two or three settings that actually decide whether a connection succeeds sit
        // directly under the button; everything else is one tap away.
        QuickSettings(
            protocol = activeTab,
            transport = transport, onTransport = { transport = it },
            scan = scanMode, onScan = { scanMode = it },
            running = state.running,
            onOpenAdvanced = { showAdvanced = true },
            onReset = {
                val n = engine.resetIdentity()
                Toast.makeText(context, "هویت‌ها پاک شد ($n فایل)", Toast.LENGTH_SHORT).show()
            },
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Progress lives at the bottom, out of the way until something is happening.
        AetherProgressStrip(state, activeTab)
    }

    if (showAdvanced) {
        AdvancedSettingsDialog(
            protocol = activeTab,
            onDismiss = { showAdvanced = false },
            engine = engine,
            fragment = fragment, onFragment = { fragment = it },
            fragmentSize = fragmentSize, onFragmentSize = { fragmentSize = it },
            fragmentDelay = fragmentDelay, onFragmentDelay = { fragmentDelay = it },
            ech = ech, onEch = { ech = it },
            wgNoize = wgNoize, onWgNoize = { wgNoize = it },
            wgRetry = wgRetry, onWgRetry = { wgRetry = it },
            keepalive = keepalive, onKeepalive = { keepalive = it },
            ip = ipFamily, onIp = { ipFamily = it },
            quick = quick, onQuick = { quick = it },
            verbose = verbose, onVerbose = { verbose = it },
            noDataCheck = noDataCheck, onNoDataCheck = { noDataCheck = it },
        )
    }
}


/**
 * The single control that matters, sized to say so. Same shape and behaviour as the GATE
 * screen's, so the two engines feel like one app.
 */
@Composable
private fun AetherPowerButton(
    state: AetherState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (state.stage) {
        AetherStage.CONNECTED -> Color(0xFF3ba55d)
        AetherStage.FAILED, AetherStage.CRASHED -> Color(0xFFff6b6b)
        AetherStage.STOPPED, AetherStage.IDLE -> MaterialTheme.colorScheme.primary
        else -> Color(0xFFFDE293)
    }
    val working = state.running && state.stage != AetherStage.CONNECTED

    val transition = rememberInfiniteTransition(label = "aetherPower")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (working) 900 else 2200, easing = FastOutSlowInEasing),
            repeatMode = if (working) RepeatMode.Restart else RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val active = state.running
    val haloScale = if (active) (if (working) 1f + pulse * 0.35f else 1.06f + pulse * 0.06f) else 1f
    val haloAlpha = if (active) (if (working) (1f - pulse) * 0.35f else 0.12f + pulse * 0.10f) else 0.06f

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
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
                        listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.65f))
                    )
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = if (state.running) "قطع اتصال" else "اتصال",
                tint = Color(0xFF121212),
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

@Composable
private fun AetherStatusLine(state: AetherState, modifier: Modifier = Modifier) {
    val color = when (state.stage) {
        AetherStage.CONNECTED -> Color(0xFF3ba55d)
        AetherStage.FAILED, AetherStage.CRASHED -> Color(0xFFff6b6b)
        AetherStage.STOPPED, AetherStage.IDLE -> Color(0xFF9AA0A6)
        else -> Color(0xFFFDE293)
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(state.stageFa, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        state.error?.takeIf { it.isNotBlank() }?.also {
            Text(
                text = it,
                color = Color(0xFFff9090),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

/**
 * The settings that decide whether a connection happens at all, kept on the main screen:
 * transport for MASQUE (h3 fails outright wherever UDP is filtered) and the scan mode.
 * Everything else is behind "تنظیمات پیشرفته".
 */
@Composable
private fun QuickSettings(
    protocol: AetherProtocol,
    transport: String, onTransport: (String) -> Unit,
    scan: AetherScan, onScan: (AetherScan) -> Unit,
    running: Boolean,
    onOpenAdvanced: () -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (protocol == AetherProtocol.MASQUE) {
            QuickRow("ترنسپورت") {
                MiniSegmented(
                    options = listOf("h3" to "HTTP/3", "h2" to "HTTP/2"),
                    selected = transport,
                    enabled = !running,
                    onSelected = onTransport,
                )
            }
        }

        QuickRow("حالت اسکن") {
            MiniSegmented(
                // The enum's displayFa is a full sentence — fine in a dropdown, far too long
                // for a segment. These are the same three modes, named short.
                options = listOf(
                    AetherScan.TURBO.name to "توربو",
                    AetherScan.BALANCED.name to "متعادل",
                    AetherScan.THOROUGH.name to "کامل",
                ),
                selected = scan.name,
                enabled = !running,
                onSelected = { v -> AetherScan.values().firstOrNull { it.name == v }?.let(onScan) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonLite(
                text = "تنظیمات پیشرفته",
                color = Color(0xFF2a2a2a),
                onClick = onOpenAdvanced,
                modifier = Modifier.weight(1f),
            )
            ButtonLite(
                text = "پاک کردن هویت",
                color = Color(0xFF2a2a2a),
                onClick = onReset,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Label above its control: a fixed-width label beside it collapses badly in RTL. */
@Composable
private fun QuickRow(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 11.sp, color = Color(0xFF9a97a3))
        Spacer(modifier = Modifier.height(6.dp))
        content()
    }
}

/** Segments share the width evenly, so the control reads as one bar rather than loose chips. */
@Composable
private fun MiniSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF1e1f22))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(enabled = enabled) { onSelected(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        on -> Color.White
                        !enabled -> Color(0xFF5F6368)
                        else -> Color(0xFF9a97a3)
                    },
                )
            }
        }
    }
}

/**
 * Compact progress at the foot of the screen: one dot per stage, so the six-row checklist
 * that used to sit above the button becomes a single line that is ignorable when idle.
 */
@Composable
private fun AetherProgressStrip(state: AetherState, protocol: AetherProtocol) {
    val steps = stagesFor(protocol)
    val current = state.stage.forUi()
    val activeIdx = if (current == null) -1 else steps.indexOfFirst { it.first == current }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEachIndexed { idx, _ ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            when {
                                activeIdx > idx -> Color(0xFF3ba55d)
                                activeIdx == idx -> MaterialTheme.colorScheme.primary
                                else -> Color(0xFF2a2a2a)
                            }
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = steps.getOrNull(activeIdx)?.second ?: "آماده",
            fontSize = 10.sp,
            color = Color(0xFF5F6368),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

private fun stagesFor(protocol: AetherProtocol): List<Pair<String, String>> = when (protocol) {
    AetherProtocol.MASQUE -> listOf(
        "identity" to "هویت", "quickcheck" to "بررسی سرور قبلی",
        "scan" to "اسکن گیت‌وی", "selected" to "انتخاب سرور",
        "tunnel" to "برقراری تونل", "validate" to "اعتبارسنجی عبور داده",
        "connected" to "اتصال",
    )
    AetherProtocol.WG -> listOf(
        "identity" to "هویت", "quickcheck" to "بررسی سرور قبلی",
        "scan" to "اسکن اندپوینت", "selected" to "انتخاب سرور",
        "handshake" to "دست‌دادن", "connected" to "اتصال",
    )
    AetherProtocol.WARP_IN_WARP -> listOf(
        "identity" to "هویت دوگانه", "scan" to "اسکن اندپوینت",
        "selected" to "انتخاب سرور", "handshake" to "تونل بیرونی + داخلی",
        "connected" to "اتصال",
    )
}

/** Everything that isn't needed to get connected, in a sheet instead of down the screen. */
@Composable
private fun AdvancedSettingsDialog(
    protocol: AetherProtocol,
    onDismiss: () -> Unit,
    engine: AetherEngine,
    fragment: Boolean, onFragment: (Boolean) -> Unit,
    fragmentSize: String, onFragmentSize: (String) -> Unit,
    fragmentDelay: String, onFragmentDelay: (String) -> Unit,
    ech: String, onEch: (String) -> Unit,
    wgNoize: String, onWgNoize: (String) -> Unit,
    wgRetry: Boolean, onWgRetry: (Boolean) -> Unit,
    keepalive: Int, onKeepalive: (Int) -> Unit,
    ip: AetherIp, onIp: (AetherIp) -> Unit,
    quick: Boolean, onQuick: (Boolean) -> Unit,
    verbose: Boolean, onVerbose: (Boolean) -> Unit,
    noDataCheck: Boolean, onNoDataCheck: (Boolean) -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "تنظیمات پیشرفته",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    // A real icon button: ButtonLite without a modifier has no horizontal
                    // padding of its own, so it collapsed to an unhittable sliver.
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "بستن",
                            tint = Color(0xFF9a97a3),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (protocol) {
                        AetherProtocol.MASQUE -> MasqueAdvancedPane(
                            fragment = fragment, onFragment = onFragment,
                            fragmentSize = fragmentSize, onFragmentSize = onFragmentSize,
                            fragmentDelay = fragmentDelay, onFragmentDelay = onFragmentDelay,
                            ech = ech, onEch = onEch,
                        )
                        AetherProtocol.WG -> WgPane(
                            noize = wgNoize, onNoize = onWgNoize,
                            retry = wgRetry, onRetry = onWgRetry,
                            keepalive = keepalive.toString(),
                            onKeepalive = { s -> onKeepalive(s.toIntOrNull() ?: 5) },
                        )
                        AetherProtocol.WARP_IN_WARP -> GooLPane(
                            noize = wgNoize, onNoize = onWgNoize,
                            keepalive = keepalive.toString(),
                            onKeepalive = { s -> onKeepalive(s.toIntOrNull() ?: 5) },
                        )
                    }
                    SharedAdvancedPane(
                        ip = ip, onIp = onIp,
                        quick = quick, onQuick = onQuick,
                        verbose = verbose, onVerbose = onVerbose,
                        noDataCheck = noDataCheck, onNoDataCheck = onNoDataCheck,
                    )
                    if (verbose) LogCard(engine)
                }
            }
        }
    }
}

@Composable
private fun HeaderCard() {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1a2233))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                Spacer(modifier = Modifier.width(8.dp))
                Text("موتور وایرگارد", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "موتور دور زدن سانسور مبتنی بر WARP کلادفلر برای شبکه‌های به‌شدت فیلترشده. " +
                    "مثل نسخه‌ی ویندوز، سرور سالم را پیدا می‌کند، تونل رمزنگاری‌شده می‌سازد و " +
                    "پراکسی SOCKS5 روی 127.0.0.1:${AetherEngine.AETHER_SOCKS_PORT} در دسترس قرار می‌دهد.",
                fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFFc9c5d0)
            )
        }
    }
}

@Composable
private fun TabRow(active: AetherProtocol, onSelect: (AetherProtocol) -> Unit) {
    val visible = AetherProtocol.values().filter { it.userVisible }
    // A tab row offering a single choice is just a decorative label — and worse, it reads
    // as a control the user could switch. Draw nothing (not even the Spacer around it)
    // until a second protocol becomes user-visible again.
    if (visible.size < 2) return
    Spacer(modifier = Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.forEach { proto ->
            val selected = proto == active
            AssistChip(
                onClick = { onSelect(proto) },
                label = { Text(proto.displayFa, fontSize = 12.sp) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF1e1f22),
                    labelColor = if (selected) Color.White else Color(0xFF9a97a3)
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MasquePane(
    transport: String, onTransport: (String) -> Unit,
    fragment: Boolean, onFragment: (Boolean) -> Unit,
    fragmentSize: String, onFragmentSize: (String) -> Unit,
    fragmentDelay: String, onFragmentDelay: (String) -> Unit,
    ech: String, onEch: (String) -> Unit,
) {
    SettingsCard {
        SectionTitle("ترنسپورت")
        SegmentedControl(options = listOf("h3" to "HTTP/3 (QUIC)", "h2" to "HTTP/2 (TCP)"),
            selected = transport, onSelected = onTransport)
        SectionHint("اگر UDP محدود شده، HTTP/2 را انتخاب کن.")

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("فرگمنت ClientHello (فقط روی HTTP/2)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = fragment, onCheckedChange = onFragment,
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (fragment) "فعال" else "غیرفعال", fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextFieldLite(value = fragmentSize, onValueChange = onFragmentSize,
                placeholder = "اندازه 16-32", modifier = Modifier.weight(1f))
            TextFieldLite(value = fragmentDelay, onValueChange = onFragmentDelay,
                placeholder = "تأخیر 2-10", modifier = Modifier.weight(1f))
        }
        SectionHint("دست‌دادن TLS را تکه‌تکه می‌فرستد تا DPI نتواند SNI را بخواند.")

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("ECH (رمزنگاری SNI)")
        DropdownLite(selected = ech, options = listOf("" to "خاموش", "auto" to "خودکار"),
            onSelected = onEch)
        SectionHint("اندپوینت MASQUE کلادفلر معمولاً ECH نمی‌پذیرد؛ خاموش بگذارید.")
    }
}

@Composable
private fun WgPane(
    noize: String, onNoize: (String) -> Unit,
    retry: Boolean, onRetry: (Boolean) -> Unit,
    keepalive: String, onKeepalive: (String) -> Unit,
) {
    SettingsCard {
        SectionTitle("پروفایل AetherNoize")
        DropdownLite(selected = noize,
            options = listOf("balanced" to "متعادل (پیش‌فرض)", "off" to "خاموش",
                "light" to "سبک", "aggressive" to "تهاجمی (GFW)"),
            onSelected = onNoize)
        SectionHint("بسته‌های جعلی اضافه می‌کند تا الگوی WireGuard شناسایی نشود.")

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("تلاش خودکار با پروفایل‌های دیگر")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = retry, onCheckedChange = onRetry)
            Spacer(modifier = Modifier.width(6.dp))
            Text("اگر پروفایل اول جواب نداد، بقیه را امتحان کن", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("Keepalive (ثانیه)")
        TextFieldLite(value = keepalive, onValueChange = onKeepalive,
            placeholder = "5", modifier = Modifier.fillMaxWidth())
        SectionHint("عدد کمتر = پایدارتر پشت NAT، مصرف کمی بیشتر.")
    }
}

@Composable
private fun GooLPane(
    noize: String, onNoize: (String) -> Unit,
    keepalive: String, onKeepalive: (String) -> Unit,
) {
    SettingsCard {
        Text("یک تونل WireGuard داخل تونل WireGuard دیگر — یک لایه رمزنگاری اضافه.",
            fontSize = 11.sp, color = Color(0xFF9a97a3), lineHeight = 17.sp)
        Spacer(modifier = Modifier.height(8.dp))
        WarningCard(text = "⚠️ این حالت دو هویت کلادفلر می‌سازد و سرعت را کاهش می‌دهد. اگر MASQUE کار می‌کند، آن را ترجیح دهید.")
        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("پروفایل AetherNoize (تونل بیرونی)")
        DropdownLite(selected = noize,
            options = listOf("balanced" to "متعادل (پیش‌فرض)", "off" to "خاموش",
                "light" to "سبک", "aggressive" to "تهاجمی"),
            onSelected = onNoize)
        SectionHint("تونل داخلی همیشه بدون مبهم‌سازی است.")
        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("Keepalive تونل بیرونی (ثانیه)")
        TextFieldLite(value = keepalive, onValueChange = onKeepalive,
            placeholder = "5", modifier = Modifier.fillMaxWidth())
    }
}

/** MASQUE settings minus the transport, which is now a quick setting under the button. */
@Composable
private fun MasqueAdvancedPane(
    fragment: Boolean, onFragment: (Boolean) -> Unit,
    fragmentSize: String, onFragmentSize: (String) -> Unit,
    fragmentDelay: String, onFragmentDelay: (String) -> Unit,
    ech: String, onEch: (String) -> Unit,
) {
    SettingsCard {
        SectionTitle("فرگمنت ClientHello (فقط روی HTTP/2)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = fragment, onCheckedChange = onFragment,
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (fragment) "فعال" else "غیرفعال", fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextFieldLite(value = fragmentSize, onValueChange = onFragmentSize,
                placeholder = "اندازه 16-32", modifier = Modifier.weight(1f))
            TextFieldLite(value = fragmentDelay, onValueChange = onFragmentDelay,
                placeholder = "تأخیر 2-10", modifier = Modifier.weight(1f))
        }
        SectionHint("دست‌دادن TLS را تکه‌تکه می‌فرستد تا DPI نتواند SNI را بخواند.")

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("ECH (رمزنگاری SNI)")
        DropdownLite(selected = ech, options = listOf("" to "خاموش", "auto" to "خودکار"),
            onSelected = onEch)
        SectionHint("اندپوینت MASQUE کلادفلر معمولاً ECH نمی‌پذیرد؛ خاموش بگذارید.")
    }
}

/** Shared settings minus the scan mode, which is now a quick setting under the button. */
@Composable
private fun SharedAdvancedPane(
    ip: AetherIp, onIp: (AetherIp) -> Unit,
    quick: Boolean, onQuick: (Boolean) -> Unit,
    verbose: Boolean, onVerbose: (Boolean) -> Unit,
    noDataCheck: Boolean, onNoDataCheck: (Boolean) -> Unit,
) {
    SettingsCard(title = "تنظیمات مشترک") {
        SectionTitle("نسخه IP")
        DropdownLiteE(selected = ip, options = AetherIp.values().toList(),
            label = { it.displayFa }, onSelected = onIp)

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("اتصال سریع به سرور قبلی")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = quick, onCheckedChange = onQuick)
            Spacer(modifier = Modifier.width(6.dp))
            Text("اگر سرور قبلی سالم بود، اسکن را رد کن", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("پذیرش سرور بدون تست عبور داده")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = noDataCheck, onCheckedChange = onNoDataCheck)
            Spacer(modifier = Modifier.width(6.dp))
            Text("سرور را فقط با دست‌دادن موفق بپذیر", fontSize = 12.sp)
        }
        SectionHint(
            "به‌طور پیش‌فرض، سرور تا وقتی واقعاً داده رد و بدل نکند پذیرفته نمی‌شود. " +
                "اگر همهٔ سرورها با «closed before data-plane confirmation» رد می‌شوند، " +
                "این را روشن کنید: اگر بعدش وصل شد ولی چیزی باز نشد، یعنی شبکه دادهٔ داخل " +
                "تونل را می‌اندازد و باید h2 یا وایرگارد را امتحان کنید."
        )

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("لاگ کامل (debug)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = verbose, onCheckedChange = onVerbose)
            Spacer(modifier = Modifier.width(6.dp))
            Text("جزئیات بیشتر در لاگ هسته + نمایش باکس لاگ", fontSize = 12.sp)
        }
        SectionHint("فقط برای عیب‌یابی روشن کنید.")
    }
}

@Composable
private fun SharedPane(
    scan: AetherScan, onScan: (AetherScan) -> Unit,
    ip: AetherIp, onIp: (AetherIp) -> Unit,
    quick: Boolean, onQuick: (Boolean) -> Unit,
    verbose: Boolean, onVerbose: (Boolean) -> Unit,
    noDataCheck: Boolean, onNoDataCheck: (Boolean) -> Unit,
) {
    SettingsCard(title = "تنظیمات مشترک") {
        SectionTitle("حالت اسکن")
        DropdownLiteE(selected = scan, options = AetherScan.values().toList(),
            label = { it.displayFa }, onSelected = onScan)
        SectionHint("«متعادل» بعد از پیدا کردن اولین سرور متوقف نمی‌شود؛ می‌گردد تا ۶ تا پیدا کند.")

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("نسخه IP")
        DropdownLiteE(selected = ip, options = AetherIp.values().toList(),
            label = { it.displayFa }, onSelected = onIp)

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("اتصال سریع به سرور قبلی")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = quick, onCheckedChange = onQuick)
            Spacer(modifier = Modifier.width(6.dp))
            Text("اگر سرور قبلی سالم بود، اسکن را رد کن", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("پذیرش سرور بدون تست عبور داده")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = noDataCheck, onCheckedChange = onNoDataCheck)
            Spacer(modifier = Modifier.width(6.dp))
            Text("سرور را فقط با دست‌دادن موفق بپذیر", fontSize = 12.sp)
        }
        SectionHint(
            "به‌طور پیش‌فرض، سرور تا وقتی واقعاً داده رد و بدل نکند پذیرفته نمی‌شود. " +
                "اگر همهٔ سرورها با «closed before data-plane confirmation» رد می‌شوند، " +
                "این را روشن کنید: اگر بعدش وصل شد ولی چیزی باز نشد، یعنی شبکه دادهٔ داخل " +
                "تونل را می‌اندازد و باید h2 یا وایرگارد را امتحان کنید."
        )

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("لاگ کامل (debug)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = verbose, onCheckedChange = onVerbose)
            Spacer(modifier = Modifier.width(6.dp))
            Text("جزئیات بیشتر در لاگ هسته + نمایش باکس لاگ", fontSize = 12.sp)
        }
        SectionHint("فقط برای عیب‌یابی روشن کنید.")
    }
}

/**
 * Live view of the engine's own output.
 *
 * The lines are the real thing: whatever the Rust process wrote to stdout/stderr, minus the
 * per-packet noise the parser filters out. Seeded from [AetherEngine.logSnapshot] because
 * [AetherEngine.logs] is replay-0 — attaching mid-scan would otherwise show an empty box
 * until the next line lands.
 *
 * Capped at [MAX_UI_LINES]: the buffer behind it holds 2000, but a Compose list that long
 * costs more to diff than it's worth on a phone, and only the tail is ever interesting.
 */
@Composable
private fun LogCard(engine: AetherEngine) {
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    // Buffer arrivals off the UI list and flush on a fixed cadence.
    //
    // The engine can emit hundreds of lines per second during a scan. Appending each one
    // straight to a SnapshotStateList recomposes the list and re-runs the autoscroll per
    // line, which saturates the main thread and makes the whole app stutter — including
    // traffic, because the UI thread is what feeds the notification and state collectors.
    // Draining on an interval bounds that to a few frames per second no matter how loud
    // the engine gets.
    LaunchedEffect(engine) {
        val pending = java.util.concurrent.ConcurrentLinkedQueue<String>()
        // Snapshot before subscribing. The other order would replay anything that arrived
        // between the two as a duplicate; this way such a line is simply missed, which is
        // the better failure for a scrolling diagnostic view.
        lines.addAll(engine.logSnapshot().takeLast(MAX_UI_LINES))
        val collector = launch {
            // Plain collect, not collectLatest: every line matters here, and collectLatest
            // would cancel and restart the block on each emission for no benefit.
            engine.logs.collect { pending.add(it) }
        }
        try {
            while (true) {
                kotlinx.coroutines.delay(FLUSH_INTERVAL_MS)
                if (pending.isEmpty()) continue
                val batch = ArrayList<String>(pending.size)
                while (true) batch.add(pending.poll() ?: break)
                if (batch.isEmpty()) continue
                lines.addAll(batch)
                if (lines.size > MAX_UI_LINES) lines.removeRange(0, lines.size - MAX_UI_LINES)
                // Follow the tail, but only while the user is already near it — otherwise
                // scrolling back to read something would fight the autoscroll.
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (last >= lines.size - batch.size - 3) {
                    listState.scrollToItem(lines.size - 1)
                }
            }
        } finally {
            collector.cancel()
        }
    }

    SettingsCard(title = "لاگ هسته") {
        if (lines.isEmpty()) {
            Text("هنوز خروجی‌ای نیست. برای دیدن لاگ، اتصال را شروع کنید.",
                fontSize = 11.sp, color = Color(0xFF7a7783), lineHeight = 17.sp)
        } else {
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color(0xFF0e0f12), RoundedCornerShape(8.dp))
                .padding(8.dp)) {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(lines) { line ->
                        Text(
                            line,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            // Colour by severity so a failure stands out without reading
                            // every line. Matches the stage colours used in StatusCard.
                            color = when {
                                line.contains("panic", true) || line.contains("error", true) ||
                                    line.contains("failed", true) || line.contains("خطا") ->
                                    Color(0xFFff9090)
                                line.contains("connected", true) || line.contains("متصل") ->
                                    Color(0xFF7ee787)
                                line.startsWith("[AETHER]") -> Color(0xFF9ecbff)
                                else -> Color(0xFFb8b5c0)
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_UI_LINES = 300

/** How often the log box redraws, at most. 4 fps is plenty for reading scrolling text. */
private const val FLUSH_INTERVAL_MS = 250L

@Composable
private fun StatusCard(state: AetherState, @Suppress("UNUSED_PARAMETER") protocol: AetherProtocol) {
    // The gateway scan is a real search, not a hang -- it can legitimately run for tens of
    // seconds to a few minutes (scan-mode dependent), and on networks that DPI-block MASQUE it
    // runs the full budget before failing. With nothing but a static "در حال کار" badge, that
    // reads as frozen. A ticking counter plus a delayed hint gives the user something to watch
    // and, past the point most successful scans have already resolved, an honest explanation
    // for why it might still be going.
    var scanElapsedSec by remember { mutableStateOf(0) }
    LaunchedEffect(state.stage) {
        if (state.stage == AetherStage.SCAN) {
            scanElapsedSec = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                scanElapsedSec++
            }
        }
    }
    SettingsCard(title = "وضعیت") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.stageFa, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = when (state.stage) {
                    AetherStage.CONNECTED -> Color(0xFF3ba55d)
                    AetherStage.FAILED, AetherStage.CRASHED -> Color(0xFFff6b6b)
                    else -> MaterialTheme.colorScheme.onSurface
                })
            Spacer(modifier = Modifier.weight(1f))
            val badgeColor = when (state.stage) {
                AetherStage.CONNECTED -> Color(0xFF1b3a22)
                AetherStage.FAILED, AetherStage.CRASHED -> Color(0xFF3b1b1b)
                else -> Color(0xFF2a2a2a)
            }
            val badgeText = when (state.stage) {
                AetherStage.CONNECTED -> "متصل"
                AetherStage.STARTING, AetherStage.IDENTITY, AetherStage.QUICKCHECK,
                AetherStage.SCAN, AetherStage.SELECTED, AetherStage.HANDSHAKE,
                AetherStage.TUNNEL, AetherStage.VALIDATE, AetherStage.RECONNECTING -> "در حال کار"
                AetherStage.FAILED, AetherStage.CRASHED -> "خطا"
                AetherStage.STOPPED, AetherStage.IDLE -> "خاموش"
            }
            Box(modifier = Modifier
                .background(badgeColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text(badgeText, fontSize = 11.sp,
                    color = when {
                        state.stage == AetherStage.CONNECTED -> Color(0xFF7ee787)
                        state.stage == AetherStage.FAILED || state.stage == AetherStage.CRASHED -> Color(0xFFff9090)
                        else -> Color(0xFF9a97a3)
                    })
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Steps per protocol — same vocabulary as the desktop.
        val steps = when (protocol) {
            AetherProtocol.MASQUE -> listOf(
                "identity" to "هویت", "quickcheck" to "بررسی سرور قبلی",
                "scan" to "اسکن گیت‌وی", "selected" to "انتخاب سرور",
                "tunnel" to "برقراری تونل", "validate" to "اعتبارسنجی عبور داده",
                "connected" to "اتصال",
            )
            AetherProtocol.WG -> listOf(
                "identity" to "هویت", "quickcheck" to "بررسی سرور قبلی",
                "scan" to "اسکن اندپوینت", "selected" to "انتخاب سرور",
                "handshake" to "دست‌دادن", "connected" to "اتصال",
            )
            AetherProtocol.WARP_IN_WARP -> listOf(
                "identity" to "هویت دوگانه", "scan" to "اسکن اندپوینت",
                "selected" to "انتخاب سرور", "handshake" to "تونل بیرونی + داخلی",
                "connected" to "اتصال",
            )
        }
        val current = state.stage.forUi()
        val activeIdx = if (current == null) -1 else steps.indexOfFirst { it.first == current }
        steps.forEachIndexed { idx, (key, fa) ->
            val done = activeIdx > idx
            val active = activeIdx == idx
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)) {
                StepDot(done = done, active = active)
                Spacer(modifier = Modifier.width(8.dp))
                Text(fa, fontSize = 12.sp,
                    color = if (done) Color(0xFF3ba55d)
                    else if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color(0xFF6a6773))
                if (key == "scan" && active && scanElapsedSec > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("${scanElapsedSec}s", fontSize = 11.sp, color = Color(0xFF6a6773))
                }
            }
        }
        if (state.stage == AetherStage.SCAN && scanElapsedSec >= 20) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "این مرحله می‌تواند تا چند دقیقه طول بکشد؛ بعضی شبکه‌ها اتصال MASQUE را کند یا مسدود می‌کنند. اگر خیلی طول کشید می‌توانید دکمه اتصال را دوباره بزنید تا لغو شود.",
                fontSize = 11.sp,
                color = Color(0xFF9a97a3),
                lineHeight = 16.sp,
            )
        }
        if (state.server != null || state.rtt != null || state.profile != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Column {
                state.server?.let { DetailRow("سرور", it) }
                state.rtt?.let { DetailRow("RTT", it) }
                state.profile?.let { DetailRow("پروفایل", it) }
                DetailRow("SOCKS", state.socks)
            }
        }
    }
}

@Composable
private fun ConnectBar(
    state: AetherState,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
) {
    val running = state.running
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1b1e))
    ) {
        Row(modifier = Modifier.padding(10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ButtonLite(
                text = if (running) "توقف" else "اتصال",
                color = if (running) Color(0xFFb3261e) else MaterialTheme.colorScheme.primary,
                onClick = { if (running) onStop() else onConnect() },
                modifier = Modifier.weight(1f)
            )
            ButtonLite(
                text = "پاک کردن هویت",
                color = Color(0xFF2a2a2a),
                onClick = onReset,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// --- Small generic components -----------------------------------------------

@Composable
private fun SettingsCard(title: String? = null, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1e1f22))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (title != null) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = Color(0xFFdddddd))
}

@Composable
private fun SectionHint(text: String) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(text, fontSize = 10.sp, color = Color(0xFF7a7783), lineHeight = 16.sp)
}

@Composable
private fun WarningCard(text: String) {
    Box(modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xFF3a2e1b), RoundedCornerShape(8.dp))
        .padding(10.dp)) {
        Text(text, fontSize = 11.sp, color = Color(0xFFe0a800), lineHeight = 17.sp)
    }
}

@Composable
private fun StepDot(done: Boolean, active: Boolean) {
    Box(modifier = Modifier
        .size(13.dp)
        .background(
            when {
                done -> Color(0xFF3ba55d)
                active -> MaterialTheme.colorScheme.primary
                else -> Color(0xFF3a3a3a)
            },
            CircleShape))
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text("$label: ", fontSize = 11.sp, color = Color(0xFF9a97a3))
        Text(value, fontSize = 11.sp, color = Color(0xFFe8eaed))
    }
}

@Composable
private fun SegmentedControl(options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xFF131417), RoundedCornerShape(8.dp))
        .padding(3.dp)) {
        options.forEach { (key, label) ->
            val sel = key == selected
            Box(modifier = Modifier
                .weight(1f)
                .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp))
                .clickable { onSelected(key) }
                .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center) {
                Text(label, fontSize = 11.sp,
                    color = if (sel) Color.White else Color(0xFF9a97a3))
            }
        }
    }
}

@Composable
private fun TextFieldLite(
    value: String, onValueChange: (String) -> Unit,
    placeholder: String, modifier: Modifier = Modifier,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onValueChange,
        placeholder = { Text(placeholder, fontSize = 12.sp, color = Color(0xFF7a7783)) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
        modifier = modifier,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color(0xFF2a2a2a),
            cursorColor = MaterialTheme.colorScheme.primary,
        )
    )
}

@Composable
private fun <T> DropdownLiteE(
    selected: T, options: List<T>,
    label: (T) -> String, onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF131417), RoundedCornerShape(8.dp))
            .clickable { expanded = true }
            .padding(10.dp)) {
            Text(label(selected), fontSize = 12.sp, color = Color.White)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label(opt), fontSize = 12.sp) },
                    onClick = { onSelected(opt); expanded = false })
            }
        }
    }
}

@Composable
private fun DropdownLite(
    selected: String, options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    DropdownLiteE(selected = selected, options = options.map { it.first },
        label = { key -> options.firstOrNull { it.first == key }?.second ?: key },
        onSelected = onSelected)
}

@Composable
private fun ButtonLite(
    text: String, color: Color, onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier
        .background(color, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick)
        // Horizontal padding matters when the caller passes no width — without it the box
        // hugs the text so tightly it stops looking (and behaving) like a button.
        .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
    }
}
