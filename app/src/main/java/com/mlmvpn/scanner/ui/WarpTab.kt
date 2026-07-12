package com.mlmvpn.scanner.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService

// Shared palette (kept from the previous WARP screen so the tab matches the app's Google-dark theme).
object AppColors {
    val BgDark = Color(0xFF171717)
    val SurfaceDark = Color(0xFF222222)
    val SurfaceVariant = Color(0xFF2A2A2A)
    val Primary = Color(0xFF81C995)
    val PrimaryBlue = Color(0xFF8AB4F8)
    val TextPrimary = Color(0xFFE8EAED)
    val TextMuted = Color(0xFF9AA0A6)
    val BorderDark = Color(0xFF333333)
    val Error = Color(0xFFF28B82)
}

/**
 * WARP tab: one button. Tapping "connect" starts the hidden-WARP engine, which automatically tries
 * several transports on the user's own network and keeps whichever works. The button reflects the
 * live connection phase (idle / testing / connected) so the user understands the wait.
 */
@Composable
fun WarpTab() {
    val context = LocalContext.current
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res -> if (res.resultCode == Activity.RESULT_OK) startWarp(context) }

    fun onConnectClick() {
        when (phase) {
            MyVpnService.Phase.CONNECTED, MyVpnService.Phase.CONNECTING -> stopWarp(context)
            else -> {
                val prep = VpnService.prepare(context)
                if (prep != null) vpnPrepareLauncher.launch(prep) else startWarp(context)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.BgDark)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            StatusOrb(phase)

            Spacer(Modifier.height(28.dp))

            Text(
                text = "WARP مخفی",
                color = AppColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusSubtitle(phase),
                color = statusColor(phase),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(40.dp))

            ConnectButton(phase = phase, onClick = { onConnectClick() })

            Spacer(Modifier.height(20.dp))

            Text(
                text = "به‌صورت خودکار بهترین مسیر را روی شبکه‌ی شما پیدا می‌کند. اولین اتصال ممکن است تا یک دقیقه طول بکشد.",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun StatusOrb(phase: MyVpnService.Phase) {
    val color = statusColor(phase)
    val connecting = phase == MyVpnService.Phase.CONNECTING

    // Spin the ring while connecting/testing.
    val transition = rememberInfiniteTransition(label = "orb")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )

    Box(
        modifier = Modifier.size(140.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .then(if (connecting) Modifier.rotate(angle) else Modifier)
                .border(
                    BorderStroke(3.dp, color.copy(alpha = if (connecting) 0.9f else 0.35f)),
                    RoundedCornerShape(70.dp)
                )
        )
        Box(
            modifier = Modifier
                .size(104.dp)
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(52.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (phase) {
                    MyVpnService.Phase.CONNECTED -> Icons.Rounded.ShieldMoon
                    MyVpnService.Phase.CONNECTING -> Icons.Rounded.Sync
                    MyVpnService.Phase.FAILED -> Icons.Rounded.ErrorOutline
                    else -> Icons.Rounded.Shield
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .size(48.dp)
                    .then(if (connecting) Modifier.rotate(-angle) else Modifier)
            )
        }
    }
}

@Composable
private fun ConnectButton(phase: MyVpnService.Phase, onClick: () -> Unit) {
    val connecting = phase == MyVpnService.Phase.CONNECTING
    val connected = phase == MyVpnService.Phase.CONNECTED
    val (bg, fg) = when {
        connected -> AppColors.Error.copy(alpha = 0.18f) to AppColors.Error
        connecting -> AppColors.SurfaceVariant to AppColors.PrimaryBlue
        else -> AppColors.Primary to AppColors.BgDark
    }

    Button(
        onClick = onClick,
        enabled = !connecting, // while testing, the button is a status indicator, not tappable
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContainerColor = bg,
            disabledContentColor = fg
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        when {
            connecting -> {
                CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("در حال تست و اتصال...", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            connected -> {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("قطع اتصال", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            else -> {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("اتصال", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun statusColor(phase: MyVpnService.Phase): Color = when (phase) {
    MyVpnService.Phase.CONNECTED -> AppColors.Primary
    MyVpnService.Phase.CONNECTING -> AppColors.PrimaryBlue
    MyVpnService.Phase.FAILED -> AppColors.Error
    else -> AppColors.TextMuted
}

private fun statusSubtitle(phase: MyVpnService.Phase): String = when (phase) {
    MyVpnService.Phase.CONNECTED -> "متصل � از مسیر امن عبور می‌کنید"
    MyVpnService.Phase.CONNECTING -> "در حال بررسی مسیرهای مختلف روی شبکه‌ی شما..."
    MyVpnService.Phase.FAILED -> "روی این شبکه مسیری پیدا نشد. بعداً یا با شبکه‌ی دیگر دوباره امتحان کنید."
    else -> "برای اتصال روی دکمه‌ی زیر بزنید"
}

private fun startWarp(context: Context) {
    val intent = Intent(context, MyVpnService::class.java).apply {
        putExtra("NODE_URI", "{\"type\":\"masque\"}")
        putExtra("NODE_ID", "warp_auto")
    }
    context.startService(intent)
}

private fun stopWarp(context: Context) {
    val intent = Intent(context, MyVpnService::class.java).apply { action = "STOP" }
    context.startService(intent)
}
