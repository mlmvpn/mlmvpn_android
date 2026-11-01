package com.mlmvpn.scanner.engines.tgproxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom

/** Where a self-test stopped: [step] is page, session, socket, stream or telegram; null on success. */
data class TgProbeOutcome(val ok: Boolean, val step: String?, val ms: Long)

fun interface TgProber {
    /** [viaApp] picks the app's own route to Cloudflare; false uses the phone's ordinary network. */
    suspend fun probe(host: String, secret: String, viaApp: Boolean): TgProbeOutcome
}

/**
 * The real test of a proxy: do what Telegram does, end to end, and ask a Telegram data centre a
 * question through it.
 *
 *  1. page     GET the bridge page with the capability derived from the secret
 *  2. session  POST HELLO with the bootstrap token the page carries
 *  3. socket   open the WebSocket with the session token
 *  4. stream   OPEN a stream and send an obfuscated2 header plus `req_pq_multi` to data centre 2
 *  5. telegram wait for `res_pq` carrying our nonce
 *
 * "Ready" is shown only when all five pass. The secret, the capability and the tokens exist only
 * inside this function; nothing is logged.
 */
class TgProbe(
    private val appClient: OkHttpClient,
    private val plainClient: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stepMs: Long = 10_000L,
) : TgProber {

    override suspend fun probe(host: String, secret: String, viaApp: Boolean): TgProbeOutcome =
        withContext(Dispatchers.IO) { run(if (viaApp) appClient else plainClient, host, secret) }

    private suspend fun run(client: OkHttpClient, host: String, secret: String): TgProbeOutcome {
        val t0 = clock()
        fun outcome(step: String?) = TgProbeOutcome(step == null, step, clock() - t0)

        // 1. the bridge page
        val page = withTimeoutOrNull(stepMs) {
            runCatching {
                client.newCall(Request.Builder().url(TgLink.bridgeUrl(host, secret)).build()).await().use { res ->
                    if (res.code != 200) null else res.body.string().take(100_000)
                }
            }.getOrNull()
        }
        val bootstrap = page?.let { BOOT.find(it)?.groupValues?.get(1) } ?: return outcome("page")

        // 2. the session
        val token = withTimeoutOrNull(stepMs) {
            runCatching {
                val req = Request.Builder().url("https://$host/api/v1/session")
                    .header("Authorization", "Bearer $bootstrap")
                    .post(TgFrames.HELLO_FRAME.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(req).await().use { res -> sessionToken(res) }
            }.getOrNull()
        } ?: return outcome("session")

        // 3. the WebSocket
        val frames = Channel<ByteArray>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Boolean>()
        val socket: WebSocket = client.newWebSocket(
            Request.Builder().url("https://$host/api/v1/ws").header("Sec-WebSocket-Protocol", "tproxy-v1.$token").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(response.code == 101)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    frames.trySend(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.complete(false)
                    frames.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    frames.close()
                }
            },
        )
        try {
            if (withTimeoutOrNull(stepMs) { opened.await() } != true) return outcome("socket")

            // 4. a stream, and a question for data centre 2
            val padded = TgLink.isPadded(secret)
            val obfs = TgObfs2Client(TgLink.keyBytes(secret), dc = 2, tag = if (padded) PADDED else INTERMEDIATE)
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            socket.send(TgFrames.encode(TgFrames.OPEN, 1).toByteString())
            socket.send(TgFrames.encode(TgFrames.DATA, 1, obfs.header + obfs.seal(reqPqMulti(nonce))).toByteString())

            // 5. the answer
            var plain = ByteArray(0)
            var sawData = false
            var closedByRelay = false
            val answered = withTimeoutOrNull(stepMs) {
                for (batch in frames) {
                    for (f in TgFrames.parse(batch) ?: return@withTimeoutOrNull false) {
                        if (f.stream != 1) continue
                        if (f.type == TgFrames.CLOSE) {
                            closedByRelay = true
                            return@withTimeoutOrNull false
                        }
                        if (f.type == TgFrames.DATA) {
                            sawData = true
                            plain += obfs.open(f.payload)
                            if (plain.size >= ANSWER_BYTES) return@withTimeoutOrNull isResPq(plain, nonce)
                        }
                    }
                }
                false
            }
            return when {
                answered == true -> outcome(null)
                closedByRelay && !sawData -> outcome("stream")
                else -> outcome("telegram")
            }
        } finally {
            runCatching { socket.send(TgFrames.encode(TgFrames.CLOSE, 1).toByteString()) }
            runCatching { socket.close(1000, null) }
            frames.close()
        }
    }

    private fun sessionToken(res: Response): String? {
        if (res.code != 200 || res.header("X-Carrier-Mode") != "websocket") return null
        return res.header("X-Session-Token")?.takeIf { TOKEN.matches(it) }
    }

    companion object {
        private val BOOT = Regex("BOOT=\"([A-Za-z0-9_-]{43})\"")
        private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
        private const val INTERMEDIATE = 0xeeeeeeee.toInt()
        private const val PADDED = 0xdddddddd.toInt()

        /** length prefix + auth_key_id + message_id + length + constructor + nonce up to the nonce end. */
        private const val ANSWER_BYTES = 4 + 24 + 16

        /**
         * An unencrypted `req_pq_multi#be7e8ef1 nonce:int128` in the intermediate transport:
         * a 4-byte length, then auth_key_id (0), message_id, body length and the body.
         */
        fun reqPqMulti(nonce: ByteArray, nowSeconds: Long = System.currentTimeMillis() / 1000): ByteArray {
            val body = byteArrayOf(0xf1.toByte(), 0x8e.toByte(), 0x7e, 0xbe.toByte()) + nonce
            val message = ByteArray(20 + body.size)
            val id = nowSeconds shl 32
            for (i in 0 until 8) message[8 + i] = (id ushr (8 * i)).toByte()
            message[16] = body.size.toByte() // 20, small enough for one byte; the rest of the length is 0
            body.copyInto(message, 20)
            val packet = ByteArray(4 + message.size)
            packet[0] = message.size.toByte()
            message.copyInto(packet, 4)
            return packet
        }

        /** True when [plain] is a `res_pq#05162463` that repeats [nonce]. */
        fun isResPq(plain: ByteArray, nonce: ByteArray): Boolean {
            if (plain.size < ANSWER_BYTES) return false
            val constructor = (plain[24].toInt() and 0xFF) or ((plain[25].toInt() and 0xFF) shl 8) or
                ((plain[26].toInt() and 0xFF) shl 16) or ((plain[27].toInt() and 0xFF) shl 24)
            return constructor == 0x05162463 && plain.copyOfRange(28, 44).contentEquals(nonce)
        }
    }
}
