package com.mlmvpn.scanner.ui.emergency

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.gst.GstConfigManager
import com.mlmvpn.scanner.engines.gst.GstDiagnostics
import com.mlmvpn.scanner.engines.gst.GstLog
import com.mlmvpn.scanner.engines.gst.GstRelay
import com.mlmvpn.scanner.ui.home.HomeDestinations
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.therealaleph.mhrv.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mlmvpn.scanner.utils.S

/** The NODE_ID this feature claims on [MyVpnService], and how the panel knows the tunnel is its. */
private const val GST_NODE_ID = "GST_EMERGENCY"

/** Where the panel currently is. Pushed pages, not dialogs -- see GstScreens.kt. */
private sealed class GstPage {
    object Panel : GstPage()
    object NetworkPath : GstPage()
    object Log : GstPage()
    data class Relay(val index: Int) : GstPage()
}

/**
 * The Google Apps Script tunnel, rebuilt on the app's own design.
 *
 * What it was: a Material top bar with an ArrowBack and an overflow menu holding four unrelated
 * things, a `LazyColumn` of `Card`s, one `Card` per relay carrying a pencil and a bin as adjacent
 * icon buttons, a 120dp circular `Button` welded to the bottom of the column, and three dialogs
 * -- an AlertDialog log, a full-bleed Dialog with a TabRow over two checkbox lists, and a second
 * AlertDialog listing relays that needed authorizing. Next to Settings, MASQUE, Tor and «ضد فیلتر
 * SNI» it read as a different application.
 *
 * What it is now, in the order a user asks the questions: am I connected (the dial, which is most
 * of the screen), can HTTPS actually work (the certificate, because nothing else matters until it
 * can), which relays do I have and are they alive, and what can I change. Each of the three old
 * dialogs became a pushed page, which is what lets the row that opens it show what it holds --
 * the log's line count, the number of names and addresses selected -- without opening anything.
 *
 * ## What did NOT change
 *
 * Every engine call is the one that was here: the same `{"type":"gst"}` NODE_URI on
 * [MyVpnService], the same Proxy Mode and Local Port passthrough, the same STOP action, the same
 * [GstDiagnostics.testDeployment] probe, the same CA priming and export, and the same
 * [GstConfigManager] storage. The wizard is still the only way a relay is created.
 *
 * The batch-authorize dialog is the one piece deliberately not carried over. It appeared after a
 * test, listed "تأیید رله ۱/۲/۳" as buttons with no way to tell which relay was which, and closed
 * for good if you dismissed it. The verdict now lands on the relay row it belongs to and stays
 * there, and the authorize action lives on that relay's own page.
 */
@Composable
fun EmergencyLevel2Screen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var relays by remember { mutableStateOf(GstConfigManager.getRelays(context)) }
    var page by remember { mutableStateOf<GstPage>(GstPage.Panel) }

    // First run, or a panel left with nothing configured, opens the wizard directly.
    var showWizard by remember { mutableStateOf(relays.none { it.deploymentId.isNotBlank() }) }
    var wizardEditIndex by remember { mutableStateOf<Int?>(null) }

    // Last probe per relay, keyed by relay id so a reorder or a removal cannot mis-attribute one.
    val reports = remember { mutableStateMapOf<String, GstDiagnostics.Report>() }
    var testingAll by remember { mutableStateOf(false) }
    var testingId by remember { mutableStateOf<String?>(null) }

    var certInstalled by remember { mutableStateOf(false) }
    var priming by remember { mutableStateOf(false) }

    val isRunning by MyVpnService.isRunningFlow.collectAsState()
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val activeNode by MyVpnService.connectedNodeIdFlow.collectAsState()
    val logLines by GstLog.lines.collectAsState()

    // Whether the tunnel currently up is OURS. `connectedNodeId` is assigned in the same statement
    // that sets Phase.CONNECTING, so this is true from the first frame of a connect attempt.
    val ownsTunnel = activeNode == GST_NODE_ID
    val stage = when {
        !ownsTunnel -> DialState.IDLE
        phase == MyVpnService.Phase.CONNECTING -> DialState.STARTING
        phase == MyVpnService.Phase.CONNECTED -> DialState.RUNNING
        phase == MyVpnService.Phase.FAILED -> DialState.FAILED
        else -> DialState.IDLE
    }
    val takenByOther = isRunning && !ownsTunnel

    val configured = relays.filter { it.deploymentId.isNotBlank() }

    // The certificate can be installed and revoked from outside the app, so it is re-checked every
    // time the screen comes back to the foreground rather than once at composition.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                certInstalled = isCaInstalled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { certInstalled = isCaInstalled(context) }

    // ---- engine ----------------------------------------------------------------------------

    fun startGstService() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val startIntent = Intent(context, MyVpnService::class.java).apply {
            putExtra("NODE_URI", "{\"type\":\"gst\"}")
            putExtra("MTU_PROFILE", com.mlmvpn.scanner.utils.NetworkSettings.Method.GST.id)
            putExtra("NODE_ID", GST_NODE_ID)
            putExtra("PROXY_MODE", com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(context))
            putExtra("LOCAL_PORT", com.mlmvpn.scanner.utils.LocalPort.getString(context))
        }
        com.mlmvpn.scanner.utils.SafeStart.service(context, startIntent)
    }

    val vpnPrepare = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res -> if (res.resultCode == Activity.RESULT_OK) startGstService() }

    fun connect() {
        if (configured.isEmpty()) {
            shortToast(context, S(R.string.create_a_relay_first))
            return
        }
        if (!certInstalled) {
            shortToast(context, S(R.string.without_the_certificate_https_sites_will_not))
        }
        val consent = runCatching { VpnService.prepare(context) }.getOrNull()
        if (consent != null) vpnPrepare.launch(consent) else startGstService()
    }

    fun disconnect() {
        // Just stop. This used to be followed by a deliberate relaunch of the whole app, because
        // the tun2proxy core calls exit(255) from native code a few seconds after a normal
        // teardown. tun2proxy now runs in the :tun process (Tun2proxyHostService), so that exit
        // takes down only that process and this one carries on.
        com.mlmvpn.scanner.utils.SafeStart.service(context, Intent(context, MyVpnService::class.java).apply { action = "STOP" })
        shortToast(context, S(R.string.disconnecting_2))
    }

    fun testRelay(relay: GstRelay) {
        if (testingId != null || testingAll) return
        testingId = relay.id
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                GstDiagnostics.testForTunnel(context, relay)
            }
            reports[relay.id] = report
            testingId = null
        }
    }

    fun testAll() {
        if (configured.isEmpty()) {
            shortToast(context, S(R.string.there_is_no_relay_to_measure_yet))
            return
        }
        if (testingAll) return
        testingAll = true
        scope.launch {
            GstLog.i("RelayTest", S(R.string.testing_relays, configured.size))
            val measured = configured
                .mapIndexed { index, relay ->
                    async(Dispatchers.IO) {
                        val report = GstDiagnostics.testForTunnel(context, relay)
                        GstLog.i("RelayTest", S(R.string.relay_2, index + 1, report.message))
                        relay.id to report
                    }
                }
                .awaitAll()
            measured.forEach { (id, report) -> reports[id] = report }
            testingAll = false
            val ok = measured.count { it.second.result == GstDiagnostics.Result.OK }
            shortToast(
                context,
                S(R.string.of_relays_are_healthy, faDigits(ok.toString()), faDigits(configured.size.toString())),
            )
        }
    }

    fun removeRelay(index: Int) {
        val updated = relays.toMutableList()
        val removed = updated.removeAt(index)
        relays = updated
        reports.remove(removed.id)
        GstConfigManager.saveRelays(context, updated)
    }

    // ---- wizard takes over the whole screen -------------------------------------------------

    if (showWizard) {
        GstSetupWizard(
            sharedAuthKey = relays.firstOrNull()?.authKey ?: "",
            editRelay = wizardEditIndex?.let { relays.getOrNull(it) },
            onComplete = { relay ->
                val updated = relays.toMutableList()
                val index = wizardEditIndex
                if (index != null && index < updated.size) {
                    updated[index] = relay
                } else {
                    val blank = updated.indexOfFirst { it.deploymentId.isBlank() }
                    if (blank >= 0) updated[blank] = relay else updated.add(relay)
                }
                relays = updated
                GstConfigManager.saveRelays(context, updated)
                // A relay that just changed has no verdict any more; the old one described a
                // different deployment id.
                reports.remove(relay.id)
                wizardEditIndex = null
                showWizard = false
                page = GstPage.Panel
            },
            onClose = {
                showWizard = false
                wizardEditIndex = null
                // Cancelled the very first setup with nothing configured: there is no panel to
                // show, so leave.
                if (relays.none { it.deploymentId.isNotBlank() }) onBack()
            },
        )
        return
    }

    // ---- pushed pages -----------------------------------------------------------------------

    BackHandler(enabled = page != GstPage.Panel) { page = GstPage.Panel }

    when (val current = page) {
        is GstPage.NetworkPath -> {
            GstNetworkPathScreen(onBack = { page = GstPage.Panel })
            return
        }
        is GstPage.Log -> {
            GstLogScreen(onBack = { page = GstPage.Panel })
            return
        }
        is GstPage.Relay -> {
            val relay = relays.getOrNull(current.index)
            if (relay == null) {
                page = GstPage.Panel
            } else {
                GstRelayScreen(
                    number = current.index + 1,
                    relay = relay,
                    report = reports[relay.id],
                    testing = testingId == relay.id || testingAll,
                    onTest = { testRelay(relay) },
                    onEdit = { wizardEditIndex = current.index; showWizard = true },
                    onRemove = { removeRelay(current.index) },
                    onBack = { page = GstPage.Panel },
                )
                return
            }
        }
        else -> Unit
    }

    // ---- the panel --------------------------------------------------------------------------

    IosScreen(
        title = stringResource(R.string.home_emergency_2),
        onBack = onBack,
        backLabel = S(R.string.home_2),
    ) {
        Spacer(Modifier.height(18.dp))

        Text(
            "Google Apps Script",
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            S(R.string.traffic_goes_through_a_script_on_google) +
                S(R.string.without_blocking_google_itself),
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 34.dp),
        )

        Spacer(Modifier.height(26.dp))

        EmergencyDial(
            state = stage,
            idleAccent = HomeDestinations.Blue,
            idleIcon = Icons.Default.FlashOn,
            idleLabel = S(R.string.connect_2),
            runningLabel = S(R.string.disconnect_3),
            onClick = {
                when (stage) {
                    DialState.RUNNING -> disconnect()
                    DialState.STARTING -> disconnect()
                    else -> connect()
                }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(18.dp))

        EmergencyStatusLine(
            state = stage,
            idleAccent = HomeDestinations.Blue,
            headline = when {
                takenByOther -> S(R.string.another_connection_is_active)
                stage == DialState.RUNNING -> S(R.string.connected_3)
                stage == DialState.STARTING -> S(R.string.connecting_4)
                stage == DialState.FAILED -> S(R.string.could_not_connect_2)
                else -> S(R.string.ready_3)
            },
            sub = when {
                takenByOther ->
                    S(R.string.tapping_the_button_disconnects_it_and_brings_2)
                stage == DialState.RUNNING && !certInstalled ->
                    S(R.string.the_tunnel_is_up_but_https_sites)
                stage == DialState.FAILED ->
                    S(R.string.tap_measure_all_relays_to_find_out)
                else -> null
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(24.dp))

        // The certificate comes first, and not because it is the most interesting thing here.
        // The tunnel terminates TLS itself, so without this installed every HTTPS site fails --
        // which used to look to the user like the tunnel not working at all.
        SettingsSectionHeader(S(R.string.security_certificate))
        SettingsGroup {
            SettingsRow(
                title = if (certInstalled) S(R.string.installed_2) else S(R.string.not_installed),
                icon = if (certInstalled) Icons.Default.VerifiedUser else Icons.Default.Security,
                tint = if (certInstalled) Ios.Green else Ios.Orange,
                subtitle = if (certInstalled) {
                    S(R.string.https_sites_open_correctly)
                } else {
                    S(R.string.without_it_https_sites_will_not_open)
                },
                showChevron = false,
            )
            if (!certInstalled) {
                Separator()
                SettingsActionRow(
                    label = if (priming) S(R.string.building_the_certificate) else S(R.string.install_the_certificate_on_the_phone),
                    icon = Icons.Default.Download,
                    busy = priming,
                    onClick = {
                        priming = true
                        scope.launch {
                            val ok = primeCaCertificate(context)
                            priming = false
                            if (ok) {
                                installCaCertificate(context)
                            } else {
                                shortToast(context, S(R.string.could_not_build_the_certificate_connect_once))
                            }
                        }
                    },
                )
            }
        }
        if (!certInstalled) {
            SettingsFooter(
                S(R.string.this_tunnel_opens_and_closes_tls_itself) +
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                        S(R.string.gst_cert_direct_install_footer)
                    } else {
                        S(R.string.certificate_the_file_is_saved_to_downloads) + S(R.string.install_that_file_from_there)
                    }
            )
        }

        // The count leads rather than trailing after a separator: a Latin-neutral "·" wedged
        // between two Persian words gets walked to the wrong end by bidi, which is how
        // "رله‌های گوگل · ۳ رله" rendered as "رله ۳ · رله‌های گوگل".
        SettingsSectionHeader(
            if (configured.isEmpty()) S(R.string.google_relays)
            else S(R.string.google_relays_2, faDigits(configured.size.toString()))
        )
        SettingsGroup {
            if (relays.isEmpty()) {
                SettingsRow(
                    title = S(R.string.no_relay_added_yet),
                    titleColor = Ios.SecondaryLabel,
                    showChevron = false,
                )
                Separator()
            }
            relays.forEachIndexed { index, relay ->
                val report = reports[relay.id]
                SettingsRow(
                    title = S(R.string.relay_3, faDigits((index + 1).toString())),
                    icon = Icons.Default.Cloud,
                    // The glyph IS the health lamp: grey until measured, then green, orange or red.
                    tint = relayTone(report),
                    subtitle = relay.deploymentId.takeIf { it.isNotBlank() }
                        ?.let { shortDeploymentId(it) } ?: S(R.string.no_id),
                    value = if (testingAll || testingId == relay.id) "…" else relayVerdict(report),
                    onClick = { page = GstPage.Relay(index) },
                )
                Separator()
            }
            SettingsActionRow(
                label = S(R.string.add_a_relay_another_google_account),
                icon = Icons.Default.Add,
                onClick = { wizardEditIndex = null; showWizard = true },
            )
            Separator()
            SettingsActionRow(
                label = if (testingAll) S(R.string.measuring_4) else S(R.string.measure_all_relays),
                icon = Icons.Default.NetworkCheck,
                busy = testingAll,
                enabled = configured.isNotEmpty(),
                onClick = { testAll() },
            )
        }
        SettingsFooter(
            S(R.string.each_relay_is_a_script_on_a) +
                S(R.string.account_so_three_accounts_mean_three_times) +
                S(R.string.the_healthy_relays_and_moves_to_the)
        )

        // Only once something has actually been measured. A permanent row saying "0 unhealthy"
        // trains the user to stop reading it.
        AnimatedVisibility(
            visible = reports.values.any { it.result == GstDiagnostics.Result.REDIRECT_BLOCKED }
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                SettingsFooter(
                    S(R.string.one_or_more_relays_exist_but_have) +
                        S(R.string.and_tap_verify_in_the_browser_sign) +
                        S(R.string.review_permissions_advanced_and_allow_in_that)
                )
            }
        }

        SettingsSectionHeader(S(R.string.settings_2))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.network_route_2),
                icon = Icons.Default.Route,
                tint = Ios.Indigo,
                value = S(R.string.names, faDigits(GstConfigManager.getSelectedSniList(context).size.toString())) +
                    S(R.string.ips, faDigits(GstConfigManager.getSelectedCleanIpList(context).size.toString())),
                onClick = { page = GstPage.NetworkPath },
            )
            Separator()
            SettingsRow(
                title = S(R.string.live_report_2),
                icon = Icons.Default.Article,
                tint = Ios.Teal,
                value = logLines.size.takeIf { it > 0 }?.let { faDigits(it.toString()) },
                onClick = { page = GstPage.Log },
            )
        }
        SettingsFooter(
            S(R.string.if_it_will_not_connect_run_measure) +
                S(R.string.the_deploy_or_authorize_step_in_the)
        )

        Spacer(Modifier.height(28.dp))
    }
}

// =================================================================================================
// Certificate plumbing
//
// Unchanged from the previous version of this screen, and deliberately so: the priming trick below
// is load-bearing and the reasons are in its own comment.
// =================================================================================================

/**
 * Ensures the MITM CA (filesDir/ca/ca.crt) exists so it can be installed WITHOUT the user
 * having to run a full VPN connection first. The CA is only minted as a side effect of the
 * native core starting, so if it's missing we briefly boot the core in proxy mode (localhost
 * listeners only — no VpnService/TUN, hence no consent and none of the tun2proxy teardown),
 * wait for the cert file to appear, then stop. No-op (returns true) once the cert exists.
 */
suspend fun primeCaCertificate(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
    val caFile = java.io.File(context.filesDir, "ca/ca.crt")
    if (caFile.exists()) return@withContext true
    // Don't touch the core while a real tunnel is running.
    if (MyVpnService.isRunningFlow.value) return@withContext caFile.exists()
    var handle = 0L
    try {
        Native.setDataDir(context.filesDir.absolutePath)
        // Minimal config on an unlikely-to-conflict port; a dummy script_id is fine because
        // we only need the core to boot far enough to mint the CA, not to relay traffic.
        val cfg = """
            [relay]
            mode = "apps_script"
            script_id = ["CA_PRIMING_PLACEHOLDER"]
            auth_key = "ca_priming"
            youtube_via_relay = true

            [network]
            google_ip = "${GstConfigManager.DEFAULT_GOOGLE_IP}"
            front_domain = "www.google.com"
            listen_host = "127.0.0.1"
            socks5_port = 39917
            listen_port = 49917
            verify_ssl = true

            [logging]
            log_level = "error"
        """.trimIndent()
        handle = Native.startProxy(cfg)
        var waited = 0
        while (!caFile.exists() && waited < 3000) {
            kotlinx.coroutines.delay(100); waited += 100
        }
    } catch (e: Exception) {
        GstLog.e("CertPrime", "priming failed: ${e.message}")
    } finally {
        if (handle != 0L) try { Native.stopProxy(handle) } catch (_: Exception) {}
    }
    caFile.exists()
}

/**
 * Exports the MITM CA (generated by the GST core at filesDir/ca/ca.crt) to Downloads and
 * opens the system security settings so the user can install it as a trusted CA.
 */
suspend fun installCaCertificate(context: android.content.Context) {
    try {
        val caFile = java.io.File(context.filesDir, "ca/ca.crt")
        if (!caFile.exists()) {
            shortToast(context, S(R.string.no_certificate_has_been_built_tap_the))
            return
        }
        // Below Android 11 the system's own installer takes the certificate directly -- the same
        // route the domain-fronting certificate uses. No file, no storage permission.
        //
        // The Downloads copy below it failed on Android 8/9: writing the public Downloads folder
        // directly needs WRITE_EXTERNAL_STORAGE, which the app does not hold, so users saw only
        // an error toast (reported 2026-09-29 from Android 8.0 phones).
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            val intent = android.security.KeyChain.createInstallIntent().apply {
                putExtra(android.security.KeyChain.EXTRA_CERTIFICATE, caFile.readBytes())
                putExtra(android.security.KeyChain.EXTRA_NAME, "MLM VPN Google Script CA")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }
        // Off the main thread: MediaStore is a provider in another process, and its delete and
        // insert waited on it for seconds (freeze and ANR reports, 1.2.41).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolver = context.contentResolver
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "MLMVPN_CA.crt")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val sel = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?"
            resolver.delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel, arrayOf("MLMVPN_CA.crt"))
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { u ->
                resolver.openOutputStream(u)?.use { out -> out.write(caFile.readBytes()) }
            }
        } else {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val exportedCert = java.io.File(downloadsDir, "MLMVPN_CA.crt")
            caFile.copyTo(exportedCert, overwrite = true)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(exportedCert.absolutePath), arrayOf("application/x-x509-ca-cert"), null)
        }
        }
        android.widget.Toast.makeText(
            context,
            S(R.string.the_certificate_was_saved_to_downloads_please),
            android.widget.Toast.LENGTH_LONG,
        ).show()
        val intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } catch (e: Exception) {
        shortToast(context, S(R.string.error, e.message))
    }
}

private fun isCaInstalled(context: android.content.Context): Boolean {
    try {
        val caFile = java.io.File(context.filesDir, "ca/ca.crt")
        if (!caFile.exists()) return false

        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val ourCert = cf.generateCertificate(caFile.inputStream()) as java.security.cert.X509Certificate
        val ourFingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(ourCert.encoded)

        val ks = java.security.KeyStore.getInstance("AndroidCAStore")
        ks.load(null)
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val cert = ks.getCertificate(alias) ?: continue
            val encoded = try { cert.encoded } catch (e: Exception) { continue }
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
            if (fingerprint.contentEquals(ourFingerprint)) return true
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return false
}
