package com.mlmvpn.scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

import com.mlmvpn.scanner.R
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpbSettingsModal(
    initialSettings: JSONObject? = null,
    onDismiss: () -> Unit,
    onConfirm: (JSONObject) -> Unit
) {
    var allowLANConnection by remember { mutableStateOf(initialSettings?.optBoolean("allowLANConnection", false) ?: false) }
    var enableIPv6 by remember { mutableStateOf(initialSettings?.optBoolean("enableIPv6", false) ?: false) }

    var vlConfigs by remember { mutableStateOf(initialSettings?.optBoolean("VLConfigs", true) ?: true) }
    var trConfigs by remember { mutableStateOf(initialSettings?.optBoolean("TRConfigs", true) ?: true) }

    val allTlsPorts = listOf("443", "8443", "2053", "2083", "2087", "2096")
    val allNonTlsPorts = listOf("80", "8080", "8880", "2052", "2082", "2086", "2095")

    // Worker v4.2.2 stores ports as a single integer array (e.g. [443, 8443, 80])
    val defaultPorts = initialSettings?.optJSONArray("ports")?.let { arr ->
        List(arr.length()) { arr.optInt(it, 0).toString() }
    }?.toSet()

    var selectedTlsPorts by remember { mutableStateOf(defaultPorts?.intersect(allTlsPorts.toSet()) ?: setOf("443")) }
    var selectedNonTlsPorts by remember { mutableStateOf(defaultPorts?.intersect(allNonTlsPorts.toSet()) ?: emptySet()) }

    var proxyIPMode by remember { mutableStateOf(initialSettings?.optString("proxyIPMode", "") ?: "proxyip") }
    var selectedProxyIp by remember { mutableStateOf(initialSettings?.optJSONArray("proxyIPs")?.let { if (it.length() > 0) it.optString(0) else "" } ?: (proxyIpsList.firstOrNull()?.first ?: "")) }
    var selectedPrefix by remember { mutableStateOf(initialSettings?.optJSONArray("prefixes")?.let { if (it.length() > 0) it.optString(0) else "" } ?: (nat64Prefixes.firstOrNull()?.first ?: "")) }
    var cleanIp by remember { mutableStateOf(initialSettings?.optJSONArray("cleanIPs")?.let { if (it.length() > 0) it.optString(0) else "" } ?: "") }

    var proxyIpExpanded by remember { mutableStateOf(false) }
    var prefixExpanded by remember { mutableStateOf(false) }

    val primaryColor = Color(0xFF8AB4F8)
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = primaryColor,
        checkedTrackColor = primaryColor.copy(alpha = 0.5f),
        uncheckedThumbColor = Color.Gray,
        uncheckedTrackColor = Color.DarkGray
    )

    Surface(
            shape = RoundedCornerShape(0.dp),
            color = Color(0xFF202124),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.bpb_settings_title), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Divider(color = Color.DarkGray)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Common
                    SectionTitle(stringResource(R.string.bpb_common))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bpb_allow_lan), color = Color.White, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(checked = allowLANConnection, onCheckedChange = { allowLANConnection = it }, colors = switchColors)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bpb_enable_ipv6), color = Color.White, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(checked = enableIPv6, onCheckedChange = { enableIPv6 = it }, colors = switchColors)
                    }

                    Divider(color = Color.DarkGray)

                    // Protocols
                    SectionTitle(stringResource(R.string.bpb_protocols))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vlConfigs = !vlConfigs }) {
                            Checkbox(checked = vlConfigs, onCheckedChange = { vlConfigs = it }, colors = CheckboxDefaults.colors(checkedColor = primaryColor, uncheckedColor = Color.Gray))
                            Text("VLESS", color = Color.White, fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { trConfigs = !trConfigs }) {
                            Checkbox(checked = trConfigs, onCheckedChange = { trConfigs = it }, colors = CheckboxDefaults.colors(checkedColor = primaryColor, uncheckedColor = Color.Gray))
                            Text("Trojan", color = Color.White, fontSize = 14.sp)
                        }
                    }

                    Divider(color = Color.DarkGray)

                    // TLS Ports
                    SectionTitle(stringResource(R.string.bpb_tls_ports))
                    PortsGrid(allTlsPorts, selectedTlsPorts) { port, isChecked ->
                        selectedTlsPorts = if (isChecked) selectedTlsPorts + port else selectedTlsPorts - port
                    }

                    // Non-TLS Ports
                    SectionTitle(stringResource(R.string.bpb_non_tls_ports))
                    PortsGrid(allNonTlsPorts, selectedNonTlsPorts) { port, isChecked ->
                        selectedNonTlsPorts = if (isChecked) selectedNonTlsPorts + port else selectedNonTlsPorts - port
                    }

                    // Clean IP (Ingress)
                    SectionTitle(stringResource(R.string.bpb_clean_ip_title))
                    OutlinedTextField(
                        value = cleanIp,
                        onValueChange = { cleanIp = it },
                        label = { Text(stringResource(R.string.bpb_clean_ip_label)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = Color.White, 
                            focusedTextColor = Color.White,
                            focusedBorderColor = primaryColor,
                            focusedLabelColor = primaryColor,
                            cursorColor = primaryColor
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Divider(color = Color.DarkGray)

                    // Proxy IP Mode
                    SectionTitle(stringResource(R.string.bpb_proxy_ip_title))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { proxyIPMode = "proxyip" }) {
                            RadioButton(selected = proxyIPMode == "proxyip", onClick = { proxyIPMode = "proxyip" }, colors = RadioButtonDefaults.colors(selectedColor = primaryColor, unselectedColor = Color.Gray))
                            Text("Proxy IP", color = Color.White, fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { proxyIPMode = "prefix" }) {
                            RadioButton(selected = proxyIPMode == "prefix", onClick = { proxyIPMode = "prefix" }, colors = RadioButtonDefaults.colors(selectedColor = primaryColor, unselectedColor = Color.Gray))
                            Text("NAT64", color = Color.White, fontSize = 14.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (proxyIPMode == "proxyip") {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedProxyIp,
                                onValueChange = { selectedProxyIp = it },
                                label = { Text(stringResource(R.string.bpb_proxy_ips_domains)) },
                                trailingIcon = {
                                    IconButton(onClick = { proxyIpExpanded = true }) {
                                        Icon(if (proxyIpExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.bpb_select_ip))
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedTextColor = Color.White, 
                                    focusedTextColor = Color.White,
                                    focusedBorderColor = primaryColor,
                                    focusedLabelColor = primaryColor,
                                    cursorColor = primaryColor
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = proxyIpExpanded,
                                onDismissRequest = { proxyIpExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.9f)
                            ) {
                                proxyIpsList.forEach { (ip, name) ->
                                    DropdownMenuItem(
                                        text = { Column { Text(ip, fontWeight = FontWeight.Bold); Text(name, fontSize = 10.sp, color = Color.Gray) } },
                                        onClick = {
                                            selectedProxyIp = ip
                                            proxyIpExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedPrefix,
                                onValueChange = { selectedPrefix = it },
                                label = { Text(stringResource(R.string.bpb_nat64_prefixes)) },
                                trailingIcon = {
                                    IconButton(onClick = { prefixExpanded = true }) {
                                        Icon(if (prefixExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.bpb_select_prefix))
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedTextColor = Color.White, 
                                    focusedTextColor = Color.White,
                                    focusedBorderColor = primaryColor,
                                    focusedLabelColor = primaryColor,
                                    cursorColor = primaryColor
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = prefixExpanded,
                                onDismissRequest = { prefixExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.9f)
                            ) {
                                nat64Prefixes.forEach { (prefix, name) ->
                                    DropdownMenuItem(
                                        text = { Column { Text(prefix, fontWeight = FontWeight.Bold); Text(name, fontSize = 10.sp, color = Color.Gray) } },
                                        onClick = {
                                            selectedPrefix = prefix
                                            prefixExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Confirm Button
                Button(
                    onClick = {
                        val json = JSONObject()
                        json.put("allowLANConnection", allowLANConnection)
                        json.put("enableIPv6", enableIPv6)
                        json.put("VLConfigs", vlConfigs)
                        json.put("TRConfigs", trConfigs)
                        
                        // Worker v4.2.2 expects a single combined integer array "ports"
                        val portsArray = JSONArray()
                        selectedTlsPorts.forEach { portsArray.put(it.toInt()) }
                        selectedNonTlsPorts.forEach { portsArray.put(it.toInt()) }
                        json.put("ports", portsArray)

                        json.put("proxyIPMode", proxyIPMode)
                        if (proxyIPMode == "proxyip") {
                            val arr = JSONArray()
                            if (selectedProxyIp.isNotBlank()) arr.put(selectedProxyIp)
                            json.put("proxyIPs", arr)
                        } else {
                            val arr = JSONArray()
                            if (selectedPrefix.isNotBlank()) arr.put(selectedPrefix)
                            json.put("prefixes", arr)
                        }
                        
                        val cleanArr = JSONArray()
                        if (cleanIp.isNotBlank()) cleanArr.put(cleanIp)
                        json.put("cleanIPs", cleanArr)

                        onConfirm(json)
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 80.dp).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8), contentColor = Color(0xFF202124))
                ) {
                    Text(stringResource(R.string.bpb_save_and_get_node), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
}

@Composable
fun SectionTitle(title: String) {
    Text(title, color = Color(0xFF8AB4F8), fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortsGrid(ports: List<String>, selected: Set<String>, onSelectionChange: (String, Boolean) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ports.forEach { port ->
            val isSelected = selected.contains(port)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Color(0xFF8AB4F8).copy(alpha = 0.2f) else Color.DarkGray)
                    .clickable { onSelectionChange(port, !isSelected) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(port, color = if (isSelected) Color(0xFF8AB4F8) else Color.White, fontSize = 12.sp)
            }
        }
    }
}

val proxyIpsList = listOf(
    Pair("213.108.198.116", "🇩🇪 Germany Frankfurt am Main NKtelecom INC"),
    Pair("213.108.20.161", "🇩🇪 Germany Frankfurt am Main Aeza International LTD"),
    Pair("49.12.237.71", "🇩🇪 Germany Nuremberg Hetzner Online GmbH"),
    Pair("62.60.245.255", "🇳🇱 The Netherlands Amsterdam NetCrafters OU"),
    Pair("92.246.136.38", "🇩🇪 Germany Frankfurt am Main Aeza International LTD"),
    Pair("91.149.233.78", "🇩🇪 Germany Frankfurt am Main Baxet Group Inc."),
    Pair("62.60.216.169", "🇩🇪 Germany Frankfurt am Main NetCrafters OU"),
    Pair("167.71.45.93", "🇩🇪 Germany Frankfurt am Main DigitalOcean, LLC"),
    Pair("94.159.103.41", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("185.66.165.51", "🇩🇪 Germany Neu-Isenburg Perfecto"),
    Pair("91.107.255.196", "🇩🇪 Germany Frankfurt Am Main Hetzner Online AG"),
    Pair("93.123.84.194", "🇩🇪 Germany Frankfurt am Main Play2go International Limited"),
    Pair("94.159.97.247", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("91.107.148.154", "🇩🇪 Germany Frankfurt Am Main Hetzner Online AG"),
    Pair("79.132.138.87", "🇩🇪 Germany Frankfurt am Main Fornex Hosting S.L."),
    Pair("94.159.100.136", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("94.159.98.123", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("94.159.104.237", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("77.83.86.169", "🇩🇪 Germany Frankfurt am Main Hostkey B.V."),
    Pair("194.247.187.244", "🇩🇪 Germany Frankfurt am Main Hostkey B.V."),
    Pair("213.108.198.56", "🇩🇪 Germany Frankfurt am Main NKtelecom INC"),
    Pair("109.122.198.127", "🇩🇪 Germany Frankfurt am Main WAIcore Ltd"),
    Pair("91.107.251.113", "🇩🇪 Germany Frankfurt Am Main Hetzner Online AG"),
    Pair("5.182.87.234", "🇩🇪 Germany Frankfurt am Main Aeza International LTD"),
    Pair("88.198.82.155", "🇩🇪 Germany Falkenstein Hetzner Online GmbH"),
    Pair("92.42.96.240", "🇩🇪 Germany Frankfurt am Main Cloud Hosting Solutions, Limited."),
    Pair("116.202.132.205", "🇩🇪 Germany Falkenstein Hetzner Online GmbH"),
    Pair("82.117.245.170", "🇩🇪 Germany Frankfurt am Main OVH SAS"),
    Pair("91.186.219.82", "🇸🇪 Sweden Stockholm NetCrafters OU"),
    Pair("51.38.98.202", "🇩🇪 Germany Limburg an der Lahn OVH SAS"),
    Pair("94.159.111.170", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("91.107.158.77", "🇩🇪 Germany Frankfurt Am Main Hetzner Online AG"),
    Pair("146.103.114.134", "🇳🇱 Netherlands Amsterdam Servers Tech Fzco"),
    Pair("94.159.105.148", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("64.188.79.4", "🇩🇪 Germany Frankfurt am Main OC NETWORKS LIMITED"),
    Pair("87.120.166.14", "🇩🇪 Germany Frankfurt am Main Play2go International Limited"),
    Pair("62.60.216.204", "🇩🇪 Germany Frankfurt am Main NetCrafters OU"),
    Pair("64.188.68.11", "🇩🇪 Germany Frankfurt am Main Senko Digital LLC"),
    Pair("94.159.98.113", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("91.107.155.13", "🇩🇪 Germany Frankfurt Am Main Hetzner Online AG"),
    Pair("5.187.4.45", "🇩🇪 Germany Frankfurt am Main Fornex Hosting S.L"),
    Pair("116.203.252.143", "🇩🇪 Germany Falkenstein Hetzner Online GmbH"),
    Pair("91.199.118.151", "🇩🇪 Germany Frankfurt am Main Clouvider Limited"),
    Pair("91.107.250.153", "🇩🇪 Germany Frankfurt Am Main Hetzner Online AG"),
    Pair("94.159.101.36", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("194.180.188.241", "🇩🇪 Germany Frankfurt am Main Hostkey B.V."),
    Pair("31.172.73.59", "🇩🇪 Germany Frankfurt am Main Fornex Hosting S.L."),
    Pair("109.172.94.240", "🇳🇱 Netherlands Meppel Aeza International LTD"),
    Pair("89.58.13.3", "🇩🇪 Germany Nuremberg netcup GmbH"),
    Pair("163.5.187.118", "🇩🇪 Germany Limburg an der Lahn OVH SAS"),
    Pair("45.147.248.115", "🇩🇪 Germany Frankfurt Kamatera Inc"),
    Pair("152.53.142.246", "🇩🇪 Germany Nuremberg Location: DE"),
    Pair("92.118.8.206", "🇩🇪 Germany Frankfurt am Main First Server Limited"),
    Pair("45.135.165.245", "🇩🇪 Germany Frankfurt am Main Big Data Host LLC"),
    Pair("5.187.7.220", "🇩🇪 Germany Frankfurt am Main Fornex Hosting S.L"),
    Pair("57.129.47.52", "🇩🇪 Germany Frankfurt am Main OVH SAS"),
    Pair("109.120.158.149", "🇳🇱 Netherlands Dronten Digital City FZE"),
    Pair("94.159.101.254", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("134.209.136.197", "🇳🇱 Netherlands Amsterdam DigitalOcean, LLC"),
    Pair("45.95.235.210", "🇩🇪 Germany Frankfurt am Main Timeweb, LLP"),
    Pair("163.5.187.49", "🇩🇪 Germany Limburg an der Lahn OVH SAS"),
    Pair("193.124.92.58", "🇩🇪 Germany Frankfurt am Main Big Data Host LLC"),
    Pair("94.159.101.93", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("194.58.33.218", "🇩🇪 Germany Frankfurt am Main International Hosting Company Limited"),
    Pair("194.164.192.16", "🇩🇪 Germany Berlin IONOS SE"),
    Pair("194.99.20.245", "🇩🇪 Germany Frankfurt am Main Mvps LTD"),
    Pair("83.147.254.245", "🇸🇪 Sweden Stockholm NetCrafters OU"),
    Pair("152.53.143.195", "🇩🇪 Germany Nuremberg Location: DE"),
    Pair("159.69.92.30", "🇩🇪 Germany Nuremberg Hetzner Online GmbH"),
    Pair("79.137.205.184", "🇳🇱 Netherlands Amsterdam Aeza International LTD"),
    Pair("91.132.160.151", "🇩🇪 Germany Frankfurt am Main Senko Digital LLC"),
    Pair("62.60.229.255", "🇫🇮 Finland Helsinki Aeza International LTD"),
    Pair("162.19.247.245", "🇩🇪 Germany Limburg an der Lahn OVH SAS"),
    Pair("194.87.71.141", "🇩🇪 Germany Frankfurt am Main Global Connectivity Solutions LLP"),
    Pair("147.45.45.136", "🇳🇱 The Netherlands Amsterdam Neon Core Network LLC"),
    Pair("94.159.109.242", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("88.198.82.151", "🇩🇪 Germany Falkenstein Hetzner Online GmbH"),
    Pair("94.159.110.149", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("89.169.34.99", "🇸🇪 Sweden Stockholm xorek.cloud International LTD"),
    Pair("85.234.100.221", "🇩🇪 Germany Frankfurt am Main Global Connectivity Solutions LLP"),
    Pair("94.159.103.71", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("94.159.110.41", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("95.179.250.68", "🇩🇪 Germany Frankfurt am Main The Constant Company, LLC"),
    Pair("37.220.83.176", "🇩🇪 Germany Frankfurt am Main Timeweb, LLP"),
    Pair("89.58.40.177", "🇩🇪 Germany Nuremberg netcup GmbH"),
    Pair("94.159.101.193", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("188.245.161.141", "🇩🇪 Germany Falkenstein Hetzner Online GmbH"),
    Pair("89.169.12.101", "🇩🇪 Germany Frankfurt am Main SERV.HOST GROUP LTD"),
    Pair("109.122.198.64", "🇩🇪 Germany Frankfurt am Main WAIcore Ltd"),
    Pair("172.86.95.236", "🇩🇪 Germany Frankfurt am Main FranTech Solutions"),
    Pair("91.149.223.242", "🇩🇪 Germany Frankfurt am Main Baxet Group Inc."),
    Pair("194.87.71.42", "🇩🇪 Germany Frankfurt am Main Global Connectivity Solutions LLP"),
    Pair("62.60.217.230", "🇩🇪 Germany Frankfurt am Main NetCrafters OU"),
    Pair("195.58.38.95", "🇩🇪 Germany Frankfurt am Main International Hosting Company Limited"),
    Pair("194.180.188.184", "🇩🇪 Germany Frankfurt am Main Hostkey B.V."),
    Pair("217.154.94.41", "🇩🇪 Germany Berlin IONOS SE"),
    Pair("103.97.88.133", "🇳🇱 Netherlands Amsterdam Melbikomas UAB"),
    Pair("194.48.250.224", "🇩🇪 Germany Frankfurt am Main OC NETWORKS LIMITED"),
    Pair("195.133.44.21", "🇩🇪 Germany Frankfurt am Main Big Data Host LLC"),
    Pair("145.223.100.111", "🇩🇪 Germany Frankfurt am Main Hostinger International Limited"),
    Pair("88.198.82.156", "🇩🇪 Germany Falkenstein Hetzner Online GmbH"),
    Pair("185.230.143.55", "🇸🇪 Sweden Stockholm xorek.cloud International LTD"),
    Pair("91.132.160.141", "🇩🇪 Germany Frankfurt am Main Senko Digital LLC"),
    Pair("94.159.111.159", "🇩🇪 Germany Frankfurt am Main H2nexus LTD"),
    Pair("152.53.12.245", "🇩🇪 Germany Nuremberg netcup GmbH"),
    Pair("185.189.58.222", "🇩🇪 Germany Frankfurt am Main Massivegrid LTD"),
    Pair("178.20.209.70", "🇩🇪 Germany Frankfurt am Main Aeza International LTD"),
    Pair("87.251.88.57", "🇩🇪 Germany Frankfurt am Main Cloud Hosting Solutions, Limited."),
    Pair("88.198.82.150", "🇩🇪 Germany Falkenstein Hetzner Online GmbH"),
    Pair("91.107.171.251", "🇩🇪 Germany Frankfurt Am Main Hetzner Online AG"),
    Pair("135.125.191.9", "🇩🇪 Germany Limburg an der Lahn OVH SAS"),
    Pair("89.22.233.52", "🇸🇪 Sweden Stockholm xorek.cloud International LTD"),
    Pair("178.250.187.110", "🇩🇪 Germany Frankfurt am Main International Hosting Company Limited"),
    Pair("146.103.113.158", "🇳🇱 Netherlands Amsterdam Servers Tech Fzco")
)

val nat64Prefixes = listOf(
    Pair("[2a02:898:146:64::]", "Netherland"),
    Pair("[2602:fc59:b0:64::]", "USA"),
    Pair("[2602:fc59:11:64::]", "USA")
)
