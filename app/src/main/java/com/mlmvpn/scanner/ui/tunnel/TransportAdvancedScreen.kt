package com.mlmvpn.scanner.ui.tunnel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.tunnel.CoreConfig
import com.mlmvpn.core.tunnel.SecureStore
import com.mlmvpn.core.tunnel.SplitTunnelSettings
import com.mlmvpn.core.tunnel.TunnelPreferences
import com.mlmvpn.scanner.ui.settings.AppPickerPage
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosOption
import com.mlmvpn.scanner.ui.settings.IosPickerScreen
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.IosTextScreen
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * Everything a user changes only when something is wrong.
 *
 * Grouped and pushed exactly like iOS Settings: every choice opens a page of its own rather than
 * a dropdown in place, which is what frees each row to show its CURRENT value in grey on the
 * trailing edge. "What is this set to" is then answered without opening anything.
 *
 * Two rules run through the whole page.
 *
 * **Nothing here is offered that the core would ignore.** Every row writes a key
 * [com.mlmvpn.core.tunnel.CoreConfig] actually reads, and the rows are filtered per transport
 * for the same reason: Psiphon brings its own server list, so a scan mode or a manual gateway
 * on that page would be a control that changes nothing.
 *
 * **Settings are disabled, not hidden, while a tunnel is up.** They are read once, when the
 * tunnel starts, so a control changed mid-session would leave the screen showing a value that is
 * not what is running.
 */
@Composable
fun TransportAdvancedScreen(transport: Transport, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { TunnelPreferences(context) }

    // A stack of its own rather than routes in AppScreen: these pages exist only inside this
    // screen, and threading twenty ids through the app's navigation would put this feature's
    // internals in a table every other feature has to scroll past.
    val stack = remember { mutableStateListOf<String>() }
    fun push(page: String) = stack.add(page)
    fun pop() { stack.removeLastOrNull() }
    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    // Mirrored into state so a row repaints the moment its page pops, without re-reading
    // preferences on every recomposition.
    var masqueTransport by remember { mutableStateOf(prefs.masqueTransport) }
    var discovery by remember { mutableStateOf(prefs.endpointDiscovery) }
    var scanMode by remember { mutableStateOf(prefs.scanMode) }
    var ipScan by remember { mutableStateOf(prefs.ipScan) }
    var shaping by remember { mutableStateOf(prefs.obfuscationProfile) }
    var retryShaping by remember { mutableStateOf(prefs.retryObfuscationProfiles) }
    var tlsPreset by remember { mutableStateOf(prefs.tlsCurvePreset) }
    var h2Fragment by remember { mutableStateOf(prefs.h2Fragmentation) }
    var manualEndpoint by remember { mutableStateOf(prefs.manualEndpoint) }
    var wgDataCheck by remember { mutableStateOf(prefs.wireguardDataCheck) }
    var chainOuter by remember { mutableStateOf(prefs.chainOuterMode) }
    var chainOuterTor by remember { mutableStateOf(prefs.chainOuterModeTor) }
    var torChainArmed by remember { mutableStateOf(prefs.torChainArmed) }
    var killSwitch by remember { mutableStateOf(prefs.killSwitch) }
    var autoReconnect by remember { mutableStateOf(prefs.autoReconnect) }
    var logLevel by remember { mutableStateOf(prefs.logLevel) }
    var perfProfile by remember { mutableStateOf(prefs.perfProfile) }
    var routeDirect by remember { mutableStateOf(prefs.routeDirect) }
    var routeBlock by remember { mutableStateOf(prefs.routeBlock) }
    var jc by remember { mutableStateOf(prefs.obfuscationJc) }
    var jmin by remember { mutableStateOf(prefs.obfuscationJmin) }
    var jmax by remember { mutableStateOf(prefs.obfuscationJmax) }
    var i1 by remember { mutableStateOf(prefs.obfuscationI1) }
    var i2 by remember { mutableStateOf(prefs.obfuscationI2) }
    var ztGateway by remember { mutableStateOf(prefs.zeroTrustGateway) }
    var ztTeam by remember {
        mutableStateOf(SecureStore.getSecret(context, TunnelPreferences.ZERO_TRUST_TEAM))
    }
    var ztClientId by remember {
        mutableStateOf(SecureStore.getSecret(context, TunnelPreferences.ZERO_TRUST_CLIENT_ID))
    }
    var ztClientSecret by remember {
        mutableStateOf(SecureStore.getSecret(context, TunnelPreferences.ZERO_TRUST_CLIENT_SECRET))
    }
    var ztToken by remember {
        mutableStateOf(SecureStore.getSecret(context, TunnelPreferences.ZERO_TRUST_TOKEN))
    }
    var ztEmail by remember {
        mutableStateOf(SecureStore.getSecret(context, TunnelPreferences.ZERO_TRUST_EMAIL))
    }

    val backLabel = S(R.string.advanced)

    when (stack.lastOrNull()) {
        P_MASQUE_TRANSPORT -> IosPickerScreen(
            title = S(R.string.transport_2),
            options = MASQUE_TRANSPORTS,
            selectedKey = masqueTransport,
            onSelect = { masqueTransport = it; prefs.masqueTransport = it },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.http_3_over_udp_is_faster_and) +
                S(R.string.blocks_udp_the_handshake_never_completes_and),
        )

        P_DISCOVERY -> IosPickerScreen(
            title = S(R.string.finding_a_gateway),
            options = DISCOVERY_MODES,
            selectedKey = discovery,
            onSelect = { discovery = it; prefs.endpointDiscovery = it },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.the_remembered_ones_are_nearly_always_right) +
                S(R.string.a_fresh_scan_earns_its_keep_when),
        )

        P_SCAN -> IosPickerScreen(
            title = S(R.string.scan_effort),
            options = if (transport == Transport.WIREGUARD) WG_SCAN_MODES else SCAN_MODES,
            selectedKey = scanMode,
            onSelect = { scanMode = it; prefs.scanMode = it },
            onBack = ::pop,
            backLabel = backLabel,
            footer = if (transport == Transport.WIREGUARD) {
                S(R.string.guaranteed_exists_here_only_only_the_wireguard) +
                    S(R.string.falls_back_to_balanced_for_every_other) +
                    S(R.string.handshake_and_then_drops_the_data_this)
            } else {
                S(R.string.turbo_connects_to_the_first_healthy_server) +
                    S(R.string.balanced_tries_up_to_six_servers_and)
            },
        )

        P_IP -> IosPickerScreen(
            title = S(R.string.address_family),
            options = IP_FAMILIES,
            selectedKey = ipScan,
            onSelect = { ipScan = it; prefs.ipScan = it },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.ipv4_works_on_any_line_ipv6_matters) +
                S(R.string.ipv4_range_but_not_its_ipv6_which),
        )

        P_SHAPING -> IosPickerScreen(
            title = S(R.string.traffic_shaping),
            options = SHAPING_PROFILES,
            selectedKey = shaping,
            onSelect = { shaping = it; prefs.obfuscationProfile = it },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.more_is_not_better_heavier_profiles_add) +
                S(R.string.signature_balanced_is_what_most_networks_want),
        )

        P_TLS -> IosPickerScreen(
            title = S(R.string.tls_fingerprint),
            options = TLS_PRESETS,
            selectedKey = tlsPreset,
            onSelect = { tlsPreset = it; prefs.tlsCurvePreset = it },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.chrome_announces_the_same_curves_real_chrome) +
                S(R.string.handshake_does_not_stand_out_under_fingerprint),
        )

        P_MANUAL -> IosTextScreen(
            title = S(R.string.manual_gateway),
            value = manualEndpoint,
            onValueChange = { manualEndpoint = it; prefs.manualEndpoint = it.trim() },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.pins_one_address_and_skips_the_scan) +
                S(R.string.you_entered_it_for_a_masque_gateway) +
                S(R.string.a_mistake_here_means_no_connection_not),
            placeholder = "198.51.100.7:443",
        )

        P_CHAIN -> IosPickerScreen(
            title = S(R.string.outer_layer),
            options = listOf(IosOption("direct", S(R.string.direct_2), "Psiphon")) + CHAIN_OUTER,
            selectedKey = chainOuter,
            onSelect = { chainOuter = it; prefs.chainOuterMode = it },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.when_psiphon_will_not_connect_on_its) +
                S(R.string.automatic_tries_the_list_from_the_top) +
                S(R.string.so_that_search_costs_you_only_once),
        )

        P_CHAIN_TOR -> IosPickerScreen(
            title = S(R.string.tor_s_outer_layer),
            options = CHAIN_OUTER,
            selectedKey = chainOuterTor,
            onSelect = { chainOuterTor = it; prefs.chainOuterModeTor = it },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.pinned_separately_from_psiphon_because_the_two) +
                S(R.string.outer_layer_tor_only_wants_a_reachable) +
                S(R.string.ladder_inside_it_and_is_far_more),
        )

        P_TOR_MODE -> IosPickerScreen(
            title = S(R.string.tor_connection_mode),
            options = TOR_MODES,
            selectedKey = prefs.torMode,
            onSelect = { prefs.torMode = it },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.the_automatic_ladder_direct_then_meek_then) +
                S(R.string.works_it_both_connects_faster_and_runs) +
                S(R.string.because_to_the_network_it_looks_like) +
                S(R.string.http_round_trip),
        )

        P_LOG_LEVEL -> IosPickerScreen(
            title = S(R.string.log_level),
            options = LOG_LEVELS,
            selectedKey = logLevel,
            onSelect = { logLevel = it; prefs.logLevel = it },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.info_is_the_default_and_the_right) +
                S(R.string.a_connection_has_failed_and_you_want) +
                S(R.string.and_a_full_scan_is_thousands_of),
        )

        P_PERF -> IosPickerScreen(
            title = S(R.string.performance_profile),
            options = PERF_PROFILES,
            selectedKey = perfProfile,
            onSelect = { perfProfile = it; prefs.perfProfile = it },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.automatic_reads_the_core_count_and_the) +
                S(R.string.nearly_always_right_the_reason_to_override) +
                S(R.string.does_not_match_that_guess),
        )

        P_ROUTE_DIRECT -> IosTextScreen(
            title = S(R.string.outside_the_tunnel),
            value = routeDirect,
            onValueChange = { routeDirect = it; prefs.routeDirect = it.trim() },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.domains_and_ranges_that_should_not_go) +
                S(R.string.the_split_tunnel_list_beside_it_works) +
                S(R.string.destination_and_only_this_one_can_keep) +
                S(R.string.that_also_needs_it_stays_inside_the),
            placeholder = "*.ir, 10.0.0.0/8",
        )

        P_ROUTE_BLOCK -> IosTextScreen(
            title = S(R.string.blocked),
            value = routeBlock,
            onValueChange = { routeBlock = it; prefs.routeBlock = it.trim() },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.domains_and_ranges_that_are_dropped_entirely) +
                S(R.string.not_a_tunnel_bypass_what_you_put),
            placeholder = "ads.example.com",
        )

        P_JC -> NumberPage(S(R.string.junk_packet_count), jc, S(R.string.s_4), ::pop, backLabel,
            S(R.string.how_many_meaningless_packets_to_send_before)
        ) { jc = it; prefs.obfuscationJc = it }

        P_JMIN -> NumberPage(S(R.string.smallest_junk_bytes), jmin, S(R.string.s_40), ::pop, backLabel,
            S(R.string.the_floor_for_junk_packet_size_it)
        ) { jmin = it; prefs.obfuscationJmin = it }

        P_JMAX -> NumberPage(S(R.string.largest_junk_bytes), jmax, S(R.string.s_70), ::pop, backLabel,
            S(R.string.the_ceiling_for_junk_packet_size_it)
        ) { jmax = it; prefs.obfuscationJmax = it }

        P_I1 -> IosTextScreen(
            title = S(R.string.first_packet_prefix),
            value = i1,
            onValueChange = { i1 = it; prefs.obfuscationI1 = it.trim() },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.a_fixed_signature_placed_in_front_of) +
                S(R.string.some_other_protocol_leave_it_empty_unless),
            placeholder = "<b 0xC0000000>",
        )

        P_I2 -> IosTextScreen(
            title = S(R.string.second_packet_prefix),
            value = i2,
            onValueChange = { i2 = it; prefs.obfuscationI2 = it.trim() },
            onBack = ::pop,
            backLabel = backLabel,
            footer = S(R.string.the_same_thing_for_the_next_packet),
            placeholder = "<b 0x01000000>",
        )

        P_ZT_TEAM -> SecretPage(S(R.string.team_name), ztTeam, TunnelPreferences.ZERO_TRUST_TEAM, ::pop, backLabel,
            S(R.string.the_teamname_part_of_teamname_cloudflareaccess_com) +
                S(R.string.enrols_in_your_organisation_instead_of_consumer)
        ) { ztTeam = it }

        P_ZT_CLIENT_ID -> SecretPage("Client ID", ztClientId, TunnelPreferences.ZERO_TRUST_CLIENT_ID, ::pop, backLabel,
            S(R.string.the_access_service_token_id_used_together)
        ) { ztClientId = it }

        P_ZT_CLIENT_SECRET -> SecretPage("Client Secret", ztClientSecret, TunnelPreferences.ZERO_TRUST_CLIENT_SECRET, ::pop, backLabel,
            S(R.string.the_service_token_secret_stored_encrypted_with)
        ) { ztClientSecret = it }

        P_ZT_TOKEN -> SecretPage(S(R.string.access_token), ztToken, TunnelPreferences.ZERO_TRUST_TOKEN, ::pop, backLabel,
            S(R.string.an_alternative_to_the_client_id_secret)
        ) { ztToken = it }

        P_ZT_EMAIL -> SecretPage(S(R.string.email_2), ztEmail, TunnelPreferences.ZERO_TRUST_EMAIL, ::pop, backLabel,
            S(R.string.for_enrolment_verified_by_a_one_time)
        ) { ztEmail = it }

        else -> IosScreen(title = S(R.string.advanced_settings_2), onBack = onBack, backLabel = transport.labelFa) {

            Spacer(Modifier.height(14.dp))

            // --- the transport's own controls ---------------------------------------------
            when (transport) {
                Transport.MASQUE -> {
                    SettingsSectionHeader(S(R.string.transport_and_gateway_discovery))
                    SettingsGroup {
                        SettingsRow(S(R.string.transport_2), Icons.Default.Bolt, Ios.Blue,
                            value = labelOf(MASQUE_TRANSPORTS, masqueTransport),
                            onClick = { push(P_MASQUE_TRANSPORT) })
                        Separator()
                        SettingsRow(S(R.string.finding_a_gateway), Icons.Default.Storage, Ios.Teal,
                            value = labelOf(DISCOVERY_MODES, discovery),
                            onClick = { push(P_DISCOVERY) })
                        Separator()
                        SettingsRow(S(R.string.scan_effort), Icons.Default.Radar, Ios.Indigo,
                            value = labelOf(SCAN_MODES, scanMode),
                            onClick = { push(P_SCAN) })
                        Separator()
                        SettingsRow(S(R.string.address_family), Icons.Default.Router, Ios.Gray,
                            value = labelOf(IP_FAMILIES, ipScan),
                            onClick = { push(P_IP) })
                    }

                    SettingsSectionHeader(S(R.string.obfuscation))
                    SettingsGroup {
                        SettingsRow(S(R.string.traffic_shaping), Icons.Default.Tune, Ios.Purple,
                            value = labelOf(SHAPING_PROFILES, shaping),
                            onClick = { push(P_SHAPING) })
                        Separator()
                        SettingsToggle(
                            title = S(R.string.try_the_other_profiles),
                            checked = retryShaping,
                            onCheckedChange = { retryShaping = it; prefs.retryObfuscationProfiles = it },
                            icon = Icons.Default.Layers,
                            tint = Ios.Orange,
                            subtitle = S(R.string.if_the_connection_fails_try_again_with),
                        )
                        Separator()
                        SettingsRow(S(R.string.tls_fingerprint), Icons.Default.VerifiedUser, Ios.Green,
                            value = labelOf(TLS_PRESETS, tlsPreset),
                            onClick = { push(P_TLS) })
                        Separator()
                        SettingsToggle(
                            title = S(R.string.tls_record_fragmentation),
                            checked = h2Fragment,
                            onCheckedChange = { h2Fragment = it; prefs.h2Fragmentation = it },
                            icon = Icons.Default.Speed,
                            tint = Ios.Pink,
                            subtitle = S(R.string.spreads_the_handshake_across_several_packets_http),
                        )
                    }
                    ManualShapingGroup(jc, jmin, jmax, i1, i2, ::push)
                    ManualEndpointGroup(manualEndpoint) { push(P_MANUAL) }
                }

                Transport.WIREGUARD -> {
                    SettingsSectionHeader(S(R.string.connection_checks))
                    SettingsGroup {
                        SettingsToggle(
                            title = S(R.string.verify_data_passes),
                            checked = wgDataCheck,
                            onCheckedChange = { wgDataCheck = it; prefs.wireguardDataCheck = it },
                            icon = Icons.Default.VerifiedUser,
                            tint = Ios.Green,
                            subtitle = S(R.string.wait_for_real_traffic_to_pass_before),
                        )
                    }
                    Footer(
                        S(R.string.the_wireguard_handshake_completes_on_carriers_that) +
                            S(R.string.so_a_handshake_on_its_own_proves) +
                            S(R.string.turning_it_off_makes_connecting_faster_and)
                    )

                    SettingsSectionHeader(S(R.string.endpoint_scan))
                    SettingsGroup {
                        SettingsRow(S(R.string.scan_effort), Icons.Default.Radar, Ios.Indigo,
                            value = labelOf(WG_SCAN_MODES, scanMode),
                            onClick = { push(P_SCAN) })
                        Separator()
                        SettingsRow(S(R.string.address_family), Icons.Default.Router, Ios.Gray,
                            value = labelOf(IP_FAMILIES, ipScan),
                            onClick = { push(P_IP) })
                    }

                    SettingsSectionHeader(S(R.string.obfuscation))
                    SettingsGroup {
                        SettingsRow(S(R.string.traffic_shaping), Icons.Default.Tune, Ios.Purple,
                            value = labelOf(SHAPING_PROFILES, shaping),
                            onClick = { push(P_SHAPING) })
                        Separator()
                        SettingsToggle(
                            title = S(R.string.try_the_other_profiles),
                            checked = retryShaping,
                            onCheckedChange = { retryShaping = it; prefs.retryObfuscationProfiles = it },
                            icon = Icons.Default.Layers,
                            tint = Ios.Orange,
                            subtitle = S(R.string.if_the_connection_fails_try_again_with),
                        )
                    }
                    ManualShapingGroup(jc, jmin, jmax, i1, i2, ::push)
                    ManualEndpointGroup(manualEndpoint) { push(P_MANUAL) }
                }

                Transport.GOOL -> {
                    SettingsSectionHeader(S(R.string.endpoint_scan))
                    SettingsGroup {
                        SettingsRow(S(R.string.scan_effort), Icons.Default.Radar, Ios.Indigo,
                            value = labelOf(SCAN_MODES, scanMode),
                            onClick = { push(P_SCAN) })
                        Separator()
                        SettingsRow(S(R.string.address_family), Icons.Default.Router, Ios.Gray,
                            value = labelOf(IP_FAMILIES, ipScan),
                            onClick = { push(P_IP) })
                    }
                    Footer(
                        S(R.string.traffic_shaping_for_the_outer_layer_is_2) +
                            S(R.string.the_reason_is_explained_on_the_warp)
                    )
                    ManualEndpointGroup(manualEndpoint) { push(P_MANUAL) }
                }

                Transport.PSIPHON -> {
                    SettingsSectionHeader(S(R.string.when_psiphon_will_not_connect_on_its_2))
                    SettingsGroup {
                        SettingsRow(S(R.string.outer_layer), Icons.Default.Layers, Ios.Orange,
                            value = labelOf(CHAIN_OUTER, chainOuter),
                            onClick = { push(P_CHAIN) })
                    }
                    Footer(
                        S(R.string.psiphon_comes_up_inside_a_warp_tunnel) +
                            S(R.string.addresses_all_that_is_visible_is_a)
                    )

                }

                Transport.TOR -> {
                    SettingsSectionHeader(S(R.string.when_tor_will_not_bootstrap_on_its))
                    SettingsGroup {
                        SettingsToggle(
                            title = S(R.string.tor_inside_warp),
                            checked = torChainArmed,
                            onCheckedChange = { torChainArmed = it; prefs.torChainArmed = it },
                            icon = Icons.Default.Shield,
                            tint = Ios.Purple,
                            subtitle = S(R.string.if_the_network_blocks_tor_itself_bring),
                        )
                        Separator()
                        SettingsRow(S(R.string.outer_layer), Icons.Default.Layers, Ios.Orange,
                            value = labelOf(CHAIN_OUTER, chainOuterTor),
                            onClick = { push(P_CHAIN_TOR) })
                    }
                    Footer(
                        S(R.string.only_direct_and_meek_are_allowed_to) +
                            S(R.string.bridge_obfs4_stalled_once_at_10_and) +
                            S(R.string.proxy_at_all_and_snowflake_makes_its) +
                            S(R.string.socks5_connect_cannot_carry)
                    )

                    SettingsSectionHeader(S(R.string.connection_mode_2))
                    SettingsGroup {
                        SettingsRow(S(R.string.reaching_the_network), Icons.Default.Article, Ios.Teal,
                            value = torModeLabel(prefs.torMode),
                            onClick = { push(P_TOR_MODE) })
                    }
                }

                // Geph has its own settings page (GephSettingsScreen); TransportHost sends its
                // "advanced" row there, so this page never renders for it.
                Transport.GEPH -> Unit
                // «وارپ» likewise: CfWarpSettingsScreen.
                Transport.CFWG -> Unit
                // «لنترن»'s own choices (routing, the server list) are on its screen; only the shared
                // sections below apply here.
                Transport.LANTERN -> Unit
                // «URnetwork»: its own choices (location, ad blocker, MAE, account) are on its screen.
                // Of the shared sections below, the per-destination lists become URnetwork's own
                // routing rules in its core, and the kill switch and reconnect apply as for the rest;
                // the diagnostics section is the tunnel core's and is left out.
                Transport.URNETWORK -> Unit
            }

            // --- Zero Trust, for the three transports that enrol -----------------------
            if (transport == Transport.MASQUE || transport == Transport.WIREGUARD ||
                transport == Transport.GOOL
            ) {
                SettingsSectionHeader("Cloudflare Zero Trust")
                SettingsGroup {
                    SettingsRow(S(R.string.team_name), Icons.Default.Security, Ios.Blue,
                        value = ztTeam.ifBlank { S(R.string.not_set) }, onClick = { push(P_ZT_TEAM) })
                    Separator()
                    SettingsRow("Client ID", Icons.Default.Security, Ios.Gray,
                        value = masked(ztClientId), onClick = { push(P_ZT_CLIENT_ID) })
                    Separator()
                    SettingsRow("Client Secret", Icons.Default.Security, Ios.Gray,
                        value = masked(ztClientSecret), onClick = { push(P_ZT_CLIENT_SECRET) })
                    Separator()
                    SettingsRow(S(R.string.access_token), Icons.Default.Security, Ios.Gray,
                        value = masked(ztToken), onClick = { push(P_ZT_TOKEN) })
                    Separator()
                    SettingsRow(S(R.string.email_2), Icons.Default.Security, Ios.Gray,
                        value = ztEmail.ifBlank { S(R.string.not_set) }, onClick = { push(P_ZT_EMAIL) })
                    Separator()
                    SettingsToggle(
                        title = S(R.string.apply_gateway_policy),
                        checked = ztGateway,
                        onCheckedChange = { ztGateway = it; prefs.zeroTrustGateway = it },
                        icon = Icons.Default.Shield,
                        tint = Ios.Green,
                    )
                }
                Footer(
                    S(R.string.fill_these_in_and_the_tunnel_enrols) +
                        S(R.string.and_the_exit_becomes_an_address_the) +
                        S(R.string.the_credentials_are_encrypted_with_androidkeystore_not) +
                        S(R.string.all_of_them_are_optional_empty_means)
                )
            }

            // --- shared, whichever transport is running --------------------------------
            //
            // Only what is genuinely this stack's own is still here. DNS, the local proxy port,
            // LAN access and the split-tunnel list were all duplicated on this page: a second
            // switch, in a second preferences file, answering exactly the same question as
            // Settings. The two disagreed by default and neither page mentioned the other, so an
            // app excluded from the tunnel here was tunnelled again under a different engine.
            // They now live once, in Settings, and apply to every engine.
            //
            // What stays: the per-DESTINATION lists. "Outside the tunnel" and "blocked" work on
            // domains and ranges rather than on apps, this stack is the only one that reads them,
            // and there is no app-wide setting they would be a copy of.
            SettingsSectionHeader(S(R.string.routing))
            SettingsGroup {
                SettingsRow(S(R.string.outside_the_tunnel), Icons.Default.Link, Ios.Teal,
                    value = countLabel(routeDirect), onClick = { push(P_ROUTE_DIRECT) })
                Separator()
                SettingsRow(S(R.string.blocked), Icons.Default.Shield, Ios.Red,
                    value = countLabel(routeBlock), onClick = { push(P_ROUTE_BLOCK) })
            }
            Footer(S(R.string.transport_shared_settings_moved))

            SettingsSectionHeader(S(R.string.behaviour))
            SettingsGroup {
                SettingsToggle(
                    title = S(R.string.kill_switch),
                    checked = killSwitch,
                    onCheckedChange = { killSwitch = it; prefs.killSwitch = it },
                    icon = Icons.Default.Shield,
                    tint = Ios.Red,
                    subtitle = S(R.string.if_the_tunnel_drops_cut_the_network),
                )
                Separator()
                SettingsToggle(
                    title = S(R.string.automatic_reconnect),
                    checked = autoReconnect,
                    onCheckedChange = { autoReconnect = it; prefs.autoReconnect = it },
                    icon = Icons.Default.Bolt,
                    tint = Ios.Green,
                    subtitle = S(R.string.a_tunnel_that_drops_on_its_own),
                )
            }
            Footer(
                S(R.string.the_kill_switch_is_a_trade_not) +
                    S(R.string.device_with_no_network_at_all_so) +
                    S(R.string.and_if_you_are_not_expecting_it)
            )

            // The tunnel core's log level and performance profile: settings of a core URnetwork
            // does not run, so its page does not offer them.
            if (transport != Transport.URNETWORK) {
                SettingsSectionHeader(S(R.string.diagnostics))
                SettingsGroup {
                    SettingsRow(S(R.string.log_level), Icons.Default.Visibility, Ios.Yellow,
                        value = labelOf(LOG_LEVELS, logLevel), onClick = { push(P_LOG_LEVEL) })
                    Separator()
                    SettingsRow(S(R.string.performance_profile), Icons.Default.Memory, Ios.Orange,
                        value = labelOf(PERF_PROFILES, perfProfile), onClick = { push(P_PERF) })
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

// --- small shared pieces --------------------------------------------------------------------

@Composable
private fun Footer(text: String) {
    Text(
        text,
        color = Ios.SecondaryLabel,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
    )
}

/** The five manual shaping fields, offered wherever the named profiles apply. */
@Composable
private fun ManualShapingGroup(
    jc: String, jmin: String, jmax: String, i1: String, i2: String,
    push: (String) -> Unit,
) {
    SettingsSectionHeader(S(R.string.manual_shaping))
    SettingsGroup {
        SettingsRow(S(R.string.junk_packet_count), value = jc.ifBlank { S(R.string.from_the_profile) },
            onClick = { push(P_JC) })
        Separator()
        SettingsRow(S(R.string.smallest_junk), value = jmin.ifBlank { S(R.string.from_the_profile) },
            onClick = { push(P_JMIN) })
        Separator()
        SettingsRow(S(R.string.largest_junk), value = jmax.ifBlank { S(R.string.from_the_profile) },
            onClick = { push(P_JMAX) })
        Separator()
        SettingsRow(S(R.string.first_packet_prefix), value = i1.ifBlank { S(R.string.none_2) },
            onClick = { push(P_I1) })
        Separator()
        SettingsRow(S(R.string.second_packet_prefix), value = i2.ifBlank { S(R.string.none_2) },
            onClick = { push(P_I2) })
    }
    Footer(
        S(R.string.the_four_named_profiles_above_are_four) +
            S(R.string.this_is_that_space_the_core_validates) +
            S(R.string.a_floor_above_the_ceiling_is_rejected) +
            S(R.string.empty_to_let_the_named_profile_decide)
    )
}

@Composable
private fun ManualEndpointGroup(value: String, onClick: () -> Unit) {
    SettingsSectionHeader(S(R.string.manual_gateway))
    SettingsGroup {
        SettingsRow(S(R.string.pinned_address), Icons.Default.Link, Ios.Gray,
            value = value.ifBlank { S(R.string.automatic_scan) }, onClick = onClick)
    }
}

@Composable
private fun NumberPage(
    title: String,
    value: String,
    placeholder: String,
    onBack: () -> Unit,
    backLabel: String,
    footer: String,
    onValueChange: (String) -> Unit,
) {
    IosTextScreen(
        title = title,
        value = value,
        // Digits only. Everything here is a byte count or a packet count, and a field that
        // accepts letters just means the core refuses the config later with a message about
        // parsing rather than about the number.
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() }) },
        onBack = onBack,
        backLabel = backLabel,
        footer = footer,
        placeholder = placeholder,
        numeric = true,
    )
}

/**
 * A page for one Zero Trust credential.
 *
 * Written straight through to [SecureStore] rather than to preferences: a service token is a
 * credential, and the rest of this screen's storage is not encrypted.
 */
@Composable
private fun SecretPage(
    title: String,
    value: String,
    key: String,
    onBack: () -> Unit,
    backLabel: String,
    footer: String,
    onValueChange: (String) -> Unit,
) {
    val context = LocalContext.current
    IosTextScreen(
        title = title,
        value = value,
        onValueChange = { text ->
            onValueChange(text)
            SecureStore.putSecret(context, key, text.trim())
        },
        onBack = onBack,
        backLabel = backLabel,
        footer = footer,
    )
}

/** Show that a secret is set without showing the secret. */
private fun masked(value: String): String =
    if (value.isBlank()) S(R.string.not_set) else "••••••••"

/** "n مورد" for a comma-separated list, so the row says how much is in it without the content. */
private fun countLabel(raw: String): String {
    val n = raw.split(',', '\n').count { it.isNotBlank() }
    return if (n == 0) S(R.string.none_2) else fa(S(R.string.n_items, n))
}

private fun labelOf(options: List<IosOption>, key: String): String =
    options.firstOrNull { it.key == key }?.label ?: key

private fun fa(text: String): String {
    val digits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    return buildString { for (c in text) append(if (c in '0'..'9') digits[c - '0'] else c) }
}

// --- the option tables ----------------------------------------------------------------------
//
// Every `key` below is a literal the Rust core parses. They are not display strings and they are
// not free to tidy: `gool` is the core's own name for WARP-on-WARP, and `compatibility` is
// spelled out because the core matches the whole word.

private val MASQUE_TRANSPORTS = listOf(
    IosOption(com.mlmvpn.core.tunnel.MasqueCarrier.AUTO, S(R.string.masque_transport_auto), S(R.string.masque_transport_auto_sub)),
    IosOption("h3", S(R.string.http_3_over_quic), S(R.string.port_443_over_udp_faster_and_what)),
    IosOption("h2", S(R.string.http_2_over_tcp), S(R.string.port_443_over_tcp_with_tls_fragmentation)),
)

private val DISCOVERY_MODES = listOf(
    IosOption("cache", S(R.string.stored_gateways), S(R.string.reuses_what_worked_last_time_and_connects)),
    IosOption("fresh", S(R.string.fresh_scan), S(R.string.ignores_what_was_remembered_slower_and_the)),
)

private val SCAN_MODES = listOf(
    IosOption("turbo", S(R.string.turbo), S(R.string.connect_to_the_first_healthy_server_the)),
    IosOption("balanced", S(R.string.balanced), S(R.string.search_up_to_six_servers_and_take)),
    IosOption("thorough", S(R.string.full), S(R.string.slower_for_the_best_ping_available)),
    IosOption("stealth", S(R.string.stealth), S(R.string.the_send_rate_is_throttled_for_aggressive)),
)

private val WG_SCAN_MODES = SCAN_MODES + IosOption(
    "ironclad", S(R.string.guaranteed),
    S(R.string.builds_a_real_tunnel_to_each_candidate)
)

private val IP_FAMILIES = listOf(
    IosOption("v4", "IPv4", S(R.string.works_on_any_line)),
    IosOption("v6", "IPv6", S(R.string.when_the_gateway_s_ipv4_range_is)),
    IosOption("both", S(R.string.both), S(R.string.both_families_are_scanned_slower)),
)

private val SHAPING_PROFILES = listOf(
    IosOption("off", S(R.string.off_2), S(R.string.no_padding_and_no_timing_noise_the)),
    IosOption("light", S(R.string.light), S(R.string.the_least_reshaping_the_least_latency_cost)),
    IosOption("balanced", S(R.string.balanced), S(R.string.what_most_networks_want)),
    IosOption("aggressive", S(R.string.aggressive), S(R.string.the_most_cover_the_most_latency)),
)

private val TLS_PRESETS = listOf(
    IosOption("chrome", S(R.string.chrome), S(R.string.the_same_curves_real_chrome_announces)),
    IosOption("compatibility", S(R.string.compatibility), S(R.string.a_simpler_list_for_middleboxes_that_do)),
)

private val CHAIN_OUTER = listOf(
    IosOption("auto", S(R.string.automatic_2), S(R.string.try_the_list_from_the_top_the)),
    IosOption("masque", "MASQUE", S(R.string.it_has_its_own_two_transports_and)),
    IosOption("wireguard", "WireGuard", S(R.string.blocked_outright_on_some_carriers_instant_on)),
    IosOption("gool", S(R.string.warp_in_warp_2), S(R.string.two_tunnels_under_the_inner_one_so)),
)

private val TOR_MODES = listOf(
    IosOption("auto", S(R.string.automatic_2), S(R.string.tries_the_ladder_from_the_top_until)),
    IosOption("direct", S(R.string.direct_2), S(R.string.no_bridge_for_a_network_that_has)),
    IosOption("obfs4", "obfs4", S(R.string.a_bridge_that_hides_the_shape_of)),
    IosOption("meek", "Meek", S(R.string.rides_the_traffic_on_a_cdn_the)),
    IosOption("webtunnel", "WebTunnel", S(R.string.tor_mode_webtunnel_sub)),
    IosOption("snowflake", "Snowflake", S(R.string.volunteer_webrtc_proxies_the_last_resort)),
    IosOption("vanilla", S(R.string.tor_mode_vanilla), S(R.string.tor_mode_vanilla_sub)),
)

private val LOG_LEVELS = listOf(
    IosOption("error", S(R.string.errors_only), S(R.string.the_smallest_volume)),
    IosOption("warn", S(R.string.warning), ""),
    IosOption("info", S(R.string.info), S(R.string.the_default_and_the_right_one_for)),
    IosOption("debug", S(R.string.debug), S(R.string.for_working_out_why_a_connection_failed)),
    IosOption("trace", S(R.string.trace), S(R.string.a_line_per_candidate_very_high_volume)),
)

private val PERF_PROFILES = listOf(
    IosOption("auto", S(R.string.automatic_2), S(R.string.decides_from_the_core_count_and_the)),
    IosOption("low", S(R.string.low), S(R.string.for_a_phone_that_runs_hot_under)),
    IosOption("medium", S(R.string.medium), ""),
    IosOption("high", S(R.string.high), S(R.string.for_a_powerful_phone_that_automatic_reads)),
)

// Page ids for the internal stack.
private const val P_MASQUE_TRANSPORT = "masque_transport"
private const val P_DISCOVERY = "discovery"
private const val P_SCAN = "scan"
private const val P_IP = "ip"
private const val P_SHAPING = "shaping"
private const val P_TLS = "tls"
private const val P_MANUAL = "manual"
private const val P_CHAIN = "chain"
private const val P_CHAIN_TOR = "chain_tor"
private const val P_TOR_MODE = "tor_mode"
private const val P_LOG_LEVEL = "log_level"
private const val P_PERF = "perf"
private const val P_ROUTE_DIRECT = "route_direct"
private const val P_ROUTE_BLOCK = "route_block"
private const val P_JC = "jc"
private const val P_JMIN = "jmin"
private const val P_JMAX = "jmax"
private const val P_I1 = "i1"
private const val P_I2 = "i2"
private const val P_ZT_TEAM = "zt_team"
private const val P_ZT_CLIENT_ID = "zt_client_id"
private const val P_ZT_CLIENT_SECRET = "zt_client_secret"
private const val P_ZT_TOKEN = "zt_token"
private const val P_ZT_EMAIL = "zt_email"
