package com.mlmvpn.scanner.ui.doctor

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.engines.cfdoctor.AndroidNetEnv
import com.mlmvpn.scanner.engines.cfdoctor.DoctorAdvice
import com.mlmvpn.scanner.engines.cfdoctor.DoctorEngine
import com.mlmvpn.scanner.engines.cfdoctor.StageVerdict
import com.mlmvpn.scanner.engines.cfdoctor.Verdict
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class Page { MAIN, ACCOUNT, REPORT }

/**
 * Cloudflare Doctor, laid out like the app's other iOS-style sections: one glass card with the
 * big action and the live state, the four results as rows, then what was found, what works, and
 * the report. The run belongs to [DoctorEngine], not to this screen -- leaving it (to send the
 * last report in Telegram, say) does not stop a check.
 *
 * [credential] arrives from the add-account page after a failed attempt (memory only);
 * [account] narrows the check to one saved account.
 */
@Composable
fun CfDoctorScreen(onBack: () -> Unit, credential: Pair<String, String>? = null, account: CloudAccount? = null, visible: Boolean = true) {
    val context = LocalContext.current
    remember { DoctorEngine.init(context); true }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    // Typed credentials live only here, never in saved state, and are dropped once a run starts.
    var token by remember { mutableStateOf(credential?.first.orEmpty()) }
    var email by remember { mutableStateOf(credential?.second.orEmpty()) }
    BackHandler(enabled = visible) { if (page != Page.MAIN) page = Page.MAIN else onBack() }
    when (page) {
        Page.ACCOUNT -> AccountPage(token, email, { token = it }, { email = it }, onBack = { page = Page.MAIN })
        Page.REPORT -> ReportPage(onBack = { page = Page.MAIN })
        Page.MAIN -> DoctorMain(
            onBack = onBack, account = account,
            hasTypedCredential = token.isNotBlank(),
            onAccount = { page = Page.ACCOUNT },
            onReport = { page = Page.REPORT },
            onStart = { full, label ->
                val c = token.trim().takeIf { it.isNotBlank() }?.let { it to email.trim() }
                DoctorEngine.start(context, full, label, c, account)
                token = ""; email = ""
            },
        )
    }
}

@Composable
private fun DoctorMain(
    onBack: () -> Unit, account: CloudAccount?, hasTypedCredential: Boolean,
    onAccount: () -> Unit, onReport: () -> Unit, onStart: (Boolean, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by DoctorEngine.state.collectAsState()
    var label by rememberSaveable { mutableStateOf(AndroidNetEnv.operator(context).let { if (it in setOf("OTHER", "UNKNOWN")) "" else it }) }
    var full by rememberSaveable { mutableStateOf(false) }
    val savedAccounts = remember { runCatching { CloudManager(context).accountsFlow.value.size }.getOrDefault(0) }
    val report = DoctorEngine.report

    var pendingSave by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(pendingSave.toByteArray()) } ?: error("no output") }.isSuccess
            }
            Toast.makeText(context, S(if (ok) R.string.doc_saved else R.string.doc_save_failed), Toast.LENGTH_SHORT).show()
            pendingSave = ""
        }
    }

    IosScreen(onBack = onBack, backLabel = stringResource(R.string.home), largeTitle = stringResource(R.string.cf_doctor_title)) {
        Hero(state, onStart = { onStart(full, label) }, onStop = { DoctorEngine.stop() })

        // ---- the four results, live while running.
        if (state.running || state.advice != null || state.progress.rows.isNotEmpty()) {
            SettingsSectionHeader(stringResource(R.string.doc_stages))
            SettingsGroup {
                val stages = liveStages(state)
                StageRow(Icons.Default.Cloud, Ios.Blue, stringResource(R.string.doc_st1), stages[0])
                Separator()
                StageRow(Icons.Default.CloudDownload, Ios.Teal, stringResource(R.string.doc_st2), stages[1])
                Separator()
                StageRow(Icons.Default.Link, Ios.Green, stringResource(R.string.doc_st3), stages[2])
                Separator()
                StageRow(Icons.Default.Radar, Ios.Purple, stringResource(R.string.doc_st4), stages[3])
            }
        }

        val advice = state.advice
        if (advice != null && !state.running) {
            if (advice.findings.isNotEmpty()) {
                SettingsSectionHeader(stringResource(R.string.doc_findings))
                SettingsGroup {
                    advice.findings.forEachIndexed { i, f ->
                        if (i > 0) Separator()
                        TextRow(Icons.Default.Warning, Ios.Orange, findingText(f))
                    }
                }
            }
            if (advice.notes.isNotEmpty()) {
                SettingsSectionHeader(stringResource(R.string.doc_notes))
                SettingsGroup {
                    advice.notes.forEachIndexed { i, f ->
                        if (i > 0) Separator()
                        TextRow(Icons.Default.Info, Ios.SecondaryLabel, findingText(f))
                    }
                }
                SettingsFooter(stringResource(R.string.doc_notes_footer))
            }
            if (advice.works.isNotEmpty()) {
                SettingsSectionHeader(stringResource(R.string.doc_works))
                SettingsGroup {
                    advice.works.forEachIndexed { i, w ->
                        if (i > 0) Separator()
                        TextRow(Icons.Default.CheckCircle, Ios.Green, worksText(w))
                    }
                }
            }
        }

        // ---- options, before a run.
        if (!state.running) {
            SettingsSectionHeader(stringResource(R.string.doc_options))
            SettingsGroup {
                SettingsTextRow(
                    title = stringResource(R.string.doc_net_label), value = label, onValueChange = { label = it.take(40) },
                    icon = Icons.Default.Speed, tint = Ios.Indigo, placeholder = stringResource(R.string.doc_net_label_hint),
                )
                Separator()
                SettingsToggle(
                    title = stringResource(R.string.doc_full), subtitle = stringResource(R.string.doc_full_sub),
                    checked = full, onCheckedChange = { full = it }, icon = Icons.Default.Radar, tint = Ios.Purple,
                )
                if (account == null) {
                    Separator()
                    SettingsRow(
                        title = stringResource(R.string.doc_with_account), subtitle = stringResource(R.string.doc_with_account_sub),
                        icon = Icons.Default.Person, tint = Ios.Orange,
                        value = stringResource(if (hasTypedCredential) R.string.doc_account_set else R.string.doc_account_off),
                        onClick = onAccount,
                    )
                }
            }
            if (savedAccounts > 0 && account == null) SettingsFooter(stringResource(R.string.doc_saved_accounts, fa(savedAccounts)))
        }

        // ---- the report.
        if (report.isNotBlank() && !state.running) {
            SettingsSectionHeader(stringResource(R.string.doc_report))
            SettingsGroup {
                SettingsActionRow(label = stringResource(R.string.doc_share), icon = Icons.Default.Send, tint = Ios.Blue) {
                    if (!DoctorShare.share(context, DoctorEngine.fileName(context), report))
                        Toast.makeText(context, S(R.string.doc_share_failed), Toast.LENGTH_SHORT).show()
                }
                Separator()
                SettingsActionRow(label = stringResource(R.string.doc_save), icon = Icons.Default.Save, tint = Ios.Teal) {
                    pendingSave = report; save.launch(DoctorEngine.fileName(context))
                }
                Separator()
                SettingsActionRow(label = stringResource(R.string.doc_group), icon = Icons.Default.Group, tint = Ios.Indigo) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/mlmvpn")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                Separator()
                SettingsActionRow(label = stringResource(R.string.doc_view), icon = Icons.Default.Description, onClick = onReport)
            }
            SettingsFooter(stringResource(R.string.doc_report_footer))
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ------------------------------------------------------------------------------ the hero card

@Composable
private fun Hero(state: DoctorEngine.State, onStart: () -> Unit, onStop: () -> Unit) {
    val advice = state.advice
    val tone by animateColorAsState(
        when {
            state.running -> Ios.Blue
            advice == null -> Ios.Blue
            advice.overall == DoctorAdvice.Overall.ALL_GOOD -> Ios.Green
            advice.overall == DoctorAdvice.Overall.PARTIAL -> Ios.Orange
            advice.overall == DoctorAdvice.Overall.PROBLEMS -> Ios.Orange
            else -> Ios.Red
        }, animationSpec = tween(450), label = "tone",
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).frostedGlass(RoundedCornerShape(30.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_app_doctor), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(52.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.cf_doctor_title), color = Ios.Label, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.doc_subtitle), color = Ios.SecondaryLabel, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(18.dp))

        when {
            state.running -> {
                val shown by animateFloatAsState(state.fraction, animationSpec = tween(600), label = "fraction")
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
                    CircularProgressIndicator(progress = 1f, color = Color.White.copy(alpha = 0.12f), strokeWidth = 8.dp, modifier = Modifier.size(132.dp))
                    CircularProgressIndicator(progress = shown, color = tone, strokeWidth = 8.dp, modifier = Modifier.size(132.dp))
                    Text("${fa((shown * 100).toInt())}٪".let { if (Locale.getDefault().language == "fa") it else "${(shown * 100).toInt()}%" },
                        color = Ios.Label, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                Text(stepText(state.step), color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                var now by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
                val secs = (state.progress.elapsedMs / 1000).coerceAtLeast(0)
                Text(
                    stringResource(R.string.doc_tests_done, fa(state.progress.rows.size), fa("%02d:%02d".format(Locale.US, secs / 60, secs % 60))),
                    color = Ios.SecondaryLabel, fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                Capsule(stringResource(R.string.doc_stop), Color.White.copy(alpha = 0.14f), Ios.Red, onStop)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.doc_running_note), color = Ios.SecondaryLabel, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            advice != null -> {
                val (icon, text) = when (advice.overall) {
                    DoctorAdvice.Overall.ALL_GOOD -> Icons.Default.CheckCircle to stringResource(R.string.doc_overall_good)
                    DoctorAdvice.Overall.PROBLEMS -> Icons.Default.Warning to stringResource(R.string.doc_overall_problems, fa(advice.findings.size))
                    DoctorAdvice.Overall.NO_INTERNET -> Icons.Default.Error to stringResource(R.string.doc_overall_no_internet)
                    DoctorAdvice.Overall.NO_INTERNATIONAL -> Icons.Default.Error to stringResource(R.string.doc_overall_no_intl)
                    DoctorAdvice.Overall.PARTIAL -> Icons.Default.Warning to stringResource(R.string.doc_overall_partial)
                }
                Box(Modifier.size(84.dp).clip(CircleShape).background(tone.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(46.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(text, color = Ios.Label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (state.failed) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.doc_failed), color = Ios.Orange, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                Capsule(stringResource(R.string.doc_again), Ios.Blue, Color.White, onStart)
            }
            else -> {
                Text(stringResource(R.string.doc_intro), color = Ios.SecondaryLabel, fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Capsule(stringResource(R.string.doc_start), Ios.Blue, Color.White, onStart)
            }
        }
    }
}

@Composable
private fun Capsule(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

// ------------------------------------------------------------------------------ stage rows

/** A stage's state on screen: a result, or where it is in the run. */
private sealed class StageUi {
    object Waiting : StageUi()
    object Running : StageUi()
    data class Done(val index: Int, val verdict: Verdict, val layer: String) : StageUi()
}

private fun liveStages(state: DoctorEngine.State): List<StageUi> {
    val rows = state.progress.rows
    val results = listOf(
        StageVerdict.account(rows, state.hasCredential), StageVerdict.chain(rows, "ST2"),
        StageVerdict.chain(rows, "ST3"), StageVerdict.chain(rows, "ST4"),
    )
    val ids = listOf("ST1", "ST2", "ST3", "ST4")
    val steps = listOf(DoctorEngine.Step.ACCOUNT, DoctorEngine.Step.PANEL, DoctorEngine.Step.CONFIG, DoctorEngine.Step.SCANNER)
    return results.mapIndexed { i, r ->
        val hasRows = rows.any { it.id == ids[i] && it.numbers["terminal"] == 1L }
        when {
            !state.running -> StageUi.Done(i, r.verdict, r.layer)
            state.step == steps[i] -> StageUi.Running
            hasRows || state.step.ordinal > steps[i].ordinal -> StageUi.Done(i, r.verdict, r.layer)
            else -> StageUi.Waiting
        }
    }
}

@Composable
private fun StageRow(icon: ImageVector, tint: Color, title: String, s: StageUi) {
    val (value, color) = when (s) {
        StageUi.Waiting -> stringResource(R.string.doc_v_waiting) to Ios.SecondaryLabel
        StageUi.Running -> stringResource(R.string.doc_v_running) to Ios.Blue
        is StageUi.Done -> when (s.verdict) {
            Verdict.PASS -> stringResource(R.string.doc_v_pass) to Ios.Green
            Verdict.DEGRADED -> stringResource(R.string.doc_v_degraded) to Ios.Orange
            Verdict.FAIL -> stringResource(R.string.doc_v_fail) to Ios.Red
            Verdict.SKIPPED -> stringResource(R.string.doc_v_skipped) to Ios.SecondaryLabel
        }
    }
    val detail = (s as? StageUi.Done)?.let { layerText(it) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(icon, tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios.Label, fontSize = 16.sp)
            if (!detail.isNullOrBlank()) Text(detail, color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (s == StageUi.Running) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Ios.Blue, strokeWidth = 2.dp)
        } else {
            Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

/** One line under a stage: why it was skipped, or the finding that names its failure. */
@Composable
private fun layerText(s: StageUi.Done): String? {
    if (s.verdict == Verdict.PASS) return null
    return when (s.layer) {
        "cf.auth.not_tested", "not_run" -> stringResource(R.string.doc_skip_no_account)
        "panel.none" -> stringResource(R.string.doc_skip_no_panel)
        "config.none" -> stringResource(R.string.doc_skip_no_config)
        "scan.config.none" -> stringResource(R.string.doc_skip_no_scan_config)
        "unfinished" -> stringResource(R.string.doc_skip_unfinished)
        else -> DoctorAdvice.stageFinding(s.index, s.verdict, s.layer)?.let { findingText(it) }
    }
}

@Composable
private fun TextRow(icon: ImageVector, tint: Color, text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Ios.Label, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun stepText(step: DoctorEngine.Step): String = stringResource(when (step) {
    DoctorEngine.Step.IDLE, DoctorEngine.Step.TRIAGE -> R.string.doc_step_triage
    DoctorEngine.Step.ACCOUNT -> R.string.doc_step_account
    DoctorEngine.Step.PANEL -> R.string.doc_step_panel
    DoctorEngine.Step.CONFIG -> R.string.doc_step_config
    DoctorEngine.Step.SCANNER -> R.string.doc_step_scanner
    DoctorEngine.Step.METHODS, DoctorEngine.Step.DONE -> R.string.doc_step_methods
    DoctorEngine.Step.VARIANTS -> R.string.doc_step_variants
})

@Composable
private fun findingText(f: DoctorAdvice.Finding): String = stringResource(when (f) {
    DoctorAdvice.Finding.DNS_POISONED -> R.string.doc_f_dns_poisoned
    DoctorAdvice.Finding.IP_BLOCK_ALL -> R.string.doc_f_ip_block_all
    DoctorAdvice.Finding.IP_BLOCK_PARTIAL -> R.string.doc_f_ip_block_partial
    DoctorAdvice.Finding.SNI_BLOCK -> R.string.doc_f_sni_block
    DoctorAdvice.Finding.TLS_BLOCK_ALL -> R.string.doc_f_tls_block_all
    DoctorAdvice.Finding.SILENT_AFTER_TLS -> R.string.doc_f_silent_after_tls
    DoctorAdvice.Finding.UDP_SUSPECT -> R.string.doc_f_udp_suspect
    DoctorAdvice.Finding.ACCOUNT_NETWORK -> R.string.doc_f_account_network
    DoctorAdvice.Finding.ACCOUNT_ROUTE_ONLY -> R.string.doc_f_account_route_only
    DoctorAdvice.Finding.ACCOUNT_AUTH -> R.string.doc_f_account_auth
    DoctorAdvice.Finding.ACCOUNT_RATELIMIT -> R.string.doc_f_account_ratelimit
    DoctorAdvice.Finding.ACCOUNT_SETUP -> R.string.doc_f_account_setup
    DoctorAdvice.Finding.ACCOUNT_SERVER -> R.string.doc_f_account_server
    DoctorAdvice.Finding.ACCOUNT_SOME_FAILED -> R.string.doc_f_account_some_failed
    DoctorAdvice.Finding.PANEL_HOST -> R.string.doc_f_panel_host
    DoctorAdvice.Finding.PANEL_SUB -> R.string.doc_f_panel_sub
    DoctorAdvice.Finding.PANEL_PARSE -> R.string.doc_f_panel_parse
    DoctorAdvice.Finding.CONFIG_TCP -> R.string.doc_f_config_tcp
    DoctorAdvice.Finding.CONFIG_TLS -> R.string.doc_f_config_tls
    DoctorAdvice.Finding.CONFIG_WS -> R.string.doc_f_config_ws
    DoctorAdvice.Finding.CONFIG_QUOTA -> R.string.doc_f_config_quota
    DoctorAdvice.Finding.CONFIG_PROXY -> R.string.doc_f_config_proxy
    DoctorAdvice.Finding.CONFIG_EGRESS -> R.string.doc_f_config_egress
    DoctorAdvice.Finding.CONFIG_APP -> R.string.doc_f_config_app
    DoctorAdvice.Finding.CONFIG_CORE -> R.string.doc_f_config_core
    DoctorAdvice.Finding.CONFIG_SOME_FAILED -> R.string.doc_f_config_some_failed
    DoctorAdvice.Finding.SCAN_NOT_CDN -> R.string.doc_f_scan_not_cdn
    DoctorAdvice.Finding.SCAN_TCP -> R.string.doc_f_scan_tcp
    DoctorAdvice.Finding.SCAN_TLS -> R.string.doc_f_scan_tls
    DoctorAdvice.Finding.SCAN_WS -> R.string.doc_f_scan_ws
    DoctorAdvice.Finding.SCAN_PROXY -> R.string.doc_f_scan_proxy
    DoctorAdvice.Finding.SCAN_EGRESS -> R.string.doc_f_scan_egress
    DoctorAdvice.Finding.SCAN_CORE -> R.string.doc_f_scan_core
})

@Composable
private fun worksText(w: DoctorAdvice.Works): String = stringResource(when (w) {
    DoctorAdvice.Works.DOH -> R.string.doc_w_doh
    DoctorAdvice.Works.DOT -> R.string.doc_w_dot
    DoctorAdvice.Works.FAKE_SNI_DNS -> R.string.doc_w_fake_sni_dns
    DoctorAdvice.Works.ALT_PORT -> R.string.doc_w_alt_port
    DoctorAdvice.Works.NO_SNI -> R.string.doc_w_no_sni
    DoctorAdvice.Works.FAKE_SNI -> R.string.doc_w_fake_sni
    DoctorAdvice.Works.MIXED_CASE_SNI -> R.string.doc_w_mixed_case_sni
    DoctorAdvice.Works.FRAGMENT -> R.string.doc_w_fragment
    DoctorAdvice.Works.TLS12 -> R.string.doc_w_tls12
    DoctorAdvice.Works.TLS13 -> R.string.doc_w_tls13
    DoctorAdvice.Works.HTTP2 -> R.string.doc_w_http2
    DoctorAdvice.Works.CONFIG_FRAGMENT -> R.string.doc_w_config_fragment
    DoctorAdvice.Works.CONFIG_FINGERPRINT -> R.string.doc_w_config_fingerprint
    DoctorAdvice.Works.CONFIG_TLS_SHAPE -> R.string.doc_w_config_tls_shape
    DoctorAdvice.Works.CONFIG_ECH -> R.string.doc_w_config_ech
    DoctorAdvice.Works.CONFIG_XHTTP -> R.string.doc_w_config_xhttp
    DoctorAdvice.Works.CONFIG_CLEAN_IP -> R.string.doc_w_config_clean_ip
    DoctorAdvice.Works.CONFIG_PORT -> R.string.doc_w_config_port
    DoctorAdvice.Works.CONFIG_MIXED_SNI -> R.string.doc_w_config_mixed_sni
    DoctorAdvice.Works.SCAN_CAN_WORK -> R.string.doc_w_scan_can_work
})

// ------------------------------------------------------------------------------ pushed pages

/** «تست با حساب من»: two fields, kept in memory only. */
@Composable
private fun AccountPage(token: String, email: String, onToken: (String) -> Unit, onEmail: (String) -> Unit, onBack: () -> Unit) {
    IosScreen(title = stringResource(R.string.doc_with_account), onBack = onBack, backLabel = stringResource(R.string.cf_doctor_title)) {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            Field(stringResource(R.string.doc_token), token, onToken, secret = true)
            Separator()
            Field(stringResource(R.string.doc_email), email, onEmail, secret = false)
        }
        SettingsFooter(stringResource(R.string.doc_with_account_sub))
    }
}

@Composable
private fun Field(placeholder: String, value: String, onChange: (String) -> Unit, secret: Boolean) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, color = Ios.SecondaryLabel.copy(alpha = 0.7f), fontSize = 16.sp)
        BasicTextField(
            value = value, onValueChange = { onChange(it.trim().take(200)) }, singleLine = true,
            textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp), cursorBrush = SolidColor(Ios.Blue),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The whole report, exactly as it would be sent, before anyone sends it. */
@Composable
private fun ReportPage(onBack: () -> Unit) {
    val lines = remember { DoctorEngine.report.lines() }
    IosScreen(title = stringResource(R.string.doc_report), onBack = onBack, backLabel = stringResource(R.string.cf_doctor_title), scrollable = false) {
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Ios.CardOverWallpaper),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(lines) { Text(it, color = Ios.Label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun fa(n: Int): String = fa(n.toString())

private fun fa(s: String): String =
    if (Locale.getDefault().language != "fa") s
    else s.map { c -> if (c in '0'..'9') '۰' + (c - '0') else c }.joinToString("")
