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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioError
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.parts.CentralKit
import com.mlmvpn.scanner.ui.configstudio.parts.FactsCard
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioQrCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioShare
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
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
 * «ساخت انبوه» on an account's central Worker: a prefix, a count and a term, and every one of them
 * made in ONE upload. The list that comes back is the product — each person's configs, by name,
 * to copy, share or save — and a name that could not be made is named, never dropped.
 */
@Composable
fun StudioCentralBulkUsersScreen(
    store: StudioStore,
    state: StudioState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onAddAccount: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var prefix by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("10") }
    var startAt by remember { mutableStateOf("1") }
    var days by remember { mutableStateOf("") }
    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var picked by remember { mutableStateOf<Plan?>(null) }
    var placement by remember { mutableStateOf(store.placementFor()) }
    var pickingShard by remember { mutableStateOf(false) }
    val fleet = remember(state.installations) { store.installedAccounts().filter { store.isCentral(it.id) } }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    /** name → their configs, in the order made; and the names that were not made, with why. */
    var made by remember { mutableStateOf<List<Pair<String, List<String>>>?>(null) }
    var failed by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var qrOf by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        plans = store.refreshPlans().filter { !it.archived }
        picked = plans.firstOrNull { it.isDefault }
        picked?.durationDays?.let { days = it.toString() }
    }

    val names = remember(prefix, count, startAt) {
        val n = count.trim().toIntOrNull()?.coerceIn(1, MAX_CENTRAL_BATCH) ?: 0
        val from = startAt.trim().toIntOrNull()?.coerceAtLeast(0) ?: 1
        val width = (from + n - 1).toString().length
        val p = prefix.trim()
        if (p.isEmpty() || n == 0) emptyList() else (from until from + n).map { p + it.toString().padStart(width, '0') }
    }
    val clashes = remember(names) {
        val taken = store.usernamesTaken(names)
        names.filter { it.lowercase() in taken }
    }
    val roomLeft = placement?.let { store.centralRoomLeft(it) } ?: 0

    fun asText(list: List<Pair<String, List<String>>>): String =
        list.joinToString("\n\n") { (name, links) -> name + "\n" + links.joinToString("\n") }

    fun create() {
        val account = placement ?: return
        if (names.isEmpty()) { error = context.getString(R.string.studio_bulk_new_needs_prefix); return }
        if (clashes.isNotEmpty()) { error = context.getString(R.string.studio_bulk_new_clash); return }
        busy = true; error = null
        scope.launch {
            val outcome = store.centralCreateMany(account, names, days.trim().toIntOrNull(), picked?.id)
            if (outcome.failedAll != null) {
                error = CentralKit.errorText(context, outcome.failedAll)
            } else {
                val out = mutableListOf<Pair<String, List<String>>>()
                for (u in outcome.created) {
                    val links = (store.centralLinks(account, u.id) as? com.mlmvpn.scanner.data.studio.api.StudioResult.Ok)?.value.orEmpty()
                    out += u.name to links
                }
                made = out
                failed = outcome.failures
                store.refreshInstallations()
            }
            busy = false
        }
    }

    IosScreen(
        title = S(R.string.studio_bulk_new_title),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_users),
    ) {
        val done = made
        if (done != null) {
            Spacer(Modifier.height(10.dp))
            FactsCard(S(R.string.studio_bulk_new_done).replace("%1\$s", faNum(done.size)))
            if (failed.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ProblemCard(S(R.string.studio_bulk_new_failed).replace("%1\$s", faNum(failed.size)))
                Spacer(Modifier.height(8.dp))
                FactsCard(failed.joinToString("\n") { (name, code) -> name + " — " + CentralKit.errorText(context, StudioError(code)) })
            }
            note?.let { Spacer(Modifier.height(10.dp)); InfoCard(it) }

            SettingsSectionHeader(S(R.string.studio_bulk_links))
            SettingsGroup {
                SettingsActionRow(label = S(R.string.studio_bulk_copy_all), icon = StudioIcons.Copy, tint = Ios.Blue) {
                    clipboard.setText(AnnotatedString(asText(done)))
                    note = context.getString(R.string.studio_copied)
                }
                Separator()
                SettingsActionRow(label = S(R.string.studio_share), icon = StudioIcons.Share, tint = Ios.Blue) {
                    StudioShare.shareText(context, asText(done), context.getString(R.string.studio_bulk_links))
                }
                Separator()
                SettingsActionRow(label = S(R.string.studio_export_file), icon = StudioIcons.Export, tint = Ios.Green) {
                    val saved = StudioShare.shareFile(
                        context,
                        fileName = (prefix.trim().ifBlank { "configs" }) + ".txt",
                        content = asText(done),
                        subject = context.getString(R.string.studio_bulk_links),
                    )
                    note = context.getString(if (saved) R.string.studio_exported else R.string.studio_export_failed)
                }
            }

            SettingsSectionHeader(S(R.string.studio_bulk_new_list))
            SettingsGroup {
                done.forEachIndexed { index, (name, _) ->
                    if (index > 0) Separator()
                    SettingsRow(
                        title = name,
                        value = if (qrOf == name) S(R.string.studio_qr_hide) else S(R.string.studio_qr),
                        icon = StudioIcons.Qr,
                        tint = Ios.Blue,
                        showChevron = false,
                        onClick = { qrOf = if (qrOf == name) null else name },
                    )
                }
            }
            qrOf?.let { name ->
                done.firstOrNull { it.first == name }?.second?.firstOrNull()?.let { link ->
                    Spacer(Modifier.height(10.dp))
                    StudioQrCard(link, S(R.string.studio_qr_too_long))
                }
            }
            Spacer(Modifier.height(18.dp))
            WizardPrimary(S(R.string.studio_done), onClick = onDone)
            Spacer(Modifier.height(28.dp))
            return@IosScreen
        }

        if (fleet.isEmpty()) {
            PageIntro(tr("کاربرها روی ورکر مرکزی یک حساب کلادفلر ساخته می‌شوند. اول یک حساب اضافه کنید.",
                "People are made on a Cloudflare account's central Worker. Add an account first."))
            Spacer(Modifier.height(10.dp))
            WizardPrimary(S(R.string.studio_add_account), onClick = onAddAccount)
            return@IosScreen
        }

        PageIntro(tr("همه با یک بارگذاری روی ورکر مرکزی ساخته می‌شوند.", "All of them are made on the central Worker in one upload."))

        SettingsSectionHeader(S(R.string.studio_bulk_new_prefix))
        WizardField(prefix, { prefix = it; error = null }, "user-")
        SettingsSectionHeader(S(R.string.studio_bulk_new_count))
        WizardField(count, { count = it; error = null }, "10")
        SettingsSectionHeader(S(R.string.studio_bulk_new_start))
        WizardField(startAt, { startAt = it; error = null }, "1")

        if (fleet.size > 1) {
            SettingsSectionHeader(S(R.string.studio_placement))
            SettingsGroup {
                if (!pickingShard) {
                    SettingsRow(
                        title = placement?.let { it.name.ifEmpty { it.email } } ?: S(R.string.studio_placement_none),
                        subtitle = tr("${faNum(roomLeft)} جای خالی", "$roomLeft places free"),
                        icon = StudioIcons.Accounts,
                        tint = Ios.Gray,
                        onClick = { pickingShard = true },
                    )
                } else {
                    fleet.forEachIndexed { index, acc ->
                        if (index > 0) StudioSeparator()
                        val left = store.centralRoomLeft(acc)
                        ChoiceRow(
                            title = acc.name.ifEmpty { acc.email },
                            subtitle = tr("${faNum(left)} جای خالی", "$left places free"),
                            selected = placement?.id == acc.id,
                            onClick = { placement = acc; pickingShard = false },
                        )
                    }
                }
            }
        }

        if (plans.isNotEmpty()) {
            SettingsSectionHeader(S(R.string.studio_plan_pick))
            SettingsGroup {
                plans.forEachIndexed { index, plan ->
                    if (index > 0) StudioSeparator()
                    ChoiceRow(
                        title = plan.name.ifBlank { plan.id },
                        subtitle = plan.durationDays?.let { faNum(it) + " " + S(R.string.studio_unit_days) } ?: S(R.string.studio_no_expiry),
                        selected = picked?.id == plan.id,
                        onClick = { picked = plan; days = plan.durationDays?.toString().orEmpty() },
                    )
                }
                StudioSeparator()
                ChoiceRow(title = S(R.string.studio_plan_none), selected = picked == null, onClick = { picked = null })
            }
        }

        SettingsSectionHeader(S(R.string.studio_field_days))
        WizardField(days, { days = it }, S(R.string.studio_field_days_hint))

        if (names.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FactsCard(names.first() + (if (names.size > 1) " … " + names.last() else "") + "  (" + faNum(names.size) + ")")
            if (names.size > roomLeft) {
                Spacer(Modifier.height(8.dp))
                ProblemCard(tr("روی این حساب فقط ${faNum(roomLeft)} جای خالی هست؛ بقیه ساخته نمی‌شوند.",
                    "This account has only $roomLeft places free; the rest are not made."))
            }
            if (clashes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ProblemCard(S(R.string.studio_bulk_new_clash) + "\n" + clashes.take(10).joinToString("، "))
            }
        }

        Spacer(Modifier.height(14.dp))
        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }
        if (busy) WizardBusy(S(R.string.studio_creating))
        else WizardPrimary(S(R.string.studio_create), enabled = names.isNotEmpty() && clashes.isEmpty() && placement != null) { create() }
        Spacer(Modifier.height(28.dp))
    }
}

/** The same ceiling the engine batch has; a central Worker holds far fewer anyway. */
private const val MAX_CENTRAL_BATCH = 500
