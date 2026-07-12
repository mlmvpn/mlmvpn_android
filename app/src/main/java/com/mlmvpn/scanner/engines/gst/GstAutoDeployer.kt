package com.mlmvpn.scanner.engines.gst

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fully-automatic Google Apps Script relay deployment via the Apps Script REST API.
 *
 * Replaces the manual "copy the script → paste at script.google.com → Deploy → copy the
 * Deployment ID back" flow. Given an OAuth access token (from
 * [com.mlmvpn.scanner.auth.GoogleAuthManager]) it:
 *   1. creates a new standalone script project,
 *   2. pushes the manifest (web app: execute-as-me, access-anyone) + Code.gs (with the
 *      user's auth key injected),
 *   3. cuts a version,
 *   4. creates a web-app deployment,
 * then persists the resulting deployment id into [GstConfigManager] so the tunnel can
 * use it immediately.
 */
object GstAutoDeployer {
    private const val TAG = "GstAutoDeployer"
    private const val API = "https://script.googleapis.com/v1"

    private val JSON = "application/json".toMediaTypeOrNull()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class DeployResult(
        val success: Boolean,
        val message: String,
        val deploymentId: String? = null,
        /** True when the failure is specifically "Apps Script API not enabled for this user". */
        val needsApiEnable: Boolean = false,
        /** True when the network's IP is geo/sanctions-blocked from Google Cloud APIs. */
        val geoBlocked: Boolean = false
    )

    /**
     * @param accessToken OAuth2 bearer token with script.projects/deployments scopes.
     * @param authKey the shared relay secret to bake into the deployed Code.gs.
     * @param relayUrl optional Cloudflare Worker relay (gst_relay_worker.js) to tunnel the
     *   script.googleapis.com calls through. Required for users on networks where Google
     *   Cloud APIs are geo/sanctions-blocked (e.g. Iran): the worker runs on CF's edge so
     *   Google sees a non-blocked IP. When null, calls go direct.
     * @param relayAuthKey the `k` secret expected by the relay worker (usually == authKey).
     * @param onProgress (0..100, label) for the UI.
     */
    suspend fun deploy(
        context: Context,
        accessToken: String,
        authKey: String,
        relayUrl: String? = null,
        relayAuthKey: String? = null,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): DeployResult = withContext(Dispatchers.IO) {
        if (!relayUrl.isNullOrEmpty()) {
            GstLog.i(TAG, "Routing deploy through Cloudflare relay: $relayUrl")
        } else {
            GstLog.i(TAG, "Deploying directly (no CF relay) � may fail on geo-blocked networks.")
        }
        try {
            // 0. Read the script source and inject the auth key (same as the manual dialog).
            onProgress(5, "آماده‌سازی اسکریپت...")
            val codeSource = try {
                context.assets.open("gst/Code.gs").bufferedReader().use { it.readText() }
                    .replace("CHANGE_ME_TO_A_STRONG_SECRET", authKey)
            } catch (e: Exception) {
                return@withContext DeployResult(false, "خطا در خواندن اسکریپت: ${e.message}")
            }

            // Diagnostic: log the scopes actually granted to this token + its project.
            // A generic HTML 403 from script.googleapis.com is ambiguous (missing scope
            // vs. API not enabled vs. wrong account); the tokeninfo endpoint disambiguates.
            logTokenInfo(accessToken)

            // 1. Create the project.
            onProgress(20, "ساخت پروژه گوگل اسکریپت...")
            val title = "sys-svc-${System.currentTimeMillis().toString(36)}"
            val createBody = JSONObject().put("title", title).toString()
            val scriptId = apiCall("POST", "$API/projects", accessToken, createBody, relayUrl, relayAuthKey).let { resp ->
                if (!resp.ok) return@withContext resp.toDeployError()
                JSONObject(resp.body).optString("scriptId", "")
            }
            if (scriptId.isEmpty()) {
                return@withContext DeployResult(false, "شناسه‌ی پروژه دریافت نشد")
            }
            GstLog.i(TAG, "Project created: ${GstLog.redact(scriptId)}")

            // 2. Push content (manifest + Code.gs).
            onProgress(45, "آپلود کد...")
            val manifest = JSONObject().apply {
                put("timeZone", "Etc/GMT")
                put("exceptionLogging", "STACKDRIVER")
                put("runtimeVersion", "V8")
                put("oauthScopes", JSONArray().put("https://www.googleapis.com/auth/script.external_request"))
                put("webapp", JSONObject().apply {
                    put("executeAs", "USER_DEPLOYING")
                    put("access", "ANYONE_ANONYMOUS")
                })
            }.toString()

            val contentBody = JSONObject().put("files", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "appsscript"); put("type", "JSON"); put("source", manifest)
                })
                put(JSONObject().apply {
                    put("name", "Code"); put("type", "SERVER_JS"); put("source", codeSource)
                })
            }).toString()

            apiCall("PUT", "$API/projects/$scriptId/content", accessToken, contentBody, relayUrl, relayAuthKey).let { resp ->
                if (!resp.ok) return@withContext resp.toDeployError()
            }
            GstLog.i(TAG, "Content pushed")

            // 3. Create a version.
            onProgress(70, "ساخت نسخه...")
            val versionBody = JSONObject().put("description", "auto").toString()
            val versionNumber = apiCall("POST", "$API/projects/$scriptId/versions", accessToken, versionBody, relayUrl, relayAuthKey).let { resp ->
                if (!resp.ok) return@withContext resp.toDeployError()
                JSONObject(resp.body).optInt("versionNumber", 1)
            }
            GstLog.i(TAG, "Version created: $versionNumber")

            // 4. Create the web-app deployment.
            onProgress(90, "استقرار وب‌اپ...")
            val deployBody = JSONObject().apply {
                put("versionNumber", versionNumber)
                put("manifestFileName", "appsscript")
                put("description", "relay")
            }.toString()
            val deploymentId = apiCall("POST", "$API/projects/$scriptId/deployments", accessToken, deployBody, relayUrl, relayAuthKey).let { resp ->
                if (!resp.ok) return@withContext resp.toDeployError()
                JSONObject(resp.body).optString("deploymentId", "")
            }
            if (deploymentId.isEmpty()) {
                return@withContext DeployResult(false, "شناسه‌ی استقرار (Deployment ID) دریافت نشد")
            }
            GstLog.i(TAG, "Deployment created: ${GstLog.redact(deploymentId)}")

            // 5. Persist into GST relays (dedupe by deploymentId).
            val existing = GstConfigManager.getRelays(context).toMutableList()
            if (existing.none { it.deploymentId == deploymentId }) {
                // Prefer filling the first EMPTY relay card (the one auto-created on screen
                // open) so the deployed relay shows in place at the top � otherwise the
                // user sees a blank first card and thinks nothing happened. Fall back to
                // inserting at the top rather than appending at the bottom.
                val emptyIdx = existing.indexOfFirst { it.deploymentId.isBlank() }
                if (emptyIdx >= 0) {
                    existing[emptyIdx] = existing[emptyIdx].copy(
                        deploymentId = deploymentId,
                        authKey = authKey
                    )
                } else {
                    existing.add(0, GstRelay(deploymentId = deploymentId, authKey = authKey))
                }
                GstConfigManager.saveRelays(context, existing)
            }

            onProgress(100, "انجام شد!")
            DeployResult(true, "استقرار خودکار موفق بود ✅", deploymentId)
        } catch (e: Exception) {
            GstLog.e(TAG, "Deploy failed: ${e.message}")
            DeployResult(false, "خطا: ${e.message}")
        }
    }

    // ── HTTP helpers ────────────────────────────────────────────

    private data class Resp(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    private fun Resp.toDeployError(): DeployResult {
        GstLog.e(TAG, "API error HTTP $code: ${body.take(800)}")
        // Distinguish two very different 403s:
        //  - HTML "Error 403 (Forbidden)" robot page → the request was rejected at
        //    Google's edge, i.e. the network's IP is geo/sanctions-blocked from Google
        //    Cloud APIs (e.g. Iran). Fix = route through the Cloudflare relay worker.
        //  - JSON 403 → the Apps Script API is not enabled for the user/project.
        val isHtml = body.contains("<html", true) || body.contains("Error 403 (Forbidden)")
        return when {
            code == 403 && isHtml -> {
                GstLog.e(TAG, "403 HTML edge block → likely geo/sanctions block on Google Cloud APIs")
                DeployResult(
                    false,
                    "دسترسی به سرویس گوگل از شبکه‌ی شما مسدود است (تحریم/جغرافیایی). برای حل، سوییچ " +
                        "«شتاب Cloudflare� را روشن کنید تا استقرار از طریق کلادفلر انجام شود، سپس دوباره �Deploy� را بزنید.",
                    geoBlocked = true
                )
            }
            code == 403 -> DeployResult(
                false,
                "خطای ۴۰۳: Apps Script API فعال نیست. با همان حساب گوگلی که لاگین کردید، آن را در " +
                    "script.google.com/home/usersettings روشن کنید. سپس ۱–۲ دقیقه صبر و دوباره تلاش کنید.",
                needsApiEnable = true
            )
            code == 401 -> DeployResult(false, "توکن نامعتبر شد؛ دوباره وارد شوید.")
            body.contains("1042", true) -> DeployResult(
                false,
                "پروکسی Cloudflare نتوانست به گوگل وصل شود (خطای 1042). سوییچ «شتاب Cloudflare� را یک‌بار " +
                    "خاموش و روشن کنید تا Worker جدید با تنظیمات درست ساخته شود، سپس دوباره Deploy بزنید. " +
                    "اگر باز نشد، به‌جای CF یک VPN روشن کنید و بدون CF مستقیم Deploy کنید."
            )
            code == 502 && (body.contains("did not return anything", true) || body.contains("<title>Web App</title>", true)) ->
                DeployResult(
                    false,
                    "پروکسی Cloudflare درخواست را رد کرد (کلید Worker قدیمی است). سوییچ «شتاب Cloudflare� را " +
                        "یک‌بار خاموش و دوباره روشن کنید تا Worker با کلید جدید ساخته شود، سپس دوباره Deploy بزنید."
                )
            else -> DeployResult(false, "خطای گوگل (HTTP $code): ${body.take(150)}")
        }
    }

    /** Logs the granted scopes + issued client/project of an access token (best-effort). */
    private fun logTokenInfo(token: String) {
        try {
            val encoded = java.net.URLEncoder.encode(token, "UTF-8")
            val req = Request.Builder()
                .url("https://www.googleapis.com/oauth2/v3/tokeninfo?access_token=$encoded")
                .get().build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    val json = JSONObject(body)
                    val scope = json.optString("scope", "<none>")
                    val aud = json.optString("aud", "<none>")
                    val email = json.optString("email", "<none>")
                    val hasProjects = scope.contains("script.projects")
                    GstLog.i(TAG, "Token account=$email")
                    GstLog.i(TAG, "Token client(aud)=$aud")
                    GstLog.i(TAG, "Token scopes=$scope")
                    if (hasProjects) {
                        GstLog.i(TAG, "✅ script.projects scope IS granted → 403 means API not enabled in the project/user settings.")
                    } else {
                        GstLog.e(TAG, "❌ script.projects scope NOT granted → consent/scope problem, not an API-enable problem.")
                    }
                } else {
                    GstLog.w(TAG, "tokeninfo HTTP ${res.code}: ${body.take(200)}")
                }
            }
        } catch (e: Exception) {
            GstLog.w(TAG, "tokeninfo failed: ${e.message}")
        }
    }

    /**
     * Performs a Google API request either directly or tunneled through a Cloudflare
     * Worker relay (gst_relay_worker.js) when [relayUrl] is set.
     */
    private fun apiCall(
        method: String,
        url: String,
        token: String,
        body: String,
        relayUrl: String?,
        relayAuthKey: String?
    ): Resp {
        return if (relayUrl.isNullOrEmpty()) {
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
            when (method) {
                "PUT" -> builder.put(body.toRequestBody(JSON))
                else -> builder.post(body.toRequestBody(JSON))
            }
            call(builder.build())
        } else {
            relayCall(method, url, token, body, relayUrl, relayAuthKey ?: "")
        }
    }

    /** Wraps the request in the relay protocol and sends it to the CF worker. */
    private fun relayCall(
        method: String,
        url: String,
        token: String,
        body: String,
        relayUrl: String,
        relayAuthKey: String
    ): Resp {
        val b64 = android.util.Base64.encodeToString(
            body.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val payload = JSONObject().apply {
            put("k", relayAuthKey)
            put("m", method)
            put("u", url)
            put("h", JSONObject().apply {
                put("Authorization", "Bearer $token")
                put("Content-Type", "application/json")
            })
            put("b", b64)
            put("ct", "application/json")
            put("r", true)
        }.toString()

        val req = Request.Builder().url(relayUrl)
            .post(payload.toRequestBody(JSON)).build()

        client.newCall(req).execute().use { res ->
            val relayBody = res.body?.string() ?: ""
            if (!res.isSuccessful) {
                GstLog.e(TAG, "Relay transport error HTTP ${res.code}: ${relayBody.take(200)}")
                return Resp(res.code, relayBody)
            }
            val json = try { JSONObject(relayBody) } catch (e: Exception) {
                GstLog.e(TAG, "Relay returned non-JSON: ${relayBody.take(200)}")
                return Resp(502, relayBody)
            }
            if (json.has("e")) {
                GstLog.e(TAG, "Relay error: ${json.optString("e")}")
                return Resp(502, json.optString("e"))
            }
            val status = json.optInt("s", 502)
            val innerB64 = json.optString("b", "")
            val decoded = if (innerB64.isNotEmpty()) {
                try {
                    String(android.util.Base64.decode(innerB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) { "" }
            } else ""
            return Resp(status, decoded)
        }
    }

    private fun call(req: Request): Resp = client.newCall(req).execute().use { res ->
        Resp(res.code, res.body?.string() ?: "")
    }
}
