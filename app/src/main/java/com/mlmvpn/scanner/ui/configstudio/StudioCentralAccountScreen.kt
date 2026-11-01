package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.central.CentralFleet
import com.mlmvpn.scanner.engines.gateway.EdgeGateway
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.parts.CentralKit
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PayloadCard
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
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
 * One account's central Worker as the Studio sees it: where it is, how many people it carries and
 * how many more fit, whether the account's list and the Worker are in step, the operator's contact
 * line for the status message, and «جدا کردن از این گوشی». Nothing here deletes anything on
 * Cloudflare: removing the Worker itself is the Cloud tab's, and it says what that does to people.
 */
@Composable
fun StudioCentralAccountScreen(
    store: StudioStore,
    installationId: String,
    onBack: () -> Unit,
    onRemoved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsState()
    val account = remember(state.installations) { store.installedAccounts().firstOrNull { it.id == installationId } }
    val version by EdgeGateway.version.collectAsState()

    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var docTick by remember { mutableStateOf(0) }
    val doc = remember(docTick, version) { CentralFleet.doc(context, installationId) }
    var contact by remember { mutableStateOf(doc?.contact.orEmpty()) }

    LaunchedEffect(installationId) {
        account ?: return@LaunchedEffect
        // The account's list taken in, so the counts below are the account's and not just this phone's.
        store.sync(account)
        docTick++
    }

    IosScreen(
        title = account?.let { it.name.ifEmpty { it.email } } ?: S(R.string.studio_accounts),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_accounts),
        onRefresh = { account?.let { store.sync(it); docTick++ } },
    ) {
        if (account == null || doc == null) {
            InfoCard(S(R.string.studio_err_generic))
            return@IosScreen
        }
        val gw = remember(version, docTick) { EdgeGateway.status(context, account) }
        val now = System.currentTimeMillis()
        val live = doc.liveCount(now)
        val people = doc.people.size

        Spacer(Modifier.height(10.dp))
        note?.let { InfoCard(it); Spacer(Modifier.height(12.dp)) }
        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }

        SettingsSectionHeader(tr("ورکر مرکزی", "Central Worker"))
        if (gw.installed) {
            PayloadCard(gw.url)
            if (doc.script.isNotBlank() && doc.script != gw.script) {
                Spacer(Modifier.height(8.dp))
                NoticeCard(
                    icon = StudioIcons.Warning, tint = Ios.Orange,
                    title = tr("ورکر مرکزی این حساب عوض شده", "This account's central Worker changed"),
                    body = tr("لینک‌هایی که پیش‌تر فرستاده‌اید به ورکر قبلی اشاره می‌کنند. کانفیگ تازهٔ هر کاربر را از صفحهٔ خودش بگیرید و برایش بفرستید.",
                        "The links you sent before point at the old Worker. Take each person's new configs from their page and send them."),
                )
            }
        } else {
            InfoCard(tr("ورکر مرکزی هنوز روی این حساب نصب نیست؛ با ساختن اولین کاربر نصب می‌شود (یک‌بار، برای همهٔ پنل‌ها).",
                "The central Worker is not installed on this account yet; making the first person installs it (once, for every panel)."))
        }

        SettingsSectionHeader(tr("جا", "Room"))
        SettingsGroup {
            SettingsRow(title = tr("کاربران", "People"), value = faNum(people), icon = StudioIcons.Users, tint = Ios.Blue, showChevron = false)
            Separator()
            SettingsRow(title = tr("الان روی ورکر", "On the Worker now"), value = faNum(live), icon = StudioIcons.Ok, tint = Ios.Green, showChevron = false)
            Separator()
            SettingsRow(title = tr("جای خالی", "Places free"), value = faNum((gw.studioRoom - live).coerceAtLeast(0)), icon = StudioIcons.Add, tint = Ios.Gray, showChevron = false)
        }
        InfoCard(tr("ورکر مرکزی روی هر حساب تعداد مشخصی شناسه می‌پذیرد: یکی برای خود ورکر، یکی برای هر پنل وصل‌شده و بقیه برای کاربران استدیو. برای جای بیشتر، حساب کلادفلر دیگری اضافه کنید.",
            "A central Worker takes a set number of ids per account: one for itself, one per connected panel, and the rest for Studio people. For more room, add another Cloudflare account."))

        SettingsSectionHeader(tr("هماهنگی", "In step"))
        SettingsGroup {
            SettingsRow(
                title = if (doc.unshared) tr("هنوز در حساب کلادفلر ثبت نشده", "Not written to the Cloudflare account yet")
                else tr("فهرست در حساب کلادفلر ثبت است", "The list is on the Cloudflare account"),
                subtitle = tr("دستگاه دیگر با همین حساب همین کاربران را می‌بیند.", "Another device with this account sees the same people."),
                icon = StudioIcons.Activity,
                tint = if (doc.unshared) Ios.Orange else Ios.Green,
                showChevron = false,
            )
            Separator()
            SettingsActionRow(label = tr("همین حالا هماهنگ کن", "Bring in step now"), icon = StudioIcons.Refresh, tint = Ios.Blue, enabled = !busy) {
                busy = true; error = null; note = null
                scope.launch {
                    when (val r = store.centralApplyNow(account)) {
                        is StudioResult.Ok -> note = when {
                            r.value.unreadable -> CentralKit.errorText(context, com.mlmvpn.scanner.data.studio.api.StudioError("registry_unreadable"))
                            r.value.leftOut > 0 -> tr("${faNum(r.value.leftOut)} کاربر روی این ورکر جا نشد.", "${r.value.leftOut} people did not fit on this Worker.")
                            r.value.uploaded -> tr("ورکر مرکزی به‌روز شد.", "The central Worker was updated.")
                            else -> tr("همه‌چیز هماهنگ است.", "Everything is in step.")
                        }
                        is StudioResult.Err -> error = CentralKit.errorText(context, r.error)
                    }
                    docTick++
                    busy = false
                }
            }
        }

        SettingsSectionHeader(tr("راه تماس در پیام وضعیت", "Contact line in the status message"))
        WizardField(contact, { contact = it }, tr("مثلاً آیدی تلگرام", "e.g. a Telegram id"), ltr = false)
        if (contact.trim() != doc.contact.orEmpty()) {
            Spacer(Modifier.height(10.dp))
            WizardPrimary(S(R.string.studio_save), busy = busy) {
                busy = true; error = null
                scope.launch {
                    when (val r = store.apiFor(account).patchSettings(contact = contact.trim())) {
                        is StudioResult.Ok -> note = context.getString(R.string.studio_saved)
                        is StudioResult.Err -> error = CentralKit.errorText(context, r.error)
                    }
                    docTick++
                    busy = false
                }
            }
        }

        SettingsSectionHeader(S(R.string.studio_section_danger))
        SettingsGroup {
            SettingsActionRow(label = tr("جدا کردن از این گوشی", "Remove from this phone"), icon = StudioIcons.Disconnect, tint = Ios.Red, enabled = !busy) {
                confirmDisconnect = true
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (confirmDisconnect) {
        IosAlert(
            title = tr("جدا کردن از این گوشی؟", "Remove from this phone?"),
            message = tr("چیزی روی کلادفلر پاک نمی‌شود: کاربران همچنان وصل می‌شوند و فهرستشان روی حساب می‌ماند. فقط این گوشی دیگر آن‌ها را مدیریت نمی‌کند؛ با افزودن دوبارهٔ حساب برمی‌گردند.",
                "Nothing on Cloudflare is deleted: people keep connecting and their list stays on the account. Only this phone stops managing them; adding the account again brings them back."),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmDisconnect = false }),
                IosAlertAction(tr("جدا کن", "Remove"), {
                    confirmDisconnect = false
                    busy = true
                    scope.launch {
                        store.disconnect(installationId)
                        busy = false
                        onRemoved()
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmDisconnect = false },
        )
    }
}
