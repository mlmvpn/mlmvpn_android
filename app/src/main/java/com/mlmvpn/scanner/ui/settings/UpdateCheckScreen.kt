package com.mlmvpn.scanner.ui.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.theme.iosButtonBorder
import com.mlmvpn.scanner.ui.theme.iosButtonColors
import com.mlmvpn.scanner.update.UpdateChecker
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The page behind "Download and install".
 *
 * One screen with four faces, because the answer to "is there an update" has four shapes and each
 * needs a different thing from the user:
 *
 *  - **Checking** — a spinner and a sentence. It is a network call to GitHub on a line that often
 *    blocks GitHub, so it can take the full timeout; a page that showed nothing for eight seconds
 *    would read as frozen.
 *  - **Up to date** — the headline, and the version it is up to date AT. "You are up to date" with
 *    no version number is unverifiable, which is exactly the case where a user stops believing it.
 *  - **Available** — version, size, and the release notes. The notes are the whole reason to say
 *    yes rather than later, and hiding them behind a link nobody opens wastes them.
 *  - **Failed** — what went wrong and a retry. Not silence: on this app's typical network the
 *    check failing is the ordinary outcome, and it means "turn on a tunnel", which is something
 *    the user can act on.
 *
 * Download and install are two presses, deliberately. The APK is ~66 MB and installing hands off
 * to the system installer, so collapsing them would start a long download from a button whose
 * label promised an install.
 */
@Composable
fun UpdateCheckScreen(onBack: () -> Unit, backLabel: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by UpdateChecker.state.collectAsState()
    val progress by UpdateChecker.downloadProgressFlow.collectAsState()
    var errorText by remember { mutableStateOf<String?>(null) }
    var showNotes by remember { mutableStateOf(false) }
    if (showNotes) {
        androidx.activity.compose.BackHandler { showNotes = false }
        LatestChangesPage(onBack = { showNotes = false })
        return
    }

    // Arriving here IS asking, so the check is forced past the rate limit that exists to stop the
    // app's own background triggers from hammering GitHub.
    LaunchedEffect(Unit) { UpdateChecker.checkForUpdate(context, force = true) }

    IosScreen(
        title = stringResource(R.string.settings_software_update),
        onBack = onBack,
        backLabel = backLabel,
    ) {
        when (val s = state) {
            is UpdateChecker.State.Idle,
            is UpdateChecker.State.Checking -> CheckingFace()

            is UpdateChecker.State.UpToDate -> UpToDateFace(
                version = installedVersionName(context),
                checkedAt = s.checkedAt,
                onRecheck = { scope.launch { UpdateChecker.checkForUpdate(context, force = true) } },
                onLatestChanges = { showNotes = true },
            )

            is UpdateChecker.State.Failed -> FailedFace(
                reason = s.reason,
                onRetry = { scope.launch { UpdateChecker.checkForUpdate(context, force = true) } },
            )

            is UpdateChecker.State.Available -> AvailableFace(
                info = s.info,
                progress = progress,
                downloaded = UpdateChecker.isDownloaded(context, s.info),
                errorText = errorText,
                onDownload = {
                    errorText = null
                    UpdateChecker.startDownload(context, s.info) { err -> errorText = err }
                },
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// The four faces
// -------------------------------------------------------------------------------------------

@Composable
private fun CheckingFace() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 180.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TwoArcSpinner()
        Spacer(Modifier.height(22.dp))
        Text(
            stringResource(R.string.update_checking_versions),
            color = Ios.SecondaryLabel,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun UpToDateFace(version: String, checkedAt: Long, onRecheck: () -> Unit, onLatestChanges: () -> Unit) {
    Spacer(Modifier.height(18.dp))
    Text(
        stringResource(R.string.update_you_are_up_to_date),
        color = Ios.Label,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(18.dp))

    SettingsGroup {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.update_info_card_title),
                color = Ios.Label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Bullet(stringResource(R.string.update_info_current_version, version))
            Spacer(Modifier.height(4.dp))
            Bullet(stringResource(R.string.update_info_checked_at, dateTimeOf(checkedAt)))
        }
    }

    Spacer(Modifier.height(14.dp))
    SettingsGroup {
        SettingsActionRow(
            label = stringResource(R.string.update_check_again),
            icon = Icons.Default.CheckCircle,
            tint = Ios.Blue,
            onClick = onRecheck,
        )
        Separator()
        SettingsActionRow(
            label = com.mlmvpn.scanner.store.tr("آخرین تغییرات", "Latest changes"),
            icon = Icons.Default.Info,
            tint = Ios.Indigo,
            onClick = onLatestChanges,
        )
    }
    Spacer(Modifier.height(28.dp))
}

/**
 * «آخرین تغییرات»: the latest public release's notes from GitHub, in full, fetched fresh each time
 * the page opens; the in-app changelog of the newest version when GitHub cannot be reached.
 */
@Composable
private fun LatestChangesPage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var notes by remember { mutableStateOf<UpdateChecker.ReleaseNotes?>(null) }
    LaunchedEffect(Unit) {
        notes = UpdateChecker.latestReleaseNotes(context)
        loading = false
    }
    IosScreen(
        title = com.mlmvpn.scanner.store.tr("آخرین تغییرات", "Latest changes"),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_software_update),
    ) {
        val n = notes
        when {
            loading -> Column(Modifier.fillMaxWidth().padding(top = 140.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TwoArcSpinner()
                Spacer(Modifier.height(18.dp))
                Text(com.mlmvpn.scanner.store.tr("دریافت از گیت‌هاب…", "Fetching from GitHub…"), color = Ios.SecondaryLabel, fontSize = 14.sp)
            }
            n != null -> {
                Spacer(Modifier.height(12.dp))
                Text(n.title.ifBlank { n.tag }, color = Ios.Label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                if (n.publishedAt.isNotBlank()) Text(n.publishedAt.take(10), color = Ios.SecondaryLabel, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
                Spacer(Modifier.height(10.dp))
                SettingsGroup { Column(Modifier.padding(16.dp)) { MarkdownLite(n.body) } }
                Spacer(Modifier.height(28.dp))
            }
            else -> {
                // GitHub unreachable: the newest version's notes from the app itself.
                val v = com.mlmvpn.scanner.ui.changelogVersions(com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()).firstOrNull()
                Spacer(Modifier.height(12.dp))
                SettingsFooter(com.mlmvpn.scanner.store.tr("گیت‌هاب در دسترس نبود؛ این تغییرات همین نسخه است که داخل برنامه است.",
                    "GitHub could not be reached; these are this version's notes from inside the app."))
                if (v != null) {
                    Text(v.versionTitle, color = Ios.Label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp))
                    SettingsGroup {
                        v.items.forEachIndexed { i, item ->
                            if (i > 0) Separator()
                            Column(Modifier.padding(16.dp)) {
                                Text(item.title, color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Text(item.description, color = Ios.SecondaryLabel, fontSize = 14.sp, lineHeight = 22.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** Enough Markdown for release notes: headings, bullets, bold runs; the rest as plain text. */
@Composable
private fun MarkdownLite(md: String) {
    md.replace("\r", "").split("\n").forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.isBlank() -> Spacer(Modifier.height(8.dp))
            line.startsWith("#") -> Text(line.trimStart('#').trim().replace("**", ""), color = Ios.Label,
                fontSize = if (line.startsWith("##")) 16.sp else 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row {
                val depth = (line.length - line.trimStart().length) / 2
                Spacer(Modifier.width((depth * 14).dp))
                Text("•  ", color = Ios.SecondaryLabel, fontSize = 14.sp)
                Text(bold(line.trimStart().drop(2)), color = Ios.Label, fontSize = 14.sp, lineHeight = 22.sp)
            }
            else -> Text(bold(line), color = Ios.Label, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}

private fun bold(s: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val parts = s.split("**")
    parts.forEachIndexed { i, part ->
        val clean = part.replace("`", "")
        if (i % 2 == 1) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)) { append(clean) } else append(clean)
    }
}

@Composable
private fun FailedFace(reason: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 120.dp, start = 28.dp, end = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            tint = Ios.SecondaryLabel,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.update_check_failed),
            color = Ios.Label,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.update_failed_line, reason),
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(20.dp))
    SettingsGroup {
        SettingsActionRow(
            label = stringResource(R.string.update_check_again),
            icon = Icons.Default.CheckCircle,
            tint = Ios.Blue,
            onClick = onRetry,
        )
    }
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun AvailableFace(
    info: UpdateChecker.UpdateInfo,
    progress: Int?,
    downloaded: Boolean,
    errorText: String?,
    onDownload: () -> Unit,
) {
    val downloading = progress != null && progress < 100
    val readyToInstall = downloaded || progress == 100

    Spacer(Modifier.height(18.dp))
    Text(
        stringResource(R.string.update_new_version_available),
        color = Ios.Label,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(16.dp))

    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.update_row_version),
            value = info.versionName,
            showChevron = false,
        )
        Separator()
        SettingsRow(
            title = stringResource(R.string.update_row_size),
            value = formatMb(info.apkSizeBytes),
            showChevron = false,
        )
    }

    if (info.changelog.isNotEmpty()) {
        SettingsSectionHeader(stringResource(R.string.update_whats_new))
        SettingsGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                // Capped, because a release body can be a page and a half of Markdown and this is
                // a decision screen, not the changelog. The full notes live in About > Changelog.
                info.changelog.take(12).forEachIndexed { index, line ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    Bullet(line)
                }
            }
        }
    }

    Spacer(Modifier.height(18.dp))

    // The bar only exists while there is something to report. An empty track sitting under an
    // untouched button reads as a stalled download rather than as one that has not started.
    if (downloading || readyToInstall) {
        val pct = progress ?: 100
        val width by animateFloatAsState(
            targetValue = (pct / 100f).coerceIn(0f, 1f),
            animationSpec = tween(200),
            label = "dl",
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(Ios.Separator, RoundedCornerShape(6.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(width)
                        .fillMaxHeight()
                        .background(Ios.Blue, RoundedCornerShape(6.dp))
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (readyToInstall) stringResource(R.string.update_ready_to_install)
                else stringResource(R.string.update_downloading_pct, pct),
                color = Ios.SecondaryLabel,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    errorText?.let {
        Text(
            it,
            color = Ios.Destructive,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
    }

    Button(
        onClick = onDownload,
        enabled = !downloading,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = iosButtonColors(if (readyToInstall) Ios.Green else Ios.Blue),
        border = iosButtonBorder(if (readyToInstall) Ios.Green else Ios.Blue, enabled = !downloading),
    ) {
        Text(
            when {
                downloading -> stringResource(R.string.update_downloading)
                readyToInstall -> stringResource(R.string.update_install_now)
                else -> stringResource(R.string.update_download)
            },
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }

    SettingsFooter(stringResource(R.string.update_mobile_data_warning))
    Spacer(Modifier.height(28.dp))
}

// -------------------------------------------------------------------------------------------
// Bits
// -------------------------------------------------------------------------------------------

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", color = Ios.SecondaryLabel, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 21.sp)
    }
}

/**
 * Two arcs chasing each other.
 *
 * A single-arc indicator is the Material default and reads as "one thing is loading". Two arcs at
 * opposite ends of the same circle is the shape the platform's own update screen uses, and this
 * screen is deliberately modelled on it -- the whole point is that it looks like the updater the
 * user has already learned.
 */
@Composable
private fun TwoArcSpinner(size: androidx.compose.ui.unit.Dp = 52.dp) {
    val spin = rememberInfiniteTransition(label = "update-spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "rotation",
    ).value

    val green = Ios.Green
    val blue = Ios.Blue
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 4.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = green,
            startAngle = spin,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = blue,
            startAngle = spin + 180f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

private fun installedVersionName(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-"
}.getOrDefault("-")

private fun dateTimeOf(millis: Long): String =
    if (millis <= 0L) "-"
    else SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(millis))

private fun formatMb(bytes: Long): String =
    if (bytes <= 0) "-" else String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
