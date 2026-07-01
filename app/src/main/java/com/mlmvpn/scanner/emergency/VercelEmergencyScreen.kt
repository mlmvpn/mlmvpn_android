package com.mlmvpn.scanner.emergency

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.mlmvpn.scanner.R

@Composable
fun VercelEmergencyScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val stateManager = remember { EmergencyStateManager.getInstance(context) }
    val isEnabled by stateManager.isVercelEnabled.collectAsState()

    val containerColor by animateColorAsState(
        targetValue = if (isEnabled) EmergencyColors.GoogleRed.copy(alpha = 0.1f) else EmergencyColors.GoogleSurface
    )
    
    val buttonColor by animateColorAsState(
        targetValue = if (isEnabled) EmergencyColors.GoogleRed else EmergencyColors.GoogleSurface2
    )

    val iconColor by animateColorAsState(
        targetValue = if (isEnabled) EmergencyColors.GoogleBg else EmergencyColors.GoogleMuted
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EmergencyColors.GoogleBg)
            .padding(top = com.mlmvpn.scanner.ui.LocalSystemTopPadding.current, bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current)
            .padding(16.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = EmergencyColors.GoogleMuted)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.emergency_vercel_title),
                color = EmergencyColors.GoogleText,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp
            )
        }
        Text(
            text = stringResource(R.string.emergency_vercel_desc),
            color = EmergencyColors.GoogleMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // Center Action Area
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .shadow(
                        elevation = if (isEnabled) 30.dp else 10.dp,
                        shape = CircleShape,
                        spotColor = if (isEnabled) EmergencyColors.GoogleRed else Color.Black
                    )
                    .clip(CircleShape)
                    .background(containerColor)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        stateManager.setVercelEnabled(!isEnabled)
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(buttonColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PowerSettingsNew,
                        contentDescription = "Power",
                        tint = iconColor,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = if (isEnabled) stringResource(R.string.emergency_tunnel_ready) else stringResource(R.string.emergency_tap_to_enable),
            color = if (isEnabled) EmergencyColors.GoogleRed else EmergencyColors.GoogleMuted,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.weight(1.5f))
    }
}
