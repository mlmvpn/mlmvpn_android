package com.mlmvpn.scanner.update

import org.json.JSONArray
import android.provider.Settings
import android.os.Build
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.store.StoreArtifact
import com.mlmvpn.scanner.store.StoreNet
import com.mlmvpn.scanner.utils.S

/**
 * Checks the GitHub "latest release" of mlmvpn/mlmvpn_android against the installed
 * versionName and, if newer, downloads + installs the APK. Every network call here is
 * best-effort: GitHub is blocked for many users in Iran, so a failure (no internet,
 * DNS/TLS block, rate limit, malformed response) must never interrupt anything else -- but it is
 * no longer SILENT, because a user who opened the update page and pressed check is owed an answer.
 * See [state].
 *
 * ## Which release a user gets
 *
 * `/releases/latest` is GitHub's own answer, not ours: the most recent **non-draft,
 * non-prerelease** release, ordered by `created_at` -- which is the TAG's commit date, not the
 * publish date -- unless a release has been explicitly ticked "Set as the latest release", which
 * overrides the ordering. Three consequences worth knowing before publishing:
 *
 *  - A draft or a pre-release is invisible here. Nobody is ever offered one.
 *  - Tagging an old commit produces an old `created_at`, so a release published today can lose to
 *    one published last week. If two releases are live at once, tick the one that should win.
 *  - Whatever GitHub returns is then still compared against the installed version by [isNewer],
 *    so a "latest" whose tag is LOWER than what is installed is ignored. The app never downgrades.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val REPO = "mlmvpn/mlmvpn_android"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    private const val PREFS = "app_settings"
    private const val KEY_AUTO_WIFI = "update_auto_download_wifi"
    private const val KEY_LAST_CHECK = "update_last_check_at"
    /** The newest public release a check has seen, for Gereh's «old version» tag in `:chat` (T75). */
    private const val LATEST_FILE = "latest_release"

    /** Auto-download over Wi-Fi is on out of the box, like the platform updaters users know. */
    const val DEFAULT_AUTO_WIFI = true

    data class UpdateInfo(
        val versionName: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val changelog: List<String>,
        /** GitHub's SHA-256 of the asset (every asset uploaded since mid-2025 has one), or null. */
        val sha256: String? = null,
    )

    /**
     * What the last check found.
     *
     * Added because "no update" and "the check never happened" used to be the same thing -- a null
     * flow -- and a page with a Check button cannot tell the user anything with that. Every branch
     * here is something the screen can say in words.
     */
    sealed class State {
        /** Nothing has been asked yet this session. */
        object Idle : State()
        object Checking : State()
        data class UpToDate(val checkedAt: Long) : State()
        data class Available(val info: UpdateInfo, val checkedAt: Long) : State()
        /** The check itself did not complete -- offline, blocked, rate-limited. */
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Kept for the app-wide "an update is available" dialog, which only cares about that case. */
    val updateAvailableFlow = MutableStateFlow<UpdateInfo?>(null)
    val downloadProgressFlow = MutableStateFlow<Int?>(null) // null = not downloading, 0..100

    /** True while a background (auto) download is filling the cache. */
    private val _autoDownloading = MutableStateFlow(false)
    val autoDownloading: StateFlow<Boolean> = _autoDownloading.asStateFlow()

    /**
     * For the version check: a small JSON body that should arrive quickly or not at all.
     *
     * Built per call by [UpdateNet], which resolves over DNS-over-HTTPS and sends the request
     * through the engine's local proxy when a tunnel is up. Both matter here: the download used to
     * fail with `Unable to resolve host "github.com"` on a line where the check beside it had just
     * succeeded, because the two use different hostnames and the network answered them
     * differently.
     */
    private fun client(context: Context) = UpdateNet.client(context, 8, 10)

    /** The latest public release's title and its full notes, as the developer wrote them. */
    data class ReleaseNotes(val tag: String, val title: String, val body: String, val publishedAt: String)

    /**
     * «آخرین تغییرات»: the notes of the latest PUBLIC release (the same `/releases/latest` the
     * check reads -- drafts and pre-releases never appear), fetched fresh on every open. Null when
     * GitHub cannot be reached; the screen then shows the in-app changelog of the newest version.
     */
    suspend fun latestReleaseNotes(context: Context): ReleaseNotes? = withContext(Dispatchers.IO) {
        runCatching {
            client(context).newCall(okhttp3.Request.Builder().url(API_URL)
                .header("Accept", "application/vnd.github+json").get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = org.json.JSONObject(r.body?.string().orEmpty())
                val body = o.optString("body")
                if (body.isBlank()) null
                else ReleaseNotes(o.optString("tag_name"), o.optString("name"), body, o.optString("published_at"))
            }
        }.onFailure { Log.w(TAG, "release notes", it) }.getOrNull()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autoJob: Job? = null

    private var lastCheckAttempt = 0L
    private const val MIN_CHECK_INTERVAL_MS = 60_000L // avoid hammering GitHub from many trigger points

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When the last check actually completed, or 0. Persisted, so the page can say it. */
    fun lastCheckedAt(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    /** The newest public release a check has seen ("1.2.46"), from any process; null before the first (T75). */
    fun latestKnown(context: Context): String? =
        runCatching { File(context.filesDir, LATEST_FILE).readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()

    fun autoDownloadOnWifi(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_WIFI, DEFAULT_AUTO_WIFI)

    fun setAutoDownloadOnWifi(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_WIFI, value).apply()
    }

    /**
     * @param force skip the rate limit. The limit exists because several places call this on their
     *   own schedule; a user who pressed a button is not one of them, and silently doing nothing
     *   for a button press is the single worst thing an updater can do.
     */
    suspend fun checkForUpdate(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAttempt < MIN_CHECK_INTERVAL_MS) return
        lastCheckAttempt = now
        if (force) _state.value = State.Checking
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(API_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "mlmvpn-android")
                    .get()
                    .build()
                client(context).newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        _state.value = State.Failed("HTTP ${resp.code}")
                        return@withContext
                    }
                    val body = resp.body?.string()
                    if (body == null) {
                        _state.value = State.Failed(S(R.string.update_check_failed))
                        return@withContext
                    }
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name", "").removePrefix("v").removePrefix("V")
                    val current = context.packageManager
                        .getPackageInfo(context.packageName, 0).versionName
                    if (tag.isBlank() || current == null) {
                        _state.value = State.Failed(S(R.string.update_check_failed))
                        return@withContext
                    }

                    prefs(context).edit().putLong(KEY_LAST_CHECK, now).apply()
                    // a file, not a preference: Gereh reads it from its own process (T75)
                    runCatching { File(context.filesDir, LATEST_FILE).writeText(tag) }

                    if (!isNewer(tag, current)) {
                        updateAvailableFlow.value = null
                        _state.value = State.UpToDate(now)
                        return@withContext
                    }

                    val assets = json.optJSONArray("assets")
                    val chosen = assets?.let { pickApkForThisDevice(it) }
                    if (chosen == null) {
                        // A release with no APK this device can install is not an update; saying
                        // "up to date" would be a lie, so it is reported as a failed check.
                        _state.value = State.Failed(S(R.string.update_no_asset))
                        return@withContext
                    }

                    val notesBody = json.optString("body", "")
                    // The notes are Markdown and the page shows plain lines: headings lose their «#»
                    // and bold its «**», which the page used to show as written (seen 2026-10-20).
                    val changelog = notesBody.lines()
                        .map { it.trim().trimStart('#').trim().removePrefix("-").removePrefix("*").trim().replace("**", "") }
                        .filter { it.isNotBlank() }

                    val info = UpdateInfo(
                        versionName = tag,
                        apkUrl = chosen.url,
                        apkSizeBytes = chosen.size,
                        changelog = changelog.ifEmpty {
                            listOf(notesBody.trim()).filter { it.isNotBlank() }
                        },
                        sha256 = chosen.sha256,
                    )
                    updateAvailableFlow.value = info
                    _state.value = State.Available(info, now)
                    maybeAutoDownload(context, info)
                }
            } catch (e: Exception) {
                // Never rethrown: GitHub being unreachable must not break app flow. But it IS
                // recorded, so a page that asked can say what happened instead of nothing.
                Log.d(TAG, "update check skipped: ${e.message}")
                _state.value = State.Failed(e.message ?: S(R.string.update_check_failed))
            }
        }
    }

    /**
     * Fill the cache in the background when the user has asked for that and the line is free.
     *
     * Unmetered only, and by the system's own definition of unmetered rather than by transport
     * type -- a Wi-Fi the user has marked as metered, or a phone tethering another phone, is
     * exactly the case the setting is meant to protect against, and TRANSPORT_WIFI alone does not
     * catch either.
     *
     * Downloading is all it does. Installing needs the user's consent and a foreground activity,
     * so an updater that installed by itself would be both impossible and unwelcome.
     */
    private fun maybeAutoDownload(context: Context, info: UpdateInfo) {
        if (!autoDownloadOnWifi(context)) return
        if (autoJob?.isActive == true) return
        if (!isUnmetered(context)) return
        if (cachedApk(context, info) != null) return
        val app = context.applicationContext
        autoJob = scope.launch {
            _autoDownloading.value = true
            try {
                fetchApk(app, info, visible = false)
                Log.i(TAG, "auto-downloaded ${info.versionName} over an unmetered link")
            } catch (e: Exception) {
                Log.d(TAG, "auto-download skipped: ${e.message}")
            } finally {
                _autoDownloading.value = false
            }
        }
    }

    private fun isUnmetered(context: Context): Boolean = try {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } catch (e: Exception) {
        false
    }

    /** Simple dotted-numeric version compare, e.g. "1.3.0" > "1.2.1". Non-numeric parts treated as 0. */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(r.size, c.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    fun dismiss() {
        updateAvailableFlow.value = null
    }

    /**
     * Picks the release asset this device can actually install.
     *
     * Up to 1.2.1 a release carried a single APK and taking the first `.apk` was correct. From
     * 1.2.2 the build is split per ABI, so a release carries three:
     *
     *     mlmvpn-1.2.2-arm64-v8a-release.apk
     *     mlmvpn-1.2.2-armeabi-v7a-release.apk
     *     mlmvpn-1.2.2-universal-release.apk
     *
     * GitHub returns them in upload order, so "the first one" is whichever happened to be
     * uploaded first -- and handing an armeabi-v7a phone the arm64 build produces a bare
     * "App not installed" from the system installer with nothing to explain it.
     *
     * Preference order:
     *   1. an asset naming this device's own ABI, most-preferred ABI first. [Build.SUPPORTED_ABIS]
     *      is already ordered that way, so a 64-bit phone takes the arm64 split rather than the
     *      32-bit one it could also run.
     *   2. the universal build, which contains every ABI.
     *   3. any `.apk` at all -- the single-asset releases that are already out there.
     *
     * @return the download URL, its size and GitHub's digest, or null if the release carries no APK.
     */
    private fun pickApkForThisDevice(assets: JSONArray): Asset? {
        val apks = ArrayList<Pair<String, JSONObject>>()
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apks.add(name.lowercase() to asset)
            }
        }
        if (apks.isEmpty()) return null

        // Matched on the ABI as a whole word: "armeabi-v7a" contains neither "arm64-v8a" nor
        // "x86_64", but a bare contains("x86") WOULD match "x86_64", so the separators matter.
        val exact = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            apks.firstOrNull { (name, _) -> name.contains("-" + abi.lowercase()) }
        }
        val chosen = exact
            ?: apks.firstOrNull { (name, _) -> name.contains("universal") }
            ?: apks.first()

        val url = chosen.second.optString("browser_download_url", "")
        if (url.isBlank()) return null
        Log.i(TAG, "update asset: ${chosen.first} for abis ${Build.SUPPORTED_ABIS.joinToString()}")
        return Asset(
            url, chosen.second.optLong("size", 0L),
            chosen.second.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
        )
    }

    private data class Asset(val url: String, val size: Long, val sha256: String?)

    /** Where a downloaded APK lives. Named per version, so two versions cannot be confused. */
    private fun apkFileFor(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        return File(dir, "mlmvpn-${info.versionName}.apk")
    }

    /**
     * The already-downloaded APK for this version, if it is complete.
     *
     * Complete means the byte count the release said it would be. A truncated file -- a server
     * closing the stream early, a process killed mid-write -- is the same size question, and
     * handing one to the installer produces "App not installed" with nothing to explain it.
     */
    fun cachedApk(context: Context, info: UpdateInfo): File? {
        val f = apkFileFor(context, info)
        if (!f.exists()) return null
        if (info.apkSizeBytes > 0 && f.length() != info.apkSizeBytes) return null
        if (f.length() <= 0) return null
        return f
    }

    /** True when the APK is already on disk and installing is instant. */
    fun isDownloaded(context: Context, info: UpdateInfo): Boolean = cachedApk(context, info) != null

    /**
     * The button's download, run in this object's scope rather than the screen's: leaving the page
     * used to cancel the coroutine, so a file that finished in the background never opened the installer.
     */
    fun startDownload(context: Context, info: UpdateInfo, onError: (String) -> Unit) {
        val app = context.applicationContext
        scope.launch { downloadAndInstall(app, info, onError) }
    }

    /** Downloads the APK with progress reporting, then launches the system installer. */
    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                // Straight to the installer when the file is already here. This is the path taken
                // after the user grants "install unknown apps" and comes back -- which used to
                // re-download all 66 MB despite a comment promising it would not.
                val ready = cachedApk(context, info)
                if (ready != null) {
                    downloadProgressFlow.value = 100
                    withContext(Dispatchers.Main) { installApk(context, ready) }
                    return@withContext
                }

                downloadProgressFlow.value = 0
                val apkFile = fetchApk(context, info, visible = true)
                downloadProgressFlow.value = 100
                withContext(Dispatchers.Main) { installApk(context, apkFile) }
            } catch (e: Exception) {
                Log.e(TAG, "download/install failed", e)
                downloadProgressFlow.value = null
                // The retries are spent but nothing is lost: the part stays, and the next press goes on from it.
                val why = if (e is java.io.IOException) com.mlmvpn.scanner.store.tr(
                    "اینترنت وسط دانلود چند بار قطع شد. دوباره بزنید؛ دانلود از همان‌جا که مانده ادامه پیدا می‌کند.",
                    "The connection kept dropping during the download. Press again: it continues from where it stopped.",
                ) else e.message ?: S(R.string.download_error)
                withContext(Dispatchers.Main) { onError(why) }
            }
        }
    }

    /** One download at a time: the background fill and the button share the same part file. */
    private val fetchLock = Any()

    /** Someone is watching the page: a background fill already under way reports its progress too. */
    @Volatile private var watched = false

    /**
     * Download to the cache and return the file. Throws on anything that would leave a bad APK.
     *
     * Through the store's downloader ([StoreNet.download]): what arrived is kept in a `.part` and the
     * next connection asks only for the rest (HTTP Range), a dropped connection is retried by itself,
     * and the file takes its real name only once its size -- and GitHub's SHA-256 of the asset, when
     * the release has one -- match, so a truncated APK never reaches the installer. Before, the APK
     * was one request under a 95 s ceiling on the whole call (see UpdateNet.client), with no retry and
     * its part deleted at the next try: on most lines it could not finish, and every press began
     * again from zero -- the users' report, 2026-10-20.
     */
    private fun fetchApk(context: Context, info: UpdateInfo, visible: Boolean): File {
        if (visible) watched = true
        try {
            synchronized(fetchLock) {
                // The background fill may have finished it while this one waited.
                cachedApk(context, info)?.let { return it }
                val apkFile = apkFileFor(context, info)
                val artifact = StoreArtifact(
                    name = apkFile.name, urls = listOf(info.apkUrl),
                    sha256 = info.sha256, size = info.apkSizeBytes, format = "raw",
                )
                var lastPct = -1
                val done = StoreNet.download(context, artifact, apkFile, { got, total ->
                    if (total > 0 && watched) {
                        val pct = ((got * 100) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; downloadProgressFlow.value = pct }
                    }
                })
                pruneOldApks(context, done)
                return done
            }
        } finally {
            if (visible) watched = false
        }
    }

    /** One APK in the cache at a time; the others are versions nobody will install now. */
    private fun pruneOldApks(context: Context, keep: File) {
        runCatching {
            File(context.cacheDir, "updates").listFiles()?.forEach { f ->
                if (f.name != keep.name) f.delete()
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        // On Android 8+ an app may only launch the package installer once the user has allowed it
        // to install unknown apps. Without this the ACTION_VIEW below opens the installer, which
        // immediately backs out to a settings prompt and loses the file it was given. Sending them
        // to the right settings page first means the download survives -- and it genuinely does
        // now, because downloadAndInstall reuses a complete cached APK instead of starting over.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }

        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
