package com.mlmvpn.scanner.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudAdvice
import com.mlmvpn.scanner.data.CloudFixAction
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.CloudVerifyProbe
import com.mlmvpn.scanner.data.advice
import com.mlmvpn.scanner.data.credentialAdvice
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import kotlinx.coroutines.launch

/**
 * Cloudflare troubleshooting: what Cloudflare answered, what to try, and the levers.
 *
 * This page exists because of a rule the cloud panel had to learn the hard way. Connecting an
 * account works for most people, so a fix for the ones it fails for must not be a change in how
 * the app treats everybody -- it has to be something the stuck user can reach and apply to their
 * own account. Three parts, in the order a person actually uses them:
 *
 *  1. **What was seen.** The raw answer -- which header shape was used, what HTTP status and which
 *     Cloudflare error code came back. Not for the user to interpret, but so that what they report
 *     and what they are looking at are the same thing.
 *  2. **The suggestion.** Findings derived from those error codes, each with the single button that
 *     acts on it. See `advice()`: every branch there is keyed to a documented code, and anything
 *     unrecognised says so instead of inventing a cause.
 *  3. **The levers.** Always present, whatever the suggestion says, because a diagnosis this app
 *     has never seen is exactly the case where the user knows more than it does. Each changes one
 *     setting, on this account only, and can be changed back.
 *
 * It answers in both directions. With an [account] it is the full page above. Called before an
 * account exists -- from the add screen, when adding failed -- [credential] is set instead, the
 * probe asks only whether the credential itself works, and the levers are gone because there is
 * nothing yet to set them on. Without that second mode the page would be unreachable by the users
 * who never got past the first step.
 */
@Composable
fun CloudTroubleshootScreen(
    cloudManager: CloudManager,
    onBack: () -> Unit,
    backLabel: String,
    account: CloudAccount? = null,
    /** token to email, for the pre-account mode. */
    credential: Pair<String, String>? = null,
    /** Fired whenever a lever or a fix changed the account, so the panel behind can catch up. */
    onAccountChanged: () -> Unit = {},
) {
    var doctorOpen by remember { mutableStateOf(false) }
    if (doctorOpen) {
        com.mlmvpn.scanner.ui.doctor.CfDoctorScreen(onBack = { doctorOpen = false }, credential = credential, account = account, visible = LocalTabVisible.current)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var probe by remember { mutableStateOf<CloudVerifyProbe?>(null) }
    // Derived once per probe rather than inside the layout: the rows below have to agree on one
    // list, and re-evaluating the rules per row would put two independent copies on screen.
    var findings by remember { mutableStateOf<List<CloudAdvice>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var reportSent by remember { mutableStateOf(false) }
    // Read once into state so the switches move under the user's finger; the account object behind
    // them is written on every change.
    var unlockEmail by remember { mutableStateOf(account?.isEmailVerified ?: true) }
    var markSubdomain by remember { mutableStateOf(account?.hasSubdomain ?: false) }
    var scheme by remember { mutableStateOf(account?.authScheme) }

    fun reprobe() {
        busy = true
        scope.launch {
            val p = when {
                account != null -> cloudManager.probeAccountStatus(account)
                credential != null -> cloudManager.probeCredential(credential.first, credential.second)
                else -> null
            }
            probe = p
            findings = p?.let { if (account != null) it.advice() else it.credentialAdvice() }.orEmpty()
            if (account != null) {
                scheme = account.authScheme
                if (p?.hasSubdomain == true) {
                    account.hasSubdomain = true
                    markSubdomain = true
                    cloudManager.saveAccounts()
                    onAccountChanged()
                }
            }
            busy = false
        }
    }

    LaunchedEffect(account?.id, credential) { reprobe() }

    fun sendReport() {
        val p = probe ?: return
        scope.launch {
            val where = if (account != null) "troubleshooting an added account"
                        else "troubleshooting a credential that could not be added"
            val ok = com.mlmvpn.scanner.quick.MlmPoolClient.reportDiagnostic(
                context,
                summary = p.summary(),
                body = p.report(context, where),
            )
            reportSent = ok
            android.widget.Toast.makeText(
                context,
                context.getString(if (ok) R.string.cloud_diag_sent else R.string.cloud_diag_failed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun applyScheme(next: String?) {
        val acc = account ?: return
        acc.authScheme = next
        scheme = next
        cloudManager.saveAccounts()
        onAccountChanged()
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.cf_fix_scheme_changed, context.getString(schemeLabel(next))),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        reprobe()
    }

    fun act(item: CloudAdvice) {
        when (item.action) {
            CloudFixAction.RETRY -> reprobe()
            CloudFixAction.SWITCH_SCHEME -> applyScheme(if (scheme == "global") "bearer" else "global")
            CloudFixAction.CREATE_SUBDOMAIN -> {
                val acc = account ?: return
                busy = true
                scope.launch {
                    val r = cloudManager.createSubdomain(acc)
                    if (r.ok) {
                        acc.hasSubdomain = true
                        markSubdomain = true
                        cloudManager.saveAccounts()
                        onAccountChanged()
                    }
                    reprobe()
                }
            }
            CloudFixAction.UNLOCK_EMAIL -> {
                val acc = account ?: return
                acc.isEmailVerified = true
                unlockEmail = true
                cloudManager.saveAccounts()
                onAccountChanged()
            }
            CloudFixAction.REPORT -> sendReport()
            CloudFixAction.NONE -> Unit
        }
    }

    IosScreen(
        title = stringResource(R.string.cf_fix_title),
        onBack = onBack,
        backLabel = backLabel,
    ) {
        Spacer(Modifier.height(10.dp))
        SettingsFooter(stringResource(R.string.cf_fix_intro))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.cf_doctor_title),
                icon = Icons.Default.CloudOff,
                tint = Ios.Blue,
                onClick = { doctorOpen = true },
            )
        }

        // ---- what was seen -------------------------------------------------------------------
        SettingsSectionHeader(stringResource(R.string.cf_fix_status))
        val p = probe
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.cf_fix_scheme_row),
                icon = Icons.Default.Key,
                tint = Ios.Indigo,
                value = if (p == null) stringResource(R.string.update_checking)
                        else stringResource(schemeLabel(p.scheme)),
                showChevron = false,
            )
            Separator()
            SettingsRow(
                title = if (account != null) stringResource(R.string.cf_fix_subprobe)
                        else stringResource(R.string.cf_fix_accounts_probe),
                icon = Icons.Default.Language,
                tint = Ios.Blue,
                value = if (p == null) stringResource(R.string.update_checking)
                        else httpValue(p.subdomainHttp),
                subtitle = p?.subdomainErrors?.takeIf { it.isNotBlank() },
                showChevron = false,
            )
            Separator()
            SettingsRow(
                title = stringResource(R.string.cf_fix_tokencheck),
                icon = Icons.Default.Shield,
                tint = Ios.Teal,
                value = when {
                    p == null -> stringResource(R.string.update_checking)
                    p.tokenVerifyHttp == 0 -> stringResource(R.string.cf_fix_not_checked)
                    else -> httpValue(p.tokenVerifyHttp)
                },
                subtitle = p?.tokenVerifyErrors?.takeIf { it.isNotBlank() },
                showChevron = false,
            )
        }
        p?.transport?.takeIf { it.isNotBlank() }?.let { SettingsFooter(it) }

        // ---- the suggestion ------------------------------------------------------------------
        if (findings.isNotEmpty()) {
            SettingsSectionHeader(stringResource(R.string.cf_fix_advice))
            findings.forEach { item ->
                val tone = when (item.severity) {
                    2 -> Ios.Red
                    1 -> Ios.Orange
                    else -> Ios.Green
                }
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(item.titleRes),
                        icon = when (item.severity) {
                            2 -> Icons.Default.CloudOff
                            1 -> Icons.Default.Warning
                            else -> Icons.Default.CheckCircle
                        },
                        tint = tone,
                        subtitle = stringResource(item.bodyRes),
                        showChevron = false,
                    )
                    if (item.action != CloudFixAction.NONE) {
                        Separator()
                        SettingsActionRow(
                            label = stringResource(item.actionLabelRes),
                            icon = actionIcon(item.action),
                            tint = tone,
                            busy = busy,
                            enabled = !busy,
                            onClick = { act(item) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        // ---- the levers ----------------------------------------------------------------------
        // Only with an account behind them. Before one exists there is nothing to write to, and a
        // switch that saves nowhere is worse than no switch.
        if (account != null) {
            SettingsSectionHeader(stringResource(R.string.cf_fix_manual))
            SettingsGroup {
                SchemeChoice(stringResource(R.string.cf_fix_scheme_auto), scheme == null, true) {
                    applyScheme(null)
                }
                Separator()
                SchemeChoice(stringResource(R.string.cf_fix_scheme_bearer), scheme == "bearer", true) {
                    applyScheme("bearer")
                }
                Separator()
                // A Global API Key is meaningless without the account email, so offering it to an
                // account that has none would only produce another failed probe.
                SchemeChoice(
                    stringResource(R.string.cf_fix_scheme_global),
                    scheme == "global",
                    account.email.isNotBlank(),
                ) { applyScheme("global") }
            }
            SettingsFooter(stringResource(R.string.cf_fix_scheme_desc))

            SettingsGroup(modifier = Modifier.padding(top = 18.dp)) {
                SettingsToggle(
                    title = stringResource(R.string.cf_fix_unlock_email),
                    subtitle = stringResource(R.string.cf_fix_unlock_email_desc),
                    checked = unlockEmail,
                    icon = Icons.Default.LockOpen,
                    tint = Ios.Orange,
                    onCheckedChange = {
                        unlockEmail = it
                        account.isEmailVerified = it
                        cloudManager.saveAccounts()
                        onAccountChanged()
                    },
                )
                Separator()
                SettingsToggle(
                    title = stringResource(R.string.cf_fix_mark_subdomain),
                    subtitle = stringResource(R.string.cf_fix_mark_subdomain_desc),
                    checked = markSubdomain,
                    icon = Icons.Default.Language,
                    tint = Ios.Purple,
                    onCheckedChange = {
                        markSubdomain = it
                        account.hasSubdomain = it
                        cloudManager.saveAccounts()
                        onAccountChanged()
                    },
                )
            }
            SettingsFooter(stringResource(R.string.cf_fix_manual_note))
        }

        // ---- always available ------------------------------------------------------------------
        SettingsGroup(modifier = Modifier.padding(top = 18.dp)) {
            SettingsActionRow(
                label = stringResource(R.string.cf_fix_recheck),
                icon = Icons.Default.Refresh,
                tint = Ios.Blue,
                busy = busy,
                enabled = !busy,
                onClick = { reprobe() },
            )
            // Hidden when a finding above already carries this button. Two "send the report"
            // buttons a thumb apart read as two different reports.
            if (findings.none { it.action == CloudFixAction.REPORT }) {
                Separator()
                SettingsActionRow(
                    label = stringResource(
                        if (reportSent) R.string.cloud_diag_sent else R.string.cf_advice_send_report
                    ),
                    icon = Icons.Default.Send,
                    tint = if (reportSent) Ios.Green else Ios.Gray,
                    enabled = probe != null && !reportSent,
                    onClick = { sendReport() },
                )
            }
        }
        SettingsFooter(stringResource(R.string.cf_fix_report_footer))

        Spacer(Modifier.height(28.dp))
    }
}

/** One row of the credential picker: a check on the chosen one, iOS's own idiom for a choice. */
@Composable
private fun com.mlmvpn.scanner.ui.settings.SettingsGroupScope.SchemeChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = label,
        icon = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        tint = if (!enabled) Ios.Gray else if (selected) Ios.Blue else Ios.Gray,
        titleColor = if (enabled) Ios.Label else Ios.SecondaryLabel,
        showChevron = false,
        onClick = if (enabled) onClick else null,
    )
}

private fun schemeLabel(scheme: String?): Int = when (scheme) {
    "bearer", "bearer?" -> R.string.cf_fix_scheme_bearer
    "global", "global?" -> R.string.cf_fix_scheme_global
    else -> R.string.cf_fix_scheme_auto
}

private fun actionIcon(action: CloudFixAction) = when (action) {
    CloudFixAction.RETRY -> Icons.Default.Refresh
    CloudFixAction.SWITCH_SCHEME -> Icons.Default.Key
    CloudFixAction.CREATE_SUBDOMAIN -> Icons.Default.Language
    CloudFixAction.UNLOCK_EMAIL -> Icons.Default.LockOpen
    CloudFixAction.REPORT -> Icons.Default.Send
    CloudFixAction.NONE -> Icons.Default.Info
}

/** `HTTP 403`, or the word for a request that never got there. */
@Composable
private fun httpValue(http: Int): String =
    if (http < 0) stringResource(R.string.cf_fix_no_answer) else "HTTP $http"
