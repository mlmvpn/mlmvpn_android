package com.mlmvpn.scanner.ui.cloud

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mlmvpn.scanner.engines.cfnet.CfNetManager
import com.mlmvpn.scanner.engines.cfnet.CfStage
import com.mlmvpn.scanner.engines.cfnet.FirewallFamily
import com.mlmvpn.scanner.engines.cfnet.MethodView
import com.mlmvpn.scanner.engines.cfnet.StrategyEngine
import com.mlmvpn.scanner.engines.gateway.EdgeGateway
import com.mlmvpn.scanner.engines.scout.ScoutEngine
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.faCount
import com.mlmvpn.scanner.ui.tunnel.Transport
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.utils.AppLocaleManager
import com.mlmvpn.scanner.utils.NetworkSettings
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.launch

/**
 * «روش اتصال این شبکه»: the Cloud tab's opening card — one button that finds out which way of
 * reaching Cloudflare works on THIS network, and the answer it found.
 *
 * It drives the engine the app already has (CfNetManager): the SCAN stage (what the scanner dials
 * with) and the CONNECT stage (what a tunnel dials with) are diagnosed on this network with the
 * config that is at hand — the central Worker's own link first, since it is the one this account is
 * sure to have — and whatever carried real data is KEPT for the network (the strategy store) and used
 * from then on by the scanner, the connect, the health tests and every config the combine centre
 * builds. Nothing here applies it; the card only asks for the measurement and shows the verdict.
 *
 * The order follows the 2026-10 summary of what gets through Iran's two firewall types: a quick look
 * at which of its ways pass here first, then those ways proven one by one (ECH, IPv6, F&F). When none
 * carries the worker config, its two other ways -- WARP and MASQUE, given for both types -- get their
 * tiles' quick check ([ScoutEngine.quickWarp]), and the answer names the tile to connect with.
 */
@Composable
fun CfMethodCard(accounts: List<CloudAccount>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { CfNetManager.get(context) }
    val live by manager.live.collectAsState()
    var tick by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<Phase?>(null) }
    /** The WARP tile Scout is checking right now (its id), during [Phase.WARP]. */
    var warpStep by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val on = remember(tick) { manager.enabled() }
    val verdict = remember(tick) { MethodView.verdict(manager.registry, manager.store, manager.profile()) }
    val hint = remember(tick) { if (verdict.proven) null else manager.firewall() }
    val farsi = AppLocaleManager.isFarsi()

    fun name(s: com.mlmvpn.scanner.engines.cfnet.Strategy?): String = MethodView.label(s).let { if (farsi) it.first else it.second }

    fun ageText(at: Long): String = MethodView.age(System.currentTimeMillis(), at).let { a ->
        when (a.span) {
            MethodView.Span.NOW -> tr("همین حالا", "just now")
            MethodView.Span.MINUTES -> tr("${faCount(a.n.toInt())} دقیقه پیش", "${a.n} min ago")
            MethodView.Span.HOURS -> tr("${faCount(a.n.toInt())} ساعت پیش", "${a.n} h ago")
            MethodView.Span.DAYS -> tr("${faCount(a.n.toInt())} روز پیش", "${a.n} d ago")
        }
    }

    fun measure() {
        if (running) return
        running = true
        message = null
        scope.launch {
            val outcome = runCatching {
                // The central Worker's own link first: a config this account is sure to have, and a fresh one.
                val cfg = accounts.firstNotNullOfOrNull { EdgeGateway.probeLink(context, it) }?.let { VpnConfig.parseUri(it) }
                val budget = StrategyEngine.StageBudget(parallel = 1, deadlineMs = STAGE_MS)
                // A few seconds' look first: which of the summary's ways pass on this line. The one-by-one
                // proof then starts with them, then with the summary's three ways themselves -- not with
                // whatever the priors put first, which on a line of the other type ran out of time
                // before ECH or IPv6 were ever tried.
                phase = Phase.LOOK
                val look = manager.probeLine(cfg)
                val first = look?.order.orEmpty() + FirewallFamily.headline(look?.family ?: manager.firewall())
                phase = Phase.CLOUDFLARE
                val scan = manager.diagnose(CfStage.SCAN, config = cfg, budget = budget, probe = look, prefer = first)
                scan to manager.diagnose(CfStage.CONNECT, config = cfg, budget = budget, probe = look,
                    prefer = listOfNotNull(scan.winner?.id) + first)
            }
            val (scan, connect) = outcome.getOrNull() ?: run {
                running = false; phase = null; tick++
                message = tr("سنجش کامل نشد؛ دوباره امتحان کنید.", "The measurement did not finish; try again.")
                return@launch
            }
            val noConfig = scan.skippedReason?.endsWith("no_config") == true && connect.skippedReason?.endsWith("no_config") == true
            // None of the three carried a worker config: the summary's other two ways, WARP and MASQUE,
            // which it gives for both firewall types -- each tile's quick check (MLM Scout).
            val warp = if (noConfig || scan.ok || connect.ok) null else {
                phase = Phase.WARP
                try { ScoutEngine.quickWarp(context) { id -> warpStep = id } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { null }
            }
            running = false
            phase = null
            tick++
            val tried = faCount(scan.tried.size + connect.tried.size)
            val bothWarp = warp?.wireguard == true && warp?.masque == true
            val skip = warp?.skipped
            message = when {
                noConfig ->
                    tr("کانفیگ کلادفلری برای سنجش نیست: ورکر مرکزی را نصب کنید و «دریافت کانفیگ» بزنید.", "There is no Cloudflare config to measure with: install the central Worker and press «Get configs».")
                scan.ok || connect.ok -> {
                    val best = connect.winner ?: scan.winner
                    tr("روش پیدا شد: ${name(best)}. از این پس اسکنر، اتصال و کانفیگ‌های ترکیبی همین را به کار می‌برند.",
                        "Found: ${name(best)}. The scanner, the connect and combined configs use it from now on.")
                }
                warp?.wireguard == true -> tr("روش‌های کلادفلر (ECH، IPv6، F&F) روی این شبکه جواب ندادند، ولی WARP کار می‌کند: از کاشی «وایرگارد» در صفحهٔ اصلی وصل شوید." +
                        (if (bothWarp) " MASQUE (کاشی «ماسک») هم کار می‌کند." else ""),
                    "The Cloudflare ways (ECH, IPv6, F&F) did not work here, but WARP does: connect with the «WireGuard» tile on the home screen." +
                        (if (bothWarp) " MASQUE (the «MASQUE» tile) works too." else ""))
                warp?.masque == true -> tr("روش‌های کلادفلر (ECH، IPv6، F&F) روی این شبکه جواب ندادند، ولی MASQUE کار می‌کند: از کاشی «ماسک» در صفحهٔ اصلی وصل شوید.",
                    "The Cloudflare ways (ECH, IPv6, F&F) did not work here, but MASQUE does: connect with the «MASQUE» tile on the home screen.")
                skip != null -> tr("روش‌های کلادفلر روی این شبکه جواب ندادند ($tried روش آزمایش شد). WARP و MASQUE امتحان نشد: ${skipFa(skip)}",
                    "The Cloudflare ways did not work here (${scan.tried.size + connect.tried.size} tried). WARP and MASQUE were not tried: ${skipEn(skip)}")
                warp != null -> tr("هیچ روشی روی این شبکه جواب نداد: نه روش‌های کلادفلر ($tried روش آزمایش شد)، نه WARP و MASQUE در بررسی سریع. «دوباره تست کن» در MLM Scout همهٔ حالت‌های آن دو را می‌سنجد.",
                    "Nothing worked on this network: neither the Cloudflare ways (${scan.tried.size + connect.tried.size} tried) nor WARP and MASQUE in the quick check. «Retest» in MLM Scout checks all of their modes.")
                else -> tr("هیچ‌کدام از روش‌های کلادفلر روی این شبکه جواب نداد ($tried روش آزمایش شد)، و امتحان WARP و MASQUE انجام نشد. کمی بعد دوباره امتحان کنید.",
                    "None of the Cloudflare ways worked here (${scan.tried.size + connect.tried.size} tried), and WARP and MASQUE could not be tried. Try again in a moment.")
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    SettingsSectionHeader(tr("روش اتصال این شبکه", "Connection method for this network"))
    SettingsGroup {
        SettingsRow(
            title = tr("بهترین روش", "Best method"),
            icon = Icons.Default.Radar,
            tint = Ios.Blue,
            value = if (verdict.proven) name(verdict.best) else tr("هنوز سنجیده نشده", "Not measured yet"),
            subtitle = when {
                verdict.proven -> tr("اسکنر: ${name(verdict.scan)} · اتصال: ${name(verdict.connect)} · ${ageText(verdict.bestAt)}",
                    "Scanner: ${name(verdict.scan)} · Connect: ${name(verdict.connect)} · ${ageText(verdict.bestAt)}")
                hint != null -> tr("به نظر این شبکه از نوع «${familyName(hint, true)}» است؛ برای اطمینان بسنجید.", "This network looks like the «${familyName(hint, false)}» kind; measure to be sure.")
                else -> null
            },
            showChevron = false,
        )
        Separator()
        SettingsActionRow(
            label = when {
                !running -> tr("سنجش روش‌ها روی این شبکه", "Measure the methods on this network")
                phase == Phase.LOOK -> tr("نگاه سریع به این خط…", "A quick look at this line…")
                phase == Phase.WARP && warpStep == Transport.MASQUE.id -> tr("امتحان MASQUE (کاشی «ماسک»)…", "Trying MASQUE (the «MASQUE» tile)…")
                phase == Phase.WARP -> tr("امتحان WARP (کاشی «وایرگارد»)…", "Trying WARP (the «WireGuard» tile)…")
                else -> tr("در حال سنجش… ${faCount(live.tried)} روش آزمایش شد", "Measuring… ${live.tried} tried")
            },
            icon = Icons.Default.Radar,
            tint = Ios.Blue,
            busy = running,
        ) { measure() }
        if (!on) {
            Separator()
            SettingsToggle(
                title = tr("رفع فیلتر کانفیگ‌های کلادفلر", "Cloudflare config unfilter"),
                checked = false,
                onCheckedChange = { NetworkSettings.setTlsUnfilter(context, it); tick++ },
                subtitle = tr("خاموش است؛ روش پیدا‌شده برای اسکنر و اتصال اعمال نمی‌شود.", "It is off; the method found is not applied to the scanner and connect."),
            )
        }
        message?.let { text ->
            Separator()
            SettingsRow(title = text, showChevron = false)
        }
    }
    SettingsFooter(tr("نتیجه برای هر شبکه جدا ذخیره می‌شود. اسکنر، اتصال و کانفیگ‌هایی که ورکر مرکزی می‌سازد از همین استفاده می‌کنند.",
        "The result is kept per network. The scanner, the connect and the configs the central Worker makes use it."))
}

/** Each Cloudflare stage's ceiling: room for the summary's three ways even when each fails slowly (~35 s). */
private const val STAGE_MS = 120_000L

/** What the button is doing: the quick look, the Cloudflare ways, or the WARP tiles. */
private enum class Phase { LOOK, CLOUDFLARE, WARP }

private fun skipFa(s: ScoutEngine.WarpSkip): String = when (s) {
    ScoutEngine.WarpSkip.OUR_VPN -> "اول VPN برنامه را خاموش کنید و دوباره بسنجید."
    ScoutEngine.WarpSkip.FOREIGN_VPN -> "یک VPN دیگر روشن است؛ خاموشش کنید و دوباره بسنجید."
    ScoutEngine.WarpSkip.SCANNING -> "اسکن آی‌پی در جریان است؛ بعد از آن دوباره بسنجید."
    ScoutEngine.WarpSkip.BUSY -> "MLM Scout در حال کار است؛ بعد از پایانش دوباره بسنجید."
    ScoutEngine.WarpSkip.OFFLINE -> "اینترنت در دسترس نیست."
    ScoutEngine.WarpSkip.INTERRUPTED -> "امتحان نیمه‌کاره ماند؛ دوباره بسنجید."
}

private fun skipEn(s: ScoutEngine.WarpSkip): String = when (s) {
    ScoutEngine.WarpSkip.OUR_VPN -> "turn the app's VPN off first and measure again."
    ScoutEngine.WarpSkip.FOREIGN_VPN -> "another VPN is on; turn it off and measure again."
    ScoutEngine.WarpSkip.SCANNING -> "an IP scan is running; measure again after it."
    ScoutEngine.WarpSkip.BUSY -> "MLM Scout is running; measure again when it is done."
    ScoutEngine.WarpSkip.OFFLINE -> "there is no internet."
    ScoutEngine.WarpSkip.INTERRUPTED -> "the check was cut short; measure again."
}

private fun familyName(f: FirewallFamily, farsi: Boolean): String = when (f) {
    FirewallFamily.ECH -> "ECH"
    FirewallFamily.FF -> "F&F"
    FirewallFamily.OPEN -> if (farsi) "بدون تغییر" else "unchanged"
}
