package com.mlmvpn.scanner.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.tunnel.SmartIranLists
import com.mlmvpn.core.tunnel.SmartIranRoute
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.amnezia.fa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «مسیریابی هوشمند ایران»: the switch, what it does in one paragraph, the lists' freshness, and the
 * apps it leaves outside the VPN -- found automatically, every one of them changeable by hand.
 */
@Composable
internal fun SmartIranPage(backLabel: String, onBack: () -> Unit, onChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(SmartIranRoute.enabled(context)) }
    var stats by remember { mutableStateOf(0 to 0) }
    var updated by remember { mutableStateOf(SmartIranLists.lastUpdated(context)) }
    var updating by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    // (package, label, system, outside, suggested-from-an-Iranian-store)
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }

    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            SmartIranLists.ensureSeeded(context)
            stats = SmartIranLists.stats(context)
            val outside = SmartIranRoute.outsideApps(context).toHashSet()
            apps = SmartIranRoute.launchableApps(context).map { (pkg, label, system) ->
                AppRow(pkg, label, system, pkg in outside, pkg !in outside && SmartIranRoute.fromIranianStore(context, pkg))
            }.sortedWith(compareBy({ !it.outside }, { !it.suggested }, { it.label.lowercase() }))
        }
    }

    IosScreen(title = tr("مسیریابی هوشمند ایران", "Smart Iran Routing"), onBack = onBack, backLabel = backLabel) {
        SettingsGroup(modifier = Modifier.padding(top = 12.dp)) {
            SettingsToggle(
                title = tr("مسیریابی هوشمند ایران", "Smart Iran Routing"),
                subtitle = tr("سایت‌ها و برنامه‌های ایرانی بدون VPN، مستقیم", "Iranian sites and apps go direct, without the VPN"),
                checked = on,
                onCheckedChange = {
                    on = it
                    SmartIranRoute.setEnabled(context, it)
                    onChanged(it)
                    if (it) scope.launch(Dispatchers.IO) { SmartIranLists.refresh(context); updated = SmartIranLists.lastUpdated(context); version++ }
                },
                icon = Icons.Default.AltRoute, tint = Ios.Green,
            )
        }
        SettingsFooter(tr(
            "با روشن بودن، در همهٔ روش‌های اتصال برنامه: (۱) برنامه‌های ایرانی مثل بانک‌ها، اسنپ، دیوار، بله و روبیکا کاملاً بیرون از VPN می‌مانند و VPN را نمی‌بینند؛ (۲) آی‌پی‌های ایران از تونل بیرون می‌روند؛ (۳) همهٔ دامنه‌های .ir و ده‌ها هزار سایت ایرانی دیگر (حتی آن‌هایی که خارج از ایران میزبانی می‌شوند) مستقیم باز می‌شوند. سرعت سایت‌های داخلی بالا می‌رود، حجم VPN مصرف نمی‌شود و سایت‌هایی که با VPN باز نمی‌شوند درست کار می‌کنند. از اتصال بعدی اعمال می‌شود.",
            "When on, on every connection method of the app: (1) Iranian apps such as banks, Snapp, Divar, Bale and Rubika stay entirely outside the VPN and never see it; (2) Iran's IP ranges leave the tunnel; (3) every .ir domain and tens of thousands of other Iranian sites (even those hosted abroad) open directly. Domestic sites get faster, use no VPN data, and the ones that refuse VPNs work. Applies from the next connect.",
        ))

        SettingsSectionHeader(tr("فهرست‌ها", "Lists"))
        SettingsGroup {
            SettingsRow(
                title = tr("رنج‌های آی‌پی ایران", "Iran's IP ranges"),
                // Isolated left-to-right: in a right-to-left line the numbers swapped places with their labels.
                value = tr("\u2066IPv4: ${fa(stats.first)} · IPv6: ${fa(stats.second)}\u2069", "IPv4: ${stats.first} · IPv6: ${stats.second}"),
                showChevron = false,
            )
            Separator()
            SettingsRow(
                title = tr("دامنه‌های ایرانی", "Iranian domains"),
                value = tr("همهٔ .ir + حدود ۵۷٬۰۰۰ سایت", "all .ir + ~57,000 sites"),
                showChevron = false,
            )
            Separator()
            SettingsActionRow(
                label = if (updating) tr("در حال به‌روزرسانی…", "Updating…") else tr("به‌روزرسانی الان", "Update now"),
                icon = Icons.Default.Refresh, tint = Ios.Orange, busy = updating,
            ) {
                updating = true
                scope.launch(Dispatchers.IO) {
                    val proxy = if (com.mlmvpn.scanner.MyVpnService.connectionPhaseFlow.value == com.mlmvpn.scanner.MyVpnService.Phase.CONNECTED)
                        com.mlmvpn.scanner.utils.LocalPort.get(context) else null
                    val ok = SmartIranLists.refresh(context, force = true, proxyPort = proxy)
                    updated = SmartIranLists.lastUpdated(context)
                    updating = false
                    version++
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context,
                            if (ok) tr("فهرست‌ها به‌روز شد", "Lists updated") else tr("دریافت نشد؛ نسخهٔ فعلی می‌ماند", "Could not download; the current lists stay"),
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        SettingsFooter(tr(
            "منبع: Chocolate4U/Iran-v2ray-rules که هر روز ساخته می‌شود؛ برنامه روزی یک بار خودش به‌روز می‌کند. آخرین به‌روزرسانی: ${if (updated > 0) agoFa(updated) else "نسخهٔ داخل برنامه"}.",
            "Source: Chocolate4U/Iran-v2ray-rules, rebuilt daily; the app refreshes once a day. Last update: ${if (updated > 0) agoEn(updated) else "the copy in the app"}.",
        ))

        SettingsSectionHeader(tr("برنامه‌های بیرون از VPN", "Apps outside the VPN"))
        val outsideCount = apps.count { it.outside }
        SettingsGroup {
            val shown = if (showAll) apps else apps.filter { it.outside || it.suggested }
            if (shown.isEmpty()) {
                Text(tr("برنامهٔ ایرانی‌ای پیدا نشد", "No Iranian app found"), color = Ios.SecondaryLabel, fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp))
            }
            shown.forEachIndexed { i, a ->
                if (i > 0) Separator()
                AppToggleRow(a) { outside ->
                    SmartIranRoute.setApp(context, a.pkg, outside)
                    apps = apps.map { if (it.pkg == a.pkg) it.copy(outside = outside) else it }
                }
            }
            Separator()
            SettingsActionRow(
                label = if (showAll) tr("فقط برنامه‌های ایرانی", "Iranian apps only") else tr("نمایش همهٔ برنامه‌ها برای افزودن", "Show every app to add"),
                icon = Icons.Default.Apps, tint = Ios.Blue,
            ) { showAll = !showAll }
        }
        SettingsFooter(tr(
            "${fa(outsideCount)} برنامه بیرون از VPN است. برنامه‌های ایرانی خودکار پیدا می‌شوند؛ هر کدام را می‌توانید خاموش کنید یا برنامهٔ دیگری اضافه کنید. «پیشنهاد» یعنی از بازار یا مایکت نصب شده.",
            "$outsideCount app(s) are outside the VPN. Iranian apps are found automatically; you can switch any off or add another. «Suggested» means it came from Bazaar or Myket.",
        ))
        Spacer(Modifier.height(36.dp))
    }
}

internal data class AppRow(val pkg: String, val label: String, val system: Boolean, val outside: Boolean, val suggested: Boolean)

@Composable
private fun AppToggleRow(a: AppRow, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    // Loaded off the main thread, once per app: every installed app is a row here, and drawing
    // each icon during composition (the launcher's icon theming included) froze the page for
    // seconds -- ANRs on opening Settings (1.2.41).
    val icon by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(appIconCache.get(a.pkg), a.pkg) {
        if (value != null) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val d = context.packageManager.getApplicationIcon(a.pkg)
                val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                d.setBounds(0, 0, 96, 96); d.draw(c)
                bmp.asImageBitmap()
            }.getOrNull()
        }?.also { appIconCache.put(a.pkg, it) }
    }
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!a.outside) }.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))) { icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) } }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.pkg, color = Ios.SecondaryLabel, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (a.suggested && !a.outside) {
                    Spacer(Modifier.width(6.dp))
                    Text(tr("پیشنهاد", "Suggested"), color = Ios.Orange, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Ios.Orange.copy(alpha = 0.16f)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
        }
        Switch(checked = a.outside, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Ios.Green))
    }
}

/** App icons already drawn for this page: a return visit shows them at once. */
private val appIconCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(120)

private fun agoFa(at: Long): String {
    val h = ((System.currentTimeMillis() - at) / 3_600_000).toInt()
    return if (h < 1) "کمتر از یک ساعت پیش" else if (h < 48) "${fa(h)} ساعت پیش" else "${fa(h / 24)} روز پیش"
}

private fun agoEn(at: Long): String {
    val h = ((System.currentTimeMillis() - at) / 3_600_000).toInt()
    return if (h < 1) "under an hour ago" else if (h < 48) "$h h ago" else "${h / 24} d ago"
}
