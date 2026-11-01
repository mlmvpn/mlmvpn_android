package com.mlmvpn.scanner.backup

import android.content.Context
import org.json.JSONObject

/**
 * «پشتیبان‌گیری و بازگردانی»: the contract every part of the app signs to be saved to a backup file
 * and brought back from one.
 *
 * ## Writing one
 *
 * 1. Implement [BackupContributor] (an `object` is fine), next to the feature it backs up.
 * 2. Add ONE line to [BackupHooks.contributors]. Nothing else in the backup code changes.
 *
 * ## How a restore happens -- and why [plan] writes nothing
 *
 * A restore is never written while the app is running. Every item computes its writes into a
 * [RestorePlan]; the plans are put on disk, the VPN is stopped, the app's processes are ended and
 * the app is started again. The writes are applied at the very start of the next launch
 * ([BackupStage.onAppStart]), before any singleton has read its prefs, any store has loaded its
 * JSON and any database has been opened -- so nothing in memory can write an old copy back over
 * the restored one. A raw copy of everything the plans touch is taken first, and any failure
 * (including the phone dying half-way) puts every byte back.
 *
 * So [plan] reads what the phone has now (for [RestoreMode.MERGE]) and describes the result. It
 * runs on the same phone as the launch that applies it, which is why sealing a secret with this
 * phone's Keystore inside [plan] (SecureStore, the OpenVPN vault) gives ciphertext that opens after
 * the restart.
 *
 * ## The rules
 *
 * - [id] is a key inside every backup file ever written: never rename one. A new shape of [export]'s
 *   JSON that an older reader would misread gets a higher [version]; an item written with a version
 *   newer than this build's is shown as "needs a newer app" and never reaches [plan].
 * - [export] returns PLAIN data: secrets decrypted. The whole file is encrypted with the user's
 *   password when they set one, and the screen warns when they do not.
 * - Leave out what is per network (learned endpoints, measurements), per device (install ids, crash
 *   state) or rebuilt by itself (server lists, caches): see [BackupKind] for what each kind means.
 * - Unknown keys in [plan]'s data are ignored, never an error: a newer app may have added some.
 * - Throw from [plan] when the data cannot be used. Nothing at all is restored then, and the user is
 *   told which item it was.
 */
interface BackupContributor {
    /** The item's key inside backup files: `[a-z0-9_]+`, stable forever. */
    val id: String

    /** Which of the three exports the item belongs to. */
    val kind: BackupKind

    /** For [BackupKind.METHOD]: the home tile it belongs to (an id of `HomeDestinations`), for its icon and order. */
    val tileId: String? get() = null

    /** The shape of [export]'s JSON. Raise it when an older [plan] would misread the new shape. */
    val version: Int get() = 1

    /** The item's name in the lists, in the user's language (`tr(fa, en)`). */
    fun title(): String

    /**
     * The item as it is now, in plain JSON -- or null when there is nothing to save, so an empty item
     * never shows in the file. Called off the main thread. An exception leaves the item out of the
     * file and names it on the screen; the rest of the export goes on.
     */
    fun export(ctx: Context): JSONObject?

    /** One short line for the restore preview ("۳ حساب"), or null. */
    fun summary(data: JSONObject): String? = null

    /**
     * Adds to [plan] every write that restoring [data] needs. Writes nothing itself; see the class
     * comment. [dataVersion] is the [version] the file was written with, never above this build's.
     */
    fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan)
}

/** The three exports the user can take. */
enum class BackupKind {
    /** «تنظیمات اصلی»: app-wide settings -- language, look, home board, network, sharing. */
    MAIN,

    /** «حساب‌ها و کانفیگ‌ها»: everything the user signed in to, made, added or was given. */
    ACCOUNTS,

    /** «تنظیمات روش‌ها»: one item per tile, that tile's own choices. */
    METHOD,
}

/** How an item meets what the phone already has. */
enum class RestoreMode {
    /**
     * «ادغام»: the file's entries are added; one the phone has too is brought up to date. By default
     * the file's copy replaces it, but the item decides: one whose entries carry their own time may
     * keep the newer of the two, so an old backup cannot bring back what was since deleted or turned
     * off (Config Studio's users).
     */
    MERGE,

    /** «جایگزینی»: the item's current data goes, and only the file's copy is left. */
    REPLACE,
}

/**
 * The writes one restore will make, collected from every chosen item and applied together at the
 * next launch (see [BackupContributor]).
 *
 * Paths are relative to the app's data directory and must start with `files/`, `no_backup/` or
 * `databases/`. A file is always replaced whole; a delete takes a file or a whole directory.
 */
class RestorePlan {

    internal sealed class Op {
        data class Prefs(
            val name: String,
            val clear: Boolean,
            val puts: Map<String, Any>,
            val removes: Set<String>,
        ) : Op()

        class PutFile(val path: String, val bytes: ByteArray) : Op()
        data class Delete(val path: String) : Op()
    }

    internal val ops = mutableListOf<Op>()

    /**
     * Edits one SharedPreferences file. [clear] empties it first: only for a file that belongs to the
     * item alone. A file several items share is replaced key by key instead ([PrefsChange.remove]).
     */
    fun prefs(name: String, clear: Boolean = false, edit: PrefsChange.() -> Unit) {
        require(PREFS_NAME.matches(name)) { "bad prefs name: $name" }
        val change = PrefsChange().apply(edit)
        if (!clear && change.puts.isEmpty() && change.removes.isEmpty()) return
        ops += Op.Prefs(name, clear, LinkedHashMap(change.puts), LinkedHashSet(change.removes))
    }

    fun putFile(path: String, bytes: ByteArray) {
        checkPath(path)
        ops += Op.PutFile(path, bytes)
    }

    fun deleteFile(path: String) {
        checkPath(path)
        ops += Op.Delete(path)
    }

    /**
     * What this plan already writes to [path] -- an earlier item's [putFile] -- or null. Two items that
     * edit parts of one file (an engine's state.json holding both the user's configs and the tile's
     * choices) must build on each other's result rather than on the disk: otherwise the second
     * file written replaces the first one's work.
     */
    fun planned(path: String): ByteArray? =
        ops.lastOrNull { (it is Op.PutFile && it.path == path) || (it is Op.Delete && it.path == path) }
            ?.let { if (it is Op.PutFile) it.bytes else ByteArray(0) }

    /** Nothing to write. */
    val isEmpty: Boolean get() = ops.isEmpty()

    companion object {
        private val PREFS_NAME = Regex("[A-Za-z0-9_.\\-]{1,120}")
        private val ROOTS = listOf("files/", "no_backup/", "databases/")

        /** Relative, inside one of the three roots, no `..`, no empty or odd segments. */
        fun checkPath(path: String) {
            require(ROOTS.any { path.startsWith(it) } && path.length > path.indexOf('/') + 1) { "path outside the app: $path" }
            val parts = path.split('/')
            require(parts.none { it.isEmpty() || it == "." || it == ".." || it.contains('\\') || it.contains('\u0000') }) {
                "bad path: $path"
            }
        }
    }
}

/** One prefs file's edit: values to set and keys to remove. */
class PrefsChange internal constructor() {
    internal val puts = LinkedHashMap<String, Any>()
    internal val removes = LinkedHashSet<String>()

    /** [value]: String, Boolean, Int, Long, Float or Set<String> -- what SharedPreferences holds. */
    fun put(key: String, value: Any) {
        require(value is String || value is Boolean || value is Int || value is Long || value is Float || value is Set<*>) {
            "unsupported prefs value for $key: ${value.javaClass.simpleName}"
        }
        if (value is Set<*>) require(value.all { it is String }) { "set of non-strings for $key" }
        if (value is Float) require(value.isFinite()) { "non-finite float for $key" }
        removes.remove(key)
        puts[key] = value
    }

    fun remove(key: String) {
        puts.remove(key)
        removes += key
    }
}
