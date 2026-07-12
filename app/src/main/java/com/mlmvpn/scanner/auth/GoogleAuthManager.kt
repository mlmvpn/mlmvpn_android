package com.mlmvpn.scanner.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.mlmvpn.scanner.engines.gst.GstLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Handles Google sign-in and minting an OAuth2 access token for the Apps Script REST
 * API, used by [com.mlmvpn.scanner.engines.gst.GstAutoDeployer] to create + deploy the
 * relay script automatically (so the user never copies/pastes it by hand).
 *
 * ── Prerequisites (must be provisioned by the app owner in Google Cloud) ──
 *   1. A GCP project with the "Apps Script API" enabled.
 *   2. An OAuth **Android** client (this app's package name + signing SHA-1) AND the
 *      OAuth consent screen listing the [SCOPES] below.
 *   3. Because `script.projects` is a *sensitive* scope, the consent screen must be
 *      verified by Google for public release; before verification only test users
 *      (max 100) can complete the flow.
 *   4. Each end-user must also enable the Apps Script API for their own account once at
 *      https://script.google.com/home/usersettings (no API can toggle this for them).
 *
 * We use GoogleSignIn + GoogleAuthUtil.getToken rather than Credential Manager because
 * we need a real OAuth *access token* for arbitrary sensitive scopes, not just an ID
 * token for authentication.
 */
object GoogleAuthManager {
    private const val TAG = "GoogleAuthManager"

    /** One-time API-enable page each user must visit. */
    const val USER_SETTINGS_URL = "https://script.google.com/home/usersettings"

    /**
     * Web OAuth client ID from the GCP project (Google Auth Platform → Clients).
     * Not required by the GoogleAuthUtil access-token flow below � Google matches the
     * app by package name + signing SHA-1 against the registered *Android* client � but
     * kept here for a future ID-token / server-auth-code path (Credential Manager).
     */
    const val WEB_CLIENT_ID = "948923635824-s94l0gkfobp6p32egjgckkafij1p1ra3.apps.googleusercontent.com"

    // Scopes required to create a project, push its content, and deploy a web app.
    // `script.external_request` is the script's own runtime scope (UrlFetchApp); we
    // request it here so the deploying user's consent covers the web app that runs as
    // them (executeAs USER_DEPLOYING) and it can fetch external URLs without a manual
    // authorization pass.
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/script.projects",
        "https://www.googleapis.com/auth/script.deployments",
        "https://www.googleapis.com/auth/script.external_request"
    )
    private val OAUTH2_SCOPE_STRING = "oauth2:" + SCOPES.joinToString(" ")

    fun signInClient(context: Context): GoogleSignInClient {
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        SCOPES.forEach { gsoBuilder.requestScopes(Scope(it)) }
        return GoogleSignIn.getClient(context, gsoBuilder.build())
    }

    /** The already-signed-in Google account, or null if the user hasn't signed in. */
    fun lastAccount(context: Context): Account? =
        GoogleSignIn.getLastSignedInAccount(context)?.account

    fun lastAccountEmail(context: Context): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    fun signOut(context: Context) {
        try { signInClient(context).signOut() } catch (_: Exception) {}
    }

    sealed class TokenResult {
        data class Success(val token: String, val email: String?) : TokenResult()
        /** User must approve the consent screen; launch [intent] with an ActivityResult. */
        data class NeedsConsent(val intent: Intent) : TokenResult()
        data class Error(val message: String) : TokenResult()
    }

    /**
     * Obtain a fresh OAuth2 access token for [account] with the Apps Script scopes.
     * Returns [TokenResult.NeedsConsent] when the user still has to approve the scopes.
     */
    private val httpClient = OkHttpClient()

    suspend fun fetchAccessToken(context: Context, account: Account): TokenResult =
        withContext(Dispatchers.IO) {
            try {
                GstLog.i(TAG, "Requesting OAuth token for ${account.name}")
                var token = GoogleAuthUtil.getToken(context, account, OAUTH2_SCOPE_STRING)
                GstLog.i(TAG, "OAuth token acquired (${GstLog.redact(token)})")

                // Verify the token actually carries the sensitive script scopes. A cached
                // token from an earlier, narrower grant would silently come back here and
                // then get a generic 403 from script.googleapis.com. If the scope is
                // missing, clear the cached token and mint a fresh one (which forces a
                // new consent when needed).
                if (!hasScriptScope(token)) {
                    GstLog.w(TAG, "Token missing script.projects scope � clearing cache and retrying")
                    GoogleAuthUtil.clearToken(context, token)
                    token = GoogleAuthUtil.getToken(context, account, OAUTH2_SCOPE_STRING)
                    GstLog.i(TAG, "Re-minted token (${GstLog.redact(token)})")
                    if (!hasScriptScope(token)) {
                        GstLog.e(TAG, "Scope STILL missing after refresh � consent did not grant script.projects")
                        return@withContext TokenResult.Error(
                            "دسترسی لازم (script.projects) به اپ داده نشده است. در تنظیمات حساب گوگل، " +
                                "دسترسی این اپ را حذف کنید و دوباره لاگین کنید تا صفحه‌ی رضایت کامل نمایش داده شود."
                        )
                    }
                }
                TokenResult.Success(token, account.name)
            } catch (e: UserRecoverableAuthException) {
                GstLog.w(TAG, "Consent required for OAuth scopes")
                val intent = e.intent
                if (intent != null) TokenResult.NeedsConsent(intent)
                else TokenResult.Error(e.message ?: "consent required")
            } catch (e: Exception) {
                GstLog.e(TAG, "Token error: ${e.message}")
                TokenResult.Error(e.message ?: "unknown auth error")
            }
        }

    /** Checks the granted scopes of an access token via Google's tokeninfo endpoint. */
    private fun hasScriptScope(token: String): Boolean {
        return try {
            val encoded = java.net.URLEncoder.encode(token, "UTF-8")
            val req = Request.Builder()
                .url("https://www.googleapis.com/oauth2/v3/tokeninfo?access_token=$encoded")
                .get().build()
            httpClient.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (!res.isSuccessful) {
                    GstLog.w(TAG, "tokeninfo HTTP ${res.code}: ${body.take(200)}")
                    return true // don't block on a diagnostic failure
                }
                val json = JSONObject(body)
                val scope = json.optString("scope", "")
                GstLog.i(TAG, "Token account=${json.optString("email", "?")}")
                GstLog.i(TAG, "Token aud=${json.optString("aud", "?")}")
                GstLog.i(TAG, "Token scopes=$scope")
                scope.contains("script.projects")
            }
        } catch (e: Exception) {
            GstLog.w(TAG, "tokeninfo failed: ${e.message}")
            true // don't block on a diagnostic failure
        }
    }

    /** Invalidate a cached token (call after a 401 from the API, then re-fetch). */
    suspend fun invalidateToken(context: Context, token: String) = withContext(Dispatchers.IO) {
        try { GoogleAuthUtil.clearToken(context, token) } catch (_: Exception) {}
    }
}
