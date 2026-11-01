package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.domain.ExpiryMode
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.ResetPolicy
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.configstudio.parts.VolumeField
import com.mlmvpn.scanner.ui.configstudio.parts.parseVolumeBytes
import com.mlmvpn.scanner.ui.configstudio.parts.volumeToField
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * Create or change one «بسته».
 *
 * The screen carries two warnings that are not decoration, because both describe behaviour the
 * operator cannot see and would otherwise assume wrongly:
 *
 *  * **Saving changes nothing for the people already on this plan.** Their terms were copied when
 *    they were created. This is the safe default and it is also the surprising one, so it is stated
 *    where the Save button is rather than in documentation nobody reads.
 *  * **«اعمال روی کاربران»** is the other half, and it is deliberately separate, destructive-styled
 *    and confirmed. It moves the caps; it leaves volume and expiry alone unless asked, because
 *    someone topped up by a renewal must not be pulled back down by an operator who only meant to
 *    change a device limit.
 *
 * A save writes to **every** installation in the fleet. A partial failure is reported by name and
 * is safe to retry — the write is a `PUT` on a fixed id, so running it again fixes the accounts
 * that missed it and does nothing to the ones that did not.
 */
@Composable
fun StudioPlanEditScreen(
    store: StudioStore,
    state: com.mlmvpn.scanner.data.studio.StudioState,
    existing: Plan?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var volumeGb by remember { mutableStateOf(volumeToField(existing?.quotaBytes).first) }
    var volumeUnit by remember { mutableStateOf(volumeToField(existing?.quotaBytes).second) }
    var days by remember { mutableStateOf(existing?.durationDays?.toString().orEmpty()) }
    var devices by remember { mutableStateOf(existing?.deviceLimit?.toString().orEmpty()) }
    var isDefault by remember { mutableStateOf(existing?.isDefault ?: false) }

    /**
     * The «قالب» this «بسته» hands out with its terms.
     *
     * `Plan.templateId` has existed in the schema, on the wire and in the domain model since the
     * first Config Studio migration, and **nothing had ever set it** — every plan ever made here
     * carried a null. This is the field that was missing: terms and shape are the two halves of
     * what somebody is given, and choosing them in two unconnected places is how a person ends up
     * with the right volume on the wrong kind of link.
     */
    var templateId by remember { mutableStateOf(existing?.templateId) }

    // The five terms a «بسته» has always carried in the schema, on the wire and in `:apply`, and
    // that this screen had no field for. They were therefore null on every plan ever made here —
    // and «اعمال روی کاربران» re-applies them by default, so pressing it wrote those nulls over
    // whatever the operator had set on individual users. A plan that cannot express a term must not
    // be the thing that erases it.
    var dailyGb by remember { mutableStateOf(volumeToField(existing?.dailyQuotaBytes).first) }
    var dailyUnit by remember { mutableStateOf(volumeToField(existing?.dailyQuotaBytes).second) }
    var ips by remember { mutableStateOf(existing?.ipLimit?.toString().orEmpty()) }
    var reset by remember { mutableStateOf(existing?.reset ?: ResetPolicy.NONE) }
    var expiryMode by remember { mutableStateOf(existing?.expiryMode ?: ExpiryMode.ABSOLUTE) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmApply by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    var onPlanUsers by remember { mutableStateOf<Int?>(null) }

    // How many people this plan already governs. Read once, and only when editing — it decides
    // whether the apply and archive actions are even worth offering, and what they will cost.
    LaunchedEffect(existing?.id) {
        val id = existing?.id ?: return@LaunchedEffect
        onPlanUsers = store.countPlanUsers(id)
    }

    fun save() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { error = context.getString(R.string.studio_field_required); return }

        val plan = Plan(
            // An existing plan keeps its id: it is the same «بسته» on every account in the fleet,
            // and minting a new one here would leave the old row behind on all of them.
            id = existing?.id ?: StudioStore.newPlanId(),
            name = trimmed,
            quotaBytes = parseVolumeBytes(volumeGb, volumeUnit),
            durationDays = days.trim().toIntOrNull()?.takeIf { it > 0 },
            deviceLimit = devices.trim().toIntOrNull()?.takeIf { it > 0 },
            expiryMode = expiryMode,
            dailyQuotaBytes = parseVolumeBytes(dailyGb, dailyUnit),
            reset = reset,
            // Folded into the device limit (build 18): one app opens dozens of connections, so a
            // count of them cut people off for browsing. Cleared, so applying the plan stops
            // carrying a number nothing reads.
            connLimit = null,
            ipLimit = ips.trim().toIntOrNull()?.takeIf { it > 0 },
            templateId = templateId,
            isDefault = isDefault,
        )

        busy = true; error = null; note = null
        scope.launch {
            val res = store.savePlan(plan)
            busy = false
            when {
                res.ok -> onSaved()
                // Some accounts took it and some did not. That is a different situation from a
                // total failure and it is named as such: the plan IS usable, on the accounts
                // listed, and pressing save again is the fix rather than starting over.
                res.partial -> error = context.getString(R.string.studio_plan_saved_partly)
                    .replace("%1\$s", res.failed.joinToString("، ") { it.first })
                else -> error = messageFor(context, res.failed.firstOrNull()?.second
                    ?: com.mlmvpn.scanner.data.studio.api.StudioError("unknown"))
            }
        }
    }

    IosScreen(
        title = if (existing == null) S(R.string.studio_plan_new) else S(R.string.studio_plan_edit),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_plans),
    ) {
        Spacer(Modifier.height(10.dp))
        PageIntro(S(R.string.studio_plan_sub))

        note?.let { InfoCard(it); Spacer(Modifier.height(12.dp)) }

        SettingsSectionHeader(S(R.string.studio_plan_name))
        WizardField(name, { name = it; error = null }, S(R.string.studio_plan_name_hint), ltr = false)

        SettingsSectionHeader(S(R.string.studio_field_volume))
        VolumeField(volumeGb, { volumeGb = it }, volumeUnit, { volumeUnit = it }, S(R.string.studio_field_volume_hint))

        SettingsSectionHeader(S(R.string.studio_field_days))
        WizardField(days, { days = it }, S(R.string.studio_field_days_hint))
        Spacer(Modifier.height(10.dp))

        // Which moment the days are counted from. Absolute is the ordinary case; on-first-connect
        // is what makes a link that can sit unused without burning its own time.
        SettingsGroup {
            ChoiceRow(
                title = S(R.string.studio_plan_expiry_absolute),
                subtitle = S(R.string.studio_plan_expiry_absolute_sub),
                selected = expiryMode == ExpiryMode.ABSOLUTE,
                onClick = { expiryMode = ExpiryMode.ABSOLUTE },
            )
            StudioSeparator()
            ChoiceRow(
                title = S(R.string.studio_plan_expiry_first_connect),
                subtitle = S(R.string.studio_plan_expiry_first_connect_sub),
                selected = expiryMode == ExpiryMode.ON_FIRST_CONNECT,
                onClick = { expiryMode = ExpiryMode.ON_FIRST_CONNECT },
            )
        }

        SettingsSectionHeader(S(R.string.studio_plan_daily_gb))
        VolumeField(dailyGb, { dailyGb = it }, dailyUnit, { dailyUnit = it }, S(R.string.studio_field_volume_hint))
        InfoCard(S(R.string.studio_plan_daily_note))

        // Only the three the engine accepts. `weekly` exists in the app's enum and the worker maps
        // anything it does not know to `none`, so offering it here would let the operator pick a
        // policy that silently became a different one on save.
        SettingsSectionHeader(S(R.string.studio_plan_reset))
        SettingsGroup {
            listOf(
                ResetPolicy.NONE to R.string.studio_plan_reset_none,
                ResetPolicy.DAILY to R.string.studio_plan_reset_daily,
                ResetPolicy.MONTHLY to R.string.studio_plan_reset_monthly,
            ).forEachIndexed { index, choice ->
                if (index > 0) StudioSeparator()
                val (policy, label) = choice
                ChoiceRow(
                    title = S(label),
                    selected = reset == policy,
                    onClick = { reset = policy },
                )
            }
        }

        SettingsSectionHeader(S(R.string.studio_new_devices_title))
        WizardField(devices, { devices = it }, S(R.string.studio_plan_device_hint))
        Spacer(Modifier.height(8.dp))
        WizardField(ips, { ips = it }, S(R.string.studio_plan_ip_hint))
        // A plan is fleet-wide and the installations under it need not agree: the Durable Object
        // that enforces these three is created where Cloudflare allows one and skipped where it
        // does not. So the flat "not enforced" this used to print was wrong on any fleet with one
        // capable account, and the flat opposite would be wrong on any fleet with one that is not.
        // Where they disagree the sentence says so, because that is the fact the operator needs.
        // A central Worker counts none of these, by design; it is said once below rather than
        // counted as an engine that is missing its counter.
        val engines = state.installations.filter { !it.isCentral }
        val enforcing = engines.count { it.can("enforcement.strict") }
        if (engines.isEmpty()) {
            if (state.installations.isNotEmpty()) SettingsFooter(tr(
                "روی ورکر مرکزی فقط مدت «بسته» به کار می‌رود؛ حجم، دستگاه و آی‌پی شمرده نمی‌شود.",
                "On the central Worker only the plan's term is used; volume, devices and IPs are not counted.",
            ))
        } else if (enforcing == engines.size) {
            SettingsFooter(S(R.string.studio_cap_plan_enforced))
        } else {
            NoticeCard(
                icon = StudioIcons.Devices,
                tint = Ios.Orange,
                title = S(R.string.studio_new_devices_off_title),
                body = if (enforcing == 0) S(R.string.studio_cap_plan_none)
                else S(R.string.studio_cap_plan_mixed)
                    .replace("%1\$s", faNum(enforcing))
                    .replace("%2\$s", faNum(engines.size)),
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---- the shape that goes with these terms ---------------------------------------
        //
        // Optional, and shown as optional. A plan with no template hands out the engine's default
        // shape, which is what every plan made before this field existed did — so leaving it unset
        // has to keep meaning exactly that.
        SettingsSectionHeader(S(R.string.studio_plan_template))
        if (state.templates.isEmpty()) {
            InfoCard(S(R.string.studio_plan_template_none), underHeader = true)
        } else {
            SettingsGroup {
                ChoiceRow(
                    title = S(R.string.studio_plan_template_default),
                    selected = templateId == null,
                    onClick = { templateId = null },
                )
                state.templates.forEach { template ->
                    StudioSeparator()
                    ChoiceRow(
                        title = template.name.ifBlank { template.id },
                        subtitle = shapeOf(template),
                        selected = templateId == template.id,
                        onClick = { templateId = template.id },
                    )
                }
            }
            InfoCard(S(R.string.studio_plan_template_note))
        }
        Spacer(Modifier.height(12.dp))

        SettingsGroup {
            SettingsToggle(
                title = S(R.string.studio_plan_default),
                checked = isDefault,
                onCheckedChange = { isDefault = it },
                subtitle = S(R.string.studio_plan_default_sub),
            )
        }

        Spacer(Modifier.height(16.dp))
        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }

        if (busy) {
            WizardBusy(S(R.string.studio_saving))
        } else {
            WizardPrimary(S(R.string.studio_save)) { save() }
        }

        // Said next to the button that does the saving, because it is the thing an operator is
        // most likely to assume the opposite of.
        InfoCard(S(R.string.studio_plan_edit_note))

        // ---- what to do about the people already on it ---------------------------------
        if (existing != null) {
            val n = onPlanUsers
            SettingsSectionHeader(S(R.string.studio_plan_existing_users))
            InfoCard(
                if (n == null) S(R.string.studio_loading)
                else S(R.string.studio_plan_user_count).replace("%1\$s", faNum(n)),
                underHeader = true,
            )
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_plan_apply),
                    icon = StudioIcons.ApplyToUsers,
                    tint = Ios.Orange,
                    enabled = !busy && (n ?: 0) > 0,
                ) { confirmApply = true }

                Separator()
                SettingsActionRow(
                    label = S(R.string.studio_plan_archive),
                    icon = StudioIcons.Archive,
                    tint = Ios.Red,
                    enabled = !busy,
                ) { confirmArchive = true }
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    if (confirmApply && existing != null) {
        IosAlert(
            title = S(R.string.studio_plan_apply_title),
            message = S(R.string.studio_plan_apply_body)
                .replace("%1\$s", faNum(onPlanUsers ?: 0)),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmApply = false }),
                IosAlertAction(S(R.string.studio_plan_apply), {
                    confirmApply = false
                    busy = true; error = null; note = null
                    scope.launch {
                        val res = store.applyPlan(existing.id)
                        busy = false
                        note = context.getString(R.string.studio_plan_applied)
                            .replace("%1\$s", faDigitsOf(res.users))
                        if (!res.ok) {
                            error = context.getString(R.string.studio_plan_saved_partly)
                                .replace("%1\$s", res.failed.joinToString("، ") { it.first })
                        }
                    }
                }),
            ),
            onDismiss = { confirmApply = false },
        )
    }

    if (confirmArchive && existing != null) {
        IosAlert(
            title = S(R.string.studio_plan_archive_title),
            message = S(R.string.studio_plan_archive_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmArchive = false }),
                IosAlertAction(S(R.string.studio_plan_archive), {
                    confirmArchive = false
                    busy = true; error = null
                    scope.launch {
                        val res = store.archivePlan(existing.id)
                        busy = false
                        if (res.ok) onSaved()
                        else error = context.getString(R.string.studio_plan_saved_partly)
                            .replace("%1\$s", res.failed.joinToString("، ") { it.first })
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmArchive = false },
        )
    }
}

/** Outside a composable, so the alert callback can build its message. */
private fun faDigitsOf(n: Int): String = com.mlmvpn.scanner.ui.emergency.faDigits(n.toString())
