package com.mlmvpn.scanner.ui.mae

import androidx.compose.foundation.clickable
import com.mlmvpn.scanner.store.tr
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.mlmvpn.scanner.engines.mae.egress.MaeEgressDeployer
import com.mlmvpn.scanner.ui.collectWhileVisible
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.mae.MaeEngine
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.engines.mae.model.Tri
import com.mlmvpn.scanner.engines.mae.route.FragmentRoute
import com.mlmvpn.scanner.engines.mae.route.ServerlessRoute
import com.mlmvpn.scanner.engines.mae.route.UserConfigRoute
import com.mlmvpn.scanner.engines.mae.store.MaeState
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Registry apps installed on this phone (or with no app at all), worked out off the main thread. */
@Composable
private fun rememberInstalledRegistryIds(): Set<String> {
    val context = LocalContext.current
    val ids by produceState(emptySet<String>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            MaeEngine.registry.services.filter { s ->
                s.packages.isEmpty() || s.packages.any { p -> runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false) }
            }.map { it.id }.toSet()
        }
    }
    return ids
}

/** "Which apps and sites do you use most?" -- installed ones first and highlighted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MaeOnboarding(
    onBack: () -> Unit,
    onDone: (apps: List<String>, sites: List<String>) -> Unit,
    onAddApp: (apps: List<String>, sites: List<String>) -> Unit,
) {
    val context = LocalContext.current
    val all = remember { MaeEngine.registry.services }
    val installed = rememberInstalledRegistryIds()
    // Everything installed starts picked; the user's own taps win once they make any.
    var touched by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val picked = if (touched) chosen.toSet() else installed
    var sites by rememberSaveable { mutableStateOf(listOf<String>()) }
    var site by rememberSaveable { mutableStateOf("") }
    var siteError by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.mae_add_site_invalid)

    IosScreen(title = stringResource(R.string.mae_title), onBack = onBack, backLabel = stringResource(R.string.home)) {
        Text(stringResource(R.string.mae_onboard_title), color = Ios.Label, fontSize = 22.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))
        Text(stringResource(R.string.mae_onboard_subtitle), color = Ios.SecondaryLabel, fontSize = 14.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))

        val onPhone = all.filter { it.id in installed }
        if (onPhone.isNotEmpty()) {
            SettingsSectionHeader(stringResource(R.string.mae_onboard_installed))
            FlowRow(Modifier.padding(horizontal = 12.dp)) {
                onPhone.forEach { s ->
                    ServiceChip(serviceName(context, s), s.id in picked, true, s) {
                        chosen = (if (s.id in picked) picked - s.id else picked + s.id).toList()
                        touched = true
                    }
                }
            }
        }
        // Apps not on this phone are not offered: the user asked for what they actually use.
        if (sites.isNotEmpty()) {
            FlowRow(Modifier.padding(horizontal = 12.dp)) {
                sites.forEach { d -> ServiceChip(d, true, false) { sites = sites - d } }
            }
        }
        SettingsGroup(modifier = Modifier.padding(top = 16.dp)) {
            SettingsActionRow(label = stringResource(R.string.mae_add_app), icon = Icons.Default.Apps, tint = Ios.Blue) {
                // Whatever is picked so far is kept; the picker then adds to it.
                onAddApp(picked.toList(), sites)
            }
        }

        SettingsSectionHeader(stringResource(R.string.mae_add_site))
        SettingsGroup {
            SettingsTextRow(title = stringResource(R.string.mae_add_site), value = site,
                onValueChange = { site = it; siteError = null }, placeholder = stringResource(R.string.mae_add_site_hint), error = siteError)
            Separator()
            SettingsActionRow(label = stringResource(R.string.mae_add), icon = Icons.Default.Add, tint = Ios.Blue) {
                val def = com.mlmvpn.scanner.engines.mae.registry.ServiceRegistry.customSite(site)
                if (def == null) siteError = invalid else { sites = (sites + def.displayName).distinct(); site = "" }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onDone(picked.toList(), sites) },
            enabled = picked.isNotEmpty() || sites.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp),
        ) {
            Text(stringResource(if (picked.isEmpty() && sites.isEmpty()) R.string.mae_onboard_pick_one else R.string.mae_onboard_continue))
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * A remove that says what goes with it -- everything learned about the app. iOS's own alert: the
 * destructive choice in red, Cancel the safe default.
 */
@Composable
internal fun MaeConfirmRemove(name: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    IosAlert(
        title = stringResource(R.string.mae_remove_confirm_title, name),
        message = body,
        actions = listOf(
            IosAlertAction(stringResource(R.string.mae_cancel), onClick = onDismiss, preferred = true),
            IosAlertAction(stringResource(R.string.mae_remove), onClick = { onDismiss(); onConfirm() }, destructive = true),
        ),
        onDismiss = onDismiss,
    )
}

/**
 * The apps, as iOS lists things you own: one row per app, a chevron, and everything about it on
 * its own page. The row of equal chips that used to sit under each app -- pause, auto, every
 * route and remove, all the same shape -- put a destructive action one mis-tap from a harmless one.
 */
/**
 * An exit abroad the user gets by setting up a method on its own tile, as MAE sees it: [ready] when
 * it is set up, [inUse] when MAE takes it, [status] what it has or what to do, [tile] where that is done.
 */
internal data class SetupExit(
    val title: String,
    val status: String,
    val ready: Boolean,
    val tile: String,
    val inUse: Boolean = ready,
) {
    /** Whether [routeId] is one of this method's exits in MAE. */
    fun owns(routeId: String): Boolean = when (tile) {
        "fenix" -> com.mlmvpn.scanner.engines.fenix.FenixRoute.isFenixRoute(routeId)
        "proton" -> com.mlmvpn.scanner.engines.amnezia.WgExitRoute.isProton(routeId)
        "windscribe" -> com.mlmvpn.scanner.engines.amnezia.WgExitRoute.isWindscribe(routeId)
        "outline" -> com.mlmvpn.scanner.engines.outline.OutlineRoute.isOutlineRoute(routeId)
        else -> false
    }
}

private fun faDigits(n: Int) = com.mlmvpn.scanner.backup.BackupNames.persianDigits(n.toString())

private val notSetUp get() = tr("راه‌اندازی نشده — برای راه‌اندازی بزنید", "Not set up — tap to set it up")

/**
 * FENIX, Proton and Windscribe, in that order: the fixed exits abroad a user gets by setting up the
 * method itself -- and so the ones MAE names when it finds no exit for an app. Reads their stores:
 * call it off the main thread.
 */
internal fun setupExits(context: android.content.Context): List<SetupExit> {
    val fenix = runCatching {
        com.mlmvpn.scanner.engines.fenix.FenixEngine.init(context)
        com.mlmvpn.scanner.engines.fenix.FenixEngine.hasAccounts() to com.mlmvpn.scanner.engines.fenix.FenixEngine.forMae
    }.getOrDefault(false to false)
    val (proton, windscribe) = com.mlmvpn.scanner.engines.amnezia.AmzMae.counts(context)
    val windscribeIn = com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.signedIn(context)
    // FENIX is set up by adding an account, nothing more: MAE takes it by itself (its «استفاده در MAE»
    // on the FENIX page is on unless the user switched it off) -- the user's rule, 2026-10-18: never
    // ask the user to turn FENIX on.
    return listOf(
        SetupExit(context.getString(R.string.fenix_title), when {
            !fenix.first -> notSetUp
            !fenix.second -> tr("در صفحهٔ FENIX، «استفاده در MAE» خاموش شده است", "«Use in MAE» was switched off on the FENIX page")
            else -> tr("آماده · کشور انتخابی و آمریکا", "Ready · the chosen country and the US")
        }, ready = fenix.first, tile = "fenix", inUse = fenix.first && fenix.second),
        SetupExit(tr("پروتون", "Proton"),
            if (proton > 0) tr("${faDigits(proton)} سرور وایرگارد", "$proton WireGuard servers") else notSetUp, proton > 0, "proton"),
        SetupExit(tr("ویندسکرایب", "Windscribe"), when {
            windscribe > 0 -> tr("${faDigits(windscribe)} سرور وایرگارد", "$windscribe WireGuard servers")
            windscribeIn -> tr("وارد شده‌اید؛ یک بار در صفحهٔ ویندسکرایب وصل شوید تا سرورهایش ساخته شود", "Signed in; connect once in Windscribe so its servers are made")
            else -> notSetUp
        }, windscribe > 0, "windscribe"),
    )
}

/** Names the way a sentence lists them: «A، B و C» / "A, B and C". */
internal fun sayList(names: List<String>): String =
    if (names.size < 2) names.joinToString("")
    else names.dropLast(1).joinToString(tr("، ", ", ")) + tr(" و ", " and ") + names.last()

/**
 * The exits abroad an app that refuses Iran can be given -- and the only ones Gemini and Google Flow
 * get, since they need one fixed server abroad: FENIX, Proton, Windscribe and Outline, plus Lantern
 * for the rest. MAE measures which one each app opens on by itself; what is not set up is listed all
 * the same, with a tap to its own tile to set it up.
 */
@Composable
private fun ForeignExitsList(onOpenTile: (String) -> Unit) {
    val context = LocalContext.current
    val rows by produceState(emptyList<SetupExit>()) {
        value = withContext(Dispatchers.IO) {
            val outline = com.mlmvpn.scanner.engines.outline.OutlineMae.servers(context).size
            setupExits(context) + SetupExit(tr("اوتلاین", "Outline"),
                if (outline > 0) tr("${faDigits(outline)} سرور روی این اینترنت جواب داده", "$outline servers answered on this internet")
                else tr("هنوز سروری روی این اینترنت جواب نداده — کلید بگذارید یا بسنجید", "No server answered on this internet yet — add a key or test"),
                outline > 0, "outline")
        }
    }
    if (rows.isEmpty()) return
    SettingsSectionHeader(tr("خروجی‌های خارجی ثابت", "Fixed exits abroad"))
    SettingsGroup {
        rows.forEachIndexed { i, r ->
            if (i > 0) Separator()
            SettingsRow(
                title = r.title,
                subtitle = r.status,
                icon = Icons.Default.Public,
                tint = if (r.inUse) Ios.Green else Ios.Orange,
                value = if (r.inUse) stringResource(R.string.on_2) else null,
                onClick = { onOpenTile(r.tile) },
            )
        }
    }
    SettingsFooter(
        tr(
            "جمنای و گوگل فلو فقط از یک سرور ثابت در خارج باز می‌شوند، نه از ورکر کلادفلر یا لنترن که خروجشان عوض می‌شود. هر کدام از این چهار را که راه بیندازید، MAE برای هر برنامه خودش می‌سنجد کدام بهتر کار می‌کند؛ هرچه بیشتر، احتمال پیدا شدن راه بیشتر.",
            "Gemini and Google Flow open only from one fixed server abroad, not from the Cloudflare Worker or Lantern, whose exit moves. Set up any of these four and MAE measures by itself which works best for each app; the more of them, the likelier a way is found.",
        )
    )
}

@Composable
internal fun MaeManageScreen(onBack: () -> Unit, openPicker: Boolean = false, onOpenTile: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The rows, as the main screen has them: worked out off the main thread and emitted only
    // when a row changes -- the raw state changes many times a second while apps are checked.
    val views by MaeEngine.viewsFlow.collectWhileVisible()
    val worker by remember { MaeEngine.store.state.map { it.worker }.distinctUntilChanged() }.collectAsState(MaeEngine.store.current.worker)
    var site by rememberSaveable { mutableStateOf("") }
    var siteError by remember { mutableStateOf<String?>(null) }
    var exitBusy by remember { mutableStateOf(false) }
    var exitError by remember { mutableStateOf<String?>(null) }
    var askRemoveExit by remember { mutableStateOf(false) }
    val invalid = stringResource(R.string.mae_add_site_invalid)
    var showPicker by rememberSaveable { mutableStateOf(openPicker) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    val installedIds = rememberInstalledRegistryIds()
    val accounts by produceState(emptyList<com.mlmvpn.scanner.models.CloudAccount>(), worker) {
        value = withContext(Dispatchers.IO) { MaeEngine.cloudAccounts() }
    }
    if (showPicker) {
        InstalledAppPicker(onBack = { showPicker = false })
        return
    }

    // The routes a user can pin, named in words (the saved configs by their own names). Built off
    // the main thread, again only when an exit is added or removed.
    val routes by produceState(emptyList<Pair<String, String>>(), worker) {
        value = withContext(Dispatchers.Default) {
            val named = MaeEngine.providers().map { p ->
                p.id to when {
                    p.id == ServerlessRoute.id -> "Serverless"
                    p.id == FragmentRoute.id -> "Fragment"
                    p is UserConfigRoute && !p.id.startsWith(UserConfigRoute.CLOUD_PREFIX) -> "${routeName(context, p.id)} · ${p.label}"
                    // Several servers of one method: their countries tell them apart.
                    p is com.mlmvpn.scanner.engines.amnezia.WgExitRoute -> routeName(context, p.id, country = p.country)
                    p is com.mlmvpn.scanner.engines.outline.OutlineRoute -> routeName(context, p.id, country = p.country)
                    else -> routeName(context, p.id)
                }
            }
            // Two routes with one name get a number each, so no row is ambiguous.
            val counts = named.groupingBy { it.second }.eachCount()
            val seen = HashMap<String, Int>()
            named.map { (id, label) ->
                if ((counts[label] ?: 0) > 1) id to "$label ${seen.merge(label, 1, Int::plus)}" else id to label
            }
        }
    }

    // FENIX, Proton and Windscribe: on every app's route list even when MAE has none of their
    // servers, so the user sees what more could carry it and is one tap from setting it up.
    val exits by produceState(emptyList<SetupExit>(), worker) {
        value = withContext(Dispatchers.IO) { setupExits(context) }
    }

    detail?.let { id ->
        val v = views.firstOrNull { it.def.id == id }
        if (v == null) { LaunchedEffect(id) { detail = null }; return }
        androidx.activity.compose.BackHandler { detail = null }
        MaeAppDetail(v, routes, exits, onOpenTile, onBack = { detail = null })
        return
    }

    if (askRemoveExit) {
        IosAlert(
            title = com.mlmvpn.scanner.store.tr("ورکر قدیمی MAE حذف شود؟", "Remove MAE's old Worker?"),
            message = com.mlmvpn.scanner.store.tr(
                "MAE حالا از ورکر مرکزی حسابتان استفاده می‌کند و این ورکر دیگر به کار نمی‌آید. برنامه‌هایی که از آن رد می‌شدند، روی ورکر مرکزی دوباره بررسی می‌شوند.",
                "MAE now uses your account's central Worker, and this one is no longer needed. Apps that went through it are checked again on the central Worker.",
            ),
            actions = listOf(
                IosAlertAction(stringResource(R.string.mae_cancel), onClick = { askRemoveExit = false }, preferred = true),
                IosAlertAction(stringResource(R.string.mae_foreign_exit_remove), destructive = true, onClick = {
                    askRemoveExit = false
                    exitBusy = true
                    scope.launch { MaeEngine.removeEgress(); exitBusy = false }
                }),
            ),
            onDismiss = { askRemoveExit = false },
        )
    }

    IosScreen(title = stringResource(R.string.mae_manage), onBack = onBack, backLabel = stringResource(R.string.mae_short)) {
        SettingsSectionHeader(stringResource(R.string.mae_your_services))
        SettingsGroup {
            views.forEachIndexed { i, v ->
                if (i > 0) Separator()
                androidx.compose.runtime.key(v.def.id) {
                    val name = serviceName(context, v.def)
                    ManageAppRow(
                        v = v,
                        name = name,
                        subtitle = when {
                            v.paused -> stringResource(R.string.mae_phase_paused)
                            v.pinned -> (routes.firstOrNull { it.first == v.routeId }?.second ?: routeName(context, v.routeId)) +
                                " · " + stringResource(R.string.mae_route_manual)
                            else -> stringResource(R.string.mae_route_auto) +
                                (v.routeId?.let { " · " + routeName(context, it, v.family, v.country) } ?: "")
                        },
                        onClick = { detail = v.def.id },
                    )
                }
            }
        }

        // Installed apps MAE knows and the user has not picked: one tap adds, as iOS's
        // "More Controls" list does, with the green plus in front.
        val unpicked = MaeEngine.registry.services.filter { s -> s.id in installedIds && views.none { it.def.id == s.id } }
        if (unpicked.isNotEmpty()) {
            SettingsSectionHeader(stringResource(R.string.mae_onboard_more))
            SettingsGroup {
                unpicked.forEachIndexed { i, s ->
                    if (i > 0) Separator()
                    val name = serviceName(context, s)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { MaeEngine.setSelection(MaeEngine.store.current.selected.map { it.id } + s.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.AddCircle, contentDescription = null, tint = Ios.Green, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        MaeAppIcon(s, name, 30.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(name, color = Ios.Label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        SettingsGroup(modifier = Modifier.padding(top = 20.dp)) {
            SettingsRow(title = stringResource(R.string.mae_add_app), icon = Icons.Default.Apps, tint = Ios.Blue,
                onClick = { showPicker = true })
        }

        SettingsSectionHeader(stringResource(R.string.mae_add_site))
        SettingsGroup {
            SettingsTextRow(title = stringResource(R.string.mae_add_site), value = site,
                onValueChange = { site = it; siteError = null }, placeholder = stringResource(R.string.mae_add_site_hint), error = siteError)
            Separator()
            SettingsActionRow(label = stringResource(R.string.mae_add), icon = Icons.Default.Add, tint = Ios.Blue, labelColor = Ios.Blue,
                enabled = site.isNotBlank()) {
                val typed = site
                when (val id = MaeEngine.addSite(typed)) {
                    null -> siteError = invalid
                    else -> {
                        site = ""
                        // A known app's site selects the app itself: say so, not silently.
                        if (!id.startsWith("site:")) MaeEngine.serviceDef(id)?.let { def ->
                            android.widget.Toast.makeText(context, context.getString(R.string.mae_site_is_app, typed.trim(), serviceName(context, def)),
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // The foreign exit is the account's central Worker -- the one every panel rides. MAE makes no
        // Worker of its own any more; one made before is offered for removal once the central one is up.
        val gwVersion by com.mlmvpn.scanner.engines.gateway.EdgeGateway.version.collectAsState()
        val central by produceState<com.mlmvpn.scanner.engines.gateway.EdgeGateway.Exit?>(null, gwVersion, worker, exitBusy) {
            value = withContext(Dispatchers.IO) { com.mlmvpn.scanner.engines.gateway.EdgeGateway.anyExit(context) }
        }
        SettingsSectionHeader(stringResource(R.string.mae_foreign_exit))
        SettingsGroup {
            val w = worker
            val gw = central
            SettingsRow(
                title = com.mlmvpn.scanner.store.tr("ورکر مرکزی", "Central Worker"),
                icon = Icons.Default.Public,
                tint = Ios.CloudflareOrange,
                subtitle = when {
                    exitBusy -> stringResource(R.string.mae_foreign_exit_working)
                    gw != null -> gw.host
                    w != null -> com.mlmvpn.scanner.store.tr("فعلاً ورکر قدیمی MAE؛ با راه‌اندازی ورکر مرکزی جایگزین می‌شود", "For now MAE's old Worker; replaced once the central Worker is set up")
                    else -> stringResource(R.string.mae_foreign_exit_none)
                },
                value = if (gw != null && !exitBusy) stringResource(R.string.on_2) else null,
                showChevron = false,
            )
            if (gw == null) {
                Separator()
                if (accounts.isEmpty()) {
                    SettingsRow(title = stringResource(R.string.mae_foreign_exit_no_account), showChevron = false)
                } else {
                    SettingsActionRow(
                        label = com.mlmvpn.scanner.store.tr("راه‌اندازی ورکر مرکزی", "Set up the central Worker"),
                        icon = Icons.Default.CloudUpload,
                        tint = Ios.CloudflareOrange, busy = exitBusy,
                    ) {
                        if (exitBusy) return@SettingsActionRow
                        exitBusy = true; exitError = null
                        scope.launch {
                            MaeEngine.deployEgress().onFailure { exitError = it.message ?: it.javaClass.simpleName }
                            exitBusy = false
                        }
                    }
                }
            }
        }
        exitError?.let { SettingsFooter(stringResource(R.string.mae_foreign_exit_failed, it)) }
        SettingsFooter(
            com.mlmvpn.scanner.store.tr(
                "برنامه‌هایی که آی‌پی ایران را نمی‌پذیرند به خروجی خارج از کشور نیاز دارند. MAE از ورکر مرکزی حساب کلادفلر شما استفاده می‌کند (همان ورکری که پنل‌ها رویش هستند) و ورکر جدایی نمی‌سازد. با Proxy IP آن، سایت‌های پشت کلادفلر هم باز می‌شوند. پیش از استفاده، برای هر برنامه جداگانه آزمایش می‌شود.",
                "Apps that refuse an Iranian address need an exit abroad. MAE uses your Cloudflare account's central Worker (the one the panels ride) and makes no Worker of its own. Its Proxy IP opens Cloudflare-hosted sites too. Each app is tested on it before it is used.",
            )
        )
        ForeignExitsList(onOpenTile = onOpenTile)
        if (worker != null && central != null && accounts.isNotEmpty()) {
            // Destructive, alone in its own card and centred, the way iOS sets apart "Delete Account".
            SettingsGroup(modifier = Modifier.padding(top = 18.dp)) {
                DestructiveRow(com.mlmvpn.scanner.store.tr("حذف ورکر قدیمی MAE", "Remove MAE's old Worker"), enabled = !exitBusy) { askRemoveExit = true }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** One app in the manage list: icon, name, where it goes, and a chevron to its own page. */
@Composable
private fun ManageAppRow(v: MaeEngine.ServiceView, name: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.alpha(if (v.paused) 0.45f else 1f)) { MaeAppIcon(v.def, name, 36.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = if (v.paused) Ios.SecondaryLabel else Ios.Label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            Icons.Default.ChevronRight, contentDescription = null, tint = Ios.Chevron,
            modifier = Modifier.size(18.dp).scale(if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f, 1f),
        )
    }
}

/** iOS's destructive button in a card: red, centred, nothing else on the row. */
@Composable
private fun DestructiveRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Ios.Red else Ios.SecondaryLabel, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * One app's own page, the way iOS shows one contact: who it is at the top, then its settings as
 * grouped rows -- its route as a checkmark list, whether MAE carries it as a switch, "Check again"
 * as an action -- and Remove alone at the bottom, red, behind a confirmation.
 */
@Composable
private fun MaeAppDetail(
    v: MaeEngine.ServiceView,
    routes: List<Pair<String, String>>,
    exits: List<SetupExit>,
    onOpenTile: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val name = serviceName(context, v.def)
    val testing by MaeEngine.testingFlow.collectAsState()
    var removing by remember { mutableStateOf(false) }
    if (removing) {
        MaeConfirmRemove(name, stringResource(R.string.mae_remove_confirm_body),
            onConfirm = { MaeEngine.remove(v.def.id); onBack() }, onDismiss = { removing = false })
    }

    IosScreen(title = name, onBack = onBack, backLabel = stringResource(R.string.mae_manage)) {
        Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MaeAppIcon(v.def, name, 72.dp)
            Spacer(Modifier.height(10.dp))
            Text(name, color = Ios.Label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val now = when {
                v.def.id in testing -> stringResource(R.string.mae_phase_testing)
                v.paused -> stringResource(R.string.mae_phase_paused)
                v.routeId != null -> routeName(context, v.routeId, v.family, v.country)
                else -> stringResource(R.string.mae_phase_waiting)
            }
            Text(now, color = Ios.SecondaryLabel, fontSize = 14.sp)
        }

        SettingsGroup(modifier = Modifier.padding(top = 14.dp)) {
            SettingsToggle(
                title = stringResource(R.string.mae_app_enabled),
                checked = !v.paused,
                onCheckedChange = { MaeEngine.setPaused(v.def.id, !it) },
                icon = Icons.Default.PowerSettingsNew,
                tint = Ios.Green,
            )
        }
        SettingsFooter(stringResource(R.string.mae_app_enabled_footer))

        SettingsSectionHeader(stringResource(R.string.mae_route))
        SettingsGroup {
            CheckRow(
                label = stringResource(R.string.mae_route_auto),
                detail = if (!v.pinned) v.routeId?.let { routeName(context, it, v.family, v.country) } else null,
                checked = !v.pinned,
            ) { MaeEngine.pin(v.def.id, null) }
            routes.forEach { (id, label) ->
                Separator()
                CheckRow(label = label, detail = null, checked = v.pinned && v.routeId == id) { MaeEngine.pin(v.def.id, id) }
            }
            // A method MAE has no server of yet is listed all the same, its reason in orange, and a
            // tap opens its tile -- there is nothing to pin until it is set up.
            exits.filter { e -> routes.none { (id, _) -> e.owns(id) } }.forEach { e ->
                Separator()
                SetupRow(
                    label = e.title,
                    detail = if (e.inUse) tr("فعلاً در دسترس MAE نیست — برای بررسی بزنید", "Not available to MAE right now — tap to check")
                        else e.status,
                ) { onOpenTile(e.tile) }
            }
        }
        SettingsFooter(stringResource(R.string.mae_route_footer))

        SettingsGroup(modifier = Modifier.padding(top = 18.dp)) {
            SettingsActionRow(
                label = stringResource(R.string.mae_recheck),
                icon = Icons.Default.Refresh,
                tint = Ios.Blue,
                labelColor = Ios.Blue,
                busy = v.def.id in testing,
                enabled = !v.paused,
            ) { MaeEngine.recheck(v.def.id) }
        }

        SettingsGroup(modifier = Modifier.padding(top = 28.dp)) {
            DestructiveRow(stringResource(R.string.mae_remove_app)) { removing = true }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** A choice in a checkmark list: the label, a detail under it, and the blue check when chosen. */
@Composable
private fun CheckRow(label: String, detail: String?, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Ios.Label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checked) Icon(Icons.Default.Check, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
    }
}

/** A method MAE could use once it is set up: its name, in orange why not yet, a chevron to its tile. */
@Composable
private fun SetupRow(label: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Ios.Label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = Ios.Orange, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            Icons.Default.ChevronRight, contentDescription = null, tint = Ios.Chevron,
            modifier = Modifier.size(18.dp).scale(if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f, 1f),
        )
    }
}

/** Developer view: everything MAE believes, and why it chose what it chose. */
@Composable
internal fun MaeDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // At most twice a second: the whole state is read here, and it changes many times a second
    // while apps are checked. A StateFlow keeps only the newest value while the collector waits.
    val state by remember { MaeEngine.store.state.transform { emit(it); delay(500) } }.collectAsState(MaeEngine.store.current)
    val net = remember { MaeEngine.currentNet() }
    val now = System.currentTimeMillis()

    IosScreen(title = stringResource(R.string.mae_diagnostics), onBack = onBack, backLabel = stringResource(R.string.mae_short)) {
        SettingsGroup(modifier = Modifier.padding(top = 16.dp)) {
            SettingsRow(title = stringResource(R.string.mae_diag_network), value = net, showChevron = false)
            Separator()
            SettingsRow(
                title = stringResource(R.string.mae_diag_live_api),
                value = stringResource(when (state.liveApiWorks) {
                    null -> R.string.mae_diag_live_api_untested
                    true -> R.string.mae_diag_live_api_on
                    false -> R.string.mae_diag_live_api_off
                }),
                showChevron = false,
            )
        }
        SettingsFooter(listOf(
            "WARP: ${if (state.warp != null) "tunnel" else "—"}${if (state.warpProbe != null) " + probe" else ""}",
            "cloud configs: ${state.cloudLinks.size}",
            "live API failures: ${state.liveApiFails}",
        ).joinToString(" · "))
        state.selected.forEach { sel ->
            val def = MaeEngine.serviceDef(sel.id) ?: return@forEach
            val key = MaeState.sk(def.id, net)
            val d = state.diagnoses[key]
            val p = state.policies[key]
            SettingsSectionHeader(serviceName(context, def))
            SettingsGroup {
                SettingsRow(title = stringResource(R.string.mae_diag_primary), value = d?.primary?.name ?: "—", showChevron = false)
                Separator()
                SettingsRow(title = stringResource(R.string.mae_route), value = (p?.routeId ?: "—") + (p?.let { " · ${it.family} · ${"%.2f".format(it.confidence)}" } ?: ""), showChevron = false)
                Separator()
                SettingsActionRow(label = stringResource(R.string.mae_diag_retest), icon = Icons.Default.Refresh, tint = Ios.Blue) {
                    MaeEngine.enqueue(def.id, incident = true)
                }
            }
            val lines = buildList {
                p?.let { add("${context.getString(R.string.mae_diag_why)}: ${it.why}") }
                state.userEvidence[key]?.let { e ->
                    if (e.geo(now)) add("user: country refusal")
                    e.failedRoutes(now).takeIf { it.isNotEmpty() }?.let { add("user: did not open on $it") }
                }
                d?.axes?.forEach { (axis, v) ->
                    if (v.state != Tri.UNKNOWN || v.confidence > 0) add("${axis.name}: ${v.state} ${"%.2f".format(v.confidence)}")
                    v.evidence.forEach { add("   $it") }
                }
                val metrics = state.metrics.filterKeys { it.endsWith("|${def.id}|$net") }
                if (metrics.isNotEmpty()) add(context.getString(R.string.mae_diag_candidates) + ":")
                metrics.forEach { (k, m) ->
                    add("   ${k.substringBefore('|')}: ok ${"%.0f".format(m.successRate * 100)}% (${m.successes}/${m.successes + m.failures})" +
                        (m.rttMs?.let { ", ${it.toInt()} ms" } ?: "") +
                        (m.throughputBps?.let { ", ${"%.1f".format(it / 125_000)} Mbps" } ?: ""))
                }
                val proofs = state.proofs.filterKeys { it.endsWith("|${def.id}|$net") }
                if (proofs.isNotEmpty()) add(context.getString(R.string.mae_diag_proofs) + ":")
                proofs.values.forEach { pr -> add("   ${pr.routeId}: ${if (pr.serviceAccepted) "PROVEN" else "REJECTED"} — ${pr.reason}") }
            }
            if (lines.isNotEmpty()) SettingsFooter(lines.joinToString("\n"))
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * Every app installed on the phone, to add to MAE. Registry apps are simply selected; any other
 * app is added by its main domain (guessed from its package, confirmed by DNS) and learns the
 * rest of its domains by itself.
 */
@Composable
private fun InstalledAppPicker(onBack: () -> Unit) {
    val context = LocalContext.current
    fun chosenOf(s: MaeState) = s.selected.mapNotNull { MaeEngine.serviceDef(it.id) }.flatMap { it.packages }.toSet()
    // The ticks change only when the selection does -- not with every probe result, which used to
    // rebuild the whole list many times a second right after a tap.
    val initial = remember { chosenOf(MaeEngine.store.current) }
    val chosen by remember {
        MaeEngine.store.state.map { chosenOf(it) }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }.collectAsState(initial)
    val (apps, loading) = com.mlmvpn.scanner.ui.settings.rememberInstalledApps(initial)
    val noDomain = stringResource(R.string.mae_add_app_no_domain)
    var confirm by remember { mutableStateOf<Pair<String, ServiceDef>?>(null) }

    confirm?.let { (pkgLabel, def) ->
        val name = serviceName(context, def)
        val body = if (def.packages.size > 1) stringResource(R.string.mae_remove_confirm_part, pkgLabel, name)
            else stringResource(R.string.mae_remove_confirm_body)
        MaeConfirmRemove(name, body, onConfirm = { MaeEngine.remove(def.id) }, onDismiss = { confirm = null })
    }
    // Nothing is applied to the running tunnel while the user picks: every tap used to rebuild
    // it, the VPN key blinked, and the list under the finger jumped. Applied once on the way out.
    androidx.compose.runtime.DisposableEffect(Unit) {
        MaeEngine.holdApply(true)
        onDispose { MaeEngine.holdApply(false) }
    }

    com.mlmvpn.scanner.ui.settings.AppPickerPage(
        apps = apps,
        isLoading = loading,
        selected = chosen,
        backLabel = stringResource(R.string.mae_short),
        onBack = onBack,
        title = stringResource(R.string.mae_add_app),
        onToggle = { pkg ->
            val label = apps.firstOrNull { it.packageName == pkg }?.name ?: pkg
            val existing = MaeEngine.store.current.selected
                .firstNotNullOfOrNull { sel -> MaeEngine.serviceDef(sel.id)?.takeIf { pkg in it.packages } }
            if (MaeEngine.detachPackage(pkg)) {
                // An app attached to another (Google Maps under Google): just untied.
            } else if (existing != null) {
                // Unticking removes the app and all it learned -- and for an app with several
                // packages (YouTube Music is YouTube) the whole app: asked first.
                confirm = label to existing
            } else if (MaeEngine.addInstalledApp(pkg, label) is MaeEngine.AddApp.NoDomain) {
                android.widget.Toast.makeText(context, noDomain.format(label), android.widget.Toast.LENGTH_LONG).show()
            }
        },
    )
}
