package com.mlmvpn.scanner.ui.scout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.scout.*
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The page owns presentation only; leaving the tab does not cancel the foreground run. */
@Composable
fun ScoutScreen(onBack: () -> Unit, onOpen: (String) -> Unit, visible: Boolean = true) {
    val context = LocalContext.current
    val ui by ScoutEngine.ui.collectAsState()
    val history by ScoutStore.runs.collectAsState()
    var ready by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var netKey by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<String?>(null) }
    var showLine by remember { mutableStateOf(false) }
    var coverage by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, (Boolean) -> Unit>?>(null) }
    var estimate by remember { mutableStateOf(0 to 0.0) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        // ScoutRegistry.ALL loads every method's adapter, and on an old phone that took long enough
        // on the main thread for Android to call the app frozen (1.2.42 #2301): loaded here first.
        withContext(Dispatchers.IO) { ScoutEngine.init(context); ScoutRegistry.ALL }
        ready = true
        while (true) {
            now = System.currentTimeMillis()
            netKey = withContext(Dispatchers.IO) { ScoutEngine.currentNetKey(context) }
            if (!ui.running) estimate = withContext(Dispatchers.IO) { ScoutEngine.estimate(context) }
            delay(1_000)
        }
    }

    fun checked(action: (Boolean) -> Unit) {
        if (!ready || ui.running) return
        when (val check = ScoutEngine.check(context)) {
            ScoutEngine.Check.Ok -> action(false)
            is ScoutEngine.Check.OurVpn -> confirm = check.label to action
            ScoutEngine.Check.ForeignVpn -> message = tr("ابتدا VPN برنامهٔ دیگر را خاموش کنید تا اینترنت خودتان بررسی شود.", "Turn off the other app's VPN to check your own internet.")
            ScoutEngine.Check.Scanning -> message = tr("ابتدا اسکن آی‌پی را متوقف کنید.", "Stop the IP scan first.")
            ScoutEngine.Check.Offline -> message = tr("ابتدا به یک شبکه وصل شوید.", "Connect to a network first.")
        }
    }

    BackHandler(enabled = visible) {
        when {
            details != null -> details = null
            showLine -> showLine = false
            coverage -> coverage = false
            showHistory -> showHistory = false
            else -> onBack()
        }
    }
    if (!visible) return
    val run = ui.run
    val openDetails: (String) -> Unit = { details = it }
    IosScreen(largeTitle = "MLM Scout", onBack = onBack, trailing = {
        TextButton(onClick = { coverage = true }) { Text(tr("پوشش", "Coverage"), color = Ios.Blue) }
    }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.running) {
                ProgressRing(if (ui.tasksTotal > 0) ui.tasksDone.toFloat() / ui.tasksTotal else 0f) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${ui.tasksDone} / ${ui.tasksTotal}", color = Ios.Label, fontSize = 22.sp)
                        Text(mmss(now - ui.startedAt), color = Ios.SecondaryLabel)
                    }
                }
                Text(when {
                    ui.stopping -> tr("در حال توقف…", "Stopping…")
                    ui.lineDown -> tr("اینترنت قطع است؛ منتظر بازگشت خط…", "Line down; waiting for it to return…")
                    run?.pass == Pass.SPEED -> tr("نمونه‌گیری سرعت", "Sampling speed")
                    run?.pass == Pass.DEEP -> tr("بررسی کامل", "Full check")
                    else -> tr("پاسخ سریع", "Quick answer")
                }, color = Ios.SecondaryLabel)
                if (!ui.stopping) PillButton(tr("توقف و نگه‌داشتن نتایج", "Stop and keep results"), Ios.Red) { ScoutEngine.stop() }
            } else {
                ScoutLogo(false)
                Text(tr("کدام روش روی اینترنت شما کار می‌کند؟", "Which method works on your internet?"), color = Ios.Label)
                if (ready) {
                    Text(tr("برآورد بررسی کامل: ${estimate.first / 60 + 1} دقیقه · ${String.format(java.util.Locale.US, "%.1f", estimate.second)} مگابایت",
                        "Full check estimate: ${estimate.first / 60 + 1} min · ${String.format(java.util.Locale.US, "%.1f", estimate.second)} MB"), color = Ios.SecondaryLabel, fontSize = 12.sp)
                    PillButton(tr("شروع بررسی", "Start check"), Ios.Blue) { checked { ScoutEngine.start(context, pauseApproved = it) } }
                    Text(tr("راه‌اندازی‌های خودکار مثل گف انجام می‌شوند؛ دانلود موتور در برآورد مصرف بالا نیست.",
                        "Automatic setup, including Geph, runs as needed; engine downloads are additional to this estimate."), color = Ios.SecondaryLabel, fontSize = 12.sp)
                    Text(tr("کشورها در روش‌های دارای انتخاب کشور جداگانه سنجیده می‌شوند؛ تعداد خروجی‌ها زمان و مصرف بررسی را افزایش می‌دهد.",
                        "Methods with country selection test each exit separately; more exits increase time and data use."), color = Ios.SecondaryLabel, fontSize = 12.sp)
                    if (run != null && run.partial && run.netKey == netKey) {
                        PillButton(tr("ادامهٔ بررسی همین شبکه", "Resume on this network"), Ios.Blue, filled = false) {
                            checked { ScoutEngine.start(context, resume = run, pauseApproved = it) }
                        }
                    }
                    run?.paused?.let { paused ->
                        PillButton(tr("بازگشت به ${paused.label}", "Return to ${paused.label}"), Ios.Green, filled = false) {
                            if (ScoutVpn.anyVpn(context) || ScoutVpn.ourVpnUp()) {
                                message = tr("یک اتصال فعال است؛ برای تغییر اتصال به صفحهٔ روش بروید.", "A connection is active; open the method to change it.")
                            } else {
                                val home = paused.homeId
                                if (home != null) onOpen(home) else onBack()
                            }
                        }
                    }
                } else Text(tr("آماده‌سازی…", "Preparing…"), color = Ios.SecondaryLabel)
            }
            if (history.isNotEmpty()) TextButton(onClick = { showHistory = true }) { Text(tr("سابقهٔ بررسی‌ها", "History"), color = Ios.Blue) }
        }
        if (ready && run != null) {
            Text(buildList {
                add(run.netLabel)
                add(ago(run.startedAt, now))
                if (run.netKey != netKey) add(tr("مربوط به شبکهٔ دیگر", "From another network"))
                if (now - run.startedAt > ScoutStore.FRESH_MS) add(tr("قدیمی — دوباره بررسی کن", "Old — check again"))
                if (run.partial) add(tr("نتیجهٔ نیمه‌کاره", "Partial result"))
                run.dataBytes?.let { add(tr("${megabytes(it)} مگابایت مصرف برنامه", "${megabytes(it)} MB app traffic")) }
            }.joinToString(" · "), color = Ios.SecondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(20.dp))
            LineCard(run.line) { showLine = true }
            val summary = ScoutSummary.build(run.methods.values,
                { id -> ScoutRegistry.byId(id)?.let { ScoutSummary.Info(it.kind, it.evidence) } },
                run.line.v4 == FamState.WORKS, run.line.v6 == FamState.WORKS,
                ScoutStore.history(run.netKey, run.id))
            summary.best?.let { id ->
                SectionTitle(tr("بهترین انتخاب برای اینترنت شما", "Best choice for your internet"))
                MethodCard(listOfNotNull(run.methods[id]), openDetails)
            }
            listOf(
                tr("سریع‌ترین", "Fastest") to summary.fastest,
                tr("کمترین تأخیر", "Lowest delay") to summary.lowestLatency,
                tr("پایدارترین", "Most stable") to summary.mostStable,
                tr("رفع فیلتر بدون تغییر آی‌پی", "Unfilter without changing IP") to summary.unfilterBest,
                tr("ضدتحریم", "Anti-sanction") to summary.antisanctionBest,
            ).forEach { (label, id) -> if (id != null) {
                SectionTitle(label)
                MethodCard(listOfNotNull(run.methods[id]), openDetails)
            } }
            FoldSection(tr("روش‌های سالم دیگر", "Other working methods"), summary.otherHealthy.mapNotNull(run.methods::get), true, openDetails)
            FoldSection(tr("در حال بررسی و در انتظار", "Checking and waiting"), run.methods.values.filter { !it.status.finished || it.pending > 0 && ui.running }, true, openDetails)
            FoldSection(tr("کار نمی‌کنند", "Do not work"), summary.failed.mapNotNull(run.methods::get), onOpen = openDetails)
            FoldSection(tr("نیازمند راه‌اندازی", "Need setup"), summary.setup.mapNotNull(run.methods::get), onOpen = openDetails)
            FoldSection(tr("هنوز قابل تست نیستند", "Testing not yet available"), summary.upcoming.mapNotNull(run.methods::get), onOpen = openDetails)
            FoldSection(tr("سایر نتایج", "Other results"), summary.other.mapNotNull(run.methods::get), onOpen = openDetails)
        }
        Spacer(Modifier.height(24.dp))
    }

    confirm?.let { (label, action) -> AlertDialog(
        onDismissRequest = { confirm = null },
        title = { Text(tr("قطع موقت اتصال", "Pause connection")) },
        text = { Text(tr("برای بررسی اینترنت شما، اتصال $label موقتاً قطع می‌شود. بعد از بررسی می‌توانید دوباره وصل شوید.", "To check your internet, $label will be paused. You can reconnect after the check.")) },
        confirmButton = { TextButton(onClick = {
            confirm = null
            // Recheck: the network or VPN may have changed while the dialog was open.
            when (val current = ScoutEngine.check(context)) {
                ScoutEngine.Check.Ok -> action(false)
                is ScoutEngine.Check.OurVpn -> if (current.label == label) action(true) else confirm = current.label to action
                else -> checked(action)
            }
        }) { Text(tr("قطع موقت و شروع", "Pause and start")) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("انصراف", "Cancel")) } },
    ) }
    message?.let { value -> ScoutDialog(tr("MLM Scout", "MLM Scout"), { message = null }) { Text(value) } }
    if (showHistory) ScoutDialog(tr("سابقهٔ بررسی‌ها", "History"), { showHistory = false }) {
        if (ui.running) Text(tr("برای مشاهدهٔ بررسی قبلی، ابتدا بررسی جاری را متوقف کنید.", "Stop the current check before viewing history."))
        history.forEach { previous -> TextButton(enabled = !ui.running, onClick = { ScoutEngine.show(previous); showHistory = false }) {
            Text("${previous.netLabel} · ${ago(previous.startedAt)} · " + if (previous.netKey == netKey) tr("همین شبکه", "This network") else tr("شبکهٔ دیگر", "Other network"))
        } }
    }
    if (coverage) ScoutDialog(tr("پوشش روش‌ها", "Method coverage"), { coverage = false }) {
        ScoutRegistry.ALL.forEach { adapter ->
            Text(methodTitle(adapter.id), fontWeight = FontWeight.Bold)
            Text(adapter.howTested())
            if (!adapter.built) Text(tr("تست این روش هنوز پیاده‌سازی نشده است.", "This method's test is not implemented yet."), color = Ios.Orange)
            Spacer(Modifier.height(12.dp))
        }
    }
    if (showLine && run != null) ScoutDialog(tr("جزئیات اینترنت شما", "Line details"), { showLine = false }) {
        val l = run.line
        fun tri(v: Tri?) = when (v) { Tri.YES -> tr("بله", "Yes"); Tri.NO -> tr("خیر", "No"); else -> tr("نامشخص", "Unknown") }
        listOf(
            "IPv4" to famText(l.v4), "IPv6" to famText(l.v6),
            tr("اینترنت بین‌الملل", "International internet") to tri(l.intl),
            tr("اینترنت داخلی", "Domestic internet") to tri(l.domestic),
            "UDP" to (tri(l.udp) + " · " + l.udpWhy.orEmpty()), "QUIC" to tri(l.quic),
            tr("دستکاری DNS", "DNS tampering") to tri(l.dnsTampered), "DoH" to tri(l.doh),
            tr("فیلتر SNI", "SNI filtering") to tri(l.sniFilter),
            "HTTP" to tri(l.http), "HTTPS" to tri(l.https),
            tr("میانهٔ دست‌دهی TCP", "Median TCP handshake") to (ms(l.rttMs) ?: "—"),
            "ICMP" to (ms(l.icmpMs) ?: "—"),
            tr("نمونهٔ سرعت مستقیم", "Direct speed sample") to (mbps(l.mbps) ?: "—"),
            tr("نوسان UDP", "UDP jitter") to (l.jitterMs?.let { "$it ms" } ?: "—"),
            tr("گم‌شدن بسته", "Packet loss") to (l.lossPct?.let { "$it%" } ?: "—"),
            tr("MTU آزمایشی", "Experimental MTU") to (l.mtu?.toString() ?: "—"),
        ).forEach { (key, value) -> Text("$key: $value") }
    }
    details?.let { id -> run?.methods?.get(id)?.let { method ->
        val adapter = ScoutRegistry.byId(id)
        ScoutDialog(methodTitle(id), { details = null }) {
            StatusPill(method.status)
            method.reason?.let { Text(it) }
            if (method.status.healthy) Text(if (adapter?.evidence == Evidence.HANDSHAKE)
                tr("تأیید فقط تا مرحلهٔ دست‌دهی؛ عبور داده تأیید نشده است.", "Handshake confirmed only; data transfer is not verified.")
                else tr("دادهٔ واقعی از این روش عبور کرده است.", "Real data passed through this method."))
            if (method.variants.count { it.status.healthy } > 1) method.best?.let {
                Text(tr("بهترین خروجی آزمایش‌شده: ", "Best tested exit: ") + it.label, fontWeight = FontWeight.Bold)
            }
            method.variants.sortedWith(compareBy<VariantResult> { ScoutRules.rank(it.status) }
                .thenByDescending { it.numbers.mbps ?: -1.0 }.thenBy { it.numbers.latencyMs ?: Long.MAX_VALUE }).forEach { variant ->
                Text(listOf(variant.label, variant.fam.label).filter(String::isNotBlank).distinct().joinToString(" · "), fontWeight = FontWeight.Bold)
                Text(statusLabel(variant.status))
                variant.reason?.let { Text(it) }
                variant.failedAt?.let { Text(stepLabel(it)) }
                val n = variant.numbers
                ms(n.connectMs)?.let { Text(tr("زمان اتصال: $it", "Connection time: $it")) }
                ms(n.latencyMs)?.let { Text(tr("تأخیر HTTPS: $it", "HTTPS delay: $it")) }
                stab(n)?.let { Text(tr("پایداری: $it", "Stability: $it")) }
                mbps(n.mbps)?.let { Text(tr("نمونهٔ کوتاه سرعت: $it", "Short speed sample: $it")) }
                if (variant.status.healthy && n.mbps == null && adapter?.evidence == Evidence.DATA)
                    Text(if (n.speedAttempted) tr("سنجش سرعت نتیجه نداد؛ سرعت نامشخص است", "Speed measurement failed; speed is unknown")
                        else tr("سرعت هنوز سنجیده نشده است", "Speed has not been measured yet"))
                if (n.speedApprox) Text(tr("تقریبی — سرعت خط تغییر کرد", "Approximate — line speed changed"))
                countryLabel(n.exitCountry)?.let { Text(it) }
                Spacer(Modifier.height(12.dp))
            }
            if (adapter != null) TextButton(onClick = { details = null; onOpen(if (method.status == ScoutStatus.NOT_INSTALLED) "store" else adapter.homeId) }) {
                Text(tr("رفتن به این روش / راه‌اندازی", "Open method / setup"))
            }
            if (!ui.running && adapter?.built == true) TextButton(onClick = { details = null; checked { ScoutEngine.retest(context, id, pauseApproved = it) } }) {
                Text(tr("دوباره تست کن", "Test again"))
            }
        }
    } }
}

@Composable
private fun ScoutDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = { TextButton(onClick = dismiss) { Text(tr("بستن", "Close")) } })
}

/** A family's state in words: the enum's own name ("WORKS") was shown as it was. */
private fun famText(f: com.mlmvpn.scanner.engines.scout.FamState?): String = when (f) {
    null -> "—"
    com.mlmvpn.scanner.engines.scout.FamState.WORKS -> com.mlmvpn.scanner.store.tr("باز است", "works")
    com.mlmvpn.scanner.engines.scout.FamState.BROKEN -> com.mlmvpn.scanner.store.tr("بسته است", "blocked")
    com.mlmvpn.scanner.engines.scout.FamState.NONE -> com.mlmvpn.scanner.store.tr("این شبکه ندارد", "not on this network")
}
