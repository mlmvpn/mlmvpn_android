package com.mlmvpn.scanner.ui.tgproxy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.tgproxy.TgDomainOps
import com.mlmvpn.scanner.engines.tgproxy.TgLink
import com.mlmvpn.scanner.engines.tgproxy.TgOp
import com.mlmvpn.scanner.engines.tgproxy.TgProxyManager
import com.mlmvpn.scanner.engines.tgproxy.TgState
import com.mlmvpn.scanner.engines.tgproxy.TgZone
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.amnezia.ActionSheet
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow

/**
 * The custom domain of one proxy, as a five-step guide that checks itself: each step is ticked when
 * Cloudflare says it is true, so the user always sees which one is theirs to do next.
 *
 *  1. the domain is a site on the Cloudflare account
 *  2. its nameservers point to Cloudflare (the site is active)
 *  3. a subdomain name is chosen
 *  4. the app connects the subdomain to the proxy
 *  5. it answers, and Telegram gets the new address
 */
@Composable
internal fun TgDomainPage(manager: TgProxyManager, serviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val services by manager.services.collectAsState()
    val zones by manager.zones.collectAsState()
    val progress by manager.progress.collectAsState()
    val service = services.firstOrNull { it.id == serviceId }
    var zoneId by rememberSaveable { mutableStateOf<String?>(null) }
    var label by rememberSaveable { mutableStateOf("tg") }
    var pickZone by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(serviceId) { manager.loadZones(serviceId) }

    val title = tr("دامنهٔ اختصاصی", "Custom domain")
    if (service == null) {
        IosScreen(title = title, onBack = onBack, backLabel = tr("پروکسی تلگرام", "Telegram Proxy")) {}
        return
    }
    val busy = progress != null
    val zone: TgZone? = zones.firstOrNull { it.id == zoneId } ?: zones.firstOrNull { it.active } ?: zones.firstOrNull()
    val domain = service.domain
    val host = zone?.let { TgDomainOps.hostFor(label, it) }

    val step1 = zones.isNotEmpty()
    val step2 = zone?.active == true
    val step3 = host != null && TgLink.isHost(host)
    val step4 = domain != null
    val step5 = domain?.ready == true

    IosScreen(title = title, onBack = onBack, backLabel = tr("پروکسی تلگرام", "Telegram Proxy")) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp)) {
            Text(
                tr(
                    "اگر شبکه‌تان آدرس ${if (service.method == com.mlmvpn.scanner.engines.tgproxy.TgMethod.WORKERS) "workers.dev" else "pages.dev"} را می‌بندد، با دامنهٔ خودتان پروکسی کار می‌کند. این راهنما قدم‌به‌قدم است و خودش پیش می‌رود.",
                    "If your network blocks ${if (service.method == com.mlmvpn.scanner.engines.tgproxy.TgMethod.WORKERS) "workers.dev" else "pages.dev"}, a domain of your own gets the proxy through. This guide goes step by step and ticks each one itself.",
                ),
                color = Ios.SecondaryLabel, fontSize = 14.sp, lineHeight = 21.sp,
            )
        }

        if (service.state != TgState.READY) {
            SettingsFooter(tr("اول باید پروکسی «آماده» باشد؛ بعد دامنه را وصل کنید.", "The proxy has to be Ready first; then connect a domain."))
            return@IosScreen
        }

        // ---- 1
        Step(1, step1, tr("دامنه را به Cloudflare اضافه کنید", "Add the domain to Cloudflare")) {
            if (step1) {
                Body(tr("${zones.size} سایت در حساب شما هست.", "${zones.size} site(s) in your account."))
            } else {
                Body(tr("در داشبورد Cloudflare روی «Add a site» بزنید و دامنه‌تان را اضافه کنید (طرح Free کافی است). دامنه‌ای ندارید؟ از هر فروشندهٔ دامنه (Registrar) بخرید.", "In the Cloudflare dashboard choose “Add a site” and add your domain (the Free plan is enough). No domain yet? Buy one from any registrar."))
                Spacer(Modifier.height(8.dp))
                Pill(tr("باز کردن داشبورد Cloudflare", "Open the Cloudflare dashboard")) { openUrl(context, "https://dash.cloudflare.com/") }
            }
        }
        // ---- 2
        Step(2, step2, tr("Nameserverها را عوض کنید", "Switch the nameservers")) {
            if (zone == null) {
                Body(tr("بعد از قدم ۱.", "After step 1."))
            } else if (step2) {
                Body(tr("«${zone.name}» فعال است.", "“${zone.name}” is active."))
            } else {
                Body(tr("در سایت فروشندهٔ دامنه (Registrar)، Nameserverهای «${zone.name}» را با این‌ها جایگزین کنید. معمولاً چند دقیقه تا چند ساعت طول می‌کشد.", "At your domain's registrar, replace the nameservers of “${zone.name}” with these. It usually takes minutes to a few hours."))
                zone.nameServers.forEach { ns ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ns, color = Ios.Label, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Default.ContentCopy, contentDescription = tr("کپی", "Copy"), tint = Ios.Blue,
                            modifier = Modifier.size(20.dp).clickable { copyPlain(context, ns) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Pill(tr("دوباره بررسی کن", "Check again"), enabled = !busy) { manager.loadZones(serviceId) }
            }
        }
        // ---- 3
        Step(3, step3 && step2, tr("نام زیردامنه", "Pick a subdomain name")) {
            if (zones.size > 1) {
                SettingsGroup(horizontal = 0.dp) {
                    SettingsRow(title = tr("سایت", "Site"), value = zone?.name.orEmpty(), onClick = { pickZone = true })
                }
                Spacer(Modifier.height(8.dp))
            }
            if (domain == null) {
                SettingsGroup(horizontal = 0.dp) {
                    SettingsTextRow(title = tr("زیردامنه", "Subdomain"), value = label, onValueChange = { label = it.lowercase().take(40) }, placeholder = "tg")
                }
                Spacer(Modifier.height(6.dp))
                Text(host ?: tr("نام معتبر نیست (حروف انگلیسی، عدد و خط تیره)", "Not a valid name (letters, digits, hyphens)"),
                    color = if (host != null) Ios.Label else Ios.Orange, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text(domain.host, color = Ios.Label, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
        }
        // ---- 4
        Step(4, step4, tr("اتصال دامنه به پروکسی", "Connect the domain to the proxy")) {
            if (domain == null) {
                Body(tr("برنامه خودش رکورد و گواهی را می‌سازد. به رکوردهای موجود شما دست نمی‌زند؛ اگر این نام از قبل رکورد داشته باشد، متوقف می‌شود.", "The app makes the record and certificate itself. It never touches your existing records; if that name already has one, it stops."))
                Spacer(Modifier.height(8.dp))
                Pill(tr("وصل کن", "Connect"), filled = true, enabled = !busy && zone != null && step2 && step3) {
                    zone?.let { manager.attachDomain(serviceId, it, label) }
                }
            } else {
                Body(tr("وصل شد.", "Connected."))
            }
        }
        // ---- 5
        Step(5, step5, tr("فعال‌شدن و آزمایش", "Goes live, and is tested")) {
            when {
                step5 -> {
                    Body(tr("از طریق «${domain?.host}» تلگرام جواب داد. لینک تلگرام حالا از این دامنه استفاده می‌کند.", "Telegram answered through “${domain?.host}”. The Telegram link now uses this domain."))
                }
                domain != null -> {
                    Body(tr("منتظر فعال‌شدن گواهی و DNS هستیم؛ تا چند دقیقه طول می‌کشد.", "Waiting for the certificate and DNS to go live; it can take a few minutes."))
                    Spacer(Modifier.height(8.dp))
                    Pill(tr("بررسی دوباره", "Check again"), filled = true, enabled = !busy) { manager.check(serviceId) }
                }
                else -> Body(tr("بعد از قدم ۴.", "After step 4."))
            }
            val running = progress?.takeIf { it.serviceId == serviceId && it.op == TgOp.DOMAIN }
            if (running != null) {
                Spacer(Modifier.height(8.dp))
                Text(tr("در حال کار…", "Working…"), color = Ios.Blue, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Pill(tr("لغو", "Cancel")) { manager.cancel() }
            }
        }

        if (domain != null) {
            SettingsSectionHeader(tr("مدیریت", "Manage"))
            SettingsGroup {
                SettingsActionRow(label = tr("بررسی دوباره", "Check again"), icon = Icons.Default.Refresh, tint = Ios.Blue, enabled = !busy) { manager.check(serviceId) }
                Separator()
                SettingsActionRow(label = tr("جدا کردن دامنه", "Remove the domain"), icon = Icons.Default.Delete, tint = Ios.Red, labelColor = Ios.Red, enabled = !busy) { confirmRemove = true }
            }
            SettingsFooter(tr("آدرس اصلی Cloudflare سر جایش می‌ماند. فقط رکوردی که همین برنامه ساخته پاک می‌شود.", "The original Cloudflare address stays. Only the record this app made is deleted."))
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickZone) {
        ActionSheet(title = tr("سایت", "Site"), onDismiss = { pickZone = false }) {
            zones.forEachIndexed { i, z ->
                if (i > 0) Separator()
                SettingsActionRow(label = z.name + if (z.active) "" else tr("  (در انتظار Nameserver)", "  (waiting for nameservers)"), icon = Icons.Default.Check, tint = if (z.active) Ios.Green else Ios.Orange) {
                    zoneId = z.id
                    pickZone = false
                }
            }
        }
    }
    if (confirmRemove) {
        IosAlert(
            title = tr("جدا کردن دامنه؟", "Remove the domain?"),
            message = tr("لینک تلگرام به آدرس اصلی Cloudflare برمی‌گردد.", "The Telegram link goes back to the Cloudflare address."),
            actions = listOf(
                IosAlertAction(tr("انصراف", "Cancel"), { confirmRemove = false }),
                IosAlertAction(tr("جدا کن", "Remove"), { confirmRemove = false; manager.detachDomain(serviceId) }, destructive = true, preferred = true),
            ),
            onDismiss = { confirmRemove = false },
        )
    }
}

// ------------------------------------------------------------------------------ pieces

@Composable
private fun Step(number: Int, done: Boolean, title: String, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).frostedGlass(RoundedCornerShape(22.dp)).padding(14.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (done) Ios.Green else Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            else Text(com.mlmvpn.scanner.ui.amnezia.fa(number), color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 20.sp)
}

@Composable
private fun Pill(text: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (!enabled) Color.White.copy(alpha = 0.10f) else if (filled) Ios.Blue else Color.White.copy(alpha = 0.14f)
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(bg).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color.White else Ios.SecondaryLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copyPlain(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("nameserver", text))
    Toast.makeText(context, tr("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
}
