package com.mlmvpn.scanner.ui.cfwarp

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.tunnel.IpFormatter
import com.mlmvpn.core.warp.Ipv4ExitRunner
import com.mlmvpn.core.warp.Ipv4ExitSession
import com.mlmvpn.core.warp.Ipv4Exits
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.emergency.faDigits
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.PanelShape
import com.mlmvpn.scanner.ui.tunnel.CountryLabel

private fun fa(n: Number): String = faDigits(n.toString())

/** The two ways to an exit country, as the desktop's page shows them side by side. */
enum class ExitWay { IPV6, IPV4 }

/** IPv6 (a NAT64 gateway: the engine itself comes out there) or IPv4 (a free proxy after the engine). */
@Composable
fun ExitWaySwitch(selected: ExitWay, onSelect: (ExitWay) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(RoundedCornerShape(12.dp)).padding(3.dp),
    ) {
        for ((way, label) in listOf(
            ExitWay.IPV6 to tr("IPv6 · دروازهٔ NAT64", "IPv6 · NAT64 gateway"),
            ExitWay.IPV4 to tr("IPv4 · پراکسی", "IPv4 · proxy"),
        )) {
            val sel = way == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Ios.Blue else Color.Transparent)
                    .clickable { onSelect(way) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) Color.White else Ios.SecondaryLabel)
            }
        }
    }
}

/**
 * «خروج از پراکسی رایگان»: the IPv4 side of the exit country -- any internet, TCP only.
 *
 * Top to bottom: what is on and where the traffic really leaves now; the look at every country
 * (through WARP, as the traffic will go); every country, measured ones fastest first, dead ones
 * last; then what this way cannot do. Picking a country measures it first if it has never been
 * measured, then turns the exit on (the privacy warning is accepted once).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColumnScope.Ipv4ExitSection(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val stateOrNull by Ipv4Exits.stateFlow(ctx).collectAsState()
    val s = stateOrNull ?: return
    val run by Ipv4ExitRunner.state.collectAsState()
    val live by Ipv4ExitSession.live.collectAsState()
    var ackFor by remember { mutableStateOf<String?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun enable(cc: String, ack: Boolean = false) {
        try {
            Ipv4Exits.setEnabled(ctx, true, ack = ack, country = cc)
            onChanged()
        } catch (e: Ipv4Exits.ExitError) {
            when (e.code) {
                "ACK" -> ackFor = cc
                // Never measured, or nothing of it carried last time: measured now, then turned on.
                "EMPTY" -> Ipv4ExitRunner.scan(ctx, cc) { ok -> if (ok > 0) main.post { enable(cc) } }
            }
        }
    }

    fun pick(cc: String) {
        if (run.running) return
        if (!s.ack) { ackFor = cc; return }
        enable(cc)
    }

    // ── on / off, and where it really leaves now ──
    SettingsSectionHeader(tr("خروج از پراکسی رایگان", "Exit through a free proxy"))
    ExitWayRow(
        flag = if (s.enabled && s.country.isNotBlank()) IpFormatter.flag(s.country) else "⭘",
        name = if (s.enabled && s.country.isNotBlank()) CountryLabel.localized(s.country)
            else tr("خاموش", "Off"),
        detail = if (s.enabled) tr("روشن — در اتصال بعدی وارپ، ماسک، وایرگارد یا وارپ در وارپ اعمال می‌شود. برای خاموش کردن بزنید.",
            "On -- applies to the next WARP, MASQUE, WireGuard or WARP-in-WARP connect. Tap to turn it off.")
            else tr("کشوری را از فهرست پایین انتخاب کنید.", "Pick a country from the list below."),
        selected = s.enabled,
        onClick = { if (s.enabled) { Ipv4Exits.disable(ctx); onChanged() } },
    )
    live?.let { l ->
        Spacer(Modifier.height(6.dp))
        val want = l.want?.takeIf { it.isNotBlank() }
        ExitWayRow(
            flag = when {
                l.measuring -> "⏳"
                l.loc != null -> IpFormatter.flag(l.loc)
                else -> "❔"
            },
            name = when {
                l.measuring -> tr("در حال سنجش کشور خروجی…", "Measuring where it leaves…")
                l.loc != null -> tr("خروجی الان: ", "Leaving from: ") + CountryLabel.localized(l.loc)
                else -> tr("خروجی سنجیده نشد", "The exit could not be measured")
            },
            detail = when {
                l.measuring -> l.proxy?.let { tr("پراکسی $it", "proxy $it") } ?: tr("سنجش پراکسی‌های این کشور از داخل وارپ…", "Measuring this country's proxies from inside WARP…")
                l.fellBack -> tr("هیچ پراکسی سالمی برای ${want?.let { CountryLabel.localized(it) } ?: "این کشور"} جواب نداد؛ ترافیک از خروجی خود وارپ می‌رود. دوباره بسنجید یا کشور دیگری انتخاب کنید.",
                    "No proxy of ${want ?: "the country"} carried; traffic leaves from WARP's own exit. Measure again or pick another country.")
                l.ok && want != null && l.loc != want -> tr("نه ${CountryLabel.localized(want)}", "not $want")
                l.ok -> tr("✓ با درخواست واقعی سنجیده شد", "✓ proved with a real request") + (l.proxy?.let { " · " + tr("پراکسی $it", "proxy $it") } ?: "")
                else -> ""
            },
            detailColor = when {
                l.fellBack || (want != null && l.loc != null && l.loc != want) -> Ios.Orange
                l.ok -> Ios.Green
                else -> Ios.SecondaryLabel
            },
            selected = false,
            onClick = {},
        )
    }
    Spacer(Modifier.height(10.dp))

    // ── the look at every country ──
    val wayText = when (run.way) {
        Ipv4ExitRunner.Way.STARTING -> tr("روشن کردن وارپ برای سنجش… (روی بعضی خط‌ها تا دو دقیقه)", "Bringing up WARP to measure through… (up to two minutes on some lines)")
        Ipv4ExitRunner.Way.ENGINE -> tr("از داخل وارپِ وصل‌شده", "through the connected WARP")
        Ipv4ExitRunner.Way.HELPER -> tr("از داخل وارپِ خودِ سنجش", "through the measurement's own WARP")
        Ipv4ExitRunner.Way.DIRECT -> tr("وارپ روی این خط بالا نیامد؛ مستقیم سنجیده می‌شود و ممکن است با اتصال واقعی فرق کند", "WARP did not come up on this line; measured straight, which may differ from a real connect")
        null -> ""
    }
    ExitWayRow(
        flag = if (run.running && run.all) "⏳" else "📶",
        name = if (run.running && run.all) tr("سنجش همهٔ کشورها: ${fa(run.done)} از ${fa(run.total)}", "Measuring every country: ${run.done} of ${run.total}")
            else tr("سنجش همهٔ کشورها", "Measure every country"),
        detail = when {
            run.running && run.all -> wayText + " · " + tr("برای توقف بزنید", "tap to stop")
            run.running -> tr("یک کشور در حال سنجش است", "A country is being measured")
            else -> tr("برای هر کشور چند پراکسی از داخل وارپ سنجیده می‌شود تا کشورها از سریع به کند مرتب شوند.",
                "A few proxies of every country are measured from inside WARP, so the countries can be ranked fastest first.")
        },
        selected = false,
        onClick = { if (run.running && run.all) Ipv4ExitRunner.cancel() else Ipv4ExitRunner.pingAll(ctx) },
    )

    // ── every country ──
    SettingsSectionHeader(tr("کشورها", "Countries"))
    val countries = remember(s, run.running) {
        Ipv4Exits.countryList(ctx).sortedWith(
            compareBy<String> {
                val p = s.pings[it]
                when {
                    p?.ok == true -> 0
                    p == null -> 1
                    else -> 2
                }
            }.thenBy { s.pings[it]?.takeIf { p -> p.ok }?.rttMs ?: Long.MAX_VALUE },
        )
    }
    Column(Modifier.padding(horizontal = 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (cc in countries) {
            val p = s.pings[cc]
            val scanning = run.running && !run.all && run.country == cc
            val looking = run.running && run.all && cc in run.current
            ExitWayRow(
                flag = if (scanning || looking) "⏳" else IpFormatter.flag(cc),
                name = CountryLabel.localized(cc),
                detail = when {
                    scanning -> tr("در حال سنجش ${fa(run.done)} از ${fa(run.total)} · ${fa(run.ok)} سالم", "Measuring ${run.done} of ${run.total} · ${run.ok} working") +
                        (wayText.takeIf { run.way == Ipv4ExitRunner.Way.STARTING || run.way == Ipv4ExitRunner.Way.DIRECT }?.let { " · $it" } ?: "")
                    looking -> tr("در حال سنجش…", "Measuring…")
                    p?.ok == true -> buildString {
                        append(tr("${fa(p.found)} پراکسی سالم", "${p.found} working"))
                        p.rttMs?.let { append(" · ").append(tr("${fa(it)} میلی‌ثانیه", "$it ms")) }
                        if (p.confirmedAt > 0) append(" · ✓")
                        if (!p.viaWarp) append(" · ").append(tr("سنجش مستقیم", "measured straight"))
                    }
                    p != null -> tr("پراکسی سالمی پیدا نشد (${fa(p.tested)} سنجیده) — برای سنجش دوباره بزنید", "None carried (${p.tested} tried) -- tap to measure again")
                    else -> tr("سنجیده نشده — برای سنجش و انتخاب بزنید", "Not measured -- tap to measure and pick")
                },
                detailColor = when {
                    p?.ok == true -> Ios.Green
                    p != null && !scanning && !looking -> Ios.Red
                    else -> Ios.SecondaryLabel
                },
                selected = s.enabled && s.country == cc,
                onClick = { pick(cc) },
                onLongClick = { if (!run.running) Ipv4ExitRunner.scan(ctx, cc) },
            )
        }
    }
    if (run.emptyResult != null && !run.running) {
        Spacer(Modifier.height(8.dp))
        SettingsFooter(tr("برای ${CountryLabel.localized(run.emptyResult!!)} پراکسی‌ای پیدا نشد که از داخل وارپ واقعاً از همان کشور بیرون بیاید. کمی بعد دوباره بسنجید یا کشور دیگری را امتحان کنید.",
            "No proxy of ${run.emptyResult} really left from that country from inside WARP. Try again a little later, or another country."))
    }

    val (total, nCountries) = Ipv4Exits.seedInfo(ctx)
    SettingsFooter(
        tr("فهرست همراه برنامه: ${fa(total)} پراکسی در ${fa(nCountries)} کشور — بدون دسترسی به سایت منبع هم هست؛ فهرست‌های زنده هم هر بار گرفته می‌شوند.\n\n" +
            "این راه روی هر اینترنتی کار می‌کند ولی فقط TCP را می‌برد: تماس‌های صوتی و بازی‌هایی که UDP می‌خواهند از آن رد نمی‌شوند. نام سایت‌ها (DNS) همچنان از داخل وارپ پرسیده می‌شود. هر پراکسی پیش از استفاده از داخل وارپ سنجیده می‌شود، و اگر وسط کار از کار بیفتد، بعدی همان کشور جایش را می‌گیرد.",
            "Shipped with the app: $total proxies in $nCountries countries -- there even when the source site is not; the live lists are fetched every time too.\n\n" +
            "This way works on any internet but carries TCP only: calls and games that need UDP do not go through it. Names (DNS) are still asked inside WARP. Every proxy is measured from inside WARP before it is used, and if one stops working, the next of the same country takes over."),
    )

    ackFor?.let { cc ->
        AlertDialog(
            onDismissRequest = { ackFor = null },
            title = { Text(tr("خروج از پراکسی رایگان", "Exit through a free proxy")) },
            text = {
                Text(tr("گردانندهٔ پراکسی رایگان می‌بیند به چه سایت‌هایی می‌روید و هر ترافیکی که HTTPS نباشد را می‌خواند. برای بانک، ایمیل و ورود به حساب‌ها از آن استفاده نکنید. ادامه می‌دهید؟",
                    "Whoever runs a free proxy sees which sites you visit and can read any traffic that is not HTTPS. Do not use it for banking, email or logging in. Continue?"))
            },
            confirmButton = { TextButton(onClick = { ackFor = null; enable(cc, ack = true) }) { Text(tr("ادامه", "Continue")) } },
            dismissButton = { TextButton(onClick = { ackFor = null }) { Text(tr("انصراف", "Cancel")) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExitWayRow(
    flag: String,
    name: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    detailColor: Color = Ios.SecondaryLabel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .frostedGlass(PanelShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = Ios.Label, fontSize = 16.sp, maxLines = 1)
            if (detail.isNotBlank()) Text(detail, color = detailColor, fontSize = 11.sp, maxLines = 4)
        }
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.Check, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
        }
    }
}

/** Whether the IPv4 exit is on, for the transport screen's row. */
fun ipv4ExitCountry(ctx: Context): String? =
    Ipv4Exits.load(ctx).takeIf { it.enabled && it.country.isNotBlank() }?.country
