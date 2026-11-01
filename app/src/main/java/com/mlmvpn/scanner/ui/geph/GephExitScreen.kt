package com.mlmvpn.scanner.ui.geph

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mlmvpn.core.geph.GephAccount
import com.mlmvpn.core.geph.GephEngine
import com.mlmvpn.core.geph.GephExitChoice
import com.mlmvpn.core.geph.GephExits
import com.mlmvpn.core.geph.GephSettings
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.emergency.faDigits
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.tunnel.CountryLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «سرور خروج» for Geph: automatic, a country, or one city in it -- with the network's own load
 * figures, and a way to MEASURE which one is fastest from this line instead of guessing.
 *
 * Exit choice is Geph's biggest speed lever: on the desktop the best free exit measured 3.5 times
 * faster than the one `auto` picked. Servers this account cannot use are listed, not hidden, and
 * say why.
 */
@Composable
fun GephExitScreen(onBack: () -> Unit, backLabel: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exits by remember { mutableStateOf(GephExits.cached(ctx)) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(GephSettings.exit(ctx)) }
    val level = GephAccount.cachedInfo(ctx)?.level ?: GephAccount.Level.FREE

    // The fastest-server run.
    var probing by remember { mutableStateOf(false) }
    var probingNow by remember { mutableStateOf<GephExitChoice?>(null) }
    // The last run, read back: it stays on the screen after connecting and coming back.
    val lastRun = remember { GephExits.lastRun(ctx) }
    val probeResults = remember { mutableStateListOf<GephExits.Probe>().apply { lastRun?.second?.let { addAll(it) } } }
    var lastRunAt by remember { mutableStateOf(lastRun?.first ?: 0L) }
    var cancel by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { GephExits.refresh(ctx) }
        r.onSuccess { if (it.isNotEmpty()) exits = it }
            .onFailure { if (exits.isEmpty()) loadError = it.message }
        loading = false
    }

    fun choose(choice: GephExitChoice) {
        selected = choice
        GephSettings.setExit(ctx, choice)
        applyGephNow(ctx)
    }

    IosScreen(title = stringResource(R.string.geph_exit_title), onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(12.dp))

        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.geph_exit_auto),
                icon = if (selected.isAuto) Icons.Default.Check else Icons.Default.AutoMode,
                tint = if (selected.isAuto) Ios.Green else Ios.Gray,
                subtitle = stringResource(R.string.geph_exit_auto_sub),
                showChevron = false,
                onClick = { choose(GephExitChoice.AUTO) },
            )
        }

        // ── the measurement ──
        SettingsSectionHeader(stringResource(R.string.geph_fastest_section))
        SettingsGroup {
            SettingsActionRow(
                label = if (probing) stringResource(
                    R.string.geph_fastest_running,
                    probingNow?.country?.let { CountryLabel.withFlag(it) } ?: "",
                ) else stringResource(if (probeResults.isEmpty()) R.string.geph_fastest_run else R.string.geph_fastest_rerun),
                icon = Icons.Default.Speed,
                tint = Ios.Green,
                busy = probing,
                enabled = !probing && exits.isNotEmpty() && GephAccount.hasAccount(ctx),
                onClick = {
                    if (GephEngine.isMainAlive) {
                        probeError = ctx.getString(R.string.geph_fastest_busy)
                        return@SettingsActionRow
                    }
                    probing = true
                    cancel = false
                    probeError = null
                    probeResults.clear()
                    // One candidate per usable country: its lightest city. Measuring every
                    // server would cost minutes for a difference the country already decides.
                    val candidates = GephExits.countries(exits, level)
                        .filter { it.usable(level) > 0 }
                        .map { c ->
                            val city = c.cities.firstOrNull { city -> city.exits.any { it.allows(level) } }
                            GephExitChoice(c.code, city?.name)
                        }
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                GephExits.findFastest(
                                    ctx, candidates,
                                    onProgress = { _, _, now, done ->
                                        probingNow = now
                                        if (done != null) probeResults.add(done)
                                    },
                                    cancelled = { cancel },
                                )
                            }
                        }
                        r.onFailure {
                            probeError = if (it.message == GephExits.BUSY) ctx.getString(R.string.geph_fastest_busy)
                            else ctx.getString(R.string.geph_error, it.message ?: "")
                        }
                        probing = false
                        if (probeResults.isNotEmpty()) lastRunAt = System.currentTimeMillis()
                        probingNow = null
                    }
                },
            )
            if (probing) {
                Separator()
                SettingsActionRow(
                    label = stringResource(R.string.geph_cancel),
                    icon = Icons.Default.Lock,
                    tint = Ios.Red,
                    onClick = { cancel = true },
                )
            }
            if (!probing && probeResults.isNotEmpty() && lastRunAt > 0) {
                Separator()
                SettingsRow(
                    title = stringResource(R.string.geph_fastest_age, gephAgo(lastRunAt)),
                    icon = Icons.Default.History,
                    tint = Ios.Gray,
                    showChevron = false,
                )
            }
            probeResults.sortedWith(
                compareByDescending<GephExits.Probe> { it.ok }.thenByDescending { it.mbit },
            ).forEachIndexed { i, p ->
                Separator()
                SettingsRow(
                    title = CountryLabel.withFlag(p.exit.country!!) + (p.exit.city?.let { " · $it" } ?: ""),
                    value = if (p.ok) stringResource(R.string.geph_mbit, mbitText(p.mbit)) else stringResource(R.string.geph_probe_failed),
                    subtitle = if (p.ok) stringResource(
                        R.string.geph_probe_detail,
                        faDigits(p.ttfbMs.toString()),
                        faDigits((p.connectMs / 1000.0).let { String.format(java.util.Locale.US, "%.1f", it) }),
                    ) + if (i == 0 && p.ok) " · " + stringResource(R.string.geph_fastest_badge) else ""
                    else when (p.error) {
                        GephExits.NO_SESSION -> stringResource(R.string.geph_probe_no_session)
                        GephExits.NO_DATA -> stringResource(R.string.geph_probe_no_data)
                        GephExits.ENGINE_EXITED -> stringResource(R.string.geph_probe_engine_exited)
                        else -> p.error
                    },
                    showChevron = false,
                    onClick = if (p.ok) ({ choose(p.exit) }) else null,
                )
            }
        }
        probeError?.let { SettingsFooter(it) }
        SettingsFooter(stringResource(R.string.geph_fastest_footer))

        // ── the list ──
        if (loading && exits.isEmpty()) SettingsFooter(stringResource(R.string.geph_exits_loading))
        loadError?.let { SettingsFooter(stringResource(R.string.geph_error, it)) }

        GephExits.countries(exits, level).forEach { country ->
            val usable = country.usable(level) > 0
            SettingsSectionHeader(
                CountryLabel.withFlag(country.code) + " · " +
                    stringResource(R.string.geph_servers_count, faDigits(country.exits.size.toString())),
            )
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.geph_any_city),
                    icon = if (selected.country == country.code && selected.city.isNullOrBlank()) Icons.Default.Check else null,
                    tint = Ios.Green,
                    value = if (!usable) stringResource(R.string.geph_plus_only) else null,
                    showChevron = false,
                    onClick = if (usable) ({ choose(GephExitChoice(country.code, null)) }) else null,
                )
                country.cities.forEach { city ->
                    val cityUsable = city.exits.any { it.allows(level) }
                    val isSel = selected.country == country.code && selected.city == city.name
                    Separator()
                    SettingsRow(
                        title = city.name.ifBlank { country.code },
                        icon = if (isSel) Icons.Default.Check else Icons.Default.LocationCity,
                        tint = if (isSel) Ios.Green else Ios.Gray,
                        value = if (!cityUsable) stringResource(R.string.geph_plus_only)
                            else stringResource(R.string.geph_load, faDigits((city.lightestLoad * 100).toInt().toString())),
                        subtitle = listOfNotNull(
                            stringResource(R.string.geph_servers_count, faDigits(city.exits.size.toString())),
                            if (city.streaming) stringResource(R.string.geph_streaming) else null,
                        ).joinToString(" · "),
                        showChevron = false,
                        onClick = if (cityUsable) ({ choose(GephExitChoice(country.code, city.name)) }) else null,
                    )
                }
            }
        }
        if (exits.isNotEmpty()) SettingsFooter(stringResource(R.string.geph_exits_footer))
        Spacer(Modifier.height(28.dp))
    }
}

/** "3 minutes ago", for the age of the last measurement. */
@Composable
private fun gephAgo(at: Long): String {
    val min = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> stringResource(R.string.geph_ago_now)
        min < 60 -> stringResource(R.string.geph_ago_min, faDigits(min.toString()))
        min < 48 * 60 -> stringResource(R.string.geph_ago_hour, faDigits((min / 60).toString()))
        else -> stringResource(R.string.geph_ago_day, faDigits((min / 1440).toString()))
    }
}
