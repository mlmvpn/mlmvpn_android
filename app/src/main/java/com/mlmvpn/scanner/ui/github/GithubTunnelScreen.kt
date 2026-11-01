package com.mlmvpn.scanner.ui.github

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.engines.github.GtAccount
import com.mlmvpn.scanner.engines.github.GtEngine
import com.mlmvpn.scanner.engines.github.GtExitInfo
import com.mlmvpn.scanner.engines.github.GtExitPrefs
import com.mlmvpn.scanner.engines.github.GtExitRule
import com.mlmvpn.scanner.engines.github.GtExitUi
import com.mlmvpn.scanner.engines.github.GtGithub
import com.mlmvpn.scanner.engines.github.GtSession
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.ui.tunnel.CountryLabel
import com.mlmvpn.scanner.ui.tunnel.RegionPickerScreen
import com.mlmvpn.scanner.utils.S
import com.mlmvpn.scanner.store.tr

/**
 * «گیت‌هاب تانل» on the phone: the same system as the Windows window — a cloud server on the user's
 * own GitHub allowance, reached through a relay Worker on their own Cloudflare account.
 *
 * The page is the decision first (one dial: sign in, build a session, connect or disconnect — the
 * dial knows which comes next), then the parts, each in its own group: what is missing, the live
 * session, the connection, the accounts, and the relay. Nothing here is a modal except the code the
 * user types into github.com, and that is a card in the page.
 */
@Composable
fun GithubTunnelScreen(onBack: () -> Unit, onOpenScanner: () -> Unit, onOpenCloud: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { GtEngine.attach(context) }
    val st by GtEngine.state.collectAsState()
    val running by MyVpnService.isRunningFlow.collectAsState()
    val nodeId by MyVpnService.connectedNodeIdFlow.collectAsState()
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val cloudAccounts by CloudManager(context).accountsFlow.collectAsState()

    // The countdown is drawn from GitHub's clock, once a second.
    var now by remember { mutableLongStateOf(GtGithub.serverNow()) }
    com.mlmvpn.scanner.ui.LaunchedWhileVisible(Unit) {
        while (true) { now = GtGithub.serverNow(); kotlinx.coroutines.delay(1000) }
    }

    val session = st.session
    val live = session?.live == true
    val onGt = running && nodeId?.startsWith(GtEngine.NODE_PREFIX) == true
    val connected = onGt && phase == MyVpnService.Phase.CONNECTED
    val building = st.run != null && st.run?.step != "FAILED" && st.run?.step != "READY"
    val linking = st.link.phase == "picking" || st.link.phase == "connecting" || (onGt && phase == MyVpnService.Phase.CONNECTING)
    val hasAccounts = st.accounts.isNotEmpty()
    val brokerOk = st.broker.deployed && st.broker.version >= st.brokerRequired
    val ex = st.exit

    // Setup is two steps: GitHub, then connect. The runner brings its own ways in -- free public
    // relays it dials out to (runner/relays.mjs) -- so a GitHub account alone is enough, also for a
    // user without Cloudflare or on a line where Cloudflare is filtered. The relay Worker on the
    // user's own Cloudflare account is an optional extra way in, further down the page.
    var pickedCf by remember { mutableStateOf<String?>(null) }
    val cf = cloudAccounts.firstOrNull { it.id == (pickedCf ?: st.broker.cloudAccountId) } ?: cloudAccounts.firstOrNull()
    val setupStep = if (!hasAccounts) 1 else 2
    val setupDone = setupStep == 2

    // Pages of this screen, not modals: «country» (the exit), and a rule's editor with its own two
    // pickers — «rule» › «rule_country» / «rule_apps». The draft lives here, so it survives the
    // trip into either picker and back.
    var page by rememberSaveable { mutableStateOf("") }
    var draft by remember { mutableStateOf(RuleDraft()) }
    BackHandler(enabled = page.isNotEmpty()) { page = if (page == "rule_country" || page == "rule_apps") "rule" else "" }
    when (page) {
        "country" -> {
            ExitCountryPage(ex, forRule = false, onBack = { page = "" }, onPick = { cc ->
                val p = ex.prefs
                GtEngine.setExitPrefs(if (cc == AUTO_CODE) p.copy(country = "", provider = "") else p.copy(country = cc))
            })
            return
        }
        "rule_country" -> {
            ExitCountryPage(ex, forRule = true, onBack = { page = "rule" }, onPick = { cc ->
                if (cc != AUTO_CODE) draft = draft.copy(country = cc)
            })
            return
        }
        "rule_apps" -> {
            val (apps, loading) = com.mlmvpn.scanner.ui.settings.rememberInstalledApps(keep = draft.apps, everyNetworkApp = true)
            com.mlmvpn.scanner.ui.settings.AppPickerPage(
                apps = apps, isLoading = loading, selected = draft.apps,
                backLabel = stringResource(if (draft.index >= 0) R.string.gt_rule_edit_title else R.string.gt_rule_new_title),
                onBack = { page = "rule" },
                onToggle = { pkg -> draft = draft.copy(apps = if (pkg in draft.apps) draft.apps - pkg else draft.apps + pkg) },
                title = stringResource(R.string.gt_rule_apps_title),
            )
            return
        }
        "rule" -> {
            RuleEditorPage(
                draft = draft, ex = ex,
                proxyMode = com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(context),
                onChange = { draft = it },
                onPickCountry = { page = "rule_country" },
                onPickApps = { page = "rule_apps" },
                onSave = {
                    GtEngine.setExitPrefs(ex.prefs.withRule(draft.toRule(), draft.index))
                    page = ""
                },
                onDelete = {
                    GtEngine.setExitPrefs(ex.prefs.copy(rules = ex.prefs.rules.filterIndexed { k, _ -> k != draft.index }))
                    page = ""
                },
                onBack = { page = "" },
            )
            return
        }
    }

    var pendingAfterConsent by remember { mutableStateOf<(() -> Unit)?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) pendingAfterConsent?.invoke()
        pendingAfterConsent = null
    }
    fun withVpnConsent(action: () -> Unit) {
        val intent = runCatching { VpnService.prepare(context) }.getOrNull()
        if (intent == null) action() else { pendingAfterConsent = action; consent.launch(intent) }
    }

    // What the dial does, and what it says above itself.
    val headline: String
    val line: String
    val dialAction: (() -> Unit)?
    when {
        connected -> {
            headline = stringResource(R.string.gt_head_connected)
            line = stringResource(R.string.gt_line_connected, countdown(session?.expiresAt ?: 0, now))
            dialAction = { GtEngine.disconnect() }
        }
        building -> {
            headline = stringResource(R.string.gt_head_building)
            line = stringResource(R.string.gt_line_building, stepLabel(st.run?.step.orEmpty()))
            dialAction = null
        }
        linking -> {
            headline = stringResource(R.string.gt_head_linking)
            line = stringResource(R.string.gt_line_linking)
            dialAction = { GtEngine.disconnect() }
        }
        !hasAccounts -> {
            headline = tr("قدم ۱ از ۲: گیت‌هاب", "Step 1 of 2: GitHub")
            line = stringResource(R.string.gt_line_no_account)
            dialAction = { GtEngine.signIn(adding = false) }
        }
        live -> {
            headline = stringResource(R.string.gt_head_ready)
            line = stringResource(R.string.gt_line_ready, countdown(session!!.expiresAt, now))
            dialAction = { withVpnConsent { GtEngine.connect() } }
        }
        else -> {
            headline = stringResource(R.string.gt_head_idle)
            line = stringResource(R.string.gt_line_idle)
            dialAction = { withVpnConsent { GtEngine.createSession(connectAfter = true) } }
        }
    }

    IosScreen(title = stringResource(R.string.gt_title), onBack = onBack) {
        // ── the decision ────────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Dial(
                on = connected,
                working = building || linking,
                failed = !connected && (st.link.phase == "failed" || st.run?.step == "FAILED"),
                enabled = dialAction != null,
                label = when {
                    connected -> stringResource(R.string.disconnect)
                    building || linking -> stringResource(R.string.gt_dial_working)
                    !hasAccounts -> stringResource(R.string.gt_dial_signin)
                    live -> stringResource(R.string.connect)
                    else -> stringResource(R.string.gt_dial_start)
                },
                onClick = { dialAction?.invoke() },
            )
            Spacer(Modifier.height(14.dp))
            Text(headline, color = Ios.Label, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(6.dp))
            Text(line, color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 28.dp))
        }
        com.mlmvpn.scanner.ui.RouteChips()

        // A failure, as one sentence and (when there is one) the one thing that fixes it.
        val failure = when {
            st.link.phase == "failed" && !connected -> st.link.error to st.link.code
            st.run?.step == "FAILED" -> st.run?.error.orEmpty() to st.run?.code.orEmpty()
            else -> null
        }
        if (failure != null && failure.first.isNotBlank()) {
            Notice(failure.first, Ios.Red)
            when (failure.second) {
                "NO_CLEAN_IP" -> SettingsGroup { SettingsActionRow(stringResource(R.string.gt_fix_scanner), Icons.Default.Radar, Ios.Blue, onClick = onOpenScanner) }
                "BROKER_NOT_DEPLOYED", "BROKER_NEEDS_UPDATE" -> Unit
                "RUN_DIED_EARLY", "NONE_AVAILABLE" -> SettingsGroup {
                    SettingsActionRow(stringResource(R.string.gt_add_account), Icons.Default.Add, Ios.Green, onClick = { GtEngine.signIn(adding = true) })
                }
                else -> if (live) SettingsGroup {
                    SettingsActionRow(stringResource(R.string.gt_fix_retry), Icons.Default.Autorenew, Ios.Blue, onClick = { withVpnConsent { GtEngine.connect() } })
                }
            }
        }

        // ── the GitHub code ───────────────────────────────────────────────────────────
        st.signIn?.let { SignInCard(it, context) }

        // ── what is still missing ───────────────────────────────────────────────────────
        if (!setupDone || (!live && !connected && !building)) {
            SettingsSectionHeader(stringResource(R.string.gt_sec_setup))
            SettingsGroup {
                SetupStep(
                    number = 1, current = setupStep,
                    title = tr("وصل کردن گیت‌هاب", "Connect GitHub"),
                    value = if (hasAccounts) "@" + st.accounts.first().login else stringResource(R.string.gt_step_do_signin),
                    busy = st.signIn?.waiting == true,
                    onClick = if (setupStep == 1) ({ GtEngine.signIn(adding = false) }) else null,
                )
                Separator()
                SetupStep(
                    number = 2, current = setupStep,
                    title = tr("اتصال", "Connect"),
                    value = when {
                        live -> stringResource(R.string.gt_ready)
                        building -> stringResource(R.string.gt_working)
                        setupDone -> tr("با دکمهٔ بالا", "With the button above")
                        else -> ""
                    },
                    busy = building,
                    onClick = if (setupDone && !building) ({ withVpnConsent { if (live) GtEngine.connect() else GtEngine.createSession(connectAfter = true) } }) else null,
                )
            }
            SettingsFooter(tr(
                "فقط یک حساب گیت‌هاب لازم است: سرور ابری خودش از راه رله‌های رایگان (بدون کلادفلر) در دسترس می‌شود. ورکر کلادفلر پایین صفحه اختیاری است و راه ورود دیگری اضافه می‌کند.",
                "A GitHub account is all it takes: the cloud server is reached through free relays (no Cloudflare). The Cloudflare Worker further down is optional and adds another way in."))
        }

        // ── the session being built ─────────────────────────────────────────────────────
        if (building) {
            SettingsSectionHeader(stringResource(R.string.gt_sec_building))
            SettingsGroup {
                listOf("SETTING_UP", "STARTING", "INSTALLING", "CONNECTING_NETWORK", "READY").forEachIndexed { i, step ->
                    if (i > 0) Separator()
                    val order = listOf("SETTING_UP", "STARTING", "INSTALLING", "CONNECTING_NETWORK", "READY")
                    val at = order.indexOf(st.run?.step.orEmpty())
                    StepRow(stepLabel(step), done = i < at, active = i == at)
                }
            }
            if (st.log.isNotEmpty()) LogBlock(st.log.takeLast(6))
        }

        // ── the session ─────────────────────────────────────────────────────────────────
        if (live && session != null) {
            SettingsSectionHeader(stringResource(R.string.gt_sec_session))
            SettingsGroup {
                SettingsRow(stringResource(R.string.gt_time_left), icon = Icons.Default.Schedule,
                    tint = if (session.status == "EXPIRING_SOON") Ios.Orange else Ios.Green,
                    value = countdown(session.expiresAt, now), showChevron = false)
                if (session.runnerCountry.isNotBlank()) {
                    Separator()
                    SettingsRow(stringResource(R.string.gt_server_place), icon = Icons.Default.Place, tint = Ios.Blue,
                        value = listOf(countryName(session.runnerCountry), session.runnerCity).filter { it.isNotBlank() }.joinToString(" — "),
                        showChevron = false)
                }
                Separator()
                SettingsRow(stringResource(R.string.gt_on_account), icon = Icons.Default.AccountCircle, tint = Ios.Gray,
                    value = "@" + session.accountLogin, showChevron = false)
                Separator()
                SettingsToggle(stringResource(R.string.gt_autorenew), checked = st.autoRenew,
                    onCheckedChange = { GtEngine.setAutoRenew(it) }, icon = Icons.Default.Autorenew, tint = Ios.Teal,
                    subtitle = if (st.renewing) stringResource(R.string.gt_renewing_now) else stringResource(R.string.gt_autorenew_hint))
                Separator()
                var confirmEnd by remember { mutableStateOf(false) }
                SettingsActionRow(stringResource(R.string.gt_end_session), Icons.Default.StopCircle, Ios.Orange,
                    onClick = { confirmEnd = true })
                if (confirmEnd) IosAlert(
                    title = stringResource(R.string.gt_end_title),
                    message = stringResource(R.string.gt_end_message),
                    actions = listOf(
                        IosAlertAction(stringResource(R.string.cancel_2), { confirmEnd = false }),
                        IosAlertAction(stringResource(R.string.gt_end_confirm), { confirmEnd = false; GtEngine.endSession() }, destructive = true),
                    ),
                    onDismiss = { confirmEnd = false },
                )
            }
            SettingsFooter(stringResource(R.string.gt_session_footer))
        }

        // ── the connection ──────────────────────────────────────────────────────────────
        if (connected || st.link.ips.isNotEmpty()) {
            SettingsSectionHeader(stringResource(R.string.gt_sec_link))
            SettingsGroup {
                SettingsRow(stringResource(R.string.gt_clean_ips), icon = Icons.Default.Link, tint = Ios.Orange,
                    value = st.link.ips.joinToString("، ").ifBlank { "—" }, showChevron = false)
                if (st.link.delayMs > 0) {
                    Separator()
                    SettingsRow(stringResource(R.string.gt_delay), icon = Icons.Default.Speed, tint = Ios.Green,
                        value = stringResource(R.string.gt_ms, st.link.delayMs.toInt()), showChevron = false)
                }
                if (connected) {
                    Separator()
                    SettingsActionRow(stringResource(R.string.disconnect), Icons.Default.PowerSettingsNew, Ios.Red,
                        onClick = { GtEngine.disconnect() })
                }
            }
            SettingsFooter(stringResource(R.string.gt_link_footer))
        }

        // Exit, rules and accounts mean nothing until the three setup steps are done.
        if (setupDone) {
        // ── the exit country ────────────────────────────────────────────────────────────
        SettingsSectionHeader(stringResource(R.string.gt_sec_exit))
        SettingsGroup {
            SettingsRow(stringResource(R.string.gt_exit_country), icon = Icons.Default.Public, tint = Ios.Blue,
                value = if (ex.prefs.country.isBlank()) stringResource(R.string.gt_exit_fastest) else CountryLabel.withFlag(ex.prefs.country),
                subtitle = if (ex.prefs.country.isBlank()) stringResource(R.string.gt_exit_fastest_detail) else null,
                onClick = { page = "country" })
            if (ex.prefs.country.isNotBlank()) {
                Separator()
                SettingsRow(stringResource(R.string.gt_exit_provider), icon = Icons.Default.Hub, tint = Ios.Purple,
                    value = providerLabel(ex.prefs.provider),
                    subtitle = providerNote(ex.prefs.provider),
                    onClick = {
                        val order = listOf("", "vpngate", "psiphon")
                        GtEngine.setExitPrefs(ex.prefs.copy(provider = order[(order.indexOf(ex.prefs.provider) + 1) % order.size]))
                    })
            }
            if (connected) {
                val busyExit = ex.job == "preparing" || ex.job == "switching"
                Separator()
                SettingsRow(stringResource(R.string.gt_exit_seen), icon = Icons.Default.Visibility, tint = Ios.Green,
                    value = when {
                        busyExit -> stringResource(R.string.gt_working)
                        ex.seenCountry.isNotBlank() -> CountryLabel.withFlag(ex.seenCountry)
                        else -> "—"
                    },
                    subtitle = listOf(ex.seenCity, ex.seenIp).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                    showChevron = false, onClick = { GtEngine.refreshExits() })
                Separator()
                // Measured, not labelled: where a STUN server sees this tunnel's UDP come from.
                SettingsRow(stringResource(R.string.gt_exit_udp), icon = Icons.Default.Call,
                    tint = when {
                        ex.udpState == "none" -> Ios.Red
                        ex.udpState == "ok" && ex.seenCountry.isNotBlank() && ex.udpCountry != ex.seenCountry -> Ios.Orange
                        ex.udpState == "ok" -> Ios.Green
                        else -> Ios.Gray
                    },
                    value = when {
                        busyExit || ex.udpState == "testing" -> stringResource(R.string.gt_exit_udp_testing)
                        ex.udpState == "none" -> stringResource(R.string.gt_exit_udp_none)
                        ex.udpState == "ok" && ex.udpCountry.isNotBlank() -> CountryLabel.withFlag(ex.udpCountry)
                        ex.udpState == "ok" -> ex.udpIp
                        else -> "—"
                    },
                    subtitle = when {
                        ex.udpState != "ok" -> null
                        ex.udpIp == ex.seenIp -> stringResource(R.string.gt_exit_udp_same)
                        ex.seenCountry.isNotBlank() && ex.udpCountry != ex.seenCountry -> ex.udpIp + " · " + stringResource(R.string.gt_exit_udp_other)
                        else -> ex.udpIp
                    },
                    showChevron = false, onClick = { GtEngine.refreshExits() })
            }
        }
        when (ex.job) {
            "preparing" -> Notice(stringResource(R.string.gt_exit_preparing, countryName(ex.jobCountry)), Ios.Blue)
            "switching" -> Notice(stringResource(R.string.gt_exit_switching), Ios.Blue)
            "partial", "failed" -> if (ex.jobError.isNotBlank()) Notice(stringResource(R.string.gt_exit_problem, ex.jobError), Ios.Orange)
        }
        SettingsFooter(stringResource(R.string.gt_exit_footer) + " " + stringResource(R.string.gt_exit_udp_footer))

        // What the runner is holding open for this phone: which one is the default, which carry a
        // rule, and — the question that decides calls and games — which of them carry UDP.
        if (connected && ex.exits.isNotEmpty()) {
            SettingsSectionHeader(stringResource(R.string.gt_sec_exits_on_server))
            SettingsGroup {
                ex.exits.forEachIndexed { i, x ->
                    if (i > 0) Separator()
                    SettingsRow(
                        title = CountryLabel.withFlag(x.country) + (if (x.city.isNotBlank()) " — " + x.city else ""),
                        icon = Icons.Default.Dns,
                        tint = when (x.state) { "ready" -> Ios.Green; "starting" -> Ios.Orange; else -> Ios.Red },
                        value = when {
                            x.state == "ready" && x.id == ex.use -> stringResource(R.string.gt_exit_role_default)
                            x.state == "ready" && x.id in ex.inUse -> stringResource(R.string.gt_exit_role_rule)
                            x.state == "ready" -> stringResource(R.string.gt_exit_state_ready)
                            x.state == "starting" -> stringResource(R.string.gt_exit_state_starting)
                            else -> stringResource(R.string.gt_exit_state_failed)
                        },
                        subtitle = exitLine(x),
                        showChevron = false,
                    )
                }
            }
            SettingsFooter(stringResource(R.string.gt_exits_footer))
        }

        // ── sites and apps from another country ─────────────────────────────────────────
        SettingsSectionHeader(stringResource(R.string.gt_sec_rules))
        SettingsGroup {
            ex.prefs.rules.forEachIndexed { i, r ->
                if (i > 0) Separator()
                SettingsRow(
                    title = CountryLabel.withFlag(r.country) + (if (r.provider.isNotBlank()) " · " + providerLabel(r.provider) else ""),
                    icon = Icons.Default.Language, tint = Ios.Teal,
                    subtitle = ruleSummary(context, r),
                    onClick = {
                        draft = RuleDraft(i, r.country, r.provider, r.domains.joinToString(" "), r.apps.toSet())
                        page = "rule"
                    },
                )
            }
            if (ex.prefs.rules.isNotEmpty()) Separator()
            SettingsActionRow(stringResource(R.string.gt_rule_add), Icons.Default.Add, Ios.Green,
                onClick = { draft = RuleDraft(); page = "rule" })
        }
        SettingsFooter(stringResource(R.string.gt_rules_footer))

        // ── GitHub accounts ─────────────────────────────────────────────────────────────
        SettingsSectionHeader(stringResource(R.string.gt_sec_accounts))
        SettingsGroup {
            var menuFor by remember { mutableStateOf<GtAccount?>(null) }
            st.accounts.forEachIndexed { i, a ->
                if (i > 0) Separator()
                SettingsRow(
                    title = "@" + a.login,
                    icon = Icons.Default.AccountCircle,
                    tint = when (a.health) { "OK" -> if (a.disabled) Ios.Gray else Ios.Green; "AUTH_REQUIRED" -> Ios.Red; else -> Ios.Orange },
                    value = accountState(a),
                    onClick = { menuFor = a },
                )
            }
            if (st.accounts.isNotEmpty()) Separator()
            SettingsActionRow(stringResource(R.string.gt_add_account), Icons.Default.Add, Ios.Green,
                busy = st.signIn?.waiting == true, onClick = { GtEngine.signIn(adding = st.accounts.isNotEmpty()) })
            menuFor?.let { a ->
                IosAlert(
                    title = "@" + a.login,
                    message = a.healthReason.takeIf { it.isNotBlank() && a.health != "OK" },
                    actions = listOfNotNull(
                        if (a.health != "OK") IosAlertAction(stringResource(R.string.gt_acc_retry), { menuFor = null; GtEngine.retryAccount(a.id) }) else null,
                        IosAlertAction(stringResource(if (a.disabled) R.string.gt_acc_enable else R.string.gt_acc_disable),
                            { menuFor = null; GtEngine.setAccountDisabled(a.id, !a.disabled) }),
                        IosAlertAction(stringResource(R.string.gt_acc_remove), { menuFor = null; GtEngine.removeAccount(a.id) }, destructive = true),
                        IosAlertAction(stringResource(R.string.cancel_2), { menuFor = null }, preferred = true),
                    ),
                    onDismiss = { menuFor = null },
                )
            }
        }
        SettingsFooter(stringResource(R.string.gt_accounts_footer))

        }

        // ── the relay Worker ────────────────────────────────────────────────────────────
        // Optional: one more way in, over Cloudflare. Without it the runner's free relays carry the
        // session; with it, both are measured and whichever answers is used.
        run {
        SettingsSectionHeader(tr("ورکر کلادفلر (اختیاری)", "Cloudflare Worker (optional)"))
        SettingsGroup {
            if (cloudAccounts.isEmpty()) {
                SettingsActionRow(stringResource(R.string.gt_broker_need_cf), Icons.Default.Cloud, Ios.CloudflareOrange, onClick = onOpenCloud)
            } else {
                SettingsRow(
                    title = stringResource(R.string.gt_broker_account),
                    icon = Icons.Default.Cloud, tint = Ios.CloudflareOrange,
                    value = cf?.let { it.name.ifBlank { it.email } } ?: "—",
                    showChevron = cloudAccounts.size > 1,
                    onClick = if (cloudAccounts.size > 1) ({
                        val i = cloudAccounts.indexOfFirst { it.id == cf?.id }
                        pickedCf = cloudAccounts[(i + 1) % cloudAccounts.size].id
                    }) else null,
                )
                if (st.broker.deployed) {
                    Separator()
                    // The address as the second line, not the trailing value: a workers.dev name is long
                    // enough to squeeze the label into two broken lines.
                    SettingsRow(stringResource(R.string.gt_broker_address), icon = Icons.Default.Link, tint = Ios.Indigo,
                        subtitle = st.broker.effectiveUrl.removePrefix("https://"), showChevron = false,
                        onClick = { copy(context, st.broker.effectiveUrl) })
                }
                Separator()
                SettingsActionRow(
                    stringResource(if (st.broker.deployed) R.string.gt_broker_update else R.string.gt_broker_deploy),
                    Icons.Default.CloudUpload, Ios.Blue,
                    busy = st.brokerBusy,
                    enabled = cf != null,
                    onClick = { cf?.let { GtEngine.deployBroker(it) } },
                )
            }
        }
        if (st.brokerError.isNotBlank()) Notice(st.brokerError, Ios.Red)
        SettingsFooter(stringResource(R.string.gt_broker_footer))
        }

        var customUrl by remember(st.broker.customUrl) { mutableStateOf(st.broker.customUrl) }
        if (st.broker.deployed) {
            SettingsGroup(modifier = Modifier.padding(top = 12.dp)) {
                SettingsTextRow(
                    title = stringResource(R.string.gt_broker_custom),
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    placeholder = "https://edge.example.com",
                )
                if (customUrl.trim() != st.broker.customUrl) {
                    Separator()
                    SettingsActionRow(stringResource(R.string.gt_save), Icons.Default.CheckCircle, Ios.Blue,
                        onClick = { GtEngine.setCustomUrl(customUrl) })
                }
            }
            SettingsFooter(stringResource(R.string.gt_broker_custom_footer))
        }

        // ── the rest ────────────────────────────────────────────────────────────────────
        SettingsSectionHeader(stringResource(R.string.gt_sec_more))
        var confirmReset by remember { mutableStateOf(false) }
        SettingsGroup {
            SettingsActionRow(stringResource(R.string.gt_reset), Icons.Default.DeleteForever, Ios.Red,
                labelColor = Ios.Red, onClick = { confirmReset = true })
        }
        SettingsFooter(stringResource(R.string.gt_reset_footer))
        if (confirmReset) IosAlert(
            title = stringResource(R.string.gt_reset_title),
            message = stringResource(R.string.gt_reset_footer),
            actions = listOf(
                IosAlertAction(stringResource(R.string.cancel_2), { confirmReset = false }),
                IosAlertAction(stringResource(R.string.gt_reset), { confirmReset = false; GtEngine.resetAll() }, destructive = true),
            ),
            onDismiss = { confirmReset = false },
        )
        if (!building && st.log.isNotEmpty()) {
            SettingsSectionHeader(stringResource(R.string.gt_sec_log))
            LogBlock(st.log.takeLast(8))
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ── pieces ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun Dial(on: Boolean, working: Boolean, failed: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    val accent = when { on -> Ios.Green; failed -> Ios.Red; else -> Ios.Indigo }
    val spin = if (working) {
        rememberInfiniteTransition(label = "gt-dial").animateFloat(0f, 360f,
            infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "gt-spin").value
    } else 0f
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(176.dp).clip(CircleShape).background(accent.copy(alpha = if (on) 0.16f else 0.07f)))
        Box(Modifier.size(150.dp).clip(CircleShape).background(accent.copy(alpha = 0.07f)).border(1.dp, accent.copy(alpha = 0.28f), CircleShape))
        if (working) {
            androidx.compose.foundation.Canvas(Modifier.size(150.dp).rotate(spin)) {
                val stroke = 3.dp.toPx()
                drawArc(accent, -90f, 96f, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
        }
        Box(
            modifier = Modifier.size(118.dp).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = if (enabled) 0.95f else 0.45f), accent.copy(alpha = if (enabled) 0.62f else 0.3f))))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (on) Icons.Default.Power else Icons.Default.PowerSettingsNew, null, tint = Color.White, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Notice(text: String, tint: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Ios.Label, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun SignInCard(s: com.mlmvpn.scanner.engines.github.GtSignIn, context: Context) {
    SettingsSectionHeader(stringResource(if (s.adding) R.string.gt_signin_add_title else R.string.gt_signin_title))
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.userCode.isBlank() && s.error.isBlank()) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Ios.Blue, strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.gt_signin_making), color = Ios.SecondaryLabel, fontSize = 13.sp)
            } else if (s.userCode.isNotBlank()) {
                Text(stringResource(R.string.gt_signin_enter), color = Ios.SecondaryLabel, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(s.userCode, color = Ios.Label, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f))
                        .clickable { copy(context, s.userCode) }.padding(horizontal = 22.dp, vertical = 8.dp))
                Spacer(Modifier.height(6.dp))
                Text(s.verificationUri, color = Ios.SecondaryLabel, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                if (s.waiting) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Ios.SecondaryLabel, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.gt_signin_waiting), color = Ios.SecondaryLabel, fontSize = 12.sp)
                    }
                }
            }
            if (s.error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(s.error, color = Ios.Red, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        if (s.userCode.isNotBlank() && s.waiting) {
            Separator()
            SettingsActionRow(stringResource(R.string.gt_signin_open), Icons.Default.OpenInBrowser, Ios.Blue, onClick = {
                copy(context, s.userCode)
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.verificationUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
            Separator()
            SettingsActionRow(stringResource(R.string.gt_signin_copy), Icons.Default.ContentCopy, Ios.Label, onClick = { copy(context, s.userCode) })
        }
        if (!s.waiting) {
            Separator()
            SettingsActionRow(stringResource(R.string.gt_fix_retry), Icons.Default.Autorenew, Ios.Blue, onClick = { GtEngine.signIn(s.adding) })
        }
        Separator()
        SettingsActionRow(stringResource(R.string.cancel_2), Icons.Default.StopCircle, Ios.SecondaryLabel, onClick = { GtEngine.cancelSignIn() })
    }
    SettingsFooter(stringResource(if (s.adding) R.string.gt_signin_add_footer else R.string.gt_signin_footer))
}

/** One setup step: done (check), the current one (its number, tappable), or still ahead (dimmed). */
@Composable
private fun SetupStep(number: Int, current: Int, title: String, value: String, busy: Boolean, onClick: (() -> Unit)?) {
    val done = number < current
    val active = number == current
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(
                when { done -> Ios.Green; active -> Ios.Blue; else -> Color.White.copy(alpha = 0.12f) }
            ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                done -> Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(18.dp))
                active && busy -> CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                else -> Text(com.mlmvpn.scanner.ui.faCount(number), color = if (active) Color.White else Ios.SecondaryLabel,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(title, color = if (done || active) Ios.Label else Ios.SecondaryLabel, fontSize = 15.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(value, color = if (active) Ios.Blue else Ios.SecondaryLabel, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun StepRow(label: String, done: Boolean, active: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(Icons.Default.CheckCircle, null, tint = Ios.Green, modifier = Modifier.size(20.dp))
                active -> CircularProgressIndicator(Modifier.size(16.dp), color = Ios.Blue, strokeWidth = 2.dp)
                else -> Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (done || active) Ios.Label else Ios.SecondaryLabel, fontSize = 15.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun LogBlock(lines: List<String>) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.28f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        lines.forEach { Text(it, color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 18.sp) }
    }
}

/** The picker's «automatic» code (RegionPickerScreen's own); here it means «حداکثر سرعت». */
private const val AUTO_CODE = "auto"

/** Countries a runner usually offers, for before the first session has told us its own list. */
private val USUAL_EXITS = listOf("JP", "KR", "US", "DE", "NL", "GB", "FR", "SG", "CA", "SE", "CH", "TH", "VN", "RU", "TW")

@Composable
private fun ExitCountryPage(ex: GtExitUi, forRule: Boolean, onPick: (String) -> Unit, onBack: () -> Unit) {
    val known = ex.vpngate.isNotEmpty() || ex.psiphon.isNotEmpty()
    val codes = remember(ex.vpngate, ex.psiphon) {
        (ex.vpngate.filterValues { it > 0 }.keys + ex.psiphon).filter { Regex("^[A-Z]{2}$").matches(it) }.distinct()
            .ifEmpty { USUAL_EXITS }
    }
    RegionPickerScreen(
        title = stringResource(if (forRule) R.string.gt_rule_country_title else R.string.gt_exit_title),
        memoryKey = "gt_exit",
        codes = codes,
        selected = if (forRule) "" else ex.prefs.country,
        label = { CountryLabel.localized(it) },
        detail = { cc -> exitDetail(ex, cc) },
        footer = stringResource(if (known) R.string.gt_exit_picker_footer else R.string.gt_exit_picker_footer_guess),
        onSelect = onPick,
        onBack = onBack,
        backLabel = stringResource(R.string.gt_title),
        autoName = stringResource(R.string.gt_exit_fastest),
        autoDetail = stringResource(R.string.gt_exit_fastest_detail),
        showAuto = !forRule,
    )
}

private fun exitDetail(ex: GtExitUi, cc: String): String {
    val n = ex.vpngate[cc] ?: 0
    val ps = cc in ex.psiphon
    return when {
        n > 0 && ps -> S(R.string.gt_exit_detail_both, n)
        n > 0 -> S(R.string.gt_exit_detail_vpngate, n)
        ps -> S(R.string.gt_exit_detail_psiphon)
        else -> ""
    }
}

private fun providerLabel(p: String): String = when (p) {
    "vpngate" -> "VPN Gate"
    "psiphon" -> "Psiphon"
    else -> S(R.string.gt_exit_provider_auto)
}

/** What choosing this provider means for calls and games (runner/exits.mjs: only VPN Gate carries UDP). */
private fun providerNote(p: String): String = when (p) {
    "vpngate" -> S(R.string.gt_provider_note_vpngate)
    "psiphon" -> S(R.string.gt_provider_note_psiphon)
    else -> S(R.string.gt_provider_note_auto)
}

/**
 * «VPN Gate · با UDP · 1.2.3.4», or why it failed. Whether an exit carries UDP is the runner's own
 * report (`udp`), which follows its provider: VPN Gate's OpenVPN tunnel does, Psiphon does not.
 */
private fun exitLine(x: GtExitInfo): String {
    if (x.state == "failed" && x.error.isNotBlank()) return x.error.take(140)
    val udp = if (x.state == "ready") x.udp else x.provider == "vpngate"
    val parts = mutableListOf(providerLabel(x.provider), S(if (udp) R.string.gt_with_udp else R.string.gt_no_udp))
    if (x.ip.isNotBlank()) parts += x.ip
    // Each part isolated (FSI…PDI): «با UDP» next to an address otherwise joins it in one
    // left-to-right run, and the line reads «UDP · 1.2.3.4 با · VPN Gate».
    return parts.joinToString(" · ") { "⁨$it⁩" }
}

// ── a rule: sites and apps that leave from another country ─────────────────────────────

/** A rule being edited: `index` -1 for a new one. Apps are package names. */
private data class RuleDraft(
    val index: Int = -1,
    val country: String = "",
    val provider: String = "",
    val sites: String = "",
    val apps: Set<String> = emptySet(),
) {
    fun toRule() = GtExitRule(country, provider, GtExitPrefs.cleanDomains(sites), GtExitPrefs.cleanApps(apps))
}

/** «instagram.com، x.com · ۲ برنامه: تلگرام، واتساپ» — what a rule sends out, in one line. */
private fun ruleSummary(context: Context, r: GtExitRule): String {
    val parts = mutableListOf<String>()
    if (r.domains.isNotEmpty()) {
        parts += r.domains.take(3).joinToString("، ") + if (r.domains.size > 3) " +" + (r.domains.size - 3) else ""
    }
    if (r.apps.isNotEmpty()) {
        val names = r.apps.take(3).map { appLabel(context, it) }
        parts += S(R.string.gt_rule_summary_apps, r.apps.size) + ": " + names.joinToString("، ") +
            if (r.apps.size > 3) " +" + (r.apps.size - 3) else ""
    }
    return parts.joinToString(" · ")
}

private fun appLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) { pkg }

private fun appIcon(context: Context, pkg: String): android.graphics.drawable.Drawable? = try {
    context.packageManager.getApplicationIcon(pkg)
} catch (_: Exception) { null }

/**
 * One rule, on a page of its own: where it leaves from, which sites, which apps. Saving folds it
 * into the prefs through [GtExitPrefs.withRule] — a site or an app can belong to one rule only, and
 * a second rule for a country already used is merged into the first.
 */
@Composable
private fun RuleEditorPage(
    draft: RuleDraft,
    ex: GtExitUi,
    proxyMode: Boolean,
    onChange: (RuleDraft) -> Unit,
    onPickCountry: () -> Unit,
    onPickApps: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val domains = remember(draft.sites) { GtExitPrefs.cleanDomains(draft.sites) }
    val rules = ex.prefs.rules
    val mergesInto = rules.withIndex().firstOrNull { (i, r) ->
        i != draft.index && draft.country.isNotBlank() && r.country == draft.country && r.provider == draft.provider
    }
    val countries = rules.withIndex().filter { it.index != draft.index }.map { it.value.country to it.value.provider }.toSet()
    val full = mergesInto == null && draft.country.isNotBlank() && countries.size >= GtExitPrefs.MAX_RULES
    val problem = when {
        draft.country.isBlank() -> stringResource(R.string.gt_rule_need_country)
        domains.isEmpty() && draft.apps.isEmpty() -> stringResource(R.string.gt_rule_need_items)
        full -> stringResource(R.string.gt_rule_full)
        else -> null
    }

    IosScreen(
        title = stringResource(if (draft.index >= 0) R.string.gt_rule_edit_title else R.string.gt_rule_new_title),
        onBack = onBack,
        backLabel = stringResource(R.string.gt_title),
    ) {
        SettingsSectionHeader(stringResource(R.string.gt_rule_sec_where))
        SettingsGroup {
            SettingsRow(stringResource(R.string.gt_rule_country), icon = Icons.Default.Public, tint = Ios.Blue,
                value = if (draft.country.isBlank()) stringResource(R.string.gt_rule_pick) else CountryLabel.withFlag(draft.country),
                subtitle = draft.country.takeIf { it.isNotBlank() }?.let { exitDetail(ex, it) }?.ifBlank { null },
                onClick = onPickCountry)
            Separator()
            SettingsRow(stringResource(R.string.gt_exit_provider), icon = Icons.Default.Hub, tint = Ios.Purple,
                value = providerLabel(draft.provider), subtitle = providerNote(draft.provider),
                onClick = {
                    val order = listOf("", "vpngate", "psiphon")
                    onChange(draft.copy(provider = order[(order.indexOf(draft.provider) + 1) % order.size]))
                })
        }
        if (mergesInto != null) SettingsFooter(stringResource(R.string.gt_rule_merge_note))

        SettingsSectionHeader(stringResource(R.string.gt_rule_sites))
        SettingsGroup { SitesField(draft.sites) { onChange(draft.copy(sites = it)) } }
        SettingsFooter(
            (if (domains.isNotEmpty()) stringResource(R.string.gt_rule_sites_count, domains.size) + " " else "") +
                stringResource(R.string.gt_rule_sites_footer)
        )

        SettingsSectionHeader(stringResource(R.string.gt_rule_sec_apps))
        SettingsGroup {
            draft.apps.sorted().forEach { pkg ->
                SelectedAppRow(context, pkg, onRemove = { onChange(draft.copy(apps = draft.apps - pkg)) })
                Separator()
            }
            SettingsActionRow(stringResource(R.string.gt_rule_pick_apps), Icons.Default.Apps, Ios.Blue, onClick = onPickApps)
        }
        SettingsFooter(stringResource(
            when {
                proxyMode -> R.string.gt_rule_apps_proxy
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q -> R.string.gt_rule_apps_old_android
                else -> R.string.gt_rule_apps_footer
            }
        ))

        SettingsGroup(modifier = Modifier.padding(top = 18.dp)) {
            SettingsActionRow(stringResource(R.string.gt_rule_save), Icons.Default.CheckCircle, Ios.Blue,
                enabled = problem == null, onClick = onSave)
            if (draft.index >= 0) {
                Separator()
                SettingsActionRow(stringResource(R.string.gt_rule_remove), Icons.Default.DeleteForever, Ios.Red,
                    labelColor = Ios.Red, onClick = onDelete)
            }
        }
        if (problem != null) SettingsFooter(problem)
        Spacer(Modifier.height(40.dp))
    }
}

/** A box for many addresses: one per line or space-separated, always left-to-right. */
@Composable
private fun SitesField(value: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = androidx.compose.ui.text.TextStyle(color = Ios.Label, fontSize = 15.sp,
            textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Ios.Blue),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text("instagram.com  x.com  telegram.org", color = Ios.SecondaryLabel, fontSize = 15.sp,
                        style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
                }
                inner()
            }
        },
    )
}

/** One chosen app: its icon and name, and a cross that takes it out of the rule. */
@Composable
private fun SelectedAppRow(context: Context, pkg: String, onRemove: () -> Unit) {
    val label = remember(pkg) { appLabel(context, pkg) }
    val icon = remember(pkg) { appIcon(context, pkg) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon.toBitmap(64, 64).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)),
            )
        } else {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(Ios.Gray.copy(alpha = 0.4f)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Ios.Label, fontSize = 15.sp, maxLines = 1)
            Text(pkg, color = Ios.SecondaryLabel, fontSize = 11.sp, maxLines = 1)
        }
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.gt_rule_app_remove),
            tint = Ios.SecondaryLabel,
            modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onRemove).padding(7.dp))
    }
}

// ── words ───────────────────────────────────────────────────────────────────────────────

private fun countdown(expiresAt: Long, now: Long): String {
    val left = ((expiresAt - now) / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(java.util.Locale.US, left / 3600, (left % 3600) / 60, left % 60)
}

private fun stepLabel(step: String): String = when (step) {
    "SETTING_UP" -> S(R.string.gt_step_setting_up)
    "STARTING" -> S(R.string.gt_step_starting)
    "INSTALLING" -> S(R.string.gt_step_installing)
    "CONNECTING_NETWORK" -> S(R.string.gt_step_network)
    "READY" -> S(R.string.gt_step_ready)
    else -> step
}

@Composable
private fun countryName(cc: String): String = com.mlmvpn.scanner.ui.tunnel.CountryLabel.localized(cc)

private fun accountState(a: GtAccount): String = when {
    a.disabled -> S(R.string.gt_acc_disabled)
    a.health == "EXHAUSTED" -> S(R.string.gt_acc_exhausted)
    a.health == "AUTH_REQUIRED" -> S(R.string.gt_acc_auth)
    a.health == "RATE_LIMITED" || a.cooldownUntil > System.currentTimeMillis() -> S(R.string.gt_acc_cooling)
    a.health != "OK" -> S(R.string.gt_acc_problem)
    else -> S(R.string.gt_ready)
}

private fun copy(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MLMVPN", text))
        android.widget.Toast.makeText(context, S(R.string.gt_copied), android.widget.Toast.LENGTH_SHORT).show()
    }
}
