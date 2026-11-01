package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.PanelBuild
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.domain.ActivitySeverity
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.index.UserFilter
import com.mlmvpn.scanner.data.studio.index.UserSort
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioStatTile
import com.mlmvpn.scanner.ui.configstudio.design.StudioTilePair
import com.mlmvpn.scanner.ui.configstudio.design.StudioType
import com.mlmvpn.scanner.ui.configstudio.parts.MeterBar
import com.mlmvpn.scanner.ui.configstudio.parts.StatusDot
import com.mlmvpn.scanner.ui.configstudio.parts.StudioChip
import com.mlmvpn.scanner.ui.configstudio.parts.StudioTile
import com.mlmvpn.scanner.ui.configstudio.parts.StudioTileRow
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S

/**
 * «خانه» -- the first tab (build 18 redesign).
 *
 * What the operator opens the app to find out, in the order they want it: whether anything needs
 * them (a notice), how things stand (six figures), what they usually do next (four shortcuts), and
 * who needs attention by name. Everything that is a PLACE rather than a figure moved to the other
 * three tabs, which is what the old twelve-tile dashboard was missing: it was a menu and a report at
 * once, and it read as neither.
 *
 * Every figure comes from one `/v1/dashboard` call per installation, summed locally, and a figure an
 * installation cannot measure draws a dash, never a zero. Pull down to reload.
 */
@Composable
fun StudioDashboardScreen(
    store: StudioStore,
    state: StudioState,
    onRefresh: suspend () -> Unit,
    /** Re-read the problems feed narrowed to failures, or not. */
    onActivityFilter: suspend (Boolean) -> Unit,
    onOpenUsers: (UserFilter) -> Unit,
    onOpenUser: (installationId: String, userId: String) -> Unit,
    onNewUser: () -> Unit,
    onBulkUsers: () -> Unit,
    onNewConfig: () -> Unit,
    onCombine: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccounts: () -> Unit,
    onExit: () -> Unit,
) {
    var errorsOnly by remember { mutableStateOf(false) }
    LaunchedEffect(errorsOnly) { onActivityFilter(errorsOnly) }
    val d = state.dashboard

    // Who needs attention, by name: out of volume first, then the soonest to run out of time. Read
    // from the local index, so it costs nothing and follows every sync.
    var outOfVolume by remember { mutableStateOf<List<StudioUser>>(emptyList()) }
    var expiring by remember { mutableStateOf<List<StudioUser>>(emptyList()) }
    LaunchedEffect(state.indexedUsers, state.indexedActive, state.oldestSyncAgeMs) {
        outOfVolume = store.page(limit = 3, filter = UserFilter.OUT_OF_VOLUME, sort = UserSort.USAGE)
        expiring = store.page(limit = 3, filter = UserFilter.EXPIRING, sort = UserSort.EXPIRY)
    }

    val stale = state.installations.filter { it.isStale(PanelBuild.MLM, PanelBuild.MLM_SCHEMA) }
    // A central Worker counts no devices by design: there is nothing to turn on, so it is not named here.
    val noLimits = state.installations.filter { it.reachable && !it.isCentral && !it.can("enforcement.strict") }
    // Traffic is counted by an engine; a fleet of central Workers alone has no number to show.
    val trafficKnown = state.installations.isEmpty() || state.installations.any { !it.isCentral }

    IosScreen(
        largeTitle = S(R.string.studio_title),
        onBack = onExit,
        backLabel = S(R.string.studio_wizard_back),
        trailing = {
            Icon(
                StudioIcons.Settings,
                contentDescription = S(R.string.studio_settings_title),
                tint = Ios.Label,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(onClick = onOpenSettings)
                    .padding(8.dp)
                    .size(22.dp),
            )
        },
        onRefresh = onRefresh,
    ) {
        // ---- what needs the operator ------------------------------------------------------
        if (state.unreachable.isNotEmpty()) {
            NoticeCard(
                icon = StudioIcons.Error,
                tint = Ios.Red,
                title = S(R.string.studio_home_unreachable_title),
                body = S(R.string.studio_account_unreachable).replace("%1\$s", state.unreachable.joinToString("، ")),
            )
        }
        if (stale.isNotEmpty()) {
            NoticeCard(
                icon = StudioIcons.EngineUpdate,
                tint = Ios.Blue,
                title = S(R.string.studio_home_update_title),
                body = S(R.string.studio_home_update_body).replace("%1\$s", faNum(stale.size)),
                actionLabel = S(R.string.studio_home_update_action),
                onAction = onOpenAccounts,
            )
        }
        if (noLimits.isNotEmpty() && stale.isEmpty()) {
            NoticeCard(
                icon = StudioIcons.Devices,
                tint = Ios.Orange,
                title = S(R.string.studio_limits_off_title).replace("%1\$s", faNum(noLimits.size)),
                body = S(R.string.studio_limits_off_body),
                actionLabel = S(R.string.studio_limits_off_action),
                onAction = onOpenAccounts,
            )
        }
        state.oldestSyncAgeMs?.let { age ->
            if (age > StudioStore_STALE) {
                NoticeCard(icon = StudioIcons.Time, tint = Ios.Orange, title = freshnessText(age),
                    body = S(R.string.studio_home_pull_hint))
            }
        }

        // ---- how things stand -------------------------------------------------------------
        Spacer(Modifier.height(4.dp))
        StudioTilePair {
            StudioStatTile(
                label = S(R.string.studio_tile_online),
                value = countOrDash(d?.online),
                icon = StudioIcons.Users,
                tint = Ios.Green,
                modifier = Modifier.weight(1f),
                onClick = { onOpenUsers(UserFilter.ACTIVE) },
            )
            StudioStatTile(
                label = S(R.string.studio_home_users),
                value = faNum(d?.total ?: state.indexedUsers),
                icon = StudioIcons.Users,
                tint = Ios.Blue,
                detail = S(R.string.studio_home_users_active).replace("%1\$s", faNum(d?.active ?: state.indexedActive)),
                modifier = Modifier.weight(1f),
                onClick = { onOpenUsers(UserFilter.ALL) },
            )
        }
        StudioTilePair {
            StudioStatTile(
                label = S(R.string.studio_tile_expiring),
                value = faNum(d?.expiringSoon ?: 0),
                icon = StudioIcons.Time,
                tint = Ios.Orange,
                modifier = Modifier.weight(1f),
                onClick = { onOpenUsers(UserFilter.EXPIRING) },
            )
            StudioStatTile(
                label = S(R.string.studio_home_stopped),
                value = faNum((d?.overQuota ?: 0) + (d?.expired ?: 0)),
                icon = StudioIcons.Block,
                tint = Ios.Red,
                detail = S(R.string.studio_home_stopped_detail),
                modifier = Modifier.weight(1f),
                onClick = { onOpenUsers(UserFilter.OUT_OF_VOLUME) },
            )
        }
        StudioTilePair {
            StudioStatTile(
                label = S(R.string.studio_traffic_24h),
                value = d?.takeIf { trafficKnown }?.let { bytesFa(it.trafficDownBytes24h + it.trafficUpBytes24h) } ?: S(R.string.studio_unknown_dash),
                icon = StudioIcons.Traffic,
                tint = Ios.Purple,
                modifier = Modifier.weight(1f),
            )
            StudioStatTile(
                label = S(R.string.studio_traffic_30d),
                value = d?.takeIf { trafficKnown }?.let { bytesFa(it.trafficDownBytes30d + it.trafficUpBytes30d) } ?: S(R.string.studio_unknown_dash),
                icon = StudioIcons.Calendar,
                tint = Ios.Indigo,
                modifier = Modifier.weight(1f),
            )
        }

        // ---- the usual next step ----------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_shortcuts))
        StudioTileRow {
            StudioTile(S(R.string.studio_new_user), StudioIcons.NewUser, Ios.Green, Modifier.weight(1f), onNewUser)
            StudioTile(S(R.string.studio_bulk_new_title), StudioIcons.BulkUsers, Ios.Teal, Modifier.weight(1f), onBulkUsers)
        }
        Spacer(Modifier.height(10.dp))
        StudioTileRow {
            StudioTile(S(R.string.studio_config_for), StudioIcons.Config, Ios.Indigo, Modifier.weight(1f), onNewConfig)
            StudioTile(S(R.string.studio_combine), StudioIcons.Combine, Ios.Purple, Modifier.weight(1f), onCombine)
        }

        // ---- who needs attention ----------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_home_attention))
        val attention = (outOfVolume.map { it to true } + expiring.map { it to false })
            .distinctBy { it.first.installationId + ":" + it.first.id }
        if (attention.isEmpty()) {
            SettingsGroup {
                SettingsRow(
                    title = S(R.string.studio_home_attention_none),
                    icon = StudioIcons.Ok,
                    tint = Ios.Green,
                    showChevron = false,
                )
            }
        } else {
            SettingsGroup {
                attention.forEachIndexed { index, (user, outOfQuota) ->
                    if (index > 0) Separator()
                    SettingsRow(
                        title = user.username,
                        subtitle = if (outOfQuota) S(R.string.studio_home_attention_out)
                        else user.policy.expiresAt?.let { expiresInText(it) },
                        icon = if (outOfQuota) StudioIcons.Volume else StudioIcons.Time,
                        tint = if (outOfQuota) Ios.Red else Ios.Orange,
                        onClick = { onOpenUser(user.installationId, user.id) },
                    )
                }
            }
        }

        // ---- the fleet, when there is one -------------------------------------------------
        if (state.installations.size > 1) {
            SettingsSectionHeader(S(R.string.studio_capacity))
            SettingsGroup {
                state.installations.forEachIndexed { index, inst ->
                    if (index > 0) Separator()
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(inst.accountLabel, color = Ios.Label, fontSize = StudioType.Subhead)
                            Text(
                                if (inst.users < 0) "—" else faNum(inst.users),
                                color = if (inst.isCrowded) Ios.Orange else Ios.SecondaryLabel,
                                fontSize = StudioType.Footnote,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        MeterBar(fraction = inst.loadFraction, tint = if (inst.isCrowded) Ios.Orange else Ios.Blue)
                    }
                }
            }
            SettingsFooter(S(R.string.studio_capacity_note))
        }

        // ---- what the engine refused, lately ----------------------------------------------
        val canSeeActivity = state.installations.isNotEmpty() && state.installations.all { it.can("activity.v1") }
        SettingsSectionHeader(S(R.string.studio_activity))
        if (canSeeActivity && state.activity.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StudioChip(S(R.string.studio_activity_all_kinds), !errorsOnly, Modifier.weight(1f)) { errorsOnly = false }
                StudioChip(S(R.string.studio_activity_only_problems), errorsOnly, Modifier.weight(1f)) { errorsOnly = true }
            }
            Spacer(Modifier.height(6.dp))
        }
        when {
            !canSeeActivity -> SettingsGroup {
                SettingsRow(title = S(R.string.studio_activity_engine_old), icon = StudioIcons.Info, tint = Ios.Gray, showChevron = false)
            }
            state.activity.isEmpty() -> SettingsGroup {
                SettingsRow(title = S(R.string.studio_activity_none), icon = StudioIcons.Ok, tint = Ios.Green, showChevron = false)
            }
            else -> {
                SettingsGroup {
                    state.activity.take(6).forEachIndexed { index, entry ->
                        if (index > 0) Separator()
                        SettingsRow(
                            title = activityLabel(entry.kind),
                            icon = if (entry.severity == ActivitySeverity.ERROR) StudioIcons.Error else StudioIcons.Warning,
                            tint = if (entry.severity == ActivitySeverity.ERROR) Ios.Red else Ios.Orange,
                            subtitle = entry.username ?: entry.detail,
                            value = whenText(entry.ts),
                            showChevron = false,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                SettingsGroup {
                    SettingsActionRow(
                        label = S(R.string.studio_activity_all),
                        icon = StudioIcons.Activity,
                        tint = Ios.Teal,
                        onClick = onOpenActivity,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** «۳ روز مانده» / «۵ ساعت مانده» for a user's attention row. */
@Composable
private fun expiresInText(expiresAt: Long): String = timeLeftText(expiresAt) ?: S(R.string.studio_row_expired)

/**
 * Time left, counted the way the subscription itself states it (04d-studio-page.js ›
 * studioRemainingText, and the first entries of the person's own list): the nearest whole day from a
 * day up -- a 30-day user reads 30 the day they are made -- whole hours under a day, minutes under an
 * hour. The operator, the person's page and their VPN app therefore read the same figure. Null once
 * the time is up.
 *
 * It used to be whole days rounded DOWN, so twelve hours left read as «۰ روز» and the user page
 * called someone with half a day remaining expired.
 */
@Composable
internal fun timeLeftText(expiresAt: Long): String? {
    val (n, unit) = timeLeftParts(expiresAt) ?: return null
    return when (unit) {
        TimeLeftUnit.DAYS -> S(R.string.studio_days_left)
        TimeLeftUnit.HOURS -> S(R.string.studio_hours_left)
        TimeLeftUnit.MINUTES -> S(R.string.studio_minutes_left)
    }.replace("%1\$s", faNum(n))
}

internal enum class TimeLeftUnit { DAYS, HOURS, MINUTES }

/** The figure behind [timeLeftText], for screens that word it their own way. Null once it is up. */
internal fun timeLeftParts(expiresAt: Long, now: Long = System.currentTimeMillis()): Pair<Long, TimeLeftUnit>? {
    val left = expiresAt - now
    if (left <= 0) return null
    return when {
        left >= 86400000L -> ((left + 43200000L) / 86400000L) to TimeLeftUnit.DAYS
        left >= 3600000L -> (left / 3600000L) to TimeLeftUnit.HOURS
        else -> (left / 60000L).coerceAtLeast(1) to TimeLeftUnit.MINUTES
    }
}

private const val StudioStore_STALE = 10 * 60 * 1000L

/**
 * A count, or a dash when the installation cannot measure it.
 *
 * Null and zero are different answers and only one of them is actionable. An engine older than
 * build 7 does not send these fields at all, and `?: 0` would turn "this account cannot count
 * connections" into "no one is connected" — a confident, wrong, unfalsifiable figure on the first
 * screen the operator sees.
 */
@Composable
private fun countOrDash(n: Int?): String =
    if (n == null) S(R.string.studio_unknown_dash) else faNum(n)

/**
 * The engine's stable machine name, in the app's own words.
 *
 * Mapped rather than printed, for the same reason error codes are: `tunnel.device_limit` is a name
 * the engine must be free to keep forever and a sentence the app must be free to reword. An
 * unrecognised kind falls back to «رد شد» rather than showing the raw code, because an operator
 * reading `tunnel.foo` learns nothing they can act on.
 */
@Composable
internal fun activityLabel(kind: String): String = S(
    when (kind) {
        "tunnel.expired" -> R.string.studio_act_expired
        "tunnel.quota" -> R.string.studio_act_quota
        "tunnel.daily_quota" -> R.string.studio_act_daily_quota
        "tunnel.disabled" -> R.string.studio_act_disabled
        "tunnel.unknown_credential" -> R.string.studio_act_unknown
        "tunnel.device_limit" -> R.string.studio_act_device_limit
        "tunnel.conn_limit" -> R.string.studio_act_conn_limit
        "tunnel.ip_limit" -> R.string.studio_act_ip_limit
        // Build 18's reasons. Until they had names here every one of them read as a bare «رد شد»,
        // which tells the operator something was refused and nothing about why.
        "tunnel.device_blocked" -> R.string.studio_act_device_blocked
        "guard.torrent" -> R.string.studio_act_guard_torrent
        "guard.mail" -> R.string.studio_act_guard_mail
        "guard.private" -> R.string.studio_act_guard_private
        "guard.flood" -> R.string.studio_act_guard_flood
        else -> R.string.studio_act_other
    }
)

@Composable
internal fun whenText(ts: Long): String {
    if (ts <= 0) return ""
    val minutes = ((System.currentTimeMillis() - ts) / 60000).coerceAtLeast(0)
    return when {
        minutes < 5 -> S(R.string.studio_device_now)
        minutes < 60 -> S(R.string.studio_sync_minutes).replace("%1\$s", faNum(minutes))
        minutes < 60 * 48 -> S(R.string.studio_sync_hours).replace("%1\$s", faNum(minutes / 60))
        else -> S(R.string.studio_days_left).replace("%1\$s", faNum(minutes / (60 * 24)))
    }
}

/**
 * Latin digits to Persian — the app's existing helper, not a new one.
 *
 * `EmergencyKit.faDigits` is locale-AWARE: it leaves numerals alone when the app is in English. The
 * private copy this file briefly had converted unconditionally, which would have printed Persian
 * numerals to an English-language user.
 */
internal fun faNum(n: Number): String =
    com.mlmvpn.scanner.ui.emergency.faDigits(n.toString())

/**
 * Bytes in the unit that fits, in the app's own language.
 *
 * Megabytes below a gigabyte: "0.03 GB" is true and reads as nothing at all. This mirrors what the
 * subscriber's own page does, so the two never describe the same number differently.
 *
 * **`Locale.US` in the format string is load-bearing.** Without it `String.format` follows the
 * device locale, which on a Persian phone returns "۵٫۰" — Persian digits and a Persian decimal
 * separator — and the version of this function that then parsed that back with `toDouble()` threw
 * `NumberFormatException` and took the whole user list down. It only fired for a user with at least
 * a gigabyte, on a Persian-locale device, which is why no unit test saw it: the JVM's default locale
 * in a test is US. Found by running it on the phone.
 */
@Composable
internal fun bytesFa(b: Long): String {
    val (number, unit) = bytesParts(b)
    return com.mlmvpn.scanner.ui.emergency.faDigits(number) + " " + when (unit) {
        ByteUnit.GB -> S(R.string.studio_unit_gb)
        ByteUnit.MB -> S(R.string.studio_unit_mb)
        ByteUnit.KB -> S(R.string.studio_unit_kb)
    }
}

internal enum class ByteUnit { KB, MB, GB }

/**
 * The number and unit [bytesFa] prints, as plain Latin text -- the one rule for every byte figure in
 * Config Studio, and the same rule the subscriber's own page uses (04d-studio-page.js › studioBytes),
 * so the operator and the person never read one figure two ways.
 *
 * ROUNDED, not truncated. The old version cut megabytes down, so a person who had used exactly their
 * 100 MB was shown «۹۹ مگابایت» here and «۱۰۰» on their page. Binary units throughout: a megabyte
 * is 1,048,576 bytes, which is what the volume field means and what VPN clients display.
 */
internal fun bytesParts(b: Long): Pair<String, ByteUnit> {
    if (b <= 0) return "0" to ByteUnit.MB
    val gb = b / 1073741824.0
    if (gb >= 1) {
        val digits = if (gb >= 100) 0 else if (gb >= 10) 1 else 2
        return trimZeros(String.format(java.util.Locale.US, "%.${digits}f", gb)) to ByteUnit.GB
    }
    val mb = b / 1048576.0
    if (mb >= 1) {
        val digits = if (mb >= 10) 0 else 1
        return trimZeros(String.format(java.util.Locale.US, "%.${digits}f", mb)) to ByteUnit.MB
    }
    return Math.round(b / 1024.0).coerceAtLeast(1).toString() to ByteUnit.KB
}

private fun trimZeros(s: String): String = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s

@Composable
private fun freshnessText(ageMs: Long): String {
    val minutes = (ageMs / 60000).toInt()
    return if (minutes < 60) S(R.string.studio_sync_minutes).replace("%1\$s", faNum(minutes))
    else S(R.string.studio_sync_hours).replace("%1\$s", faNum(minutes / 60))
}
