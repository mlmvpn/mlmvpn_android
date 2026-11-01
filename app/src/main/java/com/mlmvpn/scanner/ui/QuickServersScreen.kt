package com.mlmvpn.scanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.quick.*
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.ControlShape
import com.mlmvpn.scanner.ui.theme.PanelShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The quick-connect catalogue: pick a country, sweep it, keep what answers.
//
// What this replaces: a `Dialog` at full-screen size containing a hand-built header row, a
// `Surface` per section, filled `Button`s beside `OutlinedButton`s, `OutlinedTextField`s for the
// numbers, a second full-screen `Dialog` for the country picker, and Latin digits throughout. The
// logic underneath -- QuickScanSession, the two-stage sweep, the balanced search, the blocklist --
// is unchanged; every pixel of the presentation is not.
// =================================================================================================

@Composable
fun QuickServersScreen(
    onDismiss: () -> Unit,
    /** Hand proven servers to the connect screen's saved list. */
    onAdopt: (List<QuickNode>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var catalog by remember { mutableStateOf<QuickCatalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshStage by remember { mutableStateOf(0 to 0) }
    var refreshTitle by remember { mutableStateOf("") }
    var cacheAge by remember { mutableStateOf<Long?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var selectedCountry by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    // Owned by QuickScanSession, not by this composable: a sweep has to survive the user
    // navigating away, which is exactly what a composition-scoped coroutine cannot do.
    val results by QuickScanSession.results.collectAsState()
    val progress by QuickScanSession.progress.collectAsState()
    val scanning by QuickScanSession.running.collectAsState()
    val sessionError by QuickScanSession.error.collectAsState()
    val sessionLabel by QuickScanSession.label.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    // The targeted sweep: "N servers from M countries".
    var wantCount by remember { mutableStateOf(50) }
    var wantCountries by remember { mutableStateOf(5) }

    /** Ids already on the connect screen, so the list can say which are not new. */
    val savedIds = remember { QuickSavedStore.all(context).map { it.id }.toHashSet() }

    // Opening the screen reads only what is already stored -- memory, then disk. It never
    // touches the network, because the download is six feeds and ~12,000 lines and the user
    // came here to look at a list, not to wait for one. Refreshing is a button.
    LaunchedEffect(Unit) {
        // Off the main thread: the stored list is ~12,000 lines of JSON, each named after its
        // country, and reading it there froze the screen as it opened (freeze reports, 1.2.41).
        val (c, age) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            QuickConnectRepository.cached(context) to QuickConnectRepository.cacheAgeMs(context)
        }
        catalog = c
        cacheAge = age
        loading = false
    }

    fun refreshNow() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            loadError = null
            refreshStage = 0 to QuickConnectRepository.sourceCount
            refreshTitle = ""
            try {
                catalog = withContext(Dispatchers.IO) {
                    QuickConnectRepository.refresh(context) { done, total, title ->
                        refreshStage = done to total
                        refreshTitle = title
                    }
                }
                cacheAge = 0L
            } catch (e: Exception) {
                loadError = e.message ?: S(R.string.could_not_fetch_the_server_list)
            } finally {
                refreshing = false
            }
        }
    }

    // Back leaves the screen; it does not cancel the sweep. Stopping is an explicit request
    // (the "stop" button), never a side effect of navigating.
    BackHandler(enabled = true) { if (showPicker) showPicker = false else onDismiss() }

    fun startScan(all: Boolean) {
        if (scanning) return
        val countryName = catalog?.countries?.firstOrNull { it.code == selectedCountry }?.name ?: S(R.string.all_countries)
        QuickScanSession.startScan(
            context = context,
            country = selectedCountry,
            // "Check them all" means exactly that -- no target to stop early at. The quick sweep
            // stops at 20 because the user wants to get online, not to survey.
            want = if (all) Int.MAX_VALUE else 20,
            label = if (all) S(R.string.scan_every_countryname_server, countryName) else S(R.string.quick_scan_countryname, countryName),
        )
    }

    /** "N servers from M countries" -- every result proven, spread evenly across the countries. */
    fun startBalanced() {
        if (scanning) return
        QuickScanSession.startBalanced(
            context = context,
            countryCount = wantCountries,
            want = wantCount,
            label = S(R.string.find) + faCount(wantCount) + S(R.string.servers_from) + faCount(wantCountries) + S(R.string.countries_2),
        )
    }

    val selectedRow = catalog?.countries?.firstOrNull { it.code == selectedCountry }

    // ---- the country picker, as a page ----------------------------------------------------
    if (showPicker) {
        QuickCountryScreen(
            catalog = catalog,
            selected = selectedCountry,
            onPick = { code ->
                selectedCountry = code
                showPicker = false
                // A running sweep keeps its results; only an idle screen is cleared.
                QuickScanSession.reset()
            },
            onBack = { showPicker = false },
            onForgetVerified = {
                QuickConnectRepository.forgetVerified(context)
                showPicker = false
                refreshNow()
            },
        )
        return
    }

    IosScreen(
        title = S(R.string.server_list),
        onBack = onDismiss,
        backLabel = S(R.string.quick_connect),
        scrollable = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                bottom = LocalSystemBottomPadding.current + 16.dp,
            ),
        ) {
            item {

            SettingsSectionHeader(
                when {
                    loading -> S(R.string.loading)
                    catalog != null -> faCount(catalog!!.total) + S(R.string.servers_2) + ageLabel(cacheAge)
                    else -> S(R.string.no_list_saved_yet)
                }
            )

            // ---- the catalogue itself ---------------------------------------------------
            SettingsGroup {
                QuickCountryRow(
                    flag = selectedRow?.flag ?: "🌐",
                    name = selectedRow?.name ?: S(R.string.all_countries),
                    detail = selectedRow?.let { faCount(it.count) + S(R.string.servers_3) }
                        ?: (catalog?.let { faCount(it.total) + S(R.string.servers_3) } ?: "…"),
                    enabled = catalog != null,
                    chevron = true,
                    onClick = { showPicker = true },
                )
                Separator()
                // Getting a fresh list is an explicit act with visible progress, not something
                // that happens silently when the screen opens. Six feeds take real time, and a
                // spinner with no idea which one it is on is indistinguishable from a hang.
                if (refreshing) {
                    val (done, total) = refreshStage
                    val target = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    val fraction by animateFloatAsState(
                        targetValue = target,
                        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
                        label = "refreshProgress",
                    )
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                color = Ios.SecondaryLabel,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                refreshTitle.ifBlank { S(R.string.fetching) },
                                color = Ios.Label,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                S(R.string.source) + faCount(done) + S(R.string.of) + faCount(total),
                                color = Ios.SecondaryLabel,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = fraction,
                            color = Ios.Blue,
                            trackColor = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                        )
                    }
                } else {
                    com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                        label = if (catalog != null) S(R.string.refresh_list) else S(R.string.download_server_list),
                        icon = Icons.Default.CloudDownload,
                        tint = if (catalog == null) Ios.Orange else Ios.Gray,
                        labelColor = Ios.Label,
                        enabled = !scanning,
                    ) { refreshNow() }
                }
            }

            if (catalog == null && !refreshing) {
                SettingsFooter(S(R.string.the_list_is_saved_on_your_phone))
            }

            // ---- the two sweeps -----------------------------------------------------------
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickButton(
                    label = if (scanning) S(R.string.stop) else S(R.string.quick_scan),
                    enabled = catalog != null,
                    primary = !scanning,
                    stop = scanning,
                    modifier = Modifier.weight(1f),
                ) { if (scanning) QuickScanner.stop() else startScan(false) }
                QuickButton(
                    label = S(R.string.scan_all_servers),
                    enabled = catalog != null && !scanning,
                    modifier = Modifier.weight(1f),
                ) { startScan(true) }
            }
            SettingsFooter(
                S(R.string.pick_a_country_and_tap_quick_scan) +
                    S(R.string.country_more_thorough_but_it_takes_a)
            )

            // ---- the targeted sweep -------------------------------------------------------
            //
            // Separate from the two buttons above because it ignores the country selector
            // entirely: it picks its own countries, by pool size.
            SettingsSectionHeader(S(R.string.targeted_search))
            SettingsGroup {
                NumberRow(
                    label = S(R.string.number_of_servers),
                    value = wantCount,
                    min = 1,
                    max = (catalog?.total ?: 1000).coerceAtLeast(1),
                    enabled = !scanning,
                    onChange = { wantCount = it },
                )
                Separator()
                NumberRow(
                    label = S(R.string.from_how_many_countries),
                    value = wantCountries,
                    min = 1,
                    max = (catalog?.countries?.size ?: 1).coerceAtLeast(1),
                    enabled = !scanning,
                    onChange = { wantCountries = it },
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                QuickButton(
                    label = if (scanning) S(R.string.stop)
                            else S(R.string.find) + faCount(wantCount) + S(R.string.servers_from) + faCount(wantCountries) + S(R.string.countries_2),
                    enabled = catalog != null,
                    primary = !scanning,
                    stop = scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) { if (scanning) QuickScanner.stop() else startBalanced() }
            }
            SettingsFooter(
                S(R.string.about) +
                    faCount(kotlin.math.ceil(wantCount.toDouble() / wantCountries.coerceAtLeast(1)).toInt()) +
                    S(R.string.servers_per_country_every_result_is_confirmed)
            )

            if (progress.stage != QuickScanner.Stage.IDLE) {
                ScanStrip(progress, sessionLabel, scanning)
            }

            (loadError ?: sessionError)?.let {
                Text(
                    it,
                    color = Ios.Orange,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            catalog?.takeIf { it.stale }?.let {
                Text(
                    S(R.string.showing_the_saved_list_the_fresh_download),
                    color = Ios.Orange,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }

            // ---- the results --------------------------------------------------------------
            if (results.isNotEmpty()) {
                SettingsSectionHeader(faCount(results.size) + S(R.string.working_servers))
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickButton(
                        label = S(R.string.add_to_the_connection_page),
                        primary = true,
                        modifier = Modifier.weight(1f),
                    ) { onAdopt(results) }
                    QuickButton(
                        label = S(R.string.clear),
                        destructive = true,
                        modifier = Modifier.width(110.dp),
                    ) { confirmDelete = true }
                }
                Spacer(Modifier.height(10.dp))
            }
            }

                if (results.isNotEmpty()) {
                    item {
                        SettingsGroup {
                            results.forEachIndexed { i, node ->
                                if (i > 0) Separator()
                                ResultRow(
                                    node = node,
                                    alreadySaved = node.id in savedIds,
                                    onDelete = {
                                        QuickSavedStore.remove(context, listOf(node.id))
                                        QuickConnectRepository.invalidate()
                                        QuickScanSession.removeResults(listOf(node.id))
                                    },
                                )
                            }
                        }
                    }
                }

                catalog?.sources?.takeIf { it.isNotEmpty() }?.let { sources ->
                    item {
                        Spacer(Modifier.height(18.dp))
                        SettingsSectionHeader(S(R.string.sources))
                        SettingsGroup {
                            sources.forEachIndexed { i, s ->
                                if (i > 0) Separator()
                                SettingsRow(
                                    title = s.title,
                                    value = if (s.ok) faCount(s.usable) + S(R.string.servers_3) else S(R.string.failed),
                                    showChevron = false,
                                )
                            }
                        }
                        val blocked = QuickBlocklist.count(context)
                        if (blocked > 0) {
                            Spacer(Modifier.height(10.dp))
                            SettingsGroup {
                                com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                                    label = S(R.string.restore) + faCount(blocked) + S(R.string.deleted_servers),
                                    icon = Icons.Default.History,
                                    tint = Ios.Gray,
                                ) {
                                    QuickBlocklist.clear(context)
                                    QuickConnectRepository.invalidate()
                                    // Restoring deleted servers changes what the feeds parse
                                    // to, so the list has to be rebuilt from the network.
                                    refreshNow()
                                }
                            }
                        }
                        SettingsFooter(QuickConnectRepository.SOURCE_REFRESH_LABEL)
                    }
                }
            }
        }

    if (confirmDelete) {
        val dead = results.filter { it.delay <= 0 }
        IosAlert(
            title = S(R.string.remove_from_list),
            message = S(R.string.deleted_servers_are_not_downloaded_by_later) +
                S(R.string.scan),
            onDismiss = { confirmDelete = false },
            actions = listOf(
                IosAlertAction(S(R.string.cancel), onClick = { confirmDelete = false }),
                IosAlertAction(
                    S(R.string.dead_3) + faCount(dead.size) + ")",
                    onClick = {
                        QuickSavedStore.remove(context, dead.map { it.id })
                        QuickConnectRepository.invalidate()
                        QuickScanSession.removeResults(dead.map { it.id })
                        confirmDelete = false
                    },
                ),
                IosAlertAction(
                    S(R.string.all_3) + faCount(results.size) + ")",
                    onClick = {
                        QuickSavedStore.remove(context, results.map { it.id })
                        QuickConnectRepository.invalidate()
                        QuickScanSession.removeResults(results.map { it.id })
                        confirmDelete = false
                    },
                    destructive = true,
                ),
            ),
        )
    }
}

/**
 * The country list, on a page.
 *
 * It was a second full-screen `Dialog` stacked on the first, with its own ArrowBack row and an
 * `OutlinedTextField` for the search. Same content, in the frame the rest of the app uses.
 */
@Composable
private fun QuickCountryScreen(
    catalog: QuickCatalog?,
    selected: String?,
    onPick: (String?) -> Unit,
    onBack: () -> Unit,
    onForgetVerified: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val rows = remember(catalog, query) {
        val all = catalog?.countries.orEmpty()
        if (query.isBlank()) all
        else all.filter { it.name.contains(query, true) || it.code.contains(query, true) }
    }

    IosScreen(title = S(R.string.country), onBack = onBack, backLabel = S(R.string.servers_4), scrollable = false) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(ControlShape)
                    .background(Color.White.copy(alpha = 0.07f))
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = Ios.SecondaryLabel,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Ios.Label, fontSize = 15.sp),
                    cursorBrush = SolidColor(Ios.Blue),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                S(R.string.search_country),
                                color = Ios.SecondaryLabel.copy(alpha = 0.7f),
                                fontSize = 15.sp,
                            )
                        }
                        inner()
                    },
                )
            }

            Spacer(Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = LocalSystemBottomPadding.current + 20.dp),
            ) {
                item {
                    SettingsGroup {
                        QuickCountryRow(
                            flag = "🌐",
                            name = S(R.string.all_countries),
                            detail = faCount(catalog?.total ?: 0) + S(R.string.servers_3),
                            selected = selected == null,
                            onClick = { onPick(null) },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
                item {
                    SettingsGroup {
                        rows.forEachIndexed { i, row ->
                            if (i > 0) Separator()
                            QuickCountryRow(
                                flag = row.flag,
                                name = row.name,
                                detail = faCount(row.count) + S(R.string.servers_3),
                                selected = selected == row.code,
                                onClick = { onPick(row.code) },
                            )
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(18.dp))
                    SettingsGroup {
                        com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                            label = S(R.string.forget_verified_countries),
                            icon = Icons.Default.Refresh,
                            tint = Ios.Gray,
                        ) { onForgetVerified() }
                    }
                    SettingsFooter(
                        S(R.string.each_server_s_country_is_verified_once) +
                            S(R.string.it_is_wrong_tap_this_to_have)
                    )
                }
            }
        }
    }
}

/** One country. The tick is the selection; nothing else changes colour. */
@Composable
private fun QuickCountryRow(
    flag: String,
    name: String,
    detail: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = 21.sp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = if (enabled) Ios.Label else Ios.SecondaryLabel,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, color = Ios.SecondaryLabel, fontSize = 12.sp)
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Ios.Green,
                modifier = Modifier.size(19.dp),
            )
        } else if (chevron) {
            Icon(
                Icons.Default.ChevronLeft,
                contentDescription = null,
                tint = Ios.Chevron,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * A number the user can actually choose, rather than pick from.
 *
 * Steppers for nudging, and the field itself for typing an exact figure. Empty is allowed while
 * typing -- clamping every keystroke means deleting "50" to type "120" snaps to the minimum and
 * fights the user for the field. The digits shown are Persian like everywhere else, but the field
 * accepts either: a phone keyboard set to English must not be unusable here.
 */
@Composable
private fun NumberRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Ios.Label, fontSize = 16.sp)
            Text(
                S(R.string.between) + faCount(min) + S(R.string.and) + faCount(max),
                color = Ios.SecondaryLabel,
                fontSize = 11.sp,
            )
        }
        Stepper(Icons.Default.Remove, enabled && value > min) {
            onChange((value - stepFor(value)).coerceIn(min, max))
        }
        Box(
            modifier = Modifier
                .width(76.dp)
                .clip(ControlShape)
                .background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 8.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(6)
                    text = digits
                    digits.trim().toIntOrNull()?.let { onChange(it.coerceIn(min, max)) }
                },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    color = Ios.Label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(Ios.Blue),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Stepper(Icons.Default.Add, enabled && value < max) {
            onChange((value + stepFor(value)).coerceIn(min, max))
        }
    }
}

@Composable
private fun Stepper(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) Ios.Label else Ios.SecondaryLabel.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Bigger numbers step in bigger jumps, so 10 -> 500 is not fifty taps. */
private fun stepFor(value: Int): Int = when {
    value < 20 -> 1
    value < 100 -> 5
    value < 500 -> 25
    else -> 100
}

/** One proven server. */
@Composable
private fun ResultRow(node: QuickNode, alreadySaved: Boolean, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(node.flag ?: "🏳️", fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    node.name,
                    color = Ios.Label,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                // "Already here" vs "new to you" -- so a second sweep of the same country
                // shows at a glance what it actually added.
                if (alreadySaved) Badge(S(R.string.tested), Ios.SecondaryLabel) else Badge(S(R.string.new_str), Ios.Green)
                if (node.verified) {
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = S(R.string.verified_country),
                        tint = Ios.Green,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Text(
                listOfNotNull(node.countryName, node.protocol.uppercase()).joinToString(" · "),
                color = Ios.SecondaryLabel,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (node.delay > 0) faCount(node.delay) + " ms" else "—",
            color = when {
                node.delay in 1..399 -> Ios.Green
                node.delay in 400..999 -> Ios.Orange
                node.delay > 0 -> Ios.Destructive
                else -> Ios.SecondaryLabel
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            Icons.Default.Close,
            contentDescription = S(R.string.delete),
            tint = Ios.Destructive,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .padding(7.dp),
        )
    }
}

/**
 * Live progress for the sweep.
 *
 * Two bars, not one. The reachability probe and the real-connection test run concurrently, so a
 * single bar fed from whichever reported last jumps back and forth between two unrelated fractions
 * several times a second. Each stage gets its own bar and its own denominator: stage 1 counts
 * against every candidate, stage 2 against the hosts stage 1 has found so far. That second
 * denominator grows while the run is going, which is exactly why it cannot share a track.
 */
@Composable
private fun ScanStrip(p: QuickScanner.Progress, label: String, running: Boolean) {
    val done = p.stage == QuickScanner.Stage.DONE

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (label.isNotBlank()) {
            Text(
                if (running) label else S(R.string.label_finished, label),
                color = Ios.Label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (done) {
            Text(
                when {
                    p.empty -> S(R.string.no_responding_server_was_found)
                    p.stopped -> S(R.string.stopped) + faCount(p.found) + S(R.string.working_servers_kept)
                    else -> faCount(p.found) + S(R.string.working_servers_found)
                },
                color = if (p.empty) Ios.Orange else Ios.Green,
                fontSize = 12.sp,
            )
        } else {
            StageBar(
                title = S(R.string.s_1_reachability),
                detail = faCount(p.probed) + S(R.string.of) + faCount(p.total) + " · " + faCount(p.reachable) + S(R.string.responding),
                fraction = if (p.total > 0) (p.probed.toFloat() / p.total).coerceIn(0f, 1f) else 0f,
                color = Ios.Blue,
            )
            Spacer(Modifier.height(10.dp))
            StageBar(
                title = S(R.string.s_2_real_connection),
                detail = faCount(p.realTested) + S(R.string.of) + faCount(p.reachable) + " · " + faCount(p.found) + S(R.string.working),
                fraction = if (p.reachable > 0) (p.realTested.toFloat() / p.reachable).coerceIn(0f, 1f) else 0f,
                color = Ios.Green,
            )
        }
    }
}

/** One stage: a caption row and its own track. */
@Composable
private fun StageBar(title: String, detail: String, fraction: Float, color: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ios.SecondaryLabel, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(detail, color = Ios.SecondaryLabel, fontSize = 11.sp)
        }
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = fraction,
            color = color,
            trackColor = Color.White.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
        )
    }
}

/**
 * A button on this screen.
 *
 * These were a filled `Button` carrying `iosButtonColors` beside an `OutlinedButton` -- the accent
 * slab and the barely-visible outline, the pair this app replaced everywhere else with one tinted
 * panel whose fill says which one commits. `stop` is the running state, the one case where the
 * tint is not the accent: a sweep in progress is a thing to interrupt, not to confirm.
 */
@Composable
private fun QuickButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    stop: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(PanelShape)
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.05f)
                    stop -> Ios.Orange.copy(alpha = 0.22f)
                    destructive -> Ios.Destructive.copy(alpha = 0.18f)
                    primary -> Ios.Blue.copy(alpha = 0.24f)
                    else -> Color.White.copy(alpha = 0.09f)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) Ios.Label else Ios.SecondaryLabel,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** How long ago the stored list was built, in words. */
private fun ageLabel(ageMs: Long?): String {
    if (ageMs == null) return S(R.string.no_saved_list)
    val minutes = ageMs / 60_000
    return when {
        minutes < 1L -> S(R.string.updated_just_now)
        minutes < 60L -> faCount(minutes.toInt()) + S(R.string.minutes_ago)
        minutes < 60L * 24 -> faCount((minutes / 60).toInt()) + S(R.string.hours_ago)
        else -> faCount((minutes / (60 * 24)).toInt()) + S(R.string.days_ago)
    }
}
