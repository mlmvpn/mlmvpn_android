package com.mlmvpn.scanner.ui.emergency

import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.emergency.EmergencyColors
import com.mlmvpn.scanner.ui.tlsPing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

data class RstaConfig(val connectIp: String, val connectPort: Int, val fakeSni: String)

val defaultRstaConfigs = listOf(
    RstaConfig("104.18.35.46", 443, "replit.com"),
    RstaConfig("162.159.152.4", 443, "cdn.medium.com"),
    RstaConfig("104.18.183.237", 443, "chartjs.org"),
    RstaConfig("188.114.98.0", 443, "security.vercel.com"),
    RstaConfig("104.18.0.22", 443, "unpkg.com"),
    RstaConfig("104.18.11.207", 443, "bootstrapcdn.com"),
    RstaConfig("104.17.156.85", 443, "cloudflare.net"),
    RstaConfig("104.16.147.32", 443, "static.codepen.io")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyLevel3Screen(onBack: () -> Unit) {
    val context = LocalContext.current
    val isRstaRunning by com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.isRunningFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    
    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    var displayConfigs by remember { mutableStateOf(defaultRstaConfigs) }
    
    val initialSni = prefs.getString("rsta_fake_sni", "")
    val initialIp = prefs.getString("rsta_connect_ip", "")
    var selectedIndex by remember {
        mutableStateOf(
            displayConfigs.indexOfFirst { it.fakeSni == initialSni && it.connectIp == initialIp }.coerceAtLeast(0)
        )
    }
    
    val pings = remember { mutableStateMapOf<Int, Int>() }
    var isScanning by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRstaRunning) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EmergencyColors.GoogleBg)
            .padding(top = com.mlmvpn.scanner.ui.LocalSystemTopPadding.current, bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { }
    ) {
        // TopBar
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = EmergencyColors.GoogleMuted)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.emergency_3_title), color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            IconButton(
                onClick = {
                    if (isScanning) return@IconButton
                    isScanning = true
                    coroutineScope.launch {
                        val tempPings = mutableMapOf<RstaConfig, Int>()
                        val jobs = displayConfigs.map { config ->
                            async(Dispatchers.IO) {
                                val time = tlsPing(config.connectIp, config.connectPort, config.fakeSni)
                                tempPings[config] = time
                            }
                        }
                        jobs.awaitAll()
                        pings.clear()
                        
                        val sortedList = displayConfigs.sortedBy { config -> 
                            val p = tempPings[config] ?: 9999
                            if (p <= 0) 9999 else p 
                        }
                        displayConfigs = sortedList
                        
                        sortedList.forEachIndexed { i, c -> 
                            pings[i] = tempPings[c] ?: 0 
                        }
                        
                        // Select the fastest
                        selectedIndex = 0
                        val fastest = sortedList[0]
                        prefs.edit()
                            .putString("rsta_connect_ip", fastest.connectIp)
                            .putInt("rsta_connect_port", fastest.connectPort)
                            .putString("rsta_fake_sni", fastest.fakeSni)
                            .apply()

                        isScanning = false
                    }
                }
            ) {
                Icon(
                    Icons.Default.NetworkCheck, 
                    contentDescription = "Ping", 
                    tint = if (isScanning) EmergencyColors.GoogleGreen else EmergencyColors.GoogleBlue
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            itemsIndexed(displayConfigs) { index, config ->
                val ping = pings[index]
                val isSelected = selectedIndex == index
                
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            selectedIndex = index
                            val c = displayConfigs[index]
                            prefs.edit()
                                .putInt("rsta_selected_index", index)
                                .putString("rsta_connect_ip", c.connectIp)
                                .putInt("rsta_connect_port", c.connectPort)
                                .putString("rsta_fake_sni", c.fakeSni)
                                .apply()
                        },
                    color = if (isSelected) EmergencyColors.GoogleSurface.copy(alpha = 0.8f) else EmergencyColors.GoogleSurface,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                selectedIndex = index
                                val c = displayConfigs[index]
                                prefs.edit()
                                    .putString("rsta_connect_ip", c.connectIp)
                                    .putInt("rsta_connect_port", c.connectPort)
                                    .putString("rsta_fake_sni", c.fakeSni)
                                    .apply()
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = EmergencyColors.GoogleBlue,
                                unselectedColor = EmergencyColors.GoogleMuted
                            )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = config.fakeSni,
                                color = if (isSelected) EmergencyColors.GoogleBlue else EmergencyColors.GoogleText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "IP: ${config.connectIp}:${config.connectPort}",
                                color = EmergencyColors.GoogleMuted,
                                fontSize = 12.sp
                            )
                        }
                        if (ping != null) {
                            val pingColor = when {
                                ping in 1..200 -> Color.Green
                                ping in 201..9999 -> Color.Yellow
                                else -> Color.Red
                            }
                            Text(
                                text = if (ping in 1..9999) "${ping}ms" else stringResource(R.string.emergency_3_no_ping),
                                color = pingColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .scale(scale)
                    .shadow(
                        elevation = if (isRstaRunning) 24.dp else 16.dp, 
                        shape = CircleShape, 
                        spotColor = if (isRstaRunning) EmergencyColors.GoogleRed else EmergencyColors.GoogleBlue
                    )
                    .clip(CircleShape)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = if (isRstaRunning) {
                                listOf(Color(0xFFE53935), Color(0xFFB71C1C))
                            } else {
                                listOf(Color(0xFF4285F4), Color(0xFF1565C0))
                            }
                        )
                    )
                    .clickable {
                        if (isRstaRunning) {
                            com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.stop()
                        } else {
                            val config = displayConfigs[selectedIndex]
                            prefs.edit()
                                .putString("rsta_connect_ip", config.connectIp)
                                .putInt("rsta_connect_port", config.connectPort)
                                .putString("rsta_fake_sni", config.fakeSni)
                                .apply()
                            com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.start(context, config.connectIp, config.connectPort, config.fakeSni)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isRstaRunning) Icons.Default.PowerOff else Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isRstaRunning) stringResource(R.string.emergency_3_disconnect) else stringResource(R.string.emergency_3_connect),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
