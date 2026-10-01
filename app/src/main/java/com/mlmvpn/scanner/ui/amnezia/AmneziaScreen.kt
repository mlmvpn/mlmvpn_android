package com.mlmvpn.scanner.ui.amnezia

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.amnezia.AmzBrain
import com.mlmvpn.scanner.engines.amnezia.AmzConnect
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.engines.amnezia.AmzServer
import com.mlmvpn.scanner.engines.amnezia.AmzTestMode
import com.mlmvpn.scanner.ui.LocalTabVisible
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Locale

private enum class Page { MAIN, SERVERS, SETTINGS }

/**
 * «آمنزیا», laid out the way an iOS VPN app is: one large glass card holding the connect button
 * and the live state, the chosen location as a single row that opens the server list, and the
 * section's tools as grouped rows below. The long list lives on its own page ([AmzServersScreen]),
 * so this one never scrolls through hundreds of servers to reach the button.
 */
@Composable
fun AmneziaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(AmzEngine.isInitialized()) }
    val visible = LocalTabVisible.current
    LaunchedEffect(Unit) {
        if (!ready) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AmzEngine.init(context) }; ready = true }
    }
    // Every time the screen comes into view: lists when due, a test when the delays are old.
    LaunchedEffect(ready, visible) { if (ready && visible) AmzEngine.onOpen(context) }
    if (!ready) {
        IosScreen(title = stringResource(R.string.amz_title), onBack = onBack, backLabel = stringResource(R.string.home)) {}
        return
    }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }

    // ---- shared by every page: VPN permission, file picker, one-off notices.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) afterPermission?.invoke()
        afterPermission = null
    }
    val withVpn: (() -> Unit) -> Unit = { action ->
        com.mlmvpn.scanner.data.ScanGuard.run(com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN) {
            val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
            if (prep != null) { afterPermission = action; vpnLauncher.launch(prep) } else action()
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> readUri(context, uri)?.let { (bytes, name) -> AmzEngine.importFile(context, bytes, name) } }
    }
    val pickFile = { fileLauncher.launch(arrayOf("*/*")) }
    val paste = {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) Toast.makeText(context, S(R.string.amz_import_failed), Toast.LENGTH_SHORT).show()
        else AmzEngine.importText(context, text)
    }
    val notice by AmzEngine.notice.collectAsState()
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        val text = when (n) {
            is AmzEngine.Notice.Imported -> S(R.string.amz_imported, fa(n.count))
            AmzEngine.Notice.ImportFailed -> S(R.string.amz_import_failed)
            AmzEngine.Notice.ImportNoPeer -> S(R.string.amz_import_no_peer)
            is AmzEngine.Notice.ImportWg -> S(when (n.problem) {
                com.mlmvpn.scanner.engines.amnezia.WgConfig.Companion.Problem.NO_PEER -> R.string.amz_import_no_peer
                com.mlmvpn.scanner.engines.amnezia.WgConfig.Companion.Problem.NO_ENDPOINT -> R.string.amz_import_no_endpoint
                com.mlmvpn.scanner.engines.amnezia.WgConfig.Companion.Problem.BAD_PRIVATE_KEY -> R.string.amz_import_bad_private
                com.mlmvpn.scanner.engines.amnezia.WgConfig.Companion.Problem.BAD_PUBLIC_KEY -> R.string.amz_import_bad_public
                com.mlmvpn.scanner.engines.amnezia.WgConfig.Companion.Problem.NO_ADDRESS -> R.string.amz_import_no_address
            })
            is AmzEngine.Notice.Refreshed -> S(R.string.amz_refreshed, fa(n.added))
            AmzEngine.Notice.RefreshFailed -> S(R.string.amz_refresh_failed)
            AmzEngine.Notice.Offline -> S(R.string.amz_offline_notice)
            is AmzEngine.Notice.Removed -> S(R.string.amz_removed, fa(n.count))
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        AmzEngine.consumeNotice()
    }

    Box(Modifier.fillMaxSize()) {
    when (page) {
        Page.SETTINGS -> AmzSettingsScreen(onBack = { page = Page.MAIN })
        Page.SERVERS -> AmzServersScreen(
            onBack = { page = Page.MAIN },
            onPicked = { t ->
                AmzEngine.setTarget(t)
                page = Page.MAIN
                withVpn { AmzEngine.connectTarget(context) }
            },
            onAddFile = pickFile, onPaste = paste,
        )
        Page.MAIN -> AmzHome(
            onBack = onBack,
            onServers = { page = Page.SERVERS },
            onSettings = { page = Page.SETTINGS },
            onConnect = { withVpn { AmzEngine.connectTarget(context) } },
            onAddFile = pickFile, onPaste = paste,
        )
    }
        UndoBar(Modifier.align(Alignment.BottomCenter).padding(bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current))
    }
}

@Composable
private fun AmzHome(
    onBack: () -> Unit, onServers: () -> Unit, onSettings: () -> Unit, onConnect: () -> Unit,
    onAddFile: () -> Unit, onPaste: () -> Unit,
) {
    val context = LocalContext.current
    val state by AmzEngine.store.state.collectAsState()
    val net by AmzEngine.net.collectAsState()
    val conn by AmzEngine.connect.collectAsState()
    val progress by AmzEngine.progress.collectAsState()
    val refreshing by AmzEngine.refreshing.collectAsState()
    val target by AmzEngine.target.collectAsState()
    val stats = remember(state, net) { state.statsFor(net) }
    val byId = remember(state.servers) { state.servers.associateBy { it.id } }
    val alive = remember(state.servers, stats) { state.servers.count { stats[it.id]?.alive == true } }
    val activeId = when (val c = conn) { is AmzConnect.Connecting -> c.serverId; is AmzConnect.Connected -> c.serverId; else -> null }

    IosScreen(
        onBack = onBack,
        backLabel = stringResource(R.string.home),
        largeTitle = stringResource(R.string.amz_title),
        trailing = { BarIcon(Icons.Default.Settings, stringResource(R.string.amz_settings), onSettings) },
        onRefresh = { AmzEngine.refresh(force = true); delay(300); AmzEngine.refreshing.first { !it } },
    ) {
        HeroCard(
            conn = conn, server = activeId?.let { byId[it] },
            onButton = {
                when (conn) {
                    is AmzConnect.Connecting, is AmzConnect.Connected -> AmzEngine.disconnectAsync(context)
                    else -> onConnect()
                }
            },
        )

        SettingsSectionHeader(stringResource(R.string.amz_location))
        SettingsGroup {
            LocationRow(target, byId, stats, onServers)
        }

        SettingsSectionHeader(stringResource(R.string.amz_section_test))
        SettingsGroup {
            AnimatedVisibility(progress.running, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.amz_testing, fa(progress.done), fa(progress.total), fa(progress.alive)),
                            color = Ios.Label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.amz_stop), color = Ios.Red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { AmzEngine.stopTest() }.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = if (progress.total > 0) progress.done.toFloat() / progress.total else 0f,
                        color = Ios.Green, trackColor = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
            if (!progress.running) {
                SettingsActionRow(label = stringResource(R.string.amz_test_best), icon = Icons.Default.Bolt, tint = Ios.Green) { AmzEngine.test(AmzTestMode.BEST) }
                Separator()
                SettingsActionRow(label = stringResource(R.string.amz_test_all), icon = Icons.Default.Speed, tint = Ios.Indigo) { AmzEngine.test(AmzTestMode.ALL) }
                Separator()
                SettingsActionRow(label = stringResource(R.string.amz_remove_dead), icon = Icons.Default.DeleteSweep, tint = Ios.Red) { AmzEngine.removeDead() }
            }
        }

        SettingsSectionHeader(stringResource(R.string.amz_section_sources))
        SettingsGroup {
            SettingsActionRow(label = stringResource(R.string.amz_add_file), icon = Icons.Default.FileOpen, tint = Ios.Blue, onClick = onAddFile)
            Separator()
            SettingsActionRow(label = stringResource(R.string.amz_paste), icon = Icons.Default.ContentPaste, tint = Ios.Teal, onClick = onPaste)
            Separator()
            SettingsActionRow(label = stringResource(if (refreshing) R.string.amz_refreshing else R.string.amz_refresh),
                icon = Icons.Default.Refresh, tint = Ios.Orange, busy = refreshing) { AmzEngine.refresh(force = true) }
        }
        SettingsFooter(stringResource(R.string.amz_servers_count, fa(state.servers.size), fa(alive)))
        Spacer(Modifier.height(36.dp))
    }
}

// ------------------------------------------------------------------------------ the hero card

@Composable
private fun HeroCard(conn: AmzConnect, server: AmzServer?, onButton: () -> Unit) {
    val connecting = conn is AmzConnect.Connecting
    val connected = conn is AmzConnect.Connected
    val tone by animateColorAsState(
        when { connected -> Ios.Green; connecting -> Ios.Orange; conn is AmzConnect.Failed -> Ios.Red; else -> Ios.Blue },
        animationSpec = tween(450), label = "tone",
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .frostedGlass(RoundedCornerShape(30.dp)).padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Status capsule and the section's mark, like the header of an iOS widget.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
                Spacer(Modifier.width(6.dp))
                Text(statusLine(conn), color = Ios.Label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Image(painterResource(R.drawable.ic_app_amnezia), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(30.dp))
        }

        Spacer(Modifier.height(14.dp))
        Orb(tone, connecting, connected, onButton)
        Spacer(Modifier.height(14.dp))

        Text(
            when (conn) {
                is AmzConnect.Connected -> stringResource(R.string.amz_status_connected)
                is AmzConnect.Connecting -> stepLine(conn.step)
                else -> stringResource(R.string.amz_status_idle)
            },
            color = Ios.Label, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subLine(conn, server), color = if (conn is AmzConnect.Failed) Ios.Orange else Ios.SecondaryLabel,
            fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(16.dp))
        // The explicit action, in words: the orb is the big target, this says what it will do.
        val label = stringResource(when { connecting -> R.string.amz_cancel; connected -> R.string.amz_disconnect; else -> R.string.amz_connect })
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                .background(if (connected || connecting) Color.White.copy(alpha = 0.14f) else Ios.Blue)
                .clickable(role = Role.Button, onClick = onButton),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = if (connecting) Ios.Orange else if (connected) Ios.Red else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

        AnimatedVisibility(connected, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val c = conn as? AmzConnect.Connected
            if (c != null) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(c.since) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
                val secs = ((now - c.since) / 1000).coerceAtLeast(0)
                val clock = if (secs >= 3600) "%d:%02d:%02d".format(Locale.US, secs / 3600, secs / 60 % 60, secs % 60)
                    else "%02d:%02d".format(Locale.US, secs / 60, secs % 60)
                val group = server?.let { AmzBrain.group(it) } ?: c.country
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(stringResource(R.string.amz_stat_delay), c.delayMs?.let { stringResource(R.string.amz_ms, fa(it.toInt())) } ?: "—", Modifier.weight(1f))
                    StatTile(stringResource(R.string.amz_stat_time), fa(clock), Modifier.weight(1f))
                    StatTile(stringResource(R.string.amz_stat_exit), "${groupFlag(c.country ?: group)} ${groupName(c.country ?: group)}", Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The connect button: a glowing orb. It breathes while idle-ready, and while connecting an arc
 * turns around it for as long as the connection is not proven -- never a fake "on".
 */
@Composable
private fun Orb(tone: Color, connecting: Boolean, connected: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val label = stringResource(when { connecting -> R.string.amz_cancel; connected -> R.string.amz_disconnect; else -> R.string.amz_connect })
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(176.dp)) {
        // The glow.
        Box(
            Modifier.size(176.dp).scale(if (connecting || connected) breathe else 1f).clip(CircleShape)
                .background(Brush.radialGradient(listOf(tone.copy(alpha = 0.45f), tone.copy(alpha = 0.10f), Color.Transparent))),
        )
        if (connecting) {
            CircularProgressIndicator(progress = 0.3f, color = tone, strokeWidth = 3.dp, modifier = Modifier.size(150.dp).rotate(angle))
        }
        Box(
            Modifier.size(124.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(lerp(tone, Color.White, 0.25f), lerp(tone, Color.Black, 0.25f))))
                .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(52.dp))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f)).padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Ios.SecondaryLabel, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun statusLine(conn: AmzConnect): String = when (conn) {
    AmzConnect.Idle -> stringResource(R.string.amz_status_idle)
    is AmzConnect.Connecting -> stringResource(R.string.amz_status_preparing).trimEnd('…', '.')
    is AmzConnect.Connected -> stringResource(R.string.amz_status_connected)
    is AmzConnect.Failed -> failLine(conn.reason)
}

@Composable
private fun stepLine(step: AmzConnect.Step): String = stringResource(when (step) {
    AmzConnect.Step.PREPARING -> R.string.amz_status_preparing
    AmzConnect.Step.STARTING -> R.string.amz_status_starting
    AmzConnect.Step.HANDSHAKE -> R.string.amz_status_handshake
    AmzConnect.Step.VERIFYING -> R.string.amz_status_verifying
})

@Composable
private fun failLine(reason: AmzConnect.Reason): String = stringResource(when (reason) {
    AmzConnect.Reason.NO_DATA -> R.string.amz_fail_no_data
    AmzConnect.Reason.NO_HANDSHAKE -> R.string.amz_fail_no_handshake
    AmzConnect.Reason.VPN_REFUSED -> R.string.amz_fail_vpn
    AmzConnect.Reason.BAD_CONFIG -> R.string.amz_fail_bad_config
    AmzConnect.Reason.OFFLINE -> R.string.amz_fail_offline
})

@Composable
private fun subLine(conn: AmzConnect, server: AmzServer?): String {
    val where = server?.let { "${groupFlag(AmzBrain.group(it))} ${groupName(AmzBrain.group(it))} · ${it.name}" }.orEmpty()
    return when (conn) {
        AmzConnect.Idle -> stringResource(R.string.amz_connect_best)
        is AmzConnect.Failed -> failLine(conn.reason)
        is AmzConnect.Connecting -> listOfNotNull(
            where.ifEmpty { null },
            if (conn.attempt > 1) stringResource(R.string.amz_status_attempt, fa(conn.attempt)) else null,
        ).joinToString(" · ")
        is AmzConnect.Connected -> where
    }
}

// ------------------------------------------------------------------------------ the location row

@Composable
private fun LocationRow(
    target: AmzEngine.Target,
    byId: Map<String, AmzServer>,
    stats: Map<String, com.mlmvpn.scanner.engines.amnezia.AmzStat>,
    onClick: () -> Unit,
) {
    val server = (target as? AmzEngine.Target.Server)?.let { byId[it.id] }
    val (mark, title, sub) = when {
        server != null -> Triple(groupFlag(AmzBrain.group(server)), server.name, "${groupName(AmzBrain.group(server))} · ${kindLabel(server.kind)}")
        target is AmzEngine.Target.Group -> Triple(groupFlag(target.key), groupName(target.key), stringResource(R.string.amz_target_group_sub))
        else -> Triple("⚡", stringResource(R.string.amz_target_auto), stringResource(R.string.amz_target_auto_sub))
    }
    val ms = server?.let { stats[it.id]?.takeIf { s -> s.alive }?.lastMs }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text(mark, fontSize = 21.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ms != null) { DelayCapsule(ms); Spacer(Modifier.width(6.dp)) }
        Icon(
            Icons.Default.ChevronRight, contentDescription = null, tint = Ios.Chevron,
            modifier = Modifier.size(18.dp).scale(scaleX = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f, scaleY = 1f),
        )
    }
}

// ------------------------------------------------------------------------------ settings

@Composable
private fun AmzSettingsScreen(onBack: () -> Unit) {
    val state by AmzEngine.store.state.collectAsState()
    val p = state.prefs
    IosScreen(title = stringResource(R.string.amz_settings), onBack = onBack, backLabel = stringResource(R.string.amz_title)) {
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            SettingsToggle(
                title = stringResource(R.string.amz_obfuscate), subtitle = stringResource(R.string.amz_obfuscate_sub),
                checked = p.obfuscate, onCheckedChange = { v -> AmzEngine.setPrefs { it.copy(obfuscate = v) } },
                icon = Icons.Default.Bolt, tint = Ios.Orange,
            )
            Separator()
            SettingsToggle(
                title = stringResource(R.string.amz_auto_remove), subtitle = stringResource(R.string.amz_auto_remove_sub),
                checked = p.autoRemoveDead, onCheckedChange = { v -> AmzEngine.setPrefs { it.copy(autoRemoveDead = v) } },
                icon = Icons.Default.DeleteSweep, tint = Ios.Red,
            )
            Separator()
            SettingsToggle(
                title = stringResource(R.string.amz_auto_fallback),
                checked = p.autoFallback, onCheckedChange = { v -> AmzEngine.setPrefs { it.copy(autoFallback = v) } },
                icon = Icons.Default.Refresh, tint = Ios.Blue,
            )
        }
        SettingsFooter(stringResource(R.string.amz_settings_footer))
        Spacer(Modifier.height(40.dp))
    }
}
