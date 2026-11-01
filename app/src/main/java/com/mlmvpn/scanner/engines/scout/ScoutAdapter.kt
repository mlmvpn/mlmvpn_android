package com.mlmvpn.scanner.engines.scout

import android.content.Context
import android.net.Network
import com.mlmvpn.scanner.engines.scout.adapters.AetherWarpAdapter
import com.mlmvpn.scanner.engines.scout.adapters.AmneziaAdapter
import com.mlmvpn.scanner.engines.scout.adapters.AntiSanctionAdapter
import com.mlmvpn.scanner.engines.scout.adapters.LanternAdapter
import com.mlmvpn.scanner.engines.scout.adapters.CloudPanelsAdapter
import com.mlmvpn.scanner.engines.scout.adapters.FenixAdapter
import com.mlmvpn.scanner.engines.scout.adapters.FluxAdapter
import com.mlmvpn.scanner.engines.scout.adapters.FreeConfigAdapter
import com.mlmvpn.scanner.engines.scout.adapters.OutlineAdapter
import com.mlmvpn.scanner.engines.scout.adapters.FrontingAdapter
import com.mlmvpn.scanner.engines.scout.adapters.GephAdapter
import com.mlmvpn.scanner.engines.scout.adapters.GithubAdapter
import com.mlmvpn.scanner.engines.scout.adapters.GoogleScriptAdapter
import com.mlmvpn.scanner.engines.scout.adapters.IranConfigAdapter
import com.mlmvpn.scanner.engines.scout.adapters.MaeAdapter
import com.mlmvpn.scanner.engines.scout.adapters.MyConfigsAdapter
import com.mlmvpn.scanner.engines.scout.adapters.NpvAdapter
import com.mlmvpn.scanner.engines.scout.adapters.OpenVpnAdapter
import com.mlmvpn.scanner.engines.scout.adapters.ProtonAdapter
import com.mlmvpn.scanner.engines.scout.adapters.WindscribeAdapter
import com.mlmvpn.scanner.engines.scout.adapters.PsiphonAdapter
import com.mlmvpn.scanner.engines.scout.adapters.QuickConnectAdapter
import com.mlmvpn.scanner.engines.scout.adapters.SniAdapter
import com.mlmvpn.scanner.engines.scout.adapters.TorAdapter
import com.mlmvpn.scanner.engines.scout.adapters.TunnelCoreAdapter
import com.mlmvpn.scanner.engines.scout.adapters.UrnetworkAdapter
import com.mlmvpn.scanner.engines.scout.adapters.VpnGateAdapter
import com.mlmvpn.scanner.ui.home.HomeApp
import com.mlmvpn.scanner.ui.home.HomeDestinations
import com.mlmvpn.scanner.ui.tunnel.Transport

/**
 * The things two methods can fight over. A method declares what it needs, and the scheduler runs
 * methods that share nothing side by side and the rest one after another -- so a new method is
 * scheduled correctly by saying what it uses, not by anyone editing the scheduler.
 */
enum class Lane {
    /** The `:xprobe` test core (one at a time) and the in-process Xray races. */
    XRAY,
    /** The Rust tunnel core: MASQUE / WireGuard / WARP-in-WARP, single instance. */
    TUNNEL_CORE,
    AETHER,
    PSIPHON,
    TOR,
    GEPH,
    LANTERN,
    URNETWORK,
    AWG,
    SNI,
    GST,
}

/** What the phone's line looks like before anything is measured -- from the system, no traffic. */
data class LineSnapshot(val hasV4: Boolean, val hasV6: Boolean, val cellular: Boolean, val network: Network?)

/** Can this method run here at all? Answered locally and instantly. */
sealed class Readiness {
    object Ready : Readiness()
    /** An account, a config or a panel is missing: «راه‌اندازی نشده», never «خراب». */
    data class NeedsSetup(val reason: String) : Readiness()
    data class NotInstalled(val reason: String) : Readiness()
}

/** What [ScoutAdapter.prepare] found to run: a server, a route, a config. */
sealed class Prepared {
    data class Ready(val handle: Any, val detail: String? = null) : Prepared()
    data class Failed(val status: ScoutStatus, val reason: String, val at: Step = Step.PREPARING) : Prepared()
    /**
     * The engine's own test already gave the answer, and its path cannot be handed to the shared
     * yardstick as a plain local port (a relay that lives only inside the engine's test, a front
     * that needs its own certificate). The result is kept as measured, marked not fully measured.
     */
    data class Done(val result: VariantResult) : Prepared()
}

/**
 * A handle that learns its detail while it is served -- the way that connected, of several tried
 * in turn -- rather than knowing it when prepared. The result shows it in place of [Prepared.Ready.detail].
 */
interface LiveDetail { val liveDetail: String? }

sealed class Served<out T> {
    data class Ok<T>(val value: T) : Served<T>()
    /** [inconclusive]: the tester broke (a core crashed), which says nothing about the method. */
    data class Failed(val reason: String, val inconclusive: Boolean = false) : Served<Nothing>()
}

/** What a running test can tell the screen, and the line it runs on. */
class ScoutKit(
    val context: Context,
    val line: LineSnapshot,
    private val onStep: (Step, String?) -> Unit,
) {
    fun step(s: Step, note: String? = null) = onStep(s, note)
}

/**
 * One way of getting online, as Scout tests it. Adding a method is one small file: an id, the
 * home icon it opens, its variants, a readiness check, the lanes it needs, and how to bring it up
 * as a local proxy. Scheduling, measuring, scoring and the screens are shared.
 *
 * The shared yardstick never lives here: an adapter only hands back a local SOCKS port; verifying
 * data, the delay, stability, speed and the exit country are measured by one common piece of code
 * for every method, which is what makes their numbers comparable.
 */
abstract class ScoutAdapter {
    abstract val id: String
    /** The [HomeDestinations] id «رفتن به این روش» opens. */
    abstract val homeId: String
    abstract val kind: MethodKind
    open val evidence: Evidence = Evidence.DATA
    abstract val lanes: Set<Lane>
    /** False while this method's tester is still to come (a later stage of Scout). */
    open val built: Boolean = true
    /** A usual run's cost, for the estimate shown before starting. */
    open val estimateSec: Int = 15
    open val estimateMb: Double = 0.4
    /** Restart allowance plus a short sample; the download itself is still capped at 20 seconds. */
    open val speedBudgetMs: Long = 45_000
    open val discoversVariants: Boolean = false
    /** Network discovery belongs to the full pass, so setup never delays the first answers. */
    open suspend fun discover(kit: ScoutKit): List<Variant> = variants(kit.context, kit.line)
    open fun requiresForeignExit(variantId: String): Boolean = true

    /** How it is tested, in a sentence, for the coverage page. */
    abstract fun howTested(): String

    abstract fun readiness(ctx: Context): Readiness

    /** This method's variants on [line]. Local only: no network here. */
    abstract fun variants(ctx: Context, line: LineSnapshot): List<Variant>

    /** A handshake-only method has no SOCKS port and must never enter the data/speed yardstick. */
    open suspend fun handshake(kit: ScoutKit, v: Variant): VariantResult =
        VariantResult(v.id, v.label, v.fam, ScoutStatus.INCONCLUSIVE)

    open suspend fun prepare(kit: ScoutKit, v: Variant): Prepared =
        Prepared.Failed(ScoutStatus.UPCOMING, com.mlmvpn.scanner.store.tr("تست این روش به‌زودی اضافه می‌شود", "Testing this method comes soon"))

    /** Bring [ready] up as a local SOCKS proxy for the length of [block]; it is down again afterwards. */
    open suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> =
        Served.Failed(com.mlmvpn.scanner.store.tr("به‌زودی", "Soon"))

    /** Many variants for a yes/no at once (the full pass). Null: each is measured in full. */
    open suspend fun sweep(kit: ScoutKit, variants: List<Variant>): Map<String, VariantResult>? = null

    fun homeApp(): HomeApp? = ScoutRegistry.homeApp(homeId)
}

object ScoutRegistry {

    /**
     * Every method Scout tests, in the order the screen lists them (docs/SCOUT-PLAN.md section 2).
     * The coverage test (ScoutCoverageTest) fails the build's tests if a home destination that
     * connects, holds configs or is an emergency way is neither here nor in [EXCLUDED].
     */
    val ALL: List<ScoutAdapter> by lazy {
        (Transport.entries.mapNotNull { t ->
            // Exhaustive on purpose: a new transport does not compile until Scout knows it.
            when (t) {
                Transport.MASQUE, Transport.WIREGUARD, Transport.GOOL -> TunnelCoreAdapter(t)
                Transport.CFWG -> AetherWarpAdapter
                Transport.PSIPHON -> PsiphonAdapter
                Transport.TOR -> TorAdapter
                Transport.GEPH -> GephAdapter
                Transport.LANTERN -> LanternAdapter
                Transport.URNETWORK -> UrnetworkAdapter
            }
        } + listOf(
            QuickConnectAdapter, FluxAdapter, AmneziaAdapter, FreeConfigAdapter, VpnGateAdapter, OpenVpnAdapter,
            CloudPanelsAdapter, MyConfigsAdapter, MaeAdapter, FenixAdapter, ProtonAdapter, WindscribeAdapter, OutlineAdapter, NpvAdapter,
            GithubAdapter, GoogleScriptAdapter, AntiSanctionAdapter, IranConfigAdapter, SniAdapter, FrontingAdapter,
        ))
            // Not a method whose tile this build does not show (HomeDestinations.IN_PROGRESS in a release).
            .filter { homeApp(it.homeId) != null }
    }

    fun byId(id: String): ScoutAdapter? = ALL.firstOrNull { it.id == id }

    /**
     * Home destinations Scout does not test, each with its reason (plan 2-هـ). A destination that is
     * neither tested nor listed here is a forgotten method, and ScoutCoverageTest says so.
     */
    val EXCLUDED: Map<String, String> = linkedMapOf(
        "scanner" to "اسکن آی‌پی تمیز: ابزار ساخت است، روش اتصال نیست",
        "settings" to "تنظیمات",
        "sublink" to "لینک ساب: ابزار مدیریت کانفیگ‌هاست",
        "configstudio" to "کانفیگ استودیو: ابزار ساخت است؛ کانفیگ‌هایش در «کانفیگ‌های من» و «پنل‌های ابری» تست می‌شوند",
        "fixed_ip" to "آی‌پی ثابت: ابزار است",
        "lan" to "شبکهٔ محلی: اشتراک اتصال، روش جدا نیست",
        "store" to "استور: نصب و به‌روزرسانی",
        "usage" to "مصرف: فقط اطلاعات",
        "tutorial" to "آموزش‌ها",
        "game" to "گیم بوستر: روش اتصال جدا ندارد؛ از وارپ، آمنزیا، FENIX و DNS استفاده می‌کند که همه جدا تست می‌شوند",
        "scout" to "خود Scout",
        "tgproxy" to "پروکسی تلگرام: ابزار ساخت پروکسی برای تلگرام است، روش اتصال گوشی نیست",
        "widgetstudio" to "ویجت استدیو: ابزار ساخت ویجت است؛ هر ویجت یکی از همین روش‌ها را وصل می‌کند",
        "mlmchat" to "گره: پیام‌رسان گوشی‌به‌گوشی است، روش اتصال گوشی به اینترنت نیست",
    )

    fun homeApp(id: String): HomeApp? =
        HomeDestinations.gridById(id) ?: HomeDestinations.DOCK.firstOrNull { it.id == id }
}
