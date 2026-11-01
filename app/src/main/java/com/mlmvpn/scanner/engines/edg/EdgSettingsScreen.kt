package com.mlmvpn.scanner.engines.edg

import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.theme.*
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import com.mlmvpn.scanner.R

// Colors
private val BgDark = Color(0xFF0F172A)
private val SurfaceDark = Color(0xFF1E293B)
private val BorderDark = Color(0xFF334155)
private val Primary = Color(0xFF3B82F6) // Google Blue
private val TextPrimary = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val RedError = Color(0xFFEF4444)
private val GreenOk = Color(0xFF10B981)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgSettingsScreen(
    account: CloudAccount,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // State for config.json properties
    var proxyIp by remember { mutableStateOf("") }

    // Original JSON to merge changes
    var originalConfigJson by remember { mutableStateOf(JSONObject()) }

    LaunchedEffect(Unit) {
        if (account.edgKvNamespaceId.isNullOrEmpty()) {
            loadError = context.getString(com.mlmvpn.scanner.R.string.edg_old_panel_error)
            isLoading = false
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder().dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns()).protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector())
                    .build()
                val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
                val authHeaders = Headers.Builder().apply {
                    if (isCfat) add("Authorization", "Bearer ${account.token}")
                    else {
                        add("X-Auth-Email", account.email)
                        add("X-Auth-Key", account.token)
                    }
                    add("Content-Type", "application/json")
                }.build()

                // Fetch config.json
                val getConfigReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/${account.edgKvNamespaceId}/values/config.json")
                    .headers(authHeaders)
                    .get()
                    .build()

                val res = client.newCall(getConfigReq).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    try {
                        val json = JSONObject(body)
                        originalConfigJson = json
                        val fanDai = json.optJSONObject("反代")
                        if (fanDai != null) {
                            proxyIp = fanDai.optString("PROXYIP", "")
                            if (proxyIp == "auto") proxyIp = ""
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                withContext(Dispatchers.Main) {
                    isLoading = false
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadError = context.getString(com.mlmvpn.scanner.R.string.edg_connection_error, e.message)
                    isLoading = false
                }
            }
        }
    }

    Surface(
        // Transparent over the wallpaper; the Cloud tab that hosts this no longer pads it.
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(
                top = com.mlmvpn.scanner.ui.LocalContentTopInset.current,
                bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current,
            )) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Primary.copy(alpha = 0.15f), ControlShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.edg_settings_title),
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            account.name,
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                    }
                }
                IconButton(onClick = onDismiss, enabled = !isSaving) {
                    Icon(Icons.Default.Close, contentDescription = androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.common_close), tint = TextMuted)
                }
            }

            Divider(color = BorderDark)

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
            } else if (loadError != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = RedError, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(loadError!!, color = TextMuted, fontSize = 14.sp)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Proxy IP
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass(ControlShape).border(1.dp, BorderDark, ControlShape).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dns, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.edg_proxy_ip_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.edg_proxy_ip_desc), color = TextMuted, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = proxyIp,
                            onValueChange = { proxyIp = it },
                            placeholder = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.edg_proxy_ip_hint), color = TextMuted) },
                            colors = iosFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        ,
        shape = ControlShape,)
                    }
                }

                // Save Button
                Box(modifier = Modifier.fillMaxWidth().frostedGlass(androidx.compose.ui.graphics.RectangleShape).padding(16.dp)) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isSaving = true
                                withContext(Dispatchers.IO) {
                                    try {
                                        val client = OkHttpClient.Builder().dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns()).protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector())
                                            .build()
                                        val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
                                        val authHeaders = Headers.Builder().apply {
                                            if (isCfat) add("Authorization", "Bearer ${account.token}")
                                            else {
                                                add("X-Auth-Email", account.email)
                                                add("X-Auth-Key", account.token)
                                            }
                                            add("Content-Type", "application/json")
                                        }.build()

                                        // Update config.json
                                        val newConfig = JSONObject(originalConfigJson.toString())
                                        val fanDai = newConfig.optJSONObject("反代") ?: JSONObject()
                                        fanDai.put("PROXYIP", if (proxyIp.isNotBlank()) proxyIp else "auto")
                                        newConfig.put("反代", fanDai)

                                        val req1 = Request.Builder()
                                            .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/${account.edgKvNamespaceId}/values/config.json")
                                            .headers(authHeaders)
                                            .put(newConfig.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                            .build()
                                        client.newCall(req1).execute().use { response ->
                                            if (!response.isSuccessful) {
                                                throw IOException("HTTP ${response.code}")
                                            }
                                        }

                                        withContext(Dispatchers.Main) {
                                            isSaving = false
                                            android.widget.Toast.makeText(context, context.getString(com.mlmvpn.scanner.R.string.edg_settings_saved), android.widget.Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            isSaving = false
                                            android.widget.Toast.makeText(context, context.getString(com.mlmvpn.scanner.R.string.edg_save_error, e.message), android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = !isSaving,
                        colors = iosButtonColors(Primary),
                        border = iosButtonBorder(Primary, enabled = !isSaving)) {
                        if (isSaving) {
                            CircularProgressIndicator( modifier = Modifier.size(24.dp))
                        } else {
                            Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.edg_save_settings), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
