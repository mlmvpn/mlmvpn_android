package com.mlmvpn.scanner.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.mlmvpn.scanner.backup.BackupEngine
import com.mlmvpn.scanner.backup.BackupFile
import com.mlmvpn.scanner.backup.BackupHooks
import com.mlmvpn.scanner.backup.BackupKind
import com.mlmvpn.scanner.backup.BackupNames
import com.mlmvpn.scanner.backup.BackupStage
import com.mlmvpn.scanner.backup.RestoreApplier
import com.mlmvpn.scanner.backup.RestoreMode
import com.mlmvpn.scanner.store.tr
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
import com.mlmvpn.scanner.utils.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The Settings route of this page (see SettingsScreen). */
const val ROUTE_BACKUP = "backup_restore"

/** The Settings row's title -- also its line in the home screen's search (SettingsIndex). */
fun backupRowTitle(): String = tr("پشتیبان‌گیری و بازگردانی", "Backup & restore")

/** The Settings row's grey line. */
fun backupRowSubtitle(): String = tr("خروجی از تنظیمات، حساب‌ها و کانفیگ‌ها و برگرداندن آن‌ها", "Export settings, accounts and configs, and bring them back")

/** The Settings row's glyph, here so SettingsScreen needs no import for it. */
val backupRowIcon: androidx.compose.ui.graphics.vector.ImageVector
    get() = Icons.Default.SettingsBackupRestore

private const val SHARE_DIR = "backup-export"

private enum class Page { HOME, EXPORT, METHODS, PASSWORD, PREVIEW }

private class Notice(val title: String, val message: String?)

/**
 * «پشتیبان‌گیری و بازگردانی»: one file out of the app, and back in -- on this phone or another.
 *
 * Two doors only. «گرفتن خروجی» picks what goes in (the three exports, each method on its own),
 * an optional password, and where the file goes: saved anywhere the system picker offers, or sent
 * through the share sheet. Nothing is uploaded. «بازگردانی از فایل» opens a file, asks for its
 * password when it has one, shows what it holds -- every item with a tick -- and restores the
 * ticked ones by merging (the default) or replacing. The restore itself happens as the app starts
 * again (BackupStage), with everything put back if any step fails.
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val farsi = AppLocaleManager.isFarsi()

    var page by remember { mutableStateOf(Page.HOME) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    // ── export ──
    var parts by remember { mutableStateOf(setOf(BackupKind.MAIN, BackupKind.ACCOUNTS, BackupKind.METHOD)) }
    var methodsOff by remember { mutableStateOf(emptySet<String>()) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var exported by remember { mutableStateOf<BackupEngine.Exported?>(null) }
    var toSave by remember { mutableStateOf<ByteArray?>(null) }

    // ── import ──
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var filePassword by remember { mutableStateOf("") }
    var wrongPassword by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<BackupEngine.Preview?>(null) }
    var unticked by remember { mutableStateOf(emptySet<String>()) }
    var mode by remember { mutableStateOf(RestoreMode.MERGE) }
    var confirmReplace by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }

    val methods = remember { BackupHooks.contributors.filter { it.kind == BackupKind.METHOD } }

    // An export shared earlier sits in the cache until the next one; one opened later is gone.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { File(app.cacheDir, SHARE_DIR).deleteRecursively() } } }

    BackHandler(enabled = page != Page.HOME && busy == null && !restarting) {
        page = if (page == Page.METHODS) Page.EXPORT else Page.HOME
    }

    fun showOpenResult(r: BackupEngine.Opened) {
        when (r) {
            is BackupEngine.Opened.Ok -> {
                preview = r.preview
                unticked = emptySet()
                mode = RestoreMode.MERGE
                page = Page.PREVIEW
            }
            BackupEngine.Opened.NeedsPassword -> {
                filePassword = ""
                wrongPassword = false
                page = Page.PASSWORD
            }
            BackupEngine.Opened.WrongPassword -> wrongPassword = true
            BackupEngine.Opened.NotBackup -> notice = Notice(
                tr("این فایل پشتیبان این برنامه نیست", "This is not a backup of this app"),
                tr("فایلی را انتخاب کنید که با «گرفتن خروجی» ساخته شده است.", "Pick a file made with «Export»."),
            )
            BackupEngine.Opened.Corrupt -> notice = Notice(
                tr("فایل خراب است", "The file is damaged"),
                tr(
                    "فایل کامل نرسیده یا عوض شده است. اگر آن را فرستاده‌اید، دوباره بفرستید. هیچ چیزی تغییر نکرد.",
                    "The file is incomplete or was changed. If it was sent, send it again. Nothing was changed.",
                ),
            )
            is BackupEngine.Opened.TooNew -> notice = Notice(
                tr("فایل از نسخهٔ تازه‌تر برنامه است", "The file is from a newer app"),
                tr(
                    "این فایل با نسخهٔ ${r.appVersion} ساخته شده و این نسخه نمی‌تواند بازش کند. اول برنامه را به‌روز کنید.",
                    "It was made by version ${r.appVersion}, which this version cannot read. Update the app first.",
                ),
            )
        }
    }

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "open"
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { readLimited(context, uri) }.getOrNull() }
            if (bytes == null) {
                busy = null
                notice = Notice(
                    tr("فایل خوانده نشد", "The file could not be read"),
                    tr("فایل خیلی بزرگ است یا دسترسی به آن نیست.", "It is too large, or it cannot be opened."),
                )
                return@launch
            }
            fileBytes = bytes
            val r = withContext(Dispatchers.Default) { BackupEngine.open(bytes, null) }
            busy = null
            showOpenResult(r)
        }
    }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFile.MIME)) { uri: Uri? ->
        val bytes = toSave
        toSave = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.isSuccess
            }
            if (ok) {
                Toast.makeText(context, tr("فایل پشتیبان ذخیره شد.", "Backup saved."), Toast.LENGTH_SHORT).show()
            } else {
                notice = Notice(tr("ذخیره نشد", "Not saved"), tr("در آن جا نمی‌شد نوشت. جای دیگری را انتخاب کنید.", "That place could not be written to. Pick another."))
            }
        }
    }

    val passwordError = when {
        password.isNotEmpty() && password.trim().length < 4 -> tr("دست‌کم ۴ نویسه", "At least 4 characters")
        else -> null
    }
    val repeatError = if (password.isNotEmpty() && repeat.isNotEmpty() && repeat != password) tr("با رمز یکی نیست", "Does not match") else null
    val canExport = parts.isNotEmpty() && passwordError == null && (password.isEmpty() || repeat == password) && busy == null

    fun build(kind: String, then: (BackupEngine.Exported) -> Unit) {
        val items = BackupHooks.contributors.filter { it.kind in parts && (it.kind != BackupKind.METHOD || it.id !in methodsOff) }
        busy = kind
        scope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching { BackupEngine.export(app, items, parts, password.takeIf { it.isNotBlank() }) }
            }
            busy = null
            r.onSuccess { e ->
                exported = e
                if (e.included.isEmpty()) {
                    notice = Notice(tr("چیزی برای خروجی نبود", "Nothing to export"), tr("در بخش‌هایی که انتخاب کرده‌اید هنوز داده‌ای نیست.", "The parts you picked hold nothing yet."))
                } else {
                    then(e)
                }
            }.onFailure {
                notice = Notice(tr("فایل ساخته نشد", "The file was not made"), it.message)
            }
        }
    }

    when (page) {
        Page.HOME -> HomePage(
            onBack = onBack,
            busy = busy,
            last = remember(page) { BackupStage.lastResult(app) },
            farsi = farsi,
            onExport = { exported = null; page = Page.EXPORT },
            onImport = { openDoc.launch(arrayOf("*/*")) },
        )

        Page.EXPORT -> IosScreen(title = tr("گرفتن خروجی", "Export"), onBack = { page = Page.HOME }) {
            SettingsSectionHeader(tr("چه چیزهایی در فایل باشد", "What goes in the file"))
            SettingsGroup {
                KindToggle(BackupKind.MAIN, parts) { parts = it }
                Separator()
                KindToggle(BackupKind.ACCOUNTS, parts) { parts = it }
                Separator()
                KindToggle(BackupKind.METHOD, parts) { parts = it }
                if (BackupKind.METHOD in parts) {
                    Separator()
                    val on = methods.count { it.id !in methodsOff }
                    SettingsRow(
                        title = tr("انتخاب روش‌ها", "Choose methods"),
                        icon = Icons.Default.Tune,
                        tint = Ios.Indigo,
                        value = if (on == methods.size) tr("همه", "All") else tr(
                            BackupNames.persianDigits("$on از ${methods.size}"),
                            "$on of ${methods.size}",
                        ),
                        onClick = { page = Page.METHODS },
                    )
                }
            }

            SettingsSectionHeader(tr("رمز فایل (اختیاری)", "File password (optional)"))
            SettingsGroup {
                SettingsTextRow(
                    title = tr("رمز", "Password"),
                    value = password,
                    onValueChange = { password = it; exported = null },
                    icon = Icons.Default.Lock,
                    tint = Ios.Orange,
                    placeholder = tr("بدون رمز", "None"),
                    error = passwordError,
                    secret = true,
                )
                if (password.isNotEmpty()) {
                    Separator()
                    SettingsTextRow(
                        title = tr("تکرار رمز", "Repeat"),
                        value = repeat,
                        onValueChange = { repeat = it },
                        icon = Icons.Default.Lock,
                        tint = Ios.Orange,
                        error = repeatError,
                        secret = true,
                    )
                }
            }
            if (password.isEmpty()) {
                if (BackupKind.ACCOUNTS in parts) {
                    WarningFooter(
                        tr(
                            "بدون رمز، هر کس این فایل را داشته باشد به حساب‌ها و رمزهای شما دسترسی دارد. اگر آن را جایی می‌فرستید، رمز بگذارید.",
                            "Without a password, anyone holding this file can use your accounts and passwords. Set one if you send it anywhere.",
                        )
                    )
                }
            } else {
                SettingsFooter(
                    tr(
                        "این رمز را جایی یادداشت کنید: بدون آن، فایل باز نمی‌شود و هیچ‌کس نمی‌تواند بازش کند.",
                        "Write this password down: without it the file cannot be opened, by anyone.",
                    )
                )
            }

            Spacer(Modifier.height(20.dp))
            SettingsGroup {
                SettingsActionRow(
                    label = tr("ذخیره در گوشی", "Save to phone"),
                    icon = Icons.Default.Save,
                    tint = Ios.Blue,
                    busy = busy == "save",
                    enabled = canExport,
                    onClick = {
                        build("save") { e ->
                            toSave = e.bytes
                            createDoc.launch(e.fileName)
                        }
                    },
                )
                Separator()
                SettingsActionRow(
                    label = tr("فرستادن…", "Send…"),
                    icon = Icons.Default.Share,
                    busy = busy == "share",
                    enabled = canExport,
                    onClick = {
                        build("share") { e ->
                            if (!share(context, e)) notice = Notice(tr("فرستادن باز نشد", "Could not open sharing"), null)
                        }
                    },
                )
            }
            exported?.let { e ->
                SettingsFooter(
                    tr("در فایل: ", "In the file: ") + countText(e.included.size) +
                        (if (e.failed.isEmpty()) "" else tr("\nنیامد: ", "\nLeft out: ") +
                            e.failed.joinToString(tr("، ", ", ")) { (t, why) -> "$t ($why)" })
                )
            }
            SettingsFooter(tr("هیچ چیزی جایی آپلود نمی‌شود؛ فایل فقط همان‌جایی است که خودتان می‌گذارید.", "Nothing is uploaded: the file is only where you put it."))
            Spacer(Modifier.height(24.dp))
        }

        Page.METHODS -> IosScreen(title = tr("انتخاب روش‌ها", "Choose methods"), onBack = { page = Page.EXPORT }) {
            Spacer(Modifier.height(14.dp))
            SettingsGroup {
                methods.forEachIndexed { i, m ->
                    if (i > 0) Separator()
                    SettingsToggle(
                        title = m.title(),
                        checked = m.id !in methodsOff,
                        onCheckedChange = { on -> methodsOff = if (on) methodsOff - m.id else methodsOff + m.id },
                    )
                }
            }
            SettingsFooter(tr("روشی که هنوز تنظیمی ندارد، در فایل نمی‌آید.", "A method with nothing set yet is left out of the file."))
            Spacer(Modifier.height(24.dp))
        }

        Page.PASSWORD -> IosScreen(title = tr("رمز فایل", "File password"), onBack = { page = Page.HOME }) {
            Spacer(Modifier.height(14.dp))
            SettingsGroup {
                SettingsTextRow(
                    title = tr("رمز", "Password"),
                    value = filePassword,
                    onValueChange = { filePassword = it; wrongPassword = false },
                    icon = Icons.Default.LockOpen,
                    tint = Ios.Orange,
                    error = if (wrongPassword) tr("رمز درست نیست", "Wrong password") else null,
                    secret = true,
                )
            }
            SettingsFooter(tr("این فایل با رمز ساخته شده است. همان رمزی را بزنید که هنگام گرفتن خروجی گذاشتید.", "This file has a password: the one set when it was made."))
            Spacer(Modifier.height(20.dp))
            SettingsGroup {
                SettingsActionRow(
                    label = tr("باز کردن", "Open"),
                    icon = Icons.Default.LockOpen,
                    tint = Ios.Blue,
                    busy = busy == "password",
                    enabled = filePassword.isNotBlank() && busy == null,
                    onClick = {
                        val bytes = fileBytes ?: return@SettingsActionRow
                        busy = "password"
                        scope.launch {
                            val r = withContext(Dispatchers.Default) { BackupEngine.open(bytes, filePassword) }
                            busy = null
                            showOpenResult(r)
                        }
                    },
                )
            }
        }

        Page.PREVIEW -> {
            val p = preview
            if (p == null) {
                page = Page.HOME
            } else {
                PreviewPage(
                    preview = p,
                    farsi = farsi,
                    unticked = unticked,
                    onTick = { id, on -> unticked = if (on) unticked - id else unticked + id },
                    mode = mode,
                    onMode = { mode = it },
                    vpnUp = remember(p) { runCatching { com.mlmvpn.scanner.data.VpnSwitch.isUp(app) }.getOrDefault(false) },
                    busy = busy == "restore" || restarting,
                    onBack = { page = Page.HOME },
                    onRestore = {
                        if (mode == RestoreMode.REPLACE) confirmReplace = true
                        else startRestore(app, p, unticked, mode, onBusy = { busy = it }, onRestarting = { restarting = true }, onNotice = { notice = it })
                    },
                )
            }
        }
    }

    if (confirmReplace) {
        IosAlert(
            title = tr("جایگزینی کامل؟", "Replace completely?"),
            message = tr(
                "برای هر موردی که انتخاب کرده‌اید، اطلاعات فعلی گوشی کنار می‌رود و فقط نسخهٔ فایل می‌ماند.",
                "For every item you ticked, what the phone has now goes and only the file's copy stays.",
            ),
            actions = listOf(
                IosAlertAction(tr("انصراف", "Cancel"), { confirmReplace = false }),
                IosAlertAction(tr("جایگزینی", "Replace"), {
                    confirmReplace = false
                    preview?.let { p ->
                        startRestore(app, p, unticked, mode, onBusy = { busy = it }, onRestarting = { restarting = true }, onNotice = { notice = it })
                    }
                }, destructive = true, preferred = true),
            ),
            onDismiss = { confirmReplace = false },
        )
    }

    if (restarting) {
        IosAlert(
            title = tr("در حال بازگردانی", "Restoring"),
            message = tr(
                "برنامه یک لحظه بسته و دوباره باز می‌شود و بازگردانی پیش از بالا آمدنش انجام می‌شود.",
                "The app closes for a moment and opens again; the restore is done before it comes up.",
            ),
            actions = emptyList(),
            onDismiss = {},
        )
    }

    notice?.let { n ->
        IosAlert(
            title = n.title,
            message = n.message,
            actions = listOf(IosAlertAction(tr("باشه", "OK"), { notice = null }, preferred = true)),
            onDismiss = { notice = null },
        )
    }
}

/** Plans the ticked items, stages them and restarts the app; any item that cannot be restored stops it all, untouched. */
private fun startRestore(
    app: Context,
    preview: BackupEngine.Preview,
    unticked: Set<String>,
    mode: RestoreMode,
    onBusy: (String?) -> Unit,
    onRestarting: () -> Unit,
    onNotice: (Notice) -> Unit,
) {
    val chosen = preview.items.filter { it.blocked == null && it.id !in unticked }
    if (chosen.isEmpty()) return
    onBusy("restore")
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    Thread({
        val outcome = runCatching {
            val plan = BackupEngine.plan(app, chosen, mode)
            if (plan.isEmpty) null else plan.also { BackupStage.stage(app, it, chosen.map { c -> c.title }) }
        }
        main.post {
            onBusy(null)
            outcome.onSuccess { plan ->
                if (plan == null) {
                    onNotice(Notice(tr("چیزی برای بازگرداندن نبود", "Nothing to restore"), null))
                } else {
                    onRestarting()
                    Thread({ BackupStage.stopEverythingAndRestart(app) }, "backup-restart").start()
                }
            }.onFailure { t ->
                val title = (t as? BackupEngine.PlanError)?.title
                onNotice(
                    Notice(
                        tr("بازگردانی انجام نشد", "Nothing was restored"),
                        (if (title != null) tr("«$title» بازگردانده نشد. ", "«$title» could not be restored. ") else "") +
                            tr("هیچ چیزی تغییر نکرد. آن مورد را بردارید و دوباره امتحان کنید.", "Nothing was changed. Untick it and try again."),
                    )
                )
            }
        }
    }, "backup-plan").start()
}

@Composable
private fun HomePage(
    onBack: () -> Unit,
    busy: String?,
    last: RestoreApplier.Journal?,
    farsi: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    IosScreen(title = backupRowTitle(), onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsRow(
                title = tr("گرفتن خروجی", "Export"),
                subtitle = tr("تنظیمات، حساب‌ها و کانفیگ‌ها در یک فایل", "Settings, accounts and configs in one file"),
                icon = Icons.Default.FileUpload,
                tint = Ios.Blue,
                onClick = onExport,
            )
            Separator()
            SettingsActionRow(
                label = tr("بازگردانی از فایل", "Restore from a file"),
                icon = Icons.Default.FileDownload,
                tint = Ios.Green,
                labelColor = Ios.Label,
                busy = busy == "open",
                enabled = busy == null,
                onClick = onImport,
            )
        }
        SettingsFooter(
            tr(
                "فایل را روی همین گوشی یا گوشی دیگری برگردانید. پیش از بازگردانی، فهرست داخل فایل را می‌بینید و انتخاب می‌کنید.",
                "Restore the file on this phone or another. Before anything is restored you see what it holds and choose.",
            )
        )
        if (last != null) {
            SettingsFooter(
                tr("آخرین بازگردانی", "Last restore") + " (" + BackupNames.displayDate(last.at, farsi) + "): " +
                    BackupStage.resultMessage(last)
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PreviewPage(
    preview: BackupEngine.Preview,
    farsi: Boolean,
    unticked: Set<String>,
    onTick: (String, Boolean) -> Unit,
    mode: RestoreMode,
    onMode: (RestoreMode) -> Unit,
    vpnUp: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onRestore: () -> Unit,
) {
    IosScreen(title = tr("بازگردانی", "Restore"), onBack = if (busy) null else onBack) {
        SettingsFooter(
            tr("ساخته‌شده در ", "Made on ") + BackupNames.displayDate(preview.createdAt, farsi) +
                tr("، نسخهٔ ", ", version ") + preview.appVersion
        )
        if (preview.newerApp) {
            WarningFooter(
                tr(
                    "این فایل با نسخهٔ تازه‌تر برنامه ساخته شده است. چیزهایی که این نسخه نمی‌شناسد کنار گذاشته می‌شود.",
                    "This file was made by a newer version. What this version does not know is left out.",
                )
            )
        }
        for (kind in BackupKind.entries) {
            val items = preview.items.filter { it.kind == kind }
            if (items.isEmpty()) continue
            SettingsSectionHeader(kindTitle(kind))
            SettingsGroup {
                items.forEachIndexed { i, item ->
                    if (i > 0) Separator()
                    val blocked = item.blocked
                    if (blocked == null) {
                        SettingsToggle(
                            title = item.title,
                            subtitle = item.summary,
                            checked = item.id !in unticked,
                            onCheckedChange = { onTick(item.id, it) },
                        )
                    } else {
                        SettingsRow(title = item.title, subtitle = blocked, titleColor = Ios.SecondaryLabel, showChevron = false)
                    }
                }
            }
        }
        val unknown = preview.items.filter { it.kind == null }
        if (unknown.isNotEmpty()) {
            SettingsSectionHeader(tr("ناشناخته برای این نسخه", "Unknown to this version"))
            SettingsGroup {
                unknown.forEachIndexed { i, item ->
                    if (i > 0) Separator()
                    SettingsRow(title = item.title, subtitle = item.blocked, titleColor = Ios.SecondaryLabel, showChevron = false)
                }
            }
        }

        SettingsSectionHeader(tr("چطور برگردد", "How"))
        SettingsGroup {
            ModeRow(tr("ادغام با اطلاعات فعلی", "Merge with what is here"), mode == RestoreMode.MERGE) { onMode(RestoreMode.MERGE) }
            Separator()
            ModeRow(tr("جایگزینی کامل", "Replace completely"), mode == RestoreMode.REPLACE) { onMode(RestoreMode.REPLACE) }
        }
        SettingsFooter(
            if (mode == RestoreMode.MERGE) tr(
                "چیزهای فعلی گوشی می‌مانند و موارد فایل به آن‌ها اضافه می‌شوند. چیزی که هم در گوشی هست و هم در فایل، به‌روز می‌شود.",
                "What the phone has stays and the file's entries are added to it. Anything in both is brought up to date.",
            ) else tr(
                "برای هر مورد انتخاب‌شده، اطلاعات فعلی گوشی کنار می‌رود و فقط نسخهٔ فایل می‌ماند.",
                "For every ticked item, what the phone has goes and only the file's copy stays.",
            )
        )
        if (vpnUp) {
            WarningFooter(tr("اتصال فعلی برای بازگردانی قطع می‌شود؛ بعد دوباره وصل کنید.", "The current connection is stopped for the restore; connect again afterwards."))
        }

        Spacer(Modifier.height(20.dp))
        val count = preview.items.count { it.blocked == null && it.id !in unticked }
        SettingsGroup {
            SettingsActionRow(
                label = tr("بازگردانی", "Restore") + if (count > 0) " (" + countText(count) + ")" else "",
                icon = Icons.Default.Restore,
                tint = Ios.Green,
                labelColor = Ios.Label,
                busy = busy,
                enabled = count > 0 && !busy,
                onClick = onRestore,
            )
        }
        SettingsFooter(
            tr(
                "برنامه یک بار بسته و باز می‌شود. اگر هر قدمی خطا بدهد، همه‌چیز به حالت پیش از بازگردانی برمی‌گردد.",
                "The app restarts once. If any step fails, everything goes back to how it was before.",
            )
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun com.mlmvpn.scanner.ui.settings.SettingsGroupScope.KindToggle(kind: BackupKind, parts: Set<BackupKind>, onChange: (Set<BackupKind>) -> Unit) {
    SettingsToggle(
        title = kindTitle(kind),
        subtitle = when (kind) {
            BackupKind.MAIN -> tr("زبان، ظاهر، شبکه، اشتراک با دستگاه دیگر", "Language, look, network, sharing")
            BackupKind.ACCOUNTS -> tr("کلادفلر، وارپ، گف، گیت‌هاب، OpenVPN، کانفیگ‌ها…", "Cloudflare, WARP, Geph, GitHub, OpenVPN, configs…")
            BackupKind.METHOD -> tr("تنظیمات خود هر کاشی", "Each tile's own settings")
        },
        checked = kind in parts,
        onCheckedChange = { on -> onChange(if (on) parts + kind else parts - kind) },
    )
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ios.Label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun WarningFooter(text: String) {
    Text(
        text,
        color = Ios.Orange,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
    )
}

private fun kindTitle(kind: BackupKind): String = when (kind) {
    BackupKind.MAIN -> tr("تنظیمات اصلی", "Main settings")
    BackupKind.ACCOUNTS -> tr("حساب‌ها و کانفیگ‌ها", "Accounts and configs")
    BackupKind.METHOD -> tr("تنظیمات روش‌ها", "Method settings")
}

private fun countText(n: Int): String = tr(BackupNames.persianDigits(n.toString()) + " مورد", "$n items")

/** The file's bytes, refusing anything larger than a backup can be. */
private fun readLimited(context: Context, uri: Uri): ByteArray {
    context.contentResolver.openInputStream(uri)!!.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            check(out.size() <= BackupFile.MAX_FILE_BYTES) { "too large" }
        }
        return out.toByteArray()
    }
}

/** Through the share sheet, from the cache (FileProvider `backup-export/`). */
private fun share(context: Context, e: BackupEngine.Exported): Boolean = runCatching {
    val dir = File(context.cacheDir, SHARE_DIR)
    dir.deleteRecursively()
    dir.mkdirs()
    val file = File(dir, e.fileName)
    file.writeBytes(e.bytes)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = BackupFile.MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, e.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, tr("فرستادن فایل پشتیبان", "Send the backup"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )
    true
}.getOrDefault(false)
