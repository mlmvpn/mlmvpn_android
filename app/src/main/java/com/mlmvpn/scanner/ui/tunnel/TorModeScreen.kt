package com.mlmvpn.scanner.ui.tunnel

import androidx.compose.runtime.Composable
import com.mlmvpn.scanner.ui.settings.IosOption
import com.mlmvpn.scanner.ui.settings.IosPickerScreen
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * How Tor is to reach the network.
 *
 * The one control that matters when Tor will not connect, which is why it sits on Tor's own
 * screen rather than behind advanced settings: hiding it there would hide the answer.
 *
 * The order of the automatic ladder -- direct, meek, obfs4, snowflake -- is not the order of
 * this list, and both are deliberate. The ladder is ordered by measured time-to-connect on a
 * hostile carrier; the list is ordered from least to most exotic, because that is how someone
 * reads it when deciding what their own network is doing to them.
 */
@Composable
fun TorModeScreen(
    selected: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    backLabel: String,
) {
    IosPickerScreen(
        title = S(R.string.tor_connection_mode_2),
        options = OPTIONS,
        selectedKey = selected.ifBlank { "auto" },
        onSelect = onSelect,
        onBack = onBack,
        backLabel = backLabel,
        footer = S(R.string.the_automatic_ladder_runs_in_this_order) +
            S(R.string.the_logic_is_that_where_direct_works) +
            S(R.string.faster_meek_connects_almost_everywhere_because_to) +
            S(R.string.cdn_but_every_cell_costs_an_http) +
            S(R.string.thing_a_serious_censor_scans_for_and) +
            S(R.string.it_has_to_find_a_volunteer_proxy),
    )
}

private val OPTIONS = listOf(
    IosOption(
        "auto", S(R.string.automatic_3),
        S(R.string.tries_the_ladder_from_the_top_until_2),
    ),
    IosOption(
        "direct", S(R.string.direct_3),
        S(R.string.no_bridge_straight_to_the_tor_network),
    ),
    IosOption(
        "obfs4", "obfs4",
        S(R.string.a_bridge_that_hides_the_shape_of_2),
    ),
    IosOption(
        "meek", "Meek",
        S(R.string.rides_the_traffic_on_a_cdn_the_2),
    ),
    IosOption(
        "webtunnel", "WebTunnel",
        S(R.string.tor_mode_webtunnel_sub),
    ),
    IosOption(
        "snowflake", "Snowflake",
        S(R.string.volunteer_webrtc_proxies_the_last_resort_when),
    ),
    IosOption(
        "vanilla", S(R.string.tor_mode_vanilla),
        S(R.string.tor_mode_vanilla_sub),
    ),
)
