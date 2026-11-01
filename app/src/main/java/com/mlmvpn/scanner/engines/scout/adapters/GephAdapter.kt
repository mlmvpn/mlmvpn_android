package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import com.mlmvpn.core.geph.*
import com.mlmvpn.scanner.engines.scout.*
import com.mlmvpn.scanner.store.StoreManager
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.*
import java.io.File
import java.net.ServerSocket
import org.json.JSONArray
import org.json.JSONObject

/** Own child processes: no TUN, shared engine state, or changes to the user's exit settings. */
internal object GephAdapter : ScoutAdapter() {
    override val id = "geph"
    override val homeId = "geph"
    override val kind = MethodKind.TUNNEL
    override val lanes = setOf(Lane.GEPH)
    override val estimateSec = 180
    override val speedBudgetMs = 150_000L
    override val discoversVariants = true
    private data class Handle(val credential: GephCredential, val exit: GephExitChoice)
    override fun readiness(ctx: Context): Readiness = Readiness.Ready
    override fun howTested() = tr(
        "نصب موتور و ساخت حساب رایگان خودکار است؛ خروجی خودکار و کشورها/شهرهای گف جدا با عبور داده و نمونهٔ سرعت سنجیده می‌شوند. راه‌اندازی اول ممکن است چند دقیقه و دانلود بیشتری نیاز داشته باشد.",
        "The engine and free account are set up automatically; automatic and country/city exits get separate data and speed tests. First setup may take several minutes and an additional download.")

    override fun variants(ctx: Context, line: LineSnapshot) = choices(ctx, GephExits.cached(ctx), null)

    private fun choices(ctx: Context, exits: List<GephExit>, level: GephAccount.Level?): List<Variant> {
        val selected = GephSettings.exit(ctx)
        val all = listOf(GephExitChoice.AUTO, selected) + exits.map { GephExitChoice(it.country, it.city) }
        return all.distinct().map { choice ->
            val matches = exits.filter { it.country == choice.country && (choice.city == null || it.city == choice.city) }
            Variant(if (choice.isAuto) "geph" else "exit_${choice.country}_${java.net.URLEncoder.encode(choice.city.orEmpty(), "UTF-8")}",
                if (choice.isAuto) tr("خودکار", "Automatic") else listOfNotNull(choice.country, choice.city?.takeIf { it.isNotBlank() }).joinToString(" · "),
                quick = choice == selected && GephEngine.available(ctx) && GephAccount.hasAccount(ctx), budgetMs = 600_000,
                payload = choice, needsSetup = if (level != null && matches.isNotEmpty() && matches.none { it.allows(level) })
                    tr("این خروجی در طرح حساب فعلی در دسترس نیست", "This exit is unavailable on the current account plan") else null)
        }
    }

    override suspend fun discover(kit: ScoutKit): List<Variant> = withContext(Dispatchers.IO) {
        val ready = prepare(kit, Variant("geph", "Geph")) as? Prepared.Ready ?: error("Geph setup failed")
        val credential = (ready.handle as Handle).credential
        process(kit.context, null, GephExitChoice.AUTO) { control, _ ->
            val status = control.netStatus() ?: error("No exit catalogue")
            val exits = GephExits.parse(status).also { check(it.isNotEmpty()) }
            val info = try { control.brokerRpc("get_user_info_by_cred", JSONArray().put(credential.toJson())) as? JSONObject }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            val level = info?.let { GephAccount.Info.parse(it).level } ?: GephAccount.cachedInfo(kit.context)?.level
            choices(kit.context, exits, level)
        }
    }

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared = withContext(Dispatchers.IO) {
        if (!GephEngine.available(kit.context)) {
            kit.step(Step.PREPARING, tr("نصب خودکار موتور گف…", "Installing Geph automatically…"))
            StoreManager.ensureEngine(kit.context, "geph", "libgeph.so")
        }
        var credential = GephAccount.credential(kit.context)
        if (credential == null) {
            kit.step(Step.PREPARING, tr("ساخت خودکار حساب رایگان گف…", "Creating a free Geph account…"))
            credential = process(kit.context, null, GephExitChoice.AUTO) { control, child ->
                val index = control.startRegistration()
                var account: GephCredential? = null
                while (account == null) {
                    ensureActive()
                    check(child.isAlive) { "Geph registration process exited" }
                    val (progress, secret) = control.pollRegistration(index)
                    ensureActive()
                    if (secret != null) account = GephAccount.keepAutomaticSecret(kit.context, secret)
                    else {
                        kit.step(Step.PREPARING, tr("ساخت حساب رایگان گف", "Creating free Geph account") + " · ${(progress.coerceIn(0.0, 1.0) * 100).toInt()}%")
                        delay(1_200)
                    }
                }
                account
            }
        }
        Prepared.Ready(Handle(requireNotNull(credential), v.payload as? GephExitChoice ?: GephExitChoice.AUTO))
    }

    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> = withContext(Dispatchers.IO) {
        val handle = ready.handle as Handle
        check(!GephEngine.isMainAlive) { "Geph is already running" }
        // Same native forwarding path as the manual country probe; every measurement stays in Geph.
        val hosts = listOf("www.gstatic.com", "www.youtube.com", "dl.google.com", "ipinfo.io", "www.cloudflare.com")
        val reservations = hosts.map { ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")) }
        val routes = try { hosts.zip(reservations).associate { (host, socket) -> (host to 443) to socket.localPort } }
            finally { reservations.forEach { it.close() } }
        ScoutForwardRoutes.register(routes).use { lease ->
        val forwards = routes.map { (destination, port) -> GephForward("127.0.0.1:$port", "${destination.first}:${destination.second}") }
        process(kit.context, handle.credential, handle.exit, forwards) { control, child ->
            kit.step(Step.CONNECTING)
            val connected = withTimeoutOrNull(100_000) {
                while (!runCatching { control.connInfo().connected }.getOrDefault(false)) {
                    check(child.isAlive) { "Geph proxy process exited" }
                    delay(500)
                }
                true
            } == true
            if (connected) Served.Ok(block(lease.port)) else Served.Failed(tr("گف به خروجی وصل نشد", "Geph did not connect to an exit"))
        }
        }
    }

    private suspend fun <T> process(ctx: Context, credential: GephCredential?, exit: GephExitChoice, forwards: List<GephForward> = emptyList(), block: suspend (GephControl, GephProcess) -> T): T {
        val dir = File(ctx.cacheDir, "sg-${java.util.UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        val control = GephControl(File(dir, "ctl").absolutePath)
        var child: GephProcess? = null
        try {
            val config = File(dir, "config.json").apply { writeText(GephConfig.build(GephRun(
                credential = credential, exit = exit, dryRun = forwards.isEmpty(),
                // Reuse account tokens across every country and speed pass, avoiding daily token exhaustion.
                controlSocket = File(dir, "ctl").absolutePath,
                cacheFile = (credential?.let { GephEngine.cacheFile(ctx, it) } ?: File(dir, "cache.db")).absolutePath,
                forwards = forwards, allowDirect = false, allowLan = false,
            )).toString()) }
            child = GephProcess.start(ctx, "scout", config, dir, null, {}, {})
            withTimeout(15_000) {
                while (!control.reachable()) {
                    check(child.isAlive) { "Geph process exited" }
                    delay(150)
                }
            }
            return block(control, child)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { child?.stop(control) } finally { dir.deleteRecursively() }
            }
        }
    }
}
