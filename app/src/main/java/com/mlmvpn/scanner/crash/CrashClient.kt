package com.mlmvpn.scanner.crash

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Delivers one crash report: to the crash collector (worker-src/crash), and when that does not
 * take it, to the Quick Connect pool's `/crash`. It counts as delivered only when one of them says
 * it was filed; anything less and [com.mlmvpn.scanner.CrashReporter] keeps the report and tries
 * again later.
 *
 * Why a collector of its own: the pool authenticates every request against D1, and at its size it
 * spends its account's free D1 reads and Worker requests before the day is out -- and on those
 * days every crash report was refused. The collector needs no database and lives on another
 * account, so nothing the pool does can take it down. See docs/CRASH-REPORTS.md.
 */
object CrashClient {

    private const val TAG = "CrashClient"

    /** Also in worker-src/crash/worker.js. Shipped in every APK: it keeps out URL scanners, no more. */
    const val APP_KEY = "redacted-crash-key"

    /**
     * The crash collector, on a Cloudflare account the pool does not use.
     * scripts/deploy-crash-worker.mjs deploys it and rewrites this line when the address differs.
     */
    const val ENDPOINT = "https://crash.redacted.invalid"

    private val dohDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            // *.workers.dev is poisoned by some operators' resolvers: DNS over HTTPS to a resolver's
            // address first, the system resolver second (same as the pool and crowd clients).
            val viaDoh = runCatching {
                kotlinx.coroutines.runBlocking { com.mlmvpn.scanner.utils.DomainPreResolver.resolve(hostname) }
            }.getOrDefault(emptyList()).mapNotNull { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
            return viaDoh.ifEmpty { okhttp3.Dns.SYSTEM.lookup(hostname) }
        }
    }

    private val http by lazy {
        OkHttpClient.Builder().protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory())
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns(dohDns))
            .build()
    }

    /**
     * Sends [body], summarised by [summary] (the line the server groups reports by). True only when
     * it was filed somewhere a person reads.
     */
    suspend fun send(context: Context, summary: String, body: String, kind: CrashKind): Boolean = withContext(Dispatchers.IO) {
        if (sendToCollector(context, summary, body, kind)) return@withContext true
        // The pool's copy: D1 and, through the same GitHub token, the same issue.
        com.mlmvpn.scanner.quick.MlmPoolClient.reportCrash(context, summary, body)
    }

    private fun sendToCollector(context: Context, summary: String, body: String, kind: CrashKind): Boolean {
        val payload = JSONObject().apply {
            put("id", installId(context))
            put("kind", kind.wire)
            put("summary", summary.take(300))
            put("body", body.take(24_000))
            put("app", appVersion(context))
            put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            put("android", android.os.Build.VERSION.RELEASE)
        }.toString()
        val ts = System.currentTimeMillis().toString()
        val request = Request.Builder()
            .url("$ENDPOINT/v1/crash")
            .header("X-Ts", ts)
            .header("X-Sig", hmac(APP_KEY, "$ts.$payload"))
            .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        return try {
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) Log.w(TAG, "collector refused: HTTP ${res.code}")
                res.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "collector unreachable: ${e.message}")
            false
        }
    }

    /**
     * A random id for the collector's per-install limit and its count of installs per bug. Its own,
     * not the pool's: nothing ties a crash report to Quick Connect use.
     */
    private fun installId(context: Context): String {
        val prefs = context.getSharedPreferences("crash_diag", Context.MODE_PRIVATE)
        prefs.getString("collector_id", null)?.takeIf { it.length == 32 }?.let { return it }
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("collector_id", id).apply()
        return id
    }

    /**
     * "1.2.41 (77412345)": the release's base versionCode times a million plus the minute the APK
     * was built. The collector files a report on a closed issue only when its number is higher than
     * any it saw before, and test builds share one versionCode: with the plain code, a new APK's
     * crash on an issue closed as fixed was counted in silence (2026-10-11). The base code, without
     * the per-ABI offset, so an armeabi build does not read as older than an arm64 one.
     */
    private fun appVersion(context: Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "${pi.versionName} (${buildNumber(code, com.mlmvpn.scanner.BuildConfig.BUILD_STAMP)})"
    } catch (e: Exception) {
        "?"
    }

    internal fun buildNumber(versionCode: Long, stamp: Long): Long =
        (versionCode % 1_000_000) * 1_000_000 + stamp.coerceIn(0, 999_999)

    private fun hmac(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
