package com.mlmvpn.scanner.ui.tgproxy

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.engines.tgproxy.TgBridgeEngine
import com.mlmvpn.scanner.engines.tgproxy.TgLink
import com.mlmvpn.scanner.engines.tgproxy.TgMethod
import com.mlmvpn.scanner.engines.tgproxy.TgNotice
import com.mlmvpn.scanner.engines.tgproxy.TgOp
import com.mlmvpn.scanner.engines.tgproxy.TgOrphan
import com.mlmvpn.scanner.engines.tgproxy.TgProgress
import com.mlmvpn.scanner.engines.tgproxy.TgProxyEngine
import com.mlmvpn.scanner.engines.tgproxy.TgProxyManager
import com.mlmvpn.scanner.engines.tgproxy.TgService
import com.mlmvpn.scanner.engines.tgproxy.TgState
import com.mlmvpn.scanner.engines.tgproxy.TgStep
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.CloudAddAccountForm
import com.mlmvpn.scanner.ui.CloudTroubleshootScreen
import com.mlmvpn.scanner.ui.amnezia.ActionSheet
import com.mlmvpn.scanner.ui.amnezia.Tag
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «پروکسی تلگرام»: a Telegram WEB proxy on the user's own Cloudflare account, in one tap, added to
 * Telegram in a second.
 *
 * One page. The two ways to build (Workers, Pages) are the two rows at the top; every proxy built
 * is a card below, whose single obvious button is the next thing to do -- add it to Telegram when it
 * works, continue when it stopped half-way. The custom domain has its own page with a step-by-step
 * guide.
 */
@Composable
fun TgProxyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = produceState<TgProxyManager?>(null) { value = withContext(Dispatchers.IO) { TgProxyEngine.get(context) } }.value
    if (manager == null) {
        IosScreen(title = tr("پروکسی تلگرام", "Telegram Proxy"), onBack = onBack, backLabel = stringResource(R.string.home)) {}
        return
    }
    var domainFor by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = domainFor != null) { domainFor = null }
    val target = domainFor
    if (target != null) {
        TgDomainPage(manager = manager, serviceId = target, onBack = { domainFor = null })
    } else {
        TgMainPage(manager = manager, onBack = onBack, onDomain = { domainFor = it })
    }
}

@Composable
private fun TgMainPage(manager: TgProxyManager, onBack: () -> Unit, onDomain: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val services by manager.services.collectAsState()
    val progress by manager.progress.collectAsState()
    val notice by manager.notice.collectAsState()
    val orphans by manager.orphans.collectAsState()
    val cloud = remember { CloudManager(context) }
    val allAccounts by cloud.accountsFlow.collectAsState()
    val accounts = remember(allAccounts) { allAccounts.filter { it.accountId.isNotBlank() } }
    // The account list loads in the background after the app starts; deciding "no account" before it
    // has would flash the sign-in form at someone who has one.
    val loaded = produceState(false) {
        cloud.awaitLoaded()
        value = true
    }.value
    val clock by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }

    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    val account: CloudAccount? = accounts.firstOrNull { it.id == accountId } ?: accounts.firstOrNull()
    var pickAccount by remember { mutableStateOf(false) }
    var warnFor by remember { mutableStateOf<TgMethod?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<String?>(null) }
    var orphanFor by remember { mutableStateOf<TgOrphan?>(null) }

    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        Toast.makeText(context, noticeText(n), Toast.LENGTH_LONG).show()
        manager.consumeNotice()
    }

    // A proxy built by an older version of the app gets the current relay when the screen opens.
    LaunchedEffect(loaded) {
        if (loaded) manager.refreshOutdated()
    }

    // The bridge: once it is on for the proxy the user just started it for, Telegram's own
    // add-proxy sheet opens with the local address; if it could not start, a word says why.
    val bridge by TgBridgeEngine.state.collectAsState()
    var openWhenOn by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bridge, openWhenOn) {
        val id = openWhenOn ?: return@LaunchedEffect
        if (bridge.serviceId != id) return@LaunchedEffect
        when (bridge.phase) {
            TgBridgeEngine.Phase.ON -> {
                openWhenOn = null
                val secret = manager.secretOf(id)
                if (secret == null || !openLink(context, TgBridgeEngine.telegramLink(bridge.port, secret))) {
                    Toast.makeText(context, tr("تلگرام پیدا نشد", "Telegram was not found"), Toast.LENGTH_LONG).show()
                }
            }
            TgBridgeEngine.Phase.FAILED -> {
                openWhenOn = null
                Toast.makeText(context, bridgeError(bridge), Toast.LENGTH_LONG).show()
            }
            else -> Unit
        }
    }

    if (!loaded && account == null) {
        IosScreen(title = tr("پروکسی تلگرام", "Telegram Proxy"), onBack = onBack, backLabel = stringResource(R.string.home)) {}
        return
    }
    // No Cloudflare account yet: the same form the Cloud tab uses, right here.
    if (account == null) {
        TgCloudGate(cloud = cloud, onBack = onBack)
        return
    }

    val busy = progress != null
    val build = { method: TgMethod ->
        val prefs = context.getSharedPreferences("tgproxy_ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("warned", false)) {
            manager.create(method, account.id)
        } else {
            warnFor = method
        }
        Unit
    }

    IosScreen(title = tr("پروکسی تلگرام", "Telegram Proxy"), onBack = onBack, backLabel = stringResource(R.string.home)) {
        // What this is, in one breath.
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(Color(0xFF7AB8E6), Color(0xFF61A8DE)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.height(12.dp))
            Text(
                tr("پروکسی تلگرام روی حساب Cloudflare خودتان", "A Telegram proxy on your own Cloudflare account"),
                color = Ios.Label, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                tr("یک دکمه می‌زنید، ساخته و آزمایش می‌شود و با یک دکمهٔ دیگر به تلگرام اضافه می‌شود.",
                    "One tap builds and tests it; one more adds it to Telegram."),
                color = Ios.SecondaryLabel, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            )
        }

        if (accounts.size > 1) {
            SettingsSectionHeader(tr("حساب Cloudflare", "Cloudflare account"))
            SettingsGroup {
                SettingsRow(title = account.name.ifBlank { account.id }, icon = Icons.Default.Cloud, tint = Ios.CloudflareOrange, onClick = { pickAccount = true })
            }
        }

        SettingsSectionHeader(tr("ساخت پروکسی جدید", "Build a new proxy"))
        SettingsGroup {
            SettingsActionRow(
                label = tr("ساخت با Workers", "Build with Workers"), icon = Icons.Default.Bolt, tint = Ios.Orange,
                busy = progress?.let { it.op == TgOp.CREATE && store(manager, it)?.method == TgMethod.WORKERS } == true, enabled = !busy,
            ) { build(TgMethod.WORKERS) }
            Separator()
            SettingsActionRow(
                label = tr("ساخت با Pages", "Build with Pages"), icon = Icons.Default.Cloud, tint = Ios.Teal,
                busy = progress?.let { it.op == TgOp.CREATE && store(manager, it)?.method == TgMethod.PAGES } == true, enabled = !busy,
            ) { build(TgMethod.PAGES) }
        }
        SettingsFooter(
            tr(
                "Workers: آماده در چند ثانیه. Pages: یکی‌دو دقیقه طول می‌کشد و برای تلگرام همان کار را می‌کند. هر دو از نوع «WEB proxy» رسمی تلگرام هستند (تلگرام اندروید ۱۲.۱۰ به بالا). پروکسی MTProto معمولی روی Cloudflare ممکن نیست، چون Cloudflare اتصال TCP خام نمی‌پذیرد.",
                "Workers: ready in seconds. Pages: takes a minute or two and does the same for Telegram. Both make Telegram's official «WEB proxy» (Telegram for Android 12.10 or newer). A plain MTProto proxy cannot run on Cloudflare, which does not accept raw TCP.",
            ),
        )

        progress?.let { ProgressCard(it, manager, onCancel = { manager.cancel() }) }

        if (services.isNotEmpty()) {
            SettingsSectionHeader(tr("پروکسی‌های شما", "Your proxies"))
            services.forEach { s ->
                ServiceCard(
                    s = s, busy = busy, clock = clock, accountName = if (accounts.size > 1) accounts.firstOrNull { it.id == s.accountId }?.name else null,
                    onAdd = {
                        val secret = secretOf(manager, s)
                        if (secret == null || !openInTelegram(context, s.shownHost, secret)) {
                            Toast.makeText(context, tr("تلگرام پیدا نشد یا نسخهٔ آن «WEB proxy» را نمی‌شناسد (۱۲.۱۰ به بالا لازم است)", "Telegram was not found, or it is too old for a WEB proxy (12.10 or newer)"), Toast.LENGTH_LONG).show()
                        }
                    },
                    onContinue = { manager.resume(s.id) },
                    onMenu = { menuFor = s.id },
                    onForget = { manager.forget(s.id) },
                    bridge = bridge.takeIf { it.serviceId == s.id && it.phase != TgBridgeEngine.Phase.OFF },
                    onBridge = {
                        val mine = bridge.serviceId == s.id && bridge.phase in setOf(TgBridgeEngine.Phase.PICKING, TgBridgeEngine.Phase.ON)
                        if (mine) {
                            openWhenOn = null
                            TgBridgeEngine.stop(context)
                        } else {
                            openWhenOn = s.id
                            TgBridgeEngine.start(context, s.id)
                        }
                    },
                )
            }
            SettingsFooter(
                tr(
                    "«پل با آی‌پی تمیز»: تلگرام به خود این برنامه روی گوشی وصل می‌شود و برنامه آن را از تمیزترین آی‌پی کلادفلر به پروکسی شما می‌برد؛ آی‌پی‌ها از اسکنر خود برنامه و آی‌پی‌های سالم این شبکه می‌آیند و هر کدام واقعاً آزمایش می‌شود. وقتی به کارتان می‌آید که پروکسی معمولی کند است یا workers.dev بسته است. تا وقتی پل روشن است، برنامه در پس‌زمینه می‌ماند (در اعلان‌ها).",
                    "«Bridge with a clean IP»: Telegram connects to this app on the phone, and the app carries it to your proxy through the cleanest Cloudflare IP; the IPs come from the app's own scanner and this network's working edges, and each one is really tested. Useful when the ordinary proxy is slow or workers.dev is blocked. While the bridge is on, the app keeps running in the background (see the notification).",
                ),
            )
        }

        if (orphans.isNotEmpty()) {
            SettingsSectionHeader(tr("منابع این برنامه که در فهرست نیستند", "Resources of this app that are not listed"))
            SettingsGroup {
                orphans.forEachIndexed { i, o ->
                    if (i > 0) Separator()
                    SettingsRow(title = o.resource, subtitle = if (o.method == TgMethod.WORKERS) "Workers" else "Pages", icon = Icons.Default.Delete, tint = Ios.Red, onClick = { orphanFor = o })
                }
            }
        }

        SettingsSectionHeader(tr("ابزار", "Tools"))
        SettingsGroup {
            SettingsActionRow(
                label = tr("جست‌وجوی منابع این برنامه در حساب", "Look for this app's resources in the account"),
                icon = Icons.Default.Search, tint = Ios.Blue, enabled = !busy,
            ) { manager.scan(account.id) }
        }
        SettingsFooter(
            tr(
                "هر پروکسی یک Worker یا یک پروژهٔ Pages با نام tgp-… در حساب شماست. کلید Cloudflare شما فقط برای همین حساب و فقط به Cloudflare فرستاده می‌شود؛ هیچ‌جای دیگر نمی‌رود.",
                "Each proxy is one Worker or Pages project named tgp-… in your account. Your Cloudflare key is sent only to Cloudflare, only for this account, and nowhere else.",
            ),
        )
        Spacer(Modifier.height(24.dp))
    }

    // ---------------------------------------------------------------- dialogs and sheets

    if (pickAccount) {
        ActionSheet(title = tr("حساب Cloudflare", "Cloudflare account"), onDismiss = { pickAccount = false }) {
            accounts.forEachIndexed { i, a ->
                if (i > 0) Separator()
                SettingsActionRow(label = a.name.ifBlank { a.id }, icon = Icons.Default.Cloud, tint = Ios.CloudflareOrange) { accountId = a.id; pickAccount = false }
            }
        }
    }

    warnFor?.let { method ->
        IosAlert(
            title = tr("قبل از ساخت", "Before you build"),
            message = tr(
                "شرایط استفاده از Cloudflare اجرای سرویس‌های پروکسی را محدود کرده و ممکن است حساب شما را محدود کند؛ مسئولیت استفاده با خودتان است. برای هر روش حداکثر ${fa(TgProxyManager.MAX_PER_METHOD)} پروکسی ساخته می‌شود.",
                "Cloudflare's terms restrict running proxy services and may limit your account; use is at your own responsibility. At most ${TgProxyManager.MAX_PER_METHOD} proxies are built per method.",
            ),
            actions = listOf(
                IosAlertAction(tr("انصراف", "Cancel"), { warnFor = null }),
                IosAlertAction(tr("ادامه", "Continue"), {
                    context.getSharedPreferences("tgproxy_ui", Context.MODE_PRIVATE).edit().putBoolean("warned", true).apply()
                    warnFor = null
                    manager.create(method, account.id)
                }, preferred = true),
            ),
            onDismiss = { warnFor = null },
        )
    }

    menuFor?.let { id ->
        val s = services.firstOrNull { it.id == id }
        if (s == null) menuFor = null else {
            ActionSheet(title = s.shownHost.ifBlank { s.resource }, onDismiss = { menuFor = null }) {
                SettingsActionRow(label = tr("بررسی دوباره", "Check again"), icon = Icons.Default.Refresh, tint = Ios.Blue, enabled = !busy) { menuFor = null; manager.check(s.id) }
                Separator()
                SettingsActionRow(label = tr("دامنهٔ اختصاصی", "Custom domain"), icon = Icons.Default.Language, tint = Ios.Indigo, enabled = s.state == TgState.READY) { menuFor = null; onDomain(s.id) }
                Separator()
                SettingsActionRow(label = tr("کپی لینک تلگرام", "Copy the Telegram link"), icon = Icons.Default.ContentCopy, enabled = s.host.isNotBlank()) {
                    menuFor = null
                    secretOf(manager, s)?.let { copySensitive(context, TgLink.appLink(s.shownHost, it)) }
                }
                Separator()
                SettingsActionRow(label = tr("کپی آدرس سرور", "Copy the server address"), icon = Icons.Default.ContentCopy, enabled = s.host.isNotBlank()) {
                    menuFor = null
                    copySensitive(context, s.shownHost)
                }
                Separator()
                SettingsActionRow(label = tr("کپی کلید رمز (secret)", "Copy the secret"), icon = Icons.Default.ContentCopy, enabled = s.host.isNotBlank()) {
                    menuFor = null
                    secretOf(manager, s)?.let { copySensitive(context, it) }
                }
                Separator()
                SettingsActionRow(label = tr("حذف", "Delete"), icon = Icons.Default.Delete, tint = Ios.Red, labelColor = Ios.Red, enabled = !busy) { menuFor = null; deleteFor = s.id }
            }
        }
    }

    deleteFor?.let { id ->
        val s = services.firstOrNull { it.id == id }
        if (s == null) deleteFor = null else {
            IosAlert(
                title = tr("حذف این پروکسی؟", "Delete this proxy?"),
                message = tr(
                    "${if (s.method == TgMethod.WORKERS) "Worker" else "پروژهٔ Pages"} «${s.resource}» از حساب Cloudflare شما پاک می‌شود و لینک تلگرام آن از کار می‌افتد.",
                    "The ${if (s.method == TgMethod.WORKERS) "Worker" else "Pages project"} “${s.resource}” is removed from your Cloudflare account and its Telegram link stops working.",
                ),
                actions = listOf(
                    IosAlertAction(tr("انصراف", "Cancel"), { deleteFor = null }),
                    IosAlertAction(tr("حذف", "Delete"), { deleteFor = null; manager.delete(s.id) }, destructive = true, preferred = true),
                ),
                onDismiss = { deleteFor = null },
            )
        }
    }

    orphanFor?.let { o ->
        IosAlert(
            title = tr("حذف این منبع؟", "Delete this resource?"),
            message = tr("«${o.resource}» را این برنامه ساخته بود ولی در فهرست نیست. از حساب Cloudflare پاک می‌شود.", "“${o.resource}” was made by this app but is not in the list. It is removed from your Cloudflare account."),
            actions = listOf(
                IosAlertAction(tr("انصراف", "Cancel"), { orphanFor = null }),
                IosAlertAction(tr("حذف", "Delete"), { orphanFor = null; manager.deleteOrphan(o) }, destructive = true, preferred = true),
            ),
            onDismiss = { orphanFor = null },
        )
    }
    // Keep the scope referenced for later actions that need it.
    @Suppress("UNUSED_VARIABLE") val unused = scope
}

// ------------------------------------------------------------------------------ pieces

/** The service a running operation is about, if it has one yet. */
private fun store(manager: TgProxyManager, p: TgProgress): TgService? = p.serviceId?.let { id -> manager.services.value.firstOrNull { it.id == id } }

@Composable
private fun ProgressCard(p: TgProgress, manager: TgProxyManager, onCancel: () -> Unit) {
    val steps = listOf(TgStep.ACCOUNT, TgStep.BUILD, TgStep.ACTIVATE, TgStep.TEST)
    val words = mapOf(
        TgStep.ACCOUNT to tr("بررسی حساب", "Checking the account"),
        TgStep.BUILD to tr("ساخت", "Building"),
        TgStep.ACTIVATE to tr("فعال‌سازی", "Switching on"),
        TgStep.TEST to tr("آزمایش با تلگرام", "Testing against Telegram"),
        TgStep.CLEANUP to tr("پاک‌سازی", "Cleaning up"),
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).frostedGlass(RoundedCornerShape(22.dp)).padding(16.dp),
    ) {
        Text(words[p.step].orEmpty() + "…", color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        val index = steps.indexOf(p.step).let { if (it < 0) steps.size - 1 else it }
        LinearProgressIndicator(
            progress = (index + 1f) / steps.size, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Ios.Blue, trackColor = Color.White.copy(alpha = 0.15f),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            tr("می‌توانید لغو کنید؛ هر چه ساخته شده باشد در فهرست می‌ماند و بعداً ادامه یا حذف می‌شود.", "You can cancel; whatever was built stays in the list to continue or delete later."),
            color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
        SmallButton(tr("لغو", "Cancel"), filled = false, onClick = onCancel)
    }
    @Suppress("UNUSED_VARIABLE") val unused = manager
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceCard(
    s: TgService, busy: Boolean, clock: Long, accountName: String?,
    onAdd: () -> Unit, onContinue: () -> Unit, onMenu: () -> Unit, onForget: () -> Unit,
    bridge: TgBridgeEngine.State?, onBridge: () -> Unit,
) {
    val (stateWord, stateColor) = stateOf(s, clock)
    val waiting = s.state == TgState.ACTION && s.reason == "rate_limited" && s.blockedUntil > clock
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).frostedGlass(RoundedCornerShape(22.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(if (s.method == TgMethod.WORKERS) "Workers" else "Pages", if (s.method == TgMethod.WORKERS) Ios.Orange else Ios.Teal)
            Spacer(Modifier.width(8.dp))
            Tag(stateWord, stateColor)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(50)).clickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.MoreHoriz, contentDescription = tr("بیشتر", "More"), tint = Ios.SecondaryLabel) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            s.shownHost.ifBlank { s.resource }, color = Ios.Label, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        accountName?.let { Text(it, color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        reasonText(s, clock)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 19.sp)
        }
        if (s.state == TgState.READY) probeLines(s).forEach { (ok, text) ->
            Spacer(Modifier.height(4.dp))
            Text((if (ok) "✓  " else "⚠  ") + text, color = if (ok) Ios.Green else Ios.Orange, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (s.state == TgState.READY && bridge != null) bridgeLine(bridge)?.let { (color, text) ->
            Spacer(Modifier.height(6.dp))
            Text(text, color = color, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (s.state) {
                TgState.READY -> {
                    SmallButton(tr("افزودن به تلگرام", "Add to Telegram"), filled = true, onClick = onAdd)
                    val running = bridge?.phase == TgBridgeEngine.Phase.ON || bridge?.phase == TgBridgeEngine.Phase.PICKING
                    SmallButton(if (running) tr("خاموش کردن پل", "Turn the bridge off") else tr("پل با آی‌پی تمیز", "Bridge with a clean IP"), filled = false, onClick = onBridge)
                }
                TgState.BUILDING -> Unit
                TgState.PARTIAL, TgState.FAILED, TgState.ACTION ->
                    SmallButton(if (waiting) tr("تا پایان انتظار", "Waiting") else tr("ادامه", "Continue"), filled = true, enabled = !busy && !waiting, onClick = onContinue)
                TgState.LOST ->
                    if (s.reason == "no_account" || s.reason == "other_account") SmallButton(tr("حذف از فهرست", "Remove from the list"), filled = false, onClick = onForget)
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, filled: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val bg = if (!enabled) Color.White.copy(alpha = 0.10f) else if (filled) Ios.Blue else Color.White.copy(alpha = 0.14f)
    Box(
        Modifier.heightIn(min = 38.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color.White else Ios.SecondaryLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/** The no-account gate: the Cloud tab's own add-account form, so an account added here is its account too. */
@Composable
private fun TgCloudGate(cloud: CloudManager, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var troubleshoot by remember { mutableStateOf<Pair<String, String>?>(null) }
    troubleshoot?.let { credential ->
        BackHandler { troubleshoot = null }
        CloudTroubleshootScreen(cloudManager = cloud, onBack = { troubleshoot = null }, backLabel = tr("پروکسی تلگرام", "Telegram Proxy"), credential = credential)
        return
    }
    IosScreen(title = tr("پروکسی تلگرام", "Telegram Proxy"), onBack = onBack, backLabel = stringResource(R.string.home)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(Color(0xFFFFA94D), Ios.CloudflareOrange))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(42.dp)) }
            Spacer(Modifier.height(16.dp))
            Text(tr("اول یک حساب Cloudflare وصل کنید", "Connect a Cloudflare account first"), color = Ios.Label, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                tr("پروکسی روی حساب خودتان ساخته می‌شود. ایمیل و کلید (Global API Key یا توکن API) را وارد کنید؛ فقط به Cloudflare فرستاده می‌شود.",
                    "The proxy is built on your own account. Enter the email and the key (Global API Key or an API token); it is sent only to Cloudflare."),
                color = Ios.SecondaryLabel, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            )
        }
        CloudAddAccountForm(
            isAdding = adding, error = error,
            onTroubleshoot = { email, key -> troubleshoot = key to email },
            onAdd = { email, key ->
                adding = true
                error = null
                scope.launch {
                    val (ok, message) = cloud.addAccount(key, email)
                    adding = false
                    if (!ok) error = message
                }
            },
        )
    }
}

// ------------------------------------------------------------------------------ words

private fun secretOf(manager: TgProxyManager, s: TgService): String? = manager.secretOf(s.id)

private fun stateOf(s: TgService, clock: Long): Pair<String, Color> = when (s.state) {
    TgState.READY -> tr("آماده", "Ready") to Color(0xFF34C759)
    TgState.BUILDING -> tr("در حال ساخت", "Building") to Color(0xFF0A84FF)
    TgState.PARTIAL -> tr("نیمه‌کاره", "Unfinished") to Color(0xFFFF9500)
    TgState.FAILED -> tr("ناموفق", "Failed") to Color(0xFFFF453A)
    TgState.ACTION -> tr("نیازمند اقدام شما", "Needs your action") to Color(0xFFFF9500)
    TgState.LOST -> tr("در دسترس نیست", "Unavailable") to Color(0xFF8E8E93)
}.also { clock.hashCode() }

private fun clockText(ms: Long): String = fa(SimpleDateFormat("HH:mm", Locale.US).format(Date(ms)))

private fun reasonText(s: TgService, clock: Long): String? {
    val r = s.reason ?: return null
    val product = if (s.method == TgMethod.WORKERS) "Workers" else "Pages"
    return when {
        r == "rate_limited" ->
            if (s.blockedUntil > clock) tr("Cloudflare درخواست‌های این حساب را موقتاً محدود کرده؛ تا ساعت ${clockText(s.blockedUntil)} صبر کنید.", "Cloudflare is limiting this account for now; wait until ${clockText(s.blockedUntil)}.")
            else tr("محدودیت تمام شد؛ می‌توانید ادامه دهید.", "The limit has passed; you can continue.")
        r.startsWith("no_permission") -> tr(
            "این کلید/توکن اجازهٔ کار با $product را ندارد. با Global API Key یا توکنی که مجوز ویرایش $product دارد دوباره امتحان کنید.",
            "This key/token may not use $product. Try again with the Global API Key or a token that can edit $product.",
        )
        r == "no_subdomain" -> tr("این حساب هنوز زیردامنهٔ workers.dev ندارد و ساختنش هم ممکن نشد. در داشبورد Cloudflare بخش Workers یک‌بار باز کنید.", "This account has no workers.dev subdomain and one could not be made. Open Workers once in the Cloudflare dashboard.")
        r == "account_full" -> tr("در این حساب منابع زیادی با نام tgp- هست؛ ابتدا چند تا را حذف کنید.", "This account already holds many tgp- resources; delete a few first.")
        r == "cancelled" -> tr("لغو شد. هر چه ساخته شده بود در حساب مانده؛ «ادامه» یا «حذف».", "Cancelled. What was built is still in the account; continue or delete.")
        r == "interrupted" -> tr("ساخت نیمه‌کاره ماند (برنامه بسته شد). «ادامه» را بزنید.", "The build was interrupted (the app closed). Tap Continue.")
        r == "not_verified" -> tr("ساخته شد ولی هنوز از تلگرام جواب نگرفتیم؛ چند لحظه بعد «بررسی دوباره» بزنید.", "Built, but Telegram has not answered through it yet; try Check again in a moment.")
        r == "deploy_failed" -> tr("ساخت پروژهٔ Pages در Cloudflare ناموفق بود.", "Cloudflare could not finish the Pages deployment.")
        r == "network" -> tr("اتصال به Cloudflare قطع شد. اینترنت را بررسی کنید و ادامه دهید.", "The connection to Cloudflare dropped. Check the internet and continue.")
        r.startsWith("server") -> tr("Cloudflare خطای سروری داد؛ کمی بعد دوباره امتحان کنید.", "Cloudflare reported a server error; try again shortly.")
        r.startsWith("rejected") -> tr("Cloudflare درخواست را نپذیرفت (${r.removePrefix("rejected:")}).", "Cloudflare refused the request (${r.removePrefix("rejected:")}).")
        r == "keystore" -> tr("حافظهٔ امن گوشی در دسترس نیست؛ کلید رمز نگه داشته نمی‌شود.", "The phone's secure storage is unavailable; the secret cannot be kept.")
        r == "internal" -> tr("خطای داخلی؛ دوباره امتحان کنید.", "Internal error; try again.")
        r == "no_account" -> tr("حساب Cloudflare این پروکسی از برنامه حذف شده؛ چیزی ادعا نمی‌کنیم. منبع هنوز در Cloudflare است.", "This proxy's Cloudflare account was removed from the app; nothing is claimed. The resource is still in Cloudflare.")
        r == "other_account" -> tr("حساب فعلی همان حسابی نیست که این پروکسی در آن ساخته شد؛ به آن دست نمی‌زنیم.", "The current account is not the one this proxy was built in; it is left alone.")
        r == "auth" -> tr("کلید/توکن Cloudflare دیگر معتبر نیست؛ سلامت این پروکسی معلوم نیست.", "The Cloudflare key/token is no longer valid; this proxy's health is unknown.")
        r == "gone" -> tr("این منبع از داشبورد Cloudflare حذف شده است.", "This resource was deleted from the Cloudflare dashboard.")
        r == "not_ours" -> tr("منبعی با این نام هست ولی این برنامه آن را نساخته؛ به آن دست نمی‌زنیم.", "A resource with this name exists but this app did not make it; it is left alone.")
        else -> null
    }
}

/** (ok, text) lines for the two self-tests of a ready proxy. */
private fun probeLines(s: TgService): List<Pair<Boolean, String>> {
    val p = s.probe ?: return emptyList()
    val out = ArrayList<Pair<Boolean, String>>()
    if (p.viaApp == true) out += true to tr("تلگرام از طریق این پروکسی جواب داد", "Telegram answered through this proxy")
    when (p.direct) {
        true -> out += true to tr("از شبکهٔ خود گوشی هم می‌رسد", "It is reachable from the phone's own network too")
        false -> out += false to tr(
            "از شبکهٔ فعلی گوشی مستقیم نمی‌رسد (${stepText(p.step)}). اگر تلگرام وصل نشد، یک دامنهٔ اختصاصی بگیرید یا شبکه/VPN را عوض کنید.",
            "It is not reachable straight from the phone's current network (${stepText(p.step)}). If Telegram does not connect, add a custom domain or change network/VPN.",
        )
        null -> Unit
    }
    return out
}

/** The bridge's line on its proxy's card, or null when there is nothing to say. */
@Composable
private fun bridgeLine(b: TgBridgeEngine.State): Pair<Color, String>? = when (b.phase) {
    TgBridgeEngine.Phase.PICKING -> Ios.Blue to (
        if (b.tried > 0) tr("در حال آزمایش ${fa(b.tried)} آی‌پی کلادفلر برای پل…", "Testing ${b.tried} Cloudflare IPs for the bridge…")
        else tr("در حال پیدا کردن آی‌پی تمیز برای پل…", "Finding a clean IP for the bridge…"))
    TgBridgeEngine.Phase.ON -> Ios.Green to (
        tr("✓  پل روشن است: آی‌پی ${b.ip} · ${fa(b.ms.toInt())} میلی‌ثانیه · ${fa(b.streams)} اتصال", "✓  Bridge on: IP ${b.ip} · ${b.ms} ms · ${b.streams} connections") +
            if (b.fromScanner == 0) tr("\nبرای نتیجهٔ بهتر یک بار اسکنر آی‌پی برنامه را اجرا کنید؛ آی‌پی‌های پیدا شده هم امتحان می‌شوند.", "\nRun the app's IP scanner once for a better pick; the IPs it finds are tried too.") else "")
    TgBridgeEngine.Phase.FAILED -> Ios.Orange to ("⚠  " + bridgeError(b))
    TgBridgeEngine.Phase.OFF -> null
}

private fun bridgeError(b: TgBridgeEngine.State): String = when (b.error) {
    "no_ip" -> tr(
        "از ${fa(b.tried)} آی‌پی کلادفلری که امتحان شد، هیچ‌کدام به پروکسی نرسید. اسکنر آی‌پی برنامه را اجرا کنید و دوباره بزنید.",
        "None of the ${b.tried} Cloudflare IPs tried reached the proxy. Run the app's IP scanner and try again.",
    )
    "no_proxy" -> tr("اول پروکسی باید «آماده» باشد", "The proxy has to be Ready first")
    "port" -> tr("پورتی روی گوشی برای پل آزاد نشد", "No free port on the phone for the bridge")
    else -> tr("پل روشن نشد؛ دوباره امتحان کنید", "The bridge did not start; try again")
}

private fun stepText(step: String?): String = when (step) {
    "page" -> tr("باز شدن صفحه", "opening the page")
    "session" -> tr("ساخت نشست", "starting the session")
    "socket" -> tr("وب‌سوکت", "the WebSocket")
    "stream" -> tr("اتصال به دیتاسنتر", "reaching the data centre")
    "telegram" -> tr("پاسخ تلگرام", "Telegram's answer")
    else -> tr("نامشخص", "unknown")
}

private fun noticeText(n: TgNotice): String = when (n.code) {
    "no_account" -> tr("حساب Cloudflare پیدا نشد", "The Cloudflare account was not found")
    "limit" -> tr("برای هر روش حداکثر ${fa(n.arg.toInt())} پروکسی ساخته می‌شود؛ یکی را حذف کنید", "At most ${n.arg} proxies per method; delete one first")
    "keystore" -> tr("حافظهٔ امن گوشی در دسترس نیست؛ پروکسی ساخته نشد", "The phone's secure storage is unavailable; nothing was built")
    "cannot_delete" -> tr("حساب این پروکسی در برنامه نیست؛ «حذف از فهرست» فقط ردش را پاک می‌کند", "This proxy's account is not in the app; “Remove from the list” only clears its record")
    "deleted" -> tr("حذف شد", "Deleted")
    "scanned" -> if (n.arg == 0L) tr("منبع ناشناخته‌ای پیدا نشد", "No unknown resources found") else tr("${fa(n.arg.toInt())} منبع ناشناخته پیدا شد", "${n.arg} unknown resource(s) found")
    "rate_limited" -> tr("Cloudflare درخواست‌ها را محدود کرده؛ تا ساعت ${clockText(n.arg)} صبر کنید", "Cloudflare is limiting requests; wait until ${clockText(n.arg)}")
    "no_permission" -> tr("این کلید/توکن مجوز این کار را ندارد", "This key/token may not do this")
    "auth" -> tr("کلید/توکن Cloudflare معتبر نیست", "The Cloudflare key/token is not valid")
    "network" -> tr("اتصال به Cloudflare برقرار نشد", "Could not reach Cloudflare")
    "not_ours" -> tr("این منبع را این برنامه نساخته؛ دست نمی‌زنیم", "This app did not make that resource; it is left alone")
    "bad_label" -> tr("نام زیردامنه معتبر نیست (حروف انگلیسی، عدد و خط تیره)", "That subdomain name is not valid (letters, digits, hyphens)")
    "zone_pending" -> tr("Nameserverهای این دامنه هنوز به Cloudflare منتقل نشده", "This domain's nameservers do not point to Cloudflare yet")
    "domain_taken" -> tr("این نام از قبل رکورد DNS دارد؛ به آن دست نمی‌زنیم. نام دیگری انتخاب کنید", "That name already has a DNS record; it is left alone. Pick another name")
    "domain_exists" -> tr("هر پروکسی یک دامنه دارد؛ اول دامنهٔ قبلی را جدا کنید", "A proxy has one domain; remove the current one first")
    "domain_pending" -> tr("دامنه وصل شد ولی هنوز فعال نشده؛ چند دقیقه بعد «بررسی دوباره» بزنید", "The domain is connected but not live yet; check again in a few minutes")
    "domain_ok" -> tr("دامنه فعال شد و تلگرام از آن جواب داد", "The domain is live and Telegram answered through it")
    "domain_permission" -> tr("برای دامنه، Global API Key یا توکنی با دسترسی Zone و DNS و Workers لازم است", "A domain needs the Global API Key, or a token that can read Zones and edit DNS and Workers")
    "domain_removed" -> tr("دامنه جدا شد", "Domain removed")
    "not_ready" -> tr("اول پروکسی باید «آماده» باشد", "The proxy has to be Ready first")
    "unexpected" -> tr("خطای غیرمنتظره", "Unexpected error")
    "delete_failed" -> tr("حذف نشد (${n.arg}); دوباره امتحان کنید", "Delete failed (${n.arg}); try again")
    "failed" -> tr("Cloudflare خطا داد (${n.arg})", "Cloudflare returned an error (${n.arg})")
    else -> tr("خطای ناشناخته", "Something went wrong")
}

// ------------------------------------------------------------------------------ actions

/** Opens Telegram's own add-proxy sheet for this link. False when nothing can handle it. */
private fun openInTelegram(context: Context, host: String, secret: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TgLink.appLink(host, secret))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: Exception) {
    false
}

/** Opens a `tg://` link in Telegram. False when nothing can handle it. */
private fun openLink(context: Context, link: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}

/** Copies text marked sensitive, so Android 13+ does not show it in the clipboard preview. */
private fun copySensitive(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("telegram-proxy", text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
    Toast.makeText(context, tr("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
}
