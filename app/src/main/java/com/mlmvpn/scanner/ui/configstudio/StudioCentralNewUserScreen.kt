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
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.central.Central
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.SubscriptionPolicy
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.parts.CentralKit
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.PayloadCard
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
 * «کاربر جدید» on an account's central Worker: a name, how long (a «بسته» fills it in), and one
 * button. The screen then turns into what was made — the person's configs, ready to copy, share or
 * show as QR, and the status message to send with them.
 *
 * Only what the central Worker can keep: a term. Volume and device limits are not offered, rather
 * than offered and silently not applied.
 */
@Composable
fun StudioCentralNewUserScreen(
    store: StudioStore,
    state: StudioState,
    onBack: () -> Unit,
    onOpenUser: (installationId: String, userId: String) -> Unit,
    /** No account carries the Studio on its central Worker yet: the way to add one. */
    onAddAccount: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var username by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var picked by remember { mutableStateOf<Plan?>(null) }
    var placement by remember { mutableStateOf(store.placementFor()) }
    var pickingShard by remember { mutableStateOf(false) }
    val fleet = remember(state.installations) { store.installedAccounts().filter { store.isCentral(it.id) } }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var made by remember { mutableStateOf<Pair<StudioUser, List<String>>?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        plans = store.refreshPlans().filter { !it.archived }
        picked = plans.firstOrNull { it.isDefault }
        picked?.durationDays?.let { days = it.toString() }
    }

    fun create() {
        val name = username.trim()
        if (name.isEmpty()) { error = context.getString(R.string.studio_username_required); return }
        if (store.usernameTaken(name)) { error = context.getString(R.string.studio_username_taken_here); return }
        val account = placement ?: return
        val typedDays = days.trim().toIntOrNull()?.takeIf { it > 0 }
        busy = true; error = null
        scope.launch {
            val policy = SubscriptionPolicy(expiresAt = typedDays?.let { System.currentTimeMillis() + it * Central.DAY_MS })
            when (val r = store.createUser(account, name, policy, planId = picked?.id)) {
                is StudioResult.Err -> error = CentralKit.errorText(context, r.error)
                is StudioResult.Ok -> {
                    val links = (store.centralLinks(account, r.value.id) as? StudioResult.Ok)?.value.orEmpty()
                    made = r.value to links
                    store.refreshInstallations()
                }
            }
            busy = false
        }
    }

    IosScreen(
        title = S(R.string.studio_new_user_title),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_users),
    ) {
        val done = made
        if (done != null) {
            val (user, links) = done
            Spacer(Modifier.height(6.dp))
            NoticeCard(
                icon = StudioIcons.Ok,
                tint = Ios.Green,
                title = S(R.string.studio_new_ready_title).replace("%1\$s", user.username),
                body = tr("روی ورکر مرکزی ساخته شد. این کانفیگ‌ها را برایش بفرستید.", "Made on the central Worker. Send them these configs."),
            )
            if (links.isEmpty()) {
                InfoCard(tr("کانفیگ‌ها ساخته نشد؛ از صفحهٔ کاربر دوباره بگیرید.", "The configs were not built; take them again from the person's page."))
            } else {
                SettingsSectionHeader(tr("کانفیگ‌ها", "Configs"))
                PayloadCard(links.first())
                Spacer(Modifier.height(10.dp))
                SettingsGroup {
                    SettingsActionRow(
                        label = if (copied) S(R.string.studio_copied) else tr("کپی همهٔ کانفیگ‌ها (${faNum(links.size)})", "Copy all configs (${links.size})"),
                        icon = StudioIcons.Copy, tint = Ios.Blue,
                    ) { clipboard.setText(AnnotatedString(links.joinToString("\n"))); copied = true }
                    Separator()
                    SettingsActionRow(label = S(R.string.studio_share), icon = StudioIcons.Share, tint = Ios.Blue) {
                        StudioShare.shareText(context, links.joinToString("\n"), user.username)
                    }
                    Separator()
                    SettingsActionRow(label = S(R.string.studio_qr), icon = StudioIcons.Qr, tint = Ios.Indigo) { showQr = !showQr }
                    Separator()
                    SettingsActionRow(label = tr("فرستادن پیام وضعیت", "Send the status message"), icon = StudioIcons.Share, tint = Ios.Green) {
                        val doc = com.mlmvpn.scanner.data.studio.central.CentralFleet.doc(context, user.installationId)
                        StudioShare.shareText(context, CentralKit.statusMessage(user, doc?.contact), user.username)
                    }
                }
                if (showQr) {
                    Spacer(Modifier.height(10.dp))
                    StudioQrCard(links.first(), S(R.string.studio_qr_too_long))
                }
            }
            Spacer(Modifier.height(18.dp))
            WizardPrimary(S(R.string.studio_new_open_page)) { onOpenUser(user.installationId, user.id) }
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingsActionRow(label = S(R.string.studio_done), icon = StudioIcons.Ok, tint = Ios.Gray, onClick = onBack)
            }
            Spacer(Modifier.height(28.dp))
            return@IosScreen
        }

        if (fleet.isEmpty()) {
            PageIntro(tr("کاربر تازه روی ورکر مرکزی یک حساب کلادفلر ساخته می‌شود. اول یک حساب اضافه کنید.",
                "A new person is made on a Cloudflare account's central Worker. Add an account first."))
            Spacer(Modifier.height(10.dp))
            WizardPrimary(S(R.string.studio_add_account), onClick = onAddAccount)
            return@IosScreen
        }

        PageIntro(tr("نام و مدت؛ همین. کاربر روی ورکر مرکزی ساخته می‌شود و کانفیگ‌هایش همین‌جا آماده است.",
            "A name and a term; that is all. The person is made on the central Worker and their configs are ready here."))

        SettingsSectionHeader(S(R.string.studio_field_username))
        WizardField(username, { username = it; error = null }, S(R.string.studio_field_username_hint))

        if (fleet.size > 1) {
            SettingsSectionHeader(S(R.string.studio_placement))
            SettingsGroup {
                if (!pickingShard) {
                    SettingsRow(
                        title = placement?.let { it.name.ifEmpty { it.email } } ?: S(R.string.studio_placement_none),
                        subtitle = placement?.let { roomLine(store, it) } ?: S(R.string.studio_placement_auto),
                        icon = StudioIcons.Accounts,
                        tint = Ios.Gray,
                        onClick = { pickingShard = true },
                    )
                } else {
                    fleet.forEachIndexed { index, acc ->
                        if (index > 0) StudioSeparator()
                        ChoiceRow(
                            title = acc.name.ifEmpty { acc.email },
                            subtitle = roomLine(store, acc),
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
        InfoCard(tr("خالی یعنی بدون تاریخ پایان. حجم و تعداد دستگاه روی ورکر مرکزی شمرده نمی‌شود.",
            "Empty means no end date. Volume and the number of devices are not counted on the central Worker."))

        Spacer(Modifier.height(14.dp))
        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }
        if (busy) WizardBusy(S(R.string.studio_creating))
        else WizardPrimary(S(R.string.studio_create), enabled = username.isNotBlank() && placement != null) { create() }
        Spacer(Modifier.height(28.dp))
    }
}

/** «۳ از ۱۵ جای خالی» for one account's central Worker, from this phone's copies. */
@Composable
private fun roomLine(store: StudioStore, account: CloudAccount): String {
    val left = store.centralRoomLeft(account)
    return if (left > 0) tr("${faNum(left)} جای خالی", "$left places free") else tr("پر است", "full")
}
