package com.mlmvpn.scanner.widget.studio

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Where «ویجت استدیو» keeps its designs, the widgets placed from them and the method widgets' look:
 * one small JSON file, `files/widgets/studio.json`.
 *
 * Held in memory once read and written back whole on every change -- it is a few kilobytes even
 * with dozens of designs. The write goes to a temporary file that is then renamed over the old one,
 * so a process killed mid-write leaves the previous version, never half a file.
 *
 * Everything runs in the app's main process: the widget providers, the studio's screens and the
 * pin callback all live there, so one in-memory copy is the truth for all of them.
 */
object StudioStore {

    private const val DIR = "widgets"
    private const val FILE = "studio.json"

    /** How long a pin request waits for the launcher to place its widget. */
    private const val PENDING_MS = 3 * 60_000L

    private val lock = Any()
    @Volatile private var loaded = false
    private val _data = MutableStateFlow(StudioData())
    val data: StateFlow<StudioData> = _data

    /** A design waiting for the widget the launcher is about to place for it. Memory only. */
    @Volatile private var pending: Pair<String, Long>? = null

    fun get(context: Context): StudioData {
        ensure(context)
        return _data.value
    }

    private fun ensure(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            _data.value = read(context) ?: StudioData()
            loaded = true
        }
    }

    private fun file(context: Context) = File(File(context.applicationContext.filesDir, DIR), FILE)

    private fun read(context: Context): StudioData? = runCatching {
        val f = file(context)
        if (!f.isFile) return@runCatching null
        StudioData.fromJson(JSONObject(f.readText()))
    }.getOrNull()

    private fun write(context: Context, next: StudioData) {
        val f = file(context)
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(next.toJson().toString())
            if (!tmp.renameTo(f)) {
                // Some file systems refuse a rename over an existing file.
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private inline fun update(context: Context, change: (StudioData) -> StudioData): StudioData {
        ensure(context)
        synchronized(lock) {
            val next = change(_data.value)
            if (next != _data.value) {
                _data.value = next
                write(context, next)
            }
            return next
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Designs
    // ---------------------------------------------------------------------------------------------

    fun newId(): String = "w" + UUID.randomUUID().toString().replace("-", "").take(12)

    /** Adds [template], or replaces the one with its id. */
    fun save(context: Context, template: WidgetTemplate) {
        val now = System.currentTimeMillis()
        val stamped = template.copy(
            name = template.name.trim().take(40),
            created = if (template.created > 0) template.created else now,
            updated = now,
        )
        update(context) { d ->
            val i = d.templates.indexOfFirst { it.id == stamped.id }
            val list = d.templates.toMutableList()
            if (i >= 0) list[i] = stamped else list.add(0, stamped)
            d.copy(templates = list)
        }
    }

    /** A copy of [id] under a new id and «(کپی)» name; the copy, or null when there is no such design. */
    fun duplicate(context: Context, id: String, copySuffix: String): WidgetTemplate? {
        val src = get(context).template(id) ?: return null
        val copy = src.copy(id = newId(), name = (src.name + " " + copySuffix).take(40), created = 0, updated = 0)
        save(context, copy)
        return get(context).template(copy.id)
    }

    /**
     * Removes the design. Widgets still wearing it keep their binding and show «قالب حذف شده»
     * until they are given another one or removed, rather than silently becoming something else.
     */
    fun delete(context: Context, id: String) {
        update(context) { d -> d.copy(templates = d.templates.filterNot { it.id == id }) }
    }

    // ---------------------------------------------------------------------------------------------
    // Placed widgets
    // ---------------------------------------------------------------------------------------------

    fun templateFor(context: Context, appWidgetId: Int): WidgetTemplate? {
        val d = get(context)
        return d.template(d.bindings[appWidgetId])
    }

    fun isBound(context: Context, appWidgetId: Int): Boolean = get(context).bindings.containsKey(appWidgetId)

    fun bind(context: Context, appWidgetId: Int, templateId: String) {
        update(context) { d -> d.copy(bindings = d.bindings + (appWidgetId to templateId)) }
    }

    /** The launcher removed these widgets. */
    fun unbind(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        update(context) { d -> d.copy(bindings = d.bindings - appWidgetIds.toSet()) }
    }

    /** How many placed widgets wear [templateId]. */
    fun placedCount(context: Context, templateId: String): Int = get(context).bindings.values.count { it == templateId }

    /** A pin request went out for [templateId]: the next new widget of ours is meant to wear it. */
    fun setPending(templateId: String) {
        pending = templateId to SystemClock.elapsedRealtime()
    }

    /** The design waiting for a newly placed widget, consumed; null when none, or when it went stale. */
    fun takePending(): String? {
        val p = pending ?: return null
        pending = null
        return p.first.takeIf { SystemClock.elapsedRealtime() - p.second <= PENDING_MS }
    }

    /** Whether a pin request is waiting, without consuming it. */
    fun hasPending(): Boolean = pending?.let { SystemClock.elapsedRealtime() - it.second <= PENDING_MS } == true

    // ---------------------------------------------------------------------------------------------
    // Method widgets
    // ---------------------------------------------------------------------------------------------

    fun setMethodStyle(context: Context, style: MethodStyle) {
        update(context) { d -> d.copy(methodStyle = style) }
    }
}
