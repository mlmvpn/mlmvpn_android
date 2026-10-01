package com.mlmvpn.scanner.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.openvpn.*
import com.mlmvpn.scanner.quick.GeoLabel
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.ui.tunnel.DialCore
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * «اوپن‌وی‌پی‌ان»: TunnelBear's servers, connected directly.
 *
 * Built like every other engine page: the dial, one status line, the live figures while
 * connected, then grouped rows that open their own pages. No tabs and no dialogs -- the previous
 * version put servers, accounts and help in three tabs of text buttons and asked everything in
 * pop-ups.
 */
@Composable
fun OpenVpnScreen(onBack: () -> Unit, onStore: () -> Unit, onIran: () -> Unit, visible: Boolean = true) {
    val context = LocalContext.current
    val vm = remember(context) {
        val owner = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<ViewModelStoreOwner>().first()
        ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as android.app.Application))[OpenVpnViewModel::class.java]
    }
    val loaded by vm.data.collectAsState()
    val connection by OpenVpnRuntime.connection.collectAsState()
    val testing by vm.testing.collectAsState()
    val testTotal by vm.testTotal.collectAsState()
    val message by vm.message.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    val fa = remember { tr("fa", "en") == "fa" }

    LaunchedEffect(visible, loaded != null) { if (visible && loaded != null) vm.refresh() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.import(it) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val selection = pending
        pending = null
        if (it.resultCode == Activity.RESULT_OK && selection != null) OpenVpnRuntime.connect(context, selection.first, selection.second)
        else vm.message.value = "VPN_PERMISSION_DENIED"
    }
    fun connect(profileId: String, accountId: String) {
        vm.cancelTests()
        pending = profileId to accountId
        val intent = VpnService.prepare(context)
        if (intent != null) permission.launch(intent)
        else { pending = null; OpenVpnRuntime.connect(context, profileId, accountId) }
    }

    BackHandler(enabled = page != Page.MAIN) {
        page = if (page == Page.ACCOUNT_EDIT) Page.ACCOUNTS else Page.MAIN
    }

    val data = loaded
    if (data == null || data.storageError) {
        IosScreen(title = "OpenVPN", onBack = onBack) {
            if (data == null) CircularProgressIndicator(Modifier.padding(32.dp).align(Alignment.CenterHorizontally))
            else SettingsFooter(tr("ذخیره‌سازی امن باز نشد. اطلاعات قبلی بازنویسی نشده است؛ برنامه را دوباره باز کنید.", "Secure storage could not be opened. Existing data was preserved; reopen the app."))
        }
        return
    }
    val account = data.accounts.firstOrNull { it.id == data.activeAccount }
    val profile = data.profiles.firstOrNull { it.id == data.selectedProfile }

    when (page) {
        Page.MAIN -> MainPage(
            data = data, connection = connection, account = account, profile = profile, fa = fa,
            message = message, onDismissMessage = { vm.message.value = null },
            onBack = onBack,
            onDial = {
                when {
                    connection.active -> OpenVpnRuntime.disconnect(context)
                    // A profile that brings its own credentials (or needs none) connects as it is.
                    profile != null && profile.selfContained && (account == null || !com.mlmvpn.scanner.openvpn.ProfileRuntime.isTunnelBear(profile)) ->
                        connect(profile.id, com.mlmvpn.scanner.openvpn.OpenVpnRuntime.SELF)
                    account == null -> { editing = null; page = Page.ACCOUNT_EDIT }
                    profile == null -> page = Page.SERVERS
                    else -> connect(profile.id, account.id)
                }
            },
            onServers = { page = Page.SERVERS },
            onAccounts = { page = Page.ACCOUNTS },
            onGuide = { page = Page.GUIDE },
            onImport = { picker.launch(arrayOf("*/*")) },
        )
        Page.SERVERS -> ServersPage(
            data = data, connection = connection, testing = testing, testTotal = testTotal, fa = fa,
            onBack = { page = Page.MAIN },
            onTestAll = { if (testing.isEmpty()) vm.test(data.profiles) else vm.cancelTests() },
            onPick = { p ->
                vm.change { it.selectProfile(p.id) }
                val a = account
                val already = connection.active && connection.profileId == p.id
                if (!already && p.selfContained && !com.mlmvpn.scanner.openvpn.ProfileRuntime.isTunnelBear(p)) {
                    connect(p.id, com.mlmvpn.scanner.openvpn.OpenVpnRuntime.SELF)
                } else if (a != null && a.usable(System.currentTimeMillis()) && !already) {
                    connect(p.id, a.id)
                }
                page = Page.MAIN
            },
            onFavorite = { p -> vm.change { it.updateProfile(p.id) { old -> old.withFavorite(!old.favorite) } } },
            onFastest = {
                val fastest = ServerPolicy.fastest(data.profiles, System.currentTimeMillis())
                if (fastest == null) vm.message.value = "TEST_FIRST"
                else {
                    vm.change { it.selectProfile(fastest.id) }
                    if (fastest.selfContained && !com.mlmvpn.scanner.openvpn.ProfileRuntime.isTunnelBear(fastest)) connect(fastest.id, com.mlmvpn.scanner.openvpn.OpenVpnRuntime.SELF)
                    else account?.takeIf { it.usable(System.currentTimeMillis()) }?.let { connect(fastest.id, it.id) }
                }
                page = Page.MAIN
            },
        )
        Page.ACCOUNTS -> AccountsPage(
            data = data,
            onBack = { page = Page.MAIN },
            onSelect = { a ->
                if (connection.active && connection.accountId != a.id && connection.profileId != null) connect(connection.profileId!!, a.id)
                vm.change { it.selectAccount(a.id) }
            },
            onEdit = { a -> editing = a?.id; page = Page.ACCOUNT_EDIT },
            onAutoSwitch = { enabled -> vm.change { it.autoSwitch(enabled) } },
            onCreate = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tunnelbear.com/account/signup"))) },
            onGuide = { page = Page.GUIDE },
        )
        Page.ACCOUNT_EDIT -> AccountEditPage(
            account = data.accounts.firstOrNull { it.id == editing },
            locked = editing != null && connection.active && connection.accountId == editing,
            onBack = { page = Page.ACCOUNTS },
            onSave = { user, pass -> val id = editing; vm.change { it.saveAccount(id, user, pass) }; page = Page.ACCOUNTS },
            onDelete = { id -> vm.change { it.removeAccount(id) }; page = Page.ACCOUNTS },
        )
        Page.GUIDE -> GuidePage(
            onBack = { page = Page.MAIN },
            onIran = onIran,
            onStore = onStore,
            onSite = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tunnelbear.com/account"))) },
        )
    }
}

private enum class Page { MAIN, SERVERS, ACCOUNTS, ACCOUNT_EDIT, GUIDE }

private val OpenVpnOrange = Color(0xFFEA7E20)

// ── the main page ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun MainPage(
    data: OpenVpnData,
    connection: OpenVpnConnection,
    account: Account?,
    profile: Profile?,
    fa: Boolean,
    message: String?,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
    onDial: () -> Unit,
    onServers: () -> Unit,
    onAccounts: () -> Unit,
    onGuide: () -> Unit,
    onImport: () -> Unit,
) {
    val running = connection.phase == ConnectionPhase.CONNECTED
    val working = connection.phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING, ConnectionPhase.DISCONNECTING)
    val failed = connection.phase == ConnectionPhase.ERROR

    IosScreen(title = "OpenVPN", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        message?.let { Banner(errorText(it), onDismissMessage) }

        Text(
            tr("سرورهای TunnelBear، مستقیم و بی‌واسطه: هیچ تونل دیگری زیر آن نیست.",
                "TunnelBear's servers, connected directly: no other tunnel underneath."),
            color = Ios.SecondaryLabel, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(18.dp))
        DialCore(
            accent = when { running -> Ios.Green; failed -> Ios.Red; else -> OpenVpnOrange },
            working = working,
            running = running,
            progress = -1,
            label = when {
                running -> tr("قطع", "Disconnect")
                working -> tr("لغو", "Cancel")
                else -> tr("اتصال", "Connect")
            },
            onClick = onDial,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // A plain glyph, no artwork on the dial: the user asked for this here as for «گف».
            Icon(Icons.Default.Power, null, tint = Color.White, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            headline(connection),
            color = when { running -> Ios.Green; failed -> Ios.Red; working -> OpenVpnOrange; else -> Ios.SecondaryLabel },
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        val sub = when {
            failed -> connection.error?.let { errorText(it) }
            working && connection.detail == "VERIFYING" -> tr("بررسی عبور واقعی داده…", "Checking that data really passes…")
            working -> tr("اتصال مستقیم به سرور…", "Connecting straight to the server…")
            else -> null
        }
        if (sub != null) {
            Spacer(Modifier.height(4.dp))
            Text(sub, color = Ios.SecondaryLabel, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp))
        }
        Spacer(Modifier.height(18.dp))

        if (running) LiveGroup(connection, fa)

        SettingsSectionHeader(tr("سرور", "Server"))
        SettingsGroup {
            if (profile != null) {
                ServerRow(profile, fa, selected = false, busy = false, onClick = onServers, onFavorite = null, chevron = true)
            } else {
                SettingsRow(tr("انتخاب سرور", "Choose a server"), Icons.Default.Public, Ios.Blue, onClick = onServers)
            }
        }
        SettingsFooter(tr("کشوری که سایت‌ها می‌بینند. تأخیرها از همان مسیری سنجیده می‌شوند که اتصال از آن می‌رود.",
            "The country sites will see. Delays are measured on the same route the connection takes."))

        SettingsSectionHeader(tr("حساب", "Account"))
        SettingsGroup {
            SettingsRow(
                title = account?.username ?: tr("افزودن حساب TunnelBear", "Add a TunnelBear account"),
                icon = Icons.Default.AccountCircle,
                tint = if (account == null) Ios.Orange else Ios.Blue,
                subtitle = account?.let { accountText(it, System.currentTimeMillis()) },
                onClick = onAccounts,
            )
        }

        SettingsSectionHeader(tr("بیشتر", "More"))
        SettingsGroup {
            SettingsRow(tr("راهنمای ساخت حساب", "How to get an account"), Icons.Default.MenuBook, Ios.Teal, onClick = onGuide)
            Separator()
            SettingsActionRow(tr("افزودن پروفایل (.ovpn)", "Add profiles (.ovpn)"), Icons.Default.Add, Ios.Blue, onClick = onImport)
        }
        SettingsFooter(tr("فایل‌های ovpn و CA وابسته را با هم انتخاب کنید.", "Select .ovpn files and their CA files together."))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun LiveGroup(connection: OpenVpnConnection, fa: Boolean) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var rates by remember { mutableStateOf(0L to 0L) }
    // Only while on screen; the rates are worked out from the counters, so pausing loses nothing.
    com.mlmvpn.scanner.ui.LaunchedWhileVisible(Unit) {
        var lastRx = OpenVpnRuntime.connection.value.received
        var lastTx = OpenVpnRuntime.connection.value.sent
        var lastAt = System.currentTimeMillis()
        while (true) {
            delay(1000)
            val c = OpenVpnRuntime.connection.value
            val t = System.currentTimeMillis()
            val dt = (t - lastAt).coerceAtLeast(1)
            rates = ((c.received - lastRx).coerceAtLeast(0) * 1000 / dt) to ((c.sent - lastTx).coerceAtLeast(0) * 1000 / dt)
            lastRx = c.received; lastTx = c.sent; lastAt = t; now = t
        }
    }
    val exit = connection.exitCountry?.let { GeoLabel.countryFromCode(it, if (fa) Locale("fa") else Locale.ENGLISH) }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Figure(tr("دریافت", "Download"), rate(rates.first))
            Figure(tr("ارسال", "Upload"), rate(rates.second))
            Figure(tr("مدت", "Time"), duration(((now - (connection.connectedAt ?: now)) / 1000).coerceAtLeast(0)))
        }
        Separator()
        SettingsRow(
            title = tr("خروج از", "Exit"),
            value = exit?.let { "${it.flag}  ${it.name}" } ?: "—",
            showChevron = false,
        )
        connection.exitIp?.let {
            Separator()
            SettingsRow(title = tr("آدرسی که سایت‌ها می‌بینند", "Address sites see"), value = it, showChevron = false)
        }
        Separator()
        SettingsRow(
            title = tr("مصرف این اتصال", "This session"),
            value = "↓ ${bytes(connection.received)}   ↑ ${bytes(connection.sent)}",
            showChevron = false,
        )
    }
    SettingsFooter(tr("«متصل» فقط وقتی نشان داده می‌شود که یک درخواست واقعی از تونل رد شده باشد.",
        "\"Connected\" is shown only after a real request has crossed the tunnel."))
}

@Composable
private fun Figure(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Ios.Label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            style = TextStyle(textDirection = TextDirection.Ltr))
        Text(label, color = Ios.SecondaryLabel, fontSize = 12.sp)
    }
}

@Composable
private fun Banner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(Ios.Orange.copy(alpha = 0.16f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Info, null, tint = Ios.Orange, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Ios.Label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Default.Close, tr("بستن", "Close"), tint = Ios.SecondaryLabel,
            modifier = Modifier.size(20.dp).clickable(onClick = onDismiss))
    }
}

// ── servers ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ServersPage(
    data: OpenVpnData,
    connection: OpenVpnConnection,
    testing: Set<String>,
    testTotal: Int,
    fa: Boolean,
    onBack: () -> Unit,
    onTestAll: () -> Unit,
    onPick: (Profile) -> Unit,
    onFavorite: (Profile) -> Unit,
    onFastest: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    IosScreen(title = tr("سرورها", "Servers"), onBack = onBack, backLabel = "OpenVPN") {
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingsActionRow(
                if (testing.isEmpty()) tr("سنجش تأخیر همه", "Measure all") else tr("توقف سنجش", "Stop measuring"),
                Icons.Default.Speed, Ios.Blue, enabled = !connection.active, onClick = onTestAll,
            )
            Separator()
            SettingsActionRow(tr("اتصال به سریع‌ترین", "Connect to the fastest"), Icons.Default.Bolt, Ios.Green,
                enabled = testing.isEmpty(), onClick = onFastest)
        }
        if (testing.isNotEmpty() && testTotal > 0) {
            // How far the measurement has got, as a bar and a count: "12 of 47".
            val done = (testTotal - testing.size).coerceIn(0, testTotal)
            val shown by androidx.compose.animation.core.animateFloatAsState(done.toFloat() / testTotal, label = "ovpn-progress")
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("در حال سنجش…", "Measuring…"), color = Ios.SecondaryLabel, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        if (fa) "${faDigits(done)} از ${faDigits(testTotal)}" else "$done of $testTotal",
                        color = Ios.SecondaryLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ios.Gray.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Ios.Blue))
                }
            }
        }
        SettingsFooter(
            if (connection.active) tr("در حین اتصال سنجش ممکن نیست: از داخل تونل عدد درستی نمی‌دهد.", "Measuring is off while connected: from inside the tunnel it would not be the real figure.")
            else tr("هر سرور مثل خود اتصال سنجیده می‌شود: تا جایی که سرور جواب رمزنگاری (TLS) را واقعاً بفرستد. سروری که فقط جواب اولیه بدهد و بعد قطع شود، «مسدود» نشان داده می‌شود.",
                "Each server is measured the way the connection runs: until it really sends its TLS answer. A server that answers only the first packet and is then cut shows as \"blocked\".")
        )

        SearchBox(search) { search = it }

        val now = System.currentTimeMillis()
        val filtered = data.profiles.filter { p ->
            search.isBlank() || OpenVpnPlaces.of(p, fa).name.contains(search, true) || p.name.contains(search, true)
        }
        fun order(list: List<Profile>) = list.sortedWith(
            compareBy<Profile> { p -> p.probe?.takeIf { it.millis != null && now - it.checkedAt < 3_600_000 }?.millis ?: Long.MAX_VALUE }
                .thenBy { OpenVpnPlaces.of(it, fa).name }
        )
        val favorites = order(filtered.filter { it.favorite })
        val rest = order(filtered.filterNot { it.favorite })
        if (favorites.isNotEmpty()) {
            SettingsSectionHeader(tr("علاقه‌مندی‌ها", "Favourites"))
            SettingsGroup {
                favorites.forEachIndexed { i, p ->
                    if (i > 0) Separator()
                    ServerRow(p, fa, selected = p.id == data.selectedProfile, busy = p.id in testing,
                        onClick = { onPick(p) }, onFavorite = { onFavorite(p) })
                }
            }
        }
        SettingsSectionHeader(tr("همهٔ سرورها", "All servers"))
        if (rest.isEmpty()) SettingsFooter(tr("سروری پیدا نشد.", "No server matches."))
        else SettingsGroup {
            rest.forEachIndexed { i, p ->
                if (i > 0) Separator()
                ServerRow(p, fa, selected = p.id == data.selectedProfile, busy = p.id in testing,
                    onClick = { onPick(p) }, onFavorite = { onFavorite(p) })
            }
        }
        SettingsFooter(tr("زدن روی یک سرور آن را انتخاب و وصل می‌کند.", "Tapping a server selects it and connects."))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ServerRow(
    p: Profile,
    fa: Boolean,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onFavorite: (() -> Unit)?,
    chevron: Boolean = false,
) {
    val place = OpenVpnPlaces.of(p, fa)
    val probe = p.probe
    val fresh = probe != null && System.currentTimeMillis() - probe.checkedAt < 3_600_000
    val millis = probe?.millis
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(place.flag, fontSize = 26.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(place.name, color = Ios.Label, fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            Text(
                if (ProfileRuntime.isTunnelBear(p)) "TunnelBear" else p.remotes.first().host,
                color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1,
            )
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ios.SecondaryLabel)
            fresh && millis != null -> Text(
                "$millis ms", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = when { millis < 350 -> Ios.Green; millis < 800 -> Ios.Orange; else -> Ios.Red },
                style = TextStyle(textDirection = TextDirection.Ltr),
            )
            fresh && probe?.error == "TLS_BLOCKED" -> Text(tr("مسدود", "Blocked"), fontSize = 13.sp, color = Ios.Red)
            fresh -> Text(tr("پاسخ نداد", "No answer"), fontSize = 13.sp, color = Ios.Red)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.CheckCircle, null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
        }
        if (onFavorite != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                if (p.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                tr("علاقه‌مندی", "Favourite"),
                tint = if (p.favorite) Ios.Yellow else Ios.Gray,
                modifier = Modifier.size(24.dp).clickable(onClick = onFavorite),
            )
        }
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.ChevronLeft, null, tint = Ios.Chevron, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(Ios.Gray.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = Ios.SecondaryLabel, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp),
            cursorBrush = SolidColor(Ios.Blue),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(tr("جست‌وجوی کشور", "Search a country"), color = Ios.SecondaryLabel, fontSize = 16.sp)
                    inner()
                }
            },
        )
    }
}

// ── accounts ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun AccountsPage(
    data: OpenVpnData,
    onBack: () -> Unit,
    onSelect: (Account) -> Unit,
    onEdit: (Account?) -> Unit,
    onAutoSwitch: (Boolean) -> Unit,
    onCreate: () -> Unit,
    onGuide: () -> Unit,
) {
    val now = System.currentTimeMillis()
    IosScreen(title = tr("حساب‌ها", "Accounts"), onBack = onBack, backLabel = "OpenVPN") {
        Spacer(Modifier.height(10.dp))
        if (data.accounts.isNotEmpty()) {
            SettingsGroup {
                data.accounts.forEachIndexed { i, a ->
                    if (i > 0) Separator()
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = a.usable(now)) { onSelect(a) }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (a.id == data.activeAccount) Icons.Default.CheckCircle else Icons.Default.AccountCircle, null,
                            tint = if (a.id == data.activeAccount) Ios.Blue else Ios.Gray, modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.username, color = Ios.Label, fontSize = 16.sp, maxLines = 1,
                                style = TextStyle(textDirection = TextDirection.Ltr))
                            Text(accountText(a, now), color = if (a.usable(now)) Ios.SecondaryLabel else Ios.Orange, fontSize = 12.sp)
                        }
                        Icon(Icons.Outlined.Info, tr("ویرایش", "Edit"), tint = Ios.Blue,
                            modifier = Modifier.size(24.dp).clickable { onEdit(a) })
                    }
                }
            }
            SettingsFooter(tr("زدن روی یک حساب آن را انتخاب می‌کند؛ اگر وصل باشید، همان سرور با حساب تازه دوباره وصل می‌شود.",
                "Tapping an account selects it; if connected, the same server reconnects with it."))
        }
        SettingsGroup {
            SettingsActionRow(tr("افزودن حساب", "Add an account"), Icons.Default.PersonAdd, Ios.Blue, onClick = { onEdit(null) })
            Separator()
            SettingsActionRow(tr("ساخت حساب TunnelBear", "Create a TunnelBear account"), Icons.Default.OpenInNew, Ios.Teal, onClick = onCreate)
            Separator()
            SettingsRow(tr("راهنمای ساخت حساب", "How to get an account"), Icons.Default.MenuBook, Ios.Teal, onClick = onGuide)
        }
        SettingsSectionHeader(tr("چند حساب", "Several accounts"))
        SettingsGroup {
            SettingsToggle(
                title = tr("تعویض خودکار حساب", "Switch accounts automatically"),
                checked = data.autoSwitch,
                onCheckedChange = onAutoSwitch,
                icon = Icons.Default.SwapHoriz,
                tint = Ios.Indigo,
            )
        }
        SettingsFooter(tr("اگر ورود حسابی رد شود یا سهمیه‌اش تمام شود، حساب دیگری امتحان می‌شود.",
            "If an account's sign-in is refused or its quota runs out, another one is tried."))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AccountEditPage(
    account: Account?,
    locked: Boolean,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    // No screenshots or recents thumbnail while a password is on screen.
    DisposableEffect(Unit) {
        val window = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var username by remember(account?.id) { mutableStateOf(account?.username.orEmpty()) }
    // Never rememberSaveable: a password must not enter saved state or backups.
    var password by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    IosScreen(title = if (account == null) tr("حساب تازه", "New account") else tr("ویرایش حساب", "Edit account"),
        onBack = onBack, backLabel = tr("حساب‌ها", "Accounts")) {
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            Field(tr("ایمیل", "Email"), username, { username = it }, KeyboardType.Email, secret = false)
            Separator()
            Field(tr("رمز", "Password"), password, { password = it }, KeyboardType.Password, secret = true)
        }
        SettingsFooter(
            if (account != null) tr("برای نگه داشتن رمز قبلی، آن را دوباره وارد کنید. رمز فقط رمزگذاری‌شده روی همین گوشی می‌ماند.",
                "Enter the password again to keep it. It stays encrypted on this phone only.")
            else tr("همان ایمیل و رمزی که در سایت TunnelBear ساخته‌اید. رمز فقط رمزگذاری‌شده روی همین گوشی می‌ماند.",
                "The email and password of your TunnelBear account. The password stays encrypted on this phone only.")
        )
        SettingsGroup {
            SettingsActionRow(tr("ذخیره", "Save"), Icons.Default.CheckCircle, Ios.Blue,
                enabled = !locked && username.isNotBlank() && password.isNotEmpty(),
                onClick = { onSave(username.trim(), password); password = "" })
            if (account != null) {
                Separator()
                SettingsActionRow(
                    if (confirmDelete) tr("برای حذف دوباره بزنید", "Tap again to delete") else tr("حذف حساب", "Delete account"),
                    Icons.Default.DeleteForever, Ios.Red, labelColor = Ios.Red, enabled = !locked,
                    onClick = { if (confirmDelete) onDelete(account.id) else confirmDelete = true },
                )
            }
        }
        if (locked) SettingsFooter(tr("این حساب الان وصل است؛ برای تغییرش اول قطع کنید.", "This account is connected now; disconnect first to change it."))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType, secret: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ios.Label, fontSize = 16.sp, modifier = Modifier.width(72.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp, textDirection = TextDirection.Ltr),
            cursorBrush = SolidColor(Ios.Blue),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrect = false),
            modifier = Modifier.weight(1f),
        )
    }
}

// ── guide ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun GuidePage(onBack: () -> Unit, onIran: () -> Unit, onStore: () -> Unit, onSite: () -> Unit) {
    val steps = listOf(
        tr("یکی از کانفیگ‌های «کانفیگ ایران» را وصل کنید تا سایت TunnelBear باز شود.", "Connect one of the «Iran config» configs so TunnelBear's site opens."),
        tr("در سایت TunnelBear حساب بسازید یا وارد شوید و ایمیل را تأیید کنید.", "Create or sign in to a TunnelBear account and confirm the email."),
        tr("به اینجا برگردید و ایمیل و رمز را در «حساب» وارد کنید.", "Come back and enter the email and password under «Account»."),
        tr("در «سرور» تأخیر همه را بسنجید و سریع‌ترین را بزنید.", "Under «Server», measure all and pick the fastest."),
    )
    val digits = if (tr("fa", "en") == "fa") "۱۲۳۴" else "1234"
    IosScreen(title = tr("راهنما", "Guide"), onBack = onBack, backLabel = "OpenVPN") {
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            steps.forEachIndexed { i, s ->
                if (i > 0) Separator()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(OpenVpnOrange), contentAlignment = Alignment.Center) {
                        Text(digits[i].toString(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(s, color = Ios.Label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        SettingsFooter(tr("سهمیه و شرایط هر حساب را خود TunnelBear تعیین می‌کند. اندروید فقط یک VPN را هم‌زمان نگه می‌دارد؛ اتصال OpenVPN اتصال قبلی را جایگزین می‌کند.",
            "TunnelBear sets each account's quota and terms. Android keeps one VPN at a time; connecting OpenVPN replaces the previous one."))
        SettingsGroup {
            SettingsRow(tr("باز کردن «کانفیگ ایران»", "Open «Iran config»"), Icons.Default.Flag, Ios.Green, onClick = onIran)
            Separator()
            SettingsActionRow(tr("صفحهٔ حساب TunnelBear", "TunnelBear account page"), Icons.Default.OpenInNew, Ios.Teal, onClick = onSite)
            Separator()
            SettingsRow(tr("به‌روزرسانی هسته از استور", "Update the core from the Store"), Icons.Default.SystemUpdate, Ios.Blue, onClick = onStore)
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ── words ──────────────────────────────────────────────────────────────────────────────────────

private fun headline(c: OpenVpnConnection): String = when (c.phase) {
    ConnectionPhase.CONNECTED -> tr("متصل", "Connected")
    ConnectionPhase.CONNECTING -> tr("در حال اتصال…", "Connecting…")
    ConnectionPhase.RECONNECTING -> tr("اتصال دوباره…", "Reconnecting…")
    ConnectionPhase.DISCONNECTING -> tr("در حال قطع…", "Disconnecting…")
    ConnectionPhase.ERROR -> tr("وصل نشد", "Could not connect")
    else -> tr("آماده", "Ready")
}

private fun rate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000 -> "%.1f MB/s".format(Locale.US, bytesPerSecond / 1e6)
    bytesPerSecond >= 1_000 -> "%.0f KB/s".format(Locale.US, bytesPerSecond / 1e3)
    else -> "$bytesPerSecond B/s"
}

private fun duration(s: Long): String = if (s >= 3600) "%d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60)
    else "%02d:%02d".format(Locale.US, s / 60, s % 60)

private fun bytes(n: Long) = if (n >= 1_000_000_000) "%.2f GB".format(Locale.US, n / 1e9) else "%.1f MB".format(Locale.US, n / 1e6)

private fun accountText(a: Account, now: Long) = when (a.state(now)) {
    AccountState.DEPLETED -> tr("سهمیه تمام شده", "Quota used up")
    AccountState.AUTH_FAILED -> tr("ورود رد شد؛ ایمیل و رمز را بررسی کنید", "Sign-in refused; check the email and password")
    AccountState.VERIFICATION_REQUIRED -> tr("تأیید ایمیل لازم است", "Email confirmation needed")
    AccountState.UNAVAILABLE -> tr("موقتاً در دسترس نیست", "Temporarily unavailable")
    AccountState.QUOTA_LOW -> tr("سهمیه کم", "Quota low")
    AccountState.USAGE_UNAVAILABLE, AccountState.READY -> tr("آماده", "Ready")
}

private fun errorText(code: String): String = when {
    code.startsWith("IMPORTED:") -> tr("پروفایل تازه: ", "New profiles: ") + code.removePrefix("IMPORTED:")
    code == "TEST_FIRST" -> tr("اول تأخیر سرورها را بسنجید؛ نتیجه‌ها باید مال ده دقیقهٔ اخیر باشند.", "Measure the servers first; results must be from the last ten minutes.")
    code == "AUTH_FAILED" -> tr("ورود رد شد. ایمیل، رمز و تأیید ایمیل حساب را در سایت TunnelBear بررسی کنید.", "Sign-in refused. Check the email, password and email confirmation on TunnelBear's site.")
    code == "VPN_PERMISSION_DENIED" -> tr("اجازهٔ VPN داده نشد؛ اتصال قبلی دست نخورد.", "VPN permission was not given; the previous connection was left alone.")
    code == "NO_RESPONSE" -> tr("پاسخ نداد", "No answer")
    code == "NO_DATA" -> tr("دست‌دادن کامل شد ولی داده‌ای از تونل رد نشد؛ سرور دیگری را امتحان کنید.", "The handshake finished but no data crossed the tunnel; try another server.")
    code == "SAVE_FAILED" -> tr("ذخیره نشد؛ اگر این حساب وصل است، اول قطع کنید.", "Could not save; if this account is connected, disconnect first.")
    code.startsWith("IMPORT_FAILED") -> tr("فایل خوانده نشد: ", "Could not read the file: ") + code.removePrefix("IMPORT_FAILED").removePrefix(":")
    code == "PROFILE_UNSUPPORTED" -> tr("هسته این پروفایل را پشتیبانی نمی‌کند.", "The core does not support this profile.")
    code == "CORE_UNAVAILABLE" -> tr("هستهٔ OpenVPN بارگذاری نشد؛ استور را بررسی کنید.", "The OpenVPN core could not load; check the Store.")
    code == "CONNECT_DEADLINE" -> tr("این سرور در زمان مشخص وصل نشد و اتصال لغو شد. سرور دیگری را امتحان کنید.", "This server did not connect in time, so the attempt was stopped. Try another server.")
    code == "CONNECTION_TIMEOUT" -> tr("سرور جواب داد ولی دست‌دادن کامل نشد. سرور دیگری را امتحان کنید.", "The server answered but the handshake did not finish. Try another server.")
    code == "ACCOUNT_UNAVAILABLE" -> tr("این حساب الان قابل استفاده نیست.", "This account cannot be used right now.")
    else -> tr("وصل نشد: ", "Could not connect: ") + code
}

/** Persian digits for a count shown in Persian text. */
private fun faDigits(n: Int): String = n.toString().map { c -> if (c in '0'..'9') '۰' + (c - '0') else c }.joinToString("")
