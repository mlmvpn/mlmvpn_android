package com.mlmvpn.scanner.engines.gst

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Preflight self-test for a Google Apps Script (or Cloudflare Worker) relay deployment.
 *
 * This talks to the deployment DIRECTLY over normal HTTPS (no VPN tunnel, no domain
 * fronting) and relays a tiny throwaway request through it, then inspects the raw
 * reply. It is the single most useful signal for "why doesn't GST connect": it tells
 * us whether the deployment is reachable, whether the AUTH_KEY matches, and whether
 * Google is serving the relay JSON or a decoy/redirect/error page.
 *
 * Reused as the "Test relay" button in the unified panel.
 */
object GstDiagnostics {

    private const val TAG = "GstDiagnostics"

    /** A cheap, always-up, 204-returning endpoint to relay as the probe target. */
    private const val PROBE_TARGET = "https://www.gstatic.com/generate_204"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // Follow the script.google.com -> script.googleusercontent.com redirect so we
        // observe the FINAL body, not the 302 shell.
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    enum class Result { OK, AUTH_MISMATCH, REDIRECT_BLOCKED, UNREACHABLE, BAD_RESPONSE }

    data class Report(
        val result: Result,
        /** Human-readable Persian diagnosis for the UI. */
        val message: String,
        val httpCode: Int = 0,
        val rawPreview: String = ""
    )

    /** Build the /exec URL for a raw Apps Script deployment id. */
    fun execUrl(deploymentId: String): String =
        "https://script.google.com/macros/s/${deploymentId.trim()}/exec"

    /**
     * Relay one probe request through the given endpoint.
     *
     * @param endpointUrl full URL that accepts the relay protocol POST � either an
     *   Apps Script /exec URL (see [execUrl]) or a Cloudflare Worker relay URL.
     * @param authKey the shared secret; must equal AUTH_KEY inside the deployed script.
     */
    suspend fun testDeployment(endpointUrl: String, authKey: String): Report =
        withContext(Dispatchers.IO) {
            GstLog.i(TAG, "Preflight test → $endpointUrl (auth=${GstLog.redact(authKey)})")

            val payload = JSONObject().apply {
                put("k", authKey)
                put("m", "GET")
                put("u", PROBE_TARGET)
                put("h", JSONObject())
                put("r", true)
            }.toString()

            val req = Request.Builder()
                .url(endpointUrl)
                .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            try {
                client.newCall(req).execute().use { res ->
                    val code = res.code
                    val finalUrl = res.request.url.toString()
                    val body = res.body?.string() ?: ""
                    val preview = if (body.length > 300) body.substring(0, 300) else body
                    GstLog.d(TAG, "HTTP $code (final=$finalUrl), ${body.length} bytes")
                    GstLog.d(TAG, "Body preview: $preview")

                    // 1. Relay JSON envelope � the happy path.
                    val trimmed = body.trimStart()
                    if (trimmed.startsWith("{")) {
                        val json = try { JSONObject(body) } catch (e: Exception) { null }
                        if (json != null) {
                            when {
                                json.has("s") -> {
                                    val relayedStatus = json.optInt("s")
                                    GstLog.i(TAG, "✅ Relay OK, upstream status=$relayedStatus")
                                    return@withContext Report(
                                        Result.OK,
                                        "✅ رله سالم است. پاسخ مقصد: کد $relayedStatus",
                                        code, preview
                                    )
                                }
                                json.optString("e").contains("unauthorized", true) -> {
                                    GstLog.e(TAG, "Auth mismatch (diagnostic JSON)")
                                    return@withContext Report(
                                        Result.AUTH_MISMATCH,
                                        "❌ کلید Auth با اسکریپت Deploy‌شده مطابقت ندارد. " +
                                            "اسکریپت را با کلید فعلی دوباره Deploy کنید.",
                                        code, preview
                                    )
                                }
                                else -> {
                                    val e = json.optString("e", "unknown")
                                    GstLog.e(TAG, "Relay returned error envelope: $e")
                                    return@withContext Report(
                                        Result.BAD_RESPONSE,
                                        "❌ رله خطا برگرداند: $e",
                                        code, preview
                                    )
                                }
                            }
                        }
                    }

                    // 2. Decoy HTML � AUTH_KEY mismatch in production (DIAGNOSTIC_MODE=false),
                    //    or the default Apps Script error page.
                    if (body.contains("<title>Web App</title>", true) ||
                        body.contains("did not return anything", true)
                    ) {
                        GstLog.e(TAG, "Decoy/placeholder HTML � auth mismatch or wrong deployment")
                        return@withContext Report(
                            Result.AUTH_MISMATCH,
                            "❌ اسکریپت صفحه‌ی جایگزین (decoy) برگرداند � یعنی کلید Auth اشتباه است " +
                                "یا Deployment ID درست نیست. اسکریپت را با کلید فعلی دوباره Deploy کنید.",
                            code, preview
                        )
                    }

                    // 3a. Google Docs/Drive authorization page (docs.google.com, "پردازش
                    //     کلمه وب") � an API-deployed web app that the deploying user has
                    //     not authorized yet. THE most common cause after auto-deploy.
                    if (code == 403 && (
                            body.contains("docs.google.com", true) ||
                            body.contains("پردازش کلمه", true) ||
                            body.contains("userscripts", true))
                    ) {
                        GstLog.e(TAG, "Script not authorized � needs one-time Review Permissions")
                        return@withContext Report(
                            Result.REDIRECT_BLOCKED,
                            "❌ اسکریپت هنوز تأیید (Authorize) نشده. این لینک را در مرورگر باز کنید و " +
                                "Review Permissions → Advanced → Allow را بزنید:\n$endpointUrl",
                            code, preview
                        )
                    }

                    // 3b. Google sign-in / consent HTML � deployment not "Anyone".
                    if (body.contains("accounts.google.com", true) ||
                        finalUrl.contains("accounts.google.com", true)
                    ) {
                        GstLog.e(TAG, "Redirected to Google login � access is not 'Anyone'")
                        return@withContext Report(
                            Result.REDIRECT_BLOCKED,
                            "❌ اسکریپت به صفحه‌ی ورود گوگل هدایت شد. هنگام Deploy باید " +
                                "�Who has access: Anyone� و �Execute as: Me� باشد.",
                            code, preview
                        )
                    }

                    GstLog.e(TAG, "Unrecognized response (HTTP $code)")
                    Report(
                        Result.BAD_RESPONSE,
                        "❌ پاسخ ناشناخته از رله (HTTP $code). جزئیات در لاگ.",
                        code, preview
                    )
                }
            } catch (e: Exception) {
                GstLog.e(TAG, "Preflight failed: ${e.message}")
                Report(
                    Result.UNREACHABLE,
                    "❌ رله در دسترس نیست: ${e.message}",
                    0, ""
                )
            }
        }
}
