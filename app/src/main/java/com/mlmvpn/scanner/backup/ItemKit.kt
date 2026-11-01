package com.mlmvpn.scanner.backup

import android.content.Context
import com.mlmvpn.scanner.store.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The building blocks most items are made of: a slice of a prefs file, a set of files, and the
 * generic item that backs up a few of each. An item with secrets or a list to merge writes its own
 * [BackupContributor] and uses these helpers for the rest.
 */

/** The app's default SharedPreferences, by a name that does not depend on the package name. */
internal const val DEFAULT_PREFS = "@default"

internal fun prefsName(ctx: Context, file: String): String =
    if (file == DEFAULT_PREFS) ctx.packageName + "_preferences" else file

internal fun prefsOf(ctx: Context, file: String) =
    ctx.getSharedPreferences(prefsName(ctx, file), Context.MODE_PRIVATE)

/**
 * The keys of one prefs file that belong to an item: listed [keys], any key starting with one of
 * [prefixes], or every key ([whole]) -- minus [except] and [exceptPrefixes].
 */
internal class PrefsSlice(
    val file: String,
    val keys: Set<String> = emptySet(),
    val prefixes: List<String> = emptyList(),
    val whole: Boolean = false,
    val except: Set<String> = emptySet(),
    val exceptPrefixes: List<String> = emptyList(),
) {
    fun owns(key: String): Boolean =
        (whole || key in keys || prefixes.any { key.startsWith(it) }) &&
            key !in except && exceptPrefixes.none { key.startsWith(it) }
}

/** Typed values of [slices] as `{file: {key: typed}}`, or null when there are none. */
internal fun exportSlices(ctx: Context, slices: List<PrefsSlice>): JSONObject? {
    val out = JSONObject()
    var n = 0
    for ((file, group) in slices.groupBy { it.file }) {
        val values = prefsOf(ctx, file).all.filterKeys { k -> group.any { it.owns(k) } }
        if (values.isEmpty()) continue
        out.put(file, PrefCodec.encodeMap(values))
        n += values.size
    }
    return if (n == 0) null else out
}

/**
 * The writes that bring [slices] to [data] (as [exportSlices] wrote it). MERGE sets the file's values;
 * REPLACE also removes the item's keys the file does not have. A key the phone holds with another
 * type than the file's is left alone: reading it back as the wrong type would crash the screen
 * that asks for it, and a type only changes when the app changed what the key means.
 */
internal fun planSlices(ctx: Context, slices: List<PrefsSlice>, data: JSONObject?, mode: RestoreMode, plan: RestorePlan) {
    for ((file, group) in slices.groupBy { it.file }) {
        val owns = { k: String -> group.any { it.owns(k) } }
        val values = PrefCodec.decodeMap(data?.optJSONObject(file)).filterKeys(owns)
        val current = prefsOf(ctx, file).all
        plan.prefs(prefsName(ctx, file)) {
            if (mode == RestoreMode.REPLACE) {
                for (k in current.keys) if (owns(k) && k !in values) remove(k)
            }
            for ((k, v) in values) {
                val now = current[k]
                if (now != null && !sameType(now, v)) continue
                // A set merges as a set: the phone's ids, packages or addresses stay, the file's join them.
                if (mode == RestoreMode.MERGE && now is Set<*> && v is Set<*>) {
                    put(k, (now.filterIsInstance<String>() + v.filterIsInstance<String>()).toSet())
                } else {
                    put(k, v)
                }
            }
        }
    }
}

private fun sameType(a: Any, b: Any): Boolean = when (a) {
    is String -> b is String
    is Boolean -> b is Boolean
    is Int -> b is Int
    is Long -> b is Long
    is Float -> b is Float
    is Set<*> -> b is Set<*>
    else -> false
}

/** How many values a [exportSlices] object holds. */
internal fun countSlices(data: JSONObject?): Int {
    if (data == null) return 0
    var n = 0
    for (k in data.keys()) n += data.optJSONObject(k)?.length() ?: 0
    return n
}

// ── files ──────────────────────────────────────────────────────────────────────────────────────

/** A single file of the app's, by its [RestorePlan] path. */
internal fun dataFile(ctx: Context, path: String): File = BackupStage.applier(ctx).resolve(path)

/** A file's text as this restore will leave it so far: an earlier item's write, else the disk; null when absent. */
internal fun currentText(ctx: Context, plan: RestorePlan, path: String): String? {
    plan.planned(path)?.let { return if (it.isEmpty()) null else String(it, Charsets.UTF_8) }
    val f = dataFile(ctx, path)
    return if (f.isFile) f.readText() else null
}

/** `{path: base64}` of the files among [paths] that exist (each at most [maxBytes]). */
internal fun exportFiles(ctx: Context, paths: List<String>, maxBytes: Long = 8L * 1024 * 1024): JSONObject? {
    val out = JSONObject()
    for (p in paths) {
        val f = dataFile(ctx, p)
        if (!f.isFile || f.length() > maxBytes) continue
        out.put(p, B64.encode(f.readBytes()))
    }
    return if (out.length() == 0) null else out
}

/** Writes the files of [data] that are among [paths]; REPLACE also deletes the [paths] it lacks. */
internal fun planFiles(ctx: Context, paths: List<String>, data: JSONObject?, mode: RestoreMode, plan: RestorePlan) {
    for (p in paths) {
        val b64 = data?.optString(p, "").orEmpty()
        if (b64.isNotEmpty()) plan.putFile(p, B64.decode(b64))
        else if (mode == RestoreMode.REPLACE && dataFile(ctx, p).exists()) plan.deleteFile(p)
    }
}

// ── the generic item ───────────────────────────────────────────────────────────────────────────

/** An item that is nothing but prefs keys and whole files. */
internal open class SimpleItem(
    override val id: String,
    override val kind: BackupKind,
    private val fa: String,
    private val en: String,
    private val slices: List<PrefsSlice> = emptyList(),
    private val files: List<String> = emptyList(),
    override val tileId: String? = null,
) : BackupContributor {

    override fun title(): String = tr(fa, en)

    override fun export(ctx: Context): JSONObject? {
        val prefs = exportSlices(ctx, slices)
        val f = exportFiles(ctx, files)
        if (prefs == null && f == null) return null
        return JSONObject().apply {
            prefs?.let { put("prefs", it) }
            f?.let { put("files", it) }
        }
    }

    override fun summary(data: JSONObject): String? {
        val n = countSlices(data.optJSONObject("prefs")) + (data.optJSONObject("files")?.length() ?: 0)
        return if (n == 0) null else countLabel(n, "مورد", "items")
    }

    override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
        if (slices.isNotEmpty()) planSlices(ctx, slices, data.optJSONObject("prefs"), mode, plan)
        if (files.isNotEmpty()) planFiles(ctx, files, data.optJSONObject("files"), mode, plan)
    }
}

// ── small helpers ──────────────────────────────────────────────────────────────────────────────

/** "۳ حساب" / "3 accounts". */
internal fun countLabel(n: Int, fa: String, en: String): String =
    tr(BackupNames.persianDigits(n.toString()) + " " + fa, "$n $en")

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }

/** A JSON value's copy (org.json has no clone). */
internal fun JSONObject.copy(): JSONObject = JSONObject(toString())

/**
 * Merges two lists of JSON objects by [key]: everything in [current] keeps its place, an entry the
 * backup has too is replaced by the backup's copy, and the backup's new entries follow.
 */
internal fun mergeById(current: List<JSONObject>, backup: List<JSONObject>, key: (JSONObject) -> String?): List<JSONObject> {
    val fromBackup = LinkedHashMap<String, JSONObject>()
    val loose = mutableListOf<JSONObject>()
    for (o in backup) {
        val k = key(o)
        if (k.isNullOrEmpty()) loose += o else fromBackup[k] = o
    }
    val out = mutableListOf<JSONObject>()
    val seen = HashSet<String>()
    for (o in current) {
        val k = key(o)
        if (!k.isNullOrEmpty() && fromBackup.containsKey(k)) {
            out += fromBackup.getValue(k)
            seen += k
        } else {
            out += o
        }
    }
    for ((k, o) in fromBackup) if (k !in seen) out += o
    out += loose
    return out
}
