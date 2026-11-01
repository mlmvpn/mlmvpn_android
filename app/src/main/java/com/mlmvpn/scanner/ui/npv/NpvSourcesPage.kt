package com.mlmvpn.scanner.ui.npv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.ScanGuard
import com.mlmvpn.scanner.engines.npv.NpvEngine
import com.mlmvpn.scanner.engines.npv.NpvLibrary
import com.mlmvpn.scanner.engines.npv.NpvLibrary.Kind
import com.mlmvpn.scanner.engines.npv.NpvLibrary.Shelf
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.amnezia.ActionSheet
import com.mlmvpn.scanner.ui.amnezia.fa
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «منبع کانفیگ‌ها»: «بررسی کانفیگ‌های تازه» with its four numbered steps, the user's own channels
 * and the default ones, then «انبار» -- everything the sources brought in, measured and pruned
 * here; the working ones are ticked and sent to the main page, or connected right here.
 */
@Composable
internal fun NpvSourcesPage(
    entries: List<NpvLibrary.Entry>,
    live: Map<String, Long>,
    activeId: String?,
    onBack: () -> Unit,
    onConnect: (NpvLibrary.Entry) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val refresh by NpvEngine.refresh.collectAsState()
    val measure by NpvEngine.measuring.collectAsState()
    val rev by NpvEngine.revision.collectAsState()
    var states by remember { mutableStateOf<Map<String, NpvEngine.ChannelState>>(emptyMap()) }
    var mine by remember { mutableStateOf<List<String>>(emptyList()) }
    var defaults by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(rev, refresh.finishedAt) {
        states = withContext(Dispatchers.IO) { NpvEngine.channelStates(ctx) }
        mine = withContext(Dispatchers.IO) { NpvEngine.userChannels(ctx) }
        defaults = withContext(Dispatchers.IO) { NpvEngine.defaultChannels(ctx) }
    }
    // Held still while a measurement runs (see MainPage); sorted fastest first once it is written back.
    val store = remember(entries) { NpvLibrary.byDelay(entries.filter { it.shelf == Shelf.STORE && it.kind == Kind.LINK }) }
    val files = remember(entries) { entries.filter { it.kind == Kind.REMOTE }.sortedByDescending { it.addedAt } }
    var selected by remember { mutableStateOf(setOf<String>()) }
    // A tick never outlives its row (deleted, moved, pruned).
    LaunchedEffect(store) {
        val ids = store.map { it.id }.toHashSet()
        if (selected.any { it !in ids }) selected = selected.filter { it in ids }.toSet()
    }
    var shown by remember { mutableIntStateOf(100) }
    var filesShown by remember { mutableIntStateOf(40) }
    var showDefaults by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<NpvLibrary.Entry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val healthyIds = store.filter { (live[it.id] ?: it.ping) > 0 }.map { it.id }
    val broken = store.count { (live[it.id] ?: it.ping) < 0 }
    val storeBusy = (measure.running && measure.shelf == Shelf.STORE) || (refresh.running && refresh.step == NpvEngine.Step.TEST)

    IosScreen(title = tr("منبع کانفیگ‌ها", "Config sources"), onBack = onBack, backLabel = stringResource(R.string.home_npv), scrollable = false) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "refresh") {
                RefreshCard(refresh, defaults.size) { ScanGuard.run(ScanGuard.Reason.DELAY_TEST) { NpvEngine.startRefresh(ctx) } }
            }
            item(key = "add") {
                AddChannel { text ->
                    val ch = NpvEngine.channelOf(text)
                    if (ch == null) {
                        Toast.makeText(ctx, tr("نشانی کانال درست نیست", "Not a channel address"), Toast.LENGTH_SHORT).show()
                        false
                    } else {
                        scope.io { NpvEngine.addChannel(ctx, text) }
                        Toast.makeText(ctx, tr("@$ch اضافه شد؛ با «بررسی کانفیگ‌های تازه» خوانده می‌شود", "@$ch added; «Check for new configs» reads it"), Toast.LENGTH_LONG).show()
                        true
                    }
                }
            }
            item(key = "mine") {
                Column {
                    SettingsSectionHeader(tr("کانال‌های شما (${fa(mine.size)})", "Your channels (${mine.size})"))
                    SettingsGroup {
                        if (mine.isEmpty()) {
                            Text(
                                tr("هنوز کانالی اضافه نکرده‌اید.", "You have not added a channel yet."),
                                color = Ios.SecondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                            )
                        }
                        mine.forEachIndexed { i, ch ->
                            if (i > 0) Separator()
                            ChannelRow(ch, states[ch.lowercase()], onRemove = { scope.io { NpvEngine.removeChannel(ctx, ch) } })
                        }
                    }
                }
            }
            item(key = "defaults") {
                val withNew = defaults.count { (states[it.lowercase()]?.newAt ?: 0L) > 0 && states[it.lowercase()]?.newAt == states[it.lowercase()]?.checkedAt }
                val checked = defaults.mapNotNull { states[it.lowercase()]?.checkedAt }.maxOrNull() ?: 0L
                SettingsGroup(Modifier.padding(top = 12.dp)) {
                    SettingsRow(
                        title = tr("کانال‌های پیش‌فرض (${fa(defaults.size)})", "Default channels (${defaults.size})"),
                        subtitle = if (checked == 0L) tr("هنوز بررسی نشده", "Not checked yet")
                            else tr("آخرین بررسی ${ago(checked)} · ${fa(withNew)} کانال پست تازه داشت", "Checked ${ago(checked)} · $withNew had new posts"),
                        icon = Icons.Default.Send, tint = Ios.Teal,
                        value = if (showDefaults) tr("بستن", "Hide") else tr("نمایش", "Show"),
                        showChevron = false,
                        onClick = { showDefaults = !showDefaults },
                    )
                    if (showDefaults) defaults.forEach { ch ->
                        Separator()
                        ChannelRow(ch, states[ch.lowercase()], onRemove = null)
                    }
                }
            }
            item(key = "store-tools") {
                Column {
                    SettingsSectionHeader(tr("انبار (${fa(store.size)})", "Store (${store.size})"))
                    SettingsGroup {
                        MeasureRow(Shelf.STORE, store.size, measure) { ScanGuard.run(ScanGuard.Reason.DELAY_TEST) { NpvEngine.measure(ctx, Shelf.STORE) } }
                        if (broken > 0 && !storeBusy) {
                            Separator()
                            SettingsActionRow(label = tr("حذف خراب‌ها (${fa(broken)})", "Delete the broken ones ($broken)"), icon = Icons.Default.DeleteSweep, tint = Ios.Red) {
                                scope.io { NpvEngine.removeBroken(ctx, Shelf.STORE) }
                            }
                        }
                        if (healthyIds.isNotEmpty()) {
                            val all = selected.containsAll(healthyIds)
                            Separator()
                            SettingsActionRow(
                                label = if (all) tr("برداشتن علامت‌ها", "Clear the ticks") else tr("علامت زدن همهٔ سالم‌ها (${fa(healthyIds.size)})", "Tick every working one (${healthyIds.size})"),
                                icon = Icons.Default.CheckCircle, tint = Ios.Green,
                            ) { selected = if (all) emptySet() else healthyIds.toSet() }
                        }
                        if (selected.isNotEmpty()) {
                            Separator()
                            SettingsActionRow(
                                label = tr("فرستادن ${fa(selected.size)} کانفیگ علامت‌خورده به صفحهٔ اصلی", "Send the ${selected.size} ticked to the main page"),
                                icon = Icons.Default.Home, tint = Ios.Blue,
                            ) {
                                val ids = selected
                                selected = emptySet()
                                scope.io { NpvEngine.move(ctx, ids, Shelf.MAIN) }
                                Toast.makeText(ctx, tr("${fa(ids.size)} کانفیگ به صفحهٔ اصلی رفت", "${ids.size} configs sent to the main page"), Toast.LENGTH_SHORT).show()
                            }
                        }
                        if (store.isNotEmpty()) {
                            Separator()
                            SettingsActionRow(label = tr("حذف همهٔ انبار", "Empty the store"), icon = Icons.Default.DeleteForever, tint = Ios.Red) { confirmClear = true }
                        }
                    }
                    if (store.isEmpty()) {
                        Text(
                            tr("انبار خالی است. «بررسی کانفیگ‌های تازه» را بزنید.", "The store is empty. Tap «Check for new configs»."),
                            color = Ios.SecondaryLabel, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
                        )
                    }
                }
            }
            val chunks = store.take(shown).chunked(25)
            items(chunks.size, key = { "store$it" }) { ci ->
                SettingsGroup(Modifier.padding(top = if (ci == 0) 12.dp else 8.dp)) {
                    chunks[ci].forEachIndexed { i, e ->
                        if (i > 0) Separator()
                        ConfigRow(
                            e, live[e.id] ?: e.ping, active = e.id == activeId,
                            checked = e.id in selected,
                            onCheck = { selected = if (e.id in selected) selected - e.id else selected + e.id },
                            onClick = { menuFor = e },
                        )
                    }
                }
            }
            if (store.size > shown) item(key = "store-more") {
                SettingsGroup(Modifier.padding(top = 8.dp)) {
                    SettingsActionRow(label = tr("نمایش بیشتر (${fa(store.size - shown)})", "Show more (${store.size - shown})"), icon = Icons.Default.Description) { shown += 100 }
                }
            }
            item(key = "store-foot") {
                SettingsFooter(tr(
                    "انبار همهٔ کانفیگ‌هایی است که از منبع‌ها آمده. «سنجش» همه را روی اینترنت شما واقعاً می‌آزماید و سریع‌ترین‌ها را بالا می‌آورد؛ بعد «حذف خراب‌ها» را بزنید. سالم‌ها را علامت بزنید و به صفحهٔ اصلی بفرستید، یا با لمس هر کدام همین‌جا وصل شوید.",
                    "The store holds everything the sources brought in. «Measure» really tests them all on your internet and brings the fastest up; then tap «Delete the broken ones». Tick the working ones and send them to the main page, or tap one to connect it right here.",
                ))
            }
            if (files.isNotEmpty()) {
                item(key = "files-h") { SettingsSectionHeader(tr("فایل‌های کانال‌ها (${fa(files.size)})", "Files in the channels (${files.size})")) }
                val fileChunks = files.take(filesShown).chunked(20)
                items(fileChunks.size, key = { "files$it" }) { ci ->
                    SettingsGroup(Modifier.padding(top = if (ci == 0) 0.dp else 8.dp)) {
                        fileChunks[ci].forEachIndexed { i, e ->
                            if (i > 0) Separator()
                            FileRow(e)
                        }
                    }
                }
                if (files.size > filesShown) item(key = "files-more") {
                    SettingsGroup(Modifier.padding(top = 8.dp)) {
                        SettingsActionRow(label = tr("نمایش بیشتر (${fa(files.size - filesShown)})", "Show more (${files.size - filesShown})"), icon = Icons.Default.Description) { filesShown += 40 }
                    }
                }
                item(key = "files-foot") {
                    SettingsFooter(tr(
                        "تلگرام خود فایل را فقط به کسی می‌دهد که وارد حساب تلگرامش شده باشد، برای همین دریافت خودکار فایل‌ها به ورود به تلگرام در همین برنامه نیاز دارد که هنوز آماده نیست. فعلاً لمس هر فایل پستش را در تلگرام باز می‌کند؛ فایل را آنجا بگیرید و «باز کردن با MLMVPN» را بزنید: فایل متنی خوانده می‌شود و کانفیگ‌هایش می‌آیند. فایل‌های npvt و npvs با کلید برنامهٔ NPV Tunnel رمز شده‌اند و فقط در همان برنامه اجرا می‌شوند.",
                        "Telegram gives the file itself only to someone signed in to Telegram, so fetching files on their own needs a Telegram sign-in inside this app, which is not ready yet. For now a tap opens the post in Telegram; get the file there and use «Open with MLMVPN»: a text file is read and its configs come in. npvt and npvs files are encrypted with NPV Tunnel's key and run only in that app.",
                    ))
                }
            }
        }
    }

    menuFor?.let { e ->
        ActionSheet(title = e.name, onDismiss = { menuFor = null }) {
            SettingsActionRow(label = tr("اتصال", "Connect"), icon = Icons.Default.PowerSettingsNew, tint = Ios.Green) { menuFor = null; onConnect(e) }
            Separator()
            SettingsActionRow(label = tr("فرستادن به صفحهٔ اصلی", "Send to the main page"), icon = Icons.Default.Home, tint = Ios.Blue) {
                menuFor = null; scope.io { NpvEngine.move(ctx, listOf(e.id), Shelf.MAIN) }
            }
            Separator()
            SettingsActionRow(label = tr("حذف", "Delete"), icon = Icons.Default.Delete, tint = Ios.Red) {
                menuFor = null; scope.io { NpvEngine.remove(ctx, listOf(e.id)) }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("همهٔ انبار حذف شود؟", "Empty the whole store?")) },
            text = { Text(tr("${fa(store.size)} کانفیگ حذف می‌شود. کانفیگ‌های صفحهٔ اصلی می‌مانند.", "${store.size} configs will be deleted. The main page keeps its own.")) },
            confirmButton = { TextButton(onClick = { confirmClear = false; scope.io { NpvEngine.clear(ctx, Shelf.STORE) } }) { Text(tr("حذف همه", "Delete all"), color = Ios.Red) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("لغو", "Cancel")) } },
        )
    }
}

// ------------------------------------------------------------------------------ «بررسی کانفیگ‌های تازه»

private enum class StepState { WAITING, ACTIVE, DONE, SKIPPED, FAILED }

@Composable
private fun RefreshCard(r: NpvEngine.Refresh, defaults: Int, onStart: () -> Unit) {
    Column {
        SettingsGroup(Modifier.padding(top = 8.dp)) {
            if (!r.running) {
                SettingsActionRow(label = tr("بررسی کانفیگ‌های تازه", "Check for new configs"), icon = Icons.Default.Sync, tint = Ios.Orange, onClick = onStart)
            }
            if (r.running || r.outcome != null) {
                if (!r.running) Separator()
                Steps(r)
            }
        }
        SettingsFooter(tr(
            "${fa(defaults)} کانال پیش‌فرض، کانال‌های خودتان و فهرست‌های عمومی: اول رسیدن به تلگرام سنجیده می‌شود، بعد کانال‌هایی که پست تازه دارند پیدا و دریافت می‌شوند؛ از بار دوم فقط پست‌های تازه خوانده می‌شود. تلگرام در ایران فیلتر است: اگر نرسید، یکی از کانفیگ‌های همین بخش، موتور دیگری از برنامه یا یک VPN دیگر را روشن کنید.",
            "$defaults default channels, your own and the public lists: first Telegram is reached, then the channels with new posts are found and read; from the second time on only the new posts are read. Telegram is filtered in Iran: if it is not reached, connect one of this section's configs, another engine of the app, or another VPN.",
        ))
    }
}

@Composable
private fun Steps(r: NpvEngine.Refresh) {
    val noTelegram = r.outcome == NpvEngine.Outcome.NO_TELEGRAM
    // Past every step once it has finished; otherwise the one it is on.
    val at = if (r.running) r.step.ordinal else 4
    fun state(i: Int) = when {
        i < at -> StepState.DONE
        i == at -> StepState.ACTIVE
        else -> StepState.WAITING
    }
    fun frac(done: Int, total: Int) = if (total > 0) done.toFloat() / total else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepLine(
            1, tr("رسیدن به تلگرام", "Reaching Telegram"),
            if (noTelegram) StepState.FAILED else state(0), null,
            when {
                noTelegram && r.tunnelNoDoor -> tr(
                    "نرسید. تونلِ روشن راهی برای خود برنامه باز نمی‌کند؛ یکی از کانفیگ‌های همین بخش یا یک VPN دیگر را وصل کنید و دوباره بزنید.",
                    "Not reached. The tunnel that is on gives the app itself no way in; connect one of this section's configs or another VPN, and try again.",
                )
                noTelegram -> tr(
                    "نرسید؛ تلگرام فیلتر است. یک تونل روشن کنید (یکی از کانفیگ‌های همین بخش، موتور دیگری از برنامه یا یک VPN دیگر) و دوباره بزنید. فهرست‌های فعلی دست نخوردند.",
                    "Not reached; Telegram is filtered. Turn a tunnel on (one of this section's configs, another engine of the app, or another VPN) and try again. Nothing was changed.",
                )
                r.running && at == 0 -> tr("در حال بررسی…", "Checking…")
                else -> when (r.route) {
                    NpvEngine.Route.DIRECT -> tr("رسید، مستقیم", "Reached, directly")
                    NpvEngine.Route.OTHER_VPN -> tr("رسید، از راه VPN دیگر", "Reached, through another VPN")
                    NpvEngine.Route.APP_TUNNEL -> tr("رسید، از راه تونل همین برنامه", "Reached, through this app's tunnel")
                    null -> ""
                }
            },
        )
        if (noTelegram) return@Column
        StepLine(
            2, tr("بررسی کانال‌ها", "Checking the channels"), state(1), frac(r.channelsDone, r.channelsTotal),
            if (at < 1) "" else tr(
                "${fa(r.channelsDone)} از ${fa(r.channelsTotal)} کانال · ${fa(r.channelsNew)} کانال پست تازه دارد · ${fa(r.foundConfigs)} کانفیگ · ${fa(r.foundFiles)} فایل" +
                    if (r.channelsFailed > 0) " · ${fa(r.channelsFailed)} کانال باز نشد" else "",
                "${r.channelsDone} of ${r.channelsTotal} channels · ${r.channelsNew} with new posts · ${r.foundConfigs} configs · ${r.foundFiles} files" +
                    if (r.channelsFailed > 0) " · ${r.channelsFailed} did not open" else "",
            ),
        )
        StepLine(
            3, tr("دریافت کانفیگ‌ها و فایل‌ها", "Downloading configs and files"), state(2), frac(r.fetchDone, r.fetchTotal),
            when {
                at < 2 -> ""
                at == 2 -> tr("${fa(r.fetchDone)} از ${fa(r.fetchTotal)}", "${r.fetchDone} of ${r.fetchTotal}")
                else -> tr("${fa(r.added)} کانفیگ تازه به انبار رفت · ${fa(r.addedFiles)} فایل تازه", "${r.added} new configs into the store · ${r.addedFiles} new files")
            },
        )
        val skipped = !r.running && r.outcome != NpvEngine.Outcome.TESTED
        StepLine(
            4, tr("آزمودن و حذف خراب‌ها", "Testing, deleting the broken ones"),
            when {
                r.running && r.ask != NpvEngine.Ask.NONE -> StepState.ACTIVE
                r.running && at == 3 -> StepState.ACTIVE
                r.outcome == NpvEngine.Outcome.TESTED -> StepState.DONE
                skipped -> StepState.SKIPPED
                else -> StepState.WAITING
            },
            frac(r.testDone, r.testTotal),
            when {
                r.ask == NpvEngine.Ask.TUNNEL -> tr("منتظر جواب شما: تونل خاموش شود؟", "Waiting for you: turn the tunnel off?")
                r.ask == NpvEngine.Ask.TEST -> tr("منتظر جواب شما: همه آزموده شوند؟", "Waiting for you: test them all?")
                r.running && at == 3 -> tr("${fa(r.testDone)} از ${fa(r.testTotal)} · ${fa(r.working)} سالم", "${r.testDone} of ${r.testTotal} · ${r.working} working")
                r.outcome == NpvEngine.Outcome.TESTED -> tr("${fa(r.working)} سالم ماند · ${fa(r.removed)} خراب حذف شد", "${r.working} kept · ${r.removed} broken ones deleted")
                r.outcome == NpvEngine.Outcome.STORED -> tr("آزموده نشد؛ در انبار ماند. هر وقت خواستید «سنجش» بزنید.", "Not tested; kept in the store. Tap «Measure» whenever you like.")
                else -> ""
            },
        )
        when (r.outcome) {
            NpvEngine.Outcome.NOTHING_NEW -> Text(tr("چیز تازه‌ای نبود.", "Nothing new."), color = Ios.SecondaryLabel, fontSize = 13.sp)
            NpvEngine.Outcome.STOPPED -> Text(tr("متوقف شد.", "Stopped."), color = Ios.Orange, fontSize = 13.sp)
            NpvEngine.Outcome.FAILED -> Text(tr("خطا: ", "Error: ") + (r.note ?: ""), color = Ios.Red, fontSize = 13.sp)
            NpvEngine.Outcome.TESTED, NpvEngine.Outcome.STORED -> Text(tr("تمام شد.", "Done."), color = Ios.Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            else -> Unit
        }
        if (r.running && r.ask == NpvEngine.Ask.NONE) {
            TextButton(onClick = { NpvEngine.cancelRefresh() }) { Text(tr("توقف", "Stop"), color = Ios.Orange) }
        }
    }
}

@Composable
private fun StepLine(n: Int, title: String, state: StepState, fraction: Float?, detail: String) {
    val color = when (state) {
        StepState.DONE -> Ios.Green
        StepState.ACTIVE -> Ios.Orange
        StepState.FAILED -> Ios.Red
        else -> Ios.SecondaryLabel
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                if (state == StepState.DONE) Icon(Icons.Default.Check, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                else Text(fa(n), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title, color = if (state == StepState.WAITING || state == StepState.SKIPPED) Ios.SecondaryLabel else Ios.Label,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            val shownFraction = when (state) { StepState.DONE -> 1f; StepState.ACTIVE -> fraction; else -> null }
            if (shownFraction != null) Text(fa("${(shownFraction * 100).toInt()}") + "٪", color = Ios.SecondaryLabel, fontSize = 12.sp)
        }
        if (detail.isNotBlank()) {
            Text(
                detail, color = if (state == StepState.FAILED) Ios.Red else Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(start = 30.dp, top = 2.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        val bar = Modifier.fillMaxWidth().padding(start = 30.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
        val track = Color.White.copy(alpha = 0.15f)
        when {
            state == StepState.ACTIVE && fraction == null -> LinearProgressIndicator(modifier = bar, color = color, trackColor = track)
            else -> LinearProgressIndicator(
                progress = when (state) { StepState.DONE -> 1f; StepState.ACTIVE -> fraction ?: 0f; else -> 0f },
                modifier = bar, color = color, trackColor = track,
            )
        }
    }
}

// ------------------------------------------------------------------------------ channels and files

@Composable
private fun AddChannel(onAdd: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    Column {
        SettingsSectionHeader(tr("افزودن کانال تلگرام دلخواه", "Add a Telegram channel"))
        SettingsGroup {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text("t.me/channel  ·  @channel", color = Ios.SecondaryLabel, fontSize = 16.sp)
                    BasicTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp), cursorBrush = SolidColor(Ios.Blue),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { if (onAdd(text)) text = "" }, enabled = text.isNotBlank()) { Text(tr("افزودن", "Add")) }
            }
        }
        SettingsFooter(tr(
            "هر تعداد کانال که بخواهید، کنار کانال‌های پیش‌فرض. از هر کانال کانفیگ‌های vless، vmess، trojan، ss و دیگر لینک‌های استاندارد، لینک‌های اشتراک و فهرست فایل‌ها (npvt، npvs، txt) گرفته می‌شود.",
            "As many channels as you like, besides the default ones. From each: vless, vmess, trojan, ss and other standard links, subscription links, and the list of files (npvt, npvs, txt).",
        ))
    }
}

@Composable
private fun ChannelRow(ch: String, st: NpvEngine.ChannelState?, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(start = 16.dp, end = if (onRemove != null) 4.dp else 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Send, contentDescription = null, tint = Ios.Teal, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("@$ch", color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(channelLine(st), color = if (st?.error != null) Ios.Orange else Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = tr("برداشتن", "Remove"), tint = Ios.SecondaryLabel) }
        }
    }
}

private fun channelLine(st: NpvEngine.ChannelState?): String = when {
    st == null -> tr("هنوز بررسی نشده", "Not checked yet")
    st.error == "no-public-page" -> tr("صفحهٔ عمومی ندارد (خصوصی است یا نشانی اشتباه است)", "No public page (private, or a wrong address)")
    st.error != null -> tr("آخرین بار باز نشد · ", "Did not open last time · ") + ago(st.checkedAt)
    else -> ago(st.checkedAt) + tr(
        " · در پست‌های تازه: ${fa(st.lastConfigs)} کانفیگ، ${fa(st.lastFiles)} فایل",
        " · in new posts: ${st.lastConfigs} configs, ${st.lastFiles} files",
    )
}

private fun ago(t: Long): String {
    if (t <= 0) return ""
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> tr("همین حالا", "just now")
        m < 60 -> tr("${fa(m.toInt())} دقیقه پیش", "$m min ago")
        m < 1440 -> tr("${fa((m / 60).toInt())} ساعت پیش", "${m / 60} h ago")
        else -> tr("${fa((m / 1440).toInt())} روز پیش", "${m / 1440} d ago")
    }
}

/** A file seen in a channel: a tap opens its post in Telegram, where the file itself is. */
@Composable
private fun FileRow(e: NpvLibrary.Entry) {
    val ctx = LocalContext.current
    val n = e.name.lowercase()
    val npv = n.contains(".npvt") || n.contains(".npvs")
    Row(
        Modifier.fillMaxWidth().clickable {
            val link = e.uri
            if (link == null || !NpvEngine.openPost(ctx, link)) Toast.makeText(ctx, tr("تلگرام باز نشد", "Telegram did not open"), Toast.LENGTH_SHORT).show()
        }.heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Description, contentDescription = null, tint = if (npv) Ios.Purple else Ios.Blue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sourceLabel(e.source) + " · " + (if (npv) tr("فایل NPV Tunnel", "NPV Tunnel file") else tr("فایل متنی", "text file")) + tr(" · باز کردن در تلگرام", " · open in Telegram"),
                color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
