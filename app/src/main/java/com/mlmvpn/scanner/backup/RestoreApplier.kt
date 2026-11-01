package com.mlmvpn.scanner.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** How [RestoreApplier] reaches SharedPreferences: Android's in the app, a map in the tests. */
internal interface PrefsIo {
    fun exists(name: String): Boolean
    fun read(name: String): Map<String, Any?>

    /** Applies [clear] first, then [removes], then [puts]; throws when the write did not land. */
    fun write(name: String, clear: Boolean, puts: Map<String, Any>, removes: Set<String>)
    fun delete(name: String)
}

/**
 * The disk side of a restore, in three steps that survive the process dying between any two of them.
 *
 * 1. [stage]: the plan's writes go to `pending/` (one JSON of operations, one blob per file) and the
 *    journal says STAGED. Nothing the app reads has changed yet.
 * 2. [resume] at the next launch, before anything has loaded: a raw copy of every prefs file and
 *    path the plan touches goes to `rollback/`; only when that copy is complete does the journal say
 *    APPLYING; then the writes are made.
 * 3. The journal says DONE, with the result, and the two folders go.
 *
 * Failing writes put the copy back. A launch that finds APPLYING -- the phone died or the app was
 * killed half-way -- puts the copy back too: half a restore is worse than none. A launch that finds
 * STAGED simply starts again from step 2, since nothing was written yet.
 *
 * Paths are `files/…`, `no_backup/…` and `databases/…`, resolved through [roots]. A file's AtomicFile
 * leftovers (`.bak`, `.new`) are part of it: AtomicFile prefers a `.bak` over the file itself, so an
 * old one left beside a restored file would quietly bring the old contents back.
 *
 * Pure JVM, so the whole cycle -- including a failure half-way -- runs in unit tests.
 */
internal class RestoreApplier(
    private val prefs: PrefsIo,
    private val roots: Map<String, File>,
    work: File,
) {
    enum class Phase { STAGED, APPLYING, DONE }
    enum class Result { OK, ROLLED_BACK, FAILED }

    data class Journal(
        val phase: Phase,
        val result: Result?,
        val at: Long,
        val titles: List<String>,
        val error: String?,
    )

    private val pending = File(work, "pending")
    private val rollback = File(work, "rollback")
    private val journalFile = File(work, "journal.json")

    /** Test hook: called before each operation is applied, by index. */
    internal var beforeOp: (Int) -> Unit = {}

    /** Test stand-in for the process dying: thrown from [beforeOp], it is not caught here. */
    internal class ProcessDeath : Error()

    private sealed class Staged {
        class Prefs(val name: String, val clear: Boolean, val puts: Map<String, Any>, val removes: Set<String>) : Staged()
        class Put(val path: String, val blob: File) : Staged()
        class Del(val path: String) : Staged()

        val target: String
            get() = when (this) {
                is Prefs -> name
                is Put -> path
                is Del -> path
            }
    }

    fun journal(): Journal? = runCatching {
        if (!journalFile.isFile) return null
        val o = JSONObject(journalFile.readText())
        val titles = o.optJSONArray("titles")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        Journal(
            phase = Phase.valueOf(o.getString("phase")),
            result = o.optString("result").takeIf { it.isNotBlank() }?.let { Result.valueOf(it) },
            at = o.optLong("at"),
            titles = titles,
            error = o.optString("error").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /** Puts [ops] on disk for the next launch, replacing anything staged or left over before. */
    fun stage(ops: List<RestorePlan.Op>, titles: List<String>, now: Long) {
        pending.deleteRecursively()
        rollback.deleteRecursively()
        check(pending.mkdirs() || pending.isDirectory) { "cannot create ${pending.path}" }
        val arr = JSONArray()
        var n = 0
        for (op in ops) {
            when (op) {
                is RestorePlan.Op.Prefs -> arr.put(
                    JSONObject().put("t", "prefs").put("name", op.name).put("clear", op.clear)
                        .put("puts", PrefCodec.encodeMap(op.puts)).put("removes", JSONArray(op.removes.toList()))
                )
                is RestorePlan.Op.PutFile -> {
                    val blob = "b${n++}"
                    writeSynced(File(pending, blob), op.bytes)
                    arr.put(JSONObject().put("t", "put").put("path", op.path).put("blob", blob))
                }
                is RestorePlan.Op.Delete -> arr.put(JSONObject().put("t", "del").put("path", op.path))
            }
        }
        writeAtomic(File(pending, "ops.json"), JSONObject().put("v", 1).put("ops", arr).toString().toByteArray(Charsets.UTF_8))
        writeJournal(Journal(Phase.STAGED, null, now, titles, null))
    }

    /** Drops a staged restore that has not started yet. False when one is already under way. */
    fun unstage(): Boolean {
        val j = journal()
        if (j != null && j.phase == Phase.APPLYING) return false
        if (j != null && j.phase == Phase.STAGED) journalFile.delete()
        pending.deleteRecursively()
        return true
    }

    /**
     * At launch: finishes what a previous launch left. Null when there was nothing to do; otherwise
     * the journal as it now stands (phase DONE).
     */
    fun resume(now: Long): Journal? {
        if (!journalFile.exists()) return null
        val j = journal()
        if (j == null) {
            // An unreadable journal (it is written by rename, so this is not expected). If a copy to
            // roll back to exists, a restore may have been under way: put it back.
            val unknown = Journal(Phase.APPLYING, null, now, emptyList(), null)
            return if (File(rollback, "snapshot.json").isFile) rollBack(unknown, "journal unreadable") else {
                pending.deleteRecursively()
                finish(unknown, Result.FAILED, "journal unreadable")
            }
        }
        return when (j.phase) {
            Phase.DONE -> null
            Phase.APPLYING -> rollBack(j, "interrupted")
            Phase.STAGED -> apply(j)
        }
    }

    private fun apply(j: Journal): Journal {
        val ops = try {
            readOps()
        } catch (e: Exception) {
            return finish(j, Result.FAILED, "staged restore unreadable: ${e.message}")
        }
        try {
            snapshot(ops)
        } catch (e: Exception) {
            // Nothing has been written yet: only the partial copy goes.
            return finish(j, Result.FAILED, "copy before restore failed: ${e.message}")
        }
        writeJournal(j.copy(phase = Phase.APPLYING))
        try {
            ops.forEachIndexed { i, op ->
                beforeOp(i)
                applyOp(op)
            }
        } catch (e: ProcessDeath) {
            throw e
        } catch (e: Throwable) {
            return rollBack(j, "${e.javaClass.simpleName}: ${e.message}")
        }
        return finish(j, Result.OK, null)
    }

    private fun rollBack(j: Journal, why: String): Journal {
        val back = runCatching { restoreSnapshot() }
        return finish(
            j,
            if (back.isSuccess) Result.ROLLED_BACK else Result.FAILED,
            why + (back.exceptionOrNull()?.let { "; putting the copy back failed: ${it.message}" } ?: ""),
        )
    }

    private fun finish(j: Journal, result: Result, error: String?): Journal {
        val done = j.copy(phase = Phase.DONE, result = result, error = error?.take(500))
        writeJournal(done)
        pending.deleteRecursively()
        rollback.deleteRecursively()
        return done
    }

    // ── reading what was staged ─────────────────────────────────────────────────────────────────

    private fun readOps(): List<Staged> {
        val root = JSONObject(File(pending, "ops.json").readText())
        check(root.optInt("v") == 1) { "unknown staged version" }
        val arr = root.getJSONArray("ops")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            when (o.getString("t")) {
                "prefs" -> {
                    val removes = o.optJSONArray("removes")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
                    Staged.Prefs(o.getString("name"), o.optBoolean("clear"), PrefCodec.decodeMap(o.optJSONObject("puts")), removes)
                }
                "put" -> {
                    val path = o.getString("path").also(RestorePlan::checkPath)
                    val blob = File(pending, o.getString("blob"))
                    check(blob.isFile) { "missing blob for $path" }
                    Staged.Put(path, blob)
                }
                "del" -> Staged.Del(o.getString("path").also(RestorePlan::checkPath))
                else -> error("unknown staged operation")
            }
        }
    }

    // ── the copy to roll back to ────────────────────────────────────────────────────────────────

    private fun snapshot(ops: List<Staged>) {
        rollback.deleteRecursively()
        check(rollback.mkdirs() || rollback.isDirectory) { "cannot create ${rollback.path}" }
        val prefNames = LinkedHashSet<String>()
        val paths = LinkedHashSet<String>()
        for (op in ops) if (op is Staged.Prefs) prefNames += op.name else paths += op.target

        val snapPrefs = JSONArray()
        for (name in prefNames) {
            snapPrefs.put(
                JSONObject().put("name", name).put("existed", prefs.exists(name)).put("values", PrefCodec.encodeMap(prefs.read(name)))
            )
        }
        var n = 0
        val snapFiles = JSONArray()
        for (p in paths) {
            for (q in listOf(p, "$p.bak", "$p.new")) {
                val f = resolve(q)
                when {
                    f.isFile -> {
                        val blob = "r${n++}"
                        copySynced(f, File(rollback, blob))
                        snapFiles.put(JSONObject().put("path", q).put("kind", "file").put("blob", blob))
                    }
                    f.isDirectory -> {
                        val files = JSONArray()
                        f.walkTopDown().filter { it.isFile }.forEach { g ->
                            val blob = "r${n++}"
                            copySynced(g, File(rollback, blob))
                            files.put(JSONObject().put("rel", g.relativeTo(f).invariantSeparatorsPath).put("blob", blob))
                        }
                        snapFiles.put(JSONObject().put("path", q).put("kind", "dir").put("files", files))
                    }
                    else -> snapFiles.put(JSONObject().put("path", q).put("kind", "absent"))
                }
            }
        }
        writeAtomic(
            File(rollback, "snapshot.json"),
            JSONObject().put("prefs", snapPrefs).put("files", snapFiles).toString().toByteArray(Charsets.UTF_8),
        )
    }

    private fun restoreSnapshot() {
        val snap = JSONObject(File(rollback, "snapshot.json").readText())
        val files = snap.getJSONArray("files")
        // Every entry is attempted; the first failure is reported after the rest have been tried.
        var first: Throwable? = null
        for (i in 0 until files.length()) {
            runCatching {
                val o = files.getJSONObject(i)
                val f = resolve(o.getString("path"))
                when (o.getString("kind")) {
                    "file" -> {
                        if (f.isDirectory) f.deleteRecursively()
                        replaceWith(f, File(rollback, o.getString("blob")))
                    }
                    "dir" -> {
                        if (f.exists()) check(f.deleteRecursively()) { "cannot clear ${f.path}" }
                        val list = o.getJSONArray("files")
                        for (k in 0 until list.length()) {
                            val e = list.getJSONObject(k)
                            val rel = e.getString("rel")
                            check(rel.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "bad path in copy" }
                            replaceWith(File(f, rel), File(rollback, e.getString("blob")))
                        }
                        f.mkdirs()
                    }
                    else -> {
                        if (f.exists()) check(f.deleteRecursively()) { "cannot remove ${f.path}" }
                    }
                }
                Unit
            }.onFailure { if (first == null) first = it }
        }
        val list = snap.getJSONArray("prefs")
        for (i in 0 until list.length()) {
            runCatching {
                val o = list.getJSONObject(i)
                val name = o.getString("name")
                // A file the restore created is simply removed: writing it empty first could fail for
                // the same reason the restore did.
                if (o.optBoolean("existed")) prefs.write(name, true, PrefCodec.decodeMap(o.optJSONObject("values")), emptySet())
                else prefs.delete(name)
            }.onFailure { if (first == null) first = it }
        }
        first?.let { throw it }
    }

    // ── writing ─────────────────────────────────────────────────────────────────────────────────

    private fun applyOp(op: Staged) {
        when (op) {
            is Staged.Prefs -> prefs.write(op.name, op.clear, op.puts, op.removes)
            is Staged.Put -> {
                val f = resolve(op.path)
                if (f.isDirectory) check(f.deleteRecursively()) { "cannot replace folder ${f.path}" }
                replaceWith(f, op.blob)
                dropAtomicLeftovers(f)
            }
            is Staged.Del -> {
                val f = resolve(op.path)
                if (f.exists()) check(f.deleteRecursively()) { "cannot delete ${f.path}" }
                dropAtomicLeftovers(f)
            }
        }
    }

    private fun dropAtomicLeftovers(f: File) {
        for (s in listOf(".bak", ".new")) {
            val x = File(f.path + s)
            if (x.exists()) check(x.deleteRecursively()) { "cannot delete ${x.path}" }
        }
    }

    /** [target] gets [source]'s bytes through a synced temp file and a rename: never half-written. */
    private fun replaceWith(target: File, source: File) {
        val parent = target.parentFile ?: error("no parent for ${target.path}")
        check(parent.mkdirs() || parent.isDirectory) { "cannot create ${parent.path}" }
        val tmp = File(parent, target.name + ".restore-tmp")
        copySynced(source, tmp)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "cannot write ${target.path}" }
        }
    }

    fun resolve(path: String): File {
        RestorePlan.checkPath(path)
        val base = roots[path.substringBefore('/')] ?: error("no root for $path")
        val f = File(base, path.substringAfter('/'))
        val baseCanon = base.canonicalPath
        check(f.canonicalPath.startsWith(baseCanon + File.separator)) { "path leaves the app: $path" }
        return f
    }

    private fun writeJournal(j: Journal) {
        journalFile.parentFile?.mkdirs()
        val o = JSONObject().put("phase", j.phase.name).put("at", j.at).put("titles", JSONArray(j.titles))
        j.result?.let { o.put("result", it.name) }
        j.error?.let { o.put("error", it) }
        writeAtomic(journalFile, o.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        writeSynced(tmp, bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "cannot write ${target.path}" }
        }
    }

    private fun writeSynced(f: File, bytes: ByteArray) {
        FileOutputStream(f).use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }
        }
    }

    private fun copySynced(from: File, to: File) {
        to.parentFile?.mkdirs()
        from.inputStream().use { input ->
            FileOutputStream(to).use { out ->
                input.copyTo(out, 64 * 1024)
                out.flush()
                runCatching { out.fd.sync() }
            }
        }
    }
}
