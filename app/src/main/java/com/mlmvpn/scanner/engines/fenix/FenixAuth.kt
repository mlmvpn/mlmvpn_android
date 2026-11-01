/*
 * The Firefox Accounts sign-in below is ported from FoxyVPN (https://github.com/vauth/foxyvpn),
 * itself a port of firefox-vpn-client (https://github.com/UjuiUjuMandan/firefox-vpn-client).
 *
 * MIT License -- Copyright (c) 2026 Vauth
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions: The above copyright notice
 * and this permission notice shall be included in all copies or substantial portions of the
 * Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 *
 * Changed here: FoxyVPN answers Fastly's browser check by imitating a browser. FENIX does not --
 * it throws [FenixAuth.ChallengeNeeded], and the screen opens the real page in a WebView where the
 * user passes the check as anyone would; its cookies then carry the API calls.
 */
package com.mlmvpn.scanner.engines.fenix

import android.util.Base64
import com.mlmvpn.scanner.engines.fenix.FenixHttp.mozilla
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Signs in to Firefox Accounts and keeps each account's OAuth token (VPN scope) fresh. */
internal class FenixAuth(private val store: FenixStore) {

    class ApiError(message: String, val errno: Int?, val status: Int?) : Exception(message)

    /**
     * Fastly wants a browser check first (HTTP 406), at [host]: the UI opens that host's page in a
     * WebView. Firefox Accounts and Guardian (vpn.mozilla.org) each ask for their own (31 Oct 2026:
     * only the accounts' page was opened, so Guardian's check was never passed and FENIX did not
     * start).
     */
    class ChallengeNeeded(val host: String = CHALLENGE_HOST) : Exception("browser check required at $host")

    /** The stored session is gone for good (revoked, password changed): sign in again. */
    class SignedOut(message: String) : Exception(message)

    private var pendingSession: String? = null
    private var pendingEmail: String? = null

    /** True when an email code is needed next ([verifyCode]); false when signed in. */
    suspend fun signIn(email: String, password: String): Boolean {
        val data = login(email.trim(), password)
        val session = data.getString("sessionToken")
        pendingSession = session
        pendingEmail = email.trim()
        if (!data.optBoolean("verified", false)) return true
        complete(session)
        return false
    }

    suspend fun verifyCode(code: String) {
        val session = pendingSession ?: throw IllegalStateException("no pending sign-in")
        call("POST", "/session/verify_code", session, JSONObject().put("code", code.trim()))
        complete(session)
    }

    /** A session made on Mozilla's own page (see FenixEngine.signInWithSession). */
    suspend fun signInWithSession(email: String, sessionToken: String) {
        pendingEmail = email.trim()
        complete(sessionToken)
    }

    /** The account the last completed sign-in added (or refreshed). */
    @Volatile var lastSignedIn: String? = null
        private set

    /** A valid access token for account [id], refreshed when it is about to expire. */
    suspend fun accessToken(id: String, force: Boolean = false): String {
        val acct = store.account(id) ?: throw SignedOut("no such account")
        if (acct.signedOut) throw SignedOut("signed out")
        if (!force && acct.expiresAt - 120 > now()) return acct.access
        val refresh = acct.refresh ?: run { store.update(id) { it.copy(signedOut = true) }; throw SignedOut("no refresh token") }
        val data = try {
            call("POST", "/oauth/token", null, JSONObject()
                .put("client_id", CLIENT_ID).put("grant_type", "refresh_token")
                .put("refresh_token", refresh).put("scope", SCOPE))
        } catch (e: ApiError) {
            if (e.status == 400 || e.status == 401 || e.status == 403) {
                store.update(id) { it.copy(signedOut = true) }
                throw SignedOut(e.message ?: "refresh rejected")
            }
            throw e
        }
        val access = data.optString("access_token").ifBlank { throw ApiError("no access token", null, null) }
        store.update(id) {
            it.copy(access = access, refresh = data.optString("refresh_token").ifBlank { null } ?: refresh, expiresAt = expiry(data))
        }
        return access
    }

    private suspend fun complete(session: String) {
        val data = call("POST", "/oauth/token", session, JSONObject()
            .put("client_id", CLIENT_ID).put("grant_type", "fxa-credentials")
            .put("scope", SCOPE).put("access_type", "offline"))
        lastSignedIn = store.upsert(
            email = pendingEmail.orEmpty(),
            access = data.getString("access_token"),
            refresh = data.optString("refresh_token").ifBlank { null },
            expiresAt = expiry(data),
        )
        pendingSession = null
    }

    private suspend fun login(email: String, password: String, withMethod: Boolean = true): JSONObject {
        val body = JSONObject().put("email", email).put("authPW", authPw(email, password))
        if (withMethod) body.put("verificationMethod", "email-2fa")
        return try {
            call("POST", "/account/login", null, body)
        } catch (e: ApiError) {
            // Accounts that cannot take the email-code method say "invalid parameter" (107).
            if (e.errno == 107 && withMethod) login(email, password, withMethod = false) else throw e
        }
    }

    private suspend fun call(method: String, path: String, session: String?, json: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val url = "$AUTH_SERVER$path".toHttpUrl()
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            val hawk = session?.let { hawkCredentials(it) }
            val req = Request.Builder().url(url).mozilla()
                .header("Content-Type", "application/json")
                .apply { hawk?.let { (id, key) -> header("Authorization", hawkHeader(method, url, id, key, bytes)) } }
                .method(method, bytes.toRequestBody(JSON))
                .build()
            FenixHttp.execute(req).use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 406) throw ChallengeNeeded(url.host)
                if (r.code >= 400) {
                    val err = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                    throw ApiError(err.optString("message", "HTTP ${r.code}"), if (err.has("errno")) err.optInt("errno") else null, r.code)
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }

    // ── Firefox Accounts' password protocol (onepw) ─────────────────────────────────────────────

    private fun authPw(email: String, password: String): String {
        val quick = pbkdf2(password.toByteArray(Charsets.UTF_8), "${PROTOCOL}quickStretch:$email".toByteArray(Charsets.UTF_8), 1000, 32)
        return hkdf(quick, "${PROTOCOL}authPW", 32).hex()
    }

    private fun hawkCredentials(sessionHex: String): Pair<String, ByteArray> {
        val x = hkdf(sessionHex.unhex(), "${PROTOCOL}sessionToken", 64)
        return x.copyOfRange(0, 32).hex() to x.copyOfRange(32, 64)
    }

    private fun hawkHeader(method: String, url: HttpUrl, id: String, key: ByteArray, body: ByteArray): String {
        val ts = now().toString()
        val nonce = Base64.encodeToString(ByteArray(6).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val hash = if (body.isEmpty()) "" else MessageDigest.getInstance("SHA-256").run {
            update("hawk.1.payload\napplication/json\n".toByteArray()); update(body); update("\n".toByteArray())
            Base64.encodeToString(digest(), Base64.NO_WRAP)
        }
        val normalized = "hawk.1.header\n$ts\n$nonce\n${method.uppercase()}\n$path\n${url.host}\n${url.port}\n$hash\n\n"
        val mac = Base64.encodeToString(hmac(key, normalized.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "Hawk id=\"$id\", ts=\"$ts\", nonce=\"$nonce\", mac=\"$mac\"" + if (hash.isNotEmpty()) ", hash=\"$hash\"" else ""
    }

    private fun expiry(data: JSONObject) = now() + (data.optLong("expires_in", 0).takeIf { it > 0 } ?: 86_400L)

    companion object {
        private const val AUTH_SERVER = "https://api.accounts.firefox.com/v1"
        /** Mozilla VPN's own client id; the VPN scope is granted to it. */
        private const val CLIENT_ID = "5882386c6d801776"
        private const val SCOPE = "profile https://identity.mozilla.com/apps/vpn"
        private const val PROTOCOL = "identity.mozilla.com/picl/v1/"
        private val JSON = "application/json".toMediaType()

        /** Where the browser check is passed, and accounts are made. */
        const val CHALLENGE_URL = "https://accounts.firefox.com/"
        const val CHALLENGE_HOST = "accounts.firefox.com"
        const val SIGNUP_URL = "https://accounts.firefox.com/signup"

        fun now() = System.currentTimeMillis() / 1000

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        private fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray {
            val prk = hmac(ByteArray(32), ikm)
            val out = ByteArray(length)
            var prev = ByteArray(0)
            var done = 0
            var i = 1
            while (done < length) {
                prev = hmac(prk, prev + info.toByteArray(Charsets.UTF_8) + byteArrayOf(i.toByte()))
                val n = minOf(prev.size, length - done)
                System.arraycopy(prev, 0, out, done, n)
                done += n; i++
            }
            return out
        }

        private fun pbkdf2(password: ByteArray, salt: ByteArray, rounds: Int, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
            val blocks = (length + 31) / 32
            val out = ByteArray(blocks * 32)
            for (b in 1..blocks) {
                var u = mac.doFinal(salt + ByteBuffer.allocate(4).putInt(b).array())
                val t = u.copyOf()
                repeat(rounds - 1) {
                    u = mac.doFinal(u)
                    for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                System.arraycopy(t, 0, out, (b - 1) * 32, 32)
            }
            return out.copyOf(length)
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
