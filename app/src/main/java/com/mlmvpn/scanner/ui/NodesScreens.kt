package com.mlmvpn.scanner.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsSlider
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.utils.Platform
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The pages the connection screen pushes.
//
// Every one of these replaces something that used to be a dialog, and two of them replace dialogs
// that were not even Compose: the share sheet and the edit form were `MaterialAlertDialogBuilder`
// with inflated XML layouts, so they arrived in stock Material colours with a Material title bar
// and Material text fields, floating over an app that looks nothing like that. There was no way
// to theme them short of replacing them, which is what this file does.
//
// The node row itself used to expand in place into two rows of six `TextButton`s -- test, test,
// test, share, edit, delete -- which put "delete this config" and "measure this config" the same
// size, the same weight and 40dp apart inside a list you scroll with your thumb.
// =================================================================================================

/** What a node is, as one short word and one colour. Shared by the row and the detail page. */
@Composable
fun nodeBadge(node: VpnNode): Pair<String, Color> {
    // Only the address matters here, and a link that names no loopback address has not got it:
    // a full parse per row as the list scrolls was one of the node list's main-thread stalls.
    val loopback = remember(node.uri) {
        (node.uri.startsWith("vmess://", ignoreCase = true) || node.uri.contains("127.0.0.1")) &&
            VpnConfig.parseUri(node.uri)?.address == "127.0.0.1"
    }
    val isSni = loopback && node.engineType != "NHN"
    val isMitm = node.id == com.mlmvpn.scanner.mitm.MitmProfile.NODE_ID
    val isFree = node.engineType == "Manual" &&
        node.groupTitle == com.mlmvpn.scanner.engines.freeconfig.FreeConfigEngine.GROUP_NAME
    return when {
        node.id.startsWith("default_mlmvpn") -> "MLMVPN" to Ios.Green
        isSni -> "SNI" to Ios.Teal
        isMitm -> S(R.string.fronting) to Ios.Teal
        isFree -> S(R.string.free) to Ios.Purple
        node.engineType == "EDG" -> "EDG" to Ios.Indigo
        node.engineType == "NHN" -> "NHN" to Ios.Green
        node.engineType == "MLM" -> "MLM" to Ios.Purple
        node.engineType == "SPD" -> "Spider" to Ios.Pink
        node.engineType == "NTR" -> "Netra" to Ios.Purple
        node.engineType == "GZG" -> "Gozargah" to Ios.Teal
        node.engineType == "NVA" -> "Nova" to Ios.Indigo
        node.engineType == "Manual" -> S(R.string.manual) to Ios.Gray
        else -> "BPB" to Ios.Blue
    }
}

/**
 * A measured value, in the app's own bands.
 *
 * The stored fields are free text -- `Test` (never measured), `...` (in flight), `Timeout`, `N/A`,
 * `Error`, `Cancelled`, or a number with a unit -- and only the last of those is a reading. The
 * rest are states, and colouring them as if they were slow servers is how a config that was never
 * tested ended up looking like a failing one.
 */
@Composable
fun measurementTone(value: String, goodBelow: Int, okBelow: Int): Color {
    val number = value.replace("ms", "").replace(" MB/s", "").toDoubleOrNull()
    return when {
        value == "Test" || value == "..." || value == "Cancelled" -> Ios.SecondaryLabel
        number == null -> Ios.Red
        number < goodBelow -> Ios.Green
        number < okBelow -> Ios.Yellow
        else -> Ios.Red
    }
}

/** Speed reads the other way round: bigger is better. */
@Composable
fun speedTone(value: String): Color {
    val mbps = value.replace(" MB/s", "").toDoubleOrNull()
    return when {
        value == "Test" || value == "..." || value == "Cancelled" -> Ios.SecondaryLabel
        mbps == null -> Ios.Red
        mbps > 2.0 -> Ios.Green
        mbps > 0.5 -> Ios.Yellow
        else -> Ios.Red
    }
}

/** `Test` is not a reading, it is the absence of one, and it should not be printed as a value. */
fun measurementLabel(value: String): String? = when (value) {
    "Test" -> null
    "..." -> "…"
    else -> value
}

// -------------------------------------------------------------------------------------------
// One node
// -------------------------------------------------------------------------------------------

/**
 * Everything about a single config, on its own page.
 *
 * The three measurements are rows you tap rather than buttons in a strip, so each one shows its
 * own last result next to the thing that produces it -- which is the question a user actually has
 * ("is this one any good?") rather than the one the old strip answered ("which of six buttons is
 * the test?").
 */
@Composable
fun NodeDetailScreen(
    node: VpnNode,
    isActive: Boolean,
    onPing: () -> Unit,
    onDelay: () -> Unit,
    onSpeed: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var confirmDelete by remember { mutableStateOf(false) }

    val config = remember(node.uri) { VpnConfig.parseUri(node.uri) }
    val (badge, badgeTint) = nodeBadge(node)
    val protectedNode = NodeManager.isProtected(node)

    IosScreen(title = node.name, onBack = onBack, backLabel = S(R.string.connect)) {
        Spacer(Modifier.height(14.dp))

        // The identity block: what this is, where it comes out, and whether it is the live one.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .frostedGlass(CardShape)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (node.countryCode != null) {
                Text(getNodeFlagEmoji(node.countryCode), fontSize = 40.sp)
                Spacer(Modifier.height(8.dp))
            }
            Text(
                node.name,
                color = Ios.Label,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 26.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NodePill(badge, badgeTint)
                Spacer(Modifier.width(6.dp))
                NodePill(node.type.uppercase(), Ios.Gray)
                if (isActive) {
                    Spacer(Modifier.width(6.dp))
                    NodePill(S(R.string.connected), Ios.Green)
                }
            }
        }

        SettingsSectionHeader(S(R.string.measure))
        SettingsGroup {
            MeasureRow(S(R.string.ping), Icons.Default.FlashOn, Ios.Teal, node.ping, measurementTone(node.ping, 150, 500), onPing)
            Separator()
            MeasureRow(S(R.string.real_delay), Icons.Default.AccessTime, Ios.Indigo, node.delay, measurementTone(node.delay, 300, 800), onDelay)
            Separator()
            MeasureRow(S(R.string.speed), Icons.Default.Download, Ios.Orange, node.speed, speedTone(node.speed), onSpeed)
        }
        SettingsFooter(
            S(R.string.ping_only_tells_you_the_server_answers) +
                S(R.string.and_is_what_you_actually_feel_while) +
                S(R.string.takes_the_longest_to_finish)
        )

        SettingsSectionHeader(S(R.string.details))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.address),
                icon = Icons.Default.Public,
                tint = Ios.Indigo,
                value = config?.let { "${it.address}:${it.port}" } ?: "—",
                showChevron = false,
            )
            Separator()
            SettingsRow(
                title = S(R.string.transport),
                icon = Icons.Default.SwapHoriz,
                tint = Ios.Gray,
                value = config?.network?.takeIf { it.isNotBlank() } ?: "—",
                showChevron = false,
            )
            Separator()
            SettingsRow(
                title = S(R.string.country),
                icon = Icons.Default.Language,
                tint = Ios.Teal,
                value = node.countryCode?.let { "${getNodeFlagEmoji(it)} $it" } ?: S(R.string.unknown),
                showChevron = false,
            )
            if (!node.groupTitle.isNullOrBlank()) {
                Separator()
                SettingsRow(
                    title = S(R.string.folder),
                    icon = Icons.Default.Folder,
                    tint = Ios.Yellow,
                    value = node.groupTitle,
                    showChevron = false,
                )
            }
        }

        // The eight built-in Iran configs are not shareable, and that includes the QR code rather
        // than only the two buttons. The QR encodes the identical URI, so leaving it while
        // removing "copy link" and "send to another app" would take the two obvious ways out and
        // leave the one that needs no tap at all -- a photograph of the screen.
        if (!protectedNode) {
            SettingsSectionHeader(S(R.string.share))
            NodeQrCard(node.uri)
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingsActionRow(S(R.string.copy_config_link), Icons.Default.ContentCopy) {
                    clipboard.setText(AnnotatedString(node.uri))
                    android.widget.Toast
                        .makeText(context, S(R.string.link_copied), android.widget.Toast.LENGTH_SHORT).show()
                }
                Separator()
                SettingsActionRow(S(R.string.send_to_another_app), Icons.Default.Share) {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, node.uri)
                    }
                    context.startActivity(Intent.createChooser(share, node.name))
                }
            }
            SettingsFooter(
                S(R.string.the_code_above_is_this_config_any) +
                    S(R.string.and_so_can_anyone_who_sees_it)
            )
        }

        // Built-in Iran configs are non-deletable and not editable: they are upstream files that
        // work precisely because nothing has been changed in them.
        if (!protectedNode) {
            SettingsSectionHeader(S(R.string.manage))
            SettingsGroup {
                SettingsActionRow(S(R.string.edit_config), Icons.Default.Edit, onClick = onEdit)
                Separator()
                SettingsActionRow(
                    label = if (confirmDelete) S(R.string.are_you_sure_tap_again_to_delete) else S(R.string.delete_this_config),
                    icon = Icons.Default.DeleteOutline,
                    tint = Ios.Red,
                ) {
                    if (confirmDelete) {
                        onDelete()
                        onBack()
                    } else {
                        confirmDelete = true
                    }
                }
            }
        } else {
            SettingsSectionHeader(S(R.string.manage))
            SettingsGroup {
                SettingsRow(
                    title = S(R.string.built_in_config),
                    subtitle = S(R.string.cannot_be_edited_or_deleted),
                    icon = Icons.Default.Layers,
                    tint = Ios.Gray,
                    showChevron = false,
                )
            }
            SettingsFooter(
                S(R.string.these_are_the_original_untouched_serverless_files) +
                    S(R.string.work_editing_them_is_the_thing_that)
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** A measurement: tap the row to take it, read the last one on the trailing edge. */
@Composable
private fun MeasureRow(
    title: String,
    icon: ImageVector,
    tint: Color,
    raw: String,
    valueTone: Color,
    onClick: () -> Unit,
) {
    val busy = raw == "..."
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(icon, tint)
        Spacer(Modifier.width(12.dp))
        Text(title, color = Ios.Label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                color = Ios.SecondaryLabel,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                measurementLabel(raw) ?: S(R.string.not_measured),
                color = if (measurementLabel(raw) == null) Ios.SecondaryLabel else valueTone,
                fontSize = 15.sp,
                fontWeight = if (measurementLabel(raw) == null) FontWeight.Normal else FontWeight.Medium,
            )
        }
    }
}

/** One small rounded label: the badge, the protocol, the live marker. */
@Composable
fun NodePill(text: String, tint: Color) {
    Text(
        text,
        color = tint,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(tint.copy(alpha = 0.16f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * The config as a QR code.
 *
 * Painted black on white inside a white card rather than tinted to the app's palette: a scanner
 * needs the contrast, and a QR that looks handsome and does not read is worse than no QR.
 */
@Composable
fun NodeQrCard(uri: String) {
    val bitmap = remember(uri) {
        try {
            val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(
                com.google.zxing.EncodeHintType::class.java
            )
            hints[com.google.zxing.EncodeHintType.MARGIN] = 1
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(uri, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, hints)
            val bmp = android.graphics.Bitmap.createBitmap(
                matrix.width, matrix.height, android.graphics.Bitmap.Config.RGB_565
            )
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bmp.setPixel(
                        x, y,
                        if (matrix.get(x, y)) android.graphics.Color.BLACK
                        else android.graphics.Color.WHITE
                    )
                }
            }
            bmp.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .frostedGlass(CardShape)
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(200.dp)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(8.dp),
            )
        } else {
            // A QR code tops out around 3KB. A serverless JSON config is bigger than that, which
            // is not a failure worth apologising for -- it is simply not a thing a camera can
            // carry, and the copy button beside it is the answer.
            Text(
                S(R.string.this_config_is_too_long_for_a),
                color = Ios.SecondaryLabel,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// Editing a node
// -------------------------------------------------------------------------------------------

/**
 * The ten fields a config is made of, as an iOS form.
 *
 * This replaces an inflated `dialog_edit_node.xml` full of `TextInputLayout`s -- stock Material,
 * stock colours, a Material title bar and a Material Save button, in the middle of an app that
 * has none of those. It also had no Save-on-change: leaving it any way but the button silently
 * discarded everything typed.
 *
 * Committing still needs an explicit action here, unlike the rest of the app's settings, and that
 * is deliberate: these ten fields are ONE value together -- the URI is rebuilt from all of them at
 * once -- so a half-typed address committed on the keystroke would produce a config that cannot
 * connect and no longer says what it used to.
 */
@Composable
fun NodeEditScreen(node: VpnNode, onSave: (VpnConfig) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val config = remember(node.uri) { VpnConfig.parseUri(node.uri) }

    if (config == null) {
        IosScreen(title = S(R.string.edit), onBack = onBack, backLabel = S(R.string.back)) {
            Spacer(Modifier.height(20.dp))
            SettingsFooter(S(R.string.this_config_cannot_be_parsed_so_it))
        }
        return
    }

    var name by remember { mutableStateOf(config.name) }
    var address by remember { mutableStateOf(config.address) }
    var port by remember { mutableStateOf(config.port.toString()) }
    var uuid by remember { mutableStateOf(config.uuid) }
    var network by remember { mutableStateOf(config.network) }
    var wsHost by remember { mutableStateOf(config.wsHost) }
    var wsPath by remember { mutableStateOf(config.wsPath) }
    var tls by remember { mutableStateOf(config.tls) }
    var sni by remember { mutableStateOf(config.sni) }
    var alpn by remember { mutableStateOf(config.alpn) }

    val portError = if (port.toIntOrNull()?.let { it in 1..65535 } == true) null
    else S(R.string.port_must_be_a_number_between_1)

    IosScreen(title = S(R.string.edit_config), onBack = onBack, backLabel = S(R.string.back)) {
        Spacer(Modifier.height(14.dp))

        SettingsSectionHeader(S(R.string.server))
        SettingsGroup {
            SettingsTextRow(S(R.string.name), name, { name = it }, placeholder = S(R.string.custom_name))
            Separator()
            SettingsTextRow(S(R.string.address), address, { address = it }, placeholder = "example.com")
            Separator()
            SettingsTextRow(S(R.string.port), port, { port = it }, placeholder = "443", numeric = true, error = portError)
            Separator()
            SettingsTextRow(S(R.string.id_password), uuid, { uuid = it }, placeholder = "UUID")
        }

        SettingsSectionHeader(S(R.string.transport))
        SettingsGroup {
            SettingsTextRow(S(R.string.network), network, { network = it }, placeholder = "ws / grpc / tcp")
            Separator()
            SettingsTextRow("Host", wsHost, { wsHost = it }, placeholder = S(R.string.host_header_hint))
            Separator()
            SettingsTextRow("Path", wsPath, { wsPath = it }, placeholder = "/")
        }

        SettingsSectionHeader(S(R.string.security))
        SettingsGroup {
            SettingsTextRow("TLS", tls, { tls = it }, placeholder = "tls / none")
            Separator()
            SettingsTextRow("SNI", sni, { sni = it }, placeholder = S(R.string.server_name_hint))
            Separator()
            SettingsTextRow("ALPN", alpn, { alpn = it }, placeholder = "h2,http/1.1")
        }
        SettingsFooter(
            S(R.string.saving_rebuilds_the_config_link_from_these) +
                S(R.string.so_it_is_measured_again_next_time)
        )

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.save),
                icon = Icons.Default.Edit,
                enabled = portError == null,
            ) {
                config.name = name.trim()
                config.address = address.trim()
                config.port = port.toIntOrNull() ?: 443
                config.uuid = uuid.trim()
                config.network = network.trim()
                config.wsHost = wsHost.trim()
                config.wsPath = wsPath.trim()
                config.tls = tls.trim()
                config.sni = sni.trim()
                config.alpn = alpn.trim()
                onSave(config)
                android.widget.Toast
                    .makeText(context, S(R.string.saved), android.widget.Toast.LENGTH_SHORT).show()
                onBack()
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

// -------------------------------------------------------------------------------------------
// List settings
// -------------------------------------------------------------------------------------------

/**
 * The switches that used to live behind a «بیشتر» chevron at the top of the list.
 *
 * A collapsible header is a drawer, and a setting inside a drawer is a setting nobody finds --
 * the same reasoning that removed this app's hamburger menu. These are settings, so they are on a
 * settings page, and the row that opens it says how many servers the current folder holds, which
 * is the one thing the header was genuinely good for.
 */
@Composable
fun NodeListSettingsScreen(
    /**
     * The panel or folder these actions apply to, or null when they apply to everything.
     *
     * Shown under the section header rather than left implicit. With "separate panels" on, the
     * same screen deletes a different set of configs depending on which tab is behind it, and the
     * header alone ("this folder") never said which one.
     */
    scopeLabel: String?,
    healthyCount: Int,
    onCopyHealthy: () -> Unit,
    canGroupByPanel: Boolean,
    groupByPanel: Boolean,
    onGroupByPanel: (Boolean) -> Unit,
    platformMode: Boolean,
    onPlatformMode: (Boolean) -> Unit,
    autoSwitchEnabled: Boolean,
    autoSwitchSummary: String,
    onOpenAutoSwitch: () -> Unit,
    onDisableAutoSwitch: () -> Unit,
    visibleCount: Int,
    deadCount: Int,
    onCleanUp: () -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmDead by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }

    IosScreen(title = S(R.string.list_settings), onBack = onBack, backLabel = S(R.string.connect)) {
        Spacer(Modifier.height(14.dp))

        SettingsSectionHeader(S(R.string.display))
        SettingsGroup {
            if (canGroupByPanel) {
                SettingsToggle(
                    title = S(R.string.group_by_panel),
                    checked = groupByPanel,
                    onCheckedChange = onGroupByPanel,
                    icon = Icons.Default.Layers,
                    tint = Ios.Indigo,
                    subtitle = S(R.string.each_panel_gets_its_own_tab),
                )
                Separator()
            }
            SettingsToggle(
                title = S(R.string.platform_mode),
                checked = platformMode,
                onCheckedChange = onPlatformMode,
                icon = Icons.Default.FlashOn,
                tint = Ios.Teal,
                subtitle = S(R.string.measure_servers_against_one_specific_app),
            )
        }
        SettingsFooter(
            S(R.string.instead_of_a_generic_test_platform_mode) +
                S(R.string.claude_itself_because_a_server_that_is)
        )

        SettingsSectionHeader(S(R.string.auto_switch))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.auto_switch),
                icon = Icons.Default.Timer,
                tint = if (autoSwitchEnabled) Ios.Green else Ios.Gray,
                value = if (autoSwitchEnabled) autoSwitchSummary else S(R.string.off),
                onClick = onOpenAutoSwitch,
            )
            if (autoSwitchEnabled) {
                Separator()
                SettingsActionRow(
                    label = S(R.string.turn_auto_switch_off),
                    icon = Icons.Default.Timer,
                    tint = Ios.Orange,
                    onClick = onDisableAutoSwitch,
                )
            }
        }

        SettingsSectionHeader(
            if (scopeLabel != null) S(R.string.cleanup_in_folder, scopeLabel)
            else S(R.string.cleanup_everything)
        )
        SettingsGroup {
            SettingsRow(
                title = S(R.string.configs_in_this_folder),
                icon = Icons.Default.Layers,
                tint = Ios.Gray,
                value = faCount(visibleCount),
                showChevron = false,
            )
            Separator()
            SettingsActionRow(
                label = S(R.string.nodes_copy_healthy, faCount(healthyCount)),
                icon = Icons.Default.ContentCopy,
                tint = Ios.Teal,
                enabled = healthyCount > 0,
                onClick = onCopyHealthy,
            )
            Separator()
            SettingsActionRow(
                label = if (confirmDead) S(R.string.are_you_sure_tap_again_to_clear)
                else S(R.string.clear_dead_configs, faCount(deadCount)),
                icon = Icons.Default.DeleteOutline,
                tint = Ios.Orange,
                enabled = deadCount > 0,
            ) {
                if (confirmDead) { onCleanUp(); confirmDead = false } else confirmDead = true
            }
            Separator()
            SettingsActionRow(
                label = if (confirmAll) S(R.string.are_you_sure_all_configs_will_be, faCount(visibleCount))
                else S(R.string.delete_every_config_in_this_folder),
                icon = Icons.Default.DeleteOutline,
                tint = Ios.Red,
                enabled = visibleCount > 0,
            ) {
                if (confirmAll) { onDeleteAll(); confirmAll = false } else confirmAll = true
            }
        }
        SettingsFooter(
            S(R.string.dead_means_measured_and_did_not_answer) +
                S(R.string.is_left_alone_and_built_in_configs)
        )

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Auto-switch, as a form rather than an `AlertDialog` with a dropdown inside it.
 *
 * The dialog opened the moment the switch was flipped ON and turned the switch back OFF if it was
 * dismissed -- so a mis-tap on the scrim silently undid the thing the user had just asked for,
 * with no way to see the settings again without toggling twice.
 */
@Composable
fun AutoSwitchScreen(
    platform: String,
    onPlatform: (String) -> Unit,
    intervalMinutes: Int,
    onInterval: (Int) -> Unit,
    testCount: Int,
    onTestCount: (Int) -> Unit,
    enabled: Boolean,
    onEnable: () -> Unit,
    onBack: () -> Unit,
) {
    val entireDevice = S(R.string.whole_device)
    val options = remember { listOf(entireDevice) + Platform.values().map { it.displayName } }

    IosScreen(title = S(R.string.auto_switch), onBack = onBack, backLabel = S(R.string.list_settings)) {
        Spacer(Modifier.height(14.dp))

        SettingsSectionHeader(S(R.string.target))
        SettingsGroup {
            options.forEachIndexed { index, option ->
                if (index > 0) Separator()
                val key = if (index == 0) "None" else Platform.values()[index - 1].name
                SettingsRow(
                    title = option,
                    titleColor = Ios.Label,
                    value = if (platform == key) "✓" else null,
                    showChevron = false,
                    onClick = { onPlatform(key) },
                )
            }
        }
        SettingsFooter(
            S(R.string.whole_device_means_a_generic_test_if) +
                S(R.string.that_app_and_keeps_the_best_one)
        )

        SettingsSectionHeader(S(R.string.schedule))
        SettingsSlider(
            title = S(R.string.check_interval),
            value = intervalMinutes,
            onValueChange = onInterval,
            valueLabel = S(R.string.minutes, faCount(intervalMinutes)),
            range = 5f..120f,
        )
        Spacer(Modifier.height(10.dp))
        SettingsSlider(
            title = S(R.string.servers_per_check),
            value = testCount,
            onValueChange = onTestCount,
            valueLabel = faCount(testCount),
            range = 5f..50f,
        )
        SettingsFooter(
            S(R.string.each_check_is_one_real_connection_per) +
                S(R.string.time_a_short_interval_on_an_unstable)
        )

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsActionRow(
                label = if (enabled) S(R.string.update_and_keep_on) else S(R.string.turn_auto_switch_on),
                icon = Icons.Default.Timer,
                tint = Ios.Green,
            ) {
                onEnable()
                onBack()
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** Persian digits, like every other count the app prints. */
fun faGrouped(value: Int): String {
    val grouped = StringBuilder()
    val s = value.toString()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) grouped.append('٬')
        grouped.append(c)
    }
    return faCount0(grouped.toString())
}

    // Latin digits when the app is in English. These helpers converted unconditionally, so an
    // English UI still counted "۲۰ servers" -- 139 call sites' worth of numbers in the wrong
    // script. The conversion is the localised behaviour, not the default one.
private fun faCount0(text: String): String {
    if (!com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()) return text
    val digits = charArrayOf('۰', '۱', '۲', '۳', '۴',
        '۵', '۶', '۷', '۸', '۹')
    return buildString {
        for (c in text) append(if (c in '0'..'9') digits[c - '0'] else c)
    }
}

    // Latin digits when the app is in English. These helpers converted unconditionally, so an
    // English UI still counted "۲۰ servers" -- 139 call sites' worth of numbers in the wrong
    // script. The conversion is the localised behaviour, not the default one.
fun faCount(value: Int): String {
    if (!com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()) return value.toString()
    val digits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    return buildString {
        for (c in value.toString()) append(if (c in '0'..'9') digits[c - '0'] else c)
    }
}
