package com.mlmvpn.scanner.ui.tunnel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sort
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.tunnel.IpFormatter
import com.mlmvpn.core.tunnel.bridge.BridgeLineError
import com.mlmvpn.core.tunnel.bridge.BridgeManager
import com.mlmvpn.core.tunnel.bridge.BridgeManager.Entry
import com.mlmvpn.core.tunnel.bridge.BridgeManager.Status
import com.mlmvpn.core.tunnel.bridge.BridgeSources
import com.mlmvpn.core.tunnel.bridge.BridgeTransport
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

/** The page on Tor's screen that holds everything about bridges. */
const val TOR_BRIDGES_PAGE = "tor_bridges"

/**
 * The value of the «پل‌ها» row on Tor's own screen: the choice in two words, and in automatic mode
 * how many bridges are known to work. The row is all a typical user ever needs to see.
 */
@Composable
fun torBridgesValue(): String {
    val ctx = LocalContext.current
    // Read in off the main thread: the stored list is every bridge ever collected, each parsed
    // on the way in, and the Tor screen froze on it as it opened (freeze report, 1.2.41).
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BridgeManager.init(ctx) } }
    val state by BridgeManager.state.collectAsState()
    val p = if (BridgeManager.ready) BridgeManager.pinned(ctx) else null
    if (p != null) {
        return stringResource(R.string.tor_br_pinned_value) + " · " + (p.country?.let { IpFormatter.flag(it) + " " } ?: "") + p.bridge.transport.label
    }
    return when (BridgeManager.choice(ctx)) {
        BridgeManager.Choice.AUTO -> {
            val ok = state.entries.count { it.status == Status.OK }
            if (ok > 0) stringResource(R.string.tor_br_value_auto_ok, fa(ok)) else stringResource(R.string.tor_br_choice_auto)
        }
        BridgeManager.Choice.MINE -> stringResource(R.string.tor_br_value_mine, fa(state.entries.count { it.custom }))
        BridgeManager.Choice.BUILTIN -> stringResource(R.string.tor_br_choice_builtin)
    }
}

private enum class Sort { HEALTH, SPEED, COUNTRY, SOURCE }

/**
 * Bridges, from the plain to the technical, top to bottom:
 *  1. where Tor's bridges come from -- three choices in words, automatic first;
 *  2. one line saying how things stand, with the two actions anyone needs (refresh, test);
 *  3. the bridges themselves: country, transport, «فعال · ۱۸۷ ms»; a tap shows the details;
 *  4. adding one's own bridges, and -- last -- the sources with their state.
 * Fingerprints, certs and bridge lines appear only on a bridge's own page.
 */
@Composable
fun TorBridgesScreen(onBack: () -> Unit, backLabel: String) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BridgeManager.init(ctx) }
        BridgeManager.refreshIfDue(ctx)
    }
    var page by rememberSaveable { mutableStateOf("list") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != "list") { page = "list" }
    when (page) {
        "add" -> { AddBridgesPage(onBack = { page = "list" }); return }
        "detail" -> {
            val e = BridgeManager.state.collectAsState().value.entries.firstOrNull { it.bridge.id == openId }
            if (e != null) { BridgeDetailPage(e, onBack = { page = "list" }); return }
            page = "list"
        }
    }
    BridgeListPage(onBack, backLabel, onAdd = { page = "add" }, onOpen = { openId = it; page = "detail" })
}

@Composable
private fun BridgeListPage(onBack: () -> Unit, backLabel: String, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val state by BridgeManager.state.collectAsState()
    var choice by remember { mutableStateOf(BridgeManager.choice(ctx)) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.HEALTH) }
    var visible by rememberSaveable { mutableStateOf(PAGE) }
    // The hand-picked bridge; null once it is unpinned or deleted.
    var pinnedId by remember { mutableStateOf(if (BridgeManager.ready) BridgeManager.pinned(ctx)?.bridge?.id else null) }
    // Once the stored bridges are in (read off the main thread above): the pin among them.
    LaunchedEffect(BridgeManager.ready) { if (BridgeManager.ready) pinnedId = BridgeManager.pinned(ctx)?.bridge?.id }
    val pinned = state.entries.firstOrNull { it.bridge.id == pinnedId }

    IosScreen(title = stringResource(R.string.tor_br_title), onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(12.dp))

        // ── 1. where they come from ──
        SettingsGroup {
            ChoiceRow(BridgeManager.Choice.AUTO, choice, R.string.tor_br_choice_auto, R.string.tor_br_choice_auto_sub, Icons.Default.AutoMode) { choice = it; BridgeManager.setChoice(ctx, it) }
            Separator()
            ChoiceRow(BridgeManager.Choice.MINE, choice, R.string.tor_br_choice_mine, R.string.tor_br_choice_mine_sub, Icons.Default.Person) { choice = it; BridgeManager.setChoice(ctx, it) }
            Separator()
            ChoiceRow(BridgeManager.Choice.BUILTIN, choice, R.string.tor_br_choice_builtin, R.string.tor_br_choice_builtin_sub, Icons.Default.Inventory2) { choice = it; BridgeManager.setChoice(ctx, it) }
        }
        SettingsFooter(stringResource(R.string.tor_br_choice_footer))

        // ── 2. how things stand, and what to do about it ──
        val ok = state.entries.count { it.status == Status.OK }
        val anySourceOk = state.sources.values.any { it.ok }
        val allSourcesDown = state.sources.isNotEmpty() && !anySourceOk
        SettingsSectionHeader(stringResource(R.string.tor_br_status_section))
        if (pinned != null) {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.tor_br_pinned_row,
                        (pinned.country?.let { CountryLabel.withFlag(it) } ?: stringResource(R.string.tor_br_country_unknown)) + " · " + pinned.bridge.transport.label),
                    subtitle = stringResource(R.string.tor_br_pinned_note),
                    icon = Icons.Default.PushPin, tint = Ios.Orange, showChevron = true,
                    onClick = { onOpen(pinned.bridge.id) },
                )
                Separator()
                SettingsActionRow(
                    label = stringResource(R.string.tor_br_unpin), icon = Icons.Default.AutoMode, tint = Ios.Blue,
                    onClick = { BridgeManager.setPinned(ctx, null); pinnedId = null },
                )
            }
            Spacer(Modifier.height(14.dp))
        }
        SettingsGroup {
            SettingsRow(
                title = when {
                    state.refreshing && state.entries.isEmpty() -> stringResource(R.string.tor_br_fetching)
                    state.entries.isEmpty() -> stringResource(R.string.tor_br_none_yet)
                    else -> stringResource(R.string.tor_br_summary, fa(ok), fa(state.entries.size))
                },
                subtitle = when {
                    allSourcesDown -> stringResource(R.string.tor_br_sources_down)
                    state.lastRefreshAt > 0 -> stringResource(R.string.tor_br_updated, ago(state.lastRefreshAt))
                    else -> null
                },
                icon = Icons.Default.Speed, tint = if (ok > 0) Ios.Green else Ios.Gray, showChevron = false,
            )
            Separator()
            SettingsActionRow(
                label = stringResource(if (state.refreshing) R.string.tor_br_refreshing else R.string.tor_br_refresh),
                icon = Icons.Default.Refresh, tint = Ios.Blue, busy = state.refreshing,
                enabled = !state.refreshing, onClick = { BridgeManager.refresh(ctx, force = true) },
            )
            Separator()
            SettingsActionRow(
                label = if (state.testing) stringResource(R.string.tor_br_testing, fa(state.testDone), fa(state.testTotal))
                else stringResource(R.string.tor_br_test_all),
                icon = Icons.Default.Speed, tint = Ios.Green, busy = state.testing,
                enabled = !state.testing && state.entries.isNotEmpty(),
                onClick = { BridgeManager.testAll(ctx, filter?.let { f -> BridgeTransport.entries.firstOrNull { it.name == f } }) },
            )
            val dead = state.entries.count { it.status == Status.FAILED || it.status == Status.WAS_OK }
            if (dead > 0) {
                Separator()
                SettingsActionRow(
                    label = stringResource(R.string.tor_br_remove_dead, fa(dead)), icon = Icons.Default.DeleteSweep, tint = Ios.Red,
                    enabled = !state.testing,
                    onClick = {
                        val n = BridgeManager.remove(ctx, BridgeManager.deadIds())
                        pinnedId = BridgeManager.pinned(ctx)?.bridge?.id
                        Toast.makeText(ctx, ctx.getString(R.string.tor_br_removed_n, fa(n)), Toast.LENGTH_SHORT).show()
                    },
                )
            }
            Separator()
            SettingsActionRow(label = stringResource(R.string.tor_br_add), icon = Icons.Default.Add, tint = Ios.Purple, onClick = onAdd)
        }

        SettingsFooter(stringResource(R.string.tor_exit_tip))

        // ── 3. the bridges ──
        if (state.entries.isNotEmpty()) {
            val present = BridgeTransport.entries.filter { t -> state.entries.any { it.bridge.transport == t } }
            SettingsSectionHeader(stringResource(R.string.tor_br_list_section))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip(stringResource(R.string.tor_br_all), filter == null) { filter = null; visible = PAGE }
                present.forEach { t -> Chip(t.label, filter == t.name) { filter = t.name; visible = PAGE } }
                Chip("⇅ " + sortLabel(sort), false) { sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]; visible = PAGE }
            }
            // Countries, most bridges first, each with how many of its bridges work.
            val byTransport = state.entries.filter { filter == null || it.bridge.transport.name == filter }
            val countries = byTransport.groupBy { it.country ?: UNKNOWN }.entries
                .sortedWith(compareByDescending<Map.Entry<String, List<Entry>>> { g -> g.value.count { it.status == Status.OK } }.thenByDescending { it.value.size })
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip(stringResource(R.string.tor_br_all_countries), country == null) { country = null; visible = PAGE }
                countries.forEach { (cc, list) ->
                    val label = (if (cc == UNKNOWN) "🌐" else IpFormatter.flag(cc)) + " " + fa(list.count { it.status == Status.OK }) + "/" + fa(list.size)
                    Chip(label, country == cc) { country = cc; visible = PAGE }
                }
            }
            Spacer(Modifier.height(8.dp))
            val shown = byTransport
                .filter { country == null || (it.country ?: UNKNOWN) == country }
                .let { list -> sorted(list, sort) }
            // Every bridge of the chosen transport is down: say so, and where to go instead.
            if (filter != null && shown.isNotEmpty() && shown.all { it.status == Status.FAILED || it.status == Status.WAS_OK }) {
                Note(stringResource(R.string.tor_br_transport_all_down))
            }
            if (shown.any { it.status == Status.NEEDS_IPV6 } && shown.all { it.status == Status.NEEDS_IPV6 || it.status == Status.FAILED }) {
                Note(stringResource(R.string.tor_br_needs_ipv6_note))
            }
            // A page at a time: hundreds of rows at once in one scrolling column froze the app.
            val page = shown.take(visible)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                page.forEachIndexed { i, e ->
                    // Sorted by country, every country gets its own heading.
                    if (sort == Sort.COUNTRY && country == null && (i == 0 || page[i - 1].country != e.country)) {
                        Text(
                            e.country?.let { CountryLabel.withFlag(it) } ?: stringResource(R.string.tor_br_country_unknown),
                            color = Ios.SecondaryLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp, start = 4.dp),
                        )
                    }
                    BridgeRow(e, pinned = e.bridge.id == pinnedId) { onOpen(e.bridge.id) }
                }
            }
            if (shown.size > visible) {
                val left = shown.size - visible
                Text(
                    stringResource(R.string.tor_br_more, fa(minOf(PAGE_STEP, left)), fa(left)), color = Ios.Blue, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { visible += PAGE_STEP }.padding(vertical = 12.dp, horizontal = 20.dp),
                )
            }
        }

        // ── 4. the sources, last ──
        SettingsSectionHeader(stringResource(R.string.tor_br_sources_section))
        SettingsGroup {
            BridgeSources.ALL.forEachIndexed { i, s ->
                if (i > 0) Separator()
                val st = state.sources[s.id]
                SettingsRow(
                    title = s.label,
                    value = stringResource(if (s.official) R.string.tor_br_official else R.string.tor_br_community),
                    subtitle = when {
                        st == null -> stringResource(R.string.tor_br_source_never)
                        !st.ok -> stringResource(R.string.tor_br_source_down, ago(st.fetchedAt))
                        else -> stringResource(R.string.tor_br_source_ok, fa(st.count), ago(st.fetchedAt))
                    },
                    showChevron = false,
                )
            }
        }
        SettingsFooter(stringResource(R.string.tor_br_sources_footer))
        Spacer(Modifier.height(28.dp))
    }
}

private const val PAGE = 30
private const val PAGE_STEP = 40
private const val UNKNOWN = "??"

private fun sorted(list: List<Entry>, sort: Sort): List<Entry> = when (sort) {
    Sort.HEALTH -> list.sortedWith(compareBy<Entry> { rank(it.status) }.thenBy { it.rttMs ?: Long.MAX_VALUE })
    Sort.SPEED -> list.sortedWith(compareBy<Entry> { it.rttMs ?: Long.MAX_VALUE }.thenBy { rank(it.status) })
    Sort.COUNTRY -> list.sortedWith(compareBy<Entry> { it.country ?: "ZZ" }.thenBy { rank(it.status) })
    Sort.SOURCE -> list.sortedWith(compareBy<Entry> { if (it.custom) 0 else if (it.official) 1 else 2 }.thenBy { rank(it.status) })
}

private fun rank(s: Status) = when (s) {
    Status.OK -> 0; Status.UNTESTED -> 1; Status.WAS_OK -> 2; Status.NEEDS_IPV6 -> 3; Status.FAILED -> 4
}

@Composable
private fun sortLabel(s: Sort) = stringResource(when (s) {
    Sort.HEALTH -> R.string.tor_br_sort_health
    Sort.SPEED -> R.string.tor_br_sort_speed
    Sort.COUNTRY -> R.string.tor_br_sort_country
    Sort.SOURCE -> R.string.tor_br_sort_source
})

@Composable
private fun ChoiceRow(
    value: BridgeManager.Choice, current: BridgeManager.Choice, title: Int, sub: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector, onPick: (BridgeManager.Choice) -> Unit,
) {
    SettingsRow(
        title = stringResource(title), subtitle = stringResource(sub),
        icon = if (value == current) Icons.Default.Check else icon,
        tint = if (value == current) Ios.Green else Ios.Gray,
        showChevron = false, onClick = { onPick(value) },
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Ios.Blue else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = if (selected) Color.White else Ios.Label, fontSize = 13.sp) }
}

@Composable
private fun Note(text: String) {
    Text(
        text, color = Ios.Orange, fontSize = 13.sp, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/** One bridge, in words: where, how, and whether it answers. */
@Composable
private fun BridgeRow(e: Entry, pinned: Boolean, onClick: () -> Unit) {
    // A plain card, not frostedGlass: the glass redraws the wallpaper under every row, and
    // hundreds of those were what froze the list.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ios.CardOverWallpaper).clickable(onClick = onClick)
            .heightIn(min = 54.dp).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e.country?.let { IpFormatter.flag(it) } ?: "🌐", fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.country?.let { CountryLabel.localized(it) } ?: stringResource(R.string.tor_br_country_unknown),
                    color = Ios.Label, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Tag(e.bridge.transport.label, Ios.Indigo)
                if (pinned) { Spacer(Modifier.width(4.dp)); Tag(stringResource(R.string.tor_br_pinned_tag), Ios.Orange) }
                if (e.custom) { Spacer(Modifier.width(4.dp)); Tag(stringResource(R.string.tor_br_mine_tag), Ios.Purple) }
                else if (e.official) { Spacer(Modifier.width(4.dp)); Tag(stringResource(R.string.tor_br_official), Ios.Teal) }
            }
            Text(statusLine(e), color = statusColor(e.status), fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** «فعال · ۱۸۷ ms · ۵ دقیقه پیش», and the like. */
@Composable
private fun statusLine(e: Entry): String {
    val base = when (e.status) {
        Status.OK -> stringResource(R.string.tor_br_ok_ms, fa((e.rttMs ?: 0).toInt()))
        Status.UNTESTED -> stringResource(R.string.tor_br_untested)
        Status.FAILED -> stringResource(R.string.tor_br_failed)
        Status.WAS_OK -> stringResource(R.string.tor_br_was_ok)
        Status.NEEDS_IPV6 -> stringResource(R.string.tor_br_needs_ipv6)
    }
    val worked = if (System.currentTimeMillis() - e.torOkAt < 7 * 24 * 3600_000L) " · " + stringResource(R.string.tor_br_tor_worked) else ""
    return base + worked + (if (e.testedAt > 0) " · " + ago(e.testedAt) else "")
}

/** Composable: the Ios colours follow the theme (light / dark). */
@Composable
private fun statusColor(s: Status) = when (s) {
    Status.OK -> Ios.Green
    Status.FAILED, Status.WAS_OK -> Ios.Red
    Status.NEEDS_IPV6 -> Ios.Orange
    Status.UNTESTED -> Ios.SecondaryLabel
}

// ── one bridge ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun BridgeDetailPage(e: Entry, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by BridgeManager.state.collectAsState()
    var isPinned by remember { mutableStateOf(BridgeManager.pinned(ctx)?.bridge?.id == e.bridge.id) }
    IosScreen(title = e.bridge.transport.label, onBack = onBack, backLabel = stringResource(R.string.tor_br_title)) {
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            SettingsRow(stringResource(R.string.tor_br_d_status), value = statusLine(e), showChevron = false)
            Separator()
            SettingsRow(stringResource(R.string.tor_br_d_country),
                value = e.country?.let { CountryLabel.withFlag(it) } ?: stringResource(R.string.tor_br_country_unknown), showChevron = false)
            Separator()
            SettingsRow(stringResource(R.string.tor_br_d_server), value = "${e.bridge.dialHost}:${e.bridge.dialPort}", showChevron = false)
            Separator()
            SettingsRow(stringResource(R.string.tor_br_d_source),
                value = when {
                    e.custom && e.sources.isEmpty() -> stringResource(R.string.tor_br_mine_tag)
                    else -> e.sources.map { id -> BridgeSources.byId(id)?.label ?: if (id == BridgeManager.SEED_SOURCE) stringResource(R.string.tor_br_source_seed) else id }.joinToString("، ")
                },
                subtitle = stringResource(if (e.official) R.string.tor_br_official else R.string.tor_br_community),
                showChevron = false)
            Separator()
            SettingsRow(stringResource(R.string.tor_br_d_seen), value = ago(e.firstSeen), showChevron = false)
        }
        SettingsGroup(modifier = Modifier.padding(top = 14.dp)) {
            SettingsActionRow(
                label = stringResource(if (isPinned) R.string.tor_br_unpin else R.string.tor_br_use_this),
                icon = if (isPinned) Icons.Default.AutoMode else Icons.Default.PushPin, tint = Ios.Orange,
                onClick = {
                    BridgeManager.setPinned(ctx, if (isPinned) null else e.bridge.id)
                    isPinned = !isPinned
                },
            )
            Separator()
            SettingsActionRow(label = stringResource(R.string.tor_br_test_one), icon = Icons.Default.Speed, tint = Ios.Green,
                busy = state.testing, onClick = { BridgeManager.testOne(ctx, e.bridge.id) })
            Separator()
            SettingsActionRow(label = stringResource(R.string.tor_br_copy), icon = Icons.Default.ContentCopy, tint = Ios.Blue,
                onClick = { copy(ctx, e.bridge.line) })
            Separator()
            SettingsActionRow(label = stringResource(R.string.tor_br_remove), icon = Icons.Default.Delete, tint = Ios.Red,
                onClick = { BridgeManager.remove(ctx, setOf(e.bridge.id)); onBack() })
        }
        if (isPinned) SettingsFooter(stringResource(R.string.tor_br_pinned_note))
        // The technical part, for whoever wants it.
        SettingsSectionHeader(stringResource(R.string.tor_br_d_line))
        Text(
            e.bridge.line, color = Ios.SecondaryLabel, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(RoundedCornerShape(12.dp)).padding(12.dp),
        )
        SettingsFooter(stringResource(R.string.tor_br_d_footer))
        Spacer(Modifier.height(28.dp))
    }
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("bridge", text))
    Toast.makeText(ctx, ctx.getString(R.string.tor_br_copied), Toast.LENGTH_SHORT).show()
}

// ── adding one's own ────────────────────────────────────────────────────────────────────────────

@Composable
private fun AddBridgesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<BridgeManager.AddResult?>(null) }
    var details by remember { mutableStateOf(false) }
    IosScreen(title = stringResource(R.string.tor_br_add), onBack = onBack, backLabel = stringResource(R.string.tor_br_title)) {
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(RoundedCornerShape(12.dp))
                .heightIn(min = 140.dp).padding(12.dp),
        ) {
            BasicTextField(
                value = text, onValueChange = { text = it; result = null },
                textStyle = TextStyle(color = Ios.Label, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Ios.Blue),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box { if (text.isEmpty()) Text(stringResource(R.string.tor_br_add_hint), color = Ios.SecondaryLabel, fontSize = 13.sp); inner() }
                },
            )
        }
        SettingsFooter(stringResource(R.string.tor_br_add_footer))
        SettingsGroup {
            SettingsActionRow(
                label = stringResource(R.string.tor_br_add_button), icon = Icons.Default.Add, tint = Ios.Purple,
                enabled = text.isNotBlank(),
                onClick = { result = BridgeManager.addCustom(ctx, text); if (result?.bad?.isEmpty() == true) text = "" },
            )
        }
        result?.let { r ->
            val lines = buildList {
                if (r.added > 0) add(stringResource(R.string.tor_br_added, fa(r.added)))
                if (r.duplicates > 0) add(stringResource(R.string.tor_br_dups, fa(r.duplicates)))
                if (r.bad.isNotEmpty()) add(stringResource(R.string.tor_br_bad, fa(r.bad.size)))
                if (r.added == 0 && r.duplicates == 0 && r.bad.isEmpty()) add(stringResource(R.string.tor_br_nothing))
            }
            Text(lines.joinToString("\n"), color = if (r.bad.isEmpty()) Ios.Green else Ios.Orange, fontSize = 14.sp, lineHeight = 22.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp))
            if (r.bad.isNotEmpty()) {
                Text(stringResource(if (details) R.string.tor_br_hide_details else R.string.tor_br_show_details), color = Ios.Blue, fontSize = 13.sp,
                    modifier = Modifier.clickable { details = !details }.padding(horizontal = 20.dp, vertical = 4.dp))
                if (details) {
                    r.bad.forEach { b ->
                        Text("• " + badReason(b.error) + "\n" + b.input.take(90), color = Ios.SecondaryLabel, fontSize = 11.sp, lineHeight = 16.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun badReason(e: BridgeLineError) = stringResource(when (e) {
    BridgeLineError.EMPTY -> R.string.tor_br_err_empty
    BridgeLineError.UNSUPPORTED_TRANSPORT -> R.string.tor_br_err_transport
    BridgeLineError.CONJURE -> R.string.tor_br_err_conjure
    BridgeLineError.BAD_ADDRESS -> R.string.tor_br_err_address
    BridgeLineError.BAD_PORT -> R.string.tor_br_err_port
    BridgeLineError.BAD_FINGERPRINT -> R.string.tor_br_err_fingerprint
    BridgeLineError.MISSING_CERT -> R.string.tor_br_err_cert
    BridgeLineError.MISSING_URL -> R.string.tor_br_err_url
    BridgeLineError.BAD_URL -> R.string.tor_br_err_url
})

// ── small things ────────────────────────────────────────────────────────────────────────────────

private fun fa(n: Int): String = faDigits(n.toString())

@Composable
private fun ago(at: Long): String {
    val min = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> stringResource(R.string.tor_br_ago_now)
        min < 60 -> stringResource(R.string.tor_br_ago_min, faDigits(min.toString()))
        min < 48 * 60 -> stringResource(R.string.tor_br_ago_hour, faDigits((min / 60).toString()))
        else -> stringResource(R.string.tor_br_ago_day, faDigits((min / 1440).toString()))
    }
}

// ── on the connected screen ─────────────────────────────────────────────────────────────────────

/**
 * Under Tor's exit card: the bridge this session came in through, and -- when its country is not
 * the exit's -- why. A bridge is only the way in; Tor builds the rest of the circuit itself, so a
 * British bridge with a Dutch exit is Tor working, not a mislabelled bridge.
 */
@Composable
fun TorEntryCard(exitCode: String) {
    val id by BridgeManager.sessionBridge.collectAsState()
    val state by BridgeManager.state.collectAsState()
    val e = id?.let { x -> state.entries.firstOrNull { it.bridge.id == x } } ?: return
    val entry = e.country?.uppercase()
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().frostedGlass(RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.tor_entry_from), color = Ios.SecondaryLabel, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(
            (entry?.let { CountryLabel.withFlag(it) } ?: stringResource(R.string.tor_br_country_unknown)) + " · " + e.bridge.transport.label,
            color = Ios.Label, fontSize = 15.sp, maxLines = 1,
        )
    }
    val exit = exitCode.uppercase().takeIf { IpFormatter.isRealCountry(it) }
    if (entry != null && exit != null && entry != exit) {
        Text(
            stringResource(R.string.tor_entry_exit_differ, CountryLabel.localized(entry), CountryLabel.localized(exit)) +
                "\n" + stringResource(R.string.tor_exit_tip),
            color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}
