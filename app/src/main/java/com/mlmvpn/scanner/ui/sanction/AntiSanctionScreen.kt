package com.mlmvpn.scanner.ui.sanction

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.engines.sanction.AntiSanctionManager
import com.mlmvpn.scanner.ui.emergency.DialState
import com.mlmvpn.scanner.ui.emergency.EmergencyDial
import com.mlmvpn.scanner.ui.emergency.EmergencyStatusLine
import com.mlmvpn.scanner.ui.faCount
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.ControlShape
import com.mlmvpn.scanner.ui.theme.PanelShape
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The anti-sanction DNS screen.
//
// What this replaces: four hand-built `Card`s stacked in a scroll -- an explanatory paragraph, a
// worker panel, a query form, and all seventy-one routed domains inline -- plus a 120dp circular
// `Button` whose label was blue text on a blue fill. Every action on the page was an outlined or
// tinted button carrying accent-coloured words, which is the one thing this app's screens do not
// do: colour is state here, never decoration.
//
// The domain list is a page now. Seventy-one rows between the query form and the on/off switch
// meant the switch -- the only control most people ever touch -- sat below a screen and a half of
// domains they had never wanted to read.
// =================================================================================================

@Composable
fun AntiSanctionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud = remember { CloudManager(context) }
    val accounts by cloud.accountsFlow.collectAsState()

    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val connectedNode by MyVpnService.connectedNodeIdFlow.collectAsState()
    val isOurs = connectedNode == AntiSanctionManager.NODE_ID
    val isActive = isOurs && phase == MyVpnService.Phase.CONNECTED
    val isConnecting = isOurs && phase == MyVpnService.Phase.CONNECTING
    val didFail = isOurs && phase == MyVpnService.Phase.FAILED

    var domains by remember { mutableStateOf(AntiSanctionManager.getDomains(context)) }
    var deploying by remember { mutableStateOf(false) }
    var deployStatus by remember { mutableStateOf("") }
    var pendingConfig by remember { mutableStateOf<String?>(null) }
    var showDomains by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(AntiSanctionManager.getApps(context)) }
    var exitKind by remember { mutableStateOf(AntiSanctionManager.exitKind(context)) }
    val gatewayHost by com.mlmvpn.scanner.engines.vpngate.VpnGateStore.selectedHostFlow.collectAsState()
    val gatewayPool by com.mlmvpn.scanner.engines.vpngate.VpnGatePool.poolFlow.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.mlmvpn.scanner.engines.vpngate.VpnGateStore.load(context)
        com.mlmvpn.scanner.engines.vpngate.VpnGatePool.load(context)
    }
    val gatewayServer = remember(gatewayHost, gatewayPool) {
        gatewayHost?.let { gatewayPool[it]?.server }
    }
    // deployEdgWorker() mutates the CloudAccount object in place, so the list saveAccounts()
    // pushes into accountsFlow is structurally equal to what's already there (same mutated
    // object references) -- StateFlow's distinctUntilChanged then skips the emission and this
    // screen never recomposes with the new worker URL until it's fully recreated. Track success
    // locally instead of relying purely on the flow round-tripping.
    var justDeployed by remember { mutableStateOf(false) }

    val workerAccounts = accounts.filter { !it.edgWorkerUrl.isNullOrEmpty() && !it.edgUuid.isNullOrEmpty() }
    val hasWorker = workerAccounts.isNotEmpty() || justDeployed

    fun startService(cfg: String) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val isProxyMode = com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(context)
        val localPort = com.mlmvpn.scanner.utils.LocalPort.getString(context)
        val intent = Intent(context, MyVpnService::class.java).apply {
            putExtra("NODE_URI", cfg)
            putExtra("MTU_PROFILE", com.mlmvpn.scanner.utils.NetworkSettings.Method.ANTI_SANCTION.id)
            putExtra("NODE_ID", AntiSanctionManager.NODE_ID)
            putExtra("PROXY_MODE", isProxyMode)
            putExtra("LOCAL_PORT", localPort)
        }
        com.mlmvpn.scanner.utils.SafeStart.service(context, intent)
    }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) pendingConfig?.let { startService(it) }
        pendingConfig = null
    }

    fun turnOn() = com.mlmvpn.scanner.data.ScanGuard.run(
        // A tunnel steals the default route from a running IP scan; the guard asks first.
        com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN
    ) {
        scope.launch {
            // A gateway exit is a whole different tunnel -- SoftEther to a volunteer server --
            // but it runs through the same MyVpnService, so the per-app routing below applies to
            // it unchanged. That is the point: the routing and the exit are separate choices.
            val cfg = if (exitKind == AntiSanctionManager.Exit.GATEWAY) {
                val server = gatewayServer
                if (server == null) {
                    toast(context, S(R.string.pick_a_server_in_mlm_gateway_first))
                    return@launch
                }
                com.mlmvpn.scanner.engines.vpngate.SoftEtherEngine.uriFor(server.ip)
            } else {
                val localPort = com.mlmvpn.scanner.utils.LocalPort.get(context)
                AntiSanctionManager.buildConfig(context, localPort)
            }
            if (cfg == null) {
                toast(context, S(R.string.no_cloudflare_worker_found_create_the_worker))
                return@launch
            }
            pendingConfig = cfg
            val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
            if (prep != null) vpnPrepareLauncher.launch(prep) else { startService(cfg); pendingConfig = null }
        }
    }

    // Once the service reports anything of its own, the local "waiting for permission" flag has
    // done its job -- leaving it set would keep the dial spinning after a failure.
    androidx.compose.runtime.LaunchedEffect(phase) {
        if (phase == MyVpnService.Phase.CONNECTED || phase == MyVpnService.Phase.FAILED) {
            pendingConfig = null
        }
    }

    fun turnOff() {
        val stop = Intent(context, MyVpnService::class.java).apply { action = "STOP" }
        com.mlmvpn.scanner.utils.SafeStart.service(context, stop)
    }

    fun deployWorker() {
        val acct = accounts.firstOrNull()
        if (acct == null) {
            toast(context, S(R.string.add_a_cloudflare_account_in_the_cloud))
            return
        }
        deploying = true
        deployStatus = S(R.string.creating_the_worker_on_your_cloudflare_account)
        scope.launch {
            val (ok, msg) = cloud.deployEdgWorker(acct) { _, label -> deployStatus = label }
            deploying = false
            deployStatus = ""
            if (ok) {
                justDeployed = true
                toast(context, S(R.string.worker_created))
            } else {
                android.widget.Toast.makeText(
                    context, S(R.string.could_not_create_the_worker_msg, msg), android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    if (showApps) {
        androidx.activity.compose.BackHandler { showApps = false }
        SanctionAppsScreen(
            selected = apps.toSet(),
            onToggle = { pkg, on ->
                if (on) AntiSanctionManager.addApp(context, pkg)
                else AntiSanctionManager.removeApp(context, pkg)
                apps = AntiSanctionManager.getApps(context)
            },
            onBack = { showApps = false },
        )
        return
    }

    // ---- the domain list, on its own page ------------------------------------------------------
    if (showDomains) {
        androidx.activity.compose.BackHandler { showDomains = false }
        SanctionDomainsScreen(
            domains = domains,
            onChanged = { domains = AntiSanctionManager.getDomains(context) },
            onBack = { showDomains = false },
        )
        return
    }

    val dialState = when {
        isActive -> DialState.RUNNING
        // pendingConfig covers the gap between the tap and the service reporting CONNECTING --
        // the permission dialog lives in there, and the dial must not fall back to "off" while
        // it is up.
        isConnecting || pendingConfig != null -> DialState.STARTING
        didFail -> DialState.FAILED
        else -> DialState.IDLE
    }

    IosScreen(title = S(R.string.anti_sanction_dns), onBack = onBack, backLabel = S(R.string.home)) {
        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmergencyDial(
                state = dialState,
                idleAccent = Ios.Green,
                idleIcon = Icons.Default.Shield,
                idleLabel = S(R.string.on),
                runningLabel = S(R.string.off),
                onClick = {
                    when {
                        isActive -> turnOff()
                        // Tapping again mid-connect used to fire a second startService, which
                        // tears down the tunnel that was halfway up.
                        dialState == DialState.STARTING -> Unit
                        exitKind == AntiSanctionManager.Exit.WORKER && !hasWorker ->
                            toast(context, S(R.string.create_the_cloudflare_worker_first))
                        else -> turnOn()
                    }
                },
            )
        }

        Spacer(Modifier.height(14.dp))

        EmergencyStatusLine(
            state = dialState,
            idleAccent = Ios.Green,
            headline = when {
                isActive -> S(R.string.on_2)
                dialState == DialState.STARTING -> S(R.string.connecting)
                didFail -> S(R.string.could_not_connect)
                !hasWorker && exitKind == AntiSanctionManager.Exit.WORKER -> S(R.string.not_ready_yet)
                else -> S(R.string.ready_to_switch_on)
            },
            sub = when {
                dialState == DialState.STARTING ->
                    if (exitKind == AntiSanctionManager.Exit.GATEWAY)
                        S(R.string.the_gateway_server_takes_a_few_seconds)
                    else S(R.string.bringing_the_tunnel_up)
                didFail -> S(R.string.tap_again_or_choose_a_different_exit)
                isActive && apps.isNotEmpty() ->
                    S(R.string.apps_go_through_this_route_entirely, faCount(apps.size))
                isActive -> S(R.string.domains_go_through_this_route_everything_else, faCount(domains.size))
                !hasWorker -> S(R.string.create_the_exit_worker_on_your_cloudflare)
                apps.isNotEmpty() -> S(R.string.apps_and_domains_selected, faCount(apps.size), faCount(domains.size))
                else -> S(R.string.domains_in_the_list, faCount(domains.size))
            },
        )

        Spacer(Modifier.height(22.dp))

        // ---- where it comes out ------------------------------------------------------------
        SettingsSectionHeader(S(R.string.exit_2))
        SettingsGroup {
            ExitChoiceRow(
                title = S(R.string.cloudflare_worker),
                subtitle = S(R.string.fast_and_unmetered_for_github_steam_apis),
                selected = exitKind == AntiSanctionManager.Exit.WORKER,
            ) {
                exitKind = AntiSanctionManager.Exit.WORKER
                AntiSanctionManager.setExitKind(context, exitKind)
            }
            Separator()
            ExitChoiceRow(
                title = S(R.string.gateway_server),
                subtitle = gatewayServer?.let { S(R.string.residential_ip_for_gemini, it.countryLong) }
                    ?: S(R.string.pick_a_server_in_the_gateway_first),
                selected = exitKind == AntiSanctionManager.Exit.GATEWAY,
            ) {
                exitKind = AntiSanctionManager.Exit.GATEWAY
                AntiSanctionManager.setExitKind(context, exitKind)
            }
        }
        SettingsFooter(
            S(R.string.a_cloudflare_worker_has_a_datacentre_ip) +
                S(R.string.open_with_the_worker_but_some_gemini) +
                S(R.string.gateway_servers_run_on_volunteers_home_internet)
        )

        Spacer(Modifier.height(4.dp))
        SettingsSectionHeader(S(R.string.exit_worker))
        SettingsGroup {
            val readyAccount = workerAccounts.firstOrNull() ?: accounts.firstOrNull()
            SettingsRow(
                title = S(R.string.cloudflare_account),
                value = when {
                    hasWorker -> readyAccount?.email?.ifEmpty { readyAccount.accountId } ?: S(R.string.ready)
                    accounts.isEmpty() -> S(R.string.not_connected)
                    else -> S(R.string.not_created)
                },
                icon = Icons.Default.Cloud,
                tint = if (hasWorker) Ios.Green else Ios.Orange,
                showChevron = false,
            )
            Separator()
            SettingsActionRow(
                label = when {
                    deploying -> deployStatus.ifEmpty { S(R.string.creating) }
                    hasWorker -> S(R.string.reinstall_the_latest_version)
                    else -> S(R.string.create_the_worker_on_my_account)
                },
                icon = Icons.Default.Refresh,
                tint = if (hasWorker) Ios.Gray else Ios.Orange,
                labelColor = Ios.Label,
                enabled = !deploying,
            ) { deployWorker() }
        }
        SettingsFooter(
            S(R.string.traffic_for_sanctioned_sites_leaves_through_your) +
                S(R.string.clean_and_the_sanction_is_genuinely_bypassed)
        )

        // ---- the domains -------------------------------------------------------------------
        Spacer(Modifier.height(4.dp))
        SettingsSectionHeader(S(R.string.what_gets_unblocked))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.apps),
                subtitle = if (apps.isEmpty()) S(R.string.no_app_selected) else S(R.string.all_of_their_traffic_goes_through_the),
                value = faCount(apps.size),
                icon = Icons.Default.Apps,
                tint = Ios.Gray,
                onClick = { showApps = true },
            )
            Separator()
            SettingsRow(
                title = S(R.string.domains),
                subtitle = S(R.string.for_the_browser),
                value = faCount(domains.size),
                icon = Icons.Default.Language,
                tint = Ios.Gray,
                onClick = { showDomains = true },
            )
        }
        SettingsFooter(
            if (apps.isEmpty()) {
                S(R.string.domains_work_for_the_browser_mobile_apps) +
                    S(R.string.their_own_resolver_quic_and_sign_in) +
                    S(R.string.those_you_have_to_select_the_app)
            } else {
                S(R.string.when_you_select_an_app_only_those) +
                    S(R.string.your_worker_the_rest_of_the_phone)
            }
        )

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The routed domains, and the query that grows them.
 *
 * Seventy-one rows used to sit inline between the query form and the power button. They are the
 * least-read thing on the screen and they were pushing the most-used one off the bottom of it.
 */
@Composable
private fun SanctionDomainsScreen(
    domains: List<String>,
    onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<AntiSanctionManager.Report?>(null) }

    fun add() {
        val d = AntiSanctionManager.normalize(input)
        if (d == null) { toast(context, S(R.string.enter_a_valid_domain)); return }
        AntiSanctionManager.addDomain(context, d)
        onChanged()
        toast(context, S(R.string.d_added, d))
        input = ""
        report = null
    }

    IosScreen(title = S(R.string.sanctioned_domains), onBack = onBack, backLabel = S(R.string.anti_sanction)) {
        Spacer(Modifier.height(12.dp))

        SettingsSectionHeader(S(R.string.add_domain))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(ControlShape)
                .background(Color.White.copy(alpha = 0.07f))
                .heightIn(min = 42.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it; report = null },
                singleLine = true,
                textStyle = TextStyle(color = Ios.Label, fontSize = 15.sp),
                cursorBrush = SolidColor(Ios.Blue),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text(
                            S(R.string.e_g_chat_openai_com),
                            color = Ios.SecondaryLabel.copy(alpha = 0.7f),
                            fontSize = 15.sp,
                        )
                    }
                    inner()
                },
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SanctionButton(
                label = if (checking) S(R.string.looking_up) else S(R.string.look_up),
                icon = Icons.Default.Search,
                enabled = !checking,
                busy = checking,
                modifier = Modifier.weight(1f),
            ) {
                val d = AntiSanctionManager.normalize(input)
                if (d == null) { toast(context, S(R.string.enter_a_valid_domain)) } else {
                    checking = true
                    report = null
                    scope.launch {
                        report = AntiSanctionManager.classifyDomain(d)
                        checking = false
                    }
                }
            }
            SanctionButton(
                label = S(R.string.add),
                icon = Icons.Default.Add,
                primary = true,
                modifier = Modifier.weight(1f),
            ) { add() }
        }

        report?.let { r ->
            Spacer(Modifier.height(12.dp))
            val tone = when (r.state) {
                AntiSanctionManager.State.SANCTIONED -> Ios.Green
                AntiSanctionManager.State.OPEN -> Ios.Blue
                AntiSanctionManager.State.FILTERED -> Ios.Orange
                AntiSanctionManager.State.UNKNOWN -> Ios.SecondaryLabel
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(PanelShape)
                    .background(tone.copy(alpha = 0.13f))
                    .padding(13.dp),
            ) {
                Text(r.domain, color = Ios.Label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(3.dp))
                Text(r.note, color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 20.sp)
                // "استعلام" only classifies the domain -- it does NOT add it to the routing list
                // on its own. Without this, users read the "will open by turning this on" message,
                // turn the feature on, and are confused when the site still doesn't open because
                // they never pressed "افزودن".
                if (r.state == AntiSanctionManager.State.SANCTIONED &&
                    !AntiSanctionManager.getDomains(context).contains(r.domain)
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        S(R.string.this_domain_has_not_been_added_yet) +
                            S(R.string.the_worker_tap_add_as_well),
                        color = Ios.Orange,
                        fontSize = 12.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        SettingsSectionHeader(
            if (domains.isEmpty()) S(R.string.the_list_is_empty) else S(R.string.domains_2, faCount(domains.size))
        )
        if (domains.isEmpty()) {
            Text(
                S(R.string.you_have_not_added_any_domains_yet),
                color = Ios.SecondaryLabel,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            SettingsGroup {
                domains.forEachIndexed { i, d ->
                    if (i > 0) Separator()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .padding(start = 16.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(d, color = Ios.Label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = S(R.string.delete),
                            tint = Ios.Destructive,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable {
                                    AntiSanctionManager.removeDomain(context, d)
                                    onChanged()
                                }
                                .padding(6.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Which apps go through the worker.
 *
 * Suggested first, then everything else the user has installed. The suggestions are the apps
 * blocked by their vendor rather than by the operator -- the case this feature is for -- and the
 * ones present on the phone are already ticked the first time this opens.
 */
@Composable
private fun SanctionAppsScreen(
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager

    data class Installed(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?)

    // Reading every installed package and its icon is slow enough to drop frames if it happens
    // during composition, so it happens once and off the critical path of every recomposition.
    val installed by androidx.compose.runtime.produceState(initialValue = emptyList<Installed>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val launchable = pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map {
                        Installed(
                            pkg = it.packageName,
                            label = runCatching { pm.getApplicationLabel(it).toString() }
                                .getOrDefault(it.packageName),
                            icon = runCatching { pm.getApplicationIcon(it) }.getOrNull(),
                        )
                    }
                launchable.sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
    }

    var query by remember { mutableStateOf("") }

    val suggested = installed.filter { it.pkg in AntiSanctionManager.SUGGESTED_APPS }
    val rest = installed.filter { it.pkg !in AntiSanctionManager.SUGGESTED_APPS }
    val shown = if (query.isBlank()) rest
                else rest.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }

    IosScreen(title = S(R.string.apps), onBack = onBack, backLabel = S(R.string.anti_sanction)) {
        Spacer(Modifier.height(12.dp))

        if (installed.isEmpty()) {
            Text(
                S(R.string.reading_the_app_list),
                color = Ios.SecondaryLabel,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        if (suggested.isNotEmpty()) {
            SettingsSectionHeader(S(R.string.our_suggestions))
            SettingsGroup {
                suggested.forEachIndexed { i, app ->
                    if (i > 0) Separator()
                    AppRow(app.label, app.pkg, app.icon, app.pkg in selected) { on -> onToggle(app.pkg, on) }
                }
            }
            SettingsFooter(
                S(R.string.these_are_blocked_on_iranian_ips_by) +
                    S(R.string.services_are_routed_with_them_automatically_sign) +
                    S(R.string.and_without_them_the_app_opens_but)
            )
        }

        if (installed.isNotEmpty()) {
            SettingsSectionHeader(S(R.string.all_apps))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(ControlShape)
                    .background(Color.White.copy(alpha = 0.07f))
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = Ios.SecondaryLabel,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Ios.Label, fontSize = 15.sp),
                    cursorBrush = SolidColor(Ios.Blue),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                S(R.string.search),
                                color = Ios.SecondaryLabel.copy(alpha = 0.7f),
                                fontSize = 15.sp,
                            )
                        }
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                shown.forEachIndexed { i, app ->
                    if (i > 0) Separator()
                    AppRow(app.label, app.pkg, app.icon, app.pkg in selected) { on -> onToggle(app.pkg, on) }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun AppRow(
    label: String,
    pkg: String,
    icon: android.graphics.drawable.Drawable?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Drawn once per icon and at the size shown: toBitmap() at the icon's own size on every
        // recomposition froze the list on long app lists, and an icon with no size of its own
        // (width or height 0) threw «width and height must be > 0» (1.2.42 #2344, #2410).
        val bitmap = remember(icon) { icon?.let { runCatching { it.toBitmap(96, 96).asImageBitmap() }.getOrNull() } }
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                painter = remember(bitmap) { androidx.compose.ui.graphics.painter.BitmapPainter(bitmap) },
                contentDescription = null,
                modifier = Modifier.size(30.dp).clip(ControlShape),
            )
        } else {
            Box(modifier = Modifier.size(30.dp).clip(ControlShape).background(Color.White.copy(alpha = 0.08f)))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Ios.Label, fontSize = 15.sp, maxLines = 1)
            Text(
                pkg,
                color = Ios.SecondaryLabel,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Ios.Green,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** One exit, with a tick when it is the one in use. */
@Composable
private fun ExitChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Ios.Label, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Ios.Green,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * A button on this screen.
 *
 * The pair here used to be a Material `Button` with a 1.5dp accent rim and accent-coloured text.
 * Same tinted panel as everywhere else, and the label is white -- the tint says which of the two
 * commits something, the words do not have to.
 */
@Composable
private fun SanctionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(PanelShape)
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.05f)
                    primary -> Ios.Blue.copy(alpha = 0.24f)
                    else -> Color.White.copy(alpha = 0.09f)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                color = Ios.SecondaryLabel,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) Ios.SecondaryLabel else Ios.SecondaryLabel.copy(alpha = 0.5f),
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = if (enabled) Ios.Label else Ios.SecondaryLabel,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private fun toast(context: android.content.Context, msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}
