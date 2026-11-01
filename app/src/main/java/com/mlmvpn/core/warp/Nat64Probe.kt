package com.mlmvpn.core.warp

import com.mlmvpn.scanner.utils.alive
import com.mlmvpn.scanner.utils.killHard
import com.mlmvpn.scanner.utils.waitUpTo

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mlmvpn.core.aether.AetherOptions
import com.mlmvpn.core.aether.AetherProtocol
import com.mlmvpn.scanner.store.StoreEngines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The ping on the country list: a real, short WARP connection through every gateway at once.
 *
 * Not an ICMP or TCP ping, because neither says anything here: measured on 2026-10-03, the US
 * gateway refused TCP and carried WARP over UDP perfectly, and a plain WireGuard handshake gets
 * no answer at all on a filtered Iranian line. So each gateway gets the real thing -- the same
 * Aether engine «وارپ» runs, with its endpoint pinned to that gateway -- and is measured through
 * it: Cloudflare's trace (proof that data crosses, and the exit country it really gives), the round
 * trip of a second request on the open connection, and a short download for the speed.
 *
 * The engine runs with an identity of its own (made once, kept in `warp/probe`), so a ping never
 * touches the session of a tunnel that is up with «وارپ»'s identity.
 */
object Nat64Probe {

    private const val TAG = "Nat64Probe"
    private const val BASE_PORT = 20900
    private const val PARALLEL = 4
    private const val UP_BUDGET_MS = 22_000L

    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** Gateway keys being measured right now. */
        val testing: Set<String> = emptySet(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    private val live = java.util.Collections.synchronizedSet(HashSet<Process>())

    fun cancel() {
        job?.cancel()
        synchronized(live) { live.forEach { runCatching { it.killHard() } }; live.clear() }
        _state.value = _state.value.copy(running = false, testing = emptySet())
    }

    /** Pings [only] (a country's gateways) or every gateway. */
    fun start(ctx: Context, only: List<Nat64Exits.Gateway>? = null) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            val list = only ?: Nat64Exits.gateways(app)
            _state.value = State(running = true, total = list.size)
            try {
                if (!CfWarpEngine.available(app)) error("engine")
                if (!com.mlmvpn.scanner.data.CfFamily.hasIpv6Route()) error("no_ipv6")
                val identity = probeIdentity(app)
                val gate = Semaphore(PARALLEL)
                list.mapIndexed { i, gw ->
                    async {
                        gate.withPermit {
                            if (!isActive) return@withPermit
                            _state.value = _state.value.let { it.copy(testing = it.testing + gw.key) }
                            val r = measure(app, gw, identity, BASE_PORT + i % 64)
                            Nat64Exits.recordProbe(app, gw, r)
                            _state.value = _state.value.let { it.copy(testing = it.testing - gw.key, done = it.done + 1) }
                        }
                    }
                }.awaitAll()
                _state.value = _state.value.copy(running = false, testing = emptySet())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ping failed: ${e.message}")
                _state.value = _state.value.copy(running = false, testing = emptySet(), error = e.message)
            }
        }
    }

    // ── the identity ────────────────────────────────────────────────────────────────────────────

    private fun probeDir(ctx: Context) = File(ctx.filesDir, "warp/probe").apply { mkdirs() }

    /**
     * The measurements' own WARP identity, as the engine's TOML: made once, shared by every short-lived
     * engine that is not «وارپ» itself (this ping, [WarpLeg]) so none of them takes «وارپ»'s session.
     */
    fun identityToml(ctx: Context): String = synchronized(this) { probeIdentity(ctx.applicationContext) }

    /**
     * Its own identity when one can be made, «وارپ»'s otherwise -- a ping should not fail on that.
     * The text, not a file: «وارپ»'s identity is kept sealed between sessions (aether.toml.sealed),
     * and copying the plain file that is not there crashed every ping (1.2.40, crash #453).
     */
    private fun probeIdentity(ctx: Context): String {
        val f = File(probeDir(ctx), "identity.toml")
        if (f.exists() && f.length() > 0) return f.readText()
        runCatching {
            val s = WarpIdRelay.registerStandalone(ctx)
            f.writeText(s.toml)
            return s.toml
        }.onFailure { Log.w(TAG, "own identity: ${it.message}; using «وارپ»'s") }
        CfWarpEngine.ensureIdentity(ctx)
        val warp = File(CfWarpEngine.dataDir(ctx), "aether.toml")
        return runCatching {
            com.mlmvpn.scanner.data.CfIdentityFiles.restore(ctx, warp)
            warp.takeIf { it.isFile && it.length() > 0 }?.readText()
        }.getOrNull() ?: error("identity")
    }

    // ── one gateway ─────────────────────────────────────────────────────────────────────────────

    private fun measure(ctx: Context, gw: Nat64Exits.Gateway, identity: String, port: Int): Nat64Exits.Probe {
        val endpoint = Nat64Exits.endpoint(gw, Nat64Exits.Proto.WG) ?: return Nat64Exits.Probe(ok = false)
        // A data directory per run: the engine writes its last endpoint there, and parallel runs
        // must not share one.
        val dir = File(probeDir(ctx), "run$port").apply { deleteRecursively(); mkdirs() }
        File(dir, "aether.toml").writeText(identity)
        val opts = AetherOptions(
            protocol = AetherProtocol.WG,
            noize = CfWarpEngine.noize(ctx),
            keepalive = CfWarpEngine.keepalive(ctx),
            quickReconnect = false,
            wgPeer = endpoint,
        )
        val env = opts.toEnv(System.getenv(), dir.absolutePath, port)
        val pb = ProcessBuilder(StoreEngines.command(ctx, "aether", "libaether.so"))
            .directory(dir).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env)
        val proc = runCatching { pb.start() }.getOrElse { return Nat64Exits.Probe(ok = false) }
        live += proc
        try {
            val up = java.util.concurrent.atomic.AtomicBoolean(false)
            Thread({
                runCatching {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            if ("socks5 server listening" in line) up.set(true)
                        }
                    }
                }
            }, "nat64-probe-$port").apply { isDaemon = true }.start()
            val start = SystemClock.elapsedRealtime()
            while (!up.get() && proc.alive() && SystemClock.elapsedRealtime() - start < UP_BUDGET_MS) Thread.sleep(200)
            if (!up.get()) return Nat64Exits.Probe(ok = false)

            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                .build()
            fun trace(): String? = runCatching {
                client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build())
                    .execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull()
            val body = trace() ?: return Nat64Exits.Probe(ok = false)
            fun field(k: String) = Regex("(?m)^$k=(.*)$").find(body)?.groupValues?.get(1)?.trim()
            if (field("warp")?.startsWith("on") != true) return Nat64Exits.Probe(ok = false)
            // The second request rides the connection the first one opened: its time is the
            // round trip, not a TLS handshake.
            val t0 = SystemClock.elapsedRealtime()
            val rtt = if (trace() != null) SystemClock.elapsedRealtime() - t0 else null
            return Nat64Exits.Probe(
                ok = true, rttMs = rtt, mbps = speed(client),
                exitLoc = field("loc"), colo = field("colo"),
            )
        } finally {
            live -= proc
            proc.destroy()
            if (!runCatching { proc.waitUpTo(2, TimeUnit.SECONDS) }.getOrDefault(false)) proc.killHard()
            dir.deleteRecursively()
        }
    }

    /** Megabits per second over at most five seconds of a 2 MB download. */
    private fun speed(client: OkHttpClient): Double? = runCatching {
        val start = SystemClock.elapsedRealtime()
        var got = 0L
        client.newCall(Request.Builder().url("https://speed.cloudflare.com/__down?bytes=2000000").build())
            .execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val src = r.body?.byteStream() ?: return@runCatching null
                val buf = ByteArray(16 * 1024)
                while (SystemClock.elapsedRealtime() - start < 5_000) {
                    val n = src.read(buf)
                    if (n < 0) break
                    got += n
                }
            }
        val secs = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1) / 1000.0
        if (got < 32 * 1024) null else got * 8 / secs / 1_000_000
    }.getOrNull()
}
