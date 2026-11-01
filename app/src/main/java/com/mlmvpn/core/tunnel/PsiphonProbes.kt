package com.mlmvpn.core.tunnel

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.mlmvpn.core.warp.AwgHostClient
import com.mlmvpn.core.warp.Ipv4Exits
import com.mlmvpn.scanner.engines.scout.PsiphonProbeService
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * «سنجش واقعی» for «آی‌پی تمیز» and «پروکسی سرور»: a throwaway Psiphon given ONE way in -- one clean
 * edge address, or one upstream proxy -- and nothing else, in a process of its own (Psiphon's Go
 * runtime cannot share the main process with Xray's), on a data folder of its own (fresh for every
 * test, so no replay can stand in for what is under test) and its own port. «وصل شد» means a tunnel
 * came up AND a whole HTTPS request crossed it; the number shown is that request's time. Never
 * touches the user's engine, its port or its datastore. The desktop's psiphon-manager.js › probeWith.
 *
 * Up to [MAX_SLOTS] tests at once, as many as the phone's memory allows ([slots]). A slot keeps its
 * process for a whole run: between two tests the core is stopped ([PsiphonProbeService.HALT]) and the
 * next one starts in the same process, and the process ends [IDLE_MS] after its last test. Measured
 * on an A50 (2026-10-18), a process per test cost 2.4–4.6 s before Psiphon even started, and the
 * system's dex extraction for each new one froze the screen.
 *
 * A clean address is tested with the shipped server entries of ITS provider only ([fronted]): with
 * all of them, Psiphon also dialled the Fastly entry through DNS, and a tunnel that came up that way
 * was credited to the Akamai address under test (23.32.238.74, «connected» in 15.6 s with a 7.7 s
 * ping, answered nothing in eight direct tries). The 493 entries also took 3.5 s (up to 15 s) to
 * import into each fresh datastore, inside the test's time.
 */
object PsiphonProbes {

    private const val TAG = "PsiphonProbes"

    data class Outcome(
        val ok: Boolean,
        val ms: Long? = null,
        val pingMs: Long? = null,
        val egress: String? = null,
        val error: String? = null,
        /**
         * The test itself could not run -- its process would not start, died under it, or stopped
         * answering. Nothing is known about the address or the proxy; a caller does not record it.
         */
        val harness: Boolean = false,
    )

    /** A clean address's time: a tunnel through a good edge is up in 2–5 s; 25 s is what the desktop gives. */
    const val CLEAN_MS = 25_000

    /** A proxy's time: about half the proxies that relay HTTPS carry a Psiphon tunnel, in 8–36 s (desktop). */
    const val UPSTREAM_MS = 40_000

    const val MAX_SLOTS = 6

    /** How long a slot's process waits for the next test before it ends. */
    private const val IDLE_MS = 15_000L

    private val services: List<Class<*>> = listOf(
        PsiphonTestSlot0::class.java, PsiphonTestSlot1::class.java, PsiphonTestSlot2::class.java,
        PsiphonTestSlot3::class.java, PsiphonTestSlot4::class.java, PsiphonTestSlot5::class.java,
    )

    /**
     * How many tests may run at once on this phone. Each is a whole Psiphon core in a process of its
     * own: measured on an A50 (debug build, 2026-10-18) 150 MB PSS mid-test, most of it the app's
     * dex that a debug build extracts per process; a release build shares it.
     */
    fun slots(ctx: Context): Int {
        val am = ctx.applicationContext.getSystemService(ActivityManager::class.java) ?: return 1
        if (am.isLowRamDevice) return 1
        val mi = ActivityManager.MemoryInfo().also { runCatching { am.getMemoryInfo(it) } }
        return slotsFor(mi.totalMem)
    }

    /**
     * By the memory the kernel sees, which is short of what the box says: a "4 GB" phone reports
     * about 3.5 GiB, a "6 GB" one about 5.5.
     */
    internal fun slotsFor(totalBytes: Long): Int {
        if (totalBytes <= 0) return 2
        val gib = totalBytes / (1024.0 * 1024.0 * 1024.0)
        return when {
            gib < 2.5 -> 1
            gib < 3.2 -> 2
            gib < 4.6 -> 3
            gib < 7.0 -> 4
            else -> MAX_SLOTS
        }
    }

    // ── slots ───────────────────────────────────────────────────────────────────────────────────

    private class Slot(val index: Int) {
        // A cold process of a debug build took 5.6 s to come up (2026-10-18), past the usual 5 s wait.
        val client = AwgHostClient(services[index], "PsTest$index", bindWaitMs = 15_000)
        val mutex = Mutex()
        /** The token of the process this slot holds open, or null. Read and written under [mutex]. */
        var token: String? = null
        /** [AwgHostClient.deaths] when [token] was claimed: a death since means that process is gone. */
        var deathsAtOpen = 0
        var seq = 0
        @Volatile var lastUsed = 0L
    }

    /** Made on first use: each slot's client starts a thread, which the parsing helpers here never need. */
    private val all by lazy { List(MAX_SLOTS) { Slot(it) } }

    @Volatile private var free: Channel<Int>? = null
    @Volatile private var width = 0

    private fun pool(ctx: Context): Channel<Int> = free ?: synchronized(this) {
        free ?: Channel<Int>(Channel.UNLIMITED).also { ch ->
            width = slots(ctx).coerceIn(1, MAX_SLOTS)
            repeat(width) { ch.trySend(it) }
            free = ch
        }
    }

    /** How many tests run at once here: the pool's size, fixed for the process's life. */
    fun width(ctx: Context): Int { pool(ctx.applicationContext); return width }

    private val reaper = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The slot's process ends [IDLE_MS] after this test unless another test takes the slot first. */
    private fun scheduleRetire(app: Context, slot: Slot) {
        val stamp = slot.lastUsed
        reaper.launch {
            delay(IDLE_MS)
            if (!slot.mutex.tryLock()) return@launch
            try {
                if (slot.lastUsed == stamp) retireNow(app, slot)
            } finally {
                slot.mutex.unlock()
            }
        }
    }

    private fun args(token: String) = Bundle().apply { putString(PsiphonProbeService.TOKEN, token) }

    /** A process for the slot: the one it holds while that is alive, a fresh one otherwise. Under the slot's mutex. */
    private suspend fun ensureOpen(app: Context, slot: Slot): Boolean {
        if (alive(slot)) return true
        if (slot.token != null) retireNow(app, slot)
        // A fresh process: whatever an earlier one left in the slot's folder goes.
        runCatching { File(app.filesDir, "psiphon-probe/${slot.index}").deleteRecursively() }
        val token = UUID.randomUUID().toString()
        val p = PsiphonProbeService
        for (attempt in 0 until 4) {
            val reply = runInterruptible { slot.client.call(app, p.OPEN, args(token), 5_000) }
            if (reply?.getBoolean(p.OK) == true) {
                slot.token = token
                slot.deathsAtOpen = slot.client.deaths
                return true
            }
            // The slot's previous process ends itself a moment after its STOP; until the system says
            // it is gone, an OPEN can land on it and be refused (it holds the old token). Asked again
            // on a fresh bind.
            slot.client.release(app)
            delay(600)
        }
        return false
    }

    /** The process the slot holds is still the one it opened. */
    private fun alive(slot: Slot): Boolean = slot.token != null && slot.client.deaths == slot.deathsAtOpen

    /**
     * The slot's process told to end (it exits by itself) and let go. Under the slot's mutex. A call
     * to a process that is already gone would bind a fresh one just to refuse it, so a dead one is
     * only let go.
     */
    private suspend fun retireNow(app: Context, slot: Slot) {
        val token = slot.token
        val live = alive(slot)
        slot.token = null
        withContext(NonCancellable + Dispatchers.IO) {
            if (token != null && live) runCatching { runInterruptible { slot.client.call(app, PsiphonProbeService.STOP, args(token), 1_500) } }
            slot.client.release(app)
        }
    }

    // ── what a test is given ────────────────────────────────────────────────────────────────────

    /**
     * The shipped server entries of one fronting provider that can run FRONTED-MEEK-OSSH, and the
     * Host headers Psiphon sends through that provider's edges (an entry's `meekFrontingHosts`, or
     * its `meekFrontingHost` when it has none -- psiphon-tunnel-core selectFrontingParameters).
     */
    class Fronted(val entries: String, val count: Int, val hosts: List<String>)

    private val frontedCache = ConcurrentHashMap<String, Fronted>()

    /**
     * One reading of the 1.3 MB list per provider, whoever asks first: the pre-check's workers all
     * ask at once, and each would otherwise parse its own copy.
     */
    fun fronted(ctx: Context, providerId: String): Fronted = frontedCache[providerId] ?: synchronized(frontedCache) {
        frontedCache[providerId] ?: run {
            val text = runCatching {
                com.mlmvpn.scanner.store.StoreFiles.open(ctx.applicationContext, "server_entries.txt").bufferedReader().use { it.readText() }
            }.getOrDefault("")
            parseFronted(text, providerId).also { if (it.count > 0) frontedCache[providerId] = it }
        }
    }

    internal fun parseFronted(text: String, providerId: String): Fronted {
        val keep = ArrayList<String>()
        val hosts = LinkedHashSet<String>()
        for (line in text.lineSequence()) {
            val hex = line.trim()
            if (hex.isEmpty()) continue
            val json = decodeEntry(hex) ?: continue
            if (json.optString("frontingProviderID") != providerId) continue
            val caps = json.optJSONArray("capabilities") ?: continue
            if ((0 until caps.length()).none { caps.optString(it) == "FRONTED-MEEK" }) continue
            keep += hex
            val many = json.optJSONArray("meekFrontingHosts")
            if (many != null && many.length() > 0) {
                for (i in 0 until many.length()) many.optString(i).takeIf { it.isNotBlank() }?.let(hosts::add)
            } else {
                json.optString("meekFrontingHost").takeIf { it.isNotBlank() }?.let(hosts::add)
            }
        }
        return Fronted(keep.joinToString("\n"), keep.size, hosts.toList())
    }

    /** One line of server_entries.txt: hex of "ip port secret certificate {json}". */
    internal fun decodeEntry(hex: String): JSONObject? {
        if (hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        val s = String(bytes, Charsets.UTF_8)
        val at = s.indexOf('{')
        if (at < 0) return null
        return runCatching { JSONObject(s.substring(at)) }.getOrNull()
    }

    // ── the tests ───────────────────────────────────────────────────────────────────────────────

    /**
     * One clean address, rung E with only it and only its provider's servers: is a tunnel built
     * through it, and does a request cross? A tunnel the core reports at any other address does not
     * count for this one.
     */
    suspend fun cleanIp(
        ctx: Context,
        ip: String,
        providerId: String,
        timeoutMs: Int = CLEAN_MS,
        /** The tunnel is up and the request through it is next: for the page's «سنجش پینگ…». */
        onConnected: (() -> Unit)? = null,
    ): Outcome {
        val f = withContext(Dispatchers.IO) { fronted(ctx, providerId) }
        val want = PsiphonCleanIps.canonical(ip)
        return run(ctx, timeoutMs, entries = f.entries.takeIf { f.count > 0 },
            check = { got -> want == null || PsiphonCleanIps.canonical(got.replace("\\", "").removePrefix("[").substringBefore("]")) == want },
            onConnected = onConnected,
        ) { port, dir, seconds ->
            psiphonTestConfig(port, dir, seconds, cleanCidr = providerId to (ip + if (ip.contains(':')) "/128" else "/32"))
        }
    }

    /**
     * One upstream proxy, rung D THROUGH it. Measured on the desktop (2026-10-14): about half the
     * proxies that relay HTTPS from a line also carry a Psiphon tunnel (8–36 s); the rest never do --
     * so only this test may show a ping.
     */
    suspend fun upstream(ctx: Context, url: String, timeoutMs: Int = UPSTREAM_MS, onConnected: (() -> Unit)? = null): Outcome =
        run(ctx, timeoutMs, entries = null, check = null, onConnected = onConnected) { port, dir, seconds ->
            psiphonTestConfig(port, dir, seconds, upstreamUrl = url)
        }

    private suspend fun run(
        ctx: Context,
        timeoutMs: Int,
        entries: String?,
        check: ((String) -> Boolean)?,
        onConnected: (() -> Unit)?,
        build: (port: Int, dir: File, seconds: Int) -> String,
    ): Outcome {
        val app = ctx.applicationContext
        val ch = pool(app)
        val index = ch.receive()
        val slot = all[index]
        try {
            return slot.mutex.withLock {
                withContext(Dispatchers.IO) {
                    var r = runIn(app, slot, timeoutMs, entries, check, onConnected, build)
                    if (r.harness) {
                        // The test broke, not the address: once more, on a fresh process.
                        Log.w(TAG, "slot ${slot.index}: ${r.error} -- once more on a fresh process")
                        retireNow(app, slot)
                        r = runIn(app, slot, timeoutMs, entries, check, onConnected, build)
                    }
                    slot.lastUsed = SystemClock.elapsedRealtime()
                    r
                }
            }
        } finally {
            ch.trySend(index)
            scheduleRetire(app, slot)
        }
    }

    private suspend fun runIn(
        app: Context,
        slot: Slot,
        timeoutMs: Int,
        entries: String?,
        check: ((String) -> Boolean)?,
        onConnected: (() -> Unit)?,
        build: (Int, File, Int) -> String,
    ): Outcome {
        if (!ensureOpen(app, slot)) return Outcome(false, error = tr("پردازش سنجش آماده نشد", "The test process did not start"), harness = true)
        val token = slot.token ?: return Outcome(false, error = tr("پردازش سنجش آماده نشد", "The test process did not start"), harness = true)
        val p = PsiphonProbeService
        val dir = File(app.filesDir, "psiphon-probe/${slot.index}/${++slot.seq}")
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()
        val port = runCatching { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort } }.getOrNull()
            ?: return Outcome(false, error = tr("پورت آزادی پیدا نشد", "No free port"), harness = true)
        val seconds = timeoutMs / 1000
        val config = build(port, dir, seconds + 10)
        var started = false
        try {
            val reply = runInterruptible {
                slot.client.call(app, p.START, args(token).apply {
                    putString(p.CONFIG, config)
                    if (entries != null) putString(p.ENTRIES, entries)
                }, 5_000)
            }
            if (reply?.getBoolean(p.OK) != true) {
                slot.token = null
                return Outcome(false, error = tr("سایفون شروع نشد", "Psiphon did not start"), harness = true)
            }
            started = true
            val t0 = SystemClock.elapsedRealtime()
            var dialed = ""
            // -1: the process is gone or stopped answering; -2: the core exited on its own (it is
            // given more time than this wait, so that is never the address's doing).
            val listening: Int? = withTimeoutOrNull(timeoutMs.toLong()) {
                var quietSince = 0L
                while (true) {
                    // Asked of a process that is gone, the call would bind a fresh one.
                    if (slot.client.deaths != slot.deathsAtOpen) return@withTimeoutOrNull -1
                    val st = runInterruptible { slot.client.call(app, p.STATUS, args(token), 2_000) }
                    val now = SystemClock.elapsedRealtime()
                    if (st == null) {
                        // A missed answer is not a dead core: a busy process's main thread can take
                        // longer than one call's wait. A death, or eight quiet seconds, is.
                        if (slot.client.deaths != slot.deathsAtOpen) return@withTimeoutOrNull -1
                        if (quietSince == 0L) quietSince = now else if (now - quietSince > 8_000) return@withTimeoutOrNull -1
                        delay(300)
                        continue
                    }
                    quietSince = 0L
                    // Another process answered (ours ended and the system started a fresh one).
                    if (!st.getBoolean(p.OK)) return@withTimeoutOrNull -1
                    when (st.getString(p.PHASE)) {
                        "connected" -> {
                            dialed = st.getString(p.IP).orEmpty()
                            return@withTimeoutOrNull st.getInt(p.PORT).takeIf { it > 0 } ?: port
                        }
                        "failed" -> return@withTimeoutOrNull -2
                    }
                    delay(300)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            val ms = SystemClock.elapsedRealtime() - t0
            when (listening) {
                null -> return Outcome(false, error = tr("در ${faNum(seconds)} ثانیه وصل نشد", "Did not connect in $seconds s"))
                -1 -> {
                    slot.token = null
                    return Outcome(false, error = tr("پردازش سنجش از دست رفت", "The test process was lost"), harness = true)
                }
                -2 -> return Outcome(false, error = tr("هستهٔ سایفون بسته شد", "The Psiphon core stopped"), harness = true)
            }
            if (check != null && dialed.isNotBlank() && !check(dialed)) {
                Log.w(TAG, "slot ${slot.index}: the tunnel came up through $dialed, not the address under test")
                return Outcome(false, error = tr("تونل از آدرس دیگری ساخته شد", "The tunnel came up through another address"), harness = true)
            }
            runCatching { onConnected?.invoke() }
            // The ping a page will feel: a whole HTTPS request through this tunnel, twice -- the first
            // pays the setup. A tunnel that carries nothing is not a result (desktop, 2026-10-14: an
            // IPv4 edge came up in 12 s and no request crossed it).
            trace(listening!!, 12_000)
            val t = trace(listening, 12_000)
            val secs = String.format(Locale.US, "%.1f", ms / 1000.0)
            return if (t.ok) Outcome(true, ms, t.rttMs, t.loc)
            else Outcome(false, ms, error = tr("تونل در ${faNum(secs)} ثانیه ساخته شد ولی هیچ داده‌ای رد نکرد",
                "The tunnel came up in $secs s but carried no data"))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (started) halt(app, slot)
                runCatching { dir.deleteRecursively() }
            }
        }
    }

    /** The core down, the process kept for the next test; a process that does not answer is let go. */
    private suspend fun halt(app: Context, slot: Slot) {
        val token = slot.token
        if (token == null || !alive(slot)) { retireNow(app, slot); return }
        val r = runInterruptible { slot.client.call(app, PsiphonProbeService.HALT, args(token), 6_000) }
        if (r?.getBoolean(PsiphonProbeService.OK) != true) retireNow(app, slot)
    }

    // ── the request through a tunnel ────────────────────────────────────────────────────────────

    /** Threads shared by every request; each request still gets its own connection ([trace]). */
    private val http by lazy { OkHttpClient() }

    /**
     * [Ipv4Exits.httpsTrace], but given up the moment the test is: «توقف» ended a run only after the
     * requests in flight had sat out their 12 s. A fresh connection every time, as before -- the
     * number is a whole request's time, setup included.
     */
    private suspend fun trace(socksPort: Int, timeoutMs: Long): Ipv4Exits.Trace {
        val t0 = System.nanoTime()
        fun elapsed() = (System.nanoTime() - t0) / 1_000_000
        val client = http.newBuilder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 3_000, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").header("User-Agent", "MLMVPN").build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(Ipv4Exits.Trace(false, rttMs = elapsed()))
                }

                override fun onResponse(call: Call, response: Response) {
                    val out = runCatching {
                        response.use { r ->
                            val body = r.body?.string().orEmpty()
                            fun field(k: String) = body.lineSequence().firstOrNull { it.startsWith("$k=") }?.substringAfter('=')?.trim()
                            val loc = field("loc")?.uppercase(Locale.US)
                            if (!r.isSuccessful || loc.isNullOrBlank()) Ipv4Exits.Trace(false, rttMs = elapsed())
                            else Ipv4Exits.Trace(true, loc, field("ip"), field("colo"), field("warp"), elapsed())
                        }
                    }.getOrElse { Ipv4Exits.Trace(false, rttMs = elapsed()) }
                    if (cont.isActive) cont.resume(out)
                }
            })
        }
    }

    /** Persian digits, for the Persian half of a sentence. */
    private fun faNum(n: Any): String = n.toString().map { c -> if (c in '0'..'9') '۰' + (c - '0') else if (c == '.') '٫' else c }.joinToString("")
}

/**
 * The test slots: [PsiphonProbeService]'s protocol, each in a process of its own (`:pstest0` …
 * `:pstest5`), apart from Scout's `:psiprobe` so a Scout run and a clean-IP test never meet. As many
 * are used as [PsiphonProbes.slots] allows.
 */
class PsiphonTestSlot0 : PsiphonProbeService()
class PsiphonTestSlot1 : PsiphonProbeService()
class PsiphonTestSlot2 : PsiphonProbeService()
class PsiphonTestSlot3 : PsiphonProbeService()
class PsiphonTestSlot4 : PsiphonProbeService()
class PsiphonTestSlot5 : PsiphonProbeService()
