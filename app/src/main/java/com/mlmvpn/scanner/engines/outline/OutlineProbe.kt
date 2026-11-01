package com.mlmvpn.scanner.engines.outline

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.mlmvpn.core.warp.AwgHostClient
import com.mlmvpn.core.warp.XrayProbeService
import com.mlmvpn.scanner.engines.amnezia.AmzCarrier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket

/**
 * Outline's real delay tests, run in the `:xprobe` process like the Amnezia engine's.
 *
 * Some public Shadowsocks servers drive Xray into a Go panic ("close of closed channel", seen on the
 * user's phone 2026-10-11 during «تست همه»): a Go panic cannot be caught and aborted the whole app.
 * In `:xprobe` it ends only that process; the batch it was testing is tried again in halves.
 *
 * One core per batch: a SOCKS inbound per server, wired by tag to that server's Shadowsocks
 * outbound. With [carrier] every outbound dials through Cloudflare WARP's WireGuard inside the same
 * core ([AmzCarrier.outbounds]): one key, one session, for the whole batch.
 *
 * Batches are fed as a stream ([testStream]): a batch starts as soon as servers are waiting, so the
 * first delays arrive while the TCP knock is still going down the list. The whole list used to be
 * knocked first, in silence, and then measured 24 at a time with a process start for each -- a
 * thousand servers showed nothing for most of a minute and took many more to finish.
 */
internal object OutlineProbe {
    private const val TAG = "OutlineProbe"
    /** Servers per core, and how many of them are measured at once. */
    const val BATCH = 48
    const val PARALLEL = 48
    /** Once one server is waiting, how long a batch waits for more (the knock is still producing). */
    private const val LINGER_MS = 600L
    /** Starts that failed in a row, with not one success, before the stream stops trying. */
    private const val BROKEN_AFTER = 8
    /** One test core at a time in `:xprobe`: starting one stops the one before. */
    private val lock = Mutex()
    @Volatile private var coreUp = false

    /** Every start of one stream, so a test process that cannot start at all is noticed. */
    private class Starts { var ok = 0; var failedInRow = 0 }

    /** [servers] as one stream; see [testStream]. */
    suspend fun test(context: Context, servers: List<OutlineServer>, timeoutMs: Int, carrier: AmzCarrier.Carrier?,
                     batch: Int = BATCH, parallel: Int = PARALLEL, viaSocks: Int = 0, onResult: (String, Long?) -> Unit) {
        val input = Channel<OutlineServer>(Channel.UNLIMITED)
        servers.forEach { input.trySend(it) }
        input.close()
        testStream(context, input, timeoutMs, carrier, batch, parallel, viaSocks, onResult)
    }

    /**
     * Measures every server that comes out of [input] until it is closed and drained. Each one ends in
     * exactly one [onResult]: its delay in ms, or null when it carried nothing.
     *
     * The way in: straight, or from inside WARP -- through [carrier] (WARP's WireGuard inside the same
     * core) or through [viaSocks] (a loopback SOCKS port into WARP: [com.mlmvpn.core.warp.WarpLeg]).
     */
    suspend fun testStream(context: Context, input: ReceiveChannel<OutlineServer>, timeoutMs: Int, carrier: AmzCarrier.Carrier?,
                           batch: Int = BATCH, parallel: Int = PARALLEL, viaSocks: Int = 0, onResult: (String, Long?) -> Unit) {
        val app = context.applicationContext
        val shared = carrier?.let { AmzCarrier.outbounds(it) }
            ?: if (viaSocks > 0) listOf(OutlineCf.socksLeg(viaSocks)) else emptyList()
        val through = when {
            carrier != null -> AmzCarrier.TAG
            viaSocks > 0 -> OutlineCf.LEG_TAG
            else -> null
        }
        val starts = Starts()
        lock.withLock {
            try {
                while (true) {
                    val first = input.receiveCatching().getOrNull() ?: break
                    val chunk = ArrayList<OutlineServer>(batch).apply { add(first) }
                    val until = System.nanoTime() + LINGER_MS * 1_000_000
                    while (chunk.size < batch) {
                        val waiting = input.tryReceive().getOrNull()
                        if (waiting != null) { chunk += waiting; continue }
                        if (input.isClosedForReceive) break
                        val left = (until - System.nanoTime()) / 1_000_000
                        if (left <= 0) break
                        chunk += withTimeoutOrNull(left) { input.receiveCatching().getOrNull() } ?: break
                    }
                    val entries = chunk.mapNotNull { s -> OutlineCf.outbound(s.uri)?.let { s to it } }
                    chunk.filter { s -> entries.none { it.first.id == s.id } }.forEach { onResult(it.id, null) }
                    if (starts.ok == 0 && starts.failedInRow >= BROKEN_AFTER) {
                        // The test process does not start at all here: nothing below would be a test.
                        entries.forEach { onResult(it.first.id, null) }
                        continue
                    }
                    runBatch(app, entries, timeoutMs, shared, through, parallel, onResult, retries = 2, startTries = 2, starts = starts)
                }
            } finally {
                if (coreUp) withContext(NonCancellable + Dispatchers.IO) { stop(app) }
            }
        }
        if (starts.ok == 0 && starts.failedInRow >= BROKEN_AFTER) Log.w(TAG, "the test process never started (${starts.failedInRow} tries)")
    }

    private suspend fun runBatch(app: Context, entries: List<Pair<OutlineServer, JSONObject>>, timeoutMs: Int,
                                 shared: List<JSONObject>, through: String?, parallel: Int, onResult: (String, Long?) -> Unit,
                                 retries: Int, startTries: Int, starts: Starts) {
        if (entries.isEmpty()) return
        if (starts.ok == 0 && starts.failedInRow >= BROKEN_AFTER) { entries.forEach { onResult(it.first.id, null) }; return }
        val ports = withContext(Dispatchers.IO) { entries.map { freePort() } }
        val started = start(app, config(entries, ports, shared, through))
        if (!started.ok) {
            starts.failedInRow++
            when {
                // The call reached the last batch's process as it ended itself after its stop, or the
                // process died for a reason of its own: the same batch once more, in a fresh process,
                // before anything is judged. This used to fail every server of the batch untested.
                started.died && startTries > 1 ->
                    runBatch(app, entries, timeoutMs, shared, through, parallel, onResult, retries, startTries - 1, starts)
                // One outbound the core cannot build -- or one that panics it at start -- takes the
                // whole batch down with it: halves, until it is alone.
                entries.size > 1 -> entries.chunked((entries.size + 1) / 2)
                    .forEach { runBatch(app, it, timeoutMs, shared, through, parallel, onResult, retries, 2, starts) }
                else -> onResult(entries[0].first.id, null)
            }
            return
        }
        starts.ok++
        starts.failedInRow = 0
        coreUp = true
        val deathsAtStart = AwgHostClient.xray.deaths
        val results = coroutineScope {
            val gate = Semaphore(parallel.coerceAtLeast(1))
            entries.zip(ports).map { (e, port) ->
                async(MEASURE) { gate.withPermit { e.first to measure(port, timeoutMs) } }
            }.awaitAll()
        }
        // Judged before the stop: a stop ends the test process by design (XrayProbeService).
        val diedMeasuring = AwgHostClient.xray.deaths != deathsAtStart
        withContext(Dispatchers.IO) { stop(app) }
        if (!diedMeasuring) {
            results.forEach { (s, ms) -> onResult(s.id, ms) }
            return
        }
        Log.w(TAG, "test core died under a batch of ${entries.size}; retrying in halves")
        results.filter { it.second != null }.forEach { (s, ms) -> onResult(s.id, ms) }
        val again = entries.filter { e -> results.any { it.first.id == e.first.id && it.second == null } }
        if (retries <= 0 || again.isEmpty()) { again.forEach { onResult(it.first.id, null) }; return }
        again.chunked(maxOf(1, (again.size + 1) / 2))
            .forEach { runBatch(app, it, timeoutMs, shared, through, parallel, onResult, retries - 1, 2, starts) }
    }

    private fun config(entries: List<Pair<OutlineServer, JSONObject>>, ports: List<Int>, shared: List<JSONObject>, through: String?): String {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        entries.forEachIndexed { i, (_, outbound) ->
            val inTag = "oin-$i"; val outTag = "oout-$i"
            inbounds.put(JSONObject().put("tag", inTag).put("port", ports[i]).put("listen", "127.0.0.1").put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", false)))
            val out = JSONObject(outbound.toString()).put("tag", outTag)
            if (through != null) OutlineCf.dialThrough(out, through)
            outbounds.put(out)
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put(inTag)).put("outboundTag", outTag))
        }
        shared.forEach { outbounds.put(JSONObject(it.toString())) }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        return JSONObject()
            .put("remarks", "mlm-outline-probe")
            .put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            .toString()
    }

    private class Started(val ok: Boolean, val died: Boolean)

    private suspend fun start(app: Context, cfg: String): Started = withContext(Dispatchers.IO) {
        val r = AwgHostClient.xray.callDetailed(app, XrayProbeService.MSG_START, Bundle().apply { putString(XrayProbeService.KEY_CONFIG, cfg) }, 20_000)
        Started(r.reply?.getBoolean(XrayProbeService.KEY_OK) == true, r.died)
    }

    private fun stop(app: Context) {
        runCatching { AwgHostClient.xray.call(app, XrayProbeService.MSG_STOP, null, 5_000) }
        coreUp = false
    }

    /**
     * The measurements block on sockets for up to [timeoutMs] each, so they get threads of their own:
     * on the shared IO pool (64 threads) they queued behind the TCP knock's 64 blocking connects.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val MEASURE = Dispatchers.IO.limitedParallelism(96)

    /** Two requests through the server; the second is the delay (the first pays for the handshake). */
    private fun measure(port: Int, timeoutMs: Int): Long? {
        val first = get(port, timeoutMs) ?: return null
        return get(port, timeoutMs) ?: first
    }

    private fun get(port: Int, timeoutMs: Int): Long? = runCatching {
        val start = System.nanoTime()
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { s ->
            s.soTimeout = timeoutMs
            // Unresolved: the name is resolved at the exit, never by this phone's resolver.
            s.connect(InetSocketAddress.createUnresolved("www.gstatic.com", 80), timeoutMs)
            s.getOutputStream().write("GET /generate_204 HTTP/1.0\r\nHost: www.gstatic.com\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
            val line = readLine(s.getInputStream()).orEmpty()
            if (line.contains(" 204") || line.contains(" 200")) (System.nanoTime() - start) / 1_000_000 else null
        }
    }.getOrNull()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 256) {
            val b = input.read(); if (b < 0) break
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return sb.toString().ifEmpty { null }
    }

    private fun freePort(): Int {
        repeat(5) { runCatching { return ServerSocket(0).use { it.localPort } }; Thread.sleep(50) }
        return 20_000 + java.util.Random().nextInt(30_000)
    }
}
