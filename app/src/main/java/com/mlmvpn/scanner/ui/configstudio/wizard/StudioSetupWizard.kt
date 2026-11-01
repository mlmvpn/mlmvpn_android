package com.mlmvpn.scanner.ui.configstudio.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.studio.StudioDeployer
import com.mlmvpn.scanner.data.studio.StudioDiscovery
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.CloudAddAccountForm
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PayloadCard
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.StepDots
import com.mlmvpn.scanner.ui.configstudio.parts.StepHeader
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * First run: from nothing to an engine this device can talk to.
 *
 * The shape is `FreeConfigWizard`'s — an enum of steps that **includes the waiting states**, so a
 * long network operation is a step the wizard is on rather than a spinner laid over a step it is
 * not. The chrome comes from `parts/StudioKit`.
 *
 * The one structural thing worth stating: **step 2 exists so that step 3 can be honest.** The old
 * deployer went straight to deploying, which is how an account ends up with two engines — the second
 * one serving nothing anybody has a link to. Here the account is examined first, and what step 3
 * offers depends on what was found. When the examination *fails*, step 3 offers nothing at all: not
 * being able to see the account is exactly the state in which deploying does damage.
 */
private enum class Step { ACCOUNT, CHECKING, ENGINE, INSTALLING, DONE }

/**
 * @param excludeAccountIds accounts already carrying an installation.
 *
 *   Passed when this wizard is re-entered to add *another* Cloudflare account to the fleet (§A.8),
 *   so step 1 offers only the accounts that are not in it yet. Without it the list is every
 *   credential in «ابری», and picking one already in the fleet would walk three steps to arrive at
 *   "connected" for an account that already was.
 */
@Composable
fun StudioSetupWizard(
    onExit: () -> Unit,
    onFinished: () -> Unit,
    excludeAccountIds: Set<String> = emptySet(),
    /** False while Config Studio is parked off screen: its back handler must not take the press. */
    backEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud = remember { CloudManager(context) }

    var step by remember { mutableStateOf(Step.ACCOUNT) }
    var account by remember { mutableStateOf<CloudAccount?>(null) }
    var scan by remember { mutableStateOf<StudioDiscovery.Result?>(null) }
    var progress by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var downgrade by remember { mutableStateOf<StudioDeployer.Result.WouldDowngrade?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    // ONE BackHandler for the whole wizard, at the host of it -- the pattern GstSetupWizard uses.
    // A handler per step means the ones on parked steps stay registered and the back button starts
    // depending on which screens have been visited.
    BackHandler(enabled = backEnabled) {
        when (step) {
            Step.ACCOUNT -> onExit()
            Step.CHECKING, Step.INSTALLING -> Unit // in flight; leaving mid-deploy is the bad case
            Step.ENGINE -> step = Step.ACCOUNT
            Step.DONE -> onFinished()
        }
    }

    // What the deploy that just finished really got: false when Cloudflare would not give this
    // account the device counter, so the last step can say so instead of hedging.
    var deviceLimits by remember { mutableStateOf<Boolean?>(null) }

    // The account's old engine, when it has one, was adopted in this run: a retry after the central
    // Worker failed does not redeploy it a second time.
    var engineAdopted by remember { mutableStateOf(false) }

    fun beginCheck(picked: CloudAccount) {
        account = picked
        failure = null
        engineAdopted = false
        step = Step.CHECKING
        scope.launch {
            scan = StudioDiscovery(context).scan(picked)
            step = Step.ENGINE
        }
    }

    /**
     * Two parts, in this order (the operator's choice, 2026-10-18 — new people live on the central
     * Worker, everything on this phone):
     *  1. an engine already on the account is ADOPTED as before (D8), so the people on it keep their
     *     links and everything they had — an engine is never installed fresh any more;
     *  2. the account's central Worker is installed, or adopted when it is already there (one Worker
     *     for every panel), and the account joins the Studio on it: new people go there.
     */
    fun beginInstall(force: Boolean = false) {
        val target = account ?: return
        failure = null
        downgrade = null
        step = Step.INSTALLING
        scope.launch {
            if ((scan as? StudioDiscovery.Result.Adopted) != null && !engineAdopted) {
                when (val res = StudioDeployer(context).install(target, force) { _, message -> progress = message }) {
                    is StudioDeployer.Result.Ready -> {
                        deviceLimits = res.deviceLimits
                        engineAdopted = true
                    }
                    is StudioDeployer.Result.WouldDowngrade -> {
                        downgrade = res
                        step = Step.ENGINE
                        return@launch
                    }
                    is StudioDeployer.Result.Failed -> {
                        failure = res.message
                        step = Step.ENGINE
                        return@launch
                    }
                }
            }
            com.mlmvpn.scanner.data.studio.central.CentralFleet.register(context, target)
            com.mlmvpn.scanner.engines.gateway.EdgeGateway.deploy(context, target) { message -> progress = message }
                .onSuccess {
                    com.mlmvpn.scanner.data.studio.central.StudioCentralJob.ensure(context)
                    step = Step.DONE
                }
                .onFailure {
                    failure = com.mlmvpn.scanner.engines.gateway.EdgeGateway.localized(it)
                    step = Step.ENGINE
                }
        }
    }

    IosScreen(
        title = S(R.string.studio_title),
        onBack = if (step == Step.CHECKING || step == Step.INSTALLING) null else onExit,
        backLabel = S(R.string.studio_wizard_back),
    ) {
        Spacer(Modifier.height(10.dp))
        StepDots(current = step.ordinal.coerceAtMost(2) + 1, total = 3)

        when (step) {
            Step.ACCOUNT -> AccountStep(
                accounts = cloud.accounts.filter { it.id !in excludeAccountIds },
                adding = adding,
                error = addError,
                onPick = ::beginCheck,
                onAdd = { email, key ->
                    adding = true
                    addError = null
                    scope.launch {
                        val (ok, message) = cloud.addAccount(key, email)
                        adding = false
                        if (ok) cloud.accounts.lastOrNull()?.let(::beginCheck) else addError = message
                    }
                },
            )

            Step.CHECKING -> {
                StepHeader(S(R.string.studio_step_check_title), S(R.string.studio_step_check_sub))
                WizardBusy(S(R.string.studio_check_working))
            }

            Step.ENGINE -> EngineStep(
                scan = scan,
                failure = failure,
                downgrade = downgrade,
                onInstall = { beginInstall() },
                onRetry = { account?.let(::beginCheck) },
            )

            Step.INSTALLING -> {
                StepHeader(S(R.string.studio_step_engine_title), S(R.string.studio_step_engine_sub_new))
                WizardBusy(progress.ifEmpty { S(R.string.studio_installing) })
            }

            Step.DONE -> DoneStep(
                account = account,
                adopted = (scan as? StudioDiscovery.Result.Adopted) != null,
                deviceLimits = deviceLimits,
                onFinish = onFinished,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AccountStep(
    accounts: List<CloudAccount>,
    adding: Boolean,
    error: String?,
    onPick: (CloudAccount) -> Unit,
    onAdd: (String, String) -> Unit,
) {
    StepHeader(S(R.string.studio_step_account_title), S(R.string.studio_step_account_sub))

    if (accounts.isNotEmpty()) {
        SettingsSectionHeader(S(R.string.account))
        SettingsGroup {
            accounts.forEachIndexed { index, acc ->
                if (index > 0) Separator()
                SettingsActionRow(
                    label = acc.name.ifEmpty { acc.email },
                    icon = StudioIcons.Accounts,
                    tint = Ios.Blue,
                ) { onPick(acc) }
            }
        }
        Spacer(Modifier.height(14.dp))
    } else {
        InfoCard(S(R.string.studio_step_account_none))
        Spacer(Modifier.height(14.dp))
    }

    // The form itself, inline. Sending the operator to «ابری» to type a credential and find their
    // own way back is a seam they should never see -- and the account they add there is literally
    // the same record, so there is nothing to reconcile afterwards (plan D2, F1).
    SettingsSectionHeader(S(R.string.studio_add_account))
    CloudAddAccountForm(isAdding = adding, error = error, onAdd = onAdd, showHeader = false)
    InfoCard(S(R.string.studio_account_shared))
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.EngineStep(
    scan: StudioDiscovery.Result?,
    failure: String?,
    downgrade: StudioDeployer.Result.WouldDowngrade?,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
) {
    val found = (scan as? StudioDiscovery.Result.Adopted)?.found
    val scanFailed = scan as? StudioDiscovery.Result.Failed

    StepHeader(
        S(R.string.studio_step_engine_title),
        if (found != null) S(R.string.studio_step_engine_sub_found) else S(R.string.studio_step_engine_sub_new),
    )

    when {
        // Could not see the account. No install button at all: this is precisely the state in which
        // deploying creates a second engine beside one that already exists.
        scanFailed != null -> {
            ProblemCard(S(R.string.studio_check_failed))
            Spacer(Modifier.height(14.dp))
            WizardPrimary(S(R.string.studio_retry), onClick = onRetry)
        }

        downgrade != null -> {
            ProblemCard(
                S(R.string.studio_downgrade_body) + "\n\n" +
                    S(R.string.studio_downgrade_versions)
                        .replace("%1\$s", downgrade.installed.toString())
                        .replace("%2\$s", downgrade.shipping.toString())
            )
            InfoCard(S(R.string.studio_update_app_first))
        }

        found != null -> {
            SettingsSectionHeader(S(R.string.studio_engine_address))
            // LTR-pinned by PayloadCard: this is the URL every subscription link is built on, and
            // an RTL container reorders it both on screen and in what gets copied.
            PayloadCard(found.workerUrl)
            Spacer(Modifier.height(14.dp))
            failure?.let { ProblemCard(it); Spacer(Modifier.height(10.dp)) }
            WizardPrimary(S(R.string.studio_connect_existing), onClick = onInstall)
        }

        else -> {
            InfoCard(S(R.string.studio_check_none))
            Spacer(Modifier.height(14.dp))
            failure?.let { ProblemCard(it); Spacer(Modifier.height(10.dp)) }
            WizardPrimary(S(R.string.studio_install_engine), onClick = onInstall)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DoneStep(
    account: CloudAccount?,
    adopted: Boolean,
    deviceLimits: Boolean?,
    onFinish: () -> Unit,
) {
    StepHeader(S(R.string.studio_step_done_title), S(R.string.studio_step_done_sub))

    if (adopted) {
        InfoCard(S(R.string.studio_done_adopted))
        Spacer(Modifier.height(12.dp))
    }

    account?.mlmWorkerUrl?.let {
        SettingsSectionHeader(S(R.string.studio_engine_address))
        // `PayloadCard`, like the engine address on the step before it and everywhere else this
        // app renders an ASCII payload. It had been a `SettingsTextRow` with an `onValueChange` that
        // threw the edit away — a field that looks typeable and is not — and it was the one URL on
        // the screen NOT pinned to LTR, so an RTL container reordered it both on screen and in
        // whatever the operator copied out of it (R9).
        PayloadCard(it)
        Spacer(Modifier.height(12.dp))
    }

    // Stated from what the deploy reported (build 18): the device counter is the one part
    // Cloudflare can refuse an account, and the operator should hear it here, not from a customer.
    SettingsSectionHeader(S(R.string.studio_caps_title))
    SettingsGroup {
        SettingsRow(
            title = S(R.string.studio_cap_quota),
            icon = StudioIcons.Volume,
            tint = Ios.Green,
            showChevron = false,
        )
        if (deviceLimits != null) {
            Separator()
            SettingsRow(
                title = if (deviceLimits) S(R.string.studio_account_devices_on) else S(R.string.studio_account_devices_off),
                icon = StudioIcons.Devices,
                tint = if (deviceLimits) Ios.Green else Ios.Orange,
                showChevron = false,
            )
        }
    }
    if (deviceLimits == false) {
        NoticeCard(
            icon = StudioIcons.Devices,
            tint = Ios.Orange,
            title = S(R.string.studio_account_devices_off),
            body = S(R.string.studio_account_devices_denied),
        )
    }
    Spacer(Modifier.height(18.dp))

    WizardPrimary(S(R.string.studio_go_to_dashboard), onClick = onFinish)
}
