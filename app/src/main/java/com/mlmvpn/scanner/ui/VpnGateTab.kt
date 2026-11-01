package com.mlmvpn.scanner.ui

// Deliberately in the `ui` package: getNodeFlagEmoji() lives here (NodesTab.kt) and is used
// as-is, with no import and no third copy of the regional-indicator maths.

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.engines.vpngate.*
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosOption
import com.mlmvpn.scanner.ui.settings.IosPickerScreen
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.ui.theme.GreenOk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * Where inside the gateway the user is.
 *
 * A real page stack, replacing the two `showPicker` / `showBrowse` booleans that each caused the
 * whole tab to `return` early. That arrangement had two costs beyond the nesting itself: the
 * pages could not share state, so the sort mode and the country filter reset every time; and
 * moving between them was done by writing both flags at once, which is why leaving the browser
 * landed on the main screen rather than back where the user came from.
 */
private sealed class GatewayPage {
    object Main : GatewayPage()
    object Servers : GatewayPage()
    object Countries : GatewayPage()
    object Sort : GatewayPage()
    object Help : GatewayPage()
    data class Detail(val host: String) : GatewayPage()
}

@Composable
fun VpnGateTab(
    onDismiss: () -> Unit,
    /**
     * Escape hatch to the Cloud tab, offered only when the list cannot be fetched at all.
     *
     * When every fetch route fails, VPN Gate's domain is blocked on this operator and the feature
     * has nothing to offer -- which is the one moment where "deploy a server of your own" is the
     * actual answer rather than an advert for another tab. Defaulted so a caller that has no
     * cloud destination to offer can leave it out.
     */
    onOpenCloud: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { VpnGateRepository(context) }

    val live by repo.serversFlow.collectAsState()
    val loading by repo.loadingFlow.collectAsState()
    val error by repo.errorFlow.collectAsState()
    val source by repo.sourceFlow.collectAsState()
    val fetchedAt by repo.fetchedAtFlow.collectAsState()

    // VPN Gate's own domain resolves to the filtering page on every Iranian operator, and every
    // shared relay the app can reach is now dead (Vercel 402, allorigins/codetabs 522, jina 451).
    // The one route that works is a Worker on the user's own Cloudflare account, so whether they
    // have one is the difference between the list updating and not.
    val cloudManager = remember { com.mlmvpn.scanner.data.CloudManager(context) }
    val cloudAccounts by cloudManager.accountsFlow.collectAsState()
    var relayJustDeployed by remember { mutableStateOf(false) }
    var deployingRelay by remember { mutableStateOf(false) }
    val relayAccount = cloudAccounts.firstOrNull()
    val hasRelay = relayJustDeployed || cloudAccounts.any { !it.relayWorkerUrl.isNullOrBlank() }

    val connectedNodeId by MyVpnService.connectedNodeIdFlow.collectAsState()
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()

    val selectedHost by VpnGateStore.selectedHostFlow.collectAsState()
    val pings by VpnGateStore.pingsFlow.collectAsState()
    val connectedSince by VpnGateStore.connectedSinceFlow.collectAsState()

    val pool by VpnGatePool.poolFlow.collectAsState()
    val kept by VpnGatePool.keptFlow.collectAsState()
    val hidden by VpnGatePool.hiddenFlow.collectAsState()
    val udpAcceleration by VpnGateStore.udpAccelerationFlow.collectAsState()
    val handshakes by VpnGateStore.handshakesFlow.collectAsState()
    val sweep by VpnGateSweep.stateFlow.collectAsState()

    var page by remember { mutableStateOf<GatewayPage>(GatewayPage.Main) }
    val listState = remember { GatewayListState() }
    var snack by remember { mutableStateOf<String?>(null) }
    var pendingConnect by remember { mutableStateOf(false) }
    var autoPicked by remember { mutableStateOf(false) }

    // The main list: whatever VPN Gate is advertising right now, plus everything the user
    // promoted from the archive, minus everything they pruned. Servers they added keep working
    // after VPN Gate rotates them out of the public window, which is the whole point of the
    // archive — the live list turns over almost completely inside a year.
    val servers = remember(live, kept, hidden) {
        val keptServers = VpnGatePool.keptServers()
        (live + keptServers)
            .distinctBy { it.hostName }
            .filter { it.hostName !in hidden }
    }

    val archive = remember(pool) { pool.values.map { it.server } }

    val selected = remember(servers, selectedHost) {
        servers.firstOrNull { it.hostName == selectedHost }
    }

    // Only this screen's own node counts as connected — a VLESS/Aether session running from
    // another tab must not light this button up.
    val isOurs = connectedNodeId != null && connectedNodeId == selected?.id
    val isConnected = isOurs && phase == MyVpnService.Phase.CONNECTED
    val isConnecting = isOurs && phase == MyVpnService.Phase.CONNECTING

    LaunchedEffect(Unit) {
        VpnGateStore.load(context)
        VpnGatePool.load(context)
        repo.refresh()
    }

    // Session clock: start it when our tunnel comes up, clear it when it goes down.
    LaunchedEffect(isConnected) {
        if (isConnected && connectedSince == 0L) VpnGateStore.markConnected(context)
        if (!isConnected && !isConnecting && connectedSince != 0L) VpnGateStore.markDisconnected(context)
    }

    LaunchedEffect(snack) {
        if (snack != null) {
            delay(2600)
            snack = null
        }
    }

    // First visit: pick a server for the user instead of showing an empty button. Measures the
    // real latency of the highest-scoring candidates and keeps the fastest one that answered.
    LaunchedEffect(servers) {
        if (servers.isEmpty() || autoPicked) return@LaunchedEffect
        autoPicked = true
        if (selectedHost != null && servers.any { it.hostName == selectedHost }) return@LaunchedEffect

        val candidates = servers.sortedByDescending { it.score }.take(12)
        VpnGatePinger.pingAll(
            servers = candidates,
            ovpnOf = { repo.ovpnTextFor(it) },
            onResult = { s, rtt -> VpnGateStore.putPing(s.hostName, rtt) },
        )
        val best = candidates
            .filter { (VpnGateStore.pingOf(it.hostName) ?: VpnGatePinger.FAILED) > 0 }
            .minByOrNull { VpnGateStore.pingOf(it.hostName)!! }
            ?: candidates.firstOrNull()
        best?.let { VpnGateStore.select(context, it.hostName) }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val server = selected
        if (pendingConnect && result.resultCode == android.app.Activity.RESULT_OK && server != null) {
            scope.launch { VpnGateController.connect(context, server, repo.ovpnTextFor(server)) }
        }
        pendingConnect = false
    }

    fun toggle() {
        if (isConnected || isConnecting) {
            VpnGateController.disconnect(context)
            VpnGateStore.markDisconnected(context)
            return
        }
        val server = selected ?: return
        // A tunnel takes the default route out from under a running IP scan, which then finishes
        // early having marked every unprobed address dead. ScanGuard asks before that happens.
        com.mlmvpn.scanner.data.ScanGuard.run(
            com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN
        ) {
            val consent = VpnGateController.needsConsent(context)
            if (consent != null) {
                pendingConnect = true
                consentLauncher.launch(consent)
            } else {
                scope.launch { VpnGateController.connect(context, server, repo.ovpnTextFor(server)) }
            }
        }
    }

    // ---- the bulk tests -----------------------------------------------------------------
    //
    // Hoisted here rather than written once per list screen, which is how the picker and the
    // browser ended up with two copies of each. LAZY + begin() + start(): registering the sweep
    // before the body can run means a short list cannot call end() before begin() and strand the
    // progress strip.
    fun startPing(list: List<VpnGateServer>) {
        if (list.isEmpty() || VpnGateSweep.isRunning()) return
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                VpnGatePinger.pingAll(
                    servers = list,
                    ovpnOf = { repo.ovpnTextFor(it) },
                    onResult = { s, rtt ->
                        VpnGateStore.putPing(s.hostName, rtt)
                        VpnGateSweep.tick()
                    },
                )
            } finally { VpnGateSweep.end() }
        }
        VpnGateSweep.begin(VpnGateSweep.Kind.PING, list.size, job)
        job.start()
    }

    fun startProbe(list: List<VpnGateServer>) {
        if (list.isEmpty() || VpnGateSweep.isRunning()) return
        // Snapshot the list. The caller's list re-sorts on every result that lands, so reading it
        // inside the sweep would probe a target set that reshuffles underfoot.
        val batch = list.toList()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                SoftEtherProbe.probeAll(batch) { s, r ->
                    VpnGateStore.putHandshake(s.hostName, r)
                    VpnGateSweep.tick()
                }
            } finally { VpnGateSweep.end() }
        }
        VpnGateSweep.begin(VpnGateSweep.Kind.PROBE, batch.size, job)
        job.start()
    }

    fun refreshList() {
        if (loading) return
        scope.launch {
            repo.refresh(force = true)
            val added = repo.newlyDiscoveredFlow.value
            snack = if (added > 0) faCount(added) + S(R.string.new_servers_found)
                    else S(R.string.no_new_servers_found)
        }
    }

    fun pick(server: VpnGateServer) {
        val wasConnected = isConnected
        VpnGateStore.select(context, server.hostName)
        // A server picked out of the archive has to be kept as well, or the main list drops it
        // again the moment VPN Gate stops advertising it — and the connect button would then
        // hold a selection it cannot find.
        if (servers.none { it.hostName == server.hostName }) {
            VpnGatePool.keep(context, listOf(server.hostName))
        }
        page = GatewayPage.Main
        // Switching servers while connected should just move the tunnel, not silently leave the
        // user on the old one with a new name on screen.
        if (wasConnected) {
            scope.launch { VpnGateController.connect(context, server, repo.ovpnTextFor(server)) }
        }
    }

    fun removeServers(hosts: List<String>) {
        if (hosts.isEmpty()) return
        scope.launch {
            // banAndPurge for the main list, purge for the archive. The main list is
            // `live + kept`, so deleting the archive entry alone leaves the row on screen coming
            // out of `live` — the server looks untouched except that its test result is gone.
            if (listState.scope == GatewayScope.MINE) {
                VpnGatePool.banAndPurge(context, hosts)
            } else {
                VpnGatePool.purge(context, hosts)
            }
            VpnGateStore.forgetHandshakes(hosts)
            snack = faCount(hosts.size) + S(R.string.servers_removed)
        }
    }

    androidx.activity.compose.BackHandler(enabled = page != GatewayPage.Main) {
        page = when (page) {
            is GatewayPage.Servers -> GatewayPage.Main
            else -> GatewayPage.Servers
        }
    }

    when (val current = page) {
        is GatewayPage.Servers -> {
            VpnGateServersScreen(
                state = listState,
                mine = servers,
                archive = archive,
                kept = kept,
                pings = pings,
                handshakes = handshakes,
                selectedHost = selectedHost,
                loading = loading,
                sweepRunning = sweep != null,
                snack = snack,
                onPick = ::pick,
                onOpenDetail = { page = GatewayPage.Detail(it.hostName) },
                onOpenCountries = { page = GatewayPage.Countries },
                onOpenSort = { page = GatewayPage.Sort },
                onOpenHelp = { page = GatewayPage.Help },
                onRefresh = ::refreshList,
                onPingAll = ::startPing,
                onProbeAll = ::startProbe,
                onKeep = { hosts ->
                    VpnGatePool.keep(context, hosts)
                    snack = faCount(hosts.size) + S(R.string.servers_added_to_your_list)
                },
                onRemove = ::removeServers,
                onBack = { page = GatewayPage.Main },
            )
            return
        }

        is GatewayPage.Countries -> {
            VpnGateCountryScreen(
                servers = if (listState.scope == GatewayScope.MINE) servers else archive,
                selected = listState.countries,
                onSelectedChange = { listState.countries = it },
                onBack = { page = GatewayPage.Servers },
            )
            return
        }

        is GatewayPage.Sort -> {
            IosPickerScreen(
                title = S(R.string.sort_2),
                backLabel = S(R.string.servers_4),
                options = GatewaySort.values().map {
                    IosOption(key = it.name, label = it.label, detail = it.detail)
                },
                selectedKey = listState.sort.name,
                onSelect = { key -> listState.sort = GatewaySort.valueOf(key) },
                onBack = { page = GatewayPage.Servers },
                footer = S(R.string.sorting_only_affects_the_display_no_server),
            )
            return
        }

        is GatewayPage.Help -> {
            GatewayHelpScreen(onBack = { page = GatewayPage.Servers })
            return
        }

        is GatewayPage.Detail -> {
            val server = remember(current.host, servers, archive) {
                servers.firstOrNull { it.hostName == current.host }
                    ?: archive.firstOrNull { it.hostName == current.host }
            }
            if (server == null) {
                // The row it was opened from has been deleted underneath it.
                page = GatewayPage.Servers
                return
            }
            GatewayServerDetailScreen(
                server = server,
                ping = pings[server.hostName],
                handshake = handshakes[server.hostName],
                isKept = server.hostName in kept,
                isSelected = server.hostName == selectedHost,
                onSelect = { pick(server) },
                onKeepToggle = {
                    if (server.hostName in kept) {
                        VpnGatePool.drop(context, listOf(server.hostName))
                        snack = S(R.string.removed_from_your_list)
                    } else {
                        VpnGatePool.keep(context, listOf(server.hostName))
                        snack = S(R.string.added_to_your_list)
                    }
                },
                onRemove = { removeServers(listOf(server.hostName)) },
                onBack = { page = GatewayPage.Servers },
            )
            return
        }

        is GatewayPage.Main -> Unit
    }

    IosScreen(
        title = S(R.string.mlm_gateway),
        onBack = onDismiss,
        backLabel = S(R.string.home),
        scrollable = false,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            // ---- the button ---------------------------------------------------------
            //
            // Was a bespoke 200dp disc with its own concentric rings and its own idea of what
            // "connecting" looks like. Every transport screen in the app drives the same dial.
            com.mlmvpn.scanner.ui.emergency.EmergencyDial(
                state = when {
                    isConnected -> com.mlmvpn.scanner.ui.emergency.DialState.RUNNING
                    isConnecting -> com.mlmvpn.scanner.ui.emergency.DialState.STARTING
                    else -> com.mlmvpn.scanner.ui.emergency.DialState.IDLE
                },
                // The indigo of this feature's home-screen tile.
                idleAccent = Ios.Indigo,
                idleIcon = Icons.Default.Power,
                idleLabel = S(R.string.connect),
                runningLabel = S(R.string.disconnect),
                onClick = { if (selected != null) toggle() },
            )

            Spacer(Modifier.height(16.dp))

            StatusLine(
                isConnected = isConnected,
                isConnecting = isConnecting,
                hasServer = selected != null,
                connectedSince = connectedSince,
            )
            com.mlmvpn.scanner.ui.RouteChips()

            Spacer(Modifier.weight(1f))

            // ---- the server, and where the list came from ---------------------------
            //
            // The chosen server was a card with a flag, a name, a badge and a ping strung
            // together by bullet separators, and the list's own state ("142 servers, live")
            // floated as a caption under the nav bar with nothing to attach it to. The server is
            // a row that names what is selected; the list's state is the footer of the group it
            // describes.
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isConnecting) { page = GatewayPage.Servers }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val chosen = selected
                    if (chosen != null) {
                        Text(getNodeFlagEmoji(chosen.countryShort), fontSize = 22.sp)
                    } else {
                        SettingsGlyph(Icons.Default.Public, Ios.Gray)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(S(R.string.server), color = Ios.Label, fontSize = 16.sp)
                        if (chosen != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (chosen.isOfficialRelay) chosen.countryLong + S(R.string.official)
                                else chosen.countryLong,
                                color = Ios.SecondaryLabel,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val ping = chosen?.let { pings[it.hostName] }
                    if (ping != null) {
                        // A measurement is a state, so this one keeps its colour.
                        Text(
                            if (ping > 0) faCount(ping) + " ms" else S(R.string.no_response),
                            color = if (ping > 0) GreenOk else Ios.Orange,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    } else if (chosen == null) {
                        Text(S(R.string.not_selected), color = Ios.SecondaryLabel, fontSize = 15.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = null,
                        tint = Ios.Chevron,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Separator()

                // The list is kept on disk and read from there on every entry, so this row is the
                // only thing that goes to the network -- and the only place that says how old what
                // you are looking at actually is.
                SettingsRow(
                    title = if (loading) S(R.string.updating_2) else S(R.string.refresh_list),
                    value = if (loading) null else listAge(fetchedAt),
                    icon = Icons.Default.Refresh,
                    tint = if (isListStale(fetchedAt)) Ios.Orange else Ios.Gray,
                    showChevron = false,
                    onClick = ::refreshList,
                )

                // Without a relay the row above cannot succeed, so this sits directly under it.
                if (!hasRelay) {
                    Separator()
                    SettingsActionRow(
                        label = when {
                            deployingRelay -> S(R.string.enabling)
                            relayAccount == null -> S(R.string.connect_a_cloudflare_account)
                            else -> S(R.string.enable_updates)
                        },
                        icon = Icons.Default.Cloud,
                        tint = Ios.Orange,
                        enabled = !deployingRelay,
                    ) {
                        val account = relayAccount
                        if (account == null) {
                            onOpenCloud()
                        } else {
                            scope.launch {
                                deployingRelay = true
                                val (ok, msg) = cloudManager.deployVpnGateRelay(account)
                                deployingRelay = false
                                if (ok) {
                                    relayJustDeployed = true
                                    snack = S(R.string.relay_enabled_fetching_the_list)
                                    repo.refresh(force = true)
                                } else {
                                    snack = msg
                                }
                            }
                        }
                    }
                }
            }

            SettingsFooter(
                when {
                    loading -> S(R.string.fetching_a_fresh_list_from_vpn_gate)

                    !hasRelay ->
                        S(R.string.the_vpn_gate_domain_is_blocked_on) +
                            S(R.string.no_longer_answer_either_so_the_app) +
                            S(R.string.connect_a_cloudflare_account_and_a_small) +
                            S(R.string.and_updates_work_from_then_on_until) +
                            faCount(servers.size) + S(R.string.servers_are_what_you_have)

                    error != null ->
                        S(R.string.update_failed_none_of_the_download_routes) +
                            S(R.string.the_vpn_gate_domain_is_blocked_on_2) +
                            S(R.string.not_reachable_right_now_either_try_another) +
                            S(R.string.for_now) + faCount(servers.size) + S(R.string.available_servers_are_shown)

                    // The shipped list is stamped now (scripts/update-vpngate-seed.js): only a stale
                    // or unstamped one is "old, update once".
                    source == VpnGateRepository.Source.BUNDLED && (fetchedAt <= 0L || isListStale(fetchedAt)) ->
                        faCount(servers.size) + S(R.string.servers_from_the_list_bundled_with_the)

                    source == VpnGateRepository.Source.BUNDLED ->
                        faCount(servers.size) + com.mlmvpn.scanner.store.tr(
                            " سرور از فهرست آفلاین همراه برنامه. هر وقت خواستید، با «به‌روزرسانی فهرست» فهرست تازه بگیرید.",
                            " servers from the offline list shipped with the app. Tap \"Update list\" whenever you want a fresh one.")

                    isListStale(fetchedAt) ->
                        faCount(servers.size) + S(R.string.servers_but_taken) + listAge(fetchedAt) + S(R.string.ago_vpn_gate_rotates_its_servers_constantly)

                    else ->
                        faCount(servers.size) + S(R.string.servers_in_your_list_and) + faCount(archive.size) +
                            S(R.string.servers_in_the_archive_you_can_see)
                }
            )

            AnimatedVisibility(visible = snack != null) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    GatewaySnack(snack.orEmpty())
                }
            }

            // Polled rather than pushed: the UDP channel opens some seconds into the session,
            // after the SSL one is already up, so a value read once at connect time would
            // always say "off".
            var udpActive by remember { mutableStateOf(false) }
            LaunchedEffect(isConnected) { if (!isConnected) udpActive = false }
            // Only while this page is on screen: a hidden tab polling every 1.5 s kept the process
            // busy for as long as the tunnel kept it alive.
            com.mlmvpn.scanner.ui.LaunchedWhileVisible(isConnected) {
                if (!isConnected) return@LaunchedWhileVisible
                while (true) {
                    udpActive = SoftEtherEngine.isUdpAccelerationActive()
                    delay(1500)
                }
            }

            Spacer(Modifier.height(18.dp))

            SettingsGroup {
                SettingsToggle(
                    title = S(R.string.udp_acceleration),
                    checked = udpAcceleration,
                    onCheckedChange = {
                        // Changing this mid-session would do nothing until the next connect, so
                        // it is refused rather than silently accepted.
                        if (!isConnected && !isConnecting) VpnGateStore.setUdpAcceleration(context, it)
                    },
                    icon = Icons.Default.Bolt,
                    tint = Ios.Gray,
                )
            }

            SettingsFooter(
                when {
                    isConnected && udpActive ->
                        S(R.string.the_udp_channel_is_up_and_in)
                    isConnected && udpAcceleration ->
                        S(R.string.on_but_the_udp_channel_is_not)
                    isConnected ->
                        S(R.string.disconnect_first_to_change_this_option)
                    else ->
                        S(R.string.speeds_things_up_on_networks_where_udp)
                }
            )

            // Its own card, not a second row inside the UDP one: a group here is one subject, and
            // its footer explains that subject.
            //
            // Deleting a server used to be final and invisible. banAndPurge adds the hostname to a
            // deny-list that nothing in the app could show or clear, so one tap on "delete
            // everything that failed" was permanent -- and VpnGatePool.clearHidden() existed for
            // exactly this and had zero callers.
            if (hidden.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SettingsGroup {
                    SettingsActionRow(
                        label = S(R.string.restore) + faCount(hidden.size) + S(R.string.deleted_servers),
                        icon = Icons.Default.Restore,
                    ) {
                        VpnGatePool.clearHidden(context)
                        snack = faCount(hidden.size) + S(R.string.servers_returned_to_the_list)
                    }
                }
                SettingsFooter(
                    S(R.string.servers_you_have_deleted_never_appear_in) +
                        S(R.string.publishes_them_once_more_this_button_brings)
                )
            }

            Spacer(Modifier.height(96.dp))
        }
    }
}

// =============================================================================================
// Main screen pieces
// =============================================================================================

@Composable
private fun StatusLine(
    isConnected: Boolean,
    isConnecting: Boolean,
    hasServer: Boolean,
    connectedSince: Long,
) {
    val label = when {
        isConnecting -> S(R.string.connecting)
        isConnected -> S(R.string.connected)
        !hasServer -> S(R.string.no_server_selected)
        else -> S(R.string.disconnect)
    }
    val color = when {
        isConnecting -> Ios.Yellow
        isConnected -> Ios.Green
        else -> Ios.SecondaryLabel
    }

    Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

    AnimatedVisibility(visible = isConnected && connectedSince > 0L) {
        var elapsed by remember { mutableStateOf(0L) }
        com.mlmvpn.scanner.ui.LaunchedWhileVisible(connectedSince) {
            while (true) {
                elapsed = System.currentTimeMillis() - connectedSince
                delay(1000)
            }
        }
        Text(
            text = formatDuration(elapsed),
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * How long ago the server list was fetched, in words.
 *
 * Rounded to the unit a person would use. "۲ روز پیش" is what matters here; the exact minute
 * of a fetch two days old is noise.
 */
private fun listAge(fetchedAt: Long): String {
    if (fetchedAt <= 0L) return S(R.string.unknown)
    val ms = System.currentTimeMillis() - fetchedAt
    if (ms < 0) return S(R.string.just_now)
    val minutes = ms / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 2 -> S(R.string.just_now)
        minutes < 60 -> faCount(minutes.toInt()) + S(R.string.minutes_ago)
        hours < 24 -> faCount(hours.toInt()) + S(R.string.hours_ago)
        days < 30 -> faCount(days.toInt()) + S(R.string.days_ago)
        else -> faCount((days / 30).toInt()) + S(R.string.months_ago)
    }
}

/** Past this, VPN Gate has rotated enough of the list that most of it will not answer. */
private fun isListStale(fetchedAt: Long): Boolean {
    if (fetchedAt <= 0L) return false
    return System.currentTimeMillis() - fetchedAt > 3L * 24 * 60 * 60 * 1000
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
