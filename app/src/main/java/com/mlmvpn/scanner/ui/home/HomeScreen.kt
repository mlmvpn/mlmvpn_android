package com.mlmvpn.scanner.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.LocalSystemBottomPadding
import com.mlmvpn.scanner.ui.LocalSystemTopPadding
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * How the board is divided, for the space it actually has.
 *
 * The grid was a fixed 4x6, which is right for a phone held upright and wrong for everything
 * else: turned sideways, on a tablet, in DeX or on a television, four columns of icons ran down
 * the middle of the screen with two thirds of the width empty and the rows squeezed into a strip
 * too short to hold them. The shape is now derived, and [pageCount] is what stops that derivation
 * from ever hiding an icon — anything that does not fit goes onto a second page rather than off
 * the bottom edge.
 */
private data class BoardShape(val columns: Int, val rows: Int) {
    val perPage: Int get() = columns * rows

    fun pageCount(items: Int): Int =
        if (perPage <= 0) 1 else ((items + perPage - 1) / perPage).coerceAtLeast(1)
}

/** Cell pitch below which a row is too cramped to hold a tile and its caption. */
private val MIN_ROW_HEIGHT = 96.dp

/** Cell pitch below which captions start colliding with their neighbours. */
private val MIN_COLUMN_WIDTH = 92.dp

/**
 * Pick a shape for a board of [width] x [height].
 *
 * An upright phone is answered with exactly the 4x6 it has always had, deliberately and by an
 * early return: that layout is what every existing user's saved icon order was arranged against,
 * and re-deriving it from measurements would eventually disagree with it on some screen size for
 * no benefit at all.
 *
 * Everything wider than it is tall — a phone turned sideways, a tablet, DeX, a television — gets
 * as many columns as fit at [MIN_COLUMN_WIDTH], capped at six. The cap is not arithmetic: past
 * six, a 55-inch television at three metres renders icons that are large in pixels and unreadable
 * in practice, and the caption under each one is what actually fails first.
 */
private fun boardShapeFor(width: Dp, height: Dp, items: Int): BoardShape {
    val portraitPhone = width < 600.dp && height >= width
    if (portraitPhone) return BoardShape(columns = 4, rows = 6)

    val columns = (width / MIN_COLUMN_WIDTH).toInt().coerceIn(4, 6)
    val fit = (height / MIN_ROW_HEIGHT).toInt().coerceIn(2, 6)
    // Never more rows than there are icons to put in them. Without this second bound a tablet
    // computed six rows for nineteen icons, packed them into the top four, and left the bottom
    // third of the screen as a void between the last caption and the dock -- the board was
    // "correct" and looked abandoned. Taking the smaller of the two is what spreads the rows that
    // exist across the height available to them.
    val needed = ((items + columns - 1) / columns).coerceAtLeast(1)
    return BoardShape(columns = columns, rows = minOf(fit, needed).coerceAtLeast(2))
}

/** How icons slide aside and back: quick, and settling without a wobble, as on iOS. */
private val SlotSpring = spring<Offset>(dampingRatio = 0.82f, stiffness = 420f)

/** A folder opening out of its tile, and closing back into it. */
private val FolderOpenSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 340f)
private val FolderCloseSpring = spring<Float>(dampingRatio = 1f, stiffness = 520f)

/**
 * The app's front door: a wallpaper, a grid of icons, and a dock.
 *
 * This replaces both halves of the old navigation at once -- the five-icon floating bar and the
 * hamburger drawer that hid twelve more features behind it. Everything the drawer held is an
 * icon here, so nothing in the app is more than two taps away and nothing is invisible until you
 * go looking for it.
 *
 * Icons gather into folders the way they do on iOS: hold one over another and a folder grows
 * behind it; let go and the two are a folder, opened at once with a name taken from what they are.
 * Hold one over a folder and it springs open to take it wherever it is wanted inside; drag one out
 * of an open folder and it closes behind it, leaving the icon to land anywhere on the board. See
 * [HomeDrag] for how one drag crosses all of that.
 *
 * The backdrop is NOT drawn here. It is painted at the activity root, behind the system bars, so
 * it runs edge to edge with no cut line under the status icons; this screen is transparent and
 * sits on top of it.
 */
@Composable
fun HomeScreen(
    isRunning: Boolean,
    showTraffic: Boolean,
    trafficDown: Float,
    trafficUp: Float,
    onOpen: (HomeApp) -> Unit,
    /** Settings > Software update. Only reachable while [updateReady] is true. */
    onOpenUpdate: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var order by remember { mutableStateOf(HomeLayoutStore.load(context)) }
    var editMode by remember { mutableStateOf(false) }

    // The folder open over the board: [openKey] while it is open, [shownKey] for as long as it is
    // on screen at all, the closing animation included. What it shows is read back out of `order`,
    // so a change made inside it -- a new name, a member moved -- is the board's own state and the
    // two can never disagree.
    var openKey by remember { mutableStateOf<String?>(null) }
    var shownKey by remember { mutableStateOf<String?>(null) }
    val folderProgress = remember { Animatable(0f) }
    var removing by remember { mutableStateOf<BoardItem.Folder?>(null) }

    val drag = remember { HomeDrag(scope) }

    fun commit(items: List<BoardItem>) {
        order = items
        HomeLayoutStore.save(context, items)
    }
    fun commitLayout(layout: BoardLayout?) {
        if (layout != null) commit(HomeLayoutStore.toItems(layout))
    }
    fun openFolder(key: String) {
        openKey = key
        shownKey = key
        scope.launch { folderProgress.animateTo(1f, FolderOpenSpring) }
    }
    fun closeFolder() {
        if (openKey == null) return
        openKey = null
        scope.launch {
            folderProgress.animateTo(0f, FolderCloseSpring)
            if (openKey == null) shownKey = null
        }
    }

    // Wired on every composition, so the drag always acts on the board as it is now.
    drag.committed = { HomeLayoutStore.toLayout(order) }
    drag.commit = { commitLayout(it) }
    drag.openFolderKey = { openKey }
    drag.springOpen = { openFolder(it) }
    drag.closeFolder = { closeFolder() }
    drag.onFolderMade = { openFolder(it) }
    drag.haptics = LocalHapticFeedback.current
    drag.stillSlopPx = with(LocalDensity.current) { 4.dp.toPx() }

    // Leaving edit mode is what back does first, before it would ever offer to exit the app. An
    // open folder answers back itself (it closes, or leaves its own renaming first).
    BackHandler(enabled = editMode && openKey == null) { editMode = false }

    val liveEngines = ActiveEngines.ids()
    val progress: State<Float> = folderProgress.asState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { drag.root = it }
            .homeDragTracker(drag),
    ) {
        // While a folder is open the wallpaper behind everything goes to frosted glass, as on iOS.
        // Behind the board rather than over it, so the icons still show through, blurred.
        if (shownKey != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value }
                    .frostedGlass(RectangleShape, overWallpaper = true, underScrim = false),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // The board softens behind an open folder. Read in the layer, so the opening costs a
                // redraw a frame and no recomposition; below Android 12 the scrim alone does it.
                .graphicsLayer {
                    val p = progress.value
                    renderEffect = if (p > 0.01f) BlurEffect(p * 28f, p * 28f, TileMode.Decal) else null
                }
                .padding(
                    top = LocalSystemTopPadding.current,
                    bottom = LocalSystemBottomPadding.current,
                )
                // The sides matter only in landscape, and there they matter a lot: the display cutout
                // sits on one edge and the navigation bar on the other, so padding top and bottom
                // alone left the board pushed towards one side -- the icons were centred in the
                // window and visibly off-centre on the screen. `displayCutout` and `navigationBars`
                // rather than `systemBars`, because the status bar is a top inset already applied
                // above and taking it twice would add a margin nothing is behind.
                .windowInsetsPadding(
                    WindowInsets.displayCutout
                        .union(WindowInsets.navigationBars)
                        .only(WindowInsetsSides.Horizontal)
                )
        ) {
            // A newer release is announced the iOS way: a red badge on the Settings icon (see
            // AppIconCell) and a row at the top of Settings -- not a glyph in this strip.
            HomeStatusStrip(
                isRunning = isRunning,
                showTraffic = showTraffic,
                trafficDown = trafficDown,
                trafficUp = trafficUp,
                editMode = editMode,
                onDone = { editMode = false },
            )

            BoardGrid(
                // While something is in the air the board shows the rest, with a gap where it would
                // land; otherwise exactly what is saved.
                items = if (drag.active) drag.boardItems else order,
                gap = if (drag.active) drag.boardGap else null,
                drag = drag,
                editMode = editMode,
                liveEngines = liveEngines,
                shownFolder = shownKey,
                folderProgress = progress,
                onEnterEditMode = { editMode = true },
                onExitEditMode = { editMode = false },
                onOpen = { item ->
                    when (item) {
                        // A folder opens in edit mode too -- that is where it is renamed and rearranged.
                        is BoardItem.Folder -> openFolder(item.key)
                        is BoardItem.App -> if (!editMode) onOpen(item.app)
                    }
                },
                onRemoveFolder = { removing = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )

            // Centred, because the dock no longer fills the width it is given -- see GlassDock. On a
            // phone this changes nothing (the bar is wider than the screen); on anything larger it is
            // what keeps the dock under the middle of the board instead of pinned to one edge.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassDock(
                    apps = HomeDestinations.DOCK,
                    tileSize = 56.dp,
                    onOpen = { if (!editMode) onOpen(it) },
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
                )
            }
        }

        val key = shownKey
        val folder = order.firstOrNull { it is BoardItem.Folder && it.key == key } as BoardItem.Folder?
        if (key != null && folder == null && !drag.active) {
            // The folder is gone from under its own panel -- its last app left it. Nothing to show.
            LaunchedEffect(key) { closeFolder(); shownKey = null }
        }
        if (folder != null) {
            val factoryTitle = stringResource(HomeDestinations.folderTitleRes(folder.key))
            FolderOverlay(
                folder = folder,
                apps = drag.folderApps(folder.key) ?: folder.apps,
                gap = if (drag.active) drag.folderGap else null,
                open = openKey == folder.key,
                progress = progress,
                origin = { folderTileRect(drag, order, folder.key) },
                editMode = editMode,
                drag = drag,
                liveEngines = liveEngines,
                onEnterEditMode = { editMode = true },
                onExitEditMode = { editMode = false },
                onOpenApp = { app ->
                    closeFolder()
                    onOpen(app)
                },
                onClose = { closeFolder() },
                onRename = { title ->
                    commitLayout(
                        BoardLayoutRules.rename(HomeLayoutStore.toLayout(order), folder.key, title, factoryTitle)
                    )
                },
            )
        }

        DragLayer(drag) { item, tilePx ->
            val size = with(LocalDensity.current) { tilePx.toDp() }
            when (item) {
                is BoardItem.App -> AppIconTile(app = item.app, size = size, connected = item.id in liveEngines)
                is BoardItem.Folder -> FolderTile(folder = item, tileSize = size, connected = item.apps.any { it.id in liveEngines })
            }
        }
    }

    removing?.let { f ->
        val title = folderTitle(f)
        IosAlert(
            title = stringResource(R.string.home_folder_remove_title, title),
            message = stringResource(R.string.home_folder_remove_body),
            actions = listOf(
                IosAlertAction(stringResource(R.string.common_cancel), onClick = { removing = null }),
                IosAlertAction(
                    stringResource(R.string.home_folder_remove),
                    onClick = {
                        removing = null
                        commitLayout(BoardLayoutRules.ungroup(HomeLayoutStore.toLayout(order), f.key))
                    },
                    destructive = true,
                ),
            ),
            onDismiss = { removing = null },
        )
    }
}

/** Where folder [key]'s tile is on the board right now, for its panel to open out of. */
private fun folderTileRect(drag: HomeDrag, order: List<BoardItem>, key: String): Rect? {
    val g = drag.boardGeometry ?: return null
    val ref = BoardLayoutRules.folderRef(key)
    val items = if (drag.active) drag.boardItems else order
    val gap = if (drag.active) drag.boardGap else null
    val index = items.indexOfFirst { it.id == ref }.takeIf { it >= 0 } ?: return null
    val visual = if (gap != null && index >= gap) index + 1 else index
    if (visual / g.perPage != g.page) return null
    return g.tileRect(visual % g.perPage)
}

/**
 * The strip where a phone would put its clock and battery. Ours carries the one thing a VPN app
 * has to answer at a glance -- am I protected -- plus the session counters when they are on.
 */
@Composable
private fun HomeStatusStrip(
    isRunning: Boolean,
    showTraffic: Boolean,
    trafficDown: Float,
    trafficUp: Float,
    editMode: Boolean,
    onDone: () -> Unit,
) {
    val green = Color(0xFF34C759)
    val muted = Color(0xFFB9BDC2)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // The shield and its verdict in one frosted pill, the way iOS batches a status: readable
        // over any wallpaper without a scrim behind the whole strip. Real glass, not a flat fill,
        // so the pill refracts the backdrop behind it like the dock does.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .frostedGlass(RoundedCornerShape(20.dp), overWallpaper = true, underScrim = false)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = if (isRunning) green else muted.copy(alpha = 0.55f),
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(if (isRunning) R.string.vpn_connected else R.string.vpn_disconnected),
                color = if (isRunning) green else muted.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (editMode) {
            Text(
                text = stringResource(R.string.home_edit_done),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .pointerInput(Unit) { detectTapGestures { onDone() } }
                    .padding(horizontal = 16.dp, vertical = 5.dp),
            )
        } else if (showTraffic) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = if (isRunning) green else muted,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    String.format("%.1f", trafficDown),
                    color = if (isRunning) green else muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Default.Upload,
                    contentDescription = null,
                    tint = if (isRunning) Color(0xFF5AC8FA) else muted,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    String.format("%.1f", trafficUp),
                    color = if (isRunning) Color(0xFF5AC8FA) else muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * The board: pages of icons, each placed by slot and slid to its slot when the slot changes.
 *
 * The first version placed every icon by absolute pixel offset with the drag delta, the drop
 * target and a settle animation all feeding one coordinate, and icons went missing when any of
 * them was wrong; the version after it used Rows, which cannot lose an icon but cannot animate one
 * from one cell to another either -- the board jumped on every move. Here a slot is the ONLY input
 * to an icon's position: the dragged icon is not on the board at all (it flies in [DragLayer]), and
 * every other icon's slot comes from its index and the gap, so the worst a mistake can do is put
 * one in the wrong cell, visibly, never off the board.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardGrid(
    items: List<BoardItem>,
    gap: Int?,
    drag: HomeDrag,
    editMode: Boolean,
    liveEngines: Set<String>,
    shownFolder: String?,
    folderProgress: State<Float>,
    onEnterEditMode: () -> Unit,
    onExitEditMode: () -> Unit,
    onOpen: (BoardItem) -> Unit,
    onRemoveFolder: (BoardItem.Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val scope = rememberCoroutineScope()

        val slots = items.size + if (gap != null) 1 else 0
        val shape = boardShapeFor(maxWidth, maxHeight, slots)
        val pageCount = shape.pageCount(slots)
        // Declared here rather than hoisted, because its page count is a function of the measured
        // shape: a rotation changes how many icons fit and therefore how many pages exist.
        val pagerState = rememberPagerState(pageCount = { pageCount })
        // Clamped rather than remembered against the shape: rotating a phone while on the last
        // page of a taller board leaves `page` past the end of a shorter one, and a pager index
        // that no longer exists draws an empty board with no way back.
        val page = pagerState.currentPage.coerceIn(0, pageCount - 1)

        // The pager leaves the page dots their own strip below it.
        val dotsHeight = if (pageCount > 1) 15.dp else 0.dp
        val cellW = maxWidth / shape.columns
        val cellH = (maxHeight - dotsHeight) / shape.rows
        // 26dp covers the 6dp gap and the 11sp caption, so a tile can never push its own label
        // out of the cell on a short screen.
        // The ceiling rises with the cell. 64dp is right on a phone and far too small on a
        // television, where the same tile sat in a cell three times its size and read as a
        // postage stamp; the floor is unchanged because nothing gets smaller than it was.
        val tileCeiling = if (cellW >= 130.dp) 88.dp else 64.dp
        val baseTile = minOf(cellH - 26.dp, cellW - 14.dp).coerceIn(40.dp, tileCeiling)
        // ONLY the tile scales with the user's size setting. The cell pitch and the caption stay
        // where they are, which is what "make just the icon bigger" means -- scaling the cell as
        // well would merely re-space the board and leave the icons looking the same size. The
        // ceiling stops a large setting from pushing a tile over its own caption.
        val tileSize = (baseTile * (Appearance.iconScale / 100f))
            .coerceAtMost(minOf(cellH - 20.dp, cellW - 4.dp))
        val tilePx = with(density) { tileSize.toPx() }

        // Measured from a real cell: the tile and its caption sit centred together in the cell.
        val contentHeight = remember { mutableFloatStateOf(0f) }
        var pagerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

        fun publish() {
            val root = drag.root ?: return
            val c = pagerCoords?.takeIf { it.isAttached } ?: return
            val bounds = root.localBoundingBoxOf(c, clipBounds = false)
            val cellHpx = bounds.height / shape.rows
            val content = contentHeight.floatValue.takeIf { it > 0f } ?: (tilePx + with(density) { 22.dp.toPx() })
            drag.boardGeometry = GridGeometry(
                bounds = bounds,
                columns = shape.columns,
                rows = shape.rows,
                page = pagerState.currentPage.coerceIn(0, pageCount - 1),
                pageCount = pageCount,
                tilePx = tilePx,
                tileTopPx = ((cellHpx - content) / 2f).coerceAtLeast(0f),
                rtl = rtl,
            )
        }
        LaunchedEffect(pagerState.currentPage, pageCount, shape, tilePx, rtl, contentHeight.floatValue) { publish() }
        drag.flipBoard = { step ->
            val to = (pagerState.currentPage + step).coerceIn(0, pageCount - 1)
            scope.launch { pagerState.animateScrollToPage(to) }
        }

        // Only while rearranging: an infinite transition left in composition never stops asking for
        // frames, which pins the whole app at 60fps forever -- the failure documented in
        // AppScreen's emergency vignette, where it destabilised the GPU RenderThread.
        // In performance mode the board stays still instead: the jiggle is pure decoration and
        // exactly the frame budget an old GPU does not have.
        val jiggle: State<Float> = if (editMode && !com.mlmvpn.scanner.utils.Perf.isLowEnd(context)) {
            rememberInfiniteTransition(label = "HomeJiggle").animateFloat(
                initialValue = -1.7f,
                targetValue = 1.7f,
                animationSpec = infiniteRepeatable(
                    animation = tween(170, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "HomeJiggleAngle",
            )
        } else {
            remember { mutableStateOf(0f) }
        }

        val liveEditMode by rememberUpdatedState(editMode)
        val liveOnExitEditMode by rememberUpdatedState(onExitEditMode)

        Column(
            modifier = Modifier
                .fillMaxSize()
                // A tap anywhere the icons are not finishes rearranging, the same as the "Done"
                // button. Only the empty space reaches this: an icon's own gestures take the press.
                .pointerInput(Unit) {
                    detectTapGestures { if (liveEditMode) liveOnExitEditMode() }
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned {
                        pagerCoords = it
                        publish()
                    },
                // A drag turns pages itself, by holding at the edge; the pager must not take it.
                userScrollEnabled = pageCount > 1 && !drag.active,
            ) { pageIndex ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                    val cellWpx = with(density) { cellW.toPx() }
                    val cellHpx = with(density) { cellH.toPx() }
                    val first = pageIndex * shape.perPage
                    items.forEachIndexed { index, item ->
                        val visual = if (gap != null && index >= gap) index + 1 else index
                        if (visual < first || visual >= first + shape.perPage) return@forEachIndexed
                        val slot = visual - first
                        val row = slot / shape.columns
                        val col = slot % shape.columns
                        val visualCol = if (rtl) shape.columns - 1 - col else col
                        key(item.id) {
                            val position = rememberSlotPosition(Offset(visualCol * cellWpx, row * cellHpx))
                            BoardCell(
                                item = item,
                                drag = drag,
                                fromFolder = null,
                                tileSize = tileSize,
                                tilePx = tilePx,
                                editMode = editMode,
                                liveEditMode = { liveEditMode },
                                jiggle = jiggle,
                                jigglePhase = if (visual % 2 == 0) 1f else -1f,
                                connected = when (item) {
                                    is BoardItem.App -> item.id in liveEngines
                                    is BoardItem.Folder -> item.apps.any { it.id in liveEngines }
                                },
                                hiddenBy = if (item is BoardItem.Folder && item.key == shownFolder) folderProgress else null,
                                contentHeight = contentHeight,
                                onTap = { onOpen(item) },
                                onEnterEditMode = onEnterEditMode,
                                onRemove = (item as? BoardItem.Folder)?.let { f -> { onRemoveFolder(f) } },
                                modifier = Modifier
                                    .absoluteOffset { position.value.toIntOffset() }
                                    .size(cellW, cellH),
                            )
                        }
                    }
                }
            }

            if (pageCount > 1) PageDots(count = pageCount, current = page)
        }
    }
}

/** Where an icon is, slid there from wherever it last was. */
@Composable
internal fun rememberSlotPosition(target: Offset): State<Offset> {
    val anim = remember { Animatable(target, Offset.VectorConverter) }
    LaunchedEffect(target) {
        if (anim.value != target) anim.animateTo(target, SlotSpring)
    }
    return anim.asState()
}

internal fun Offset.toIntOffset(): IntOffset = IntOffset(x.roundToInt(), y.roundToInt())

/**
 * One cell of the board or of an open folder: its tile and caption, and everything a cell does
 * -- open on a tap, start a drag, jiggle while rearranging, show the plate a folder grows from.
 */
@Composable
internal fun BoardCell(
    item: BoardItem,
    drag: HomeDrag,
    fromFolder: String?,
    tileSize: Dp,
    tilePx: Float,
    editMode: Boolean,
    liveEditMode: () -> Boolean,
    jiggle: State<Float>,
    jigglePhase: Float,
    connected: Boolean,
    /** For the folder whose panel is open: its tile fades as the panel grows out of it. */
    hiddenBy: State<Float>?,
    contentHeight: androidx.compose.runtime.MutableFloatState,
    onTap: () -> Unit,
    onEnterEditMode: () -> Unit,
    /** Folders only: the edit-mode badge that takes the folder apart. */
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // The gestures live as long as the cell; these keep what they act on current.
    val currentItem by rememberUpdatedState(item)
    val currentTilePx by rememberUpdatedState(tilePx)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnEnterEditMode by rememberUpdatedState(onEnterEditMode)
    val landing = drag.landingId == item.id
    val target = drag.onto == item.id
    // The plate a new folder grows from, behind an app held over; a folder held over swells.
    val plate by animateFloatAsState(if (target) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 520f), label = "plate")

    Box(
        modifier = modifier
            .zIndex(if (target) 1f else 0f)
            .onGloballyPositioned { coords = it }
            .homeItemGestures(
                key = item.id,
                drag = drag,
                item = { currentItem },
                fromFolder = fromFolder,
                tilePx = { currentTilePx },
                editMode = liveEditMode,
                coordinates = { coords },
                tileTopLeftInRoot = {
                    val root = drag.root
                    val own = coords
                    if (root == null || own == null || !own.isAttached) null else {
                        val cell = root.localBoundingBoxOf(own, clipBounds = false)
                        val top = ((cell.height - contentHeight.floatValue) / 2f).coerceAtLeast(0f)
                        Offset(cell.left + (cell.width - currentTilePx) / 2f, cell.top + top)
                    }
                },
                onTap = { currentOnTap() },
                onEnterEditMode = { currentOnEnterEditMode() },
            )
            .tvFocusable(onPress = onTap)
            .graphicsLayer {
                alpha = when {
                    landing -> 0f
                    hiddenBy != null -> 1f - hiddenBy.value
                    else -> 1f
                }
                rotationZ = jiggle.value * jigglePhase
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.onSizeChanged { contentHeight.floatValue = it.height.toFloat() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(tileSize)) {
                when (item) {
                    is BoardItem.App -> {
                        if (plate > 0.01f) {
                            Box(
                                modifier = Modifier
                                    .size(tileSize)
                                    .graphicsLayer {
                                        val s = 1f + 0.26f * plate
                                        scaleX = s
                                        scaleY = s
                                        alpha = plate
                                    }
                                    .frostedGlass(FolderShape, overWallpaper = true, underScrim = false),
                            )
                        }
                        AppIconTile(app = item.app, size = tileSize, connected = connected)
                    }
                    is BoardItem.Folder -> FolderTile(
                        folder = item,
                        tileSize = tileSize,
                        connected = connected,
                        modifier = Modifier.graphicsLayer {
                            val s = 1f + 0.12f * plate
                            scaleX = s
                            scaleY = s
                        },
                    )
                }
                if (editMode && onRemove != null) {
                    RemoveBadge(size = tileSize, onClick = onRemove)
                }
            }
            Spacer(Modifier.height(6.dp))
            TileCaption(
                when (item) {
                    is BoardItem.App -> stringResource(item.app.labelRes)
                    is BoardItem.Folder -> folderTitle(item)
                }
            )
        }
    }
}

/**
 * iOS's minus badge, top-left of an icon while the board is being rearranged. Only folders wear
 * it here -- the destinations are the app itself and cannot be removed -- and on a folder it takes
 * the folder apart, its apps going back onto the board.
 */
@Composable
private fun RemoveBadge(size: Dp, onClick: () -> Unit) {
    val d = (size * 0.34f).coerceIn(18.dp, 24.dp)
    val description = stringResource(R.string.home_folder_remove)
    // Physical top-left in both directions, like iOS.
    val corner = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Alignment.TopEnd else Alignment.TopStart
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .align(corner)
                .absoluteOffset(x = -(d * 0.32f), y = -(d * 0.32f))
                .size(d)
                .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .background(Color(0xFF8E8E93).copy(alpha = 0.96f))
                .semantics { contentDescription = description }
                .pointerInput(Unit) { detectTapGestures { onClick() } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Remove, contentDescription = null, tint = Color.White, modifier = Modifier.size(d * 0.72f))
        }
    }
}

/**
 * The row of dots under a board that has more than one page.
 *
 * Drawn only when there IS more than one page. A single dot under a board that cannot be swiped
 * is an invitation to try, and the thing it invites does nothing.
 */
@Composable
internal fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            // iOS's tell: the dot for the page you are on stretches into a small pill instead of
            // merely brightening, so the position reads at a glance even with several pages.
            val active = i == current
            val dotWidth by animateDpAsState(
                targetValue = if (active) 18.dp else 7.dp,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f),
                label = "pageDotWidth",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(dotWidth)
                    .height(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Color.White.copy(alpha = if (active) 0.9f else 0.28f)
                    )
            )
        }
    }
}
