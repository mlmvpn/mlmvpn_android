package com.mlmvpn.scanner.ui.tunnel

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.mlmvpn.core.tunnel.PsiphonRegions
import com.mlmvpn.core.tunnel.TorRegions
import com.mlmvpn.core.tunnel.TunnelPreferences
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * Everything one transport owns, behind one home icon.
 *
 * The five icons on the home screen route here and nowhere else, so the app's navigation table
 * gains five entries rather than the twenty-odd this feature actually has. Pages that only exist
 * inside a transport -- its advanced settings, its country picker, the connection log -- live on
 * this local stack instead, where back pops one page at a time and the last pop hands control
 * back to the caller.
 */
@Composable
fun TransportHost(transport: Transport, onExit: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { TunnelPreferences(context) }
    val stack = remember { mutableStateListOf<String>() }

    fun push(page: String) = stack.add(page)
    fun pop() { stack.removeLastOrNull() }
    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    // Register before the first screen paints so a tunnel started from the Quick Settings tile
    // or restored after a reboot is already reflected when the user opens an icon.
    LaunchedEffect(Unit) { TunnelController.attach(context) }

    // Same threshold as Settings and the transport screen itself: the width at which a page fits
    // BESIDE the control instead of on top of it.
    val twoPane = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720

    // Each sub-page defined once and rendered in whichever place the screen has room for -- over
    // the top on a phone, in the second column on a tablet or a television. Writing them twice
    // would be two lists of pages to keep in step, and the second one would rot.
    val detail: (@Composable () -> Unit)? = when (stack.lastOrNull()) {
        // Geph's settings are its engine's config, not the tunnel core's: its own page.
        PAGE_ADVANCED -> if (transport == Transport.GEPH) {
            { com.mlmvpn.scanner.ui.geph.GephSettingsScreen(onBack = ::pop, backLabel = transport.labelFa) }
        } else if (transport == Transport.CFWG) {
            { com.mlmvpn.scanner.ui.cfwarp.CfWarpSettingsScreen(onBack = ::pop, backLabel = transport.labelFa) }
        } else {
            { TransportAdvancedScreen(transport = transport, onBack = ::pop) }
        }

        com.mlmvpn.scanner.ui.geph.GephPages.ACCOUNT -> {
            { com.mlmvpn.scanner.ui.geph.GephAccountScreen(onBack = ::pop, backLabel = transport.labelFa) }
        }

        com.mlmvpn.scanner.ui.geph.GephPages.EXIT -> {
            { com.mlmvpn.scanner.ui.geph.GephExitScreen(onBack = ::pop, backLabel = transport.labelFa) }
        }

        com.mlmvpn.scanner.ui.geph.GephPages.NEWS -> {
            { com.mlmvpn.scanner.ui.geph.GephNewsScreen(onBack = ::pop, backLabel = transport.labelFa) }
        }

        com.mlmvpn.scanner.ui.geph.GephPages.SESSIONS -> {
            { com.mlmvpn.scanner.ui.geph.GephSessionsScreen(onBack = ::pop, backLabel = transport.labelFa) }
        }

        PAGE_LOG -> {
            { TunnelLogScreen(onBack = ::pop) }
        }

        com.mlmvpn.scanner.ui.cfwarp.NAT64_EXIT_PAGE -> {
            { com.mlmvpn.scanner.ui.cfwarp.Nat64ExitScreen(onBack = ::pop, backLabel = transport.labelFa, transport = transport) }
        }

        PAGE_PSIPHON_REGION -> {
            {
                RegionPickerScreen(
                    title = S(R.string.exit_country_2),
                    memoryKey = "psiphon_region",
                    codes = PsiphonRegions.options(context),
                    selected = prefs.egressRegion,
                    label = { CountryLabel.localized(it) },
                    detail = { CountryLabel.psiphonDetail(it) },
                    footer = S(R.string.this_is_a_preference_egressregion_inside_psiphon) +
                        S(R.string.every_server_outside_that_country_is_dropped) +
                        S(R.string.for_a_whole_session_closes_the_very) +
                        S(R.string.carrier_so_the_app_makes_one_short),
                    onSelect = { prefs.egressRegion = it },
                    onBack = ::pop,
                    backLabel = transport.labelFa,
                    benchmarkable = true,
                )
            }
        }

        PAGE_TOR_REGION -> {
            {
                RegionPickerScreen(
                    title = S(R.string.exit_country_2),
                    memoryKey = "tor_region",
                    codes = TorRegions.options(),
                    selected = prefs.torExitRegion,
                    label = { CountryLabel.localized(it) },
                    detail = { CountryLabel.torDetail(it) },
                    footer = S(R.string.strictnodes_0_is_set_here_the_country) +
                        S(R.string.tor_cannot_build_a_circuit_in_that) +
                        S(R.string.each_country_because_that_number_is_the) +
                        S(R.string.because_a_country_with_one_or_two),
                    onSelect = { prefs.torExitRegion = it },
                    onBack = ::pop,
                    backLabel = transport.labelFa,
                )
            }
        }

        TOR_BRIDGES_PAGE -> {
            { TorBridgesScreen(onBack = ::pop, backLabel = transport.labelFa) }
        }

        PsiphonPages.METHOD -> {
            { PsiphonMethodScreen(onBack = ::pop, backLabel = transport.labelFa, onOpenPage = { push(it) }) }
        }

        PsiphonPages.PROXY -> {
            { PsiphonProxyScreen(onBack = ::pop, backLabel = transport.labelFa) }
        }

        PsiphonPages.CLEAN -> {
            { PsiphonCleanIpScreen(onBack = ::pop, backLabel = transport.labelFa) }
        }

        PsiphonPages.PAIRING -> {
            { PsiphonPairingScreen(onBack = ::pop, backLabel = transport.labelFa) }
        }

        com.mlmvpn.scanner.ui.urnetwork.UrnetworkPages.LOCATIONS -> {
            { com.mlmvpn.scanner.ui.urnetwork.UrnetworkLocationsScreen(onBack = ::pop, backLabel = transport.labelFa) }
        }

        com.mlmvpn.scanner.ui.urnetwork.UrnetworkPages.RESTORE -> {
            { com.mlmvpn.scanner.ui.urnetwork.UrnetworkRestoreScreen(onBack = ::pop, backLabel = transport.labelFa) }
        }

        PAGE_TOR_MODE -> {
            {
                TorModeScreen(
                    selected = prefs.torMode,
                    onSelect = { prefs.torMode = it },
                    onBack = ::pop,
                    backLabel = transport.labelFa,
                )
            }
        }

        else -> null
    }

    // A phone shows the sub-page and nothing else, exactly as before.
    if (detail != null && !twoPane) {
        detail()
        return
    }

    TransportScreen(
        transport = transport,
        onBack = onExit,
        onOpenAdvanced = { push(PAGE_ADVANCED) },
        onOpenLog = { push(PAGE_LOG) },
        onOpenPsiphonRegion = { push(PAGE_PSIPHON_REGION) },
        onOpenTorRegion = { push(PAGE_TOR_REGION) },
        onOpenTorMode = { push(PAGE_TOR_MODE) },
        onOpenPage = { push(it) },
        detail = detail,
    )
}

private const val PAGE_ADVANCED = "advanced"
private const val PAGE_LOG = "log"
private const val PAGE_PSIPHON_REGION = "psiphon_region"
private const val PAGE_TOR_REGION = "tor_region"
private const val PAGE_TOR_MODE = "tor_mode"
