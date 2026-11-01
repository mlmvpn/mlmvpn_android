package com.mlmvpn.scanner.ui.cfwarp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.tunnel.IpFormatter
import com.mlmvpn.core.tunnel.TunnelVpnService
import com.mlmvpn.core.warp.CfWarpEngine
import com.mlmvpn.core.warp.Nat64Exits
import com.mlmvpn.core.warp.Nat64Probe
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.emergency.faDigits
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.PanelShape
import com.mlmvpn.scanner.ui.tunnel.CountryLabel
import com.mlmvpn.scanner.ui.tunnel.Transport
import com.mlmvpn.scanner.ui.tunnel.TunnelController
import com.mlmvpn.scanner.ui.tunnel.TunnelStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The sub-page that holds the country list. */
const val NAT64_EXIT_PAGE = "nat64_exit"

/** The transports whose exit country a NAT64 gateway chooses: «وارپ», MASQUE and WireGuard. */
fun Transport.hasNat64Exit(): Boolean =
    this == Transport.CFWG || this == Transport.MASQUE || this == Transport.WIREGUARD

/**
 * The transports with an exit country at all: the NAT64 three, and WARP-in-WARP too -- a free proxy
 * after the engine works behind any WARP engine (Ipv4Exits), a NAT64 gateway does not apply to it.
 */
fun Transport.hasExitCountry(): Boolean = hasNat64Exit() || this == Transport.GOOL

/** A change of country while one of them is up: reconnect so the new gateway or proxy is used. */
internal fun reconnectIfUp(ctx: Context) {
    val s = TunnelController.state.value
    if (s.active?.hasExitCountry() != true || s.stage != TunnelStage.RUNNING) return
    com.mlmvpn.scanner.utils.SafeStart.service(ctx, Intent(ctx, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_RECONNECT))
}

private fun fa(n: Number): String = faDigits(n.toString())

/** The row on the transport screen: where the tunnel comes out, one tap from changing it. */
@Composable
fun ColumnScope.Nat64ExitRow(transport: Transport? = null, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val selection by Nat64Exits.selectionFlow(ctx).collectAsState()
    val ipv4 by com.mlmvpn.core.warp.Ipv4Exits.stateFlow(ctx).collectAsState()
    val v4 = ipv4?.takeIf { it.enabled && it.country.isNotBlank() }?.country
    // WARP-in-WARP takes no NAT64 gateway: for it only the free-proxy exit counts.
    val sel = if (transport == Transport.GOOL) Nat64Exits.DIRECT else selection.orEmpty()
    SettingsSectionHeader(stringResource(R.string.nat64_exit_title))
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.nat64_exit_title),
            icon = Icons.Default.Public,
            tint = Ios.Indigo,
            value = when {
                v4 != null -> CountryLabel.withFlag(v4) + " · IPv4"
                sel == Nat64Exits.DIRECT -> stringResource(R.string.nat64_direct)
                sel == Nat64Exits.FASTEST -> "⚡ " + stringResource(R.string.nat64_fastest)
                else -> CountryLabel.withFlag(sel) + " · IPv6"
            },
            onClick = onOpen,
        )
    }
    SettingsFooter(
        when {
            v4 != null -> com.mlmvpn.scanner.store.tr(
                "از راه یک پراکسی رایگان در همان کشور، پشت وارپ — روی هر اینترنتی، فقط TCP. کشور خروجی بعد از اتصال با یک درخواست واقعی سنجیده می‌شود.",
                "Through a free proxy in that country, behind WARP -- any internet, TCP only. The exit country is measured with a real request after connecting.")
            sel.isBlank() -> stringResource(R.string.nat64_row_footer_direct)
            else -> stringResource(R.string.nat64_row_footer_country)
        },
    )
}

/**
 * The country list.
 *
 * Top to bottom in the order a user needs it: the ping (so the numbers are there before a choice
 * is made), the direct connection with what it means, the fastest country, then every country,
 * fastest first once there are numbers. Holding a country copies a WireGuard config that comes out
 * there; the user's own NAT64 gateways can be added at the bottom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Nat64ExitScreen(onBack: () -> Unit, backLabel: String, transport: Transport? = null) {
    val ctx = LocalContext.current
    val selection by Nat64Exits.selectionFlow(ctx).collectAsState()
    val probes by Nat64Exits.probes.collectAsState()
    val ping by Nat64Probe.state.collectAsState()
    var version by remember { mutableStateOf(0) }   // bumps when the catalogue changes
    var adding by remember { mutableStateOf(false) }
    // Two ways to a country, as on the desktop: IPv6 (a NAT64 gateway) or IPv4 (a free proxy after the
    // engine). The page opens on the one in use; WARP-in-WARP has only the IPv4 way.
    val ipv4Only = transport == Transport.GOOL
    var way by remember {
        mutableStateOf(if (ipv4Only || com.mlmvpn.core.warp.Ipv4Exits.isOn(ctx)) ExitWay.IPV4 else ExitWay.IPV6)
    }

    LaunchedEffect(Unit) {
        Nat64Exits.loadProbes(ctx)
        withContext(Dispatchers.IO) { Nat64Exits.refreshIfStale(ctx) }
        version++
    }

    val gateways = remember(version) { Nat64Exits.gateways(ctx) }
    val byCountry = gateways.groupBy { it.country }
    fun best(cc: String) = byCountry[cc].orEmpty().mapNotNull { g -> probes[g.key]?.takeIf { it.ok } }
        .minWithOrNull(Nat64Exits.BETTER_PROBE)
    fun anyTested(cc: String) = byCountry[cc].orEmpty().any { probes[it.key] != null }
    // In the order «سریع‌ترین» picks from: a country whose ping only answered the trace (no speed)
    // comes after every one that carried the download, whatever its round trip.
    val countries = remember(version, probes) {
        Nat64Exits.countries(ctx).sortedWith(
            compareBy<String> {
                val b = best(it)
                when {
                    b != null && b.mbps != null -> 0
                    b != null -> 1
                    !anyTested(it) -> 2
                    else -> 3
                }
            }.thenBy { best(it)?.rttMs ?: Long.MAX_VALUE },
        )
    }

    fun choose(value: String) {
        Nat64Exits.setSelection(ctx, value)
        // «اتصال مستقیم» means no exit country at all: the free-proxy exit goes off with it.
        if (value == Nat64Exits.DIRECT) com.mlmvpn.core.warp.Ipv4Exits.disable(ctx)
        reconnectIfUp(ctx)
        onBack()
    }

    IosScreen(title = stringResource(R.string.nat64_exit_title), onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(10.dp))
        if (!ipv4Only) {
            ExitWaySwitch(way) { way = it }
            Spacer(Modifier.height(12.dp))
        }
        if (way == ExitWay.IPV4) {
            Ipv4ExitSection(onChanged = { reconnectIfUp(ctx) })
            Spacer(Modifier.height(28.dp))
        } else {

        // ── the ping ──
        ExitRow(
            flag = if (ping.running) "⏳" else "📶",
            name = if (ping.running) stringResource(R.string.nat64_ping_running, fa(ping.done), fa(ping.total))
                   else stringResource(R.string.nat64_ping_all),
            detail = when {
                ping.running -> stringResource(R.string.nat64_ping_stop)
                ping.error == "no_ipv6" -> stringResource(R.string.nat64_no_ipv6)
                ping.error == "engine" -> stringResource(R.string.nat64_engine_missing)
                ping.error != null -> stringResource(R.string.nat64_ping_failed)
                else -> stringResource(R.string.nat64_ping_detail)
            },
            selected = false,
            onClick = { if (ping.running) Nat64Probe.cancel() else Nat64Probe.start(ctx) },
        )
        Spacer(Modifier.height(14.dp))

        // ── direct and fastest ──
        ExitRow(
            flag = "🇮🇷",
            name = stringResource(R.string.nat64_direct),
            detail = stringResource(R.string.nat64_direct_detail),
            selected = selection.isNullOrBlank(),
            onClick = { choose(Nat64Exits.DIRECT) },
        )
        Spacer(Modifier.height(6.dp))
        ExitRow(
            flag = "⚡",
            name = stringResource(R.string.nat64_fastest),
            detail = stringResource(R.string.nat64_fastest_detail),
            selected = selection == Nat64Exits.FASTEST,
            onClick = { choose(Nat64Exits.FASTEST) },
        )

        // ── the countries ──
        SettingsSectionHeader(stringResource(R.string.nat64_countries_section))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (cc in countries) {
                val list = byCountry[cc].orEmpty()
                val testing = list.any { it.key in ping.testing }
                val b = best(cc)
                val seen = b?.exitLoc?.takeIf { !it.equals(cc, ignoreCase = true) }
                ExitRow(
                    flag = if (testing) "⏳" else IpFormatter.flag(cc),
                    name = CountryLabel.localized(cc),
                    detail = when {
                        testing -> stringResource(R.string.nat64_testing)
                        b != null -> buildString {
                            append(stringResource(R.string.nat64_result_rtt, fa(b.rttMs ?: 0)))
                            b.mbps?.let { append(" · ").append(stringResource(R.string.nat64_result_speed, faDigits("%.1f".format(java.util.Locale.US, it)))) }
                            seen?.let { append(" · ").append(stringResource(R.string.nat64_exit_seen, it)) }
                        }
                        anyTested(cc) -> stringResource(R.string.nat64_dead)
                        else -> stringResource(R.string.nat64_untested, fa(list.size))
                    },
                    detailColor = when {
                        b != null -> Ios.Green
                        anyTested(cc) && !testing -> Ios.Red
                        else -> Ios.SecondaryLabel
                    },
                    selected = selection.equals(cc, ignoreCase = true),
                    onClick = { choose(cc) },
                    onLongClick = { copyConfig(ctx, cc) },
                )
            }
        }

        // ── the user's own gateways ──
        val custom = gateways.filter { it.source == Nat64Exits.Source.CUSTOM }
        SettingsSectionHeader(stringResource(R.string.nat64_custom_section))
        SettingsGroup {
            custom.forEach { g ->
                SettingsRow(
                    title = "${IpFormatter.flag(g.country)} ${g.prefix}",
                    icon = Icons.Default.Delete,
                    tint = Ios.Red,
                    showChevron = false,
                    onClick = {
                        Nat64Exits.removeCustom(ctx, g.prefix)
                        version++
                    },
                )
                Separator()
            }
            SettingsActionRow(
                label = stringResource(R.string.nat64_add_custom),
                icon = Icons.Default.Add,
                tint = Ios.Blue,
                onClick = { adding = true },
            )
        }

        SettingsFooter(stringResource(R.string.nat64_footer))
        Spacer(Modifier.height(28.dp))
        }
    }

    if (adding) {
        var prefix by remember { mutableStateOf("") }
        var country by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val badPrefix = stringResource(R.string.nat64_custom_bad_prefix)
        val badCountry = stringResource(R.string.nat64_custom_bad_country)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.nat64_add_custom)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = prefix, onValueChange = { prefix = it.trim() }, singleLine = true,
                        label = { Text(stringResource(R.string.nat64_custom_prefix)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = country, onValueChange = { country = it.trim().take(2).uppercase() }, singleLine = true,
                        label = { Text(stringResource(R.string.nat64_custom_country)) },
                    )
                    error?.let { Text(it, color = Ios.Red, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (Nat64Exits.addCustom(ctx, prefix, country)) {
                        null -> { adding = false; version++ }
                        "country" -> error = badCountry
                        else -> error = badPrefix
                    }
                }) { Text(stringResource(R.string.nat64_add)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.geph_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExitRow(
    flag: String,
    name: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    detailColor: androidx.compose.ui.graphics.Color = Ios.SecondaryLabel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (onLongClick == null) 16.dp else 0.dp)
            .frostedGlass(PanelShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = Ios.Label, fontSize = 16.sp, maxLines = 1)
            if (detail.isNotBlank()) Text(detail, color = detailColor, fontSize = 11.sp, maxLines = 3)
        }
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.Check, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * A plain WireGuard config that comes out in [country]: «وارپ»'s own identity, with the endpoint
 * written through the country's best gateway. For any WireGuard app; without the obfuscation the
 * app adds, so on a line that filters plain WireGuard it may not connect where the app does.
 */
private fun copyConfig(ctx: Context, country: String) {
    val toml = runCatching { java.io.File(CfWarpEngine.dataDir(ctx), "aether.toml").readText() }.getOrNull()
    fun field(k: String) = toml?.let { Regex("""(?m)^$k\s*=\s*"([^"]*)"""").find(it)?.groupValues?.get(1) }
    val key = field("wg_private_key")
    val v4 = field("ipv4")
    if (key.isNullOrBlank() || v4.isNullOrBlank()) {
        Toast.makeText(ctx, ctx.getString(R.string.nat64_copy_needs_identity), Toast.LENGTH_LONG).show()
        return
    }
    val v6 = field("ipv6").orEmpty()
    val peer = field("wg_peer_public_key") ?: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    val gw = Nat64Exits.gatewaysOf(ctx, country).sortedByDescending { Nat64Exits.probes.value[it.key]?.ok == true || it.verified }
        .firstOrNull() ?: return
    val endpoint = Nat64Exits.endpoint(gw, Nat64Exits.Proto.WG) ?: return
    val conf = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $key")
        appendLine("Address = $v4/32" + if (v6.isNotBlank()) ", $v6/128" else "")
        appendLine("DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001")
        appendLine("MTU = 1280")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $peer")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("Endpoint = $endpoint")
    }
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("WireGuard $country", conf))
    Toast.makeText(ctx, ctx.getString(R.string.nat64_copied, country), Toast.LENGTH_SHORT).show()
}
