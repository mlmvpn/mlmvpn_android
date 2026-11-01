package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import com.mlmvpn.core.aether.*
import com.mlmvpn.core.warp.CfWarpEngine
import com.mlmvpn.core.warp.Nat64Exits
import com.mlmvpn.scanner.data.CfIdentityFiles
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

/** The same WG data plane as the WARP page, with its own process, port and temporary files. */
internal object AetherWarpAdapter : ScoutAdapter() {
    override val id = "warp"
    override val homeId = "warp"
    override val kind = MethodKind.TUNNEL
    override val lanes = setOf(Lane.AETHER)
    override val estimateSec = 180
    override val speedBudgetMs = 180_000L
    override val discoversVariants = true
    override fun requiresForeignExit(variantId: String) = variantId.startsWith("exit_")
    override fun readiness(ctx: Context): Readiness = Readiness.Ready
    override fun howTested() = tr(
        "موتور و هویت وارپ خودکار آماده می‌شوند؛ اتصال مستقیم IPv4/IPv6 و همهٔ درگاه‌های کشورها جدا، با پاسخ warp=on/plus، عبور داده و نمونهٔ سرعت سنجیده می‌شوند.",
        "The engine and identity are prepared automatically; direct IPv4/IPv6 and every country gateway are tested separately with warp=on/plus, real data and a speed sample.")

    override fun variants(ctx: Context, line: LineSnapshot): List<Variant> {
        val warm = CfWarpEngine.available(ctx) && CfWarpEngine.hasIdentity(ctx)
        return listOf(
            // IPv6 first where the line has it: where WARP over IPv4 is dropped, the quick pass spent
            // its whole wait on IPv4 first.
            Variant("v4", tr("مستقیم · IPv4", "Direct · IPv4"), Fam.V4, quick = warm && line.hasV4 && !line.hasV6, budgetMs = 360_000,
                notApplicable = if (!line.hasV4) tr("این خط IPv4 ندارد", "This line has no IPv4") else null),
            Variant("v6", tr("مستقیم · IPv6", "Direct · IPv6"), Fam.V6, quick = warm && line.hasV6, budgetMs = 360_000,
                notApplicable = if (!line.hasV6) tr("این خط IPv6 ندارد", "This line has no IPv6") else null),
        ) + countryVariants(ctx, line)
    }

    private fun countryVariants(ctx: Context, line: LineSnapshot) = Nat64Exits.onePerCountry(ctx).mapNotNull { gateway ->
        val peer = Nat64Exits.endpoint(gateway, Nat64Exits.Proto.WG) ?: return@mapNotNull null
        Variant("exit_${gateway.key}", listOf(gateway.country, gateway.city, gateway.provider).filter { it.isNotBlank() }.joinToString(" · "),
            Fam.V6, budgetMs = 240_000, payload = peer,
            notApplicable = if (!line.hasV6) tr("خروجی این کشور به IPv6 نیاز دارد", "This country gateway requires IPv6") else null)
    }

    override suspend fun discover(kit: ScoutKit): List<Variant> = withContext(Dispatchers.IO) {
        runInterruptible { Nat64Exits.refreshIfStale(kit.context) }
        variants(kit.context, kit.line)
    }

    private data class Handle(val family: Fam, val noize: String, val keepalive: Int, val peer: String?)

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared = withContext(Dispatchers.IO) {
        if (!CfWarpEngine.available(kit.context)) {
            kit.step(Step.PREPARING, tr("نصب خودکار موتور وارپ…", "Installing the WARP engine…"))
            StoreManager.ensureEngine(kit.context, "aether", "libaether.so")
        }
        if (!CfWarpEngine.hasIdentity(kit.context)) {
            kit.step(Step.PREPARING, tr("ساخت خودکار هویت وارپ…", "Registering a WARP identity…"))
            val owner = currentCoroutineContext()
            runInterruptible { CfWarpEngine.ensureIdentity(kit.context) { !owner.isActive } }
        }
        Prepared.Ready(Handle(v.fam, CfWarpEngine.noize(kit.context), CfWarpEngine.keepalive(kit.context), v.payload as? String))
    }

    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> = withContext(Dispatchers.IO) {
        val handle = ready.handle as Handle
        val dir = File(kit.context.filesDir, "scout/warp-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        var process: Process? = null
        var reader: Thread? = null
        try {
            CfIdentityFiles.copyForProbe(kit.context, File(CfWarpEngine.dataDir(kit.context), "aether.toml"), File(dir, "aether.toml"))
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            // BOTH avoids the app's automatic V4-to-V6 substitution; the explicit probe family
            // is pinned below. A clean parent excludes stale peers, proxies and no-data flags.
            val options = AetherOptions(protocol = AetherProtocol.WG, scan = AetherScan.TURBO,
                ipFamily = AetherIp.BOTH, noize = handle.noize, keepalive = handle.keepalive, quickReconnect = false, wgPeer = handle.peer)
            val env = ScoutWarpPolicy.pinFamily(options.toEnv(ScoutWarpPolicy.cleanParent(System.getenv()), dir.absolutePath, port), handle.family)
            val child = ProcessBuilder(StoreEngines.command(kit.context, "aether", "libaether.so"))
                .directory(dir).redirectErrorStream(true).apply { environment().clear(); environment().putAll(env) }.start()
            process = child
            // Drain without retaining output: the engine may print identity material.
            reader = Thread({ runCatching { child.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (input.read(buffer) >= 0) Unit
            } } }, "scout-warp-output").apply { isDaemon = true; start() }
            kit.step(Step.CONNECTING, tr("انتظار برای عبور واقعی داده از وارپ…", "Waiting for verified WARP traffic…"))
            // A fixed country gateway answers within a minute or not at all.
            val connected = withTimeoutOrNull(if (handle.peer != null) 35_000L else 150_000L) {
                while (child.alive()) {
                    if (runInterruptible { ScoutNet.warpVerified(port, 5_000) }) return@withTimeoutOrNull true
                    delay(2_000)
                }
                false
            } == true
            if (connected) Served.Ok(block(port)) else Served.Failed(
                tr("عبور داده از وارپ تأیید نشد", "WARP traffic could not be verified"), inconclusive = !child.alive())
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let { child ->
                    child.destroy()
                    if (!child.waitUpTo(2, TimeUnit.SECONDS)) { child.killHard(); child.waitUpTo(2, TimeUnit.SECONDS) }
                    runCatching { child.inputStream.close() }
                }
                reader?.join(1_000)
                dir.deleteRecursively()
            }
        }
    }
}
