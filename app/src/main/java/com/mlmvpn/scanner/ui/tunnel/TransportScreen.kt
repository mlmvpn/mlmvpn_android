package com.mlmvpn.scanner.ui.tunnel

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mlmvpn.core.tunnel.ConnectionLog
import com.mlmvpn.core.tunnel.IpFormatter
import com.mlmvpn.core.tunnel.TunnelPreferences
import com.mlmvpn.scanner.ui.cfwarp.CfWarpPanel
import com.mlmvpn.scanner.ui.lantern.LanternPanel
import com.mlmvpn.scanner.ui.urnetwork.UrnetworkLiveCard
import com.mlmvpn.scanner.ui.urnetwork.UrnetworkOnboarding
import com.mlmvpn.scanner.ui.urnetwork.UrnetworkPages
import com.mlmvpn.scanner.ui.urnetwork.UrnetworkPanel
import com.mlmvpn.scanner.ui.urnetwork.UrnetworkUsageCard
import com.mlmvpn.scanner.ui.cfwarp.NAT64_EXIT_PAGE
import com.mlmvpn.scanner.ui.cfwarp.Nat64ExitRow
import com.mlmvpn.scanner.ui.cfwarp.hasNat64Exit
import com.mlmvpn.scanner.ui.cfwarp.hasExitCountry
import com.mlmvpn.scanner.ui.geph.GephPanel
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.PanelShape
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * One transport, one screen.
 *
 * The layout answers three questions in the order a user asks them, and nothing else is on the
 * page: am I connected (the control, which is most of the screen), what is it doing right now
 * (the line and the bar under it), and what can I change without thinking (the country, the
 * bridge). Everything a user changes only when something is wrong is one row further in, on
 * [TransportAdvancedScreen].
 *
 * The control is deliberately the largest thing here. Every earlier version of this screen led
 * with five settings cards stacked above a small button, which put the two decisions a user
 * makes once a year above the one they make every day.
 */
@Composable
fun TransportScreen(
    transport: Transport,
    onBack: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenPsiphonRegion: () -> Unit,
    onOpenTorRegion: () -> Unit,
    onOpenTorMode: () -> Unit,
    /** A transport's own page by name -- «گف»'s account, exit, news and sessions pages. */
    onOpenPage: (String) -> Unit = {},
    /**
     * A page to show INSTEAD of the settings rows, in two-pane mode only.
     *
     * The transport's own sub-pages -- advanced settings, the connection log, a country picker --
     * used to replace this whole screen, which on a wide display meant the connect control
     * disappeared the moment anyone opened them. It is the one control the screen exists for, and
     * there is a column of room beside it, so the sub-page goes in the other column and the dial
     * stays where it was. On a phone this is null and nothing changes.
     */
    detail: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current

    // «URnetwork»: until there is an account, the screen is URnetwork's terms and one button --
    // nothing else; the connect screen comes once the account is made.
    if (transport == Transport.URNETWORK && com.mlmvpn.core.urnetwork.UrnetworkEngine.available(context)) {
        var hasAccount by remember { mutableStateOf(com.mlmvpn.core.urnetwork.UrnetworkEngine.hasAccount(context)) }
        if (!hasAccount) {
            IosScreen(title = transport.labelFa, onBack = onBack, backLabel = S(R.string.home)) {
                UrnetworkOnboarding(onCreated = { hasAccount = true })
            }
            return
        }
    }

    val state by TunnelController.state.collectAsStateWithLifecycle()
    val prefs = remember { TunnelPreferences(context) }

    // Which transport this screen is about, versus which one holds the tunnel. They differ
    // whenever the user opened a second transport while the first was up, and that difference
    // is the whole reason the control can say "take over" instead of lying about the state.
    // A WARP tile whose connect the service handed to another member (the ladder) still owns the
    // session: its button disconnects it, and a card says which member carries it.
    val handedTo = state.active?.takeIf { state.handedOverFrom == transport && it != transport && state.isBusy }
    val ownsTunnel = state.active == transport || handedTo != null
    val stage = if (ownsTunnel) state.stage else TunnelStage.IDLE
    val someoneElse = state.active?.takeIf { it != transport && state.isBusy && handedTo == null }

    var pendingStart by remember { mutableStateOf(false) }
    val vpnPrepare = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingStart) {
            TunnelController.connect(context, transport)
        } else {
            TunnelController.onPermissionDenied()
            Toast.makeText(context, S(R.string.without_vpn_permission_there_can_be_no), Toast.LENGTH_LONG)
                .show()
        }
        pendingStart = false
    }

    fun start() {
        if (!transport.supportedHere) {
            Toast.makeText(context, S(R.string.this_route_was_not_built_for_this), Toast.LENGTH_LONG).show()
            return
        }
        if (transport.needsTunnelCore && !state.engineAvailable) {
            Toast.makeText(context, S(R.string.the_tunnel_core_for_this_device_was), Toast.LENGTH_LONG).show()
            return
        }
        transport.unavailableReason(context)?.let { reason ->
            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
            return
        }
        if (!TunnelController.hasNetwork(context)) {
            Toast.makeText(context, S(R.string.the_device_is_not_on_any_network), Toast.LENGTH_LONG).show()
            return
        }
        // A tunnel takes the default route, which kills every socket a running IP scan has open
        // and makes it finish early on a fake, mostly-empty result. ScanGuard asks once, at the
        // app root, and either runs this now or after the user chooses to stop the scan.
        com.mlmvpn.scanner.data.ScanGuard.run(
            com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN
        ) {
            val consent = runCatching { VpnService.prepare(context) }.getOrNull()
            if (consent != null) {
                pendingStart = true
                vpnPrepare.launch(consent)
            } else {
                TunnelController.connect(context, transport)
            }
        }
    }

    // Announce the outcome. The status line already carries it, but a scan can run for minutes
    // and a user who tapped connect and looked away needs something that reaches them without
    // staring at the page.
    LaunchedEffect(stage) {
        when (stage) {
            TunnelStage.RUNNING -> Toast
                .makeText(context, S(R.string.connected_2, transport.labelFa), Toast.LENGTH_SHORT).show()
            TunnelStage.FAILED -> Toast
                .makeText(
                    context,
                    com.mlmvpn.core.warp.WarpIdRelay.readable(state.message) ?: S(R.string.could_not_connect),
                    Toast.LENGTH_LONG,
                ).show()
            else -> Unit
        }
    }

    // 720dp, the same threshold Settings uses, and for the same reason: it is the width at which
    // two real columns fit rather than two cramped ones. A phone in landscape is not it.
    val twoPane = LocalConfiguration.current.screenWidthDp >= 720

    // The half a user looks AT: what this transport is, the control, and what it is doing. On a
    // wide screen this is a column of its own instead of a strip down the middle with a metre of
    // empty wallpaper on either side.
    val hero: @Composable ColumnScope.() -> Unit = {

        Spacer(Modifier.height(18.dp))

        Text(
            transport.label,
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            transport.taglineFa,
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 34.dp),
        )

        Spacer(Modifier.height(26.dp))

        ConnectDial(
            transport = transport,
            stage = stage,
            progress = if (ownsTunnel) state.progress else -1,
            onClick = { if (ownsTunnel && state.isBusy) TunnelController.disconnect(context) else start() },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(18.dp))

        StatusLine(
            transport = transport,
            stage = stage,
            detail = if (ownsTunnel) com.mlmvpn.core.warp.WarpIdRelay.readable(state.message) else null,
            takenBy = someoneElse,
            unavailable = transport.unavailableReason(context) != null,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        com.mlmvpn.scanner.ui.RouteChips()

        // «سایفون»: which method of how many while it searches, then what carried it and how it holds.
        if (transport == Transport.PSIPHON && ownsTunnel && (stage == TunnelStage.STARTING || stage == TunnelStage.RUNNING)) {
            Spacer(Modifier.height(12.dp))
            PsiphonLiveCard()
        }

        // «URnetwork»: each step while it connects -- the providers being checked and joining, as its
        // own app counts them -- then the providers it carries through; a failed try keeps its reason.
        if (transport == Transport.URNETWORK) {
            Spacer(Modifier.height(10.dp))
            UrnetworkLiveCard(ownsTunnel = ownsTunnel && stage != TunnelStage.IDLE)
        }

        if (handedTo != null) {
            Spacer(Modifier.height(12.dp))
            LadderHandOverCard(from = transport, to = handedTo)
        }

        // The identity could not be made: offer the way around it here, not in Settings.
        if (ownsTunnel && stage == TunnelStage.FAILED &&
            (transport.usesWarpIdentity || transport == Transport.CFWG) &&
            com.mlmvpn.core.warp.WarpIdRelay.isBlocked(state.message)
        ) {
            Spacer(Modifier.height(14.dp))
            IdentityBlockedCard(onRetry = { start() })
        }

        Spacer(Modifier.height(24.dp))

        AnimatedVisibility(visible = ownsTunnel && stage == TunnelStage.RUNNING) {
            LiveStats(state, transport)
        }
        if (transport == Transport.GEPH && ownsTunnel && stage == TunnelStage.RUNNING) {
            Spacer(Modifier.height(8.dp))
            com.mlmvpn.scanner.ui.geph.GephLiveCard()
        }

        // «URnetwork»: the account's monthly data -- the bar, used and left, the days to the reset.
        if (transport == Transport.URNETWORK && com.mlmvpn.core.urnetwork.UrnetworkEngine.available(context)) {
            UrnetworkUsageCard(onRestore = { onOpenPage(UrnetworkPages.RESTORE) })
        }
    }

    // The half a user ACTS on: the choices this transport offers and the pages behind them.
    // Every row here is a `Modifier.clickable`, which is focusable and answers the centre key,
    // so this column is already navigable with a remote without anything added.
    val options: @Composable ColumnScope.() -> Unit = {

        if (transport.usesWarpIdentity) WarpIdentitySection(transport, stage)

        // The exit country, for the three that reach Cloudflare's WARP: through a NAT64 gateway
        // in that country, or direct (see Nat64Exits).
        // WARP-in-WARP too: a free proxy after the engine (IPv4) works behind any WARP engine.
        if (transport.hasExitCountry()) Nat64ExitRow(transport, onOpen = { onOpenPage(NAT64_EXIT_PAGE) })

        // What a user picks routinely, on the transport's own screen. For MASQUE and WireGuard
        // that is otherwise nothing at all: their defaults are the answer until something is
        // wrong, and then the advanced page is where to go.
        when (transport) {
            Transport.PSIPHON -> {
                PsiphonMethodRows(onOpenPage)
                SettingsSectionHeader(S(R.string.exit_country))
                SettingsGroup {
                    SettingsRow(
                        title = S(R.string.country),
                        icon = Icons.Default.Language,
                        tint = Ios.Indigo,
                        value = regionLabel(prefs.egressRegion) { CountryLabel.withFlag(it) },
                        onClick = onOpenPsiphonRegion,
                    )
                }
                Footer(
                    S(R.string.this_is_a_preference_not_a_guarantee) +
                        S(R.string.then_lets_it_go_because_pinning_the) +
                        S(R.string.that_is_the_only_one_left_on)
                )

            }

            Transport.TOR -> {
                SettingsSectionHeader(S(R.string.reaching_the_tor_network))
                SettingsGroup {
                    SettingsRow(
                        title = S(R.string.connection_mode),
                        icon = Icons.Default.Route,
                        tint = Ios.Purple,
                        value = torModeLabel(prefs.torMode),
                        onClick = onOpenTorMode,
                    )
                    Separator()
                    // Bridges: the way IN to Tor -- not how it is disguised (the mode above) and
                    // not where it comes out (the country below).
                    SettingsRow(
                        title = S(R.string.tor_br_row),
                        icon = Icons.Default.Hub,
                        tint = Ios.Teal,
                        value = torBridgesValue(),
                        onClick = { onOpenPage(TOR_BRIDGES_PAGE) },
                    )
                    Separator()
                    SettingsRow(
                        title = S(R.string.exit_country),
                        icon = Icons.Default.Language,
                        tint = Ios.Indigo,
                        value = regionLabel(prefs.torExitRegion) { CountryLabel.withFlag(it) },
                        onClick = onOpenTorRegion,
                    )
                }
                Footer(
                    S(R.string.tor_br_main_footer) + " " +
                    S(R.string.the_exit_country_is_applied_with_strictnodes) +
                        S(R.string.goes_elsewhere_if_it_cannot_build_a) +
                        S(R.string.is_written_next_to_its_name_because)
                )
            }

            Transport.GOOL -> {
                SettingsSectionHeader(S(R.string.how_this_works))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .frostedGlass(CardShape)
                        .padding(16.dp),
                ) {
                    Text(
                        S(R.string.a_wireguard_tunnel_carried_inside_a_masque) +
                            S(R.string.one_it_is_slower_and_the_reason) +
                            S(R.string.layer_on_its_own),
                        color = Ios.Label,
                        fontSize = 14.sp,
                        lineHeight = 24.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        S(R.string.traffic_shaping_for_the_outer_layer_is) +
                            S(R.string.separate_places_have_to_agree_on_this) +
                            S(R.string.up_the_check_that_decides_whether_a) +
                            S(R.string.reused_and_what_gets_written_to_storage) +
                            S(R.string.here_would_only_have_changed_one_of),
                        color = Ios.SecondaryLabel,
                        fontSize = 13.sp,
                        lineHeight = 22.sp,
                    )
                }
            }

            Transport.GEPH -> GephPanel(onOpenPage)

            Transport.CFWG -> CfWarpPanel()

            Transport.LANTERN -> LanternPanel()

            Transport.URNETWORK -> UrnetworkPanel(onOpenPage)

            else -> Unit
        }

        SettingsSectionHeader(S(R.string.settings))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.advanced_settings),
                icon = Icons.Default.Tune,
                tint = Ios.Gray,
                onClick = onOpenAdvanced,
            )
            Separator()
            SettingsRow(
                title = S(R.string.connection_report),
                icon = Icons.Default.Article,
                tint = Ios.Teal,
                value = ConnectionLog.snapshot().size.takeIf { it > 0 }?.toString(),
                onClick = onOpenLog,
            )
        }

        Footer(transport.adviceFa)

        Spacer(Modifier.height(28.dp))
    }

    IosScreen(
        title = transport.labelFa,
        onBack = onBack,
        backLabel = S(R.string.home),
        // Off in two panes, because each column scrolls on its own below. One scroll around both
        // would move the connect control off screen to reach a settings row, which is the exact
        // thing having two columns is for.
        scrollable = !twoPane,
    ) {
        if (!twoPane) {
            hero()
            options()
            return@IosScreen
        }

        // A Row, so right-to-left mirrors it for nothing: in Persian the control sits on the
        // right and the settings on the left, in English the other way round. Same reasoning as
        // the two-pane Settings screen -- a branch on the language would be a second place to
        // keep in step and would be wrong for every RTL language not thought of yet.
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                hero()
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (detail != null) {
                    // No scroll wrapper here: every sub-page is an IosScreen and brings its own.
                    // Nesting one vertical scroll inside another gives the inner one unbounded
                    // height, which is the crash the IosScreen `scrollable` flag exists for.
                    detail()
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        options()
                    }
                }
            }
        }
    }
}

/**
 * The exit country, in the user's own language.
 *
 * The value reaches here as a two-letter code, and the name is built from it with the
 * platform's region data against the app's active locale — so a Persian UI reads «آلمان» and an
 * English one reads "Germany", with no translation table to maintain and no source that can be
 * in the wrong language. Psiphon used to hand its region straight through as the English name
 * it happens to use internally, which is how "UNITED STATES" ended up on a Persian screen.
 *
 * [fallbackName] covers the seconds between "connected" and the country lookup landing, when
 * Psiphon has already named its region and no code has been resolved yet. Showing that name is
 * better than showing nothing, and it is replaced the moment a code arrives.
 */
@Composable
private fun ExitCountryCard(code: String, fallbackName: String) {
    val locale = LocalConfiguration.current.locales[0]
    val known = code.takeIf { IpFormatter.isRealCountry(it) }?.uppercase()
    val name = when {
        known != null -> java.util.Locale("", known).getDisplayCountry(locale)
            .ifBlank { known }
        // `T1` is Cloudflare's marker for "this came out of the Tor network", not a country. It
        // only appears if a reading slipped through from that source; the lookup itself resolves
        // the exit relay's real country from its address.
        code.equals("T1", ignoreCase = true) -> S(R.string.the_tor_network)
        code.equals("XX", ignoreCase = true) || code.isEmpty() -> fallbackName.ifBlank { "" }
        else -> fallbackName.ifBlank { code }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass(PanelShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(S(R.string.exit_from), color = Ios.SecondaryLabel, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        if (known != null) {
            Text(IpFormatter.flag(known), fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            name.ifBlank { S(R.string.detecting) },
            color = if (name.isBlank()) Ios.SecondaryLabel else Ios.Label,
            fontSize = 16.sp,
            fontWeight = if (name.isBlank()) FontWeight.Normal else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** The grey explanatory paragraph iOS puts under a group. */
@Composable
private fun Footer(text: String) {
    Text(
        text,
        color = Ios.SecondaryLabel,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
    )
}

/**
 * The one control on the screen, sized to say so.
 *
 * Three concentric layers, all of them a FIXED size: a halo, a glass ring, and the pressable
 * disc. While connecting, a determinate arc rides the ring at the progress the service actually
 * reports — and when it reports none, the arc sweeps instead of standing still at zero. Nothing
 * here is a fabricated animation over an unknown state: a progress bar that moves on a timer
 * while nothing happens is worse than no progress bar.
 *
 * ## Why there is no breathing halo any more
 *
 * There was one, and it was animating `Modifier.size(...)` on the outer layer. A dp size is a
 * LAYOUT value, so every frame of that animation re-measured the halo, which re-measured the
 * column holding it, which nudged the status line, the stats card and every settings row below
 * it up and down for as long as the screen was open. It read as the whole page breathing rather
 * than the button. The arc below is the only motion left, and it is drawn inside a fixed-size
 * Canvas, so it cannot move anything.
 */
@Composable
private fun ConnectDial(
    transport: Transport,
    stage: TunnelStage,
    progress: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (stage) {
        TunnelStage.RUNNING -> Ios.Green
        TunnelStage.FAILED -> Ios.Red
        else -> transport.tint
    }
    DialCore(
        accent = accent,
        working = stage == TunnelStage.STARTING,
        running = stage == TunnelStage.RUNNING,
        progress = progress,
        label = when (stage) {
            TunnelStage.RUNNING -> S(R.string.disconnect)
            TunnelStage.STARTING -> S(R.string.cancel_2)
            else -> S(R.string.connect)
        },
        onClick = onClick,
        modifier = modifier,
    ) {
        val art = transport.artRes
        if (stage != TunnelStage.RUNNING && art != null) {
            // The transport's own tile, the one on the home screen -- so the button says
            // which product this is at a glance, the way the desktop's hero does.
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(art),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        // The artwork insets its squircle to 90% (see AppIcon.kt).
                        scaleX = 100f / 90f
                        scaleY = 100f / 90f
                    },
            )
        } else {
            Icon(
                when {
                    stage == TunnelStage.RUNNING -> Icons.Default.Power
                    transport.glyphRes != null ->
                        androidx.compose.ui.graphics.vector.ImageVector.vectorResource(transport.glyphRes)
                    else -> transport.icon
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

/**
 * The dial itself, without knowing which engine it belongs to -- «اوپن‌وی‌پی‌ان» has its own
 * service and state and uses this directly, so every connect button in the app is the same one.
 */
@Composable
internal fun DialCore(
    accent: Color,
    working: Boolean,
    running: Boolean,
    progress: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    face: @Composable () -> Unit,
) {
    // Declared only while it is needed. An infiniteRepeatable never stops on its own and keeps
    // asking for a frame every vsync for as long as it is in composition, even when nothing
    // reads it — the same trap the emergency vignette in AppScreen documents.
    val spin = if (working) {
        rememberInfiniteTransition(label = "dial").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "spin",
        ).value
    } else {
        0f
    }
    // Animated so a jump from 12% to 40% reads as motion rather than as a redraw.
    val sweep by animateFloatAsState(
        targetValue = if (progress in 0..100) progress / 100f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "sweep",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(196.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (running) 0.16f else 0.07f))
        )
        Box(
            modifier = Modifier
                .size(168.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.07f))
                .border(1.dp, accent.copy(alpha = 0.28f), CircleShape)
        )

        // The progress arc. Only while connecting: on a settled state motion is noise, not
        // feedback, and on a failed one it would suggest something is still being tried.
        if (working) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .size(168.dp)
                    .then(if (progress in 0..100) Modifier else Modifier.rotate(spin))
            ) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2f
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = if (progress in 0..100) 360f * sweep else 96f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(134.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.62f))
                    )
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                face()
                Spacer(Modifier.height(6.dp))
                Text(
                    label,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** The one line under the dial: what is happening, and the service's own words for why. */
@Composable
private fun StatusLine(
    transport: Transport,
    stage: TunnelStage,
    detail: String?,
    takenBy: Transport?,
    /** This build cannot connect it at all (see [Transport.unavailableReason]): never «ready». */
    unavailable: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val headline = when {
        takenBy != null -> S(R.string.is_connected_right_now, takenBy.labelFa)
        unavailable && stage != TunnelStage.FAILED -> com.mlmvpn.scanner.store.tr("در دسترس نیست", "Not available")
        stage == TunnelStage.RUNNING -> S(R.string.connected)
        stage == TunnelStage.STARTING -> S(R.string.connecting)
        stage == TunnelStage.FAILED -> S(R.string.could_not_connect)
        stage == TunnelStage.STOPPED -> S(R.string.disconnected)
        else -> S(R.string.ready)
    }
    val tone = when {
        takenBy != null -> Ios.Orange
        stage == TunnelStage.RUNNING -> Ios.Green
        stage == TunnelStage.FAILED -> Ios.Red
        stage == TunnelStage.STARTING -> transport.tint
        else -> Ios.SecondaryLabel
    }

    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(headline, color = tone, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        val sub = when {
            takenBy != null ->
                S(R.string.tapping_the_button_disconnects_it_and_brings, transport.labelFa)
            !detail.isNullOrBlank() -> detail
            else -> null
        }
        if (sub != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                sub,
                color = Ios.SecondaryLabel,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * What a live tunnel is actually doing.
 *
 * Shown only while connected, because every field in it is meaningless otherwise, and an empty
 * card that is permanently on screen trains the user to stop reading it.
 */
@Composable
private fun LiveStats(state: TunnelUiState, transport: Transport) {
    // Recomputed once a second while visible; the elapsed time is the only field that changes
    // without a broadcast, and it is the one people look at.
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    com.mlmvpn.scanner.ui.LaunchedWhileVisible(state.connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .frostedGlass(CardShape)
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Metric(S(R.string.connected_for), elapsed(state.connectedAt, now))
            Divider()
            Metric(S(R.string.down), speed(state.speedRx))
            Divider()
            Metric(S(R.string.up), speed(state.speedTx))
        }
        Spacer(Modifier.height(10.dp))
        // Where the tunnel comes out, as a flag and a country name.
        //
        // The address itself is deliberately not shown. It is the input to this answer, not the
        // answer: nobody reads an IPv6 literal to find out which country they appear to be in,
        // and a full one is 39 characters, which is what pushed this card into two lines and
        // then out of its own bounds. It is still measured, still logged, and still on the
        // notification for anyone diagnosing a connection.
        // «URnetwork» leaves through several providers at once: one country only when the user chose
        // one (its live card lists the providers' countries otherwise).
        if (transport != Transport.URNETWORK || state.country.isNotBlank()) {
            ExitCountryCard(code = state.country.trim(), fallbackName = state.countryName.trim())
        }
        // Tor: the bridge it came in through, and why its country is not the exit's.
        if (transport == Transport.TOR) TorEntryCard(exitCode = state.country.trim())

        // What that country actually means on this transport, for the three that ride WARP.
        //
        // Plain WARP is not a way to appear somewhere else, and this surprises people: Cloudflare
        // assigns anycast egress addresses PER USER rather than per region, chosen so that they
        // geolocate near you -- which is deliberate on their side, so that content localisation
        // keeps working. A WARP exit measured from Tehran therefore reads Iran in every
        // geolocation database, and that is the truth about what websites see, not a leak and not
        // a failed tunnel. The traffic is still carried inside the tunnel; only the apparent
        // country is unchanged. Psiphon and Tor are the ones that move it.
        if (transport == Transport.MASQUE || transport == Transport.WIREGUARD ||
            transport == Transport.GOOL
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                S(R.string.warp_picks_an_exit_address_that_geolocates) +
                    S(R.string.usually_stays_in_your_own_country_this) +
                    S(R.string.the_tunnel_to_change_the_apparent_country),
                color = Ios.SecondaryLabel,
                fontSize = 12.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            S(R.string.this_session_s_total_down_up, bytes(state.rxBytes), bytes(state.txBytes)),
            color = Ios.SecondaryLabel,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Ios.Label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(label, color = Ios.SecondaryLabel, fontSize = 11.sp)
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .height(28.dp)
            .size(width = 0.5.dp, height = 28.dp)
            .background(Ios.Separator)
    )
}

// --- formatting -----------------------------------------------------------------------------
//
// Persian digits throughout. The rest of the app renders numbers this way and a card that
// switches to Latin numerals mid-screen is the one thing that gives away a translated UI.

private fun fa(text: String): String {
    val digits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    return buildString {
        for (c in text) append(if (c in '0'..'9') digits[c - '0'] else c)
    }
}

private fun elapsed(since: Long, now: Long): String {
    if (since <= 0L) return fa("00:00")
    val total = ((now - since) / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return fa(if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s))
}

private fun bytes(value: Long): String {
    if (value <= 0L) return fa("0 B")
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return fa(if (v >= 100 || i == 0) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i]))
}

private fun speed(bytesPerSecond: Long): String = "${bytes(bytesPerSecond)}/s"

/** "خودکار" for the auto sentinel, a flag and a localised country name otherwise. */
@Composable
private fun regionLabel(code: String, label: @Composable (String) -> String): String =
    if (code.isBlank() || code.equals("auto", ignoreCase = true)) S(R.string.automatic) else label(code)

internal fun torModeLabel(mode: String): String = when (mode.lowercase()) {
    "direct" -> S(R.string.direct)
    "obfs4" -> "obfs4"
    "meek" -> "Meek"
    "snowflake" -> "Snowflake"
    "webtunnel" -> "WebTunnel"
    "vanilla" -> S(R.string.tor_mode_vanilla)
    else -> S(R.string.automatic)
}
