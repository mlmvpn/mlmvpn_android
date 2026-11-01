package com.mlmvpn.scanner.ui.cfwarp

import android.content.Intent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mlmvpn.core.aether.AetherIp
import com.mlmvpn.core.aether.AetherScan
import com.mlmvpn.core.tunnel.TunnelVpnService
import com.mlmvpn.core.warp.CfWarpEngine
import com.mlmvpn.core.warp.WarpIdRelay
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.emergency.faDigits
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.tunnel.Transport
import com.mlmvpn.scanner.ui.tunnel.TunnelController
import com.mlmvpn.scanner.ui.tunnel.TunnelStage

private fun cfwarpRunning(): Boolean {
    val s = TunnelController.state.value
    return s.active == Transport.CFWG && s.stage == TunnelStage.RUNNING
}

/** A setting changed while «وارپ» is up: reconnect so the engine starts with it. */
private fun applyNow(ctx: android.content.Context) {
    if (!cfwarpRunning()) return
    com.mlmvpn.scanner.utils.SafeStart.service(ctx, Intent(ctx, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_RECONNECT))
}

private fun dateOf(ms: Long): String =
    java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(java.util.Date(ms))

/** «وارپ»'s part of its transport screen: where it comes out, and its own identity. */
@Composable
fun ColumnScope.CfWarpPanel() {
    val ctx = LocalContext.current
    val live by CfWarpEngine.live.collectAsState()
    val tunnel by TunnelController.state.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var identity by remember { mutableStateOf(CfWarpEngine.identityInfo(ctx)) }
    val made = remember(tunnel.stage) { CfWarpEngine.hasIdentity(ctx) }

    if (tunnel.active == Transport.CFWG && tunnel.stage == TunnelStage.RUNNING) {
        SettingsSectionHeader(stringResource(R.string.cfwarp_live_section))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.cfwarp_colo),
                icon = Icons.Default.Cloud,
                tint = Ios.Orange,
                value = live.colo ?: "—",
                showChevron = false,
            )
            live.edge?.let {
                Separator()
                SettingsRow(title = stringResource(R.string.cfwarp_edge), value = it, showChevron = false)
            }
            live.exitIp?.let {
                Separator()
                SettingsRow(title = stringResource(R.string.cfwarp_exit_ip), value = it, showChevron = false)
            }
        }
        SettingsFooter(stringResource(R.string.cfwarp_exit_footer))
    }

    SettingsSectionHeader(stringResource(R.string.cfwarp_identity_section))
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.cfwarp_identity_row),
            icon = Icons.Default.Badge,
            tint = Ios.Teal,
            value = when {
                !made -> stringResource(R.string.cfwarp_identity_none)
                identity.via == WarpIdRelay.VIA_WORKER -> stringResource(R.string.cfwarp_identity_worker)
                identity.via == WarpIdRelay.VIA_FRONT -> stringResource(R.string.cfwarp_identity_front)
                identity.via == WarpIdRelay.VIA_ECH -> stringResource(R.string.cfwarp_identity_ech)
                else -> stringResource(R.string.cfwarp_identity_direct)
            },
            subtitle = identity.at.takeIf { made && it > 0 }?.let { faDigits(dateOf(it)) },
            showChevron = false,
        )
        if (made) {
            Separator()
            SettingsActionRow(
                label = stringResource(R.string.cfwarp_identity_reset),
                icon = Icons.Default.Delete,
                tint = Ios.Red,
                enabled = !cfwarpRunning(),
                onClick = { confirmReset = true },
            )
        }
    }
    SettingsFooter(stringResource(R.string.cfwarp_identity_footer))

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.cfwarp_identity_reset)) },
            text = { Text(stringResource(R.string.cfwarp_identity_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    CfWarpEngine.resetIdentity(ctx)
                    identity = CfWarpEngine.identityInfo(ctx)
                }) { Text(stringResource(R.string.cfwarp_identity_reset_yes), color = Ios.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.geph_cancel)) } },
        )
    }
}

/**
 * «وارپ»'s settings. Every row reaches the engine -- on the desktop some of this page's controls
 * turned out to be decoration, and here none are.
 */
@Composable
fun CfWarpSettingsScreen(onBack: () -> Unit, backLabel: String) {
    val ctx = LocalContext.current
    var scan by remember { mutableStateOf(CfWarpEngine.scan(ctx)) }
    var ip by remember { mutableStateOf(CfWarpEngine.ip(ctx)) }
    var noize by remember { mutableStateOf(CfWarpEngine.noize(ctx)) }
    var keepalive by remember { mutableStateOf(CfWarpEngine.keepalive(ctx)) }

    IosScreen(title = stringResource(R.string.cfwarp_settings_title), onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(12.dp))

        SettingsSectionHeader(stringResource(R.string.cfwarp_scan_section))
        SettingsGroup {
            AetherScan.entries.forEachIndexed { i, m ->
                if (i > 0) Separator()
                SettingsRow(
                    title = m.displayFa,
                    icon = if (m == scan) Icons.Default.Check else Icons.Default.Radar,
                    tint = if (m == scan) Ios.Green else Ios.Gray,
                    showChevron = false,
                    onClick = {
                        scan = m
                        CfWarpEngine.setScan(ctx, m)
                        applyNow(ctx)
                    },
                )
            }
        }
        SettingsFooter(stringResource(R.string.cfwarp_scan_footer))

        SettingsSectionHeader(stringResource(R.string.cfwarp_ip_section))
        SettingsGroup {
            AetherIp.entries.forEachIndexed { i, m ->
                if (i > 0) Separator()
                SettingsRow(
                    title = m.displayFa,
                    icon = if (m == ip) Icons.Default.Check else Icons.Default.Router,
                    tint = if (m == ip) Ios.Green else Ios.Gray,
                    showChevron = false,
                    onClick = {
                        ip = m
                        CfWarpEngine.setIp(ctx, m)
                        applyNow(ctx)
                    },
                )
            }
        }

        SettingsSectionHeader(stringResource(R.string.cfwarp_noize_section))
        SettingsGroup {
            val options = listOf(
                "" to stringResource(R.string.cfwarp_noize_default),
                "light" to stringResource(R.string.cfwarp_noize_light),
                "balanced" to stringResource(R.string.cfwarp_noize_balanced),
                "aggressive" to stringResource(R.string.cfwarp_noize_aggressive),
                "off" to stringResource(R.string.cfwarp_noize_off),
            )
            options.forEachIndexed { i, (value, label) ->
                if (i > 0) Separator()
                SettingsRow(
                    title = label,
                    icon = if (value == noize) Icons.Default.Check else Icons.Default.Tune,
                    tint = if (value == noize) Ios.Green else Ios.Gray,
                    showChevron = false,
                    onClick = {
                        noize = value
                        CfWarpEngine.setNoize(ctx, value)
                        applyNow(ctx)
                    },
                )
            }
        }
        SettingsFooter(stringResource(R.string.cfwarp_noize_footer))

        SettingsSectionHeader(stringResource(R.string.cfwarp_keepalive_section))
        SettingsGroup {
            listOf(15, 25, 45).forEachIndexed { i, sec ->
                if (i > 0) Separator()
                SettingsRow(
                    title = stringResource(R.string.cfwarp_seconds, faDigits(sec.toString())),
                    icon = if (sec == keepalive) Icons.Default.Check else Icons.Default.Timer,
                    tint = if (sec == keepalive) Ios.Green else Ios.Gray,
                    showChevron = false,
                    onClick = {
                        keepalive = sec
                        CfWarpEngine.setKeepalive(ctx, sec)
                        applyNow(ctx)
                    },
                )
            }
        }
        SettingsFooter(stringResource(R.string.cfwarp_keepalive_footer))

        SettingsFooter(stringResource(R.string.cfwarp_settings_apply_footer))
        Spacer(Modifier.height(28.dp))
    }
}
