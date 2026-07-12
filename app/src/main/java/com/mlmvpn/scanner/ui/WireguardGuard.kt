package com.mlmvpn.scanner.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.ui.theme.BgDark
import com.mlmvpn.scanner.ui.theme.BorderDark
import com.mlmvpn.scanner.ui.theme.Primary
import com.mlmvpn.scanner.ui.theme.SurfaceDark
import com.mlmvpn.scanner.ui.theme.TextMuted
import com.mlmvpn.scanner.ui.theme.TextPrimary

/**
 * The Xray-based engines (node connect, scanner, emergency) and the WireGuard trial
 * both drive a shared native Go runtime (libgojni). Starting an Xray engine while the
 * WireGuard trial tunnel is up crashes the process (SIGABRT). So any Xray action must
 * first make sure the WireGuard trial is stopped — with the user's confirmation.
 */
fun isWireguardTrialActive(): Boolean =
    MyVpnService.isRunning && MyVpnService.connectedNodeId == "game_uae_trial"

/** Tears down the active VPN (WireGuard trial) before an Xray engine takes over. */
fun stopActiveVpn(context: Context) {
    val stop = Intent(context, MyVpnService::class.java).apply { action = "STOP" }
    context.startService(stop)
    MyVpnService.isRunning = false
    MyVpnService.connectedNodeId = null
}

/**
 * Elegant confirmation modal shown when the user tries to start an Xray action while
 * the WireGuard trial is active.
 */
@Composable
fun WireguardConflictDialog(
    message: String = "اتصال وایرگارد (تست بازی) هم‌اکنون فعال است. برای ادامه باید ابتدا وایرگارد غیرفعال شود.\n\nمی‌خواهید وایرگارد غیرفعال شود؟",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(20.dp))
                .border(1.dp, BorderDark, RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFFFFA000).copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFA000), modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("وایرگارد فعال است", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(22.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.Transparent, RoundedCornerShape(12.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("انصراف", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Primary, RoundedCornerShape(12.dp))
                        .clickable { onConfirm() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("بله، غیرفعال کن", color = BgDark, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * Returns a Persian label for whichever native engine is currently running (so we can warn the
 * user before a game-boost starts a DIFFERENT engine family). Returns null when nothing is running.
 *
 * WHY this matters: WireGuard (AmneziaWG, libam-go) and Xray (libgojni) each bring up their own
 * gomobile Go runtime, and two Go runtimes cannot coexist in one process — starting the second
 * crashes with SIGSEGV/SIGABRT during InitCoreEnv. Merely stopping the first tunnel does NOT unload
 * its .so, so the only reliable way to switch families is a fresh process (see stopAllEnginesAndRestart).
 */
fun activeEngineLabelFa(): String? = when {
    com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.isRunningFlow.value -> "موتور ضد فیلتر SNI"
    MyVpnService.isRunning && MyVpnService.connectedNodeId == "game_uae_trial" -> "وایرگارد (تست بازی)"
    MyVpnService.isRunning -> "موتور VPN فعلی"
    else -> null
}

/** Restarts the whole app process — the only reliable way to unload a gomobile Go runtime. */
fun restartAppProcess(context: Context) {
    val ctx = context.applicationContext
    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
    launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    if (launch != null) ctx.startActivity(launch)
    android.os.Process.killProcess(android.os.Process.myPid())
    Runtime.getRuntime().exit(0)
}

/**
 * Stops every running engine and hard-restarts the app so the native Go runtime is fully unloaded.
 * Used when the user confirms disabling a conflicting engine before a game boost that would start a
 * different engine family.
 */
fun stopAllEnginesAndRestart(context: Context) {
    try {
        val stop = Intent(context, MyVpnService::class.java).apply { action = "STOP" }
        context.startService(stop)
    } catch (_: Exception) {}
    try { com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.stop() } catch (_: Exception) {}
    MyVpnService.isRunning = false
    MyVpnService.connectedNodeId = null
    // Let the STOP intent land, then relaunch into a clean process.
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        restartAppProcess(context)
    }, 450)
}

/**
 * Elegant, general engine-conflict modal (names the specific engine). Same look as the WireGuard
 * one but reused for any engine family before a game boost.
 */
@Composable
fun EngineConflictDialog(
    engineName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(20.dp))
                .border(1.dp, BorderDark, RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFFFFA000).copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFA000), modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("$engineName روشن است", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "برای شروعِ تست، ابتدا «$engineName» باید غیرفعال شود. با تأیید، این موتور خاموش می‌شود و برنامه یک‌بار تازه‌سازی می‌شود؛ سپس روی «شروع» بزنید.",
                color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp
            )
            Spacer(Modifier.height(22.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.Transparent, RoundedCornerShape(12.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("انصراف", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Primary, RoundedCornerShape(12.dp))
                        .clickable { onConfirm() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("تأیید و تازه‌سازی", color = BgDark, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
