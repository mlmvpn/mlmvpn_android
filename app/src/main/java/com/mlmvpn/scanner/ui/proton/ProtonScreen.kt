package com.mlmvpn.scanner.ui.proton

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.SslErrorHandler
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.amnezia.AmzBrain
import com.mlmvpn.scanner.engines.amnezia.AmzConnect
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.engines.amnezia.AmzServer
import com.mlmvpn.scanner.engines.flux.FluxEngine
import com.mlmvpn.scanner.engines.flux.core.model.FluxUiState
import com.mlmvpn.scanner.engines.proton.ProtonAccess
import com.mlmvpn.scanner.engines.proton.ProtonApi
import com.mlmvpn.scanner.engines.proton.ProtonEngine
import com.mlmvpn.scanner.ui.amnezia.DelayCapsule
import com.mlmvpn.scanner.ui.amnezia.fa
import com.mlmvpn.scanner.ui.amnezia.groupFlag
import com.mlmvpn.scanner.ui.amnezia.groupName
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.tunnel.TunnelController
import com.mlmvpn.scanner.ui.tunnel.TunnelStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Proxy

private val ProtonPurple = Color(0xFF6D4AFF)

private enum class Page { MAIN, WEB }

/**
 * «Proton VPN»: sign in to (or make) a Proton account on Proton's own page inside the app, pick
 * free servers there, and the app takes the WireGuard configs that page makes and connects with
 * them. See [ProtonEngine] for why nothing of the user's password or verification reaches the app,
 * and [ProtonAccess] for how the page is reached when Proton is filtered: any engine that is on
 * carries it, and is turned off again before Proton itself connects.
 */
@Composable
fun ProtonScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(ProtonEngine.isInitialized()) }
    LaunchedEffect(Unit) {
        if (!ready) { withContext(Dispatchers.IO) { ProtonEngine.init(context) }; ready = true }
    }
    if (!ready) {
        IosScreen(title = stringResource(R.string.proton_title), onBack = onBack, backLabel = stringResource(R.string.home)) {}
        return
    }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var url by rememberSaveable { mutableStateOf(ProtonEngine.SIGN_IN_URL) }
    BackHandler(enabled = page == Page.WEB) { page = Page.MAIN }

    // A config that was refused: the reason is shown here and the notice consumed, so «آمنزیا»
    // never shows it later.
    val notice by AmzEngine.notice.collectAsState()
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        val text = when (n) {
            is AmzEngine.Notice.Imported -> context.getString(R.string.proton_added)
            AmzEngine.Notice.ImportNoPeer -> context.getString(R.string.amz_import_no_peer)
            AmzEngine.Notice.ImportFailed, is AmzEngine.Notice.ImportWg -> context.getString(R.string.proton_file_bad)
            else -> null
        }
        if (text != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            AmzEngine.consumeNotice()
        }
    }

    when (page) {
        Page.WEB -> ProtonWebPage(
            url = url,
            onBack = { page = Page.MAIN },
            onDone = { switch -> page = Page.MAIN; if (switch) ProtonEngine.switchToProton(context) },
        )
        Page.MAIN -> ProtonHome(
            onBack = onBack,
            onSignIn = { url = ProtonEngine.SIGN_IN_URL; page = Page.WEB },
            onSignUp = { url = ProtonEngine.SIGN_UP_URL; page = Page.WEB },
        )
    }
}

/** The VPN permission, asked once before an engine is turned on. */
@Composable
private fun rememberVpnGate(onDenied: () -> Unit = {}): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) after?.invoke() else onDenied()
        after = null
    }
    return { action ->
        com.mlmvpn.scanner.data.ScanGuard.run(com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN) {
            val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
            if (prep != null) { after = action; launcher.launch(prep) } else action()
        }
    }
}

// ── the main page ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProtonHome(onBack: () -> Unit, onSignIn: () -> Unit, onSignUp: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ids by ProtonEngine.ids.collectAsState()
    val switching by ProtonEngine.switching.collectAsState()
    val state by AmzEngine.store.state.collectAsState()
    val net by AmzEngine.net.collectAsState()
    val conn by AmzEngine.connect.collectAsState()
    val pending by AmzEngine.pending.collectAsState()
    val progress by AmzEngine.progress.collectAsState()
    val protonPinging by ProtonEngine.pinging.collectAsState()
    val running by MyVpnService.isRunningFlow.collectAsState()
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val tun by TunnelController.state.collectAsState()
    val fluxUi by FluxEngine.ui.collectAsState()
    val mine = remember(state.servers, ids) { ProtonEngine.servers(state.servers, ids) }
    val stats = remember(state, net) { state.statsFor(net) }
    // A whole account is a long list: the ones that answer on this network first, fastest first,
    // then the untested, then the ones that did not answer.
    val sorted = remember(mine, stats) {
        mine.sortedWith(compareBy<AmzServer>(
            { s -> stats[s.id].let { st -> if (st != null && st.alive) 0 else if (st == null || !st.tested) 1 else 2 } },
            { s -> stats[s.id]?.takeIf { it.alive }?.lastMs ?: Long.MAX_VALUE },
        ))
    }
    // Another engine is still on from the sign-in: Proton cannot run inside it.
    val helperOn = remember(running, phase, tun.stage, fluxUi) { ProtonAccess.current() != null }

    val tried by ProtonEngine.tried.collectAsState()
    val activeId = when (val c = conn) { is AmzConnect.Connecting -> c.serverId; is AmzConnect.Connected -> c.serverId; is AmzConnect.Failed -> c.serverId; else -> null }
    // A failure before any server was picked names none; it is still this screen's when Proton asked.
    val ours = if (activeId != null) activeId in ids else tried && conn is AmzConnect.Failed
    val connected = ours && conn is AmzConnect.Connected
    val busy = (ours && conn is AmzConnect.Connecting) || switching

    val withVpn = rememberVpnGate()
    var askSignOut by remember { mutableStateOf(false) }
    // A session can outlive the servers (all deleted by hand): signing out stays possible then too.
    // Asked off the main thread: the first cookie read of a process starts the whole WebView, and
    // on the main thread that froze the Proton screen as it opened (freeze and ANR reports, 1.2.41).
    var signedIn by remember(mine) { mutableStateOf(false) }
    LaunchedEffect(mine) { signedIn = withContext(Dispatchers.IO) { ProtonApi.signedIn() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) { uris.forEach { importUri(context, it) } }
    }
    val pickFile = {
        try { picker.launch(arrayOf("*/*")) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, context.getString(R.string.amz_no_file_picker), Toast.LENGTH_LONG).show() }
    }

    IosScreen(onBack = onBack, backLabel = stringResource(R.string.home)) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_app_proton), contentDescription = null, modifier = Modifier.size(84.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.proton_title), color = Ios.Label, fontSize = 32.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.proton_tagline), color = Ios.SecondaryLabel, fontSize = 13.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        )
        com.mlmvpn.scanner.ui.RouteChips()
        Spacer(Modifier.height(14.dp))

        if (mine.isEmpty()) {
            PrimaryButton(stringResource(R.string.proton_sign_in), onClick = onSignIn)
            SettingsFooter(stringResource(R.string.proton_sign_in_footer))
            SettingsGroup {
                SettingsActionRow(label = stringResource(R.string.proton_sign_up), icon = Icons.Default.PersonAdd, tint = ProtonPurple, onClick = onSignUp)
            }
        } else {
            Spacer(Modifier.height(10.dp))
            if (helperOn && !switching) {
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.proton_helper_on), subtitle = stringResource(R.string.proton_helper_on_sub),
                        icon = Icons.Default.Bolt, tint = Ios.Orange, showChevron = false,
                    )
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton(stringResource(R.string.proton_switch_now), tone = Ios.Orange) { ProtonEngine.switchToProton(context) }
                Spacer(Modifier.height(18.dp))
            } else {
                PrimaryButton(
                    stringResource(when { busy -> R.string.proton_cancel; connected -> R.string.proton_disconnect; else -> R.string.proton_connect }),
                    tone = when { connected -> Ios.Green; busy -> Ios.Orange; else -> ProtonPurple },
                ) {
                    if (connected || busy) ProtonEngine.disconnect(context) else withVpn { ProtonEngine.connect(context) }
                }
                Spacer(Modifier.height(12.dp))
            }
            val byId = remember(mine) { mine.associateBy { it.id } }
            Text(
                if (switching) stringResource(R.string.proton_switching) else statusText(conn, ours, activeId?.let { byId[it] }),
                color = if (ours && conn is AmzConnect.Failed) Ios.Orange else Ios.SecondaryLabel,
                fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )

            SettingsSectionHeader(stringResource(R.string.proton_servers))
            SettingsGroup {
                // At the top, not under 111 rows: the one action that applies to the whole list. While it
                // runs it shows how far it is, how many answered, and which way it is trying.
                if (progress.running && protonPinging) PingProgress(progress) else SettingsActionRow(
                    label = stringResource(R.string.proton_ping_all),
                    icon = Icons.Default.Speed, tint = Ios.Green,
                ) { ProtonEngine.pingAll(context) }
                sorted.forEach { s ->
                    Separator()
                    val st = stats[s.id]
                    ServerRow(
                        s, active = ours && s.id == activeId,
                        ms = st?.takeIf { it.alive }?.lastMs,
                        testing = protonPinging && s.id in pending,
                        failed = st != null && st.tested && !st.alive,
                        onClick = { withVpn { ProtonEngine.connect(context, s.id) } },
                        onDelete = { ProtonEngine.remove(context, s.id) },
                    )
                }
                Separator()
                SettingsActionRow(label = stringResource(R.string.proton_add_more), icon = Icons.Default.AddCircle, tint = ProtonPurple, onClick = onSignIn)
            }
            SettingsFooter(stringResource(R.string.proton_free_footer))
        }

        SettingsSectionHeader(stringResource(R.string.proton_fallback_title))
        SettingsGroup {
            SettingsActionRow(label = stringResource(R.string.proton_open_browser), icon = Icons.Default.OpenInBrowser, tint = Ios.Blue) { openInBrowser(context) }
            Separator()
            SettingsActionRow(label = stringResource(R.string.proton_pick_file), icon = Icons.Default.FileOpen, tint = Ios.Teal, onClick = pickFile)
            if (mine.isNotEmpty() || signedIn) {
                Separator()
                SettingsActionRow(label = stringResource(R.string.proton_sign_out), icon = Icons.Default.Logout, tint = Ios.Red) { askSignOut = true }
            }
        }
        // Signing out takes the account's servers out of the list too, so it is asked first.
        if (askSignOut) {
            AlertDialog(
                onDismissRequest = { askSignOut = false },
                title = { Text(stringResource(R.string.proton_sign_out_title)) },
                text = { Text(stringResource(R.string.proton_sign_out_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        askSignOut = false
                        ProtonEngine.signOut(context)
                        signedIn = false
                        Toast.makeText(context, context.getString(R.string.proton_signed_out), Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.proton_sign_out_action), color = Ios.Red) }
                },
                dismissButton = { TextButton(onClick = { askSignOut = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        SettingsFooter(stringResource(R.string.proton_fallback_footer))
        Spacer(Modifier.height(40.dp))
    }
}

/** «تست ۲۳ از ۱۱۱ · ۸ سالم», a bar, the way being tried, and Stop. */
@Composable
private fun PingProgress(progress: com.mlmvpn.scanner.engines.amnezia.AmzTestProgress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.amz_testing, fa(progress.done.toString()), fa(progress.total.toString()), fa(progress.alive.toString())),
                color = Ios.Label, fontSize = 15.sp, modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.amz_stop), color = Ios.Red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { AmzEngine.stopTest() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = if (progress.total > 0) progress.done.toFloat() / progress.total else 0f,
            color = Ios.Green, trackColor = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(when (progress.via) {
                "u6" -> R.string.proton_ping_via_u6
                "v6" -> R.string.proton_ping_via_v6
                "cf" -> R.string.proton_ping_via_cf
                "u4" -> R.string.proton_ping_via_u4
                "v4" -> R.string.proton_ping_via_v4
                "all" -> R.string.proton_ping_via_all
                else -> R.string.proton_ping_starting
            }),
            color = Ios.SecondaryLabel, fontSize = 13.sp,
        )
    }
}

@Composable
private fun ServerRow(server: AmzServer, active: Boolean, ms: Long?, testing: Boolean, failed: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val key = AmzBrain.group(server)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text(groupFlag(key), fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(server.name, color = if (active) Ios.Green else Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(groupName(key), color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            testing -> Text("…", color = Ios.SecondaryLabel, fontSize = 16.sp)
            ms != null -> DelayCapsule(ms)
            failed -> Text("—", color = Ios.Red, fontSize = 16.sp)
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).semantics { contentDescription = "delete" }.clickable(role = Role.Button, onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Close, contentDescription = null, tint = Ios.SecondaryLabel, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun statusText(conn: AmzConnect, ours: Boolean, server: AmzServer?): String {
    if (!ours) return stringResource(R.string.proton_status_idle)
    return when (conn) {
        AmzConnect.Idle -> stringResource(R.string.proton_status_idle)
        is AmzConnect.Connecting -> stringResource(when (conn.step) {
            AmzConnect.Step.PREPARING -> R.string.amz_status_preparing
            AmzConnect.Step.STARTING -> R.string.amz_status_starting
            AmzConnect.Step.HANDSHAKE -> R.string.amz_status_handshake
            AmzConnect.Step.VERIFYING -> R.string.amz_status_verifying
        })
        is AmzConnect.Connected -> listOfNotNull(
            stringResource(R.string.amz_status_connected),
            server?.let { groupName(AmzBrain.group(it)) },
            conn.delayMs?.let { fa("$it ms") },
            // Through Cloudflare, on a line that gives Proton no IPv6 and blackholes its IPv4.
            if (conn.via == "cf") stringResource(R.string.proton_via_cf) else null,
        ).joinToString(" · ")
        is AmzConnect.Failed -> stringResource(when (conn.reason) {
            AmzConnect.Reason.NO_DATA -> if (conn.serverId == null) R.string.proton_fail_none else R.string.amz_fail_no_data
            AmzConnect.Reason.NO_HANDSHAKE -> R.string.proton_fail_no_handshake
            AmzConnect.Reason.VPN_REFUSED -> R.string.amz_fail_vpn
            AmzConnect.Reason.BAD_CONFIG -> R.string.amz_fail_bad_config
            AmzConnect.Reason.OFFLINE -> R.string.amz_fail_offline
        })
    }
}

@Composable
private fun PrimaryButton(label: String, tone: Color = ProtonPurple, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp))
            .background(tone)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── the fallbacks: a browser, a file ────────────────────────────────────────────────────────────

/**
 * Proton's page in the phone's own browser. The browser is not the app, so it rides whatever engine
 * is on; the `.conf` it downloads is then picked with «انتخاب فایل».
 */
private fun openInBrowser(context: android.content.Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ProtonEngine.SIGN_IN_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.amz_no_file_picker), Toast.LENGTH_SHORT).show()
    }
}

/** A `.conf` the user picked: read (a config is a few hundred bytes) and kept like any other. */
private fun importUri(context: android.content.Context, uri: Uri) {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty().substringBeforeLast('.')
    val text = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(MAX_FILE).toByteArray().toString(Charsets.UTF_8) }
    }.getOrNull()
    if (text.isNullOrBlank()) { toastBadFile(context); return }
    ProtonEngine.onConf(context, text, name)
}

private fun toastBadFile(context: android.content.Context) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(context, context.getString(R.string.proton_file_bad), Toast.LENGTH_SHORT).show() }
}

private const val MAX_FILE = 64 * 1024

// ── Proton's own page ───────────────────────────────────────────────────────────────────────────

/** What the page's script hands over; it runs on a WebView thread, so it is posted to the main one. */
private class ConfBridge(private val token: String, private val onConf: (String, String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface
    fun conf(token: String?, text: String?, name: String?) {
        if (token != this.token) return
        val t = text ?: return
        if (t.length > 20_000) return
        main.post { onConf(t, name.orEmpty()) }
    }
}

private enum class Check { TESTING, READY, BLOCKED }

/**
 * Proton's real sign-in / sign-up page, then its WireGuard download.
 *
 * Before the page opens, whether Proton answers is checked -- on the network as it is, or through
 * the engine that is on. When it does not, the page does not open: the user is offered an engine to
 * turn on, shown while it comes up (and told if it does not), and the page opens by itself the
 * moment Proton answers through it. The page does the checks it wants (CAPTCHA, emailed code, 2FA)
 * as in any browser; the app only listens for the `.conf` files the dashboard makes, as many as the
 * user takes, and «تمام» ends it. Main-frame navigation is fenced to Proton's own hosts, so no
 * other page can ever reach the one function this page exposes.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
private fun ProtonWebPage(url: String, onBack: () -> Unit, onDone: (switch: Boolean) -> Unit) {
    val context = LocalContext.current
    val running by MyVpnService.isRunningFlow.collectAsState()
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val tun by TunnelController.state.collectAsState()
    val fluxUi by FluxEngine.ui.collectAsState()
    val route = remember(running, phase, tun.stage, fluxUi) { ProtonAccess.current() }

    // The engine the user just asked for, from the tap until Proton opens through it.
    var pending by remember { mutableStateOf<ProtonAccess.Helper?>(null) }
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(pending) {
        timedOut = false
        if (pending != null) { delay(HELPER_WAIT_MS); timedOut = true }
    }
    // An engine's state from BEFORE the tap (a failure of an earlier try) says nothing about this
    // one: a failure counts only after this try has been seen starting.
    var started by remember(pending) { mutableStateOf(false) }
    LaunchedEffect(pending, fluxUi, tun.stage, phase) {
        if (pending != null && (fluxUi is FluxUiState.Searching || phase == MyVpnService.Phase.CONNECTING || tun.stage == TunnelStage.STARTING)) started = true
    }
    val engineFailed = timedOut || (started && when (pending) {
        ProtonAccess.Helper.FLUX -> fluxUi is FluxUiState.Failed
        ProtonAccess.Helper.TOR, ProtonAccess.Helper.WIREGUARD -> tun.stage == TunnelStage.FAILED
        null -> false
    })
    val waiting = route == null && pending != null && !engineFailed
    val starting = waiting || phase == MyVpnService.Phase.CONNECTING || tun.stage == TunnelStage.STARTING ||
        fluxUi is FluxUiState.Searching

    var attempt by remember { mutableStateOf(0) }
    var check by remember { mutableStateOf(Check.TESTING) }
    // The page, once open, stays open: an engine that drops for a moment (FLUX re-racing on a bad
    // line did, on a phone, two minutes in) must not throw away a half-typed sign-in and start the
    // site over. [via] is the listener it opened through; null when Proton answered on the line.
    var opened by remember { mutableStateOf(false) }
    var via by remember { mutableStateOf<ProtonAccess.Route?>(null) }
    // Once the page is open, the engine's wait is over: its timer must not later turn into "the
    // engine failed" while the user is signing in.
    LaunchedEffect(opened) { if (opened) pending = null }
    var proxyUnsupported by remember { mutableStateOf(false) }
    LaunchedEffect(route, attempt, starting) {
        if (opened || starting) return@LaunchedEffect
        check = Check.TESTING
        proxyUnsupported = false
        if (ProtonAccess.reachable(route)) {
            val ok = ProtonAccess.setWebViewProxy(route)
            proxyUnsupported = !ok
            check = if (ok) Check.READY else Check.BLOCKED
            if (ok) { via = route; opened = true }
        } else check = Check.BLOCKED
    }
    // The override is process-wide: whoever sets it puts it back.
    DisposableEffect(Unit) { onDispose { ProtonAccess.clearWebViewProxy() } }

    val ids by ProtonEngine.ids.collectAsState()
    val first = remember { ids.size }
    val got = ids.size - first

    val web = remember { arrayOfNulls<WebView>(1) }
    DisposableEffect(Unit) {
        onDispose { web[0]?.let { it.removeJavascriptInterface("MlmProton"); it.destroy() }; web[0] = null }
    }
    var failed by remember(check) { mutableStateOf<String?>(null) }
    var progress by remember(check) { mutableStateOf(0) }
    var pageError by remember(check) { mutableStateOf<String?>(null) }
    var blank by remember(check) { mutableStateOf(false) }
    // A slow route (FLUX on a busy line) often loses the first load of the page and carries the
    // second: the page is reloaded by itself a couple of times before "did not open" is shown.
    var autoReloads by remember(check) { mutableStateOf(0) }
    // What went wrong inside the page (script errors, requests that failed), newest last: shown when
    // the page stays white, because that is the only way to see why.
    val errs = remember(check) { androidx.compose.runtime.mutableStateListOf<String>() }
    fun note(line: String) { if (line !in errs) errs.add(line); while (errs.size > 4) errs.removeAt(0) }
    // A page that "loaded" and is still white is what a script that cannot run looks like, and it
    // has no error to show: so the page's own text is asked for every few seconds.
    LaunchedEffect(check, attempt) {
        if (check != Check.READY) return@LaunchedEffect
        repeat(8) {
            delay(BLANK_CHECK_MS)
            val w = web[0] ?: return@LaunchedEffect
            val len = suspendCancellableCoroutine<Int> { c ->
                w.evaluateJavascript("(document.body?document.body.innerText.length:-1)") { c.resume(it?.trim()?.toIntOrNull() ?: -1) }
            }
            blank = len < 20
            if (!blank) return@LaunchedEffect
        }
    }

    // An open page follows its engine: when the engine is back (on whatever port it listens now),
    // the page is pointed at it and a load that failed in the meantime is tried again.
    val lost = opened && via != null && route == null
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        if (!opened || via == null) return@LaunchedEffect
        if (r != via && ProtonAccess.setWebViewProxy(r)) via = r
        if (failed != null) { failed = null; autoReloads = 0; web[0]?.loadUrl(url) }
    }

    // Signed in: the app takes the account's free servers itself ([ProtonApi]) -- by itself the
    // first time the page shows the signed-in site, and from «تمام» if that has not happened.
    val scope = rememberCoroutineScope()
    var taking by remember { mutableStateOf(false) }
    var taken by remember { mutableStateOf<ProtonApi.Outcome?>(null) }
    fun take(then: (() -> Unit)? = null) {
        if (taking) return
        val w = web[0]
        taking = true
        taken = null
        scope.launch {
            val out = try {
                val version = w?.let { pageVersion(it) }.orEmpty()
                val uid = w?.let { pageUid(it) }
                val ua = w?.settings?.userAgentString.orEmpty()
                // The way the page reached Proton: the engine it opened through (wherever it listens
                // now), or the line.
                val proxy = if (via == null) Proxy.NO_PROXY else (ProtonAccess.current() ?: via)?.javaProxy() ?: Proxy.NO_PROXY
                ProtonEngine.takeFromAccount(context, proxy, version, ua, uid)
            } finally { taking = false }
            taken = out
            if (out is ProtonApi.Outcome.Got) then?.invoke()
        }
    }
    LaunchedEffect(opened) {
        if (!opened) return@LaunchedEffect
        // A take that failed on the way (the route dropped) is tried again by itself, twice.
        var tries = 0
        while (tries < AUTO_TAKES) {
            delay(if (tries == 0) TAKE_POLL_MS else TAKE_RETRY_MS)
            if (taking) continue
            when (taken) { null, is ProtonApi.Outcome.Failed -> Unit; else -> return@LaunchedEffect }
            val w = web[0] ?: continue
            val path = Uri.parse(w.url.orEmpty()).path.orEmpty().lowercase()
            val routeDown = via != null && ProtonAccess.current() == null
            if (progress >= 100 && !routeDown && SIGN_IN_STEPS.none { path.contains(it) } && withContext(Dispatchers.IO) { ProtonApi.signedIn() }) {
                tries++
                take()
            }
        }
    }

    val withVpn = rememberVpnGate(onDenied = { pending = null })
    val pick: (ProtonAccess.Helper) -> Unit = { h ->
        pending = h
        withVpn { ProtonAccess.start(context, h) }
    }

    IosScreen(title = stringResource(R.string.proton_web_title), onBack = onBack, backLabel = stringResource(R.string.proton_title), scrollable = false) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Once open, only the page: none of the states before it may take its place again.
            when {
                !opened && waiting -> HelperProgress(
                    helper = pending!!, fluxUi = fluxUi, phase = phase, tun = tun,
                    onCancel = { ProtonAccess.stop(context); pending = null },
                )
                !opened && starting -> Message(stringResource(R.string.proton_helper_starting))
                !opened && check == Check.TESTING -> Message(stringResource(if (route != null) R.string.proton_checking_via else R.string.proton_checking))
                !opened && check == Check.BLOCKED -> HelperChooser(
                    onPick = pick,
                    onRetry = { pending = null; attempt++ },
                    engineFailed = engineFailed,
                    afterBlockedRoute = route != null,
                    proxyUnsupported = proxyUnsupported,
                )
                else -> {
                    Column(Modifier.fillMaxSize()) {
                        Text(
                            stringResource(R.string.proton_web_hint), color = Ios.SecondaryLabel, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                        if (lost) {
                            Text(
                                stringResource(R.string.proton_route_lost), color = Ios.Orange, fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                            )
                        }
                        if (progress in 1..99) {
                            LinearProgressIndicator(
                                progress = progress / 100f, color = ProtonPurple, trackColor = Color.White.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                            )
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx -> com.mlmvpn.scanner.ui.webViewOrNotice(ctx) { ctx ->
                                    WebView(ctx).apply {
                                        web[0] = this
                                        // AndroidView leaves a view WRAP_CONTENT, and a WebView that
                                        // is WRAP_CONTENT high forces the page's viewport height to 0:
                                        // CSS `100vh` became 0, Proton's full-height layout centred
                                        // the sign-in form above the top edge, and the page was
                                        // white with all of its text loaded (measured on a phone).
                                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                        setBackgroundColor(android.graphics.Color.WHITE)
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.databaseEnabled = true
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.setSupportMultipleWindows(false)
                                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                        // A WebView says so in its user agent ("; wv"), and a site may
                                        // treat that as a lesser browser: this one asks as Chrome does.
                                        settings.userAgentString = android.webkit.WebSettings.getDefaultUserAgent(ctx)
                                            .replace("; wv", "").replace(" Version/4.0", "")
                                        CookieManager.getInstance().setAcceptCookie(true)
                                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                        val token = java.util.UUID.randomUUID().toString()
                                        val js = ProtonEngine.captureJs(token)
                                        addJavascriptInterface(ConfBridge(token) { text, name -> ProtonEngine.onConf(context, text, name) }, "MlmProton")
                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                                            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                                    pageError = m.message().take(140)
                                                    note("JS: " + m.message().take(90))
                                                    android.util.Log.w("ProtonPage", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                                                }
                                                return true
                                            }
                                        }
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                                // Only a whole-page move to somewhere that is not Proton is refused; frames
                                                // inside the page (the CAPTCHA, blank ones) are the page's own business.
                                                val u = request.url
                                                val isWeb = u.scheme == "http" || u.scheme == "https"
                                                return request.isForMainFrame && isWeb && !ProtonEngine.isProtonHost(u.host)
                                            }
                                            override fun onPageStarted(view: WebView, loaded: String?, favicon: android.graphics.Bitmap?) {
                                                failed = null
                                            }
                                            override fun onPageFinished(view: WebView, loaded: String?) {
                                                if (ProtonEngine.isProtonHost(Uri.parse(loaded.orEmpty()).host)) view.evaluateJavascript(js, null)
                                            }
                                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                                if (request.isForMainFrame) {
                                                    if (autoReloads < AUTO_RELOADS) {
                                                        autoReloads++
                                                        view.postDelayed({ if (web[0] === view) view.loadUrl(url) }, 1_500)
                                                    } else failed = error.description?.toString() ?: "error"
                                                } else note("${request.url.host}: ${error.description}")
                                            }
                                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                                                if (request.isForMainFrame) pageError = "HTTP ${response.statusCode}"
                                                else if (response.statusCode >= 400) note("${request.url.host}: HTTP ${response.statusCode}")
                                            }
                                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                                // Never carry on past a certificate problem on a sign-in page.
                                                handler.cancel()
                                                failed = "SSL"
                                            }
                                        }
                                        loadUrl(url)
                                    }
                                } },
                            )
                            if (failed != null) {
                                Column(
                                    Modifier.fillMaxSize().background(Ios.Background).padding(24.dp),
                                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(stringResource(R.string.proton_page_failed), color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                                    Text(failed.orEmpty(), color = Ios.SecondaryLabel, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                                    Spacer(Modifier.height(14.dp))
                                    PrimaryButton(stringResource(R.string.proton_retry)) { failed = null; autoReloads = 0; web[0]?.loadUrl(url) }
                                }
                            } else if (blank) {
                                Column(
                                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Ios.Background.copy(alpha = 0.94f)).padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(stringResource(R.string.proton_blank), color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                                    errs.forEach { Text(it, color = Ios.SecondaryLabel, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.weight(1f)) { PrimaryButton(stringResource(R.string.proton_reload)) { blank = false; web[0]?.reload() } }
                                        Box(Modifier.weight(1f)) { PrimaryButton(stringResource(R.string.proton_open_browser), tone = Ios.Blue) { openInBrowser(context) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (check == Check.READY || got > 0) {
            Column(Modifier.fillMaxWidth().background(Ios.Background).padding(horizontal = 16.dp, vertical = 8.dp)) {
                val outcome = taken
                when {
                    taking -> Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = ProtonPurple, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.proton_taking), color = Ios.SecondaryLabel, fontSize = 13.sp)
                    }
                    got > 0 -> Text(
                        stringResource(R.string.proton_got_servers, fa(got)), color = Ios.Green, fontSize = 13.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                    outcome != null && outcome !is ProtonApi.Outcome.Got -> Text(
                        when (outcome) {
                            is ProtonApi.Outcome.NothingNew -> stringResource(R.string.proton_take_none)
                            ProtonApi.Outcome.Expired -> stringResource(R.string.proton_take_expired)
                            ProtonApi.Outcome.NotSignedIn -> stringResource(R.string.proton_sign_in_first)
                            is ProtonApi.Outcome.Failed -> stringResource(R.string.proton_take_failed, outcome.detail.take(120))
                            is ProtonApi.Outcome.Got -> ""
                        },
                        color = Ios.Orange, fontSize = 13.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
                PrimaryButton(
                    stringResource(if (via != null && got > 0) R.string.proton_done_switch else R.string.proton_done),
                    tone = if (got > 0) ProtonPurple else Ios.Gray,
                ) {
                    when {
                        got > 0 -> onDone(via != null)
                        taking -> Unit
                        // Signed in but nothing taken yet: «تمام» takes them, then finishes.
                        ProtonApi.signedIn() -> take { onDone(via != null) }
                        else -> Toast.makeText(context, context.getString(R.string.proton_sign_in_first), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

private const val HELPER_WAIT_MS = 120_000L
private const val TAKE_POLL_MS = 2_000L
private const val TAKE_RETRY_MS = 10_000L
private const val AUTO_TAKES = 3
/** Pages of the sign-in or sign-up itself: the session may exist there before the account is ready. */
private val SIGN_IN_STEPS = listOf("login", "signup", "reset", "forgot", "authorize", "verify", "recovery")

private val VERSION = Regex("""^\d+(\.\d+){1,3}$""")

/**
 * The session the page itself is using: Proton keeps it in local storage as `ps-<n>`, the newest one
 * the live one. Only its id is read (it names the AUTH cookie to use), nothing of what it unlocks.
 */
private suspend fun pageUid(w: WebView): String? = withTimeoutOrNull(3_000) {
    suspendCancellableCoroutine<String?> { c ->
        w.evaluateJavascript(PAGE_UID_JS) { raw ->
            val v = raw?.trim()?.trim('"').orEmpty()
            c.resume(v.takeIf { it.isNotEmpty() && it != "null" && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } })
        }
    }
}

private const val PAGE_UID_JS = """(function(){try{var best=null,at=-1;
for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(!/^ps-\d+$/.test(k))continue;
var o=JSON.parse(localStorage.getItem(k)||'{}');var t=o.persistedAt||0;if(o.UID&&t>=at){best=o.UID;at=t;}}
return best||'';}catch(e){return '';}})()"""

/** The dashboard's version as the page reports it (`SENTRY_RELEASE`); "" when it does not say. */
private suspend fun pageVersion(w: WebView): String = withTimeoutOrNull(3_000) {
    suspendCancellableCoroutine<String> { c ->
        w.evaluateJavascript("(window.SENTRY_RELEASE&&SENTRY_RELEASE.id)||''") { raw ->
            val v = raw?.trim()?.trim('"').orEmpty()
            c.resume(if (VERSION.matches(v)) v else "")
        }
    }
}.orEmpty()
private const val BLANK_CHECK_MS = 6_000L
private const val AUTO_RELOADS = 2

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Ios.SecondaryLabel, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun helperName(h: ProtonAccess.Helper): String = stringResource(when (h) {
    ProtonAccess.Helper.FLUX -> R.string.proton_name_flux
    ProtonAccess.Helper.TOR -> R.string.proton_name_tor
    ProtonAccess.Helper.WIREGUARD -> R.string.proton_name_wg
})

/**
 * The engine the user asked for, coming up: a spinner, what the engine itself says it is doing
 * (asked of the engine, not guessed), and a way to give up. When it is up the page moves on by itself.
 */
@Composable
private fun HelperProgress(
    helper: ProtonAccess.Helper, fluxUi: FluxUiState, phase: MyVpnService.Phase,
    tun: com.mlmvpn.scanner.ui.tunnel.TunnelUiState, onCancel: () -> Unit,
) {
    val name = helperName(helper)
    val line = when {
        helper == ProtonAccess.Helper.FLUX && phase == MyVpnService.Phase.CONNECTING -> stringResource(R.string.proton_wait_starting)
        helper == ProtonAccess.Helper.FLUX && fluxUi is FluxUiState.Searching -> stringResource(R.string.proton_wait_flux_search)
        helper != ProtonAccess.Helper.FLUX && tun.stage == TunnelStage.STARTING && tun.progress >= 0 ->
            stringResource(R.string.proton_wait_starting) + " " + fa(tun.progress) + "٪"
        else -> stringResource(R.string.proton_wait_starting)
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = ProtonPurple, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.proton_wait_title, name), color = Ios.Label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(line, color = Ios.SecondaryLabel, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.width(220.dp)) { PrimaryButton(stringResource(R.string.proton_cancel), tone = Ios.Gray, onClick = onCancel) }
    }
}

/**
 * Proton does not answer: the engines the page can turn on by itself, in one tap each. Whatever
 * else the user turns on (a V2Ray config, FENIX, ...) from its own screen is noticed the same way.
 */
@Composable
private fun HelperChooser(
    onPick: (ProtonAccess.Helper) -> Unit, onRetry: () -> Unit,
    engineFailed: Boolean, afterBlockedRoute: Boolean, proxyUnsupported: Boolean,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        Text(
            stringResource(R.string.proton_blocked_title), color = Ios.Label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Text(
            stringResource(when {
                proxyUnsupported -> R.string.proton_no_webproxy
                engineFailed -> R.string.proton_helper_failed
                afterBlockedRoute -> R.string.proton_helper_up_failed
                else -> R.string.proton_blocked_body
            }),
            color = if (engineFailed) Ios.Orange else Ios.SecondaryLabel, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (!proxyUnsupported) {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.proton_helper_flux), subtitle = stringResource(R.string.proton_helper_flux_sub),
                    icon = Icons.Default.Bolt, tint = Ios.Indigo,
                    onClick = { onPick(ProtonAccess.Helper.FLUX) })
                Separator()
                SettingsRow(
                    title = stringResource(R.string.proton_helper_tor), subtitle = stringResource(R.string.proton_helper_tor_sub),
                    icon = Icons.Default.Shield, tint = Ios.Purple,
                    onClick = { onPick(ProtonAccess.Helper.TOR) })
                Separator()
                SettingsRow(
                    title = stringResource(R.string.proton_helper_wg), subtitle = stringResource(R.string.proton_helper_wg_sub),
                    icon = Icons.Default.VpnKey, tint = Ios.Green,
                    onClick = { onPick(ProtonAccess.Helper.WIREGUARD) })
            }
            SettingsFooter(stringResource(R.string.proton_helper_other))
        }
        SettingsGroup {
            SettingsActionRow(label = stringResource(R.string.proton_retry), icon = Icons.Default.Refresh, tint = ProtonPurple, onClick = onRetry)
            Separator()
            SettingsActionRow(label = stringResource(R.string.proton_open_browser), icon = Icons.Default.OpenInBrowser, tint = Ios.Blue) { openInBrowser(context) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
