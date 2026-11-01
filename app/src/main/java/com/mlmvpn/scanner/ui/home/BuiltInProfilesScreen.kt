package com.mlmvpn.scanner.ui.home

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.data.IranProfileTester
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.data.ScanGuard
import com.mlmvpn.scanner.mitm.MitmProfile
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.ui.NodeNotice
import com.mlmvpn.scanner.ui.mitm.MitmSetupCard
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.LocalPort
import com.mlmvpn.scanner.utils.NetworkSettings

// =================================================================================================
// The two built-in profiles, each on the home screen with an icon of its own.
//
// They were both folders inside the connection list, which is the wrong shelf for either of them.
// A folder in that list is a place configs ARRIVE -- a panel deployed, a subscription imported, a
// scan combined -- and both of these are the opposite: they ship with the app, there is nothing to
// deploy, and neither has anything to do with the Cloudflare account or the panels the rest of
// that screen is about. Someone who needs the Iran defaults needs them on the day nothing else
// works, and "open the config list, find the right folder among the panels" is not a path anyone
// walks in that state.
//
// One screen serves both, because they are the same shape: a short list of built-in JSON profiles,
// tap to connect, tap again to stop. Domain fronting adds its setup wizard above the list, since
// its config cannot do anything until the certificate is trusted.
// =================================================================================================

/** The built-in Iran defaults, straight off the home screen. */
@Composable
fun IranConfigsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val port = remember { LocalPort.getString(context) }

    BuiltInProfileScreen(
        title = stringResource(R.string.home_iran),
        group = NodeManager.IRAN_GROUP,
        footer = stringResource(R.string.iran_home_footer),
        onBack = onBack,
        tester = { configs, onResult -> IranProfileTester.testAll(context, configs, onResult) },
        header = {
            // The one way these configs fail that is not the network's fault.
            //
            // They are upstream Serverless-for-Iran files, shipped untouched, and their inbound is
            // hardcoded to 10808 -- so in proxy mode a changed local port points at a port nothing
            // is listening on, and the config looks broken when the setting is. The connection
            // list already says this inside the folder; it has to be said here too, because this
            // screen is now the way most people will reach them.
            NodeNotice(
                wrong = port != "10808",
                title = if (port != "10808") stringResource(R.string.the_local_port_must_be_10808)
                else stringResource(R.string.keep_the_local_port_at_10808),
                body = if (port != "10808")
                    stringResource(R.string.your_local_port_is_set_to_userport, port)
                else
                    stringResource(R.string.these_configs_are_the_original_serverless_files),
            )
            Spacer(Modifier.height(14.dp))
        },
    )
}

/** Domain fronting: the wizard and the connection, in the order they have to happen. */
@Composable
fun DomainFrontingScreen(onBack: () -> Unit) {
    BuiltInProfileScreen(
        title = stringResource(R.string.home_fronting),
        group = MitmProfile.GROUP,
        footer = stringResource(R.string.fronting_home_footer),
        onBack = onBack,
        header = {
            // The wizard first, and the list under it, because the order is not cosmetic: the
            // config cannot carry anything until the certificate it terminates TLS with is one
            // this device trusts. A connect button above an unfinished wizard is a button whose
            // only outcome is failure.
            MitmSetupCard()
            Spacer(Modifier.height(14.dp))
        },
    )
}

/**
 * A short list of built-in configs, and the one thing anyone wants to do with them.
 *
 * Reads the live connection from the service rather than a local flag: these can be started from
 * the connection list or the tile as well, and a screen that kept its own idea of "connected"
 * would be wrong every time the user came back to it.
 */
@Composable
private fun BuiltInProfileScreen(
    title: String,
    group: String,
    footer: String,
    onBack: () -> Unit,
    header: @Composable () -> Unit = {},
    /**
     * Measure which of these profiles CAN work on this network, and badge the rows.
     *
     * Only «کانفیگ ایران» passes one: the serverless configs are the ones whose resolver can be
     * unreachable without anything on screen saying so. Domain fronting has a single config and a
     * wizard that already reports its own state, so a test button there would be a button that
     * answers a question nobody has.
     */
    tester: (suspend (Map<String, String>, (String, IranProfileTester.Verdict) -> Unit) -> Map<String, IranProfileTester.Verdict>)? = null,
) {
    val context = LocalContext.current
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val connectedId by MyVpnService.connectedNodeIdFlow.collectAsState()
    val storedNodes by NodeManager(context).nodesFlow.collectAsState()

    val profiles = remember(storedNodes, group) { storedNodes.filter { it.groupTitle == group } }

    // Which one this screen is trying to start. Held so the row can show a spinner on itself
    // rather than the screen showing one somewhere general.
    var starting by remember { mutableStateOf<String?>(null) }

    // What the measurement said about each row, and whether one is running.
    val verdicts = remember { mutableStateMapOf<String, IranProfileTester.Verdict>() }
    var testing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun runTest() {
        val run = tester ?: return
        if (testing) return
        testing = true
        tested = 0
        verdicts.clear()
        scope.launch {
            val configs = profiles.associate { it.id to it.uri }
            // Each row fills in as soon as its own config has been run.
            runCatching { run(configs) { id, v -> verdicts[id] = v; tested++ } }
            testing = false
        }
    }

    fun start(node: VpnNode) {
        com.mlmvpn.scanner.utils.SafeStart.service(context, 
            Intent(context, MyVpnService::class.java).apply {
                putExtra("NODE_URI", node.uri)
                putExtra("NODE_ID", node.id)
                putExtra("PROXY_MODE", NetworkSettings.proxyMode(context))
                putExtra("LOCAL_PORT", LocalPort.getString(context))
            }
        )
    }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = profiles.firstOrNull { it.id == starting }
        if (result.resultCode == Activity.RESULT_OK && pending != null) start(pending)
        else starting = null
    }

    fun connect(node: VpnNode) {
        // The same guard every other connect path goes through: a running scan owns the link, and
        // taking it away mid-scan turns the scan's result into a fake one.
        ScanGuard.run(ScanGuard.Reason.CONNECT_VPN) {
            starting = node.id
            val ask = runCatching { VpnService.prepare(context) }.getOrNull()
            if (ask != null) consent.launch(ask) else start(node)
        }
    }

    fun disconnect() {
        starting = null
        com.mlmvpn.scanner.utils.SafeStart.service(context, Intent(context, MyVpnService::class.java).apply { action = "STOP" })
    }

    IosScreen(title = title, onBack = onBack, backLabel = stringResource(R.string.emergency_3_back)) {
        Spacer(Modifier.height(14.dp))
        header()
        com.mlmvpn.scanner.ui.RouteChips()

        if (profiles.isEmpty()) {
            SettingsFooter(stringResource(R.string.builtin_profiles_none))
            Spacer(Modifier.height(28.dp))
            return@IosScreen
        }

        if (tester != null) {
            SettingsGroup {
                SettingsActionRow(
                    label = if (testing) stringResource(R.string.iran_test_running_n, tested, profiles.size)
                    else stringResource(R.string.iran_test_action),
                    icon = Icons.Default.Speed,
                    busy = testing,
                    onClick = { runTest() },
                )
            }
            SettingsFooter(stringResource(R.string.iran_test_footer))
            Spacer(Modifier.height(14.dp))
        }

        SettingsSectionHeader(stringResource(R.string.builtin_profiles_section))
        SettingsGroup {
            profiles.forEachIndexed { index, node ->
                if (index > 0) Separator()
                val live = connectedId == node.id && phase == MyVpnService.Phase.CONNECTED
                val busy = starting == node.id && !live && phase != MyVpnService.Phase.FAILED
                ProfileRow(
                    node = node,
                    connected = live,
                    busy = busy,
                    verdict = verdicts[node.id],
                    onClick = { if (live) disconnect() else connect(node) },
                )
            }
        }
        SettingsFooter(footer)
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * One profile.
 *
 * The whole row is the switch, and the mark on the leading edge carries the state -- a tick when
 * this is the one carrying traffic, a spinner while it comes up, the plain glyph otherwise. No
 * separate connect button: there is exactly one thing to do with a row on this screen, and giving
 * it a target smaller than the row only makes it harder to hit.
 */
@Composable
private fun ProfileRow(
    node: VpnNode,
    connected: Boolean,
    busy: Boolean,
    verdict: IranProfileTester.Verdict? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Ios.Blue,
                    strokeWidth = 2.dp,
                )
                connected -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Ios.Green,
                    modifier = Modifier.size(20.dp),
                )
                else -> Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    tint = Ios.SecondaryLabel,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                node.name,
                color = Ios.Label,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // While connected the row says what it is doing; otherwise a measurement, when there
            // is one, outranks «برای اتصال بزنید» — the user pressed a button to get exactly that.
            val measuredLine: String? = when {
                connected || busy -> null
                verdict == null -> null
                verdict.level == IranProfileTester.Level.OK -> stringResource(R.string.iran_test_row_ok, verdict.ms)
                verdict.level == IranProfileTester.Level.PARTIAL -> stringResource(R.string.iran_test_row_partial)
                else -> stringResource(R.string.iran_test_row_bad, verdict.why)
            }
            Text(
                measuredLine ?: stringResource(
                    when {
                        connected -> R.string.builtin_profile_connected
                        busy -> R.string.builtin_profile_connecting
                        else -> R.string.builtin_profile_tap_to_connect
                    }
                ),
                color = when {
                    connected -> Ios.Green
                    measuredLine != null && verdict?.level == IranProfileTester.Level.OK -> Ios.Green
                    measuredLine != null && verdict?.level == IranProfileTester.Level.PARTIAL -> Ios.Orange
                    measuredLine != null -> Ios.Red
                    else -> Ios.SecondaryLabel
                },
                fontSize = 12.sp,
                fontWeight = if (connected) FontWeight.Medium else FontWeight.Normal,
            )
        }

        Icon(
            Icons.Default.PowerSettingsNew,
            contentDescription = null,
            tint = if (connected) Ios.Red else Ios.SecondaryLabel,
            modifier = Modifier.size(18.dp),
        )
    }
}
