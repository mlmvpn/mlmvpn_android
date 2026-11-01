package com.mlmvpn.scanner.ui.lantern

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.lantern.LanternEngine
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.emergency.faDigits
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.ui.tunnel.Transport
import com.mlmvpn.scanner.ui.tunnel.TunnelController
import com.mlmvpn.scanner.ui.tunnel.TunnelStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** «لنترن»'s own choice, kept where the tile's other settings are. */
object LanternSettings {
    private const val PREF_SMART = "lantern_smart"
    private const val PREF_FOR_MAE = "lantern_for_mae"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Lantern's smart routing: also the services it lists as safe to reach directly go direct. Off by default. */
    fun smart(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_SMART, false)
    fun setSmart(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(PREF_SMART, on).apply()

    /** Whether MAE may use Lantern as one of its exits abroad (see LanternMae). On by default, as FENIX's is. */
    fun forMae(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_FOR_MAE, true)
    fun setForMae(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(PREF_FOR_MAE, on).apply()
}

/**
 * «لنترن»'s screen below the connect control -- the desktop's window (components/fronts.js,
 * FRONTS.lantern): «مسیریابی» with its two routings, no country section (the free tier assigns the
 * servers and the core ranks them on measured speed, so a picker would be a control that does
 * nothing), the kept list with «فهرست سرور تازه», and what the core is doing while it does it.
 */
@Composable
fun ColumnScope.LanternPanel() {
    val ctx = LocalContext.current
    val live by LanternEngine.live.collectAsState()
    val tunnel by TunnelController.state.collectAsState()
    var smart by remember { mutableStateOf(LanternSettings.smart(ctx)) }
    var kept by remember { mutableIntStateOf(0) }
    var keptAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tunnel.stage, live.servers, live.stage) {
        withContext(Dispatchers.IO) {
            kept = LanternEngine.cachedServerCount(ctx)
            keptAt = LanternEngine.keptAt(ctx)
        }
    }
    val ours = tunnel.active == Transport.LANTERN && (tunnel.stage == TunnelStage.STARTING || tunnel.stage == TunnelStage.RUNNING)

    if (!LanternEngine.supported()) {
        SettingsGroup {
            SettingsRow(
                title = tr("روی این گوشی در دسترس نیست", "Not available on this phone"),
                subtitle = tr("هستهٔ لنترن فقط برای گوشی‌های ۶۴ بیتی (arm64) ساخته شده است؛ این گوشی ۳۲ بیتی است. موتورهای دیگر را امتحان کنید.",
                    "Lantern's core is built for 64-bit (arm64) phones only, and this one is 32-bit. Try the other engines."),
                showChevron = false,
            )
        }
        return
    }

    // ── what the core is doing ──
    if (ours || live.log.isNotEmpty() || live.error != null) LanternLiveCard(live)

    // ── routing ──
    SettingsSectionHeader(tr("مسیریابی", "Routing"))
    SettingsGroup {
        ModeRow(
            title = tr("همهٔ ترافیک", "All traffic"),
            detail = tr("همه چیز از لنترن رد می‌شود؛ فقط سایت‌های ایرانی مستقیم می‌روند (فهرست خودِ لنترن) — امن‌تر",
                "Everything goes through Lantern; only Iranian sites go direct (Lantern's own list) -- safer"),
            selected = !smart,
        ) { smart = false; LanternSettings.setSmart(ctx, false) }
        Separator()
        ModeRow(
            title = tr("هوشمند", "Smart"),
            detail = tr("علاوه بر سایت‌های ایرانی، سرویس‌هایی که لنترن مستقیم‌شان را بی‌خطر می‌داند (مثل اپل و وان‌درایو) هم مستقیم می‌روند — کم‌مصرف‌تر، ولی شبکهٔ محلی آن‌ها را می‌بیند",
                "Besides Iranian sites, the services Lantern considers safe to reach directly (like Apple and OneDrive) go direct too -- lighter, but the local network sees them"),
            selected = smart,
        ) { smart = true; LanternSettings.setSmart(ctx, true) }
    }
    SettingsFooter(
        tr("بار اول، هسته فهرست سرورهایش را از سرویس لنترن می‌گیرد (از راه دامین‌فرانتینگ و چند روش دیگر) و ذخیره می‌کند؛ از آن به بعد مستقیم و در یکی دو ثانیه وصل می‌شود. اگر اینترنت شما بار اول به سرویس لنترن نرسد، فهرست از راه وارپ گرفته می‌شود — فقط همین یک بار؛ خود ترافیک همیشه مستقیم به سرورهای لنترن می‌رود.",
            "The first time, the core fetches its server list from Lantern's service (through domain fronting and a few other ways) and keeps it; from then on it connects straight away, in a second or two. If your internet cannot reach Lantern's service that first time, the list is fetched through WARP -- only that once; the traffic itself always goes straight to Lantern's servers.") +
            if (ours) tr("\n\nمسیریابی تازه از اتصال بعدی اعمال می‌شود — یک بار قطع و وصل کنید.", "\n\nA new routing applies from the next connect -- disconnect and connect once.") else "",
    )

    // ── the kept list ──
    SettingsSectionHeader(tr("فهرست سرورها", "Server list"))
    SettingsGroup {
        SettingsRow(
            title = if (kept > 0) tr("${faDigits(kept.toString())} سرور ذخیره‌شده", "$kept servers kept") else tr("هنوز فهرستی ذخیره نشده", "No list kept yet"),
            subtitle = if (keptAt > 0) tr("به‌روز شده ", "Updated ") + ago(keptAt) else tr("با اولین اتصال گرفته و ذخیره می‌شود.", "Fetched and kept on the first connect."),
            icon = Icons.Default.FormatListBulleted,
            tint = Ios.Teal,
            showChevron = false,
        )
        Separator()
        SettingsActionRow(
            label = tr("فهرست سرور تازه", "Fresh server list"),
            icon = Icons.Default.Refresh,
            tint = Ios.Orange,
            enabled = kept > 0,
            onClick = {
                val n = LanternEngine.rotate(ctx)
                kept = LanternEngine.cachedServerCount(ctx)
                keptAt = LanternEngine.keptAt(ctx)
                Toast.makeText(ctx, if (n > 0) {
                    if (ours) tr("فهرست قبلی پاک شد — یک بار قطع و وصل کنید تا فهرست تازه گرفته شود.", "The old list is gone -- disconnect and connect once to get a fresh one.")
                    else tr("فهرست قبلی پاک شد — با «اتصال» فهرست تازه گرفته می‌شود.", "The old list is gone -- «Connect» gets a fresh one.")
                } else tr("فهرستی برای پاک کردن نبود.", "There was no list to clear."), Toast.LENGTH_LONG).show()
            },
        )
    }
    SettingsFooter(
        tr("«فهرست سرور تازه» فهرست ذخیره‌شده و حسابی را که سرورها به آن داده شده بود پاک می‌کند تا اتصال بعدی فهرست تازه بگیرد — به کار می‌آید وقتی سرورهای فعلی از اینترنت شما جواب نمی‌دهند. کشور خروج را خود لنترن انتخاب می‌کند؛ نسخهٔ رایگان انتخاب کشور ندارد.",
            "«Fresh server list» clears the kept list and the account its servers were assigned to, so the next connect gets a new list -- useful when the current servers do not answer from your internet. Lantern picks the exit country itself; the free tier has no country choice."),
    )

    // ── MAE ──
    var forMae by remember { mutableStateOf(LanternSettings.forMae(ctx)) }
    SettingsSectionHeader("MAE")
    SettingsGroup {
        SettingsToggle(
            title = tr("استفاده در MAE", "Use in MAE"),
            checked = forMae,
            onCheckedChange = {
                forMae = it
                LanternSettings.setForMae(ctx, it)
                com.mlmvpn.scanner.engines.mae.MaeEngine.lanternForMaeChanged()
            },
            icon = Icons.Default.AutoAwesome,
            tint = Ios.Blue,
        )
    }
    SettingsFooter(
        tr("وقتی MAE وصل است، لنترن یکی از خروجی‌های خارجی آن می‌شود: یک هستهٔ لنترن جدا، با فهرست سرورهای همین کاشی، کنار MAE اجرا می‌شود و MAE برای هر برنامه خودش می‌سنجد که از لنترن باز می‌شود یا نه. به لنترنِ این کاشی دست نمی‌زند.",
            "While MAE is connected, Lantern becomes one of its exits abroad: a separate Lantern core, with this tile's server list, runs beside MAE, and MAE checks for each app by itself whether it opens through Lantern. The Lantern of this tile is not touched."),
    )
}

/** The core's stage, the list, where it comes out, and its last lines -- while it runs and after it stopped. */
@Composable
private fun LanternLiveCard(live: LanternEngine.Live) {
    var all by rememberSaveable { mutableStateOf(false) }
    SettingsSectionHeader(tr("وضعیت لنترن", "Lantern's status"))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val head = when {
            live.connected -> tr("وصل است", "Connected") + LanternEngine.exitLabel(live).takeIf { it.isNotBlank() }?.let { tr(" — خروج از $it", " -- exit: $it") }.orEmpty()
            live.running && live.exhausted -> tr("سرورهای لنترن جواب نمی‌دهند", "Lantern's servers do not answer")
            live.running -> live.detail.ifBlank { tr("راه‌اندازی هسته", "Starting the core") } +
                (if (live.awaitingList && live.via != null) tr(" — از راه «${live.via}»", " -- through «${live.via}»") else "") +
                (if (!live.awaitingList && live.servers > 0) tr(" — ${faDigits(live.servers.toString())} سرور", " -- ${live.servers} servers") +
                    (if (live.cached) tr(" ذخیره‌شده", " kept") else "") else "")
            live.error != null -> tr("وصل نشد", "Did not connect")
            else -> tr("خاموش", "Off")
        }
        Text(head, color = when {
            live.connected -> Ios.Green
            live.running && live.exhausted -> Ios.Red
            live.running -> Ios.Label
            live.error != null -> Ios.Red
            else -> Ios.SecondaryLabel
        }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
        if (!live.running && live.error != null) Text(live.error, color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 18.sp)
        if (live.running && live.exhausted) LanternEngine.blockedMessage()?.let { Text(it, color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 18.sp) }
        val lines = if (all) live.log else live.log.takeLast(6)
        if (lines.isNotEmpty()) {
            Spacer(Modifier.heightIn(min = 4.dp))
            lines.forEach { Text("· $it", color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 17.sp) }
        }
        if (live.log.size > 6) {
            Text(if (all) tr("کوتاه‌تر", "Less") else tr("همهٔ گزارش (${faDigits(live.log.size.toString())} خط)", "The whole log (${live.log.size} lines)"),
                color = Ios.Blue, fontSize = 13.sp, modifier = Modifier.clickable { all = !all }.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ModeRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios.Label, fontSize = 16.sp)
            Text(detail, color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Check, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
        }
    }
}

/** "۳ دقیقه پیش", "۲ روز پیش". */
private fun ago(at: Long): String {
    val s = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> tr("همین حالا", "just now")
        s < 3600 -> tr("${faDigits((s / 60).toString())} دقیقه پیش", "${s / 60} min ago")
        s < 86_400 -> tr("${faDigits((s / 3600).toString())} ساعت پیش", "${s / 3600} h ago")
        else -> tr("${faDigits((s / 86_400).toString())} روز پیش", "${s / 86_400} days ago")
    }
}
