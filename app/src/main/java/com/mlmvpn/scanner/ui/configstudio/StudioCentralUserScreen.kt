package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.RenewMode
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.central.CentralFleet
import com.mlmvpn.scanner.data.studio.domain.StoppedReason
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.parts.CentralKit
import com.mlmvpn.scanner.ui.configstudio.parts.FactsCard
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PayloadCard
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioChip
import com.mlmvpn.scanner.ui.configstudio.parts.StudioQrCard
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
 * One person on an account's central Worker: how long they have, their links, and what is done to
 * them — the same three things the engine page puts first, without what the central Worker cannot
 * know (volume, devices, sessions). Their links are built on this phone and handed over by copy,
 * share or QR, with a ready status message in place of the subscription page.
 *
 * Every change goes through the store, which writes the account's list and then the Worker, all or
 * nothing; the screen repaints from what came back.
 */
@Composable
fun StudioCentralUserScreen(
    store: StudioStore,
    installationId: String,
    userId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val account = remember { store.installedAccounts().firstOrNull { it.id == installationId } }

    var user by remember { mutableStateOf<StudioUser?>(store.cached(installationId, userId)) }
    var links by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    var renewing by remember { mutableStateOf(false) }
    var addDays by remember { mutableStateOf("") }
    var reducing by remember { mutableStateOf(false) }

    var noteDraft by remember { mutableStateOf(user?.note.orEmpty()) }
    var tagsDraft by remember { mutableStateOf(user?.tags?.joinToString("، ").orEmpty()) }
    var labelsTouched by remember { mutableStateOf(false) }

    /** Whether the Worker is in step with this person — from this phone's copy, no request. */
    fun pendingText(u: StudioUser): String? {
        val doc = CentralFleet.doc(context, installationId) ?: return null
        val cu = doc.user(u.id) ?: return null
        val now = System.currentTimeMillis()
        val onWorker = cu.gw in doc.appliedIds
        return when {
            cu.isLive(now) && !onWorker -> tr("این کاربر هنوز روی ورکر مرکزی اعمال نشده است.", "This person is not on the central Worker yet.")
            !cu.isLive(now) && onWorker -> tr("این کاربر باید از ورکر مرکزی برداشته شود؛ در بررسی بعدی برداشته می‌شود.", "This person is due to come off the central Worker; the next check takes them off.")
            else -> null
        }
    }

    suspend fun reload(refreshLinks: Boolean = false) {
        val acc = account ?: return
        when (val fresh = store.apiFor(acc).getUser(userId)) {
            is StudioResult.Ok -> {
                user = fresh.value
                store.remember(installationId, fresh.value)
                if (!labelsTouched) {
                    noteDraft = fresh.value.note.orEmpty()
                    tagsDraft = fresh.value.tags.joinToString("، ")
                }
            }
            is StudioResult.Err -> error = CentralKit.errorText(context, fresh.error)
        }
        when (val l = store.centralLinks(acc, userId, refreshLinks)) {
            is StudioResult.Ok -> links = l.value
            is StudioResult.Err -> { links = emptyList(); error = CentralKit.errorText(context, l.error) }
        }
    }

    LaunchedEffect(installationId, userId) { reload() }

    fun act(block: suspend () -> StudioResult<StudioUser>, done: String? = null) {
        if (busy) return
        busy = true; error = null; note = null
        scope.launch {
            when (val r = block()) {
                is StudioResult.Ok -> { user = r.value; done?.let { note = it } }
                is StudioResult.Err -> error = CentralKit.errorText(context, r.error)
            }
            busy = false
        }
    }

    val u = user
    IosScreen(
        title = u?.username ?: S(R.string.studio_users),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_users),
        onRefresh = { reload(refreshLinks = true) },
    ) {
        if (u == null || account == null) {
            InfoCard(S(R.string.studio_err_generic))
            return@IosScreen
        }

        Spacer(Modifier.height(10.dp))
        note?.let { InfoCard(it); Spacer(Modifier.height(12.dp)) }
        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }

        u.stoppedReason()?.let { reason ->
            ProblemCard(
                when (reason) {
                    StoppedReason.DISABLED -> S(R.string.studio_row_disabled)
                    else -> S(R.string.studio_row_expired)
                }
            )
            Spacer(Modifier.height(14.dp))
        }
        pendingText(u)?.let { text ->
            NoticeCard(icon = StudioIcons.Refresh, tint = Ios.Orange, title = tr("در انتظار ورکر", "Waiting for the Worker"), body = text)
            SettingsGroup {
                SettingsActionRow(label = tr("همین حالا اعمال کن", "Apply now"), icon = StudioIcons.Refresh, tint = Ios.Blue, enabled = !busy) {
                    busy = true; error = null
                    scope.launch {
                        when (val r = store.centralApplyNow(account)) {
                            is StudioResult.Ok -> note = tr("ورکر مرکزی به‌روز شد.", "The central Worker is up to date.")
                            is StudioResult.Err -> error = CentralKit.errorText(context, r.error)
                        }
                        reload()
                        busy = false
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // ---- time --------------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_section_time))
        val expiresAt = u.policy.expiresAt
        if (expiresAt == null) {
            FactsCard(S(R.string.studio_no_end_date))
        } else {
            SettingsGroup {
                SettingsRow(
                    title = S(R.string.studio_time_left),
                    value = timeLeftText(expiresAt) ?: S(R.string.studio_row_expired),
                    subtitle = CentralKit.dateText(expiresAt),
                    showChevron = false,
                )
            }
        }

        // ---- the links ---------------------------------------------------------------
        SettingsSectionHeader(tr("کانفیگ‌های این کاربر", "This person's configs"))
        val list = links
        when {
            list == null -> WizardBusy(S(R.string.studio_loading))
            list.isEmpty() -> InfoCard(tr("کانفیگی ساخته نشد؛ صفحه را پایین بکشید تا دوباره امتحان شود.", "No configs were built; pull down to try again."))
            else -> {
                // The first is the Worker's own name on 443 — the one most clients take as it is.
                PayloadCard(list.first())
                if (showAll) {
                    for (link in list.drop(1)) { Spacer(Modifier.height(6.dp)); PayloadCard(link) }
                }
                Spacer(Modifier.height(10.dp))
                SettingsGroup {
                    SettingsActionRow(label = tr("کپی همهٔ کانفیگ‌ها (${faNum(list.size)})", "Copy all configs (${list.size})"), icon = StudioIcons.Copy, tint = Ios.Blue) {
                        clipboard.setText(AnnotatedString(list.joinToString("\n")))
                        note = S(R.string.studio_copied)
                    }
                    Separator()
                    SettingsActionRow(label = S(R.string.studio_share), icon = StudioIcons.Share, tint = Ios.Blue) {
                        StudioShare.shareText(context, list.joinToString("\n"), u.username)
                    }
                    Separator()
                    SettingsActionRow(label = if (showQr) S(R.string.studio_qr_hide) else S(R.string.studio_qr), icon = StudioIcons.Qr, tint = Ios.Indigo) { showQr = !showQr }
                    Separator()
                    SettingsActionRow(
                        label = if (showAll) tr("فقط کانفیگ اول", "Only the first config") else tr("نمایش همهٔ کانفیگ‌ها", "Show every config"),
                        icon = StudioIcons.Config, tint = Ios.Gray,
                    ) { showAll = !showAll }
                }
                if (showQr) {
                    Spacer(Modifier.height(10.dp))
                    StudioQrCard(list.first(), S(R.string.studio_qr_too_long))
                }
                InfoCard(tr("همهٔ این کانفیگ‌ها یکی‌اند و فقط نشانی‌شان فرق دارد؛ اگر یکی روی اینترنت کاربر وصل نشد، بعدی را امتحان کند.",
                    "These configs are one and the same, only their address differs; if one does not connect on the person's internet, they try the next."))
            }
        }

        // ---- the status message --------------------------------------------------------
        SettingsSectionHeader(tr("پیام وضعیت", "Status message"))
        val doc = remember(u) { CentralFleet.doc(context, installationId) }
        val message = CentralKit.statusMessage(u, doc?.contact)
        FactsCard(message)
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            SettingsActionRow(label = tr("فرستادن پیام وضعیت", "Send the status message"), icon = StudioIcons.Share, tint = Ios.Green) {
                StudioShare.shareText(context, message, u.username)
            }
        }

        // ---- note and tags ---------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_section_labels))
        WizardField(value = tagsDraft, onValueChange = { tagsDraft = it; labelsTouched = true }, placeholder = S(R.string.studio_tags_hint), ltr = false)
        Spacer(Modifier.height(8.dp))
        WizardField(value = noteDraft, onValueChange = { noteDraft = it; labelsTouched = true }, placeholder = S(R.string.studio_note_hint), ltr = false, lines = 3)
        if (noteDraft.trim() != u.note.orEmpty() || centralSplitTags(tagsDraft) != u.tags) {
            Spacer(Modifier.height(10.dp))
            WizardPrimary(S(R.string.studio_save), busy = busy) {
                act({ store.setLabels(account, userId, noteDraft.trim(), centralSplitTags(tagsDraft)).also { if (it is StudioResult.Ok) labelsTouched = false } },
                    context.getString(R.string.studio_saved))
            }
        }

        // ---- renew -------------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_renew_title))
        if (!renewing) {
            SettingsGroup {
                SettingsActionRow(label = S(R.string.studio_renew), icon = StudioIcons.Renew, tint = Ios.Green, enabled = !busy) { renewing = true }
            }
        } else {
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioChip(S(R.string.studio_renew_dir_add), !reducing, Modifier.weight(1f)) { reducing = false }
                StudioChip(S(R.string.studio_renew_dir_sub), reducing, Modifier.weight(1f)) { reducing = true }
            }
            SettingsSectionHeader(if (reducing) S(R.string.studio_renew_sub_days) else S(R.string.studio_renew_add_days))
            WizardField(addDays, { addDays = it }, "30")
            Spacer(Modifier.height(12.dp))
            WizardPrimary(if (reducing) S(R.string.studio_renew_reduce) else S(R.string.studio_renew_extend), busy = busy) {
                val n = addDays.trim().toIntOrNull()?.let { kotlin.math.abs(it) * if (reducing) -1 else 1 }
                if (n == null || n == 0) { error = context.getString(R.string.studio_renew_needs_input); return@WizardPrimary }
                act({
                    store.renew(account, userId, addDays = n, mode = RenewMode.EXTEND).also {
                        if (it is StudioResult.Ok) { renewing = false; reducing = false; addDays = "" }
                    }
                }, context.getString(R.string.studio_renewed))
            }
            InfoCard(tr("تمدید لینک را عوض نمی‌کند؛ روزها به پایان فعلی اضافه می‌شوند (یا از امروز، اگر تمام شده باشد).",
                "Renewing does not change the link; the days are added to the current end (or to today, when it has passed)."))
        }

        // ---- on / off, a new link -------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_section_actions))
        SettingsGroup {
            val on = u.stoppedReason() != StoppedReason.DISABLED
            SettingsActionRow(
                label = if (on) S(R.string.studio_disable) else S(R.string.studio_enable),
                icon = StudioIcons.Power,
                tint = if (on) Ios.Orange else Ios.Green,
                enabled = !busy,
            ) { act({ store.setEnabled(account, userId, !on) }) }
            Separator()
            SettingsActionRow(label = tr("لینک تازه", "New link"), icon = StudioIcons.Credential, tint = Ios.Orange, enabled = !busy) { confirmRotate = true }
        }

        SettingsSectionHeader(S(R.string.studio_section_danger))
        SettingsGroup {
            SettingsActionRow(label = S(R.string.studio_delete), icon = StudioIcons.Delete, tint = Ios.Red, enabled = !busy) { confirmDelete = true }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (confirmRotate) {
        IosAlert(
            title = tr("لینک تازه؟", "A new link?"),
            message = tr("همهٔ کانفیگ‌های قبلی این کاربر روی همهٔ دستگاه‌هایش از کار می‌افتند و باید کانفیگ‌های تازه را برایش بفرستید.",
                "Every earlier config of this person stops working on all of their devices, and you send them the new ones."),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmRotate = false }),
                IosAlertAction(tr("لینک تازه بساز", "Make a new link"), {
                    confirmRotate = false
                    val acc = account ?: return@IosAlertAction
                    busy = true; error = null
                    scope.launch {
                        when (val r = store.centralRotate(acc, userId)) {
                            is StudioResult.Ok -> { user = r.value; note = S_rotatedText(context); links = null; reload() }
                            is StudioResult.Err -> error = CentralKit.errorText(context, r.error)
                        }
                        busy = false
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmRotate = false },
        )
    }

    if (confirmDelete) {
        IosAlert(
            title = S(R.string.studio_delete_title),
            message = S(R.string.studio_delete_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmDelete = false }),
                IosAlertAction(S(R.string.studio_delete), {
                    confirmDelete = false
                    val acc = account ?: return@IosAlertAction
                    busy = true; error = null
                    scope.launch {
                        when (val r = store.deleteUser(acc, userId)) {
                            is StudioResult.Ok -> onBack()
                            is StudioResult.Err -> { error = CentralKit.errorText(context, r.error); busy = false }
                        }
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Tags as typed: either comma, Persian or Latin, trimmed, de-duplicated, in the operator's order. */
internal fun centralSplitTags(raw: String): List<String> =
    raw.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

private fun S_rotatedText(c: android.content.Context) = c.getString(R.string.studio_rotated)
