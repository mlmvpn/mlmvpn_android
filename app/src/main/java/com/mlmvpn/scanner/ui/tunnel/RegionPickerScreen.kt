package com.mlmvpn.scanner.ui.tunnel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.tunnel.IpFormatter
import com.mlmvpn.core.tunnel.RegionMemory
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.theme.PanelShape
import java.util.Locale
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * The exit-country picker, shared by Psiphon and Tor.
 *
 * Shared because the interaction is identical and the DATA is not: Psiphon's list comes from its
 * own server inventory, Tor's from which volunteer relays hold the Exit flag, and those two
 * distributions have nothing to do with each other. So the codes, the names and the per-country
 * detail line are all passed in, and only the behaviour lives here.
 *
 * Three things this does that a plain list would not, each because the underlying choice is a
 * preference rather than a guarantee:
 *
 *  - **The detail line carries the capacity.** "۹ رلهٔ خروجی" next to a country is the honest
 *    predictor of whether the choice will be honoured at all, and it is the only number that
 *    tells a user why their pick was ignored.
 *  - **Favourites and recents are pinned above the list.** Anyone who cares about the country
 *    cycles between two or three, and scrolling past fifty to reach them every time is the
 *    reason a country picker stops being used.
 *  - **"خودکار" is a first-class row at the top**, not an absence. Auto is genuinely faster than
 *    any pinned country, and it has to be as easy to get back to as it was to leave.
 */
@Composable
fun RegionPickerScreen(
    title: String,
    /** Preference namespace for recents/favourites, so the two pickers do not share memory. */
    memoryKey: String,
    codes: List<String>,
    selected: String,
    /** Composable because the name is built against the app's active locale; see CountryLabel. */
    label: @Composable (String) -> String,
    detail: (String) -> String,
    footer: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    backLabel: String,
    /**
     * Whether this picker can measure its countries.
     *
     * Only Psiphon can: it is the one transport whose exit country is chosen from a handful of
     * servers that either answer from this line or do not, and where the difference between them
     * is worth minutes of testing. Tor picks its own exit from thousands of relays and MASQUE has
     * no country list at all.
     */
    benchmarkable: Boolean = false,
    /**
     * What the top «automatic» row is called, where a picker's automatic means something else —
     * GitHub Tunnel's is «حداکثر سرعت», the cloud server's own address. Null keeps the default;
     * `showAuto = false` drops the row (a per-site rule needs a real country).
     */
    autoName: String? = null,
    autoDetail: String? = null,
    showAuto: Boolean = true,
) {
    val context = LocalContext.current
    val memory = remember(memoryKey) { RegionMemory(context, memoryKey) }
    var favourites by remember { mutableStateOf(memory.favourites()) }
    var recents by remember { mutableStateOf(memory.recent()) }
    var query by remember { mutableStateOf("") }

    val isAuto = selected.isBlank() || selected.equals(AUTO, ignoreCase = true)

    // Pinned first, in the order that answers "the one I use" before "the one I might": starred,
    // then recently picked, then everything else alphabetically by NAME rather than by code --
    // a user looks for «آلمان», not for DE.
    val pinned = (favourites.toList() + recents).distinct().filter { it in codes }
    // Sorted by the name the user will actually read, not by the code: an alphabetical list of
    // codes is not alphabetical in Persian, or in English either ("DE" before "DK" is fine,
    // "ایالات متحده" after "آلمان" is not something a code order can produce).
    val locale = LocalConfiguration.current.locales[0]
    val rest = codes.filterNot { it in pinned }
        // A Collator, not String order: by code point «ژ», «ک», «گ» and «ی» sort after «و», which put
        // «ژاپن» and «کره» below «ویتنام». The locale's collation is the alphabet a reader expects.
        .sortedWith(compareBy(java.text.Collator.getInstance(locale)) { CountryLabel.name(it, locale) })

    // Measured results outrank everything, including favourites.
    //
    // A starred country the user picked last month is a guess; a number measured on this line ten
    // minutes ago is not. Once a run has produced results the list is ordered by them -- fastest
    // first, then by round trip, then the ones that did not answer -- so the answer is at the top
    // where the finger already is.
    val results = if (benchmarkable) {
        com.mlmvpn.core.tunnel.PsiphonBench.rows.associateBy { it.region }
    } else {
        emptyMap()
    }
    val ordered = if (results.isEmpty()) {
        pinned + rest
    } else {
        codes.sortedWith(
            compareByDescending<String> { results[it]?.kbps ?: -1 }
                .thenBy { results[it]?.rttMs ?: Long.MAX_VALUE }
                .thenBy { CountryLabel.name(it, locale) }
        )
    }
    // Search matches the localised name, the English name and the code alike, so a Persian UI
    // still finds "Denmark" and "DK" -- people type the code they saw in a config.
    val shown = if (query.isBlank()) ordered else ordered.filter {
        CountryLabel.name(it, locale).contains(query, ignoreCase = true) ||
            CountryLabel.name(it, Locale.ENGLISH).contains(query, ignoreCase = true) ||
            it.contains(query, ignoreCase = true)
    }

    fun choose(code: String) {
        onSelect(code)
        if (code != AUTO) recents = memory.remember(code)
        onBack()
    }

    IosScreen(title = title, onBack = onBack, backLabel = backLabel, scrollable = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .heightIn(min = 36.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = Ios.SecondaryLabel,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(7.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp),
                cursorBrush = SolidColor(Ios.Blue),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(S(R.string.search_country_2), color = Ios.SecondaryLabel, fontSize = 16.sp)
                        }
                        inner()
                    }
                },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (benchmarkable && query.isBlank()) {
                item(key = "bench") {
                    val bench = com.mlmvpn.core.tunnel.PsiphonBench
                    RegionRow(
                        flag = if (bench.running) "⏳" else "⚡",
                        name = if (bench.running) {
                            S(R.string.psiphon_bench_running).format(
                                bench.current.ifBlank { "…" }, bench.done, bench.total
                            )
                        } else {
                            S(R.string.psiphon_bench_start)
                        },
                        detail = if (bench.running) S(R.string.psiphon_bench_stop_hint)
                                 else S(R.string.psiphon_bench_row_detail),
                        selected = false,
                        starred = false,
                        onStar = null,
                        // The same row starts and stops it. A run is minutes long and moves the
                        // tunnel up and down as it goes, so leaving the only way out as "wait"
                        // was not an option; and a separate stop control that only exists while
                        // running is a control that is missing when you look for it.
                        onClick = {
                            if (bench.running) {
                                bench.cancel()
                            } else {
                                com.mlmvpn.scanner.utils.SafeStart.service(context, 
                                    android.content.Intent(
                                        context,
                                        com.mlmvpn.core.tunnel.TunnelVpnService::class.java
                                    ).setAction(
                                        com.mlmvpn.core.tunnel.TunnelVpnService.ACTION_BENCH_REGIONS
                                    )
                                )
                            }
                        },
                    )
                }
            }
            if (query.isBlank() && showAuto) {
                item(key = AUTO) {
                    RegionRow(
                        flag = "🌐",
                        name = autoName ?: S(R.string.automatic_3),
                        detail = autoDetail ?: S(R.string.let_it_take_the_nearest_healthy_route),
                        selected = isAuto,
                        starred = false,
                        onStar = null,
                        onClick = { choose(AUTO) },
                    )
                }
            }
            items(shown, key = { it }) { code ->
                val measured = results[code]
                val testing = benchmarkable &&
                    com.mlmvpn.core.tunnel.PsiphonBench.running &&
                    com.mlmvpn.core.tunnel.PsiphonBench.current == code
                RegionRow(
                    // The country being measured says so on its own row. The progress line at the
                    // top names it too, but the eye is on the list, and a row that is about to
                    // change should look like it.
                    flag = if (testing) "⏳" else IpFormatter.flag(code),
                    name = label(code),
                    // Keyed on the round trip, which is what every measured country now has.
                    // It used to key on the download figure, and once that moved to the winner
                    // alone every row in a finished run read "no answer" -- including the four
                    // that had just been timed.
                    detail = when {
                        testing -> S(R.string.psiphon_bench_testing_now)
                        measured == null -> detail(code)
                        measured.rttMs != null -> S(R.string.psiphon_bench_result).format(
                            measured.rttMs, (measured.connectMs ?: 0L) / 1000.0
                        )
                        else -> S(R.string.psiphon_bench_no_answer)
                    },
                    selected = !isAuto && code.equals(selected, ignoreCase = true),
                    starred = code in favourites,
                    onStar = { favourites = memory.toggleFavourite(code) },
                    onClick = { choose(code) },
                )
            }
            item(key = "footer") {
                Spacer(Modifier.size(10.dp))
                Text(
                    footer,
                    color = Ios.SecondaryLabel,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun RegionRow(
    flag: String,
    name: String,
    detail: String,
    selected: Boolean,
    starred: Boolean,
    onStar: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass(PanelShape)
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = Ios.Label, fontSize = 16.sp, maxLines = 1)
            if (detail.isNotBlank()) {
                Text(detail, color = Ios.SecondaryLabel, fontSize = 11.sp, maxLines = 2)
            }
        }
        if (onStar != null) {
            Icon(
                if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = null,
                tint = if (starred) Ios.Yellow else Ios.SecondaryLabel.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .clickable(onClick = onStar)
                    .padding(5.dp),
            )
        }
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Ios.Blue,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

internal const val AUTO = "auto"
