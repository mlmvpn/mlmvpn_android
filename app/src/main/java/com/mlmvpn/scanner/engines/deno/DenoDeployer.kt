package com.mlmvpn.scanner.engines.deno

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fully-automated VLESS-over-WS backend deployment to Deno Deploy (v2 / deno.net).
 *
 * Targets the NEW Deno Deploy platform (api.deno.com/v2). The old classic
 * platform + v1 subhosting API shut down 2026-07-20, so we do NOT use it.
 *
 * Flow (token is organization-scoped, so no org id is needed in the path):
 *   1. POST /v2/apps              -> create app (random slug)
 *   2. POST /v2/apps/{app}/deploy -> upload deno_vless.ts as a revision,
 *                                    inject per-user UUID + WS path as env vars
 *   3. GET  /v2/revisions/{id}    -> poll until "succeeded", read live hostname
 *
 * User only supplies an ORGANIZATION access token (starts with `ddo_`),
 * created in the Deno Deploy dashboard under Settings > Access Tokens.
 */
class DenoDeployer(private val context: Context) {

    companion object {
        // Filter logcat with:  adb logcat -s DenoDeploy
        const val TAG = "DenoDeploy"
        private const val API = "https://api.deno.com/v2"
        private const val ASSET_NAME = "deno_vless.ts"
    }

    // Built per-deploy based on VPN state (see DenoHttp.buildClient).
    private lateinit var client: OkHttpClient

    data class DeployResult(
        val success: Boolean,
        val message: String,
        val host: String? = null,      // e.g. my-app.my-org.deno.net
        val uuid: String? = null,
        val wsPath: String? = null,
        val vlessLink: String? = null,
        val appId: String? = null,
        val appSlug: String? = null
    )

    private fun log(msg: String) = Log.d(TAG, msg)

    /** Log an HTTP result compactly; truncates long bodies. */
    private fun logHttp(step: String, code: Int, body: String?) {
        val b = (body ?: "").let { if (it.length > 800) it.take(800) + "�(truncated)" else it }
        Log.d(TAG, "[$step] HTTP $code | $b")
    }

    private fun authHeaders(token: String) = DenoHttp.authHeaders(token)

    private val json = "application/json".toMediaTypeOrNull()

    /**
     * Runs the whole flow. onProgress(percent, statusText) drives the UI bar.
     * accountId ties the created deployment to a stored Deno account.
     */
    suspend fun deploy(
        accountId: String,
        token: String,
        onProgress: (Int, String) -> Unit
    ): DeployResult = withContext(Dispatchers.IO) {
        try {
            log("=== deploy start (v2) ===")
            val cleanToken = token.trim()

            // Friendly early check for the common mistake: classic ddp_ token.
            if (cleanToken.startsWith("ddp_")) {
                return@withContext DeployResult(
                    false,
                    "این توکن مربوط به Deno کلاسیک است (ddp_). یک Organization Access Token جدید " +
                        "با پیشوند ddo_ از داشبورد جدید Deno Deploy → Settings → Access Tokens بسازید."
                )
            }

            // Per-user secrets generated on-device.
            val uuid = java.util.UUID.randomUUID().toString()
            val wsPath = "/" + randomPathSegment() + "/" + randomPathSegment()
            val xhttpPath = "/" + randomPathSegment() + "/" + randomPathSegment()
            log("generated uuid=$uuid wsPath=$wsPath xhttpPath=$xhttpPath")

            // Build the HTTP client according to VPN state (proxy via tunnel
            // when a config is connected, else direct).
            client = DenoHttp.buildClient(context)
            log(if (com.mlmvpn.scanner.MyVpnService.isRunning) "VPN active -> via local proxy" else "VPN off -> direct")

            // --- 1. Validate token (lightweight list call) ---
            onProgress(10, "بررسی توکن…")
            val code = validateToken(cleanToken)
            if (code != 200) {
                val msg = when {
                    code == 403 && !com.mlmvpn.scanner.MyVpnService.isRunning ->
                        "دسترسی به Deno مسدود است (۴۰۳). api.deno.com در ایران بلاک است � " +
                            "اول یک کانفیگ کارآمد mlmvpn را روشن کن، سپس دوباره Deploy بزن."
                    code == 403 ->
                        "دسترسی مسدود شد (۴۰۳). سرور VPN فعلی نتوانست به api.deno.com برسد؛ " +
                            "یک کانفیگ دیگر را امتحان کن."
                    code == 401 ->
                        "توکن نامعتبر یا منقضی است. یک Organization Access Token جدید (ddo_) بساز."
                    else ->
                        "بررسی توکن ناموفق بود (HTTP $code)."
                }
                return@withContext DeployResult(false, msg)
            }

            // --- 2. Read the embedded VLESS script ---
            onProgress(25, "آماده‌سازی اسکریپت…")
            val script = try {
                context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                return@withContext DeployResult(false, "خطا در خواندن اسکریپت: ${e.message}")
            }

            // --- 3. Create the app ---
            onProgress(40, "ساخت اپ…")
            val slug = randomSlug()
            val app = createApp(cleanToken, slug)
                ?: return@withContext DeployResult(
                    false,
                    "ساخت اپ ناموفق بود. ممکن است slug تکراری باشد یا سقف حساب پر شده باشد."
                )
            log("app created id=${app.first} slug=${app.second}")

            // --- 3.5 Create a Deno KV database so the script can self-count
            //     usage (exact, real-time stats). Non-fatal if it fails �
            //     the proxy still works, only stats will be unavailable.
            onProgress(50, "ساخت دیتابیس آمار…")
            val dbId = createKvDatabase(cleanToken)
            log("kv database id=$dbId")

            // --- 4. Deploy a revision (code + env vars + KV binding) ---
            onProgress(60, "آپلود و استقرار…")
            val revisionId = deployRevision(cleanToken, app.second, script, uuid, wsPath, xhttpPath, dbId)
                ?: return@withContext DeployResult(false, "استقرار (revision) ناموفق بود.")

            // --- 5. Wait for the build and grab the live hostname ---
            onProgress(80, "در انتظار build�")
            val host = awaitRevisionHost(cleanToken, revisionId)
                ?: return@withContext DeployResult(
                    false,
                    "Build کامل نشد یا دامنه فعال نشد. لاگ‌ها را در داشبورد Deno بررسی کنید."
                )

            // --- 6. Build the client config ---
            onProgress(100, "آماده شد ✅")
            val vless = buildVlessLink(uuid, host, wsPath, app.second)

            // --- 7. Persist ---
            DenoManager(context).saveDeployment(
                accountId = accountId,
                token = cleanToken,
                projectId = app.first,
                projectName = app.second,
                host = host,
                uuid = uuid,
                wsPath = wsPath,
                vlessLink = vless,
                xhttpPath = xhttpPath
            )

            DeployResult(
                success = true,
                message = "استقرار موفق: https://$host",
                host = host,
                uuid = uuid,
                wsPath = wsPath,
                vlessLink = vless,
                appId = app.first,
                appSlug = app.second
            )
        } catch (e: Exception) {
            Log.e(TAG, "deploy failed", e)
            DeployResult(false, "خطا: ${e.message}")
        }
    }

    // ---- API STEPS ---------------------------------------------------------

    /** GET /v2/apps?limit=1 to confirm reachability + token. Returns HTTP code (-1 on error). */
    private fun validateToken(token: String): Int {
        return try {
            val req = Request.Builder()
                .url("$API/apps?limit=1")
                .headers(authHeaders(token))
                .get().build()
            client.newCall(req).execute().use { res ->
                logHttp("GET /apps (validate)", res.code, res.body?.string())
                res.code
            }
        } catch (e: Exception) {
            log("validate exception: ${e.message}")
            -1
        }
    }

    /** POST /v2/apps -> Pair(appId, appSlug). */
    private fun createApp(token: String, slug: String): Pair<String, String>? {
        val payload = JSONObject().apply {
            put("slug", slug)
            put("config", JSONObject().apply {
                put("runtime", JSONObject().apply {
                    put("type", "dynamic")
                    put("entrypoint", "main.ts")
                })
            })
        }
        val req = Request.Builder()
            .url("$API/apps")
            .headers(authHeaders(token))
            .post(payload.toString().toRequestBody(json))
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string()
            logHttp("POST /apps", res.code, body)
            if (!res.isSuccessful || body == null) return null
            val o = JSONObject(body)
            val id = o.optString("id")
            val realSlug = o.optString("slug", slug)
            return if (id.isNotEmpty()) Pair(id, realSlug) else null
        }
    }

    /**
     * POST /v2/apps/{app}/deploy -> revision id.
     * assets keys are paths under /app/src. UUID + WS path go in immutable
     * revision env vars so every user's backend has unique credentials.
     */
    private fun deployRevision(
        token: String,
        appSlug: String,
        script: String,
        uuid: String,
        wsPath: String,
        xhttpPath: String,
        dbId: String?
    ): String? {
        val assets = JSONObject().put(
            "main.ts",
            JSONObject()
                .put("kind", "file")
                .put("encoding", "utf-8")
                .put("content", script)
        )
        val envVars = JSONArray()
            .put(JSONObject().put("key", "VLESS_UUID").put("value", uuid))
            .put(JSONObject().put("key", "WS_PATH").put("value", wsPath))
            .put(JSONObject().put("key", "XHTTP_PATH").put("value", xhttpPath))

        // production=true unless we have a KV database to bind; then we pass an
        // explicit object with the database so Deno.openKv() works in the app.
        val production: Any = if (dbId != null) {
            JSONObject().put("databases", JSONArray().put(
                JSONObject().put("instance", dbId).put("name", "default")
            ))
        } else true

        val payload = JSONObject()
            .put("assets", assets)
            .put("config", JSONObject().put("runtime", JSONObject()
                .put("type", "dynamic").put("entrypoint", "main.ts")))
            .put("env_vars", envVars)
            .put("production", production)

        val req = Request.Builder()
            .url("$API/apps/$appSlug/deploy")
            .headers(authHeaders(token))
            .post(payload.toString().toRequestBody(json))
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string()
            logHttp("POST /apps/$appSlug/deploy", res.code, body)
            if (!res.isSuccessful || body == null) return null
            return JSONObject(body).optString("id").ifEmpty { null }
        }
    }

    /** POST /v2/database_instances (denokv) -> instance id, or null on failure. */
    private fun createKvDatabase(token: String): String? {
        val slug = "mlmkv-" + java.util.UUID.randomUUID().toString().take(8)
        val payload = JSONObject()
            .put("slug", slug)
            .put("connection", JSONObject().put("engine", "denokv"))
        val req = Request.Builder()
            .url("$API/database_instances")
            .headers(authHeaders(token))
            .post(payload.toString().toRequestBody(json))
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                val body = res.body?.string()
                logHttp("POST /database_instances", res.code, body)
                if (!res.isSuccessful || body == null) null
                else JSONObject(body).optString("id").ifEmpty { null }
            }
        } catch (e: Exception) {
            log("createKvDatabase error: ${e.message}")
            null
        }
    }

    /**
     * Poll GET /v2/revisions/{id} until status == succeeded, then return the
     * live hostname from timelines[].hostnames. Builds usually finish in
     * ~10-30s. Returns null on failure/timeout.
     */
    private suspend fun awaitRevisionHost(token: String, revisionId: String): String? {
        repeat(30) { attempt -> // ~30 * 2s = 60s max
            val req = Request.Builder()
                .url("$API/revisions/$revisionId")
                .headers(authHeaders(token))
                .get().build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    val o = JSONObject(body)
                    val status = o.optString("status")
                    if (attempt % 3 == 0) log("revision status=$status (try ${attempt + 1})")
                    when (status) {
                        "succeeded" -> {
                            val host = firstHostname(o)
                            if (host != null) { log("live host=$host"); return host }
                        }
                        "failed", "skipped" -> {
                            Log.e(TAG, "revision $status: $body")
                            return null
                        }
                    }
                } else {
                    logHttp("GET /revisions/$revisionId", res.code, body)
                }
            }
            delay(2000)
        }
        log("revision poll timed out")
        return null
    }

    /** Extract the first production hostname from a revision object. */
    private fun firstHostname(revision: JSONObject): String? {
        val timelines = revision.optJSONArray("timelines") ?: return null
        // Prefer the "production" timeline, else any timeline with a hostname.
        for (i in 0 until timelines.length()) {
            val t = timelines.getJSONObject(i)
            if (t.optString("name") == "production") {
                t.optJSONArray("hostnames")?.let { if (it.length() > 0) return it.getString(0) }
            }
        }
        for (i in 0 until timelines.length()) {
            timelines.getJSONObject(i).optJSONArray("hostnames")?.let {
                if (it.length() > 0) return it.getString(0)
            }
        }
        return null
    }

    // ---- CONFIG / HELPERS --------------------------------------------------

    /** VLESS over WS + TLS on 443, pointing at the Deno hostname. */
    private fun buildVlessLink(
        uuid: String,
        host: String,
        wsPath: String,
        tag: String
    ): String {
        val encPath = java.net.URLEncoder.encode(wsPath, "UTF-8")
        val remark = java.net.URLEncoder.encode("mlmvpn-$tag", "UTF-8")
        return "vless://$uuid@$host:443" +
            "?encryption=none&security=tls&sni=$host&fp=chrome" +
            "&type=ws&host=$host&path=$encPath#$remark"
    }

    // Harmless-looking words for slug/path camouflage.
    private val nouns = listOf(
        "studio", "labs", "cloud", "portal", "hub", "works", "space",
        "market", "media", "digital", "systems", "group", "team"
    )

    /**
     * Deno slug rules: 3-32 chars, lowercase letters/numbers/hyphens, no
     * underscores, no leading/trailing hyphen, no consecutive hyphens in
     * positions 3 and 4. "studio-1234" satisfies all of these.
     */
    private fun randomSlug(): String {
        val a = nouns.random()
        val n = (1000..9999).random()
        return "$a-$n"
    }

    private fun randomPathSegment(): String {
        val words = listOf("api", "v1", "stream", "assets", "static", "cdn", "sync", "data", "live", "media")
        return words.random()
    }
}
