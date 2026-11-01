package com.mlmvpn.scanner.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.UUID
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// Adding a config.
//
// This was a `Dialog` at 95% × 90% of the screen with `usePlatformDefaultWidth = false` -- which is
// a screen wearing a dialog's clothes. Inside it: its own title row with a Close that turned into a
// Back, its own Divider, a LazyColumn of bordered "option cards" laid out two-per-row, and six
// protocol forms built from `OutlinedTextField`s that swapped in and out behind an AnimatedContent.
//
// It is a page now, and its forms are pages under it. That buys three things the dialog could not
// have: the app's own navigation bar instead of a second one, back gestures that pop one level at a
// time instead of dismissing everything, and forms that are settings groups like every other form
// in the app rather than a stack of Material fields.
//
// The logic is untouched and still lives in AddNodeModal.kt: isValidUri, createNodeFromUri,
// spoofToSni, the URI builders, and DEFAULT_SNI_CONFIGS.
// =================================================================================================

private sealed class AddPage {
    object Root : AddPage()
    object SubLink : AddPage()
    data class Manual(val type: AddNodeFormType) : AddPage()
}

/** The largest file read as a config list: well past any real list, far below a video. */
private const val MAX_IMPORT_CHARS = 16 * 1024 * 1024

@Composable
fun AddNodeScreen(
    onBack: () -> Unit,
    onNodesAdded: (List<VpnNode>) -> Unit,
    onSubscriptionAdded: ((name: String, url: String) -> Unit)? = null,
    onUpdateSubscriptions: (() -> Unit)? = null,
    selectedManualGroup: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<AddPage>(AddPage.Root) }

    BackHandler(enabled = page != AddPage.Root) { page = AddPage.Root }

    val qrScannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val uri = result.contents.trim()
            if (isValidUri(uri)) {
                val node = createNodeFromUri(uri)
                node.groupTitle = selectedManualGroup
                onNodesAdded(listOf(node))
                Toast.makeText(context, context.getString(R.string.nodes_add_success), Toast.LENGTH_SHORT).show()
                onBack()
            } else if (uri.startsWith("http://") || uri.startsWith("https://")) {
                onSubscriptionAdded?.invoke("Sub-${UUID.randomUUID().toString().substring(0, 4)}", uri)
                Toast.makeText(context, context.getString(R.string.nodes_add_success), Toast.LENGTH_SHORT).show()
                onBack()
            } else {
                Toast.makeText(context, context.getString(R.string.nodes_invalid_config), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // OpenDocument, not GetContent.
    //
    // `GetContent` is ACTION_GET_CONTENT, and on several vendor builds -- Samsung's among them --
    // that opens a media picker organised by category: images, video, audio, documents. A `.json`
    // file belongs to none of them and simply is not listed, so a user who downloaded a config
    // could not select it however hard they looked. `OpenDocument` is ACTION_OPEN_DOCUMENT, the
    // system document browser, which lists every file whatever its type.
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                // Reading the file is its own step, with its own failure message.
                //
                // A document picker will happily hand back a URI whose file no longer exists.
                // Telegram is the usual way it happens here: while downloading it writes
                // "Name.json.cac" and that temp name gets indexed by the media database, then the
                // finished file is renamed -- and the row for the temp name stays behind. It is
                // listed in the picker looking exactly like the real file, and opening it throws
                // ENOENT. That is not a bad config, and answering it with "invalid config" sends
                // the user off to fix the one thing that was never wrong.
                // At most 16 MB of text: a video picked by mistake was read whole and the app ran
                // out of memory (crashes #697, #702).
                var tooLarge = false
                val text = try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val r = input.bufferedReader()
                        val sb = StringBuilder()
                        val buf = CharArray(64 * 1024)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            sb.append(buf, 0, n)
                            if (sb.length > MAX_IMPORT_CHARS) { tooLarge = true; break }
                        }
                        if (tooLarge) null else sb.toString()
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("AddNodeScreen", "cannot read $uri", e)
                    if (e is OutOfMemoryError) tooLarge = true
                    null
                }
                if (tooLarge) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.file_too_large_to_import), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                if (text.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.file_unreadable), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                try {
                    val nodesToAdd = mutableListOf<VpnNode>()
                    // The whole file first, as one JSON document. It has to be tried before the
                    // line loop and not after: a JSON config spans many lines, none of which is a
                    // config on its own, so the loop below cannot see it however it is written.
                    nodesToAdd += parseJsonConfigs(text)
                    if (nodesToAdd.isEmpty()) {
                        text.lineSequence().forEach { line ->
                            val config = line.trim()
                            if (isValidUri(config)) nodesToAdd.add(createNodeFromUri(config))
                        }
                    }
                    nodesToAdd.forEach { it.groupTitle = selectedManualGroup }
                    withContext(Dispatchers.Main) {
                        // Same reasoning as the clipboard path above.
                        if (nodesToAdd.isEmpty() && looksLikeJsonConfig(text)) {
                            Toast.makeText(context, context.getString(R.string.json_looks_truncated), Toast.LENGTH_LONG).show()
                            return@withContext
                        }
                        if (nodesToAdd.isNotEmpty()) {
                            onNodesAdded(nodesToAdd)
                            Toast.makeText(context, context.getString(R.string.nodes_add_success), Toast.LENGTH_SHORT).show()
                            onBack()
                        } else {
                            Toast.makeText(context, context.getString(R.string.nodes_invalid_config), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    // Logged, not just swallowed. "Error adding file" with nothing behind it is
                    // the same dead end for whoever has to fix it as for the user who sees it.
                    android.util.Log.e("AddNodeScreen", "file import failed for $uri", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.nodes_add_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun handleClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            // Whole-clipboard JSON first, for the same reason as the file path above: a
            // pasted JSON config is one document over many lines, and every one of those
            // lines fails the `://` test that the loop below is made of.
            val jsonNodes = parseJsonConfigs(text)
                .onEach { it.groupTitle = selectedManualGroup }
            val lines = if (jsonNodes.isNotEmpty()) emptyList()
                        else text.split("\n", "\r").map { it.trim() }
            val nodesToAdd = mutableListOf<VpnNode>()
            nodesToAdd += jsonNodes
            var subAdded = false
            for (line in lines) {
                if (isValidUri(line)) {
                    val node = createNodeFromUri(line)
                    node.groupTitle = selectedManualGroup
                    nodesToAdd.add(node)
                } else if (line.startsWith("http://") || line.startsWith("https://")) {
                    onSubscriptionAdded?.invoke("Sub-${UUID.randomUUID().toString().substring(0, 4)}", line)
                    subAdded = true
                }
            }
            // A paste that was plainly meant to be a JSON config but yielded nothing is
            // almost always one that arrived incomplete -- a messaging app splits a long
            // document across messages and only one piece gets copied. "Invalid config" is a
            // verdict on the config; this says what actually happened.
            if (nodesToAdd.isEmpty() && !subAdded && looksLikeJsonConfig(text)) {
                Toast.makeText(context, context.getString(R.string.json_looks_truncated), Toast.LENGTH_LONG).show()
                return
            }
            if (nodesToAdd.isNotEmpty() || subAdded) {
                if (nodesToAdd.isNotEmpty()) {
                    onNodesAdded(nodesToAdd)
                    Toast.makeText(context, context.getString(R.string.nodes_add_success), Toast.LENGTH_SHORT).show()
                }
                onBack()
            } else {
                Toast.makeText(context, context.getString(R.string.nodes_invalid_config), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, context.getString(R.string.nodes_invalid_config), Toast.LENGTH_SHORT).show()
        }
    }

    when (val current = page) {
        is AddPage.Manual -> {
            ManualEntryScreen(
                formType = current.type,
                onSubmit = { uri ->
                    val node = createNodeFromUri(uri)
                    node.groupTitle = selectedManualGroup
                    onNodesAdded(listOf(node))
                    Toast.makeText(context, context.getString(R.string.nodes_add_success), Toast.LENGTH_SHORT).show()
                    onBack()
                },
                onBack = { page = AddPage.Root },
            )
            return
        }
        AddPage.SubLink -> {
            SubLinkAddScreen(
                onSubmit = { name, url ->
                    onSubscriptionAdded?.invoke(name, url)
                    onBack()
                },
                onBack = { page = AddPage.Root },
            )
            return
        }
        AddPage.Root -> Unit
    }

    IosScreen(
        title = stringResource(R.string.nodes_add_title),
        onBack = onBack,
        backLabel = S(R.string.connection),
    ) {
        Spacer(Modifier.height(14.dp))

        SettingsSectionHeader(S(R.string.import_str))
        SettingsGroup {
            SettingsActionRow(S(R.string.scan_qr), Icons.Default.QrCodeScanner) {
                val options = ScanOptions()
                options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                options.setPrompt(S(R.string.scan_the_config_s_qr_code))
                options.setBeepEnabled(false)
                options.setOrientationLocked(false)
                qrScannerLauncher.launch(options)
            }
            Separator()
            SettingsActionRow(S(R.string.from_the_clipboard), Icons.Default.ContentPaste) { handleClipboard() }
            Separator()
            SettingsActionRow(S(R.string.from_a_file), Icons.Default.UploadFile) {
                try { filePickerLauncher.launch(arrayOf("*/*")) }
                catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, S(R.string.amz_no_file_picker), Toast.LENGTH_LONG).show() }
            }
        }
        SettingsFooter(
            S(R.string.both_the_clipboard_and_a_file_accept) +
                S(R.string.is_recorded_as_a_subscription_link_n) +
                S(R.string.add_accepts_json) +
                // A pointer, not the feature. Everything on this page takes a config the user
                // already has; free configs GO AND FIND them, which is why they moved out — but
                // anyone who used to press the row that was here needs to be told where it went.
                S(R.string.looking_for_free_configs_it_has_its)
        )

        SettingsSectionHeader(S(R.string.subscriptions_and_building))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.add_a_subscription_link),
                icon = Icons.Default.Link,
                tint = Ios.Indigo,
                onClick = { page = AddPage.SubLink },
            )
            Separator()
            SettingsActionRow(S(R.string.update_all_subscriptions), Icons.Default.Refresh) {
                onUpdateSubscriptions?.invoke()
                onBack()
            }
        }

        SettingsSectionHeader(S(R.string.manual_entry))
        SettingsGroup {
            listOf(
                AddNodeFormType.VLESS to "VLESS",
                AddNodeFormType.VMESS to "VMess",
                AddNodeFormType.TROJAN to "Trojan",
                AddNodeFormType.SHADOWSOCKS to "Shadowsocks",
                AddNodeFormType.SOCKS to "SOCKS",
                AddNodeFormType.HTTP to "HTTP",
            ).forEachIndexed { index, (type, label) ->
                if (index > 0) Separator()
                SettingsRow(
                    title = label,
                    icon = Icons.Default.Edit,
                    tint = Ios.Gray,
                    onClick = { page = AddPage.Manual(type) },
                )
            }
        }
        SettingsFooter(
            S(R.string.only_needed_when_you_do_not_have) +
                S(R.string.otherwise_from_the_clipboard_is_faster_and)
        )

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * One protocol's fields, as an iOS form.
 *
 * The Save action stays disabled until the config could actually connect. The old form let it
 * through with empty fields and produced strings like `vless://uuid@:` -- valid enough to be saved
 * and then to fail at connect time with nothing on screen to explain why.
 */
@Composable
private fun ManualEntryScreen(
    formType: AddNodeFormType,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var name by remember { mutableStateOf("") }
    var uuid by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var network by remember { mutableStateOf("ws") }
    var security by remember { mutableStateOf("tls") }
    var sni by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }
    var flow by remember { mutableStateOf("") }
    var alpn by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("chacha20-ietf-poly1305") }

    val hasTransport = formType == AddNodeFormType.VMESS ||
        formType == AddNodeFormType.VLESS ||
        formType == AddNodeFormType.TROJAN
    val needsCredential = formType != AddNodeFormType.SOCKS && formType != AddNodeFormType.HTTP

    val portError = if (port.isBlank()) S(R.string.a_port_is_required)
    else if (port.toIntOrNull()?.let { it in 1..65535 } != true) S(R.string.a_number_between_1_and_65535)
    else null

    val canSave = address.isNotBlank() && portError == null && (!needsCredential || uuid.isNotBlank())

    IosScreen(title = getTitleForForm(formType), onBack = onBack, backLabel = S(R.string.add_r2)) {
        Spacer(Modifier.height(14.dp))

        SettingsSectionHeader(S(R.string.server_r2))
        SettingsGroup {
            SettingsTextRow(stringResource(R.string.form_name), name, { name = it }, placeholder = S(R.string.custom_name_r2))
            Separator()
            SettingsTextRow(stringResource(R.string.form_address), address, { address = it }, placeholder = "example.com")
            Separator()
            SettingsTextRow(stringResource(R.string.form_port), port, { port = it }, placeholder = "443", numeric = true, error = portError)
        }

        SettingsSectionHeader(S(R.string.authentication))
        SettingsGroup {
            when (formType) {
                AddNodeFormType.VMESS, AddNodeFormType.VLESS ->
                    SettingsTextRow(stringResource(R.string.form_uuid), uuid, { uuid = it }, placeholder = "UUID")
                AddNodeFormType.TROJAN, AddNodeFormType.SHADOWSOCKS ->
                    SettingsTextRow(stringResource(R.string.form_password), uuid, { uuid = it }, placeholder = S(R.string.password))
                else -> {
                    SettingsTextRow(stringResource(R.string.form_username), username, { username = it }, placeholder = S(R.string.optional))
                    Separator()
                    SettingsTextRow(stringResource(R.string.form_password), uuid, { uuid = it }, placeholder = S(R.string.optional))
                }
            }
            if (formType == AddNodeFormType.SHADOWSOCKS) {
                Separator()
                SettingsTextRow(stringResource(R.string.form_method), method, { method = it })
            }
        }
        if (formType == AddNodeFormType.SOCKS || formType == AddNodeFormType.HTTP) {
            SettingsFooter(S(R.string.these_two_protocols_can_work_without_a))
        }

        if (hasTransport) {
            SettingsSectionHeader(S(R.string.transport_r2))
            SettingsGroup {
                SettingsTextRow(stringResource(R.string.form_network), network, { network = it }, placeholder = "ws / grpc / tcp")
                Separator()
                SettingsTextRow(stringResource(R.string.form_tls), security, { security = it }, placeholder = "tls / none")
                Separator()
                SettingsTextRow(stringResource(R.string.form_sni), sni, { sni = it })
                Separator()
                SettingsTextRow(stringResource(R.string.form_host), host, { host = it })
                Separator()
                SettingsTextRow(stringResource(R.string.form_path), path, { path = it })
                if (formType == AddNodeFormType.VLESS) {
                    Separator()
                    SettingsTextRow(stringResource(R.string.form_flow), flow, { flow = it })
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsActionRow(
                label = stringResource(R.string.common_save),
                icon = Icons.Default.Check,
                tint = Ios.Green,
                enabled = canSave,
            ) {
                onSubmit(buildManualUri(formType, name, address, port, uuid, username, method, network, security, sni, host, path, flow, alpn))
            }
        }
        if (!canSave) {
            SettingsFooter(
                S(R.string.address_port) + (if (needsCredential) S(R.string.and_id_password) else "") +
                    S(R.string.are_required_saving_stays_disabled_until_they) +
                    S(R.string.fine_and_then_fails_to_connect_with)
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The URI builders, verbatim from the old form.
 *
 * Lifted into a function of their own only so the composable above reads as a form rather than as
 * a `when` with six string-concatenation branches inside a button's onClick.
 */
private fun buildManualUri(
    formType: AddNodeFormType,
    name: String,
    address: String,
    port: String,
    uuid: String,
    username: String,
    method: String,
    network: String,
    security: String,
    sni: String,
    host: String,
    path: String,
    flow: String,
    alpn: String,
): String {
    val remark = name.ifEmpty { "Manual Node" }
    val encodedRemark = URLEncoder.encode(remark, "UTF-8")
    return when (formType) {
        AddNodeFormType.VMESS -> {
            val json = JSONObject()
            json.put("v", "2")
            json.put("ps", remark)
            json.put("add", address)
            json.put("port", port)
            json.put("id", uuid)
            json.put("aid", "0")
            json.put("scy", "auto")
            json.put("net", network)
            json.put("type", "none")
            json.put("host", host)
            json.put("path", path)
            json.put("tls", security)
            json.put("sni", sni)
            json.put("alpn", alpn)
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            "vmess://" + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
        AddNodeFormType.VLESS -> {
            var u = "vless://$uuid@$address:$port?type=$network&security=$security"
            if (sni.isNotEmpty()) u += "&sni=$sni"
            if (host.isNotEmpty()) u += "&host=$host"
            if (path.isNotEmpty()) u += "&path=${URLEncoder.encode(path, "UTF-8")}"
            if (flow.isNotEmpty()) u += "&flow=$flow"
            if (alpn.isNotEmpty()) u += "&alpn=$alpn"
            u + "#$encodedRemark"
        }
        AddNodeFormType.TROJAN -> {
            var u = "trojan://$uuid@$address:$port?type=$network&security=$security"
            if (sni.isNotEmpty()) u += "&sni=$sni"
            if (host.isNotEmpty()) u += "&host=$host"
            if (path.isNotEmpty()) u += "&path=${URLEncoder.encode(path, "UTF-8")}"
            u + "#$encodedRemark"
        }
        AddNodeFormType.SHADOWSOCKS -> {
            val auth = android.util.Base64.encodeToString("$method:$uuid".toByteArray(), android.util.Base64.NO_WRAP)
            "ss://$auth@$address:$port#$encodedRemark"
        }
        AddNodeFormType.SOCKS -> {
            val auth = if (username.isNotEmpty() || uuid.isNotEmpty()) {
                android.util.Base64.encodeToString("$username:$uuid".toByteArray(), android.util.Base64.NO_WRAP) + "@"
            } else ""
            "socks://$auth$address:$port#$encodedRemark"
        }
        AddNodeFormType.HTTP -> {
            val auth = if (username.isNotEmpty() || uuid.isNotEmpty()) {
                android.util.Base64.encodeToString("$username:$uuid".toByteArray(), android.util.Base64.NO_WRAP) + "@"
            } else ""
            "http://$auth$address:$port#$encodedRemark"
        }
        else -> ""
    }
}

/** A subscription link, as two fields rather than two Material text fields in a dialog. */
@Composable
private fun SubLinkAddScreen(onSubmit: (String, String) -> Unit, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val urlOk = url.startsWith("http://") || url.startsWith("https://")

    IosScreen(title = S(R.string.subscription_link), onBack = onBack, backLabel = S(R.string.add_r2)) {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsTextRow(S(R.string.name_r2), name, { name = it }, placeholder = S(R.string.optional_2))
            Separator()
            SettingsTextRow(
                S(R.string.link),
                url,
                { url = it },
                placeholder = "https://…",
                error = if (url.isNotBlank() && !urlOk) S(R.string.the_link_must_start_with_http_or) else null,
            )
        }
        SettingsFooter(
            S(R.string.a_subscription_link_is_an_address_that) +
                S(R.string.update_subscriptions_the_list_is_fetched_fresh)
        )

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.save_and_fetch),
                icon = Icons.Default.Check,
                tint = Ios.Green,
                enabled = urlOk,
            ) {
                onSubmit(name.ifBlank { "Sub-${UUID.randomUUID().toString().take(4)}" }, url.trim())
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

