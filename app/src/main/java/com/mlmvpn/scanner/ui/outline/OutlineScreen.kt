package com.mlmvpn.scanner.ui.outline

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.outline.OutlineBrain
import com.mlmvpn.scanner.engines.outline.OutlineConnect
import com.mlmvpn.scanner.engines.outline.OutlineEngine
import com.mlmvpn.scanner.engines.outline.OutlineServer
import com.mlmvpn.scanner.engines.outline.OutlineSources
import com.mlmvpn.scanner.engines.outline.OutlineTestProgress
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.LocalTabVisible
import com.mlmvpn.scanner.ui.amnezia.BarIcon
import com.mlmvpn.scanner.ui.amnezia.fa
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
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Locale

private enum class Page { MAIN, SERVERS, SOURCES }

/**
 * «Outline»: one glass card with the connect orb and the live state, the server as one row that
 * opens the list, and the section's tools below. The long list lives on its own page
 * ([OutlineServersPage]); the lists it comes from on a third ([SourcesPage]).
 */
@Composable
fun OutlineScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(OutlineEngine.isInitialized()) }
    val visible = LocalTabVisible.current
    LaunchedEffect(Unit) {
        if (!ready) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { OutlineEngine.init(context) }; ready = true }
    }
    // Asked again every few minutes while the page stays open: Shadowmere's hourly round is
    // fetched once it is out. Fetching only; nothing is pinged by itself.
    LaunchedEffect(ready, visible) {
        if (!ready || !visible) return@LaunchedEffect
        while (true) { OutlineEngine.onOpen(context); delay(5 * 60_000L) }
    }
    if (!ready) {
        IosScreen(title = stringResource(R.string.outline_title), onBack = onBack, backLabel = stringResource(R.string.home)) {}
        return
    }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    /** The servers page opened from the Shadowmere row starts on Shadowmere's keys. */
    var serversChip by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }

    // ---- shared: the VPN permission, paste, QR, the one-off notices.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) afterPermission?.invoke()
        afterPermission = null
    }
    val withVpn: (() -> Unit) -> Unit = { action ->
        com.mlmvpn.scanner.data.ScanGuard.run(com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN) {
            val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
            if (prep != null) { afterPermission = action; vpnLauncher.launch(prep) } else action()
        }
    }
    val paste = {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) Toast.makeText(context, tr("کلیپ‌بورد خالی است", "The clipboard is empty"), Toast.LENGTH_SHORT).show()
        else OutlineEngine.importText(context, text)
    }
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let { OutlineEngine.importText(context, it) }
    }
    val scanQr = {
        runCatching {
            qrLauncher.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(tr("QR کلید Outline را اسکن کنید", "Scan the Outline key's QR"))
                setBeepEnabled(false)
                setOrientationLocked(false)
            })
        }
        Unit
    }
    // A key already on the clipboard when the screen opens is offered once, never imported silently.
    var clipKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = runCatching { cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull().orEmpty()
        val key = com.mlmvpn.scanner.engines.outline.OutlineParser.keysIn(text).firstOrNull()
        val known = key != null && OutlineEngine.store.current.servers.any { it.uri == key || it.ssconf == key }
        val seen = context.getSharedPreferences("outline_ui", Context.MODE_PRIVATE).getString("clip_seen", null)
        clipKey = key?.takeIf { !known && it != seen }
    }
    val notice by OutlineEngine.notice.collectAsState()
    var needTunnel by remember { mutableStateOf(false) }
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        if (n is OutlineEngine.Notice.NeedTunnel) { needTunnel = true; OutlineEngine.consumeNotice(); return@LaunchedEffect }
        val text = when (n) {
            is OutlineEngine.Notice.Imported -> tr("${fa(n.count)} کلید اضافه شد و در حال تست است", "${n.count} key(s) added, testing now")
            OutlineEngine.Notice.ImportFailed -> tr("کلید Outline معتبری پیدا نشد", "No valid Outline key found")
            is OutlineEngine.Notice.Refreshed -> if (n.added > 0) tr("${fa(n.added)} سرور تازه اضافه شد", "${n.added} new servers") else tr("فهرست‌ها به‌روز است", "Lists are up to date")
            OutlineEngine.Notice.RefreshFailed -> tr("فهرست‌ها دریافت نشد؛ اینترنت را بررسی کنید", "Could not fetch the lists; check the connection")
            OutlineEngine.Notice.Offline -> tr("اینترنت در دسترس نیست", "No internet connection")
            is OutlineEngine.Notice.Removed -> tr("${fa(n.count)} سرور قطع حذف شد", "${n.count} dead servers removed")
            is OutlineEngine.Notice.NeedTunnel -> ""
            is OutlineEngine.Notice.ShadowmereFailed -> tr("کلیدهای Shadowmere از داخل تونل هم نرسید؛ کمی بعد دوباره امتحان کنید",
                "Shadowmere did not answer even through the tunnel; try again a little later")
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        OutlineEngine.consumeNotice()
    }

    // A refresh asked for with no tunnel up: Shadowmere is filtered and is reached only through one.
    if (needTunnel) {
        IosAlert(
            title = tr("اول یک تونل را روشن کنید", "Turn on a tunnel first"),
            message = tr(
                "سایت Shadowmere فیلتر است و کلیدهایش فقط از داخل یکی از تونل‌های برنامه به‌روز می‌شوند: Outline، V2Ray، WireGuard یا هر کاشی دیگر. با اتصال Outline خودشان به‌روز می‌شوند. فهرست‌های گیت‌هاب به‌روز شد.",
                "Shadowmere is filtered, so its keys are updated only through one of the app's tunnels: Outline, V2Ray, WireGuard or any other tile. Connecting Outline updates them by itself. The GitHub lists were updated.",
            ),
            actions = listOf(
                IosAlertAction(tr("اتصال Outline", "Connect Outline"), {
                    needTunnel = false
                    page = Page.MAIN
                    withVpn { OutlineEngine.connectAsync(context, null) }
                }, preferred = true),
                IosAlertAction(tr("باشه", "OK"), { needTunnel = false }),
            ),
            onDismiss = { needTunnel = false },
        )
    }

    Box(Modifier.fillMaxSize()) {
        when (page) {
            Page.SOURCES -> SourcesPage(onBack = { page = Page.MAIN })
            Page.SERVERS -> OutlineServersPage(
                onBack = { page = Page.MAIN },
                onPicked = { id -> page = Page.MAIN; withVpn { OutlineEngine.connectAsync(context, id) } },
                onPaste = paste, onScan = scanQr,
                startChip = serversChip,
            )
            Page.MAIN -> OutlineHome(
                onBack = onBack,
                onServers = { serversChip = null; page = Page.SERVERS },
                onShadowmere = { serversChip = OutlineSources.SHADOWMERE; page = Page.SERVERS },
                onSources = { page = Page.SOURCES },
                onConnect = { id -> withVpn { OutlineEngine.connectAsync(context, id) } },
                onPaste = paste, onScan = scanQr,
                clipKey = clipKey,
                onClipKey = { add ->
                    val k = clipKey
                    clipKey = null
                    context.getSharedPreferences("outline_ui", Context.MODE_PRIVATE).edit().putString("clip_seen", k).apply()
                    if (add && k != null) OutlineEngine.importText(context, k)
                },
            )
        }
        OutlineUndoBar(Modifier.align(Alignment.BottomCenter).padding(bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current))
    }
}

@Composable
private fun OutlineHome(
    onBack: () -> Unit, onServers: () -> Unit, onShadowmere: () -> Unit, onSources: () -> Unit, onConnect: (String?) -> Unit,
    onPaste: () -> Unit, onScan: () -> Unit, clipKey: String?, onClipKey: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val state by OutlineEngine.store.state.collectAsState()
    val net by OutlineEngine.net.collectAsState()
    val conn by OutlineEngine.connect.collectAsState()
    val progress by OutlineEngine.progress.collectAsState()
    val refreshing by OutlineEngine.refreshing.collectAsState()
    val speed by OutlineEngine.speed.collectAsState()
    val stats = remember(state, net) { state.statsFor(net) }
    val byId = remember(state.servers) { state.servers.associateBy { it.id } }
    val alive = remember(state.servers, stats) { state.servers.count { stats[it.id]?.alive == true } }
    val best = remember(state, net) { OutlineBrain.best(state, net) }
    val activeId = when (val c = conn) { is OutlineConnect.Connecting -> c.serverId; is OutlineConnect.Connected -> c.serverId; else -> null }

    IosScreen(
        onBack = onBack,
        backLabel = stringResource(R.string.home),
        largeTitle = stringResource(R.string.outline_title),
        trailing = { BarIcon(Icons.Default.Settings, tr("منابع و تنظیمات", "Sources and settings"), onSources) },
        onRefresh = { OutlineEngine.refresh(force = true); delay(300); OutlineEngine.refreshing.first { !it } },
    ) {
        // The one-time note: these are other people's servers.
        if (!state.prefs.warned) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                    .background(Ios.Orange.copy(alpha = 0.16f)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("این سرورها عمومی‌اند و صاحبشان ناشناس است. برای بانک، رمز و حساب‌های مهم از آن‌ها استفاده نکنید.",
                        "These are public servers run by strangers. Don't use them for banking, passwords or important accounts."),
                    color = Ios.Label, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(tr("متوجه شدم", "Got it"), color = Ios.Blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { OutlineEngine.setPrefs { it.copy(warned = true) } }.padding(6.dp))
            }
        }
        // A key found on the clipboard, offered once.
        if (clipKey != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                    .background(Ios.Blue.copy(alpha = 0.18f)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("یک کلید Outline در کلیپ‌بورد هست. اضافه شود؟", "There's an Outline key on the clipboard. Add it?"),
                    color = Ios.Label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(tr("نه", "No"), color = Ios.SecondaryLabel, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClipKey(false) }.padding(8.dp))
                Text(tr("افزودن", "Add"), color = Ios.Blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClipKey(true) }.padding(8.dp))
            }
        }

        HeroCard(
            conn = conn, server = activeId?.let { byId[it] }, speed = speed,
            onButton = {
                when (conn) {
                    is OutlineConnect.Connecting, is OutlineConnect.Connected -> OutlineEngine.disconnectAsync(context)
                    else -> onConnect(null)
                }
            },
            onSpeed = { OutlineEngine.speedTest() },
        )
        com.mlmvpn.scanner.ui.RouteChips()

        SettingsSectionHeader(tr("سرور", "Server"))
        SettingsGroup {
            val sub = when {
                best != null -> tr("سریع‌ترین: ${flagOf(best.country)} ${countryName(best.country)} · ${msText(stats[best.id]!!.lastMs)}",
                    "Fastest: ${flagOf(best.country)} ${countryName(best.country)} · ${stats[best.id]!!.lastMs} ms")
                state.servers.isEmpty() -> tr("هنوز سروری نیست؛ فهرست‌ها در حال دریافت است", "No servers yet; fetching the lists")
                else -> tr("هنوز تست نشده", "Not tested yet")
            }
            SettingsRow(
                title = tr("همهٔ سرورها", "All servers"), icon = Icons.Default.List, tint = Ios.Teal,
                value = fa(alive) + " / " + fa(state.servers.size), subtitle = sub, onClick = onServers,
            )
            // Shadowmere's keys on their own: how many, how many answered here, and when Shadowmere
            // last checked them (it does every hour).
            val sm = remember(state.servers) { state.servers.filter { it.shadowmere } }
            if (sm.isNotEmpty()) {
                Separator()
                val checked = sm.maxOf { it.checkedAt }
                val live = remember(state.lastRefreshAt) { OutlineEngine.sourceInfo(OutlineSources.SHADOWMERE).first > 0 }
                SettingsRow(
                    title = "Shadowmere", icon = Icons.Default.Public, tint = Ios.Pink,
                    value = fa(sm.count { stats[it.id]?.alive == true }) + " / " + fa(sm.size),
                    subtitle = when {
                        !live -> tr("برچسب shadowmere · نسخهٔ همراه برنامه (${checkTime(checked)})؛ با روشن بودن یک تونل به‌روز می‌شود",
                            "Tagged shadowmere · the copy in the app (${checkTime(checked)}); updated while a tunnel is on")
                        else -> tr("برچسب shadowmere · به‌روزرسانی: ${checkTime(checked)} · از داخل تونل",
                            "Tagged shadowmere · updated ${checkTime(checked)} · through a tunnel")
                    },
                    onClick = onShadowmere,
                )
            }
        }

        SettingsSectionHeader(tr("تست", "Test"))
        SettingsGroup {
            AnimatedVisibility(progress.running, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                TestProgress(progress)
            }
            if (!progress.running) {
                SettingsActionRow(label = tr("تست سریع (بهترین‌ها)", "Quick test (the best)"), icon = Icons.Default.Bolt, tint = Ios.Green) { OutlineEngine.test(all = false) }
                Separator()
                SettingsActionRow(label = tr("تست همهٔ سرورها", "Test every server"), icon = Icons.Default.Speed, tint = Ios.Indigo) { OutlineEngine.test(all = true) }
                Separator()
                SettingsActionRow(label = tr("حذف سرورهای قطع", "Remove dead servers"), icon = Icons.Default.DeleteSweep, tint = Ios.Red) { OutlineEngine.removeDead() }
            }
        }

        SettingsSectionHeader(tr("کلید شخصی", "Your own key"))
        SettingsGroup {
            SettingsActionRow(label = tr("چسباندن کلید (ss:// یا ssconf://)", "Paste a key (ss:// or ssconf://)"), icon = Icons.Default.ContentPaste, tint = Ios.Teal, onClick = onPaste)
            Separator()
            SettingsActionRow(label = tr("اسکن QR", "Scan a QR"), icon = Icons.Default.QrCodeScanner, tint = Ios.Blue, onClick = onScan)
            Separator()
            SettingsActionRow(label = if (refreshing) tr("در حال دریافت فهرست‌ها…", "Fetching the lists…") else tr("به‌روزرسانی فهرست‌ها", "Refresh the lists"),
                icon = Icons.Default.Refresh, tint = Ios.Orange, busy = refreshing) { OutlineEngine.refresh(force = true) }
        }
        SettingsFooter(tr(
            "سرورها از سایت Shadowmere و ${fa(OutlineSources.BUILT_IN.count { it.site == null })} فهرست عمومی گیت‌هاب گرفته می‌شوند. Shadowmere فیلتر است: کلیدهایش همراه برنامه می‌آید و فقط از داخل یکی از تونل‌های برنامه به‌روز می‌شود (با اتصال Outline خودکار). آخرین دریافت: ${ago(state.lastRefreshAt)}. پینگ فقط وقتی گرفته می‌شود که خودتان «تست» را بزنید.",
            "Servers come from Shadowmere and ${OutlineSources.BUILT_IN.count { it.site == null }} public GitHub lists. Shadowmere is filtered: its keys ship with the app and are updated only through one of the app's tunnels (by themselves when Outline connects). Last fetched: ${ago(state.lastRefreshAt)}. Servers are pinged only when you tap a test.",
        ))
        Spacer(Modifier.height(36.dp))
    }
}

/**
 * The running test: which pass, how far, how many work so far, and for how long. The second pass
 * (from inside WARP) has its own count and bar, and says why it exists.
 */
@Composable
internal fun TestProgress(p: OutlineTestProgress) {
    val warp = p.phase == OutlineTestProgress.Phase.WARP
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.phaseStartedAt) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val secs = ((now - p.phaseStartedAt).coerceAtLeast(0) / 1_000).toInt()
    val clock = "%d:%02d".format(secs / 60, secs % 60)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (warp) tr("از داخل وارپ: ${fa(p.done)} از ${fa(p.total)} · ${fa(p.alive)} سالم", "Through WARP: ${p.done} of ${p.total} · ${p.alive} working")
                else tr("سنجش مستقیم: ${fa(p.done)} از ${fa(p.total)} · ${fa(p.alive)} سالم", "Direct: ${p.done} of ${p.total} · ${p.alive} working"),
                color = Ios.Label, fontSize = 15.sp, modifier = Modifier.weight(1f),
            )
            Text(fa(clock), color = Ios.SecondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
            Text(tr("توقف", "Stop"), color = Ios.Red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { OutlineEngine.stopTest() }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = if (p.total > 0) p.done.toFloat() / p.total else 0f,
            color = if (warp) Ios.Orange else Ios.Green, trackColor = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
        val note = when {
            warp && p.warpStarting -> tr("در حال روشن کردن وارپِ برنامه برای مرحلهٔ دوم؛ روی بعضی خط‌ها تا یک دقیقه طول می‌کشد.",
                "Bringing up the app's WARP for the second pass; on some lines this takes up to a minute.")
            warp && p.warpUnreachable -> tr("وارپ روی این اینترنت در دسترس نبود؛ سرورهایی که مستقیم جواب ندادند فعلاً سنجیده نشدند. بعداً یا روی اینترنت دیگری دوباره بسنجید.",
                "WARP could not be reached on this internet; the servers that gave nothing straight were not tested. Try again later or on another network.")
            warp -> tr("این سرورها مستقیم جواب ندادند و حالا از داخل وارپ دوباره امتحان می‌شوند؛ روی خطی که آدرسشان را می‌بندد، این راه بازشان می‌کند. هر وقت بخواهید متوقفش کنید؛ نتیجه‌ها می‌مانند.",
                "These gave nothing straight and are tried again from inside WARP, which reaches servers this line blocks. Stop it whenever you like; the results so far are kept.")
            p.warpQueued > 0 -> tr("${fa(p.warpQueued)} سرور مستقیم جواب ندادند؛ بعد از این مرحله از داخل وارپ دوباره امتحان می‌شوند.",
                "${p.warpQueued} gave nothing straight; they get a second try from inside WARP after this pass.")
            else -> null
        }
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(note, color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

// ------------------------------------------------------------------------------ the hero card

@Composable
private fun HeroCard(conn: OutlineConnect, server: OutlineServer?, speed: OutlineEngine.SpeedState, onButton: () -> Unit, onSpeed: () -> Unit) {
    val connecting = conn is OutlineConnect.Connecting
    val connected = conn is OutlineConnect.Connected
    val tone by animateColorAsState(
        when { connected -> Ios.Green; connecting -> Ios.Orange; conn is OutlineConnect.Failed -> Ios.Red; else -> OUTLINE_GREEN },
        animationSpec = tween(450), label = "tone",
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .frostedGlass(RoundedCornerShape(30.dp)).padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
                Spacer(Modifier.width(6.dp))
                Text(statusWord(conn), color = Ios.Label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Image(painterResource(R.drawable.ic_app_outline), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(30.dp))
        }

        Spacer(Modifier.height(14.dp))
        Orb(tone, connecting, connected, onButton)
        Spacer(Modifier.height(14.dp))

        Text(
            when (conn) {
                is OutlineConnect.Connected -> tr("متصل", "Connected")
                is OutlineConnect.Connecting -> stepLine(conn)
                else -> tr("آمادهٔ اتصال", "Ready to connect")
            },
            color = Ios.Label, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subLine(conn, server), color = if (conn is OutlineConnect.Failed) Ios.Orange else Ios.SecondaryLabel,
            fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(16.dp))
        val label = when { connecting -> tr("لغو", "Cancel"); connected -> tr("قطع اتصال", "Disconnect"); else -> tr("اتصال به سریع‌ترین", "Connect to the fastest") }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                .background(if (connected || connecting) Color.White.copy(alpha = 0.14f) else OUTLINE_GREEN)
                .clickable(role = Role.Button, onClick = onButton),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = if (connecting) Ios.Orange else if (connected) Ios.Red else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

        AnimatedVisibility(connected, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val c = conn as? OutlineConnect.Connected
            if (c != null) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(c.since) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
                val secs = ((now - c.since) / 1000).coerceAtLeast(0)
                val clock = if (secs >= 3600) "%d:%02d:%02d".format(Locale.US, secs / 3600, secs / 60 % 60, secs % 60)
                    else "%02d:%02d".format(Locale.US, secs / 60, secs % 60)
                Column {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(tr("پینگ", "Delay"), c.delayMs?.let { msText(it) } ?: "—", Modifier.weight(1f))
                        StatTile(tr("زمان", "Time"), fa(clock), Modifier.weight(1f))
                        StatTile(tr("خروجی", "Exit"), "${flagOf(c.country)} ${countryName(c.country)}", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(tr("آی‌پی خروجی", "Exit IP"), c.exitIp ?: "…", Modifier.weight(2f))
                        val speedLabel = when (speed) {
                            OutlineEngine.SpeedState.Running -> tr("در حال سنجش…", "Measuring…")
                            is OutlineEngine.SpeedState.Done -> speedText(speed.bps)
                            OutlineEngine.SpeedState.Failed -> tr("ناموفق؛ دوباره", "Failed; retry")
                            OutlineEngine.SpeedState.None -> tr("تست سرعت", "Speed test")
                        }
                        StatTile(tr("سرعت", "Speed"), speedLabel, Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onSpeed))
                    }
                }
            }
        }
    }
}

private val OUTLINE_GREEN = Color(0xFF2A9D7D)

@Composable
private fun Orb(tone: Color, connecting: Boolean, connected: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val label = when { connecting -> tr("لغو", "Cancel"); connected -> tr("قطع اتصال", "Disconnect"); else -> tr("اتصال", "Connect") }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(176.dp)) {
        Box(
            Modifier.size(176.dp).scale(if (connecting || connected) breathe else 1f).clip(CircleShape)
                .background(Brush.radialGradient(listOf(tone.copy(alpha = 0.45f), tone.copy(alpha = 0.10f), Color.Transparent))),
        )
        if (connecting) {
            CircularProgressIndicator(progress = 0.3f, color = tone, strokeWidth = 3.dp, modifier = Modifier.size(150.dp).rotate(angle))
        }
        Box(
            Modifier.size(124.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(lerp(tone, Color.White, 0.25f), lerp(tone, Color.Black, 0.25f))))
                .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(52.dp))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f)).padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Ios.SecondaryLabel, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun statusWord(conn: OutlineConnect): String = when (conn) {
    OutlineConnect.Idle -> tr("قطع", "Off")
    is OutlineConnect.Connecting -> tr("در حال اتصال", "Connecting")
    is OutlineConnect.Connected -> tr("متصل", "Connected")
    is OutlineConnect.Failed -> tr("ناموفق", "Failed")
}

private fun stepLine(c: OutlineConnect.Connecting): String {
    val s = when (c.step) {
        OutlineConnect.Step.PREPARING -> if (c.serverId.isEmpty()) tr("پیدا کردن سریع‌ترین سرور…", "Finding the fastest server…") else tr("آماده‌سازی…", "Preparing…")
        OutlineConnect.Step.WARP -> tr("روشن کردن وارپ برای عبور از فیلتر…", "Bringing up WARP to get past the filter…")
        OutlineConnect.Step.STARTING -> tr("راه‌اندازی تونل…", "Starting the tunnel…")
        OutlineConnect.Step.VERIFYING -> tr("بررسی عبور داده…", "Checking that data passes…")
    }
    return if (c.attempt > 1) s + tr(" (تلاش ${fa(c.attempt)})", " (try ${c.attempt})") else s
}

private fun subLine(conn: OutlineConnect, server: OutlineServer?): String = when (conn) {
    is OutlineConnect.Failed -> when (conn.reason) {
        OutlineConnect.Reason.OFFLINE -> tr("اینترنت وصل نیست", "You're offline")
        OutlineConnect.Reason.NO_SERVERS -> tr("روی این خط سرور سالمی پیدا نشد؛ «تست همه» یا به‌روزرسانی فهرست‌ها را امتحان کنید", "No working server on this line; try «Test every server» or refresh the lists")
        OutlineConnect.Reason.NO_DATA -> tr("سرور وصل شد ولی داده رد نکرد", "The server connected but carried no data")
        OutlineConnect.Reason.BAD_CONFIG -> tr("تونل با این کلید بالا نیامد", "The tunnel did not start with this key")
        OutlineConnect.Reason.VPN_REFUSED -> tr("اجازهٔ VPN داده نشد", "VPN permission was refused")
    }
    else -> server?.let { "${flagOf(it.country)} ${it.name}" } ?: tr("اتصال یک‌لمسی به سریع‌ترین سرور Outline", "One tap to the fastest Outline server")
}

// ------------------------------------------------------------------------------ sources page

@Composable
private fun SourcesPage(onBack: () -> Unit) {
    val state by OutlineEngine.store.state.collectAsState()
    IosScreen(title = tr("منابع و تنظیمات", "Sources and settings"), onBack = onBack, backLabel = stringResource(R.string.outline_title)) {
        SettingsSectionHeader(tr("اتصال", "Connecting"))
        SettingsGroup {
            SettingsToggle(
                title = tr("رفتن خودکار به سرور بعدی", "Move on automatically"),
                subtitle = tr("اگر سرور داده رد نکرد، سریع‌ترین بعدی (تا ۳ سرور)", "If a server carries nothing, the next fastest (up to 3)"),
                checked = state.prefs.autoFallback,
                onCheckedChange = { on -> OutlineEngine.setPrefs { it.copy(autoFallback = on) } },
                icon = Icons.Default.Bolt, tint = Ios.Green,
            )
        }
        SettingsSectionHeader(tr("فهرست‌های عمومی", "Public lists"))
        SettingsGroup {
            OutlineSources.BUILT_IN.forEachIndexed { i, src ->
                if (i > 0) Separator()
                val (ok, n) = remember(state.lastRefreshAt) { OutlineEngine.sourceInfo(src.id) }
                SettingsToggle(
                    title = src.label,
                    subtitle = when {
                        src.site != null && ok == 0L -> tr("${src.where} · نسخهٔ همراه برنامه · فقط از داخل تونل", "${src.where} · the copy in the app · through a tunnel only")
                        src.site != null -> tr("${src.where} · ${fa(n)} سرور · ${ago(ok)} · از داخل تونل", "${src.where} · $n servers · ${ago(ok)} · through a tunnel")
                        else -> tr("${src.where} · ${fa(n)} سرور · ${ago(ok)}", "${src.where} · $n servers · ${ago(ok)}")
                    },
                    checked = src.id !in state.prefs.disabledSources,
                    onCheckedChange = { on -> OutlineEngine.setSourceEnabled(src.id, on) },
                )
            }
        }
        SettingsFooter(tr(
            "Shadowmere همهٔ کلیدهایش را مرتب خودش بررسی می‌کند و فقط سالم‌ها را می‌دهد؛ کلیدهایش برچسب shadowmere و زمان بررسی دارند. چون سایتش فیلتر است، کلیدهایش همراه برنامه می‌آید و فقط از داخل یکی از تونل‌های برنامه به‌روز می‌شود. فهرست‌های دیگر از گیت‌هاب گرفته می‌شوند و اگر گیت‌هاب فیلتر بود از jsDelivr، و اگر هر دو بسته بود از داخل تونل روشن. سرورهای حذف‌شده دو هفته برنمی‌گردند.",
            "Shadowmere checks all its keys itself, often, and hands out only the working ones; they carry the shadowmere tag and their check time. As the site is filtered, its keys ship with the app and are updated only through one of the app's tunnels. The other lists come from GitHub, or jsDelivr when GitHub is filtered, and through the running tunnel when both are. Deleted servers stay out for two weeks.",
        ))
        Spacer(Modifier.height(36.dp))
    }
}
