package com.mlmvpn.scanner.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnSettingsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("vpn_routing_prefs", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()

    var routingMode by remember { mutableStateOf(prefs.getString("vpn_routing_mode", "ALL") ?: "ALL") }
    var selectedApps by remember { mutableStateOf(prefs.getStringSet("vpn_routing_apps", emptySet())?.toMutableSet() ?: mutableSetOf()) }
    var mtuValue by remember { mutableStateOf(prefs.getInt("vpn_mtu", 1280).toString()) }
    
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            val pm = context.packageManager
            val allPackages = try {
                pm.getInstalledPackages(0)
            } catch (e: Exception) {
                emptyList()
            }
            
            val apps = allPackages.filter { pkgInfo ->
                val appInfo = pkgInfo.applicationInfo
                val flags = appInfo?.flags ?: 0
                val isSystem = (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                !isSystem || pm.getLaunchIntentForPackage(pkgInfo.packageName) != null || selectedApps.contains(pkgInfo.packageName)
            }.map { pkgInfo ->
                AppInfo(
                    name = pkgInfo.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkgInfo.packageName,
                    packageName = pkgInfo.packageName,
                    icon = try { pkgInfo.applicationInfo?.let { pm.getApplicationIcon(it) } } catch (e: Exception) { null }
                )
            }.sortedBy { it.name.lowercase() }
            
            withContext(Dispatchers.Main) {
                installedApps = apps
                isLoading = false
            }
        }
    }

    val saveSettings = {
        val mtu = mtuValue.toIntOrNull() ?: 1280
        prefs.edit()
            .putString("vpn_routing_mode", routingMode)
            .putStringSet("vpn_routing_apps", selectedApps)
            .putInt("vpn_mtu", mtu)
            .apply()
        android.widget.Toast.makeText(context, context.getString(com.mlmvpn.scanner.R.string.vpn_settings_saved), android.widget.Toast.LENGTH_LONG).show()
        onDismiss()
    }

    LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A)), contentPadding = PaddingValues(bottom = 100.dp)) {
        item {
            // Top Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.vpn_settings_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = saveSettings) {
                    Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.common_save), color = Color(0xFF00FF88), fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            // Always On VPN Card
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable {
                    try {
                        val intent = Intent("android.settings.VPN_SETTINGS")
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, context.getString(com.mlmvpn.scanner.R.string.vpn_cannot_open), android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2E31)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                    Box(modifier = Modifier.background(Color(0xFF00FF88).copy(alpha=0.2f), CircleShape).padding(10.dp)) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = Color(0xFF00FF88))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.vpn_always_on), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.vpn_always_on_desc), color = Color.Gray, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                }
            }
        }

        item {
            // MTU Setting
            Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.vpn_connection_settings), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start=16.dp, top=16.dp, bottom=8.dp))
            OutlinedTextField(
                value = mtuValue,
                onValueChange = { mtuValue = it.filter { char -> char.isDigit() } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.vpn_mtu_label), color = Color.Gray) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = Color(0xFF00FF88),
                    unfocusedBorderColor = Color.DarkGray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFF00FF88)
                ),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }

        item {
            // Routing Modes
            Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.vpn_routing_modes), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start=16.dp, top=16.dp, bottom=8.dp))
            
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeCard("ALL", androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.vpn_mode_all), Icons.Default.Public, routingMode == "ALL", Modifier.weight(1f)) { routingMode = "ALL" }
                ModeCard("ALLOW", androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.vpn_mode_allow), Icons.Default.CheckCircle, routingMode == "ALLOW", Modifier.weight(1f)) { routingMode = "ALLOW" }
                ModeCard("BYPASS", androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.vpn_mode_bypass), Icons.Default.Block, routingMode == "BYPASS", Modifier.weight(1f)) { routingMode = "BYPASS" }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        if (routingMode != "ALL") {
            item {
                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text(androidx.compose.ui.res.stringResource(com.mlmvpn.scanner.R.string.vpn_search_app), color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = Color(0xFF00FF88),
                        unfocusedBorderColor = Color.DarkGray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF00FF88)
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            if (isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF00FF88))
                    }
                }
            } else {
                val filteredApps = installedApps.filter { 
                    it.name.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true)
                }
                
                items(filteredApps) { app ->
                    val isChecked = selectedApps.contains(app.packageName)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val newSet = selectedApps.toMutableSet()
                                if (isChecked) newSet.remove(app.packageName) else newSet.add(app.packageName)
                                selectedApps = newSet
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        app.icon?.let { drawable ->
                            Image(bitmap = drawable.toBitmap().asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
                        } ?: Box(modifier = Modifier.size(40.dp).background(Color.Gray, CircleShape))
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.name, color = Color.White, fontSize = 16.sp, maxLines = 1)
                            Text(app.packageName, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                        }
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                val newSet = selectedApps.toMutableSet()
                                if (checked) newSet.add(app.packageName) else newSet.remove(app.packageName)
                                selectedApps = newSet
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FF88), checkmarkColor = Color.Black)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModeCard(mode: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bgColor = if (isSelected) Color(0xFF00FF88).copy(alpha = 0.2f) else Color(0xFF2D2E31)
    val contentColor = if (isSelected) Color(0xFF00FF88) else Color.Gray

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(bgColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, color = contentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
