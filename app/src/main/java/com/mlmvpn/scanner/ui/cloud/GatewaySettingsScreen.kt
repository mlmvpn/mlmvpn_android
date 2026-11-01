package com.mlmvpn.scanner.ui.cloud

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.gateway.EdgeGateway
import com.mlmvpn.scanner.engines.gateway.Gateway
import com.mlmvpn.scanner.engines.gateway.SettingsPatch
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import kotlinx.coroutines.launch

/**
 * The central Worker's settings — what BPB's own settings are for a relay: the TLS ports, the clean
 * IPs and domains the links are built on, the Worker's own resolved addresses, IPv6, the anti-filter
 * method links and the Proxy IP (the only one that is a Worker variable and re-uploads it). All of it
 * is shared with the Windows app through the account, so both devices list the same links.
 */
@Composable
fun GatewaySettingsScreen(account: CloudAccount, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by EdgeGateway.version.collectAsState()
    val gw = remember(version, account.accountId) { EdgeGateway.status(context, account) }

    var cleanText by remember { mutableStateOf(gw.settings.cleanIPs.joinToString("\n")) }
    var ports by remember { mutableStateOf(gw.settings.ports.toSet()) }
    var ipv6 by remember { mutableStateOf(gw.settings.ipv6) }
    var withResolved by remember { mutableStateOf(gw.settings.withResolved) }
    var methods by remember { mutableStateOf(gw.settings.methods) }
    var fallback by remember { mutableStateOf(gw.fallback) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }

    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    fun save() {
        if (busy != null) return
        busy = "save"
        scope.launch {
            val patch = SettingsPatch(cleanIPs = cleanText, ports = ports.sorted(), ipv6 = ipv6, withResolved = withResolved, methods = methods)
            EdgeGateway.saveSettings(context, account, patch, fallback) { }
                .onSuccess { say(tr("تنظیمات ذخیره شد — «دریافت کانفیگ» را بزنید تا با آن‌ها کانفیگ ساخته شود.", "Settings saved — press «Get configs» to build configs with them.")) }
                .onFailure { say(EdgeGateway.localized(it)) }
            busy = null
        }
    }

    IosScreen(title = tr("ورکر مرکزی", "Central Worker"), onBack = onDismiss, backLabel = tr("ابر", "Cloud")) {
        SettingsSectionHeader(tr("وضعیت", "Status"))
        SettingsGroup {
            SettingsRow(title = tr("نشانی ورکر", "Worker address"), value = gw.host.ifBlank { "—" }, showChevron = false)
            Separator()
            SettingsRow(
                title = tr("کد ورکر", "Worker code"),
                value = if (!gw.installed) "—" else if (gw.upToDate) tr("به‌روز", "Up to date") else tr("نسخهٔ تازه‌تر هست", "A newer one is shipped"),
                showChevron = false,
            )
            Separator()
            SettingsRow(
                title = tr("هماهنگ با ویندوز", "Shared with Windows"),
                value = if (gw.shared) tr("بله", "Yes") else tr("هنوز نه", "Not yet"),
                subtitle = sharedLine(gw.shared, gw.sharedBy),
                showChevron = false,
            )
            if (gw.panels.isNotEmpty()) {
                Separator()
                SettingsRow(
                    title = tr("پنل‌های وصل", "Connected panels"),
                    value = gw.panels.keys.joinToString("، ") { Gateway.PANEL_TITLE[it] ?: it },
                    showChevron = false,
                )
            }
        }

        SettingsSectionHeader(tr("درگاه‌های TLS", "TLS ports"))
        SettingsGroup {
            Gateway.TLS_PORTS.forEachIndexed { i, p ->
                if (i > 0) Separator()
                SettingsToggle(
                    title = if (p == 443) "443 · ${tr("پیش‌فرض", "default")}" else "$p",
                    checked = p in ports,
                    onCheckedChange = { on -> ports = if (on) ports + p else (ports - p).ifEmpty { ports } },
                )
            }
        }
        SettingsFooter(tr("برای هر آدرس یک کانفیگ روی هر درگاه انتخاب‌شده ساخته می‌شود. درگاه‌های بدون TLS نیستند: هستهٔ Xray وی‌لس بدون TLS را به آدرس عمومی نمی‌پذیرد.",
            "A config is made for every address on every chosen port. There are no non-TLS ports: the Xray core refuses VLESS without TLS to a public address."))

        SettingsSectionHeader(tr("آی‌پی یا دامنهٔ تمیز", "Clean IPs or domains"))
        SettingsGroup { Editor(value = cleanText, onChange = { cleanText = it }, hint = "www.speedtest.net") }
        SettingsFooter(tr("هر خط یا با ویرگول جدا؛ تا ۳۰ مورد. SNI و Host همیشه نام همین ورکر می‌ماند.", "One per line or comma-separated, up to 30. SNI and Host always stay this Worker's name."))

        SettingsSectionHeader(tr("کانفیگ‌ها", "The set of configs"))
        SettingsGroup {
            SettingsToggle(title = tr("با آی‌پی‌های خودِ ورکر", "With the Worker's own addresses"), checked = withResolved, onCheckedChange = { withResolved = it },
                subtitle = tr("نام ورکر با DNS-over-HTTPS ترجمه می‌شود؛ وقتی نام ورکر روی خط شما بسته است همین کارگشاست.", "The Worker's name is resolved over DNS-over-HTTPS; this is what works where the name itself is blocked."))
            Separator()
            SettingsToggle(title = "IPv6", checked = ipv6, onCheckedChange = { ipv6 = it },
                subtitle = tr("آدرس‌های IPv6 ورکر و کانفیگ روش IPv6 هم ساخته شود.", "Also list the Worker's IPv6 addresses and the IPv6 method config."))
            Separator()
            SettingsToggle(title = tr("کانفیگ‌های ضدفیلتر (ECH و F&F)", "Anti-filter configs (ECH and F&F)"), checked = methods, onCheckedChange = { methods = it },
                subtitle = tr("همان روش‌هایی که برنامه روی شبکهٔ شما می‌سنجد، به‌صورت کانفیگ آماده.", "The methods the app measures on your network, as ready configs."))
        }

        SettingsSectionHeader(tr("آی‌پی پشتیبان (Proxy IP)", "Backup address (Proxy IP)"))
        SettingsGroup { Editor(value = fallback, onChange = { fallback = it }, hint = Gateway.DEFAULT_FALLBACK, single = true) }
        SettingsFooter(tr("برای رسیدن به سایت‌هایی که پشت خود کلادفلرند. تا ۳ نشانی به شکل host یا host:port؛ تغییرش ورکر را دوباره بارگذاری می‌کند.",
            "To reach sites that sit behind Cloudflare itself. Up to 3 addresses as host or host:port; changing it re-uploads the Worker."))

        Spacer(Modifier.height(18.dp))
        SettingsGroup {
            SettingsActionRow(label = tr("ذخیره", "Save"), icon = Icons.Default.Save, tint = Ios.Blue, busy = busy == "save") { save() }
            Separator()
            SettingsActionRow(label = tr("آزمایش ورکر", "Test the Worker"), icon = Icons.Default.CloudDone, busy = busy == "check") {
                if (busy != null) return@SettingsActionRow
                busy = "check"
                scope.launch {
                    EdgeGateway.check(context, account)
                        .onSuccess { c ->
                            say(when {
                                c.gateway -> tr("ورکر جواب داد (${c.ms} میلی‌ثانیه).", "The Worker answered (${c.ms} ms).")
                                c.reachable -> tr("جوابی آمد ولی از ورکر مرکزی نبود — احتمالاً نشانی عوض شده؛ ورکر را به‌روز کنید.", "Something answered but not the central Worker — the address may have changed; update the Worker.")
                                else -> tr("ورکر از این شبکه جواب نداد. اگر workers.dev روی این اینترنت فیلتر است طبیعی است؛ اتصال واقعی را با آی‌پی تمیز امتحان کنید.",
                                    "The Worker did not answer from this network. That is normal where workers.dev is filtered; try a real connection with a clean IP.")
                            })
                        }
                        .onFailure { say(EdgeGateway.localized(it)) }
                    busy = null
                }
            }
            if (!gw.upToDate) {
                Separator()
                SettingsActionRow(label = tr("به‌روزرسانی ورکر", "Update the Worker"), icon = Icons.Default.Refresh, tint = Ios.Orange, busy = busy == "update") {
                    if (busy != null) return@SettingsActionRow
                    busy = "update"
                    scope.launch {
                        EdgeGateway.deploy(context, account)
                            .onSuccess { say(tr("ورکر به‌روز شد؛ شناسه‌ها همان ماندند.", "The Worker was updated; the ids stayed the same.")) }
                            .onFailure { say(EdgeGateway.localized(it)) }
                        busy = null
                    }
                }
            }
            Separator()
            SettingsActionRow(label = tr("شناسهٔ تازه برای همه", "New ids for everyone"), icon = Icons.Default.VpnKey, tint = Ios.Orange, busy = busy == "rotate") { confirm = "rotate" }
            Separator()
            SettingsActionRow(label = tr("حذف ورکر مرکزی از کلادفلر", "Remove the central Worker from Cloudflare"), icon = Icons.Default.DeleteForever, tint = Ios.Red, busy = busy == "remove") { confirm = "remove" }
        }
        SettingsFooter(tr("«شناسهٔ تازه» شناسهٔ همهٔ پنل‌ها و مسیر تونل را عوض می‌کند و کانفیگ‌های قبلی (روی گوشی و ویندوز) از کار می‌افتند.",
            "«New ids» renews every panel's id and the tunnel path; the earlier configs (on the phone and on Windows) stop working."))
        Spacer(Modifier.height(24.dp))
    }

    when (confirm) {
        "rotate" -> IosAlert(
            title = tr("شناسهٔ تازه برای همه؟", "New ids for everyone?"),
            message = tr("شناسهٔ همهٔ پنل‌ها و مسیر تونل عوض می‌شود و کانفیگ‌های قبلی از کار می‌افتند؛ باید دوباره «دریافت کانفیگ» بزنید.",
                "Every panel's id and the tunnel path change and the earlier configs stop working; press «Get configs» again."),
            actions = listOf(
                IosAlertAction(tr("انصراف", "Cancel"), { confirm = null }),
                IosAlertAction(tr("بله، عوض کن", "Yes, renew"), {
                    confirm = null
                    busy = "rotate"
                    scope.launch {
                        EdgeGateway.deploy(context, account, rotate = true)
                            .onSuccess { say(tr("شناسهٔ تازه ساخته شد و ورکر به‌روز شد؛ دوباره «دریافت کانفیگ» بزنید.", "New ids made and the Worker updated; press «Get configs» again.")) }
                            .onFailure { say(EdgeGateway.localized(it)) }
                        busy = null
                    }
                }, destructive = true),
            ),
            onDismiss = { confirm = null },
        )
        "remove" -> IosAlert(
            title = tr("حذف ورکر مرکزی؟", "Remove the central Worker?"),
            message = tr("ورکر از حساب کلادفلر پاک می‌شود و کانفیگ‌های همهٔ پنل‌هایی که از آن گرفته‌اید (روی گوشی و ویندوز) از کار می‌افتند.",
                "The Worker is deleted from the Cloudflare account and the configs of every panel you took from it (on the phone and on Windows) stop working."),
            actions = listOf(
                IosAlertAction(tr("انصراف", "Cancel"), { confirm = null }),
                IosAlertAction(tr("حذف", "Remove"), {
                    confirm = null
                    busy = "remove"
                    scope.launch {
                        EdgeGateway.remove(context, account)
                            .onSuccess { say(tr("ورکر مرکزی از حساب حذف شد.", "The central Worker was removed from the account.")); onDismiss() }
                            .onFailure { say(EdgeGateway.localized(it)) }
                        busy = null
                    }
                }, destructive = true),
            ),
            onDismiss = { confirm = null },
        )
        else -> Unit
    }
}

/** A typed field inside a settings card: several lines by default, the look of IosTextScreen's. */
@Composable
private fun Editor(value: String, onChange: (String) -> Unit, hint: String, single: Boolean = false) {
    Box(Modifier.fillMaxWidth().heightIn(min = if (single) 46.dp else 96.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = single,
            textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp),
            cursorBrush = SolidColor(Ios.Blue),
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.TopStart) {
                    if (value.isEmpty()) Text(hint, color = Ios.SecondaryLabel.copy(alpha = 0.5f), fontSize = 16.sp)
                    inner()
                }
            },
        )
    }
}
