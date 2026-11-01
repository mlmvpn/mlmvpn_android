package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import android.os.Bundle
import com.mlmvpn.core.warp.AwgHostClient
import com.mlmvpn.core.warp.XrayProbeService
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.CombineEngine
import com.mlmvpn.scanner.engines.amnezia.AmzBrain
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.engines.amnezia.AmzKind
import com.mlmvpn.scanner.engines.amnezia.AmzServer
import com.mlmvpn.scanner.engines.amnezia.AmzStat
import com.mlmvpn.scanner.engines.amnezia.AmzTester
import com.mlmvpn.scanner.engines.amnezia.AmzTestMode
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.engines.gst.GstConfigManager
import com.mlmvpn.scanner.engines.gst.GstDiagnostics
import com.mlmvpn.scanner.engines.proton.ProtonEngine
import com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager
import com.mlmvpn.scanner.engines.rstaspoof.SniEch
import com.mlmvpn.scanner.engines.rstaspoof.SniSession
import com.mlmvpn.scanner.engines.sanction.AntiSanctionManager
import com.mlmvpn.scanner.engines.scout.*
import com.mlmvpn.scanner.mitm.MitmCertManager
import com.mlmvpn.scanner.mitm.MitmProfile
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The methods whose engines Scout first only listed: each now tested the way its own screen tests
 * it, so the answer here is the answer a tap there gets. Where an engine's path can be handed to
 * the shared yardstick as a local port, it is; where it cannot (a relay that lives inside the
 * engine's own test, a front that needs its own certificate), the engine's own verdict is kept and
 * marked as not fully measured -- never turned into «کار نمی‌کند».
 */

// ---- Psiphon ------------------------------------------------------------------------------------

internal object PsiphonAdapter : ScoutAdapter() {
    override val id = "psiphon"
    override val homeId = "psiphon"
    override val kind = MethodKind.TUNNEL
    override val lanes = setOf(Lane.PSIPHON, Lane.AETHER)
    override val estimateSec = 150
    override val estimateMb = 2.0
    override val speedBudgetMs = 200_000L

    override fun howTested() = tr(
        "سایفون با همان تنظیمات پایهٔ اتصال (کلیدها، فهرست سرورها، DNS جایگزین، همهٔ پروتکل‌ها) روی پورت جدای Scout بالا می‌آید و از داخلش داده و یوتیوب سنجیده می‌شود؛ «سایفون از داخل وارپ» هم جدا.",
        "Psiphon starts with the connect's own base settings (keys, server lists, alternate DNS, every protocol) on Scout's own port and is measured through; Psiphon inside WARP separately.")

    override fun readiness(ctx: Context): Readiness = Readiness.Ready

    override fun variants(ctx: Context, line: LineSnapshot) = listOf(
        Variant("normal", tr("معمولی", "Normal"), quick = true, budgetMs = 240_000),
        // Only where the plain way does not get through: the chain is the way around a blocked line.
        Variant("in_warp", tr("از داخل وارپ", "Inside WARP"), if (line.hasV4) Fam.V4 else Fam.V6, budgetMs = 600_000, unneededIf = "normal"),
    )

    private class Handle(val warp: Prepared.Ready?) : LiveDetail {
        @Volatile var via: String? = null
        override val liveDetail: String? get() = via
    }

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        if (v.id != "in_warp") return Prepared.Ready(Handle(null))
        return when (val outer = AetherWarpAdapter.prepare(kit, Variant("outer", "WARP", v.fam))) {
            is Prepared.Ready -> Prepared.Ready(Handle(outer), tr("از داخل وارپ", "Inside WARP"))
            is Prepared.Failed -> outer
            is Prepared.Done -> Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("وارپ برای زنجیره آماده نشد", "WARP was not prepared for the chain"))
        }
    }

    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> {
        val h = ready.handle as Handle
        val warp = h.warp ?: return run(kit, null, block, h)
        return when (val outer = AetherWarpAdapter.serve(kit, warp) { port -> run(kit, port, block) }) {
            is Served.Ok -> outer.value
            is Served.Failed -> outer
        }
    }


    /**
     * Psiphon runs in its own process ([PsiphonProbeService]): its Go runtime and Xray's cannot share
     * one. Plain, it climbs the tile's own ladder (CDN-fronted, direct, peer relay) from the rung that
     * last carried the tile here, one fresh process per rung, and the result names the rung that
     * connected. Inside WARP it is one try with the chained config.
     */
    private suspend fun <T> run(kit: ScoutKit, upstream: Int?, block: suspend (Int) -> T, handle: Handle? = null): Served<T> = withContext(Dispatchers.IO) {
        val ctx = kit.context.applicationContext
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val base = com.mlmvpn.core.tunnel.psiphonProbeConfig(ctx, port, upstream)
        val rungs: List<Pair<String, Pair<Int, String>>> = if (upstream != null) listOf("" to (150 to base))
        else com.mlmvpn.core.tunnel.psiphonProbeRungs(ctx).map { r ->
            r.label to (r.seconds to JSONObject(base).also { r.apply(it) }.toString())
        }
        val token = java.util.UUID.randomUUID().toString()
        val P = com.mlmvpn.scanner.engines.scout.PsiphonProbeService
        fun args() = android.os.Bundle().apply { putString(P.TOKEN, token) }
        var opened = false
        try {
            opened = runInterruptible { client.call(ctx, P.OPEN, args(), 5_000) }?.getBoolean(P.OK) == true
            if (!opened) return@withContext Served.Failed(tr("پردازش تست آماده نشد", "The test process did not become available"), true)
            // One process for the whole ladder: each rung's START replaces the one before.
            for ((label, rung) in rungs) {
                currentCoroutineContext().ensureActive()
                if (label.isNotBlank()) handle?.via = tr("پلهٔ ", "rung ") + label
                kit.step(Step.CONNECTING, tr("سایفون در حال پیدا کردن سرور…", "Psiphon is finding a server…") + if (label.isNotBlank()) " ($label)" else "")
                val started = runInterruptible { client.call(ctx, P.START, args().apply { putString(P.CONFIG, rung.second) }, 5_000) }
                if (started?.getBoolean(P.OK) != true) return@withContext Served.Failed(tr("سایفون شروع نشد", "Psiphon did not start"), true)
                // The rung's own time, plus the previous rung's stop and the pause after it.
                val listening = withTimeoutOrNull(rung.first * 1_000L + 2_000L) {
                    while (true) {
                        val st = runInterruptible { client.call(ctx, P.STATUS, args(), 2_000) }
                        if (st?.getBoolean(P.OK) != true) return@withTimeoutOrNull null
                        if (st.getString(P.PHASE) == "connected") return@withTimeoutOrNull st.getInt(P.PORT).takeIf { it > 0 } ?: port
                        if (st.getString(P.PHASE) == "failed") return@withTimeoutOrNull null
                        delay(1_000)
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
                if (listening != null) return@withContext Served.Ok(block(listening))
            }
            if (upstream == null) handle?.via = tr("امتحان شد: ", "Tried: ") + rungs.joinToString("، ") { it.first }
            Served.Failed(tr("سایفون در زمان مقرر به سروری وصل نشد", "Psiphon reached no server in time"))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { if (opened) runCatching { client.call(ctx, P.STOP, args(), 1_500) } }
                finally { client.release(ctx) }
            }
        }
    }

    private val client by lazy { com.mlmvpn.core.warp.AwgHostClient(com.mlmvpn.scanner.engines.scout.PsiphonProbeService::class.java, "ScoutPsiphonClient") }
}

// ---- Amnezia and Proton (one engine, one tester) -------------------------------------------------

/**
 * The Amnezia engine's own delay test over a set of its servers: the line first, then from inside
 * Cloudflare for the WireGuard ones the line drops, Proton's through their Stealth relay, the
 * AmneziaWG-only ones in their own process. The engine's stored results are not touched.
 */
internal object AmzScout {
    class Hit(val server: AmzServer, val ms: Long, val country: String?, val viaCf: Boolean)

    suspend fun race(ctx: Context, servers: List<AmzServer>): List<Hit> {
        if (servers.isEmpty()) return emptyList()
        val app = ctx.applicationContext
        AmzEngine.init(app)
        val st = AmzEngine.store.current
        val net = FluxNet.current(app).key
        val cfFirst = st.statsFor(net).filterValues { it.viaCloudflare }.keys
        val got = ConcurrentHashMap<String, Pair<Long, String?>>()
        val ways = ConcurrentHashMap<String, String>()
        val pins = ConcurrentHashMap<String, String>()
        val sink = object : AmzTester.Sink {
            override fun result(id: String, ms: Long?, country: String?) = result(id, ms, country, null)
            override fun result(id: String, ms: Long?, country: String?, via: String?) {
                if (ms == null) return
                got[id] = ms to country
                if (via != null) ways[id] = via
            }
            override fun skip(id: String) {}
            override fun pinned(id: String, raw: String) { pins[id] = raw }
        }
        AmzTester(app).run(servers, st.prefs.obfuscate, sink, cfFirst = cfFirst, warpPool = st.servers.filter { it.warp })
        return servers.mapNotNull { s ->
            val (ms, cc) = got[s.id] ?: return@mapNotNull null
            Hit(pins[s.id]?.let { s.copy(raw = it) } ?: s, ms, cc ?: s.country, ways[s.id] == AmzStat.VIA_CF)
        }.sortedBy { it.ms }
    }

    /** What the winner gives Scout: a yardstick through it when it can be dialled plainly, else the engine's own verdict. */
    suspend fun prepared(ctx: Context, servers: List<AmzServer>, relayed: Set<String>, what: String): Prepared {
        // The fastest abroad, not the fastest: a server inside Iran answers quickest of all and is no
        // way out (Amnezia «وصل شد ولی خروجی‌اش ایران است» on the user's A50, 2026-10-11). Iranian
        // only when nothing else answered, so the result still says so.
        val hits = race(ctx, servers).sortedBy { if (it.country.equals("IR", ignoreCase = true)) 1 else 0 }
        val best = hits.firstOrNull() ?: return Prepared.Failed(ScoutStatus.FAILED,
            tr("از ${servers.size} سرور $what، هیچ‌کدام روی این خط داده رد نکرد", "None of $what's ${servers.size} servers carried data on this line"), Step.CONNECTING)
        val detail = listOfNotNull(countryName(best.country), if (best.viaCf) tr("از داخل کلادفلر", "inside Cloudflare") else null)
            .joinToString(" · ").ifBlank { null }
        val plain = !best.viaCf && !best.server.needsAwgCore && best.server.id !in relayed
        if (plain) {
            val app = ctx.applicationContext
            val cfg = runCatching { AmzTester(app).probeConfig(best.server, AmzEngine.store.current.prefs.obfuscate) }.getOrNull()
            if (cfg != null) return Prepared.Ready(XrayHandle(cfg, keepRouting = true), detail)
        }
        return Prepared.Done(VariantResult("", "", status = ScoutStatus.WORKS,
            reason = tr("${hits.size} از ${servers.size} سرور جواب داد (تست خود $what)؛ سرعت جدا سنجیده نشد",
                "${hits.size} of ${servers.size} servers answered ($what's own test); speed not sampled"),
            numbers = Numbers(latencyMs = best.ms, exitCountry = best.country), detail = detail))
    }
}

internal object AmneziaAdapter : XrayAdapter() {
    // No country is promised, and many of the public WireGuard servers are WARP: Cloudflare places
    // WARP's exit addresses in the user's own country, so a working one reads «IR» (A50, 2026-10-11:
    // YouTube open, 9 Mbps, judged «خروجی‌اش ایران است»). A server really inside Iran still fails on
    // the filtered site.
    override fun requiresForeignExit(variantId: String) = false
    override val id = "amnezia"
    override val homeId = "amnezia"
    override val kind = MethodKind.TUNNEL
    override val lanes = setOf(Lane.XRAY, Lane.AWG)
    override val estimateSec = 90
    override val estimateMb = 1.5

    override fun howTested() = tr(
        "تست خود آمنزیا روی سرورهای امیدوارکنندهٔ هر نوع (WireGuard، AmneziaWG، Hysteria2 و فایل‌های خود شما)، اول مستقیم و بعد از داخل کلادفلر؛ بهترین سرور با خط‌کش مشترک سنجیده می‌شود.",
        "Amnezia's own test over the most promising servers of each kind (WireGuard, AmneziaWG, Hysteria2, your files), direct then inside Cloudflare; the best is measured with the shared yardstick.")

    override fun readiness(ctx: Context): Readiness = Readiness.Ready

    private const val SAMPLE = 48

    private fun pool(ctx: Context, variantId: String): List<AmzServer> {
        AmzEngine.init(ctx.applicationContext)
        val st = AmzEngine.store.current
        val proton = ProtonEngine.storedIds(ctx)
        val ranked = AmzBrain.pickForTest(st, FluxNet.current(ctx.applicationContext).key, AmzTestMode.ALL, System.currentTimeMillis())
            .filter { it.id !in proton }
        return when (variantId) {
            "own" -> ranked.filter { it.user }
            else -> ranked.filter { !it.user && it.kind.name.lowercase() == variantId }
        }.take(SAMPLE)
    }

    override fun variants(ctx: Context, line: LineSnapshot): List<Variant> =
        AmzKind.entries.map { k ->
            // Exhaustive: a new server kind does not compile until Scout tests it.
            when (k) {
                AmzKind.WG -> "wg" to "WireGuard"
                AmzKind.AWG -> "awg" to "AmneziaWG"
                AmzKind.HY2 -> "hy2" to "Hysteria2"
            }
        }.plus("own" to tr("فایل‌های خود شما", "Your own files")).mapIndexed { i, (vid, label) ->
            val empty = runCatching { pool(ctx, vid).isEmpty() }.getOrDefault(true)
            Variant(vid, label, quick = i == 0, budgetMs = 300_000,
                notApplicable = if (!empty) null else if (vid == "own") tr("فایلی اضافه نکرده‌اید", "You added no files")
                    else tr("در فهرست آمنزیا سروری از این نوع نیست", "Amnezia's list has no server of this kind"))
        }

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        val servers = pool(kit.context, v.id)
        kit.step(Step.CONNECTING, tr("آزمودن ${servers.size} سرور ${v.label}…", "Trying ${servers.size} ${v.label} servers…"))
        return AmzScout.prepared(kit.context, servers, emptySet(), tr("آمنزیا", "Amnezia"))
    }
}

internal object ProtonAdapter : XrayAdapter() {
    override val id = "proton"
    override val homeId = "proton"
    override val kind = MethodKind.TUNNEL
    override val lanes = setOf(Lane.XRAY, Lane.AWG)
    override val estimateSec = 90

    override fun howTested() = tr(
        "سرورهای حساب پروتون شما با همان مسابقهٔ راه‌های خود پروتون (IPv6، کلادفلر، IPv4، UDP) آزموده می‌شوند.",
        "Your Proton account's servers are tested with Proton's own race of ways (IPv6, Cloudflare, IPv4, UDP).")

    private fun servers(ctx: Context): List<AmzServer> {
        AmzEngine.init(ctx.applicationContext)
        return ProtonEngine.servers(AmzEngine.store.current.servers, ProtonEngine.storedIds(ctx))
    }

    override fun readiness(ctx: Context): Readiness =
        if (runCatching { servers(ctx).isNotEmpty() }.getOrDefault(false)) Readiness.Ready
        else Readiness.NeedsSetup(tr("به حساب پروتون وارد نشده‌اید یا سروری گرفته نشده", "Not signed in to Proton, or no servers taken yet"))

    override fun variants(ctx: Context, line: LineSnapshot) = listOf(Variant("proton", "Proton VPN", quick = true, budgetMs = 300_000))

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        val all = servers(kit.context)
        kit.step(Step.CONNECTING, tr("مسابقهٔ راه‌های پروتون روی ${all.size} سرور…", "Racing Proton's ways on ${all.size} servers…"))
        // Proton's servers reach Scout only through the engine's own relay, so none is dialled plainly.
        val relayed = if (AmzEngine.relay != null) all.map { it.id }.toSet() else emptySet()
        return AmzScout.prepared(kit.context, all, relayed, "Proton")
    }
}

// ---- MAE ---------------------------------------------------------------------------------------

internal object MaeAdapter : XrayAdapter() {
    override val id = "mae"
    override val homeId = "mae"
    override val kind = MethodKind.TUNNEL
    override val estimateSec = 30
    /** MAE sends only what needs it abroad; ordinary traffic leaving in Iran is its design, not a fault. */
    override fun requiresForeignExit(variantId: String) = false

    override fun howTested() = tr(
        "همان کانفیگی که MAE برای اتصال روی همین شبکه می‌سازد در هستهٔ تست بالا می‌آید و با قواعد خودش سنجیده می‌شود.",
        "The very config MAE compiles to connect on this network runs in the test core, with its own rules.")

    override fun readiness(ctx: Context): Readiness =
        if (runCatching { CloudManager(ctx).loadedAccounts().isNotEmpty() }.getOrDefault(false)) Readiness.Ready
        else Readiness.NeedsSetup(tr("حساب کلادفلر لازم است", "A Cloudflare account is needed"))

    override fun variants(ctx: Context, line: LineSnapshot) = listOf(Variant("mae", "MAE", quick = true, budgetMs = 90_000))

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        kit.step(Step.PREPARING, tr("ساخت کانفیگ MAE برای این شبکه…", "Compiling MAE's config for this network…"))
        val cfg = withContext(Dispatchers.IO) { com.mlmvpn.scanner.engines.mae.MaeEngine.scoutConfig(kit.context) }
            ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("کانفیگ MAE ساخته نشد", "MAE's config could not be compiled"))
        // One inbound under the tun's own tag, sniffing on, so MAE's rules see what an app's traffic shows them.
        val j = JSONObject(cfg)
        j.put("inbounds", JSONArray().put(JSONObject().put("tag", com.mlmvpn.scanner.engines.mae.compile.MaeConfigCompiler.TUN_IN_TAG)
            .put("protocol", "socks").put("listen", "127.0.0.1").put("port", 10_999)
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls")))))
        return Prepared.Ready(XrayHandle(j.toString(), keepRouting = true))
    }
}

// ---- Google Script -----------------------------------------------------------------------------

internal object GoogleScriptAdapter : ScoutAdapter() {
    override val id = "gscript"
    override val homeId = "emergency_2"
    override val kind = MethodKind.TUNNEL
    override val evidence = Evidence.HANDSHAKE
    override val lanes = setOf(Lane.GST)

    override fun howTested() = tr(
        "یک درخواست واقعی از داخل رلهٔ گوگل اسکریپت شما فرستاده می‌شود و جلوی گوگلی که تونل از آن رد می‌شود هم بررسی می‌شود. برای استفاده گواهی لازم است؛ برای تست نه.",
        "A real request is relayed through your Google Script relay, and the Google front the tunnel uses is checked. Using it needs the certificate; testing does not.")

    override fun readiness(ctx: Context): Readiness =
        if (runCatching { GstConfigManager.getRelays(ctx).any { it.deploymentId.isNotBlank() } }.getOrDefault(false)) Readiness.Ready
        else Readiness.NeedsSetup(tr("رله با ویزارد ساخته نشده", "No relay made with the wizard yet"))

    override fun variants(ctx: Context, line: LineSnapshot) = listOf(Variant("relay", tr("رلهٔ شما", "Your relay"), quick = true, budgetMs = 120_000))

    override suspend fun handshake(kit: ScoutKit, v: Variant): VariantResult {
        val relays = GstConfigManager.getRelays(kit.context).filter { it.deploymentId.isNotBlank() }.take(3)
        var last: GstDiagnostics.Report? = null
        for (relay in relays) {
            val t0 = System.currentTimeMillis()
            val r = GstDiagnostics.testForTunnel(kit.context, relay)
            if (r.result == GstDiagnostics.Result.OK) return VariantResult(v.id, v.label, status = ScoutStatus.WORKS,
                reason = tr("رله یک درخواست واقعی را رد کرد؛ سرعت سنجیده نشد. برای استفاده، گواهی برنامه باید نصب باشد.",
                    "The relay carried a real request; speed not measured. Using it needs the app's certificate installed."),
                numbers = Numbers(connectMs = System.currentTimeMillis() - t0), at = System.currentTimeMillis())
            last = r
        }
        val r = last ?: return VariantResult(v.id, v.label, status = ScoutStatus.NEEDS_SETUP,
            reason = tr("رله‌ای نیست", "No relay"), at = System.currentTimeMillis())
        val status = when (r.result) {
            GstDiagnostics.Result.AUTH_MISMATCH, GstDiagnostics.Result.REDIRECT_BLOCKED,
            GstDiagnostics.Result.NOT_FOUND, GstDiagnostics.Result.KEY_NOT_USED -> ScoutStatus.NEEDS_SETUP
            GstDiagnostics.Result.BAD_RESPONSE -> ScoutStatus.SERVICE_DOWN
            GstDiagnostics.Result.FRONT_BLOCKED -> ScoutStatus.FAILED
            // The check reaches script.google.com directly, the tunnel goes through a Google front:
            // a closed direct path says nothing when a front answers.
            GstDiagnostics.Result.UNREACHABLE ->
                if (com.mlmvpn.scanner.engines.gst.GstFront.pick(kit.context) != null) ScoutStatus.INCONCLUSIVE else ScoutStatus.FAILED
            GstDiagnostics.Result.OK -> ScoutStatus.WORKS
        }
        return VariantResult(v.id, v.label, status = status, reason = r.message,
            failedAt = if (status == ScoutStatus.FAILED) Step.CONNECTING else null, at = System.currentTimeMillis())
    }
}

// ---- Anti-sanction ------------------------------------------------------------------------------

internal object AntiSanctionAdapter : XrayAdapter() {
    override val id = "antisanction"
    override val homeId = "antisanction"
    override val kind = MethodKind.ANTISANCTION
    override val estimateSec = 25

    override fun howTested() = tr(
        "خروجی ورکر شما (هر سه نشانی‌اش) آزموده می‌شود؛ باید داده رد کند و خروجی‌اش بیرون از ایران باشد.",
        "Your worker exit (all three of its addresses) is tried; it must carry data and come out outside Iran.")

    private fun accounts(ctx: Context) = CloudManager(ctx).loadedAccounts()
        .filter { !it.edgWorkerUrl.isNullOrEmpty() && !it.edgUuid.isNullOrEmpty() }

    override fun readiness(ctx: Context): Readiness =
        if (runCatching { accounts(ctx).isNotEmpty() }.getOrDefault(false)) Readiness.Ready
        else Readiness.NeedsSetup(tr("ورکر روی حساب کلادفلر نصب نشده", "No worker on the Cloudflare account"))

    override fun variants(ctx: Context, line: LineSnapshot) = listOf(Variant("worker", tr("خروجی ورکر", "Worker exit"), quick = true, budgetMs = 90_000))

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        val all = accounts(kit.context)
        val chosen = AntiSanctionManager.selectedAccountId(kit.context)?.let { id -> all.firstOrNull { it.accountId == id } } ?: all.firstOrNull()
            ?: return Prepared.Failed(ScoutStatus.NEEDS_SETUP, tr("ورکر پیدا نشد", "No worker found"))
        kit.step(Step.PREPARING, tr("گرفتن نشانی‌های ورکر…", "Getting the worker's addresses…"))
        val (ok, configs) = CloudManager(kit.context).fetchEdgConfigs(chosen)
        if (!ok || configs.isEmpty()) return Prepared.Failed(ScoutStatus.SERVICE_DOWN,
            tr("نشانی‌های ورکر از کلادفلر گرفته نشد", "The worker's addresses could not be fetched from Cloudflare"))
        kit.step(Step.CONNECTING, tr("آزمودن ${configs.size} نشانی…", "Trying ${configs.size} addresses…"))
        val won = fastest(kit.context, configs, parallel = 3, timeoutMs = 12_000)
            ?: return Prepared.Failed(ScoutStatus.FAILED, tr("هیچ‌کدام از نشانی‌های ورکر داده رد نکرد", "None of the worker's addresses carried data"), Step.CONNECTING)
        val handle = uriHandle(won.first) ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("کانفیگ خوانده نشد", "Could not read the config"))
        return Prepared.Ready(handle)
    }
}

// ---- SNI ---------------------------------------------------------------------------------------

internal object SniAdapter : XrayAdapter() {
    override val id = "sni"
    override val homeId = "emergency_3"
    override val kind = MethodKind.UNFILTER
    override val lanes = setOf(Lane.SNI, Lane.XRAY)
    override val estimateSec = 90

    override fun howTested() = tr(
        "کانفیگ‌های SNI شما سه‌جور تست می‌شوند: با روش فعلی جلوی محلی، با روش ECH، و اگر هیچ‌کدام نشد با بقیهٔ روش‌های جلو یکی‌یکی (تنظیم شما عوض نمی‌ماند).",
        "Your SNI configs are tested three ways: the local front's current method, ECH, and if neither works every other front method in turn (your setting is left as it was).")

    override fun readiness(ctx: Context): Readiness =
        if (runCatching { SniSession.configs(ctx).isNotEmpty() }.getOrDefault(false)) Readiness.Ready
        else Readiness.NeedsSetup(tr("کانفیگ SNI ساخته نشده", "No SNI config made yet"))

    override fun variants(ctx: Context, line: LineSnapshot) = listOf(
        Variant("front", tr("جلوی محلی", "Local front"), quick = true, budgetMs = 120_000),
        Variant("ech", "ECH", budgetMs = 90_000),
        Variant("ladder", tr("روش‌های دیگر جلو", "Other front methods"), budgetMs = 600_000),
    )

    private class Handle(val xray: XrayHandle, val front: Boolean)
    @Volatile private var easyWorked = false

    private suspend fun firstAnswer(ctx: Context, uris: List<String>): Pair<String, Long>? {
        for (u in uris) {
            val ms = CombineEngine.measureUri(ctx, u, 20_000)
            if (ms > 0) return u to ms
        }
        return null
    }

    private suspend fun <T> withFront(ctx: Context, block: suspend () -> T): T {
        withContext(Dispatchers.IO) { RstaSpoofManager.acquire(ctx) }
        try { return block() } finally { withContext(NonCancellable + Dispatchers.IO) { RstaSpoofManager.release() } }
    }

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        val ctx = kit.context
        val nodes = SniSession.configs(ctx).take(2)
        if (nodes.isEmpty()) return Prepared.Failed(ScoutStatus.NEEDS_SETUP, tr("کانفیگ SNI ساخته نشده", "No SNI config made yet"))
        when (v.id) {
            "front" -> {
                easyWorked = false
                val profile = RstaSpoofManager.storedProfile(ctx)
                kit.step(Step.CONNECTING, tr("جلوی محلی، روش ${RstaSpoofManager.profileNumber(profile.id)}…", "Local front, method ${RstaSpoofManager.profileNumber(profile.id)}…"))
                val won = withFront(ctx) { firstAnswer(ctx, nodes.map { it.uri }) }
                    ?: return Prepared.Failed(ScoutStatus.FAILED, tr("با روش فعلی جلوی محلی جوابی نیامد", "No answer through the front's current method"), Step.CONNECTING)
                easyWorked = true
                val h = uriHandle(won.first) ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("کانفیگ خوانده نشد", "Could not read the config"))
                return Prepared.Ready(Handle(h, front = true), tr("روش ${RstaSpoofManager.profileNumber(profile.id)}", "Method ${RstaSpoofManager.profileNumber(profile.id)}"))
            }
            "ech" -> {
                val edge = SniEch.edgeFor(ctx, RstaSpoofManager.storedRoute(ctx).first)
                val uris = nodes.mapNotNull { SniEch.directUri(it.uri, edge) }
                if (uris.isEmpty()) return Prepared.Failed(ScoutStatus.NOT_APPLICABLE, tr("کانفیگ‌ها برای ECH مناسب نیستند", "The configs do not suit ECH"))
                kit.step(Step.CONNECTING, tr("روش ECH…", "ECH method…"))
                val won = firstAnswer(ctx, uris)
                    ?: return Prepared.Failed(ScoutStatus.FAILED, tr("از راه ECH جوابی نیامد", "No answer through ECH"), Step.CONNECTING)
                easyWorked = true
                val h = uriHandle(won.first) ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("کانفیگ خوانده نشد", "Could not read the config"))
                return Prepared.Ready(Handle(h, front = false), "ECH")
            }
            else -> {
                if (easyWorked) return Prepared.Failed(ScoutStatus.NOT_APPLICABLE, tr("روش فعلی جواب داد؛ لازم نشد", "The current method works; not needed"))
                return ladder(kit, nodes.map { it.uri })
            }
        }
    }

    /** Every other front method, as the SNI screen's own ladder walks it; the user's choice is put back afterwards. */
    private suspend fun ladder(kit: ScoutKit, uris: List<String>): Prepared {
        val ctx = kit.context
        val original = RstaSpoofManager.storedProfile(ctx)
        withContext(Dispatchers.IO) { RstaSpoofManager.acquire(ctx) }
        try {
            for ((i, p) in RstaSpoofManager.PROFILES.withIndex()) {
                if (p.id == original.id) continue
                kit.step(Step.CONNECTING, tr("روش ${i + 1} از ${RstaSpoofManager.PROFILES.size}…", "Method ${i + 1} of ${RstaSpoofManager.PROFILES.size}…"))
                val up = withContext(Dispatchers.IO) { RstaSpoofManager.setProfile(ctx, p.id); RstaSpoofManager.restart(ctx) }
                if (!up) continue
                val won = firstAnswer(ctx, uris) ?: continue
                return Prepared.Done(VariantResult("", "", status = ScoutStatus.WORKS,
                    reason = tr("با روش شمارهٔ ${i + 1} جلوی محلی کار کرد؛ در صفحهٔ SNI «تست» را بزنید تا همین روش انتخاب شود",
                        "Works with front method ${i + 1}; press «test» on the SNI page to switch to it"),
                    numbers = Numbers(latencyMs = won.second), detail = tr("روش ${i + 1}", "Method ${i + 1}")))
            }
            return Prepared.Failed(ScoutStatus.FAILED, tr("هیچ‌کدام از روش‌های جلو جوابی نگرفت", "None of the front's methods got an answer"), Step.CONNECTING)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                RstaSpoofManager.setProfile(ctx, original.id)
                RstaSpoofManager.release()
            }
        }
    }

    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> {
        val h = ready.handle as Handle
        if (!h.front) return super.serve(kit, Prepared.Ready(h.xray, ready.detail), block)
        return withFront(kit.context) { super.serve(kit, Prepared.Ready(h.xray, ready.detail), block) }
    }
}

// ---- Domain fronting ----------------------------------------------------------------------------

/**
 * The fronting profile terminates YouTube's TLS with this device's own certificate and re-dials it
 * under another name. The shared yardstick trusts only the system's certificates, so it would call
 * a working front broken; this tester trusts the app's certificate as well, and nothing else changes.
 */
internal object FrontingAdapter : ScoutAdapter() {
    override val id = "fronting"
    override val homeId = "fronting"
    override val kind = MethodKind.UNFILTER
    override val lanes = setOf(Lane.XRAY)
    override val estimateSec = 25

    override fun howTested() = tr(
        "پروفایل فرانتینگ در هستهٔ تست بالا می‌آید و یوتیوب از داخلش باز می‌شود (با گواهی خود برنامه)؛ برای تست، نصب گواهی لازم نیست.",
        "The fronting profile runs in the test core and YouTube is opened through it (with the app's own certificate); testing needs no installed certificate.")

    override fun readiness(ctx: Context): Readiness = Readiness.Ready
    override fun variants(ctx: Context, line: LineSnapshot) = listOf(Variant("fronting", tr("دامین فرانتینگ", "Domain fronting"), quick = true, budgetMs = 90_000))

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared = withContext(Dispatchers.IO) {
        val ctx = kit.context
        if (!MitmCertManager.ensure(ctx)) return@withContext Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("گواهی ساخته نشد", "The certificate could not be made"))
        val base = MitmProfile.buildConfig(ctx) ?: return@withContext Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("پروفایل خوانده نشد", "The profile could not be read"))
        val cert = MitmCertManager.loadCert(ctx) ?: return@withContext Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("گواهی خوانده نشد", "The certificate could not be read"))
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val j = JSONObject(base).put("log", JSONObject().put("loglevel", "none"))
        val inbounds = j.optJSONArray("inbounds") ?: JSONArray()
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            if (ib.optString("protocol") == "mixed") ib.put("port", port).put("listen", "127.0.0.1")
        }
        kit.step(Step.CONNECTING)
        val started = runInterruptible {
            AwgHostClient.xray.call(ctx, XrayProbeService.MSG_START, Bundle().apply { putString(XrayProbeService.KEY_CONFIG, j.toString()) }, 20_000)
                ?.getBoolean(XrayProbeService.KEY_OK) == true
        }
        try {
            if (!started) return@withContext Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("هستهٔ تست پروفایل را اجرا نکرد", "The test core did not run the profile"))
            kit.step(Step.MEASURING, tr("باز کردن یوتیوب…", "Opening YouTube…"))
            val first = runInterruptible { youtube(port, cert, 15_000) } ?: runInterruptible { youtube(port, cert, 15_000) }
                ?: return@withContext Prepared.Failed(ScoutStatus.FAILED, tr("یوتیوب از داخلش باز نشد", "YouTube did not open through it"), Step.CONNECTING)
            val again = runInterruptible { youtube(port, cert, 10_000) }
            val trusted = runCatching { MitmCertManager.isTrusted(ctx) }.getOrDefault(false)
            Prepared.Done(VariantResult("", "", status = ScoutStatus.WORKS,
                reason = if (trusted) tr("یوتیوب باز شد؛ فقط در مرورگر کار می‌کند", "YouTube opened; works in a browser only")
                    else tr("یوتیوب باز شد؛ برای استفاده باید گواهی برنامه نصب شود (فقط مرورگر)", "YouTube opened; using it needs the app's certificate installed (browser only)"),
                numbers = Numbers(connectMs = first, latencyMs = again ?: first, filtered = Tri.YES)))
        } finally {
            withContext(NonCancellable) { AwgHostClient.xray.call(ctx, XrayProbeService.MSG_STOP, null, 5_000) }
        }
    }

    /** Never reached: [prepare] runs the profile and answers itself (see the class comment). */
    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> =
        Served.Failed(tr("این روش پورت آزمون مشترک ندارد", "This method has no shared test port"), inconclusive = true)

    /** One request to YouTube through the front, trusting the app's certificate beside the system's; ms or null. */
    private fun youtube(port: Int, cert: java.security.cert.X509Certificate, timeoutMs: Int): Long? = runCatching {
        val host = ScoutNet.FILTERED_HOST
        val t0 = System.nanoTime()
        Socket(java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { raw ->
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress.createUnresolved(host, 443), timeoutMs)
            ScoutNet.guarded(raw) {
                (trusting(cert).socketFactory.createSocket(raw, host, 443, true) as SSLSocket).use { t ->
                    t.sslParameters = t.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    t.soTimeout = timeoutMs
                    t.startHandshake()
                    val r = ScoutNet.request(t, host, "/", keepAlive = false, maxBody = 4096)
                    if (r.code in 200..399) (System.nanoTime() - t0) / 1_000_000 else null
                }
            }
        }
    }.getOrNull()

    private fun trusting(cert: java.security.cert.X509Certificate): SSLContext {
        fun tm(ks: java.security.KeyStore?): X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
        val own = tm(java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("mlm", cert) })
        val system = tm(null)
        val both = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {
                try { own.checkServerTrusted(chain, authType) } catch (_: Exception) { system.checkServerTrusted(chain, authType) }
            }
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = system.acceptedIssuers + own.acceptedIssuers
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(both), null) }
    }
}
