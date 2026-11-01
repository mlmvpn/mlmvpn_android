package com.mlmvpn.scanner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.PanelShape
import com.mlmvpn.scanner.utils.S

/**
 * About, in the shape iOS gives Settings › General › About -- and now in that place too.
 *
 * It used to be a tile on the home grid and a tab of its own, which put "who wrote this app and
 * where do I find them" on the same level as a transport. It is the last row of Settings ›
 * About now, under the version, the updater and the crash reports, because that group is already
 * the answer to every question this screen answers.
 *
 * Being a settings page rather than a tab is what the parameters are for. The changelog is no
 * longer opened by a `showChangelog` flag held here: it is a route on the settings stack, pushed
 * through [onOpenChangelog], so on a tablet or a television it lands in the detail pane beside
 * the settings list instead of replacing the whole screen -- and physical back walks
 * Changelog → About → Settings through the one stack that owns the rest of the tree.
 *
 * The 1500 lines of release notes live in [changelogVersions] in ChangelogData.kt. Nothing about
 * their content changed.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    backLabel: String,
    onOpenChangelog: () -> Unit,
) {
    val context = LocalContext.current
    var versionTaps by remember { mutableIntStateOf(0) }

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    val appIcon = remember {
        try {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = android.graphics.Bitmap.createBitmap(
                drawable.intrinsicWidth.takeIf { it > 0 } ?: 200,
                drawable.intrinsicHeight.takeIf { it > 0 } ?: 200,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    val isFa = com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale().language == "fa"
    val versions = remember(isFa) { changelogVersions(isFa) }

    fun open(url: String) {
        try {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                )
            )
        } catch (e: Exception) {
            android.widget.Toast
                .makeText(context, url, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    IosScreen(
        title = stringResource(R.string.about_title),
        onBack = onBack,
        backLabel = backLabel,
    ) {
        Spacer(Modifier.height(24.dp))

        // The identity block iOS puts at the top of About: the mark, the name, the version, and
        // nothing else competing with them.
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    // A squircle, not a circle: it is the same mark the home screen shows as a
                    // tile, and a circle here made it a different object from the one you tapped.
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)),
                )
            } else {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = Ios.SecondaryLabel,
                    modifier = Modifier.size(96.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("MLMVPN", color = Ios.Label, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            // The version stays in Latin digits. It is an identifier people read back to us in a
            // bug report, not a measurement.
            Text(
                stringResource(R.string.about_version, versionName),
                // Seven taps toggle the hidden developer mode (Cloudflare Doctor's strategy details):
                // ids, latencies and counts only, never a credential.
                modifier = Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) {
                    versionTaps++
                    if (versionTaps >= 7) {
                        val prefs = context.getSharedPreferences("cfnet_ui", android.content.Context.MODE_PRIVATE)
                        val on = !prefs.getBoolean("developer", false)
                        prefs.edit().putBoolean("developer", on).apply()
                        android.widget.Toast.makeText(context, com.mlmvpn.scanner.utils.S(if (on) R.string.cfnet_dev_on else R.string.cfnet_dev_off),
                            android.widget.Toast.LENGTH_SHORT).show()
                        versionTaps = 0
                    }
                },
                color = Ios.SecondaryLabel,
                fontSize = 15.sp,
            )
        }

        Spacer(Modifier.height(28.dp))

        SettingsSectionHeader(if (isFa) "تازه‌ها" else "WHAT'S NEW")
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.about_changelog),
                icon = Icons.Default.NewReleases,
                tint = Ios.Orange,
                value = versions.firstOrNull()?.versionTitle,
                onClick = onOpenChangelog,
            )
        }
        SettingsFooter(
            if (isFa) {
                S(R.string.everything_that_changed_in_recent_versions_with) +
                    S(R.string.it_is_probably_written_up_there_where)
            } else {
                "Everything that changed in recent releases, with the reasoning. If something " +
                    "moved, this is where it says where it went."
            }
        )

        SettingsSectionHeader(if (isFa) "دربارهٔ برنامه" else "ABOUT")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .frostedGlass(CardShape)
                .padding(18.dp),
        ) {
            Text(
                stringResource(R.string.about_desc),
                color = Ios.Label,
                fontSize = 14.sp,
                lineHeight = 25.sp,
                textAlign = TextAlign.Justify,
            )
        }

        // Right under the description: supporting the app, in the phone's own browser.
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            SettingsRow(
                title = if (isFa) "حمایت از ما" else "Support us",
                subtitle = "donito.me/ehsan2noven",
                icon = Icons.Default.Favorite,
                tint = androidx.compose.ui.graphics.Color(0xFFFF2D55),
                onClick = { com.mlmvpn.scanner.utils.Donate.open(context) },
            )
        }

        SettingsSectionHeader(if (isFa) "ارتباط با ما" else "LINKS")
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.about_telegram),
                subtitle = "t.me/mlmvpn",
                icon = Icons.Default.Send,
                tint = Ios.Teal,
                onClick = { open("https://t.me/mlmvpn") },
            )
            Separator()
            SettingsRow(
                title = stringResource(R.string.about_github),
                subtitle = "github.com/mlmvpn",
                icon = Icons.Default.Code,
                tint = Ios.Gray,
                onClick = { open("https://github.com/mlmvpn") },
            )
            Separator()
            SettingsRow(
                title = stringResource(R.string.about_youtube),
                subtitle = "youtube.com/@marketmlm",
                icon = Icons.Default.PlayArrow,
                tint = Ios.Red,
                onClick = { open("https://www.youtube.com/@marketmlm") },
            )
        }
        SettingsFooter(
            if (isFa) {
                S(R.string.all_three_links_open_in_the_phone) +
                    S(R.string.these_do_not_go_through_it)
            } else {
                "All three open in the phone's own browser, outside the tunnel."
            }
        )

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The changelog, as a page.
 *
 * Public and self-contained -- it reads its own releases -- because it is a route on the settings
 * stack rather than something [AboutScreen] shows in place. That is what lets it open in the
 * detail pane on a wide screen while the settings list stays put beside it.
 *
 * Each entry gets its own glass card rather than all of a release sharing one, and that is a
 * deliberate trade. A grouped card would be more literally iOS, but a release here can run to
 * forty entries of several sentences each: one card per release means the whole release composes
 * the moment any part of it scrolls into view, and there are eleven of them. One card per entry
 * keeps `LazyColumn` doing what it is for, and long-form text reads better with air between the
 * items than with hairlines through it.
 *
 * `scrollable = false` because this page brings its own list. A `LazyColumn` measured inside a
 * vertically scrolling Column gets infinite height and crashes -- see [IosScreen].
 */
@Composable
fun ChangelogScreen(
    onBack: () -> Unit,
    backLabel: String,
) {
    val isFa = com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale().language == "fa"
    val versions = remember(isFa) { changelogVersions(isFa) }

    IosScreen(
        title = stringResource(R.string.about_changelog),
        onBack = onBack,
        backLabel = backLabel,
        scrollable = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                bottom = LocalSystemBottomPadding.current + 28.dp,
            ),
        ) {
            versions.forEachIndexed { index, version ->
                item(key = "v-${version.versionTitle}") {
                    ReleaseHeader(
                        title = version.versionTitle,
                        count = version.items.size,
                        isFa = isFa,
                        isLatest = index == 0,
                    )
                }
                items(
                    count = version.items.size,
                    key = { i -> "${version.versionTitle}-$i" },
                ) { i ->
                    ChangeCard(version.items[i])
                }
            }
        }
    }
}

/**
 * A release, and how much is in it.
 *
 * The newest one carries a badge, because the first question anyone opens this page with is
 * "what did I just get" and the answer was previously indistinguishable from the ten releases
 * under it.
 */
@Composable
private fun ReleaseHeader(title: String, count: Int, isFa: Boolean, isLatest: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Ios.Label, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        if (isLatest) {
            Spacer(Modifier.width(8.dp))
            Text(
                if (isFa) "تازه" else "NEW",
                color = Ios.Green,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Ios.Green.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (isFa) "${faNum(count)} مورد" else "$count items",
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
        )
    }
}

/** One change: the glyph tile every list in this app uses, a title, and the paragraph. */
@Composable
private fun ChangeCard(item: ChangelogItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .frostedGlass(PanelShape)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SettingsGlyph(item.icon, Ios.Gray)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                color = Ios.Label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 24.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                changelogText(item.description),
                color = Ios.SecondaryLabel,
                fontSize = 13.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Justify,
            )
        }
    }
}

/** Persian digits, like every other count the app prints. */
private fun faNum(value: Int): String {
    val digits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    return buildString {
        for (c in value.toString()) append(if (c in '0'..'9') digits[c - '0'] else c)
    }
}

/**
 * The release notes are written with `**emphasis**` and `` `identifiers` `` in them, and both
 * markers used to render as literal asterisks and backticks in the middle of the sentence -- the
 * text was authored as Markdown and displayed as plain text.
 *
 * Only these two, deliberately: a changelog is prose with the occasional emphasised phrase and
 * the occasional symbol name in it, and anything more (links, lists, headings) would be a
 * Markdown renderer, which is not what this page needs.
 */
private val EMPHASIS = Regex("""\*\*([^*]+)\*\*|`([^`]+)`""")

@Composable
private fun changelogText(raw: String): AnnotatedString {
    val strong = Ios.Label
    return remember(raw, strong) {
        buildAnnotatedString {
            var cursor = 0
            for (match in EMPHASIS.findAll(raw)) {
                append(raw.substring(cursor, match.range.first))
                val bold = match.groupValues[1]
                if (bold.isNotEmpty()) {
                    withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) {
                        append(bold)
                    }
                } else {
                    withStyle(SpanStyle(color = strong, fontFamily = FontFamily.Monospace)) {
                        append(match.groupValues[2])
                    }
                }
                cursor = match.range.last + 1
            }
            append(raw.substring(cursor))
        }
    }
}
