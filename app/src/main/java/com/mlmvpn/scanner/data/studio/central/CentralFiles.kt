package com.mlmvpn.scanner.data.studio.central

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * This phone's copy of each account's Studio record: one file per account in `files/studio_central/`.
 * A file existing IS the account being part of the Studio on the central Worker; there is no second
 * list to keep in step with it.
 *
 * Written whole, through a temporary file and a rename, so a phone that dies half-way leaves the
 * previous copy rather than half of a new one. Read on every call — the files are small and a cached
 * copy is how two screens end up disagreeing about who exists.
 */
object CentralFiles {

    private const val TAG = "CentralFiles"
    const val DIR = "studio_central"

    private fun dir(context: Context) = File(context.applicationContext.filesDir, DIR)

    /** A file name for an account id: its letters and digits, nothing that can climb a directory. */
    fun fileName(accountId: String): String =
        accountId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifEmpty { "_" } + ".json"

    private fun fileOf(context: Context, accountId: String) = File(dir(context), fileName(accountId))

    private val lock = Any()

    /** Which accounts have a file, kept in memory: the fleet list asks on every screen. Null = not read yet. */
    @Volatile private var ids: List<String>? = null

    /** The account ids this phone runs the Studio on — no file is opened after the first call. */
    fun accountIds(context: Context): List<String> = ids ?: synchronized(lock) {
        ids ?: all(context).map { it.accountId }.also { ids = it }
    }

    fun read(context: Context, accountId: String): CentralDoc? = synchronized(lock) {
        val f = fileOf(context, accountId)
        if (!f.isFile) return null
        runCatching { CentralDoc.fromLocal(JSONObject(f.readText(Charsets.UTF_8))) }
            .onFailure { Log.w(TAG, "unreadable ${f.name}: ${it.message}") }
            .getOrNull()
            // A file that names another account is not this account's record.
            ?.takeIf { it.accountId == accountId }
    }

    fun write(context: Context, doc: CentralDoc): Unit = synchronized(lock) {
        val d = dir(context)
        if (!d.isDirectory && !d.mkdirs()) error("cannot make ${d.path}")
        val f = fileOf(context, doc.accountId)
        val tmp = File(d, f.name + ".tmp")
        tmp.writeText(doc.toLocal().toString(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            // Some filesystems will not rename over an existing file.
            f.delete()
            if (!tmp.renameTo(f)) error("cannot replace ${f.name}")
        }
        ids?.let { if (doc.accountId !in it) ids = it + doc.accountId }
    }

    /** Every account this phone runs the Studio on, by its file. */
    fun all(context: Context): List<CentralDoc> = synchronized(lock) {
        val d = dir(context)
        if (!d.isDirectory) return emptyList()
        d.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            runCatching { CentralDoc.fromLocal(JSONObject(f.readText(Charsets.UTF_8))) }
                .onFailure { Log.w(TAG, "unreadable ${f.name}: ${it.message}") }.getOrNull()
                ?.takeIf { fileName(it.accountId) == f.name }
        }
    }

    /** Stop running the Studio on this account from this phone. Nothing on Cloudflare is touched. */
    fun delete(context: Context, accountId: String): Boolean = synchronized(lock) {
        val gone = fileOf(context, accountId).delete()
        ids = ids?.filter { it != accountId }
        gone
    }

    /** Forget what is held in memory (a restore replaced the files underneath). */
    fun invalidate() { ids = null }

    /** The [CentralLocal] seam over one account's file. */
    fun local(context: Context, accountId: String): CentralLocal = object : CentralLocal {
        override fun get(): CentralDoc? = read(context, accountId)
        override fun put(doc: CentralDoc) = write(context, doc)
    }
}
