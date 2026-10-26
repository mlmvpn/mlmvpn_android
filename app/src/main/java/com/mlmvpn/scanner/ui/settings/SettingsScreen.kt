package com.mlmvpn.scanner.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.ui.home.Appearance
import com.mlmvpn.scanner.ui.home.CustomWallpaper
import com.mlmvpn.scanner.ui.home.HomeLayoutStore
import com.mlmvpn.scanner.ui.home.AppIconCell
import com.mlmvpn.scanner.ui.home.HomeDestinations
import com.mlmvpn.scanner.ui.home.HomeWallpaper
import com.mlmvpn.scanner.ui.home.WallpaperPreset
import com.mlmvpn.scanner.ui.home.Wallpapers
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.utils.AppLocaleManager
import com.mlmvpn.scanner.utils.LocalPort
import com.mlmvpn.scanner.utils.NetworkSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mlmvpn.scanner.utils.S

/** Routes inside Settings. A page is pushed; back pops it. */
private const val P_LANGUAGE = "language"
private const val P_TIMEOUT = "timeout"
private const val P_DNS = "dns"
private const val P_PORT = "port"
private const val P_PROXY_MODE = "proxy_mode_page"
private const val P_ALLOW_LAN = "allow_lan_page"
private const val P_UPDATE = "software_update"
private const val P_UPDATE_CHECK = "software_update_check"
private const val P_DISPLAY = "display"
private const val P_WALLPAPER = "wallpaper"
private const val P_WALLPAPER_PICK = "wallpaper_pick"
private const val P_THEME = "theme"
/** The Cloudflare resources hub, reached from the row under the account card. */
private const val P_CF_RESOURCES = "cf_resources"

// Two screens that used to be reached by LEAVING settings for a tab of their own. On a phone
// that is invisible -- one full screen replaces another either way -- but in two panes it
// meant the sidebar vanished the moment either was opened, and the user was dropped somewhere
// that no longer looked like Settings at all. Given routes here, they open in the detail pane
// like every other page, and the list stays where it is.
private const val P_VPN_ADVANCED = "vpn_advanced"
private const val P_USAGE = "usage_page"

// About came in from the outside too, and further out than those two: it was a tile on the home
// grid and a tab of its own. Everything it answers -- who made this, where to reach them, what
// changed -- is what the About group at the bottom of this list is already for, so it is the last
// row of that group, and the changelog under it is a route like any other rather than a flag held
// inside the screen. That is what puts both in the detail pane on a tablet or a television.
private const val P_ABOUT = "about_page"
private const val P_ABOUT_CHANGELOG = "about_changelog_page"

/**
 * The Cloudflare resources page, addressable from outside this screen.
 *
 * The settings screen owns a private route stack, which is why every other page here is a private
 * constant. This one has a second, legitimate entrance -- the Cloud tab, where the accounts it
 * describes are managed -- so its name is public and [SettingsScreen] accepts it as [openRoute].
 */
const val ROUTE_CF_RESOURCES = P_CF_RESOURCES

/**
 * The proxy-mode page, addressable from outside this screen.
 *
 * Public for the same reason as [ROUTE_CF_RESOURCES]: the Local Network screen sends users here
 * when the running transport takes the tun and therefore publishes no proxy to share. That is the
 * one setting which fixes it, and making the user find it themselves is what the LAN screen
 * exists to stop.
 */
const val ROUTE_PROXY_MODE = P_PROXY_MODE

/**
 * The download-and-install page, addressable from outside this screen.
 *
 * The "a new version is out" notice no longer downloads anything itself. It is a notice, and the
 * one place an update is installed from is this page -- so a user who has just been told about a
 * version is sent here rather than being handed a second, parallel downloader that knew nothing
 * about the Wi-Fi setting, the retry, or whether the file was already on disk.
 */
const val ROUTE_UPDATE_DOWNLOAD = P_UPDATE_CHECK
/**
 * One resource list: `cf_list:<accountIndex>:<WORKERS|D1|KV>`.
 *
 * The parameters ride in the route rather than in a separate piece of state so that the back
 * stack stays a plain list of strings -- popping one is `removeLast()`, with nothing else to
 * unwind alongside it.
 */
private const val P_CF_LIST_PREFIX = "cf_list:"

/**
 * Settings, laid out the way iOS lays out Settings: inset grouped cards of rows, each row led by
 * a tinted glyph, and every choice on a page of its own.
 *
 * Two rules from iOS are load-bearing rather than cosmetic:
 *
 * **Nothing opens a dropdown in place.** A choice pushes a page. That is what frees the row to
 * show the current value in grey on its trailing edge, so "what is this set to" is answered
 * without opening anything.
 *
 * **Every change applies immediately.** The old screen had Cancel/Save at the bottom, which made
 * a screen whose changes had been saved and one whose changes had not look identical. The one
 * thing that cannot apply on every keystroke is the local port -- every prefix of a valid port is
 * itself a number -- so that one writes only while it validates, and says why when it does not.
 */
@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenUsage: () -> Unit,
    /** A page to open on arrival, when something outside settings navigated here for it. */
    openRoute: String? = null,
    onRouteOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }
    val defaultPrefs = remember { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }

    // Read from the configuration rather than from a measurement, because the ROWS need it and
    // they are built long before the BoxWithConstraints at the bottom of this function measures
    // anything. Same threshold, same answer -- see the two-pane branch for why 720dp.
    val twoPane = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720

    val stack = remember { mutableStateListOf<String>() }
    fun push(route: String) { stack.add(route) }

    // Arriving with a destination: land on it rather than on the settings root, and clear the
    // request so returning to settings later does not jump there again.
    androidx.compose.runtime.LaunchedEffect(openRoute) {
        val target = openRoute ?: return@LaunchedEffect
        // A page that lives under another arrives with its parent beneath it, so back walks the
        // hierarchy the user would have walked by hand instead of dropping them at the root.
        val path = when (target) {
            P_UPDATE_CHECK -> listOf(P_UPDATE, P_UPDATE_CHECK)
            else -> listOf(target)
        }
        if (stack.toList() != path) {
            stack.clear()
            stack.addAll(path)
        }
        onRouteOpened()
    }
    fun pop() { stack.removeLastOrNull() }
    // Takes priority over AppScreen's handler because it is declared deeper in the tree, so back
    // walks out of a detail page before it leaves Settings altogether.
    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    // Through NetworkSettings, which is the single store every engine reads. Read straight from
    // preferences here, these four showed the raw key's default rather than the app's -- so the
    // screen said LAN access was off while the services, reading the same key with a different
    // fallback, had it on.
    var dnsServer by remember { mutableStateOf(NetworkSettings.backendDns(context)) }
    var localPort by remember { mutableStateOf(LocalPort.getString(context)) }
    var proxyMode by remember { mutableStateOf(NetworkSettings.proxyMode(context)) }
    var allowLan by remember { mutableStateOf(NetworkSettings.allowLan(context)) }
    var tlsUnfilter by remember { mutableStateOf(NetworkSettings.tlsUnfilter(context)) }
    var googleFix by remember { mutableStateOf(NetworkSettings.googleFix(context)) }
    var showRealtimeTraffic by remember { mutableStateOf(defaultPrefs.getBoolean("show_realtime_traffic", true)) }
    var enableUsageTracking by remember { mutableStateOf(defaultPrefs.getBoolean("enable_usage_tracking", true)) }
    var screenOffTimeout by remember { mutableStateOf(defaultPrefs.getString("screen_off_timeout", "0") ?: "0") }
    var appLanguage by remember { mutableStateOf(AppLocaleManager.currentLanguage.value) }

    val languageOptions = listOf(
        IosOption("auto", stringResource(R.string.settings_lang_auto)),
        IosOption("zh", stringResource(R.string.settings_lang_zh)),
        IosOption("fa", stringResource(R.string.settings_lang_fa)),
        IosOption("en", stringResource(R.string.settings_lang_en)),
    )
    val themeOptions = listOf(
        IosOption(Appearance.THEME_AUTO, stringResource(R.string.settings_theme_auto)),
        IosOption(Appearance.THEME_LIGHT, stringResource(R.string.settings_theme_light)),
        IosOption(Appearance.THEME_DARK, stringResource(R.string.settings_theme_dark)),
    )
    val timeoutOptions = listOf(
        IosOption("0", stringResource(R.string.settings_timeout_0)),
        IosOption("1", stringResource(R.string.settings_timeout_1)),
        IosOption("5", stringResource(R.string.settings_timeout_5)),
        IosOption("30", stringResource(R.string.settings_timeout_30)),
        IosOption("60", stringResource(R.string.settings_timeout_60)),
    )

    val cloudManager = remember { CloudManager(context) }
    val accounts by cloudManager.accountsFlow.collectAsState()
    val primary = accounts.firstOrNull()

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun putBool(key: String, value: Boolean) = defaultPrefs.edit().putBoolean(key, value).apply()

    val settingsTitle = stringResource(R.string.settings_title)

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { CustomWallpaper.save(context, uri) }
                if (ok) {
                    Wallpapers.select(context, Wallpapers.CUSTOM_ID)
                } else {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.wallpaper_pick_failed),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    val route = stack.lastOrNull()
    // A resource-list route carries its parameters after the prefix, so the `when` below matches
    // on the bare prefix and the branch reads the rest back off `route` itself.
    val routeKey = if (route != null && route.startsWith(P_CF_LIST_PREFIX)) P_CF_LIST_PREFIX else route

    // Every page this screen can show, addressed by route, as one composable value.
    //
    // A lambda rather than a function because it closes over three dozen pieces of local state --
    // every `var` above is read by some branch below, and threading them through a parameter list
    // would be a worse copy of what the compiler already does here. Naming it is what makes the
    // two-pane layout possible at all: on a wide screen the SAME `when` is rendered twice, once
    // with `null` for the list and once with the selected route for the detail, so the two panes
    // cannot drift apart the way a second hand-written list of rows would.
    val pane: @Composable (String?) -> Unit = { routeKey ->
    when (routeKey) {
        P_LANGUAGE -> IosPickerScreen(
            title = stringResource(R.string.settings_language),
            options = languageOptions,
            selectedKey = appLanguage,
            backLabel = settingsTitle,
            onBack = { pop() },
            onSelect = { key ->
                if (key != appLanguage) {
                    appLanguage = key
                    AppLocaleManager.setLanguage(context, key)
                    // Unwrap the ContextWrapper chain to reach the Activity.
                    var ctx: android.content.Context = context
                    while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) {
                        ctx = ctx.baseContext
                    }
                    (ctx as? android.app.Activity)?.recreate()
                }
            },
        )

        P_VPN_ADVANCED -> VpnSettingsScreen(onDismiss = { pop() })

        P_USAGE -> UsageScreen(onDismiss = { pop() })

        P_ABOUT -> com.mlmvpn.scanner.ui.AboutScreen(
            onBack = { pop() },
            backLabel = settingsTitle,
            onOpenChangelog = { push(P_ABOUT_CHANGELOG) },
        )

        P_ABOUT_CHANGELOG -> com.mlmvpn.scanner.ui.ChangelogScreen(
            onBack = { pop() },
            backLabel = stringResource(R.string.about_title),
        )

        P_TIMEOUT -> IosPickerScreen(
            title = stringResource(R.string.settings_screen_off_timeout),
            options = timeoutOptions,
            selectedKey = screenOffTimeout,
            backLabel = settingsTitle,
            footer = stringResource(R.string.settings_screen_off_timeout_desc),
            onBack = { pop() },
            onSelect = {
                screenOffTimeout = it
                defaultPrefs.edit().putString("screen_off_timeout", it).apply()
            },
        )

        P_DNS -> BackendDnsPage(
            value = dnsServer,
            backLabel = settingsTitle,
            onBack = { pop() },
            onValueChange = {
                dnsServer = it
                NetworkSettings.setBackendDns(context, it)
            },
        )

        P_PORT -> LocalPortPage(
            value = localPort,
            backLabel = settingsTitle,
            onBack = { pop() },
            onValueChange = {
                localPort = it
                if (LocalPort.validate(it) == null) {
                    defaultPrefs.edit().putString(LocalPort.KEY, it.trim()).apply()
                }
            },
        )

        P_PROXY_MODE -> ProxyModePage(
            checked = proxyMode,
            backLabel = settingsTitle,
            onBack = { pop() },
            onCheckedChange = { proxyMode = it; NetworkSettings.setProxyMode(context, it) },
        )

        P_UPDATE -> SoftwareUpdateScreen(
            onDismiss = { pop() },
            backLabel = settingsTitle,
            onOpenCheck = { push(P_UPDATE_CHECK) },
        )

        P_UPDATE_CHECK -> UpdateCheckScreen(
            onBack = { pop() },
            backLabel = stringResource(R.string.settings_software_update),
        )

        P_ALLOW_LAN -> AllowLanPage(
            checked = allowLan,
            backLabel = settingsTitle,
            onBack = { pop() },
            onCheckedChange = { allowLan = it; NetworkSettings.setAllowLan(context, it) },
        )

        P_DISPLAY -> DisplayPage(
            backLabel = settingsTitle,
            onBack = { pop() },
            onOpenWallpaper = { push(P_WALLPAPER) },
            onOpenTheme = { push(P_THEME) },
        )

        P_THEME -> IosPickerScreen(
            title = stringResource(R.string.settings_theme),
            options = themeOptions,
            selectedKey = Appearance.theme,
            backLabel = stringResource(R.string.settings_display),
            footer = stringResource(R.string.settings_theme_desc),
            onBack = { pop() },
            onSelect = { Appearance.setTheme(context, it) },
        )

        P_WALLPAPER -> WallpaperPage(
            backLabel = stringResource(R.string.settings_display),
            onBack = { pop() },
            onChooseImage = { push(P_WALLPAPER_PICK) },
            onPickFromGallery = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
        )

        P_WALLPAPER_PICK -> PresetPickerPage(
            backLabel = stringResource(R.string.settings_wallpaper),
            onBack = { pop() },
        )

        P_CF_RESOURCES -> CloudResourcesHub(
            accounts = accounts,
            backLabel = settingsTitle,
            onBack = { pop() },
            onOpenAccount = onOpenCloud,
            onOpen = { index, resource -> push("$P_CF_LIST_PREFIX$index:${resource.name}") },
        )

        P_CF_LIST_PREFIX -> {
            val parts = (route ?: "").removePrefix(P_CF_LIST_PREFIX).split(":")
            val account = parts.getOrNull(0)?.toIntOrNull()?.let { accounts.getOrNull(it) }
            val resource = parts.getOrNull(1)?.let { name ->
                CloudResource.entries.firstOrNull { it.name == name }
            }
            // An account removed while its list was open leaves the route pointing at nothing.
            // Popping is the honest answer; rendering an empty list would claim the account still
            // exists and has no workers.
            if (account == null || resource == null) {
                LaunchedEffect(Unit) { pop() }
            } else {
                val back = stringResource(R.string.cf_resources_title)
                when (resource) {
                    CloudResource.WORKERS ->
                        WorkersListPage(account = account, backLabel = back, onBack = { pop() })
                    CloudResource.D1 ->
                        D1ListPage(account = account, backLabel = back, onBack = { pop() })
                    CloudResource.KV ->
                        KvListPage(account = account, backLabel = back, onBack = { pop() })
                }
            }
        }

        else -> IosScreen(largeTitle = settingsTitle) {

            CloudflareAccountCard(
                title = primary?.name?.takeIf { it.isNotBlank() }
                    ?: primary?.email?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.settings_cf_none_title),
                subtitle = when {
                    primary == null -> stringResource(R.string.settings_cf_none_subtitle)
                    // The account COUNT moved to the dots row below, so the subtitle can go back
                    // to saying which account this actually is.
                    primary.email.isNotBlank() -> primary.email
                    else -> stringResource(R.string.settings_cf_subtitle)
                },
                onClick = onOpenCloud,
                accountInitials = accounts.map {
                    it.name.takeIf { n -> n.isNotBlank() } ?: it.email
                },
                accountsLabel = stringResource(R.string.settings_cf_accounts, accounts.size),
                onAccountsClick = onOpenCloud,
            )

            // iOS puts "Software Update Available" in a card of its own right under the account,
            // with the same red "1" the Settings icon wears on the home screen.
            val update by com.mlmvpn.scanner.update.UpdateChecker.updateAvailableFlow.collectAsState()
            if (update != null) {
                Spacer(Modifier.height(14.dp))
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.settings_update_available),
                        subtitle = update?.versionName,
                        icon = Icons.Default.SystemUpdate,
                        tint = Ios.Gray,
                        badge = 1,
                        onClick = { push(P_UPDATE) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.cf_resources_row),
                    subtitle = stringResource(R.string.cf_resources_subtitle),
                    icon = Icons.Default.Dns,
                    tint = Ios.CloudflareOrange,
                    onClick = { push(P_CF_RESOURCES) },
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_group_network))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_local_port),
                    icon = Icons.Default.SettingsEthernet,
                    tint = Ios.Blue,
                    value = localPort,
                    onClick = { push(P_PORT) },
                )
                Separator()
                SettingsRow(
                    title = stringResource(R.string.settings_backend_dns),
                    icon = Icons.Default.Dns,
                    tint = Ios.Indigo,
                    value = dnsServer,
                    onClick = { push(P_DNS) },
                )
                Separator()
                // Rows, not switches. Both of these change what the phone does in ways the name
                // alone cannot convey -- one decides whether traffic is tunnelled at all, the
                // other opens an unauthenticated proxy to the network -- and a switch on a list
                // gives the user nowhere to read that before flipping it. Each opens a page that
                // carries the switch AND what it means.
                SettingsToggle(
                    title = stringResource(R.string.settings_tls_unfilter),
                    subtitle = stringResource(R.string.settings_tls_unfilter_desc),
                    checked = tlsUnfilter,
                    onCheckedChange = {
                        tlsUnfilter = it
                        NetworkSettings.setTlsUnfilter(context, it)
                    },
                    icon = Icons.Default.Https,
                    tint = Ios.Purple,
                )
                Separator()
                // A switch rather than a page: it changes how Google is reached and how QUIC is
                // turned away, never whether the tunnel carries anything, and off is exactly the
                // old behaviour.
                SettingsToggle(
                    title = stringResource(R.string.settings_google_fix),
                    subtitle = stringResource(R.string.settings_google_fix_desc),
                    checked = googleFix,
                    onCheckedChange = {
                        googleFix = it
                        NetworkSettings.setGoogleFix(context, it)
                    },
                    icon = Icons.Default.AutoAwesome,
                    tint = Ios.Blue,
                )
                // The half of the fix that needs somewhere to run: only meaningful with it on.
                if (googleFix) {
                    Separator()
                    GeminiExitRow()
                }
                Separator()
                WarpIdRelayRow()
                Separator()
                SettingsRow(
                    title = stringResource(R.string.settings_proxy_mode),
                    subtitle = stringResource(R.string.settings_proxy_mode_desc),
                    icon = Icons.Default.SwapHoriz,
                    tint = Ios.Purple,
                    value = stringResource(if (proxyMode) R.string.on_2 else R.string.off_2),
                    onClick = { push(P_PROXY_MODE) },
                )
                Separator()
                SettingsRow(
                    title = stringResource(R.string.settings_allow_lan),
                    subtitle = stringResource(R.string.settings_allow_lan_desc),
                    icon = Icons.Default.Wifi,
                    tint = Ios.Teal,
                    value = stringResource(if (allowLan) R.string.on_2 else R.string.off_2),
                    onClick = { push(P_ALLOW_LAN) },
                )
                Separator()
                SettingsRow(
                    title = stringResource(R.string.settings_advanced_vpn),
                    icon = Icons.Default.VpnKey,
                    tint = Ios.Green,
                    onClick = if (twoPane) ({ push(P_VPN_ADVANCED) }) else onOpenVpnSettings,
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_display))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_display),
                    icon = Icons.Default.Palette,
                    tint = Ios.Pink,
                    onClick = { push(P_DISPLAY) },
                )
                Separator()
                SettingsToggle(
                    title = stringResource(R.string.settings_realtime_traffic),
                    checked = showRealtimeTraffic,
                    onCheckedChange = {
                        showRealtimeTraffic = it; putBool("show_realtime_traffic", it)
                    },
                    icon = Icons.Default.Insights,
                    tint = Ios.Orange,
                )
                Separator()
                SettingsRow(
                    title = stringResource(R.string.settings_language),
                    icon = Icons.Default.Language,
                    tint = Ios.Blue,
                    value = languageOptions.firstOrNull { it.key == appLanguage }?.label,
                    onClick = { push(P_LANGUAGE) },
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_group_usage))
            SettingsGroup {
                SettingsToggle(
                    title = stringResource(R.string.settings_usage_tracking),
                    subtitle = stringResource(R.string.settings_usage_tracking_desc),
                    checked = enableUsageTracking,
                    onCheckedChange = {
                        enableUsageTracking = it; putBool("enable_usage_tracking", it)
                    },
                    icon = Icons.Default.Timeline,
                    tint = Ios.Yellow,
                )
                Separator()
                SettingsRow(
                    title = stringResource(R.string.settings_total_usage),
                    icon = Icons.Default.BarChart,
                    tint = Ios.Green,
                    onClick = if (twoPane) ({ push(P_USAGE) }) else onOpenUsage,
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_group_system))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_screen_off_timeout),
                    icon = Icons.Default.Timer,
                    tint = Ios.Gray,
                    // No trailing value on this one. Its title is the longest in Settings, and a
                    // value beside it leaves so little room that the title wraps to two lines --
                    // which costs more than knowing the setting at a glance is worth. The value
                    // is on the page it opens.
                    onClick = { push(P_TIMEOUT) },
                )
                Separator()
                SettingsRow(
                    title = stringResource(R.string.settings_reset_home),
                    icon = Icons.Default.Apps,
                    tint = Ios.Red,
                    titleColor = Ios.Destructive,
                    showChevron = false,
                    onClick = {
                        HomeLayoutStore.reset(context)
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_reset_home_done),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_group_about))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_version),
                    icon = Icons.Default.Info,
                    tint = Ios.Gray,
                    value = versionName,
                    showChevron = false,
                )
                Separator()
                // The updater had no home before this: it only ever appeared as a dialog, on its
                // own schedule, when a check happened to succeed. A user who dismissed it once --
                // or whose network blocked GitHub on the single attempt that ran -- had no way to
                // ask again, and no way to turn off the background download.
                SettingsRow(
                    title = stringResource(R.string.settings_software_update),
                    subtitle = stringResource(R.string.settings_software_update_desc),
                    icon = Icons.Default.SystemUpdate,
                    tint = Ios.Blue,
                    badge = if (update != null) 1 else 0,
                    onClick = { push(P_UPDATE) },
                )
                Separator()
                // The app has recorded every crash to files/crashlogs/ for a long time and there
                // was no way for anyone but a developer with a USB cable to read one. That gap is
                // why "the app crashes when I open V2Ray" could be reported by several users and
                // still be unreproducible: the one artefact that would have answered it in a
                // minute was sitting on their phones with no way out.
                val reportCount = remember { com.mlmvpn.scanner.CrashReporter.reportCount() }
                SettingsRow(
                    title = stringResource(R.string.settings_crash_report),
                    subtitle = if (reportCount > 0) {
                        stringResource(R.string.settings_crash_report_count, reportCount)
                    } else {
                        stringResource(R.string.settings_crash_report_none)
                    },
                    icon = Icons.Default.Warning,
                    tint = if (reportCount > 0) Ios.Orange else Ios.Gray,
                    showChevron = reportCount > 0,
                    onClick = {
                        if (reportCount > 0) com.mlmvpn.scanner.CrashReporter.share(context)
                        else android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_crash_report_none),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
                Separator()
                // On unless turned off: a crash of any kind -- Java, native, an ANR -- is sent to the
                // crash collector as it happens. Off, the launch after a crash asks instead.
                var crashAutoSend by remember { mutableStateOf(com.mlmvpn.scanner.CrashReporter.autoSend()) }
                SettingsToggle(
                    title = stringResource(R.string.settings_crash_auto),
                    subtitle = stringResource(R.string.settings_crash_auto_desc),
                    checked = crashAutoSend,
                    onCheckedChange = {
                        crashAutoSend = it
                        com.mlmvpn.scanner.CrashReporter.setAutoSend(it)
                    },
                    icon = Icons.Default.Warning,
                    tint = Ios.Orange,
                )
                Separator()
                // Last in the group, and last on purpose: the three rows above it are things a
                // user comes to Settings to DO -- read the version back to us, install an update,
                // send a crash. This one is reading. It is also the row the changelog hangs off,
                // which is why it sits under the updater rather than above it.
                SettingsRow(
                    title = stringResource(R.string.about_title),
                    subtitle = stringResource(R.string.about_row_subtitle),
                    // Not the Info glyph it carried on the home grid: the version row two above
                    // it is already an Info glyph, and two identical marks in one card is the one
                    // place a user cannot tell the rows apart at a glance.
                    icon = Icons.Default.Groups,
                    tint = Ios.Blue,
                    onClick = { push(P_ABOUT) },
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 720dp, not a device class. A tablet, a television and a DeX window are the same thing
        // to this screen -- enough width to show the list and a page at once -- and a phone
        // turned sideways is not, however wide it is in pixels: 720dp of width still means two
        // real columns rather than two cramped ones.
        val twoPane = maxWidth >= 720.dp

        if (!twoPane) {
            pane(routeKey)
            return@BoxWithConstraints
        }

        // Row, and nothing more, for right-to-left. In Persian the list lands on the right and
        // the open page on the left; in English it is the other way round. That is Compose
        // mirroring a Row under LayoutDirection.Rtl, not a branch on the language -- a branch
        // would be a second place to keep the two in step, and it would be wrong for every
        // right-to-left language the app has not thought about yet.
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.width(380.dp).fillMaxHeight()) {
                pane(null)
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Ios.Separator),
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (routeKey == null) SettingsDetailPlaceholder() else pane(routeKey)
            }
        }
    }
}

/**
 * What fills the detail pane before anything is chosen.
 *
 * An empty half of the screen reads as a rendering fault rather than as a waiting state, and on
 * a television -- where the first thing that happens is a remote moving focus around the list --
 * it is the half the user is looking at.
 */
@Composable
private fun SettingsDetailPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = Ios.SecondaryLabel.copy(alpha = 0.5f),
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.settings_pick_a_row),
                color = Ios.SecondaryLabel,
                fontSize = 15.sp,
            )
        }
    }
}

/**
 * Settings > Display: the wallpaper, how large the home icons are drawn, whether the wallpaper
 * reaches beyond the home screen, and light or dark.
 */
@Composable
private fun DisplayPage(
    backLabel: String,
    onBack: () -> Unit,
    onOpenWallpaper: () -> Unit,
    onOpenTheme: () -> Unit,
) {
    val context = LocalContext.current
    val current = Wallpapers.current
    val name = if (current == Wallpapers.CUSTOM_ID) {
        stringResource(R.string.wallpaper_custom)
    } else {
        presetName(Wallpapers.byId(current))
    }
    val themeLabel = when (Appearance.theme) {
        Appearance.THEME_LIGHT -> stringResource(R.string.settings_theme_light)
        Appearance.THEME_DARK -> stringResource(R.string.settings_theme_dark)
        else -> stringResource(R.string.settings_theme_auto)
    }

    IosScreen(
        title = stringResource(R.string.settings_display),
        onBack = onBack,
        backLabel = backLabel,
    ) {
        Spacer(Modifier.height(14.dp))

        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_wallpaper),
                icon = Icons.Default.Palette,
                tint = Ios.Pink,
                value = name,
                onClick = onOpenWallpaper,
            )
            Separator()
            SettingsRow(
                title = stringResource(R.string.settings_theme),
                icon = Icons.Default.Contrast,
                tint = Ios.Indigo,
                value = themeLabel,
                onClick = onOpenTheme,
            )
        }

        SettingsSectionHeader(stringResource(R.string.settings_icon_size))

        // A live sample rather than a number alone: the slider is percent of whatever size the
        // grid computes for the screen, so the percentage on its own says nothing useful.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .frostedGlass(RoundedCornerShape(22.dp))
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The full cell, tile AND caption, so this one sample previews both size sliders
            // at once -- the icon size above it and the caption size below.
            HomeDestinations.GRID_DEFAULT.take(4).forEach { app ->
                AppIconCell(app = app, tileSize = (56 * Appearance.iconScale / 100).dp)
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsSlider(
            title = stringResource(R.string.settings_icon_size),
            value = Appearance.iconScale,
            valueLabel = S(R.string.str_5, Appearance.iconScale),
            range = Appearance.SCALE_MIN.toFloat()..Appearance.SCALE_MAX.toFloat(),
            onValueChange = { Appearance.setIconScale(context, it) },
        )
        Text(
            stringResource(R.string.settings_icon_size_desc),
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
        )

        SettingsSectionHeader(stringResource(R.string.settings_text_size))
        SettingsGroup {
            SettingsToggle(
                title = stringResource(R.string.settings_text_size_system),
                subtitle = stringResource(R.string.settings_text_size_system_desc),
                checked = Appearance.textScaleAuto,
                onCheckedChange = { Appearance.setTextScaleAuto(context, it) },
                icon = Icons.Default.TextFields,
                tint = Ios.Blue,
            )
        }

        Spacer(Modifier.height(14.dp))

        // The manual slider is only meaningful while the app is NOT following the system, so it
        // is hidden rather than shown disabled: a control that cannot do anything is worse than
        // no control at all.
        if (!Appearance.textScaleAuto) {
        SettingsSlider(
            title = stringResource(R.string.settings_text_size),
            value = Appearance.textScale,
            valueLabel = S(R.string.str_6, Appearance.textScale),
            range = Appearance.SCALE_MIN.toFloat()..Appearance.SCALE_MAX.toFloat(),
            onValueChange = { Appearance.setTextScale(context, it) },
        )
        Text(
            stringResource(R.string.settings_text_size_desc),
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
        )
        }

        Spacer(Modifier.height(14.dp))

        SettingsSectionHeader(stringResource(R.string.settings_label_size))
        SettingsSlider(
            title = stringResource(R.string.settings_label_size),
            value = Appearance.labelScale,
            valueLabel = S(R.string.str_7, Appearance.labelScale),
            range = Appearance.SCALE_MIN.toFloat()..Appearance.SCALE_MAX.toFloat(),
            onValueChange = { Appearance.setLabelScale(context, it) },
        )
        Text(
            stringResource(R.string.settings_label_size_desc),
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
        )

        SettingsSectionHeader(stringResource(R.string.settings_wallpaper))
        SettingsGroup {
            SettingsToggle(
                title = stringResource(R.string.settings_dock_labels),
                subtitle = stringResource(R.string.settings_dock_labels_desc),
                checked = Appearance.dockLabels,
                onCheckedChange = { Appearance.setDockLabels(context, it) },
                icon = Icons.Default.Label,
                tint = Ios.Purple,
            )
            Separator()
            SettingsToggle(
                title = stringResource(R.string.settings_wallpaper_everywhere),
                subtitle = stringResource(R.string.settings_wallpaper_everywhere_desc),
                checked = Appearance.wallpaperEverywhere,
                onCheckedChange = { Appearance.setWallpaperEverywhere(context, it) },
                icon = Icons.Default.Layers,
                tint = Ios.Teal,
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Settings > Display > Wallpaper.
 *
 * Not a wall of thumbnails. The current choice is shown once, full-size, and the ways to change
 * it are two ordinary rows -- which is both what was asked for and what keeps the blur slider
 * useful, because the preview above it is the actual wallpaper at the actual blur.
 */
@Composable
private fun WallpaperPage(
    backLabel: String,
    onBack: () -> Unit,
    onChooseImage: () -> Unit,
    onPickFromGallery: () -> Unit,
) {
    val context = LocalContext.current
    val current = Wallpapers.current
    var blur by remember { mutableStateOf(Wallpapers.blur) }

    IosScreen(
        title = stringResource(R.string.settings_wallpaper),
        onBack = onBack,
        backLabel = backLabel,
    ) {
        Spacer(Modifier.height(14.dp))

        // Live preview: the real renderer at the live blur value, so what is on screen here is
        // exactly what the home screen will show.
        // Capped and centred rather than full-width. The preview is a picture OF A PHONE
        // SCREEN, so its useful size is fixed: filling the width made it grow with the container,
        // and in the detail pane of a tablet that produced a single image roughly a foot tall
        // that pushed every control under it off the screen. 300dp is about the size the home
        // screen it stands for is actually looked at.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .aspectRatio(1.15f)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
            ) {
                HomeWallpaper(wallpaperId = current, blurPercent = blur)
            }
        }

        Spacer(Modifier.height(18.dp))

        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.wallpaper_choose_image),
                icon = Icons.Default.Image,
                tint = Ios.Indigo,
                value = if (current == Wallpapers.CUSTOM_ID) null else presetName(Wallpapers.byId(current)),
                onClick = onChooseImage,
            )
            Separator()
            SettingsRow(
                title = stringResource(R.string.wallpaper_from_gallery),
                icon = Icons.Default.PhotoLibrary,
                tint = Ios.Green,
                value = if (current == Wallpapers.CUSTOM_ID) stringResource(R.string.wallpaper_current) else null,
                onClick = onPickFromGallery,
            )
        }

        Spacer(Modifier.height(18.dp))

        SettingsSlider(
            title = stringResource(R.string.wallpaper_blur),
            value = blur,
            valueLabel = S(R.string.blur, blur),
            onValueChange = {
                blur = it
                Wallpapers.setBlur(context, it)
            },
        )
        Text(
            stringResource(R.string.wallpaper_blur_desc),
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
        )

        Spacer(Modifier.height(28.dp))
    }
}

/** The preset gallery, reached from "Choose Image". Two columns of live-rendered previews. */
@Composable
private fun PresetPickerPage(backLabel: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val current = Wallpapers.current
    val blur = Wallpapers.blur

    IosScreen(
        title = stringResource(R.string.wallpaper_presets),
        onBack = onBack,
        backLabel = backLabel,
    ) {
        Spacer(Modifier.height(14.dp))
        // Capped for the same reason as the preview above: two columns of `weight(1f)` inside a
        // tablet's detail pane made each thumbnail nearly five hundred dp wide, and at 0.62 that
        // is a tile taller than the screen it is previewing.
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        ) {
        // A Column of Rows rather than a LazyVerticalGrid: this page is inside a vertically
        // scrolling Column, and a lazy grid measured with unbounded height crashes.
        Wallpapers.PRESETS.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                pair.forEach { preset ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.62f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    width = if (preset.id == current) 2.5.dp else 1.dp,
                                    color = if (preset.id == current) Ios.Blue
                                    else Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(16.dp),
                                )
                                .clickable { Wallpapers.select(context, preset.id) },
                        ) {
                            HomeWallpaper(wallpaperId = preset.id, blurPercent = blur)
                            if (preset.id == current) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Ios.Blue),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(presetName(preset), color = Ios.Label, fontSize = 12.sp)
                    }
                }
                // Keeps a lone last preset the same width as the others instead of stretching it.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** Preset names come from resources so they follow the app's language setting. */
@Composable
private fun presetName(preset: WallpaperPreset): String {
    val res = when (preset.id) {
        "dusk" -> R.string.wallpaper_name_dusk
        "amber" -> R.string.wallpaper_name_amber
        "indigo" -> R.string.wallpaper_name_indigo
        "charcoal" -> R.string.wallpaper_name_charcoal
        "mist" -> R.string.wallpaper_name_mist
        "violet" -> R.string.wallpaper_name_violet
        "ocean" -> R.string.wallpaper_name_ocean
        "slate" -> R.string.wallpaper_name_slate
        "sunset" -> R.string.wallpaper_name_sunset
        "forest" -> R.string.wallpaper_name_forest
        else -> null
    }
    return if (res != null) stringResource(res) else preset.nameEn
}