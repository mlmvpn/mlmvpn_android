package com.mlmvpn.core.warp

import com.mlmvpn.scanner.utils.alive
import com.mlmvpn.scanner.utils.killHard
import com.mlmvpn.scanner.utils.waitUpTo

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mlmvpn.core.aether.AetherIp
import com.mlmvpn.core.aether.AetherOptions
import com.mlmvpn.core.aether.AetherProtocol
import com.mlmvpn.core.aether.AetherScan
import com.mlmvpn.core.aether.AetherStage
import com.mlmvpn.core.aether.AetherStageParser
import com.mlmvpn.scanner.store.StoreEngines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * «وارپ»: plain Cloudflare WARP, as its own engine -- the Windows app's `warp-manager.js`.
 *
 * Its own everything: its own Cloudflare identity (made with `warp_enabled` switched on, which a
 * fresh registration is not), its own data directory, its own SOCKS port, its own lifecycle and
 * its own definition of "connected". None of the core that carries MASQUE, WireGuard and
 * WARP-in-WARP on this phone is involved, so when that core is in trouble this still connects --
 * the reason it exists.
 *
 * The data plane is the Aether binary's WireGuard (`AETHER_PROTOCOL=wg`), exactly as on the
 * desktop: it is the one WARP data plane measured to carry traffic on Iranian lines, because it
 * obfuscates the handshake (plain WireGuard measured zero bytes in 170 s there). It scans
 * Cloudflare's own ranges for an endpoint and serves a SOCKS5 port; the TUN reaches that port
 * through tun2proxy.
 *
 * "Connected" is a real HTTPS request through that port to Cloudflare's trace page answering
 * `warp=on` -- never the handshake, never the engine's own word: an endpoint can pass both and
 * carry nothing, which was measured again and again.
 */
object CfWarpEngine {

    private const val TAG = "CfWarpEngine"

    /** «وارپ»'s SOCKS port -- the same one the desktop engine uses. */
    const val SOCKS_PORT = 20870

    private const val PREFS = "cfwarp"

    data class Live(
        val running: Boolean = false,
        val stage: AetherStage = AetherStage.IDLE,
        val stageText: String = "",
        val edge: String? = null,
        val colo: String? = null,
        val exitIp: String? = null,
        val loc: String? = null,
        val verifiedAt: Long = 0L,
    )

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    private val ring = ArrayDeque<String>()
    private val logRedactor = com.mlmvpn.scanner.utils.SecretRedactor.Stream()
    fun logs(): List<String> = synchronized(ring) { ring.toList() }

    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    @Volatile var onUnexpectedExit: ((Int) -> Unit)? = null

    fun dataDir(ctx: Context): File = File(ctx.filesDir, "warp/data").apply { mkdirs() }
    private fun identityFile(ctx: Context) = File(dataDir(ctx), "aether.toml")

    fun available(ctx: Context): Boolean = StoreEngines.available(ctx, "aether", "libaether.so")
    val isAlive: Boolean get() = process?.alive() == true

    // ── settings: every one of them reaches the engine ─────────────────────────────────────────

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun scan(ctx: Context): AetherScan =
        runCatching { AetherScan.valueOf(p(ctx).getString("scan", AetherScan.BALANCED.name)!!) }.getOrDefault(AetherScan.BALANCED)
    fun setScan(ctx: Context, v: AetherScan) = p(ctx).edit().putString("scan", v.name).apply()

    fun ip(ctx: Context): AetherIp =
        runCatching { AetherIp.valueOf(p(ctx).getString("ip", AetherIp.V4.name)!!) }.getOrDefault(AetherIp.V4)
    fun setIp(ctx: Context, v: AetherIp) = p(ctx).edit().putString("ip", v.name).apply()

    /** "" leaves the engine's own default (obfuscated). "off" is plain WireGuard. */
    fun noize(ctx: Context): String = p(ctx).getString("noize", "").orEmpty()
    fun setNoize(ctx: Context, v: String) = p(ctx).edit().putString("noize", v).apply()

    /** 25 s, the desktop's figure: under the ~30 s a mobile carrier keeps an idle UDP mapping. */
    fun keepalive(ctx: Context): Int = p(ctx).getInt("keepalive", 25)
    fun setKeepalive(ctx: Context, v: Int) = p(ctx).edit().putInt("keepalive", v.coerceIn(5, 120)).apply()

    // ── identity ────────────────────────────────────────────────────────────────────────────────

    fun hasIdentity(ctx: Context): Boolean = com.mlmvpn.scanner.data.CfIdentityFiles.exists(identityFile(ctx))

    data class IdentityInfo(val via: String?, val at: Long, val deviceId: String?)

    fun identityInfo(ctx: Context): IdentityInfo = IdentityInfo(
        via = p(ctx).getString("id_via", null),
        at = p(ctx).getLong("id_at", 0L),
        deviceId = p(ctx).getString("id_device", null),
    )

    /** Registers «وارپ»'s own identity when it has none. Throws with a readable reason. */
    private val identityDeadline = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "warp-identity-deadline").apply { isDaemon = true }
    }
    fun ensureIdentity(ctx: Context, cancelled: () -> Boolean = { false }) {
        if (hasIdentity(ctx)) return
        if (cancelled()) throw java.io.InterruptedIOException("identity_cancelled")
        val owner = Thread.currentThread()
        val expired = System.nanoTime() + 90_000_000_000L
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val timer = identityDeadline.scheduleAtFixedRate({
            if (cancelled() || System.nanoTime() >= expired) { interrupted.set(true); owner.interrupt() }
        }, 100, 100, TimeUnit.MILLISECONDS)
        try {
        log("Identity: registering a WARP identity of its own…")
        val s = WarpIdRelay.registerStandalone(ctx)
        if (cancelled() || owner.isInterrupted) throw java.io.InterruptedIOException("identity_cancelled")
        val f = identityFile(ctx)
        com.mlmvpn.scanner.data.CfIdentityFiles.save(ctx, f, s.toml)
        p(ctx).edit()
            .putString("id_via", s.via)
            .putLong("id_at", System.currentTimeMillis())
            .putString("id_device", s.deviceId)
            .apply()
        log("Identity: made (${s.via}), WARP enabled")
        } finally { timer.cancel(false); if (interrupted.get()) Thread.interrupted() }
    }

    /** A fresh identity next time: the account, and every endpoint the engine remembered for it. */
    fun resetIdentity(ctx: Context) {
        dataDir(ctx).listFiles()?.forEach { if (it.name.endsWith(".toml") || it.name.endsWith(".toml.sealed")) it.delete() }
        p(ctx).edit().remove("id_via").remove("id_at").remove("id_device").apply()
    }

    /**
     * Forgets the endpoint the engine would try first. After a dead endpoint this is what makes
     * the next scan land somewhere else -- the engine's own avoid list lives in the process and
     * dies with it, and it takes no variable to steer from outside.
     */
    fun dropLastEndpoint(ctx: Context) {
        File(dataDir(ctx), "aether-lastconn.toml").delete()
    }

    // ── the process ─────────────────────────────────────────────────────────────────────────────

    /**
     * The NAT64 endpoint this run is pinned to, or null when it scans (the direct behaviour).
     * Set by [start]; read back to report how the gateway did (see [Nat64Exits.reportOutcome]).
     */
    @Volatile var currentPeer: String? = null
        private set

    /**
     * @param peer a pinned endpoint -- a NAT64 gateway of the chosen exit country -- or null to scan.
     *   Defaults to the best gateway for the current selection, which is null for the direct one.
     */
    fun start(ctx: Context, shareLan: Boolean, peer: String? = Nat64Exits.pick(ctx, Nat64Exits.Proto.WG)) {
        stop()
        stopping = false
        val app = ctx.applicationContext
        val dir = dataDir(app)
        currentPeer = peer
        val opts = AetherOptions(
            protocol = AetherProtocol.WG,
            scan = scan(app),
            ipFamily = ip(app),
            noize = noize(app),
            keepalive = keepalive(app),
            // A pinned gateway is the whole point of the run; a remembered endpoint from a direct
            // session must not be "quick-reconnected" to instead.
            quickReconnect = peer == null,
            wgPeer = peer,
        )
        val env = opts.toEnv(System.getenv(), dir.absolutePath, SOCKS_PORT).toMutableMap()
        if (shareLan) env["AETHER_SOCKS"] = "0.0.0.0:$SOCKS_PORT"
        val pb = ProcessBuilder(StoreEngines.command(app, "aether", "libaether.so"))
            .directory(dir)
            .redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env)
        log("════════ وارپ · scan ${opts.scan.env} · ip ${opts.ipFamily.env} · noize ${opts.noize.ifBlank { "default" }}${peer?.let { " · NAT64 $it" } ?: ""} ════════")
        val proc = com.mlmvpn.scanner.data.CfIdentityFiles.guarded {
            try {
                com.mlmvpn.scanner.data.CfIdentityFiles.restoreDirectory(app, dir)
                pb.start().also { process = it }
            }
            catch (e: Exception) { com.mlmvpn.scanner.data.CfIdentityFiles.sealDirectory(app, dir); throw e }
        }
        _live.value = Live(running = true, stage = AetherStage.STARTING, stageText = AetherStage.STARTING.fa)
        Thread({
            try {
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        onLine(line)
                    }
                }
            } catch (_: Exception) {
            }
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            runCatching { com.mlmvpn.scanner.data.CfIdentityFiles.sealDirectory(app, dir, maySeal = { process == null || process === proc }) }
            com.mlmvpn.scanner.data.CfIdentityFiles.guarded {
                if (process === proc) {
                    process = null
                    if (!stopping) {
                        log("The WARP engine exited on its own (code $code)")
                        _live.value = _live.value.copy(running = false, stage = AetherStage.CRASHED, stageText = AetherStage.CRASHED.fa)
                        onUnexpectedExit?.invoke(code)
                    }
                }
            }
        }, "cfwarp-engine").apply { isDaemon = true }.start()
    }

    fun stop() {
        val proc = process ?: return
        stopping = true
        process = null
        proc.destroy()
        // Its port has to be free before a restart binds it again.
        if (!runCatching { proc.waitUpTo(3, TimeUnit.SECONDS) }.getOrDefault(false)) proc.killHard()
        _live.value = Live()
    }

    private val EDGE = Regex("""(?:using cloudflare edge|connected to|endpoint)\s+([0-9a-fA-F.:\[\]]+:\d+)""", RegexOption.IGNORE_CASE)

    private fun onLine(raw: String) {
        val line = raw.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "").trim()
        if (line.isEmpty()) return
        // Per-candidate prober lines are trace-level noise here; the stage is what matters.
        if ("aether::prober" in line && "TRACE" in line) return
        AetherStageParser.classify(line)?.let { (stage, fa) ->
            val cur = _live.value
            if (stage.isRegression || stage.progressRank > cur.stage.progressRank) {
                _live.value = cur.copy(stage = stage, stageText = fa)
            }
        }
        EDGE.find(line)?.let { _live.value = _live.value.copy(edge = it.groupValues[1]) }
        log(line)
    }

    // ── "connected" ─────────────────────────────────────────────────────────────────────────────

    data class Trace(val warpOn: Boolean, val colo: String?, val ip: String?, val loc: String?)

    /** Cloudflare's trace page through «وارپ»'s own port. */
    fun verify(timeoutMs: Long = 8_000): Trace? = runCatching {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SOCKS_PORT)))
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
            .build()
        client.newCall(
            Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").header("User-Agent", "mlm-warp").build(),
        ).execute().use { r ->
            if (!r.isSuccessful) return@runCatching null
            val body = r.body?.string().orEmpty()
            fun field(k: String) = Regex("(?m)^$k=(.*)$").find(body)?.groupValues?.get(1)?.trim()
            Trace(field("warp")?.startsWith("on") == true, field("colo"), field("ip"), field("loc"))
        }
    }.getOrNull()

    /**
     * Waits for traffic to really cross: first check at 6 s, then every 4 s, inside [budgetMs]
     * (150 s on the desktop, which covers a full rescan). Returns the trace, or null.
     */
    fun awaitVerified(budgetMs: Long, cancelled: () -> Boolean, onProgress: (Int, String) -> Unit): Trace? {
        val start = SystemClock.elapsedRealtime()
        var next = start + 6_000
        while (SystemClock.elapsedRealtime() - start < budgetMs) {
            if (cancelled() || !isAlive) return null
            val s = _live.value
            val rank = s.stage.progressRank.coerceAtLeast(0)
            onProgress((10 + rank * 10).coerceAtMost(92), s.stageText)
            if (SystemClock.elapsedRealtime() >= next) {
                val t = verify()
                if (t?.warpOn == true) {
                    _live.value = _live.value.copy(
                        stage = AetherStage.CONNECTED, stageText = AetherStage.CONNECTED.fa,
                        colo = t.colo, exitIp = t.ip, loc = t.loc, verifiedAt = System.currentTimeMillis(),
                    )
                    log("✅ Connected (exit: ${t.colo ?: "?"}) — SOCKS on 127.0.0.1:$SOCKS_PORT")
                    return t
                }
                next = SystemClock.elapsedRealtime() + 4_000
            }
            Thread.sleep(500)
        }
        return null
    }

    fun markVerified(t: Trace) {
        _live.value = _live.value.copy(colo = t.colo, exitIp = t.ip, loc = t.loc, verifiedAt = System.currentTimeMillis())
    }

    fun log(line: String) {
        val line = logRedactor.redact(line)
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > 400) ring.removeFirst()
        }
        Log.i(TAG, line)
    }
}
