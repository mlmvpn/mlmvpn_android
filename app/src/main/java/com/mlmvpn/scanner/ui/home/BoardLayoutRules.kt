package com.mlmvpn.scanner.ui.home

/**
 * A folder as it is stored: its name and its members' destination ids, in order.
 *
 * [title] is null while the folder still carries its factory name, so switching the app's language
 * renames it too. Only a name the user typed is stored as text.
 */
data class FolderRecord(val title: String?, val apps: List<String>)

/**
 * The home board as ids: [order] holds destination ids and `@key` references to [folders].
 *
 * Plain strings on purpose -- the rules below are the part that has to be right on every upgrade,
 * and keeping them free of Android and Compose types is what lets a JVM test pin them down.
 */
data class BoardLayout(val order: List<String>, val folders: Map<String, FolderRecord>)

/**
 * How a saved arrangement is brought up to date with the destinations this release has.
 *
 * The same contract [HomeLayoutStore] always had -- nothing a user arranged is thrown away, ids that
 * no longer exist leave no gap, and a feature shipped today appears at the end of their board
 * instead of resetting it -- extended to folders, the way the Windows app keeps them (`apps.js ›
 * DEFAULT_FOLDERS`, `mv-folders`): a destination lives in exactly one place, an empty folder is no
 * folder, and a new destination that belongs to a factory folder the user still has goes into it.
 */
object BoardLayoutRules {

    const val FOLDER_PREFIX = "@"

    /** Keys the store accepts: short, ASCII, and never able to collide with a destination id. */
    private val KEY_RE = Regex("^[a-z0-9_-]{1,40}$", RegexOption.IGNORE_CASE)

    fun isFolderRef(id: String): Boolean = id.startsWith(FOLDER_PREFIX)
    fun folderRef(key: String): String = FOLDER_PREFIX + key
    fun folderKey(ref: String): String = ref.removePrefix(FOLDER_PREFIX)

    /**
     * @param savedOrder the stored top level, or null when the user never arranged anything.
     * @param savedFolders the stored folders, or null when none were ever written -- which is
     *   different from an empty map: null on an old layout is what triggers the one-time move of
     *   the factory folders' members off the board and into their folder.
     * @param migrated whether that one-time move already ran for this install.
     * @param known every destination this release has, in factory order.
     * @param boardDefault a fresh board's top level, with `@key` references.
     * @param defaultFolders the folders a fresh board starts with.
     */
    fun reconcile(
        savedOrder: List<String>?,
        savedFolders: Map<String, FolderRecord>?,
        migrated: Boolean,
        known: List<String>,
        boardDefault: List<String>,
        defaultFolders: Map<String, List<String>>,
    ): BoardLayout {
        val knownSet = known.toSet()
        val order: MutableList<String>
        val folders = LinkedHashMap<String, FolderRecord>()

        if (savedOrder == null) {
            order = boardDefault.toMutableList()
            defaultFolders.forEach { (key, apps) -> folders[key] = FolderRecord(null, apps) }
        } else {
            order = savedOrder.toMutableList()
            savedFolders?.let { folders.putAll(it) }
            if (!migrated && savedFolders == null) {
                // A board arranged before folders existed. Its tools move into their folder once,
                // and the folder takes the place of the first of them -- where the user already
                // looked for that group.
                defaultFolders.forEach { (key, apps) ->
                    if (folders.containsKey(key)) return@forEach
                    val at = order.indexOfFirst { it in apps }
                    order.removeAll { it in apps }
                    val ref = folderRef(key)
                    if (ref !in order) order.add(if (at < 0) order.size else minOf(at, order.size), ref)
                    folders[key] = FolderRecord(null, apps)
                }
            }
        }

        // One place per destination, only destinations that still exist, and no empty folders.
        val claimed = HashSet<String>()
        val cleanFolders = LinkedHashMap<String, FolderRecord>()
        folders.forEach { (key, record) ->
            if (!KEY_RE.matches(key)) return@forEach
            val apps = record.apps.filter { it in knownSet && claimed.add(it) }
            if (apps.isNotEmpty()) cleanFolders[key] = record.copy(apps = apps)
        }

        val top = ArrayList<String>()
        val seenTop = HashSet<String>()
        for (id in order) {
            val keep = if (isFolderRef(id)) {
                cleanFolders.containsKey(folderKey(id))
            } else {
                id in knownSet && id !in claimed
            }
            if (keep && seenTop.add(id)) top.add(id)
        }
        // A folder the order lost track of is still the user's: it goes at the end, not away.
        cleanFolders.keys.forEach { key ->
            val ref = folderRef(key)
            if (seenTop.add(ref)) top.add(ref)
        }

        // Destinations this arrangement has never seen: into their factory folder when the user
        // still has it, otherwise onto the end of the board.
        for (id in known) {
            if (id in claimed || id in seenTop) continue
            val home = defaultFolders.entries
                .firstOrNull { (key, apps) -> id in apps && cleanFolders.containsKey(key) }
                ?.key
            if (home != null) {
                val record = cleanFolders.getValue(home)
                cleanFolders[home] = record.copy(apps = record.apps + id)
                claimed.add(id)
            } else {
                top.add(id)
                seenTop.add(id)
            }
        }

        return BoardLayout(top, cleanFolders)
    }

    /**
     * Brings [id] -- a destination a release added, which the reconciliation appended at the end of
     * an arranged board, a page the user may never open -- to the last cell of the first page: the
     * icon that was in that cell moves to the first cell of the second page, and nothing else moves.
     * What is in [keep] stays where it is (MLM Scout, lifted to that same cell once before): the
     * cell before it is used instead.
     *
     * Null when there is nothing to do: [id] is not on the board itself (a folder holds it, or it is
     * gone), or it is on the first page already.
     */
    fun promoteToFirstPage(layout: BoardLayout, id: String, perPage: Int, keep: Set<String> = emptySet()): BoardLayout? {
        val at = layout.order.indexOf(id)
        if (perPage < 1 || at < perPage) return null
        val order = layout.order.toMutableList()
        order.removeAt(at)
        // [at] was past the first page, so the first page is still full without it.
        var cell = perPage - 1
        while (cell > 0 && order[cell] in keep) cell--
        val displaced = order[cell]
        order[cell] = id
        order.add(perPage, displaced)
        return layout.copy(order = order)
    }

    /**
     * Drops [draggedId] onto [targetId]: into the target folder, or, onto a plain destination, into
     * a new folder called [newKey] that takes the target's place. A folder is never put inside a
     * folder -- the board has one level, as on iOS and on the Windows desktop.
     *
     * Returns null when the drop does not make sense (unknown ids, a folder being dropped).
     */
    fun merge(layout: BoardLayout, draggedId: String, targetId: String, newKey: String): BoardLayout? {
        if (draggedId == targetId || isFolderRef(draggedId)) return null
        if (draggedId !in layout.order || targetId !in layout.order) return null
        val folders = LinkedHashMap(layout.folders)
        val order = layout.order.toMutableList()
        if (isFolderRef(targetId)) {
            val key = folderKey(targetId)
            val record = folders[key] ?: return null
            folders[key] = record.copy(apps = record.apps + draggedId)
            order.remove(draggedId)
        } else {
            if (!KEY_RE.matches(newKey) || folders.containsKey(newKey)) return null
            val at = order.indexOf(targetId)
            order[at] = folderRef(newKey)
            order.remove(draggedId)
            folders[newKey] = FolderRecord(null, listOf(targetId, draggedId))
        }
        return BoardLayout(order, folders)
    }

    /**
     * Takes [appId] out of folder [key] and puts it on the board right after the folder. A folder
     * left with nothing in it disappears, and the destination takes the place it had.
     */
    fun moveOut(layout: BoardLayout, key: String, appId: String): BoardLayout? {
        val record = layout.folders[key] ?: return null
        if (appId !in record.apps) return null
        val folders = LinkedHashMap(layout.folders)
        val order = layout.order.toMutableList()
        val ref = folderRef(key)
        val at = order.indexOf(ref).let { if (it < 0) order.size - 1 else it }
        val rest = record.apps - appId
        if (rest.isEmpty()) {
            folders.remove(key)
            if (at in order.indices && order[at] == ref) order[at] = appId else order.add(appId)
        } else {
            folders[key] = record.copy(apps = rest)
            order.add(minOf(at + 1, order.size), appId)
        }
        return BoardLayout(order, folders)
    }

    /** Renames folder [key]; a blank name, or the factory one, goes back to following the language. */
    fun rename(layout: BoardLayout, key: String, title: String?, factoryTitle: String): BoardLayout? {
        val record = layout.folders[key] ?: return null
        val clean = title?.trim()?.take(40)?.takeIf { it.isNotEmpty() && it != factoryTitle }
        return layout.copy(folders = LinkedHashMap(layout.folders).apply { put(key, record.copy(title = clean)) })
    }

    /** Puts folder [key]'s members in the order given; anything not a member is ignored. */
    fun reorderInside(layout: BoardLayout, key: String, apps: List<String>): BoardLayout? {
        val record = layout.folders[key] ?: return null
        val kept = apps.filter { it in record.apps }.distinct()
        val rest = record.apps.filter { it !in kept }
        return layout.copy(
            folders = LinkedHashMap(layout.folders).apply { put(key, record.copy(apps = kept + rest)) },
        )
    }

    // ------------------------------------------------------------------ drag and drop
    //
    // A drag is three steps, the way the board shows it: [lift] takes the item out of wherever it
    // is, the board then shows the rest with a gap that follows the finger, and one of the drops
    // below puts it back down. Every index a drop takes is an index into what was on screen while
    // the item was in the air -- the lifted layout, [settle]d -- so the gap the user saw is exactly
    // where the item lands, wherever it came from.

    /** The folder [appId] is in, or null when it is on the board itself (or nowhere). */
    fun folderOf(layout: BoardLayout, appId: String): String? =
        layout.folders.entries.firstOrNull { appId in it.value.apps }?.key

    /**
     * The board with [id] picked up: an app out of the board or out of its folder, a folder off the
     * board. A folder left empty is KEPT here -- it may be the one open on screen, and the app may
     * go straight back into it; [settle] is what removes it. Null when [id] is nowhere.
     */
    fun lift(layout: BoardLayout, id: String): BoardLayout? {
        if (id in layout.order) return layout.copy(order = layout.order.filterNot { it == id })
        if (isFolderRef(id)) return null
        val key = folderOf(layout, id) ?: return null
        val record = layout.folders.getValue(key)
        return layout.copy(
            folders = LinkedHashMap(layout.folders).apply { put(key, record.copy(apps = record.apps.filterNot { it == id })) },
        )
    }

    /** Drops the folders that hold nothing, and their place on the board with them. */
    fun settle(layout: BoardLayout): BoardLayout {
        val empty = layout.folders.filterValues { it.apps.isEmpty() }.keys
        if (empty.isEmpty()) return layout
        return BoardLayout(
            layout.order.filterNot { isFolderRef(it) && folderKey(it) in empty },
            LinkedHashMap(layout.folders).apply { empty.forEach { remove(it) } },
        )
    }

    /** Puts a lifted app or folder on the board, at [index] of the settled board. */
    fun dropOnBoard(lifted: BoardLayout, id: String, index: Int): BoardLayout? {
        val s = settle(lifted)
        if (id in s.order || folderOf(s, id) != null) return null
        if (isFolderRef(id) && !s.folders.containsKey(folderKey(id))) return null
        val order = s.order.toMutableList()
        order.add(index.coerceIn(0, order.size), id)
        return s.copy(order = order)
    }

    /** Puts a lifted app into folder [key], at [index] of its apps -- at the end when null. */
    fun dropIntoFolder(lifted: BoardLayout, appId: String, key: String, index: Int? = null): BoardLayout? {
        if (isFolderRef(appId)) return null
        val record = lifted.folders[key] ?: return null
        if (appId in lifted.order || folderOf(lifted, appId) != null) return null
        val apps = record.apps.toMutableList()
        apps.add((index ?: apps.size).coerceIn(0, apps.size), appId)
        return settle(lifted.copy(folders = LinkedHashMap(lifted.folders).apply { put(key, record.copy(apps = apps)) }))
    }

    /**
     * Drops a lifted app onto app [targetId] on the board: a new folder [newKey], holding the target
     * and then the app, takes the target's place -- the way iOS makes one.
     */
    fun dropOnApp(lifted: BoardLayout, appId: String, targetId: String, newKey: String): BoardLayout? {
        if (isFolderRef(appId) || isFolderRef(targetId) || appId == targetId) return null
        if (!KEY_RE.matches(newKey) || lifted.folders.containsKey(newKey)) return null
        if (appId in lifted.order || folderOf(lifted, appId) != null) return null
        val at = lifted.order.indexOf(targetId)
        if (at < 0) return null
        val order = lifted.order.toMutableList()
        order[at] = folderRef(newKey)
        val folders = LinkedHashMap(lifted.folders).apply { put(newKey, FolderRecord(null, listOf(targetId, appId))) }
        return settle(BoardLayout(order, folders))
    }

    /** Takes folder [key] apart: its apps go back onto the board, in their order, where it was. */
    fun ungroup(layout: BoardLayout, key: String): BoardLayout? {
        val record = layout.folders[key] ?: return null
        val order = layout.order.toMutableList()
        val at = order.indexOf(folderRef(key))
        if (at >= 0) {
            order.removeAt(at)
            order.addAll(at, record.apps)
        } else {
            order.addAll(record.apps)
        }
        return BoardLayout(order, LinkedHashMap(layout.folders).apply { remove(key) })
    }
}
