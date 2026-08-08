package com.mlmvpn.scanner.ui.emergency

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.emergency.EmergencyColors

/**
 * Compact card for one configured Google relay. The auth key is managed once in the setup
 * wizard (shared across all relays), so it isn't shown here — only the Deployment ID (masked)
 * plus edit/remove actions, to keep the list uncluttered.
 */
@Composable
fun DynamicRelayCard(
    deploymentId: String,
    isActive: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    val borderColor = if (isActive) EmergencyColors.GoogleBlue else EmergencyColors.GoogleSurface2
    val containerColor = if (isActive) EmergencyColors.GoogleBlue.copy(alpha = 0.05f) else EmergencyColors.GoogleSurface

    val shortId = if (deploymentId.length > 18) deploymentId.take(10) + "…" + deploymentId.takeLast(6) else deploymentId

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "رله گوگل",
                    color = if (isActive) EmergencyColors.GoogleBlue else EmergencyColors.GoogleText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (shortId.isBlank()) "بدون شناسه" else shortId,
                    color = EmergencyColors.GoogleMuted,
                    fontSize = 12.sp
                )
                if (isActive) {
                    Spacer(Modifier.height(2.dp))
                    Text("● فعال", color = EmergencyColors.GoogleGreen, fontSize = 11.sp)
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "ویرایش", tint = EmergencyColors.GoogleBlue)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "حذف", tint = EmergencyColors.GoogleMuted)
            }
        }
    }
}
