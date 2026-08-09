package com.mlmvpn.scanner.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks the GitHub "latest release" of mlmvpn/mlmvpn_android against the installed
 * versionName and, if newer, downloads + installs the APK. Every network call here is
 * best-effort: GitHub is blocked for many users in Iran, so any failure (no internet,
 * DNS/TLS block, rate limit, malformed response) is swallowed silently -- this must never
 * surface an error or interrupt anything else the app is doing.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val REPO = "mlmvpn/mlmvpn_android"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val changelog: List<String>
    )

    val updateAvailableFlow = MutableStateFlow<UpdateInfo?>(null)
    val downloadProgressFlow = MutableStateFlow<Int?>(null) // null = not downloading, 0..100

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var lastCheckAttempt = 0L
    private const val MIN_CHECK_INTERVAL_MS = 60_000L // avoid hammering GitHub from many trigger points

    suspend fun checkForUpdate(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCheckAttempt < MIN_CHECK_INTERVAL_MS) return
        lastCheckAttempt = now
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(API_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "mlmvpn-android")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext
                    val body = resp.body?.string() ?: return@withContext
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name", "").removePrefix("v").removePrefix("V")
                    if (tag.isBlank()) return@withContext
                    val current = context.packageManager
                        .getPackageInfo(context.packageName, 0).versionName ?: return@withContext
                    if (!isNewer(tag, current)) return@withContext

                    val assets = json.optJSONArray("assets") ?: return@withContext
                    var apkUrl: String? = null
                    var apkSize = 0L
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", null)
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                    if (apkUrl.isNullOrBlank()) return@withContext

                    val notesBody = json.optString("body", "")
                    val changelog = notesBody.lines()
                        .map { it.trim().removePrefix("-").removePrefix("*").trim() }
                        .filter { it.isNotBlank() }

                    updateAvailableFlow.value = UpdateInfo(
                        versionName = tag,
                        apkUrl = apkUrl,
                        apkSizeBytes = apkSize,
                        changelog = changelog.ifEmpty { listOf(notesBody.trim()).filter { it.isNotBlank() } }
                    )
                }
            } catch (e: Exception) {
                // Silent by design -- GitHub being unreachable (filtered network, no internet,
                // rate-limited) must never bother the user or break app flow.
                Log.d(TAG, "update check skipped: ${e.message}")
            }
        }
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

    /** Downloads the APK with progress reporting, then launches the system installer. */
    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                downloadProgressFlow.value = 0
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(dir, "mlmvpn_update.apk")

                val req = Request.Builder().url(info.apkUrl).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw Exception("HTTP ${resp.code}")
                    }
                    val respBody = resp.body ?: throw Exception("empty response")
                    val total = respBody.contentLength().takeIf { it > 0 } ?: info.apkSizeBytes
                    respBody.byteStream().use { input ->
                        apkFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            var lastReported = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (total > 0) {
                                    val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                    if (pct != lastReported) {
                                        downloadProgressFlow.value = pct
                                        lastReported = pct
                                    }
                                }
                            }
                        }
                    }
                }
                downloadProgressFlow.value = 100

                withContext(Dispatchers.Main) {
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "download/install failed", e)
                downloadProgressFlow.value = null
                withContext(Dispatchers.Main) { onError(e.message ?: "خطای دانلود") }
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
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
