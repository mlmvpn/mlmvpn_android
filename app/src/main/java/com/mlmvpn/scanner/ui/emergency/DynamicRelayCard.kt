package com.mlmvpn.scanner.ui.emergency

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.emergency.EmergencyColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicRelayCard(
    deploymentId: String,
    authKey: String,
    isActive: Boolean,
    onDeploymentIdChange: (String) -> Unit,
    onAuthKeyChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    val borderColor = if (isActive) EmergencyColors.GoogleBlue else EmergencyColors.GoogleSurface2
    val containerColor = if (isActive) EmergencyColors.GoogleBlue.copy(alpha = 0.05f) else EmergencyColors.GoogleSurface

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "رله گوگل (Google Relay)",
                    color = if (isActive) EmergencyColors.GoogleBlue else EmergencyColors.GoogleText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = EmergencyColors.GoogleMuted)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = deploymentId,
                onValueChange = onDeploymentIdChange,
                label = { Text("Deployment ID", color = EmergencyColors.GoogleMuted) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = EmergencyColors.GoogleBlue,
                    unfocusedBorderColor = EmergencyColors.GoogleSurface2,
                    focusedTextColor = EmergencyColors.GoogleText,
                    unfocusedTextColor = EmergencyColors.GoogleText
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = authKey,
                onValueChange = onAuthKeyChange,
                label = { Text("Auth Key (رمز عبور)", color = EmergencyColors.GoogleMuted) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = EmergencyColors.GoogleBlue,
                    unfocusedBorderColor = EmergencyColors.GoogleSurface2,
                    focusedTextColor = EmergencyColors.GoogleText,
                    unfocusedTextColor = EmergencyColors.GoogleText
                )
            )
        }
    }
}
