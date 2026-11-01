package com.mlmvpn.scanner.backup

import android.content.Context
import com.mlmvpn.scanner.store.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export and import, between the screen and the items: which items go into a file, what a file
 * holds, and the plan a chosen set of them makes.
 *
 * The content inside [BackupFile]'s envelope:
 * ```
 * { "content": 1, "app_version": "1.2.42", "app_build": 78, "created_at": <ms>,
 *   "parts": ["MAIN", "ACCOUNTS", "METHOD"],
 *   "items": [ { "id", "kind", "v", "title", "tile"?, "data" } … ] }
 * ```
 * An item's `title` is kept so a newer file's unknown item can still be named in the preview.
 */
internal object BackupEngine {

    private const val CONTENT = 1

    class Exported(
        val bytes: ByteArray,
        val fileName: String,
        val included: List<String>,
        /** Items that could not be read, with why: left out, named on the screen. */
        val failed: List<Pair<String, String>>,
    )

    /** Builds a file from [items]. Off the main thread: the password's key derivation takes about a second. */
    fun export(ctx: Context, items: List<BackupContributor>, parts: Set<BackupKind>, password: String?): Exported {
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        val included = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (c in items) {
            val data = try {
                c.export(ctx)
            } catch (t: Throwable) {
                failed += c.title() to reason(t)
                null
            } ?: continue
            arr.put(
                JSONObject().put("id", c.id).put("kind", c.kind.name).put("v", c.version).put("title", c.title())
                    .apply { c.tileId?.let { put("tile", it) } }
                    .put("data", data)
            )
            included += c.title()
        }
        val content = JSONObject()
            .put("content", CONTENT)
            .put("app_version", com.mlmvpn.scanner.BuildConfig.VERSION_NAME)
            .put("app_build", com.mlmvpn.scanner.BuildConfig.VERSION_CODE.toLong())
            .put("created_at", now)
            .put("parts", JSONArray(parts.map { it.name }.sorted()))
            .put("items", arr)
        val bytes = BackupFile.write(
            content, password, com.mlmvpn.scanner.BuildConfig.VERSION_NAME, com.mlmvpn.scanner.BuildConfig.VERSION_CODE.toLong(), now,
        )
        return Exported(bytes, BackupNames.fileName(now, com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()), included, failed)
    }

    private fun reason(t: Throwable): String = when {
        t.message?.contains("cannot be opened") == true ->
            tr("رمزگذاری امن این گوشی آن را باز نکرد", "this phone's secure storage could not open it")
        t.message?.contains("too large") == true -> tr("بیش از اندازه بزرگ است", "too large")
        else -> tr("خوانده نشد", "could not be read")
    }

    // ── import ─────────────────────────────────────────────────────────────────────────────────

    class Preview(
        val createdAt: Long,
        val appVersion: String,
        /** Made by a newer build than this one: what this build does not know is left out. */
        val newerApp: Boolean,
        val encrypted: Boolean,
        val items: List<Item>,
    )

    class Item(
        val id: String,
        val kind: BackupKind?,
        val title: String,
        val tileId: String?,
        val dataVersion: Int,
        val data: JSONObject?,
        val contributor: BackupContributor?,
    ) {
        /** Null when it can be restored; otherwise why not, for the user. */
        val blocked: String?
            get() = when {
                contributor == null || kind == null || data == null ->
                    tr("این نسخهٔ برنامه این مورد را نمی‌شناسد", "This version of the app does not know this item")
                dataVersion > contributor.version ->
                    tr("برای این مورد نسخهٔ تازه‌تر برنامه لازم است", "This item needs a newer version of the app")
                else -> null
            }

        val summary: String? get() = if (blocked == null) runCatching { contributor!!.summary(data!!) }.getOrNull() else null
    }

    sealed class Opened {
        class Ok(val preview: Preview) : Opened()
        object NotBackup : Opened()
        object Corrupt : Opened()
        object NeedsPassword : Opened()
        object WrongPassword : Opened()
        class TooNew(val appVersion: String) : Opened()
    }

    /** Opens a file's bytes. Off the main thread when [password] is set: the key derivation is slow. */
    fun open(bytes: ByteArray, password: String?): Opened = when (val r = BackupFile.open(bytes, password)) {
        is BackupFile.Opened.Ok -> preview(r.header, r.content)
        BackupFile.Opened.NotBackup -> Opened.NotBackup
        BackupFile.Opened.Corrupt -> Opened.Corrupt
        BackupFile.Opened.NeedsPassword -> Opened.NeedsPassword
        BackupFile.Opened.WrongPassword -> Opened.WrongPassword
        is BackupFile.Opened.TooNew -> Opened.TooNew(r.header.appVersion)
    }

    private fun preview(header: BackupFile.Header, content: JSONObject): Opened {
        if (content.optInt("content", 0) > CONTENT) return Opened.TooNew(header.appVersion)
        val arr = content.optJSONArray("items") ?: return Opened.Corrupt
        val items = arr.objects().mapNotNull { o ->
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val c = BackupHooks.byId(id)
            Item(
                id = id,
                kind = runCatching { BackupKind.valueOf(o.optString("kind")) }.getOrNull() ?: c?.kind,
                title = c?.title() ?: o.optString("title").ifBlank { id },
                tileId = c?.tileId ?: o.optString("tile").ifBlank { null },
                dataVersion = o.optInt("v", 1),
                data = o.optJSONObject("data"),
                contributor = c,
            )
        }
        return Opened.Ok(
            Preview(
                createdAt = content.optLong("created_at", header.createdAt),
                appVersion = content.optString("app_version", header.appVersion),
                newerApp = header.appBuild > com.mlmvpn.scanner.BuildConfig.VERSION_CODE,
                encrypted = header.encrypted,
                items = items,
            )
        )
    }

    class PlanError(val title: String, cause: Throwable) : Exception(cause.message, cause)

    /**
     * Every write the chosen [items] make, in the registry's order (items editing one file build on
     * each other in that order). The first item that cannot be restored stops it all: [PlanError].
     */
    fun plan(ctx: Context, items: List<Item>, mode: RestoreMode): RestorePlan {
        val order = BackupHooks.contributors.withIndex().associate { (i, c) -> c.id to i }
        val plan = RestorePlan()
        for (item in items.filter { it.blocked == null }.sortedBy { order[it.id] ?: Int.MAX_VALUE }) {
            try {
                item.contributor!!.plan(ctx, item.data!!, item.dataVersion, mode, plan)
            } catch (t: Throwable) {
                throw PlanError(item.title, t)
            }
        }
        return plan
    }
}
