package com.mlmvpn.scanner.engines.tgproxy

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lanes to the user's relay at [host], each one a session and its WebSocket, through the Cloudflare
 * addresses in [ips] (best first; the TLS name stays [host], so any edge serves it). When an address
 * stops working the next one is tried; [ipInUse] says which one carries the lanes now.
 *
 * A session needs a token: the freshest one the relay sent in a PING, or the one the last session
 * came with, and when the relay refuses those (a relay from before session renewal, or a token past
 * its hour) a fresh bootstrap from the bridge page, which also tells the lane budget.
 */
class TgWsLanes(
    private val base: OkHttpClient,
    private val host: String,
    private val secret: String,
    ips: List<String>,
) : TgLaneFactory {

    @Volatile var ips: List<String> = ips
        set(value) { field = value; start = 0 }

    @Volatile override var budget: Int = DEFAULT_BUDGET
        private set

    @Volatile var ipInUse: String? = null
        private set

    @Volatile private var token: String? = null
    @Volatile private var start = 0
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    override fun token(token: String) {
        this.token = token
    }

    override fun open(events: TgLaneEvents): TgLane? {
        val list = ips
        val order: List<String?> = if (list.isEmpty()) listOf(null) else list.indices.map { list[(start + it) % list.size] }
        for (ip in order) {
            val lane = runCatching { openVia(clientFor(ip), events) }.getOrNull() ?: continue
            if (ip != null) start = list.indexOf(ip).coerceAtLeast(0)
            ipInUse = ip
            return lane
        }
        return null
    }

    /** The app's client with the name pinned to [ip]: TCP goes to that edge, TLS still says [host]. */
    private fun clientFor(ip: String?): OkHttpClient = if (ip == null) base else clients.getOrPut(ip) {
        val address = InetAddress.getByName(ip)
        base.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = if (hostname.equals(host, true)) listOf(address) else Dns.SYSTEM.lookup(hostname)
        }).build()
    }

    private fun openVia(client: OkHttpClient, events: TgLaneEvents): TgLane? {
        val session = session(client) ?: return null
        val opened = CountDownLatch(1)
        var ok = false
        val request = Request.Builder().url("https://$host/api/v1/ws").header("Sec-WebSocket-Protocol", "tproxy-v1.$session").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ok = response.code == 101
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = events.message(bytes.toByteArray())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                events.closed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (opened.count > 0) opened.countDown() else events.closed()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.closed()
        })
        if (!opened.await(OPEN_MS, TimeUnit.MILLISECONDS) || !ok) {
            ws.cancel()
            return null
        }
        return object : TgLane {
            override fun send(frame: ByteArray): Boolean = ws.send(frame.toByteString())
            override fun close() { ws.close(1000, null) }
        }
    }

    /** A session token for the next WebSocket: renewed with the last token, else from a fresh page. */
    private fun session(client: OkHttpClient): String? {
        token?.let { t -> post(client, t)?.let { return it } }
        val page = runCatching {
            client.newCall(Request.Builder().url(TgLink.bridgeUrl(host, secret)).build()).execute().use { res ->
                if (res.code == 200) res.body.string().take(200_000) else null
            }
        }.getOrNull() ?: return null
        DIALS.find(page)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it >= 4) budget = it }
        val boot = BOOT.find(page)?.groupValues?.get(1) ?: return null
        return post(client, boot)
    }

    private fun post(client: OkHttpClient, bearer: String): String? = runCatching {
        val req = Request.Builder().url("https://$host/api/v1/session")
            .header("Authorization", "Bearer $bearer")
            .post(TgFrames.HELLO_FRAME.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(req).execute().use { res ->
            if (res.code != 200 || res.header("X-Carrier-Mode") != "websocket") return@use null
            res.header("X-Session-Token")?.takeIf { TOKEN.matches(it) }?.also { token = it }
        }
    }.getOrNull()

    companion object {
        const val DEFAULT_BUDGET = 45
        private const val OPEN_MS = 12_000L
        private val BOOT = Regex("BOOT=\"([A-Za-z0-9_-]{43})\"")
        private val DIALS = Regex("DIALS=(\\d{1,6});")
        private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
