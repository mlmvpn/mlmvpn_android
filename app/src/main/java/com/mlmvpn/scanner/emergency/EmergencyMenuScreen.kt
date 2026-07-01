package com.mlmvpn.scanner.emergency

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.mlmvpn.scanner.R

@Composable
fun EmergencyMenuScreen(onNavigateToVercel: () -> Unit, onNavigateToGst: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EmergencyColors.GoogleBg)
    ) {
        // TopBar
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = EmergencyColors.GoogleMuted)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.emergency_select_route), color = EmergencyColors.GoogleText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                EmergencyOptionCard(
                    title = stringResource(R.string.emergency_route_1),
                    subtitle = stringResource(R.string.emergency_route_1_sub),
                    isActive = true,
                    onClick = onNavigateToVercel
                )
            }
            item {
                EmergencyOptionCard(
                    title = stringResource(R.string.emergency_route_2),
                    subtitle = "زیرساخت قدرتمند Google Apps Script",
                    isActive = true,
                    onClick = onNavigateToGst
                )
            }
            items(3) { index ->
                val titles = listOf(
                    stringResource(R.string.emergency_route_3),
                    stringResource(R.string.emergency_route_4),
                    stringResource(R.string.emergency_route_5)
                )
                EmergencyOptionCard(
                    title = titles[index],
                    subtitle = stringResource(R.string.emergency_launching_soon),
                    isActive = false,
                    onClick = { /* TODO: Show SnackBar or empty screen */ }
                )
            }
        }
    }
}

@Composable
fun EmergencyOptionCard(title: String, subtitle: String, isActive: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = isActive, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = EmergencyColors.GoogleSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (isActive) EmergencyColors.GoogleRed.copy(alpha = 0.5f) else EmergencyColors.GoogleSurface2)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isActive) EmergencyColors.GoogleRed else EmergencyColors.GoogleSurface2)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, color = if (isActive) EmergencyColors.GoogleText else EmergencyColors.GoogleMuted, fontWeight = FontWeight.Bold)
                Text(subtitle, color = EmergencyColors.GoogleMuted, fontSize = 12.sp)
            }
        }
    }
}
