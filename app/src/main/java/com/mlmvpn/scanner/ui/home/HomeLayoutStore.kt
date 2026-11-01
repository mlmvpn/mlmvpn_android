package com.mlmvpn.scanner.ui.home

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** One cell of the home board: a destination, or a folder of them. */
sealed class BoardItem {
    abstract val id: String

    data class App(val app: HomeApp) : BoardItem() {
        override val id: String get() = app.id
    }

    /**
     * A named group of destinations, opened as a panel above the board.
     *
     * [title] is null while the folder carries its factory name -- see [HomeDestinations.folderTitleRes].
     */
    data class Folder(val key: String, val title: String?, val apps: List<HomeApp>) : BoardItem() {
        override val id: String get() = BoardLayoutRules.folderRef(key)
    }
}

/**
 * Where the user's icon arrangement lives.
 *
 * Only ids are stored -- the order of the top level, with `@key` for a folder, and each folder's
 * name and members -- never the icons or labels themselves, which belong to [HomeDestinations] and
 * change with every release. [BoardLayoutRules.reconcile] brings a saved arrangement up to date with
 * what this release has, so shipping a feature adds an icon instead of resetting anyone's board and
 * removing one leaves no gap.
 */
object HomeLayoutStore {

    private const val KEY = "home_icon_order"
    private const val KEY_FOLDERS = "home_folders"
    /** Set once the tools have been moved into their folder on a board arranged before folders. */
    private const val KEY_FOLDERS_MIGRATED = "home_folders_v1"
    /** Set once MLM Scout has been placed on an existing board's first page. */
    private const val KEY_SCOUT_PROMOTED = "home_scout_promoted_v1"
    /** Set once «لنترن» (1.2.42) has been placed on an existing board's first page. */
    private const val KEY_LANTERN_PROMOTED = "home_lantern_promoted_v1"
    /** Set once each of «URnetwork» and «NPV» has been placed on an existing board's first page. */
    private val NEW_ENGINES_PROMOTED = listOf(
        "urnetwork" to "home_urnetwork_promoted_v1",
        "npv" to "home_npv_promoted_v1",
    )
    /** Set once «ویجت استدیو» has been placed on an existing board's first page. */
    private const val KEY_WIDGET_STUDIO_PROMOTED = "home_widgetstudio_promoted_v1"
    /** Icons a later promotion leaves where an earlier one put them. */
    private val PROMOTED = setOf("scout", "lantern", "urnetwork", "npv", "widgetstudio")

    /** The connection widget's slots on the first page of an upright phone (4 columns x 2 rows). */
    private const val WIDGET_SLOTS = 8

    /** 4 columns x 6 rows. Past this the grid would need horizontal paging, which it has not got. */
    const val CAPACITY = 24

    fun load(context: Context): List<BoardItem> = withoutRemoved(context, loadAll(context))

    /** Gereh taken out of the app (q3): its tile is nowhere, as if the app never had it. */
    private fun withoutRemoved(context: Context, items: List<BoardItem>): List<BoardItem> {
        if (!com.mlmvpn.scanner.chat.MlmChat.removed(context)) return items
        val gereh = com.mlmvpn.scanner.chat.MlmChat.TILE_ID
        return items.mapNotNull { item ->
            when (item) {
                is BoardItem.App -> item.takeIf { it.id != gereh }
                is BoardItem.Folder -> item.copy(apps = item.apps.filter { it.id != gereh }).takeIf { it.apps.isNotEmpty() }
            }
        }
    }

    private fun loadAll(context: Context): List<BoardItem> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedOrder = prefs.getString(KEY, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
        val savedFolders = prefs.getString(KEY_FOLDERS, null)?.let(::parseFolders)
        val migrated = prefs.getBoolean(KEY_FOLDERS_MIGRATED, false)

        var layout = BoardLayoutRules.reconcile(
            savedOrder = savedOrder,
            savedFolders = savedFolders,
            migrated = migrated,
            known = HomeDestinations.GRID_DEFAULT.map { it.id },
            boardDefault = HomeDestinations.BOARD_DEFAULT,
            defaultFolders = HomeDestinations.DEFAULT_FOLDERS,
        )
        // The one-time move into the folder is written back straight away, so it happens exactly
        // once even for someone who never touches the board again.
        if (savedOrder != null && !migrated) write(context, layout)
        if (savedOrder == null) return toItems(layout)

        var promoted = false
        // MLM Scout arrived after these boards were arranged, and the reconciliation appends a new
        // destination at the very end -- a page the user may never scroll to. Once, it is lifted to
        // the last cell of the first page instead (moving only the icon that was there); after
        // that it stays wherever the user puts it.
        if (!prefs.getBoolean(KEY_SCOUT_PROMOTED, false)) {
            prefs.edit().putBoolean(KEY_SCOUT_PROMOTED, true).apply()
            if ("scout" !in savedOrder && layout.folders.values.none { "scout" in it.apps }) {
                val order = layout.order.toMutableList()
                if (order.remove("scout")) {
                    order.add(minOf(CAPACITY - 1, order.size), "scout")
                    layout = layout.copy(order = order)
                    promoted = true
                }
            }
        }
        // «لنترن» the same way, once: it arrived at the end of the second page, where the user had to
        // go looking for it. Scout keeps its place on the first page.
        if (!prefs.getBoolean(KEY_LANTERN_PROMOTED, false)) {
            prefs.edit().putBoolean(KEY_LANTERN_PROMOTED, true).apply()
            if ("lantern" !in savedOrder) {
                BoardLayoutRules.promoteToFirstPage(layout, "lantern", CAPACITY, keep = setOf("scout"))?.let {
                    layout = it
                    promoted = true
                }
            }
        }
        // «URnetwork» and «NPV» the same way, each once this build has its tile at all: a release
        // lists one only when it works (HomeDestinations.IN_PROGRESS), and that is when it moves.
        for ((id, key) in NEW_ENGINES_PROMOTED) {
            if (prefs.getBoolean(key, false) || HomeDestinations.gridById(id) == null) continue
            prefs.edit().putBoolean(key, true).apply()
            if (id in savedOrder) continue
            BoardLayoutRules.promoteToFirstPage(layout, id, CAPACITY, keep = PROMOTED)?.let {
                layout = it
                promoted = true
            }
        }
        // «ویجت استدیو» the same way, once. With the connection widget on, the first page has the
        // widget's rows fewer cells, and the last of the remaining ones is where it goes.
        if (!prefs.getBoolean(KEY_WIDGET_STUDIO_PROMOTED, false)) {
            prefs.edit().putBoolean(KEY_WIDGET_STUDIO_PROMOTED, true).apply()
            if ("widgetstudio" !in savedOrder) {
                val firstPage = if (Appearance.homeWidget) CAPACITY - WIDGET_SLOTS else CAPACITY
                BoardLayoutRules.promoteToFirstPage(layout, "widgetstudio", firstPage, keep = PROMOTED)?.let {
                    layout = it
                    promoted = true
                }
            }
        }
        if (promoted) write(context, layout)
        return toItems(layout)
    }

    fun save(context: Context, items: List<BoardItem>) = write(context, toLayout(items))

    fun reset(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(KEY)
            .remove(KEY_FOLDERS)
            .remove(KEY_FOLDERS_MIGRATED)
            .apply()
    }

    fun toLayout(items: List<BoardItem>): BoardLayout {
        val folders = LinkedHashMap<String, FolderRecord>()
        items.forEach { item ->
            if (item is BoardItem.Folder) folders[item.key] = FolderRecord(item.title, item.apps.map { it.id })
        }
        return BoardLayout(items.map { it.id }, folders)
    }

    fun toItems(layout: BoardLayout): List<BoardItem> = layout.order.mapNotNull { id ->
        if (BoardLayoutRules.isFolderRef(id)) {
            val key = BoardLayoutRules.folderKey(id)
            val record = layout.folders[key] ?: return@mapNotNull null
            val apps = record.apps.mapNotNull { HomeDestinations.gridById(it) }
            if (apps.isEmpty()) null else BoardItem.Folder(key, record.title, apps)
        } else {
            HomeDestinations.gridById(id)?.let { BoardItem.App(it) }
        }
    }

    private fun write(context: Context, layout: BoardLayout) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY, layout.order.joinToString(","))
            .putString(KEY_FOLDERS, foldersJson(layout.folders))
            .putBoolean(KEY_FOLDERS_MIGRATED, true)
            .apply()
    }

    private fun foldersJson(folders: Map<String, FolderRecord>): String {
        val root = JSONObject()
        folders.forEach { (key, record) ->
            root.put(key, JSONObject().apply {
                record.title?.let { put("title", it) }
                put("apps", JSONArray(record.apps))
            })
        }
        return root.toString()
    }

    /** A damaged value is treated as "no folders saved" rather than taking the board down. */
    private fun parseFolders(raw: String): Map<String, FolderRecord>? = runCatching {
        val root = JSONObject(raw)
        val out = LinkedHashMap<String, FolderRecord>()
        root.keys().forEach { key ->
            val obj = root.optJSONObject(key) ?: return@forEach
            val arr = obj.optJSONArray("apps") ?: return@forEach
            val apps = (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            val title = if (obj.has("title") && !obj.isNull("title")) obj.optString("title") else null
            out[key] = FolderRecord(title, apps)
        }
        out
    }.getOrNull()
}
