package com.mlmvpn.scanner.engines.fenix

import android.util.Base64
import com.mlmvpn.scanner.engines.fenix.FenixHttp.mozilla
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * Mozilla's Guardian: turns the account's OAuth token into the short-lived "proxy pass" (a JWT)
 * the Fastly proxies accept, and reports the monthly allowance (50 GB on a free account).
 */
internal object FenixGuardian {

    private const val ENDPOINT = "https://vpn.mozilla.org/api/v1/fpn"

    class QuotaExhausted : Exception("monthly allowance used up")
    class Rejected : Exception("access token rejected")

    data class Status(val subscribed: Boolean, val limited: Boolean, val maxBytes: Long?)

    /** Switches the free allowance on for an account that has never used it. Harmless when on. */
    suspend fun activate(access: String): Status = withContext(Dispatchers.IO) {
        request("POST", "/activate", access).use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw Rejected()
            if (r.code != 200) throw IllegalStateException("activate: HTTP ${r.code}")
            status(JSONObject(text))
        }
    }

    suspend fun status(access: String): Status = withContext(Dispatchers.IO) {
        request("GET", "/status", access).use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw Rejected()
            if (r.code != 200) throw IllegalStateException("status: HTTP ${r.code}")
            status(JSONObject(text))
        }
    }

    suspend fun pass(access: String): FenixStore.Pass = withContext(Dispatchers.IO) {
        request("GET", "/token", access).use { r ->
            when (r.code) {
                401, 403 -> throw Rejected()
                429 -> throw QuotaExhausted()
            }
            val text = r.body?.string().orEmpty()
            if (r.code != 200) throw IllegalStateException("token: HTTP ${r.code}")
            val token = JSONObject(text).optString("token").ifBlank { throw IllegalStateException("token: empty") }
            val (iat, exp) = claims(token)
            val now = FenixAuth.now()
            val expiresAt = JSONObject(text).optLong("expires_at", 0L).takeIf { it > now } ?: exp?.takeIf { it > now } ?: (now + 3600)
            FenixStore.Pass(
                token = token,
                // The JWT's own times can be off by the server's clock or a time zone (seen by
                // firefox-vpn-client); the phone's clock at receipt is what renewal is timed on.
                issuedAt = now,
                expiresAt = if (iat != null && exp != null && exp > iat) now + (exp - iat) else expiresAt,
                quotaLimit = r.header("X-Quota-Limit")?.toLongOrNull(),
                quotaRemaining = r.header("X-Quota-Remaining")?.toLongOrNull(),
                quotaReset = r.header("X-Quota-Reset")?.toLongOrNull(),
            )
        }
    }

    private fun status(o: JSONObject) = Status(
        subscribed = o.optBoolean("subscribed"),
        limited = o.optBoolean("limited_bandwidth"),
        maxBytes = if (o.has("maxBytes") && !o.isNull("maxBytes")) o.optLong("maxBytes", -1).takeIf { it >= 0 } else null,
    )

    private fun request(method: String, path: String, access: String): Response {
        val b = Request.Builder().url("$ENDPOINT$path").mozilla()
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $access")
        if (method == "POST") b.post(ByteArray(0).toRequestBody(null))
        val r = FenixHttp.execute(b.build())
        if (r.code == 406) { r.close(); throw FenixAuth.ChallengeNeeded(r.request.url.host) }
        return r
    }

    /** `iat` and `exp` from a JWT, without verifying it (Guardian's servers do that). */
    fun claims(jwt: String): Pair<Long?, Long?> = runCatching {
        var b64 = jwt.split('.')[1]
        while (b64.length % 4 != 0) b64 += "="
        val o = JSONObject(String(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        Pair(o.optLong("iat", 0).takeIf { it > 0 } ?: o.optLong("nbf", 0).takeIf { it > 0 }, o.optLong("exp", 0).takeIf { it > 0 })
    }.getOrDefault(null to null)
}
