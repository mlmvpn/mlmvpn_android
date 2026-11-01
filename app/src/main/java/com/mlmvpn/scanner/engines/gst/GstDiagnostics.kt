package com.mlmvpn.scanner.engines.gst

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

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

    enum class Result { OK, AUTH_MISMATCH, REDIRECT_BLOCKED, UNREACHABLE, BAD_RESPONSE, NOT_FOUND, FRONT_BLOCKED, KEY_NOT_USED }

    data class Report(
        val result: Result,
        /** Human-readable Persian diagnosis for the UI. */
        val message: String,
        val httpCode: Int = 0,
        val rawPreview: String = ""
    )

    /** Build the /exec URL for a raw Apps Script deployment id. */
    fun execUrl(deploymentId: String): String =
        "https://script.google.com/macros/s/${GstConfigManager.normalizeDeploymentId(deploymentId)}/exec"

    /**
     * The panel's full verdict for one saved relay: the relay itself, then the two things the
     * tunnel adds on top of it. A healthy relay whose password is not the tunnel's is never
     * used, and a healthy relay behind a closed Google front moves nothing; both used to show
     * as "healthy" and leave the user with a tunnel that connects and carries no data.
     */
    suspend fun testForTunnel(context: android.content.Context, relay: GstRelay): Report {
        val report = testDeployment(execUrl(relay.deploymentId), relay.authKey)
        if (report.result != Result.OK) return report
        val tunnelKey = GstConfigManager.tunnelAuthKey(GstConfigManager.getRelays(context))
        if (tunnelKey != null && tunnelKey != relay.authKey) {
            GstLog.w(TAG, "Relay healthy but its password is not the tunnel's - it is left out")
            return report.copy(result = Result.KEY_NOT_USED, message = S(R.string.gst_relay_key_not_used))
        }
        val front = GstFront.pick(context)
        if (front == null) {
            GstLog.e(TAG, "Relay healthy but no Google front answers on this network")
            return report.copy(result = Result.FRONT_BLOCKED, message = S(R.string.gst_front_blocked))
        }
        GstLog.i(TAG, "Tunnel front OK: ${front.ip} / ${front.sni} (${front.ms} ms)")
        return report
    }

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
                                        S(R.string.the_relay_is_healthy_destination_replied_code, relayedStatus),
                                        code, preview
                                    )
                                }
                                json.optString("e").contains("unauthorized", true) -> {
                                    GstLog.e(TAG, "Auth mismatch (diagnostic JSON)")
                                    return@withContext Report(
                                        Result.AUTH_MISMATCH,
                                        S(R.string.the_auth_key_does_not_match_the) +
                                            S(R.string.deploy_the_script_again_with_the_current),
                                        code, preview
                                    )
                                }
                                json.optString("e").contains("too many times", true) -> {
                                    GstLog.e(TAG, "Apps Script daily quota used up: ${json.optString("e")}")
                                    return@withContext Report(
                                        Result.BAD_RESPONSE,
                                        S(R.string.gst_relay_quota_used_up),
                                        code, preview
                                    )
                                }
                                else -> {
                                    val e = json.optString("e", "unknown")
                                    GstLog.e(TAG, "Relay returned error envelope: $e")
                                    return@withContext Report(
                                        Result.BAD_RESPONSE,
                                        S(R.string.the_relay_returned_an_error_e, e),
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
                            S(R.string.the_script_returned_the_decoy_page_which) +
                                S(R.string.or_the_deployment_id_is_not_right),
                            code, preview
                        )
                    }

                    // 3a. Google Docs/Drive authorization page (docs.google.com, "پردازش
                    //     کلمه وب") � an API-deployed web app that the deploying user has
                    //     not authorized yet. THE most common cause after auto-deploy.
                    if (code == 403 && (
                            body.contains("docs.google.com", true) ||
                            body.contains(S(R.string.word_processing), true) ||
                            body.contains("userscripts", true))
                    ) {
                        GstLog.e(TAG, "Script not authorized � needs one-time Review Permissions")
                        return@withContext Report(
                            Result.REDIRECT_BLOCKED,
                            S(R.string.the_script_has_not_been_authorised_yet) +
                                S(R.string.review_permissions_advanced_allow_n_endpointurl, endpointUrl),
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
                            S(R.string.the_script_redirected_to_google_s_sign) +
                                S(R.string.who_has_access_anyone_and_execute_as),
                            code, preview
                        )
                    }

                    // 3c. Google has no web app under this id: a Script ID or a test (/dev)
                    //     id pasted instead of the Deployment ID, or a deployment that was
                    //     archived/deleted.
                    if (code == 404) {
                        GstLog.e(TAG, "404 - no web app under this deployment id")
                        return@withContext Report(
                            Result.NOT_FOUND,
                            S(R.string.gst_relay_not_found),
                            code, preview
                        )
                    }

                    GstLog.e(TAG, "Unrecognized response (HTTP $code)")
                    Report(
                        Result.BAD_RESPONSE,
                        S(R.string.unknown_response_from_the_relay_http_code, code),
                        code, preview
                    )
                }
            } catch (e: Exception) {
                GstLog.e(TAG, "Preflight failed: ${e.message}")
                Report(
                    Result.UNREACHABLE,
                    S(R.string.the_relay_is_unreachable, e.message),
                    0, ""
                )
            }
        }
}
