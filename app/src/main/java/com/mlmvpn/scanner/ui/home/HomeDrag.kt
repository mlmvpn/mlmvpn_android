package com.mlmvpn.scanner.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// =================================================================================================
// Dragging on the home screen, the iOS way.
//
// One drag can cross three surfaces: the board, a folder sprung open by holding an app over it,
// and back out onto the board when the app leaves the folder's panel. No single composable lives
// through all of that -- the icon's own cell leaves the board the moment it is picked up, and a
// folder's cells are gone when it closes -- so the drag does not belong to any of them. A cell only
// notices the long press and hands the pointer over; from then on [HomeDrag] follows the finger
// from the home screen's root ([homeDragTracker]), works out what is under it from geometry the
// grids publish ([GridGeometry]), and draws the icon itself in a layer above everything
// ([DragLayer]).
//
// Nothing changes the arrangement until the finger lifts. While it moves, the board shows the
// layout with the item lifted out of it ([BoardLayoutRules.lift]) and a gap where it would land;
// the drop is one of BoardLayoutRules' pure functions, applied once. A finger crossing the board
// therefore never reorders anything under itself, which is what made the first drag drift.
// =================================================================================================

/**
 * How long the icon has to REST before the board acts on what it is over. Rest, not merely be:
 * carrying an icon towards another crosses that other's edge on the way to its middle, and if
 * crossing counted, the target slid aside just before the folder could form behind it. A finger
 * slowing down pauses on the way as well, which is what keeps the reorder wait from being shorter.
 */
internal object DragTiming {
    /** Before the gap moves to the slot under the icon: crossing icons, or pausing on the way, moves nothing. */
    const val REORDER_MS = 240L
    /** Over the middle of an app, before the folder plate appears behind it. */
    const val MERGE_MS = 380L
    /** Over a folder, before it springs open to take the app at a place of the user's choosing. */
    const val SPRING_OPEN_MS = 1_050L
    /** Outside an open folder's panel, before it closes and the drag goes on over the board. */
    const val LEAVE_FOLDER_MS = 240L
    /** At a side edge, before the next page turns in (and again, while the finger stays). */
    const val EDGE_FLIP_MS = 650L
}

/**
 * A grid of icon slots as it sits on screen right now, in the home screen's root coordinates.
 * [bounds] is the visible page; slots are numbered row by row from the page's start corner, which
 * is the right-hand one in a right-to-left layout.
 */
internal class GridGeometry(
    val bounds: Rect,
    val columns: Int,
    val rows: Int,
    val page: Int,
    val pageCount: Int,
    val tilePx: Float,
    /** From a cell's top to its tile's top: the tile and its caption sit centred in the cell. */
    val tileTopPx: Float,
    val rtl: Boolean,
    /**
     * Slots at the start of the FIRST page that hold no icon: the connection widget's rows
     * (HomeConnectionWidget). Zero for a folder's panel and for a board without the widget, where
     * every formula below reduces to the plain `page * perPage + slot`.
     */
    val reserved: Int = 0,
) {
    val perPage: Int get() = columns * rows
    val cellW: Float get() = bounds.width / columns
    val cellH: Float get() = bounds.height / rows

    /** Icons the first page holds. */
    private val firstCapacity: Int get() = (perPage - reserved).coerceAtLeast(0)

    /** The visual index [slot] of the current page stands for, or -1 for a slot the widget takes. */
    fun visualAt(slot: Int): Int = when {
        page == 0 -> if (slot < reserved) -1 else slot - reserved
        else -> firstCapacity + (page - 1) * perPage + slot
    }

    /** The page visual index [visual] is on. */
    fun pageOf(visual: Int): Int = if (visual < firstCapacity) 0 else 1 + (visual - firstCapacity) / perPage

    /** The slot, on its page, of visual index [visual]. */
    fun slotOf(visual: Int): Int = if (visual < firstCapacity) visual + reserved else (visual - firstCapacity) % perPage

    /** The slot of this page under [p], or -1 when [p] is outside the grid. */
    fun slotAt(p: Offset): Int {
        if (!bounds.contains(p) || cellW <= 0f || cellH <= 0f) return -1
        val visualCol = ((p.x - bounds.left) / cellW).toInt().coerceIn(0, columns - 1)
        val col = if (rtl) columns - 1 - visualCol else visualCol
        val row = ((p.y - bounds.top) / cellH).toInt().coerceIn(0, rows - 1)
        return row * columns + col
    }

    /** The top-left corner of [slot]'s cell. */
    fun cellOrigin(slot: Int): Offset {
        val row = slot / columns
        val col = slot % columns
        val visualCol = if (rtl) columns - 1 - col else col
        return Offset(bounds.left + visualCol * cellW, bounds.top + row * cellH)
    }

    /** Where [slot]'s tile is drawn. */
    fun tileRect(slot: Int): Rect {
        val o = cellOrigin(slot)
        val left = o.x + (cellW - tilePx) / 2f
        val top = o.y + tileTopPx
        return Rect(left, top, left + tilePx, top + tilePx)
    }
}

/**
 * The drag in progress on the home screen, if any, and everything the board and an open folder
 * need to draw it. Held by [HomeScreen]; see the note at the top of this file.
 */
@Stable
internal class HomeDrag(private val scope: CoroutineScope) {

    /** What is in the air, and everything worked out from it once, when it was picked up. */
    class Session(
        val item: BoardItem,
        /** The folder it came out of; null when it came off the board. */
        val fromFolder: String?,
        val pointer: PointerId,
        val original: BoardLayout,
        val lifted: BoardLayout,
        /** Where it was: its index on the board, or in [fromFolder]. */
        val startIndex: Int,
        /** The finger's offset from the tile's top-left corner, so the tile does not jump to it. */
        val grab: Offset,
        val tilePx: Float,
    )

    var session by mutableStateOf<Session?>(null)
        private set

    /** The board as it looks while something is in the air: the lifted layout, settled. */
    var boardItems by mutableStateOf<List<BoardItem>>(emptyList())
        private set

    /** Where the gap is on the board (an index into [boardItems]), or null for none. */
    var boardGap by mutableStateOf<Int?>(null)
        private set

    /** Where the gap is in the open folder, or null for none. */
    var folderGap by mutableStateOf<Int?>(null)
        private set

    /** The item a drop would go INTO: an app id (a new folder) or a folder's `@key`. */
    var onto by mutableStateOf<String?>(null)
        private set

    /** True between the finger lifting and the icon landing; the board is already final then. */
    var settling by mutableStateOf(false)
        private set

    /** The item that is landing: its cell stays invisible until the flying copy arrives. */
    var landingId by mutableStateOf<String?>(null)
        private set

    /** The flying tile's top-left, in root coordinates. Read only in layers. */
    val position = mutableStateOf(Offset.Zero)

    /** The flying tile's scale and alpha, for the landing. Read only in layers. */
    val scale = mutableStateOf(1f)
    val alpha = mutableStateOf(1f)

    // ---------------------------------------------------------------- set up by the screen

    /** The home screen's root, the space every coordinate here is in. */
    var root: LayoutCoordinates? = null
    var boardGeometry: GridGeometry? = null
    var folderGeometry: GridGeometry? = null
    /** The open folder's panel, and whose it is. */
    var folderPanel: Rect? = null
    var folderPanelKey: String? = null

    /** Wired by [HomeScreen] on every composition, so they always see its current state. */
    var committed: () -> BoardLayout = { BoardLayout(emptyList(), emptyMap()) }
    var commit: (BoardLayout) -> Unit = {}
    var openFolderKey: () -> String? = { null }
    var springOpen: (String) -> Unit = {}
    var closeFolder: () -> Unit = {}
    /** A drop that made a new folder: opened once the icon has landed in it. */
    var onFolderMade: (String) -> Unit = {}
    var flipBoard: (Int) -> Unit = {}
    var flipFolder: (Int) -> Unit = {}
    var haptics: HapticFeedback? = null

    // ---------------------------------------------------------------- what the finger is over

    private sealed interface Hover {
        data class BoardSlot(val index: Int) : Hover
        data class OntoApp(val id: String) : Hover
        data class OntoFolder(val key: String) : Hover
        data class FolderSlot(val index: Int) : Hover
        data class Edge(val step: Int, val inFolder: Boolean) : Hover
        object OutsideFolder : Hover
        object Nowhere : Hover
    }

    private var hover: Hover = Hover.Nowhere
    private var hoverSince = 0L
    private var pointerAt = Offset.Zero
    /** Movement smaller than this between two ticks counts as resting. Set from the density. */
    var stillSlopPx = 10f
    private var lastTickPointer = Offset.Zero
    private var stillSince = 0L
    private var ticker: Job? = null
    private var springOpenedKey: String? = null

    /**
     * How far the tile still trails the finger. In edit mode a drag starts only once the finger has
     * moved a touch slop, and the tile -- still in its cell until then -- catches up with the point
     * it was picked up by, instead of jumping to it.
     */
    private var lag = Offset.Zero
    private var catchUp: Job? = null

    /**
     * Whether the icon has been over the open folder's panel yet. A folder sprung open under an
     * icon opens where it likes, often away from the icon; until the icon has been inside it, only
     * carrying the icon further away counts as leaving, measured from [panelBaseline], how far off
     * the panel it was when the panel appeared.
     */
    private var enteredPanel = false
    private var panelBaseline: Float? = null

    val active: Boolean get() = session != null && !settling

    /**
     * Picks [item] up. [at] is the finger and [tileTopLeft] the tile, both in root coordinates;
     * [grabAt] is where the finger first touched it, when that was somewhere else. Refused while an
     * icon is still landing, or when the item is not where the layout says.
     */
    fun begin(
        item: BoardItem,
        fromFolder: String?,
        pointer: PointerId,
        at: Offset,
        tileTopLeft: Offset,
        tilePx: Float,
        grabAt: Offset = at,
    ): Boolean {
        if (session != null) return false
        val original = committed()
        val startIndex = if (fromFolder == null) original.order.indexOf(item.id)
            else original.folders[fromFolder]?.apps?.indexOf(item.id) ?: -1
        if (startIndex < 0) return false
        val lifted = BoardLayoutRules.lift(original, item.id) ?: return false
        boardItems = HomeLayoutStore.toItems(BoardLayoutRules.settle(lifted))
        session = Session(item, fromFolder, pointer, original, lifted, startIndex, grabAt - tileTopLeft, tilePx)
        // The gap starts where the item was, so picking it up moves nothing.
        boardGap = if (fromFolder == null) startIndex.coerceAtMost(boardItems.size) else null
        folderGap = if (fromFolder != null) startIndex else null
        onto = null
        springOpenedKey = null
        enteredPanel = fromFolder != null
        panelBaseline = null
        settling = false
        landingId = null
        pointerAt = at
        position.value = tileTopLeft
        // The finger is already a slop away from where it took hold: the tile starts where it is and
        // closes that distance itself.
        lag = grabAt - at
        catchUp?.cancel()
        if (lag != Offset.Zero) catchUp = scope.launch {
            Animatable(lag, Offset.VectorConverter).animateTo(
                Offset.Zero,
                spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium),
            ) {
                lag = value
                val now = session
                if (now != null && !settling) position.value = pointerAt - now.grab + lag
            }
        }
        scale.value = 1f
        alpha.value = 1f
        hover = Hover.Nowhere
        hoverSince = now()
        lastTickPointer = at
        stillSince = hoverSince
        haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && active) {
                tick(now())
                delay(40)
            }
        }
        return true
    }

    fun move(at: Offset) {
        val s = session ?: return
        if (settling) return
        pointerAt = at
        position.value = at - s.grab + lag
        updateHover(s, now())
    }

    /**
     * Works out what the icon is over. Asked on every move and on every tick as well, because that
     * can change under a finger that is not moving: a gap opens, a page turns, a folder opens.
     */
    private fun updateHover(s: Session, now: Long) {
        val next = hoverAt(pointerAt, iconCenter(pointerAt, s), s)
        if (next == hover) return
        hover = next
        hoverSince = now
        // Leaving the middle of a tile lets go of it at once: only arriving takes patience.
        val stillOnto = (next as? Hover.OntoApp)?.id ?: (next as? Hover.OntoFolder)?.let { BoardLayoutRules.folderRef(it.key) }
        if (onto != null && onto != stillOnto) onto = null
    }

    /**
     * The middle of the icon in the air, where it would be without any [lag]. That, not the finger,
     * is what is held over things: people aim the icon they are carrying, and they rarely carry it
     * by its middle.
     */
    private fun iconCenter(at: Offset, s: Session): Offset = at - s.grab + Offset(s.tilePx / 2f, s.tilePx / 2f)

    /** The apps of the folder open on screen, as they show while something is in the air. */
    fun folderApps(key: String): List<HomeApp>? {
        val s = session ?: return null
        if (settling) return null
        val record = s.lifted.folders[key] ?: return null
        return record.apps.mapNotNull { HomeDestinations.gridById(it) }
    }

    /** What [c], the icon's middle, is over -- and [p], the finger, for the page edges it reaches. */
    private fun hoverAt(p: Offset, c: Offset, s: Session): Hover {
        val openKey = openFolderKey()
        if (openKey != null) {
            // Nothing to aim at until the panel has been laid out; the board behind it is not live.
            val panel = folderPanel?.takeIf { folderPanelKey == openKey } ?: return Hover.Nowhere
            // A little slack around the panel, so an icon near its edge does not close it.
            val slack = panel.width * 0.06f
            val off = distanceOutside(panel, c) - slack
            if (off > 0f) {
                if (enteredPanel) return Hover.OutsideFolder
                val baseline = panelBaseline ?: off.also { panelBaseline = it }
                return if (off > baseline + s.tilePx * 0.5f) Hover.OutsideFolder else Hover.Nowhere
            }
            enteredPanel = true
            val g = folderGeometry ?: return Hover.Nowhere
            edgeOf(p, g, inFolder = true)?.let { return it }
            val slot = g.slotAt(c)
            if (slot < 0) return Hover.Nowhere
            val count = s.lifted.folders[openKey]?.apps?.size ?: 0
            return Hover.FolderSlot((g.page * g.perPage + slot).coerceIn(0, count))
        }
        val g = boardGeometry ?: return Hover.Nowhere
        if (p.y >= g.bounds.top && p.y <= g.bounds.bottom) edgeOf(p, g, inFolder = false)?.let { return it }
        if (!g.bounds.contains(c)) return Hover.Nowhere
        val slot = g.slotAt(c)
        if (slot < 0) return Hover.Nowhere
        // Over the connection widget's rows: not a place an icon can go.
        val visual = g.visualAt(slot)
        if (visual < 0) return Hover.Nowhere
        val gap = boardGap
        val itemIndex = when {
            gap == null -> visual
            visual == gap -> -1
            visual < gap -> visual
            else -> visual - 1
        }
        val under = boardItems.getOrNull(itemIndex)
        // Only an app goes into a folder; a folder is never put inside another.
        if (under != null && s.item is BoardItem.App && inMiddle(c, g.tileRect(slot))) {
            return when (under) {
                is BoardItem.Folder -> Hover.OntoFolder(under.key)
                is BoardItem.App -> Hover.OntoApp(under.id)
            }
        }
        return Hover.BoardSlot(visual.coerceIn(0, boardItems.size))
    }

    /** How far [p] is outside [r]; zero inside it. */
    private fun distanceOutside(r: Rect, p: Offset): Float {
        val dx = maxOf(r.left - p.x, 0f, p.x - r.right)
        val dy = maxOf(r.top - p.y, 0f, p.y - r.bottom)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** The middle of a tile, where holding means "into", not "here". */
    private fun inMiddle(p: Offset, tile: Rect): Boolean {
        val inset = tile.width * 0.14f
        return Rect(tile.left + inset, tile.top + inset, tile.right - inset, tile.bottom - inset).contains(p)
    }

    /** A side edge with a page beyond it, as a page step: +1 is the next page, whatever the direction. */
    private fun edgeOf(p: Offset, g: GridGeometry, inFolder: Boolean): Hover.Edge? {
        if (g.pageCount <= 1) return null
        val band = g.cellW * 0.32f
        val atLeft = p.x < g.bounds.left + band
        val atRight = p.x > g.bounds.right - band
        if (!atLeft && !atRight) return null
        // The next page lies to the right in a left-to-right layout and to the left otherwise.
        val step = if (atRight != g.rtl) 1 else -1
        val target = g.page + step
        return if (target in 0 until g.pageCount) Hover.Edge(step, inFolder) else null
    }

    private fun tick(now: Long) {
        val s = session ?: return
        if ((pointerAt - lastTickPointer).getDistance() > stillSlopPx) stillSince = now
        lastTickPointer = pointerAt
        updateHover(s, now)
        // How long the finger has rested over what it is over now.
        val dwell = now - maxOf(hoverSince, stillSince)
        when (val h = hover) {
            is Hover.BoardSlot -> if (dwell >= DragTiming.REORDER_MS && boardGap != h.index) boardGap = h.index
            is Hover.OntoApp -> if (dwell >= DragTiming.MERGE_MS && onto != h.id) {
                onto = h.id
                haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            is Hover.OntoFolder -> {
                val ref = BoardLayoutRules.folderRef(h.key)
                if (dwell >= DragTiming.MERGE_MS && onto != ref) {
                    onto = ref
                    haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                if (dwell >= DragTiming.SPRING_OPEN_MS && springOpenedKey != h.key && openFolderKey() == null) {
                    springOpenedKey = h.key
                    enteredPanel = false
                    panelBaseline = null
                    onto = null
                    folderGap = null
                    haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
                    springOpen(h.key)
                }
            }
            is Hover.FolderSlot -> if (dwell >= DragTiming.REORDER_MS && folderGap != h.index) folderGap = h.index
            is Hover.Edge -> if (dwell >= DragTiming.EDGE_FLIP_MS) {
                if (h.inFolder) flipFolder(h.step) else flipBoard(h.step)
                hoverSince = now
                haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            // Leaving needs no rest: a folder closes as the icon is carried out of it, moving or not.
            Hover.OutsideFolder -> if (now - hoverSince >= DragTiming.LEAVE_FOLDER_MS && openFolderKey() != null) {
                // Out of the folder: it closes, and the drag carries on over the board.
                leaveFolder(s)
                hover = Hover.Nowhere
                hoverSince = now
            }
            Hover.Nowhere -> Unit
        }
    }

    /**
     * The open folder closes under a drag that left it. An app that came off the board keeps the
     * gap it had there; one that came out of this folder gets one right after the folder -- or in
     * its place, when it was the folder's last app and the folder is gone with it.
     */
    private fun leaveFolder(s: Session) {
        val key = openFolderKey() ?: return
        folderGap = null
        if (boardGap == null) {
            val ref = BoardLayoutRules.folderRef(key)
            val at = boardItems.indexOfFirst { it.id == ref }
            boardGap = when {
                at >= 0 -> at + 1
                else -> s.original.order.indexOf(ref).takeIf { it >= 0 }?.coerceAtMost(boardItems.size) ?: boardItems.size
            }
        }
        closeFolder()
    }

    /** Where the finger lifted decides everything; the icon then flies to where it went. */
    fun release() {
        val s = session ?: return
        if (settling) return
        ticker?.cancel()
        if (openFolderKey() != null && hover is Hover.OutsideFolder) {
            // Let go just outside the panel, before it had closed: out of the folder all the same.
            leaveFolder(s)
        }
        val openKey = openFolderKey()
        val id = s.item.id
        var madeFolder: String? = null
        val result: BoardLayout = when {
            openKey != null && s.item is BoardItem.App ->
                BoardLayoutRules.dropIntoFolder(s.lifted, id, openKey, folderGap)
            onto != null && BoardLayoutRules.isFolderRef(onto!!) ->
                BoardLayoutRules.dropIntoFolder(s.lifted, id, BoardLayoutRules.folderKey(onto!!))
            onto != null -> {
                val key = HomeDestinations.newFolderKey(onto!!, id)
                BoardLayoutRules.dropOnApp(s.lifted, id, onto!!, key)?.also { madeFolder = key }
            }
            boardGap != null -> BoardLayoutRules.dropOnBoard(s.lifted, id, boardGap!!)
            else -> null
        } ?: s.original
        if (result != s.original) commit(result)
        land(s, result, madeFolder)
    }

    /** The finger was taken away without lifting (the window lost it): everything goes back. */
    fun cancel() {
        val s = session ?: return
        if (settling) return
        ticker?.cancel()
        land(s, s.original, null)
    }

    private fun land(s: Session, result: BoardLayout, madeFolder: String?) {
        settling = true
        catchUp?.cancel()
        lag = Offset.Zero
        boardGap = null
        folderGap = null
        val openKey = openFolderKey()
        val id = s.item.id
        val where = BoardLayoutRules.folderOf(result, id)
        // Where the tile ends up on screen, if it is on screen at all.
        var target: Rect? = null
        var into = false
        if (where != null && where == openKey && folderPanelKey == openKey) {
            val g = folderGeometry
            val index = result.folders[where]?.apps?.indexOf(id) ?: -1
            if (g != null && index >= 0 && g.pageOf(index) == g.page) target = g.tileRect(g.slotOf(index))
        } else {
            val g = boardGeometry
            val ref = if (where != null) BoardLayoutRules.folderRef(where) else id
            into = where != null
            val index = result.order.indexOf(ref)
            if (g != null && index >= 0 && g.pageOf(index) == g.page && openKey == null) target = g.tileRect(g.slotOf(index))
        }
        // The landing tile stays hidden until its flying copy arrives; a folder it flies INTO stays
        // in view, since that is what it is flying into.
        landingId = if (into) null else id
        onto = null
        scope.launch {
            if (target != null) {
                val endScale = if (into) 0.32f else target.width / s.tilePx
                // The tile is drawn scaled about its centre: aim its centre at the target's.
                val end = target.center - Offset(s.tilePx / 2f, s.tilePx / 2f)
                val spec = spring<Offset>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
                val pos = Animatable(position.value, Offset.VectorConverter)
                val sc = Animatable(scale.value)
                val al = Animatable(alpha.value)
                launch { sc.animateTo(endScale, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { scale.value = value } }
                if (into) launch { al.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) { alpha.value = value } }
                pos.animateTo(end, spec) { position.value = value }
            } else {
                val al = Animatable(alpha.value)
                al.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) { alpha.value = value }
            }
            session = null
            settling = false
            landingId = null
            boardItems = emptyList()
            madeFolder?.let { onFolderMade(it) }
        }
    }

    private fun now(): Long = android.os.SystemClock.uptimeMillis()
}

/**
 * Follows the finger of a drag in progress, from the home screen's root: the cell that started
 * the drag may be gone a moment later. Reads the INITIAL pass, ahead of everything below it, and
 * consumes what it reads, so neither the pager nor a cell's own detector sees a drag as theirs.
 */
internal fun Modifier.homeDragTracker(drag: HomeDrag): Modifier = pointerInput(drag) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val s = drag.session ?: continue
            if (drag.settling) continue
            val change = event.changes.firstOrNull { it.id == s.pointer } ?: continue
            // A touch the system took away (a gesture of its own, the window losing focus) arrives
            // as a lift that is already consumed: that puts everything back instead of dropping.
            val cancelled = !change.pressed && change.isConsumed
            change.consume()
            when {
                change.pressed -> drag.move(change.position)
                cancelled -> drag.cancel()
                else -> drag.release()
            }
        }
    }
}

/**
 * A board or folder cell's own gestures: a tap, and the start of a drag -- after a long press, or
 * at once in edit mode, where holding is not needed to move an icon (as on iOS). Once a drag has
 * started, [homeDragTracker] has the finger.
 *
 * [tileTopLeftInRoot] places the tile, so it can be picked up from under the finger exactly where
 * it is; [coordinates] is this cell's layout, in which the pointer positions here are given.
 */
internal fun Modifier.homeItemGestures(
    key: Any,
    drag: HomeDrag,
    item: () -> BoardItem,
    fromFolder: String?,
    tilePx: () -> Float,
    editMode: () -> Boolean,
    coordinates: () -> LayoutCoordinates?,
    tileTopLeftInRoot: () -> Offset?,
    onTap: () -> Unit,
    onEnterEditMode: () -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown()
        if (drag.session != null) return@awaitEachGesture
        fun start(pointer: PointerId, local: Offset, grabLocal: Offset = local): Boolean {
            val root = drag.root ?: return false
            val own = coordinates()?.takeIf { it.isAttached } ?: return false
            val tile = tileTopLeftInRoot() ?: return false
            val at = root.localPositionOf(own, local)
            val grabAt = root.localPositionOf(own, grabLocal)
            return drag.begin(item(), fromFolder, pointer, at, tile, tilePx(), grabAt)
        }
        if (editMode()) {
            val moved = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
            if (moved != null) {
                // Held by where the finger came down, not where it had got to when it counted as a drag.
                start(moved.id, moved.position, grabLocal = down.position)
                return@awaitEachGesture
            }
            val last = currentEvent.changes.firstOrNull { it.id == down.id }
            if (last != null && last.changedToUp()) {
                last.consume()
                onTap()
            }
        } else {
            when (val press = awaitTapOrHold(down)) {
                is Press.Held -> {
                    val held = item()
                    if (fromFolder == null && held is BoardItem.App && held.app.id == com.mlmvpn.scanner.chat.MlmChat.TILE_ID) {
                        // the Gereh tile's own menu; edit mode is one of its rows (GerehTileMenu)
                        GerehTileMenu.open.value = true
                    } else {
                        onEnterEditMode()
                        start(press.change.id, press.change.position)
                    }
                }
                Press.Tapped -> onTap()
                Press.Cancelled -> Unit
            }
        }
    }
}

private sealed interface Press {
    class Held(val change: PointerInputChange) : Press
    object Tapped : Press
    object Cancelled : Press
}

/**
 * A tap or a long press, told apart the way a home screen needs.
 *
 * Not foundation's own long-press wait: that one looks at the lift once more after the Main pass,
 * by which time the board's "tap the empty space to stop rearranging" handler -- an ancestor, so
 * later in that pass -- has already consumed it, and the tap on the icon was lost. Here the lift is
 * claimed in the Main pass itself, before any ancestor sees it. A finger that wanders more than a
 * slop or two is neither: it was scrolling, or changing its mind.
 */
private suspend fun AwaitPointerEventScope.awaitTapOrHold(down: PointerInputChange): Press {
    var last = down
    val wander = viewConfiguration.touchSlop * 2f
    return try {
        withTimeout(viewConfiguration.longPressTimeoutMillis) {
            var result: Press? = null
            while (result == null) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id }
                result = when {
                    change == null -> Press.Cancelled
                    change.changedToUp() -> {
                        change.consume()
                        if ((change.position - down.position).getDistance() > wander) Press.Cancelled else Press.Tapped
                    }
                    change.isConsumed || change.isOutOfBounds(size, extendedTouchPadding) -> Press.Cancelled
                    (change.position - down.position).getDistance() > wander -> Press.Cancelled
                    else -> {
                        last = change
                        null
                    }
                }
            }
            result!!
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        Press.Held(last)
    }
}

/**
 * The icon in the air, drawn above the board, the dock and an open folder alike. Placed at the
 * root's absolute top-left and moved only by its layer, so following the finger costs a redraw and
 * no layout, and nothing about it is mirrored in a right-to-left layout.
 */
@Composable
internal fun DragLayer(drag: HomeDrag, content: @Composable (BoardItem, Float) -> Unit) {
    val s = drag.session ?: return
    val density = LocalDensity.current
    val sizeDp = with(density) { s.tilePx.toDp() }
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .size(sizeDp)
                    .graphicsLayer {
                        val p = drag.position.value
                        translationX = p.x
                        translationY = p.y
                        // Lifted: a little larger than on the board, the way iOS picks one up.
                        val lift = if (drag.settling) 1f else 1.12f
                        scaleX = drag.scale.value * lift
                        scaleY = drag.scale.value * lift
                        alpha = drag.alpha.value
                    }
            ) {
                content(s.item, s.tilePx)
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

/** A size in pixels, for callers that keep geometry in pixels. */
internal fun Size.toRect(at: Offset): Rect = Rect(at, this)
