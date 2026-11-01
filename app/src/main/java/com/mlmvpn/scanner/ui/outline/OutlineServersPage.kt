package com.mlmvpn.scanner.ui.outline

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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DismissDirection
import androidx.compose.material3.DismissValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.mlmvpn.scanner.engines.outline.OutlineBrain
import com.mlmvpn.scanner.engines.outline.OutlineConnect
import com.mlmvpn.scanner.engines.outline.OutlineEngine
import com.mlmvpn.scanner.engines.outline.OutlineSources
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.amnezia.ActionSheet
import com.mlmvpn.scanner.ui.amnezia.BarIcon
import com.mlmvpn.scanner.ui.amnezia.DelayCapsule
import com.mlmvpn.scanner.ui.amnezia.Tag
import com.mlmvpn.scanner.ui.amnezia.fa
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosRefreshIndicator
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.rememberIosRefreshState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private enum class Filter { WORKING, ALL, FAVS, MINE }
private enum class Sort { DELAY, SPEED, NEWEST }

private const val CHIP_ALL = "*all"
/** Shadowmere's keys only (tagged «shadowmere»). */
private const val CHIP_SM = "*shadowmere"

@Immutable
private data class RowModel(
    val id: String,
    val name: String,
    val country: String?,
    val method: String,
    val source: String,
    val mine: Boolean,
    /** From Shadowmere: its tag, when Shadowmere last checked it and how often it passed. */
    val shadowmere: Boolean,
    val checkedAt: Long,
    val uptime: Int?,
    val fav: Boolean,
    val prefix: Boolean,
    val ms: Long?,
    val speedBps: Long?,
    /** 0 untested, 1 working, 2 failed. */
    val status: Int,
    val testedAt: Long,
    val pending: Boolean,
    /** 0 none, 1 connecting, 2 connected. */
    val active: Int,
    val scout: Boolean,
)

/**
 * Every server, fastest first. Filters (working / all / starred / mine), country chips, sort,
 * search, and the tests. Swipe a row away to delete it -- the bar at the bottom brings it back
 * for six seconds. The order holds still while a test runs and settles, animated, when it ends.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OutlineServersPage(
    onBack: () -> Unit, onPicked: (String?) -> Unit, onPaste: () -> Unit, onScan: () -> Unit, startChip: String? = null,
) {
    val context = LocalContext.current
    val state by OutlineEngine.store.state.collectAsState()
    val net by OutlineEngine.net.collectAsState()
    val pending by OutlineEngine.pending.collectAsState()
    val progress by OutlineEngine.progress.collectAsState()
    val conn by OutlineEngine.connect.collectAsState()
    val refreshing by OutlineEngine.refreshing.collectAsState()

    // From the Shadowmere row: all of its keys, tested or not.
    var filter by rememberSaveable { mutableStateOf(if (startChip == OutlineSources.SHADOWMERE) Filter.ALL else Filter.WORKING) }
    var sort by rememberSaveable { mutableStateOf(Sort.DELAY) }
    var chip by rememberSaveable { mutableStateOf(if (startChip == OutlineSources.SHADOWMERE) CHIP_SM else CHIP_ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var qrFor by remember { mutableStateOf<String?>(null) }

    val stats = remember(state, net) { state.statsFor(net) }
    val byId = remember(state.servers) { state.servers.associateBy { it.id } }
    val anyWorking = remember(stats) { stats.values.any { it.alive } }
    // "Working" with nothing tested yet would be an empty page; it shows everything until a result exists.
    val effFilter = if (filter == Filter.WORKING && !anyWorking) Filter.ALL else filter

    val filtered = remember(state, stats, effFilter, query) {
        val q = query.trim().lowercase()
        state.servers.filter { s ->
            val ok = when (effFilter) {
                Filter.WORKING -> stats[s.id]?.alive == true || s.id in pending
                Filter.ALL -> true
                Filter.FAVS -> s.id in state.favorites
                Filter.MINE -> s.user
            }
            ok && (q.isEmpty() || s.name.lowercase().contains(q) || s.host.contains(q) || (s.country ?: "").lowercase() == q ||
                countryName(s.country).lowercase().contains(q))
        }
    }
    val countries = remember(filtered, stats) {
        filtered.groupBy { OutlineBrain.group(it) }.map { (k, v) -> Triple(k, v.count { stats[it.id]?.alive == true }, v.size) }
            .sortedWith(compareBy<Triple<String, Int, Int>>({ if (it.first == OutlineBrain.UNKNOWN) 1 else 0 }, { -it.second }, { -it.third }))
    }
    // Counted over the whole pool, so the chip stays while a filter hides its keys.
    val shadowmere = remember(state.servers, stats) { state.servers.filter { it.shadowmere }.let { l -> l.count { stats[it.id]?.alive == true } to l.size } }
    LaunchedEffect(countries, chip) { if (chip != CHIP_ALL && chip != CHIP_SM && countries.none { it.first == chip }) chip = CHIP_ALL }

    val ordered = remember(filtered, if (progress.running) null else stats, chip, sort, state.favorites) {
        val base = OutlineBrain.sorted(state.copy(servers = filtered), net)
            .filter { chip == CHIP_ALL || (chip == CHIP_SM && it.shadowmere) || OutlineBrain.group(it) == chip }
        when (sort) {
            Sort.DELAY -> base
            Sort.SPEED -> base.sortedWith(compareBy<com.mlmvpn.scanner.engines.outline.OutlineServer>({ if (it.id in state.favorites) 0 else 1 }, { -(stats[it.id]?.speedBps ?: -1L) }))
            Sort.NEWEST -> base.sortedWith(compareBy<com.mlmvpn.scanner.engines.outline.OutlineServer>({ if (it.id in state.favorites) 0 else 1 }, { -it.addedAt }))
        }.map { it.id }
    }
    val latest by rememberUpdatedState(ordered)
    var frozen by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(progress.running) { frozen = if (progress.running) latest else null }
    val shown = (frozen ?: ordered).let { ids -> if (frozen != null) ids.filter { it in byId } else ids }

    val activeId = when (val c = conn) { is OutlineConnect.Connecting -> c.serverId; is OutlineConnect.Connected -> c.serverId; else -> null }
    val activeLevel = when (conn) { is OutlineConnect.Connecting -> 1; is OutlineConnect.Connected -> 2; else -> 0 }
    val scoutId = state.scoutWinners[net]

    val refresh = rememberIosRefreshState {
        OutlineEngine.refresh(force = true)
        delay(300)
        OutlineEngine.refreshing.first { !it }
    }
    val listState = rememberLazyListState()

    IosScreen(
        title = tr("سرورها", "Servers"),
        onBack = onBack,
        backLabel = stringResource(R.string.outline_title),
        scrollable = false,
        trailing = { BarIcon(Icons.Default.Add, tr("افزودن کلید", "Add a key")) { showAdd = true } },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(refresh.connection),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "refresh", contentType = "refresh") { IosRefreshIndicator(refresh) }
            item(key = "search", contentType = "search") { SearchBar(query) { query = it } }
            item(key = "seg", contentType = "seg") {
                Segmented(
                    listOf(Filter.WORKING to tr("سالم", "Working"), Filter.ALL to tr("همه", "All"), Filter.FAVS to tr("ستاره‌دار", "Starred"), Filter.MINE to tr("کلید من", "Mine")),
                    effFilter,
                ) { filter = it }
            }
            item(key = "chips", contentType = "chips") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = CHIP_ALL) { Chip(tr("همهٔ کشورها", "All countries"), chip == CHIP_ALL) { chip = CHIP_ALL } }
                    if (shadowmere.second > 0) item(key = CHIP_SM) {
                        Chip("shadowmere · " + if (shadowmere.first > 0) "${fa(shadowmere.first)}/${fa(shadowmere.second)}" else fa(shadowmere.second), chip == CHIP_SM) { chip = CHIP_SM }
                    }
                    items(countries, key = { it.first }) { (cc, alive, _) ->
                        Chip("${flagOf(cc)} ${countryName(cc)}" + if (alive > 0) " · ${fa(alive)}" else "", chip == cc) { chip = cc }
                    }
                }
            }
            item(key = "tools", contentType = "tools") {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    AnimatedVisibility(progress.running, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Box(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Ios.CardOverWallpaper)) {
                            TestProgress(progress)
                        }
                    }
                    AnimatedVisibility(refreshing && !progress.running, enter = fadeIn(), exit = fadeOut()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = Ios.SecondaryLabel, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(tr("در حال دریافت فهرست‌ها…", "Fetching the lists…"), color = Ios.SecondaryLabel, fontSize = 13.sp)
                        }
                    }
                    if (!progress.running) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { Pill(tr("تست سریع", "Quick test"), Icons.Default.Bolt, Ios.Green) { OutlineEngine.test(all = false) } }
                            if (chip == CHIP_SM) item { Pill(tr("تست همهٔ shadowmere", "Test all shadowmere"), Icons.Default.Speed, Ios.Pink) { OutlineEngine.test(all = true, source = OutlineSources.SHADOWMERE) } }
                            else if (chip != CHIP_ALL) item { Pill(tr("تست ${countryName(chip)}", "Test ${countryName(chip)}"), Icons.Default.Speed, Ios.Blue) { OutlineEngine.test(all = true, country = chip) } }
                            item { Pill(tr("تست همه", "Test all"), Icons.Default.Speed, Ios.Indigo) { OutlineEngine.test(all = true) } }
                            item { Pill(tr("حذف قطع‌ها", "Remove dead"), Icons.Default.DeleteSweep, Ios.Red) { OutlineEngine.removeDead() } }
                        }
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("مرتب‌سازی:", "Sort:"), color = Ios.SecondaryLabel, fontSize = 12.sp)
                        SortChip(tr("پینگ", "Delay"), sort == Sort.DELAY) { sort = Sort.DELAY }
                        SortChip(tr("سرعت", "Speed"), sort == Sort.SPEED) { sort = Sort.SPEED }
                        SortChip(tr("جدیدترین", "Newest"), sort == Sort.NEWEST) { sort = Sort.NEWEST }
                    }
                }
            }
            item(key = "pick", contentType = "pick") {
                PickRow { onPicked(null) }
            }
            if (shown.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    Text(
                        when {
                            state.servers.isEmpty() && refreshing -> tr("در حال دریافت سرورها…", "Fetching servers…")
                            state.servers.isEmpty() -> tr("هنوز سروری نیست. فهرست‌ها را به‌روز کنید یا کلید خودتان را اضافه کنید.", "No servers yet. Refresh the lists or add your own key.")
                            else -> tr("با این فیلتر سروری نیست", "No server matches this filter")
                        },
                        color = Ios.SecondaryLabel, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
                    )
                }
            }
            items(shown, key = { it }, contentType = { "row" }) { id ->
                val sv = byId[id] ?: return@items
                val st = stats[id]
                val model = RowModel(
                    id = id, name = sv.name, country = sv.country, method = sv.method, source = OutlineSources.label(sv.sourceId),
                    mine = sv.user, shadowmere = sv.shadowmere, checkedAt = sv.checkedAt, uptime = sv.uptime,
                    fav = id in state.favorites, prefix = sv.prefix,
                    ms = st?.lastMs?.takeIf { it >= 0 }, speedBps = st?.speedBps,
                    status = when { st?.alive == true -> 1; st?.tested == true -> 2; else -> 0 },
                    testedAt = st?.lastTestAt ?: 0, pending = id in pending,
                    active = if (id == activeId) activeLevel else 0, scout = id == scoutId,
                )
                Box(Modifier.animateItemPlacement()) {
                    SwipeRow(
                        model = model,
                        onClick = { onPicked(id) },
                        onLongClick = { menuFor = id },
                        onStar = { OutlineEngine.toggleFavorite(id) },
                        onDelete = { OutlineEngine.delete(listOf(id)) },
                    )
                }
            }
        }
    }

    if (showAdd) {
        ActionSheet(title = tr("افزودن کلید Outline", "Add an Outline key"), onDismiss = { showAdd = false }) {
            SettingsActionRow(label = tr("چسباندن از کلیپ‌بورد", "Paste from the clipboard"), icon = Icons.Default.ContentPaste, tint = Ios.Teal) { showAdd = false; onPaste() }
            Separator()
            SettingsActionRow(label = tr("اسکن QR", "Scan a QR"), icon = Icons.Default.QrCodeScanner, tint = Ios.Blue) { showAdd = false; onScan() }
            Separator()
            SettingsActionRow(label = tr("به‌روزرسانی فهرست‌ها", "Refresh the lists"), icon = Icons.Default.Refresh, tint = Ios.Orange, busy = refreshing) {
                showAdd = false; OutlineEngine.refresh(force = true)
            }
        }
    }

    menuFor?.let { id ->
        val sv = byId[id]
        if (sv == null) { menuFor = null } else {
            ActionSheet(title = "${flagOf(sv.country)}  ${sv.name}", onDismiss = { menuFor = null }) {
                SettingsActionRow(label = tr("اتصال", "Connect"), icon = Icons.Default.PowerSettingsNew, tint = Ios.Green) { menuFor = null; onPicked(id) }
                Separator()
                SettingsActionRow(label = tr("تست همین سرور", "Test this server"), icon = Icons.Default.Speed, tint = Ios.Indigo) { menuFor = null; OutlineEngine.testOne(id) }
                Separator()
                SettingsActionRow(label = if (id in state.favorites) tr("برداشتن ستاره", "Unstar") else tr("ستاره‌دار کردن", "Star"),
                    icon = Icons.Default.Star, tint = Ios.Yellow) { menuFor = null; OutlineEngine.toggleFavorite(id) }
                Separator()
                SettingsActionRow(label = tr("کپی کلید", "Copy the key"), icon = Icons.Default.ContentCopy) {
                    menuFor = null
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("outline", sv.uri))
                    Toast.makeText(context, tr("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
                }
                Separator()
                SettingsActionRow(label = tr("نمایش QR", "Show QR"), icon = Icons.Default.QrCode) { menuFor = null; qrFor = id }
                Separator()
                SettingsActionRow(label = tr("اشتراک‌گذاری", "Share"), icon = Icons.Default.Share) {
                    menuFor = null
                    runCatching {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sv.uri)
                        }, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Separator()
                SettingsActionRow(label = tr("حذف", "Delete"), icon = Icons.Default.Delete, tint = Ios.Red) { menuFor = null; OutlineEngine.delete(listOf(id)) }
            }
        }
    }

    qrFor?.let { id ->
        val sv = byId[id]
        if (sv == null) { qrFor = null } else {
            ActionSheet(title = "${flagOf(sv.country)}  ${sv.name}", onDismiss = { qrFor = null }) { KeyQr(sv.uri) }
        }
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
            if (query.isEmpty()) Text(tr("جستجو: نام، کشور، آدرس", "Search: name, country, address"), color = Ios.SecondaryLabel, fontSize = 16.sp)
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
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(11.dp)).background(Ios.CardOverWallpaper).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (s, label) ->
            val sel = s == selected
            val bg by animateColorAsState(if (sel) Color.White.copy(alpha = 0.22f) else Color.Transparent, label = "seg")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(bg)
                    .clickable(role = Role.RadioButton) { onSelect(s) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = Ios.Label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1) }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Ios.Blue else Ios.CardOverWallpaper, label = "chip")
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun SortChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Ios.Blue else Ios.SecondaryLabel, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Ios.Blue.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun Pill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Ios.CardOverWallpaper).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Ios.Label, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun PickRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(Color(0xFF2A9D7D).copy(alpha = 0.25f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Text("⚡", fontSize = 19.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("اتصال به سریع‌ترین", "Connect to the fastest"), color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(tr("سالم‌ترین سرور همین شبکه؛ اگر داده رد نکرد، بعدی", "The best server on this network; the next if it carries nothing"), color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1)
        }
        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = Ios.Label, modifier = Modifier.size(20.dp))
    }
}

// ------------------------------------------------------------------------------ rows

/** Swipe toward the start past half the row to delete; every delete can be undone from the bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(model: RowModel, onClick: () -> Unit, onLongClick: () -> Unit, onStar: () -> Unit, onDelete: () -> Unit) {
    val currentDelete by rememberUpdatedState(onDelete)
    val dismiss = rememberDismissState(
        confirmValueChange = { v -> if (v == DismissValue.DismissedToStart) { currentDelete(); true } else false },
        positionalThreshold = { total -> total * 0.5f },
    )
    SwipeToDismiss(
        state = dismiss,
        directions = setOf(DismissDirection.EndToStart),
        background = {
            if (dismiss.dismissDirection != null) {
                val armed = dismiss.targetValue == DismissValue.DismissedToStart
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(16.dp)).background(if (armed) Ios.Red else Ios.Red.copy(alpha = 0.45f)).padding(horizontal = 22.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White) }
            }
        },
        dismissContent = { ServerRow(model, onClick, onLongClick, onStar) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(m: RowModel, onClick: () -> Unit, onLongClick: () -> Unit, onStar: () -> Unit) {
    val highlight = when (m.active) { 2 -> Ios.Green.copy(alpha = 0.22f); 1 -> Ios.Orange.copy(alpha = 0.18f); else -> Color.Transparent }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp)).background(Ios.CardOverWallpaper).background(highlight)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 62.dp).padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Text(flagOf(m.country), fontSize = 20.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Tag(countryName(m.country), Ios.Teal)
                Tag(cipherLabel(m.method), Ios.Purple)
                if (m.mine) Tag(tr("کلید من", "Mine"), Ios.Blue)
                if (m.shadowmere) Tag(OutlineSources.SHADOWMERE, Ios.Pink)
                if (m.scout) Tag(tr("تأیید Scout", "Scout-proved"), Ios.Green)
                if (m.active == 2) Tag(tr("متصل", "Connected"), Ios.Green)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    // A Shadowmere key's tag is on the line above; here, when Shadowmere last checked it.
                    m.source.takeIf { !m.mine && !m.shadowmere },
                    m.checkedAt.takeIf { m.shadowmere && it > 0 }?.let { tr("به‌روزرسانی ${checkTime(it)}", "updated ${checkTime(it)}") },
                    m.uptime?.takeIf { m.shadowmere }?.let { tr("پایداری ${fa(it)}٪", "$it% up") },
                    m.speedBps?.let { speedText(it) },
                    m.testedAt.takeIf { it > 0 }?.let { tr("تست: ${ago(it)}", "tested ${ago(it)}") },
                ).joinToString(" · "),
                color = Ios.SecondaryLabel, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.widthIn(min = 60.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                m.pending -> CircularProgressIndicator(Modifier.size(16.dp), color = Ios.SecondaryLabel, strokeWidth = 2.dp)
                m.status == 1 && m.ms != null -> DelayCapsule(m.ms)
                m.status == 2 -> Text(tr("قطع", "Dead"), color = Ios.Red, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Ios.Red.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 4.dp))
                else -> Text("—", color = Ios.SecondaryLabel, fontSize = 14.sp)
            }
        }
        Icon(
            if (m.fav) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = tr("ستاره", "Star"),
            tint = if (m.fav) Ios.Yellow else Ios.SecondaryLabel,
            modifier = Modifier.padding(start = 4.dp).size(30.dp).clip(CircleShape).clickable(onClick = onStar).padding(5.dp),
        )
    }
}
