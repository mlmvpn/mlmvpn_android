package com.mlmvpn.scanner.ui.amnezia

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DismissDirection
import androidx.compose.material3.DismissValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SwipeToDismiss
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDismissState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.amnezia.AmzBrain
import com.mlmvpn.scanner.engines.amnezia.AmzConnect
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.engines.amnezia.AmzKind
import com.mlmvpn.scanner.engines.amnezia.AmzServer
import com.mlmvpn.scanner.engines.amnezia.AmzStat
import com.mlmvpn.scanner.engines.amnezia.AmzState
import com.mlmvpn.scanner.engines.amnezia.AmzTestMode
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosRefreshIndicator
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.rememberIosRefreshState
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Which protocols the list shows. */
private enum class Seg { ALL, HY2, WG, MINE }

private const val CHIP_ALL = "*all"
private const val CHIP_FAST = "*fast"

/** What one row draws. Immutable, so a row recomposes only when its own values change. */
@Immutable
private data class RowModel(
    val id: String,
    val name: String,
    val group: String,
    val kind: AmzKind,
    val mine: Boolean,
    val ms: Long?,
    /** 0 untested, 1 working, 2 failed. */
    val status: Int,
    val pending: Boolean,
    /** 0 none, 1 connecting to it, 2 connected to it. */
    val active: Int,
)

@Immutable
private data class Section(val key: String, val ids: List<String>)

/**
 * Every server, by group (country, WARP, «نامشخص»), fastest first, with search, protocol filter,
 * group chips and the tests. Picking a server, or "the fastest of this group", sets the main
 * page's location and connects.
 *
 * Built for smooth scrolling on long lists: one LazyColumn with stable keys and content types,
 * immutable row models, no per-row glass (a frosted card samples the wallpaper on every move,
 * which a list of hundreds cannot afford), and an order that holds still while a test is running
 * and settles, animated, when it ends.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AmzServersScreen(
    onBack: () -> Unit,
    onPicked: (AmzEngine.Target) -> Unit,
    onAddFile: () -> Unit,
    onPaste: () -> Unit,
) {
    val context = LocalContext.current
    val state by AmzEngine.store.state.collectAsState()
    val net by AmzEngine.net.collectAsState()
    val pending by AmzEngine.pending.collectAsState()
    val progress by AmzEngine.progress.collectAsState()
    val conn by AmzEngine.connect.collectAsState()
    val refreshing by AmzEngine.refreshing.collectAsState()

    var seg by rememberSaveable { mutableStateOf(Seg.ALL) }
    var chip by rememberSaveable { mutableStateOf(CHIP_ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    val stats: Map<String, AmzStat> = remember(state, net) { state.statsFor(net) }
    val byId = remember(state.servers) { state.servers.associateBy { it.id } }
    val filtered = remember(state.servers, seg, query) {
        val q = query.trim().lowercase()
        state.servers.filter {
            val kindOk = when (seg) {
                Seg.ALL -> true
                Seg.HY2 -> it.kind == AmzKind.HY2
                Seg.WG -> it.kind != AmzKind.HY2
                Seg.MINE -> it.user
            }
            kindOk && (q.isEmpty() || it.name.lowercase().contains(q) || it.host.contains(q) || (it.country ?: "").lowercase() == q)
        }
    }
    val groups = remember(filtered, stats) { groupChips(filtered, stats) }
    // A chip for a group that emptied out (moved, removed) falls back to all.
    LaunchedEffect(groups, chip) {
        if (chip != CHIP_ALL && chip != CHIP_FAST && groups.none { it.first == chip }) chip = CHIP_ALL
    }
    // The order is computed when not testing, and held while a test runs: rows keep their place
    // while their delays fill in, and then everything settles at once.
    val sorted = remember(filtered, if (progress.running) null else stats, chip, net) { sections(filtered, state, net, chip) }
    val latest by rememberUpdatedState(sorted)
    var frozen by remember { mutableStateOf<List<Section>?>(null) }
    LaunchedEffect(progress.running) { frozen = if (progress.running) latest else null }
    val shown = frozen ?: sorted

    val activeId = when (val c = conn) { is AmzConnect.Connecting -> c.serverId; is AmzConnect.Connected -> c.serverId; else -> null }
    val activeLevel = when (conn) { is AmzConnect.Connecting -> 1; is AmzConnect.Connected -> 2; else -> 0 }
    val groupChip = chip.takeIf { it != CHIP_ALL && it != CHIP_FAST }

    val refresh = rememberIosRefreshState {
        AmzEngine.refresh(force = true)
        delay(300)
        AmzEngine.refreshing.first { !it }
    }
    val listState = rememberLazyListState()

    IosScreen(
        title = stringResource(R.string.amz_servers_title),
        onBack = onBack,
        backLabel = stringResource(R.string.amz_title),
        scrollable = false,
        trailing = { BarIcon(Icons.Default.Add, stringResource(R.string.amz_add_title)) { showAdd = true } },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(refresh.connection),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "refresh", contentType = "refresh") { IosRefreshIndicator(refresh) }
            item(key = "search", contentType = "search") { SearchBar(query) { query = it } }
            item(key = "seg", contentType = "seg") { Segmented(seg) { seg = it } }
            item(key = "chips", contentType = "chips") { GroupChips(groups, chip) { chip = it } }
            item(key = "tools", contentType = "tools") {
                TestBar(
                    running = progress.running, done = progress.done, total = progress.total, alive = progress.alive,
                    refreshing = refreshing, groupChip = groupChip,
                    onBest = { AmzEngine.test(AmzTestMode.BEST, kind = segKind(seg)) },
                    onAll = { AmzEngine.test(AmzTestMode.ALL, kind = segKind(seg)) },
                    onGroup = { g -> AmzEngine.test(AmzTestMode.COUNTRY, country = g, kind = segKind(seg)) },
                    onDead = { AmzEngine.removeDead() },
                    onStop = { AmzEngine.stopTest() },
                )
            }
            // The quick picks: the fastest anywhere, or the fastest of the chosen group.
            item(key = "pick", contentType = "pick") {
                if (groupChip != null) PickRow(groupFlag(groupChip), stringResource(R.string.amz_pick_group), groupName(groupChip)) {
                    onPicked(AmzEngine.Target.Group(groupChip))
                } else PickRow("⚡", stringResource(R.string.amz_target_auto), stringResource(R.string.amz_target_auto_sub)) {
                    onPicked(AmzEngine.Target.Auto)
                }
            }
            if (state.servers.isEmpty() || shown.all { it.ids.isEmpty() }) {
                item(key = "empty", contentType = "empty") {
                    Text(
                        stringResource(if (state.servers.isEmpty()) R.string.amz_empty else R.string.amz_empty_filter),
                        color = Ios.SecondaryLabel, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
                    )
                }
            }
            shown.forEach { section ->
                if (section.ids.isEmpty()) return@forEach
                if (chip == CHIP_ALL) {
                    stickyHeader(key = "h_${section.key}", contentType = "header") {
                        GroupHeader(section.key, section.ids.size, section.ids.count { stats[it]?.alive == true })
                    }
                }
                items(section.ids, key = { it }, contentType = { "row" }) { id ->
                    val sv = byId[id] ?: return@items
                    val st = stats[id]
                    val model = RowModel(
                        id = id, name = sv.name, group = AmzBrain.group(sv), kind = sv.kind, mine = sv.user,
                        ms = st?.lastMs?.takeIf { it >= 0 },
                        status = when { st?.alive == true -> 1; st?.tested == true -> 2; else -> 0 },
                        pending = id in pending,
                        active = if (id == activeId) activeLevel else 0,
                    )
                    Box(Modifier.animateItemPlacement()) {
                        SwipeRow(
                            model = model,
                            onClick = { onPicked(AmzEngine.Target.Server(id)) },
                            onLongClick = { menuFor = id },
                            onDelete = { AmzEngine.delete(listOf(id)) },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        ActionSheet(title = stringResource(R.string.amz_add_title), onDismiss = { showAdd = false }) {
            SettingsActionRow(label = stringResource(R.string.amz_add_file), icon = Icons.Default.FileOpen, tint = Ios.Blue) { showAdd = false; onAddFile() }
            Separator()
            SettingsActionRow(label = stringResource(R.string.amz_paste), icon = Icons.Default.ContentPaste, tint = Ios.Teal) { showAdd = false; onPaste() }
            Separator()
            SettingsActionRow(label = stringResource(R.string.amz_refresh), icon = Icons.Default.Refresh, tint = Ios.Orange, busy = refreshing) {
                showAdd = false; AmzEngine.refresh(force = true)
            }
        }
    }

    menuFor?.let { id ->
        val sv = byId[id]
        if (sv == null) { menuFor = null } else {
            ActionSheet(title = "${groupFlag(AmzBrain.group(sv))}  ${sv.name}", onDismiss = { menuFor = null }) {
                SettingsActionRow(label = stringResource(R.string.amz_connect), icon = Icons.Default.PowerSettingsNew, tint = Ios.Green) {
                    menuFor = null; onPicked(AmzEngine.Target.Server(id))
                }
                Separator()
                SettingsActionRow(label = stringResource(R.string.amz_menu_test), icon = Icons.Default.Speed, tint = Ios.Indigo) {
                    menuFor = null; AmzEngine.testOne(id)
                }
                Separator()
                SettingsActionRow(label = stringResource(R.string.amz_menu_copy), icon = Icons.Default.ContentCopy) {
                    menuFor = null
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("config", sv.raw))
                    Toast.makeText(context, S(R.string.amz_copied), Toast.LENGTH_SHORT).show()
                }
                Separator()
                SettingsActionRow(label = stringResource(R.string.amz_menu_share), icon = Icons.Default.Share) {
                    menuFor = null
                    runCatching {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, sv.raw)
                        }, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Separator()
                SettingsActionRow(label = stringResource(R.string.amz_menu_delete), icon = Icons.Default.Delete, tint = Ios.Red) {
                    menuFor = null; AmzEngine.delete(listOf(id))
                }
            }
        }
    }
}

private fun segKind(seg: Seg): AmzKind? = when (seg) {
    Seg.HY2 -> AmzKind.HY2
    Seg.WG -> AmzKind.WG
    else -> null
}

/** Group chips: key and how many servers work there, in the list's own order. */
private fun groupChips(servers: List<AmzServer>, stats: Map<String, AmzStat>): List<Pair<String, Int>> {
    val groups = servers.groupBy { AmzBrain.group(it) }
    return groups.entries.map { (key, list) ->
        val best = list.mapNotNull { stats[it.id]?.takeIf { s -> s.alive }?.lastMs }.minOrNull()
        Triple(key, list.count { stats[it.id]?.alive == true }, best)
    }.sortedWith(compareBy<Triple<String, Int, Long?>>(
        { if (it.first == AmzBrain.UNKNOWN) 2 else if (it.third != null) 0 else 1 },
        { it.third ?: Long.MAX_VALUE },
    )).map { it.first to it.second }
}

private fun sections(servers: List<AmzServer>, state: AmzState, net: String, chip: String): List<Section> {
    val now = System.currentTimeMillis()
    val stats = state.statsFor(net)
    fun sortIds(list: List<AmzServer>) =
        AmzBrain.sortRows(list.map { AmzBrain.Row(it, stats[it.id]) }, state, net, now).map { it.server.id }
    return when (chip) {
        CHIP_FAST -> listOf(Section(CHIP_FAST, servers.filter { stats[it.id]?.alive == true }
            .sortedBy { stats[it.id]!!.lastMs }.take(60).map { it.id }))
        CHIP_ALL -> {
            val groups = servers.groupBy { AmzBrain.group(it) }
            groupChips(servers, stats).map { (key, _) -> Section(key, sortIds(groups[key].orEmpty())) }
        }
        else -> listOf(Section(chip, sortIds(servers.filter { AmzBrain.group(it) == chip })))
    }
}

// ------------------------------------------------------------------------------ controls

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp)).background(Ios.CardOverWallpaper).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Ios.SecondaryLabel, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.amz_search), color = Ios.SecondaryLabel, fontSize = 16.sp)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp), cursorBrush = SolidColor(Ios.Blue),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Default.Close, contentDescription = null, tint = Ios.SecondaryLabel,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onChange("") })
        }
    }
}

@Composable
private fun Segmented(seg: Seg, onSelect: (Seg) -> Unit) {
    val options = listOf(
        Seg.ALL to stringResource(R.string.amz_seg_all),
        Seg.HY2 to stringResource(R.string.amz_seg_hy2),
        Seg.WG to stringResource(R.string.amz_seg_wg),
        Seg.MINE to stringResource(R.string.amz_seg_mine),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(11.dp)).background(Ios.CardOverWallpaper).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (s, label) ->
            val sel = s == seg
            val bg by animateColorAsState(if (sel) Color.White.copy(alpha = 0.22f) else Color.Transparent, label = "seg")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(bg)
                    .clickable(role = Role.RadioButton) { onSelect(s) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = Ios.Label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GroupChips(groups: List<Pair<String, Int>>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = CHIP_FAST) { Chip("⚡ " + stringResource(R.string.amz_chip_fastest), selected == CHIP_FAST) { onSelect(CHIP_FAST) } }
        item(key = CHIP_ALL) { Chip(stringResource(R.string.amz_chip_all_countries), selected == CHIP_ALL) { onSelect(CHIP_ALL) } }
        items(groups, key = { it.first }) { (key, alive) ->
            Chip("${groupFlag(key)} ${groupName(key)}" + if (alive > 0) " · ${fa(alive)}" else "", selected == key) { onSelect(key) }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Ios.Blue else Ios.CardOverWallpaper, label = "chip")
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun TestBar(
    running: Boolean, done: Int, total: Int, alive: Int, refreshing: Boolean, groupChip: String?,
    onBest: () -> Unit, onAll: () -> Unit, onGroup: (String) -> Unit, onDead: () -> Unit, onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        AnimatedVisibility(running, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Ios.CardOverWallpaper)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.amz_testing, fa(done), fa(total), fa(alive)), color = Ios.Label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        stringResource(R.string.amz_stop), color = Ios.Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onStop).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = if (total > 0) done.toFloat() / total else 0f,
                    color = Ios.Green, trackColor = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
        AnimatedVisibility(refreshing && !running, enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Ios.SecondaryLabel, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.amz_refreshing), color = Ios.SecondaryLabel, fontSize = 13.sp)
            }
        }
        if (!running) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill(stringResource(R.string.amz_test_best), Icons.Default.Bolt, Ios.Green, onBest) }
                if (groupChip != null) item { Pill(stringResource(R.string.amz_test_country), Icons.Default.Speed, Ios.Blue) { onGroup(groupChip) } }
                item { Pill(stringResource(R.string.amz_test_all), Icons.Default.Speed, Ios.Indigo, onAll) }
                item { Pill(stringResource(R.string.amz_remove_dead), Icons.Default.DeleteSweep, Ios.Red, onDead) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Ios.CardOverWallpaper).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Ios.Label, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun PickRow(mark: String, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(Ios.Blue.copy(alpha = 0.22f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Text(mark, fontSize = 19.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(sub, color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1)
        }
        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = Ios.Label, modifier = Modifier.size(20.dp))
    }
}

// ------------------------------------------------------------------------------ the list

@Composable
private fun GroupHeader(key: String, count: Int, alive: Int) {
    Row(
        Modifier.fillMaxWidth().background(Ios.Background.copy(alpha = 0.78f))
            .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${groupFlag(key)}  ${groupName(key)}", color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.amz_servers_count, fa(count), fa(alive)), color = Ios.SecondaryLabel, fontSize = 12.sp)
    }
}

/**
 * Swipe to delete, the iOS way: one direction only (toward the start), and only a deliberate
 * swipe past half the row counts -- a vertical scroll that drifts sideways must never delete a
 * working server. The red is drawn only while the row is actually moving (the row itself is
 * translucent, so a background drawn all the time showed through it), and every delete can be
 * undone from the bar at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(model: RowModel, onClick: () -> Unit, onLongClick: () -> Unit, onDelete: () -> Unit) {
    val currentDelete by rememberUpdatedState(onDelete)
    val dismiss = rememberDismissState(
        confirmValueChange = { v ->
            if (v == DismissValue.DismissedToStart) { currentDelete(); true } else false
        },
        positionalThreshold = { total -> total * 0.5f },
    )
    SwipeToDismiss(
        state = dismiss,
        directions = setOf(DismissDirection.EndToStart),
        background = {
            val moving = dismiss.dismissDirection != null
            if (moving) {
                val armed = dismiss.targetValue == DismissValue.DismissedToStart
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(16.dp)).background(if (armed) Ios.Red else Ios.Red.copy(alpha = 0.45f))
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White) }
            }
        },
        dismissContent = { ServerRow(model, onClick, onLongClick) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(m: RowModel, onClick: () -> Unit, onLongClick: () -> Unit) {
    val highlight = when (m.active) { 2 -> Ios.Green.copy(alpha = 0.22f); 1 -> Ios.Orange.copy(alpha = 0.18f); else -> Color.Transparent }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Ios.CardOverWallpaper)
            .background(highlight)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 58.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Text(groupFlag(m.group), fontSize = 19.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(kindLabel(m.kind), kindColor(m.kind))
                if (m.mine) { Spacer(Modifier.width(5.dp)); Tag(stringResource(R.string.amz_mine_tag), Ios.Blue) }
                if (m.active == 2) { Spacer(Modifier.width(5.dp)); Tag(stringResource(R.string.amz_status_connected), Ios.Green) }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.widthIn(min = 64.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                m.pending -> CircularProgressIndicator(Modifier.size(16.dp), color = Ios.SecondaryLabel, strokeWidth = 2.dp)
                m.status == 1 && m.ms != null -> DelayCapsule(m.ms)
                m.status == 2 -> Text(stringResource(R.string.amz_dead), color = Ios.Red, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Ios.Red.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 4.dp))
                else -> Text("—", color = Ios.SecondaryLabel, fontSize = 14.sp)
            }
        }
    }
}
