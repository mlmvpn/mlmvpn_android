package com.mlmvpn.scanner.ui.tunnel

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.tunnel.IpFormatter
import com.mlmvpn.core.tunnel.PsiphonCleanIps
import com.mlmvpn.core.tunnel.PsiphonLive
import com.mlmvpn.core.tunnel.PsiphonMethods
import com.mlmvpn.core.tunnel.PsiphonPairing
import com.mlmvpn.core.tunnel.PsiphonProbes
import com.mlmvpn.core.tunnel.PsiphonUpstream
import com.mlmvpn.core.warp.Ipv4Exits
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.LocalSystemBottomPadding
import com.mlmvpn.scanner.ui.emergency.faDigits
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «سایفون»'s pages beyond the country: «روش اتصال», «پروکسی سرور», «آی‌پی تمیز» and «کاندوئیت» --
 * the desktop's window (components/fronts.js + psiphon-extra.js), page for page. Every number on
 * them is measured through Psiphon itself ([PsiphonProbes]): a ping appears only after a throwaway
 * Psiphon really connected and a request crossed it.
 */
object PsiphonPages {
    const val METHOD = "psiphon_method"
    const val PROXY = "psiphon_proxy"
    const val CLEAN = "psiphon_clean"
    const val PAIRING = "psiphon_pairing"
}

private fun fa(n: Number): String = faDigits(n.toString()).replace('.', '٫')

private fun ms(n: Long?): String = if (n == null) "—" else tr("${fa(n)} میلی‌ثانیه", "$n ms")

private fun secs(ms: Long?): String = if (ms == null) "" else String.format(java.util.Locale.US, "%.1f", ms / 1000.0).let { tr(faDigits(it).replace('.', '٫'), it) }

// ── on the transport's own screen ───────────────────────────────────────────────────────────────

/** «روش اتصال» and the three ways in, on «سایفون»'s own screen. */
@Composable
fun ColumnScope.PsiphonMethodRows(onOpenPage: (String) -> Unit) {
    val ctx = LocalContext.current
    val up by PsiphonUpstream.state.collectAsState()
    val clean by PsiphonCleanIps.state.collectAsState()
    var pairing by remember { mutableStateOf<PsiphonPairing.Pairing?>(null) }
    var usable by remember { mutableIntStateOf(0) }
    LaunchedEffect(clean.list) {
        withContext(Dispatchers.IO) {
            PsiphonUpstream.init(ctx)
            PsiphonCleanIps.init(ctx)
            pairing = PsiphonPairing.read(ctx)
            usable = PsiphonCleanIps.usableCount(ctx)
        }
    }
    val method = PsiphonMethods.pinned(ctx)

    SettingsSectionHeader(tr("روش اتصال", "Connection method"))
    SettingsGroup {
        SettingsRow(
            title = tr("روش اتصال", "Connection method"),
            icon = Icons.Default.Route,
            tint = Ios.Blue,
            value = PsiphonMethods.label(method),
            onClick = { onOpenPage(PsiphonPages.METHOD) },
        )
        Separator()
        SettingsRow(
            title = tr("پروکسی سرور", "Proxy server"),
            icon = Icons.Default.SwapHoriz,
            tint = Ios.Indigo,
            value = when (up.choice?.mode) {
                "custom" -> up.custom?.let { "${it.host}:${it.port}" } ?: tr("خاموش", "Off")
                "country" -> up.choice?.country?.let { CountryLabel.localized(it) } ?: tr("خاموش", "Off")
                else -> tr("خاموش", "Off")
            },
            onClick = { onOpenPage(PsiphonPages.PROXY) },
        )
        Separator()
        SettingsRow(
            title = tr("آی‌پی تمیز", "Clean IP"),
            icon = Icons.Default.CleaningServices,
            tint = Ios.Green,
            value = if (usable > 0) tr("${fa(usable)} آماده", "$usable ready") else tr("هنوز نیست", "None yet"),
            onClick = { onOpenPage(PsiphonPages.CLEAN) },
        )
        Separator()
        SettingsRow(
            title = tr("کاندوئیت", "Conduit"),
            icon = Icons.Default.Link,
            tint = Ios.Purple,
            value = pairing?.let { it.name.ifBlank { tr("جفت شده", "Paired") } } ?: tr("جفت نشده", "Not paired"),
            onClick = { onOpenPage(PsiphonPages.PAIRING) },
        )
    }
    SettingsFooter(
        tr(
            "روی اینترنتی که آدرس سرورها را بسته، دامین‌فرانتینگ معمولاً تنها راه است: اتصال روی لبهٔ یک CDN تمام می‌شود، پس آدرس سروری برای بستن نیست. حالت خودکار همین را اول امتحان می‌کند، و روشی که روی اینترنت شما جواب داد برای دفعهٔ بعد می‌ماند.",
            "On an internet that blocks the servers' addresses, domain fronting is usually the only way: the connection ends on a CDN's edge, so there is no server address to block. Automatic tries it first, and the method that answered on your internet is kept for next time.",
        ),
    )
}

/**
 * What the tile is doing, while it does it: which method of how many, then what carried it -- the
 * protocol, the proxy or the clean address, the server's capacity -- and how stable it has been.
 */
@Composable
fun PsiphonLiveCard() {
    val live by PsiphonLive.state.collectAsState()
    val l = live ?: return
    if (l.rung.isEmpty()) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(l.connected, l.startedAt) {
        while (!l.connected) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() }
    }
    val lines = buildList {
        if (!l.connected) {
            val el = ((now - l.startedAt) / 1000).coerceAtLeast(0)
            add(tr("روش ${fa(l.index)} از ${fa(l.count)}: ${l.label} — ${fa(el)} از ${fa(l.seconds)} ثانیه",
                "Method ${l.index} of ${l.count}: ${l.label} -- $el of ${l.seconds} s") to Ios.Label)
        } else {
            add((l.label + (if (l.protocol.isNotBlank()) " · ${l.protocol}" else "")) to Ios.Label)
        }
        if (l.measuring) add(tr("سنجش ظرفیت این سرور…", "Measuring this server's capacity…") to Ios.SecondaryLabel)
        if (l.mbit > 0) add(tr("ظرفیت این سرور: ${fa(l.mbit)} مگابیت (با ۶ اتصال هم‌زمان)", "This server: ${l.mbit} Mbit/s (six connections at once)") to
            (if (l.mbit < 0.9) Ios.Orange else Ios.Green))
        if (l.upstream.isNotBlank()) add(tr("از راه پروکسی سرور ${l.upstream}", "Through the proxy server ${l.upstream}") +
            (l.upstreamCountry.takeIf { it.isNotBlank() }?.let { " (" + CountryLabel.localized(it) + ")" } ?: "") to Ios.SecondaryLabel)
        if (l.cleanIp.isNotBlank()) add(tr("آی‌پی تمیز ${l.cleanIp} اتصال را برقرار کرد", "Clean IP ${l.cleanIp} carried it") to Ios.Green)
        if (l.serverRegion.isNotBlank() && l.connected) add(tr("سرور: ", "Server: ") + CountryLabel.localized(l.serverRegion) to Ios.SecondaryLabel)
        if (l.drops > 0) add(tr("قطع و وصل در این اتصال: ${fa(l.drops)} بار", "Drops this session: ${l.drops}") +
            (if (l.lastGapSeconds > 0) tr(" · آخرین بار بعد از ${fa(l.lastGapSeconds)} ثانیه برگشت", " · back after ${l.lastGapSeconds} s last time") else "") to Ios.Orange)
        // Destinations the server refused stay in the connection report, as on the desktop: a
        // count on this card a second after connecting read as «something is wrong» when it is
        // the server's own policy on a port or two.
        if (l.note.isNotBlank()) add(l.note to Ios.SecondaryLabel)
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lines.forEachIndexed { i, (text, color) ->
            Text(text, color = color, fontSize = if (i == 0) 15.sp else 12.sp, lineHeight = if (i == 0) 21.sp else 18.sp,
                fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

// ── «روش اتصال» ─────────────────────────────────────────────────────────────────────────────────

@Composable
fun PsiphonMethodScreen(onBack: () -> Unit, backLabel: String, onOpenPage: (String) -> Unit) {
    val ctx = LocalContext.current
    var method by remember { mutableStateOf(PsiphonMethods.pinned(ctx)) }
    var hasClean by remember { mutableStateOf(true) }
    var hasPairing by remember { mutableStateOf(true) }
    var hasProxy by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            hasClean = PsiphonCleanIps.hasUsable(ctx)
            hasPairing = PsiphonPairing.read(ctx) != null
            PsiphonUpstream.init(ctx)
            hasProxy = PsiphonUpstream.state.value.choice != null
        }
    }
    val live by PsiphonLive.state.collectAsState()
    IosScreen(title = tr("روش اتصال", "Connection method"), onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            (listOf(PsiphonMethods.AUTO) + PsiphonMethods.ALL).forEachIndexed { i, key ->
                if (i > 0) Separator()
                val warn = when (key) {
                    "E" -> if (!hasClean) tr("هنوز آی‌پی تمیزی نیست — اول در «آی‌پی تمیز» اسکن کنید یا آی‌پی‌های خودتان را وارد کنید.",
                        "No clean IP yet -- scan in «Clean IP» first, or add your own.") else null
                    "P" -> if (!hasPairing) tr("لینک جفت‌سازی وارد نشده — اول در «کاندوئیت» واردش کنید.",
                        "No pairing link yet -- add one in «Conduit» first.") else null
                    "X" -> if (!hasProxy) tr("هنوز پروکسی‌ای انتخاب نشده — اول در «پروکسی سرور» پروکسی خودتان را وارد کنید یا کشوری را انتخاب کنید.",
                        "No proxy chosen yet -- add yours or pick a country in «Proxy server» first.") else null
                    else -> null
                }
                MethodRow(
                    title = PsiphonMethods.label(key),
                    detail = PsiphonMethods.desc(key) + when (key) {
                        PsiphonMethods.AUTO -> ""
                        "X" -> tr(" (هر پروکسی تا ${fa(PsiphonMethods.seconds(key))} ثانیه)", " (up to ${PsiphonMethods.seconds(key)} s per proxy)")
                        else -> tr(" (تا ${fa(PsiphonMethods.seconds(key))} ثانیه)", " (up to ${PsiphonMethods.seconds(key)} s)")
                    },
                    warn = warn,
                    selected = method == key,
                    onClick = {
                        method = key
                        PsiphonMethods.setPinned(ctx, key)
                        if (warn != null) when (key) {
                            "E" -> onOpenPage(PsiphonPages.CLEAN)
                            "P" -> onOpenPage(PsiphonPages.PAIRING)
                            "X" -> onOpenPage(PsiphonPages.PROXY)
                        }
                    },
                )
            }
        }
        SettingsFooter(
            tr("«کاندوئیت شخصی» در حالت خودکار امتحان نمی‌شود — ایستگاه دوستتان ممکن است خاموش باشد و نردبان نباید منتظرش بماند. «آی‌پی تمیز» در حالت خودکار فقط وقتی امتحان می‌شود که آی‌پی آماده‌ای باشد. داخل وارپ، «رله از طریق کاربران دیگر» و «کاندوئیت شخصی» کار نمی‌کنند (WebRTC از پروکسی رد نمی‌شود).",
                "«Personal Conduit» is never tried automatically -- a friend's station may be off and the ladder must not wait on it. «Clean IP» is tried automatically only when there are ready addresses. Inside WARP, «relay through other users» and «personal Conduit» do not work (WebRTC does not cross a proxy).") +
                if (live?.connected == true) tr("\n\nروش تازه از اتصال بعدی اعمال می‌شود — یک بار قطع و وصل کنید.", "\n\nA new method applies from the next connect -- disconnect and connect once.") else "",
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun MethodRow(title: String, detail: String, warn: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios.Label, fontSize = 16.sp)
            Text(detail, color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 18.sp)
            if (warn != null) Text(warn, color = Ios.Orange, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Check, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
        }
    }
}

// ── «پروکسی سرور» ───────────────────────────────────────────────────────────────────────────────

/**
 * A lazy list, like the clean-IP page: a run updates its card four times a second, and a page that
 * laid out every country row again for each of those updates stuttered under the finger.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PsiphonProxyScreen(onBack: () -> Unit, backLabel: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by PsiphonUpstream.state.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PsiphonUpstream.init(ctx) } }
    val c = st.custom
    var type by rememberSaveable(c?.type) { mutableStateOf(c?.type ?: "http") }
    var host by rememberSaveable(c?.host) { mutableStateOf(c?.host.orEmpty()) }
    var port by rememberSaveable(c?.port) { mutableStateOf(c?.port?.toString().orEmpty()) }
    var auth by rememberSaveable(c?.auth) { mutableStateOf(c?.auth ?: false) }
    var user by rememberSaveable(c?.user) { mutableStateOf(c?.user.orEmpty()) }
    var pass by rememberSaveable { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var want by remember { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val countries = remember { Ipv4Exits.countryList(ctx) }
    val job = st.job
    // A country's name outside composition too (click handlers, effects), in the language the user set.
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    fun cname(cc: String) = CountryLabel.name(cc, locale)
    val now = rememberTicker(job.running || st.customTest != null)

    fun use(choice: PsiphonUpstream.Choice?) {
        try {
            PsiphonUpstream.choose(ctx, choice)
            PsiphonMethods.setPinned(ctx, if (choice == null) PsiphonMethods.AUTO else "X")
            Toast.makeText(ctx, if (choice == null) tr("پروکسی سرور خاموش شد — روش اتصال: خودکار", "Proxy server off -- method: automatic")
                else tr("روش اتصال سایفون: پروکسی سرور", "Psiphon's method: proxy server"), Toast.LENGTH_SHORT).show()
        } catch (e: PsiphonUpstream.UpstreamError) { error = e.message }
    }

    // A country picked before it was measured is chosen as soon as its measurement finds one.
    LaunchedEffect(job.running, want) {
        val w = want ?: return@LaunchedEffect
        if (job.running) return@LaunchedEffect
        want = null
        val p = st.pings[w]
        if (p != null && p.realOk > 0) {
            use(PsiphonUpstream.Choice("country", w))
            note = tr("${cname(w)} انتخاب شد — روش اتصال: پروکسی سرور.", "${cname(w)} chosen -- method: proxy server.")
        } else {
            note = tr("از ${cname(w)} پروکسی‌ای پیدا نشد که سایفون از راهش وصل شود — کشور دیگری را امتحان کنید.",
                "No proxy of ${cname(w)} carried Psiphon -- try another country.")
        }
    }

    val rows = remember(countries, st.pings) {
        (countries.take(25).map { it to st.pings[it] } + st.pings.filterKeys { it !in countries.take(25) }.map { it.key to it.value })
            // One row per country: the list is keyed by it.
            .distinctBy { it.first }
            .sortedWith(compareBy<Pair<String, PsiphonUpstream.Ping?>> { (_, p) -> if (p?.realOk ?: 0 > 0) 0 else if (p == null) 1 else 2 }
                .thenBy { (_, p) -> p?.bestPing ?: Long.MAX_VALUE })
    }
    val shown = if (showAll) rows else rows.take(12)
    val carded = job.running || st.customTest != null

    Box(Modifier.fillMaxSize()) {
        IosScreen(title = tr("پروکسی سرور", "Proxy server"), onBack = onBack, backLabel = backLabel, scrollable = false) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = if (carded) CARD_ROOM else 28.dp),
            ) {
                item(key = "top", contentType = "top") {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        error?.let { Note(it, Ios.Red) }
                        note?.let { Note(it, Ios.Blue) }
                        // ── what is on ──
                        val ch = st.choice
                        SettingsGroup {
                            SettingsRow(
                                title = when (ch?.mode) {
                                    "custom" -> tr("پروکسی خودتان ", "Your proxy ") + (c?.let { "${it.host}:${it.port}" } ?: "")
                                    "country" -> tr("فهرست آماده — ", "Ready list -- ") + cname(ch.country.orEmpty())
                                    else -> tr("خاموش", "Off")
                                },
                                subtitle = when (ch?.mode) {
                                    null -> tr("سایفون مستقیم به سرورهایش وصل می‌شود. پروکسی خودتان را پایین وارد کنید یا یک کشور از فهرست آماده انتخاب کنید.",
                                        "Psiphon reaches its servers directly. Enter your proxy below, or pick a country from the ready list.")
                                    "custom" -> c?.real?.let { r ->
                                        if (r.ok) tr("سنجش واقعی: سایفون از راه این پروکسی در ${secs(r.ms)} ثانیه وصل شد، پینگ ${ms(r.pingMs)}.",
                                            "Real test: Psiphon connected through it in ${secs(r.ms)} s, ping ${ms(r.pingMs)}.")
                                        else tr("سنجش واقعی: ", "Real test: ") + r.error
                                    } ?: tr("هنوز سنجیده نشده — «ذخیره و سنجش» را بزنید.", "Not tested yet -- tap «Save and test».")
                                    else -> st.pings[ch.country]?.takeIf { it.realOk > 0 }?.let { p ->
                                        tr("${fa(p.realOk)} پروکسی از این کشور با سایفون واقعاً وصل شدند — بهترینش (پینگ ${ms(p.bestPing)}) استفاده می‌شود و اگر جواب نداد، بعدی.",
                                            "${p.realOk} proxies of this country really connected with Psiphon -- the best (ping ${ms(p.bestPing)}) is used, then the next.")
                                    } ?: tr("هنوز پروکسی وصل‌شده‌ای از این کشور نیست — بسنجید.", "No proxy of this country has connected yet -- measure it.")
                                },
                                icon = Icons.Default.SwapHoriz, tint = if (ch != null) Ios.Indigo else Ios.Gray, showChevron = false,
                            )
                            if (ch != null) {
                                Separator()
                                SettingsActionRow(label = tr("روش اتصال سایفون: پروکسی سرور", "Make it Psiphon's method"), icon = Icons.Default.Check, tint = Ios.Blue,
                                    onClick = { use(ch) })
                                Separator()
                                SettingsActionRow(label = tr("خاموش", "Turn off"), icon = Icons.Default.PowerSettingsNew, tint = Ios.Red, onClick = { use(null) })
                            }
                        }
                    }
                }

                // ── the user's own, the official app's form ──
                item(key = "custom", contentType = "custom") {
                    Column {
                        SettingsSectionHeader(tr("پروکسی خودتان", "Your own proxy"))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("HTTP", type == "http") { type = "http" }
                            Chip("SOCKS5", type == "socks5") { type = "socks5" }
                        }
                        Spacer(Modifier.height(6.dp))
                        SettingsGroup {
                            SettingsTextRow(title = tr("میزبان (سرور)", "Host (server)"), value = host, onValueChange = { host = it.trim(); error = null }, placeholder = "1.2.3.4")
                            Separator()
                            SettingsTextRow(title = tr("پورت", "Port"), value = port, onValueChange = { port = it.filter(Char::isDigit).take(5); error = null }, placeholder = "8080", numeric = true)
                            Separator()
                            SettingsToggle(title = tr("احراز هویت", "Authentication"), checked = auth, onCheckedChange = { auth = it })
                            if (auth) {
                                Separator()
                                SettingsTextRow(title = tr("نام کاربری", "Username"), value = user, onValueChange = { user = it })
                                Separator()
                                SettingsTextRow(title = tr("رمز عبور", "Password"), value = pass, onValueChange = { pass = it },
                                    placeholder = if (c?.pass?.isNotEmpty() == true) tr("•••••• (ذخیره شده)", "•••••• (saved)") else "", secret = true)
                            }
                            Separator()
                            SettingsActionRow(
                                label = if (testing) tr("در حال سنجش — جزئیات در کارت پایین صفحه", "Testing -- details in the card below")
                                    else tr("ذخیره و سنجش", "Save and test"),
                                icon = Icons.Default.Save, tint = Ios.Blue, busy = testing, enabled = !testing && host.isNotBlank() && port.isNotBlank(),
                                onClick = {
                                    error = null; note = null
                                    try {
                                        PsiphonUpstream.setCustom(ctx, type, host, port, auth, user, pass.ifEmpty { null })
                                        testing = true
                                        testJob = scope.launch {
                                            try { PsiphonUpstream.testCustom(ctx) }
                                            catch (e: kotlinx.coroutines.CancellationException) { note = tr("سنجش متوقف شد.", "The test was stopped.") }
                                            catch (e: Exception) { error = e.message }
                                            finally { testing = false; pass = "" }
                                        }
                                    } catch (e: PsiphonUpstream.UpstreamError) { error = e.message }
                                },
                            )
                            if (c != null) {
                                Separator()
                                SettingsActionRow(label = tr("استفاده از همین", "Use this one"), icon = Icons.Default.Check, tint = Ios.Green,
                                    onClick = { use(PsiphonUpstream.Choice("custom")) })
                            }
                        }
                        c?.real?.let { r ->
                            Note(if (r.ok) tr("✓ وصل شد · پینگ ${ms(r.pingMs)}", "✓ connected · ping ${ms(r.pingMs)}") else r.error,
                                if (r.ok) Ios.Green else Ios.Red)
                        }
                    }
                }

                // ── the ready list ──
                item(key = "ready", contentType = "ready") {
                    Column {
                        SettingsSectionHeader(tr("فهرست آماده", "Ready list"))
                        SettingsGroup {
                            SettingsActionRow(
                                label = if (job.running) tr("توقف — ${fa(job.done)} از ${fa(job.countries)} کشور", "Stop -- ${job.done} of ${job.countries} countries")
                                    else tr("سنجش همه", "Measure all"),
                                icon = if (job.running) Icons.Default.Stop else Icons.Default.Speed, tint = if (job.running) Ios.Red else Ios.Green,
                                onClick = {
                                    if (job.running) { PsiphonUpstream.cancel(); want = null }
                                    else PsiphonUpstream.scanMany(ctx, countries.take(25))
                                },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                items(shown, key = { it.first }, contentType = { "country" }) { (cc, p) ->
                    val testingHere = cc in job.current
                    val step = job.steps[cc]
                    val ago = p?.at?.takeIf { it > 0 }?.let { tr(" · ${fa(((System.currentTimeMillis() - it) / 60_000).coerceAtLeast(1))} دقیقه پیش", " · ${((System.currentTimeMillis() - it) / 60_000).coerceAtLeast(1)} min ago") } ?: ""
                    Box(Modifier.animateItemPlacement().padding(horizontal = 16.dp, vertical = 3.dp)) {
                        CountryRow(
                            flag = if (testingHere) "⏳" else IpFormatter.flag(cc),
                            name = cname(cc),
                            detail = when {
                                testingHere && step?.stage == "real" -> tr("سایفون موقت از راه پراکسی‌هایی که جواب دادند (${fa(step.quickOk)})…",
                                    "A throwaway Psiphon through the proxies that answered (${step.quickOk})…")
                                testingHere && step != null -> tr("سنجش سریع از اینترنت شما: ${fa(step.quickDone)} از ${fa(step.quickTotal)} · ${fa(step.quickOk)} جواب داد",
                                    "Quick test from your internet: ${step.quickDone} of ${step.quickTotal} · ${step.quickOk} answered")
                                testingHere -> tr("در حال سنجش…", "Measuring…")
                                p == null -> tr("هنوز سنجیده نشده — بزنید تا سنجیده شود", "Not measured -- tap to measure")
                                p.realOk > 0 -> tr("${fa(p.realOk)} پروکسی با سایفون واقعاً وصل شد", "${p.realOk} really connected with Psiphon") + ago
                                p.quickOk > 0 -> tr("${fa(p.quickOk)} پروکسی از اینترنت شما جواب داد، ولی سایفون از هیچ‌کدام وصل نشد", "${p.quickOk} answered from your internet, but Psiphon connected through none") + ago
                                else -> tr("از ${fa(p.tested)} پروکسی هیچ‌کدام از اینترنت شما جواب نداد", "None of ${p.tested} answered from your internet") + ago
                            },
                            detailColor = when { p == null || testingHere -> Ios.SecondaryLabel; p.realOk > 0 -> Ios.Green; else -> Ios.Red },
                            trailing = if (p != null && p.realOk > 0) ms(p.bestPing) else "",
                            selected = st.choice?.mode == "country" && st.choice?.country == cc,
                            onClick = {
                                error = null; note = null
                                if (p != null && p.realOk > 0) use(PsiphonUpstream.Choice("country", cc))
                                else if (job.running) error = tr("یک سنجش در جریان است؛ صبر کنید یا «توقف» را بزنید.", "A measurement is running; wait or tap «Stop».")
                                else {
                                    want = cc
                                    PsiphonUpstream.scanMany(ctx, listOf(cc))
                                    note = tr("پراکسی‌های ${cname(cc)} از اینترنت شما سنجیده می‌شوند و هر کدام جواب داد، با یک سایفون موقت واقعاً امتحان می‌شود؛ اگر وصل شد همین کشور انتخاب می‌شود.",
                                        "The proxies of ${cname(cc)} are measured from your internet, and each that answers is really tried with a throwaway Psiphon; if one connects, this country is chosen.")
                                }
                            },
                        )
                    }
                }
                if (!showAll && rows.size > shown.size) {
                    item(key = "more", contentType = "more") {
                        Text(tr("نمایش همهٔ ${fa(rows.size)} کشور", "Show all ${rows.size} countries"), color = Ios.Blue, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable { showAll = true }.padding(horizontal = 20.dp, vertical = 12.dp))
                    }
                }
                st.choice?.takeIf { it.mode == "country" }?.country?.let { cc ->
                    val pool = st.pools[cc].orEmpty()
                    if (pool.isNotEmpty()) {
                        item(key = "pool", contentType = "pool") {
                            Column {
                                SettingsSectionHeader(tr("پروکسی‌های ", "Proxies of ") + cname(cc))
                                SettingsGroup {
                                    pool.forEachIndexed { i, e ->
                                        if (i > 0) Separator()
                                        SettingsRow(
                                            title = "${e.ip}:${e.port}",
                                            subtitle = (if (e.kind == "socks5") "SOCKS5" else "HTTP") + " · " + (e.real?.let { r ->
                                                if (r.ok) tr("سایفون در ${secs(r.ms)} ثانیه وصل شد", "Psiphon connected in ${secs(r.ms)} s") else r.error
                                            } ?: tr("سنجیده نشده", "not tested")),
                                            value = e.real?.takeIf { it.ok }?.pingMs?.let { ms(it) },
                                            showChevron = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "footer", contentType = "footer") {
                    SettingsFooter(
                        tr("پروکسی سرور چیست؟ سایفون به‌جای وصل شدن مستقیم، از راه این پروکسی به سرورهایش می‌رسد (شما ← پروکسی ← سایفون ← اینترنت) — همان گزینهٔ «پروکسی» اپ رسمی. به کار می‌آید وقتی راه‌های خود سایفون روی اینترنت شما بسته یا کند است. کشور خروج را عوض نمی‌کند؛ آن را سرور سایفون تعیین می‌کند. سنجش از اینترنت خود شما انجام می‌شود و نیازی به روشن بودن سایفون نیست؛ پینگ فقط وقتی نشان داده می‌شود که یک سایفون موقت از راه همان پروکسی واقعاً وصل شده باشد، و عدد، زمان یک درخواست کامل از داخل همان اتصال است. پراکسی‌های رایگان زود از کار می‌افتند: پیش از اتصال دوباره سنجیده می‌شوند، اگر اولی جواب ندهد بعدی امتحان می‌شود، و اگر هیچ‌کدام نشد، سایفون با راه‌های خودش وصل می‌شود.",
                            "What is a proxy server? Instead of connecting directly, Psiphon reaches its servers through this proxy (you → proxy → Psiphon → internet) -- the official app's «Proxy» option. Useful when Psiphon's own ways are blocked or slow on your internet. It does not change the exit country; Psiphon's server decides that. Measured from your own internet, with Psiphon off; a ping appears only once a throwaway Psiphon really connected through that proxy, and the number is a whole request's time through it. Free proxies die quickly: they are tested again before a connect, the next one is tried if the first does not answer, and if none does, Psiphon connects its own ways."),
                    )
                }
            }
        }
        val cardMod = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalSystemBottomPadding.current + 8.dp)
        val ct = st.customTest
        when {
            job.running -> ProxyProgressCard(job, now, ::cname, onStop = { PsiphonUpstream.cancel(); want = null }, modifier = cardMod)
            ct != null -> CustomTestCard(ct, now, onStop = { PsiphonUpstream.stopCustom(); testJob?.cancel() }, modifier = cardMod)
        }
    }
}

// ── «آی‌پی تمیز» ────────────────────────────────────────────────────────────────────────────────

/**
 * A lazy list keyed by address: the list re-sorts on every result (fastest first), and the page used
 * to lay out all of it again each time -- 1.3 to 4.6 s frames on an A50 during «سنجش واقعی همه»
 * (2026-10-18), the scroll stuck under the finger.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PsiphonCleanIpScreen(onBack: () -> Unit, backLabel: String) {
    val ctx = LocalContext.current
    val st by PsiphonCleanIps.state.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PsiphonCleanIps.init(ctx) } }
    var providers by rememberSaveable { mutableStateOf(listOf("akamai")) }
    var families by rememberSaveable { mutableStateOf(listOf("v6", "v4")) }
    var sources by rememberSaveable { mutableStateOf(listOf("names")) }
    var maxIps by rememberSaveable { mutableIntStateOf(1500) }
    var realTop by rememberSaveable { mutableIntStateOf(6) }
    var fast by remember { mutableStateOf(PsiphonCleanIps.fastMode(ctx)) }
    var text by rememberSaveable { mutableStateOf("") }
    var addProvider by rememberSaveable { mutableStateOf("akamai") }
    var note by remember { mutableStateOf<String?>(null) }
    var acting by remember { mutableStateOf<PsiphonCleanIps.Entry?>(null) }
    var guide by rememberSaveable { mutableStateOf(false) }
    val job = st.job
    var usable by remember { mutableIntStateOf(0) }
    LaunchedEffect(st.list) { usable = withContext(Dispatchers.IO) { PsiphonCleanIps.usableCount(ctx) } }
    val pinned = remember(st.list) { st.list.count { it.pinned } }
    // The list is fastest first: its first working row is the one to pick.
    val fastest = remember(st.list) { st.list.firstOrNull { it.real?.ok == true } }
    val now = rememberTicker(job.running)

    fun toggle(list: List<String>, v: String) = if (v in list) list - v else list + v

    fun pick(ip: String) {
        PsiphonCleanIps.setPinned(ctx, ip, true)
        PsiphonMethods.setPinned(ctx, "E")
        Toast.makeText(ctx, tr("انتخاب شد — روش اتصال سایفون: آی‌پی تمیز", "Chosen -- Psiphon's method: clean IP"), Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize()) {
        IosScreen(title = tr("آی‌پی تمیز", "Clean IP"), onBack = onBack, backLabel = backLabel, scrollable = false) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = if (job.running) CARD_ROOM else 28.dp),
            ) {
                item(key = "top", contentType = "top") {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        note?.let { Note(it, Ios.Blue) }
                        SettingsGroup {
                            SettingsRow(
                                title = if (pinned > 0) tr("${fa(pinned)} آی‌پی انتخاب‌شده", "$pinned chosen IPs") else tr("خودکار: ${fa(usable)} آی‌پی برتر", "Automatic: the best $usable IPs"),
                                subtitle = tr("${fa(st.list.size)} در فهرست. همان دامین‌فرانتینگ سایفون، ولی به‌جای آدرسی که DNS می‌دهد، به آی‌پی‌های تمیزی وصل می‌شود که روی اینترنت خود شما پیدا و آزموده شده‌اند — معمولاً اتصال سریع‌تر و پایدارتر.",
                                    "${st.list.size} on the list. Psiphon's same domain fronting, but to clean IPs found and tested on your own internet instead of what DNS gives -- usually a faster, steadier connect."),
                                icon = Icons.Default.CleaningServices, tint = Ios.Green, showChevron = false,
                            )
                            Separator()
                            SettingsActionRow(
                                label = if (job.running) tr("توقف", "Stop") else tr("اسکن آی‌پی تمیز", "Scan for clean IPs"),
                                icon = if (job.running) Icons.Default.Stop else Icons.Default.Search, tint = if (job.running) Ios.Red else Ios.Blue,
                                onClick = {
                                    note = null
                                    if (job.running) PsiphonCleanIps.cancel()
                                    else if (providers.isEmpty() || families.isEmpty() || sources.isEmpty()) note = tr("از هر گروه تنظیمات اسکن دست‌کم یک گزینه را انتخاب کنید.", "Pick at least one option of each scan setting.")
                                    else PsiphonCleanIps.scan(ctx, PsiphonCleanIps.ScanOptions(providers, families, sources, maxIps, realTop))
                                },
                            )
                            Separator()
                            SettingsActionRow(label = tr("روش اتصال سایفون: آی‌پی تمیز", "Make it Psiphon's method"), icon = Icons.Default.Check, tint = Ios.Green, enabled = usable > 0,
                                onClick = {
                                    PsiphonMethods.setPinned(ctx, "E")
                                    Toast.makeText(ctx, tr("روش اتصال سایفون: آی‌پی تمیز", "Psiphon's method: clean IP"), Toast.LENGTH_SHORT).show()
                                })
                            if (pinned > 0) {
                                Separator()
                                SettingsActionRow(label = tr("برگشت به خودکار", "Back to automatic"), icon = Icons.Default.AutoMode, tint = Ios.Blue,
                                    onClick = { PsiphonCleanIps.clearPinned(ctx) })
                            }
                        }
                        if (!job.running) RunSummary(job)
                    }
                }

                // ── scan settings ──
                item(key = "settings", contentType = "settings") {
                    Column {
                        SettingsSectionHeader(tr("تنظیمات اسکن", "Scan settings"))
                        OptionRow(tr("منبع (CDN)", "Source (CDN)")) {
                            Chip(tr("اکامای", "Akamai"), "akamai" in providers) { providers = toggle(providers, "akamai") }
                            Chip(tr("فستلی", "Fastly"), "fastly" in providers) { providers = toggle(providers, "fastly") }
                        }
                        OptionRow(tr("نوع آی‌پی", "IP family")) {
                            Chip("IPv6", "v6" in families) { families = toggle(families, "v6") }
                            Chip("IPv4", "v4" in families) { families = toggle(families, "v4") }
                        }
                        OptionRow(tr("از کجا پیدا شود", "Where from")) {
                            Chip(tr("نام‌های لبه", "Edge names"), "names" in sources) { sources = toggle(sources, "names") }
                            Chip(tr("رنج‌های IPv4", "IPv4 ranges"), "ranges" in sources) { sources = toggle(sources, "ranges") }
                        }
                        OptionRow(tr("تعداد", "How many")) {
                            for (n in listOf(500, 1500, 3000, 5000)) Chip(fa(n), maxIps == n) { maxIps = n }
                        }
                        OptionRow(tr("سنجش واقعی بعد از اسکن", "Real test after the scan")) {
                            for (n in listOf(0, 4, 6, 10)) Chip(if (n == 0) tr("هیچ", "None") else tr("${fa(n)} تای اول", "first $n"), realTop == n) { realTop = n }
                        }
                        OptionRow(tr("روش سنجش واقعی", "Real-test method")) {
                            Chip(tr("سریع (پیش‌سنجش داده)", "Fast (data pre-check)"), fast) { fast = true; PsiphonCleanIps.setFastMode(ctx, true) }
                            Chip(tr("کامل", "Full"), !fast) { fast = false; PsiphonCleanIps.setFastMode(ctx, false) }
                        }
                        SettingsFooter(tr("پیش‌فرض‌ها برای بیشتر اینترنت‌ها مناسب‌اند. درگاه همیشه ۴۴۳ است — فرانتینگ سایفون فقط روی همین درگاه است. سنجش واقعی برای هر آی‌پی یک سایفون موقت (با پوشه و درگاه جدا) بالا می‌آورد و به اتصال فعلی شما دست نمی‌زند.\n\n«سریع»: پیش از سایفون موقت، یک درخواست کوچک از همان لبه به سرور خود سایفون فرستاده می‌شود. آی‌پی‌ای که در ${fa(PsiphonCleanIps.EDGE_MS / 1000)} ثانیه هیچ جوابی برنگرداند سایفون موقت نمی‌گیرد، چون تونل از آن هم داده‌ای رد نمی‌کند. بقیه، آن‌هایی که زودتر جواب دادند اول، با سایفون موقت آزموده می‌شوند. «کامل»: همهٔ آی‌پی‌ها با سایفون موقت، کندتر.",
                            "The defaults suit most internets. The port is always 443 -- Psiphon's fronting is only there. The real test brings up a throwaway Psiphon (own folder and port) per IP and never touches your connection.\n\n«Fast»: before the throwaway Psiphon, one small request goes through that edge to Psiphon's own server. An IP that sends nothing back in ${PsiphonCleanIps.EDGE_MS / 1000} s gets no throwaway Psiphon, since a tunnel carries nothing through it either. The rest, the quickest answers first, are tested with a throwaway Psiphon. «Full»: every IP with a throwaway Psiphon, slower."))
                    }
                }

                // ── the user's own ──
                item(key = "mine", contentType = "mine") {
                    Column {
                        SettingsSectionHeader(tr("آی‌پی‌های خودتان", "Your own IPs"))
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(RoundedCornerShape(12.dp)).heightIn(min = 96.dp).padding(12.dp),
                        ) {
                            BasicTextField(
                                value = text, onValueChange = { text = it },
                                textStyle = TextStyle(color = Ios.Label, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                                cursorBrush = SolidColor(Ios.Blue),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    Box {
                                        if (text.isEmpty()) Text("2a02:26f0:3500:4::b818:4db5\n2.16.10.69\n2.16.10.0/24", color = Ios.SecondaryLabel, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                        inner()
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Chip(tr("اکامای", "Akamai"), addProvider == "akamai") { addProvider = "akamai" }
                            Chip(tr("فستلی", "Fastly"), addProvider == "fastly") { addProvider = "fastly" }
                            Spacer(Modifier.weight(1f))
                            Chip("+ " + tr("افزودن", "Add"), false) {
                                val r = PsiphonCleanIps.addUser(ctx, text, addProvider)
                                if (r.added > 0) text = ""
                                note = tr("${fa(r.added)} آی‌پی افزوده شد", "${r.added} added") +
                                    (if (r.bad.isNotEmpty()) tr(" — ${fa(r.bad.size)} خط نامعتبر بود: ", " -- ${r.bad.size} lines were not IPs: ") + r.bad.take(3).joinToString(" ") else "") +
                                    tr(". برای اطمینان «سنجش» را بزنید.", ". Run «test» to be sure.")
                            }
                        }
                        SettingsFooter(tr("هر آی‌پی (IPv4 یا IPv6) یا رنج کوچک IPv4 مثل 2.16.10.0/24، در یک خط.", "Any IP (IPv4 or IPv6) or a small IPv4 range like 2.16.10.0/24, one per line."))
                    }
                }

                // ── the list ──
                item(key = "listhead", contentType = "listhead") {
                    Column {
                        SettingsSectionHeader(tr("آی‌پی‌های تمیز — سریع‌ترین اول", "Clean IPs -- fastest first"))
                        SettingsGroup {
                            SettingsActionRow(
                                label = if (job.running && job.stage == "real") tr("توقف سنجش", "Stop testing") else tr("سنجش واقعی همه", "Real-test all"),
                                icon = if (job.running && job.stage == "real") Icons.Default.Stop else Icons.Default.Speed,
                                tint = if (job.running && job.stage == "real") Ios.Red else Ios.Green,
                                enabled = st.list.isNotEmpty() && (!job.running || job.stage == "real"),
                                onClick = {
                                    if (job.running) PsiphonCleanIps.cancel()
                                    else {
                                        note = null
                                        PsiphonCleanIps.realTest(ctx, PsiphonCleanIps.allIps())
                                    }
                                },
                            )
                            Separator()
                            SettingsActionRow(label = tr("حذف خراب‌ها", "Remove the dead"), icon = Icons.Default.DeleteSweep, tint = Ios.Red, enabled = !job.running,
                                onClick = {
                                    val n = PsiphonCleanIps.removeDead(ctx)
                                    Toast.makeText(ctx, tr("${fa(n)} آی‌پی خراب حذف شد", "$n dead IPs removed"), Toast.LENGTH_SHORT).show()
                                })
                        }
                        Spacer(Modifier.height(8.dp))
                        if (st.list.isEmpty()) {
                            Note(tr("هنوز آی‌پی‌ای نیست — «اسکن آی‌پی تمیز» را بزنید یا آی‌پی‌های خودتان را وارد کنید.", "No IP yet -- scan, or add your own."), Ios.SecondaryLabel)
                        }
                    }
                }
                items(st.list, key = { it.ip }, contentType = { "ip" }) { e ->
                    Box(Modifier.animateItemPlacement().padding(horizontal = 16.dp, vertical = 3.dp)) {
                        IpRow(e, job.current[e.ip], now, job.budgetMs) { acting = e }
                    }
                }

                // ── the guide ──
                item(key = "guide", contentType = "guide") {
                    Column {
                        Text(if (guide) tr("بستن راهنمای اسکن", "Hide the scan guide") else tr("راهنمای اسکن", "Scan guide"), color = Ios.Blue, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable { guide = !guide }.padding(horizontal = 20.dp, vertical = 14.dp))
                        if (guide) {
                            SettingsFooter(tr(
                                "منبع: اکامای را انتخاب کنید. سرورهای فرانتینگ سایفون پشت اکامای‌اند (و یکی پشت فستلی)؛ آی‌پی کلادفلر یا CDN دیگر اینجا به کار نمی‌آید چون سایفون پشت آن‌ها سروری ندارد.\n\n" +
                                    "نوع آی‌پی: هر دو را بگذارید. روی بعضی اینترنت‌ها آی‌پی‌های IPv4 اکامای به دست‌دادن TLS جواب می‌دهند ولی تونل سایفون از آن‌ها داده‌ای رد نمی‌کند، در حالی که IPv6 به‌خوبی وصل می‌شود — «سنجش واقعی» همین را برای اینترنت شما روشن می‌کند. اگر اینترنتتان IPv6 ندارد فقط IPv4 را بزنید.\n\n" +
                                    "از کجا: «نام‌های لبه» بهترین است: همان نام‌هایی که سایفون موقع اتصال می‌سازد روی همین گوشی حل می‌شوند و آی‌پی‌های پیداشده با همسایه‌هایشان (همان /۲۴ یا ۲۵۶ آدرس آخر IPv6) آزموده می‌شوند. «رنج‌ها» آدرس تصادفی از رنج‌های اکامای است — بیشترشان سرور نیستند، پس کندتر است.\n\n" +
                                    "سنجش واقعی: برای هر آی‌پی یک هستهٔ موقت سایفون با پوشه و درگاه جدا فقط با همان آی‌پی و فقط با سرورهای همان CDN بالا می‌آید؛ «وصل شد» یعنی تونل واقعاً از همان آی‌پی ساخته شد و یک درخواست HTTPS از آن رد شد. عدد، زمان همان درخواست است. در حین سنجش فهرست مرتب می‌شود و سریع‌ترین بالای فهرست است؛ هر وقت خواستید «توقف» را بزنید و ردیف اول را انتخاب کنید.\n\n" +
                                    "انتخاب: لازم نیست — اگر هیچ آی‌پی‌ای را انتخاب نکنید، سایفون خودش از ۸ آی‌پی برتر فهرست (اول آن‌هایی که در سنجش واقعی وصل شدند) استفاده می‌کند. اگر روی یک یا چند ردیف «انتخاب» را بزنید، فقط از همان‌ها وصل می‌شود.\n\n" +
                                    "استفاده: «آی‌پی تمیز» را در «روش اتصال» انتخاب کنید. در حالت خودکار هم، وقتی آی‌پی آماده باشد، بعد از دامین‌فرانتینگ امتحان می‌شود. آی‌پی‌ای که اتصال شما را برقرار کند ✓ می‌گیرد.",
                                "Source: choose Akamai. Psiphon's fronting servers sit behind Akamai (one behind Fastly); a Cloudflare or other CDN address is no use here.\n\n" +
                                    "IP family: keep both. On some internets Akamai's IPv4 edges answer TLS but carry no Psiphon data while IPv6 connects well -- the real test settles it for yours. Without IPv6, keep IPv4 only.\n\n" +
                                    "Where from: «edge names» is best -- the names Psiphon itself builds, resolved on this phone, each address tested with its neighbours. «Ranges» are random Akamai addresses -- most are not servers, so it is slower.\n\n" +
                                    "Real test: a throwaway Psiphon, own folder and port, with only that IP and only that CDN's servers; «connected» means a tunnel really came up through that IP and an HTTPS request crossed it. The number is that request's time. The list re-sorts as it goes, fastest at the top: stop whenever you like and pick the first row.\n\n" +
                                    "Choosing: not needed -- with none chosen, Psiphon uses the list's best 8 (real-test winners first). Choose one or more and it connects only through those.\n\n" +
                                    "Using it: pick «Clean IP» as the method. In automatic mode too, it is tried after domain fronting whenever IPs are ready. An IP that carries your connection gets a ✓.",
                            ))
                        }
                    }
                }
            }
        }
        if (job.running) {
            CleanProgressCard(job, fastest, now, onStop = { PsiphonCleanIps.cancel() }, onPick = ::pick,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalSystemBottomPadding.current + 8.dp))
        }
    }

    acting?.let { e ->
        AlertDialog(
            onDismissRequest = { acting = null },
            title = { Text(e.ip, fontFamily = FontFamily.Monospace, fontSize = 15.sp) },
            text = {
                Column {
                    ActionLine(tr("سنجش واقعی کامل با سایفون", "Full real test with Psiphon"), enabled = !job.running) {
                        acting = null; PsiphonCleanIps.realTest(ctx, listOf(e.ip))
                    }
                    ActionLine(if (e.pinned) tr("لغو انتخاب", "Unchoose") else tr("انتخاب — فقط از آی‌پی‌های انتخاب‌شده وصل شود", "Choose -- connect only through chosen IPs")) {
                        acting = null
                        PsiphonCleanIps.setPinned(ctx, e.ip, !e.pinned)
                        if (!e.pinned) {
                            PsiphonMethods.setPinned(ctx, "E")
                            Toast.makeText(ctx, tr("روش اتصال سایفون: آی‌پی تمیز — فقط آی‌پی‌های انتخاب‌شده", "Psiphon's method: clean IP -- the chosen ones only"), Toast.LENGTH_SHORT).show()
                        }
                    }
                    ActionLine(tr("حذف", "Remove"), color = Ios.Red) { acting = null; PsiphonCleanIps.remove(ctx, listOf(e.ip)) }
                }
            },
            confirmButton = { TextButton(onClick = { acting = null }) { Text(tr("بستن", "Close")) } },
        )
    }
}

/** One row of the clean list: a live timer while its real test runs. */
@Composable
private fun IpRow(e: PsiphonCleanIps.Entry, testing: PsiphonCleanIps.Testing?, now: State<Long>, budgetMs: Int, onClick: () -> Unit) {
    val r = e.real
    val state = when {
        testing != null -> {
            // Read only here: the rows under a test change once a second, the rest not at all.
            val s = ((now.value - testing.since) / 1000).coerceAtLeast(0)
            if (testing.connected) tr("وصل شد — سنجش پینگ… (${fa(s)} ثانیه)", "Connected -- measuring the ping… ($s s)")
            else tr("در حال سنجش — ${fa(s)} از ${fa(budgetMs / 1000)} ثانیه", "Testing -- $s of ${budgetMs / 1000} s")
        }
        r == null -> tr("سنجش واقعی نشده", "not real-tested")
        r.ok -> tr("سایفون در ${secs(r.ms)} ثانیه وصل شد", "Psiphon connected in ${secs(r.ms)} s")
        else -> r.error.ifBlank { tr("با سایفون وصل نشد", "Psiphon did not connect") }
    }
    val sub = listOfNotNull(
        if (e.v6) "IPv6" else "IPv4",
        PsiphonCleanIps.PROVIDERS[e.provider]?.label,
        if (e.source == "user") tr("از خودتان", "yours") else tr("اسکن", "scan"),
        e.tlsMs?.let { "TLS " + ms(it) },
        state,
        if (e.confirmedAt > 0) tr("✓ اتصال شما از همین آی‌پی برقرار شد", "✓ your connection came up through it") else null,
        if (e.pinned) tr("انتخاب شد", "chosen") else null,
    ).joinToString(" · ")
    CountryRow(
        flag = if (testing != null) "⏳" else if (e.v6) "6" else "4",
        name = e.ip,
        detail = sub,
        detailColor = when {
            testing != null -> Ios.SecondaryLabel
            e.confirmedAt > 0 || r?.ok == true -> Ios.Green
            r != null -> Ios.Red
            else -> Ios.SecondaryLabel
        },
        trailing = if (r?.ok == true && testing == null) ms(r.pingMs) else "",
        selected = e.pinned,
        mono = true,
        onClick = onClick,
    )
}

/** What the last run came to, once it is over: the counts, its time, and advice when nothing came back. */
@Composable
private fun RunSummary(job: PsiphonCleanIps.Job) {
    if (job.stage != "done" && job.stage != "cancelled") return
    val took = if (job.endedAt > job.startedAt && job.startedAt > 0) duration((job.endedAt - job.startedAt) / 1000) else ""
    val stopped = job.stage == "cancelled"
    if (job.ips > 0) {
        val parts = buildList {
            add(tr("${fa(job.realOk)} وصل شد", "${job.realOk} connected"))
            if (job.real - job.realOk > 0) add(tr("${fa(job.real - job.realOk)} وصل نشد", "${job.real - job.realOk} did not connect"))
            if (job.edgeSilent > 0) add(tr("${fa(job.edgeSilent)} در پیش‌سنجش جوابی نداد", "${job.edgeSilent} gave nothing back in the pre-check"))
            if (job.broken > 0) add(tr("${fa(job.broken)} سنجش انجام نشد", "${job.broken} could not be tested"))
            if (stopped && job.ips - job.settled > 0) add(tr("${fa(job.ips - job.settled)} سنجیده نشد", "${job.ips - job.settled} not tested"))
        }
        val head = if (stopped) tr("سنجش متوقف شد؛ نتیجه‌های تا اینجا ماند: ", "Stopped; the results so far are kept: ")
            else tr("سنجش واقعی ${fa(job.ips)} آی‌پی تمام شد: ", "Real test of ${job.ips} IPs done: ")
        Note(head + parts.joinToString("، ") + (if (took.isNotEmpty()) tr(" — در $took", " -- in $took") else "") +
            tr(". سریع‌ترین بالای فهرست است.", ". The fastest is at the top of the list."), if (job.realOk > 0) Ios.Green else Ios.SecondaryLabel)
        if (job.fast && !stopped && job.edgeSilent > 0 && job.edgeOk == 0 && job.realOk == 0) {
            Note(tr("در پیش‌سنجش از هیچ آی‌پی‌ای داده برنگشت. اگر فکر می‌کنید سایفون روی اینترنت شما با آی‌پی تمیز وصل می‌شود، «روش سنجش واقعی: کامل» را بزنید و دوباره بسنجید — کندتر است ولی هر آی‌پی را با سایفون موقت می‌آزماید.",
                "No IP sent anything back in the pre-check. If you think Psiphon connects through clean IPs on your internet, pick «Real-test method: Full» and test again -- slower, but every IP gets a throwaway Psiphon."), Ios.Orange)
        }
    } else if (job.total > 0) {
        Note(tr("${fa(job.ok)} آی‌پی از ${fa(job.total)} جواب TLS داد.", "${job.ok} of ${job.total} answered TLS."), Ios.Green)
    }
    if (job.note.isNotBlank()) Note(job.note, Ios.Orange)
    if (job.error.isNotBlank()) Note(job.error, Ios.Red)
}

@Composable
private fun ActionLine(text: String, enabled: Boolean = true, color: Color = Ios.Blue, onClick: () -> Unit) {
    Text(text, color = if (enabled) color else Ios.SecondaryLabel, fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 12.dp))
}

// ── the progress card ───────────────────────────────────────────────────────────────────────────

/** Room left under a list for the floating card, so its last rows can be scrolled clear of it. */
private val CARD_ROOM = 260.dp

/** A clock that ticks once a second while [active]; read it only where a number changes with it. */
@Composable
private fun rememberTicker(active: Boolean): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return now
}

/** "m:ss", in the user's digits. */
private fun clock(seconds: Long): String = (String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60)).let { tr(faDigits(it), it) }

/** A length of time in words: "۳ دقیقه و ۱۲ ثانیه". */
private fun duration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return when {
        m == 0L -> tr("${fa(s)} ثانیه", "$s s")
        s == 0L -> tr("${fa(m)} دقیقه", "$m min")
        else -> tr("${fa(m)} دقیقه و ${fa(s)} ثانیه", "$m min $s s")
    }
}

/** The estimate, in words; null until there is enough to go on. */
private fun aboutLeft(ms: Long?): String? = when {
    ms == null -> null
    ms < 60_000 -> tr("کمتر از یک دقیقه مانده", "under a minute left")
    else -> ((ms + 59_999) / 60_000).let { tr("حدود ${fa(it)} دقیقه مانده", "about $it min left") }
}

/** The end of an IPv6 (its last two groups) or a whole IPv4: enough to tell rows apart on a card. */
private fun shortIp(ip: String): String = if (ip.contains(':')) "…" + ip.split(':').takeLast(2).joinToString(":") else ip

/**
 * The real-test run's estimate: the tests still to come (queued, in flight at half, and -- while the
 * pre-check runs -- the share of the rest it is likely to pass on), at the average test's time, over
 * the tests that run at once.
 */
private fun cleanEta(j: PsiphonCleanIps.Job): Long? {
    if (j.real < 2) return null
    val avg = j.realMs / j.real
    val waiting = (j.realTotal - j.real - j.broken - j.current.size).coerceAtLeast(0)
    val fromEdge = if (j.fast && j.edgeDone in 1 until j.ips) (j.ips - j.edgeDone) * (j.edgeDone - j.edgeSilent).toDouble() / j.edgeDone else 0.0
    val left = waiting + fromEdge + j.current.size * 0.5
    return (left * avg / j.slots.coerceAtLeast(1)).toLong()
}

/**
 * The card that floats over the bottom of the page while a run is on: what it is doing, how far it
 * is, and «توقف» -- one tap, and the page is free at once. A tap on its title folds the details away.
 */
@Composable
private fun ProgressShell(
    title: String,
    clockText: String,
    progress: Float?,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    details: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(true) }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).frostedGlass(shape)
            // Over the list rather than the wallpaper alone: dense enough that rows passing under it never show through.
            .background(Color.Black.copy(alpha = 0.32f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(clockText, color = Ios.SecondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Text(tr("توقف", "Stop"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Ios.Red).clickable(onClick = onStop)
                    .padding(horizontal = 16.dp, vertical = 7.dp))
        }
        Spacer(Modifier.height(10.dp))
        val bar = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
        if (progress == null) {
            LinearProgressIndicator(color = Ios.Green, trackColor = Color.White.copy(alpha = 0.15f), modifier = bar)
        } else {
            val shown by animateFloatAsState(progress.coerceIn(0f, 1f), label = "progress")
            LinearProgressIndicator(progress = shown, color = Ios.Green, trackColor = Color.White.copy(alpha = 0.15f), modifier = bar)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { details() }
        }
    }
}

@Composable
private fun CardLine(text: String, color: Color = Ios.SecondaryLabel) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** An address or a proxy on the card: its own left-to-right run, so the sentence around it cannot reorder it. */
@Composable
private fun Mono(text: String, color: Color = Ios.Label) {
    Text(text, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
        style = TextStyle(textDirection = TextDirection.Ltr))
}

@Composable
private fun CleanProgressCard(
    job: PsiphonCleanIps.Job,
    fastest: PsiphonCleanIps.Entry?,
    now: State<Long>,
    onStop: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier,
) {
    val t = now.value
    val elapsed = ((t - job.startedAt) / 1000).coerceAtLeast(0)
    val (title, progress) = when (job.stage) {
        "harvest" -> tr("پیدا کردن لبه‌ها از نام‌هایی که خود سایفون می‌گیرد…", "Finding edges from the names Psiphon itself dials…") to null
        "tls" -> tr("دست‌دادن TLS: ${fa(job.done)} از ${fa(job.total)} آی‌پی", "TLS handshake: ${job.done} of ${job.total} IPs") to
            (if (job.total > 0) job.done.toFloat() / job.total else 0f)
        else -> tr("سنجش واقعی: ${fa(job.settled)} از ${fa(job.ips)} آی‌پی", "Real test: ${job.settled} of ${job.ips} IPs") to
            (if (job.ips > 0) job.settled.toFloat() / job.ips else 0f)
    }
    ProgressShell(title, clock(elapsed), progress, onStop, modifier) {
        when (job.stage) {
            "harvest" -> CardLine(tr("نام‌های فرانتینگ سایفون روی همین گوشی حل می‌شوند تا لبه‌های نزدیک شما پیدا شوند.",
                "Psiphon's fronting names are resolved on this phone to find the edges near you."))
            "tls" -> CardLine(tr("${fa(job.ok)} آی‌پی با گواهی درست جواب داد · ${fa((job.total - job.done).coerceAtLeast(0))} مانده",
                "${job.ok} answered with the right certificate · ${(job.total - job.done).coerceAtLeast(0)} left"), Ios.Label)
            else -> {
                val failed = job.real - job.realOk
                val left = (job.ips - job.settled).coerceAtLeast(0)
                CardLine(buildList {
                    add(tr("✓ ${fa(job.realOk)} وصل شد", "✓ ${job.realOk} connected"))
                    if (failed > 0) add(tr("✗ ${fa(failed)} وصل نشد", "✗ ${failed} did not"))
                    if (job.edgeSilent > 0) add(tr("${fa(job.edgeSilent)} بی‌جواب در پیش‌سنجش", "${job.edgeSilent} silent in the pre-check"))
                    if (job.broken > 0) add(tr("${fa(job.broken)} انجام نشد", "${job.broken} could not run"))
                    add(tr("${fa(left)} مانده", "$left left"))
                }.joinToString(" · "), Ios.Label)
                if (job.fast && job.edgeDone < job.ips) {
                    CardLine(tr("پیش‌سنجش داده از لبه: ${fa(job.edgeDone)} از ${fa(job.ips)} · ${fa(job.edgeOk)} جواب سرور سایفون را برگرداند",
                        "Data pre-check: ${job.edgeDone} of ${job.ips} · ${job.edgeOk} got Psiphon's server to answer"))
                }
                CardLine(listOfNotNull(
                    tr("${fa(job.slots)} سنجش هم‌زمان", "${job.slots} at once"),
                    aboutLeft(cleanEta(job)) ?: if (job.real < 2) tr("برآورد زمان بعد از چند نتیجه", "an estimate after a few results") else null,
                ).joinToString(" · "))
                job.current.entries.sortedBy { it.value.since }.take(3).forEach { (ip, test) ->
                    val s = ((t - test.since) / 1000).coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (test.connected) "✓" else "⏳", fontSize = 12.sp, color = if (test.connected) Ios.Green else Ios.SecondaryLabel)
                        Spacer(Modifier.width(6.dp))
                        Mono(shortIp(ip))
                        Spacer(Modifier.weight(1f))
                        Text(if (test.connected) tr("وصل شد، سنجش پینگ…", "connected, measuring the ping…")
                            else tr("${fa(s)} از ${fa(job.budgetMs / 1000)} ثانیه", "$s of ${job.budgetMs / 1000} s"),
                            color = Ios.SecondaryLabel, fontSize = 12.sp)
                    }
                }
                if (job.current.size > 3) CardLine(tr("و ${fa(job.current.size - 3)} سنجش دیگر", "and ${job.current.size - 3} more"))
                if (job.recent.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("آخرین‌ها:", "Latest:"), color = Ios.SecondaryLabel, fontSize = 12.sp)
                        job.recent.forEach { rc ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (rc.ok) "✓" else "✗", fontSize = 12.sp, color = if (rc.ok) Ios.Green else Ios.Red)
                                Spacer(Modifier.width(3.dp))
                                Mono(shortIp(rc.ip), Ios.SecondaryLabel)
                                if (rc.ok && rc.pingMs != null) Text(" " + fa(rc.pingMs), color = Ios.Green, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        if (fastest != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("⚡ سریع‌ترین تا اینجا:", "⚡ Fastest so far:"), color = Ios.Label, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Mono(shortIp(fastest.ip))
                Text(" · " + ms(fastest.real?.pingMs), color = Ios.Green, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (!fastest.pinned) {
                    Text(tr("انتخاب", "Choose"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Ios.Blue).clickable { onPick(fastest.ip) }
                            .padding(horizontal = 12.dp, vertical = 5.dp))
                } else {
                    Text(tr("انتخاب شد", "Chosen"), color = Ios.Blue, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ProxyProgressCard(job: PsiphonUpstream.Job, now: State<Long>, cname: (String) -> String, onStop: () -> Unit, modifier: Modifier) {
    val t = now.value
    val elapsed = ((t - job.startedAt) / 1000).coerceAtLeast(0)
    val single = job.countries == 1
    val title = if (single) tr("سنجش پراکسی‌های ${cname(job.scope)}", "Measuring the proxies of ${cname(job.scope)}")
        else tr("سنجش کشورها: ${fa(job.done)} از ${fa(job.countries)}", "Measuring countries: ${job.done} of ${job.countries}")
    val quickDone = job.quickTested + job.steps.values.filter { it.stage == "quick" }.sumOf { it.quickDone }
    val quickOk = job.quickOk + job.steps.values.filter { it.stage == "quick" }.sumOf { it.quickOk }
    val progress: Float? = if (!single) job.done.toFloat() / job.countries.coerceAtLeast(1)
        else job.steps[job.scope]?.takeIf { it.stage == "quick" && it.quickTotal > 0 }?.let { it.quickDone.toFloat() / it.quickTotal }
    ProgressShell(title, clock(elapsed), progress, onStop, modifier) {
        CardLine(tr("سنجش سریع از اینترنت شما: ${fa(quickDone)} پراکسی · ${fa(quickOk)} جواب داد · سایفون موقت: ${fa(job.realDone)} · ✓ ${fa(job.realOk)} وصل شد",
            "Quick test from your internet: $quickDone proxies · $quickOk answered · throwaway Psiphon: ${job.realDone} · ✓ ${job.realOk} connected"), Ios.Label)
        if (!single && job.withProxy > 0) CardLine(tr("${fa(job.withProxy)} کشور پراکسی‌ای دارد که سایفون از راهش وصل می‌شود", "${job.withProxy} countries have a proxy Psiphon connects through"), Ios.Green)
        if (job.current.isNotEmpty()) {
            CardLine(tr("در حال سنجش: ", "Measuring: ") + job.current.joinToString("، ") { cc ->
                val s = job.steps[cc]
                cname(cc) + when {
                    s == null -> ""
                    s.stage == "quick" -> tr(" (سنجش سریع ${fa(s.quickDone)} از ${fa(s.quickTotal)})", " (quick ${s.quickDone} of ${s.quickTotal})")
                    else -> tr(" (سایفون موقت)", " (throwaway Psiphon)")
                }
            })
        }
        job.testing.entries.sortedBy { it.value.since }.take(3).forEach { (key, test) ->
            val s = ((t - test.since) / 1000).coerceAtLeast(0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (test.connected) "✓" else "⏳", fontSize = 12.sp, color = if (test.connected) Ios.Green else Ios.SecondaryLabel)
                Spacer(Modifier.width(6.dp))
                Mono(key)
                Text(" · " + cname(test.country), color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(if (test.connected) tr("وصل شد، سنجش پینگ…", "connected, measuring the ping…")
                    else tr("${fa(s)} از ${fa(PsiphonProbes.UPSTREAM_MS / 1000)} ثانیه", "$s of ${PsiphonProbes.UPSTREAM_MS / 1000} s"),
                    color = Ios.SecondaryLabel, fontSize = 12.sp)
            }
        }
        if (job.testing.size > 3) CardLine(tr("و ${fa(job.testing.size - 3)} سنجش دیگر", "and ${job.testing.size - 3} more"))
        val eta = if (!single && job.done >= 1) (job.countries - job.done).coerceAtLeast(0) * (job.countryMs / job.done) / 3 else null
        CardLine(listOfNotNull(
            tr("${fa(job.slots)} سایفون موقت هم‌زمان", "${job.slots} throwaway Psiphons at once"),
            aboutLeft(eta),
        ).joinToString(" · "))
        if (job.recent.isNotEmpty()) {
            CardLine(tr("آخرین‌ها: ", "Latest: ") + job.recent.joinToString(" · ") { r ->
                (if (r.ok) "✓ " else "✗ ") + cname(r.country) + (if (r.ok && r.pingMs != null) " " + ms(r.pingMs) else "")
            })
        }
    }
}

@Composable
private fun CustomTestCard(test: PsiphonUpstream.CustomTest, now: State<Long>, onStop: () -> Unit, modifier: Modifier) {
    val s = ((now.value - test.since) / 1000).coerceAtLeast(0)
    val budget = if (test.stage == "quick") 10L else PsiphonProbes.UPSTREAM_MS / 1000L
    ProgressShell(
        tr("سنجش پروکسی خودتان", "Testing your proxy"), clock(s),
        if (test.connected) null else (s.toFloat() / budget).coerceIn(0f, 1f), onStop, modifier,
    ) {
        CardLine(when {
            test.stage == "quick" -> tr("۱ از ۲ — سنجش سریع از اینترنت شما: آیا پروکسی HTTPS را رد می‌کند؟ — ${fa(s)} از ${fa(budget)} ثانیه",
                "1 of 2 -- quick test from your internet: does the proxy relay HTTPS? -- $s of $budget s")
            test.connected -> tr("۲ از ۲ — سایفون از راه این پروکسی وصل شد؛ سنجش پینگ…", "2 of 2 -- Psiphon connected through this proxy; measuring the ping…")
            else -> tr("۲ از ۲ — یک سایفون موقت از راه این پروکسی بالا می‌آید — ${fa(s)} از ${fa(budget)} ثانیه",
                "2 of 2 -- a throwaway Psiphon comes up through this proxy -- $s of $budget s")
        }, Ios.Label)
    }
}

// ── «کاندوئیت» ──────────────────────────────────────────────────────────────────────────────────

@Composable
fun PsiphonPairingScreen(onBack: () -> Unit, backLabel: String) {
    val ctx = LocalContext.current
    var pairing by remember { mutableStateOf<PsiphonPairing.Pairing?>(null) }
    LaunchedEffect(Unit) { pairing = withContext(Dispatchers.IO) { PsiphonPairing.read(ctx) } }
    var link by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    IosScreen(title = tr("کاندوئیت", "Conduit"), onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(12.dp))
        error?.let { Note(it, Ios.Red) }
        note?.let { Note(it, Ios.Green) }
        val p = pairing
        SettingsGroup {
            SettingsRow(
                title = p?.let { (it.name.ifBlank { tr("ایستگاه بدون نام", "Unnamed station") }) + tr(" — جفت شده", " -- paired") } ?: tr("جفت نشده", "Not paired"),
                subtitle = if (p != null) tr("روش «کاندوئیت شخصی» فقط از ایستگاه(های) همین لینک وصل می‌شود — نه از سرورهای عمومی سایفون.",
                    "«Personal Conduit» connects only through this link's station(s) -- not Psiphon's public servers.")
                    else tr("لینکی که دوست یا خانواده‌تان از برنامهٔ Conduit برایتان فرستاده را پایین بگذارید.", "Paste the link a friend or relative sent you from the Conduit app below."),
                icon = Icons.Default.Link, tint = if (p != null) Ios.Purple else Ios.Gray, showChevron = false,
            )
            if (p != null) {
                Separator()
                SettingsActionRow(label = tr("روش اتصال سایفون: کاندوئیت شخصی", "Make it Psiphon's method"), icon = Icons.Default.Check, tint = Ios.Blue,
                    onClick = {
                        PsiphonMethods.setPinned(ctx, "P")
                        Toast.makeText(ctx, tr("روش اتصال سایفون: کاندوئیت شخصی", "Psiphon's method: personal Conduit"), Toast.LENGTH_SHORT).show()
                    })
                Separator()
                SettingsActionRow(label = tr("حذف جفت‌سازی", "Remove the pairing"), icon = Icons.Default.LinkOff, tint = Ios.Red,
                    onClick = {
                        PsiphonPairing.clear(ctx)
                        pairing = null
                        if (PsiphonMethods.pinned(ctx) == "P") PsiphonMethods.setPinned(ctx, PsiphonMethods.AUTO)
                    })
            }
        }
        SettingsSectionHeader(tr("لینک جفت‌سازی", "Pairing link"))
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(RoundedCornerShape(12.dp)).heightIn(min = 84.dp).padding(12.dp),
        ) {
            BasicTextField(
                value = link, onValueChange = { link = it; error = null },
                textStyle = TextStyle(color = Ios.Label, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Ios.Blue),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (link.isEmpty()) Text("psiphon://pair/eyJ2IjoiMSIsImRhdGEiOnsi...", color = Ios.SecondaryLabel, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            SettingsActionRow(label = tr("ذخیره", "Save"), icon = Icons.Default.Save, tint = Ios.Blue, enabled = link.isNotBlank(),
                onClick = {
                    try {
                        val saved = PsiphonPairing.save(ctx, link)
                        pairing = saved
                        link = ""
                        error = null
                        PsiphonMethods.setPinned(ctx, "P")
                        note = tr("جفت شد", "Paired") + (if (saved.name.isNotBlank()) tr(" با «${saved.name}»", " with «${saved.name}»") else "") +
                            tr(" — روش اتصال روی «کاندوئیت شخصی» رفت.", " -- the method is now «personal Conduit».")
                    } catch (e: PsiphonPairing.PairingError) { error = e.message }
                })
        }
        SettingsFooter(tr("شکلش psiphon://pair/… یا یک آدرس https://…/pair/… است.", "It looks like psiphon://pair/… or an https://…/pair/… address."))
        SettingsSectionHeader(tr("کاندوئیت چیست و لینک را از کجا بگیرم؟", "What is Conduit, and where is the link from?"))
        SettingsFooter(tr(
            "کاندوئیت برنامهٔ داوطلبانهٔ سایفون است: کسی بیرون از ایران روی گوشی یا کامپیوترش آن را روشن می‌کند و دستگاهش برای کاربران سایفون پل می‌شود. آدرس این دستگاه‌ها خانگی است و در هیچ فهرست مسدودی نیست.\n\n" +
                "جفت‌سازی شخصی یعنی آن ایستگاه فقط برای شما (و هر که لینک را دارد) کار کند: در برنامهٔ Conduit بخش «Personal Pairing» را باز می‌کند، نامی می‌گذارد و لینک می‌سازد و برایتان می‌فرستد.\n\n" +
                "بعد از ذخیره، دکمهٔ اتصال سایفون را بزنید. اتصال فقط از همان ایستگاه می‌گذرد، پس اگر ایستگاه خاموش باشد وصل نمی‌شوید — در آن صورت روش دیگری را انتخاب کنید. این روش داخل وارپ کار نمی‌کند و در حالت خودکار امتحان نمی‌شود.",
            "Conduit is Psiphon's volunteer app: someone outside Iran runs it on their phone or computer and their device becomes a bridge for Psiphon users. Those addresses are residential and on no block list.\n\n" +
                "Personal pairing makes that station work only for you (and whoever has the link): in Conduit they open «Personal Pairing», name it, make a link and send it to you.\n\n" +
                "After saving, tap Psiphon's connect button. The connection goes only through that station, so if it is off you will not connect -- then pick another method. It does not work inside WARP and is never tried automatically.",
        ))
        Spacer(Modifier.height(28.dp))
    }
}

// ── small parts ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Note(text: String, color: Color) {
    Text(text, color = color, fontSize = 13.sp, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Ios.Blue else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = if (selected) Color.White else Ios.Label, fontSize = 13.sp) }
}

/** A label and its chips on one scrolling line -- segmented choices, never a drop-down. */
@Composable
private fun OptionRow(label: String, chips: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(label, color = Ios.SecondaryLabel, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

/** One row of a long list: a plain card (glass under hundreds of rows froze the bridge list), a tick when chosen. */
@Composable
private fun CountryRow(
    flag: String,
    name: String,
    detail: String,
    detailColor: Color,
    trailing: String,
    selected: Boolean,
    mono: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ios.CardOverWallpaper).clickable(onClick = onClick)
            .heightIn(min = 54.dp).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = if (flag.length <= 1) 15.sp else 22.sp, color = Ios.SecondaryLabel, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Ios.Label, fontSize = if (mono) 13.sp else 15.sp, maxLines = 1, fontFamily = if (mono) FontFamily.Monospace else null)
            Text(detail, color = detailColor, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 3)
        }
        if (trailing.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, color = Ios.Green, fontSize = 12.sp)
        }
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Check, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
        }
    }
}
