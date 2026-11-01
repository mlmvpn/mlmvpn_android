package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.BulkLink
import com.mlmvpn.scanner.data.studio.BulkResult
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.UserRef
import com.mlmvpn.scanner.data.studio.api.RenewMode
import com.mlmvpn.scanner.data.studio.config.payloadFor
import com.mlmvpn.scanner.data.studio.config.toDraft
import com.mlmvpn.scanner.data.studio.domain.ConfigTemplate
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.parts.FactsCard
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioChip
import com.mlmvpn.scanner.ui.configstudio.parts.StudioShare
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * One thing, done to everyone who was selected.
 *
 * A pushed page rather than a bar of icons under the list, and that is the same call this app makes
 * everywhere else: a bulk action needs a figure typed, a confirmation read, and a per-person result
 * shown afterwards, none of which fits in a toolbar. It also means the selection is still there,
 * unchanged, if the operator backs out — a sheet that dismissed on a mis-tap would lose it.
 *
 * **Every action here reports per person.** Nineteen of twenty succeeding is the ordinary outcome
 * across a fleet — one account unreachable, one name deleted from another device — and a single
 * "failed" would hide which nineteen worked. See `data/studio/StudioBulk.kt` for why the loop lives
 * on the client at all.
 */
@Composable
fun StudioBulkActionScreen(
    store: StudioStore,
    state: StudioState,
    targets: List<UserRef>,
    onBack: () -> Unit,
    /** Called after a delete, because the list behind this page no longer describes anything. */
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var result by remember { mutableStateOf<BulkResult?>(null) }
    var links by remember { mutableStateOf<List<BulkLink>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    var addDays by remember { mutableStateOf("") }
    var addGb by remember { mutableStateOf("") }
    var reducing by remember { mutableStateOf(false) }

    var deviceLimit by remember { mutableStateOf("") }
    var ipLimit by remember { mutableStateOf("") }

    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    // Adding a config to everyone selected is asked about too: it is one tap on a row that looks
    // like any other, and it writes to every one of them.
    var confirmTemplate by remember { mutableStateOf<ConfigTemplate?>(null) }

    /**
     * Whether EVERY account this selection touches understands a reduction.
     *
     * All, not any. A selection spanning two accounts where one is on build 7 would apply the
     * reduction on one and extend the expiry into the past on the other — the exact silent failure
     * `renew.adjust` exists to name. With one control for the whole selection, the honest answer is
     * to withhold it until the whole selection can take it.
     */
    val canAdjust = remember(targets, state.installations) {
        val shards = targets.map { it.installationId }.toSet()
        shards.isNotEmpty() && shards.all { id ->
            state.installations.firstOrNull { it.installationId == id }?.can("renew.adjust") == true
        }
    }

    /** Run one bulk operation, with the progress and result plumbing every one of them needs. */
    fun runBulk(block: suspend ((Int, Int) -> Unit) -> BulkResult, after: (BulkResult) -> Unit = {}) {
        if (busy) return
        busy = true; result = null; note = null; progress = 0 to targets.size
        scope.launch {
            val r = block { done, total -> progress = done to total }
            result = r
            busy = false
            after(r)
        }
    }

    IosScreen(
        title = S(R.string.studio_bulk_title),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_users),
    ) {
        Spacer(Modifier.height(10.dp))
        PageIntro(S(R.string.studio_bulk_intro).replace("%1\$s", faNum(targets.size)))

        // Who, by name, before anything is done to them. A count alone is a number the operator has
        // to trust; the names are what let them notice the one that should not be in the list.
        FactsCard(
            targets.take(12).joinToString("، ") { it.username } +
                if (targets.size > 12) "، " + S(R.string.studio_bulk_and_more)
                    .replace("%1\$s", faNum(targets.size - 12))
                else ""
        )

        note?.let { Spacer(Modifier.height(10.dp)); InfoCard(it) }

        if (busy) {
            WizardBusy(
                S(R.string.studio_bulk_run_progress)
                    .replace("%1\$s", faNum(progress.first))
                    .replace("%2\$s", faNum(progress.second))
            )
        }

        // ---- what happened, per person -------------------------------------------------
        result?.let { r ->
            Spacer(Modifier.height(10.dp))
            if (r.allSucceeded) {
                FactsCard(S(R.string.studio_bulk_all_ok).replace("%1\$s", faNum(r.ok)))
            } else {
                ProblemCard(
                    S(R.string.studio_bulk_partial)
                        .replace("%1\$s", faNum(r.ok))
                        .replace("%2\$s", faNum(r.total))
                )
                Spacer(Modifier.height(8.dp))
                // Named, not counted. "One failed" sends the operator back through twenty people to
                // find which; the name sends them to that person.
                FactsCard(
                    r.failures.joinToString("\n") {
                        it.username + " — " + bulkFailureText(context, it.code)
                    }
                )
            }
        }

        // ---- switching on and off --------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_bulk_status))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.studio_bulk_enable),
                icon = StudioIcons.Power,
                tint = Ios.Green,
                enabled = !busy,
            ) { runBulk({ p -> store.bulkSetEnabled(targets, true, p) }) }
            Separator()
            SettingsActionRow(
                label = S(R.string.studio_bulk_disable),
                icon = StudioIcons.Power,
                tint = Ios.Orange,
                enabled = !busy,
            ) { confirmDisable = true }
        }

        // ---- time and volume -------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_renew_title))
        if (canAdjust) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StudioChip(S(R.string.studio_renew_dir_add), !reducing, Modifier.weight(1f)) { reducing = false }
                StudioChip(S(R.string.studio_renew_dir_sub), reducing, Modifier.weight(1f)) { reducing = true }
            }
            Spacer(Modifier.height(8.dp))
        }
        SettingsSectionHeader(
            if (reducing) S(R.string.studio_renew_sub_days) else S(R.string.studio_renew_add_days)
        )
        WizardField(addDays, { addDays = it }, "30")
        SettingsSectionHeader(
            if (reducing) S(R.string.studio_renew_sub_gb) else S(R.string.studio_renew_add_gb)
        )
        WizardField(addGb, { addGb = it }, "30")
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            SettingsActionRow(
                label = if (reducing) S(R.string.studio_renew_reduce) else S(R.string.studio_renew_extend),
                icon = StudioIcons.Renew,
                tint = if (reducing) Ios.Orange else Ios.Green,
                enabled = !busy,
            ) {
                val sign = if (reducing) -1 else 1
                val days = addDays.trim().toIntOrNull()?.let { kotlin.math.abs(it) * sign }
                val bytes = addGb.trim().toDoubleOrNull()
                    ?.let { (kotlin.math.abs(it) * 1073741824).toLong() * sign }
                if (days == null && bytes == null) {
                    note = context.getString(R.string.studio_renew_needs_input)
                } else {
                    runBulk({ p -> store.bulkRenew(targets, days, bytes, RenewMode.EXTEND, p) }) {
                        addDays = ""; addGb = ""
                    }
                }
            }
        }
        InfoCard(
            if (reducing) S(R.string.studio_renew_reduce_note) else S(R.string.studio_bulk_renew_note)
        )

        // ---- the three caps ---------------------------------------------------------------
        //
        // Blank means "leave it alone" and zero means "no limit", which are different intentions
        // that a single Int cannot carry. Written on the card rather than assumed, because the
        // alternative — three boxes that all get written — clears two caps every time the operator
        // means to change one.
        // The connection count is folded into the device limit (build 18): one app opens dozens of
        // connections, so counting them cut people off for browsing.
        SettingsSectionHeader(S(R.string.studio_new_devices_title))
        WizardField(deviceLimit, { deviceLimit = it }, S(R.string.studio_plan_device_hint))
        Spacer(Modifier.height(8.dp))
        WizardField(ipLimit, { ipLimit = it }, S(R.string.studio_plan_ip_hint))
        InfoCard(S(R.string.studio_bulk_limits_note))
        Spacer(Modifier.height(10.dp))
        val anyLimit = listOf(deviceLimit, ipLimit).any { it.isNotBlank() }
        WizardPrimary(S(R.string.studio_save), enabled = anyLimit && !busy) {
            runBulk({ p ->
                store.bulkSetLimits(
                    targets,
                    deviceLimit = capValue(deviceLimit),
                    ipLimit = capValue(ipLimit),
                    onProgress = p,
                )
            }) { deviceLimit = ""; ipLimit = "" }
        }

        // ---- give them all a config from a stored shape ------------------------------------
        //
        // **Additive, never a replacement.** Re-shaping somebody's existing config means re-issuing
        // its credential, which cuts off the link they are using right now — that is `rotate`, it
        // is per person on purpose, and doing it to two hundred people from one button is not a
        // thing this screen should be able to do by accident. This leaves every existing link
        // working and puts another server in their subscription.
        val offeredTemplates = state.templates.filter {
            com.mlmvpn.scanner.data.studio.config.LocationConfigs.isOffered(it.toDraft().shape)
        }
        if (offeredTemplates.isNotEmpty()) {
            SettingsSectionHeader(S(R.string.studio_bulk_add_config))
            SettingsGroup {
                offeredTemplates.forEachIndexed { index, template ->
                    if (index > 0) Separator()
                    SettingsRow(
                        title = template.name.ifBlank { template.id },
                        value = shapeOf(template),
                        icon = StudioIcons.Template,
                        tint = Ios.Indigo,
                        onClick = { if (!busy) confirmTemplate = template },
                    )
                }
            }
            InfoCard(S(R.string.studio_bulk_add_config_note))
        }

        // ---- their links, collected --------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_bulk_links))
        links?.let { collected ->
            val missing = collected.count { it.url == null }
            FactsCard(
                S(R.string.studio_bulk_links_ready).replace("%1\$s", faNum(collected.size - missing)) +
                    if (missing > 0) "\n" + S(R.string.studio_bulk_links_missing).replace("%1\$s", faNum(missing))
                    else ""
            )
            Spacer(Modifier.height(10.dp))
        }
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.studio_bulk_collect),
                icon = StudioIcons.Config,
                tint = Ios.Blue,
                enabled = !busy,
            ) {
                if (busy) return@SettingsActionRow
                busy = true; note = null; result = null; progress = 0 to targets.size
                scope.launch {
                    links = store.bulkLinks(targets) { done, total -> progress = done to total }
                    busy = false
                }
            }
            if (links != null) {
                Separator()
                SettingsActionRow(
                    label = S(R.string.studio_bulk_copy_all),
                    icon = StudioIcons.Copy,
                    tint = Ios.Blue,
                ) {
                    clipboard.setText(AnnotatedString(linksAsText(links.orEmpty(), context)))
                    note = context.getString(R.string.studio_copied)
                }
                Separator()
                SettingsActionRow(
                    label = S(R.string.studio_share),
                    icon = StudioIcons.Share,
                    tint = Ios.Blue,
                ) {
                    StudioShare.shareText(
                        context,
                        linksAsText(links.orEmpty(), context),
                        context.getString(R.string.studio_bulk_links),
                    )
                }
                Separator()
                SettingsActionRow(
                    label = S(R.string.studio_export_file),
                    icon = StudioIcons.Export,
                    tint = Ios.Green,
                ) {
                    val ok = StudioShare.shareFile(
                        context,
                        fileName = "links.txt",
                        content = linksAsText(links.orEmpty(), context),
                        subject = context.getString(R.string.studio_bulk_links),
                    )
                    note = context.getString(
                        if (ok) R.string.studio_exported else R.string.studio_export_failed
                    )
                }
            }
        }
        InfoCard(S(R.string.studio_bulk_links_note))

        // ---- the one that cannot be undone --------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_section_danger))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.studio_bulk_delete),
                icon = StudioIcons.Delete,
                tint = Ios.Red,
                enabled = !busy,
            ) { confirmDelete = true }
        }

        Spacer(Modifier.height(28.dp))
    }

    // Disabling is asked about because it stops working links for people who are using them right
    // now, and a mis-tap on a list of two hundred is not a thing to discover from support messages.
    if (confirmDisable) {
        IosAlert(
            // The count is in the title; the body has none to fill.
            title = S(R.string.studio_bulk_disable_title).replace("%1\$s", faNum(targets.size)),
            message = S(R.string.studio_bulk_disable_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmDisable = false }),
                IosAlertAction(S(R.string.studio_bulk_disable), {
                    confirmDisable = false
                    runBulk({ p -> store.bulkSetEnabled(targets, false, p) })
                }, destructive = true),
            ),
            onDismiss = { confirmDisable = false },
        )
    }

    confirmTemplate?.let { template ->
        IosAlert(
            title = S(R.string.studio_bulk_add_config_title)
                .replace("%1\$s", template.name.ifBlank { template.id }),
            message = S(R.string.studio_bulk_add_config_body).replace("%1\$s", faNum(targets.size)),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmTemplate = null }),
                IosAlertAction(S(R.string.studio_bulk_add_config_confirm), {
                    confirmTemplate = null
                    runBulk({ p ->
                        store.bulkAddConfig(targets, template, { u -> template.payloadFor(u) }, p)
                    })
                }),
            ),
            onDismiss = { confirmTemplate = null },
        )
    }

    if (confirmDelete) {
        IosAlert(
            // The count is in the title; the body has none to fill.
            title = S(R.string.studio_bulk_delete_title).replace("%1\$s", faNum(targets.size)),
            message = S(R.string.studio_bulk_delete_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmDelete = false }),
                IosAlertAction(S(R.string.studio_bulk_delete), {
                    confirmDelete = false
                    runBulk({ p -> store.bulkDelete(targets, p) }) { r ->
                        // Only when it actually worked for everyone. A partial delete has a failure
                        // list on this screen that leaving would throw away unread.
                        if (r.allSucceeded) onDeleted()
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * A typed cap field, as the three-way value `bulkSetLimits` takes.
 *
 * Blank is null — leave this cap where it is. Zero, or anything not a positive number, is
 * [StudioStore.CLEAR_CAP] — the operator asked for no limit. The two are different intentions that
 * both end as `null` on the wire, which is why the sentinel exists at all.
 */
private fun capValue(raw: String): Int? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    val n = t.toIntOrNull() ?: return StudioStore.CLEAR_CAP
    return if (n > 0) n else StudioStore.CLEAR_CAP
}

/**
 * The collected links as one block of text.
 *
 * Name and link on one line, tab-separated, because that is what pastes into a spreadsheet as two
 * columns — and a list of two hundred links is going into a spreadsheet. A person with no link gets
 * a line saying so rather than being dropped: a file of a hundred and ninety-nine lines under a
 * heading of two hundred is one nobody notices is short.
 */
private fun linksAsText(links: List<BulkLink>, context: android.content.Context): String =
    links.joinToString("\n") {
        it.username + "\t" + (it.url ?: context.getString(R.string.studio_bulk_no_link))
    }

/** Mapped from the engine's stable code, never its message — the same rule as everywhere else. */
private fun bulkFailureText(context: android.content.Context, code: String): String =
    context.getString(
        when (code) {
            "network" -> R.string.studio_err_network
            "unauthorized" -> R.string.studio_err_unauthorized
            "username_taken" -> R.string.studio_err_username_taken
            "schema_stale" -> R.string.studio_err_schema_stale
            "not_found" -> R.string.studio_bulk_gone
            else -> R.string.studio_err_generic
        }
    )
