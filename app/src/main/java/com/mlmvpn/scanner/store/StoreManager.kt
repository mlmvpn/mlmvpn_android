package com.mlmvpn.scanner.store

import android.content.Context
import android.os.Build
import android.util.Log
import com.mlmvpn.scanner.data.studio.StudioDeployer
import com.mlmvpn.scanner.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The store's one entry point: what the screen reads, and every action it can take.
 *
 * Opening the store never waits on the network. The last check is kept on disk and drawn at once;
 * a check runs in the background and replaces it when it lands. Each long action is a [StoreJob]
 * the screen can watch, keyed by item, so two items can be updated side by side and a screen that
 * is closed and reopened still sees the progress of what it started.
 */
object StoreManager {

    private const val TAG = "StoreManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _rows = MutableStateFlow<List<StoreRow>>(emptyList())
    val rows: StateFlow<List<StoreRow>> = _rows.asStateFlow()

    private val _jobs = MutableStateFlow<Map<String, StoreJob>>(emptyMap())
    val jobs: StateFlow<Map<String, StoreJob>> = _jobs.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _lastCheck = MutableStateFlow(0L)
    val lastCheck: StateFlow<Long> = _lastCheck.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val checkMutex = Mutex()
    private val running = HashMap<String, Job>()

    private fun root(context: Context) = File(context.filesDir, "store")
    private fun cacheFile(context: Context) = File(root(context), "check.json")

    // ── the picture ────────────────────────────────────────────────────────────────────────────

    /** Draw from what is on disk. No network. */
    fun load(context: Context) {
        val app = context.applicationContext
        scope.launch { rebuild(app) }
    }

    private fun readCache(context: Context): JSONObject =
        runCatching { JSONObject(cacheFile(context).readText()) }.getOrElse { JSONObject() }

    private fun writeCache(context: Context, j: JSONObject) {
        runCatching { cacheFile(context).apply { parentFile?.mkdirs() }.writeText(j.toString()) }
    }

    private fun candidateToJson(c: StoreCandidate) = JSONObject().apply {
        put("version", c.version); put("notes", c.notes); put("released", c.released); put("from", c.from)
        put("url", c.url ?: "")
        put("artifacts", JSONArray().apply {
            c.artifacts.forEach { a ->
                put(JSONObject().apply {
                    put("name", a.name); put("urls", JSONArray(a.urls)); put("sha256", a.sha256 ?: "")
                    put("size", a.size); put("format", a.format); put("extract", JSONObject(a.extract))
                })
            }
        })
    }

    private fun candidateFromJson(o: JSONObject?): StoreCandidate? {
        o ?: return null
        val arts = o.optJSONArray("artifacts") ?: JSONArray()
        return StoreCandidate(
            version = o.optString("version"), notes = o.optString("notes"), released = o.optString("released"),
            from = o.optString("from"), url = o.optString("url").ifBlank { null },
            artifacts = (0 until arts.length()).map { i ->
                val a = arts.getJSONObject(i)
                val urls = a.optJSONArray("urls") ?: JSONArray()
                val ex = a.optJSONObject("extract") ?: JSONObject()
                StoreArtifact(
                    name = a.optString("name"),
                    urls = (0 until urls.length()).map { urls.getString(it) },
                    sha256 = a.optString("sha256").ifBlank { null },
                    size = a.optLong("size"), format = a.optString("format", "raw"),
                    extract = ex.keys().asSequence().associateWith { ex.getString(it) },
                )
            },
        )
    }

    private suspend fun rebuild(context: Context) {
        val cache = readCache(context)
        _lastCheck.value = cache.optLong("at")
        val out = StoreCatalog.ALL.map { item ->
            runCatching { rowFor(context, item, cache) }.getOrElse { e ->
                Log.w(TAG, "row ${item.id}: ${e.message}")
                StoreRow(item, null, null, false, 0, false, null, StoreRow.State.UNKNOWN, e.message)
            }
        }
        _rows.value = out
    }

    /**
     * The version of an engine built into the app. Xray says it itself (the library's
     * `checkVersionX`), read once per app build; the other two are fixed by the build files.
     */
    private fun withAppVersion(context: Context, item: StoreItem): String? = when (item.id) {
        "xray" -> {
            val prefs = context.getSharedPreferences("store_prefs", Context.MODE_PRIVATE)
            val code = runCatching {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            }.getOrDefault(0L)
            val key = "withapp_xray_$code"
            prefs.getString(key, null) ?: try {
                val raw = libv2ray.Libv2ray.checkVersionX()
                val v = Regex("Xray[- ]?core\\s+v?(\\d+\\.\\d+\\.\\d+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
                    ?: Regex("v?(\\d+\\.\\d+\\.\\d+)").findAll(raw).lastOrNull()?.groupValues?.get(1)
                v?.also { prefs.edit().putString(key, it).apply() }
            } catch (t: Throwable) {
                null
            }
        }
        // app/libs/psiphontunnel-2.0.39-ns.aar and the amneziawg-android dependency in build.gradle.
        "psiphon" -> "2.0.39"
        "amneziawg" -> "2.1.13"
        else -> null
    }

    /** The code the app would deploy for a worker right now: the store's copy, else the shipped one. */
    private fun workerTarget(context: Context, item: StoreItem): String? =
        runCatching { StoreFiles.readText(context, item.worker!!.asset) }.getOrNull()

    private fun workerVersionOf(item: StoreItem, code: String): String? =
        runCatching { item.worker!!.versionOf(code) }.getOrNull()

    private fun rowFor(context: Context, item: StoreItem, cache: JSONObject): StoreRow {
        val inst = StoreFiles.installed(context, item.id)
        val c = cache.optJSONObject("items")?.optJSONObject(item.id)
        val candidate = candidateFromJson(c?.optJSONObject("candidate"))
        val error = c?.optString("error")?.ifBlank { null }
        val checkedAt = cache.optLong("at")

        return when (item.kind) {
            StoreKind.APP -> appRow(context, item)
            StoreKind.ENGINE -> {
                if (item.source is StoreSource.WithApp) {
                    StoreRow(item, withAppVersion(context, item), null, false, 0, false, null, StoreRow.State.WITH_APP,
                        checkedAt = checkedAt, upstream = c?.optString("upstream")?.ifBlank { null })
                } else {
                    val shipped = StoreEngines.shippedVersion(context, item)
                    val active = inst?.takeIf { it.quarantined == null }
                    val inUse = active?.version ?: shipped
                    StoreRow(
                        item = item, version = inUse, shipped = shipped, fromStore = active != null,
                        installedAt = inst?.at ?: 0, canRollback = inst != null, candidate = candidate,
                        state = stateOf(item, candidate, inUse, active != null, checkedAt),
                        error = inst?.quarantined?.let { tr("نسخهٔ استور کنار گذاشته شد: ", "The store's copy was set aside: ") + it } ?: error,
                        checkedAt = checkedAt,
                    )
                }
            }
            StoreKind.DATA -> {
                val inUse = inst?.version ?: item.data?.shipped
                StoreRow(
                    item = item, version = inUse, shipped = null, fromStore = inst != null,
                    installedAt = inst?.at ?: 0, canRollback = inst != null, candidate = candidate,
                    state = stateOf(item, candidate, inUse, inst != null, checkedAt),
                    error = error, checkedAt = checkedAt,
                )
            }
            StoreKind.WORKER -> {
                val code = workerTarget(context, item)
                val codeVersion = code?.let { workerVersionOf(item, it) } ?: inst?.version
                val codeHash = code?.let { item.worker!!.hashOf(it) }
                val deps = (c?.optJSONArray("deployments") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { d ->
                        val v = d.optString("version").ifBlank { null }
                        val hash = d.optString("hash")
                        val behind = when {
                            // Numbers on both sides: behind means lower.
                            v != null && codeVersion != null && StoreVersions.compare(v, codeVersion) != null ->
                                StoreVersions.compare(v, codeVersion) == -1
                            // No version in the code at all: the bytes decide. BPB never lands
                            // here — its version is always in its code.
                            v == null && codeHash != null && hash.isNotBlank() -> hash != codeHash
                            else -> false
                        }
                        StoreRow.Deployment(
                            accountId = d.optString("accountId"), accountName = d.optString("accountName"),
                            script = d.optString("script"), mainModule = d.optString("mainModule", "worker.js"),
                            version = v, behind = behind,
                        )
                    }
                }
                StoreRow(
                    item = item, version = codeVersion, shipped = null, fromStore = inst != null,
                    installedAt = inst?.at ?: 0, canRollback = inst != null, candidate = candidate,
                    state = stateOf(item, candidate, codeVersion, inst != null, checkedAt),
                    error = error, checkedAt = checkedAt, deployments = deps,
                )
            }
        }
    }

    private fun stateOf(item: StoreItem, candidate: StoreCandidate?, inUse: String?, fromStore: Boolean, checkedAt: Long): StoreRow.State {
        if (candidate == null) {
            return when {
                checkedAt == 0L -> StoreRow.State.UNCHECKED
                inUse == null && item.kind == StoreKind.ENGINE -> StoreRow.State.UNKNOWN
                else -> StoreRow.State.CURRENT
            }
        }
        if (inUse == null) {
            // Nothing to compare with — the shipped copy carries no number. The channel offers an
            // entry only to the apps it is meant for, and a developer's data file is always newer
            // than one frozen into the APK months ago, so the offer stands until installed once.
            return StoreRow.State.UPDATE
        }
        return if (isNewer(item, candidate.version, inUse)) StoreRow.State.UPDATE else StoreRow.State.CURRENT
    }

    /**
     * Newer, with one exception: Loyalsoldier publishes routing data every day, and a badge that
     * never goes away teaches people to ignore the store. Data dated by its tag counts as newer
     * only once it is a week ahead of what is installed.
     */
    private fun isNewer(item: StoreItem, candidate: String, inUse: String): Boolean {
        val dated = Regex("""^(\d{4})(\d{2})(\d{2})\d{4}$""")
        val a = dated.find(candidate)
        val b = dated.find(inUse)
        if (item.kind == StoreKind.DATA && a != null && b != null) {
            fun day(m: MatchResult) = java.util.Calendar.getInstance().apply {
                set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
            }.timeInMillis / 86_400_000L
            return day(a) - day(b) >= 7
        }
        return StoreVersions.newer(candidate, inUse)
    }

    private fun appRow(context: Context, item: StoreItem): StoreRow {
        val current = StoreChannel.appVersion(context)
        return when (val s = UpdateChecker.state.value) {
            is UpdateChecker.State.Available -> StoreRow(
                item, current, current, false, 0, false,
                StoreCandidate(s.info.versionName, s.info.changelog.joinToString("\n"), "", "developer",
                    listOf(StoreArtifact("apk", listOf(s.info.apkUrl), null, s.info.apkSizeBytes, "raw"))),
                StoreRow.State.UPDATE, checkedAt = s.checkedAt,
            )
            is UpdateChecker.State.UpToDate -> StoreRow(item, current, current, false, 0, false, null, StoreRow.State.CURRENT, checkedAt = s.checkedAt)
            is UpdateChecker.State.Failed -> StoreRow(item, current, current, false, 0, false, null, StoreRow.State.UNKNOWN, s.reason)
            else -> StoreRow(item, current, current, false, 0, false, null,
                if (UpdateChecker.lastCheckedAt(context) > 0) StoreRow.State.CURRENT else StoreRow.State.UNCHECKED)
        }
    }

    // ── checking ───────────────────────────────────────────────────────────────────────────────

    /** Ask every developer and the channel what is new, and survey the user's accounts. */
    fun checkAll(context: Context, force: Boolean = true) {
        val app = context.applicationContext
        scope.launch {
            if (!checkMutex.tryLock()) return@launch
            _checking.value = true
            try {
                runCatching { UpdateChecker.checkForUpdate(app, force = force) }
                StoreChannel.refresh(app, force = force)
                val prev = readCache(app).optJSONObject("items") ?: JSONObject()
                val items = JSONObject()
                for (item in StoreCatalog.ALL) {
                    if (item.kind == StoreKind.APP) continue
                    if (item.source is StoreSource.WithApp) {
                        // Not installable from here, but its developer's newest version is shown.
                        val repo = item.repo ?: continue
                        val o = JSONObject()
                        runCatching { StoreNet.newestTag(app, repo) }
                            .onSuccess { t -> t?.let { o.put("upstream", it.removePrefix("v")) } }
                            .onFailure { prev.optJSONObject(item.id)?.optString("upstream")?.ifBlank { null }?.let { o.put("upstream", it) } }
                        items.put(item.id, o)
                        continue
                    }
                    val o = JSONObject()
                    try {
                        resolve(app, item, prev.optJSONObject(item.id))?.let { o.put("candidate", candidateToJson(it)) }
                    } catch (e: Exception) {
                        o.put("error", e.message ?: e.javaClass.simpleName)
                        // A failed look is not "nothing new": keep what the last good look found.
                        prev.optJSONObject(item.id)?.optJSONObject("candidate")?.let { o.put("candidate", it) }
                    }
                    prev.optJSONObject(item.id)?.optJSONArray("deployments")?.let { o.put("deployments", it) }
                    items.put(item.id, o)
                }
                runCatching { survey(app, items) }.onFailure { Log.w(TAG, "survey: ${it.message}") }
                writeCache(app, JSONObject().put("at", System.currentTimeMillis()).put("build", buildStamp(app)).put("items", items))
                rebuild(app)
            } finally {
                _checking.value = false
                checkMutex.unlock()
            }
        }
    }

    /**
     * Check twice a day on its own when the store is opened -- and at once after the app itself
     * was updated: a check made by the previous build looked items up where THAT build's catalog
     * said (the Iran configs used to come from the channel only), so its answers say nothing
     * about this one. Seen 2026-10-04: upstream had v52, the store kept showing 50.
     */
    fun checkIfStale(context: Context) {
        val cache = readCache(context)
        val at = cache.optLong("at")
        val otherBuild = cache.optLong("build") != buildStamp(context)
        if (otherBuild || System.currentTimeMillis() - at > 12 * 3600 * 1000L) checkAll(context, force = false)
    }

    /** When this build of the app was installed: changes with every update, debug reinstalls included. */
    private fun buildStamp(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    /** The newest installable version of one item: the developer's or the channel's. */
    private fun resolve(context: Context, item: StoreItem, prev: JSONObject?): StoreCandidate? {
        val fromChannel = StoreChannel.candidate(context, item.id)
        val fromDev: StoreCandidate? = when (val s = item.source) {
            is StoreSource.GithubRelease -> resolveRelease(context, item, s)
            is StoreSource.GithubFile -> resolveFile(context, item, s, prev)
            is StoreSource.GithubFiles -> resolveFiles(context, s, prev)
            is StoreSource.SignedManifest -> resolveManifest(context, s)
            else -> null
        }
        // Newest wins; on a tie the channel's copy, because at equal versions tested beats untested.
        return when {
            fromDev == null -> fromChannel
            fromChannel == null -> fromDev
            StoreVersions.newer(fromDev.version, fromChannel.version) -> fromDev
            else -> fromChannel
        }
    }

    private fun resolveRelease(context: Context, item: StoreItem, s: StoreSource.GithubRelease): StoreCandidate? {
        val rel = StoreNet.latestRelease(context, s.repo)
        val names = Build.SUPPORTED_ABIS.mapNotNull { s.assetFor(it) }.distinct()
        val asset = names.firstNotNullOfOrNull { n -> rel.assets.firstOrNull { it.name == n } }
            ?: throw StoreError(tr("سازنده برای این گوشی فایلی منتشر نکرده است.", "The developer published nothing for this phone."))
        // GitHub's own digest, or the developer's `.sha256` beside the file.
        val sha = asset.sha256 ?: rel.assets.firstOrNull { it.name == asset.name + ".sha256" || it.name == asset.name + ".sha256sum" }
            ?.let { side -> runCatching { StoreNet.getText(context, side.url, 4096).trim().split(Regex("""\s+""")).first().lowercase() }.getOrNull() }
            ?.takeIf { it.length == 64 }
        // An executable with nothing to check it against is not something this store runs.
        if (sha == null && item.kind == StoreKind.ENGINE) {
            throw StoreError(tr("این انتشار هش ندارد؛ بدون آن نصب نمی‌شود.", "This release carries no digest; it will not be installed without one."))
        }
        return StoreCandidate(
            version = s.tagToVersion(rel.tag),
            notes = rel.body.trim(),
            released = rel.publishedAt,
            from = "developer",
            url = rel.url,
            artifacts = listOf(StoreArtifact(asset.name, listOf(asset.url), sha, asset.size, s.format, s.extract)),
        )
    }

    /**
     * The developer's signed manifest: verified before a word of it is believed, then the build it
     * names, with the digest it states. Each mirror in turn -- the same order the developer's own
     * client uses -- and the download may come from any of them, since the digest is fixed.
     */
    private fun resolveManifest(context: Context, s: StoreSource.SignedManifest): StoreCandidate? {
        var last: Exception? = null
        for (base in s.mirrors) {
            try {
                val yaml = StoreNet.getText(context, "$base/metadata.yaml", 1024 * 1024)
                val sig = StoreNet.getText(context, "$base/metadata.yaml.minisig", 4096)
                if (!Minisign.verify(yaml.toByteArray(Charsets.UTF_8), sig, s.minisignKey)) {
                    throw StoreError(tr("امضای فهرست نسخه‌های سازنده درست نبود؛ چیزی نصب نشد.",
                        "The developer's release list did not verify; nothing was installed."))
                }
                val entry = SignedManifestYaml.entry(yaml, s.track)
                    ?: throw StoreError(tr("سازنده نسخه‌ای برای اندروید منتشر نکرده است.", "The developer lists no Android build."))
                val extract = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { s.extract(it) }
                    ?: throw StoreError(tr("سازنده برای این گوشی فایلی منتشر نکرده است.", "The developer published nothing for this phone."))
                val path = "${s.track}/${entry.version}/${entry.filename}"
                return StoreCandidate(
                    version = entry.version,
                    notes = "",
                    released = "",
                    from = "developer",
                    url = "$base/$path",
                    artifacts = listOf(
                        StoreArtifact(entry.filename, s.mirrors.map { "$it/$path" }, entry.sha256, 0L, "zip", extract),
                    ),
                )
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: StoreError(tr("هیچ‌کدام از آینه‌های سازنده جواب نداد.", "None of the developer's mirrors answered."))
    }

    /**
     * A file on a branch. The commit is read first and the file is fetched only when the commit
     * moved since the last look — edgetunnel's worker is 300 KB, and asking for it on every check
     * would cost more than everything else together.
     */
    private fun resolveFile(context: Context, item: StoreItem, s: StoreSource.GithubFile, prev: JSONObject?): StoreCandidate? {
        val commit = StoreNet.latestCommit(context, s.repo, s.branch, s.path)
        val url = "https://raw.githubusercontent.com/${s.repo}/${commit.sha}/${s.path}"
        val old = candidateFromJson(prev?.optJSONObject("candidate"))
        if (old != null && old.artifacts.firstOrNull()?.urls?.firstOrNull() == url) return old
        val text = StoreNet.getText(context, url, 8 * 1024 * 1024)
        val version = s.versionInContent?.find(text)?.groupValues?.get(1)
            ?: "${commit.date} (${commit.sha.take(7)})"
        val bytes = text.toByteArray(Charsets.UTF_8)
        return StoreCandidate(
            version = version,
            notes = commit.message,
            released = commit.date,
            from = "developer",
            url = commit.url,
            artifacts = listOf(StoreArtifact(s.path.substringAfterLast('/'), listOf(url), StoreNet.sha256(text), bytes.size.toLong(), "raw")),
        )
    }

    /**
     * Several files of one repo at ONE commit: the newest that touched any of them, so the set is
     * always a set upstream actually published together. Fetched only when that commit moved.
     * raw.githubusercontent.com first, jsDelivr (same commit, same bytes) when it is filtered.
     */
    private fun resolveFiles(context: Context, s: StoreSource.GithubFiles, prev: JSONObject?): StoreCandidate? {
        val commit = s.paths.map { StoreNet.latestCommit(context, s.repo, s.branch, it) }
            .maxWith(compareBy({ it.date }, { it.sha }))
        fun urls(path: String) = listOf(
            "https://raw.githubusercontent.com/${s.repo}/${commit.sha}/$path",
            "https://cdn.jsdelivr.net/gh/${s.repo}@${commit.sha}/$path",
        )
        val old = candidateFromJson(prev?.optJSONObject("candidate"))
        if (old != null && old.artifacts.firstOrNull()?.urls?.firstOrNull() == urls(s.paths.first()).first()) return old
        val texts = s.paths.map { path ->
            var last: Exception? = null
            urls(path).firstNotNullOfOrNull { u -> runCatching { StoreNet.getText(context, u, 2 * 1024 * 1024) }.onFailure { last = it as? Exception }.getOrNull() }
                ?: throw last ?: StoreError("no URL")
        }
        val version = s.versionInContent?.find(texts.first())?.groupValues?.get(1)
            ?: "${commit.date} (${commit.sha.take(7)})"
        return StoreCandidate(
            version = version,
            notes = commit.message,
            released = commit.date,
            from = "developer",
            url = commit.url,
            artifacts = s.paths.mapIndexed { i, path ->
                // Saved under its path in the repo: that is the name DataSpec.build asks for.
                StoreArtifact(path.substringAfterLast('/'), urls(path), StoreNet.sha256(texts[i]),
                    texts[i].toByteArray(Charsets.UTF_8).size.toLong(), "raw", mapOf(path to path))
            },
        )
    }

    /** Every recognised worker on every connected Cloudflare account. */
    private fun survey(context: Context, items: JSONObject) {
        val accounts = StoreWorkers.accounts(context)
        val found = HashMap<String, JSONArray>()
        for (acc in accounts) {
            val names = runCatching { StoreWorkers.listScripts(acc) }
                .onFailure { Log.w(TAG, "list ${acc.name}: ${it.message}") }
                .getOrNull() ?: continue
            for (name in names) {
                val s = runCatching { StoreWorkers.fetchScript(acc, name) }.getOrNull() ?: continue
                val item = StoreCatalog.classify(s.body) ?: continue   // the user's own: left alone
                found.getOrPut(item.id) { JSONArray() }.put(JSONObject().apply {
                    put("accountId", acc.accountId)
                    put("accountName", acc.name.ifBlank { acc.email })
                    put("script", name)
                    put("mainModule", s.mainModule)
                    put("version", workerVersionOf(item, s.body) ?: "")
                    put("hash", item.worker!!.hashOf(s.body))
                })
            }
        }
        for (w in StoreCatalog.WORKERS) {
            val o = items.optJSONObject(w.id) ?: JSONObject().also { items.put(w.id, it) }
            o.put("deployments", found[w.id] ?: JSONArray())
        }
    }

    // ── jobs ───────────────────────────────────────────────────────────────────────────────────

    private fun setJob(
        id: String, phase: String, progress: Float? = null, running: Boolean = true, error: String? = null, done: String? = null,
        paused: Boolean = false, bytes: Long = 0, total: Long = 0, speedBps: Long = 0,
    ) {
        _jobs.value = _jobs.value + (id to StoreJob(id, phase, progress, running, error, done, paused, bytes, total, speedBps))
    }

    /** Items the user paused: their download loop stops at the next chunk and keeps the part. */
    private val pausing = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    @Volatile private var appRef: Context? = null

    /** Stops a running download and keeps what arrived; [resume] continues from there. */
    fun pause(id: String) { pausing += id }

    /** Continues a paused download (or starts it again after an error) from the bytes on disk. */
    fun resume(context: Context, id: String) {
        pausing -= id
        install(context, id)
    }

    fun clearJob(id: String) { _jobs.value = _jobs.value - id }

    fun dismissMessage() { _message.value = null }

    private fun launchJob(context: Context, id: String, block: suspend (Context) -> String): Pair<Job, Boolean> = synchronized(running) {
        val app = context.applicationContext
        appRef = app
        running[id]?.takeIf { !it.isCompleted }?.let { return@synchronized it to false }
        pausing -= id
        // Set before the coroutine starts, so anything watching sees "running" at once.
        setJob(id, tr("آماده‌سازی…", "Preparing…"))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val msg = block(app)
                setJob(id, msg, 1f, running = false, done = msg)
            } catch (e: StoreNet.Paused) {
                val j = _jobs.value[id]
                setJob(id, tr("متوقف شد", "Paused"), j?.progress, running = false, paused = true, bytes = j?.bytes ?: 0, total = j?.total ?: 0)
            } catch (e: Exception) {
                Log.w(TAG, "$id failed", e)
                setJob(id, e.message ?: "", null, running = false, error = e.message ?: e.javaClass.simpleName)
            } finally {
                rebuild(app)
            }
        }
        running[id] = job
        job.start()
        job to true
    }

    /** Install only a missing executable, using the store's verified source and validation. */
    suspend fun ensureEngine(context: Context, id: String, lib: String) = withContext(Dispatchers.IO) {
        val item = requireNotNull(StoreCatalog.byId(id))
        require(item.kind == StoreKind.ENGINE && item.engine?.mode == EngineSpec.Mode.EXEC)
        require(lib in item.engine!!.libs)
        if (StoreEngines.available(context, id, lib)) return@withContext
        val (job, owned) = launchJob(context, id) { app ->
            if (StoreEngines.available(app, id, lib)) return@launchJob ""
            val candidate = resolve(app, item, null)
                ?: throw StoreError(tr("نسخهٔ قابل نصب پیدا نشد", "No installable version found"))
            currentCoroutineContext().ensureActive()
            installCandidate(app, item, candidate)
        }
        try {
            job.join()
            currentCoroutineContext().ensureActive()
            if (!StoreEngines.available(context, id, lib)) throw StoreError(
                jobs.value[id]?.error ?: tr("نصب موتور کامل نشد", "Engine installation did not complete"))
        } finally {
            if (owned && !job.isCompleted) {
                job.cancel()
                withContext(NonCancellable) { job.join() }
            }
        }
    }

    /** Stops it for good: the downloaded part is deleted too. */
    fun cancel(id: String) {
        pausing -= id
        synchronized(running) { running[id]?.cancel() }
        clearJob(id)
        appRef?.let { ctx -> runCatching { File(StoreFiles.itemDir(ctx, id), "partial").deleteRecursively() } }
    }

    // ── installing ─────────────────────────────────────────────────────────────────────────────

    /** The one button on a product page. */
    fun install(context: Context, id: String) {
        val item = StoreCatalog.byId(id) ?: return
        if (item.kind == StoreKind.APP) return installApp(context)
        launchJob(context, id) { app -> installItem(app, item) }
    }

    private fun installApp(context: Context) {
        val s = UpdateChecker.state.value as? UpdateChecker.State.Available ?: return
        val app = context.applicationContext
        scope.launch {
            setJob("mlmvpn", tr("در حال دانلود…", "Downloading…"), 0f)
            val watcher = launch {
                UpdateChecker.downloadProgressFlow.collect { p ->
                    if (p != null) setJob("mlmvpn", tr("در حال دانلود…", "Downloading…"), p / 100f)
                }
            }
            UpdateChecker.downloadAndInstall(app, s.info) { err ->
                setJob("mlmvpn", err, null, running = false, error = err)
            }
            watcher.cancel()
            if (_jobs.value["mlmvpn"]?.error == null) {
                setJob("mlmvpn", tr("نصب‌کنندهٔ اندروید باز شد", "Android's installer opened"), 1f, running = false,
                    done = tr("نصب‌کنندهٔ اندروید باز شد", "Android's installer opened"))
            }
        }
    }

    private suspend fun installItem(context: Context, item: StoreItem): String {
        val row = _rows.value.firstOrNull { it.item.id == item.id }
        val candidate = row?.candidate
        var message = ""
        if (row != null && candidate != null && row.state == StoreRow.State.UPDATE) {
            message = installCandidate(context, item, candidate)
            rebuild(context)
        }
        // A worker is not done until the copies on the user's accounts carry the new code too.
        if (item.kind == StoreKind.WORKER) {
            val pushed = pushWorker(context, item)
            if (pushed.isNotEmpty()) message = listOf(message, pushed).filter { it.isNotBlank() }.joinToString("\n")
        }
        if (message.isBlank()) message = tr("همین حالا بروز است.", "Already up to date.")
        return message
    }

    private fun installCandidate(context: Context, item: StoreItem, c: StoreCandidate): String {
        val work = File(context.cacheDir, "store-dl/${item.id}").apply { deleteRecursively(); mkdirs() }
        // Downloads live where a pause leaves them: resuming continues instead of starting over.
        val partial = File(StoreFiles.itemDir(context, item.id), "partial").apply { mkdirs() }
        val staged = File(StoreFiles.itemDir(context, item.id), "staged").apply { deleteRecursively(); mkdirs() }
        try {
            // 1. download, digest-checked
            val files = HashMap<String, File>()
            c.artifacts.forEachIndexed { i, a ->
                setJob(item.id, tr("در حال دانلود…", "Downloading…"), 0f)
                var lastAt = 0L
                var lastBytes = -1L
                var speed = 0L
                val dl = StoreNet.download(context, a, File(partial, "a$i-${a.name.substringAfterLast('/')}"), { got, total ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (lastBytes < 0) { lastBytes = got; lastAt = now }
                    // Shown at most four times a second; the speed is smoothed over each step.
                    if (now - lastAt >= 250 || (total > 0 && got >= total)) {
                        val inst = if (now > lastAt) (got - lastBytes) * 1000 / (now - lastAt) else 0L
                        speed = if (speed == 0L) inst else (speed * 7 + inst * 3) / 10
                        lastAt = now; lastBytes = got
                        setJob(item.id, tr("در حال دانلود…", "Downloading…"),
                            if (total > 0) (got.toFloat() / total).coerceIn(0f, 1f) else null,
                            bytes = got, total = total, speedBps = speed)
                    }
                }, { synchronized(running) { running[item.id]?.isCancelled == true } }, { item.id in pausing })
                if (a.format == "raw") {
                    val name = a.extract.values.firstOrNull() ?: defaultName(item, a)
                    files[name] = dl
                } else {
                    setJob(item.id, tr("باز کردن بسته…", "Unpacking…"))
                    files.putAll(StoreNet.extract(dl, a.format, a.extract, File(work, "x$i")))
                }
            }
            setJob(item.id, tr("بررسی…", "Checking…"))
            var version = c.version
            // Sources that become the data file only once built (the Iran configs from upstream).
            item.data?.build?.let { build ->
                setJob(item.id, tr("ساختن کانفیگ‌ها…", "Building…"))
                val text = try {
                    build(context, files.mapValues { it.value.readText() })
                } catch (e: Exception) {
                    throw StoreError(tr("از فایل‌های تازهٔ سازنده نشد کانفیگ ساخت (${e.message}) — شکلشان عوض شده؛ چیزی نصب نشد و نسخهٔ فعلی سر جایش ماند.",
                        "The developer's new files could not be built (${e.message}); nothing was installed and the current version stays."))
                }
                val built = File(work, item.data.file).apply { parentFile?.mkdirs(); writeText(text) }
                files.clear()
                files[item.data.file] = built
            }
            // 2. prove it before it is used
            when (item.kind) {
                StoreKind.ENGINE -> version = checkEngine(item, files, c.version)
                StoreKind.WORKER -> version = checkWorker(item, files, c.version)
                StoreKind.DATA -> checkData(item, files)
                else -> Unit
            }
            // 3. stage, then activate in one step
            for ((name, f) in files) {
                val dest = File(staged, name).apply { parentFile?.mkdirs() }
                f.copyTo(dest, overwrite = true)
                if (item.kind == StoreKind.ENGINE) dest.setExecutable(true, true)
            }
            val before = _rows.value.firstOrNull { it.item.id == item.id }?.version
            if (synchronized(running) { running[item.id]?.isCancelled == true }) throw kotlinx.coroutines.CancellationException()
            StoreFiles.activate(context, item.id, staged, version, c.from)
            // Installed: the downloaded parts have done their job.
            partial.deleteRecursively()
            if (item.data?.target == DataSpec.Target.FILES_DIR) placeLive(context, item)
            StoreFiles.addHistory(context, StoreHistory(item.id, item.title(), before, version, System.currentTimeMillis(), c.notes))
            return when (item.kind) {
                StoreKind.ENGINE -> if (item.engine?.mode == EngineSpec.Mode.JNI) {
                    tr("نسخهٔ $version نصب شد. برنامه را کامل ببندید و دوباره باز کنید تا استفاده شود.",
                        "Version $version installed. Close the app fully and reopen it to use it.")
                } else tr("نسخهٔ $version نصب شد و از اتصال بعدی استفاده می‌شود.", "Version $version installed; used from the next connection.")
                StoreKind.WORKER -> tr("کد نسخهٔ $version آماده شد.", "Version $version code is ready.")
                else -> tr("نسخهٔ $version نصب شد.", "Version $version installed.")
            }
        } finally {
            work.deleteRecursively()
            staged.deleteRecursively()
        }
    }

    private fun defaultName(item: StoreItem, a: StoreArtifact): String = when {
        item.worker != null -> item.worker.asset
        item.data != null -> item.data.file
        item.engine != null -> item.engine.libs.first()
        else -> a.name
    }

    private fun checkEngine(item: StoreItem, files: Map<String, File>, claimed: String): String {
        val spec = item.engine!!
        for (lib in spec.libs) {
            val f = files[lib] ?: throw StoreError(tr("فایل $lib در این نسخه نبود.", "$lib was missing from this version."))
            StoreEngines.checkElf(f, needDynamic = true)?.let { throw StoreError(it) }
        }
        if (spec.requires.isNotEmpty()) {
            val missing = StoreEngines.missingStrings(files.getValue(spec.libs.first()), spec.requires)
            if (missing.isNotEmpty()) {
                throw StoreError(
                    tr("این نسخه با برنامه جور نیست (${missing.first()} در آن نیست)؛ نصب نشد و نسخهٔ فعلی سر جایش ماند.",
                        "This version does not fit the app (${missing.first()} is missing); it was not installed and the current one stays."),
                )
            }
        }
        if (spec.mode == EngineSpec.Mode.EXEC) {
            val exe = files.getValue(spec.libs.first())
            exe.setExecutable(true, true)
            val probe = StoreEngines.probe(listOf(StoreEngines.linker(), exe.absolutePath), spec.probeArgs, spec.probeRe)
            if (!probe.started(claimed)) {
                throw StoreError(tr("نسخهٔ تازه روی این گوشی اجرا نشد، پس نصب نشد.", "The new version did not run on this phone, so it was not installed.") +
                    (probe.output.lines().lastOrNull { it.isNotBlank() }?.take(160)?.let { "\n$it" } ?: ""))
            }
            return probe.version ?: claimed
        }
        return claimed
    }

    private fun checkWorker(item: StoreItem, files: Map<String, File>, claimed: String): String {
        val spec = item.worker!!
        val f = files[spec.asset] ?: files.values.first()
        val text = f.readText().let { if (spec.stripBom) it.removePrefix("﻿") else it }
        f.writeText(text)
        if (!runCatching { spec.detect(text) }.getOrDefault(false) || spec.mustContain.any { !text.contains(it) }) {
            throw StoreError(tr("کد تازه دیگر با این برنامه نمی‌خواند (نشانه‌هایی که برنامه به آن‌ها تکیه دارد در آن نیست) — نصب نشد.",
                "The new code no longer matches what this app drives — it was not installed."))
        }
        val inCode = runCatching { spec.versionOf(text) }.getOrNull()
        return inCode ?: claimed
    }

    private fun checkData(item: StoreItem, files: Map<String, File>) {
        val spec = item.data!!
        val f = files[spec.file] ?: files.values.first()
        if (f.length() < spec.minBytes) throw StoreError(tr("فایل داده ناقص است.", "The data file is incomplete."))
        if (spec.json) {
            val t = f.readText().trim()
            val ok = runCatching { if (t.startsWith("[")) JSONArray(t) else JSONObject(t) }.isSuccess
            if (!ok) throw StoreError(tr("فایل داده JSON معتبر نیست.", "The data file is not valid JSON."))
        }
    }

    /** Geo data lives where Xray reads it: filesDir itself. Replace it in one rename. */
    private fun placeLive(context: Context, item: StoreItem) {
        val spec = item.data ?: return
        val live = File(context.filesDir, spec.file)
        val src = StoreFiles.activeFile(context, item.id, spec.file)
        val tmp = File(live.path + ".store")
        if (src != null) src.copyTo(tmp, overwrite = true) else context.assets.open(spec.file).use { i -> tmp.outputStream().use { i.copyTo(it) } }
        if (!tmp.renameTo(live)) { tmp.copyTo(live, overwrite = true); tmp.delete() }
    }

    // ── workers on the user's accounts ─────────────────────────────────────────────────────────

    /** Push the store's current code to every deployed copy that is behind. Returns a summary. */
    private suspend fun pushWorker(context: Context, item: StoreItem): String {
        val spec = item.worker!!
        val target = workerTarget(context, item) ?: return ""
        val targetVersion = workerVersionOf(item, target)
        val targetHash = spec.hashOf(target)
        val accounts = StoreWorkers.accounts(context)
        val cachedDeps = _rows.value.firstOrNull { it.item.id == item.id }?.deployments.orEmpty()
        var updated = 0
        val failed = ArrayList<String>()
        cachedDeps.forEachIndexed { i, dep ->
            val acc = accounts.firstOrNull { it.accountId == dep.accountId } ?: return@forEachIndexed
            setJob(item.id, tr("بروزرسانی روی ${dep.accountName}…", "Updating on ${dep.accountName}…"), i.toFloat() / cachedDeps.size)
            try {
                // Read it again right now: the survey may be hours old.
                val live = StoreWorkers.fetchScript(acc, dep.script) ?: throw StoreError(tr("کد ورکر خوانده نشد", "Could not read the worker"))
                if (StoreCatalog.classify(live.body)?.id != item.id) throw StoreError(tr("این ورکر دیگر همان نیست — دست نخورد", "This worker is no longer what it was — left alone"))
                val liveVersion = workerVersionOf(item, live.body)
                val behind = when {
                    liveVersion != null && targetVersion != null -> StoreVersions.compare(liveVersion, targetVersion) == -1
                    else -> spec.hashOf(live.body) != targetHash
                }
                if (!behind) return@forEachIndexed
                if (spec.update == WorkerSpec.Update.STUDIO) {
                    when (val r = StudioDeployer(context).install(acc)) {
                        is StudioDeployer.Result.Ready -> updated++
                        is StudioDeployer.Result.WouldDowngrade -> throw StoreError(tr("نسخهٔ روی حساب جدیدتر است", "The account runs a newer build"))
                        is StudioDeployer.Result.Failed -> throw StoreError(r.message)
                    }
                    return@forEachIndexed
                }
                val code = StoreWorkers.codeFor(item, target, live.body)
                StoreWorkers.saveBackup(context, acc, dep.script, live, liveVersion)
                StoreWorkers.putContent(acc, dep.script, live, code)
                // Read it back. An upload that answered 200 and landed as something else is the
                // failure a user would otherwise discover when their panel stopped working.
                val after = runCatching { StoreWorkers.fetchScript(acc, dep.script) }.getOrNull()
                if (after != null && StoreCatalog.classify(after.body)?.id != item.id) {
                    StoreWorkers.putContent(acc, dep.script, live, live.body)
                    throw StoreError(tr("کد تازه روی ورکر ننشست؛ نسخهٔ قبلی برگردانده شد.", "The new code did not take; the previous one was restored."))
                }
                updated++
            } catch (e: Exception) {
                failed += "${dep.accountName}: ${e.message}"
            }
        }
        if (updated > 0 || failed.isNotEmpty()) {
            // The picture on the accounts changed; look again so the rows are right.
            val items = readCache(context).optJSONObject("items") ?: JSONObject()
            runCatching { survey(context, items) }
            val cache = readCache(context).put("items", items)
            writeCache(context, cache)
            if (updated > 0) {
                StoreFiles.addHistory(context, StoreHistory(item.id, item.title(), null, targetVersion ?: targetHash.take(8), System.currentTimeMillis(),
                    tr("روی $updated ورکر در حساب‌های کلودفلر شما نشست.", "Applied to $updated worker(s) on your Cloudflare accounts.")))
            }
        }
        return buildString {
            if (updated > 0) append(tr("روی $updated ورکر بروز شد.", "Updated on $updated worker(s)."))
            if (failed.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(failed.joinToString("\n"))
                if (updated == 0) throw StoreError(toString())
            }
        }
    }

    // ── going back ─────────────────────────────────────────────────────────────────────────────

    fun rollback(context: Context, id: String) {
        val item = StoreCatalog.byId(id) ?: return
        launchJob(context, id) { app ->
            setJob(id, tr("برگشت…", "Rolling back…"))
            val to = StoreFiles.rollback(app, id)
            if (item.data?.target == DataSpec.Target.FILES_DIR) placeLive(app, item)
            StoreFiles.addHistory(app, StoreHistory(id, item.title(), null, to ?: tr("نسخهٔ همراه برنامه", "the shipped version"), System.currentTimeMillis(),
                tr("برگشت به نسخهٔ قبل", "Rolled back")))
            if (to == null) tr("به نسخهٔ همراه برنامه برگشت.", "Back to the version the app shipped.")
            else tr("به نسخهٔ $to برگشت.", "Back to version $to.")
        }
    }

    /** Put back the code that was live on one account before the store changed it. */
    fun rollbackWorker(context: Context, id: String, accountId: String, script: String) {
        launchJob(context, id) { app ->
            val acc = StoreWorkers.accounts(app).firstOrNull { it.accountId == accountId }
                ?: throw StoreError(tr("این حساب دیگر در برنامه نیست.", "This account is no longer in the app."))
            setJob(id, tr("برگرداندن کد قبلی…", "Restoring the previous code…"))
            val v = StoreWorkers.rollback(app, acc, script)
            val items = readCache(app).optJSONObject("items") ?: JSONObject()
            runCatching { survey(app, items) }
            writeCache(app, readCache(app).put("items", items))
            tr("کد قبلی برگردانده شد", "The previous code was restored") + (v?.let { " ($it)" } ?: "")
        }
    }

    fun hasWorkerBackup(context: Context, accountId: String, script: String): Boolean {
        val acc = runCatching { StoreWorkers.accounts(context).firstOrNull { it.accountId == accountId } }.getOrNull() ?: return false
        return StoreWorkers.hasBackup(context, acc, script)
    }

    // ── everything at once ─────────────────────────────────────────────────────────────────────

    /**
     * «بروزرسانی همه». One at a time on purpose: two 20 MB downloads over one filtered line finish
     * later than the same two in turn, and the progress bars stop meaning anything.
     */
    fun updateAll(context: Context) {
        val app = context.applicationContext
        val todo = _rows.value.filter { it.hasUpdate && it.item.kind != StoreKind.APP }.map { it.item.id }
        if (todo.isEmpty()) return
        scope.launch {
            setJob("all", tr("بروزرسانی همه…", "Updating all…"), 0f)
            var ok = 0
            todo.forEachIndexed { i, id ->
                setJob("all", StoreCatalog.byId(id)?.title() ?: id, i.toFloat() / todo.size)
                install(app, id)
                // Wait for that item's job to finish before starting the next.
                while (_jobs.value[id]?.running != false) kotlinx.coroutines.delay(400)
                if (_jobs.value[id]?.error == null) ok++
            }
            setJob("all", tr("$ok مورد بروز شد", "$ok updated"), 1f, running = false, done = tr("$ok مورد بروز شد", "$ok updated"))
        }
    }

    /** How many items have something to install — the badge on the store's icon. */
    fun pendingCount(): Int = _rows.value.count { it.hasUpdate }
}

fun StoreItem.title(): String = tr(titleFa, titleEn)
fun StoreItem.subtitle(): String = tr(subtitleFa, subtitleEn)
fun StoreItem.about(): String = tr(aboutFa, aboutEn)
