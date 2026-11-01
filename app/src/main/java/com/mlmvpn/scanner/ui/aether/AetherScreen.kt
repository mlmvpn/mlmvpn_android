package com.mlmvpn.scanner.ui.aether

import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.theme.*
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
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

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
                S(R.string.connected_server_suffix, state.server?.let { " • $it" } ?: ""),
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
    // HTTP/3, HTTP/2 or HTTP/2 + fragment is the app's choice, made once per network and
    // remembered (AetherCarrier) -- never a question for the user.
    var transport by remember { mutableStateOf(com.mlmvpn.core.aether.AetherCarrier.AUTO) }
    var fragment by remember { mutableStateOf(false) }
    var fragmentSize by remember { mutableStateOf("") }
    var fragmentDelay by remember { mutableStateOf("") }
    var ech by remember { mutableStateOf("") }
    var wgNoize by remember { mutableStateOf("balanced") }
    var wgRetry by remember { mutableStateOf(true) }
    var keepalive by remember { mutableStateOf(5) }

    // Shared.
    //
    // TURBO for every protocol.
    //
    // IRONCLAD was briefly the WireGuard default here, on the reasoning that it verifies a
    // real data plane per candidate and so cannot pick a peer that handshakes and then
    // carries nothing. Device logs killed that idea: on a filtered Iranian line EVERY
    // ironclad candidate fails with "tunnel exited before data-plane validation" — it builds
    // a full tunnel per candidate and the network tears each one down before the HTTP check
    // can run, for MASQUE and WireGuard alike. A mode that never returns an endpoint is worse
    // than one that occasionally picks a bad one, so it stays available but never automatic.
    //
    // The same logs also show BALANCED is a poor default here: it found its one and only
    // working candidate 4s into the scan and then burned the remaining 118s of its deadline
    // looking for the six it will never find. TURBO takes that same endpoint and is connected
    // in seconds.
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
            putExtra("LOCAL_PORT", com.mlmvpn.scanner.utils.LocalPort.getString(context))
        }
        com.mlmvpn.scanner.utils.SafeStart.service(context, intent)
    }

    val vpnPrepareLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            pendingConfig?.let { startTunnel(it) }
        } else {
            Toast.makeText(context, S(R.string.without_vpn_permission_there_can_be_no_2), Toast.LENGTH_LONG).show()
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

    /**
     * True only for a session THIS screen started.
     *
     * The engine is a process-wide singleton, so this screen's state collector also sees
     * sessions the Game tab starts — a device log caught it reacting to a Game-tab WireGuard
     * boost while still holding its own stale context (`tab=MASQUE transport=h2
     * triedH2=true`). The auto-retry below can call resetIdentity() and doConnect(); firing
     * either against someone else's session would wipe the identity mid-boost and redial with
     * the wrong protocol. Ownership is the guard.
     */
    var screenOwnsSession by remember { mutableStateOf(false) }

    fun doConnect() = com.mlmvpn.scanner.data.ScanGuard.run(
        // A tunnel steals the default route from a running IP scan; the guard asks first.
        com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN
    ) {
        userStopped = false
        screenOwnsSession = true
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
                AetherProtocol.MASQUE -> "firewall"
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
        com.mlmvpn.scanner.utils.SafeStart.service(context, 
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
        // screenOwnsSession: never "recover" a session another screen started — see its doc.
        if (!ended || state.connected || userStopped || !screenOwnsSession) return@LaunchedEffect
        // Between two carriers of the automatic ladder the engine stops for a moment: not an end.
        if (com.mlmvpn.core.aether.AetherTunEngine.ladderActive) return@LaunchedEffect

        when {
            // Cloudflare accepted the TLS connection and refused the session: the stored
            // MASQUE identity is stale. Re-enrol and dial again.
            state.sawAccessDenied && !triedIdentityReset -> {
                triedIdentityReset = true
                val n = engine.resetIdentity()
                autoRetryNote = S(R.string.the_identity_was_rejected_a_fresh_one, n)
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
            // (Only for a fixed h3; with the automatic carrier the engine itself goes on to HTTP/2
            // and HTTP/2 + fragment, and remembers what worked.)
            activeTab == AetherProtocol.MASQUE && transport == "h3" && !triedH2Fallback -> {
                triedH2Fallback = true
                transport = "h2"
                autoRetryNote = S(R.string.no_server_was_found_over_http_3)
                kotlinx.coroutines.delay(1200)
                doConnect()
            }
        }
    }

    Column(modifier = Modifier
        .fillMaxSize()
        // Transparent: the feature host owns the backdrop for every screen it hosts.
        .background(androidx.compose.ui.graphics.Color.Transparent)
        // The floating bottom nav bar this used to clear no longer exists; the system
        // navigation bar inset is handled once by AppScreen's feature host.
        .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 24.dp)) {

        // Title + protocol tabs, kept compact so the button dominates the screen.
        Text(
            S(R.string.wireguard_engine),
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
        com.mlmvpn.scanner.ui.RouteChips()

        autoRetryNote?.also { note ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = note,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(ControlShape)
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
                // Spell out the split. "۲ فایل" after a single connect reads like the app
                // enrolled twice; "۱ هویت + ۱ حافظه‌ی سرور" says what actually happened.
                val r = engine.resetIdentityDetailed()
                val msg = if (r.total == 0) S(R.string.there_was_nothing_to_clear)
                          else S(R.string.identities_and_server_caches_were_cleared, r.identities, r.caches)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
        AetherStage.CONNECTED -> GreenOk
        AetherStage.FAILED, AetherStage.CRASHED -> Color(0xFFff6b6b)
        AetherStage.STOPPED, AetherStage.IDLE -> MaterialTheme.colorScheme.primary
        else -> Color(0xFFFDE293)
    }
    val working = state.running && state.stage != AetherStage.CONNECTED
    val active = state.running

    // Composed only while the button is lit and on screen. Idle, the halo does not move, and an
    // infinite transition asks for every frame for as long as it exists -- whether anything
    // reads it or not, and in a tab parked out of sight as well.
    val onScreen by com.mlmvpn.scanner.ui.rememberOnScreen()
    val pulse = if (active && onScreen) {
        rememberInfiniteTransition(label = "aetherPower").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (working) 900 else 2200, easing = FastOutSlowInEasing),
                repeatMode = if (working) RepeatMode.Restart else RepeatMode.Reverse,
            ),
            label = "pulse",
        ).value
    } else {
        0f
    }
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
                contentDescription = if (state.running) S(R.string.disconnect_4) else S(R.string.connect_3),
                tint = BgDark,
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

@Composable
private fun AetherStatusLine(state: AetherState, modifier: Modifier = Modifier) {
    val color = when (state.stage) {
        AetherStage.CONNECTED -> GreenOk
        AetherStage.FAILED, AetherStage.CRASHED -> Color(0xFFff6b6b)
        AetherStage.STOPPED, AetherStage.IDLE -> TextMuted
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
            QuickRow(S(R.string.transport_3)) {
                MiniSegmented(
                    options = listOf("h3" to "HTTP/3", "h2" to "HTTP/2"),
                    selected = transport,
                    enabled = !running,
                    onSelected = onTransport,
                )
            }
        }

        QuickRow(S(R.string.scan_mode_2)) {
            MiniSegmented(
                // The enum's displayFa is a full sentence — fine in a dropdown, far too long
                // for a segment. Same modes, named short.
                //
                // IRONCLAD is offered but never defaulted. It is the only mode that pushes
                // real traffic through each candidate before accepting it, which is the right
                // idea — but it does so by building a full tunnel per candidate, and on a
                // filtered line the network tears each one down before the check completes,
                // so every candidate fails and the scan returns nothing. Useful on a clean
                // connection, useless on the ones this app exists for.
                options = listOf(
                    AetherScan.TURBO.name to S(R.string.turbo_2),
                    AetherScan.BALANCED.name to S(R.string.balanced_2),
                    AetherScan.THOROUGH.name to S(R.string.full_2),
                    AetherScan.IRONCLAD.name to S(R.string.guaranteed_2),
                ),
                selected = scan.name,
                enabled = !running,
                onSelected = { v -> AetherScan.values().firstOrNull { it.name == v }?.let(onScan) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonLite(
                text = S(R.string.advanced_settings_3),
                color = SurfaceDark,
                onClick = onOpenAdvanced,
                modifier = Modifier.weight(1f),
            )
            ButtonLite(
                text = S(R.string.clear_the_identity),
                color = SurfaceDark,
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
        Text(label, fontSize = 11.sp, color = TextMuted)
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
            .clip(ControlShape)
            .frostedGlass(androidx.compose.ui.graphics.RectangleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
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
                        else -> TextMuted
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
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                activeIdx > idx -> GreenOk
                                activeIdx == idx -> MaterialTheme.colorScheme.primary
                                else -> SurfaceDark
                            }
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = steps.getOrNull(activeIdx)?.second ?: S(R.string.ready_4),
            fontSize = 10.sp,
            color = Color(0xFF5F6368),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

private fun stagesFor(protocol: AetherProtocol): List<Pair<String, String>> = when (protocol) {
    AetherProtocol.MASQUE -> listOf(
        "identity" to S(R.string.identity), "quickcheck" to S(R.string.checking_the_previous_server),
        "scan" to S(R.string.gateway_scan), "selected" to S(R.string.choosing_a_server),
        "tunnel" to S(R.string.bringing_the_tunnel_up_2), "validate" to S(R.string.verifying_data_passes),
        "connected" to S(R.string.connect_3),
    )
    AetherProtocol.WG -> listOf(
        "identity" to S(R.string.identity), "quickcheck" to S(R.string.checking_the_previous_server),
        "scan" to S(R.string.endpoint_scan_2), "selected" to S(R.string.choosing_a_server),
        "handshake" to S(R.string.handshake), "connected" to S(R.string.connect_3),
    )
    AetherProtocol.WARP_IN_WARP -> listOf(
        "identity" to S(R.string.dual_identity), "scan" to S(R.string.endpoint_scan_2),
        "selected" to S(R.string.choosing_a_server), "handshake" to S(R.string.outer_inner_tunnel),
        "connected" to S(R.string.connect_3),
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
            shape = CardShape,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        S(R.string.advanced_settings_3),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    // A real icon button: ButtonLite without a modifier has no horizontal
                    // padding of its own, so it collapsed to an unhittable sliver.
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = S(R.string.close_4),
                            tint = TextMuted,
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                Spacer(modifier = Modifier.width(8.dp))
                Text(S(R.string.wireguard_engine), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                S(R.string.a_censorship_circumvention_engine_built_on_cloudflare) +
                    S(R.string.like_the_windows_build_it_finds_a) +
                    S(R.string.exposes_a_socks5_proxy_on_127_0, AetherEngine.AETHER_SOCKS_PORT),
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
                    labelColor = if (selected) Color.White else TextMuted
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
        SectionTitle(S(R.string.transport_3))
        SegmentedControl(options = listOf("h3" to "HTTP/3 (QUIC)", "h2" to "HTTP/2 (TCP)"),
            selected = transport, onSelected = onTransport)
        SectionHint(S(R.string.if_udp_is_restricted_choose_http_2))

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.clienthello_fragmentation_http_2_only))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = fragment, onCheckedChange = onFragment,
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (fragment) S(R.string.on_3) else S(R.string.off_3), fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextFieldLite(value = fragmentSize, onValueChange = onFragmentSize,
                placeholder = S(R.string.size_16_32), modifier = Modifier.weight(1f))
            TextFieldLite(value = fragmentDelay, onValueChange = onFragmentDelay,
                placeholder = S(R.string.delay_2_10), modifier = Modifier.weight(1f))
        }
        SectionHint(S(R.string.sends_the_tls_handshake_in_pieces_so))

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.ech_encrypted_sni))
        DropdownLite(selected = ech, options = listOf("" to S(R.string.off_4), "auto" to S(R.string.automatic_4)),
            onSelected = onEch)
        SectionHint(S(R.string.cloudflare_s_masque_endpoint_usually_refuses_ech))
    }
}

@Composable
private fun WgPane(
    noize: String, onNoize: (String) -> Unit,
    retry: Boolean, onRetry: (Boolean) -> Unit,
    keepalive: String, onKeepalive: (String) -> Unit,
) {
    SettingsCard {
        SectionTitle(S(R.string.aethernoize_profile))
        DropdownLite(selected = noize,
            options = listOf("balanced" to S(R.string.balanced_default), "off" to S(R.string.off_4),
                "light" to S(R.string.light_2), "aggressive" to S(R.string.aggressive_gfw)),
            onSelected = onNoize)
        SectionHint(S(R.string.adds_decoy_packets_so_the_wireguard_pattern))

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.try_the_other_profiles_automatically))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = retry, onCheckedChange = onRetry)
            Spacer(modifier = Modifier.width(6.dp))
            Text(S(R.string.if_the_first_profile_does_not_work), fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.keepalive_seconds))
        TextFieldLite(value = keepalive, onValueChange = onKeepalive,
            placeholder = "5", modifier = Modifier.fillMaxWidth())
        SectionHint(S(R.string.a_lower_number_means_more_stability_behind))
    }
}

@Composable
private fun GooLPane(
    noize: String, onNoize: (String) -> Unit,
    keepalive: String, onKeepalive: (String) -> Unit,
) {
    SettingsCard {
        Text(S(R.string.a_wireguard_tunnel_inside_another_wireguard_tunnel),
            fontSize = 11.sp, color = TextMuted, lineHeight = 17.sp)
        Spacer(modifier = Modifier.height(8.dp))
        WarningCard(text = S(R.string.this_mode_creates_two_cloudflare_identities_and))
        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.aethernoize_profile_outer_tunnel))
        DropdownLite(selected = noize,
            options = listOf("balanced" to S(R.string.balanced_default), "off" to S(R.string.off_4),
                "light" to S(R.string.light_2), "aggressive" to S(R.string.aggressive_2)),
            onSelected = onNoize)
        SectionHint(S(R.string.the_inner_tunnel_is_always_unobfuscated))
        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.outer_tunnel_keepalive_seconds))
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
        SectionTitle(S(R.string.clienthello_fragmentation_http_2_only))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = fragment, onCheckedChange = onFragment,
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (fragment) S(R.string.on_3) else S(R.string.off_3), fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextFieldLite(value = fragmentSize, onValueChange = onFragmentSize,
                placeholder = S(R.string.size_16_32), modifier = Modifier.weight(1f))
            TextFieldLite(value = fragmentDelay, onValueChange = onFragmentDelay,
                placeholder = S(R.string.delay_2_10), modifier = Modifier.weight(1f))
        }
        SectionHint(S(R.string.sends_the_tls_handshake_in_pieces_so))

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.ech_encrypted_sni))
        DropdownLite(selected = ech, options = listOf("" to S(R.string.off_4), "auto" to S(R.string.automatic_4)),
            onSelected = onEch)
        SectionHint(S(R.string.cloudflare_s_masque_endpoint_usually_refuses_ech))
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
    SettingsCard(title = S(R.string.shared_settings)) {
        SectionTitle(S(R.string.ip_version))
        DropdownLiteE(selected = ip, options = AetherIp.values().toList(),
            label = { it.displayFa }, onSelected = onIp)

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.reconnect_quickly_to_the_previous_server))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = quick, onCheckedChange = onQuick)
            Spacer(modifier = Modifier.width(6.dp))
            Text(S(R.string.if_the_previous_server_was_healthy_skip), fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.accept_a_server_without_a_data_pass))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = noDataCheck, onCheckedChange = onNoDataCheck)
            Spacer(modifier = Modifier.width(6.dp))
            Text(S(R.string.accept_a_server_on_a_successful_handshake), fontSize = 12.sp)
        }
        SectionHint(
            S(R.string.by_default_a_server_is_not_accepted) +
                S(R.string.if_every_server_is_rejected_with_closed) +
                S(R.string.turn_this_on_if_it_then_connects) +
                S(R.string.the_tunnel_and_you_should_try_h2)
        )

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.full_log_debug))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = verbose, onCheckedChange = onVerbose)
            Spacer(modifier = Modifier.width(6.dp))
            Text(S(R.string.more_detail_in_the_core_log_and), fontSize = 12.sp)
        }
        SectionHint(S(R.string.turn_it_on_for_troubleshooting_only))
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
    SettingsCard(title = S(R.string.shared_settings)) {
        SectionTitle(S(R.string.scan_mode_2))
        DropdownLiteE(selected = scan, options = AetherScan.values().toList(),
            label = { it.displayFa }, onSelected = onScan)
        SectionHint(S(R.string.balanced_does_not_stop_at_the_first))

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.ip_version))
        DropdownLiteE(selected = ip, options = AetherIp.values().toList(),
            label = { it.displayFa }, onSelected = onIp)

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.reconnect_quickly_to_the_previous_server))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = quick, onCheckedChange = onQuick)
            Spacer(modifier = Modifier.width(6.dp))
            Text(S(R.string.if_the_previous_server_was_healthy_skip), fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.accept_a_server_without_a_data_pass))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = noDataCheck, onCheckedChange = onNoDataCheck)
            Spacer(modifier = Modifier.width(6.dp))
            Text(S(R.string.accept_a_server_on_a_successful_handshake), fontSize = 12.sp)
        }
        SectionHint(
            S(R.string.by_default_a_server_is_not_accepted) +
                S(R.string.if_every_server_is_rejected_with_closed) +
                S(R.string.turn_this_on_if_it_then_connects) +
                S(R.string.the_tunnel_and_you_should_try_h2)
        )

        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle(S(R.string.full_log_debug))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = verbose, onCheckedChange = onVerbose)
            Spacer(modifier = Modifier.width(6.dp))
            Text(S(R.string.more_detail_in_the_core_log_and), fontSize = 12.sp)
        }
        SectionHint(S(R.string.turn_it_on_for_troubleshooting_only))
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
    // Only while on screen. Out of sight it would wake every flush interval for nothing; back
    // on screen it starts again from the engine's own buffer, so no line is missed.
    com.mlmvpn.scanner.ui.LaunchedWhileVisible(engine) {
        val pending = java.util.concurrent.ConcurrentLinkedQueue<String>()
        // Snapshot before subscribing. The other order would replay anything that arrived
        // between the two as a duplicate; this way such a line is simply missed, which is
        // the better failure for a scrolling diagnostic view.
        lines.clear()
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

    SettingsCard(title = S(R.string.core_log)) {
        if (lines.isEmpty()) {
            Text(S(R.string.no_output_yet_start_a_connection_to),
                fontSize = 11.sp, color = Color(0xFF7a7783), lineHeight = 17.sp)
        } else {
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .frostedGlass(RoundedCornerShape(8.dp))
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
                                    line.contains("failed", true) || line.contains(S(R.string.error_3)) ->
                                    Color(0xFFff9090)
                                line.contains("connected", true) || line.contains(S(R.string.connected_5)) ->
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
    // From a start stamp rather than by counting ticks, so the count survives the page going out
    // of sight (when the ticking stops) and is right again the moment it is back.
    var scanStartedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(state.stage) {
        if (state.stage == AetherStage.SCAN) {
            scanStartedAt = android.os.SystemClock.elapsedRealtime()
            scanElapsedSec = 0
        }
    }
    com.mlmvpn.scanner.ui.LaunchedWhileVisible(state.stage) {
        if (state.stage != AetherStage.SCAN) return@LaunchedWhileVisible
        while (true) {
            scanElapsedSec = ((android.os.SystemClock.elapsedRealtime() - scanStartedAt) / 1000).toInt()
            kotlinx.coroutines.delay(1000)
        }
    }
    SettingsCard(title = S(R.string.status_2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.stageFa, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = when (state.stage) {
                    AetherStage.CONNECTED -> GreenOk
                    AetherStage.FAILED, AetherStage.CRASHED -> Color(0xFFff6b6b)
                    else -> MaterialTheme.colorScheme.onSurface
                })
            Spacer(modifier = Modifier.weight(1f))
            val badgeColor = when (state.stage) {
                AetherStage.CONNECTED -> Color(0xFF1b3a22)
                AetherStage.FAILED, AetherStage.CRASHED -> Color(0xFF3b1b1b)
                else -> SurfaceDark
            }
            val badgeText = when (state.stage) {
                AetherStage.CONNECTED -> S(R.string.connected_5)
                AetherStage.STARTING, AetherStage.IDENTITY, AetherStage.QUICKCHECK,
                AetherStage.SCAN, AetherStage.SELECTED, AetherStage.HANDSHAKE,
                AetherStage.TUNNEL, AetherStage.VALIDATE, AetherStage.RECONNECTING -> S(R.string.working_3)
                AetherStage.FAILED, AetherStage.CRASHED -> S(R.string.error_3)
                AetherStage.STOPPED, AetherStage.IDLE -> S(R.string.off_4)
            }
            Box(modifier = Modifier
                .background(badgeColor, ControlShape)
                .padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text(badgeText, fontSize = 11.sp,
                    color = when {
                        state.stage == AetherStage.CONNECTED -> Color(0xFF7ee787)
                        state.stage == AetherStage.FAILED || state.stage == AetherStage.CRASHED -> Color(0xFFff9090)
                        else -> TextMuted
                    })
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Steps per protocol — same vocabulary as the desktop.
        val steps = when (protocol) {
            AetherProtocol.MASQUE -> listOf(
                "identity" to S(R.string.identity), "quickcheck" to S(R.string.checking_the_previous_server),
                "scan" to S(R.string.gateway_scan), "selected" to S(R.string.choosing_a_server),
                "tunnel" to S(R.string.bringing_the_tunnel_up_2), "validate" to S(R.string.verifying_data_passes),
                "connected" to S(R.string.connect_3),
            )
            AetherProtocol.WG -> listOf(
                "identity" to S(R.string.identity), "quickcheck" to S(R.string.checking_the_previous_server),
                "scan" to S(R.string.endpoint_scan_2), "selected" to S(R.string.choosing_a_server),
                "handshake" to S(R.string.handshake), "connected" to S(R.string.connect_3),
            )
            AetherProtocol.WARP_IN_WARP -> listOf(
                "identity" to S(R.string.dual_identity), "scan" to S(R.string.endpoint_scan_2),
                "selected" to S(R.string.choosing_a_server), "handshake" to S(R.string.outer_inner_tunnel),
                "connected" to S(R.string.connect_3),
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
                    color = if (done) GreenOk
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
                S(R.string.this_step_can_take_a_few_minutes),
                fontSize = 11.sp,
                color = TextMuted,
                lineHeight = 16.sp,
            )
        }
        if (state.server != null || state.rtt != null || state.profile != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Column {
                state.server?.let { DetailRow(S(R.string.server_2), it) }
                state.rtt?.let { DetailRow("RTT", it) }
                state.profile?.let { DetailRow(S(R.string.profile), it) }
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
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Row(modifier = Modifier.padding(10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ButtonLite(
                text = if (running) S(R.string.stop_2) else S(R.string.connect_3),
                color = if (running) Color(0xFFb3261e) else MaterialTheme.colorScheme.primary,
                onClick = { if (running) onStop() else onConnect() },
                modifier = Modifier.weight(1f)
            )
            ButtonLite(
                text = S(R.string.clear_the_identity),
                color = SurfaceDark,
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
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
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
        .frostedGlass(RoundedCornerShape(8.dp))
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
                done -> GreenOk
                active -> MaterialTheme.colorScheme.primary
                else -> Color(0xFF3a3a3a)
            },
            CircleShape))
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text("$label: ", fontSize = 11.sp, color = TextMuted)
        Text(value, fontSize = 11.sp, color = Color(0xFFe8eaed))
    }
}

@Composable
private fun SegmentedControl(options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .frostedGlass(RoundedCornerShape(8.dp))
        .padding(3.dp)) {
        options.forEach { (key, label) ->
            val sel = key == selected
            Box(modifier = Modifier
                .weight(1f)
                .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable { onSelected(key) }
                .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center) {
                Text(label, fontSize = 11.sp,
                    color = if (sel) Color.White else TextMuted)
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
        colors = iosFieldColors()
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
            .frostedGlass(RoundedCornerShape(8.dp))
            .clickable { expanded = true }
            .padding(10.dp)) {
            Text(label(selected), fontSize = 12.sp, color = Color.White)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.iosMenu(),
        ) {
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
        .background(color, ControlShape)
        .clickable(onClick = onClick)
        // Horizontal padding matters when the caller passes no width — without it the box
        // hugs the text so tightly it stops looking (and behaving) like a button.
        .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
    }
}
