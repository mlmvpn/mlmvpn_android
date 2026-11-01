package com.mlmvpn.scanner.ui.emergency

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.gst.GstConfigManager
import com.mlmvpn.scanner.engines.gst.GstDiagnostics
import com.mlmvpn.scanner.engines.gst.GstRelay
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.ControlShape
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * Five steps to a working Google relay, on the app's own design.
 *
 * The flow is unchanged and deliberately so -- it mirrors a Google web flow the user has to
 * follow exactly, and reordering the steps here would desynchronise the instructions from what
 * they see in the browser:
 *
 *   1. Choose or generate the security password (auth key).
 *   2. Confirm it. One key is shared by every relay, so this is asked once, not per relay.
 *   3. The ready-to-paste script, the deployment guide, and a link straight to script.google.com.
 *   4. Enter the Deployment ID and probe it for real.
 *   5. Done.
 *
 * What changed is everything around them. The bar is [IosScreen]'s, so back and cancel sit where
 * they do on every other page; the fields are the app's own rather than `OutlinedTextField`s with
 * a floating Material label; the instruction slabs are inset grouped cards; and the step
 * indicator is a row of dots rather than five stretched progress bars.
 *
 * @param sharedAuthKey the password already in use for other relays, prefilled so all scripts
 *   share one key. Empty on first setup.
 * @param editRelay current values when re-opening an existing relay; null for a new one.
 * @param onComplete called with the finished relay to persist.
 * @param onClose dismiss without finishing.
 */
@Composable
fun GstSetupWizard(
    sharedAuthKey: String,
    editRelay: GstRelay?,
    onComplete: (GstRelay) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val saveScope = rememberCoroutineScope()

    fun genKey() = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    var step by remember { mutableStateOf(1) }
    var authKey by remember {
        mutableStateOf(
            editRelay?.authKey?.takeIf { it.isNotEmpty() }
                ?: sharedAuthKey.takeIf { it.isNotEmpty() }
                ?: genKey()
        )
    }
    var deploymentId by remember { mutableStateOf(editRelay?.deploymentId ?: "") }

    // Physical back steps the wizard backwards; from step 1 it closes the wizard.
    BackHandler(enabled = true) { if (step > 1) step-- else onClose() }

    // The finished script with the current auth key baked in. It is CLEANED (comments + blank
    // lines removed, folded to pure ASCII) so the copied text is small and byte-clean: no giant
    // Unicode comment blocks that some device clipboards truncate, which is what left a stray
    // "*/" at the end of pasted code.
    val scriptCode = remember(authKey) {
        try {
            val raw = context.assets.open("gst/Code.gs").bufferedReader().use { it.readText() }
            cleanAppsScript(raw, authKey)
        } catch (e: Exception) {
            S(R.string.failed_to_load_the_script)
        }
    }

    IosScreen(
        title = S(R.string.google_relay_setup),
        onBack = { if (step > 1) step-- else onClose() },
        backLabel = if (step > 1) S(R.string.back_2) else S(R.string.cancel_r2),
    ) {
        Spacer(Modifier.height(14.dp))

        StepDots(current = step, total = 5)

        when (step) {
            1 -> StepPassword(authKey, onChange = { authKey = GstConfigManager.normalizeAuthKey(it) }, onGenerate = { authKey = genKey() })
            2 -> StepConfirmPassword(authKey, onChange = { authKey = GstConfigManager.normalizeAuthKey(it) }, onRegenerate = { authKey = genKey() })
            3 -> StepScript(
                scriptCode = scriptCode,
                onCopyCode = {
                    clipboard.setText(AnnotatedString(scriptCode))
                    shortToast(context, S(R.string.the_whole_code_was_copied_characters, faDigits(scriptCode.length.toString())))
                },
                onSaveFile = {
                    // Off the main thread: MediaStore is a provider in another process, and its
                    // delete and insert held the screen for seconds (freeze report, 1.2.41).
                    saveScope.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveScriptToDownloads(context, scriptCode) }
                        shortToast(
                            context,
                            if (ok) S(R.string.saved_to_your_downloads_folder_mlmvpn_relay)
                            else S(R.string.could_not_save_the_file),
                        )
                    }
                },
                onOpenScriptSite = {
                    openExternalUrl(context, "https://script.google.com/home/projects/create")
                },
            )
            4 -> StepDeploymentId(
                deploymentId = deploymentId,
                authKey = authKey,
                onChange = { deploymentId = it },
            )
            5 -> StepDone()
        }

        Spacer(Modifier.height(22.dp))

        WizardPrimary(
            label = when (step) {
                5 -> S(R.string.go_to_the_connection_page_r2)
                else -> S(R.string.next)
            },
            onClick = {
                when (step) {
                    1 -> {
                        if (authKey.isBlank()) {
                            shortToast(context, S(R.string.enter_or_generate_a_password))
                        } else {
                            step = 2
                        }
                    }
                    2 -> step = 3
                    3 -> step = 4
                    4 -> {
                        if (GstConfigManager.normalizeDeploymentId(deploymentId).isBlank()) {
                            shortToast(context, S(R.string.enter_the_deployment_id))
                        } else {
                            step = 5
                        }
                    }
                    5 -> onComplete(
                        (editRelay ?: GstRelay(deploymentId = "", authKey = "")).copy(
                            deploymentId = GstConfigManager.normalizeDeploymentId(deploymentId),
                            authKey = authKey,
                        )
                    )
                }
            },
        )

        Spacer(Modifier.height(28.dp))
    }
}

// -------------------------------------------------------------------------------------------
// Chrome
// -------------------------------------------------------------------------------------------

/**
 * Where you are in the flow, as dots.
 *
 * The previous version was five bars stretched edge to edge with a caption under them, which is
 * a download indicator's shape, not a wizard's -- it reads as "how much is finished" rather than
 * "which of five steps you are on". The current dot is wider so the state survives a glance.
 */
@Composable
private fun StepDots(current: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 1..total) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(6.dp)
                    .width(if (i == current) 20.dp else 6.dp)
                    .background(
                        when {
                            i == current -> Ios.Blue
                            i < current -> Ios.Blue.copy(alpha = 0.45f)
                            else -> Color.White.copy(alpha = 0.18f)
                        },
                        RoundedCornerShape(3.dp),
                    )
            )
        }
    }
}

/** The title and the one paragraph that says what this step is for. */
@Composable
private fun StepHeader(title: String, subtitle: String) {
    Spacer(Modifier.height(18.dp))
    Text(
        title,
        color = Ios.Label,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        subtitle,
        color = Ios.SecondaryLabel,
        fontSize = 14.sp,
        lineHeight = 23.sp,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(18.dp))
}

/**
 * The single field a step asks for, in its own card.
 *
 * A `BasicTextField` rather than an `OutlinedTextField`: the Material version brings a floating
 * label, an indicator line and its own 56dp metrics, none of which appear anywhere else in this
 * app. This is the same treatment [com.mlmvpn.scanner.ui.settings.IosTextScreen] gives a typed
 * value.
 */
@Composable
private fun WizardField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    monospace: Boolean = false,
) {
    SettingsGroup {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // An auth key and a Deployment ID are ASCII tokens, and this app runs right-to-left.
            // Left to inherit the page direction, bidi throws their punctuation to the wrong end
            // and the caret starts on the wrong side of the field.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Ios.Label,
                        fontSize = 17.sp,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    ),
                    cursorBrush = SolidColor(Ios.Blue),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    placeholder,
                                    color = Ios.SecondaryLabel.copy(alpha = 0.6f),
                                    fontSize = 17.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }
    }
}

/** The one full-width action at the foot of every step. */
@Composable
private fun WizardPrimary(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Ios.Blue, ControlShape)
            .clickable(onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** An explanatory slab that is not attached to a group. */
@Composable
private fun InfoCard(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .frostedGlass(CardShape)
            .padding(16.dp),
    ) {
        Text(text, color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 22.sp)
    }
}

// -------------------------------------------------------------------------------------------
// Steps
// -------------------------------------------------------------------------------------------

@Composable
private fun StepPassword(authKey: String, onChange: (String) -> Unit, onGenerate: () -> Unit) {
    StepHeader(
        S(R.string.relay_password),
        S(R.string.this_password_is_your_relay_s_key) +
            S(R.string.set_one_of_your_own_or_generate),
    )
    WizardField(authKey, onChange, placeholder = S(R.string.relay_password_2), monospace = true)
    Spacer(Modifier.height(10.dp))
    SettingsGroup {
        SettingsActionRow(S(R.string.generate_a_secure_password), Icons.Default.Refresh, onClick = onGenerate)
    }
}

@Composable
private fun StepConfirmPassword(authKey: String, onChange: (String) -> Unit, onRegenerate: () -> Unit) {
    StepHeader(
        S(R.string.confirm_the_password),
        S(R.string.this_same_password_goes_inside_the_code) +
            S(R.string.relay_works_with_this_one_password_this),
    )
    WizardField(authKey, onChange, placeholder = S(R.string.final_password), monospace = true)
    Spacer(Modifier.height(10.dp))
    SettingsGroup {
        SettingsActionRow(
            S(R.string.generate_a_new_password),
            Icons.Default.Refresh,
            onClick = onRegenerate,
        )
    }
    Spacer(Modifier.height(16.dp))
    SettingsFooter(
        S(R.string.write_this_password_down_somewhere_with_it) +
            S(R.string.too_and_they_all_work_with_no)
    )
}

@Composable
private fun StepScript(
    scriptCode: String,
    onCopyCode: () -> Unit,
    onSaveFile: () -> Unit,
    onOpenScriptSite: () -> Unit,
) {
    StepHeader(
        S(R.string.the_code_ready_to_deploy),
        S(R.string.the_code_below_is_ready_with_your) +
            S(R.string.script_google_com_as_the_guide_describes),
    )

    SettingsGroup {
        SettingsActionRow(S(R.string.copy_the_code), Icons.Default.ContentCopy, onClick = onCopyCode)
        Separator()
        SettingsActionRow(
            S(R.string.save_as_a_file),
            Icons.Default.Download,
            onClick = onSaveFile,
        )
        Separator()
        SettingsActionRow(
            S(R.string.open_script_google_com),
            Icons.Default.OpenInNew,
            onClick = onOpenScriptSite,
        )
    }
    SettingsFooter(
        S(R.string.the_code_is_complete_and_ends_with) +
            S(R.string.save_as_a_file_the_file_is)
            + S(R.string.code_length_characters, faDigits(scriptCode.length.toString()))
    )

    SettingsSectionHeader(S(R.string.script_code))
    // JavaScript is left-to-right and the app is not. Without pinning the direction here, bidi
    // reorders every line that ends in a semicolon or a brace -- the ";" jumps to the head of the
    // line -- and the preview reads as corrupted code the user is about to paste into Google.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .frostedGlass(CardShape)
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(
                scriptCode,
                color = Ios.Green,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }

    SettingsSectionHeader(S(R.string.step_by_step_on_the_phone))
    InfoCard(
        S(R.string.s_1_tap_copy_the_code_n_n) +
            S(R.string.s_2_tap_open_script_google_com_if) +
            S(R.string.desktop_site_so_the_full_editor_opens) +
            S(R.string.s_3_delete_all_the_default_code_in) +
            S(R.string.icon_or_file_save_n_n) +
            S(R.string.s_4_tap_deploy_then_new_deployment_and) +
            "    • Select type: Web app\n" +
            "    • Execute as: Me\n" +
            "    • Who has access: Anyone\n\n" +
            S(R.string.s_5_tap_deploy_and_authorise_it_n) +
            S(R.string.review_permissions_first_then_advanced_then_go) +
            S(R.string.s_6_copy_the_deployment_id_the_string)
    )
    SettingsFooter(
        S(R.string.if_you_later_get_a_401_or) +
            S(R.string.was_not_completed_not_that_the_network)
    )
}

@Composable
private fun StepDeploymentId(deploymentId: String, authKey: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<GstDiagnostics.Report?>(null) }

    StepHeader(
        S(R.string.deployment_id_2),
        S(R.string.enter_the_id_you_copied_from_google) +
            S(R.string.right_here_whether_it_works),
    )
    WizardField(deploymentId, onChange, placeholder = "AKfy…", monospace = true)
    Spacer(Modifier.height(10.dp))
    SettingsGroup {
        SettingsActionRow(
            label = if (testing) S(R.string.measuring_r2) else S(R.string.measure_the_relay),
            icon = Icons.Default.NetworkCheck,
            busy = testing,
            onClick = {
                if (deploymentId.isBlank()) {
                    shortToast(context, S(R.string.enter_the_deployment_id_first))
                } else {
                    testing = true
                    report = null
                    scope.launch {
                        report = GstDiagnostics.testDeployment(
                            GstDiagnostics.execUrl(deploymentId),
                            authKey,
                        )
                        testing = false
                    }
                }
            },
        )
    }

    report?.let { r ->
        val needsAuth = r.result == GstDiagnostics.Result.REDIRECT_BLOCKED
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(relayTone(r), CircleShape)
                        .padding(top = 6.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        relayVerdict(r),
                        color = relayTone(r),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(r.message, color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 21.sp)
                }
            }
            if (needsAuth) {
                Separator()
                SettingsActionRow(
                    S(R.string.verify_in_the_browser),
                    Icons.Default.OpenInNew,
                    tint = Ios.Orange,
                    onClick = { openExternalUrl(context, GstDiagnostics.execUrl(deploymentId)) },
                )
            }
        }
        if (needsAuth) {
            SettingsFooter(
                S(R.string.the_script_exists_but_has_not_been) +
                    S(R.string.account_and_tap_review_permissions_advanced_and) +
                    S(R.string.measure_the_relay_again)
            )
        }
    }
}

@Composable
private fun StepDone() {
    Spacer(Modifier.height(34.dp))
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(84.dp).background(Ios.Green.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Ios.Green,
                modifier = Modifier.size(46.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(S(R.string.the_relay_is_ready), color = Ios.Label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            S(R.string.your_relay_is_saved_to_get_around) +
                S(R.string.as_well_the_tunnel_spreads_the_load) +
                S(R.string.automatically_when_one_runs_out),
            color = Ios.SecondaryLabel,
            fontSize = 14.sp,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(14.dp))
}

// =================================================================================================
// Script delivery
//
// Unchanged. Both functions below are byte-level plumbing that the Apps Script editor is fussy
// about, and the reasons are in their own comments.
// =================================================================================================

// Turns the raw Apps Script asset into a small, byte-clean, pure-ASCII script for copy/paste:
//   - strips the UTF-8 BOM,
//   - drops whole-line "//" comments, full-line block comments, and blank lines (all block
//     openers in Code.gs are full-line, and JS has no multi-line string literals in this file,
//     so this can't touch executable code — verified),
//   - folds any stray non-ASCII (only ever inside the rare inline comment) to ASCII,
//   - bakes in the auth key and guarantees a single trailing newline so the final "}" survives
//     even paste targets that drop a missing terminator.
// Result: ~1/3 the size, no block comments at all (so no stray comment-close can appear at the
// end), and nothing that renders as "changed" when pasted.
fun cleanAppsScript(rawIn: String, authKey: String): String {
    val raw = rawIn.removePrefix("﻿")
    val sb = StringBuilder()
    var inBlock = false
    for (rawLine in raw.split("\n")) {
        val line = rawLine.trimEnd('\r')
        val t = line.trimStart()
        if (inBlock) {
            if (t.contains("*/")) inBlock = false
            continue
        }
        if (t.startsWith("/*")) {
            if (!t.contains("*/")) inBlock = true
            continue
        }
        if (t.startsWith("//")) continue
        if (t.isBlank()) continue
        val ascii = buildString {
            for (c in line) append(if (c.code in 9..126) c else ' ')
        }
        sb.append(ascii.trimEnd()).append('\n')
    }
    var out = sb.toString().trimEnd()
    if (authKey.isNotEmpty()) out = out.replace("CHANGE_ME_TO_A_STRONG_SECRET", authKey)
    return out + "\n"
}

/**
 * Writes the (already cleaned) script to Downloads as a byte-exact file — a clipboard-free,
 * truncation-proof delivery path. Returns true on success.
 */
fun saveScriptToDownloads(context: android.content.Context, code: String): Boolean {
    return try {
        val bytes = code.toByteArray(Charsets.UTF_8)
        val name = "MLMVPN_relay.gs"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val sel = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?"
            resolver.delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel, arrayOf(name))
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        } else {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val f = java.io.File(dir, name)
            f.writeBytes(bytes)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("text/plain"), null)
        }
        true
    } catch (e: Exception) {
        false
    }
}
