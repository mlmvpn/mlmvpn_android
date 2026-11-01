package com.mlmvpn.scanner.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.home.frostedGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val system: Boolean = false,
    /**
     * The icon, already a bitmap at list size -- made off the main thread when the list loads.
     * Converting the Drawable in the row meant a full-size bitmap allocated on the main thread
     * for every visible row on every recomposition: the list stuttered as it scrolled.
     */
    val bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
)

/**
 * Every app worth offering in a split-tunnel list, loaded off the main thread.
 *
 * System packages are dropped unless they have a launcher entry, because the full list is
 * hundreds of entries of which most are platform components no user routes deliberately. The
 * exception is a package that is ALREADY selected: a stored choice must stay visible and
 * removable even if the rule above would now hide it, or the user is left with a count they
 * cannot account for.
 *
 * `keep` carries that already-selected set. It is read once, at load; a package selected during
 * this session is by definition in the list already.
 */
@Composable
internal fun rememberInstalledApps(
    keep: Set<String>,
    /**
     * Every package that can use the network at all (it asks for INTERNET), system ones included,
     * installed apps first — for a routing choice, where a background service such as Play
     * Services is a real target. The split-tunnel lists keep the shorter launcher-only rule.
     */
    everyNetworkApp: Boolean = false,
): Pair<List<AppInfo>, Boolean> {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.Default) {
            val pm = context.packageManager
            val packages = try {
                pm.getInstalledPackages(if (everyNetworkApp) android.content.pm.PackageManager.GET_PERMISSIONS else 0)
            } catch (e: Exception) { emptyList() }
            packages.filter { pkg ->
                val flags = pkg.applicationInfo?.flags ?: 0
                val isSystem = (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (everyNetworkApp) {
                    pkg.packageName != context.packageName &&
                        (pkg.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true ||
                            keep.contains(pkg.packageName))
                } else {
                    !isSystem ||
                        pm.getLaunchIntentForPackage(pkg.packageName) != null ||
                        keep.contains(pkg.packageName)
                }
            }.map { pkg ->
                AppInfo(
                    name = pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
                        ?: pkg.packageName,
                    packageName = pkg.packageName,
                    icon = null,
                    system = ((pkg.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        pm.getLaunchIntentForPackage(pkg.packageName) == null,
                )
            }.sortedWith(compareBy<AppInfo> { if (everyNetworkApp) it.system else false }.thenBy { it.name.lowercase() })
        }
        // Names first — the list is usable (and searchable) at once; the icons, which are most of
        // the cost with every system package in the list, follow in a second pass.
        apps = loaded
        loading = false
        val pm = context.packageManager
        val px = (34 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        apps = withContext(Dispatchers.Default) {
            loaded.map { app ->
                val d = try { pm.getApplicationIcon(app.packageName) } catch (e: Exception) { null }
                app.copy(icon = d, bitmap = d?.let { runCatching { it.toBitmap(px, px).asImageBitmap() }.getOrNull() })
            }
        }
    }
    return apps to loading
}

private const val V_MTU = "mtu"

/** Prefix for the per-slot MTU pages: [MTU_ROUTE] + a Method id, or + [MTU_GLOBAL]. */
private const val MTU_ROUTE = "mtu:"
private const val MTU_GLOBAL = "global"

/** Every route [MTU_ROUTE] can produce, so the `when` can match on membership. */
private val mtuRoutes: Set<String> =
    com.mlmvpn.scanner.utils.NetworkSettings.Method.entries.map { MTU_ROUTE + it.id }.toSet() +
        (MTU_ROUTE + MTU_GLOBAL)

/** The name the user knows a method by -- the same one on its home icon. */
@Composable
private fun mtuMethodLabel(method: com.mlmvpn.scanner.utils.NetworkSettings.Method): String =
    when (method) {
        com.mlmvpn.scanner.utils.NetworkSettings.Method.MASQUE -> stringResource(R.string.home_masque)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.WIREGUARD -> stringResource(R.string.home_wireguard)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.WARP_ON_WARP -> stringResource(R.string.home_warp_on_warp)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.PSIPHON -> stringResource(R.string.home_psiphon)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.TOR -> stringResource(R.string.home_tor)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.CFWARP -> stringResource(R.string.home_warp)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.QUICK_CONNECT -> stringResource(R.string.home_quick)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.GATEWAY -> stringResource(R.string.home_gateway)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.SNI -> stringResource(R.string.home_emergency_3)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.V2RAY -> "V2Ray"
        com.mlmvpn.scanner.utils.NetworkSettings.Method.AMNEZIA -> com.mlmvpn.scanner.store.tr("آمنزیا", "Amnezia")
        com.mlmvpn.scanner.utils.NetworkSettings.Method.PROTON -> "Proton VPN"
        com.mlmvpn.scanner.utils.NetworkSettings.Method.WINDSCRIBE -> com.mlmvpn.scanner.store.tr("وینداسکرایب", "Windscribe")
        com.mlmvpn.scanner.utils.NetworkSettings.Method.OUTLINE -> "Outline"
        com.mlmvpn.scanner.utils.NetworkSettings.Method.FLUX -> "FLUX"
        com.mlmvpn.scanner.utils.NetworkSettings.Method.FENIX -> "FENIX"
        com.mlmvpn.scanner.utils.NetworkSettings.Method.MAE -> "MAE"
        com.mlmvpn.scanner.utils.NetworkSettings.Method.GITHUB -> stringResource(R.string.home_github)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.GST -> stringResource(R.string.home_emergency_2)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.ANTI_SANCTION -> stringResource(R.string.home_antisanction)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.OPENVPN -> stringResource(R.string.home_openvpn)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.GEPH -> stringResource(R.string.home_geph)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.LANTERN -> stringResource(R.string.home_lantern)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.URNETWORK -> stringResource(R.string.home_urnetwork)
        com.mlmvpn.scanner.utils.NetworkSettings.Method.NPV -> stringResource(R.string.home_npv)
    }
private const val V_TILE = "tile"
private const val V_MODE = "mode"
private const val V_APPS = "apps"

/**
 * Settings > Advanced VPN, in the same idiom as the rest of Settings.
 *
 * The old screen put everything on one page behind a Save button, including a full list of every
 * installed app. Split up, each choice gets a page, and the routing mode and app count are
 * readable from the list without opening anything.
 *
 * Like the rest of Settings there is no Save: each control writes as it changes. The previous
 * version applied nothing until Save was pressed AND closed the screen on save, so backing out
 * silently discarded everything the user had just set.
 */
@Composable
fun VpnSettingsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vpn_routing_prefs", Context.MODE_PRIVATE) }
    val defaultPrefs = remember { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }

    val stack = remember { mutableStateListOf<String>() }

    // One box per method, plus the app-wide one, all empty when unset.
    //
    // A single field could not be right: the methods do not share a path, so the number that
    // keeps MASQUE alive wastes 200 bytes a packet on Psiphon. Empty means "use this method's
    // default" -- the box used to seed itself with 1280, which was true on none of them.
    //
    // Declared above pop(), because pop() is what commits whichever one is open.
    val mtuText = remember {
        mutableStateMapOf<String, String>().apply {
            com.mlmvpn.scanner.utils.NetworkSettings.Method.entries.forEach { m ->
                put(m.id, com.mlmvpn.scanner.utils.NetworkSettings.methodMtu(context, m)
                    .let { if (it > 0) it.toString() else "" })
            }
            put(MTU_GLOBAL, com.mlmvpn.scanner.utils.NetworkSettings.globalMtu(context)
                .let { if (it > 0) it.toString() else "" })
        }
    }

    /**
     * Store what is in one MTU box, clamped, and put the stored value back on screen.
     *
     * Committed on the way out rather than on every keystroke: saving as-typed meant a
     * half-finished "1" was written and read back by the next connect as an MTU of 1, and
     * clamping as-typed rewrites the field under the user's fingers. Writing the kept value back
     * into the box is the other half -- the number on screen is then the number that was stored,
     * not the one that was refused.
     */
    fun commitMtu(slot: String) {
        val ns = com.mlmvpn.scanner.utils.NetworkSettings
        val method = com.mlmvpn.scanner.utils.NetworkSettings.Method.byId(slot)
        val typed = mtuText[slot]?.toIntOrNull()
        if (typed == null) {
            if (method != null) ns.clearMethodMtu(context, method) else ns.clearGlobalMtu(context)
            mtuText[slot] = ""
        } else {
            val kept = typed.coerceIn(ns.MIN_MTU, ns.MAX_MTU)
            if (method != null) ns.setMethodMtu(context, method, kept) else ns.setGlobalMtu(context, kept)
            mtuText[slot] = kept.toString()
        }
    }

    // What the auto-measure is doing, if anything. Held per screen rather than per method: only
    // one page is open at a time, and a result from another method on screen would be a result
    // about the wrong line.
    val scope = rememberCoroutineScope()
    var mtuProbing by remember { mutableStateOf(false) }
    var mtuProbeAt by remember { mutableStateOf(0) }
    var mtuProbeResult by remember { mutableStateOf<com.mlmvpn.scanner.utils.MtuProbe.Result?>(null) }

    fun push(route: String) {
        // A result belongs to the page that produced it.
        mtuProbeResult = null
        stack.add(route)
    }

    fun pop() {
        // There are two ways off a sub-page -- the back chevron and the system back gesture --
        // and BackHandler calls pop() directly rather than the screen's own onBack. A commit
        // that lived only in that lambda was therefore skipped by the gesture, which is the way
        // most people leave a screen: the value was typed, shown, and silently dropped.
        // Measured on the device before this line existed. Both routes go through here now.
        stack.lastOrNull()?.removePrefix(MTU_ROUTE)?.takeIf { it != stack.lastOrNull() }
            ?.let { commitMtu(it) }
        stack.removeLastOrNull()
    }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    var routingMode by remember { mutableStateOf(prefs.getString("vpn_routing_mode", "ALL") ?: "ALL") }
    var selectedApps by remember {
        mutableStateOf(prefs.getStringSet("vpn_routing_apps", emptySet())?.toSet() ?: emptySet())
    }
    var tileCount by remember { mutableStateOf(defaultPrefs.getInt("quick_tile_count", 20).toString()) }

    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            val pm = context.packageManager
            val allPackages = try {
                pm.getInstalledPackages(0)
            } catch (e: Exception) {
                emptyList()
            }
            val apps = allPackages.filter { pkgInfo ->
                val flags = pkgInfo.applicationInfo?.flags ?: 0
                val isSystem = (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                !isSystem ||
                    pm.getLaunchIntentForPackage(pkgInfo.packageName) != null ||
                    selectedApps.contains(pkgInfo.packageName)
            }.map { pkgInfo ->
                AppInfo(
                    name = pkgInfo.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
                        ?: pkgInfo.packageName,
                    packageName = pkgInfo.packageName,
                    icon = try {
                        pkgInfo.applicationInfo?.let { pm.getApplicationIcon(it) }
                    } catch (e: Exception) {
                        null
                    },
                )
            }.sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                installedApps = apps
                isLoading = false
            }
        }
    }

    val modeOptions = listOf(
        IosOption("ALL", stringResource(R.string.vpn_mode_all)),
        IosOption("ALLOW", stringResource(R.string.vpn_mode_allow)),
        IosOption("BYPASS", stringResource(R.string.vpn_mode_bypass)),
    )
    val title = stringResource(R.string.vpn_settings_title)

    when (stack.lastOrNull()) {
        V_MTU -> IosScreen(
            title = stringResource(R.string.vpn_mtu_label),
            onBack = { pop() },
            backLabel = title,
        ) {
            Spacer(Modifier.height(14.dp))
            SettingsSectionHeader(stringResource(R.string.vpn_mtu_per_method))
            SettingsGroup {
                com.mlmvpn.scanner.utils.NetworkSettings.Method.entries.forEachIndexed { i, m ->
                    if (i > 0) Separator()
                    // Resolved before the row: stringResource cannot be called from
                    // inside ifEmpty's lambda, which is not composable.
                    val shown = mtuText[m.id].orEmpty()
                    val fallback = stringResource(R.string.vpn_mtu_default_is, m.default)
                    SettingsRow(
                        title = mtuMethodLabel(m),
                        icon = Icons.Default.Tune,
                        tint = Ios.Blue,
                        value = shown.ifEmpty { fallback },
                        onClick = { push(MTU_ROUTE + m.id) },
                    )
                }
            }
            SettingsFooter(stringResource(R.string.vpn_mtu_footer))

            SettingsSectionHeader(stringResource(R.string.vpn_mtu_all_methods))
            val globalAuto = stringResource(R.string.vpn_mtu_auto)
            val globalShown = mtuText[MTU_GLOBAL].orEmpty().ifEmpty { globalAuto }
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.vpn_mtu_global),
                    icon = Icons.Default.Tune,
                    tint = Ios.Gray,
                    value = globalShown,
                    onClick = { push(MTU_ROUTE + MTU_GLOBAL) },
                )
            }
            SettingsFooter(stringResource(R.string.vpn_mtu_global_footer))
            Spacer(Modifier.height(28.dp))
        }

        in mtuRoutes -> {
            val slot = stack.last().removePrefix(MTU_ROUTE)
            val method = com.mlmvpn.scanner.utils.NetworkSettings.Method.byId(slot)
            IosScreen(
                title = if (method != null) mtuMethodLabel(method) else stringResource(R.string.vpn_mtu_global),
                // pop() commits what is typed; see it for why that is not done here.
                onBack = { pop() },
                backLabel = stringResource(R.string.vpn_mtu_label),
            ) {
                Spacer(Modifier.height(14.dp))
                SettingsGroup {
                    SettingsTextRow(
                        title = "MTU",
                        value = mtuText[slot].orEmpty(),
                        onValueChange = { raw -> mtuText[slot] = raw.filter { it.isDigit() }.take(4) },
                        icon = Icons.Default.Tune,
                        tint = Ios.Blue,
                        placeholder = if (method != null) method.default.toString()
                                      else stringResource(R.string.vpn_mtu_auto),
                        numeric = true,
                    )
                }

                if (method != null) {
                    SettingsSectionHeader(stringResource(R.string.vpn_mtu_measure_header))
                    SettingsGroup {
                        SettingsActionRow(
                            label = if (mtuProbing) stringResource(R.string.vpn_mtu_measuring, mtuProbeAt)
                                    else stringResource(R.string.vpn_mtu_measure),
                            icon = Icons.Default.Speed,
                            tint = Ios.Blue,
                            // Colour on this screen means state, so it stays on the glyph. A
                            // blue sentence in a list of black ones reads as a link, not as the
                            // ordinary action it is.
                            labelColor = Ios.Label,
                            busy = mtuProbing,
                            onClick = {
                                mtuProbing = true
                                mtuProbeResult = null
                                scope.launch {
                                    val r = com.mlmvpn.scanner.utils.MtuProbe.measure(method) {
                                        mtuProbeAt = it
                                    }
                                    mtuProbeResult = r
                                    mtuProbing = false
                                }
                            },
                        )
                        val found = mtuProbeResult?.inner
                        if (found != null && !mtuProbing) {
                            Separator()
                            SettingsActionRow(
                                label = stringResource(R.string.vpn_mtu_apply, found),
                                icon = Icons.Default.Check,
                                tint = Ios.Green,
                                labelColor = Ios.Label,
                                onClick = { mtuText[slot] = found.toString(); commitMtu(slot) },
                            )
                        }
                    }
                    val r = mtuProbeResult
                    SettingsFooter(
                        when {
                            mtuProbing -> stringResource(R.string.vpn_mtu_measure_running)
                            r == null -> stringResource(R.string.vpn_mtu_measure_footer)
                            r.localTermination -> stringResource(R.string.vpn_mtu_measure_local)
                            r.fixed -> com.mlmvpn.scanner.store.tr(
                                "URnetwork بسته‌ها را درون قاب‌های خودش می‌برد و اندازه‌شان را خودش با مسیر هماهنگ می‌کند؛ اندازهٔ رابط رسمی آن ${r.inner} است (کمترین اندازه‌ای که IPv6 لازم دارد) و چیزی برای اندازه‌گیری روی این خط نیست. مقدار دستی هم اعمال می‌شود؛ کمتر از ۱۱۰۰ (بزرگ‌ترین بسته‌ای که ارائه‌دهنده می‌فرستد) ۱۱۰۰ به کار می‌رود، و زیر ۱۲۸۰ تونل IPv6 ندارد.",
                                "URnetwork carries packets inside its own frames and sizes them to the path itself; its official interface size is ${r.inner} (the least IPv6 needs), and there is nothing on this line to measure. A manual value applies too; below 1100 (the largest packet a provider sends) 1100 is used, and below 1280 the tunnel has no IPv6.",
                            )
                            r.inner == null -> stringResource(R.string.vpn_mtu_measure_failed)
                            else -> stringResource(
                                R.string.vpn_mtu_measure_result, r.outerPathMtu ?: 0, r.inner, r.probes
                            )
                        }
                    )
                }

                SettingsFooter(
                    if (method != null) stringResource(R.string.vpn_mtu_method_footer, method.default)
                    else stringResource(R.string.vpn_mtu_global_footer)
                )
                Spacer(Modifier.height(28.dp))
            }
        }

        V_TILE -> IosTextScreen(
            title = stringResource(R.string.vpn_quick_tile_count),
            value = tileCount,
            backLabel = title,
            numeric = true,
            placeholder = "20",
            footer = stringResource(R.string.vpn_quick_tile_desc),
            onBack = { pop() },
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(3)
                tileCount = digits
                digits.toIntOrNull()?.let {
                    defaultPrefs.edit().putInt("quick_tile_count", it.coerceIn(1, 200)).apply()
                }
            },
        )

        V_MODE -> IosPickerScreen(
            title = stringResource(R.string.vpn_routing_mode),
            options = modeOptions,
            selectedKey = routingMode,
            backLabel = title,
            onBack = { pop() },
            onSelect = {
                routingMode = it
                prefs.edit().putString("vpn_routing_mode", it).apply()
            },
        )

        V_APPS -> AppPickerPage(
            apps = installedApps,
            isLoading = isLoading,
            selected = selectedApps,
            backLabel = title,
            onBack = { pop() },
            onToggle = { pkg ->
                val next = selectedApps.toMutableSet()
                if (!next.remove(pkg)) next.add(pkg)
                selectedApps = next
                prefs.edit().putStringSet("vpn_routing_apps", next).apply()
            },
        )

        else -> IosScreen(title = title, onBack = onDismiss) {
            Spacer(Modifier.height(14.dp))

            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.vpn_always_on),
                    subtitle = stringResource(R.string.vpn_always_on_desc),
                    icon = Icons.Default.VpnKey,
                    tint = Ios.Green,
                    onClick = {
                        try {
                            val intent = Intent("android.settings.VPN_SETTINGS")
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.vpn_cannot_open),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }

            SettingsSectionHeader(stringResource(R.string.vpn_connection_settings))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.vpn_mtu_label),
                    icon = Icons.Default.Tune,
                    tint = Ios.Blue,
                    value = stringResource(R.string.vpn_mtu_row_value),
                    onClick = { push(V_MTU) },
                )
                Separator()
                SettingsRow(
                    title = stringResource(R.string.vpn_quick_tile_count),
                    icon = Icons.Default.Widgets,
                    tint = Ios.Orange,
                    value = tileCount,
                    onClick = { push(V_TILE) },
                )
            }

            SettingsSectionHeader(stringResource(R.string.vpn_routing_modes))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.vpn_routing_mode),
                    icon = when (routingMode) {
                        "ALLOW" -> Icons.Default.CheckCircle
                        "BYPASS" -> Icons.Default.Block
                        else -> Icons.Default.Public
                    },
                    tint = when (routingMode) {
                        "ALLOW" -> Ios.Green
                        "BYPASS" -> Ios.Red
                        else -> Ios.Indigo
                    },
                    value = modeOptions.firstOrNull { it.key == routingMode }?.label,
                    onClick = { push(V_MODE) },
                )
                // The app list is meaningless in ALL mode, so it is not offered there rather than
                // being offered and quietly ignored.
                if (routingMode != "ALL") {
                    Separator()
                    SettingsRow(
                        title = stringResource(R.string.vpn_selected_apps),
                        icon = Icons.Default.Speed,
                        tint = Ios.Purple,
                        value = stringResource(R.string.vpn_apps_count, selectedApps.size),
                        onClick = { push(V_APPS) },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The per-app list. Scrolls itself with a LazyColumn rather than using IosScreen's scroller,
 * because a list of every installed app is exactly the case a lazy list exists for.
 */
/**
 * The per-app list, shared by both VPN stacks.
 *
 * Internal rather than private because the five-transport tunnel has its own split-tunnel
 * setting with its own storage, and one list of installed apps rendered two ways would drift:
 * the icons, the search field and the system-app rule are the same question whichever service
 * is going to route the traffic.
 */
@Composable
internal fun AppPickerPage(
    apps: List<AppInfo>,
    isLoading: Boolean,
    selected: Set<String>,
    backLabel: String,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    title: String? = null,
) {
    var query by remember { mutableStateOf("") }

    IosScreen(
        title = title ?: stringResource(R.string.vpn_selected_apps),
        onBack = onBack,
        backLabel = backLabel,
        scrollable = false,
    ) {
        // iOS search field: a rounded grey capsule, not an outlined Material box.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .heightIn(min = 36.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = Ios.SecondaryLabel,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(7.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp),
                cursorBrush = SolidColor(Ios.Blue),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.vpn_search_app),
                                color = Ios.SecondaryLabel,
                                fontSize = 16.sp,
                            )
                        }
                        inner()
                    }
                },
            )
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Ios.Blue)
            }
            return@IosScreen
        }

        val filtered = apps.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        ) {
            items(filtered, key = { it.packageName }) { app ->
                val isChecked = selected.contains(app.packageName)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .frostedGlass(RoundedCornerShape(16.dp))
                        .clickable { onToggle(app.packageName) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Made once per app, off the main thread where the loader could, and at the
                    // size it is shown -- never a full-size conversion per frame.
                    val icon = app.bitmap ?: app.icon?.let { d ->
                        val px = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.roundToPx() }
                        remember(app.packageName) { runCatching { d.toBitmap(px, px).asImageBitmap() }.getOrNull() }
                    }
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Ios.Gray.copy(alpha = 0.4f))
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.name, color = Ios.Label, fontSize = 15.sp, maxLines = 1)
                        Text(
                            app.packageName,
                            color = Ios.SecondaryLabel,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                    if (isChecked) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Ios.Blue,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}
