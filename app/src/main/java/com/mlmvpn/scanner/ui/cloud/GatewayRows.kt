package com.mlmvpn.scanner.ui.cloud

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudGroup
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.engines.gateway.EdgeGateway
import com.mlmvpn.scanner.engines.gateway.Gateway
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.CloudPanelHeaderRow
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import kotlinx.coroutines.launch

/**
 * «ورکر مرکزی» — the Cloud tab's rows for the account's ONE relay Worker, shared with the Windows app.
 *
 * BPB is the Worker's own row. Every other panel is one more id on the SAME Worker: connecting one
 * installs no panel code and no second Worker, and the Worker is installed once, for all of them and
 * for Windows too (docs/EDGE-GATEWAY.fa.md, docs/PANEL-REGISTRY.md key `GW`). A panel that is already
 * installed as a FULL panel on this account ([legacy]) keeps its own row further down and gets no
 * row here; a panel that is not installed gets a row here instead of a «Deploy» button of its own.
 *
 * Deploying and taking configs go DIRECT; a user whose line blocks Cloudflare turns on one of the
 * app's tunnels and the same buttons go through it (engines/cloud/CfTunnel.kt) — nothing here knows.
 */
@Composable
fun GatewayBlock(
    account: CloudAccount,
    groupManager: GroupManager,
    cfgCount: (String) -> Int,
    /** Panels installed as FULL panels on this account: they keep their own row, and none here. */
    legacy: Set<String>,
    openPanel: String?,
    setOpenPanel: (String?) -> Unit,
    onShowSettings: () -> Unit,
    onGroupsUpdated: () -> Unit,
    /** MLM on the phone IS Config Studio's engine: its full install stays one tap away. */
    onInstallFullMlm: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by EdgeGateway.version.collectAsState()
    val gw = remember(version, account.accountId) { EdgeGateway.status(context, account) }
    // Which row is working, and what it is doing right now (the deploy's own steps).
    var busyKey by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf<String?>(null) }

    fun launchWork(key: String, work: suspend () -> Result<*>, done: (Any?) -> String?) {
        if (busyKey != null) return
        busyKey = key
        step = tr("آماده‌سازی…", "Preparing…")
        scope.launch {
            val r = work()
            busyKey = null
            step = null
            r.onSuccess { v -> done(v)?.let { toast(context, it) } }
                .onFailure { toast(context, EdgeGateway.localized(it)) }
        }
    }

    fun install() = launchWork("GW", { EdgeGateway.deploy(context, account) { s -> step = s } }) {
        tr("ورکر مرکزی آماده است.", "The central Worker is ready.")
    }
    fun fetch(code: String) = launchWork(code, { GatewayConfigs.fetch(context, groupManager, account, code) }) { n ->
        onGroupsUpdated()
        context.getString(R.string.cloud_admin_configs_received, n as Int)
    }
    fun connect(code: String) = launchWork(code, { EdgeGateway.connectPanel(context, account, code) { s -> step = s } }) {
        tr("${Gateway.PANEL_TITLE[code]} به ورکر مرکزی وصل شد.", "${Gateway.PANEL_TITLE[code]} is connected to the central Worker.")
    }
    fun disconnect(code: String) = launchWork(code, { EdgeGateway.disconnectPanel(context, account, code) { s -> step = s } }) {
        tr("${Gateway.PANEL_TITLE[code]} از ورکر مرکزی جدا شد.", "${Gateway.PANEL_TITLE[code]} was disconnected from the central Worker.")
    }

    Column(Modifier.fillMaxWidth()) {
        // ── the Worker itself: BPB's row ──
        CloudPanelHeaderRow(
            title = "BPB",
            subtitle = tr("ورکر مرکزی: یک رلهٔ کوچک برای همهٔ پنل‌ها و ویندوز", "The central Worker: one small relay for every panel and Windows"),
            state = if (busyKey == "GW") "deploying" else if (gw.installed) "done" else "idle",
            configCount = cfgCount("BPB"),
            expanded = openPanel == "GW",
            stale = gw.installed && !gw.upToDate,
            engine = "BPB",
            onClick = { setOpenPanel(if (openPanel == "GW") null else "GW") },
        )
        if (openPanel == "GW") Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.035f))) {
            if (!gw.installed) {
                ActionRow(Icons.Default.CloudUpload, if (busyKey == "GW") step ?: "…" else tr("نصب ورکر مرکزی", "Install the central Worker"), busy = busyKey == "GW") { install() }
                Note(tr("یک‌بار برای همهٔ پنل‌های این حساب (و ویندوز) نصب می‌شود؛ پنل و تنظیماتی روی آن نیست و کدی از پنل‌ها نصب نمی‌شود.",
                    "Installed once for every panel on this account (and Windows); it holds no panel and no settings."))
            } else {
                ActionRow(Icons.Default.CloudDownload, if (busyKey == "BPB") tr("در حال ساخت کانفیگ…", "Making the configs…") else tr("دریافت کانفیگ", "Get configs"), busy = busyKey == "BPB") { fetch("BPB") }
                GwDivider()
                ActionRow(Icons.Default.Settings, tr("تنظیمات ورکر مرکزی", "Central Worker settings"), busy = false) { onShowSettings() }
                if (!gw.upToDate) {
                    GwDivider()
                    ActionRow(Icons.Default.Refresh, if (busyKey == "GW") step ?: "…" else tr("به‌روزرسانی ورکر (شناسه‌ها همان می‌مانند)", "Update the Worker (ids stay the same)"), busy = busyKey == "GW", tint = Ios.Orange) { install() }
                }
                Note(sharedLine(gw.shared, gw.sharedBy) + (gw.extraScript?.let { "\n" + tr("ورکر قبلیِ این گوشی ($it) هنوز روی حساب مانده و استفاده نمی‌شود؛ از «منابع کلادفلر» پاکش کنید.", "This phone's earlier Worker ($it) is still on the account and unused; remove it from Cloudflare resources.") }.orEmpty()))
            }
        }

        // ── every other panel that is not installed as a full panel ──
        for (code in Gateway.PANEL_CODES) {
            if (code in legacy) continue
            val on = gw.connected(code)
            GwDivider()
            CloudPanelHeaderRow(
                title = Gateway.PANEL_TITLE.getValue(code),
                subtitle = tr("به همان ورکر مرکزی وصل می‌شود؛ ورکر یا کد پنل تازه‌ای نصب نمی‌شود", "Connects to the same central Worker; no new Worker or panel code is installed"),
                state = if (busyKey == code) "deploying" else if (on) "done" else "idle",
                configCount = cfgCount(code),
                expanded = openPanel == code,
                engine = code,
                onLabel = tr("متصل", "Connected"),
                offLabel = tr("وصل نیست", "Not connected"),
                onClick = { setOpenPanel(if (openPanel == code) null else code) },
            )
            if (openPanel == code) Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.035f))) {
                if (!on) {
                    val label = if (busyKey == code) step ?: "…"
                    else if (gw.installed) tr("اتصال به ورکر مرکزی", "Connect to the central Worker")
                    else tr("نصب ورکر مرکزی و اتصال", "Install the central Worker and connect")
                    ActionRow(Icons.Default.SyncAlt, label, busy = busyKey == code) { connect(code) }
                } else {
                    ActionRow(Icons.Default.CloudDownload, if (busyKey == code) tr("در حال ساخت کانفیگ…", "Making the configs…") else tr("دریافت کانفیگ", "Get configs"), busy = busyKey == code) { fetch(code) }
                    GwDivider()
                    ActionRow(Icons.Default.LinkOff, tr("جدا کردن از ورکر مرکزی", "Disconnect from the central Worker"), busy = false, tint = Ios.Red) { confirmDisconnect = code }
                }
                // MLM on the phone IS «کانفیگ استدیو», and it now runs on this same central Worker:
                // users, «بسته» and links on this phone, one id each on the Worker — no second
                // Worker, no database. The old full install is no longer offered (the user's choice).
                if (code == "MLM") {
                    GwDivider()
                    ActionRow(Icons.Default.Group, tr("کانفیگ استدیو (کاربر و «بسته» روی همین ورکر)", "Config Studio (users and plans on this Worker)"), busy = false) { onInstallFullMlm() }
                    Note(tr("کاربرهای «کانفیگ استدیو» روی همین ورکر مرکزی ساخته می‌شوند؛ ورکر یا دیتابیس دیگری نصب نمی‌شود.",
                        "Config Studio's users are made on this same central Worker; no other Worker or database is installed."))
                }
            }
        }
    }

    confirmDisconnect?.let { code ->
        val title = Gateway.PANEL_TITLE[code] ?: code
        IosAlert(
            title = tr("جدا کردن $title؟", "Disconnect $title?"),
            message = tr("شناسهٔ $title از ورکر برداشته می‌شود و کانفیگ‌هایی که از این پنل گرفته‌اید از کار می‌افتند؛ بقیهٔ پنل‌ها دست نمی‌خورند.",
                "$title's id is taken off the Worker and the configs you took from it stop working; the other panels are untouched."),
            actions = listOf(
                IosAlertAction(tr("انصراف", "Cancel"), { confirmDisconnect = null }),
                IosAlertAction(tr("جدا کن", "Disconnect"), { confirmDisconnect = null; disconnect(code) }, destructive = true),
            ),
            onDismiss = { confirmDisconnect = null },
        )
    }
}

/** «همین ورکر برای ویندوز هم هست» / «هنوز با ویندوز هماهنگ نشده». */
internal fun sharedLine(shared: Boolean, by: String): String =
    if (shared) tr("همین ورکر برای ویندوز هم هست (${if (by == "windows") "ویندوز نصبش کرده" else "هماهنگ شده"}).", "The same Worker serves Windows too (${if (by == "windows") "installed from Windows" else "in sync"}).")
    else tr("هنوز با ویندوز هماهنگ نشده؛ در اولین هماهنگی ثبت می‌شود.", "Not shared with Windows yet; it is written to the account on the next sync.")

@Composable
private fun Note(text: String) {
    Text(text, color = Ios.SecondaryLabel, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, top = 2.dp, bottom = 10.dp))
}

@Composable
private fun GwDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(Ios.Separator))
}

private fun toast(context: Context, text: String?) {
    if (!text.isNullOrBlank()) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

/**
 * Taking a panel's links from the central Worker and filing them as a Cloud group, the way every
 * panel's are filed — except that a new fetch REPLACES that panel's group on this account: pressing
 * «دریافت کانفیگ» five times used to leave five identical groups (the Windows app does the same).
 */
object GatewayConfigs {

    /** Group ids of the central Worker's links: `gw_<code>_<account>_<stamp>`. */
    fun groupPrefix(code: String, accountId: String) = "gw_${code.lowercase()}_${accountId}_"

    /** [offer]: ask «combine with a clean IP?» under the new group; the coach, which walks the user on itself, passes false. */
    suspend fun fetch(context: Context, groupManager: GroupManager, account: CloudAccount, code: String, offer: Boolean = true): Result<Int> =
        EdgeGateway.configs(context, account, code).mapCatching { res ->
            if (res.configs.isEmpty()) error(tr("کانفیگی ساخته نشد.", "No configs were made."))
            file(context, groupManager, account, code, res.configs, offer)
            res.configs.size
        }

    fun file(context: Context, groupManager: GroupManager, account: CloudAccount, code: String, links: List<String>, offer: Boolean = true): CloudGroup {
        val label = Gateway.PANEL_TITLE[code] ?: code
        val stamp = System.currentTimeMillis()
        val prefix = groupPrefix(code, account.id)
        groupManager.cloudGroups.removeAll { it.id.startsWith(prefix) }
        val nodes = links.mapIndexed { i, uri ->
            VpnNode(id = "${prefix}${stamp}_$i", name = "$label · ${PanelConfigs.nameOf(uri)}", uri = uri, type = "vless", engineType = code)
        }
        val group = CloudGroup(
            id = "$prefix$stamp",
            accountId = account.id,
            date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
            title = com.mlmvpn.scanner.utils.NamingHelper.generateMythName(),
            nodes = nodes,
        )
        groupManager.cloudGroups.add(0, group)
        groupManager.saveCloudGroups()
        if (offer) com.mlmvpn.scanner.ui.CombineCoach.offer(context, group.id, nodes.first().uri)
        return group
    }
}
