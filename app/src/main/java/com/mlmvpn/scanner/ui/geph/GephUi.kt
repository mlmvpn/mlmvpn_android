package com.mlmvpn.scanner.ui.geph

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mlmvpn.core.geph.GephAccount
import com.mlmvpn.core.geph.GephEngine
import com.mlmvpn.core.geph.GephExitChoice
import com.mlmvpn.core.geph.GephSettings
import com.mlmvpn.core.tunnel.TunnelVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.emergency.faDigits
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.tunnel.CountryLabel
import com.mlmvpn.scanner.ui.tunnel.Transport
import com.mlmvpn.scanner.ui.tunnel.TunnelController
import com.mlmvpn.scanner.ui.tunnel.TunnelStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The pages «گف» adds to its transport screen. See TransportHost. */
object GephPages {
    const val ACCOUNT = "geph_account"
    const val EXIT = "geph_exit"
    const val NEWS = "geph_news"
    const val SESSIONS = "geph_sessions"
}

/** Whether «گف» is the transport currently carrying traffic. */
internal fun gephRunning(): Boolean {
    val s = TunnelController.state.value
    return s.active == Transport.GEPH && s.stage == TunnelStage.RUNNING
}

/**
 * A Geph setting changed. If Geph is up, its engine is restarted with the new setting -- the TUN
 * stays up across it, so nothing reaches the bare link while it happens.
 */
internal fun applyGephNow(ctx: Context) {
    if (!gephRunning()) return
    com.mlmvpn.scanner.utils.SafeStart.service(ctx, Intent(ctx, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_GEPH_APPLY))
}

/**
 * Coverage changed (whole device <-> proxy only). That is the TUN appearing or going away, which an
 * engine restart cannot do -- so the session is reconnected, the way the service's own quick
 * reconnect does it.
 */
internal fun reconnectGephNow(ctx: Context) {
    if (!gephRunning()) return
    com.mlmvpn.scanner.utils.SafeStart.service(ctx, Intent(ctx, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_RECONNECT))
}

internal fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, ctx.getString(R.string.geph_copied), Toast.LENGTH_SHORT).show()
}

/** A date in the app's language: the Persian calendar in Persian, Gregorian in English. */
internal fun gephDate(unixSeconds: Long): String {
    val farsi = com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()
    val locale = if (farsi) android.icu.util.ULocale("fa_IR@calendar=persian") else android.icu.util.ULocale.ENGLISH
    val fmt = android.icu.text.DateFormat.getDateInstance(android.icu.text.DateFormat.MEDIUM, locale)
    return fmt.format(java.util.Date(unixSeconds * 1000))
}

internal fun mbitText(mbit: Double): String = faDigits(String.format(java.util.Locale.US, "%.2f", mbit))

@Composable
internal fun levelLabel(info: GephAccount.Info?): String = when (info?.level) {
    null -> stringResource(R.string.geph_level_unknown)
    GephAccount.Level.FREE -> stringResource(R.string.geph_level_free)
    GephAccount.Level.BASIC -> stringResource(R.string.geph_level_basic)
    GephAccount.Level.PLUS -> stringResource(R.string.geph_level_plus)
}

@Composable
internal fun exitLabel(exit: GephExitChoice): String = when {
    exit.isAuto -> stringResource(R.string.geph_exit_auto)
    exit.city.isNullOrBlank() -> CountryLabel.withFlag(exit.country!!)
    else -> CountryLabel.withFlag(exit.country!!) + " · " + exit.city
}

/**
 * «گف»'s part of its transport screen: the account, the exit, and the pages behind them. The
 * generic rows under it (advanced settings -> GephSettingsScreen, the connection report) are the
 * transport screen's own.
 */
@Composable
fun ColumnScope.GephPanel(onOpenPage: (String) -> Unit) {
    val ctx = LocalContext.current
    var hasAccount by remember { mutableStateOf(GephAccount.hasAccount(ctx)) }
    var info by remember { mutableStateOf(GephAccount.cachedInfo(ctx)) }
    val exit = remember { mutableStateOf(GephSettings.exit(ctx)) }
    val tunnel by TunnelController.state.collectAsState()

    // Every time the screen comes back to the front (after the account or exit page).
    LaunchedEffect(tunnel.stage) {
        hasAccount = GephAccount.hasAccount(ctx)
        exit.value = GephSettings.exit(ctx)
        if (hasAccount) {
            info = withContext(Dispatchers.IO) { GephAccount.refreshInfo(ctx).getOrNull() } ?: GephAccount.cachedInfo(ctx)
        }
    }

    SettingsSectionHeader(stringResource(R.string.geph_account_section))
    SettingsGroup {
        if (!hasAccount) {
            SettingsActionRow(
                label = stringResource(R.string.geph_make_free_account),
                icon = Icons.Default.PersonAdd,
                tint = Ios.Blue,
                onClick = { onOpenPage(GephPages.ACCOUNT) },
            )
        } else {
            SettingsRow(
                title = stringResource(R.string.geph_account_row),
                icon = Icons.Default.AccountCircle,
                tint = Ios.Blue,
                value = levelLabel(info),
                subtitle = info?.plusExpiresUnix?.takeIf { info?.level != GephAccount.Level.FREE }
                    ?.let { stringResource(R.string.geph_until, gephDate(it)) },
                onClick = { onOpenPage(GephPages.ACCOUNT) },
            )
        }
    }
    if (!hasAccount) SettingsFooter(stringResource(R.string.geph_account_footer_none))

    SettingsSectionHeader(stringResource(R.string.geph_exit_section))
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.geph_exit_row),
            icon = Icons.Default.Language,
            tint = Ios.Indigo,
            value = exitLabel(exit.value),
            onClick = { onOpenPage(GephPages.EXIT) },
        )
        GephSettings.lastFastest(ctx)?.takeIf { it.has("country") }?.let { f ->
            Separator()
            SettingsRow(
                title = stringResource(R.string.geph_last_fastest),
                icon = Icons.Default.Speed,
                tint = Ios.Green,
                value = CountryLabel.withFlag(f.optString("country")) + " · " +
                    stringResource(R.string.geph_mbit, mbitText(f.optDouble("mbit"))),
                onClick = { onOpenPage(GephPages.EXIT) },
            )
        }
    }
    SettingsFooter(stringResource(R.string.geph_exit_footer))

    SettingsSectionHeader(stringResource(R.string.geph_more_section))
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.geph_sessions_row),
            icon = Icons.Default.Hub,
            tint = Ios.Teal,
            onClick = { onOpenPage(GephPages.SESSIONS) },
        )
        Separator()
        SettingsRow(
            title = stringResource(R.string.geph_news_row),
            icon = Icons.Default.Campaign,
            tint = Ios.Orange,
            onClick = { onOpenPage(GephPages.NEWS) },
        )
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * Live figures for a connected Geph session: which server, over what, how far away, and a speed
 * test that really crosses the tunnel.
 */
@Composable
fun GephLiveCard() {
    val live by GephEngine.live.collectAsState()
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<GephEngine.Speed?>(null) }
    var failed by remember { mutableStateOf(false) }

    SettingsSectionHeader(stringResource(R.string.geph_live_section))
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.geph_live_server),
            value = live.exitCountry?.let { c ->
                CountryLabel.withFlag(c) + (live.exitCity?.let { " · $it" } ?: "")
            } ?: "—",
            showChevron = false,
        )
        Separator()
        SettingsRow(
            title = stringResource(R.string.geph_live_protocol),
            value = live.protocol ?: "—",
            subtitle = if (live.sessions.any { it.bridge == null }) stringResource(R.string.geph_live_direct)
                else stringResource(R.string.geph_live_bridged),
            showChevron = false,
        )
        Separator()
        SettingsRow(
            title = stringResource(R.string.geph_live_ping),
            value = live.pingMs?.let { faDigits(it.toString()) + " ms" } ?: "—",
            showChevron = false,
        )
        live.exitIp?.let { ip ->
            Separator()
            SettingsRow(title = stringResource(R.string.geph_live_ip), value = ip, showChevron = false)
        }
        Separator()
        SettingsActionRow(
            label = when {
                testing -> stringResource(R.string.geph_speed_testing)
                result != null -> stringResource(
                    R.string.geph_speed_result,
                    mbitText(result!!.mbit),
                    faDigits(result!!.ttfbMs.toString()),
                )
                failed -> stringResource(R.string.geph_speed_failed)
                else -> stringResource(R.string.geph_speed_test)
            },
            icon = Icons.Default.Speed,
            tint = Ios.Green,
            busy = testing,
            onClick = {
                testing = true
                failed = false
                scope.launch {
                    val r = withContext(Dispatchers.IO) { GephEngine.speedTest() }
                    result = r
                    failed = r == null
                    testing = false
                }
            },
        )
    }
    SettingsFooter(stringResource(R.string.geph_speed_footer))
}
