package com.mlmvpn.scanner.ui.npv

import android.app.Activity
import android.content.ClipboardManager
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.ScanGuard
import com.mlmvpn.scanner.engines.npv.NpvEngine
import com.mlmvpn.scanner.engines.npv.NpvLibrary
import com.mlmvpn.scanner.engines.npv.NpvLibrary.Kind
import com.mlmvpn.scanner.engines.npv.NpvLibrary.Shelf
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.amnezia.ActionSheet
import com.mlmvpn.scanner.ui.amnezia.DelayCapsule
import com.mlmvpn.scanner.ui.amnezia.fa
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal val NPV_GREEN = Color(0xFF54E08C)

private enum class Page { MAIN, SOURCES }

/** Library writes go off the main thread: the file can hold thousands of configs. */
internal fun CoroutineScope.io(block: () -> Unit) { launch(Dispatchers.IO) { block() } }

/**
 * «NPV»: a glass card with the connect button and the live state, «منبع کانفیگ‌ها» and «سنجش»,
 * then the configs of the main page, fastest first. Everything the sources bring in waits in
 * «انبار» ([NpvSourcesPage]) until the user sends it here. Standard links connect on the app's Xray
 * engine; NPV Tunnel's own files are kept and opened in NPV Tunnel, never run here (see [NpvLibrary]).
 */
@Composable
fun NpvScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rev by NpvEngine.revision.collectAsState()
    var entries by remember { mutableStateOf<List<NpvLibrary.Entry>>(emptyList()) }
    LaunchedEffect(rev) { entries = withContext(Dispatchers.IO) { NpvEngine.ensureSeed(ctx); NpvLibrary.entries(ctx) } }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }

    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val nodeId by MyVpnService.connectedNodeIdFlow.collectAsState()
    val live by NpvEngine.live.collectAsState()
    val ours = nodeId == NpvEngine.NODE_ID && phase != MyVpnService.Phase.IDLE
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var failedId by rememberSaveable { mutableStateOf<String?>(null) }

    var pendingUri by remember { mutableStateOf<String?>(null) }
    val vpnPrepare = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = pendingUri
        pendingUri = null
        if (r.resultCode == Activity.RESULT_OK && uri != null) NpvEngine.connect(ctx, uri)
    }
    val connect: (NpvLibrary.Entry) -> Unit = { e ->
        val uri = e.uri
        if (uri != null) ScanGuard.run(ScanGuard.Reason.CONNECT_VPN) {
            activeId = e.id
            failedId = null
            val consent = runCatching { VpnService.prepare(ctx) }.getOrNull()
            if (consent != null) { pendingUri = uri; vpnPrepare.launch(consent) } else NpvEngine.connect(ctx, uri)
        }
    }
    // A connect of ours that ended without data marks that config, so the next tap tries another.
    var tracking by remember { mutableStateOf(false) }
    LaunchedEffect(phase, nodeId) {
        if (ours) tracking = true
        if (phase == MyVpnService.Phase.FAILED && tracking) {
            tracking = false
            activeId?.let { id -> failedId = id; withContext(Dispatchers.IO) { NpvEngine.markFailed(ctx, id) } }
        }
        if (phase == MyVpnService.Phase.IDLE) tracking = false
    }

    when (page) {
        Page.SOURCES -> NpvSourcesPage(
            entries = entries, live = live, activeId = activeId.takeIf { ours }, onBack = { page = Page.MAIN }, onConnect = connect,
        )
        Page.MAIN -> MainPage(
            onBack = onBack, entries = entries, live = live, phase = phase, ours = ours, activeId = activeId,
            failed = phase == MyVpnService.Phase.FAILED && failedId != null, onConnect = connect, onSources = { page = Page.SOURCES },
        )
    }
    RefreshQuestions(entries)
}

/** The two questions «بررسی کانفیگ‌های تازه» asks on its way: shown over either page. */
@Composable
private fun RefreshQuestions(entries: List<NpvLibrary.Entry>) {
    val ctx = LocalContext.current
    val r by NpvEngine.refresh.collectAsState()
    when (r.ask) {
        NpvEngine.Ask.TUNNEL -> AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("تونل خاموش شود؟", "Turn the tunnel off?")) },
            text = {
                Text(tr(
                    "دریافت تمام شد. تونل برنامه فقط برای رسیدن به تلگرام لازم بود. خاموشش کنم تا آزمودن کانفیگ‌ها روی اینترنت خودتان انجام شود؟",
                    "The download is done. The app's tunnel was only needed to reach Telegram. Turn it off, so the configs are tested on your own internet?",
                ))
            },
            confirmButton = { TextButton(onClick = { NpvEngine.answer(true) }) { Text(tr("خاموش کن", "Turn it off")) } },
            dismissButton = { TextButton(onClick = { NpvEngine.answer(false) }) { Text(tr("روشن بماند", "Keep it on")) } },
        )
        NpvEngine.Ask.TEST -> {
            val n = entries.count { it.shelf == Shelf.STORE && it.kind == Kind.LINK }
            val other = remember(r.ask) { NpvEngine.otherVpnUp(ctx) }
            AlertDialog(
                onDismissRequest = {},
                title = { Text(tr("همه آزموده شوند؟", "Test them all?")) },
                text = {
                    Text(tr(
                        "${fa(r.added)} کانفیگ تازه آمد و ${fa(n)} کانفیگ در انبار است. همه را همین حالا روی اینترنت شما بیازمایم و خراب‌ها را حذف کنم تا انبار بیهوده پر نشود؟ اگر نه، در انبار می‌مانند و هر وقت خواستید «سنجش» بزنید." +
                            if (other) "\n\nیک VPN دیگر روی گوشی روشن است؛ نتیجهٔ آزمون دربارهٔ همان VPN خواهد بود. برای نتیجهٔ درست اول آن را خاموش کنید." else "",
                        "${r.added} new configs came in and ${n} are in the store. Test them all on your internet now and delete the broken ones, so the store does not fill up for nothing? If not, they wait in the store; tap «Measure» whenever you like." +
                            if (other) "\n\nAnother VPN is on; the test would be about that VPN. Turn it off first for a true result." else "",
                    ))
                },
                confirmButton = { TextButton(onClick = { NpvEngine.answer(true) }) { Text(tr("بله، بیازما", "Yes, test")) } },
                dismissButton = { TextButton(onClick = { NpvEngine.answer(false) }) { Text(tr("نه، در انبار بماند", "No, keep them stored")) } },
            )
        }
        NpvEngine.Ask.NONE -> Unit
    }
}

@Composable
private fun MainPage(
    onBack: () -> Unit,
    entries: List<NpvLibrary.Entry>,
    live: Map<String, Long>,
    phase: MyVpnService.Phase,
    ours: Boolean,
    activeId: String?,
    failed: Boolean,
    onConnect: (NpvLibrary.Entry) -> Unit,
    onSources: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = ours && phase == MyVpnService.Phase.CONNECTED
    // Sorted by what the library holds, not by the live results: the rows hold still while a
    // measurement runs and settle fastest first when it writes them back.
    val main = remember(entries) { NpvLibrary.byDelay(entries.filter { it.shelf == Shelf.MAIN && it.kind == Kind.LINK }) }
    val myFiles = remember(entries) { entries.filter { it.kind == Kind.NATIVE } }
    val stored = remember(entries) { entries.count { it.shelf == Shelf.STORE && it.kind == Kind.LINK } }
    val measure by NpvEngine.measuring.collectAsState()
    // Off the main thread: picking the best reads every config's link (VpnConfig.parseUri), and it
    // ran again on every measurement result -- the NPV page froze for nine seconds on a slow phone
    // (1.2.42 #2408).
    val best by androidx.compose.runtime.produceState<NpvLibrary.Entry?>(null, entries, live) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { NpvEngine.best(entries, live) }
    }
    var menuFor by remember { mutableStateOf<NpvLibrary.Entry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(80) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "npv"
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    if (bytes.size > 2_000_000) -1 else NpvEngine.importFile(ctx, name, bytes)
                }.getOrDefault(-1)
            }
            Toast.makeText(ctx, when {
                n < 0 -> tr("فایل خوانده نشد", "The file could not be read")
                n == 0 -> tr("چیز تازه‌ای در فایل نبود", "Nothing new in the file")
                else -> tr("${fa(n)} مورد به صفحهٔ اصلی آمد", "$n added to the main page")
            }, Toast.LENGTH_SHORT).show()
        }
    }

    IosScreen(onBack = onBack, backLabel = stringResource(R.string.home), largeTitle = stringResource(R.string.home_npv)) {
        val current = entries.firstOrNull { it.id == activeId }
        HeroCard(
            phase = phase, ours = ours, failed = failed, current = current.takeIf { ours }, best = best, live = live,
            onButton = {
                val pick = best
                when {
                    ours -> NpvEngine.disconnect(ctx)
                    pick != null -> onConnect(pick)
                    else -> Toast.makeText(ctx, tr(
                        "کانفیگ سالمی نیست. در «منبع کانفیگ‌ها» کانفیگ تازه بگیرید یا «سنجش» بزنید.",
                        "No working config. Get new ones in «Config sources», or tap «Measure».",
                    ), Toast.LENGTH_LONG).show()
                }
            },
        )
        com.mlmvpn.scanner.ui.RouteChips()

        SettingsGroup(Modifier.padding(top = 18.dp)) {
            SettingsRow(
                title = tr("منبع کانفیگ‌ها", "Config sources"),
                subtitle = tr("کانال‌های تلگرام، دریافت تازه‌ها و انبار", "Telegram channels, new configs and the store"),
                icon = Icons.Default.Inventory2, tint = Ios.Indigo,
                value = tr("انبار ${fa(stored)}", "Store $stored"),
                onClick = onSources,
            )
            Separator()
            MeasureRow(Shelf.MAIN, main.size, measure) { ScanGuard.run(ScanGuard.Reason.DELAY_TEST) { NpvEngine.measure(ctx, Shelf.MAIN) } }
            val broken = main.count { (live[it.id] ?: it.ping) < 0 }
            if (broken > 0 && !(measure.running && measure.shelf == Shelf.MAIN)) {
                Separator()
                SettingsActionRow(label = tr("حذف خراب‌ها (${fa(broken)})", "Delete the broken ones ($broken)"), icon = Icons.Default.DeleteSweep, tint = Ios.Red) {
                    scope.io { NpvEngine.removeBroken(ctx, Shelf.MAIN) }
                }
            }
        }

        if (myFiles.isNotEmpty()) {
            // NPV Tunnel files moved here from a chat or imported: up top, where they are seen (Ehsan,
            // 31 Oct 2026: «چیزی توی صفحه npv یا انبار نمیبینم»); a tap opens one in NPV Tunnel
            SettingsSectionHeader(tr("فایل‌های NPV شما (${fa(myFiles.size)})", "Your NPV files (${myFiles.size})"))
            SettingsGroup {
                myFiles.forEachIndexed { i, e ->
                    if (i > 0) Separator()
                    NativeRow(e, onRemove = { scope.io { NpvEngine.remove(ctx, listOf(e.id)) } })
                }
            }
        }

        SettingsSectionHeader(tr("کانفیگ‌های صفحهٔ اصلی (${fa(main.size)})", "Main page configs (${main.size})"))
        if (main.isEmpty()) {
            SettingsGroup {
                Text(
                    tr("هنوز کانفیگی اینجا نیست. در «منبع کانفیگ‌ها» کانفیگ‌های سالم انبار را علامت بزنید و به این صفحه بفرستید، یا کانفیگ خودتان را وارد کنید.",
                        "Nothing here yet. In «Config sources», tick working configs in the store and send them here, or import your own."),
                    color = Ios.SecondaryLabel, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            SettingsGroup {
                main.take(shown).forEachIndexed { i, e ->
                    if (i > 0) Separator()
                    ConfigRow(e, live[e.id] ?: e.ping, active = ours && e.id == activeId, onClick = { menuFor = e })
                }
                if (main.size > shown) {
                    Separator()
                    SettingsActionRow(label = tr("نمایش بیشتر (${fa(main.size - shown)})", "Show more (${main.size - shown})"), icon = Icons.Default.Description) { shown += 80 }
                }
            }
            SettingsGroup(Modifier.padding(top = 12.dp)) {
                SettingsActionRow(label = tr("انتقال همه به انبار", "Move all to the store"), icon = Icons.Default.Archive, tint = Ios.Blue) {
                    scope.io { NpvEngine.moveAll(ctx, Shelf.MAIN, Shelf.STORE) }
                }
                Separator()
                SettingsActionRow(label = tr("حذف همه", "Delete all"), icon = Icons.Default.DeleteForever, tint = Ios.Red) { confirmClear = true }
            }
        }
        SettingsFooter(tr(
            "«سنجش» هر کانفیگ را همین حالا روی اینترنت شما واقعاً می‌آزماید و فهرست را از سریع‌ترین مرتب می‌کند. دکمهٔ اتصال سریع‌ترینِ سالم را وصل می‌کند. لمس هر کانفیگ: اتصال، انتقال به انبار یا حذف. کانفیگ رایگان مال دیگران است؛ برای کارهای حساس فقط HTTPS.",
            "«Measure» really tests every config on your internet now and sorts the list fastest first. The connect button connects the fastest working one. Tap a config to connect it, move it to the store or delete it. Free configs belong to others; HTTPS only for anything sensitive.",
        ))

        SettingsSectionHeader(tr("کانفیگ یا فایل خودتان", "Your own config or file"))
        SettingsGroup {
            SettingsActionRow(label = tr("وارد کردن فایل (npvt، npvs یا متن)", "Import a file (npvt, npvs or text)"), icon = Icons.Default.FileOpen, tint = Ios.Blue,
                onClick = { pickFile.launch(arrayOf("*/*")) })
            Separator()
            SettingsActionRow(label = tr("چسباندن لینک از کلیپ‌بورد", "Paste links from the clipboard"), icon = Icons.Default.ContentPaste, tint = Ios.Blue,
                onClick = {
                    val text = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { NpvEngine.importText(ctx, text) }
                        Toast.makeText(ctx, if (n > 0) tr("${fa(n)} کانفیگ به صفحهٔ اصلی آمد", "$n configs added") else tr("لینک تازه‌ای در کلیپ‌بورد نبود", "No new link in the clipboard"), Toast.LENGTH_SHORT).show()
                    }
                })
        }
        SettingsFooter(tr(
            "لینک‌های استاندارد با موتور Xray همین برنامه وصل می‌شوند. فایل‌های npvt و npvs با کلید برنامهٔ NPV Tunnel رمز شده‌اند و آن کلید منتشر نشده؛ این فایل‌ها نگه داشته و با یک لمس در NPV Tunnel باز می‌شوند. فایل را از تلگرام با «باز کردن با MLMVPN» هم می‌شود فرستاد. MLMVPN وابستگی رسمی به NPV Tunnel ندارد.",
            "Standard links connect on this app's Xray engine. npvt and npvs files are encrypted with NPV Tunnel's key, which is not published; they are kept and open in NPV Tunnel with one tap. Files can also come from Telegram with «Open with MLMVPN». MLMVPN is not affiliated with NPV Tunnel.",
        ))
        Spacer(Modifier.height(36.dp))
    }

    menuFor?.let { e ->
        ActionSheet(title = e.name, onDismiss = { menuFor = null }) {
            SettingsActionRow(label = tr("اتصال", "Connect"), icon = Icons.Default.PowerSettingsNew, tint = Ios.Green) { menuFor = null; onConnect(e) }
            Separator()
            SettingsActionRow(label = tr("انتقال به انبار", "Move to the store"), icon = Icons.Default.Archive, tint = Ios.Blue) {
                menuFor = null; scope.io { NpvEngine.move(ctx, listOf(e.id), Shelf.STORE) }
            }
            Separator()
            SettingsActionRow(label = tr("حذف", "Delete"), icon = Icons.Default.Delete, tint = Ios.Red) {
                menuFor = null; scope.io { NpvEngine.remove(ctx, listOf(e.id)) }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("همهٔ کانفیگ‌های صفحهٔ اصلی حذف شوند؟", "Delete every config on the main page?")) },
            text = { Text(tr("${fa(main.size)} کانفیگ حذف می‌شود. اگر فقط می‌خواهید از جلوی چشم بروند، «انتقال همه به انبار» را بزنید.", "${main.size} configs will be deleted. To only clear the page, use «Move all to the store».")) },
            confirmButton = { TextButton(onClick = { confirmClear = false; scope.io { NpvEngine.clear(ctx, Shelf.MAIN) } }) { Text(tr("حذف همه", "Delete all"), color = Ios.Red) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("لغو", "Cancel")) } },
        )
    }
}

// ------------------------------------------------------------------------------ the hero card

@Composable
private fun HeroCard(
    phase: MyVpnService.Phase,
    ours: Boolean,
    failed: Boolean,
    current: NpvLibrary.Entry?,
    best: NpvLibrary.Entry?,
    live: Map<String, Long>,
    onButton: () -> Unit,
) {
    val connected = ours && phase == MyVpnService.Phase.CONNECTED
    val connecting = ours && !connected
    // Green means connected and nothing else: off is grey, so a glance never reads it as on.
    val tone by animateColorAsState(
        when { connected -> Ios.Green; connecting -> Ios.Orange; failed -> Ios.Red; else -> Ios.Gray },
        animationSpec = tween(450), label = "tone",
    )
    var since by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connected) { if (connected) since = System.currentTimeMillis() }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(RoundedCornerShape(30.dp)).padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
                Spacer(Modifier.width(6.dp))
                Text(
                    when { connected -> tr("متصل", "Connected"); connecting -> tr("در حال اتصال", "Connecting"); failed -> tr("ناموفق", "Failed"); else -> tr("قطع", "Off") },
                    color = Ios.Label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.weight(1f))
            Image(painterResource(R.drawable.ic_app_npv), null, Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Orb(tone, connecting, connected, onButton)
        Spacer(Modifier.height(14.dp))
        Text(
            when { connected -> tr("متصل", "Connected"); connecting -> tr("در حال اتصال…", "Connecting…"); failed -> tr("وصل نشد", "Did not connect"); else -> tr("قطع است", "Not connected") },
            color = Ios.Label, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                current != null -> current.name
                failed && best != null -> tr("بار دیگر بزنید تا کانفیگ بعدی امتحان شود: ", "Tap again to try the next: ") + best.name
                best != null -> tr("سریع‌ترین: ", "Fastest: ") + best.name
                else -> tr("کانفیگ سالمی نیست", "No working config")
            },
            color = if (failed) Ios.Orange else Ios.SecondaryLabel, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.14f))
                .clickable(role = Role.Button, onClick = onButton),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when { connecting -> tr("لغو", "Cancel"); connected -> tr("قطع اتصال", "Disconnect"); else -> tr("اتصال به سریع‌ترین", "Connect to the fastest") },
                color = when { connecting -> Ios.Orange; connected -> Ios.Red; else -> Ios.Label }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        AnimatedVisibility(connected, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(since) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
                val s = ((now - since) / 1000).coerceAtLeast(0)
                val clock = if (s >= 3600) "%d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(Locale.US, s / 60, s % 60)
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(tr("پینگ", "Delay"), current?.let { live[it.id] ?: it.ping }?.takeIf { it > 0 }?.let { fa("$it") + " ms" } ?: "—", Modifier.weight(1f))
                    Stat(tr("زمان", "Time"), fa(clock), Modifier.weight(1f))
                }
                // Live speed, the way NPV Tunnel shows it: per second, from the tunnel's own counters.
                var down by remember { mutableLongStateOf(0L) }
                var up by remember { mutableLongStateOf(0L) }
                var totDown by remember { mutableLongStateOf(0L) }
                var totUp by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) {
                    var lastRx = -1L; var lastTx = -1L; var lastAt = 0L
                    com.mlmvpn.scanner.data.SessionTraffic.totals.collect { t ->
                        val nowMs = System.currentTimeMillis()
                        if (lastRx >= 0 && nowMs > lastAt) {
                            val dt = (nowMs - lastAt) / 1000.0
                            down = ((t.rx - lastRx).coerceAtLeast(0) / dt).toLong()
                            up = ((t.tx - lastTx).coerceAtLeast(0) / dt).toLong()
                        }
                        lastRx = t.rx; lastTx = t.tx; lastAt = nowMs
                        totDown = t.rx; totUp = t.tx
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(tr("↓ دانلود", "↓ Down"), rate(down) + "\n" + size(totDown), Modifier.weight(1f))
                    Stat(tr("↑ آپلود", "↑ Up"), rate(up) + "\n" + size(totUp), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Orb(tone: Color, connecting: Boolean, connected: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val label = when { connecting -> tr("لغو", "Cancel"); connected -> tr("قطع اتصال", "Disconnect"); else -> tr("اتصال", "Connect") }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(170.dp)) {
        Box(
            Modifier.size(170.dp).scale(if (connecting || connected) breathe else 1f).clip(CircleShape)
                .background(Brush.radialGradient(listOf(tone.copy(alpha = if (connected || connecting) 0.45f else 0.22f), tone.copy(alpha = 0.08f), Color.Transparent))),
        )
        if (connecting) {
            CircularProgressIndicator(progress = 0.3f, color = tone, strokeWidth = 3.dp, modifier = Modifier.size(146.dp).rotate(angle))
        }
        Box(
            Modifier.size(120.dp).clip(CircleShape)
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
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Ios.SecondaryLabel, fontSize = 11.sp)
        Text(value, color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

private fun rate(bps: Long): String = when {
    bps >= 1_048_576 -> fa("%.1f".format(Locale.US, bps / 1_048_576.0)) + " MB/s"
    bps >= 1024 -> fa("${bps / 1024}") + " KB/s"
    else -> fa("$bps") + " B/s"
}

private fun size(b: Long): String = when {
    b >= 1_073_741_824 -> fa("%.2f".format(Locale.US, b / 1_073_741_824.0)) + " GB"
    b >= 1_048_576 -> fa("%.1f".format(Locale.US, b / 1_048_576.0)) + " MB"
    else -> fa("${b / 1024}") + " KB"
}

// ------------------------------------------------------------------------------ rows shared by both pages

/** «سنجش» for one shelf: the button, or while it runs, how far it is and a stop. */
@Composable
internal fun MeasureRow(shelf: Shelf, count: Int, m: NpvEngine.Measure, onMeasure: () -> Unit) {
    if (m.running && m.shelf == shelf) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Ios.Green, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("در حال سنجش… ${fa(m.done)} از ${fa(m.total)} · ${fa(m.working)} سالم", "Measuring… ${m.done} of ${m.total} · ${m.working} working"),
                    color = Ios.Label, fontSize = 15.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { NpvEngine.stopMeasure() }) { Text(tr("توقف", "Stop"), color = Ios.Orange) }
            }
            LinearProgressIndicator(
                progress = if (m.total > 0) m.done.toFloat() / m.total else 0f, color = Ios.Green, trackColor = Color.White.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            )
        }
    } else {
        SettingsActionRow(
            label = tr("سنجش", "Measure") + if (count > 0) tr(" (${fa(count)} کانفیگ)", " ($count configs)") else "",
            icon = Icons.Default.Speed, tint = Ios.Green, enabled = count > 0 && !m.running, onClick = onMeasure,
        )
    }
}

/** Where a config came from, in words. */
internal fun sourceLabel(source: String): String = when {
    source == "mine" -> tr("خودتان", "yours")
    source == "free:seed" -> tr("همراه برنامه", "shipped")
    source.startsWith("tg:") -> "@" + source.removePrefix("tg:")
    source.startsWith("free:tg:") -> "@" + source.removePrefix("free:tg:")
    source.startsWith("sub:") -> source.removePrefix("sub:")
    source.startsWith("gh:") || source.startsWith("free:gh:") -> tr("گیت‌هاب", "GitHub")
    else -> tr("فهرست عمومی", "public list")
}

/** One config: the delay dot, its name, protocol and source, the delay. A tick when [checked] is given. */
@Composable
internal fun ConfigRow(
    e: NpvLibrary.Entry,
    ms: Long,
    active: Boolean,
    checked: Boolean? = null,
    onCheck: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 54.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) {
            Icon(
                if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = null,
                tint = if (checked) Ios.Green else Ios.SecondaryLabel,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable { onCheck?.invoke() }.padding(2.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(when {
            active -> Ios.Green; ms > 0 -> NPV_GREEN; ms == 0L -> Ios.SecondaryLabel; else -> Ios.Red
        }))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (e.uri?.substringBefore("://")?.uppercase() ?: "") + " · " + sourceLabel(e.source) + if (active) tr(" · متصل", " · connected") else "",
                color = if (active) Ios.Green else Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        when {
            ms > 0 -> DelayCapsule(ms)
            ms < 0 -> Text(tr("خراب", "broken"), color = Ios.Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            else -> Text("—", color = Ios.SecondaryLabel, fontSize = 13.sp)
        }
    }
}

/** One of the user's own NPV Tunnel files: opens in NPV Tunnel, or goes. */
@Composable
private fun NativeRow(e: NpvLibrary.Entry, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { ask = true }.heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Ios.Gray))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr("فایل NPV Tunnel · باز کردن در NPV Tunnel", "NPV Tunnel file · opens in NPV Tunnel"), color = Ios.SecondaryLabel, fontSize = 12.sp)
        }
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text(e.name) },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    if (!NpvEngine.openNative(ctx, e)) Toast.makeText(ctx, tr("برنامه‌ای برای باز کردن این فایل نیست", "No app can open this file"), Toast.LENGTH_LONG).show()
                }) { Text(tr("باز کردن در NPV Tunnel", "Open in NPV Tunnel")) }
            },
            dismissButton = { TextButton(onClick = { ask = false; onRemove() }) { Text(tr("حذف", "Remove")) } },
        )
    }
}
