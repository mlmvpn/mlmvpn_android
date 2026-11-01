package com.mlmvpn.core.geph

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * «گف»: Geph's own engine, driven the way the official Geph Android app drives it.
 *
 * Two kinds of process:
 *  - the MAIN engine, the one carrying traffic -- as the VPN (the TUN handed to it as its stdin),
 *    or as a local proxy;
 *  - a QUERY engine, `dry_run`, no ports, no account, started only while the Geph screens need the
 *    broker (account, voucher, exit list) and nothing is connected, and stopped a minute after
 *    the last question. While the main engine runs, questions go to it instead: its broker
 *    calls ride the tunnel, which is both faster and unblockable.
 *
 * What "connected" means here is the lesson from the desktop, where it was measured: not a
 * listening port and not the engine's word for it, but a TLS request that crossed the tunnel. That
 * request goes through a `port_forward` the engine itself serves on loopback, to one fixed
 * destination -- so it proves the data path without opening a general proxy on the phone that
 * any app could find and use.
 */
object GephEngine {

    private const val TAG = "GephEngine"

    /** ipstack-geph sizes its segments by 16384, whatever the TUN says; the TUN has to agree. */
    const val VPN_MTU = 16384
    const val TUN_V4 = "100.64.89.64"
    const val TUN_V4_PREFIX = 10
    const val TUN_V6 = "fd00::1"
    const val TUN_V6_PREFIX = 64
    /**
     * A PUBLIC resolver on purpose. The engine rewrites every UDP/53 to 1.1.1.1 and tunnels it
     * whatever the address, but a TCP retry to a private resolver would be refused at the exit
     * (allow.rs) -- so the one the phone is told about must be reachable there too.
     */
    const val TUN_DNS = "1.1.1.1"

    internal const val VERIFY_HOST = "www.gstatic.com"
    private const val GEO_HOST = "api.ip.sb"
    internal const val SPEED_HOST = "dl.google.com"
    // Measured 2026-10-03: through Geph's exits dl.google.com answers 404 for the platform-tools
    // zips (it serves them directly), and 206 for Chrome's installer -- so that is what is timed.
    const val SPEED_PATH = "/linux/direct/google-chrome-stable_current_amd64.deb"

    enum class Mode {
        /** The TUN is the engine's own stdin: its userspace stack, no hop in between. */
        VPN_FD,
        /** Android 7: no way to hand the TUN over, so tun2proxy bridges it to the local SOCKS. */
        VPN_BRIDGE,
        /** No TUN: SOCKS5/HTTP/PAC for the programs the user points at them. */
        PROXY,
    }

    data class Live(
        val running: Boolean = false,
        val connected: Boolean = false,
        val mode: Mode? = null,
        val sessions: List<GephSession> = emptyList(),
        val pingMs: Int? = null,
        val rx: Long = 0,
        val tx: Long = 0,
        val history: List<Double> = emptyList(),
        val exitIp: String? = null,
        val socksPort: Int = 0,
        val httpPort: Int = 0,
        val pacPort: Int = 0,
        val lanReachable: Boolean = false,
    ) {
        val exitCountry: String? get() = sessions.firstOrNull()?.country?.takeIf { it.isNotBlank() }
        val exitCity: String? get() = sessions.firstOrNull()?.city?.takeIf { it.isNotBlank() }
        val protocol: String? get() = sessions.firstOrNull()?.protocol?.takeIf { it.isNotBlank() }
    }

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    private val _log = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logFlow: SharedFlow<String> = _log.asSharedFlow()
    private val ring = ArrayDeque<String>()
    fun logs(): List<String> = synchronized(ring) { ring.toList() }

    fun dir(ctx: Context): File = File(ctx.noBackupFilesDir, "geph").apply { mkdirs() }
    private fun cacheDir(ctx: Context): File = File(dir(ctx), "cache").apply { mkdirs() }
    fun cacheFile(ctx: Context, cred: GephCredential): File = File(cacheDir(ctx), "db-" + cred.tag())
    private fun sock(ctx: Context, name: String): String = File(dir(ctx), "$name.sock").absolutePath

    fun available(ctx: Context): Boolean =
        com.mlmvpn.scanner.store.StoreEngines.available(ctx, "geph", "libgeph.so")

    // ── the main engine ─────────────────────────────────────────────────────────────────────────

    private data class Forwards(val verify: Int, val geo: Int, val speed: Int)

    @Volatile private var main: GephProcess? = null
    @Volatile private var mainControl: GephControl? = null
    @Volatile private var mainMode: Mode? = null
    @Volatile private var forwards: Forwards? = null
    @Volatile private var stopping = false
    private val mainLock = Any()

    /** Called by the engine's owner when the process died on its own; the owner decides. */
    @Volatile var onUnexpectedExit: ((code: Int) -> Unit)? = null

    val isMainAlive: Boolean get() = main?.isAlive == true
    val currentMode: Mode? get() = mainMode

    /**
     * Starts the main engine. [tun] is required for [Mode.VPN_FD]. [shareLan] binds the SOCKS and
     * HTTP proxies to every interface (for «شبکه محلی») instead of loopback.
     */
    fun startMain(ctx: Context, mode: Mode, tun: ParcelFileDescriptor?, shareLan: Boolean) {
        val app = ctx.applicationContext
        val cred = GephAccount.credential(app) ?: throw IllegalStateException(NO_ACCOUNT)
        synchronized(mainLock) {
            stopMainLocked()
            stopping = false
            // The query engine has nothing left to do once a real one runs (questions go to the
            // real one) -- unless a question is in flight right now, which it must be allowed to
            // finish. The idle timer picks it up afterwards.
            synchronized(queryLock) { if (users == 0) stopQueryNow() }
            val fw = Forwards(freePort(), freePort(), freePort())
            val bind = if (shareLan || (mode == Mode.PROXY && GephSettings.listenAll(app))) "0.0.0.0" else "127.0.0.1"
            val wantsProxy = mode != Mode.VPN_FD || shareLan
            val socks = if (wantsProxy) "$bind:${GephSettings.SOCKS_PORT}" else null
            val http = if (wantsProxy) "$bind:${GephSettings.HTTP_PORT}" else null
            val pac = if (mode == Mode.PROXY && GephSettings.pac(app)) "$bind:${GephSettings.PAC_PORT}" else null
            val control = sock(app, "ctl")
            File(control).delete()
            val run = GephRun(
                credential = cred,
                exit = GephSettings.exit(app),
                dryRun = false,
                controlSocket = control,
                cacheFile = cacheFile(app, cred).absolutePath,
                socks5 = socks,
                http = http,
                pac = pac,
                allowDirect = GephSettings.allowDirect(app),
                allowLan = GephSettings.allowLan(app),
                spoofDns = GephSettings.spoofDns(app),
                blockAds = GephSettings.blockAds(app),
                blockAdult = GephSettings.blockAdult(app),
                forwards = GephSettings.forwards(app) + listOf(
                    GephForward("127.0.0.1:${fw.verify}", "$VERIFY_HOST:443"),
                    GephForward("127.0.0.1:${fw.geo}", "$GEO_HOST:443"),
                    GephForward("127.0.0.1:${fw.speed}", "$SPEED_HOST:443"),
                ),
            )
            val cfg = File(dir(app), "config.json")
            cfg.writeText(GephConfig.build(run).toString(2))
            log("════════ Geph · ${mode.name.lowercase()} · exit ${run.exit.country ?: "auto"}${run.exit.city?.let { " · $it" } ?: ""} ════════")
            val proc = GephProcess.start(
                context = app,
                name = "main",
                configFile = cfg,
                workDir = dir(app),
                tun = if (mode == Mode.VPN_FD) tun else null,
                onLine = { line -> onEngineLine(line) },
                onExit = { code -> onMainExit(code) },
            )
            main = proc
            mainControl = GephControl(control)
            mainMode = mode
            forwards = fw
            _live.value = Live(
                running = true,
                mode = mode,
                socksPort = if (socks != null) GephSettings.SOCKS_PORT else 0,
                httpPort = if (http != null) GephSettings.HTTP_PORT else 0,
                pacPort = if (pac != null) GephSettings.PAC_PORT else 0,
                lanReachable = bind == "0.0.0.0",
            )
        }
    }

    fun stopMain() {
        synchronized(mainLock) { stopMainLocked() }
    }

    private fun stopMainLocked() {
        val proc = main ?: return
        stopping = true
        proc.stop(mainControl)
        main = null
        mainControl = null
        mainMode = null
        forwards = null
        _live.value = Live()
    }

    private fun onMainExit(code: Int) {
        val wasStopping = stopping
        log(if (wasStopping) "Geph stopped." else "Geph exited on its own (code $code).")
        if (!wasStopping) {
            _live.value = _live.value.copy(running = false, connected = false)
            onUnexpectedExit?.invoke(code)
        }
    }

    fun mainControl(): GephControl? = mainControl?.takeIf { isMainAlive }

    /**
     * Waits until the engine has a session to its exit. Returns the engine's view, or null on
     * timeout / cancel / exit. Progress (0..100) is reported from the engine's own milestones.
     */
    fun awaitConnected(timeoutMs: Long, cancelled: () -> Boolean, onProgress: (Int, String) -> Unit): GephConnInfo? {
        val control = mainControl ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var reported = -1
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancelled() || !isMainAlive) return null
            val info = runCatching { control.connInfo(3_000) }.getOrNull()
            if (info?.connected == true) return info
            val p = milestone
            if (p != reported) {
                reported = p
                onProgress(p, milestoneText)
            }
            Thread.sleep(400)
        }
        return null
    }

    /** A real TLS request across the tunnel -- the definition of "connected". */
    fun verifyThroughTunnel(timeoutMs: Int = 15_000): Boolean {
        val port = forwards?.verify ?: return false
        return runCatching {
            val (status, _) = httpsViaForward(port, VERIFY_HOST, "/generate_204", timeoutMs, maxBody = 0)
            status in 200..399
        }.onFailure { log("data check: ${it.message}") }.getOrDefault(false)
    }

    /** The exit's public address and country, as sites see it. */
    fun measureExit(timeoutMs: Int = 12_000): Pair<String, String?>? {
        val port = forwards?.geo ?: return null
        return runCatching {
            val (status, body) = httpsViaForward(port, GEO_HOST, "/geoip", timeoutMs, maxBody = 4096)
            if (status != 200) return null
            val o = JSONObject(body)
            val ip = o.optString("ip").takeIf { it.isNotBlank() } ?: return null
            ip to o.optString("country_code").takeIf { it.length == 2 }?.uppercase()
        }.getOrNull()
    }

    data class Speed(val ttfbMs: Long, val bytes: Long, val millis: Long) {
        val mbit: Double get() = if (millis <= 0) 0.0 else bytes * 8.0 / millis / 1000.0
    }

    /**
     * Downloads [bytes] from Google's download CDN through the tunnel. Google, not Cloudflare:
     * speed.cloudflare.com is answered at the edge and measures nothing (see the desktop notes).
     */
    fun speedTest(bytes: Int = 1_048_576, timeoutMs: Int = 30_000): Speed? {
        val port = forwards?.speed ?: return null
        return runCatching {
            val started = SystemClock.elapsedRealtime()
            var firstByte = 0L
            val (status, _) = httpsViaForward(
                port, SPEED_HOST, SPEED_PATH, timeoutMs, maxBody = bytes,
                extraHeaders = "Range: bytes=0-${bytes - 1}\r\n",
                onFirstByte = { firstByte = SystemClock.elapsedRealtime() - started },
                onBody = { },
            )
            if (status !in 200..299) return null
            Speed(firstByte, lastBodyBytes, SystemClock.elapsedRealtime() - started)
        }.getOrNull()
    }

    /** One sample for the live screen and the notification. Cheap: a few unix-socket calls. */
    fun sample(withConn: Boolean): Live {
        val control = mainControl() ?: return _live.value.copy(running = false, connected = false)
        val rx = runCatching { control.statNum("total_rx_bytes").toLong() }.getOrDefault(_live.value.rx)
        val tx = runCatching { control.statNum("total_tx_bytes").toLong() }.getOrDefault(_live.value.tx)
        var next = _live.value.copy(running = true, rx = rx, tx = tx)
        if (withConn) {
            val info = runCatching { control.connInfo(3_000) }.getOrNull()
            val ping = runCatching { control.statNum("ping") }.getOrNull()
            if (info != null) next = next.copy(connected = info.connected, sessions = info.sessions)
            if (ping != null && ping > 0) next = next.copy(pingMs = (ping * 1000).toInt())
        }
        _live.value = next
        return next
    }

    fun history(): List<Double> {
        val h = runCatching { mainControl()?.statHistory() }.getOrNull().orEmpty()
        if (h.isNotEmpty()) _live.value = _live.value.copy(history = h)
        return h
    }

    fun setExitIp(ip: String?) {
        _live.value = _live.value.copy(exitIp = ip)
    }

    // ── the query engine: for account work and the exit list ──────────────────────────────────

    @Volatile private var query: GephProcess? = null
    @Volatile private var queryControl: GephControl? = null
    private val queryLock = Any()
    private var users = 0
    private var idleStop: ScheduledFuture<*>? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "geph-query-timer").apply { isDaemon = true } }

    /**
     * Runs [block] against an engine that can answer broker questions: the connected one if there
     * is one, otherwise a dry-run engine started for the purpose. Blocking -- call it off the main
     * thread.
     */
    fun <T> withControl(ctx: Context, block: (GephControl) -> T): T {
        mainControl()?.let { return block(it) }
        val control = synchronized(queryLock) {
            users++
            idleStop?.cancel(false)
            idleStop = null
            ensureQueryLocked(ctx.applicationContext)
        }
        try {
            return block(control)
        } finally {
            synchronized(queryLock) {
                users--
                if (users == 0) {
                    idleStop = timer.schedule({ synchronized(queryLock) { if (users == 0) stopQueryNow() } }, 20, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun ensureQueryLocked(app: Context): GephControl {
        queryControl?.takeIf { query?.isAlive == true }?.let { return it }
        if (!available(app)) throw IllegalStateException(NOT_INSTALLED)
        val control = sock(app, "query")
        File(control).delete()
        val run = GephRun(
            credential = null,
            exit = GephExitChoice.AUTO,
            dryRun = true,
            controlSocket = control,
            cacheFile = File(cacheDir(app), "query-db").absolutePath,
        )
        val cfg = File(dir(app), "config-query.json")
        cfg.writeText(GephConfig.build(run).toString(2))
        val proc = GephProcess.start(app, "query", cfg, dir(app), null, onLine = { onEngineLine(it) }, onExit = {})
        val c = GephControl(control)
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline && proc.isAlive) {
            if (c.reachable()) {
                query = proc
                queryControl = c
                return c
            }
            Thread.sleep(250)
        }
        proc.stop(null)
        throw IllegalStateException(ENGINE_DID_NOT_START)
    }

    /**
     * The app went to the background: a query engine nobody is using goes now. It used to run on
     * for a minute after the last question, as a process of its own with nothing in front of it --
     * and the system killed the whole app for that child's CPU use (1.2.41, after the Geph screen).
     */
    fun onAppBackground() {
        synchronized(queryLock) {
            if (users == 0) {
                idleStop?.cancel(false)
                idleStop = null
                stopQueryNow()
            }
        }
    }

    private fun stopQueryNow() {
        val proc = query ?: return
        query = null
        val c = queryControl
        queryControl = null
        Thread({ proc.stop(c) }, "geph-query-stop").start()
    }

    // ── logs and milestones ─────────────────────────────────────────────────────────────────────

    @Volatile private var milestone = 5
    @Volatile private var milestoneText = "starting"

    private fun onEngineLine(line: String) {
        val lower = line.lowercase()
        // Milestones, in the order a cold connect meets them; only ever forwards.
        val (p, what) = when {
            "tunnel open" in lower || "session established" in lower -> 90 to "tunnel"
            "opening tunnel" in lower || "dialing" in lower && "exit" in lower -> 70 to "dialing"
            "exit route obtained" in lower || "route" in lower && "obtained" in lower -> 55 to "route"
            "auth" in lower && "token" in lower -> 35 to "auth"
            "broker" in lower -> 20 to "broker"
            else -> -1 to ""
        }
        if (p > milestone) {
            milestone = p
            milestoneText = what
        }
        if (NOISE.containsMatchIn(line)) return
        log(line)
    }

    fun resetMilestones() {
        milestone = 5
        milestoneText = "starting"
    }

    fun log(line: String) {
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > 400) ring.removeFirst()
        }
        _log.tryEmit(line)
        Log.i(TAG, line)
    }

    /** The race's losers: forty "failures" per successful connect at debug level. */
    private val NOISE = Regex("dial stage failed|returning unexpired cached|calling broker through Geph", RegexOption.IGNORE_CASE)

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────

    internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Volatile internal var lastBodyBytes = 0L
        private set

    /**
     * One HTTPS GET to [host] through the engine's loopback forward on [port]: TLS with the real
     * name for SNI and certificate checking, so a forward that went somewhere else fails.
     */
    internal fun httpsViaForward(
        port: Int,
        host: String,
        path: String,
        timeoutMs: Int,
        maxBody: Int,
        extraHeaders: String = "",
        onFirstByte: () -> Unit = {},
        onBody: (Int) -> Unit = {},
    ): Pair<Int, String> {
        val raw = Socket()
        raw.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        raw.soTimeout = timeoutMs
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
        ssl.use { s ->
            s.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) {
                throw javax.net.ssl.SSLPeerUnverifiedException("certificate is not for $host")
            }
            s.outputStream.write(
                ("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\n" +
                    "Accept: */*\r\n${extraHeaders}Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            s.outputStream.flush()
            val input = s.inputStream
            val head = StringBuilder()
            var first = true
            // Header, byte by byte up to the blank line.
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (first) { onFirstByte(); first = false }
                head.append(b.toChar())
                if (head.endsWith("\r\n\r\n")) break
                if (head.length > 16_384) break
            }
            val status = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
            var total = 0L
            val body = StringBuilder()
            if (maxBody > 0) {
                val buf = ByteArray(16_384)
                while (total < maxBody) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    onBody(n)
                    if (body.length < 8192) body.append(String(buf, 0, minOf(n, 8192 - body.length), Charsets.UTF_8))
                }
            }
            lastBodyBytes = total
            // A chunked JSON answer carries its size line first; the object is what we want.
            val text = body.toString()
            val json = text.indexOf('{').let { i -> if (i >= 0) text.substring(i, text.lastIndexOf('}') + 1) else text }
            return status to json
        }
    }

    const val NO_ACCOUNT = "GEPH_NO_ACCOUNT"
    const val NOT_INSTALLED = "GEPH_NOT_INSTALLED"
    const val ENGINE_DID_NOT_START = "GEPH_ENGINE_DID_NOT_START"
}
