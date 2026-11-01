package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.configstudio.parts.CentralKit
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.data.PanelBuild
import com.mlmvpn.scanner.data.studio.StudioDeployer
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.domain.ShardCapacity
import com.mlmvpn.scanner.data.studio.domain.StudioInstallation
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.MeterBar
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.StatusDot
import com.mlmvpn.scanner.ui.configstudio.parts.StudioListRow
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosRefreshIndicator
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.rememberIosRefreshState
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * «حساب‌ها» — the fleet.
 *
 * Every ceiling in this product is **per Cloudflare account**: a hundred thousand Worker requests a
 * day, a hundred thousand D1 row writes, five million reads. Connecting a second account does not
 * make one account bigger, it adds another set of all of them — which is why the fleet is uncapped
 * (D7) and why this screen exists at all.
 *
 * Three things it is careful about, each because the alternative is a quiet wrong answer:
 *
 *  * **The capacity meter is an estimate and says so.** Cloudflare's request counter cannot be read
 *    from inside the Worker, and counting requests in D1 would spend one row write per request to
 *    measure the budget it is spending. So the bar is a user count against a stated band
 *    ([ShardCapacity]), not a reading, and the footer says which.
 *  * **An account that did not answer stays on the list, marked.** Dropping it would make a shard —
 *    and everyone on it — disappear for a reason that has nothing to do with them (R12).
 *  * **A stale shard is named individually.** With an uncapped fleet, one account on an older engine
 *    while another is current is an ordinary state, not an error (R13). It gets its own «بروزرسانی»
 *    rather than a single global banner that cannot say which account it means.
 *
 * The list pages and searches because it is uncapped like everything else, and «افزودن گروهی» exists
 * because running a five-step wizard fifty times is not a thing anyone will do (§A.8).
 */
@Composable
fun StudioAccountsScreen(
    store: StudioStore,
    state: StudioState,
    onAddAccount: () -> Unit,
    onBulkAdd: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        store.refreshInstallations()
        store.refreshDashboard()
    }

    val all = state.installations
    val rows = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all
        else all.filter { it.accountLabel.lowercase().contains(q) || it.workerUrl.lowercase().contains(q) }
    }
    val stale = all.filter { it.isStale(PanelBuild.MLM, PanelBuild.MLM_SCHEMA) }
    val refresh = rememberIosRefreshState {
        store.refreshInstallations()
        store.refreshDashboard()
    }

    // scrollable = false: the body holds a LazyColumn, and a LazyColumn measured inside a
    // vertically scrolling Column gets infinite height and crashes.
    IosScreen(
        title = S(R.string.studio_accounts),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_settings_title),
        scrollable = false,
        trailing = {
            Text(
                S(R.string.studio_account_add),
                color = Ios.Blue,
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable(enabled = !busy, onClick = onAddAccount)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        },
    ) {
        Spacer(Modifier.height(10.dp))

        // Only once the list is long enough for a search box to be the faster way to a row.
        if (all.size > 6) {
            WizardField(query, { query = it }, S(R.string.studio_account_search), ltr = false)
            Spacer(Modifier.height(6.dp))
        }

        note?.let { InfoCard(it); Spacer(Modifier.height(10.dp)) }
        error?.let { ProblemCard(it); Spacer(Modifier.height(10.dp)) }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(refresh.connection)) {
            item { IosRefreshIndicator(refresh) }
            item {
                FleetSummary(all)
                Spacer(Modifier.height(10.dp))
            }

            items(rows, key = { it.installationId }) { inst ->
                ShardRow(inst) { onOpenAccount(inst.installationId) }
            }

            item {
                Spacer(Modifier.height(14.dp))
                SettingsGroup {
                    SettingsActionRow(
                        label = S(R.string.studio_account_add),
                        icon = StudioIcons.Add,
                        tint = Ios.Blue,
                        enabled = !busy,
                        onClick = onAddAccount,
                    )
                    Separator()
                    SettingsActionRow(
                        label = S(R.string.studio_account_bulk_add),
                        icon = StudioIcons.BulkAdd,
                        tint = Ios.Indigo,
                        enabled = !busy,
                        onClick = onBulkAdd,
                    )
                    // Offered only when something is actually behind, so it is never a button whose
                    // only outcome is "everything was already up to date".
                    if (stale.isNotEmpty()) {
                        Separator()
                        SettingsActionRow(
                            label = S(R.string.studio_account_update_all)
                                .replace("%1\$s", faNum(stale.size)),
                            icon = StudioIcons.EngineUpdate,
                            tint = Ios.Orange,
                            enabled = !busy,
                        ) {
                            busy = true; error = null; note = null
                            scope.launch {
                                val accounts = store.installedAccounts().associateBy { it.id }
                                var done = 0
                                val failed = mutableListOf<String>()
                                for (inst in stale) {
                                    val acc = accounts[inst.installationId] ?: continue
                                    when (StudioDeployer(context).install(acc)) {
                                        is StudioDeployer.Result.Ready -> done++
                                        // A downgrade refusal is not a failure of the update, it is
                                        // the guard doing its job — that account is AHEAD of this
                                        // app. Named separately so "update all" does not read as
                                        // broken when it was correct.
                                        is StudioDeployer.Result.WouldDowngrade ->
                                            failed.add(inst.accountLabel)
                                        is StudioDeployer.Result.Failed ->
                                            failed.add(inst.accountLabel)
                                    }
                                }
                                store.refreshInstallations()
                                note = context.getString(R.string.studio_account_updated_n)
                                    .replace("%1\$s", faDigitsOfInt(done))
                                if (failed.isNotEmpty()) {
                                    error = context.getString(R.string.studio_account_update_failed)
                                        .replace("%1\$s", failed.joinToString("، "))
                                }
                                busy = false
                            }
                        }
                    }
                }
                // The engines' room is an estimate; a central Worker's is an exact count of ids.
                if (all.isEmpty() || all.any { !it.isCentral }) InfoCard(S(R.string.studio_capacity_note))
                if (all.any { it.isCentral }) InfoCard(tr(
                    "جای ورکر مرکزی دقیق است: هر حساب تعداد مشخصی شناسه می‌پذیرد؛ یکی برای خود ورکر، یکی برای هر پنل وصل‌شده و بقیه برای کاربرانی که الان فعال‌اند. کاربر خاموش یا تمام‌شده جایی نمی‌گیرد.",
                    "A central Worker's room is exact: each account takes a set number of ids — one for the Worker, one per connected panel, the rest for the people active right now. Someone switched off or ended takes no place.",
                ))
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/**
 * The fleet in one line: how many accounts, how many people, how much room.
 *
 * Total capacity is the sum of the per-shard bands rather than one big number, because that is what
 * the fleet actually buys — the ceilings do not pool, they repeat.
 */
@Composable
private fun FleetSummary(all: List<StudioInstallation>) {
    val known = all.filter { it.users >= 0 }
    // A central Worker counts the places taken right now against its exact room; an engine, its
    // people against the estimated band.
    val users = known.sumOf { if (it.isCentral) it.centralLive else it.users }
    val room = known.sumOf { if (it.isCentral) it.centralRoom.coerceAtLeast(0) else ShardCapacity.FULL_AT_USERS }
    val unreachable = all.count { !it.reachable }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                S(R.string.studio_accounts_count).replace("%1\$s", faNum(all.size)),
                color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            if (room > 0) {
                Text(
                    S(R.string.studio_capacity_of)
                        .replace("%1\$s", faNum(users))
                        .replace("%2\$s", faNum(room)),
                    color = Ios.SecondaryLabel, fontSize = 13.sp,
                )
            }
        }
        if (room > 0) {
            Spacer(Modifier.height(8.dp))
            MeterBar(
                fraction = users.toFloat() / room,
                tint = if (users.toFloat() / room >= ShardCapacity.CROWDED_FRACTION) Ios.Orange else Ios.Blue,
            )
        }
        if (unreachable > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                S(R.string.studio_accounts_unreachable_n).replace("%1\$s", faNum(unreachable)),
                color = Ios.Orange, fontSize = 12.sp,
            )
        }
    }
}

/** One shard: what it is, how full, and whether anything is wrong with it. */
@Composable
private fun ShardRow(inst: StudioInstallation, onClick: () -> Unit) {
    val stale = inst.isStale(PanelBuild.MLM, PanelBuild.MLM_SCHEMA)
    StudioListRow(onClick = onClick) {
        StatusDot(
            when {
                !inst.reachable -> Ios.SecondaryLabel
                !inst.isHealthy || stale -> Ios.Orange
                inst.isCrowded -> Ios.Orange
                else -> Ios.Green
            }
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                inst.accountLabel.ifBlank { inst.workerUrl },
                color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(shardSubtitle(inst, stale), color = Ios.SecondaryLabel, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            MeterBar(
                fraction = inst.loadFraction,
                tint = if (inst.isCrowded) Ios.Orange else Ios.Blue,
            )
        }
    }
}

/**
 * What is worth saying about a shard in one line.
 *
 * Problems first and one at a time. A row that lists everything at once is a row nobody reads, and
 * the first problem is the one to act on anyway — an unreachable account's user count is a stale
 * number, and its engine version cannot be trusted either.
 */
@Composable
private fun shardSubtitle(inst: StudioInstallation, stale: Boolean): String = when {
    !inst.reachable -> S(R.string.studio_shard_unreachable)
    // No engine and no database here: what matters is the people and the places left on the Worker.
    inst.isCentral -> inst.syncError?.let { CentralKit.syncProblemText(it) } ?: (
        tr("ورکر مرکزی", "Central Worker") + " · " + S(R.string.studio_shard_users).replace("%1\$s", faNum(inst.users.coerceAtLeast(0))) +
            " · " + tr("${faNum((inst.centralRoom - inst.centralLive).coerceAtLeast(0))} جای خالی", "${(inst.centralRoom - inst.centralLive).coerceAtLeast(0)} places free")
        )
    inst.migrationError != null -> S(R.string.studio_engine_migration_failed)
    !inst.d1Ok -> S(R.string.studio_shard_no_database)
    stale -> S(R.string.studio_engine_update_available)
    inst.users < 0 -> S(R.string.studio_shard_unknown_load)
    else -> S(R.string.studio_shard_users).replace("%1\$s", faNum(inst.users)) +
        " · " + S(R.string.studio_engine_schema) + " " + faNum(inst.schemaVersion)
}

/** Outside a composable, so a coroutine callback can build its message. */
internal fun faDigitsOfInt(n: Int): String = com.mlmvpn.scanner.ui.emergency.faDigits(n.toString())
