package com.mlmvpn.scanner.ui.store

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.store.StoreCatalog
import com.mlmvpn.scanner.store.StoreHistory
import com.mlmvpn.scanner.store.StoreItem
import com.mlmvpn.scanner.store.StoreJob
import com.mlmvpn.scanner.store.StoreKind
import com.mlmvpn.scanner.store.StoreFiles
import com.mlmvpn.scanner.store.StoreManager
import com.mlmvpn.scanner.store.StoreRow
import com.mlmvpn.scanner.store.StoreSource
import com.mlmvpn.scanner.store.about
import com.mlmvpn.scanner.store.subtitle
import com.mlmvpn.scanner.store.title
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader

/**
 * «ام‌ال‌ام استور» — the Windows app's store, as a phone app shaped like Apple's App Store:
 * a Today page with the one update that matters most, every item by category, an Updates page with
 * «بروزرسانی همه», and a product page for each item with its release notes, where it came from,
 * and one button that goes back.
 *
 * All of it reads [StoreManager]; nothing here touches the network or a file itself.
 */
private enum class StoreTab { TODAY, APPS, UPDATES }

private val Squircle = RoundedCornerShape(percent = 23)
private val CardShape = RoundedCornerShape(24.dp)

@Composable
fun StoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rows by StoreManager.rows.collectAsState()
    val jobs by StoreManager.jobs.collectAsState()
    val checking by StoreManager.checking.collectAsState()
    val lastCheck by StoreManager.lastCheck.collectAsState()
    val appState by com.mlmvpn.scanner.update.UpdateChecker.state.collectAsState()

    var tab by rememberSaveable { mutableStateOf(StoreTab.TODAY) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        StoreManager.load(context)
        StoreManager.checkIfStale(context)
    }
    // The app row is read from the updater's own state; redraw when it moves.
    LaunchedEffect(appState) { StoreManager.load(context) }
    // A job that ends changes what is installed.
    LaunchedEffect(jobs.values.count { it.running }) { StoreManager.load(context) }

    BackHandler(enabled = openId != null) { openId = null }

    val pending = rows.filter { it.hasUpdate }
    val history = remember(rows, jobs) { StoreFiles.history(context) }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = openId to tab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "store-page",
        ) { (id, t) ->
            val row = id?.let { i -> rows.firstOrNull { it.item.id == i } }
            if (row != null) {
                ProductPage(row = row, job = jobs[row.item.id], onBack = { openId = null })
            } else when (t) {
                StoreTab.TODAY -> TodayPage(rows, pending, history, jobs, checking, lastCheck, onBack, onOpen = { openId = it })
                StoreTab.APPS -> AppsPage(rows, jobs, onBack, onOpen = { openId = it })
                StoreTab.UPDATES -> UpdatesPage(pending, history, jobs, checking, lastCheck, onBack, onOpen = { openId = it })
            }
        }
        if (openId == null) {
            StoreTabBar(
                tab = tab,
                badge = pending.size,
                onSelect = { tab = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current + 10.dp),
            )
        }
    }
}

// ── pages ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun TodayPage(
    rows: List<StoreRow>,
    pending: List<StoreRow>,
    history: List<StoreHistory>,
    jobs: Map<String, StoreJob>,
    checking: Boolean,
    lastCheck: Long,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    IosScreen(onBack = onBack, largeTitle = tr("امروز", "Today")) {
        Text(
            todayLine(),
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 0.dp, bottom = 12.dp),
        )

        val feature = pending.sortedBy { order(it.item) }.firstOrNull()
        if (feature != null) {
            FeatureCard(feature, jobs[feature.item.id], onOpen = { onOpen(feature.item.id) })
        } else {
            AllCurrentCard(checking, lastCheck)
        }

        if (pending.size > 1) {
            SettingsSectionHeader(tr("بروزرسانی‌های در انتظار", "Waiting to update"))
            SettingsGroup {
                pending.sortedBy { order(it.item) }.drop(1).take(4).forEachIndexed { i, r ->
                    if (i > 0) Separator()
                    AppRow(r, jobs[r.item.id], onOpen = { onOpen(r.item.id) })
                }
            }
        }

        SettingsSectionHeader(tr("در یک نگاه", "At a glance"))
        StatsCard(rows)

        if (history.isNotEmpty()) {
            SettingsSectionHeader(tr("بروزرسانی‌های اخیر", "Recently updated"))
            SettingsGroup {
                history.take(4).forEachIndexed { i, h ->
                    if (i > 0) Separator()
                    HistoryRow(h, onOpen = { onOpen(h.id) })
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun AppsPage(rows: List<StoreRow>, jobs: Map<String, StoreJob>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    IosScreen(onBack = onBack, largeTitle = tr("همه", "Everything")) {
        Section(tr("برنامه", "App"), rows.filter { it.item.kind == StoreKind.APP }, jobs, onOpen)
        Section(tr("هسته‌ها", "Engines"), rows.filter { it.item.kind == StoreKind.ENGINE && it.item.source !is StoreSource.WithApp }, jobs, onOpen)
        Section(tr("ورکرها", "Workers"), rows.filter { it.item.kind == StoreKind.WORKER }, jobs, onOpen,
            footer = tr("کدی که برنامه روی حساب کلودفلر شما مستقر می‌کند. بروزرسانی، کد ورکرهای مستقرشده را هم عوض می‌کند و تنظیمات، کاربران و دیتابیسشان دست نمی‌خورد. ورکرهایی که استور نشناسد هرگز لمس نمی‌شوند.",
                "The code the app deploys to your Cloudflare account. Updating also replaces the code of the ones already deployed; their settings, users and databases are untouched. Workers the store does not recognise are never touched."))
        Section(tr("داده‌ها", "Data"), rows.filter { it.item.kind == StoreKind.DATA }, jobs, onOpen)
        Section(tr("همراه برنامه", "With the app"), rows.filter { it.item.source is StoreSource.WithApp }, jobs, onOpen,
            footer = tr("اندروید اجازه نمی‌دهد این هسته‌ها در حین کار عوض شوند؛ با هر نسخهٔ تازهٔ برنامه بروز می‌شوند.",
                "Android does not allow these engines to be swapped while running; they update with each new version of the app."))
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun UpdatesPage(
    pending: List<StoreRow>,
    history: List<StoreHistory>,
    jobs: Map<String, StoreJob>,
    checking: Boolean,
    lastCheck: Long,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val all = jobs["all"]
    IosScreen(onBack = onBack, largeTitle = tr("بروزرسانی‌ها", "Updates")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (checking) tr("در حال بررسی…", "Checking…")
                else if (lastCheck > 0) tr("آخرین بررسی: ", "Last checked: ") + ago(lastCheck)
                else tr("هنوز بررسی نشده", "Not checked yet"),
                color = Ios.SecondaryLabel, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            if (checking) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Ios.SecondaryLabel, strokeWidth = 2.dp)
            } else {
                Pill(tr("بررسی", "Check"), onClick = { StoreManager.checkAll(context) })
            }
        }

        SettingsSectionHeader(
            if (pending.isEmpty()) tr("در انتظار", "Pending") else tr("در انتظار (${pending.size})", "Pending (${pending.size})")
        )
        if (pending.isEmpty()) {
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = Ios.Green, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tr("همه‌چیز بروز است.", "Everything is up to date."), color = Ios.Label, fontSize = 15.sp)
                }
            }
        } else {
            if (pending.any { it.item.kind != StoreKind.APP }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        all?.let { if (it.running) it.phase else (it.done ?: it.error ?: "") } ?: "",
                        color = Ios.SecondaryLabel, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Pill(
                        if (all?.running == true) tr("در حال بروزرسانی…", "Updating…") else tr("بروزرسانی همه", "Update All"),
                        prominent = true,
                        enabled = all?.running != true,
                        onClick = { StoreManager.updateAll(context) },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            SettingsGroup {
                pending.sortedBy { order(it.item) }.forEachIndexed { i, r ->
                    if (i > 0) Separator()
                    AppRow(r, jobs[r.item.id], onOpen = { onOpen(r.item.id) }, showNotes = true)
                }
            }
        }

        if (history.isNotEmpty()) {
            SettingsSectionHeader(tr("بروزرسانی‌های اخیر", "Recently updated"))
            SettingsGroup {
                history.take(12).forEachIndexed { i, h ->
                    if (i > 0) Separator()
                    HistoryRow(h, onOpen = { onOpen(h.id) })
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun ColumnScope.Section(
    title: String,
    rows: List<StoreRow>,
    jobs: Map<String, StoreJob>,
    onOpen: (String) -> Unit,
    footer: String? = null,
) {
    if (rows.isEmpty()) return
    SettingsSectionHeader(title)
    SettingsGroup {
        rows.forEachIndexed { i, r ->
            if (i > 0) Separator()
            AppRow(r, jobs[r.item.id], onOpen = { onOpen(r.item.id) })
        }
    }
    footer?.let { SettingsFooter(it) }
}

// ── the product page ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ProductPage(row: StoreRow, job: StoreJob?, onBack: () -> Unit) {
    val context = LocalContext.current
    val item = row.item
    var notesOpen by remember { mutableStateOf(false) }
    IosScreen(onBack = onBack, backLabel = tr("استور", "Store")) {
        // Header: the big icon, the name, who makes it, and the one button.
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StoreIconTile(item, 108.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title(), color = Ios.Label, fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
                Spacer(Modifier.height(2.dp))
                Text(item.subtitle(), color = Ios.SecondaryLabel, fontSize = 14.sp, maxLines = 2)
                Spacer(Modifier.height(12.dp))
                ActionButton(row, job, big = true)
            }
        }
        JobLine(job)

        // The strip of facts under the header, as the App Store draws ratings, age and size.
        InfoStrip(row)

        // What's new.
        row.candidate?.let { c ->
            SettingsSectionHeader(tr("تازه‌ها", "What's New"))
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("نسخهٔ ", "Version ") + c.version, color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (c.released.isNotBlank()) Text(c.released, color = Ios.SecondaryLabel, fontSize = 13.sp)
                    }
                    val notes = c.notes.ifBlank { tr("سازنده توضیحی برای این نسخه ننوشته است.", "The developer wrote no notes for this version.") }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        cleanNotes(notes),
                        color = Ios.Label.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 22.sp,
                        maxLines = if (notesOpen) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                    )
                    if (notes.lines().size > 5 || notes.length > 280) {
                        Text(
                            if (notesOpen) tr("کمتر", "less") else tr("بیشتر", "more"),
                            color = Ios.Blue, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp).clickable { notesOpen = !notesOpen },
                        )
                    }
                    if (row.item.kind == StoreKind.ENGINE && c.from == "developer" &&
                        com.mlmvpn.scanner.store.StoreVersions.major(c.version) != com.mlmvpn.scanner.store.StoreVersions.major(row.version) &&
                        row.version != null
                    ) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Warning, null, tint = Ios.Orange, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                tr("این یک نسخهٔ اصلی تازه است. اگر بعد از بروزرسانی اتصال مشکل داشت، «برگشت به نسخهٔ قبل» را بزنید.",
                                    "This is a new major version. If connecting misbehaves afterwards, use «Go back to the previous version»."),
                                color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 18.sp,
                            )
                        }
                    }
                }
            }
        }

        // Workers: every deployed copy on the user's accounts.
        if (item.kind == StoreKind.WORKER) {
            SettingsSectionHeader(tr("روی حساب‌های کلودفلر شما", "On your Cloudflare accounts"))
            SettingsGroup {
                if (row.deployments.isEmpty()) {
                    Text(
                        if (row.checkedAt == 0L) tr("برای دیدن ورکرهای مستقرشده «بررسی» را بزنید.", "Press Check to find deployed workers.")
                        else tr("روی حساب‌های ذخیره‌شده در برنامه، نمونه‌ای از این ورکر پیدا نشد.", "None of this worker was found on the accounts saved in the app."),
                        color = Ios.SecondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                    )
                } else {
                    row.deployments.forEachIndexed { i, d ->
                        if (i > 0) Separator()
                        DeploymentRow(item, d, job?.running == true)
                    }
                }
            }
            SettingsFooter(
                tr("فقط کد عوض می‌شود؛ متغیرها، رمزها، KV، دیتابیس و دامنه همان می‌مانند. کد قبلی روی گوشی نگه داشته می‌شود تا با یک دکمه برگردد.",
                    "Only the code changes; variables, secrets, KV, database and domain stay. The previous code is kept on the phone so one button puts it back.")
            )
        }

        if (item.about().isNotBlank()) {
            SettingsSectionHeader(tr("درباره", "About"))
            SettingsGroup {
                Text(item.about(), color = Ios.Label.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(16.dp))
            }
        }

        SettingsSectionHeader(tr("اطلاعات", "Information"))
        SettingsGroup {
            InfoRow(tr("سازنده", "Developer"), item.developer)
            Separator()
            InfoRow(tr("نسخهٔ در حال استفاده", "Version in use"), row.version ?: when (item.source) {
                is StoreSource.WithApp, is StoreSource.App -> tr("همراه برنامه", "Shipped with the app")
                else -> tr("همراه برنامه", "Shipped with the app")
            })
            if (row.fromStore) {
                Separator()
                InfoRow(tr("نصب از استور", "Installed from the store"), ago(row.installedAt))
            }
            row.shipped?.takeIf { it != row.version }?.let {
                Separator()
                InfoRow(tr("نسخهٔ همراه برنامه", "Shipped version"), it)
            }
            row.upstream?.let {
                Separator()
                InfoRow(tr("آخرین نسخهٔ سازنده (گیت‌هاب)", "Developer's newest (GitHub)"), it)
            }
            Separator()
            InfoRow(tr("منبع", "Source"), sourceLabel(item, row))
            if (row.checkedAt > 0) {
                Separator()
                InfoRow(tr("آخرین بررسی", "Last checked"), ago(row.checkedAt))
            }
        }

        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            item.repo?.let { repo ->
                SettingsActionRow(label = tr("مشاهده در گیت‌هاب", "View on GitHub"), icon = Icons.Default.OpenInNew) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.candidate?.url ?: "https://github.com/$repo"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            if (row.canRollback) {
                if (item.repo != null) Separator()
                SettingsActionRow(
                    label = tr("برگشت به نسخهٔ قبل", "Go back to the previous version"),
                    icon = Icons.Default.Replay,
                    tint = Ios.Destructive,
                    busy = job?.running == true,
                    onClick = { StoreManager.rollback(context, item.id) },
                )
            }
            if (item.kind != StoreKind.APP) {
                if (item.repo != null || row.canRollback) Separator()
                SettingsActionRow(label = tr("بررسی دوباره", "Check again"), icon = Icons.Default.Refresh) {
                    StoreManager.checkAll(context)
                }
            }
        }
        row.error?.let {
            SettingsFooter(tr("آخرین بررسی: ", "Last check: ") + it)
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun DeploymentRow(item: StoreItem, d: StoreRow.Deployment, busy: Boolean) {
    val context = LocalContext.current
    val canBack = remember(d, busy) { StoreManager.hasWorkerBackup(context, d.accountId, d.script) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(9.dp).clip(CircleShape)
                .background(if (d.behind) Ios.Orange else Ios.Green)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.accountName.ifBlank { d.accountId.take(8) }, color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                d.script + (d.version?.let { " · $it" } ?: "") + " · " +
                    if (d.behind) tr("قدیمی", "behind") else tr("بروز", "up to date"),
                color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (canBack && !busy) {
            Text(
                tr("برگشت", "Undo"), color = Ios.Destructive, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable { StoreManager.rollbackWorker(context, item.id, d.accountId, d.script) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

// ── rows and cards ───────────────────────────────────────────────────────────────────────────

/** One App Store row: icon, name, what uses it, and the pill. */
@Composable
private fun AppRow(row: StoreRow, job: StoreJob?, onOpen: () -> Unit, showNotes: Boolean = false) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StoreIconTile(row.item, 54.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.item.title(), color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.item.subtitle(), color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(versionLine(row), color = Ios.SecondaryLabel.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            ActionButton(row, job)
        }
        if (job != null && !job.running && (job.error != null)) {
            Text(job.error, color = Ios.Destructive, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 66.dp))
        }
        if (showNotes) {
            row.candidate?.notes?.takeIf { it.isNotBlank() }?.let {
                Text(cleanNotes(it), color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, start = 66.dp))
            }
        }
    }
}

/**
 * The button: «بروزرسانی» when there is something to install, a progress ring while it installs,
 * and a quiet label when there is nothing to do. The App Store's GET / UPDATE / OPEN, in Persian.
 */
@Composable
private fun ActionButton(row: StoreRow, job: StoreJob?, big: Boolean = false) {
    val context = LocalContext.current
    if (job?.running == true) {
        // Tapping the ring pauses: what arrived is kept, and «ادامه» continues from there.
        ProgressRing(job.progress, size = if (big) 34.dp else 30.dp, onStop = { StoreManager.pause(row.item.id) })
        return
    }
    when {
        job?.paused == true -> Pill(
            tr("ادامه", "Resume"), prominent = true, big = big,
            onClick = { StoreManager.resume(context, row.item.id) },
        )
        row.hasUpdate -> Pill(
            tr("بروزرسانی", "Update"), prominent = true, big = big,
            onClick = { StoreManager.install(context, row.item.id) },
        )
        row.state == StoreRow.State.WITH_APP -> Pill(tr("با برنامه", "With app"), enabled = false, big = big) {}
        row.state == StoreRow.State.UNCHECKED -> Pill(tr("بررسی", "Check"), big = big) { StoreManager.checkAll(context) }
        row.item.kind == StoreKind.WORKER && row.deployments.isEmpty() && row.state == StoreRow.State.CURRENT ->
            Pill(tr("بروز", "Current"), enabled = false, big = big) {}
        else -> Pill(tr("بروز", "Current"), enabled = false, big = big) {}
    }
}

@Composable
private fun Pill(label: String, prominent: Boolean = false, enabled: Boolean = true, big: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        !enabled -> Color.White.copy(alpha = 0.10f)
        prominent -> Ios.Blue
        else -> Color.White.copy(alpha = 0.16f)
    }
    val fg = when {
        !enabled -> Ios.SecondaryLabel
        else -> Color.White
    }
    Box(
        Modifier
            .heightIn(min = if (big) 32.dp else 28.dp)
            .widthIn(min = if (big) 96.dp else 74.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = if (big) 15.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** iOS's download ring: a track, the arc of progress, and a stop square in the middle. */
@Composable
private fun ProgressRing(progress: Float?, size: Dp, onStop: () -> Unit) {
    val blue = Ios.Blue
    val track = Color.White.copy(alpha = 0.18f)
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onStop),
        contentAlignment = Alignment.Center,
    ) {
        if (progress == null) {
            CircularProgressIndicator(Modifier.size(size), color = blue, strokeWidth = 2.5.dp)
        } else {
            Canvas(Modifier.size(size)) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(blue, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(size * 0.08f)) {
            Box(Modifier.size(width = size * 0.09f, height = size * 0.3f).clip(RoundedCornerShape(1.dp)).background(blue))
            Box(Modifier.size(width = size * 0.09f, height = size * 0.3f).clip(RoundedCornerShape(1.dp)).background(blue))
        }
    }
}

@Composable
private fun JobLine(job: StoreJob?) {
    job ?: return
    val text = when {
        job.running && job.bytes > 0 -> job.phase + "  " + transferText(job)
        job.running -> job.phase + (job.progress?.let { "  ${faNum((it * 100).toInt().toString())}٪" } ?: "")
        job.paused -> tr("متوقف شد", "Paused") + (if (job.bytes > 0) " · " + sizeText(job.bytes, job.total) else "")
        job.error != null -> job.error
        else -> job.done ?: ""
    }
    if (text.isBlank()) return
    Text(
        text,
        color = when {
            job.error != null -> Ios.Destructive
            job.running || job.paused -> Ios.SecondaryLabel
            else -> Ios.Green
        },
        fontSize = 13.sp, lineHeight = 19.sp,
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp),
    )
    // Pausing keeps the part on disk; this is the way to drop it.
    if (job.paused || job.running) {
        Text(
            tr("لغو دانلود", "Cancel download"),
            color = Ios.Destructive, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)
                .clickable { StoreManager.cancel(job.id) },
        )
    }
}

/** "12.3 of 45.0 MB · 1.2 MB/s · 20 s left". */
private fun transferText(job: StoreJob): String {
    val parts = mutableListOf(sizeText(job.bytes, job.total))
    if (job.speedBps > 0) {
        parts += mb(job.speedBps) + tr(" مگ/ث", " MB/s")
        if (job.total > job.bytes) {
            val left = (job.total - job.bytes) / job.speedBps
            parts += if (left < 60) tr("${faNum(left.toString())} ثانیه مانده", "${left}s left")
            else tr("${faNum((left / 60).toString())} دقیقه مانده", "${left / 60} min left")
        }
    }
    return parts.joinToString(" · ")
}

private fun sizeText(bytes: Long, total: Long): String =
    if (total > 0) tr("${mb(bytes)} از ${mb(total)} مگ", "${mb(bytes)} of ${mb(total)} MB") else tr("${mb(bytes)} مگ", "${mb(bytes)} MB")

private fun mb(bytes: Long): String = faNum(String.format(java.util.Locale.US, "%.1f", bytes / 1_048_576.0))

private fun faNum(s: String): String = tr(com.mlmvpn.scanner.ui.emergency.faDigits(s), s)

@Composable
private fun FeatureCard(row: StoreRow, job: StoreJob?, onOpen: () -> Unit) {
    val icon = row.item.icon
    val top = if (row.item.id == "mlmvpn") Color(0xFF18BFFB) else lerp(icon.tint, Color.White, 0.10f)
    val bottom = if (row.item.id == "mlmvpn") Color(0xFF2072F3) else lerp(icon.tint2 ?: icon.tint, Color.Black, 0.45f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(14.dp, CardShape, clip = false)
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .clickable(onClick = onOpen)
            .padding(20.dp),
    ) {
        Text(
            if (row.item.kind == StoreKind.APP) tr("نسخهٔ تازهٔ برنامه", "A NEW VERSION OF THE APP") else tr("بروزرسانی تازه", "NEW UPDATE"),
            color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(row.item.title(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
        row.candidate?.let {
            Text(
                tr("نسخهٔ ${it.version}", "Version ${it.version}") + (row.version?.let { v -> tr(" — الان $v", " — now $v") } ?: ""),
                color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(18.dp))
        row.candidate?.notes?.takeIf { it.isNotBlank() }?.let {
            Text(cleanNotes(it), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.22f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StoreIconTile(row.item, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(row.item.title(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.item.developer, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
            }
            ActionButton(row, job)
        }
    }
}

@Composable
private fun AllCurrentCard(checking: Boolean, lastCheck: Long) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(14.dp, CardShape, clip = false)
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF18BFFB), Color(0xFF2072F3))))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_app_store), null, Modifier.size(84.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            if (checking) tr("در حال پرسیدن از سازنده‌ها…", "Asking the developers…")
            else if (lastCheck == 0L) tr("به استور خوش آمدید", "Welcome to the Store")
            else tr("همه‌چیز بروز است", "Everything is up to date"),
            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("برنامه، هسته‌ها، ورکرهای روی حساب‌های شما و داده‌ها — همه از یک جا، مستقیم از گیت‌هاب سازنده‌ها.",
                "The app, its engines, the workers on your accounts and the data — all from one place, straight from the developers' GitHub."),
            color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (checking) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
        } else {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { StoreManager.checkAll(context) }
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            ) {
                Text(tr("بررسی بروزرسانی‌ها", "Check for updates"), color = Color(0xFF2072F3), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            if (lastCheck > 0) {
                Spacer(Modifier.height(8.dp))
                Text(tr("آخرین بررسی: ", "Last checked: ") + ago(lastCheck), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatsCard(rows: List<StoreRow>) {
    val engines = rows.filter { it.item.kind == StoreKind.ENGINE }
    val fromStore = rows.count { it.fromStore }
    val deployed = rows.sumOf { it.deployments.size }
    val pending = rows.count { it.hasUpdate }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("${engines.size}", tr("هسته", "engines"))
            Stat("$deployed", tr("ورکر مستقر", "deployed workers"))
            Stat("$fromStore", tr("از استور", "from the store"))
            Stat("$pending", tr("در انتظار", "pending"), if (pending > 0) Ios.Orange else Ios.Label)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color = Ios.Label) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Ios.SecondaryLabel, fontSize = 11.sp)
    }
}

@Composable
private fun HistoryRow(h: StoreHistory, onOpen: () -> Unit) {
    val item = StoreCatalog.byId(h.id)
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item != null) StoreIconTile(item, 40.dp) else Spacer(Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.title, color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (h.from?.let { "$it ← " } ?: "") + h.to,
                color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(ago(h.at), color = Ios.SecondaryLabel, fontSize = 12.sp)
    }
}

@Composable
private fun InfoStrip(row: StoreRow) {
    val c = row.candidate
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .frostedGlass(RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // edgetunnel versions are timestamps; the strip has room for the date only.
        fun short(v: String?) = v?.substringBefore(' ') ?: "—"
        Fact(tr("نسخه", "VERSION"), short(row.version))
        Fact(tr("تازه‌ترین", "LATEST"), short(c?.version ?: row.version))
        Fact(tr("اندازه", "SIZE"), c?.artifacts?.sumOf { it.size }?.takeIf { it > 0 }?.let { size(it) } ?: "—")
        Fact(tr("منبع", "FROM"), when (c?.from ?: "") {
            "developer" -> tr("سازنده", "Developer")
            "channel" -> tr("کانال امضاشده", "Signed")
            else -> when (row.item.source) {
                is StoreSource.WithApp, is StoreSource.App -> tr("برنامه", "App")
                is StoreSource.Channel -> tr("کانال", "Channel")
                else -> tr("گیت‌هاب", "GitHub")
            }
        })
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 96.dp)) {
        Text(label, color = Ios.SecondaryLabel, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ios.SecondaryLabel, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ios.Label, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 220.dp))
    }
}

/** The glass tab bar along the bottom, as the App Store has: Today, Apps, Updates. */
@Composable
private fun StoreTabBar(tab: StoreTab, badge: Int, onSelect: (StoreTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .frostedGlass(RoundedCornerShape(30.dp))
            .padding(vertical = 8.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TabItem(Icons.Default.Today, tr("امروز", "Today"), tab == StoreTab.TODAY, 0) { onSelect(StoreTab.TODAY) }
        TabItem(Icons.Default.Apps, tr("همه", "Apps"), tab == StoreTab.APPS, 0) { onSelect(StoreTab.APPS) }
        TabItem(Icons.Default.SystemUpdate, tr("بروزرسانی‌ها", "Updates"), tab == StoreTab.UPDATES, badge) { onSelect(StoreTab.UPDATES) }
    }
}

@Composable
private fun TabItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val color = if (selected) Ios.Blue else Ios.SecondaryLabel
    Column(
        Modifier.clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).graphicsLayer { translationX = 16f; translationY = -10f }
                        .size(17.dp).clip(CircleShape).background(Ios.Red),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── the icon ─────────────────────────────────────────────────────────────────────────────────

/** An item's tile: the app's own icon, finished artwork, or a white glyph on a tinted squircle. */
@Composable
fun StoreIconTile(item: StoreItem, size: Dp) {
    val icon = item.icon
    when {
        item.id == "mlmvpn" -> Box(Modifier.size(size).shadow(4.dp, Squircle, clip = false).clip(Squircle)) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size(size))
            Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.size(size).graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
        }
        icon.imageRes != null -> Image(
            painterResource(icon.imageRes), null,
            Modifier.size(size)
                // The ic_app_* artwork insets itself to 90% like a shipped iOS icon asset.
                .graphicsLayer { scaleX = 100f / 90f; scaleY = 100f / 90f }
                .shadow(4.dp, Squircle, clip = false),
        )
        else -> Box(
            Modifier.size(size).shadow(4.dp, Squircle, clip = false).clip(Squircle)
                .background(Brush.verticalGradient(listOf(lerp(icon.tint, Color.White, 0.16f), lerp(icon.tint2 ?: icon.tint, Color.Black, 0.18f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon.glyph, null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
        }
    }
}

// ── words ────────────────────────────────────────────────────────────────────────────────────

private fun order(item: StoreItem): Int = when (item.kind) {
    StoreKind.APP -> 0
    StoreKind.ENGINE -> 1
    StoreKind.WORKER -> 2
    StoreKind.DATA -> 3
}

private fun versionLine(row: StoreRow): String {
    val now = row.version ?: tr("همراه برنامه", "shipped")
    val next = row.candidate?.version
    val behind = row.deployments.count { it.behind }
    return when {
        row.state == StoreRow.State.WITH_APP -> listOfNotNull(
            row.version,
            row.upstream?.takeIf { it != row.version }?.let { tr("سازنده: $it", "developer: $it") },
        ).joinToString(" · ").ifBlank { tr("با بروزرسانی برنامه", "Updates with the app") }
        // The arrow follows the reading direction: new version last, whichever way that is.
        row.state == StoreRow.State.UPDATE && next != null -> tr("$now  ←  $next", "$now  →  $next")
        behind > 0 -> tr("$behind ورکر روی حساب‌ها قدیمی است", "$behind deployed worker(s) behind")
        row.item.kind == StoreKind.WORKER && row.deployments.isNotEmpty() ->
            tr("$now · ${row.deployments.size} ورکر مستقر", "$now · ${row.deployments.size} deployed")
        else -> now
    }
}

private fun sourceLabel(item: StoreItem, row: StoreRow): String = when (val s = item.source) {
    is StoreSource.GithubRelease -> tr("انتشارهای ${s.repo}", "${s.repo} releases")
    is StoreSource.GithubFile -> "${s.repo} · ${s.path}"
    is StoreSource.GithubFiles -> "${s.repo} · " + s.paths.joinToString(" + ")
    is StoreSource.SignedManifest -> tr("فهرست نسخه‌های امضاشدهٔ سازنده", "The developer's signed release list")
    is StoreSource.Channel -> tr("کانال امضاشدهٔ MLM VPN", "MLM VPN signed channel")
    is StoreSource.WithApp -> tr("همراه برنامه", "With the app")
    is StoreSource.App -> item.repo ?: ""
}

private fun size(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** Release notes arrive as GitHub markdown; the headings' hashes and the bold stars are noise here. */
private fun cleanNotes(s: String): String = s.lines()
    .map { it.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim().replace("**", "").replace("`", "") }
    .filter { it.isNotBlank() }
    .joinToString("\n")
    .take(4000)

private fun ago(at: Long): String {
    if (at <= 0) return "—"
    val d = (System.currentTimeMillis() - at).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> tr("همین الان", "just now")
        d < 3600 -> tr("${d / 60} دقیقه پیش", "${d / 60} min ago")
        d < 86_400 -> tr("${d / 3600} ساعت پیش", "${d / 3600} h ago")
        d < 30 * 86_400 -> tr("${d / 86_400} روز پیش", "${d / 86_400} d ago")
        else -> java.text.DateFormat.getDateInstance().format(java.util.Date(at))
    }
}

private fun todayLine(): String = runCatching {
    if (com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()) {
        android.icu.text.SimpleDateFormat("EEEE d MMMM", android.icu.util.ULocale("fa_IR@calendar=persian")).format(java.util.Date())
    } else {
        android.icu.text.SimpleDateFormat("EEEE, MMMM d", android.icu.util.ULocale.ENGLISH).format(java.util.Date())
    }
}.getOrDefault("")
