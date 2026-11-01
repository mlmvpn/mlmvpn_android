package com.mlmvpn.scanner.engines.github

import android.util.Base64
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub, for GitHub Tunnel — the same calls the Windows app makes (github-tunnel/gt-github.js).
 *
 * The only thing the user does with GitHub directly is type a short code into github.com; the
 * private repository, the workflow and its agent, the dispatch and the watching all happen here.
 * Scopes `repo workflow` are required (a private repo; files under .github/workflows); `user` is
 * asked for too, only so the real Actions allowance can be read — everything works without it.
 *
 * Every call takes its token explicitly: with a pool of accounts there must be no ambient
 * credential to get wrong (a cancel with the wrong account's token 404s and the runner keeps
 * spending the right account's minutes).
 */
object GtGithub {
    /** The same OAuth app as the Windows client — device flow enabled. */
    const val CLIENT_ID = "Ov23liWP55HtVHdmfZ9P"
    const val SCOPE = "repo workflow user"
    const val REPO_NAME = "mlmvpn-cloud-tunnel"
    private const val API = "https://api.github.com"

    private val JSON_TYPE = "application/json".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        // The broker is a Worker: its name gets the checked, healthy-first answer (IPv6 edge when
        // IPv4 is silent). GitHub's own hosts resolve exactly as before.
        .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns())
        .protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** GitHub's clock minus ours, from every response's Date header: session deadlines are GitHub time. */
    @Volatile var clockOffsetMs = 0L
    fun serverNow(): Long = System.currentTimeMillis() + clockOffsetMs

    class GhError(message: String, val status: Int, val code: String = "") : Exception(message)

    private fun call(token: String, method: String, endpoint: String, body: JSONObject? = null): Pair<Int, String> {
        val req = Request.Builder()
            .url("$API$endpoint")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MLM-VPN-App")
            .method(method, when {
                body != null -> body.toString().toRequestBody(JSON_TYPE)
                method == "POST" || method == "PUT" -> "".toRequestBody(null)
                else -> null
            })
            .build()
        http.newCall(req).execute().use { res ->
            res.header("date")?.let { d ->
                try { clockOffsetMs = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(d)!!.time - System.currentTimeMillis() } catch (_: Exception) {}
            }
            return res.code to (res.body?.string().orEmpty())
        }
    }

    /** JSON in, JSON out; a non-2xx becomes a [GhError] carrying GitHub's own message. */
    fun gh(token: String, method: String, endpoint: String, body: JSONObject? = null): JSONObject {
        val (code, text) = call(token, method, endpoint, body)
        val data = try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (_: Exception) { JSONObject() }
        if (code !in 200..299) throw GhError(data.optString("message").ifBlank { "GitHub API error ($code)" }, code)
        return data
    }

    // ── sign-in (device flow) ─────────────────────────────────────────────────────
    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val interval: Int, val expiresAt: Long)

    fun startDeviceFlow(): DeviceCode {
        val req = Request.Builder().url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .post(FormBody.Builder().add("client_id", CLIENT_ID).add("scope", SCOPE).build())
            .build()
        http.newCall(req).execute().use { res ->
            val d = try { JSONObject(res.body?.string().orEmpty()) } catch (_: Exception) { JSONObject() }
            val code = d.optString("device_code")
            if (code.isBlank()) throw GhError(d.optString("error_description").ifBlank { "Could not start GitHub sign-in." }, res.code)
            return DeviceCode(code, d.optString("user_code"), d.optString("verification_uri", "https://github.com/login/device"),
                maxOf(5, d.optInt("interval", 5)), System.currentTimeMillis() + d.optLong("expires_in", 900) * 1000)
        }
    }

    /**
     * Poll until the user approves (the token), refuses, or the code expires (a [GhError]).
     * `stillWanted` lets the caller cancel between polls.
     */
    suspend fun awaitDeviceToken(dc: DeviceCode, stillWanted: () -> Boolean): Pair<String, String> {
        var interval = dc.interval
        while (stillWanted()) {
            delay(interval * 1000L)
            if (!stillWanted()) break
            if (System.currentTimeMillis() > dc.expiresAt) throw GhError("expired", 0, "EXPIRED")
            val d = try {
                val req = Request.Builder().url("https://github.com/login/oauth/access_token")
                    .header("Accept", "application/json")
                    .post(FormBody.Builder().add("client_id", CLIENT_ID).add("device_code", dc.deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build())
                    .build()
                http.newCall(req).execute().use { JSONObject(it.body?.string().orEmpty()) }
            } catch (_: Exception) { continue }
            val token = d.optString("access_token")
            if (token.isNotBlank()) return token to d.optString("scope", SCOPE)
            when (d.optString("error")) {
                "authorization_pending" -> continue
                "slow_down" -> { interval = maxOf(interval + 5, d.optInt("interval", 10)); continue }
                "access_denied" -> throw GhError("denied", 0, "DENIED")
                else -> throw GhError(d.optString("error_description").ifBlank { d.optString("error", "Sign-in failed.") }, 0, "FAILED")
            }
        }
        throw GhError("cancelled", 0, "CANCELLED")
    }

    data class Identity(val login: String, val name: String, val avatarUrl: String, val scopes: String)

    /** Who a token belongs to, and what it may actually do (the consent screen can withhold scopes). */
    fun identify(token: String): Identity {
        val req = Request.Builder().url("$API/user")
            .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "MLM-VPN-App").build()
        http.newCall(req).execute().use { res ->
            val d = try { JSONObject(res.body?.string().orEmpty()) } catch (_: Exception) { JSONObject() }
            if (!res.isSuccessful) throw GhError(d.optString("message").ifBlank { "GitHub API error (${res.code})" }, res.code)
            val login = d.optString("login")
            return Identity(login, d.optString("name").ifBlank { login }, d.optString("avatar_url"), res.header("x-oauth-scopes").orEmpty())
        }
    }

    // ── the repository ──────────────────────────────────────────────────────────
    data class Repo(val fullName: String, val owner: String, val defaultBranch: String)

    fun ensureRepo(token: String): Repo {
        val me = gh(token, "GET", "/user").optString("login")
        val repo = try {
            gh(token, "GET", "/repos/$me/$REPO_NAME")
        } catch (e: GhError) {
            if (e.status != 404) throw e
            gh(token, "POST", "/user/repos", JSONObject()
                .put("name", REPO_NAME).put("private", true).put("auto_init", true)
                .put("description", "MLMVPN GitHub Tunnel — auto-managed, disposable cloud sessions. Safe to delete; it will be recreated automatically."))
        }
        return Repo(repo.optString("full_name"), me, repo.optString("default_branch", "main"))
    }

    /** Put a file only if its content differs — an unchanged one costs a single GET. */
    fun ensureFile(token: String, repo: String, path: String, content: String, what: String): Boolean {
        var sha: String? = null
        try {
            val existing = gh(token, "GET", "/repos/$repo/contents/$path")
            sha = existing.optString("sha")
            val current = String(Base64.decode(existing.optString("content"), Base64.DEFAULT), Charsets.UTF_8)
            if (current == content) return false
        } catch (e: GhError) {
            if (e.status != 404) throw e
        }
        gh(token, "PUT", "/repos/$repo/contents/$path", JSONObject()
            .put("message", if (sha != null) "MLMVPN: update $what" else "MLMVPN: add $what")
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .apply { if (sha != null) put("sha", sha) })
        return true
    }

    // ── runs ────────────────────────────────────────────────────────────────────
    /**
     * Trigger the workflow and return the run it created. workflow_dispatch returns no id, so the
     * run is found afterwards as the first one with an id above the newest that existed before —
     * GitHub's own ordering, no clock involved (a phone's clock is often off).
     */
    suspend fun dispatch(token: String, repo: String, ref: String, workflowFile: String, inputs: JSONObject): Long {
        val list = "/repos/$repo/actions/workflows/$workflowFile/runs?event=workflow_dispatch&per_page=10"
        var floor = 0L
        try {
            val prior = gh(token, "GET", list).optJSONArray("workflow_runs")
            for (i in 0 until (prior?.length() ?: 0)) floor = maxOf(floor, prior!!.getJSONObject(i).optLong("id"))
        } catch (e: GhError) {
            if (e.status != 404) throw e
        }
        gh(token, "POST", "/repos/$repo/actions/workflows/$workflowFile/dispatches", JSONObject().put("ref", ref).put("inputs", inputs))
        repeat(12) {
            delay(1500)
            val runs = try { gh(token, "GET", list).optJSONArray("workflow_runs") } catch (_: Exception) { null } ?: return@repeat
            val ids = (0 until runs.length()).map { runs.getJSONObject(it).optLong("id") }.filter { it > floor }.sorted()
            if (ids.isNotEmpty()) return ids.first()
        }
        throw GhError("The cloud session did not start in time.", 0, "DISPATCH_TIMEOUT")
    }

    fun getRun(token: String, repo: String, runId: Long): JSONObject = gh(token, "GET", "/repos/$repo/actions/runs/$runId")

    fun cancelRun(token: String, repo: String, runId: Long) {
        try { call(token, "POST", "/repos/$repo/actions/runs/$runId/cancel") } catch (_: Exception) {}
    }

    /** The runner's sealed hand-off, `sessions/<id>.json`, or null while it is not there yet. */
    fun sessionFile(token: String, repo: String, sessionId: String): JSONObject? = try {
        val f = gh(token, "GET", "/repos/$repo/contents/sessions/$sessionId.json")
        JSONObject(String(Base64.decode(f.optString("content"), Base64.DEFAULT), Charsets.UTF_8))
    } catch (e: GhError) {
        if (e.status == 404) null else throw e
    }

    fun deleteSessionFile(token: String, repo: String, sessionId: String) {
        try {
            val f = gh(token, "GET", "/repos/$repo/contents/sessions/$sessionId.json")
            gh(token, "DELETE", "/repos/$repo/contents/sessions/$sessionId.json",
                JSONObject().put("message", "MLMVPN: clear session data").put("sha", f.optString("sha")))
        } catch (_: Exception) {}
    }

    /** The real Actions allowance, or null when the token cannot see it (no `user` scope). */
    fun billing(token: String, login: String): Pair<Int, Int>? = try {
        val d = gh(token, "GET", "/users/$login/settings/billing/actions")
        val inc = d.optDouble("included_minutes", Double.NaN)
        val used = d.optDouble("total_minutes_used", Double.NaN)
        if (inc.isNaN() || used.isNaN()) null else inc.toInt() to used.toInt()
    } catch (_: Exception) { null }
}
