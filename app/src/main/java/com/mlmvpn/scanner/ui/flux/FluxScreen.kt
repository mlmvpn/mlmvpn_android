package com.mlmvpn.scanner.ui.flux

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.flux.FluxEngine
import com.mlmvpn.scanner.engines.flux.core.model.FailureKind
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxUiState
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.model.Tri
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosOption
import com.mlmvpn.scanner.ui.settings.IosPickerScreen
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.IosTextScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class Page { MAIN, COUNTRY, DIAGNOSTICS, ADD_SUB }

/**
 * FLUX's screen: a country, an IP version, and Connect. Nothing else. Protocols, nodes, edges,
 * fragmenting and the rest are FLUX's business; the developer view behind a long press on the
 * title shows them for debugging and is never part of normal use.
 */
@Composable
fun FluxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(FluxEngine.isInitialized()) }
    LaunchedEffect(Unit) {
        if (!ready) { withContext(Dispatchers.IO) { FluxEngine.init(context) }; ready = true }
    }
    if (!ready) {
        IosScreen(title = stringResource(R.string.flux_title_short), onBack = onBack, backLabel = stringResource(R.string.home)) {}
        return
    }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    androidx.activity.compose.BackHandler(enabled = page != Page.MAIN) {
        page = if (page == Page.ADD_SUB) Page.DIAGNOSTICS else Page.MAIN
    }
    when (page) {
        Page.COUNTRY -> { CountryPicker(onBack = { page = Page.MAIN }); return }
        Page.DIAGNOSTICS -> { FluxDiagnosticsScreen(onBack = { page = Page.MAIN }, onAddSub = { page = Page.ADD_SUB }); return }
        Page.ADD_SUB -> { AddSubscription(onBack = { page = Page.DIAGNOSTICS }); return }
        Page.MAIN -> Unit
    }
    FluxMain(onBack, onCountry = { page = Page.COUNTRY }, onDiagnostics = { page = Page.DIAGNOSTICS })
}

@Composable
private fun FluxMain(onBack: () -> Unit, onCountry: () -> Unit, onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val state by FluxEngine.store.state.collectAsState()
    val ui by FluxEngine.ui.collectAsState()
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val nodeId by MyVpnService.connectedNodeIdFlow.collectAsState()
    val ours = nodeId == FluxEngine.NODE_ID && phase != MyVpnService.Phase.IDLE
    val prefs = state.prefs
    val searching = ui is FluxUiState.Searching
    // A FLUX tunnel can be up without this screen having started it (the tile, a restart of the
    // app): it still shows as connected, and can still be disconnected.
    val connected = ui is FluxUiState.Connected || (ours && phase == MyVpnService.Phase.CONNECTED && !searching)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) FluxEngine.connectAsync(context)
    }
    fun connect() = com.mlmvpn.scanner.data.ScanGuard.run(com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN) {
        val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
        if (prep != null) launcher.launch(prep) else FluxEngine.connectAsync(context)
    }

    // No bar title: the big one below is the page's only "FLUX" (and the developer's door).
    IosScreen(onBack = onBack, backLabel = stringResource(R.string.home)) {
        Spacer(Modifier.height(12.dp))
        // The title is also the developer's door: a long press opens diagnostics.
        Text(
            stringResource(R.string.flux_title_short),
            color = Ios.Label, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().longPress(onDiagnostics),
        )
        Spacer(Modifier.height(18.dp))

        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.flux_country),
                icon = Icons.Default.Public, tint = Ios.Blue,
                value = prefs.country?.let { countryLabel(it) } ?: stringResource(R.string.flux_country_auto),
                // Open while connected too: a new country moves the tunnel to a route for it.
                onClick = onCountry,
            )
        }
        SettingsSectionHeader(stringResource(R.string.flux_ip))
        IpSelector(prefs.ipMode, enabled = true) { m ->
            if (m != prefs.ipMode) { FluxEngine.setIpMode(m); FluxEngine.applyChoice(context) }
        }

        Spacer(Modifier.height(30.dp))
        ConnectButton(connected, searching) {
            if (connected || searching) FluxEngine.disconnectAsync(context) else connect()
        }
        Spacer(Modifier.height(14.dp))
        Text(
            statusText(ui, connected, searching),
            color = if (ui is FluxUiState.Failed) Ios.Orange else Ios.SecondaryLabel,
            fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        com.mlmvpn.scanner.ui.RouteChips()
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun statusText(ui: FluxUiState, connected: Boolean, searching: Boolean): String = when {
    searching -> stringResource(R.string.flux_status_searching)
    ui is FluxUiState.Connected -> listOfNotNull(
        stringResource(R.string.flux_status_connected),
        ui.countryCode?.let { "${flag(it)} ${countryLabel(it)}" },
        if (ui.family == Family.V6) "IPv6" else "IPv4",
        ui.latencyMs?.let { "$it ms" },
    ).joinToString(" · ")
    connected -> stringResource(R.string.flux_status_connected)
    ui is FluxUiState.Failed -> when (ui.reason) {
        FailureKind.OFFLINE -> stringResource(R.string.flux_fail_offline)
        FailureKind.NO_NODES -> stringResource(R.string.flux_fail_no_nodes)
        FailureKind.NOTHING_WORKS -> stringResource(R.string.flux_fail_nothing)
        FailureKind.NO_ROUTE_FOR_COUNTRY -> stringResource(R.string.flux_fail_country) +
            (ui.suggestion?.let { s -> "\n" + stringResource(R.string.flux_fail_country_try, s.split(" · ").joinToString(" · ") { countryLabel(it) }) } ?: "")
        FailureKind.FAMILY_UNAVAILABLE -> stringResource(R.string.flux_fail_family)
        FailureKind.NO_ROUTE_FOR_FAMILY -> stringResource(R.string.flux_fail_family_route)
        FailureKind.VPN_REFUSED -> stringResource(R.string.flux_fail_vpn)
    }
    else -> stringResource(R.string.flux_status_idle)
}

@Composable
private fun IpSelector(mode: IpMode, enabled: Boolean, onSelect: (IpMode) -> Unit) {
    val options = listOf(IpMode.V4 to "IPv4", IpMode.V6 to "IPv6", IpMode.BOTH to stringResource(R.string.flux_ip_both))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (m, label) ->
            val sel = m == mode
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Ios.Blue else Color.Transparent)
                    .then(if (enabled) Modifier.clickable(role = Role.RadioButton) { onSelect(m) } else Modifier)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) Color.White else Ios.Label, fontSize = 15.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun ConnectButton(connected: Boolean, busy: Boolean, onClick: () -> Unit) {
    val ring = when {
        connected -> Ios.Green
        busy -> Ios.Orange
        else -> Ios.Gray
    }
    val label = stringResource(if (connected || busy) R.string.flux_disconnect else R.string.flux_connect)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(ring.copy(alpha = 0.18f))
                .border(3.dp, ring, CircleShape)
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = ring, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CountryPicker(onBack: () -> Unit) {
    val context = LocalContext.current
    // Worked out off the main thread: it parses every cached source list (thousands of links), and
    // on the main thread the FLUX screen froze while the picker opened (freeze report, 1.2.41).
    val countries by androidx.compose.runtime.produceState(emptyList<FluxEngine.CountryOption>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { FluxEngine.countries() }
    }
    val current = FluxEngine.prefs().country
    val here = stringResource(R.string.flux_country_here)
    val unchecked = stringResource(R.string.flux_country_unchecked)
    val unverified = stringResource(R.string.flux_country_unverified)
    val options = listOf(IosOption("", stringResource(R.string.flux_country_auto))) +
        countries.map { c ->
            IosOption(c.code, "${flag(c.code)}  ${countryLabel(c.code)}",
                when { c.availableHere -> here; c.verified -> unchecked; else -> unverified })
        }
    IosPickerScreen(
        title = stringResource(R.string.flux_country),
        options = options,
        selectedKey = current ?: "",
        onSelect = { key ->
            val cc = key.ifEmpty { null }
            if (cc != current) { FluxEngine.setCountry(cc); FluxEngine.applyChoice(context) }
        },
        onBack = onBack,
        backLabel = stringResource(R.string.flux_title_short),
        footer = stringResource(R.string.flux_country_footer),
    )
}

/** Developer view. Never shows a credential: candidates are printed redacted. */
@Composable
private fun FluxDiagnosticsScreen(onBack: () -> Unit, onAddSub: () -> Unit) {
    val diag by FluxEngine.diagnostics.collectAsState()
    var subs by remember { mutableStateOf(FluxEngine.userSubscriptions()) }
    IosScreen(title = stringResource(R.string.flux_diag_title), onBack = onBack, backLabel = stringResource(R.string.flux_title_short)) {
        SettingsSectionHeader("Network")
        Mono(listOfNotNull(
            "net: ${diag.net.ifEmpty { "—" }}",
            diag.verdict?.let { v -> "cloudflare v4=${tri(v.cfV4)} v6=${tri(v.cfV6)} · ipv6=${tri(v.v6)} · udp=${tri(v.udp)}" },
            "failovers: ${diag.failovers} · last race: ${diag.lastRaceMs} ms",
        ))
        SettingsSectionHeader("In tunnel (primary first)")
        Mono(diag.inTunnel.ifEmpty { listOf("—") })
        SettingsSectionHeader("Why this route")
        Mono(diag.why.ifEmpty { listOf("—") })
        SettingsSectionHeader("Last race")
        Mono(diag.lastRace.map { r ->
            val s1 = when (r.reachOk) { true -> "✓${r.reachMs ?: ""}"; false -> "✗"; null -> "·" }
            val s2 = when (r.realOk) { true -> "✓${r.rttMs}ms"; false -> "✗"; null -> "·" }
            "$s1 $s2 ${r.country ?: "??"} ${r.reason ?: ""} ${r.score?.let { "%.2f".format(it) } ?: ""}\n  ${r.label}"
        }.ifEmpty { listOf("—") })

        SettingsSectionHeader(stringResource(R.string.flux_diag_subs))
        SettingsGroup {
            subs.forEachIndexed { i, url ->
                if (i > 0) Separator()
                SettingsActionRow(label = url.take(60), icon = Icons.Default.Delete, tint = Ios.Red, onClick = {
                    subs = subs - url; FluxEngine.setUserSubscriptions(subs)
                })
            }
            if (subs.isNotEmpty()) Separator()
            SettingsActionRow(label = stringResource(R.string.flux_diag_add_sub), icon = Icons.Default.Add, tint = Ios.Blue, onClick = onAddSub)
        }
        SettingsFooter(stringResource(R.string.flux_diag_subs_footer))
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun AddSubscription(onBack: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val valid = com.mlmvpn.scanner.engines.flux.core.source.FluxSources.isHttpsUrl(value)
    IosTextScreen(
        title = stringResource(R.string.flux_diag_add_sub),
        value = value,
        onValueChange = { value = it },
        onBack = {
            if (valid) FluxEngine.setUserSubscriptions(FluxEngine.userSubscriptions() + value.trim())
            onBack()
        },
        backLabel = stringResource(R.string.flux_diag_title),
        placeholder = "https://…",
        error = if (value.isNotBlank() && !valid) stringResource(R.string.flux_diag_sub_invalid) else null,
    )
}

@Composable
private fun Mono(lines: List<String>) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(RoundedCornerShape(14.dp)).padding(12.dp),
    ) {
        lines.forEach { Text(it, color = Ios.Label, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp) }
    }
}

private fun tri(t: Tri) = when (t) { Tri.YES -> "yes"; Tri.NO -> "NO"; Tri.UNKNOWN -> "?" }

/** A country's name in the app's language. */
private fun countryLabel(cc: String): String =
    Locale("", cc).getDisplayCountry(Locale.getDefault()).ifBlank { cc }

/** A country's flag from its two-letter code. */
private fun flag(cc: String): String =
    if (cc.length != 2 || !cc.all { it.isLetter() }) cc
    else cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

/** A developer's door, not a button: no click role for a screen reader to announce (as in MAE). */
private fun Modifier.longPress(onLongPress: () -> Unit) =
    this.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
