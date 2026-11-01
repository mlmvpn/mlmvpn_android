package com.mlmvpn.scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.data.CloudGroup
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.PanelShape
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The Cloud screen's pieces and pushed pages.
//
// What this replaces: one 22,000-instruction composable holding a 3×3 grid of hand-built cells --
// eleven `Box(weight(1f)) { Column { Icon; Text } }` blocks separated by full-bleed dividers, each
// with a deploy state machine inlined in its onClick -- plus an `AlertDialog` for adding an account
// whose whole body was a Column of Material text fields, and a groups list whose actions were two
// buttons and a TextButton crammed onto one 40dp row.
//
// A panel is a row now, and a received group is a page. That is not only tidier: the grid could
// only ever show a glyph and two words per action, which is why "دیپلوی" and "دریافت نود" had to
// carry the entire explanation of what a panel is and what state it is in.
// =================================================================================================

/**
 * One action on one panel.
 *
 * The state lives on the trailing edge where a settings row puts its value, so "deployed" and
 * "not yet" are read in the same place for all four panels instead of being a colour change on a
 * glyph in a grid cell.
 */
@Composable
fun CloudPanelRow(
    label: String,
    icon: ImageVector,
    tint: Color,
    value: String? = null,
    valueTone: Color? = null,
    busy: Boolean = false,
    progress: Float = -1f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled && !busy, onClick = onClick)
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsGlyph(icon, if (enabled) tint else Ios.Gray)
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                color = if (enabled) Ios.Label else Ios.SecondaryLabel.copy(alpha = 0.55f),
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    color = Ios.SecondaryLabel,
                    strokeWidth = 2.dp,
                )
            } else if (value != null) {
                Text(
                    value,
                    color = valueTone ?: Ios.SecondaryLabel,
                    fontSize = 15.sp,
                    fontWeight = if (valueTone != null) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
        // Deploys report a percentage. A thin line under the row is enough to show it moving
        // without the row changing height and shoving the list around under the thumb.
        if (busy && progress >= 0f) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 57.dp, end = 16.dp, bottom = 6.dp)
                    .height(2.dp)
                    .clip(CircleShape),
                color = Ios.Blue,
                trackColor = Color.White.copy(alpha = 0.12f),
            )
        }
    }
}

/**
 * Connecting a Cloudflare account, on a page.
 *
 * It was an `AlertDialog` whose text slot held two Material fields, a hint and a full-width
 * button -- a form wearing an alert's clothes, and the one place in the app where the user types
 * a credential, squeezed into the narrowest container the framework has.
 *
 * The key is never shown back: once an account exists the Cloud screen prints its state, not eight
 * characters of its token.
 */
@Composable
fun CloudAddAccountScreen(
    isAdding: Boolean,
    error: String?,
    onAdd: (email: String, apiKey: String) -> Unit,
    onBack: () -> Unit,
    /**
     * Opens the troubleshooting page for the credential as typed, without adding anything.
     *
     * Offered only once an attempt has failed. "The credentials were rejected" is the same
     * sentence for a Global API Key pasted without its email, a revoked token, a token with no
     * account permission, and a phone that never reached Cloudflare -- and a user stuck on this
     * screen has no account yet, so none of the tools on the account card can reach them.
     */
    onTroubleshoot: (email: String, apiKey: String) -> Unit = { _, _ -> },
) {
    IosScreen(title = S(R.string.add_cloudflare_account), onBack = onBack, backLabel = S(R.string.cloud)) {
        CloudAddAccountForm(isAdding = isAdding, error = error, onAdd = onAdd, onTroubleshoot = onTroubleshoot)
    }
}

/**
 * The add-an-account form on its own, without a screen wrapped around it.
 *
 * Split out of [CloudAddAccountScreen] because Config Studio's setup wizard has to ask for a
 * Cloudflare account **inside** its own first step, and cannot send the user to «ابری» to do it --
 * the two surfaces share one account store, so being bounced to another part of the app to type a
 * credential and then finding your way back is a seam the user should never see.
 *
 * Hosting the old screen inside a wizard step was not an option: it brings its own [IosScreen], so
 * the result is a navigation bar inside a navigation bar and one scrolling container inside another,
 * with a hardcoded "Cloud" back label pointing somewhere the wizard is not.
 *
 * The credential state stays here rather than being hoisted, so a caller gets the same contract the
 * screen always had -- the values arrive through [onAdd] and [onTroubleshoot] and nothing else has
 * to hold a half-typed API key.
 *
 * See `android/docs/CONFIG-STUDIO-PLAN.md` F1.
 */
@Composable
fun ColumnScope.CloudAddAccountForm(
    isAdding: Boolean,
    error: String?,
    onAdd: (email: String, apiKey: String) -> Unit,
    onTroubleshoot: (email: String, apiKey: String) -> Unit = { _, _ -> },
    /**
     * Off when the host already announced this section.
     *
     * The Config Studio wizard heads it «افزودن حساب کلادفلر», and the form heading itself «حساب»
     * underneath that put the word twice on one screen -- which reads as a mistake rather than as
     * two sections, because it is one.
     */
    showHeader: Boolean = true,
) {
    var email by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    Spacer(Modifier.height(14.dp))

    if (showHeader) SettingsSectionHeader(S(R.string.account))
        SettingsGroup {
            SettingsTextRow(
                title = S(R.string.email),
                value = email,
                onValueChange = { email = it },
                placeholder = "you@example.com",
            )
            Separator()
            SettingsTextRow(
                title = "Global API Key",
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = S(R.string.global_key),
                secret = true,
            )
        }
        SettingsFooter(
            S(R.string.the_global_key_is_in_the_cloudflare) +
                S(R.string.take_the_global_api_key_from_that) +
                S(R.string.and_is_sent_nowhere_but_cloudflare_itself)
        )

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(PanelShape)
                    .background(Ios.Red.copy(alpha = 0.12f))
                    .padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(error, color = Ios.Label, fontSize = 12.sp, lineHeight = 19.sp)
            }
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.cf_fix_open_from_add),
                    icon = Icons.Default.Build,
                    tint = Ios.Orange,
                    enabled = !isAdding,
                ) {
                    onTroubleshoot(email.trim(), apiKey.trim())
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsActionRow(
                label = if (isAdding) S(R.string.checking_the_account) else S(R.string.save_and_connect),
                icon = Icons.Default.CheckCircle,
                tint = Ios.Green,
                busy = isAdding,
                enabled = apiKey.isNotBlank(),
            ) {
                onAdd(email.trim(), apiKey.trim())
            }
        }

    Spacer(Modifier.height(28.dp))
}

/**
 * One received group of configs, on a page.
 *
 * The four things you can do with a group used to be two buttons and a text button on one 40dp
 * row, plus a 28dp bin in the header — four different sizes for four actions of similar weight,
 * with no room to say what any of them would produce.
 *
 * The combine action is the new one, and it is the one worth taking: a config straight from a
 * panel goes out over whatever Cloudflare address it happens to resolve, and combining it against
 * measured clean IPs is what turns it from "works" into "fast".
 */
@Composable
fun CloudGroupScreen(
    group: CloudGroup,
    onSendToV2Ray: () -> Unit,
    onOpenV2Ray: () -> Unit,
    onCombine: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    val knownUris = remember(group.id) {
        NodeManager(context).nodes.map { it.uri }.toSet()
    }
    var pending by remember(group.id) {
        mutableStateOf(group.nodes.count { it.uri !in knownUris })
    }
    val engine = group.nodes.firstOrNull()?.engineType ?: "BPB"

    IosScreen(title = group.title, onBack = onBack, backLabel = S(R.string.cloud)) {
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .frostedGlass(CardShape)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                faCount(group.nodes.size),
                color = Ios.Label,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(S(R.string.configs), color = Ios.SecondaryLabel, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NodePill(panelName(engine), panelTint(engine))
                Spacer(Modifier.width(6.dp))
                // Same two-Latin-runs problem as the list row: the clock jumps in front of the date.
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides
                        androidx.compose.ui.unit.LayoutDirection.Ltr
                ) {
                    NodePill(group.date, Ios.Gray)
                }
            }
        }

        // The action worth taking, first and on its own, because it is the one nobody finds.
        SettingsSectionHeader(S(R.string.speed_boost))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.combine_with_a_fixed_ip),
                subtitle = S(R.string.find_a_clean_ip_and_ride_these),
                icon = Icons.Default.Speed,
                tint = Ios.Green,
                onClick = onCombine,
            )
        }
        SettingsFooter(
            S(R.string.these_configs_leave_through_whatever_address_cloudflare) +
                S(R.string.finding_clean_ips_and_then_riding_these) +
                S(R.string.usually_several_times_faster_the_step_by) +
                S(R.string.testing_and_transferring_itself)
        )

        SettingsSectionHeader(S(R.string.connection_page))
        SettingsGroup {
            if (pending > 0) {
                SettingsActionRow(
                    label = S(R.string.move_configs_to_the_connection_page, faCount(pending)),
                    icon = Icons.Default.Download,
                ) {
                    onSendToV2Ray()
                    pending = 0
                }
            } else {
                SettingsRow(
                    title = S(R.string.all_of_them_are_on_the_connection),
                    icon = Icons.Default.CheckCircle,
                    tint = Ios.Green,
                    showChevron = false,
                )
            }
            Separator()
            SettingsActionRow(S(R.string.go_to_the_connection_page), Icons.Default.OpenInNew, onClick = onOpenV2Ray)
        }

        SettingsSectionHeader(S(R.string.configs_2))
        SettingsGroup {
            group.nodes.take(12).forEachIndexed { index, node ->
                if (index > 0) Separator()
                SettingsRow(
                    title = node.name,
                    icon = Icons.Default.Layers,
                    tint = Ios.Gray,
                    value = node.type.uppercase(),
                    showChevron = false,
                )
            }
        }
        if (group.nodes.size > 12) {
            SettingsFooter(S(R.string.and_more_configs, faCount(group.nodes.size - 12)))
        }

        SettingsSectionHeader(S(R.string.manage))
        SettingsGroup {
            SettingsActionRow(
                label = if (confirmDelete) S(R.string.are_you_sure_tap_again_to_delete) else S(R.string.delete_this_group),
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
        if (confirmDelete) {
            SettingsFooter(
                S(R.string.deleting_the_group_only_clears_this_list) +
                    S(R.string.stay_where_they_are)
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * One panel, collapsed.
 *
 * Four panels with two or three actions each meant eleven rows permanently on screen, which is why
 * this card ran off the bottom of the phone -- and why every action had to be labelled by a glyph
 * and two words. Collapsed, a panel says the three things worth knowing about it: what it is,
 * whether it is deployed, and how many configs it has actually produced.
 */
@Composable
fun CloudPanelHeaderRow(
    title: String,
    subtitle: String,
    state: String,
    configCount: Int,
    expanded: Boolean,
    /** Deployed, but from an older build of the app than the one installed. */
    stale: Boolean = false,
    /** Which panel, for its mark; worked out from [title] when not given. */
    engine: String = engineOfTitle(title),
    /** The pill's words when the row is on / off, for a row that is not «installed» (the central Worker's panels are «connected»). */
    onLabel: String? = null,
    offLabel: String? = null,
    onClick: () -> Unit,
) {
    val deployed = state == "done"
    val busy = state == "deploying"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The panel's own mark, the way Settings shows an app: a squircle in its colour with its
        // initial. The state moved to a label at the end of the row, where it reads as a word.
        val tint = panelTint(engine)
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.78f))))
                .then(if (deployed || busy) Modifier else Modifier.alpha(0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(panelMonogram(engine), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    busy -> S(R.string.installing)
                    // «نصب شده» is the label at the end of the row now; the line says only what is new.
                    deployed && configCount > 0 -> com.mlmvpn.scanner.store.tr("${faCount(configCount)} گروه کانفیگ گرفته‌اید", "$configCount config groups received")
                    deployed -> com.mlmvpn.scanner.store.tr("هنوز کانفیگی نگرفته‌اید", "No configs received yet")
                    else -> subtitle
                },
                color = if (deployed) Ios.SecondaryLabel else Ios.SecondaryLabel.copy(alpha = 0.75f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(15.dp), color = Ios.Blue, strokeWidth = 2.dp)
            stale -> PanelStatePill(com.mlmvpn.scanner.store.tr("به‌روزرسانی", "Update"), Ios.Orange)
            deployed -> PanelStatePill(onLabel ?: com.mlmvpn.scanner.store.tr("نصب شده", "Installed"), Ios.Green)
            else -> PanelStatePill(offLabel ?: com.mlmvpn.scanner.store.tr("نصب نشده", "Not installed"), Ios.SecondaryLabel)
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = Ios.Chevron,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PanelStatePill(text: String, tint: Color) {
    Text(
        text,
        color = tint,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** One or two letters for a panel's mark. */
fun panelMonogram(engine: String): String = when (engine) {
    "EDG" -> "E"
    "ZEU" -> "Z"
    "NHN" -> "N"
    "MLM" -> "M"
    "SPD" -> "S"
    "NTR" -> "Nt"
    "GZG" -> "G"
    "NVA" -> "Nv"
    else -> "B"
}

/** The engine key behind a panel row's title, for rows that only pass the title. */
fun engineOfTitle(title: String): String = when (title.trim().lowercase()) {
    "edg", "edge" -> "EDG"
    "zeus" -> "ZEU"
    "nahan" -> "NHN"
    "mlm" -> "MLM"
    "spider" -> "SPD"
    "netra" -> "NTR"
    "gozargah" -> "GZG"
    "nova" -> "NVA"
    else -> "BPB"
}

/** The four panels' colours, fixed so a badge and a header agree wherever they appear. */
fun panelTint(engine: String): Color = when (engine) {
    "EDG" -> Color(0xFF30D158)
    "ZEU" -> Color(0xFFFFB340)
    "NHN" -> Color(0xFFBF5AF2)
    "MLM" -> Color(0xFFFF9F0A)
    "SPD" -> Color(0xFFFF375F)
    "NTR" -> Color(0xFF9B59F6)
    "GZG" -> Color(0xFF40C8E0)
    "NVA" -> Color(0xFF5E5CE6)
    else -> Color(0xFF0A84FF)
}

fun panelName(engine: String): String = when (engine) {
    "EDG" -> "EDG"
    "ZEU" -> "Zeus"
    "NHN" -> "Nahan"
    "MLM" -> "MLM"
    "SPD" -> "Spider"
    "NTR" -> "Netra"
    "GZG" -> "Gozargah"
    "NVA" -> "Nova"
    else -> "BPB"
}

/**
 * Which panel a group of configs came from.
 *
 * The subtitle used to read `BPB · ۱۴۰۴/۰۶/۱۱`, and a neutral separator between a Latin token and a
 * Persian date does not stay between them in a right-to-left paragraph -- it walks to whichever end
 * the algorithm decides. A chip carries its own boundary and is scannable besides: with four panels
 * feeding one list, the panel is the first thing worth reading off a row.
 */
@Composable
fun PanelBadge(engine: String) {
    val tint = panelTint(engine)
    Text(
        panelName(engine),
        color = tint,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** What one panel's lamp is saying. */
enum class PanelLamp { NOT_DEPLOYED, DEPLOYED, HAS_CONFIGS }

fun lampOf(deployState: String, configCount: Int): PanelLamp = when {
    configCount > 0 -> PanelLamp.HAS_CONFIGS
    deployState == "done" -> PanelLamp.DEPLOYED
    else -> PanelLamp.NOT_DEPLOYED
}

/**
 * Four panels' state as four lamps, under the account's name.
 *
 * The line used to read "هنوز دیپلوی نشده" -- one sentence for an account that has four independent
 * panels, so it could only ever describe one of them (it described BPB) and quietly lied about the
 * other three. Four lamps say all four at once, in the order the panels appear directly below.
 *
 * Each lamp opens its own panel, so the mapping teaches itself on the first tap.
 */
@Composable
fun PanelLamps(
    lamps: List<Pair<String, PanelLamp>>,
    onSelect: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        lamps.forEachIndexed { index, (key, lamp) ->
            if (index > 0) Spacer(Modifier.width(5.dp))
            val color = when (lamp) {
                PanelLamp.HAS_CONFIGS -> Color(0xFF30D158)
                PanelLamp.DEPLOYED -> Color(0xFFFFD60A)
                PanelLamp.NOT_DEPLOYED -> Color.White.copy(alpha = 0.22f)
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSelect(key) }
                    // The dot is 7dp; the target around it is 22dp, which is the smallest thing
                    // on this card and must not also be the hardest to hit.
                    .size(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            when {
                lamps.all { it.second == PanelLamp.NOT_DEPLOYED } -> S(R.string.no_panel_installed)
                lamps.any { it.second == PanelLamp.HAS_CONFIGS } ->
                    S(R.string.panels_have_configs, faCount(lamps.count { it.second == PanelLamp.HAS_CONFIGS }))
                else -> S(R.string.installed_you_have_not_taken_any_configs_2)
            },
            color = Ios.SecondaryLabel,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Redeploy and remove, for a panel that is already up.
 *
 * These are the two things a deployed panel could not do before. Redeploy overwrites the script
 * that is there -- the same name, so the configs already handed out keep working -- and remove
 * really deletes it from Cloudflare rather than only clearing the row.
 */
@Composable
fun CloudPanelManageRows(
    stale: Boolean,
    busy: Boolean,
    removing: Boolean,
    onRedeploy: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
            .height(0.5.dp)
            .background(Ios.Separator)
    )
    CloudPanelRow(
        label = if (stale) S(R.string.update_to_the_latest_version) else S(R.string.redeploy),
        icon = Icons.Default.Refresh,
        tint = if (stale) Ios.Orange else Ios.Gray,
        value = if (stale) S(R.string.recommended) else null,
        valueTone = if (stale) Ios.Orange else null,
        busy = busy,
        enabled = !removing,
        onClick = onRedeploy,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
            .height(0.5.dp)
            .background(Ios.Separator)
    )
    CloudPanelRow(
        label = S(R.string.remove_this_panel_from_cloudflare),
        icon = Icons.Default.DeleteOutline,
        tint = Ios.Red,
        busy = removing,
        enabled = !busy,
        onClick = onRemove,
    )
}
