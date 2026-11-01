package com.mlmvpn.scanner.engines.tgproxy

import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Why a Cloudflare call failed, and nothing more.
 *
 * The message is built from the kind and the two numbers only. Cloudflare's own error text, the
 * request, the headers and the credential never reach an exception, a log line or the screen:
 * [kind] and [cf] are what the UI words, in the user's language.
 */
class TgApiException(val kind: Kind, val http: Int, val cf: Int?, val until: Long = 0L) :
    Exception("cloudflare ${kind.name} http=$http code=${cf ?: 0}") {

    enum class Kind {
        /** The credential is wrong, expired or revoked. */
        AUTH,
        /** The credential works but may not do this (a token without the Pages or Workers right). */
        PERMISSION,
        /** Too many requests; nothing more is sent for this account until [until]. */
        RATE_LIMIT,
        NOT_FOUND,
        CONFLICT,
        /** Cloudflare refused the request as it was written. */
        INVALID,
        NETWORK,
        SERVER,
        OTHER,
    }
}

/**
 * The only door to Cloudflare's API for the Telegram proxy feature.
 *
 *  - every call is authenticated through [CloudAuth], like the rest of the app;
 *  - timeouts come from [client]; network errors and 5xx are retried a bounded number of times
 *    with a growing pause, but only for calls that are safe to repeat (never a bare POST);
 *  - a 429 is not retried: it blocks this account until Cloudflare's `Retry-After` (five minutes
 *    when it gives none -- Cloudflare blocks all calls for five minutes after the limit) and every
 *    call in the meantime fails at once, without touching the network;
 *  - cancelling the coroutine cancels the HTTP call underneath.
 */
class TgCfApi(
    private val client: OkHttpClient,
    private val base: String = "https://api.cloudflare.com/client/v4",
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {

    class Reply(val http: Int, val json: JSONObject)

    private val blocked = ConcurrentHashMap<String, Long>()

    /** When requests for this account may resume, or 0 when they may go now. */
    fun blockedUntil(account: CloudAccount): Long = blocked[account.id]?.takeIf { it > now() } ?: 0L

    suspend fun send(
        account: CloudAccount,
        method: String,
        path: String,
        body: RequestBody? = null,
        retries: Int = if (method == "POST") 0 else 2,
    ): Reply {
        var attempt = 0
        while (true) {
            val until = blockedUntil(account)
            if (until > 0) throw TgApiException(TgApiException.Kind.RATE_LIMIT, 429, null, until)

            val request = Request.Builder().url(base + path).headers(CloudAuth.headers(account)).method(method, body ?: emptyBody(method)).build()
            val outcome: Outcome = try {
                client.newCall(request).await().use { res -> read(res) }
            } catch (e: IOException) {
                Outcome(0, null, 0L)
            }

            when {
                outcome.http == 429 -> {
                    val wait = if (outcome.retryAfterMs > 0) outcome.retryAfterMs.coerceAtMost(3_600_000L) else DEFAULT_BLOCK_MS
                    val resumeAt = now() + wait
                    blocked[account.id] = resumeAt
                    throw TgApiException(TgApiException.Kind.RATE_LIMIT, 429, cfCode(outcome.json), resumeAt)
                }
                outcome.http in 200..299 -> {
                    val json = outcome.json ?: JSONObject()
                    if (json.has("success") && !json.optBoolean("success")) {
                        val cf = cfCode(json)
                        throw TgApiException(classify(outcome.http, cf).takeIf { it != TgApiException.Kind.OTHER } ?: TgApiException.Kind.INVALID, outcome.http, cf)
                    }
                    return Reply(outcome.http, json)
                }
                outcome.http == 0 || outcome.http in 500..599 -> {
                    if (attempt >= retries) {
                        throw TgApiException(if (outcome.http == 0) TgApiException.Kind.NETWORK else TgApiException.Kind.SERVER, outcome.http, cfCode(outcome.json))
                    }
                    pause(PAUSES[attempt.coerceAtMost(PAUSES.lastIndex)])
                    attempt++
                }
                else -> {
                    val cf = cfCode(outcome.json)
                    throw TgApiException(classify(outcome.http, cf), outcome.http, cf)
                }
            }
        }
    }

    private class Outcome(val http: Int, val json: JSONObject?, val retryAfterMs: Long)

    private fun read(res: Response): Outcome {
        val text = runCatching { res.body.string() }.getOrDefault("")
        val json = runCatching { JSONObject(text) }.getOrNull()
        val retry = res.header("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000L } ?: 0L
        return Outcome(res.code, json, retry)
    }

    private fun emptyBody(method: String): RequestBody? =
        if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(null) else null

    companion object {
        private val PAUSES = longArrayOf(1_000L, 3_000L)
        const val DEFAULT_BLOCK_MS = 5 * 60_000L

        /** Codes that mean the credential itself is bad, as opposed to lacking a permission. */
        private val AUTH_CODES = setOf(9109, 6003, 6111, 9106, 9103, 1000)

        /** "A project with this name already exists" on Pages. */
        private val CONFLICT_CODES = setOf(8000002)

        fun cfCode(json: JSONObject?): Int? =
            json?.optJSONArray("errors")?.optJSONObject(0)?.optInt("code", 0)?.takeIf { it != 0 }

        fun classify(http: Int, cf: Int?): TgApiException.Kind = when {
            http == 429 -> TgApiException.Kind.RATE_LIMIT
            http == 401 -> TgApiException.Kind.AUTH
            http == 403 -> if (cf in AUTH_CODES) TgApiException.Kind.AUTH else TgApiException.Kind.PERMISSION
            http == 404 -> TgApiException.Kind.NOT_FOUND
            http == 409 || cf in CONFLICT_CODES -> TgApiException.Kind.CONFLICT
            http in 500..599 -> TgApiException.Kind.SERVER
            http in 400..499 -> TgApiException.Kind.INVALID
            else -> TgApiException.Kind.OTHER
        }
    }
}
