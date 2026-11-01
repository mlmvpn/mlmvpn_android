package com.mlmvpn.scanner.ui.emergency

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.gst.GstConfigManager
import com.mlmvpn.scanner.engines.gst.GstDiagnostics
import com.mlmvpn.scanner.engines.gst.GstLog
import com.mlmvpn.scanner.engines.gst.GstRelay
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.ControlShape
import com.mlmvpn.scanner.ui.tlsPing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The pages the Google Script panel pushes.
//
// All three were dialogs before -- an AlertDialog for the log, a full-bleed Dialog with a TabRow
// for the SNI/IP scanner, and a second AlertDialog listing relays that needed authorizing. A
// dialog is the wrong container for every one of them: they are not questions, they are places
// you go and come back from, and iOS pushes a page for that. Pushing also means the trailing
// value of the row that opens them can say what they currently hold, which is the whole reason
// the panel can be read without opening anything.
// =================================================================================================

/**
 * A getter, not a `val`: the language can change while the process lives, and a top-level
 * property would be initialised once at class load and then say "Back" in Persian forever.
 */
internal val GST_BACK: String get() = S(R.string.back_r2)

/** What a relay's last probe means, as one word and one colour. */
@Composable
internal fun relayTone(report: GstDiagnostics.Report?): Color = when (report?.result) {
    null -> Ios.Gray
    GstDiagnostics.Result.OK -> Ios.Green
    GstDiagnostics.Result.REDIRECT_BLOCKED,
    GstDiagnostics.Result.FRONT_BLOCKED,
    GstDiagnostics.Result.KEY_NOT_USED -> Ios.Orange
    else -> Ios.Red
}

internal fun relayVerdict(report: GstDiagnostics.Report?): String = when (report?.result) {
    null -> S(R.string.not_measured_r2)
    GstDiagnostics.Result.OK -> S(R.string.healthy)
    GstDiagnostics.Result.REDIRECT_BLOCKED -> S(R.string.not_verified)
    GstDiagnostics.Result.AUTH_MISMATCH -> S(R.string.wrong_password)
    GstDiagnostics.Result.UNREACHABLE -> S(R.string.unreachable)
    GstDiagnostics.Result.NOT_FOUND -> S(R.string.gst_verdict_not_found)
    GstDiagnostics.Result.FRONT_BLOCKED -> S(R.string.gst_verdict_front_blocked)
    GstDiagnostics.Result.KEY_NOT_USED -> S(R.string.gst_verdict_key_not_used)
    else -> S(R.string.invalid_response)
}

/** A Deployment ID is 60-odd characters of base64; nobody reads the middle of it. */
internal fun shortDeploymentId(id: String): String =
    if (id.length > 20) id.take(11) + "…" + id.takeLast(6) else id

// -------------------------------------------------------------------------------------------
// One relay
// -------------------------------------------------------------------------------------------

/**
 * Everything about a single relay, on its own page.
 *
 * The list row used to carry a pencil and a bin as two 48dp icon buttons, which put "delete this
 * relay for good" one mis-tap away from "open it" -- on a row whose whole content was a truncated
 * id. Destructive actions belong at the bottom of a detail page, behind a deliberate second tap,
 * which is where this one is.
 */
@Composable
fun GstRelayScreen(
    number: Int,
    relay: GstRelay,
    report: GstDiagnostics.Report?,
    testing: Boolean,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var confirmRemove by remember { mutableStateOf(false) }

    IosScreen(title = S(R.string.relay, faDigits(number.toString())), onBack = onBack, backLabel = GST_BACK) {
        Spacer(Modifier.height(14.dp))

        SettingsSectionHeader(S(R.string.status_r2))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.last_measured),
                icon = Icons.Default.NetworkCheck,
                tint = relayTone(report),
                value = relayVerdict(report),
                showChevron = false,
            )
            Separator()
            SettingsActionRow(
                label = if (testing) S(R.string.measuring_r2) else S(R.string.measure_this_relay),
                icon = Icons.Default.NetworkCheck,
                busy = testing,
                onClick = onTest,
            )
            if (report?.result == GstDiagnostics.Result.REDIRECT_BLOCKED) {
                Separator()
                SettingsActionRow(
                    label = S(R.string.verify_in_the_browser),
                    icon = Icons.Default.OpenInNew,
                    tint = Ios.Orange,
                    onClick = { openExternalUrl(context, GstDiagnostics.execUrl(relay.deploymentId)) },
                )
            }
        }
        if (report != null) SettingsFooter(report.message)

        SettingsSectionHeader(S(R.string.deployment_id))
        SettingsGroup {
            SettingsRow(
                title = "Deployment ID",
                icon = Icons.Default.Dns,
                tint = Ios.Indigo,
                value = shortDeploymentId(relay.deploymentId),
                showChevron = false,
            )
            Separator()
            SettingsActionRow(
                label = S(R.string.copy_the_id),
                icon = Icons.Default.ContentCopy,
                onClick = {
                    clipboard.setText(AnnotatedString(relay.deploymentId))
                    shortToast(context, S(R.string.id_copied))
                },
            )
        }
        SettingsFooter(
            S(R.string.this_is_the_string_you_copied_from) +
                S(R.string.relays_and_is_kept_in_the_wizard)
        )

        SettingsSectionHeader(S(R.string.manage_r2))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.edit_in_the_wizard),
                icon = Icons.Default.Edit,
                onClick = onEdit,
            )
            Separator()
            SettingsActionRow(
                label = if (confirmRemove) S(R.string.are_you_sure_tap_again_to_delete_r2) else S(R.string.delete_this_relay),
                icon = Icons.Default.DeleteOutline,
                tint = Ios.Red,
                onClick = {
                    if (confirmRemove) {
                        onRemove()
                        onBack()
                    } else {
                        confirmRemove = true
                    }
                },
            )
        }
        if (confirmRemove) {
            SettingsFooter(
                S(R.string.deleting_only_removes_this_relay_from_the) +
                    S(R.string.untouched_and_you_can_add_the_same)
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

// -------------------------------------------------------------------------------------------
// Network path
// -------------------------------------------------------------------------------------------

/**
 * Which names and which addresses the tunnel fronts through.
 *
 * The old dialog had a TabRow over two checkbox lists and a "ذخیره و خروج" button. Both are
 * gone: iOS shows one grouped card per list on a single scrolling page, and a choice commits on
 * the tap -- there is no Save button anywhere else in this app, and a picker that silently
 * discards what you ticked if you leave by the back gesture is a trap.
 *
 * A tick cannot be removed when it is the last one in its list. An empty list is not a
 * preference, it is a broken tunnel, and the engine would have taken it without complaint.
 */
@Composable
fun GstNetworkPathScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val snis = GstConfigManager.DEFAULT_SNI_LIST
    val ips = GstConfigManager.DEFAULT_IP_LIST

    val selectedSnis = remember {
        mutableStateListOf(*GstConfigManager.getSelectedSniList(context).toTypedArray())
    }
    val selectedIps = remember {
        mutableStateListOf(*GstConfigManager.getSelectedCleanIpList(context).toTypedArray())
    }

    val pings = remember { mutableStateMapOf<String, Int>() }
    var scanning by remember { mutableStateOf(false) }

    // Unticking everything is allowed, and it is not a broken state: GstEngine falls back to the
    // upstream reference pair (www.google.com over 216.239.38.120) when a list is empty, which is
    // the best answer on most networks. Refusing the last untick would have been guessing.
    fun toggleSni(item: String) {
        if (selectedSnis.contains(item)) selectedSnis.remove(item) else selectedSnis.add(item)
        GstConfigManager.saveSelectedSniList(context, selectedSnis.toList())
    }

    fun toggleIp(item: String) {
        if (selectedIps.contains(item)) selectedIps.remove(item) else selectedIps.add(item)
        GstConfigManager.saveSelectedCleanIpList(context, selectedIps.toList())
    }

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            // A name is probed as its own SNI; an address is probed while fronting www.google.com,
            // which is exactly how the tunnel will use each of them.
            val results = (snis.map { it to it } + ips.map { it to "www.google.com" })
                .map { (target, sni) -> async(Dispatchers.IO) { target to tlsPing(target, 443, sni) } }
                .awaitAll()
            results.forEach { (target, ms) -> pings[target] = ms }
            scanning = false
        }
    }

    IosScreen(title = S(R.string.network_route), onBack = onBack, backLabel = GST_BACK) {
        Spacer(Modifier.height(14.dp))

        SettingsGroup {
            SettingsActionRow(
                label = if (scanning) S(R.string.measuring_every_route) else S(R.string.ping_every_route),
                icon = Icons.Default.NetworkCheck,
                busy = scanning,
                onClick = { scan() },
            )
        }

        SettingsSectionHeader(S(R.string.domain_name_sni))
        SettingsGroup {
            snis.forEachIndexed { index, item ->
                if (index > 0) Separator()
                PathRow(
                    label = item,
                    detail = GstConfigManager.ITEM_DESCRIPTIONS[item],
                    ping = pings[item],
                    dimmed = scanning,
                    checked = selectedSnis.contains(item),
                    onClick = { toggleSni(item) },
                )
            }
        }
        SettingsFooter(
            S(R.string.the_name_that_rides_on_the_tls) +
                S(R.string.several_is_better_than_one_if_none) +
                S(R.string.www_google_com)
        )

        SettingsSectionHeader(S(R.string.clean_ip))
        SettingsGroup {
            ips.forEachIndexed { index, item ->
                if (index > 0) Separator()
                PathRow(
                    label = item,
                    detail = GstConfigManager.ITEM_DESCRIPTIONS[item],
                    ping = pings[item],
                    dimmed = scanning,
                    checked = selectedIps.contains(item),
                    onClick = { toggleIp(item) },
                )
            }
        }
        SettingsFooter(
            S(R.string.the_address_we_actually_connect_to_while) +
                S(R.string.these_are_google_s_own_ips_not) +
                S(R.string.and_a_cloudflare_ip_here_breaks_video) +
                S(R.string.the_tunnel_then_uses_the_reference_default) +
                S(R.string.networks)
        )

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * One name or one address: label, its note, the measured latency, and a tick when selected.
 *
 * A tick rather than a Checkbox. The rest of the app marks a chosen row with a blue tick on the
 * trailing edge, and a Material checkbox on the leading edge was the only square control left on
 * any of these pages.
 */
@Composable
private fun PathRow(
    label: String,
    detail: String?,
    ping: Int?,
    dimmed: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Ios.Label, fontSize = 15.sp)
            if (detail != null) {
                Text(detail, color = Ios.SecondaryLabel, fontSize = 12.sp)
            }
        }
        if (ping != null) {
            Text(
                gstPingLabel(ping),
                color = pingTone(ping).copy(alpha = if (dimmed) 0.4f else 1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(10.dp))
        }
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (checked) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Ios.Blue,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Live log
// -------------------------------------------------------------------------------------------

/**
 * The core's own words, on a page rather than inside an AlertDialog whose text slot was a
 * scrolling black box 400dp tall.
 *
 * Modelled on [com.mlmvpn.scanner.ui.tunnel.TunnelLogScreen], including the rule that the tail is
 * followed only while the reader is already at the tail.
 */
@Composable
fun GstLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val entries by GstLog.lines.collectAsState()
    val listState = rememberLazyListState()
    var confirmClear by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        if (listState.firstVisibleItemIndex >= (entries.size - 12).coerceAtLeast(0)) {
            delay(60)
            runCatching { listState.animateScrollToItem(entries.size - 1) }
        }
    }

    IosScreen(title = S(R.string.live_report), onBack = onBack, backLabel = GST_BACK, scrollable = false) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LogChip(
                label = S(R.string.copy),
                icon = Icons.Default.ContentCopy,
                tint = Ios.Blue,
                modifier = Modifier.weight(1f),
            ) {
                val dump = GstLog.dump()
                if (dump.isNotBlank()) {
                    clipboard.setText(AnnotatedString(dump))
                    shortToast(context, S(R.string.report_copied))
                }
            }
            LogChip(
                label = S(R.string.share_r2),
                icon = Icons.Default.Share,
                tint = Ios.Teal,
                modifier = Modifier.weight(1f),
            ) {
                val dump = GstLog.dump()
                if (dump.isNotBlank()) {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, dump)
                    }
                    context.startActivity(Intent.createChooser(share, S(R.string.google_relay_report)))
                }
            }
            LogChip(
                label = if (confirmClear) S(R.string.are_you_sure) else S(R.string.clear_r2),
                icon = Icons.Default.DeleteOutline,
                tint = Ios.Red,
                modifier = Modifier.weight(1f),
            ) {
                if (confirmClear) {
                    GstLog.clear()
                    confirmClear = false
                } else {
                    confirmClear = true
                }
            }
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    S(R.string.nothing_recorded_yet_nrun_measure_all_relays),
                    color = Ios.SecondaryLabel,
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                )
            }
            return@IosScreen
        }

        // A log line is "HH:mm:ss I/Tag: message" -- a timestamp, a slash and a colon, and often a
        // URL. Left to inherit the page's right-to-left direction, bidi walks that punctuation to
        // the wrong end of every line and the timestamps stop lining up. The Persian message text
        // inside a line still renders right-to-left within its own run, which is correct.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .frostedGlass(CardShape),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(entries) { entry ->
                    Text(
                        entry.format(),
                        color = when (entry.level) {
                            GstLog.Level.E -> Ios.Red
                            GstLog.Level.W -> Ios.Yellow
                            GstLog.Level.I -> Ios.Label
                            else -> Ios.SecondaryLabel
                        },
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LogChip(
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(tint.copy(alpha = 0.14f), ControlShape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 14.sp)
    }
}

// -------------------------------------------------------------------------------------------
// Shared bits
// -------------------------------------------------------------------------------------------

/** A route that never completed its handshake is unreachable, not merely slow. */
private fun gstPingLabel(ms: Int): String =
    if (ms <= 0) S(R.string.no_response_r2) else "${faDigits(ms.toString())}ms"
