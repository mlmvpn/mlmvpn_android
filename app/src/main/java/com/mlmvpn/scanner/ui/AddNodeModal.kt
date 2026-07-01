package com.mlmvpn.scanner.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.UUID

enum class AddNodeFormType {
    NONE, VLESS, VMESS, TROJAN, SHADOWSOCKS, SOCKS, HTTP, DEFAULT_SNI, SUB_LINK
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNodeModal(
    onDismiss: () -> Unit,
    onNodesAdded: (List<VpnNode>) -> Unit,
    onSubscriptionAdded: ((name: String, url: String) -> Unit)? = null,
    onUpdateSubscriptions: (() -> Unit)? = null,
    selectedManualGroup: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentForm by remember { mutableStateOf(AddNodeFormType.NONE) }
    
    // Launchers
    val qrScannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val uri = result.contents.trim()
            if (isValidUri(uri)) {
                val node = createNodeFromUri(uri)
                node.groupTitle = selectedManualGroup
                onNodesAdded(listOf(node))
                Toast.makeText(context, context.getString(R.string.nodes_add_success), Toast.LENGTH_SHORT).show()
                onDismiss()
            } else if (uri.startsWith("http://") || uri.startsWith("https://")) {
                onSubscriptionAdded?.invoke("Sub-${java.util.UUID.randomUUID().toString().substring(0,4)}", uri)
                Toast.makeText(context, context.getString(R.string.nodes_add_success), Toast.LENGTH_SHORT).show()
                onDismiss()
            } else {
                Toast.makeText(context, context.getString(R.string.nodes_invalid_config), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val nodesToAdd = mutableListOf<VpnNode>()
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val reader = BufferedReader(InputStreamReader(stream))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val config = line?.trim() ?: ""
                            if (isValidUri(config)) {
                                val node = createNodeFromUri(config)
                                node.groupTitle = selectedManualGroup
                                nodesToAdd.add(node)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (nodesToAdd.isNotEmpty()) {
                            onNodesAdded(nodesToAdd)
                            Toast.makeText(context, context.getString(R.string.nodes_add_success), Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } else {
                            Toast.makeText(context, context.getString(R.string.nodes_invalid_config), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
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
            val lines = text.split("\n", "\r").map { it.trim() }
            val nodesToAdd = mutableListOf<VpnNode>()
            var subAdded = false
            for (line in lines) {
                if (isValidUri(line)) {
                    val node = createNodeFromUri(line)
                    node.groupTitle = selectedManualGroup
                    nodesToAdd.add(node)
                } else if (line.startsWith("http://") || line.startsWith("https://")) {
                    onSubscriptionAdded?.invoke("Sub-${java.util.UUID.randomUUID().toString().substring(0,4)}", line)
                    subAdded = true
                }
            }
            if (nodesToAdd.isNotEmpty() || subAdded) { if (nodesToAdd.isNotEmpty()) { onNodesAdded(nodesToAdd); Toast.makeText(context, context.getString(R.string.nodes_add_success), Toast.LENGTH_SHORT).show() }
                onDismiss()
            } else {
                Toast.makeText(context, context.getString(R.string.nodes_invalid_config), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, context.getString(R.string.nodes_invalid_config), Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .padding(top = com.mlmvpn.scanner.ui.LocalSystemTopPadding.current, bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, BorderDark, RoundedCornerShape(24.dp)),
            color = SurfaceDark
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val title = if (currentForm == AddNodeFormType.NONE) stringResource(R.string.nodes_add_title) else getTitleForForm(currentForm)
                    Text(title, color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = {
                        if (currentForm == AddNodeFormType.NONE) onDismiss() else currentForm = AddNodeFormType.NONE
                    }) {
                        Icon(if (currentForm == AddNodeFormType.NONE) Icons.Default.Close else Icons.Default.ArrowBack, contentDescription = "Close/Back", tint = TextPrimary)
                    }
                }
                
                Divider(color = BorderDark, modifier = Modifier.padding(vertical = 12.dp))

                AnimatedContent(targetState = currentForm, label = "FormTransition") { form ->
                    if (form == AddNodeFormType.NONE) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Text("Quick Add", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    AddOptionCard(Modifier.weight(1f), Icons.Default.QrCodeScanner, stringResource(R.string.nodes_add_qrcode)) {
                                        val options = ScanOptions()
                                        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        options.setPrompt("Scan a VPN Configuration QR Code")
                                        options.setBeepEnabled(false)
                                        options.setOrientationLocked(false)
                                        qrScannerLauncher.launch(options)
                                    }
                                    AddOptionCard(Modifier.weight(1f), Icons.Default.ContentPaste, stringResource(R.string.nodes_add_clipboard)) {
                                        handleClipboard()
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    AddOptionCard(Modifier.weight(1f), Icons.Default.UploadFile, stringResource(R.string.nodes_add_file)) {
                                        filePickerLauncher.launch("*/*")
                                    }
                                    AddOptionCard(Modifier.weight(1f), Icons.Default.Public, stringResource(R.string.add_node_default_sni)) {
                                        currentForm = AddNodeFormType.DEFAULT_SNI
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    AddOptionCard(Modifier.weight(1f), Icons.Default.Link, "افزودن لینک ساب") {
                                        currentForm = AddNodeFormType.SUB_LINK
                                    }
                                    AddOptionCard(Modifier.weight(1f), Icons.Default.Refresh, "بروزرسانی ساب‌ها") {
                                        onUpdateSubscriptions?.invoke()
                                        onDismiss()
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Manual Entry", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                                AddOptionCard(Modifier.fillMaxWidth(), Icons.Default.Edit, stringResource(R.string.nodes_add_manual_vless)) { currentForm = AddNodeFormType.VLESS }
                                Spacer(modifier = Modifier.height(8.dp))
                                AddOptionCard(Modifier.fillMaxWidth(), Icons.Default.Edit, stringResource(R.string.nodes_add_manual_vmess)) { currentForm = AddNodeFormType.VMESS }
                                Spacer(modifier = Modifier.height(8.dp))
                                AddOptionCard(Modifier.fillMaxWidth(), Icons.Default.Edit, stringResource(R.string.nodes_add_manual_trojan)) { currentForm = AddNodeFormType.TROJAN }
                                Spacer(modifier = Modifier.height(8.dp))
                                AddOptionCard(Modifier.fillMaxWidth(), Icons.Default.Edit, stringResource(R.string.nodes_add_manual_ss)) { currentForm = AddNodeFormType.SHADOWSOCKS }
                                Spacer(modifier = Modifier.height(8.dp))
                                AddOptionCard(Modifier.fillMaxWidth(), Icons.Default.Edit, stringResource(R.string.nodes_add_manual_socks)) { currentForm = AddNodeFormType.SOCKS }
                                Spacer(modifier = Modifier.height(8.dp))
                                AddOptionCard(Modifier.fillMaxWidth(), Icons.Default.Edit, stringResource(R.string.nodes_add_manual_http)) { currentForm = AddNodeFormType.HTTP }
                            }
                        }
                    } else if (form == AddNodeFormType.DEFAULT_SNI) {
                        DefaultSniConfigForm(
                            maxCount = DEFAULT_SNI_CONFIGS.size,
                            onSubmit = { count ->
                                val selectedConfigs = DEFAULT_SNI_CONFIGS.shuffled().take(count)
                                val nodesToAdd = selectedConfigs.mapNotNull {
                                    if (isValidUri(it)) {
                                        val node = createNodeFromUri(it)
                                        node.groupTitle = selectedManualGroup
                                        node
                                    } else null
                                }
                                if (nodesToAdd.isNotEmpty()) {
                                    onNodesAdded(nodesToAdd)
                                    Toast.makeText(context, "Added ${nodesToAdd.size} configs!", Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            }
                        )
                    } else if (form == AddNodeFormType.SUB_LINK) {
                        SubLinkForm(
                            onSubmit = { name, url ->
                                onSubscriptionAdded?.invoke(name, url)
                                onDismiss()
                            }
                        )
                    } else {
                        // Manual Entry Form
                        ManualEntryForm(
                            formType = form,
                            onSubmit = { uri ->
                                val node = createNodeFromUri(uri)
                                node.groupTitle = selectedManualGroup
                                onNodesAdded(listOf(node))
                                Toast.makeText(context, context.getString(R.string.nodes_add_success), Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddOptionCard(modifier: Modifier = Modifier, icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BgDark)
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(title, color = TextPrimary, fontSize = 14.sp)
    }
}

@Composable
fun getTitleForForm(form: AddNodeFormType): String {
    return when(form) {
        AddNodeFormType.VMESS -> stringResource(R.string.nodes_add_manual_vmess)
        AddNodeFormType.VLESS -> stringResource(R.string.nodes_add_manual_vless)
        AddNodeFormType.TROJAN -> stringResource(R.string.nodes_add_manual_trojan)
        AddNodeFormType.SHADOWSOCKS -> stringResource(R.string.nodes_add_manual_ss)
        AddNodeFormType.SOCKS -> stringResource(R.string.nodes_add_manual_socks)
        AddNodeFormType.HTTP -> stringResource(R.string.nodes_add_manual_http)
        AddNodeFormType.DEFAULT_SNI -> stringResource(R.string.add_node_default_sni)
        AddNodeFormType.SUB_LINK -> "افزودن لینک سابسکریپشن"
        else -> ""
    }
}

// ----------------------------------------------------
// Manual Entry Form
// ----------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryForm(formType: AddNodeFormType, onSubmit: (String) -> Unit) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    
    // Common fields
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var name by remember { mutableStateOf("") }
    
    // Auth
    var uuid by remember { mutableStateOf("") } // or password
    var username by remember { mutableStateOf("") }
    
    // Transports & Stream
    var network by remember { mutableStateOf("ws") }
    var security by remember { mutableStateOf("tls") }
    var sni by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }
    var flow by remember { mutableStateOf("") }
    var alpn by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("chacha20-ietf-poly1305") }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            
            FormTextField(stringResource(R.string.form_name), name) { name = it }
            FormTextField(stringResource(R.string.form_address), address) { address = it }
            FormTextField(stringResource(R.string.form_port), port) { port = it }
            
            if (formType == AddNodeFormType.VMESS || formType == AddNodeFormType.VLESS) {
                FormTextField(stringResource(R.string.form_uuid), uuid) { uuid = it }
            } else if (formType == AddNodeFormType.TROJAN || formType == AddNodeFormType.SHADOWSOCKS) {
                FormTextField(stringResource(R.string.form_password), uuid) { uuid = it }
            } else if (formType == AddNodeFormType.SOCKS || formType == AddNodeFormType.HTTP) {
                FormTextField(stringResource(R.string.form_username), username) { username = it }
                FormTextField(stringResource(R.string.form_password), uuid) { uuid = it }
            }

            if (formType == AddNodeFormType.SHADOWSOCKS) {
                FormTextField(stringResource(R.string.form_method), method) { method = it }
            }

            if (formType == AddNodeFormType.VMESS || formType == AddNodeFormType.VLESS || formType == AddNodeFormType.TROJAN) {
                Divider(color = BorderDark, modifier = Modifier.padding(vertical = 8.dp))
                Text("Transport Settings", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                
                FormTextField(stringResource(R.string.form_network), network) { network = it }
                FormTextField(stringResource(R.string.form_tls), security) { security = it }
                FormTextField(stringResource(R.string.form_sni), sni) { sni = it }
                FormTextField(stringResource(R.string.form_host), host) { host = it }
                FormTextField(stringResource(R.string.form_path), path) { path = it }
                
                if (formType == AddNodeFormType.VLESS) {
                    FormTextField(stringResource(R.string.form_flow), flow) { flow = it }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        Button(
            onClick = {
                val remark = name.ifEmpty { "Manual Node" }
                val encodedRemark = URLEncoder.encode(remark, "UTF-8")
                val uri = when (formType) {
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
                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        "vmess://$base64"
                    }
                    AddNodeFormType.VLESS -> {
                        var u = "vless://$uuid@$address:$port?type=$network&security=$security"
                        if (sni.isNotEmpty()) u += "&sni=$sni"
                        if (host.isNotEmpty()) u += "&host=$host"
                        if (path.isNotEmpty()) u += "&path=${URLEncoder.encode(path, "UTF-8")}"
                        if (flow.isNotEmpty()) u += "&flow=$flow"
                        if (alpn.isNotEmpty()) u += "&alpn=$alpn"
                        u += "#$encodedRemark"
                        u
                    }
                    AddNodeFormType.TROJAN -> {
                        var u = "trojan://$uuid@$address:$port?type=$network&security=$security"
                        if (sni.isNotEmpty()) u += "&sni=$sni"
                        if (host.isNotEmpty()) u += "&host=$host"
                        if (path.isNotEmpty()) u += "&path=${URLEncoder.encode(path, "UTF-8")}"
                        u += "#$encodedRemark"
                        u
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
                onSubmit(uri)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = BgDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextMuted) },
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            unfocusedIndicatorColor = BorderDark,
            focusedIndicatorColor = Primary,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = Primary
        ),
        singleLine = true
    )
}

// ----------------------------------------------------
// Helpers
// ----------------------------------------------------
fun isValidUri(uri: String): Boolean {
    val lower = uri.lowercase()
    return lower.startsWith("vmess://") ||
           lower.startsWith("vless://") ||
           lower.startsWith("trojan://") ||
           lower.startsWith("ss://") ||
           lower.startsWith("socks://")
           
           
}

fun createNodeFromUri(uri: String): VpnNode {
    val type = when {
        uri.lowercase().startsWith("vless") -> "vless"
        uri.lowercase().startsWith("trojan") -> "trojan"
        uri.lowercase().startsWith("ss") -> "shadowsocks"
        uri.lowercase().startsWith("socks") -> "socks"
        uri.lowercase().startsWith("http") -> "http"
        else -> "vmess"
    }
    
    // Best effort name extraction for quick display, 
    // real parsing happens in VpnConfig.parseUri during connection/ping
    var name = "New Node"
    if (uri.contains("#")) {
        name = java.net.URLDecoder.decode(uri.substringAfterLast("#"), "UTF-8")
    } else if (uri.startsWith("vmess://")) {
        try {
            val base64 = uri.substring(8)
            val jsonStr = String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            if (json.has("ps")) name = json.getString("ps")
        } catch (e: Exception) {}
    }

    return VpnNode(
        id = UUID.randomUUID().toString(),
        name = name,
        uri = uri.trim(),
        type = type,
        engineType = "Manual"
    )
}

@Composable
fun DefaultSniConfigForm(maxCount: Int, onSubmit: (Int) -> Unit) {
    var count by remember { mutableStateOf(if (maxCount > 0) 1 else 0) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.add_node_default_sni_count), color = TextPrimary, fontSize = 16.sp)
        
        Text(stringResource(R.string.add_node_config_unit, count), color = Primary, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        
        Slider(
            value = count.toFloat(),
            onValueChange = { count = it.toInt() },
            valueRange = 1f..maxCount.toFloat(),
            steps = if (maxCount > 2) maxCount - 2 else 0,
            colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = { onSubmit(count) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = BgDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.add_node_add_btn), fontWeight = FontWeight.Bold)
        }
    }
}

val DEFAULT_SNI_CONFIGS = listOf(
    "vless://fc965ad9-bdd7-4815-ad71-b39ec5972dc1@127.0.0.1:40443?encryption=none&security=tls&sni=octopusss4.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=octopusss4.com&path=%2Ftsghdws#%20By%20EbraSha%20%F0%9F%90%BA",
    "vless://fc965ad9-bdd7-4815-ad71-b39ec5972dc1@127.0.0.1:40443?encryption=none&security=tls&sni=octopusss4.com&insecure=0&allowInsecure=0&type=ws&host=octopusss4.com&path=%2Ftsghdws#%20By%20EbraSha%20%F0%9F%A7%B2",
    "vless://f2a73750-3087-48ff-a763-1348c15dce68@127.0.0.1:40443?encryption=none&security=tls&sni=rAyAn-007.mAxImA.DpDnS.OrG&insecure=0&allowInsecure=0&type=ws&host=rAyAn-007.mAxImA.DpDnS.OrG&path=%2F#%20By%20EbraSha%20%F0%9F%9B%9C",
    "vless://f4acdab7-7487-4bb0-bce0-d8a9906d44aa@127.0.0.1:40443?encryption=none&security=tls&sni=little-surf-1d9a.amirhost1.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=little-surf-1d9a.amirhost1.workers.dev&path=%2F#%20By%20EbraSha%20%F0%9F%93%B1",
    "vless://c7addea9-6d8a-48d6-8a00-aa99b8ece143@127.0.0.1:40443?encryption=none&security=tls&sni=ws35.adsvxpro.com&fp=firefox&insecure=0&allowInsecure=0&type=ws&host=ws35.adsvxpro.com&path=%2Fpath#%F0%9F%87%A8%F0%9F%87%A650403%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#%40meliproxyy",
    "vless://d8c05f47-6ca7-4c6a-badf-b7e5e818699c@127.0.0.1:40443?encryption=none&security=tls&sni=noiZvPn.aSAd13321.wORkers.dEv&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2FeyJqdW5rIjoiM25HVThlWGhhcThVT3g2ZSIsInByb3RvY29sIjoidmwiLCJtb2RlIjoicHJveHlpcCIsInBhbmVsSVBzIjpbXX0%3D%3Fed%3D2560#%F0%9F%87%A8%F0%9F%87%A650427%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "vless://d3c88e05-8df2-46ea-8205-65c5c86bb6fd@127.0.0.1:40443?encryption=none&security=tls&sni=PEZesHkIaN.jEnde.woRkers.DeV&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2FeyJqdW5rIjoiOWxyNGZ2OFpTNGdGeCIsInByb3RvY29sIjoidmwiLCJtb2RlIjoicHJveHlpcCIsInBhbmVsSVBzIjpbXX0%3D%3Fed%3D2560#%F0%9F%87%A8%F0%9F%87%B750428%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "vless://45c8b2f4-343c-4d37-99d2-59cb13e6c7fb@127.0.0.1:40443?encryption=none&security=tls&sni=peDarET.BaBJOON.WOrKErs.deV&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2FeyJqdW5rIjoiQmtnQkhTSzYiLCJwcm90b2NvbCI6InZsIiwibW9kZSI6InByb3h5aXAiLCJwYW5lbElQcyI6W119%3Fed%3D2560#%F0%9F%87%A8%F0%9F%87%A650429%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#1",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#2",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#3",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#4",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#5",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#6",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#7",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&fp=safari&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#8",
    "vless://fc965ad9-bdd7-4815-ad71-b39ec5972dc1@127.0.0.1:40443?encryption=none&security=tls&sni=octopusss4.com&fp=chrome&alpn=h3%2Ch2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=octopusss4.com&path=%2Ftsghdws#9",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#10",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#11",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%897",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%8910",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%892",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%893",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%894",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%895",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%896",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%899",
    "vless://577699f7-468d-4b63-ae21-cdcdfd8d11c2@127.0.0.1:40443?encryption=none&security=tls&sni=baguette.adaspoloandco.com&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=baguette.adaspoloandco.com&path=%2FGoorBah#Baguette-France-c8",
    "vless://577699f7-468d-4b63-ae21-cdcdfd8d11c2@127.0.0.1:40443?encryption=none&security=tls&sni=baguette.adaspoloandco.com&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=baguette.adaspoloandco.com&path=%2FGoorBah#Baguette-France-c10",
    "vless://10a6b923-e349-4594-92bb-d81a6245aaec@127.0.0.1:40443?encryption=none&security=tls&sni=sertraline.adaspoloandco.com&fp=chrome&alpn=http%2F1.1%2Ch2&insecure=0&allowInsecure=0&type=ws&host=sertraline.adaspoloandco.com&path=%2Fdownload.php#Sertraline-Finland-c10",
    "vless://10a6b923-e349-4594-92bb-d81a6245aaec@127.0.0.1:40443?encryption=none&security=tls&sni=sertraline.adaspoloandco.com&fp=chrome&alpn=http%2F1.1%2Ch2&insecure=0&allowInsecure=0&type=ws&host=sertraline.adaspoloandco.com&path=%2Fdownload.php#Sertraline-Finland-c11",
    "vless://10a6b923-e349-4594-92bb-d81a6245aaec@127.0.0.1:40443?encryption=none&security=tls&sni=sertraline.adaspoloandco.com&fp=chrome&alpn=http%2F1.1%2Ch2&insecure=0&allowInsecure=0&type=ws&host=sertraline.adaspoloandco.com&path=%2Fdownload.php#Sertraline-Finland-c12",
    "vless://10a6b923-e349-4594-92bb-d81a6245aaec@127.0.0.1:40443?encryption=none&security=tls&sni=sertraline.adaspoloandco.com&fp=chrome&alpn=http%2F1.1%2Ch2&insecure=0&allowInsecure=0&type=ws&host=sertraline.adaspoloandco.com&path=%2Fdownload.php#Sertraline-Finland-c13",
    "vless://10a6b923-e349-4594-92bb-d81a6245aaec@127.0.0.1:40443?encryption=none&security=tls&sni=sertraline.adaspoloandco.com&fp=chrome&alpn=http%2F1.1%2Ch2&insecure=0&allowInsecure=0&type=ws&host=sertraline.adaspoloandco.com&path=%2Fdownload.php#Sertraline-Finland-c14",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=37cdnws.iamali.info&fp=firefox&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=37cdnws.iamali.info&path=%2F37cdnws#Ettehadvpn%20%7C%2023",
    "vless://%40a_amr79@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv#%F0%9F%8C%90%20Anycast-IP%20%7C%20%F0%9F%87%A8%F0%9F%87%A6%20%F0%9F%87%AC%F0%9F%87%A7%20%F0%9F%87%B2%F0%9F%87%BE%20%F0%9F%87%B7%F0%9F%87%BA%20%5B%2ACIDR%5D%20t.me%2Frjsxrd",
    "vless://%40a_amr79@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv#%F0%9F%8C%90%20Anycast-IP%20%7C%20%F0%9F%87%AC%F0%9F%87%A7%20%F0%9F%87%B2%F0%9F%87%BE%20%F0%9F%87%B7%F0%9F%87%BA%20%5B%2ACIDR%5D%20t.me%2Frjsxrd",
    "vless://1ce9392f-caf4-48f7-bbb9-475b101fbde0@127.0.0.1:40443?encryption=none&security=tls&sni=graph.instagram.com.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=graph.instagram.com.cyylr.eu.cc&path=%2Fid-tksi#%5B%F0%9F%87%AE%F0%9F%87%A9%5D%20%5Bvl-tl-ws%5D%20%5B260602-084353.555%5D%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=37cdnws.iamali.info&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=37cdnws.iamali.info&path=%2F37cdnws#%F0%9F%94%A5Join%2BTelegram%3A%40Farah_VPN%F0%9F%9F%A3%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=37cdnws.iamali.info&fp=firefox&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=37cdnws.iamali.info&path=%2F37cdnws#4Kian-9432%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=35cdnws.frnstatic.info&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=35cdnws.frnstatic.info&path=%2F35cdnws#4Kian-9431%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=35cdnws.frnstatic.info&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=35cdnws.frnstatic.info&path=%2F35cdnws#US_speednode_0054%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=35cdnws.frnstatic.info&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=35cdnws.frnstatic.info&path=%2F35cdnws#%20By%20EbraSha%20t.me%2Frjsxrd",
    "vless://c2f0a8f0-36fe-4a94-824b-bca271ca642b@127.0.0.1:40443?encryption=none&security=tls&sni=alphacdn.alphashops.shop&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=alphacdn.alphashops.shop&path=%2Fws#CF%E4%B8%AD%E8%BD%AC_0602176520%20t.me%2Frjsxrd",
    "vless://2dc797c8-9588-48a6-bc4a-265c23d87cd6@127.0.0.1:40443?encryption=none&security=tls&sni=ava.game.naver.com.cyylr.eu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=ava.game.naver.com.cyylr.eu.cc&path=%2Fid-pusat#%F0%9F%87%BA%F0%9F%87%B8%20%E6%9C%BA%E5%9C%BA%E6%8E%A8%E8%8D%90%3Adafei.de%20%E7%BE%8E%E5%9B%BD%2017%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=35cdnws.frnstatic.info&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=35cdnws.frnstatic.info&path=%2F35cdnws#20.%20%F0%9F%87%AB%F0%9F%87%B7%20France%20t.me%2Frjsxrd",
    "vless://%40a_amr79@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv#%F0%9F%87%A8%F0%9F%87%A6%D0%9E%D0%B1%D1%85%D0%BE%D0%B4%D1%8B%20-%20TG%3A%20AirLinkVPNBot%20%7C0232%7C%20t.me%2Frjsxrd",
    "vless://%40a_amr79@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv#%F0%9F%87%AC%F0%9F%87%A7%D0%9E%D0%B1%D1%85%D0%BE%D0%B4%D1%8B%20-%20TG%3A%20AirLinkVPNBot%20%7C0238%7C%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=37cdnws.iamali.info&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=37cdnws.iamali.info&path=%2F37cdnws#%5B69380%5D%20-%20Telegram%20%3A%20%40V2All%20t.me%2Frjsxrd",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&insecure=0&allowInsecure=0&ech=ip.gs%2Budp%3A%2F%2F8.8.8.8&type=ws&host=www.creationlong.org&path=%2Fassignment#%F0%9F%87%B5%F0%9F%87%B1%20%D0%9F%D0%BE%D0%BB%D1%8C%D1%88%D0%B0%203%20%7C%20%5BBL%5D%20t.me%2Frjsxrd",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&insecure=0&allowInsecure=0&type=ws&host=www.creationlong.org&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17290",
    "vless://2244d9e4-dc80-4c61-9362-dde0afd034dd@127.0.0.1:40443?encryption=none&security=tls&sni=hs.qoogl.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=hs.qoogl.workers.dev&path=%2F#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17917",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.calmlunch.com&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17305",
    "trojan://25ae6230-7834-41dc-94a4-586e1a79ea89@127.0.0.1:40443?security=tls&sni=shy-brook-df1e.amirkhan69.workers.dev&fp=ios&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=shy-brook-df1e.amirkhan69.workers.dev&path=%2F%3Fed%3D2048#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17722",
    "vless://aa0e6991-6eaa-4bbb-9497-abc04d54de2f@127.0.0.1:40443?encryption=none&security=tls&sni=cdn.opensignal.com.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=cdn.opensignal.com.cyylr.eu.cc&path=%2Fsg-melbi#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18141",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.creationlong.org&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-12035",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&insecure=0&allowInsecure=0&type=ws&host=www.creationlong.org&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17078",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=0&allowInsecure=0&type=ws&host=www.multiplydose.com&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17647",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=0&allowInsecure=0&type=ws&host=www.multiplydose.com&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17750",
    "vless://cc752a3e-1537-4e86-bb50-1b897bf7b33c@127.0.0.1:40443?encryption=none&security=tls&sni=cyylr.eu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=cyylr.eu.cc&path=%2Fsg-melbi%23TELEGRAM-MARAMBASHI_MARAMBASHI_MARAMBASHI_MARAMBASHI_MARAMBASHI%3Fed%3D512#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18076",
    "vless://6a8f6dc6-2a42-4a03-8047-e39ce6df3ec9@127.0.0.1:40443?encryption=none&security=tls&sni=www.genflix.co.id.cyylr.eu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=www.genflix.co.id.cyylr.eu.cc&path=%2Fmy-wbv#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18100",
    "vless://5f225374-54c9-4945-91ea-a911b9384239@127.0.0.1:40443?encryption=none&security=tls&sni=graph.instagram.com.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=graph.instagram.com.cyylr.eu.cc&path=%2Fmy-wbv#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18142",
    "vless://429f43ca-087a-4fbc-9d88-9d9007bf30bc@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18207",
    "vless://429f43ca-087a-4fbc-9d88-9d9007bf30bc@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv%23TELEGRAM-MARAMBASHI_MARAMBASHI_MARAMBASHI_MARAMBASHI_MARAMBASHI%3Fed%3D512#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18215",
    "vless://adffad16-ed8f-4020-97b5-64d1c4548222@127.0.0.1:40443?encryption=none&security=tls&sni=www.genflix.co.id.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=www.genflix.co.id.cyylr.eu.cc&path=%2Fmy-wbv#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18219",
    "vless://9dd05602-de19-494a-af15-6ac1cd6fb4b2@127.0.0..1:40443?encryption=none&security=tls&sni=graph.instagram.com.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=graph.instagram.com.cyylr.eu.cc&path=%2Fmy-wbv#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18236",
    "vless://fc965ad9-bdd7-4815-ad71-b39ec5972dc1@127.0.0.1:40443?encryption=none&security=tls&sni=octopusss4.com&fp=chrome&alpn=h3%2Ch2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=octopusss4.com&path=%2Ftsghdws#PLASMA_SERVE",
    "vless://fc965ad9-bdd7-4815-ad71-b39ec5972dc1@127.0.0.1:40443?encryption=none&security=tls&sni=octopusss4.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=octopusss4.com&path=%2Ftsghdws#Ettehadvpn%20%7C%206",
    "trojan://0jvOqSPtoC@127.0.0.1:40443?security=tls&sni=da.aananas.ir&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2F%3Fed%3D2048#%F0%9F%87%A8%F0%9F%87%A645754%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "vless://0413d90c-0d59-4c38-ac2c-1aff836c45d1@127.0.0.1:40443?encryption=none&security=tls&sni=vpn.madiden137.workers.dev&fp=chrome&alpn=h3%2Ch2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=vpn.madiden137.workers.dev&path=%2F%3Fed%3D2048#hamvex%20snispf%C2%B9",
    "trojan://mitivpn@127.0.0.1:40443?security=tls&sni=cdn.linkman1.ir&fp=chrome&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=cdn.linkman1.ir&path=%2F720f09dba195249b423f771661162528%2Fworkers%2Fservices%2Fview%2Fmitivpn%2Fproduction%2Fsettings#hamvex%20snispf%C2%B2",
    "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@127.0.0.1:40443?encryption=none&security=tls&sni=cdn.veilvpn.fans&fp=chrome&insecure=1&allowInsecure=1&type=httpupgrade&host=cdn.veilvpn.fans&path=%2Fvpnhu#hamvex%20snispf%C2%B3",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&insecure=1&allowInsecure=1&type=ws&host=www.calmlunch.com&path=%2Fassignment#hamvex%20snispf%E2%81%B4",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&fp=chrome&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#hamvex%20snispf%E2%81%B5",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#hamvex%20snispf%E2%81%B6",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=ios&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#hamvex%20snispf%E2%81%B7",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#hamvex%20snispf%E2%81%B8",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=1&allowInsecure=1&type=ws&host=www.gossipglove.com&path=%2Fassignment#hamvex%20snispf%E2%81%B9",
    "vless://InternetAzadRobot@146.75.117.91:80?encryption=none&security=none&type=xhttp&host=tignaltofansv4.global.ssl.fastly.net&path=%2FTignal&mode=auto#hamvex%20snispf%C2%B9%E2%81%B0",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&insecure=1&allowInsecure=1&type=ws&host=www.creationlong.org&path=%2Fassignment#hamvex%20snispf%C2%B9%C2%B9",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#hamvex%20snispf%C2%B9%C2%B2",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.calmloud.com&path=%2Fassignment#hamvex%20snispf%C2%B9%C2%B3",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#hamvex%20snispf%C2%B9%E2%81%B4",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&fp=chrome&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#hamvex%20snispf%C2%B9%E2%81%B5",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=www.calmloud.com&path=%2Fassignment#hamvex%20snispf%C2%B9%E2%81%B6",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=0&allowInsecure=0&type=ws&host=www.multiplydose.com&path=%2F%2Fassignment#hamvex%20snispf%C2%B9%E2%81%B7",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&alpn=h2%2Ch3%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&path=assignment#hamvex%20snispf%C2%B9%E2%81%B8",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&fp=chrome&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#hamvex%20snispf%C2%B9%E2%81%B9",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#hamvex%20snispf%C2%B2%E2%81%B0",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=1&allowInsecure=1&type=ws&host=www.multiplydose.com&path=%2Fassignment#hamvex%20snispf%C2%B2%C2%B9",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&type=ws&host=www.ignitelimit.com&path=/assignment#hamvex%20snispf%C2%B2%E2%81%B0",
    "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@127.0.0.1:40443?encryption=none&security=tls&sni=cdn.veilvpn.fans&allowInsecure=true&fp=chrome&type=httpupgrade&host=cdn.veilvpn.fans&path=/vpnhu&packetEncoding=xudp#hamvex%20snispf%C2%B3",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&allowInsecure=true&type=ws&host=www.calmlunch.com&path=/assignment#hamvex%20snispf%E2%81%B4",
    "vless://6682035b-8de0-4603-9e8a-b496857897b2@chatgpt.com:2095?encryption=none&security=none&type=ws&host=n5.asmdns.net&path=%2F#none",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#%F0%9F%87%AB%F0%9F%87%B7%20",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=floral-feather-f5dc.fg165fdr4g.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=floral-feather-f5dc.fg165fdr4g.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%899",
    "vless://6202b230-417c-4d8e-b624-0f71afa9c75d@198.12.145.30:8880?encryption=none&security=none&type=ws&host=vms.lifetime61.workers.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://179718c1-27b3-449d-9626-98c2c854d010@127.0.0.1:40443?encryption=none&security=tls&sni=del.ccnnx.ir&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=del.ccnnx.ir&path=%2F%40v2raydia--%40v2raydia--%40v2raydia--%40v2raydia--%40v2raydia--%40v2raydia--%40v2raydia--%40v2raydia--%40v2raydia--%40v2raydia#%40DeltaKroneckerGithub",
    "vless://af501b09-1af5-4399-b36a-52624e4304a7@127.0.0.1:40443?encryption=none&security=tls&sni=09OSerT5Ts9U9wgB4X-u45B-h5u8w275.yzDaN2879.WOrKERS.DEV&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=09osert5ts9u9wgb4x-u45b-h5u8w275.yzdan2879.workers.dev&path=%2FeyJqdW5rIjoibEdCdlM3dElEZWkxIiwicHJvdG9jb2wiOiJ2bCIsIm1vZGUiOiJwcm94eWlwIiwicGFuZWxJUHMiOltdfQ%3D%3D#%40DeltaKroneckerGithub",
    "vless://45d9e1d7-2853-4b7e-8bad-2dd08bb87cd4@127.0.0.1:40443?encryption=none&security=tls&sni=sertraline.adaspoloandco.com&fp=chrome&alpn=http%2F1.1%2Ch2&insecure=0&allowInsecure=0&type=ws&host=sertraline.adaspoloandco.com&path=%2Fdownload.php#Sertraline-Finland-c10",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#%40SNI_SPOOFINGconfig",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&insecure=0&allowInsecure=0&type=ws&host=www.calmlunch.com&path=%2Fassignment#%40SNI_SPOOFINGconfig",
    "vless://94daa8ad-75ed-4dbf-a6de-d05036d98df5@127.0.0.1:40443?encryption=none&security=tls&sni=vangoghhh.info&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=vangoghhh.info&path=%2Ftixvuws#%F0%9F%87%A8%F0%9F%87%A6%20%40SNI_SPOOFINGconfig",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.calmloud.com&path=%2Fassignment#DADA%20%40TheVPNMethod",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#BKING%20%40TheVPNMethod",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#TALA%20%40TheVPNMethod",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&insecure=0&allowInsecure=0&type=ws&host=www.calmlunch.com&path=%2Fassignment#PEOPLE%20%40TheVPNMethod",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#FLOKI%20%40TheVPNMethod",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&path=assignment#XFINITY%20%40TheVPNMethod",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment%3FTELEGRAM--KANAL--JKVPN--JKVPN--JKVPN--JKVPN--JKVPN--JKVPN#PARI%20%40TheVPNMethod",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#YAS%20%40TheVPNMethod",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=0&allowInsecure=0&type=ws&host=www.multiplydose.com&path=%2F%2Fassignment#LION%20%40TheVPNMethod",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#PANDA%20%40TheVPNMethod",
    "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@127.0.0.1:40443?encryption=none&security=tls&sni=cdn.veilvpn.fans&fp=chrome&insecure=1&allowInsecure=1&type=httpupgrade&host=cdn.veilvpn.fans&path=%2Fvpnhu#DIDAR%20%40TheVPNMethod",
    "trojan://r137979f@127.0.0.1:40443?security=tls&sni=sheleqaz.emotionreza.workers.dev&fp=chrome&insecure=1&allowInsecure=1&type=ws&host=sheleqaz.emotionreza.workers.dev&path=%2F#%40DeltaKroneckerGithub",
    "trojan://mitivpn@127.0.0.1:40443?security=tls&sni=www.linkman1.ir&fp=chrome&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=www.linkman1.ir&path=%2F720f09dba195249b423f771661162528%2Fworkers%2Fservices%2Fview%2Fmitivpn%2Fproduction%2Fsettings#%C2%BB%C2%BB%20%40ConfigPars%20%7C%20%D8%AA%D9%84%DA%AF%D8%B1%D8%A7%D9%85%20%C2%AB%C2%AB",
    "vless://0413d90c-0d59-4c38-ac2c-1aff836c45d1@127.0.0.1:40443?encryption=none&security=tls&sni=vpn.madiden137.workers.dev&fp=chrome&alpn=h3%2Ch2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=vpn.madiden137.workers.dev&path=%2F%3Fed%3D2048#%40TL_V2ray",
    "trojan://freedom@127.0.0.1:40443?security=tls&sni=042.eLEctrocElLCo-cf-042-Fe0.wOrkERs.deV&fp=chrome&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=042.electrocellco-cf-042-fe0.workers.dev&path=%2FeyJqdW5rIjoiZ3JCVzl1T1ciLCJwcm90b2NvbCI6InRyIiwibW9kZSI6InByb3h5aXAiLCJwYW5lbElQcyI6W119#%40DeltaKroneckerGithub",
    "trojan://mitivpn@127.0.0.1:40443?security=tls&sni=cdn.linkman1.ir&fp=chrome&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=cdn.linkman1.ir&path=%2F720f09dba195249b423f771661162528%2Fworkers%2Fservices%2Fview%2Fmitivpn%2Fproduction%2Fsettings#%F0%9F%87%A8%F0%9F%87%A640202%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "vless://1f9d104e-ca0e-4202-ba4b-a0afb969c747@127.0.0.1:40443?encryption=none&security=tls&sni=www.multiplydose.cc.cd&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=1&allowInsecure=1&type=xhttp&host=www.multiplydose.cc.cd&path=%2Fassignment&mode=stream-one#%28%201%20%29%20Irancell%20%F0%9F%9F%A1%20%40Spotify_Porteghali",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&insecure=1&allowInsecure=1&type=ws&host=www.calmlunch.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://Mehdi1234@127.0.0.1:40443?security=tls&sni=purple-recipe-5e30.cimavo4626.workers.dev&fp=chrome&insecure=1&allowInsecure=1&type=ws&host=purple-recipe-5e30.cimavo4626.workers.dev&path=%2F%3Fed%3D2560#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=1&allowInsecure=1&type=ws&host=www.calmlunch.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#%40TL_V2ray",
    "trojan://bpb-trojan@127.0.0.1:40443?security=tls&sni=wing2452.nyc.mn&insecure=1&allowInsecure=1&type=ws&host=wing2452.nyc.mn&path=%2Ftr%3Fed%3D2560#%40DeltaKroneckerGithub",
    "vless://21f1af13-f6e9-4483-8a17-a521a665b335@127.0.0.1:40443?encryption=none&security=tls&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=1&allowInsecure=1&type=ws&host=germany.samanidempire.org&path=%2Freteweef#%40filembad",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#%40TL_V2ray",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#%40TL_V2ray",
    "vless://40fde68e-da2e-4e6f-ae4c-2d7422a6a172@127.0.0.1:40443?encryption=none&security=tls&sni=Freealireza2.nscl.ir&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=1&allowInsecure=1&type=ws&host=Freealireza2.nscl.ir&path=%2FKose-nanat-decoder#%28%202%20%29%20Irancell%20%F0%9F%9F%A1%20%40Spotify_Porteghali",
    "vless://1f9d104e-ca0e-4202-ba4b-a0afb969c747@127.0.0.1:40443?encryption=none&security=tls&sni=www.multiplydose.cc.cd&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=1&allowInsecure=1&type=xhttp&path=%2Fassignment&mode=stream-one#%40NormanV2rayX%F0%9F%8E%81",
    "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@127.0.0.1:40443?encryption=none&security=tls&sni=cdn.veilvpn.fans&fp=chrome&insecure=1&allowInsecure=1&type=httpupgrade&host=cdn.veilvpn.fans&path=%2Fvpnhu#%40DeltaKroneckerGithub_V2ray_1638",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&alpn=h2%2Ch3%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&path=assignment#%40TL_V2ray",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&insecure=1&allowInsecure=1&type=ws&host=www.creationlong.org&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=1&allowInsecure=1&type=ws&host=www.gossipglove.com&path=%2Fassignment#%40DeltaKroneckerGithub_V2ray_4015",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=www.calmloud.com&path=%2Fassignment#%28%201%20%29%20WiFi%20%F0%9F%9F%A2%20%40Spotify_Porteghali",
    "vless://InternetAzadRobot@146.75.117.91:80?encryption=none&security=none&type=xhttp&host=tignaltofansv4.global.ssl.fastly.net&path=%2FTignal&mode=auto#%40Ajexvpn%20%F0%9F%8E%AF",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=www.creationlong.org&path=%2Fassignment#%40TL_V2ray",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&insecure=1&allowInsecure=1&type=ws&host=www.creationlong.org&path=%2Fassignment#%40TL_V2ray",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.calmloud.com&path=%2Fassignment#%40TL_V2ray",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&fp=chrome&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#%40DeltaKroneckerGithub",
    "vless://94daa8ad-75ed-4dbf-a6de-d05036d98df5@127.0.0.1:40443?encryption=none&security=tls&sni=vangoghhh.info&insecure=1&allowInsecure=1&type=ws&host=vangoghhh.info&path=%2Ftixvuws#%40DeltaKroneckerGithub_V2ray_1922",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&fp=chrome&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#%28%204%20%29%20WiFi%20%F0%9F%9F%A2%20%40Spotify_Porteghali",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&fp=chrome&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#%40TL_V2ray",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=1&allowInsecure=1&type=ws&host=www.multiplydose.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=www.calmloud.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&fp=chrome&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=1&allowInsecure=1&type=ws&host=www.multiplydose.com&path=%2Fassignment#%40TL_V2ray",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#%40TL_V2ray",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&alpn=h3%2Ch2%2Chttp%2F1.1&insecure=1&allowInsecure=1&type=ws&host=www.creationlong.org&path=%2Fassignment#%28%207%20%29%20Irancell%20%F0%9F%9F%A1%20%40Spotify_Porteghali",
    "vless://f9fa9ed3-1381-441c-8672-588cbedd012d@127.0.0.1:40443?encryption=none&security=tls&sni=r.bibak.bz&insecure=1&allowInsecure=1&type=ws&host=r.bibak.bz&path=%2F#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=ios&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#%40DeltaKroneckerGithub_V2ray_5841",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=0&allowInsecure=0&type=ws&host=www.multiplydose.com&path=%2F%2Fassignment#%40TL_V2ray",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=www.calmloud.com&path=%2Fassignment#%40ArchiveTell",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&fp=chrome&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=www.gossipglove.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTprMWRCT21PQjRvcWk3VW1wMzdhMWJR@82.38.31.194:8080?",
    "trojan://A4u%5Ep%28_UR%2AF6@127.0.0.1:40443?security=tls&sni=027.eLECTROcelLCO-cf-027.WorKerS.Dev&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=027.electrocellco-cf-027.workers.dev&path=%2FeyJqdW5rIjoidDJ0UDljR2ZIaVBQakg2byIsInByb3RvY29sIjoidHIiLCJtb2RlIjoicHJveHlpcCIsInBhbmVsSVBzIjpbXX0%3D#%40DeltaKroneckerGithub",
    "vless://1f9d104e-ca0e-4202-ba4b-a0afb969c747@127.0.0.1:40443?encryption=none&security=tls&sni=www.multiplydose.cc.cd&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=1&allowInsecure=1&type=xhttp&host=www.multiplydose.cc.cd&path=%2Fassignment&mode=stream-one#llFreak",
    "vless://7d4e989b-6107-4389-a8d0-c7e71551c373@127.0.0.1:40443?encryption=none&security=tls&sni=fara-4.y8rs.xyz&insecure=1&allowInsecure=1&type=ws&path=%2F%3Fed%3D2048#%F0%9F%87%B8%F0%9F%87%A840217%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "vless://8e9710d2-4e44-4ddc-a1f7-be9466271ee5@127.0.0.1:40443?encryption=none&security=tls&sni=cd-01.kolman.com.de&fp=firefox&alpn=h2&insecure=1&allowInsecure=1&ech=AEX%2BDQBBlwAgACCNH6zGvHNnXHsQNfThdM6%2B92q%2BdDgMMoRF4deXBzY2SgAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA%3D&type=xhttp&host=cd-01.kolman.com.de&path=%2FeQ7EK5s88mh1QkaLDB4v5psc&extra=%7B%22headers%22%3A%7B%22User-Agent%22%3A%22Mozilla%2F5.0%2B%28Macintosh%3B%2BIntel%2BMac%2BOS%2BX%2B10_15_7%29%2BAppleWebKit%2F537.36%2B%28KHTML%2C%2Blike%2BGecko%29%2Bobsidian%2F1.6.5%2BChrome%2F124.0.6367.243%2BElectron%2F30.1.2%2BSafari%2F537.36%22%2C%22Pragma%22%3A%22no-cache%22%7D%7D#%F0%9F%87%AE%F0%9F%87%B740220%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "vless://8e9710d2-4e44-4ddc-a1f7-be9466271ee5@127.0.0.1:40443?encryption=none&security=tls&sni=cd-01.kolman.com.de&fp=firefox&alpn=h2&insecure=1&allowInsecure=1&ech=AEX%2BDQBBlwAgACCNH6zGvHNnXHsQNfThdM6%2B92q%2BdDgMMoRF4deXBzY2SgAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA%3D&type=xhttp&host=cd-01.kolman.com.de&path=%2FeQ7EK5s88mh1QkaLDB4v5psc&extra=%7B%22headers%22%3A%7B%22User-Agent%22%3A%22Mozilla%2F5.0%2B%28Macintosh%3B%2BIntel%2BMac%2BOS%2BX%2B10_15_7%29%2BAppleWebKit%2F537.36%2B%28KHTML%2C%2Blike%2BGecko%29%2Bobsidian%2F1.6.5%2BChrome%2F124.0.6367.243%2BElectron%2F30.1.2%2BSafari%2F537.36%22%2C%22Pragma%22%3A%22no-cache%22%7D%7D#%F0%9F%87%AE%F0%9F%87%B740221%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "vless://1d073c8b-6843-4415-b4d4-279d817f7525@127.0.0.1:40443?encryption=none&security=tls&sni=spaa.napsternetx.ir&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=1&allowInsecure=1&type=xhttp&path=%2F%3Fed%3D80&mode=auto&extra=%7B%22scMaxEachPostBytes%22%3A%2B1000000%2C%2B%22scMaxConcurrentPosts%22%3A%2B100%2C%2B%22scMinPostsIntervalMs%22%3A%2B30%2C%2B%22xPaddingBytes%22%3A%2B%22100-1000%22%2C%2B%22noGRPCHeader%22%3A%2Bfalse%7D#%40filembad",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "trojan://freedom@127.0.0.1:40443?security=tls&sni=042.eLEctrocElLCo-cf-042-Fe0.wOrkERs.deV&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=042.electrocellco-cf-042-fe0.workers.dev&path=%2FeyJqdW5rIjoiZ3JCVzl1T1ciLCJwcm90b2NvbCI6InRyIiwibW9kZSI6InByb3h5aXAiLCJwYW5lbElQcyI6W119#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "trojan://freedom@127.0.0.1:40443?security=tls&sni=042.eLEctrocElLCo-cf-042-Fe0.wOrkERs.deV&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=042.electrocellco-cf-042-fe0.workers.dev&path=%2FeyJqdW5rIjoiZ3JCVzl1T1ciLCJwcm90b2NvbCI6InRyIiwibW9kZSI6InByb3h5aXAiLCJwYW5lbElQcyI6W119#%40DeltaKroneckerGithub",
    "vless://a6f1755f-0140-4bea-8727-0db1bed7c4df@127.0.0.1:40443?encryption=none&security=tls&sni=juzi.qea.ccwu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=juzi.qea.ccwu.cc&path=%2F%3Fed%3D2048#%40DeltaKroneckerGithub",
    "vless://e5cc16a6-ea42-46b2-82ae-ad2157e1641b@127.0.0.1:40443?encryption=none&security=tls&sni=hhlfy.twiladaphne.ndjp.net&insecure=0&allowInsecure=0&type=ws&host=hhlfy.twiladaphne.ndjp.net&path=%2Ffp%3Drandom#%40DeltaKroneckerGithub",
    "vless://cc752a3e-1537-4e86-bb50-1b897bf7b33c@127.0.0.1:40443?encryption=none&security=tls&sni=cyylr.eu.cc&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=cyylr.eu.cc&path=%2Fsg-melbi#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://00000000-0000-4000-8000-000000000000@127.0.0.1:40443?encryption=none&security=tls&sni=oia8or7k8rs5.zrf.xx.kg&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=oia8or7k8rs5.zrf.xx.kg&path=%2F%40Marisa_kristifp%3Dchrome#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://69d38faa-21a1-4d18-82cb-796a942c181b@127.0.0.1:40443?encryption=none&security=tls&sni=sfsl.ardomains1.dpdns.org&insecure=0&allowInsecure=0&type=ws&host=sfsl.ardomains1.dpdns.org&path=%2Fsecurity%3Dtls#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://ce69190e-935a-4394-9e5e-2ab2872ba242@127.0.0.1:40443?encryption=none&security=tls&sni=up2-df6.pages.dev&insecure=0&allowInsecure=0&type=ws&host=up2-df6.pages.dev&path=%2FuTZSXPBI2IfoxF86#%40DeltaKroneckerGithub",
    "vless://f2564bb4-9960-4ccb-8017-bc02802f768a@127.0.0.1:40443?encryption=none&security=tls&sni=white-snowflake-e65e.queenmahsan1.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=white-snowflake-e65e.queenmahsan1.workers.dev&path=ws#%40DeltaKroneckerGithub",
    "vless://a6f1755f-0140-4bea-8727-0db1bed7c4df@127.0.0.1:40443?encryption=none&security=tls&sni=juzi.qea.ccwu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=juzi.qea.ccwu.cc&path=%2F%3Fed%3D2048#%40DeltaKroneckerGithub",
    "vless://93f0761d-4393-4b43-a523-e812fa0b7e83@127.0.0.1:40443?encryption=none&security=tls&sni=cf.cfvip.lol&insecure=0&allowInsecure=0&type=ws&host=cf.cfvip.lol&path=%2Fpyip%3D47.251.95.178#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://a6f1755f-0140-4bea-8727-0db1bed7c4df@127.0.0.1:40443?encryption=none&security=tls&sni=juzi.qea.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=juzi.qea.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://e5cc16a6-ea42-46b2-82ae-ad2157e1641b@127.0.0.1:40443?encryption=none&security=tls&sni=hhlfy.twiladaphne.ndjp.net&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=hhlfy.twiladaphne.ndjp.net&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://2244d9e4-dc80-4c61-9362-dde0afd034dd@127.0.0.1:40443?encryption=none&security=tls&sni=hs.qoogl.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=hs.qoogl.workers.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://cc752a3e-1537-4e86-bb50-1b897bf7b33c@127.0.0.1:40443?encryption=none&security=tls&sni=cyylr.eu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=cyylr.eu.cc&path=%2Fsg-melbi#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://af501b09-1af5-4399-b36a-52624e4304a7@127.0.0.1:40443?encryption=none&security=tls&sni=09OSerT5Ts9U9wgB4X-u45B-h5u8w275.yzDaN2879.WOrKERS.DEV&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=09osert5ts9u9wgb4x-u45b-h5u8w275.yzdan2879.workers.dev&path=%2FeyJqdW5rIjoibEdCdlM3dElEZWkxIiwicHJvdG9jb2wiOiJ2bCIsIm1vZGUiOiJwcm94eWlwIiwicGFuZWxJUHMiOltdfQ%3D%3D#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://0413d90c-0d59-4c38-ac2c-1aff836c45d1@127.0.0.1:40443?encryption=none&security=tls&sni=vpn.madiden137.workers.dev&fp=chrome&alpn=h3%2Ch2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=vpn.madiden137.workers.dev&path=%2F%3Fed%3D2048#%40TL_V2ray",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://e5cc16a6-ea42-46b2-82ae-ad2157e1641b@127.0.0.1:40443?encryption=none&security=tls&sni=hhlfy.twiladaphne.ndjp.net&insecure=0&allowInsecure=0&type=ws&host=hhlfy.twiladaphne.ndjp.net&path=%2Ffp%3Drandom#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://cb0be83e-ff5c-4601-8572-18deac592958@127.0.0.1:40443?encryption=none&security=tls&sni=nimahavok.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=nimahavok.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://cb0be83e-ff5c-4601-8572-18deac592958@127.0.0.1:40443?encryption=none&security=tls&sni=nimahavok.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=nimahavok.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://a6f1755f-0140-4bea-8727-0db1bed7c4df@127.0.0.1:40443?encryption=none&security=tls&sni=juzi.qea.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=juzi.qea.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://656bb400-3b7a-47e3-94b0-0362f3865072@127.0.0.1:40443?encryption=none&security=tls&sni=socks5.revil-time3.workers.dev&insecure=0&allowInsecure=0&type=ws&host=socks5.revil-time3.workers.dev&path=%2Fsocks5%3A%2F%2F128.140.46.169%3A13482#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://93f0761d-4393-4b43-a523-e812fa0b7e83@127.0.0.1:40443?encryption=none&security=tls&sni=cf.cfvip.lol&insecure=0&allowInsecure=0&type=ws&host=cf.cfvip.lol&path=%2Fpyip%3D47.251.95.178#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://e5cc16a6-ea42-46b2-82ae-ad2157e1641b@127.0.0.1:40443?encryption=none&security=tls&sni=hhlfy.twiladaphne.ndjp.net&insecure=0&allowInsecure=0&type=ws&host=hhlfy.twiladaphne.ndjp.net&path=%2Ffp%3Drandom#%40DeltaKroneckerGithub",
    "vless://e5cc16a6-ea42-46b2-82ae-ad2157e1641b@127.0.0.1:40443?encryption=none&security=tls&sni=hhlfy.twiladaphne.ndjp.net&fp=random&insecure=0&allowInsecure=0&type=ws&host=hhlfy.twiladaphne.ndjp.net&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://d555dac9-27cb-4ddb-89f5-6a1d8d1e4798@127.0.0.1:40443?encryption=none&security=tls&sni=momi.timoreyhaneh.workers.dev&insecure=0&allowInsecure=0&type=ws&host=momi.timoreyhaneh.workers.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://4cf1a3a0-7360-41ff-84ae-b621001e8376@127.0.0.1:40443?encryption=none&security=tls&sni=Usa.cORreCtCOsETTe.WOrKeRS.DeV&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=usa.correctcosette.workers.dev&path=%2FR8BFTroQ00j1#%40DeltaKroneckerGithub",
    "vless://e5cc16a6-ea42-46b2-82ae-ad2157e1641b@127.0.0.1:40443?encryption=none&security=tls&sni=hhlfy.twiladaphne.ndjp.net&fp=random&insecure=0&allowInsecure=0&type=ws&host=hhlfy.twiladaphne.ndjp.net&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://a6f1755f-0140-4bea-8727-0db1bed7c4df@127.0.0.1:40443?encryption=none&security=tls&sni=juzi.qea.ccwu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=juzi.qea.ccwu.cc&path=%2F%3Fed%3D2048#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://e5cc16a6-ea42-46b2-82ae-ad2157e1641b@127.0.0.1:40443?encryption=none&security=tls&sni=hhlfy.twiladaphne.ndjp.net&fp=random&insecure=0&allowInsecure=0&type=ws&host=hhlfy.twiladaphne.ndjp.net&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://f86f5a03-3872-46b5-bf03-a50388a0261d@127.0.0.1:40443?encryption=none&security=tls&sni=a1-tss.pages.dev&insecure=0&allowInsecure=0&type=ws&host=a1-tss.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://c1836360-0954-42f9-92d1-5457db6fce25@127.0.0.1:40443?encryption=none&security=tls&sni=bitter-base-6f9e.amirmahdizamani50.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=bitter-base-6f9e.amirmahdizamani50.workers.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://49d598ee-4dfc-4001-95ca-99a5b6002e3c@127.0.0.1:40443?encryption=none&security=tls&sni=tiny-leaf-f59f.my-lla-s-a-njo-s-e.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=tiny-leaf-f59f.my-lla-s-a-njo-s-e.workers.dev&path=%2Fws#%40DeltaKroneckerGithub",
    "vless://733a9a09-ba93-4e73-b1f3-ae0bcbd88310@127.0.0.1:40443?encryption=none&security=tls&sni=noisy-paper-6b70.grirbp.workers.dev&insecure=0&allowInsecure=0&type=ws&host=noisy-paper-6b70.grirbp.workers.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://69d38faa-21a1-4d18-82cb-796a942c181b@127.0.0.1:40443?encryption=none&security=tls&sni=sfsl.ardomains1.dpdns.org&insecure=0&allowInsecure=0&type=ws&host=sfsl.ardomains1.dpdns.org&path=%2F%3Fed%3D2048#%40DeltaKroneckerGithub",
    "vless://69d38faa-21a1-4d18-82cb-796a942c181b@127.0.0.1:40443?encryption=none&security=tls&sni=sfsl.ardomains1.dpdns.org&insecure=0&allowInsecure=0&type=ws&host=sfsl.ardomains1.dpdns.org&path=%2F%3Fed%3D2048security%3Dtls#%40DeltaKroneckerGithub",
    "vless://e5cc16a6-ea42-46b2-82ae-ad2157e1641b@127.0.0.1:40443?encryption=none&security=tls&sni=hhlfy.twiladaphne.ndjp.net&insecure=0&allowInsecure=0&type=ws&host=hhlfy.twiladaphne.ndjp.net&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://ce69190e-935a-4394-9e5e-2ab2872ba242@127.0.0.1:40443?encryption=none&security=tls&sni=up2-df6.pages.dev&insecure=0&allowInsecure=0&type=ws&host=up2-df6.pages.dev&path=%2FuTZSXPBI2IfoxF86#%40DeltaKroneckerGithub",
    "vless://a6f1755f-0140-4bea-8727-0db1bed7c4df@127.0.0.1:40443?encryption=none&security=tls&sni=juzi.qea.ccwu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=juzi.qea.ccwu.cc&path=%2F%3Fed%3D2048#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://cc752a3e-1537-4e86-bb50-1b897bf7b33c@127.0.0.1:40443?encryption=none&security=tls&sni=support.zoom.us.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=support.zoom.us.cyylr.eu.cc&path=%2Fsg-melbi#%40DeltaKroneckerGithub",
    "vless://f2564bb4-9960-4ccb-8017-bc02802f768a@127.0.0.1:40443?encryption=none&security=tls&sni=white-snowflake-e65e.queenmahsan1.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=white-snowflake-e65e.queenmahsan1.workers.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://a6f1755f-0140-4bea-8727-0db1bed7c4df@127.0.0.1:40443?encryption=none&security=tls&sni=juzi.qea.ccwu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=juzi.qea.ccwu.cc&path=%2F%3Fed%3D2048#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://733a9a09-ba93-4e73-b1f3-ae0bcbd88310@127.0.0.1:40443?encryption=none&security=tls&sni=noisy-paper-6b70.grirbp.workers.dev&insecure=0&allowInsecure=0&type=ws&host=noisy-paper-6b70.grirbp.workers.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://93f0761d-4393-4b43-a523-e812fa0b7e83@127.0.0.1:40443?encryption=none&security=tls&sni=cf.cfvip.lol&insecure=0&allowInsecure=0&type=ws&host=cf.cfvip.lol&path=%2Fpyip%3D47.251.95.178#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://51a0af77-a60c-4d9b-81a4-f50e1b38d90b@127.0.0.1:40443?encryption=none&security=tls&sni=javad.fighterjavad8-faa.workers.dev&insecure=0&allowInsecure=0&type=ws&host=javad.fighterjavad8-faa.workers.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://e73d4e2a-ac73-4948-960a-2c2dd57b7ea8@127.0.0.1:40443?encryption=none&security=tls&sni=yellow-disk-0edf.diyenec996.workers.dev&insecure=0&allowInsecure=0&type=ws&host=yellow-disk-0edf.diyenec996.workers.dev&path=%2Fws#%40DeltaKroneckerGithub",
    "vless://e5cc16a6-ea42-46b2-82ae-ad2157e1641b@127.0.0.1:40443?encryption=none&security=tls&sni=hhlfy.twiladaphne.ndjp.net&fp=random&insecure=0&allowInsecure=0&type=ws&host=hhlfy.twiladaphne.ndjp.net&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://7f3faff8-41db-403d-8329-6b9072aaabe2@127.0.0.1:40443?encryption=none&security=tls&sni=edgetunnell-1dc.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=edgetunnell-1dc.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://7f3faff8-41db-403d-8329-6b9072aaabe2@127.0.0.1:40443?encryption=none&security=tls&sni=edgetunnell-1dc.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=edgetunnell-1dc.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://ae0dd58e-e222-40bf-84ae-365a97532737@127.0.0.1:40443?encryption=none&security=tls&sni=cyylr.eu.cc&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&ech=ip.gs%2Budp%3A%2F%2F8.8.8.8&type=ws&host=cyylr.eu.cc&path=%2Fsg-melbi#%40DeltaKroneckerGithub",
    "vless://e73d4e2a-ac73-4948-960a-2c2dd57b7ea8@127.0.0.1:40443?encryption=none&security=tls&sni=yellow-disk-0edf.diyenec996.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=yellow-disk-0edf.diyenec996.workers.dev&path=%2Fws#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://69c115fd-ddd9-4d7f-bd90-6f34c810cf0e@127.0.0.1:40443?encryption=none&security=tls&sni=v2configlab.v2configlab5.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=v2configlab.v2configlab5.workers.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://a6f1755f-0140-4bea-8727-0db1bed7c4df@127.0.0.1:40443?encryption=none&security=tls&sni=juzi.qea.ccwu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=juzi.qea.ccwu.cc&path=%2F%3Fed%3D2048#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://4cf1a3a0-7360-41ff-84ae-b621001e8376@127.0.0.1:40443?encryption=none&security=tls&sni=Usa.cORreCtCOsETTe.WOrKeRS.DeV&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=usa.correctcosette.workers.dev&path=%2FR8BFTroQ00j1#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.calmloud.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&insecure=0&allowInsecure=0&type=ws&host=www.creationlong.org&path=%2Fassignment#%40DeltaKroneckerGithub",
    "vless://cc752a3e-1537-4e86-bb50-1b897bf7b33c@127.0.0.1:40443?encryption=none&security=tls&sni=support.zoom.us.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=support.zoom.us.cyylr.eu.cc&path=%2Fsg-melbi#%40DeltaKroneckerGithub",
    "vless://51a0af77-a60c-4d9b-81a4-f50e1b38d90b@127.0.0.1:40443?encryption=none&security=tls&sni=javad.fighterjavad8-faa.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=javad.fighterjavad8-faa.workers.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://a6f1755f-0140-4bea-8727-0db1bed7c4df@127.0.0.1:40443?encryption=none&security=tls&sni=juzi.qea.ccwu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=juzi.qea.ccwu.cc&path=%2Ffp%3Dchrome#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.calmloud.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=ios&insecure=0&allowInsecure=0&type=ws&host=www.calmloud.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.calmloud.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "vless://5b6be23b-dff4-428f-8a60-4f11568abe12@127.0.0.1:40443?encryption=none&security=tls&sni=sync.amordad.xyz&insecure=0&allowInsecure=0&type=ws&host=sync.amordad.xyz&path=%2Fapi%2Fv1%2Fstream#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&insecure=0&allowInsecure=0&type=ws&host=www.calmlunch.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#%40TL_V2ray",
    "vless://532a9115-aee7-4d39-9638-1e5146de075b@127.0.0.1:40443?encryption=none&security=tls&sni=muddy-math-c1fc.tmpnriqueau89.workers.dev&insecure=0&allowInsecure=0&type=ws&host=muddy-math-c1fc.tmpnriqueau89.workers.dev&path=%2Fjs%2FcHlpcC55Z2tray5kcGRucy5vcmc%3D%2F#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=0&allowInsecure=0&type=ws&host=www.multiplydose.com&path=%2F%2Fassignment#%40TL_V2ray",
    "vless://e5cc16a6-ea42-46b2-82ae-ad2157e1641b@127.0.0.1:40443?encryption=none&security=tls&sni=hhlfy.twiladaphne.ndjp.net&insecure=0&allowInsecure=0&type=ws&host=hhlfy.twiladaphne.ndjp.net&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.calmloud.com&path=%2Fassignment#%40TL_V2ray",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=www.creationlong.org&path=%2Fassignment#%40TL_V2ray",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://77777777-8a3e-6666-b6d1-a9c5f0e8b3a2@127.0.0.1:40443?encryption=none&security=tls&sni=fgnix832fx.fx6hsv0.ccwu.cc&insecure=0&allowInsecure=0&type=ws&host=fgnix832fx.fx6hsv0.ccwu.cc&path=%2F#%40DeltaKroneckerGithub",
    "vless://93f0761d-4393-4b43-a523-e812fa0b7e83@127.0.0.1:40443?encryption=none&security=tls&sni=cf.cfvip.lol&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=cf.cfvip.lol&path=%2Fpyip%3D47.251.95.178#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.calmloud.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=0&allowInsecure=0&type=ws&host=www.multiplydose.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&alpn=h2%2Ch3%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&path=assignment#%40TL_V2ray",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://19804f6b-3661-4eca-b294-ba37196dab5b@127.0.0.1:40443?encryption=none&security=tls&sni=graph.instagram.com.cyylr.eu.cc&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=graph.instagram.com.cyylr.eu.cc&path=%2Fid-tksi#%40DeltaKroneckerGithub",
    "vless://94daa8ad-75ed-4dbf-a6de-d05036d98df5@127.0.0.1:40443?encryption=none&security=tls&sni=vangoghhh.info&insecure=0&allowInsecure=0&type=ws&host=vangoghhh.info&path=%2Ftixvuws#%40DeltaKroneckerGithub",
    "vless://94daa8ad-75ed-4dbf-a6de-d05036d98df5@127.0.0.1:40443?encryption=none&security=tls&sni=vangoghhh.info&insecure=0&allowInsecure=0&type=ws&host=vangoghhh.info&path=%2Ftixvuws#%40DeltaKroneckerGithub",
    "vless://94daa8ad-75ed-4dbf-a6de-d05036d98df5@127.0.0.1:40443?encryption=none&security=tls&sni=vangoghhh.info&insecure=0&allowInsecure=0&type=ws&host=vangoghhh.info&path=%2Ftixvuws#%40DeltaKroneckerGithub",
    "vless://94daa8ad-75ed-4dbf-a6de-d05036d98df5@127.0.0.1:40443?encryption=none&security=tls&sni=vangoghhh.info&insecure=0&allowInsecure=0&type=ws&host=vangoghhh.info&path=%2Ftixvuws#%40DeltaKroneckerGithub",
    "trojan://aff9928f-7ba3-45fb-b56d-4dec45653ca9@127.0.0.1:40443?security=tls&sni=q6clkp794liy3y90.hktimecsgo.xyz&insecure=0&allowInsecure=0&type=ws&host=q6clkp794liy3y90.hktimecsgo.xyz&path=%2Fimages#%40DeltaKroneckerGithub",
    "vless://b1f8283d-36f5-4ff9-8ad8-e6d733a18fa0@127.0.0.1:40443?encryption=none&security=tls&sni=darkness427edge-93h.pages.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=darkness427edge-93h.pages.dev&path=%2F#%40DeltaKroneckerGithub",
    "vless://e5cc16a6-ea42-46b2-82ae-ad2157e1641b@127.0.0.1:40443?encryption=none&security=tls&sni=hhlfy.twiladaphne.ndjp.net&fp=random&insecure=0&allowInsecure=0&type=ws&host=hhlfy.twiladaphne.ndjp.net&path=%2F#%40DeltaKroneckerGithub",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.creationlong.org&path=%2Fassignment#%40DeltaKroneckerGithub",
    "vless://c7addea9-6d8a-48d6-8a00-aa99b8ece143@127.0.0.1:40443?encryption=none&security=tls&sni=ws35.adsvxpro.com&fp=firefox&insecure=0&allowInsecure=0&type=ws&host=ws35.adsvxpro.com&path=%2Fpath#%F0%9F%87%A8%F0%9F%87%A650403%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#%40meliproxyy",
    "vless://d8c05f47-6ca7-4c6a-badf-b7e5e818699c@127.0.0.1:40443?encryption=none&security=tls&sni=noiZvPn.aSAd13321.wORkers.dEv&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2FeyJqdW5rIjoiM25HVThlWGhhcThVT3g2ZSIsInByb3RvY29sIjoidmwiLCJtb2RlIjoicHJveHlpcCIsInBhbmVsSVBzIjpbXX0%3D%3Fed%3D2560#%F0%9F%87%A8%F0%9F%87%A650427%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "vless://d3c88e05-8df2-46ea-8205-65c5c86bb6fd@127.0.0.1:40443?encryption=none&security=tls&sni=PEZesHkIaN.jEnde.woRkers.DeV&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2FeyJqdW5rIjoiOWxyNGZ2OFpTNGdGeCIsInByb3RvY29sIjoidmwiLCJtb2RlIjoicHJveHlpcCIsInBhbmVsSVBzIjpbXX0%3D%3Fed%3D2560#%F0%9F%87%A8%F0%9F%87%B750428%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "vless://45c8b2f4-343c-4d37-99d2-59cb13e6c7fb@127.0.0.1:40443?encryption=none&security=tls&sni=peDarET.BaBJOON.WOrKErs.deV&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2FeyJqdW5rIjoiQmtnQkhTSzYiLCJwcm90b2NvbCI6InZsIiwibW9kZSI6InByb3h5aXAiLCJwYW5lbElQcyI6W119%3Fed%3D2560#%F0%9F%87%A8%F0%9F%87%A650429%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%897",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%8910",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%892",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%893",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%894",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%895",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%896",
    "vless://7b102311-43fd-4e8f-877e-8090623c101d@127.0.0.1:40443?encryption=none&security=tls&sni=fancy-thunder-d65b.sdfds544fs.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=fancy-thunder-d65b.sdfds544fs.workers.dev&path=%2F#CF%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%899",
    "vless://577699f7-468d-4b63-ae21-cdcdfd8d11c2@127.0.0.1:40443?encryption=none&security=tls&sni=baguette.adaspoloandco.com&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=baguette.adaspoloandco.com&path=%2FGoorBah#Baguette-France-c8",
    "vless://577699f7-468d-4b63-ae21-cdcdfd8d11c2@127.0.0.1:40443?encryption=none&security=tls&sni=baguette.adaspoloandco.com&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=baguette.adaspoloandco.com&path=%2FGoorBah#Baguette-France-c10",
    "vless://10a6b923-e349-4594-92bb-d81a6245aaec@127.0.0.1:40443?encryption=none&security=tls&sni=sertraline.adaspoloandco.com&fp=chrome&alpn=http%2F1.1%2Ch2&insecure=0&allowInsecure=0&type=ws&host=sertraline.adaspoloandco.com&path=%2Fdownload.php#Sertraline-Finland-c10",
    "vless://10a6b923-e349-4594-92bb-d81a6245aaec@127.0.0.1:40443?encryption=none&security=tls&sni=sertraline.adaspoloandco.com&fp=chrome&alpn=http%2F1.1%2Ch2&insecure=0&allowInsecure=0&type=ws&host=sertraline.adaspoloandco.com&path=%2Fdownload.php#Sertraline-Finland-c11",
    "vless://10a6b923-e349-4594-92bb-d81a6245aaec@127.0.0.1:40443?encryption=none&security=tls&sni=sertraline.adaspoloandco.com&fp=chrome&alpn=http%2F1.1%2Ch2&insecure=0&allowInsecure=0&type=ws&host=sertraline.adaspoloandco.com&path=%2Fdownload.php#Sertraline-Finland-c12",
    "vless://10a6b923-e349-4594-92bb-d81a6245aaec@127.0.0.1:40443?encryption=none&security=tls&sni=sertraline.adaspoloandco.com&fp=chrome&alpn=http%2F1.1%2Ch2&insecure=0&allowInsecure=0&type=ws&host=sertraline.adaspoloandco.com&path=%2Fdownload.php#Sertraline-Finland-c13",
    "vless://10a6b923-e349-4594-92bb-d81a6245aaec@127.0.0.1:40443?encryption=none&security=tls&sni=sertraline.adaspoloandco.com&fp=chrome&alpn=http%2F1.1%2Ch2&insecure=0&allowInsecure=0&type=ws&host=sertraline.adaspoloandco.com&path=%2Fdownload.php#Sertraline-Finland-c14",
    "vless://fc965ad9-bdd7-4815-ad71-b39ec5972dc1@127.0.0.1:40443?encryption=none&security=tls&sni=octopusss4.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=octopusss4.com&path=%2Ftsghdws#%20By%20EbraSha%20%F0%9F%90%BA",
    "vless://fc965ad9-bdd7-4815-ad71-b39ec5972dc1@127.0.0.1:40443?encryption=none&security=tls&sni=octopusss4.com&insecure=0&allowInsecure=0&type=ws&host=octopusss4.com&path=%2Ftsghdws#%20By%20EbraSha%20%F0%9F%A7%B2",
    "vless://f2a73750-3087-48ff-a763-1348c15dce68@127.0.0.1:40443?encryption=none&security=tls&sni=rAyAn-007.mAxImA.DpDnS.OrG&insecure=0&allowInsecure=0&type=ws&host=rAyAn-007.mAxImA.DpDnS.OrG&path=%2F#%20By%20EbraSha%20%F0%9F%9B%9C",
    "vless://f4acdab7-7487-4bb0-bce0-d8a9906d44aa@127.0.0.1:40443?encryption=none&security=tls&sni=little-surf-1d9a.amirhost1.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=little-surf-1d9a.amirhost1.workers.dev&path=%2F#%20By%20EbraSha%20%F0%9F%93%B1",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=37cdnws.iamali.info&fp=firefox&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=37cdnws.iamali.info&path=%2F37cdnws#Ettehadvpn%20%7C%2023",
    "vless://%40a_amr79@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv#%F0%9F%8C%90%20Anycast-IP%20%7C%20%F0%9F%87%A8%F0%9F%87%A6%20%F0%9F%87%AC%F0%9F%87%A7%20%F0%9F%87%B2%F0%9F%87%BE%20%F0%9F%87%B7%F0%9F%87%BA%20%5B%2ACIDR%5D%20t.me%2Frjsxrd",
    "vless://%40a_amr79@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv#%F0%9F%8C%90%20Anycast-IP%20%7C%20%F0%9F%87%AC%F0%9F%87%A7%20%F0%9F%87%B2%F0%9F%87%BE%20%F0%9F%87%B7%F0%9F%87%BA%20%5B%2ACIDR%5D%20t.me%2Frjsxrd",
    "vless://1ce9392f-caf4-48f7-bbb9-475b101fbde0@127.0.0.1:40443?encryption=none&security=tls&sni=graph.instagram.com.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=graph.instagram.com.cyylr.eu.cc&path=%2Fid-tksi#%5B%F0%9F%87%AE%F0%9F%87%A9%5D%20%5Bvl-tl-ws%5D%20%5B260602-084353.555%5D%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=37cdnws.iamali.info&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=37cdnws.iamali.info&path=%2F37cdnws#%F0%9F%94%A5Join%2BTelegram%3A%40Farah_VPN%F0%9F%9F%A3%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=37cdnws.iamali.info&fp=firefox&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=37cdnws.iamali.info&path=%2F37cdnws#4Kian-9432%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=35cdnws.frnstatic.info&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=35cdnws.frnstatic.info&path=%2F35cdnws#4Kian-9431%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=35cdnws.frnstatic.info&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=35cdnws.frnstatic.info&path=%2F35cdnws#US_speednode_0054%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=35cdnws.frnstatic.info&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=35cdnws.frnstatic.info&path=%2F35cdnws#%20By%20EbraSha%20t.me%2Frjsxrd",
    "vless://c2f0a8f0-36fe-4a94-824b-bca271ca642b@127.0.0.1:40443?encryption=none&security=tls&sni=alphacdn.alphashops.shop&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=alphacdn.alphashops.shop&path=%2Fws#CF%E4%B8%AD%E8%BD%AC_0602176520%20t.me%2Frjsxrd",
    "vless://2dc797c8-9588-48a6-bc4a-265c23d87cd6@127.0.0.1:40443?encryption=none&security=tls&sni=ava.game.naver.com.cyylr.eu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=ava.game.naver.com.cyylr.eu.cc&path=%2Fid-pusat#%F0%9F%87%BA%F0%9F%87%B8%20%E6%9C%BA%E5%9C%BA%E6%8E%A8%E8%8D%90%3Adafei.de%20%E7%BE%8E%E5%9B%BD%2017%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=35cdnws.frnstatic.info&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=35cdnws.frnstatic.info&path=%2F35cdnws#20.%20%F0%9F%87%AB%F0%9F%87%B7%20France%20t.me%2Frjsxrd",
    "vless://%40a_amr79@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv#%F0%9F%87%A8%F0%9F%87%A6%D0%9E%D0%B1%D1%85%D0%BE%D0%B4%D1%8B%20-%20TG%3A%20AirLinkVPNBot%20%7C0232%7C%20t.me%2Frjsxrd",
    "vless://%40a_amr79@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv#%F0%9F%87%AC%F0%9F%87%A7%D0%9E%D0%B1%D1%85%D0%BE%D0%B4%D1%8B%20-%20TG%3A%20AirLinkVPNBot%20%7C0238%7C%20t.me%2Frjsxrd",
    "vless://e277ac9b-3225-4381-a9fb-3585419f7142@127.0.0.1:40443?encryption=none&security=tls&sni=37cdnws.iamali.info&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=37cdnws.iamali.info&path=%2F37cdnws#%5B69380%5D%20-%20Telegram%20%3A%20%40V2All%20t.me%2Frjsxrd",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&insecure=0&allowInsecure=0&ech=ip.gs%2Budp%3A%2F%2F8.8.8.8&type=ws&host=www.creationlong.org&path=%2Fassignment#%F0%9F%87%B5%F0%9F%87%B1%20%D0%9F%D0%BE%D0%BB%D1%8C%D1%88%D0%B0%203%20%7C%20%5BBL%5D%20t.me%2Frjsxrd",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&insecure=0&allowInsecure=0&type=ws&host=www.creationlong.org&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17290",
    "vless://2244d9e4-dc80-4c61-9362-dde0afd034dd@127.0.0.1:40443?encryption=none&security=tls&sni=hs.qoogl.workers.dev&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=hs.qoogl.workers.dev&path=%2F#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17917",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.calmlunch.com&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17305",
    "trojan://25ae6230-7834-41dc-94a4-586e1a79ea89@127.0.0.1:40443?security=tls&sni=shy-brook-df1e.amirkhan69.workers.dev&fp=ios&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=shy-brook-df1e.amirkhan69.workers.dev&path=%2F%3Fed%3D2048#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17722",
    "vless://aa0e6991-6eaa-4bbb-9497-abc04d54de2f@127.0.0.1:40443?encryption=none&security=tls&sni=cdn.opensignal.com.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=cdn.opensignal.com.cyylr.eu.cc&path=%2Fsg-melbi#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18141",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.creationlong.org&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-12035",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&insecure=0&allowInsecure=0&type=ws&host=www.creationlong.org&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17078",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=0&allowInsecure=0&type=ws&host=www.multiplydose.com&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17647",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=0&allowInsecure=0&type=ws&host=www.multiplydose.com&path=%2Fassignment#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-17750",
    "vless://cc752a3e-1537-4e86-bb50-1b897bf7b33c@127.0.0.1:40443?encryption=none&security=tls&sni=cyylr.eu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=cyylr.eu.cc&path=%2Fsg-melbi%23TELEGRAM-MARAMBASHI_MARAMBASHI_MARAMBASHI_MARAMBASHI_MARAMBASHI%3Fed%3D512#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18076",
    "vless://6a8f6dc6-2a42-4a03-8047-e39ce6df3ec9@127.0.0.1:40443?encryption=none&security=tls&sni=www.genflix.co.id.cyylr.eu.cc&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=www.genflix.co.id.cyylr.eu.cc&path=%2Fmy-wbv#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18100",
    "vless://5f225374-54c9-4945-91ea-a911b9384239@127.0.0.1:40443?encryption=none&security=tls&sni=graph.instagram.com.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=graph.instagram.com.cyylr.eu.cc&path=%2Fmy-wbv#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18142",
    "vless://429f43ca-087a-4fbc-9d88-9d9007bf30bc@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18207",
    "vless://429f43ca-087a-4fbc-9d88-9d9007bf30bc@127.0.0.1:40443?encryption=none&security=tls&sni=unnes.ac.id.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=unnes.ac.id.cyylr.eu.cc&path=%2Fmy-wbv%23TELEGRAM-MARAMBASHI_MARAMBASHI_MARAMBASHI_MARAMBASHI_MARAMBASHI%3Fed%3D512#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18215",
    "vless://adffad16-ed8f-4020-97b5-64d1c4548222@127.0.0.1:40443?encryption=none&security=tls&sni=www.genflix.co.id.cyylr.eu.cc&insecure=0&allowInsecure=0&type=ws&host=www.genflix.co.id.cyylr.eu.cc&path=%2Fmy-wbv#%5BOpenRay%5D%20%F0%9F%87%A8%F0%9F%87%A6%20CA-18219",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#1",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&fp=chrome&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#2",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#3",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#4",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#5",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#6",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#7",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&fp=safari&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#8",
    "vless://fc965ad9-bdd7-4815-ad71-b39ec5972dc1@127.0.0.1:40443?encryption=none&security=tls&sni=octopusss4.com&fp=chrome&alpn=h3%2Ch2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=octopusss4.com&path=%2Ftsghdws#9",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#10",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2Fassignment#11",
    "vless://fc965ad9-bdd7-4815-ad71-b39ec5972dc1@127.0.0.1:40443?encryption=none&security=tls&sni=octopusss4.com&fp=chrome&alpn=h3%2Ch2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=octopusss4.com&path=%2Ftsghdws#PLASMA_SERVE",
    "vless://fc965ad9-bdd7-4815-ad71-b39ec5972dc1@127.0.0.1:40443?encryption=none&security=tls&sni=octopusss4.com&fp=chrome&insecure=0&allowInsecure=0&type=ws&host=octopusss4.com&path=%2Ftsghdws#Ettehadvpn%20%7C%206",
    "trojan://0jvOqSPtoC@127.0.0.1:40443?security=tls&sni=da.aananas.ir&fp=chrome&insecure=0&allowInsecure=0&type=ws&path=%2F%3Fed%3D2048#%F0%9F%87%A8%F0%9F%87%A645754%20%7C%20%E2%9A%A1%EF%B8%8FTelegram%20%3D%20t.me%2FLonUp_M",
    "vless://0413d90c-0d59-4c38-ac2c-1aff836c45d1@127.0.0.1:40443?encryption=none&security=tls&sni=vpn.madiden137.workers.dev&fp=chrome&alpn=h3%2Ch2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&host=vpn.madiden137.workers.dev&path=%2F%3Fed%3D2048#hamvex%20snispf%C2%B9",
    "trojan://mitivpn@127.0.0.1:40443?security=tls&sni=cdn.linkman1.ir&fp=chrome&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=cdn.linkman1.ir&path=%2F720f09dba195249b423f771661162528%2Fworkers%2Fservices%2Fview%2Fmitivpn%2Fproduction%2Fsettings#hamvex%20snispf%C2%B2",
    "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@127.0.0.1:40443?encryption=none&security=tls&sni=cdn.veilvpn.fans&fp=chrome&insecure=1&allowInsecure=1&type=httpupgrade&host=cdn.veilvpn.fans&path=%2Fvpnhu#hamvex%20snispf%C2%B3",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&insecure=1&allowInsecure=1&type=ws&host=www.calmlunch.com&path=%2Fassignment#hamvex%20snispf%E2%81%B4",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&fp=chrome&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#hamvex%20snispf%E2%81%B5",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#hamvex%20snispf%E2%81%B6",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=ios&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#hamvex%20snispf%E2%81%B7",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#hamvex%20snispf%E2%81%B8",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=1&allowInsecure=1&type=ws&host=www.gossipglove.com&path=%2Fassignment#hamvex%20snispf%E2%81%B9",
    "vless://InternetAzadRobot@146.75.117.91:80?encryption=none&security=none&type=xhttp&host=tignaltofansv4.global.ssl.fastly.net&path=%2FTignal&mode=auto#hamvex%20snispf%C2%B9%E2%81%B0",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.creationlong.org&insecure=1&allowInsecure=1&type=ws&host=www.creationlong.org&path=%2Fassignment#hamvex%20snispf%C2%B9%C2%B9",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#hamvex%20snispf%C2%B9%C2%B2",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=www.calmloud.com&path=%2Fassignment#hamvex%20snispf%C2%B9%C2%B3",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.gossipglove.com&insecure=0&allowInsecure=0&type=ws&host=www.gossipglove.com&path=%2Fassignment#hamvex%20snispf%C2%B9%E2%81%B4",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&fp=chrome&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#hamvex%20snispf%C2%B9%E2%81%B5",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmloud.com&alpn=http%2F1.1&insecure=1&allowInsecure=1&type=ws&host=www.calmloud.com&path=%2Fassignment#hamvex%20snispf%C2%B9%E2%81%B6",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=0&allowInsecure=0&type=ws&host=www.multiplydose.com&path=%2F%2Fassignment#hamvex%20snispf%C2%B9%E2%81%B7",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&alpn=h2%2Ch3%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=ws&path=assignment#hamvex%20snispf%C2%B9%E2%81%B8",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&fp=chrome&insecure=1&allowInsecure=1&type=ws&path=%2Fassignment#hamvex%20snispf%C2%B9%E2%81%B9",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#hamvex%20snispf%C2%B2%E2%81%B0",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.multiplydose.com&insecure=1&allowInsecure=1&type=ws&host=www.multiplydose.com&path=%2Fassignment#hamvex%20snispf%C2%B2%C2%B9",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.ignitelimit.com&insecure=0&allowInsecure=0&type=ws&host=www.ignitelimit.com&path=%2Fassignment#hamvex%20snispf%C2%B2%E2%81%B0",
    "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@127.0.0.1:40443?encryption=none&security=tls&sni=cdn.veilvpn.fans&fp=chrome&insecure=0&allowInsecure=0&type=httpupgrade&host=cdn.veilvpn.fans&path=%2Fvpnhu#hamvex%20snispf%C2%B3",
    "trojan://humanity@127.0.0.1:40443?security=tls&sni=www.calmlunch.com&insecure=0&allowInsecure=0&type=ws&host=www.calmlunch.com&path=%2Fassignment#hamvex%20snispf%E2%81%B4",
    "trojan://rYuChHcwQZskq9E0zdoR@127.0.0.1:40443?security=tls&sni=vpn5.rnmcnm.com&insecure=0&allowInsecure=0&type=ws&host=vpn5.rnmcnm.com&path=%2Fws-79dffb8106db#MLMMCISNI1",
    "trojan://rYuChHcwQZskq9E0zdoR@127.0.0.1:40443?security=tls&sni=vpn5.rnmcnm.com&insecure=0&allowInsecure=0&type=ws&host=vpn5.rnmcnm.com&path=%2Fws-79dffb8106db#MLMVPNMCISNI"
)



@Composable
fun SubLinkForm(onSubmit: (String, String) -> Unit) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var url by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
        FormTextField("نام سابسکریپشن", name) { name = it }
        FormTextField("لینک ساب (URL)", url) { url = it }
        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
        androidx.compose.material3.Button(
            onClick = { onSubmit(name, url) },
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = com.mlmvpn.scanner.ui.theme.Primary)
        ) {
            androidx.compose.material3.Text("ذخیره و دریافت", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
    }
}


