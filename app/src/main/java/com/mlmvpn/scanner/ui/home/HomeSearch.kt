package com.mlmvpn.scanner.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.LocalSystemTopPadding
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsEntry
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsIndex

// =================================================================================================
// Search on the home screen, the way an iPhone has it: tap the dots under the board (or the
// «جستجو» pill on a board of one page) and Spotlight comes down over a frosted wallpaper with the
// keyboard already up. What it finds is the app itself -- every tile, on the board, in the dock and
// inside folders, by its Persian or English name or a word people use for it -- and below a line,
// the rows of Settings that match, each with the path to it. A tile opens exactly as its icon does;
// a row opens Settings on its page and lights itself up (SettingsSpotlight).
// =================================================================================================

/** The strip under the board that holds the search pill, with the page dots inside it. */
internal val SearchPillStrip = 30.dp

/**
 * Other words a tile is looked for by, beyond its own name in Persian and English: another
 * spelling, the protocol underneath, what people call it. Keyed by destination id.
 */
internal val TileAliases: Map<String, List<String>> = mapOf(
    "scanner" to listOf("اسکنر", "آی‌پی تمیز", "Clean IP", "IP"),
    "nodes" to listOf("Xray", "VLESS", "VMess", "Trojan", "کانفیگ", "Config"),
    "settings" to listOf("Preferences"),
    "cloud" to listOf("کلادفلر", "Cloudflare", "ورکر", "وورکر", "Worker", "پنل", "Panel"),
    "scout" to listOf("اسکات", "بهترین روش", "Best method"),
    "wireguard" to listOf("WG"),
    "warp_on_warp" to listOf("WARP on WARP", "WARP in WARP", "وارپ تو وارپ", "وارپ روی وارپ"),
    "warp" to listOf("1.1.1.1"),
    "mae" to listOf("موتور تطبیقی", "Adaptive engine"),
    "flux" to listOf("فلاکس"),
    "fenix" to listOf("فنیکس", "Firefox", "فایرفاکس"),
    "amnezia" to listOf("AmneziaWG", "Hysteria", "هیستریا"),
    "proton" to listOf("پروتون"),
    "windscribe" to listOf("ویندسکرایب", "وینداسکرایب"),
    "outline" to listOf("اوتلاین", "Shadowsocks"),
    "urnetwork" to listOf("یو آر نتورک", "یوآر", "UR", "ur.io", "BringYour"),
    "npv" to listOf("NPV Tunnel", "ان پی وی", "NapsternetV", "ناپسترنت", "npvt", "npvs"),
    "tgproxy" to listOf("MTProto", "MTProxy"),
    "game" to listOf("بازی", "پینگ", "Ping"),
    "vpngate" to listOf("VPN Gate", "VPNGate"),
    "openvpn" to listOf("اوپن وی پی ان", "OVPN"),
    "fixed_ip" to listOf("Static IP"),
    "sublink" to listOf("Subscription", "اشتراک"),
    "configstudio" to listOf("استودیو"),
    "widgetstudio" to listOf("ویجت", "Widget", "ابزارک", "استودیو ویجت"),
    "lan" to listOf("LAN", "هات اسپات", "Hotspot"),
    "store" to listOf("فروشگاه"),
    "antisanction" to listOf("ضد تحریم", "تحریم شکن"),
    "usage" to listOf("حجم", "آمار", "Data"),
    "tutorial" to listOf("راهنما", "Help", "FAQ"),
    "emergency_2" to listOf("Apps Script", "اضطراری", "Emergency"),
    "emergency_3" to listOf("اضطراری", "Emergency"),
)

/** A tile the search can find: the destination, the folder it is in (if any), and its names. */
internal class TileHit(val app: HomeApp, val folder: BoardItem.Folder?, val fields: List<SearchMatcher.Field>)

/**
 * Every destination on the home screen -- the board, inside every folder, and the dock -- once
 * each, in the order the user keeps them: page by page, a folder's apps where the folder is, the
 * dock last. Equal matches keep this order, so the search reads the way the board does.
 */
internal fun homeTiles(board: List<BoardItem>, dock: List<HomeApp>): List<Pair<HomeApp, BoardItem.Folder?>> {
    val seen = HashSet<String>()
    val out = ArrayList<Pair<HomeApp, BoardItem.Folder?>>()
    board.forEach { item ->
        when (item) {
            is BoardItem.App -> if (seen.add(item.app.id)) out += item.app to null
            is BoardItem.Folder -> item.apps.forEach { if (seen.add(it.id)) out += it to item }
        }
    }
    dock.forEach { if (seen.add(it.id)) out += it to null }
    return out
}

/** What a tile is found by: its names first, then the other words for it, then the folder it is in. */
internal fun tileFields(names: List<String>, aliases: List<String>, folderNames: List<String>): List<SearchMatcher.Field> =
    names.map { SearchMatcher.Field(it, 1f) } +
        aliases.map { SearchMatcher.Field(it, 0.9f) } +
        folderNames.map { SearchMatcher.Field(it, 0.45f) }

/**
 * Where the page dots were: a small glass pill with a magnifier, the dots inside it on a board of
 * several pages and «جستجو» on a board of one. A tap opens the search. While the board is being
 * rearranged it is the bare dots again and takes no tap of its own -- a tap there falls through to
 * the board, which finishes rearranging.
 */
@Composable
internal fun SearchPill(
    pageCount: Int,
    current: Int,
    editMode: Boolean,
    /** Off while the search is over the board, so a remote cannot move behind it. */
    focusable: Boolean,
    onSearch: () -> Unit,
) {
    val label = tr("جستجو", "Search")
    val live by rememberUpdatedState(onSearch)
    Box(modifier = Modifier.fillMaxWidth().height(SearchPillStrip), contentAlignment = Alignment.Center) {
        if (editMode) {
            if (pageCount > 1) PageDots(count = pageCount, current = current)
        } else {
            Row(
                modifier = Modifier
                    // The touch area is a little larger than the pill: it is small to look at, not to hit.
                    .pointerInput(Unit) { detectTapGestures { live() } }
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .height(24.dp)
                    .frostedGlass(RoundedCornerShape(50), overWallpaper = true, underScrim = false)
                    .tvFocusable(enabled = focusable, cornerRadius = 12, onPress = { live() })
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        role = Role.Button
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(13.dp),
                )
                if (pageCount > 1) {
                    Spacer(Modifier.width(3.dp))
                    repeat(pageCount) { i ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.5.dp)
                                .size(6.5.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (i == current) 0.9f else 0.35f)),
                        )
                    }
                } else {
                    Spacer(Modifier.width(5.dp))
                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The search itself, over the whole home screen.
 *
 * [open] is false while it fades out: it takes no more input then, and the board behind it answers
 * at once. [board] is the arrangement as it is now, so a tile added or moved into a folder is found
 * where it is.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HomeSearch(
    open: Boolean,
    progress: State<Float>,
    board: List<BoardItem>,
    liveEngines: Set<String>,
    onOpenApp: (HomeApp) -> Unit,
    /** Settings, on this route; the row to show is already armed (see SettingsIndex.prepare). */
    onOpenSetting: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val field = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }

    // Every name in both languages, whichever the app is showing: someone using it in Persian may
    // well type "settings", and the other way round.
    val persian = remember(context) { SettingsIndex.localized(context, "fa") }
    val english = remember(context) { SettingsIndex.localized(context, "en") }
    val tiles = remember(board, persian, english) {
        homeTiles(board, HomeDestinations.DOCK).map { (app, folder) ->
            val folderNames: List<String> = when {
                folder == null -> emptyList()
                folder.title != null -> listOf(folder.title)
                else -> HomeDestinations.folderTitleRes(folder.key).let { listOf(persian.getString(it), english.getString(it)) }
            }
            TileHit(
                app = app,
                folder = folder,
                fields = tileFields(
                    names = listOf(persian.getString(app.labelRes), english.getString(app.labelRes)),
                    aliases = TileAliases[app.id].orEmpty(),
                    folderNames = folderNames,
                ),
            )
        }
    }
    val rows = remember(persian, english) {
        SettingsIndex.entries.map { it to it.fields { text -> text.both(persian, english) } }
    }
    val foundTiles = remember(query, tiles) { SearchMatcher.rank(query, tiles) { it.fields } }
    val foundRows = remember(query, rows) { SearchMatcher.rank(query, rows) { it.second }.map { it.first } }

    fun putAwayKeyboard() {
        keyboard?.hide()
    }
    fun leave() {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
    }
    fun openApp(app: HomeApp) {
        leave()
        onOpenApp(app)
    }
    fun openRow(entry: SettingsEntry) {
        leave()
        onOpenSetting(SettingsIndex.prepare(context, entry))
    }
    fun cancel() {
        leave()
        onClose()
    }

    BackHandler(enabled = open) { cancel() }

    // The keyboard comes up with the search, as on iOS: a frame for the field to be in place first.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { field.requestFocus() }
        keyboard?.show()
    }

    val dark = Appearance.isDark()
    val slide = with(LocalDensity.current) { 18.dp.toPx() }
    Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }) {
        // The wallpaper, frosted, over the whole home screen; a tap on it puts the keyboard away.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .frostedGlass(RectangleShape, overWallpaper = true, underScrim = false)
                .background(Color.Black.copy(alpha = if (dark) 0.26f else 0.16f))
                .pointerInput(open) { if (open) detectTapGestures { putAwayKeyboard() } },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = LocalSystemTopPadding.current)
                .windowInsetsPadding(
                    WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    // A phone's width; on a tablet or a television, a column down the middle.
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .graphicsLayer { translationY = -(1f - progress.value) * slide },
            ) {
                SearchField(
                    query = query,
                    onQuery = { query = it },
                    focus = field,
                    onSubmit = {
                        // The keyboard's search key opens the first result, as Spotlight's does.
                        val tile = foundTiles.firstOrNull()
                        val row = foundRows.firstOrNull()
                        when {
                            tile != null -> openApp(tile.app)
                            row != null -> openRow(row)
                            else -> putAwayKeyboard()
                        }
                    },
                    onCancel = { cancel() },
                )
                SearchResults(
                    query = query,
                    tiles = foundTiles,
                    rows = foundRows,
                    liveEngines = liveEngines,
                    onApp = { openApp(it) },
                    onRow = { openRow(it) },
                    onIdle = { putAwayKeyboard() },
                    modifier = Modifier
                        .weight(1f)
                        .windowInsetsPadding(
                            WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
                        ),
                )
            }
        }
    }
}

/** The field across the top, iOS's way: a rounded grey well with a magnifier and a clear button, and «لغو» beside it. */
@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    focus: FocusRequester,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val hint = tr("جستجو", "Search")
    val clearLabel = tr("پاک کردن", "Clear")
    val liveQuery by rememberUpdatedState(onQuery)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.17f))
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                // The direction follows what is typed -- an English name reads left to right, a
                // Persian one right to left -- but the place does not: it stays beside the
                // magnifier, where the hint was, whichever language the app is in.
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 17.sp,
                    textDirection = TextDirection.Content,
                    textAlign = if (rtl) TextAlign.Right else TextAlign.Left,
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrect = false),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .semantics { contentDescription = hint },
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(hint, color = Color.White.copy(alpha = 0.55f), fontSize = 17.sp, maxLines = 1)
                        }
                        // The whole width of the well, so the alignment above has room to act: left to
                        // its own size the field is ten letters wide, and English sat halfway along it.
                        Box(modifier = Modifier.fillMaxWidth(), propagateMinConstraints = true) { inner() }
                    }
                },
            )
            if (query.isNotEmpty()) {
                // iOS's clear button: only while there is something to clear.
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .pointerInput(Unit) { detectTapGestures { liveQuery("") } }
                        .semantics {
                            contentDescription = clearLabel
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.42f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFF2C2C2E), modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            tr("لغو", "Cancel"),
            color = Color.White,
            fontSize = 17.sp,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .pressable(cornerRadius = 10, role = Role.Button, onOpen = onCancel)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

/**
 * The results: matching tiles first, as icons the way the board shows them; then, under a line, the
 * matching rows of Settings. Nothing at all before anything is typed.
 */
@Composable
private fun SearchResults(
    query: String,
    tiles: List<TileHit>,
    rows: List<SettingsEntry>,
    liveEngines: Set<String>,
    onApp: (HomeApp) -> Unit,
    onRow: (SettingsEntry) -> Unit,
    /** A tap on the empty space, or a drag of the list: the keyboard goes, the search stays. */
    onIdle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val liveIdle by rememberUpdatedState(onIdle)
    // A new query starts from the top, where its best results are.
    LaunchedEffect(query) { scroll.scrollTo(0) }
    val dragAway = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y != 0f) liveIdle()
                return Offset.Zero
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fadingEdges(top = 10.dp, bottom = 18.dp)
            .nestedScroll(dragAway)
            .pointerInput(Unit) { detectTapGestures { liveIdle() } }
            .verticalScroll(scroll)
            .padding(top = 6.dp, bottom = 28.dp),
    ) {
        when {
            query.isBlank() -> Unit
            tiles.isEmpty() && rows.isEmpty() -> Text(
                tr("نتیجه‌ای پیدا نشد", "No results"),
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 56.dp),
            )
            else -> {
                if (tiles.isNotEmpty()) TileResults(tiles, liveEngines, onApp)
                // The line between the two kinds of result, only when there are both.
                if (tiles.isNotEmpty() && rows.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .height(0.8.dp)
                            .background(Color.White.copy(alpha = 0.30f)),
                    )
                }
                if (rows.isNotEmpty()) SettingsResults(rows, query, onRow)
            }
        }
    }
}

@Composable
private fun TileResults(tiles: List<TileHit>, liveEngines: Set<String>, onApp: (HomeApp) -> Unit) {
    // The same red "1" the Settings icon wears on the board while an update waits.
    val update by com.mlmvpn.scanner.update.UpdateChecker.updateAvailableFlow.collectAsState()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val columns = (maxWidth / 86.dp).toInt().coerceIn(4, 6)
        Column {
            tiles.chunked(columns).forEach { line ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    line.forEach { hit ->
                        key(hit.app.id) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .pressable(cornerRadius = 16, onOpen = { onApp(hit.app) })
                                    .padding(top = 8.dp, bottom = 8.dp, start = 2.dp, end = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AppIconTile(
                                    app = hit.app,
                                    size = 56.dp,
                                    connected = hit.app.id in liveEngines,
                                    badge = if (hit.app.id == "settings" && update != null) 1 else 0,
                                )
                                Spacer(Modifier.height(6.dp))
                                TileCaption(stringResource(hit.app.labelRes))
                                // Where it is, when that is inside a folder.
                                hit.folder?.let { f ->
                                    Text(
                                        folderTitle(f),
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsResults(rows: List<SettingsEntry>, query: String, onRow: (SettingsEntry) -> Unit) {
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val settings = SettingsIndex.root.current(context)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .frostedGlass(RoundedCornerShape(22.dp), overWallpaper = true, underScrim = false),
    ) {
        rows.forEachIndexed { i, entry ->
            key(entry) {
                if (i > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 57.dp)
                            .height(0.5.dp)
                            .background(Ios.Separator),
                    )
                }
                val title = entry.title.current(context)
                // An arrow, not iOS's ›: in the app's font that one is a speck that reads as a comma.
                val path = (listOf(settings) + entry.path.map { it.current(context) })
                    .joinToString(if (rtl) " ← " else " → ")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable(cornerRadius = 0, onOpen = { onRow(entry) })
                        .heightIn(min = 54.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    SettingsGlyph(entry.icon(), entry.hue.color)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            bolded(title, query),
                            color = Color.White,
                            fontSize = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            path,
                            color = Ios.SecondaryLabel,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Ios.Chevron,
                        // Points the way it goes, which is leftward in Persian.
                        modifier = Modifier.size(18.dp).scale(scaleX = if (rtl) -1f else 1f, scaleY = 1f),
                    )
                }
            }
        }
    }
}

/** The words of [text] that the query found, in bold. */
private fun bolded(text: String, query: String): AnnotatedString {
    val ranges = SearchMatcher.highlights(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.first, it.last + 1) }
    }
}

/**
 * Opened by a tap or by a remote's centre key, and dimmed under a finger the way a launcher answers
 * a touch. One focus target, not two: `clickable` would add its own beside [tvFocusable]'s.
 */
@Composable
private fun Modifier.pressable(cornerRadius: Int, role: Role? = null, onOpen: () -> Unit): Modifier {
    val live by rememberUpdatedState(onOpen)
    var pressed by remember { mutableStateOf(false) }
    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = { live() },
            )
        }
        .tvFocusable(cornerRadius = cornerRadius, onPress = { live() })
        .then(if (role != null) Modifier.semantics { this.role = role } else Modifier)
        .graphicsLayer { alpha = if (pressed) 0.5f else 1f }
}
