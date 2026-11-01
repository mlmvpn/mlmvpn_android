package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.CombineEngine
import com.mlmvpn.scanner.data.IranProfileTester
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.engines.arena.ArenaPanels
import com.mlmvpn.scanner.engines.cfdoctor.NativeProbe
import com.mlmvpn.scanner.engines.flux.FluxEngine
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.freeconfig.FreeConfigEngine
import com.mlmvpn.scanner.engines.rstaspoof.SniSession
import com.mlmvpn.scanner.engines.scout.Fam
import com.mlmvpn.scanner.engines.scout.Lane
import com.mlmvpn.scanner.engines.scout.LineSnapshot
import com.mlmvpn.scanner.engines.scout.MethodKind
import com.mlmvpn.scanner.engines.scout.Numbers
import com.mlmvpn.scanner.engines.scout.Prepared
import com.mlmvpn.scanner.engines.scout.Readiness
import com.mlmvpn.scanner.engines.scout.ScoutAdapter
import com.mlmvpn.scanner.engines.scout.ScoutKit
import com.mlmvpn.scanner.engines.scout.ScoutStatus
import com.mlmvpn.scanner.engines.scout.Served
import com.mlmvpn.scanner.engines.scout.Step
import com.mlmvpn.scanner.engines.scout.Tri
import com.mlmvpn.scanner.engines.scout.Variant
import com.mlmvpn.scanner.engines.scout.VariantResult
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.quick.MlmPoolClient
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import java.util.Locale

/**
 * The Xray family: everything that becomes one Xray config. Each is run in the `:xprobe` process
 * (NativeProbe): a Go panic in a core costs one answer, never the app, and only one test core is up
 * at a time -- which is why they share [Lane.XRAY] and go one after another.
 */

/** An Xray config ready for the test core. [keepRouting]: a serverless config IS its routing. */
internal data class XrayHandle(val json: String, val keepRouting: Boolean = false)

internal abstract class XrayAdapter : ScoutAdapter() {
    override val lanes: Set<Lane> = setOf(Lane.XRAY)

    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> {
        val h = ready.handle as? XrayHandle ?: return Served.Failed(tr("داده‌ای برای اجرا نبود", "Nothing to run"), inconclusive = true)
        val (code, value) = NativeProbe(kit.context).withCore(h.json, h.keepRouting) { port -> block(port) }
        @Suppress("UNCHECKED_CAST")
        return if (code == "ok") Served.Ok(value as T)
        else Served.Failed(coreReason(code), inconclusive = code == "core_crashed")
    }

    protected fun coreReason(code: String): String = when (code) {
        "config_invalid" -> tr("کانفیگ نامعتبر است", "The config is invalid")
        "core_refused" -> tr("هستهٔ Xray این کانفیگ را نپذیرفت", "The Xray core refused the config")
        "core_crashed" -> tr("ابزار تست از کار افتاد؛ نتیجه نامشخص است", "The test core crashed; no verdict")
        "core_not_listening" -> tr("هستهٔ Xray بالا نیامد", "The Xray core did not come up")
        else -> code
    }

    /** One link as the test core runs it: the same config (and the same unblock method) a connect uses. */
    protected fun uriHandle(uri: String): XrayHandle? =
        VpnConfig.parseUri(uri)?.takeIf { it.address.isNotBlank() }?.let { XrayHandle(XrayJsonGenerator.generateSpeedtestConfig(it)) }

    /**
     * The fastest of [uris] that carries one real request, tested a few at a time the way the app's
     * own delay test does. Null when none answered.
     */
    protected suspend fun fastest(ctx: Context, uris: List<String>, parallel: Int, timeoutMs: Long): Pair<String, Long>? = coroutineScope {
        if (uris.isEmpty()) return@coroutineScope null
        FreeConfigEngine.ensureXrayEnv(ctx)
        val gate = Semaphore(parallel)
        uris.distinct().map { u -> async(Dispatchers.IO) { gate.withPermit { u to CombineEngine.measureUri(ctx, u, timeoutMs) } } }
            .awaitAll().filter { it.second > 0 }.minByOrNull { it.second }
    }
}

/** Up to three of a panel's configs: plain TLS on 443 first, the panel's most typical one (as the Arena picks). */
internal fun pickTypical(configs: List<String>, max: Int = 3): List<String> {
    val parsed = configs.mapNotNull { u -> VpnConfig.parseUri(u)?.let { u to it } }
    return parsed.sortedBy { (_, c) -> (if (c.port == 443) 0 else 1) + (if (c.tls == "tls") 0 else 2) }
        .map { it.first }.distinct().take(max).ifEmpty { configs.take(max) }
}

/** The user's configs, read without waiting for the list to load: the stored file if it is not in memory yet. */
internal object ScoutNodes {
    fun all(ctx: Context): List<VpnNode> {
        val nm = NodeManager(ctx)
        val live = synchronized(nm.nodes) { nm.nodes.toList() }
        if (live.isNotEmpty()) return live
        return nm.storedNodes()
    }
}

internal fun countryName(cc: String?): String? =
    cc?.takeIf { it.length == 2 }?.let { Locale("", it).getDisplayCountry(com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale()).ifBlank { it } }

// ---------------------------------------------------------------------------------------------

/** «کانفیگ ایران»: serverless configs that pass the filter by fragmenting; each run for real. */
internal object IranConfigAdapter : XrayAdapter() {
    override val id = "iran"
    override val homeId = "iran"
    override val kind = MethodKind.UNFILTER
    override val estimateSec = 25
    override val estimateMb = 0.4

    override fun howTested() = tr(
        "هر پروفایل با قواعد خودش واقعاً اجرا می‌شود و باید یوتیوب از داخلش باز شود. پاسخ سریع یک پروفایل؛ بررسی کامل همه را می‌آزماید و بهترین را کامل می‌سنجد.",
        "Each profile runs for real with its own rules and must open YouTube. The quick pass tries one; the full pass sweeps all and measures the best in full.",
    )

    override fun readiness(ctx: Context): Readiness = Readiness.Ready

    private data class Profile(val id: String, val name: String, val config: String)

    /** Read from the app's (or the Store's newer) profile file -- never a hand-kept list. */
    private fun profiles(ctx: Context): List<Profile> = runCatching {
        val raw = com.mlmvpn.scanner.store.StoreFiles.open(ctx, "iran_profiles.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val cfg = o.optString("config")
            if (cfg.isBlank()) null else Profile(o.optString("id").ifBlank { "iran-$i" }, o.optString("name").ifBlank { o.optString("id") }, cfg)
        }
    }.getOrDefault(emptyList())

    override fun variants(ctx: Context, line: LineSnapshot): List<Variant> =
        profiles(ctx).mapIndexed { i, p -> Variant(p.id, p.name, quick = i == 0, budgetMs = 30_000, payload = p.config) }

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared =
        Prepared.Ready(XrayHandle(v.payload as String, keepRouting = true), detail = v.label)

    /** The rest, four at a time, for a yes/no each: YouTube opened, only an open site, or nothing. */
    override suspend fun sweep(kit: ScoutKit, variants: List<Variant>): Map<String, VariantResult> {
        kit.step(Step.MEASURING, tr("آزمودن ${variants.size} پروفایل…", "Trying ${variants.size} profiles…"))
        val byId = variants.associateBy { it.id }
        val out = IranProfileTester.testAll(kit.context, variants.associate { it.id to (it.payload as String) })
        val now = System.currentTimeMillis()
        return out.mapNotNull { (id, verdict) ->
            val v = byId[id] ?: return@mapNotNull null
            id to when (verdict.level) {
                IranProfileTester.Level.OK -> VariantResult(id, v.label, status = ScoutStatus.WORKS, at = now, full = false,
                    numbers = Numbers(latencyMs = verdict.ms, filtered = Tri.YES))
                IranProfileTester.Level.PARTIAL -> VariantResult(id, v.label, status = ScoutStatus.WEAK, at = now, full = false,
                    reason = tr("وصل شد ولی یوتیوب باز نشد", "Connects, but YouTube did not open"),
                    numbers = Numbers(latencyMs = verdict.ms, filtered = Tri.NO))
                IranProfileTester.Level.FAIL -> VariantResult(id, v.label, status = ScoutStatus.FAILED, at = now, full = false,
                    reason = verdict.why.ifBlank { tr("هیچ سایتی باز نشد", "Nothing opened") }, failedAt = Step.CONNECTING)
            }
        }.toMap()
    }
}

/** «اتصال سریع»: the shared MLM pool, raced the way the Quick Connect button races it. */
internal object QuickConnectAdapter : XrayAdapter() {
    override val id = "quick"
    override val homeId = "quick"
    override val kind = MethodKind.TUNNEL
    override val estimateSec = 25
    override val estimateMb = 0.6

    override fun howTested() = tr(
        "سرورهای استخر مشترک گرفته و هم‌زمان آزموده می‌شوند؛ سریع‌ترینی که دادهٔ واقعی رد کند با خط‌کش مشترک سنجیده می‌شود. اگر خود استخر جواب ندهد، «سرویس در دسترس نیست» نوشته می‌شود.",
        "The shared pool's servers are raced; the fastest that carries real data is measured. If the pool itself does not answer, it reads «service unavailable».",
    )

    override fun readiness(ctx: Context): Readiness = Readiness.Ready

    override fun variants(ctx: Context, line: LineSnapshot) =
        listOf(Variant("pool", tr("سرورهای مشترک MLM", "Shared MLM servers"), quick = true, budgetMs = 80_000))

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        kit.step(Step.PREPARING, tr("گرفتن سرورهای مشترک…", "Fetching shared servers…"))
        val pool = MlmPoolClient.fetch(kit.context)
        if (pool.isEmpty()) return Prepared.Failed(ScoutStatus.SERVICE_DOWN,
            tr("سرویس سرورهای مشترک جواب نداد؛ ایراد از سمت سرویس است، نه اینترنت شما", "The shared-server service did not answer; that is the service, not your line"))
        kit.step(Step.CONNECTING, tr("آزمودن ${pool.size} سرور…", "Trying ${pool.size} servers…"))
        val won = fastest(kit.context, pool.map { it.uri }.take(24), parallel = 6, timeoutMs = 10_000)
            ?: return Prepared.Failed(ScoutStatus.FAILED, tr("هیچ‌کدام از ${pool.size} سرور مشترک روی این خط جواب نداد", "None of the ${pool.size} shared servers answered on this line"), Step.CONNECTING)
        val handle = uriHandle(won.first) ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("کانفیگ سرور خوانده نشد", "Could not read the server's config"))
        return Prepared.Ready(handle, detail = countryName(pool.firstOrNull { it.uri == won.first }?.country))
    }
}

/** «FLUX»: its own route for this line on each IP family -- the planner, racer and memory of a connect, no tunnel. */
internal object FluxAdapter : XrayAdapter() {
    override val id = "flux"
    override val homeId = "flux"
    override val kind = MethodKind.TUNNEL
    override val estimateSec = 30
    override val estimateMb = 0.8

    override fun howTested() = tr(
        "FLUX مسیر همین شبکه را خودش پیدا می‌کند (مسیر شناخته‌شده یا مسابقه)، جدا برای IPv4 و IPv6؛ مسیر برنده در هستهٔ تست بالا می‌آید و سنجیده می‌شود.",
        "FLUX finds this network's route itself (a known one or a race), separately on IPv4 and IPv6; the winner is brought up in the test core and measured.",
    )

    override fun readiness(ctx: Context): Readiness = Readiness.Ready

    override fun variants(ctx: Context, line: LineSnapshot) = listOf(
        Variant("v4", "IPv4", Fam.V4, quick = line.hasV4, budgetMs = 100_000,
            notApplicable = if (!line.hasV4) tr("این خط IPv4 ندارد", "This line has no IPv4") else null),
        Variant("v6", "IPv6", Fam.V6, quick = !line.hasV4 && line.hasV6, budgetMs = 100_000,
            notApplicable = if (!line.hasV6) tr("این خط IPv6 ندارد", "This line has no IPv6") else null),
    )

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        kit.step(Step.PREPARING, tr("پیدا کردن مسیر FLUX…", "Finding a FLUX route…"))
        val r = FluxEngine.scoutRoute(kit.context, if (v.fam == Fam.V6) Family.V6 else Family.V4)
            ?: return Prepared.Failed(ScoutStatus.FAILED, tr("FLUX روی این خط مسیری پیدا نکرد", "FLUX found no route on this line"), Step.CONNECTING)
        return Prepared.Ready(XrayHandle(r.config), detail = countryName(r.country))
    }
}

/** «کانفیگ رایگان»: the public lists, the way its importer finds working ones. */
internal object FreeConfigAdapter : XrayAdapter() {
    override val id = "freeconfig"
    override val homeId = "freeconfig"
    override val kind = MethodKind.TUNNEL
    override val estimateSec = 40
    override val estimateMb = 1.5

    override fun howTested() = tr(
        "فهرست‌های عمومی گرفته می‌شوند و اولین کانفیگی که دادهٔ واقعی رد کند با خط‌کش مشترک سنجیده می‌شود.",
        "The public lists are fetched; the first config that carries real data is measured.",
    )

    override fun readiness(ctx: Context): Readiness = Readiness.Ready

    override fun variants(ctx: Context, line: LineSnapshot) =
        // Fetching the lists and racing their configs took 146 s in one scan and ran out of 100 s in
        // the next («در زمان مقرر به نتیجه نرسید» while the method worked).
        listOf(Variant("lists", tr("فهرست‌های عمومی", "Public lists"), quick = true, budgetMs = 240_000))

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        kit.step(Step.PREPARING, tr("گرفتن فهرست‌های عمومی…", "Fetching the public lists…"))
        val cands = runCatching { FreeConfigEngine.fetchCandidates() }.getOrDefault(emptyList())
        if (cands.isEmpty()) return Prepared.Failed(ScoutStatus.SERVICE_DOWN,
            tr("فهرست‌های عمومی کانفیگ دریافت نشد", "The public config lists could not be fetched"))
        kit.step(Step.CONNECTING, tr("آزمودن کانفیگ‌ها…", "Trying configs…"))
        val found = FreeConfigEngine.collectWorking(kit.context, cands, targetCount = 1) { }
        val uri = found.firstOrNull()?.uri
            ?: return Prepared.Failed(ScoutStatus.FAILED, tr("از ${cands.size} کانفیگ عمومی، هیچ‌کدام روی این خط کار نکرد", "None of ${cands.size} public configs worked on this line"), Step.CONNECTING)
        val handle = uriHandle(uri) ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("کانفیگ خوانده نشد", "Could not read the config"))
        return Prepared.Ready(handle)
    }
}

/** «Outline»: the section's own pool -- starred, proven here, the user's keys, then the most promising -- raced. */
internal object OutlineAdapter : XrayAdapter() {
    override val id = "outline"
    override val homeId = "outline"
    override val kind = MethodKind.TUNNEL
    override val estimateSec = 30
    override val estimateMb = 0.8

    override fun howTested() = tr(
        "تا ۲۴ سرور Outline (اول ستاره‌دارها، کلیدهای خودتان و آن‌هایی که روی همین شبکه جواب داده‌اند) هم‌زمان آزموده می‌شوند و سریع‌ترینی که دادهٔ واقعی رد کند با خط‌کش مشترک سنجیده می‌شود؛ همان سرورها از داخل وارپ هم جدا آزموده می‌شوند، مثل خود بخش Outline. برنده در بخش Outline با برچسب «تأیید Scout» بالا می‌آید.",
        "Up to 24 Outline servers (starred ones, your own keys and those that answered on this network first) are raced; the fastest that carries real data is measured. The same servers are also tried from inside WARP, as the Outline section does. The winner is marked «Scout-proved» in the Outline section.",
    )

    override fun readiness(ctx: Context): Readiness = Readiness.Ready

    override fun variants(ctx: Context, line: LineSnapshot) = listOf(
        Variant("pool", tr("سرورهای Outline · مستقیم", "Outline servers · direct"), quick = true, budgetMs = 90_000),
        // The way the Outline section itself gets through where the line drops the servers.
        Variant("in_warp", tr("سرورهای Outline · از داخل وارپ", "Outline servers · inside WARP"), budgetMs = 240_000, unneededIf = "pool"),
    )

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        kit.step(Step.PREPARING, tr("گرفتن سرورهای Outline…", "Fetching Outline servers…"))
        val pool = com.mlmvpn.scanner.engines.outline.OutlineEngine.scoutCandidates(kit.context)
        if (pool.isEmpty()) return Prepared.Failed(ScoutStatus.SERVICE_DOWN,
            tr("فهرست سرورهای Outline دریافت نشد", "The Outline server lists could not be fetched"))
        if (v.id == "in_warp") {
            kit.step(Step.PREPARING, tr("پیدا کردن راه ورود به وارپ…", "Finding a way into WARP…"))
            val carrier = runCatching { com.mlmvpn.scanner.engines.amnezia.AmzEngine.warpCarrier(kit.context) }.getOrNull()
                ?: return Prepared.Failed(ScoutStatus.FAILED, tr("راهی به وارپ روی این خط پیدا نشد", "No way into WARP on this line"), Step.CONNECTING)
            kit.step(Step.CONNECTING, tr("آزمودن ${pool.size} سرور از داخل وارپ…", "Trying ${pool.size} servers inside WARP…"))
            val got = java.util.concurrent.ConcurrentHashMap<String, Long>()
            com.mlmvpn.scanner.engines.outline.OutlineProbe.test(kit.context, pool, 14_000, carrier) { id, ms -> if (ms != null) got[id] = ms }
            val best = got.minByOrNull { it.value }?.let { e -> pool.firstOrNull { it.id == e.key } }
                ?: return Prepared.Failed(ScoutStatus.FAILED, tr("هیچ‌کدام از ${pool.size} سرور Outline از داخل وارپ هم جواب نداد", "None of ${pool.size} Outline servers answered inside WARP either"), Step.CONNECTING)
            val json = com.mlmvpn.scanner.engines.outline.OutlineCf.probeConfig(best.uri, carrier)
                ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("کلید خوانده نشد", "Could not read the key"))
            return Prepared.Ready(XrayHandle(json), detail = listOfNotNull(countryName(best.country), tr("از داخل وارپ", "inside WARP")).joinToString(" · "))
        }
        kit.step(Step.CONNECTING, tr("آزمودن ${pool.size} سرور…", "Trying ${pool.size} servers…"))
        val won = fastest(kit.context, pool.map { it.uri }, parallel = 6, timeoutMs = 10_000)
            ?: return Prepared.Failed(ScoutStatus.FAILED, tr("هیچ‌کدام از ${pool.size} سرور Outline روی این خط جواب نداد", "None of ${pool.size} Outline servers answered on this line"), Step.CONNECTING)
        com.mlmvpn.scanner.engines.outline.OutlineEngine.scoutProved(kit.context, won.first, won.second)
        val handle = uriHandle(won.first) ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("کلید خوانده نشد", "Could not read the key"))
        return Prepared.Ready(handle, detail = countryName(pool.firstOrNull { it.uri == won.first }?.country))
    }
}

/** «کانفیگ‌های من»: the user's own configs -- manual, subscriptions, combined -- but not the panels' or SNI's. */
internal object MyConfigsAdapter : XrayAdapter() {
    override val id = "myconfigs"
    override val homeId = "nodes"
    override val kind = MethodKind.TUNNEL
    override val estimateSec = 20
    override val estimateMb = 0.5

    override fun howTested() = tr(
        "اول ۸ کانفیگ امیدوارکنندهٔ شما و اگر هیچ‌کدام جواب نداد تا ۴۰ کانفیگ هم‌زمان آزموده می‌شوند؛ سریع‌ترین با خط‌کش مشترک سنجیده می‌شود. کانفیگ‌های پنل‌ها و SNI جدا تست می‌شوند.",
        "Your 8 likeliest configs first, then up to 40 if none answers; the fastest is measured. Panel and SNI configs are tested separately.",
    )

    private fun own(ctx: Context): List<VpnNode> {
        val panels = ArenaPanels.ALL.map { it.id }.toSet()
        return ScoutNodes.all(ctx).filter {
            !NodeManager.isProtected(it) && it.engineType !in panels && !SniSession.isSniNode(it) &&
                it.id != com.mlmvpn.scanner.mitm.MitmProfile.NODE_ID && VpnConfig.parseUri(it.uri)?.address?.isNotBlank() == true
        }
    }

    override fun readiness(ctx: Context): Readiness =
        if (own(ctx).isEmpty()) Readiness.NeedsSetup(tr("هنوز کانفیگی اضافه نکرده‌اید", "You have not added any configs yet")) else Readiness.Ready

    override fun variants(ctx: Context, line: LineSnapshot) =
        listOf(Variant("mine", tr("کانفیگ‌های شما", "Your configs"), quick = true, budgetMs = 150_000))

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        val nodes = own(kit.context)
        // Those with a measured delay first (they answered before), then the newest.
        val ordered = nodes.sortedWith(compareBy<VpnNode> { it.delay.filter(Char::isDigit).toLongOrNull()?.takeIf { d -> d > 0 } ?: Long.MAX_VALUE }
            .thenByDescending { it.addedAt }).take(40)
        // The likeliest eight first; only if none of them answers, the rest -- a working config
        // further down the list must not be reported as «none of yours works».
        val first = ordered.take(8)
        kit.step(Step.CONNECTING, tr("آزمودن ${first.size} کانفیگ…", "Trying ${first.size} configs…"))
        val won = fastest(kit.context, first.map { it.uri }, parallel = 4, timeoutMs = 10_000)
            ?: ordered.drop(8).takeIf { it.isNotEmpty() }?.let { rest ->
                kit.step(Step.CONNECTING, tr("آزمودن ${rest.size} کانفیگ دیگر…", "Trying ${rest.size} more configs…"))
                fastest(kit.context, rest.map { it.uri }, parallel = 6, timeoutMs = 10_000)
            }
            ?: return Prepared.Failed(ScoutStatus.FAILED, tr("هیچ‌کدام از ${ordered.size} کانفیگ آزموده‌شدهٔ شما جواب نداد", "None of the ${ordered.size} configs tried answered"), Step.CONNECTING)
        val handle = uriHandle(won.first) ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("کانفیگ خوانده نشد", "Could not read the config"))
        return Prepared.Ready(handle, detail = ordered.firstOrNull { it.uri == won.first }?.name)
    }
}

/** «پنل‌های کلادفلر»: each panel on the user's account, through the app's current unblock method. */
internal object CloudPanelsAdapter : XrayAdapter() {
    override val id = "panels"
    override val homeId = "cloud"
    override val kind = MethodKind.TUNNEL
    override val estimateSec = 30
    override val estimateMb = 0.8

    override fun howTested() = tr(
        "هر پنل نصب‌شده جدا: تا سه کانفیگ خودش (همان‌هایی که در فهرست شماست، وگرنه تازه از پنل) با روش رفع فیلتر فعلی برنامه آزموده و سریع‌ترین سنجیده می‌شود.",
        "Each installed panel separately: up to three of its configs (yours, else fresh from the panel), with the app's current unblock method; the fastest is measured.",
    )

    override fun readiness(ctx: Context): Readiness {
        val accounts = CloudManager(ctx).loadedAccounts()
        if (accounts.isEmpty()) return Readiness.NeedsSetup(tr("حساب کلادفلر اضافه نشده", "No Cloudflare account added"))
        val any = ArenaPanels.ALL.any { p -> accounts.any { a -> runCatching { p.installed(ctx, a) }.getOrDefault(false) } }
        return if (any) Readiness.Ready else Readiness.NeedsSetup(tr("هیچ پنلی روی حساب کلادفلر نصب نشده", "No panel is installed on the Cloudflare account"))
    }

    override fun variants(ctx: Context, line: LineSnapshot): List<Variant> {
        val accounts = CloudManager(ctx).loadedAccounts()
        var first = true
        // From the Arena's lineup itself: a panel added there is tested here with no edit.
        return ArenaPanels.ALL.map { p ->
            val acc = accounts.firstOrNull { a -> runCatching { p.installed(ctx, a) }.getOrDefault(false) }
            val quick = acc != null && first
            if (quick) first = false
            Variant(p.id, p.name, quick = quick, budgetMs = 110_000, payload = acc?.accountId,
                needsSetup = if (acc == null) tr("${p.name} روی حساب نصب نشده", "${p.name} is not installed") else null)
        }
    }

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        val panel = ArenaPanels.byId(v.id) ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, v.id)
        val account = CloudManager(kit.context).loadedAccounts().firstOrNull { it.accountId == v.payload }
            ?: return Prepared.Failed(ScoutStatus.NEEDS_SETUP, tr("حساب این پنل پیدا نشد", "This panel's account was not found"))
        val stored = ScoutNodes.all(kit.context).filter { it.engineType == panel.id }.map { it.uri }
        val configs = stored.ifEmpty {
            kit.step(Step.PREPARING, tr("گرفتن کانفیگ از پنل…", "Getting configs from the panel…"))
            runCatching { panel.candidates(kit.context, account) }.getOrDefault(emptyList())
        }
        if (configs.isEmpty()) return Prepared.Failed(ScoutStatus.FAILED, tr("پنل کانفیگی نداد", "The panel gave no config"))
        val picks = pickTypical(configs)
        kit.step(Step.CONNECTING, tr("آزمودن ${picks.size} کانفیگ…", "Trying ${picks.size} configs…"))
        // The typical three first; if the line closes those (TLS on 443, say), the panel's other
        // shapes before «کار نمی‌کند» -- one of them may be exactly what this line lets through.
        val won = fastest(kit.context, picks, parallel = 3, timeoutMs = 10_000)
            ?: (pickTypical(configs, max = 12) - picks.toSet()).takeIf { it.isNotEmpty() }?.let { rest ->
                kit.step(Step.CONNECTING, tr("آزمودن ${rest.size} کانفیگ دیگر پنل…", "Trying ${rest.size} more panel configs…"))
                fastest(kit.context, rest, parallel = 4, timeoutMs = 10_000)
            }
            ?: return Prepared.Failed(ScoutStatus.FAILED, tr("از هیچ‌کدام از کانفیگ‌های پنل جوابی نیامد", "None of the panel's configs answered"), Step.CONNECTING)
        val handle = uriHandle(won.first) ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("کانفیگ خوانده نشد", "Could not read the config"))
        return Prepared.Ready(handle)
    }
}
