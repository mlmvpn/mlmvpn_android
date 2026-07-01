package com.mlmvpn.scanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.Locale

data class CountryInfo(
    val code: String,
    val name: String,
    val flag: String,
    val fileLength: Int
)

enum class IpStatus {
    UNKNOWN, TESTING, VALID, INVALID
}

data class ProxyIpModel(
    val ip: String,
    val port: String,
    var status: IpStatus = IpStatus.UNKNOWN
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedIpScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val bgColor = Color(0xFF121212)
    val surfaceColor = Color(0xFF202124)
    val primaryColor = Color(0xFF8AB4F8)
    val textColor = Color(0xFFE8EAED)
    val mutedColor = Color(0xFF9AA0A6)
    val greenOk = Color(0xFF81C995)
    val redError = Color(0xFFF28B82)

    var countries by remember { mutableStateOf<List<CountryInfo>>(emptyList()) }
    var selectedCountry by remember { mutableStateOf<CountryInfo?>(null) }
    var proxyIps by remember { mutableStateOf<List<ProxyIpModel>>(emptyList()) }
    var isTesting by remember { mutableStateOf(false) }
    var testingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Load countries on init
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val ipFiles = context.assets.list("ip") ?: emptyArray()
                val list = mutableListOf<CountryInfo>()
                for (fileName in ipFiles) {
                    if (fileName.endsWith(".txt")) {
                        val code = fileName.removeSuffix(".txt")
                        // Estimate lines by reading bytes (rough) or just keep file name
                        list.add(
                            CountryInfo(
                                code = code,
                                name = getLocalizedCountryName(code),
                                flag = getFlagEmoji(code),
                                fileLength = 0 // Optional: Count lines if needed
                            )
                        )
                    }
                }
                countries = list.sortedBy { it.name }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = bgColor,
        topBar = {
            Surface(color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (selectedCountry != null) {
                            testingJob?.cancel()
                            testingJob = null
                            selectedCountry = null
                            proxyIps = emptyList()
                            isTesting = false
                        } else {
                            testingJob?.cancel()
                            onDismiss()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (selectedCountry == null) androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.fixed_ip_select_country) else "${selectedCountry!!.flag} ${selectedCountry!!.name}",
                        color = primaryColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (selectedCountry == null) {
                // Country List
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(countries) { country ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(surfaceColor)
                                .clickable {
                                    selectedCountry = country
                                    loadIps(context, country.code) { ips ->
                                        proxyIps = ips
                                    }
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(country.flag, fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(country.name, color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = mutedColor)
                        }
                    }
                }
            } else {
                // IP List
                Column(modifier = Modifier.fillMaxSize()) {
                    // Start test button
                    Button(
                        onClick = {
                            if (!isTesting) {
                                isTesting = true
                                testingJob?.cancel()
                                testingJob = scope.launch {
                                    try {
                                        startTesting(proxyIps, onUpdate = { updatedList ->
                                            proxyIps = updatedList.toList()
                                        })
                                    } finally {
                                        isTesting = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        enabled = !isTesting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(if (isTesting) Icons.Default.HourglassEmpty else Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isTesting) androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.fixed_ip_testing) else androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.fixed_ip_start_test), fontWeight = FontWeight.Bold)
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(proxyIps) { model ->
                            val statusColor = when (model.status) {
                                IpStatus.VALID -> greenOk
                                IpStatus.INVALID -> redError
                                IpStatus.TESTING -> primaryColor
                                IpStatus.UNKNOWN -> mutedColor
                            }
                            val statusText = when (model.status) {
                                IpStatus.VALID -> androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.fixed_ip_status_valid)
                                IpStatus.INVALID -> androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.fixed_ip_status_invalid)
                                IpStatus.TESTING -> androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.fixed_ip_status_testing)
                                IpStatus.UNKNOWN -> androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.fixed_ip_status_unknown)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceColor)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${model.ip}:${model.port}", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(statusText, color = statusColor, fontSize = 12.sp)
                                }

                                if (model.status == IpStatus.VALID) {
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Proxy IP", model.ip)
                                            clipboard.setPrimaryClip(clip)
                                            android.widget.Toast.makeText(context, context.getString(com.mlmvpn.scanner.R.string.fixed_ip_copied), android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.background(Color(0xFF2D2E31), RoundedCornerShape(8.dp))
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = greenOk)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadIps(context: Context, code: String, onLoaded: (List<ProxyIpModel>) -> Unit) {
    try {
        val inputStream = context.assets.open("ip/$code.txt")
        val lines = inputStream.bufferedReader().readLines()
        val ips = lines.mapNotNull { line ->
            val parts = line.trim().split(" ", ":")
            if (parts.size >= 2) {
                ProxyIpModel(ip = parts[0], port = parts[1])
            } else null
        }
        onLoaded(ips)
    } catch (e: Exception) {
        e.printStackTrace()
        onLoaded(emptyList())
    }
}

private suspend fun startTesting(ips: List<ProxyIpModel>, onUpdate: suspend (List<ProxyIpModel>) -> Unit) = kotlinx.coroutines.coroutineScope {
    val baseClient = OkHttpClient.Builder()
        .build()
        
    val semaphore = Semaphore(5) // Reduced to 5 concurrent tests to prevent network congestion
    val currentList = ips.toMutableList()

    val jobs = currentList.mapIndexed { index, model ->
        launch(Dispatchers.IO) {
            semaphore.withPermit {
                // Check if cancelled before proceeding
                kotlinx.coroutines.yield()
                
                // Update state to testing
                currentList[index] = model.copy(status = IpStatus.TESTING)
                withContext(Dispatchers.Main) { onUpdate(currentList) }

                val isValid = checkProxyIp(baseClient, model.ip, model.port)
                
                // Check if cancelled before updating final result
                kotlinx.coroutines.yield()
                
                // Update state to result
                currentList[index] = model.copy(status = if (isValid) IpStatus.VALID else IpStatus.INVALID)
                withContext(Dispatchers.Main) { onUpdate(currentList) }
            }
        }
    }
    jobs.forEach { it.join() }
}

private suspend fun checkProxyIp(baseClient: OkHttpClient, ip: String, port: String): Boolean = withContext(Dispatchers.IO) {
    var retries = 2
    while (retries >= 0) {
        // Cloudflare requires SNI for HTTPS. We use speed.cloudflare.com as Host and SNI,
        // and a custom Dns resolver to route the request to the specific Proxy IP.
        try {
            val isHttps = port == "443" || port == "8443" || port == "2053"
            val protocol = if (isHttps) "https" else "http"
            val host = "speed.cloudflare.com"
            val url = "$protocol://$host:$port/"
            
            // Clone the client with custom DNS and longer timeout
            val client = baseClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (hostname == host) {
                            return listOf(InetAddress.getByName(ip))
                        }
                        return Dns.SYSTEM.lookup(hostname)
                    }
                })
                .build()
                
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
                
            val response = client.newCall(request).execute()
            response.use { resp ->
                val serverHeader = resp.header("Server")?.lowercase(Locale.US) ?: ""
                // If it's Cloudflare, it's a valid proxy IP
                if (serverHeader.contains("cloudflare") || serverHeader.contains("envoy")) {
                    return@withContext true
                }
                // Fallback: Check if response code is 400 Bad Request or 403 Forbidden
                if (resp.code == 400 || resp.code == 403) {
                    return@withContext true
                }
                if (resp.isSuccessful) {
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            // TCP timeout or other error
        }
        retries--
        if (retries >= 0) kotlinx.coroutines.delay(1000)
    }
    return@withContext false
}

private fun getLocalizedCountryName(countryCode: String): String {
    return Locale("", countryCode.uppercase(Locale.US)).getDisplayCountry(com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale())
}

private fun getFlagEmoji(countryCode: String): String {
    if (countryCode.length != 2) return ""
    val code = countryCode.uppercase(Locale.US)
    val flagOffset = 0x1F1E6
    val asciiOffset = 0x41
    val firstChar = Character.codePointAt(code, 0) - asciiOffset + flagOffset
    val secondChar = Character.codePointAt(code, 1) - asciiOffset + flagOffset
    return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
}
