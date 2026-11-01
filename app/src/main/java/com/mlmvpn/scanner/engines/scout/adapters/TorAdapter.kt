package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import com.mlmvpn.core.tunnel.TorManager.TorMode
import com.mlmvpn.core.tunnel.bridge.BridgeManager
import com.mlmvpn.core.tunnel.bridge.BridgeTransport
import com.mlmvpn.scanner.engines.scout.*
import com.mlmvpn.scanner.store.StoreEngines
import com.mlmvpn.scanner.store.StoreManager
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.utils.alive
import com.mlmvpn.scanner.utils.killHard
import com.mlmvpn.scanner.utils.waitUpTo
import kotlinx.coroutines.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object TorAdapter : ScoutAdapter() {
    override val id = "tor"
    override val homeId = "tor"
    override val kind = MethodKind.TUNNEL
    // Tor no longer has a WARP chain (two results, no «inside WARP»): it waits for nothing of WARP's.
    override val lanes = setOf(Lane.TOR)
    override val estimateSec = 600
    override val estimateMb = 8.0
    override val speedBudgetMs = 650_000L
    override fun readiness(ctx: Context): Readiness = Readiness.Ready
    override fun howTested() = tr(
        "تور مثل خود کاشی دو نتیجه دارد، هرکدام در پردازش مستقل و بدون تغییر تنظیمات شما: «پل‌ها» با پل‌های خودکار (بهترین پل‌های آزموده از همهٔ منبع‌ها: obfs4، وب‌تانل، ساده) و کشور خروج خودکار؛ و «پایه» که وب‌تانل، meek، اسنوفلیک و obfs4 را به ترتیب امتحان می‌کند. هرکدام وصل شود، نتیجه می‌گوید از چه راهی و کشور خروجی کجاست.",
        "Tor has two results, like its tile, each run independently without changing your settings: «Bridges» with automatic bridges (the best tested from every source: obfs4, WebTunnel, vanilla) and an automatic exit country; and «Base», which tries WebTunnel, meek, Snowflake and obfs4 in turn. Whichever connects, the result says which way, and the exit country.")

    /**
     * Two results, as the tile is laid out (user request 2026-10-11): its «پل‌ها» row on automatic
     * with the exit country on automatic, and its connection modes. Each used to be a result of its
     * own (direct, obfs4, meek, WebTunnel, Snowflake, vanilla, inside WARP) and said nothing about
     * which way the tile itself would get through.
     */
    override fun variants(ctx: Context, line: LineSnapshot): List<Variant> = listOf(
        Variant("bridges", tr("پل‌ها · سرور خودکار · کشور خروج خودکار", "Bridges · automatic server · automatic exit"),
            budgetMs = 900_000, payload = BRIDGES),
        Variant("base", tr("پایه · وب‌تانل، meek، اسنوفلیک، obfs4", "Base · WebTunnel, meek, Snowflake, obfs4"),
            budgetMs = 900_000, payload = BASE),
    )

    private const val BRIDGES = "bridges"
    private const val BASE = "base"
    private val BRIDGE_ORDER = listOf(TorMode.OBFS4, TorMode.WEBTUNNEL, TorMode.VANILLA)
    private val BASE_ORDER = listOf(TorMode.WEBTUNNEL, TorMode.MEEK, TorMode.SNOWFLAKE, TorMode.OBFS4)
    /** Each way's own time to bootstrap: four of them in one result must still end in the budget. */
    private const val MODE_BOOTSTRAP_MS = 180_000L

    private data class Mode(val mode: TorMode, val transport: String?, val bridges: List<String>)
    private class Handle(val modes: List<Mode>, val kind: String, val warp: Prepared.Ready? = null) : LiveDetail {
        /** The way trying now, and once one connects, the way that did. */
        @Volatile var via: String? = null
        override val liveDetail: String? get() = via
    }
    private fun transport(mode: TorMode): BridgeTransport? = when (mode) {
        TorMode.OBFS4 -> BridgeTransport.OBFS4
        TorMode.MEEK -> BridgeTransport.MEEK
        TorMode.WEBTUNNEL -> BridgeTransport.WEBTUNNEL
        TorMode.SNOWFLAKE -> BridgeTransport.SNOWFLAKE
        TorMode.VANILLA -> BridgeTransport.VANILLA
        TorMode.AUTO, TorMode.DIRECT -> null
    }
    private fun plugin(mode: TorMode): String? = when (mode) {
        TorMode.OBFS4 -> "obfs4"
        TorMode.MEEK -> "meek_lite"
        TorMode.WEBTUNNEL -> "webtunnel"
        TorMode.SNOWFLAKE -> "snowflake"
        TorMode.AUTO, TorMode.DIRECT, TorMode.VANILLA -> null
    }

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared = withContext(Dispatchers.IO) {
        val kind = v.payload as? String ?: BASE
        val modes = if (kind == BRIDGES) BRIDGE_ORDER else BASE_ORDER
        kit.step(Step.PREPARING, tr("آماده‌سازی خودکار تور…", "Preparing Tor automatically…"))
        StoreManager.ensureEngine(kit.context, "tor", "libtor.so")
        val chained = false
        if (modes.any { plugin(it) != null }) StoreManager.ensureEngine(kit.context, "lyrebird", "libobfs4proxy.so")
        BridgeManager.refreshForProbe(kit.context)
        val preparedModes = modes.mapNotNull { mode ->
            val bridgeType = transport(mode)
            // «پل‌ها: خودکار» whatever the tile is set to; the base modes take the tile's own lines.
            val bridges = bridgeType?.let {
                if (kind == BRIDGES) BridgeManager.autoLinesFor(kit.context, it) else BridgeManager.linesFor(kit.context, it)
            }.orEmpty()
            if (bridgeType != null && bridges.isEmpty()) null else Mode(mode, plugin(mode), bridges)
        }
        if (preparedModes.isEmpty()) return@withContext Prepared.Failed(
            if (BridgeManager.choice(kit.context) == BridgeManager.Choice.MINE) ScoutStatus.NEEDS_SETUP else ScoutStatus.SERVICE_DOWN,
            tr("برای این حالت پل قابل استفاده پیدا نشد", "No usable bridges found for this mode"))
        val warp = if (chained) {
            when (val prepared = AetherWarpAdapter.prepare(kit, Variant("outer", "WARP", v.fam))) {
                is Prepared.Ready -> prepared
                is Prepared.Failed -> return@withContext prepared
                is Prepared.Done -> return@withContext Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("وارپ برای زنجیره آماده نشد", "WARP was not prepared for the chain"))
            }
        } else null
        Prepared.Ready(Handle(preparedModes, kind, warp))
    }

    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> {
        val handle = ready.handle as Handle
        return if (handle.warp == null) serveModes(kit, handle, handle.modes, null, block)
        else when (val outer = AetherWarpAdapter.serve(kit, handle.warp) { port -> serveModes(kit, handle, handle.modes, "127.0.0.1:$port", block) }) {
            is Served.Ok -> outer.value
            is Served.Failed -> outer
        }
    }

    private suspend fun <T> serveModes(kit: ScoutKit, handle: Handle, modes: List<Mode>, upstream: String?, block: suspend (Int) -> T): Served<T> {
        var result: Served<T> = Served.Failed(tr("تور آماده نشد", "Tor did not bootstrap"))
        val tried = ArrayList<String>()
        for (mode in modes) {
            currentCoroutineContext().ensureActive()
            val preparedMode = if (upstream != null && mode.transport != null) withContext(Dispatchers.IO) {
                kit.step(Step.PREPARING, tr("آماده‌سازی روش جایگزین تور…", "Preparing Tor's fallback…"))
                StoreManager.ensureEngine(kit.context, "lyrebird", "libobfs4proxy.so")
                BridgeManager.refreshForProbe(kit.context)
                mode.copy(bridges = BridgeManager.linesFor(kit.context, requireNotNull(transport(mode.mode))))
            } else mode
            if (preparedMode.transport != null && preparedMode.bridges.isEmpty()) continue
            val name = mode.mode.label + if (handle.kind == BRIDGES) tr(" · پل خودکار", " · automatic bridge") else ""
            tried += mode.mode.label
            handle.via = tr("از راه ", "via ") + name
            result = serveMode(kit, preparedMode, upstream, block)
            if (result is Served.Ok) return result
        }
        // None got through: the detail names every way that was tried.
        handle.via = tr("امتحان شد: ", "Tried: ") + tried.joinToString("، ")
        return result
    }

    private suspend fun <T> serveMode(kit: ScoutKit, mode: Mode, upstream: String?, block: suspend (Int) -> T): Served<T> = withContext(Dispatchers.IO) {
        // Keep consensus/descriptors warm between speed and data rounds; never use tor_data.
        val dir = File(kit.context.filesDir, "scout/tor-${if (upstream == null) "direct" else "chained"}-${mode.mode.key}").apply { mkdirs() }
        val children = ArrayList<Process>()
        val readers = ArrayList<Thread>()
        fun start(id: String, lib: String, args: List<String>, env: Map<String, String>, line: (String) -> Unit): Process {
            val child = ProcessBuilder(StoreEngines.command(kit.context, id, lib) + args)
                .directory(dir).redirectErrorStream(true).apply { environment().clear(); environment().putAll(env) }.start()
            children += child
            readers += Thread({ runCatching { child.inputStream.bufferedReader().useLines { lines -> lines.forEach(line) } } }, "scout-$id-output")
                .apply { isDaemon = true; start() }
            return child
        }
        val env = ScoutWarpPolicy.cleanParent(System.getenv()).filterKeys { !it.startsWith("TOR_PT_") } + ("HOME" to dir.absolutePath)
        try {
            val listener = mode.transport?.let { transport ->
                val announced = CompletableDeferred<String?>()
                val ptDir = File(dir, "pt").apply { mkdirs() }
                val ptEnv = env + mapOf("TOR_PT_MANAGED_TRANSPORT_VER" to "1", "TOR_PT_CLIENT_TRANSPORTS" to transport,
                    "TOR_PT_STATE_LOCATION" to (ptDir.absolutePath + "/"), "TOR_PT_EXIT_ON_STDIN_CLOSE" to "1") +
                    (upstream?.let { mapOf("TOR_PT_PROXY" to "socks5://$it") } ?: emptyMap())
                start("lyrebird", "libobfs4proxy.so", emptyList(), ptEnv) { raw ->
                    ScoutTorPolicy.listener(raw, transport)?.let { announced.complete(it) }
                    if (raw.trim() == "CMETHODS DONE" || raw.startsWith("CMETHOD-ERROR") || raw.startsWith("ENV-ERROR")) announced.complete(null)
                }
                withTimeoutOrNull(12_000) { announced.await() }
                    ?: return@withContext Served.Failed(tr("موتور پل تور آماده نشد", "Tor bridge engine did not start"), inconclusive = true)
            }
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val config = File(dir, "torrc").apply { writeText(ScoutTorPolicy.config(dir.absolutePath, port, mode.bridges, mode.transport, listener, upstream)) }
            val progress = AtomicInteger(0)
            val bootstrap = Regex("Bootstrapped (\\d+)%")
            val tor = start("tor", "libtor.so", listOf("-f", config.absolutePath), env) { raw ->
                bootstrap.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.let { progress.set(it) }
            }
            val bootstrapped = withTimeoutOrNull(if (mode.mode == TorMode.DIRECT) 150_000L else MODE_BOOTSTRAP_MS) {
                var shown = -1
                while (tor.alive()) {
                    val percent = progress.get()
                    if (percent == 100) return@withTimeoutOrNull true
                    if (percent != shown) {
                        kit.step(Step.CONNECTING, "Tor ${mode.mode.label} · $percent%")
                        shown = percent
                    }
                    delay(250)
                }
                false
            } == true
            if (bootstrapped) Served.Ok(block(port)) else Served.Failed(
                tr("تور در زمان مقرر آماده نشد", "Tor did not bootstrap in time"), inconclusive = !tor.alive())
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                children.asReversed().forEach { child ->
                    runCatching { child.outputStream.close() }
                    runCatching {
                        child.destroy()
                        if (!child.waitUpTo(2, TimeUnit.SECONDS)) { child.killHard(); child.waitUpTo(2, TimeUnit.SECONDS) }
                    }
                    runCatching { child.inputStream.close() }
                }
                readers.forEach { it.join(1_000) }
                File(dir, "torrc").delete()
            }
        }
    }
}
