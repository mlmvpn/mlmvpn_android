package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.data.PanelBuild
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S

/**
 * «تنظیمات» -- what holds for the whole fleet, reached from the gear on «خانه» (build 18 redesign).
 *
 * Every per-account action lives on that account's own page under «حساب‌ها», where the address of
 * the thing being changed is on screen next to the button that changes it. What is here is the way
 * into the fleet, the engine updates it needs, and what this installation really enforces -- stated
 * per account, never as the old «ثبت می‌شود ولی هنوز اعمال نمی‌شود».
 */
@Composable
fun StudioSettingsScreen(
    store: StudioStore,
    state: StudioState,
    onOpenAccounts: () -> Unit,
    onOpenNodes: () -> Unit,
    onOpenLocations: () -> Unit,
    onOpenAudit: () -> Unit,
    onBulkAdd: () -> Unit,
    onBack: () -> Unit,
) {
    var loading by remember { mutableStateOf(state.installations.isEmpty()) }

    LaunchedEffect(Unit) {
        store.refreshInstallations()
        loading = false
    }

    val all = state.installations
    val stale = all.count { it.isStale(PanelBuild.MLM, PanelBuild.MLM_SCHEMA) }
    val unreachable = all.count { !it.reachable }
    // A central Worker counts neither volume nor devices (and has no engine to update), so the
    // caps below are about the engine accounts; the central ones get their own sentence.
    val engines = all.filter { !it.isCentral }
    val centrals = all.size - engines.size
    val enforcing = engines.count { it.can("enforcement.strict") }

    IosScreen(
        title = S(R.string.studio_settings_title),
        onBack = onBack,
        backLabel = S(R.string.studio_tab_home),
        onRefresh = { store.refreshInstallations() },
    ) {
        if (stale > 0) {
            NoticeCard(
                icon = StudioIcons.EngineUpdate,
                tint = Ios.Blue,
                title = S(R.string.studio_home_update_title),
                body = S(R.string.studio_home_update_body).replace("%1\$s", faNum(stale)),
                actionLabel = S(R.string.studio_home_update_action),
                onAction = onOpenAccounts,
            )
        }

        SettingsSectionHeader(S(R.string.studio_accounts))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_more_accounts),
                icon = StudioIcons.Accounts,
                tint = Ios.Blue,
                value = if (loading) null else faNum(all.size),
                subtitle = when {
                    unreachable > 0 -> S(R.string.studio_accounts_unreachable_n).replace("%1\$s", faNum(unreachable))
                    stale > 0 -> S(R.string.studio_account_update_all).replace("%1\$s", faNum(stale))
                    else -> null
                },
                onClick = onOpenAccounts,
            )
            Separator()
            SettingsActionRow(
                label = S(R.string.studio_account_bulk_add),
                icon = StudioIcons.Add,
                tint = Ios.Indigo,
                onClick = onBulkAdd,
            )
        }
        SettingsFooter(S(R.string.studio_accounts_note))

        SettingsSectionHeader(S(R.string.studio_more_network))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_more_locations),
                subtitle = S(R.string.studio_more_locations_sub),
                icon = StudioIcons.Locations,
                tint = Ios.Indigo,
                onClick = onOpenLocations,
            )
            Separator()
            SettingsRow(
                title = S(R.string.studio_nodes),
                icon = StudioIcons.Endpoints,
                tint = Ios.Teal,
                value = if (state.nodes.isEmpty()) null else faNum(state.nodes.size),
                subtitle = state.nodes.count { it.isDown }.takeIf { it > 0 }?.let { S(R.string.studio_node_down) },
                onClick = onOpenNodes,
            )
        }

        // What is enforced, per account -- a fact about each installation, not about the build.
        SettingsSectionHeader(S(R.string.studio_caps_title))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_settings_quota_title),
                subtitle = when {
                    centrals == 0 -> S(R.string.studio_settings_quota_sub)
                    engines.isEmpty() -> tr(
                        "روی ورکر مرکزی فقط زمان اعمال می‌شود: این گوشی کاربرانی را که زمانشان تمام شده حدود هر نیم ساعت از ورکر برمی‌دارد. حجم شمرده نمی‌شود.",
                        "On the central Worker only time is enforced: this phone takes people whose time is up off the Worker about every half hour. Volume is not counted.",
                    )
                    else -> S(R.string.studio_settings_quota_sub) + " " + tr(
                        "روی ورکر مرکزی فقط زمان.", "On the central Worker, time only.",
                    )
                },
                icon = StudioIcons.Volume,
                tint = Ios.Green,
                showChevron = false,
            )
            Separator()
            SettingsRow(
                title = S(R.string.studio_settings_devices_title),
                subtitle = when {
                    all.isEmpty() -> null
                    engines.isEmpty() -> tr("روی ورکر مرکزی شمرده نمی‌شود.", "Not counted on the central Worker.")
                    enforcing == engines.size -> S(R.string.studio_settings_devices_all)
                    enforcing == 0 -> S(R.string.studio_settings_devices_none)
                    else -> S(R.string.studio_settings_devices_some)
                        .replace("%1\$s", faNum(enforcing)).replace("%2\$s", faNum(engines.size))
                },
                icon = StudioIcons.Devices,
                tint = when {
                    engines.isEmpty() -> Ios.Gray
                    enforcing == engines.size -> Ios.Green
                    else -> Ios.Orange
                },
                onClick = if (enforcing < engines.size) onOpenAccounts else null,
                showChevron = enforcing < engines.size,
            )
        }

        SettingsSectionHeader(S(R.string.studio_more_history))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_more_history),
                subtitle = S(R.string.studio_more_history_sub),
                icon = StudioIcons.Audit,
                tint = Ios.Teal,
                onClick = onOpenAudit,
            )
        }
        SettingsFooter(S(R.string.studio_audit_scope))

        Spacer(Modifier.height(28.dp))
    }
}
