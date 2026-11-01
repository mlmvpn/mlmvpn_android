package com.mlmvpn.scanner.ui.windscribe

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.windscribe.*
import com.mlmvpn.scanner.ui.settings.*
import java.text.DateFormat
import java.util.Date

@Composable
fun WindscribeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = WindscribeEngine
    LaunchedEffect(Unit) { engine.init(context) }
    val data by engine.data.collectAsState()
    val connection by engine.connection.collectAsState()
    val busy by engine.busy.collectAsState()
    val error by engine.error.collectAsState()
    val results by engine.results.collectAsState()
    val challenge by engine.challenge.collectAsState()
    val sent by engine.emailSent.collectAsState()
    val protocols by engine.supportedProtocols.collectAsState()
    val loginGeneration by engine.loginGeneration.collectAsState()
    var accountsPage by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf(false) }
    var protocolDialog by remember { mutableStateOf(false) }
    var emailDialog by remember { mutableStateOf(false) }
    var permissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(data.activeId) { if (data.active?.sessionValid == true && !busy) engine.refresh() }
    LaunchedEffect(loginGeneration) { if (loginGeneration > 0) login = false }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = permissionAction
        permissionAction = null
        if (result.resultCode == Activity.RESULT_OK && action != null) action()
    }
    /** Anything that brings up the VPN asks Android's permission first, once. */
    fun withVpn(action: () -> Unit) {
        com.mlmvpn.scanner.data.ScanGuard.run(com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN) {
            val intent = VpnService.prepare(context)
            if (intent == null) action() else { permissionAction = action; consent.launch(intent) }
        }
    }
    fun connect(again: Boolean = false) = withVpn { engine.connect(context, searchAgain = again) }
    // A phone with no browser (TV boxes) has nothing to open the page with: said, not a crash (1.2.41).
    fun webRegister() {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(engine.SIGN_UP_URL))) }
        catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, context.getString(R.string.studio_sub_page_no_browser), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    BackHandler(accountsPage) { accountsPage = false }
    IosScreen(title = stringResource(if (accountsPage) R.string.windscribe_accounts else R.string.windscribe_title),
        onBack = { if (accountsPage) accountsPage = false else onBack() }, backLabel = stringResource(R.string.home), scrollable = false) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                // The bar above already names the page: no logo or second title here.
                if (!accountsPage) Spacer(Modifier.height(24.dp))
                if (data.storageError) SettingsFooter(stringResource(R.string.windscribe_error_storage))
                // An account operation's failure; a connection's is shown under the button.
                error?.let { SettingsFooter(stringResource(errorRes(it))) }
            }
            if (accountsPage) {
                items(data.accounts, key = { it.id }) { account ->
                    SettingsGroup {
                        SettingsRow(title = account.email ?: account.username, icon = if (account.id == data.activeId) Icons.Default.CheckCircle else Icons.Default.Person,
                            tint = Ios.Blue, subtitle = accountSummary(account), value = if (account.id == data.activeId) stringResource(R.string.windscribe_active) else null,
                            onClick = if (busy) null else { { engine.selectAccount(account.id) } })
                        Separator()
                        SettingsRow(stringResource(R.string.windscribe_remove), icon = Icons.Default.Logout, tint = Ios.Red,
                            onClick = if (busy) null else { { engine.logout(account.id) } })
                    }
                }
                item { Action(stringResource(R.string.windscribe_add_account), !busy && !data.storageError) { login = true } }
                item { Action(stringResource(R.string.windscribe_register), !busy) { webRegister() } }
            } else {
                if (data.accounts.isEmpty() || data.active == null) {
                    item { Action(stringResource(R.string.windscribe_login), !busy && !data.storageError) { login = true } }
                    item {
                        SettingsGroup {
                            SettingsRow(title = stringResource(R.string.windscribe_register), icon = Icons.Default.PersonAdd, tint = Ios.Blue, onClick = { webRegister() })
                            if (data.accounts.isNotEmpty()) {
                                Separator()
                                SettingsRow(title = stringResource(R.string.windscribe_accounts), icon = Icons.Default.People, tint = Ios.Blue, onClick = { accountsPage = true })
                            }
                        }
                    }
                } else {
                    item {
                        val running = connection.phase in setOf(WsPhase.CONNECTING, WsPhase.CONNECTED, WsPhase.DISCONNECTING)
                        ConnectButton(connected = connection.phase == WsPhase.CONNECTED, busy = running && connection.phase != WsPhase.CONNECTED,
                            enabled = running || !busy) { if (running) engine.disconnect() else connect() }
                        Spacer(Modifier.height(12.dp))
                        Text(connectionLabel(connection), color = if (connection.phase == WsPhase.ERROR) Ios.Orange else Ios.SecondaryLabel,
                            fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                        Spacer(Modifier.height(6.dp))
                        com.mlmvpn.scanner.ui.RouteChips()
                    }
                    item {
                        val active = data.active!!
                        SettingsGroup {
                            SettingsRow(title = active.email ?: active.username, icon = Icons.Default.Person, tint = Ios.Blue,
                                value = stringResource(if (active.premium) R.string.windscribe_paid else R.string.windscribe_free), subtitle = accountSummary(active), showChevron = false)
                            Separator()
                            SettingsRow(title = stringResource(R.string.windscribe_refresh), icon = Icons.Default.Refresh, tint = Ios.Blue, onClick = if (busy) null else { { engine.refresh() } })
                            if (!active.verified) {
                                Separator()
                                SettingsRow(title = stringResource(if (active.email.isNullOrBlank()) R.string.windscribe_add_email else R.string.windscribe_verify_email), icon = Icons.Default.Email,
                                    tint = Ios.Orange, onClick = if (busy) null else { { if (active.email.isNullOrBlank()) emailDialog = true else engine.sendVerification() } })
                            }
                        }
                        if (!active.sessionValid) {
                            SettingsFooter(stringResource(R.string.windscribe_error_session))
                            Action(stringResource(R.string.windscribe_login), !busy) { login = true }
                        }
                        if (sent && !active.verified) SettingsFooter(stringResource(R.string.windscribe_email_sent))
                        SettingsFooter(stringResource(R.string.windscribe_checked, timestamp(active.checkedAt)))
                        active.lastReset?.let { SettingsFooter(stringResource(R.string.windscribe_last_reset, it)) }
                    }
                    item {
                        SettingsGroup {
                            SettingsRow(title = stringResource(R.string.windscribe_protocol), icon = Icons.Default.Tune, tint = Ios.Blue,
                                value = data.protocol?.let { protocolName(it) } ?: stringResource(R.string.windscribe_auto), onClick = if (busy) null else { { protocolDialog = true } })
                            Separator()
                            SettingsRow(title = stringResource(R.string.windscribe_search_again), icon = Icons.Default.Autorenew, tint = Ios.Orange,
                                onClick = if (busy) null else { { connect(again = true) } })
                            Separator()
                            SettingsRow(title = stringResource(R.string.windscribe_accounts), icon = Icons.Default.People, tint = Ios.Blue, onClick = { accountsPage = true })
                        }
                        SettingsFooter(stringResource(R.string.windscribe_methods_hint))
                    }
                    // «تست همه»: every server with every protocol, then one clear table.
                    item {
                        SettingsSectionHeader(stringResource(R.string.windscribe_test_title))
                        val testing = connection.testing && connection.phase == WsPhase.CONNECTING
                        SettingsGroup {
                            if (testing) TestProgress(connection, data.servers) { engine.disconnect() }
                            else {
                                val picked = data.servers.firstOrNull { it.id == data.selectedServer }
                                // One server with every protocol: about a minute.
                                if (picked != null) {
                                    SettingsRow(title = stringResource(R.string.windscribe_test_one, picked.city), icon = Icons.Default.NetworkCheck, tint = Ios.Blue,
                                        subtitle = stringResource(R.string.windscribe_test_one_sub),
                                        onClick = if (busy) null else { { withVpn { engine.connect(context, benchmark = true, all = true, allOf = picked.dcId) } } })
                                    Separator()
                                }
                                SettingsRow(title = stringResource(R.string.windscribe_test_all), icon = Icons.Default.Speed, tint = Ios.Green,
                                    subtitle = stringResource(R.string.windscribe_test_all_sub, data.servers.distinctBy { it.dcId }.size),
                                    onClick = if (busy) null else { { withVpn { engine.connect(context, benchmark = true, all = true) } } })
                            }
                        }
                        if (results.isNotEmpty() && !testing) SettingsFooter(stringResource(R.string.windscribe_test_footer))
                    }
                    val byServer = results.groupBy { it.method.serverId }.entries
                        .sortedWith(compareByDescending<Map.Entry<String, List<WsMeasurement>>> { e -> e.value.any { it.success } }
                            .thenComparator { a, b -> WsPlanner.SCORE.compare(b.value.filter { it.success }.maxWithOrNull(WsPlanner.SCORE) ?: a.value.first(),
                                a.value.filter { it.success }.maxWithOrNull(WsPlanner.SCORE) ?: b.value.first()) })
                    WsPlanner.best(results)?.let { best ->
                        item(key = "best") {
                            val server = data.servers.firstOrNull { it.id == best.method.serverId }
                            SettingsGroup {
                                SettingsRow(title = stringResource(R.string.windscribe_test_best, server?.let { com.mlmvpn.scanner.ui.tunnel.CountryLabel.withFlag(it.country) + " · " + it.city }.orEmpty(), protocolName(best.method.protocol)),
                                    icon = Icons.Default.EmojiEvents, tint = Ios.Orange, subtitle = resultLine(best, speed = true),
                                    onClick = if (busy) null else { { withVpn { engine.connect(context, exact = best.method) } } })
                            }
                        }
                    }
                    byServer.forEach { (serverId, rows) ->
                        item(key = "srv-$serverId") {
                            val server = data.servers.firstOrNull { it.id == serverId }
                            Text(server?.let { com.mlmvpn.scanner.ui.tunnel.CountryLabel.withFlag(it.country) + " · " + it.city }.orEmpty(),
                                color = Ios.SecondaryLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 14.dp, bottom = 6.dp))
                            SettingsGroup {
                                rows.sortedWith(compareByDescending<WsMeasurement> { it.success }.thenComparator { a, b -> WsPlanner.SCORE.compare(b, a) })
                                    .forEachIndexed { i, r ->
                                        if (i > 0) Separator()
                                        SettingsRow(title = protocolName(r.method.protocol),
                                            icon = if (r.success) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                            tint = if (r.success) Ios.Green else Ios.Gray,
                                            value = if (r.success) r.downloadBps?.let { speedText(it) } ?: "—" else null,
                                            subtitle = resultLine(r),
                                            onClick = if (busy || !r.success) null else { { withVpn { engine.connect(context, exact = r.method) } } })
                                    }
                            }
                        }
                    }
                    item {
                        SettingsSectionHeader(stringResource(R.string.windscribe_servers))
                        SettingsGroup {
                            SettingsRow(title = stringResource(R.string.windscribe_best_server), icon = Icons.Default.AutoAwesome, tint = Ios.Blue,
                                value = if (data.selectedServer == null) stringResource(R.string.windscribe_selected) else null,
                                onClick = if (busy) null else { { engine.selectServer(null) } })
                        }
                        if (data.serverCheckedAt > 0) SettingsFooter(stringResource(R.string.windscribe_cached, timestamp(data.serverCheckedAt)))
                    }
                    // Group nodes by datacenter; picking a location uses its least-loaded advertised node.
                    items(data.servers.groupBy { it.dcId }.values.sortedWith(compareBy<List<WsServer>> { it.first().country }.thenBy { it.first().city }), key = { it.first().dcId }) { nodes ->
                        val server = nodes.minBy { it.load ?: 100 }
                        SettingsGroup {
                            SettingsRow(title = "${server.country} · ${server.city}", icon = Icons.Default.Public, tint = Ios.Blue,
                                subtitle = server.nick.takeIf { it.isNotBlank() }, value = if (nodes.any { it.id == data.selectedServer }) stringResource(R.string.windscribe_selected) else null,
                                onClick = if (busy) null else { { engine.selectServer(server.id) } })
                        }
                    }
                    if (data.servers.isEmpty()) item { SettingsFooter(stringResource(R.string.windscribe_no_servers)) }
                }
            }
        }
    }
    if (login) LoginDialog(busy, error, onDismiss = { if (!busy) { login = false; engine.cancelLogin() } }, onLogin = { user, pass, two -> engine.login(user, pass, two) })
    if (challenge != null) CaptchaDialog(challenge!!, busy, onDismiss = { engine.cancelLogin() }, onRefresh = { engine.refreshCaptcha() }, onAnswer = { engine.answerCaptcha(it) })
    if (protocolDialog) AlertDialog(onDismissRequest = { protocolDialog = false }, containerColor = Ios.DialogSurface,
        title = { Text(stringResource(R.string.windscribe_protocol)) }, text = {
            Column {
                TextButton(onClick = { engine.selectProtocol(null); protocolDialog = false }) { Text(stringResource(R.string.windscribe_auto)) }
                WsProtocol.values().filter { protocol -> protocol in protocols && data.ports[protocol].orEmpty().isNotEmpty() && data.servers.any { server ->
                    (data.selectedServer == null || server.id == data.selectedServer) &&
                        when(protocol) {
                            WsProtocol.WIREGUARD -> server.publicKey.isNotBlank() && server.ip3.isNotBlank()
                            WsProtocol.STEALTH -> server.x509.isNotBlank() && server.ip3.isNotBlank()
                            WsProtocol.WSTUNNEL -> server.x509.isNotBlank() && server.ip.isNotBlank()
                            else -> server.x509.isNotBlank() && server.ip2.isNotBlank()
                        }
                } }.forEach { protocol -> TextButton(onClick = { engine.selectProtocol(protocol); protocolDialog = false }) { Text(protocolName(protocol)) } }
                Text(stringResource(R.string.windscribe_protocol_limits), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { protocolDialog = false }) { Text(stringResource(R.string.windscribe_close)) } })
    if (emailDialog) EmailDialog(onDismiss = { emailDialog = false }, onSubmit = { engine.addEmail(it); emailDialog = false })
}

@Composable private fun Action(text: String, enabled: Boolean, click: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Ios.Blue else Ios.Blue.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = click)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** The same round power button as FLUX: gray when off, orange while it searches, green when connected. */
@Composable private fun ConnectButton(connected: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ring = when { connected -> Ios.Green; busy -> Ios.Orange; else -> Ios.Gray }
    val label = stringResource(if (connected || busy) R.string.windscribe_disconnect_short else R.string.windscribe_connect)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.size(132.dp).clip(CircleShape).background(ring.copy(alpha = 0.18f)).border(3.dp, ring, CircleShape)
                .semantics { contentDescription = label }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = ring, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
@Composable private fun accountSummary(account: WsAccount): String {
    val quota = if (account.remaining != null) stringResource(R.string.windscribe_quota, bytes(account.remaining!!), bytes(account.used ?: 0), bytes(account.quota ?: 0))
        else stringResource(R.string.windscribe_quota_unknown)
    return quota + "\n" + stringResource(if (account.verified) R.string.windscribe_verified else R.string.windscribe_unverified) +
        (if (account.status != 1) "\n" + stringResource(R.string.windscribe_error_account) else "")
}
private fun bytes(value: Long) = String.format(java.util.Locale.getDefault(), "%.2f GB", value / 1_000_000_000.0)
private fun timestamp(value: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
@Composable private fun protocolName(protocol: WsProtocol) = stringResource(when (protocol) {
    WsProtocol.WIREGUARD -> R.string.windscribe_wg
    WsProtocol.OPENVPN_UDP -> R.string.windscribe_ovpn_udp
    WsProtocol.OPENVPN_TCP -> R.string.windscribe_ovpn_tcp
    WsProtocol.STEALTH -> R.string.windscribe_stealth
    WsProtocol.WSTUNNEL -> R.string.windscribe_wstunnel
})
@Composable private fun methodName(method: WsMethod): String = stringResource(R.string.windscribe_method, protocolName(method.protocol), method.port,
    stringResource(when { method.cf -> R.string.windscribe_via_cf; method.padding -> R.string.windscribe_padding; else -> R.string.windscribe_direct }))
@Composable private fun connectionLabel(connection: WsConnection): String = when (connection.phase) {
    WsPhase.DISCONNECTED -> stringResource(R.string.windscribe_disconnected)
    WsPhase.CONNECTING -> if (connection.testing) stringResource(R.string.windscribe_testing, connection.attempt, connection.total) else (if (connection.total > 0) stringResource(R.string.windscribe_connecting, connection.attempt, connection.total)
        else stringResource(R.string.windscribe_preparing)) + (connection.method?.let { "\n" + methodName(it) } ?: "")
    WsPhase.CONNECTED -> stringResource(R.string.windscribe_connected) + (connection.method?.let { "\n" + methodName(it) } ?: "")
    WsPhase.DISCONNECTING -> stringResource(R.string.windscribe_disconnecting)
    WsPhase.ERROR -> stringResource(errorRes(connection.error ?: WsError.NETWORK))
}
/** «در حال تست ۱۲ از ۱۰۵ · پاریس · Stealth», a bar, and Stop. */
@Composable private fun TestProgress(connection: WsConnection, servers: List<WsServer>, onStop: () -> Unit) {
    val server = connection.method?.let { m -> servers.firstOrNull { it.id == m.serverId } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.windscribe_testing, connection.attempt, connection.total), color = Ios.Label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.windscribe_stop), color = Ios.Red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onStop).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = if (connection.total > 0) connection.attempt.toFloat() / connection.total else 0f,
            color = Ios.Green, trackColor = Color.White.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
        Spacer(Modifier.height(8.dp))
        Text(listOfNotNull(server?.city, connection.method?.let { protocolName(it.protocol) }).joinToString(" · "), color = Ios.SecondaryLabel, fontSize = 13.sp)
    }
}

private fun speedText(bps: Long) = String.format(java.util.Locale.getDefault(), "%.1f Mbps", bps / 1_000_000.0)

/** «پینگ ۲۱۰ میلی‌ثانیه · پایداری ۱۰۰٪ · از راه کلادفلر», or «وصل نشد». */
@Composable private fun resultLine(r: WsMeasurement, speed: Boolean = false): String {
    if (!r.success) return stringResource(R.string.windscribe_failed)
    val parts = listOfNotNull(
        r.downloadBps?.takeIf { speed }?.let { stringResource(R.string.windscribe_result_speed, speedText(it)) },
        r.latencyMs?.let { stringResource(R.string.windscribe_result_ping, it) },
        stringResource(R.string.windscribe_result_stability, r.successfulSamples * 100 / r.samples.coerceAtLeast(1)),
        if (r.method.cf) stringResource(R.string.windscribe_via_cf) else null,
    )
    return parts.joinToString(" · ")
}

@Composable private fun measurementLabel(result: WsMeasurement): String {
    if (!result.success) return stringResource(R.string.windscribe_failed)
    val base = stringResource(R.string.windscribe_sample, result.latencyMs ?: 0, result.connectMs, result.successfulSamples, result.samples)
    return base + (result.downloadBps?.let { "\n" + stringResource(R.string.windscribe_speed, it / 1_000_000.0) } ?: "")
}
private fun errorRes(error: WsError) = when (error) {
    WsError.NETWORK -> R.string.windscribe_error_network
    WsError.AUTH -> R.string.windscribe_error_auth
    WsError.SESSION_EXPIRED -> R.string.windscribe_error_session
    WsError.TWO_FA -> R.string.windscribe_error_2fa
    WsError.CAPTCHA -> R.string.windscribe_error_captcha
    WsError.ACCOUNT_UNAVAILABLE -> R.string.windscribe_error_account
    WsError.QUOTA -> R.string.windscribe_error_quota
    WsError.STORAGE -> R.string.windscribe_error_storage
    WsError.PROTOCOL -> R.string.windscribe_error_protocol
    WsError.CONFIG -> R.string.windscribe_error_config
    WsError.TIMEOUT -> R.string.windscribe_error_timeout
    WsError.CORE -> R.string.windscribe_error_core
    WsError.TESTED_NOT_CONNECTED -> R.string.windscribe_error_tested
}
@Composable private fun LoginDialog(busy: Boolean, error: WsError?, onDismiss: () -> Unit, onLogin: (String, String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var twoFa by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Ios.DialogSurface, title = { Text(stringResource(R.string.windscribe_login)) },
        text = { Column {
            OutlinedTextField(username, { username = it }, singleLine = true, label = { Text(stringResource(R.string.windscribe_username)) })
            OutlinedTextField(password, { password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text(stringResource(R.string.windscribe_password)) })
            OutlinedTextField(twoFa, { twoFa = it }, singleLine = true, label = { Text(stringResource(R.string.windscribe_two_fa)) })
            error?.let { Text(stringResource(errorRes(it))) }
        } }, confirmButton = {
            TextButton(onClick = { onLogin(username, password, twoFa); password = "" }, enabled = !busy && username.isNotBlank() && password.isNotEmpty()) { Text(stringResource(R.string.windscribe_login)) }
        }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.windscribe_close)) } })
}
@Composable private fun CaptchaDialog(challenge: com.mlmvpn.scanner.engines.windscribe.WsLoginChallenge, busy: Boolean,
                                      onDismiss: () -> Unit, onRefresh: () -> Unit, onAnswer: (String) -> Unit) {
    var answer by remember(challenge) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Ios.DialogSurface, title = { Text(stringResource(R.string.windscribe_captcha)) },
        text = { Column {
            Text(stringResource(R.string.windscribe_captcha_help), style = MaterialTheme.typography.bodySmall)
            CaptchaArt(challenge.ascii, Modifier.fillMaxWidth().padding(vertical = 8.dp))
            if (challenge.wrong) Text(stringResource(R.string.windscribe_captcha_wrong), color = Ios.Orange, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(answer, { answer = it }, singleLine = true, label = { Text(stringResource(R.string.windscribe_captcha_answer)) })
            TextButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.windscribe_captcha_new)) }
        } }, confirmButton = { TextButton(onClick = { onAnswer(answer) }, enabled = !busy && answer.isNotBlank()) { Text(stringResource(R.string.windscribe_login)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.windscribe_close)) } })
}

/**
 * The challenge is a small black-and-white picture whose pixels happen to be written as characters
 * (`*` set, space clear). Laid out as text, each cell takes a glyph's width and a line's height --
 * about 1 to 2.5 -- and the letters stretch until they no longer read (user report 2026-10-11:
 * «ریخته بهمه»). Drawn as square cells, filled where the character is not blank, they keep the
 * shape they were drawn with.
 */
@Composable private fun CaptchaArt(art: String, modifier: Modifier = Modifier) {
    val rows = remember(art) { art.replace("\r", "").replace("\t", "    ").lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() } }
    val columns = remember(rows) { rows.maxOfOrNull { it.length } ?: 0 }
    if (rows.isEmpty() || columns == 0) return
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp),
        contentAlignment = Alignment.Center) {
        val cell = minOf(maxWidth / columns, 200.dp / rows.size)
        androidx.compose.foundation.Canvas(Modifier.size(cell * columns, cell * rows.size)) {
            val px = cell.toPx()
            rows.forEachIndexed { y, line ->
                var start = -1
                for (x in 0..line.length) {
                    val set = x < line.length && !line[x].isWhitespace()
                    if (set && start < 0) start = x
                    if (!set && start >= 0) {
                        // One rectangle per run: no hairline seams between neighbouring cells.
                        drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(start * px, y * px),
                            size = androidx.compose.ui.geometry.Size((x - start) * px, px))
                        start = -1
                    }
                }
            }
        }
    }
}
@Composable private fun EmailDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Ios.DialogSurface, title = { Text(stringResource(R.string.windscribe_add_email)) },
        text = { OutlinedTextField(email, { email = it }, singleLine = true, label = { Text(stringResource(R.string.windscribe_email)) }) },
        confirmButton = { TextButton(onClick = { onSubmit(email) }, enabled = email.contains('@')) { Text(stringResource(R.string.windscribe_add_email)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.windscribe_close)) } })
}
